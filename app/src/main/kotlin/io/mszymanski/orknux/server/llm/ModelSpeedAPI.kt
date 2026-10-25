package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.connector.model.LlmModelView
import io.mszymanski.orknux.connector.model.usageWindow
import io.mszymanski.orknux.server.model.usageDay
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import org.springframework.data.domain.PageRequest
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.TreeMap

/**
 * The speeds a provider reported, on a session and on a model.
 *
 * Fields of objects the caller has already been let see: `llmSession` and
 * `model` answer null for a session or model in a workspace the caller cannot
 * see, so nothing here is reached for one, and nothing here checks again.
 *
 * The session's figure is over all of its calls, because a session is a bounded
 * thing and its average is the honest one; the model's is over its newest
 * calls, as many as [Tunable.MODEL_SPEED_CALLS], because a model lives for
 * months and a server it runs on is upgraded under it.
 */
@Controller
class ModelSpeedAPI(
    private val calls: SessionModelCallRepository,
    private val tuning: Tuning = Tuning.DEFAULTS,
    private val clock: Clock = Clock.systemDefaultZone(),
) {

    @SchemaMapping(typeName = "LlmSession", field = "speed")
    fun sessionSpeed(session: LlmSessionView): ModelSpeedView? =
        ModelSpeedView.ofSums(calls.timedSums(session.id).firstOrNull())

    @SchemaMapping(typeName = "LlmSession", field = "modelCalls")
    fun modelCalls(session: LlmSessionView, @Argument timedOnly: Boolean?): List<SessionModelCallView> {
        val page = PageRequest.of(0, window())
        val found = if (timedOnly == true) {
            calls.findBySessionIdAndPromptTokensIsNotNullOrderByIdDesc(session.id, page)
        } else {
            calls.findBySessionIdOrderByIdDesc(session.id, page)
        }
        return found.map(::SessionModelCallView)
    }

    @SchemaMapping(typeName = "Model", field = "speed")
    fun modelSpeed(model: LlmModelView): ModelSpeedView? =
        ModelSpeedView.over(calls.findByModelIdAndPromptTokensIsNotNullOrderByIdDesc(model.id, PageRequest.of(0, window())))

    /**
     * The model's speed per [unit] over the usage card's window, read the way
     * `modelUsage` reads it, in the server's time zone as its days are.
     *
     * Bucketed here rather than in SQL, because the two engines truncate a
     * timestamp in two dialects and SQLite keeps no time zone to truncate in.
     * The rows are read a page at a time and the buckets held are never more
     * than [Tunable.MODEL_SPEED_POINTS] and one, so a long window costs time
     * and not memory. Past the cap the oldest buckets give way, which leaves
     * the newest - the part of a chart anybody is looking at.
     */
    @SchemaMapping(typeName = "Model", field = "speedOverTime")
    fun speedOverTime(
        model: LlmModelView,
        @Argument unit: SpeedUnit,
        @Argument from: String?,
        @Argument to: String?,
    ): ModelSpeedOverTimeView {
        val zone = clock.zone
        val (since, until) = usageWindow(USAGE_DAYS, usageDay(from, "from"), usageDay(to, "to"), LocalDate.now(clock))
        val start = since.atStartOfDay(zone).toOffsetDateTime()
        val end = until.plusDays(1).atStartOfDay(zone).toOffsetDateTime()
        val cap = tuning[Tunable.MODEL_SPEED_POINTS]

        val buckets = TreeMap<LocalDateTime, Sums>()
        var truncated = false
        var before = Long.MAX_VALUE
        while (true) {
            val rows = calls.timedBetween(model.id, start, end, before, PageRequest.of(0, PAGE))
            for (row in rows) {
                val at = unit.start((row[1] as OffsetDateTime).atZoneSameInstant(zone).toLocalDateTime())
                val held = buckets[at]
                if (held == null && buckets.size >= cap && at < buckets.firstKey()) {
                    truncated = true
                    continue
                }
                (held ?: Sums().also { buckets[at] = it }).add(row)
                if (buckets.size > cap) {
                    buckets.pollFirstEntry()
                    truncated = true
                }
            }
            if (rows.size < PAGE) break
            before = (rows.last()[0] as Number).toLong()
        }

        return ModelSpeedOverTimeView(
            unit = unit,
            from = since.format(DateTimeFormatter.ISO_LOCAL_DATE),
            to = until.format(DateTimeFormatter.ISO_LOCAL_DATE),
            points = buckets.map { (at, sums) -> ModelSpeedPointView(at.format(BUCKET), sums.speed()) },
            truncated = truncated,
        )
    }

    private fun window(): Int = tuning[Tunable.MODEL_SPEED_CALLS]

    /** What one bucket's speeds are made of: the sums [ModelSpeedView] takes. */
    private class Sums {
        var calls = 0
        var promptTokens = 0L
        var promptMillis = 0.0
        var generatedTokens = 0L
        var generatedMillis = 0.0

        /** A row of [SessionModelCallRepository.timedBetween]. */
        fun add(row: Array<Any?>) {
            calls++
            promptTokens += (row[2] as Number?)?.toLong() ?: 0
            promptMillis += (row[3] as Number?)?.toDouble() ?: 0.0
            generatedTokens += (row[4] as Number?)?.toLong() ?: 0
            generatedMillis += (row[5] as Number?)?.toDouble() ?: 0.0
        }

        fun speed() = ModelSpeedView(calls, promptTokens, promptMillis, generatedTokens, generatedMillis)
    }

    private companion object {
        /** The usage card's default window, which the chart follows. */
        const val USAGE_DAYS = 30

        /** Rows read at once: the size of a page, not a limit on anything anybody sees. */
        const val PAGE = 2000

        val BUCKET: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    }
}

/** How long a period each point of a speed chart averages over. */
enum class SpeedUnit {
    MINUTE, HOUR, DAY, MONTH, YEAR;

    /** The start of the period [at] falls in. */
    fun start(at: LocalDateTime): LocalDateTime = when (this) {
        MINUTE -> at.truncatedTo(ChronoUnit.MINUTES)
        HOUR -> at.truncatedTo(ChronoUnit.HOURS)
        DAY -> at.truncatedTo(ChronoUnit.DAYS)
        MONTH -> at.withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS)
        YEAR -> at.withDayOfYear(1).truncatedTo(ChronoUnit.DAYS)
    }
}

/** One period of the chart; its speeds are [ModelSpeedView]'s, weighted by tokens. */
class ModelSpeedPointView(val at: String, private val speed: ModelSpeedView) {
    val calls: Int get() = speed.calls
    val promptTokensPerSecond: Double? get() = speed.promptTokensPerSecond
    val generatedTokensPerSecond: Double? get() = speed.generatedTokensPerSecond
}

/** A model's speed chart: one point per period that had timed calls, oldest first. */
data class ModelSpeedOverTimeView(
    val unit: SpeedUnit,
    val from: String,
    val to: String,
    val points: List<ModelSpeedPointView>,
    val truncated: Boolean,
)
