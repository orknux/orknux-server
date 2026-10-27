package io.mszymanski.orknux.server.flag

import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Admin > Reports > Flags: what agents flagged about the conversations they
 * were in, through [SessionFlagTools.FLAG].
 *
 * The installation's administrators and nobody else, for every query here: a
 * flag is about what a person was doing, and a workspace's own members are
 * exactly who must not read one. Read whole and paged here, as the ratings
 * report is - an installation's flags are a list a person reads.
 */
@Controller
class SessionFlagAPI(
    private val access: WorkspaceAccess,
    private val audit: WorkspaceAuditRecorder,
    private val flags: SessionFlagRepository,
    private val sessions: LlmSessionRepository,
    private val workspaces: WorkspaceRepository,
) {

    @QueryMapping
    @Transactional(readOnly = true)
    fun sessionFlagReport(
        @Argument filter: SessionFlagFilter?,
        @Argument page: Int?,
        @Argument size: Int?,
        @Argument order: SessionFlagOrder?,
        @Argument ascending: Boolean?,
    ): SessionFlagReportView {
        access.requireAdmin()
        val (from, to) = periodOf(filter)
        val all = flags.findAll(
            flagFilter(
                from, to, filter?.workspaceId, filter?.type,
                filter?.severityFrom, filter?.severityTo, filter?.state, filter?.search,
            ),
        )
        val held = sessions.findAllById(all.map { it.sessionId }.toSet()).associateBy { it.id }
        val places = workspaces.findAllById(all.map { it.workspaceId }.toSet()).associate { it.id to it.name }
        val views = all.map { view(it, held[it.sessionId]?.let { one -> one.title ?: one.sessionKey }, places[it.workspaceId]) }
        val at = (page ?: 0).coerceAtLeast(0)
        val take = (size ?: DEFAULT_PAGE).coerceIn(1, MAX_PAGE)
        return SessionFlagReportView(
            total = all.size,
            open = all.count { it.clearedAt == null },
            security = all.count { it.type == SessionFlagType.SECURITY },
            technical = all.count { it.type == SessionFlagType.TECHNICAL_ISSUE },
            highest = all.maxOfOrNull { it.severity },
            days = daysOf(all, from, to),
            rows = arranged(views, order, ascending).drop(at * take).take(take),
        )
    }

    /** The flags one session carries, oldest first, for its page. Administrators. */
    @QueryMapping
    @Transactional(readOnly = true)
    fun sessionFlags(@Argument sessionId: Long): List<SessionFlagView> {
        access.requireAdmin()
        val session = sessions.findByIdOrNull(sessionId)
        val place = session?.workspaceId?.let { workspaces.findByIdOrNull(it)?.name }
        return flags.findBySessionIdOrderByFlaggedAtAscIdAsc(sessionId).map { view(it, session?.let { one -> one.title ?: one.sessionKey }, place) }
    }

    /** An administrator clearing a flag, from the report or the session's page. */
    @MutationMapping
    @Transactional
    fun clearSessionFlag(@Argument id: Long): SessionFlagView {
        access.requireAdmin()
        val flag = flags.findByIdOrNull(id) ?: throw SessionFlagNotFoundException(id)
        if (flag.clearedAt != null) throw SessionFlagAlreadyClearedException(id)
        flag.clearedAt = OffsetDateTime.now()
        flag.clearedBy = SecurityContextHolder.getContext().authentication?.name ?: "system"
        flags.save(flag)
        audit.record(null, WorkspaceAuditCategory.AGENT, "Flag #$id on session #${flag.sessionId} cleared")
        val session = sessions.findByIdOrNull(flag.sessionId)
        return view(flag, session?.let { it.title ?: it.sessionKey }, workspaces.findByIdOrNull(flag.workspaceId)?.name)
    }

    private fun view(flag: SessionFlag, sessionTitle: String?, workspaceName: String?) = SessionFlagView(
        id = requireNotNull(flag.id),
        at = flag.flaggedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        workspaceId = flag.workspaceId,
        workspaceName = workspaceName,
        sessionId = flag.sessionId,
        sessionTitle = sessionTitle,
        agentName = flag.agentName,
        type = flag.type,
        severity = flag.severity,
        note = flag.note,
        state = flag.state,
        clearedAt = flag.clearedAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        clearedBy = flag.clearedBy,
        flaggedAt = flag.flaggedAt,
    )

    /** The first day counted to the day after the last, in this server's zone; the last 30 days where left out. */
    private fun periodOf(filter: SessionFlagFilter?): Pair<OffsetDateTime, OffsetDateTime> {
        val zone = ZoneId.systemDefault()
        val last = filter?.to?.let(LocalDate::parse) ?: LocalDate.now(zone)
        val first = filter?.from?.let(LocalDate::parse) ?: last.minusDays(DEFAULT_DAYS - 1)
        return first.atStartOfDay(zone).toOffsetDateTime() to last.plusDays(1).atStartOfDay(zone).toOffsetDateTime()
    }

    /** Every day of the period, a quiet one included: how many of each type, and of each severity. */
    private fun daysOf(all: List<SessionFlag>, from: OffsetDateTime, to: OffsetDateTime): List<SessionFlagDayView> {
        val zone = ZoneId.systemDefault()
        val byDay = all.groupBy { it.flaggedAt.atZoneSameInstant(zone).toLocalDate() }
        val first = from.atZoneSameInstant(zone).toLocalDate()
        val last = to.atZoneSameInstant(zone).toLocalDate().minusDays(1)
        return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.map { day ->
            val held = byDay[day].orEmpty()
            SessionFlagDayView(
                day = day.toString(),
                security = held.count { it.type == SessionFlagType.SECURITY },
                technical = held.count { it.type == SessionFlagType.TECHNICAL_ISSUE },
                severities = (SessionFlagTools.LEAST_SEVERE..SessionFlagTools.MOST_SEVERE).map { level -> held.count { it.severity == level } },
            )
        }.toList()
    }

    /** Every column sorts; the id is the last word, so a page never reorders between two reads. */
    private fun arranged(rows: List<SessionFlagView>, order: SessionFlagOrder?, ascending: Boolean?): List<SessionFlagView> {
        val key: (SessionFlagView) -> Comparable<*>? = when (order ?: SessionFlagOrder.FLAGGED_AT) {
            SessionFlagOrder.FLAGGED_AT -> { one -> one.flaggedAt }
            SessionFlagOrder.WORKSPACE -> { one -> one.workspaceName?.lowercase() }
            SessionFlagOrder.SESSION -> { one -> one.sessionId }
            SessionFlagOrder.AGENT -> { one -> one.agentName.lowercase() }
            SessionFlagOrder.TYPE -> { one -> one.type.name }
            SessionFlagOrder.SEVERITY -> { one -> one.severity }
            SessionFlagOrder.NOTE -> { one -> one.note.lowercase() }
            SessionFlagOrder.STATE -> { one -> one.state.name }
            SessionFlagOrder.CLEARED_BY -> { one -> one.clearedBy?.lowercase() }
        }
        @Suppress("UNCHECKED_CAST")
        val comparator = compareBy<SessionFlagView, Comparable<Any>?>(nullsFirst()) { key(it) as Comparable<Any>? }
        // Words read A to Z first; times, numbers and severities biggest first.
        val up = ascending ?: (order in TEXT_ORDERS)
        val sorted = if (up) comparator.thenBy { it.id } else comparator.reversed().thenByDescending { it.id }
        return rows.sortedWith(sorted)
    }

    private companion object {
        const val DEFAULT_PAGE = 25
        const val MAX_PAGE = 200
        const val DEFAULT_DAYS = 30L
        val TEXT_ORDERS = setOf(SessionFlagOrder.WORKSPACE, SessionFlagOrder.AGENT, SessionFlagOrder.TYPE, SessionFlagOrder.NOTE, SessionFlagOrder.STATE, SessionFlagOrder.CLEARED_BY)
    }
}

