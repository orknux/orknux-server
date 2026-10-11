package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.server.chat.RoundWatch

/**
 * What a turn writes down while the model is still having it.
 *
 * The chat's [RoundWatch] relays a round to somebody on the other end of an open
 * connection. A task, and a workflow's agent node, have nobody in particular:
 * either may be running in a Temporal worker in another process, the page
 * showing it may be closed, and whoever opens it tomorrow must get the same
 * account as whoever is watching now. So this one *writes* rather than relays,
 * into the session the turn is already being recorded in, and the live view
 * falls out of that for free - `SessionTail` follows the table, so a line
 * written here reaches every browser watching and survives the reconnect
 * `TaskStreamAPI` forces every four minutes.
 *
 * It began as the task loop's and was never anything but a session's: it takes
 * a session, an agent's name and the recorder, and knows nothing about what is
 * running. Which is why an agent node now hands one over too - its session had
 * every other kind of line in it and nothing at all for the minute the model
 * spent reasoning.
 *
 * That is the whole reason the reasoning is not simply handed on and forgotten.
 * A delta relayed and not written down would vanish at the next stint, on a page
 * whose entire promise is that it reads the same afterwards.
 *
 * ## One line per round, not one per frame
 *
 * A reasoning model emits its thinking a few characters at a time, hundreds of
 * frames for one turn. A row each would be a transcript in which the reasoning
 * is spread over three hundred lines nobody can read, and three hundred inserts
 * for one turn. So a round's thinking is one line that grows: opened on the
 * first frame, so it is on the page within a moment of the model starting, and
 * replaced on a clock afterwards - [FLUSH_EVERY_MILLIS] rather than on every
 * frame, because what a reader can see is a block that moves rather than every
 * character of it arriving separately.
 *
 * ## When a round's thinking is over
 *
 * Three things end it, and all three are the model doing something other than
 * thinking: asking for a tool, starting to answer, and the turn finishing.
 * [called] closes the line because a model that has decided what to look up has
 * stopped reasoning about whether to; [answering] closes it because a model
 * that has begun writing has equally stopped; [settle] is called by the loop
 * when the turn ends however it ended, including by an exception. Until one of
 * those the line carries no duration, which is what a page reads as "still
 * thinking".
 *
 * [answering] is the one that was missing, and the turn it matters on is the
 * ordinary one - a round that answers rather than looking something up. Left to
 * [settle] alone, a task asked for a thousand words thought for ten seconds and
 * then wrote for two minutes with the reasoning still open the whole time: the
 * page counted up, said *Thinking*, and held the reasoning at whatever the last
 * flush had written, which is a sentence that stops in the middle. That is
 * issue #290.
 *
 * ## What the duration means
 *
 * From the request going out to the last frame of reasoning, which is the wait
 * somebody actually sat through. Not between the first and last frames: a model
 * that emits its whole reasoning in one frame has those at the same instant, so
 * that measurement reports nothing on most real providers. The clock is
 * restarted as each tool returns, because the next round's request goes out the
 * moment the last result is threaded in - so a round is timed from its own
 * beginning rather than from the turn's.
 */
class SessionThinking(
    private val session: Long,
    private val agent: String,
    private val sessions: LlmSessionRecorder,
    private val clock: () -> Long = System::nanoTime,
) : RoundWatch {

    /** The round's reasoning so far. Emptied when the line is settled. */
    private val thought = StringBuilder()

    /** The line it is being written on, or null when no round is thinking. */
    private var line: Long? = null

    /** When this round's request went out, as nearly as this can tell. */
    private var began: Long = clock()

    /** When the last frame of reasoning arrived. */
    private var lastFrame: Long = began

    /** When the line was last written, so it is not written on every frame. */
    private var flushed: Long = 0

    @Synchronized
    override fun thinking(text: String) {
        // Nothing is opened on whitespace: an empty block of reasoning drawn
        // under a turn asserts there was thinking to see.
        if (thought.isEmpty() && text.isBlank()) return

        thought.append(text)
        lastFrame = clock()

        val now = System.currentTimeMillis()
        val open = line
        if (open == null) {
            // The prompt has been read: the model is thinking now, and that is a line of its own.
            sessions.readingDone(session)
            line = sessions.thinking(session, agent, thought.toString())
            flushed = now
            return
        }
        if (now - flushed < FLUSH_EVERY_MILLIS) return
        flushed = now
        sessions.thinkingGrew(open, thought.toString())
    }

    /**
     * A tool was asked for, so this round has stopped thinking.
     *
     * The call itself is already being recorded by [LlmSessionRecorder]; what
     * this adds is closing the reasoning that led to it, so the two read in the
     * order they happened rather than as a block of thinking that never ended
     * sitting above a lookup.
     */
    override fun called(at: Int, tool: String, arguments: String) = settle()

    /** How far the model has got writing, for the session page to show while it does. */
    override fun writing(tool: String?, chars: Int) = sessions.writing(session, agent, tool, chars)

    /** How far the server has read the prompt, for the session page to show before anything is written. */
    override fun reading(total: Long, cached: Long, processed: Long) =
        sessions.reading(session, agent, total, cached, processed)

    /**
     * The model began writing its answer, so this round has stopped thinking.
     *
     * The same rule [called] follows, for the other way a round's reasoning
     * ends - and it is the ordinary way, because most rounds answer rather than
     * look something up. It was missing, and what that cost is issue #290: a
     * task asked to write at length thought for a few seconds and then wrote
     * for two minutes, and for the whole of those two minutes the page said
     * *Thinking*, counted up, and showed the reasoning cut off wherever the
     * last flush had fallen. Nothing closed the line until [settle] at the end
     * of the turn, so the only way to see the rest was to reload after it.
     *
     * Idempotent: this arrives on every piece of the answer, and only the first
     * has a line to close.
     */
    @Synchronized
    override fun answering() {
        if (line == null) return
        settle()
    }

    /**
     * A tool answered, which is the last thing to happen before the next round
     * is asked for. Restarts the clock, so the next round's thinking is timed
     * from its own request rather than from the turn's beginning.
     */
    @Synchronized
    override fun returned(at: Int, result: String, failed: Boolean) {
        began = clock()
    }

    /**
     * Closes whatever line is open, with how long it took.
     *
     * Safe to call when nothing is open and safe to call twice, because the
     * loop calls it in a `finally` and a round that asked for a tool has
     * already closed its own.
     */
    @Synchronized
    fun settle() {
        // Whatever was being written has landed, or the turn is over.
        sessions.quiet(session)
        val open = line
        val took = (lastFrame - began) / NANOS
        began = clock()
        if (open == null) return
        sessions.thoughtFor(open, thought.toString(), took)
        line = null
        thought.setLength(0)
    }

    private companion object {
        /**
         * How often the growing line is written.
         *
         * Often enough that somebody watching sees a block that is moving, and
         * rarely enough that a turn is a handful of updates rather than one per
         * frame. The count of how long it has been thinking ticks in the
         * browser on its own clock, so this is about the words rather than
         * about the page looking alive.
         */
        const val FLUSH_EVERY_MILLIS = 800L

        const val NANOS = 1_000_000L
    }
}
