package io.mszymanski.orknux.server.errors

import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.usage.UsageReportAPI
import io.mszymanski.orknux.server.usage.UsageReportFilter
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Admin > Reports > Errors: what failed over a period - runs, agents that could
 * not answer, the quick chat's model - newest first, with how many of each and
 * what failed most often.
 *
 * Takes the usage report's period and workspace, as the ratings report does,
 * so switching between the reports keeps them.
 */
@Controller
class ErrorReportAPI(
    private val access: WorkspaceAccess,
    private val records: ErrorRecordRepository,
    private val workspaces: WorkspaceRepository,
) {

    @QueryMapping
    @Transactional(readOnly = true)
    fun errorReport(
        @Argument filter: UsageReportFilter?,
        @Argument source: ErrorSource?,
        @Argument page: Int?,
        @Argument size: Int?,
    ): ErrorReportView {
        access.requireAdmin()
        val period = UsageReportAPI.Period.of(filter)
        fun matching(only: ErrorSource?) = errorFilter(period.from, period.to, filter?.workspaceId, only, filter?.search)

        val take = (size ?: DEFAULT_PAGE).coerceIn(1, MAX_PAGE)
        val shown = records.findAll(
            matching(source),
            PageRequest.of((page ?: 0).coerceAtLeast(0), take, Sort.by(Sort.Order.desc("at"), Sort.Order.desc("id"))),
        )
        // What failed most often, over every source the other filters match.
        val often = records.findAll(matching(source))
            .groupBy { it.source to it.subject }
            .map { (key, rows) -> ErrorSubjectView(key.first, key.second, rows.size, rows.maxOf { it.at }.format(ISO)) }
            .sortedWith(compareByDescending<ErrorSubjectView> { it.count }.thenBy { it.subject.lowercase() })
            .take(TOP_SUBJECTS)
        val places = workspaces.findAllById(shown.content.map { it.workspaceId }.toSet()).associate { it.id to it.name }
        // Every day of the period, a quiet one included, so the chart's gaps are days and not missing bars.
        val zone = ZoneId.systemDefault()
        val byDay = records.findAll(matching(source)).groupBy { it.at.atZoneSameInstant(zone).toLocalDate() }
        val first = period.from.atZoneSameInstant(zone).toLocalDate()
        val last = period.to.atZoneSameInstant(zone).toLocalDate().minusDays(1)
        val days = generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.map { day ->
            val held = byDay[day].orEmpty()
            ErrorDayView(
                day = day.toString(),
                runs = held.count { it.source == ErrorSource.RUN },
                agents = held.count { it.source == ErrorSource.AGENT },
                models = held.count { it.source == ErrorSource.MODEL },
            )
        }.toList()
        return ErrorReportView(
            total = shown.totalElements.toInt(),
            counts = ErrorSource.entries.map { ErrorCountView(it, records.count(matching(it)).toInt()) },
            often = often,
            days = days,
            rows = shown.content.map { one ->
                ErrorRecordView(
                    id = one.id!!,
                    at = one.at.format(ISO),
                    workspaceId = one.workspaceId,
                    workspaceName = places[one.workspaceId],
                    source = one.source,
                    subject = one.subject,
                    executionId = one.executionId,
                    sessionId = one.sessionId,
                    message = one.message,
                )
            },
        )
    }

    private companion object {
        const val DEFAULT_PAGE = 25
        const val MAX_PAGE = 200
        const val TOP_SUBJECTS = 5
        val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    }
}

data class ErrorReportView(
    /** Every row the filter and source match, across pages. */
    val total: Int,
    /** How many of each source the filter matches, whatever source is picked. */
    val counts: List<ErrorCountView>,
    val often: List<ErrorSubjectView>,
    /** How many of each source failed on each day of the period, oldest first. */
    val days: List<ErrorDayView>,
    val rows: List<ErrorRecordView>,
)

data class ErrorDayView(val day: String, val runs: Int, val agents: Int, val models: Int)

data class ErrorCountView(val source: ErrorSource, val count: Int)

data class ErrorSubjectView(val source: ErrorSource, val subject: String, val count: Int, val last: String)

data class ErrorRecordView(
    val id: Long,
    val at: String,
    val workspaceId: Long,
    val workspaceName: String?,
    val source: ErrorSource,
    val subject: String,
    val executionId: Long?,
    val sessionId: Long?,
    val message: String,
)
