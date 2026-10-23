package io.mszymanski.orknux.server.errors

import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.usage.UsageReportAPI
import io.mszymanski.orknux.server.usage.UsageReportFilter
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
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
        @Argument order: ErrorOrder?,
        @Argument ascending: Boolean?,
    ): ErrorReportView {
        access.requireAdmin()
        val period = UsageReportAPI.Period.of(filter)
        fun matching(only: ErrorSource?) = errorFilter(period.from, period.to, filter?.workspaceId, only, filter?.search)

        // Every row the filter matches, once: the chart and the most frequent
        // read all of them anyway, and the workspace column orders by a name
        // the record does not hold, which the database cannot sort by.
        val all = records.findAll(matching(source))
        val names = workspaces.findAllById(all.map { it.workspaceId }.toSet()).associate { it.id to it.name }
        val take = (size ?: DEFAULT_PAGE).coerceIn(1, MAX_PAGE)
        val from = (page ?: 0).coerceAtLeast(0) * take
        val shown = all.sortedWith(ordered(order ?: ErrorOrder.AT, ascending ?: false, names)).drop(from).take(take)
        // What failed most often, over every source the other filters match.
        val often = all
            .groupBy { it.source to it.subject }
            .map { (key, rows) -> ErrorSubjectView(key.first, key.second, rows.size, rows.maxOf { it.at }.format(ISO)) }
            .sortedWith(compareByDescending<ErrorSubjectView> { it.count }.thenBy { it.subject.lowercase() })
            .take(TOP_SUBJECTS)
        // Every day of the period, a quiet one included, so the chart's gaps are days and not missing bars.
        val zone = ZoneId.systemDefault()
        val byDay = all.groupBy { it.at.atZoneSameInstant(zone).toLocalDate() }
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
            total = all.size,
            counts = ErrorSource.entries.map { ErrorCountView(it, records.count(matching(it)).toInt()) },
            often = often,
            days = days,
            rows = shown.map { one ->
                ErrorRecordView(
                    id = one.id!!,
                    at = one.at.format(ISO),
                    workspaceId = one.workspaceId,
                    workspaceName = names[one.workspaceId],
                    source = one.source,
                    subject = one.subject,
                    executionId = one.executionId,
                    sessionId = one.sessionId,
                    message = one.message,
                )
            },
        )
    }

    /**
     * The rows in the order a heading asked for, newest first within a tie so
     * a page boundary falls in the same place on every read. A removed
     * workspace has no name and goes last either way.
     */
    private fun ordered(order: ErrorOrder, ascending: Boolean, names: Map<Long?, String>): Comparator<ErrorRecord> {
        val text = String.CASE_INSENSITIVE_ORDER
        val column: Comparator<ErrorRecord> = when (order) {
            // Two recorded in one instant still have an order: the one written first.
            ErrorOrder.AT -> compareBy<ErrorRecord>({ it.at }, { it.id })
            ErrorOrder.SOURCE -> compareBy { it.source.name }
            ErrorOrder.SUBJECT -> compareBy(text) { it.subject }
            ErrorOrder.MESSAGE -> compareBy(text) { it.message }
            ErrorOrder.WORKSPACE -> compareBy(nullsLast(text)) { names[it.workspaceId] }
        }
        val turned = if (ascending) column else column.reversed()
        val removedLast = if (order == ErrorOrder.WORKSPACE) compareBy<ErrorRecord> { names[it.workspaceId] == null } else null
        val newest = compareByDescending<ErrorRecord> { it.at }.thenByDescending { it.id }
        return (removedLast?.then(turned) ?: turned).then(newest)
    }

    private companion object {
        const val DEFAULT_PAGE = 25
        const val MAX_PAGE = 200
        const val TOP_SUBJECTS = 5
        val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    }
}

/** What a heading of the errors table orders by. */
enum class ErrorOrder { AT, SOURCE, SUBJECT, MESSAGE, WORKSPACE }

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
