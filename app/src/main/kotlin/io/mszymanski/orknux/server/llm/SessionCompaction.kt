package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.workspace.Workspace
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Keeps a long session inside the window its model will accept. Issue #523.
 *
 * A chat has had this since #286: past a threshold the older part of the thread
 * is replaced by one summary of itself and the conversation carries on. A
 * session never did, and the difference was not deliberate - a chat's history is
 * a stored list of messages and a session's is `llm_session_event`, so the code
 * that compacts one could not touch the other.
 *
 * What happened instead was quiet. [LlmSessionRecorder.remembered] carries the
 * most recent turns up to a budget and the rest simply do not come: an agent
 * asked on Friday about something settled on Monday answers as though Monday
 * never happened, with nothing anywhere saying the beginning was dropped. A
 * budget that truncates is a memory that forgets without telling anybody.
 *
 * **Marked rather than deleted.** The turns that were summarised stay in the
 * table with [LlmSessionEvent.superseded] set, so the transcript still shows
 * every step and a person reading how an answer was arrived at still sees all of
 * it. Only what is put in front of the model is narrowed. That is the same
 * distinction the recall budget already draws, made durable - and it is why this
 * is safe to do automatically where deleting would not be.
 *
 * **Measured before a turn is built**, so the turn that would have overflowed is
 * the one that fits, and the summary is written once rather than recomputed on
 * every read.
 *
 * Tokens are estimated at four characters each, as [ChatCompaction] estimates
 * them and for the same reason: the exact count belongs to the model's own
 * tokeniser, which this server does not carry one of per provider. It errs high,
 * because compacting early costs one summary and compacting late costs the turn.
 */
