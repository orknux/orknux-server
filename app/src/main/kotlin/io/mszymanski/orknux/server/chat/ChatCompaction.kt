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
     * @param keep how many of the most recent turns are left untouched.
     */
    fun shrink(turns: List<ChatTurn>, modelId: Long, summaryTokens: Int, keep: Int): List<ChatTurn>? {
        /*
         * The system turn, the question, a summary, and the recent end. Anything
         * shorter than that has no middle to lose, and shedding it would be
         * throwing away the question instead of the lookups.
         */
        if (turns.size < keep + 3) return null
        val head = turns.take(2)
        val recent = turns.takeLast(keep)
        val middle = turns.drop(2).dropLast(keep)
        if (middle.isEmpty()) return null

        val transcript = middle.joinToString("\n\n") { "${it.role}: ${it.content}" }
        val summary = summariseText(modelId, transcript, summaryTokens) ?: return null

        log.info("Shrank a turn in flight: {} turns became a summary, {} kept", middle.size, keep)
        /*
         * Offered as something said rather than as an instruction, the way
         * [compactIfNeeded] does it and for the same reason: a model handed a
         * summary as a system turn starts following it.
         */
        return head + ChatTurn(
            role = "user",
            content = "The middle of this turn was too long to carry, so " + middle.size +
                " of its earlier steps were replaced by this summary of them. " +
                "What follows it is the recent end, word for word.\n\n" + summary,
        ) + recent
    }

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