/** What the flags report may be ordered by: every column it draws. */
enum class SessionFlagOrder { FLAGGED_AT, WORKSPACE, SESSION, AGENT, TYPE, SEVERITY, NOTE, STATE, CLEARED_BY }

data class SessionFlagFilter(
    /** The first day counted, YYYY-MM-DD; the last 30 days where left out. */
    val from: String? = null,
    /** The last day counted, inclusive; today where left out. */
    val to: String? = null,
    val workspaceId: Long? = null,
    val type: SessionFlagType? = null,
    val severityFrom: Int? = null,
    val severityTo: Int? = null,
    val state: SessionFlagState? = null,
    /** Over the notes. */
    val search: String? = null,
)

data class SessionFlagReportView(
    val total: Int,
    val open: Int,
    val security: Int,
    val technical: Int,
    /** The highest severity among them; null where there are none. */
    val highest: Int?,
    val days: List<SessionFlagDayView>,
    val rows: List<SessionFlagView>,
)

/** One day of the flags chart: how many of each type, and how many at each severity from 0 to 5. */
data class SessionFlagDayView(val day: String, val security: Int, val technical: Int, val severities: List<Int>)

data class SessionFlagView(
    val id: Long,
    val at: String,
    val workspaceId: Long,
    val workspaceName: String?,
    val sessionId: Long,
    val sessionTitle: String?,
    val agentName: String,
    val type: SessionFlagType,
    val severity: Int,
    val note: String,
    val state: SessionFlagState,
    val clearedAt: String?,
    val clearedBy: String?,
    /** For ordering; not in the schema. */
    val flaggedAt: OffsetDateTime,
)

/** A flag asked for by an id that names none. */
class SessionFlagNotFoundException(val id: Long) : RuntimeException("No flag with id $id"), Refusal {
    override val arguments: Map<String, Any?> get() = mapOf("id" to id)
}

/** A flag cleared already, asked to be cleared again. */
class SessionFlagAlreadyClearedException(val id: Long) : RuntimeException("Flag $id is already cleared"), Refusal {
    override val arguments: Map<String, Any?> get() = mapOf("id" to id)
}
