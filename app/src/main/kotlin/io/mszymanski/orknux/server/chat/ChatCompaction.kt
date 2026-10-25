package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.server.workspace.Workspace
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.memory.ChatMemoryRepository
import org.springframework.stereotype.Service

/**
 * What one compaction came to, for the chat it happened in to say so.
 *
 * A compaction throws messages away. Doing that silently is the behaviour people
 * remember as "it lost my conversation", so the numbers travel out of here and
 * onto the screen: how many turns were replaced, how many were kept word for
 * word, and roughly what the thread had grown to.
 */
data class Compacted(
    val replaced: Int,
    val kept: Int,
    val tokens: Int,
    /**
     * The summary that replaced the older turns, so a chat can show what was
     * kept of them rather than only how many went. Issue #332.
     */
    val summary: String,
)

/**
 * A turn shrunk in flight: what to send now, the summary that stands in for its
 * middle where there was one, how many steps that replaced, and how many long
 * steps were cut to fit.
 */
data class Shrunk(val turns: List<ChatTurn>, val summary: String?, val replaced: Int, val trimmed: Int)

/**
 * Keeps a long chat inside the window the model will accept.
 *
 * A conversation that outgrows its model fails on the next turn, and it fails
 * with a number: *this model's maximum context length is 128000 tokens*. That is
 * true and useless — the person reading it wanted to keep talking, and the only
 * thing they can do about it is start again and lose everything. Issue #286.
 *
 * So above a threshold the older part of the thread is replaced by one summary
 * of itself and the chat carries on. What is kept is the recent end, verbatim:
 * the last few turns are what the next answer is actually about, and summarising
 * those would be summarising the question being asked.
 *
 * **The summary replaces the messages, and they are gone.** That is the point of
 * it — a copy kept beside them would be the same conversation with something
 * added, which is the opposite of compacting. It is why this is off until
 * somebody turns it on, and why the threshold is theirs to set rather than
 * guessed from the model.
 *
 * Tokens are estimated rather than counted. The exact number depends on the
 * model's own tokeniser, which this server does not have and should not carry
 * one per provider of; four characters to a token is the usual rule of thumb and
 * is close enough for a threshold somebody chose approximately anyway. It errs
 * high — see [tokensIn] — because compacting a little early costs one summary
 * and compacting a little late costs the turn.
 */
