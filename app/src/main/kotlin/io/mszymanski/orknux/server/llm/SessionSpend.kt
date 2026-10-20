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
) {

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
        if (input == 0L && output == 0L) return
        val cost = models.costOf(modelId, input, output, cached)
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
