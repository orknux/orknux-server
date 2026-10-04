package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.Hangup
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/** One server-sent frame of an answer being written: its event name and what it carries. */
class ChatFrame(val event: String, val payload: Any)

/**
 * One answer being written, and every frame it has produced so far.
 *
 * The answer is composed on a thread of its own and does not belong to any
 * request: the request that asked for it only relays what it produces, and so
 * may any other. That is issue #201. A text chat left while the agent was
 * thinking went on being answered (#335) and the answer was written to the
 * history - but a person who came back *while it was still being written* was
 * shown the question and nothing else, because the frames went down the one
 * connection that had been closed and the page that came back had no way to
 * hear about an answer still being composed. So the frames are kept here, from
 * the first, and whoever opens [ChatStreamAPI.follow] reads them from the start
 * and then live, exactly as the request that asked would have.
 *
 * Kept whole rather than trimmed to a window, because a reader who joins late
 * has to be able to draw the answer from its beginning; what is held is one
 * answer's worth of frames, which the history is about to hold anyway, and it
 * goes when the answer ends.
 */
class ChatGeneration(
    /** How this answer is stopped on purpose: Stop, or a voice reader leaving. */
    val hangup: Hangup = Hangup(),
    /**
     * Started by the server rather than by somebody sending - [ChatWake]. A page
     * open on the chat did not ask for it and so has no stream reading it; it
     * waits for one of these instead ([ChatGenerations.awaitWoken]).
     */
    val woken: Boolean = false,
) {
    private val lock = Object()
    private val frames = ArrayList<ChatFrame>()
    private var over = false

    /** Adds a frame for every reader, present and future. Ignored once [finish]ed. */
    fun post(event: String, payload: Any) {
        synchronized(lock) {
            if (over) return
            frames.add(ChatFrame(event, payload))
            lock.notifyAll()
        }
    }

    /** Whether the answer has ended, so there is nothing left to follow. */
    fun isOver(): Boolean = synchronized(lock) { over }

    /** Says there will be no more frames, however the answer ended. */
    fun finish() {
        synchronized(lock) {
            over = true
            lock.notifyAll()
        }
    }

    /**
     * The frame at [at], waiting for it to be written if it has not been yet, or
     * null once the answer is over and there is nothing at [at] to wait for.
     *
     * By position rather than by a queue per reader, so a reader is nothing but
     * a number: one that arrives late starts at nought and catches up, and none
     * of them can take a frame away from another.
     */
    @Throws(InterruptedException::class)
    fun frameAt(at: Int): ChatFrame? {
        synchronized(lock) {
            while (frames.size <= at && !over) lock.wait()
            return frames.getOrNull(at)
        }
    }
}

/**
 * The answers being written right now, so one can be stopped - or followed -
 * from outside the request that is writing it.
 *
 * A text chat that is left goes on being answered - that is the whole of issue
 * #335, and it is why a lost reader no longer stops the model there. So stopping
 * on purpose needs a door of its own: the Stop button closes the stream *and*
 * calls the interrupt endpoint, and this is what that endpoint reaches through
 * to the [Hangup] the answering thread is holding. Without it, Stop in an
 * asynchronous chat would close the browser's connection and leave the model
 * writing - which is exactly the waste #299 set out to end.
 *
 * And coming back needs one too (#201): a page opened on a chat whose answer is
 * still being written asks for it here, and reads it from its first frame.
 *
 * Keyed by chat rather than by turn: a person presses Stop on a chat, not on a
 * turn id they have never seen, and the rare two-in-flight case is stopped
 * together, which is what somebody pressing Stop meant. A generation removes
 * itself when its turn ends, so what is held here is only ever live ones.
 */
@Component
class ChatGenerations {

    private val inFlight = ConcurrentHashMap<Long, MutableList<ChatGeneration>>()

    /** Told of every [register], for pages waiting on a woken turn. */
    private val arrivals = Object()

    /** Notes that a turn on this chat is being answered, until [release]. */
    fun register(chatId: Long, generation: ChatGeneration) {
        inFlight.compute(chatId) { _, held ->
            (held ?: java.util.concurrent.CopyOnWriteArrayList()).apply { add(generation) }
        }
        synchronized(arrivals) { arrivals.notifyAll() }
    }

    /**
     * The next turn the server starts on this chat by itself - a watcher firing,
     * an agent it asked answering - waiting until there is one, or null once
     * [given] says the reader waiting for it has gone.
     *
     * Only a woken turn: one somebody sent is already being read by the page that
     * sent it, and a second reader on the same page would draw it twice. And not
     * one that has already ended, which the history now holds.
     */
    @Throws(InterruptedException::class)
    fun awaitWoken(chatId: Long, given: () -> Boolean): ChatGeneration? {
        synchronized(arrivals) {
            while (!given()) {
                inFlight[chatId]?.lastOrNull { it.woken && !it.isOver() }?.let { return it }
                // Bounded, so a reader that left is noticed without a register to wake this.
                arrivals.wait(1_000)
            }
        }
        return null
    }

    /**
     * Forgets a turn that has ended, however it ended, and tells anybody
     * following it that there is nothing more to come. Here rather than left to
     * each door, because a follower waits for that and a door that forgot it
     * would hold a page open on an answer that is long over.
     */
    fun release(chatId: Long, generation: ChatGeneration) {
        generation.finish()
        inFlight.computeIfPresent(chatId) { _, held ->
            held.remove(generation)
            held.ifEmpty { null }
        }
    }

    /** Whether a turn on this chat is being answered right now. */
    fun answering(chatId: Long): Boolean = inFlight[chatId]?.isNotEmpty() == true

    /** The newest answer being written on this chat, or null where none is. */
    fun current(chatId: Long): ChatGeneration? = inFlight[chatId]?.lastOrNull()

    /**
     * Stops whatever is being answered on this chat, if anything is.
     *
     * Only hangs up: the answering thread reads that on its next piece and puts
     * itself back the way an interrupted turn is, the same path a voice chat's
     * lost reader already takes. Nothing here waits for that to happen - the
     * caller is a button press, not the turn.
     */
    fun interrupt(chatId: Long) {
        inFlight[chatId]?.forEach { it.hangup.hangUp() }
    }
}