@Service
class ChatCompaction(
    private val history: ChatMemoryRepository,
    private val models: ModelChatClient,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Compacts this conversation if the workspace asked for it and it has grown
     * past the line.
     *
     * Called before a turn is built rather than after one lands, so the turn
     * that would have overflowed is the one that fits. Answers whether anything
     * was done, which is what the caller says out loud.
     */
    fun compactIfNeeded(
        workspace: Workspace,
        conversationId: String,
        fallbackModelId: Long?,
        /**
         * Called the moment before the summariser is asked, and only then.
         *
         * A chat that is about to be compacted goes quiet for as long as a model
         * takes to read forty turns, and until this there was nothing on screen
         * to say why - somebody pressed Send and watched nothing happen. The
         * stream announces it from here rather than guessing beforehand, because
         * whether it is going to happen at all is only known once the thread has
         * been measured. Issue #286.
         */
        beginning: () -> Unit = {},
    ): Compacted? {
        val threshold = workspace.compactAfterTokens?.takeIf { it > 0 } ?: return null

        val thread = history.findByConversationId(conversationId)
        if (thread.size <= KEEP + 1) return null

        val carried = tokensIn(thread)
        if (carried < threshold) return null

        val older = thread.dropLast(KEEP)
        val recent = thread.takeLast(KEEP)

        val modelId = workspace.compactionModelId ?: fallbackModelId ?: return null

        beginning()
        val summary = summarise(modelId, older, workspace.compactionSummaryTokens ?: DEFAULT_SUMMARY_TOKENS)
            ?: return null

        /*
         * Written as an assistant turn rather than a system one. A system
         * message is an instruction, and this is not one: it is what was said,
         * shorter. Models treat the two differently, and one that took a summary
         * of a conversation as an instruction would start following it.
         */
        history.saveAll(conversationId, listOf(AssistantMessage(summary)) + recent)
        log.info(
            "Compacted conversation {}: {} messages ({} tokens) became a summary and the last {}",
            conversationId,
            older.size,
            carried,
            KEEP,
        )
        return Compacted(replaced = older.size, kept = recent.size, tokens = carried, summary = summary)
    }

    /**
     * The older turns, as one paragraph.
     *
     * Null when the model would not answer, and the caller then leaves the
     * thread alone: a chat that goes on being too long is a worse outcome than
     * one that fails to send, but silently throwing the older half away because
     * the summariser was unreachable is worse than both.
     */
    /**
     * One turn's conversation, shrunk to fit, when the model has already said
     * it does not. Issue #522.
     *
     * A different moment from [compactIfNeeded], which measures a stored thread
     * before a turn is built. This is for a turn already in flight: an agent
     * that has called forty tools inside one turn holds all forty answers in
     * memory, was never measured against anything, and the provider refuses the
     * next round outright - `request (69015 tokens) exceeds the available
     * context size (65536 tokens)`. The turn then ended, having done all of that
     * work, and whoever was waiting got nothing.
     *
     * So the middle goes and a summary of it stands in its place. The system
     * turn stays because it is what the agent is; the first turn stays because
     * it is what was asked; the last [keep] stay word for word because they are
     * what the next round is about. Everything between them is a record of
     * lookups, which is exactly the part a summary can carry.
     *
     * Null where there is nothing to gain - too short to have a middle, or a
     * summariser that would not answer - and the caller then fails the way it
     * used to, because a turn that goes on being too long is better than one
     * quietly missing the half of itself that mattered.
     *
     * **And the recent end is held to [budgetChars] too.** Chat 981 kept its
     * newest step word for word, and the newest step was a 523,768-character
     * SVG: two summaries of everything before it later, the request was still
     * 452,827 tokens against a window of 262,144, and the turn failed. "Word
     * for word" is a promise about what fits; where the recent end alone does
     * not, its longest items are cut, largest first, each saying how long it
     * was - which works with no middle at all, the case of a turn whose very
     * first lookup was too big.
     *
     * @param keep how many of the most recent turns are left untouched.
     * @param budgetChars what the whole turn must come to, in characters; null
     *   to summarise without measuring, as before.
     */
    fun shrink(
        turns: List<ChatTurn>,
        modelId: Long,
        summaryTokens: Int,
        keep: Int,
        budgetChars: Int? = null,
    ): Shrunk? {
        /*
         * The system turn, the question, a summary, and the recent end. Anything
         * shorter than that has no middle to lose, and shedding it would be
         * throwing away the question instead of the lookups.
         */
        val head = turns.take(2)
        val from = recentStart(turns, keep)
        val middle = if (from > 2) turns.subList(2, from) else emptyList()
        val recent = turns.drop(maxOf(from, head.size))

        /*
         * A long tool answer is not summarised: 476,668 characters of a copper
         * layer's path data have no gist, and handing them to the summariser is
         * a second request too large for the window. It becomes a one-line
         * record of what it was instead, kept as it is beside the summary.
         */
        val records = middle.filter { long(it) }.map { record(turns, it) }
        val summary = if (middle.isEmpty()) {
            null
        } else {
            val transcript = middle.joinToString("\n\n") { "${it.role}: ${if (long(it)) record(turns, it) else it.content}" }
            summariseText(modelId, transcript, summaryTokens)?.let { said ->
                if (records.isEmpty()) said else said + "\n\nLong answers in those steps, as they were:\n" +
                    records.joinToString("\n") { "- $it" }
            }
        }
        if (summary == null && budgetChars == null) return null

        /*
         * Offered as something said rather than as an instruction, the way
         * [compactIfNeeded] does it and for the same reason: a model handed a
         * summary as a system turn starts following it.
         */
        val summarised = if (summary == null) {
            turns
        } else {
            log.info("Shrank a turn in flight: {} turns became a summary, {} kept", middle.size, recent.size)
            head + ChatTurn(
                role = "user",
                content = "The middle of this turn was too long to carry, so " + middle.size +
                    " of its earlier steps were replaced by this summary of them. " +
                    "What follows it is the recent end, word for word unless a step says it was trimmed.\n\n" + summary,
            ) + recent
        }
        val (fitted, trimmed) = budgetChars?.let { fitTo(summarised, it) } ?: (summarised to 0)
        if (summary == null && trimmed == 0) return null
        if (trimmed > 0) log.info("Trimmed {} long steps of a turn in flight to fit {} characters", trimmed, budgetChars)
        return Shrunk(fitted, summary, if (summary == null) 0 else middle.size, trimmed)
    }

    /**
     * Where the recent end begins: [keep] turns from the end, moved back to the
     * call a tool answer belongs to. A request that opens on an answer whose
     * call was summarised away is one providers refuse outright.
     */
    private fun recentStart(turns: List<ChatTurn>, keep: Int): Int {
        var at = (turns.size - keep).coerceAtLeast(0)
        while (at > 2 && turns[at].respondingTo != null) at -= 1
        return at
    }

    /**
     * The turns cut, longest first, until they come to [budget] characters, and
     * how many were cut. The system turn is left whole: it is what the agent is.
     * A tool call's arguments are counted and never cut, because cut JSON is a
     * request the provider refuses for a different reason.
     */
    private fun fitTo(turns: List<ChatTurn>, budget: Int): Pair<List<ChatTurn>, Int> {
        val out = turns.toMutableList()
        var excess = out.sumOf { charsOf(it).toLong() } - budget
        if (excess <= 0) return out to 0
        var trimmed = 0
        val newestAnswer = out.indexOfLast { it.respondingTo != null }
        val longestFirst = out.indices.filter { out[it].role != "system" }.sortedByDescending { charsOf(out[it]) }
        for (at in longestFirst) {
            if (excess <= 0) break
            val turn = out[at]
            /*
             * An older tool answer goes whole, to the one line saying what it
             * was: the model has read it and moved on, and half of it is no more
             * use than none. The newest keeps its beginning, because it is what
             * the next round is about.
             */
            if (turn.respondingTo != null && at != newestAnswer && long(turn)) {
                out[at] = turn.copy(content = record(turns, turn), reasoning = null)
                excess -= charsOf(turn) - charsOf(out[at])
                trimmed += 1
                continue
            }
            // Thinking first: it is the model's own working, and the step it led to is still there.
            val reasoning = turn.reasoning.orEmpty().length
            var shorter = turn
            if (reasoning > 0) {
                shorter = shorter.copy(reasoning = null)
                excess -= reasoning
            }
            val length = shorter.content.length
            /*
             * An answer cut when it arrived keeps the note it was cut with -
             * the whole length and the way to the rest - and only loses more
             * of its beginning.
             */
            val cutAt = shorter.content.indexOf(CUT_MARK)
            val body = if (cutAt >= 0) shorter.content.substring(0, cutAt) else shorter.content
            val note = if (cutAt >= 0) shorter.content.substring(cutAt) else trimNote(length)
            val keep = (length - excess - note.length).coerceIn(minOf(SHORTEST_KEPT, body.length).toLong(), body.length.toLong()).toInt()
            if (excess > 0 && keep + note.length < length) {
                shorter = shorter.copy(content = body.take(keep) + note)
                excess -= length - shorter.content.length
            }
            if (shorter != turn) {
                out[at] = shorter
                trimmed += 1
            }
        }
        return out to trimmed
    }

    /** Whether a step is a tool answer long enough that a line saying what it was says more than its text. */
    private fun long(turn: ChatTurn): Boolean = turn.respondingTo != null && turn.content.length > LONG_ANSWER

    /**
     * One line for a tool answer: the call, how long the answer was, and how
     * much of it the model was shown. The length is the one a cut answer's own
     * note gives, where it was cut when it arrived.
     */
    internal fun record(turns: List<ChatTurn>, answer: ChatTurn): String {
        val call = turns.asSequence().flatMap { it.asked.asSequence() }.firstOrNull { it.id == answer.respondingTo }
        val cutAt = answer.content.indexOf(CUT_MARK)
        val whole = if (cutAt >= 0) {
            CUT_LENGTH.find(answer.content, cutAt)?.groupValues?.get(1)?.toIntOrNull() ?: answer.content.length
        } else {
            answer.content.length
        }
        val shown = if (cutAt >= 0) cutAt else answer.content.length
        val asked = call?.let { it.name + " " + it.arguments.replace(WHITESPACE, " ").take(RECORD_ARGUMENTS) } ?: "a tool"
        return "$asked: answered $whole characters, the first $shown shown; left out of this turn to fit the " +
            "model's window - call it again for any part you need"
    }

    private fun charsOf(turn: ChatTurn): Int =
        turn.content.length + turn.reasoning.orEmpty().length + turn.asked.sumOf { it.arguments.length + it.name.length }

    private fun trimNote(length: Int) =
        "… (trimmed from $length characters when this turn was shrunk to fit the model's window; " +
            "ask for a smaller part if you need more of it)"

    private fun summarise(modelId: Long, older: List<Message>, budget: Int): String? {
        val transcript = older.joinToString("\n\n") { "${role(it)}: ${it.text.orEmpty()}" }
        return summariseText(modelId, transcript, budget)
    }

    /** The same ask, for callers that already hold the text. */
    private fun summariseText(modelId: Long, transcript: String, budget: Int): String? {
        val asked = listOf(
            ChatTurn("system", BRIEF.format(budget)),
            ChatTurn("user", transcript),
        )

        return when (val said = runCatching { models.complete(modelId, asked) }.getOrNull()) {
            is ChatCompletion.Answered -> said.content.trim().takeIf { it.isNotBlank() }
            else -> {
                log.warn("A conversation could not be compacted: the summariser did not answer")
                null
            }
        }
    }

    private fun role(message: Message): String =
        if (message is AssistantMessage) "assistant" else "user"

    /**
     * Roughly how many tokens a thread comes to.
     *
     * Four characters to a token, rounded up, and it is meant to read a little
     * high: compacting early costs one summary, compacting late costs the turn
     * somebody was in the middle of.
     */
    private fun tokensIn(thread: List<Message>): Int =
        thread.sumOf { (it.text.orEmpty().length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN }

    private companion object {
        /**
         * How many turns are kept word for word.
         *
         * The recent end is what the next answer is about, and summarising the
         * question being asked is how a chat starts answering something adjacent
         * to what was said.
         */
        const val KEEP = 6

        const val CHARS_PER_TOKEN = 4

        /** A tool answer longer than this becomes a one-line record when a turn is shrunk, rather than text to summarise. */
        const val LONG_ANSWER = 4_000

        /** How much of a call's arguments its record repeats. */
        const val RECORD_ARGUMENTS = 200

        /** How a cut answer's note begins, wherever it was cut, and the length it gives. */
        const val CUT_MARK = "… (trimmed from "
        val CUT_LENGTH = Regex("""… \(trimmed from (\d+) characters""")
        val WHITESPACE = Regex("""\s+""")

        /** What a step cut to fit keeps at least, so it still says what it was. */
        const val SHORTEST_KEPT = 500

        /** Long enough to be worth having, short enough to be a compaction. */
        const val DEFAULT_SUMMARY_TOKENS = 500

        /**
         * What the summariser is told.
         *
         * It asks for the things a conversation is resumed from - what was
         * decided, what is outstanding, what the person is like to talk to - and
         * says plainly that this replaces the transcript, because a summariser
         * that thinks it is writing an abstract writes about the conversation
         * instead of continuing it.
         */
        const val BRIEF = """
            You are compacting a conversation so it can carry on. What you write replaces
            the transcript below: whatever you leave out is gone.

            Keep what a person picking this up would need - what was asked, what was
            decided, what is still open, names, numbers and any instruction that still
            stands. Drop pleasantries and anything already superseded.

            Write it as notes in the third person, under %d tokens. Do not address anyone,
            do not describe the conversation, and do not add anything that was not said.
        """
    }
}
