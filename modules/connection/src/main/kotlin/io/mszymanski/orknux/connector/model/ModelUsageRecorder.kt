package io.mszymanski.orknux.connector.model

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate

/**
 * Counts what a model was actually used for.
 *
 * `model_usage_day` holds one row per model per day, and until now nothing wrote
 * to it — which is why the metrics screen reported an empty window however much
 * the chat was used. Every answered call adds itself here.
 *
 * In its own transaction: recording is bookkeeping about a call that already
 * happened, and a chat that answered should not be rolled back because the
 * counter could not be written.
 */
@Service
class ModelUsageRecorder(
    private val usage: ModelUsageRepository,
    /** Defaulted rather than a bean, the way `ModelService` takes one. */
    private val clock: Clock = Clock.systemDefaultZone(),
) {

    /**
     * Adds one call to today's row for this model, creating it when this is the
     * first call of the day.
     *
     * One upsert, so two calls at once are safe without a retry: there is no
     * window between a read and a write for the other to land in, and no
     * failed insert to leave the transaction aborted on Postgres - which is
     * what the old catch-and-retry ran into, losing the loser's usage with an
     * assertion failure from Hibernate. Issue #599.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(modelId: Long, inputTokens: Long, outputTokens: Long, millis: Long) {
        // Summed, not averaged: the mean over a window is the total time over
        // the total requests, which is what `ModelService.usage` works out.
        usage.addCall(modelId, LocalDate.now(clock), inputTokens, outputTokens, millis)
    }
}
