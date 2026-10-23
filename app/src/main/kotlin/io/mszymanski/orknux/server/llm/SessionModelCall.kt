package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.connector.model.ModelTimings
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime

/**
 * One model call made into a session, with the timings its provider reported
 * where it reported any. See V420.
 *
 * The timing columns are the provider's own figures and nothing else: null for
 * every provider that does not report them, never a speed worked out here from
 * [millis], which is the whole request - the network, a queue and the prompt
 * together - and not the model's speed.
 */
@Entity
@Table(name = "session_model_call")
class SessionModelCall(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "session_id", nullable = false)
    val sessionId: Long,

    @Column(name = "model_id", nullable = false)
    val modelId: Long,

    @Column(nullable = false)
    val at: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "input_tokens", nullable = false)
    val inputTokens: Long,

    @Column(name = "cached_input_tokens", nullable = false)
    val cachedInputTokens: Long,

    @Column(name = "output_tokens", nullable = false)
    val outputTokens: Long,

    /** How long the call took from here, request to last byte. */
    @Column(nullable = false)
    val millis: Long,

    /** Prompt tokens the provider processed, which leaves out what its cache served. */
    @Column(name = "prompt_tokens")
    val promptTokens: Long? = null,

    @Column(name = "prompt_millis")
    val promptMillis: Double? = null,

    @Column(name = "prompt_per_second")
    val promptPerSecond: Double? = null,

    @Column(name = "generated_tokens")
    val generatedTokens: Long? = null,

    @Column(name = "generated_millis")
    val generatedMillis: Double? = null,

    @Column(name = "generated_per_second")
    val generatedPerSecond: Double? = null,
) {
    constructor(
        sessionId: Long,
        modelId: Long,
        inputTokens: Long,
        cachedInputTokens: Long,
        outputTokens: Long,
        millis: Long,
        timings: ModelTimings?,
    ) : this(
        sessionId = sessionId,
        modelId = modelId,
        inputTokens = inputTokens,
        cachedInputTokens = cachedInputTokens,
        outputTokens = outputTokens,
        millis = millis,
        promptTokens = timings?.promptTokens,
        promptMillis = timings?.promptMillis,
        promptPerSecond = timings?.promptPerSecond,
        generatedTokens = timings?.generatedTokens,
        generatedMillis = timings?.generatedMillis,
        generatedPerSecond = timings?.generatedPerSecond,
    )
}

interface SessionModelCallRepository : JpaRepository<SessionModelCall, Long> {

    /** A session's calls, newest first. */
    fun findBySessionIdOrderByIdDesc(sessionId: Long, page: Pageable): List<SessionModelCall>

    /** A session's calls whose provider reported timings, newest first. */
    fun findBySessionIdAndPromptTokensIsNotNullOrderByIdDesc(sessionId: Long, page: Pageable): List<SessionModelCall>

    /**
     * The sums a session's averages are made of, over the calls that reported:
     * prompt tokens, prompt milliseconds, generated tokens, generated
     * milliseconds, and how many calls. One row, of nulls where none reported.
     */
    @Query(
        "SELECT SUM(c.promptTokens), SUM(c.promptMillis), SUM(c.generatedTokens), SUM(c.generatedMillis), COUNT(c) " +
            "FROM SessionModelCall c WHERE c.sessionId = :session AND c.promptTokens IS NOT NULL",
    )
    fun timedSums(@Param("session") session: Long): List<Array<Any?>>

    /** A model's newest calls that reported timings, newest first. */
    fun findByModelIdAndPromptTokensIsNotNullOrderByIdDesc(modelId: Long, page: Pageable): List<SessionModelCall>
}

/**
 * How fast a provider read prompts and wrote answers, over some calls.
 *
 * Weighted by tokens - every token over every millisecond - rather than a mean
 * of each call's rate: a three-token call that ran at an odd speed is not worth
 * as much as a thirty-thousand-token one. A speed is null where its milliseconds
 * add up to nothing, so a set of calls served whole from cache says nothing
 * rather than infinity.
 */
data class ModelSpeedView(
    /** How many calls these figures are over. */
    val calls: Int,
    val promptTokens: Long,
    val promptMillis: Double,
    val generatedTokens: Long,
    val generatedMillis: Double,
) {
    val promptTokensPerSecond: Double? get() = perSecond(promptTokens, promptMillis)
    val generatedTokensPerSecond: Double? get() = perSecond(generatedTokens, generatedMillis)

    companion object {
        private fun perSecond(tokens: Long, millis: Double): Double? =
            if (millis > 0) tokens * 1000.0 / millis else null

        /** Over [calls], of which only those that reported count; null where none did. */
        fun over(calls: List<SessionModelCall>): ModelSpeedView? {
            val timed = calls.filter { it.promptTokens != null }
            if (timed.isEmpty()) return null
            return ModelSpeedView(
                calls = timed.size,
                promptTokens = timed.sumOf { it.promptTokens ?: 0 },
                promptMillis = timed.sumOf { it.promptMillis ?: 0.0 },
                generatedTokens = timed.sumOf { it.generatedTokens ?: 0 },
                generatedMillis = timed.sumOf { it.generatedMillis ?: 0.0 },
            )
        }

        /** From [SessionModelCallRepository.timedSums]' row. */
        fun ofSums(row: Array<Any?>?): ModelSpeedView? {
            if (row == null) return null
            val count = (row[4] as Number?)?.toInt() ?: 0
            if (count == 0) return null
            return ModelSpeedView(
                calls = count,
                promptTokens = (row[0] as Number?)?.toLong() ?: 0,
                promptMillis = (row[1] as Number?)?.toDouble() ?: 0.0,
                generatedTokens = (row[2] as Number?)?.toLong() ?: 0,
                generatedMillis = (row[3] as Number?)?.toDouble() ?: 0.0,
            )
        }
    }
}

/** One call as the session page lists it. */
data class SessionModelCallView(
    val id: Long,
    val at: String,
    val modelId: Long,
    val inputTokens: Long,
    val cachedInputTokens: Long,
    val outputTokens: Long,
    val millis: Long,
    val promptTokens: Long?,
    val promptMillis: Double?,
    val promptTokensPerSecond: Double?,
    val generatedTokens: Long?,
    val generatedMillis: Double?,
    val generatedTokensPerSecond: Double?,
) {
    constructor(call: SessionModelCall) : this(
        id = call.id ?: 0,
        at = call.at.toString(),
        modelId = call.modelId,
        inputTokens = call.inputTokens,
        cachedInputTokens = call.cachedInputTokens,
        outputTokens = call.outputTokens,
        millis = call.millis,
        promptTokens = call.promptTokens,
        promptMillis = call.promptMillis,
        promptTokensPerSecond = call.promptPerSecond,
        generatedTokens = call.generatedTokens,
        generatedMillis = call.generatedMillis,
        generatedTokensPerSecond = call.generatedPerSecond,
    )
}