@Service
class SessionCompaction(
    private val events: LlmSessionEventRepository,
    private val models: ModelChatClient,
    private val settings: InstallationSettings,
    transactions: PlatformTransactionManager,
) {

    /**
     * Only the writes, and not the summary. The summariser is a model call, and
     * a transaction held open across one holds a connection for as long as the
     * model takes - and on SQLite the write lock with it, so the usage the call
     * records in a transaction of its own waited out the busy timeout, failed,
     * and left its connection unusable for whoever drew it next.
     */
    private val writing = TransactionTemplate(transactions)

    private val log = LoggerFactory.getLogger(javaClass)

    /** What a compaction came to, for whoever wants to say it out loud. */
    data class Compacted(val replaced: Int, val kept: Int, val tokens: Int)

    /**
     * Compacts this session if the workspace asked for it and it has grown past
     * the line.
     *
     * Null where nothing was done, which is the ordinary case: off, short
     * enough, or a summariser that would not answer. A session that goes on
     * being too long is a worse outcome than a failed compaction, but silently
     * throwing away the older half because the summariser was unreachable is
     * worse than both - so a summary that could not be written leaves the
     * session exactly as it was.
     *
     * @param fallbackModelId the model this turn is using, for the summary,
     *   where the workspace has not named one of its own.
     */
    fun compactIfNeeded(session: Long, workspace: Workspace?, fallbackModelId: Long?): Compacted? {
        val threshold = after(workspace)?.takeIf { it > 0 } ?: return null
        val keep = workspace?.sessionCompactionKeepTurns ?: settings.sessionCompactionKeepTurns()

        /*
         * Everything still carried, newest first. Read wide rather than to the
         * recall budget: the budget is what a turn can afford to carry and this
         * is what the session actually holds, and compacting only what the
         * budget already kept would summarise the recent end while the older
         * turns it is meant to rescue stay dropped.
         */
        val carried = events.latest(session, CARRIED, PageRequest.of(0, MOST_READ))
        if (carried.size <= keep + 1) return null

        val tokens = carried.sumOf { tokensIn(it) }
        if (tokens < threshold) return null

        // Oldest first, which is how a transcript reads and how a summary of one
        // has to be written.
        val ordered = carried.asReversed()
        val older = ordered.dropLast(keep)
        if (older.isEmpty()) return null

        val modelId = workspace?.sessionCompactionModelId ?: fallbackModelId ?: return null
        val budget = workspace?.sessionCompactionSummaryTokens ?: settings.sessionCompactionSummaryTokens()

        val summary = summarise(modelId, older, budget) ?: return null

        /*
         * The summary is written at the moment the oldest turn it replaces
         * happened, not now. Every read here orders by `at`, so a summary
         * stamped with the present would sort after the turns it is standing in
         * front of and the conversation would read back to front.
         */
        val standsAt = older.first().at
        writing.executeWithoutResult {
            events.save(
                LlmSessionEvent(
                    sessionId = session,
                    kind = LlmSessionEventKind.SUMMARY,
                    actor = SUMMARISER,
                    content = summary,
                    at = standsAt,
                ),
            )
            older.forEach { it.superseded = true }
            events.saveAll(older)
        }

        log.info(
            "Compacted session {}: {} turns ({} tokens) became a summary and the last {} were kept",
            session,
            older.size,
            tokens,
            keep,
        )
        return Compacted(replaced = older.size, kept = keep, tokens = tokens)
    }

    /**
     * The threshold, which is the workspace's own or the installation's.
     *
     * Its own number rather than the chat's, because they are different
     * judgements about different things: a chat's threshold is about a
     * conversation a person is having and a session's is about one an agent is
     * having, and the second runs far longer and carries tool results the first
     * never sees.
     */
    private fun after(workspace: Workspace?): Int? =
        workspace?.sessionCompactAfterTokens ?: settings.sessionCompactAfterTokens().takeIf { it > 0 }

    /**
     * The older turns, as one paragraph.
     *
     * Asked as a plain completion with no tools: a summariser that could call
     * something would be doing the agent's work rather than describing it.
     */
    private fun summarise(modelId: Long, older: List<LlmSessionEvent>, budget: Int): String? {
        val transcript = older.joinToString(PARAGRAPH) { event ->
            role(event) + ": " + event.content.orEmpty()
        }
        val asked = listOf(
            ChatTurn("system", BRIEF.format(budget)),
            ChatTurn("user", transcript),
        )
        return when (val said = runCatching { models.complete(modelId, asked) }.getOrNull()) {
            is ChatCompletion.Answered -> said.content.trim().takeIf { it.isNotBlank() }
            else -> {
                log.warn("Session {} could not be compacted: the summariser did not answer", older.first().sessionId)
                null
            }
        }
    }

    private fun role(event: LlmSessionEvent): String = when (event.kind) {
        LlmSessionEventKind.USER -> "user"
        LlmSessionEventKind.SUMMARY -> "summary of what came before"
        else -> event.actor
    }

    private fun tokensIn(event: LlmSessionEvent): Int =
        (event.content.orEmpty().length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

    private companion object {

        /**
         * What counts towards the length and what a summary replaces.
         *
         * What was said, and any summary already standing in for what was said
         * before that - so a session compacted twice folds the first summary
         * into the second rather than accumulating one per compaction.
         */
        val CARRIED = listOf(
            LlmSessionEventKind.USER,
            LlmSessionEventKind.AGENT,
            LlmSessionEventKind.SUMMARY,
        )

        /**
         * How far back a compaction will read.
         *
         * High enough that no real session reaches it and low enough that a
         * pathological one does not read a hundred thousand rows into memory to
         * find out it should be compacted.
         */
        const val MOST_READ = 2_000

        const val CHARS_PER_TOKEN = 4

        const val SUMMARISER = "system"

        val PARAGRAPH = 10.toChar().toString() + 10.toChar().toString()

        val BRIEF =
            "You are shortening the earlier part of a conversation between a person and an agent so " +
                "the agent can carry on without it in full. Write about %d tokens. Keep what the next " +
                "answer would need: what was asked, what was decided, what was done, names, numbers, " +
                "identifiers and anything still outstanding. Drop pleasantries and repetition. Write it " +
                "as a plain account of what happened, not as instructions, and do not address anybody."
    }
}
