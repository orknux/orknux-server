package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.server.usage.UsageTracking
import org.springframework.stereotype.Service
import java.math.BigDecimal

/**
 * What a session has cost: the tokens of every model call made into it, and
 * their price where the model carries one.
 *
 * Added after each call rather than worked out from the transcript, because the
 * transcript holds what was said, not what was charged - a round that asked for
 * tools and a round re-asked after something arrived cost tokens and leave no
 * answer behind. Added in one statement, so two turns finishing at once cannot
 * lose either's count. A call whose model has no price counts its tokens and
 * marks the cost as partial, because no price is not a price of nothing.
 */
@Service
class SessionSpend(
    private val sessions: LlmSessionRepository,
    private val models: ModelService,
    private val usage: UsageTracking,
    /** Each call on its own, with the timings its provider reported; see [SessionModelCall]. */
    private val calls: SessionModelCallRepository,
    /** Where the spend of a step that keeps no session is kept instead. */
    private val steps: io.mszymanski.orknux.workflow.execution.ExecutionStepRepository,
) {

    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    /**
     * One model call, into [sessionId] where there is one. A turn that keeps no
     * session is charged all the same - an agent node with no session wired to
     * it was simply not counted, on its run's page or in any report - so its
     * spend goes on the step carrying it, and to the ledger under
     * [UsageTracking.NO_SESSION].
     */
    fun add(sessionId: Long?, workspaceId: Long, modelId: Long, call: ChatCompletion) {
        if (sessionId != null) {
            add(sessionId, modelId, call)
            return
        }
        val (input, output, cached, written) = when (call) {
            is ChatCompletion.Answered -> listOf(call.inputTokens, call.outputTokens, call.cachedInputTokens, call.cacheWriteTokens)
            is ChatCompletion.CalledTools -> listOf(call.inputTokens, call.outputTokens, call.cachedInputTokens, call.cacheWriteTokens)
            else -> return
        }
        if (input == 0L && output == 0L) return
        val cost = models.costOf(modelId, input, output, cached, written)
        // Read off the thread, as a stop finds it; a turn no step is carrying has only the ledger.
        io.mszymanski.orknux.workflow.execution.StepInterrupts.current()?.let {
            runCatching { steps.addSpend(it.executionId, it.nodeKey, cost ?: BigDecimal.ZERO, cost == null) }
                .onFailure { failure -> log.warn("The spend of step {} could not be recorded", it.nodeKey, failure) }
        }
        usage.recordUnkept(workspaceId, modelId, input, cached, output, cost)
    }

    /**
     * One model call made into [sessionId], whatever it answered. Nothing for a
     * call that reported no counts.
     *
     * Not one transaction: the session's sum is its own statement, and the
     * report's row its own save, so a row that could not be written cannot take
     * the session's count back with it.
     */
    fun add(sessionId: Long, modelId: Long, call: ChatCompletion) {
        val (input, output, cached) = when (call) {
            is ChatCompletion.Answered -> Triple(call.inputTokens, call.outputTokens, call.cachedInputTokens)
            is ChatCompletion.CalledTools -> Triple(call.inputTokens, call.outputTokens, call.cachedInputTokens)
            else -> return
        }
        val written = when (call) {
            is ChatCompletion.Answered -> call.cacheWriteTokens
            is ChatCompletion.CalledTools -> call.cacheWriteTokens
            else -> 0L
        }
        val (millis, timings) = when (call) {
            is ChatCompletion.Answered -> call.millis to call.timings
            is ChatCompletion.CalledTools -> call.millis to call.timings
            else -> 0L to null
        }
        if (input == 0L && output == 0L && timings == null) return
        // Its own failure, never the call's: a row that could not be written is
        // a call missing from the list, not a turn that failed after it answered.
        runCatching { calls.save(SessionModelCall(sessionId, modelId, input, cached, output, millis, timings)) }
            .onFailure { log.warn("A model call into session {} could not be recorded", sessionId, it) }
        if (input == 0L && output == 0L) return
        val cost = models.costOf(modelId, input, output, cached, written)
        sessions.addSpend(sessionId, input, output, cached, cost ?: BigDecimal.ZERO, cost == null)
        // And to whoever prompted it, where an administrator is tracking that.
        usage.record(sessionId, modelId, input, cached, output, cost)
    }
}

/** A session's spend, as the session page shows it. */
data class SessionSpendView(
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    /** In the model's currency, from the prices on each model called; null where none was priced. */
    val cost: String?,
    /** Whether some call's model had no price, so [cost] is part of the whole. */
    val partlyUnpriced: Boolean,
)
