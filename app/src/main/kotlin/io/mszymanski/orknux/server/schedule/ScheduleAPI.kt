package io.mszymanski.orknux.server.schedule

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.graphql.refused
import io.mszymanski.orknux.server.llm.LlmSessionNotFoundException
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller

/**
 * The Schedules page, a session's Schedules panel, and Admin -> Settings ->
 * Schedules.
 *
 * The page is anybody's who can see the workspace, reading and stopping alike,
 * as the Watchers page is: stopping a schedule only ever ends something early,
 * and the agent is told it was stopped. The limits are an administrator's,
 * audited as AGENT beside the other limits on what an agent may do.
 */
@Controller
class ScheduleAPI(
    private val schedules: AgentScheduleRepository,
    private val service: ScheduleService,
    private val settings: ScheduleSettings,
    private val sessions: LlmSessionRepository,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    @QueryMapping
    fun schedules(
        @Argument workspaceId: Long,
        @Argument ended: Boolean?,
        @Argument page: Int?,
        @Argument size: Int?,
    ): SchedulePageView {
        access.requireVisible(workspaceId)
        val asked = PageRequest.of((page ?: 0).coerceAtLeast(0), (size ?: DEFAULT_PAGE).coerceIn(1, LARGEST_PAGE))
        val found = if (ended == true) {
            schedules.findByWorkspaceIdAndStatusNotOrderByFinishedAtDescIdDesc(workspaceId, ScheduleStatus.ACTIVE, asked)
        } else {
            schedules.findByWorkspaceIdAndStatusOrderByCreatedAtDescIdDesc(workspaceId, ScheduleStatus.ACTIVE, asked)
        }
        val titles = sessions.findAllById(found.content.map { it.sessionId }.distinct())
            .associate { requireNotNull(it.id) to it.title }
        return SchedulePageView(
            content = found.content.map { ScheduleView.of(it, titles[it.sessionId]) },
            page = found.number,
            size = found.size,
            totalElements = found.totalElements.toInt(),
            totalPages = found.totalPages,
        )
    }

    /** The schedules set in one session, every state, newest first; a hundred at most. */
    @QueryMapping
    fun sessionSchedules(@Argument sessionId: Long): List<ScheduleView> {
        val session = sessions.findByIdOrNull(sessionId) ?: throw LlmSessionNotFoundException(sessionId)
        access.requireVisible(session.workspaceId)
        return schedules.findTop100BySessionIdOrderByCreatedAtDescIdDesc(sessionId).map { ScheduleView.of(it, session.title) }
    }

    @MutationMapping
    fun stopSchedule(@Argument id: Long): ScheduleView {
        val schedule = schedules.findByIdOrNull(id) ?: throw ScheduleNotFoundException(id)
        access.requireVisible(schedule.workspaceId)
        val stopped = service.stop(id, currentUser())
        auditRecorder.record(stopped.workspaceId, WorkspaceAuditCategory.AGENT, "Schedule #$id of ${stopped.agentName} stopped")
        return ScheduleView.of(stopped, sessions.findByIdOrNull(stopped.sessionId)?.title)
    }

    @QueryMapping
    fun scheduleSettings(): ScheduleSettingsView {
        access.requireAdmin()
        return view()
    }

    @MutationMapping
    fun setScheduleMinIntervalSeconds(@Argument seconds: Int): ScheduleSettingsView {
        access.requireAdmin()
        settings.setMinIntervalSeconds(seconds, currentUser())
        auditRecorder.record(null, WorkspaceAuditCategory.AGENT, "Shortest schedule interval set to $seconds seconds")
        return view()
    }

    @MutationMapping
    fun setScheduleMaxPerAgent(@Argument count: Int): ScheduleSettingsView {
        access.requireAdmin()
        settings.setMaxPerAgent(count, currentUser())
        auditRecorder.record(null, WorkspaceAuditCategory.AGENT, "Schedules per agent set to $count")
        return view()
    }

    private fun view() = ScheduleSettingsView(
        minIntervalSeconds = settings.minIntervalSeconds(),
        minIntervalSecondsConfigured = settings.minIntervalSecondsConfigured(),
        maxPerAgent = settings.maxPerAgent(),
        maxPerAgentConfigured = settings.maxPerAgentConfigured(),
    )

    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"

    private companion object {
        const val DEFAULT_PAGE = 50

        /** A page the browser asks for, not a dump of the table. */
        const val LARGEST_PAGE = 200
    }
}

data class SchedulePageView(
    val content: List<ScheduleView>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
)

/** A schedule as the page reads it; times as ISO strings, the schema's way. */
data class ScheduleView(
    val id: Long,
    val sessionId: Long,
    val sessionTitle: String?,
    val agentName: String,
    val note: String,
    val everySeconds: Int?,
    val cron: String?,
    val timezone: String?,
    val status: ScheduleStatus,
    val runs: Int,
    val times: Int?,
    val until: String?,
    val createdAt: String,
    val nextRunAt: String?,
    val lastRunAt: String?,
    val finishedAt: String?,
    val outcome: String?,
) {
    companion object {
        fun of(schedule: AgentSchedule, sessionTitle: String?) = ScheduleView(
            id = requireNotNull(schedule.id),
            sessionId = schedule.sessionId,
            sessionTitle = sessionTitle,
            agentName = schedule.agentName,
            note = schedule.note,
            everySeconds = schedule.everySeconds,
            cron = schedule.cron,
            timezone = schedule.timezone,
            status = schedule.status,
            runs = schedule.runs,
            times = schedule.maxRuns,
            until = schedule.untilAt?.toString(),
            createdAt = schedule.createdAt.toString(),
            nextRunAt = schedule.nextRunAt?.toString(),
            lastRunAt = schedule.lastRunAt?.toString(),
            finishedAt = schedule.finishedAt?.toString(),
            outcome = schedule.outcome,
        )
    }
}

data class ScheduleSettingsView(
    val minIntervalSeconds: Int,
    val minIntervalSecondsConfigured: Int,
    val maxPerAgent: Int,
    val maxPerAgentConfigured: Int,
)

@Component
class ScheduleExceptionResolver : DataFetcherExceptionResolverAdapter() {
    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? =
        when (exception) {
            is ScheduleNotFoundException -> refused(exception, ErrorType.NOT_FOUND, environment)

            is ScheduleNotActiveException,
            is ScheduleMinIntervalOutOfRangeException,
            is ScheduleMaxPerAgentOutOfRangeException,
            -> refused(exception, ErrorType.BAD_REQUEST, environment)

            else -> null
        }
}
