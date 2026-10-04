package io.mszymanski.orknux.server.watcher

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.graphql.refused
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
 * The Watchers page, and Admin -> Settings -> Watchers. Issue #606.
 *
 * The page is anybody's who can see the workspace, reading and stopping alike:
 * stopping a watcher only ever ends something early, and the agent is told it
 * was stopped. The limits are an administrator's, audited as AGENT beside the
 * other limits on what an agent may do.
 */
@Controller
class WatcherAPI(
    private val watchers: WatcherRepository,
    private val service: WatcherService,
    private val settings: WatcherSettings,
    private val sessions: LlmSessionRepository,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    @QueryMapping
    fun watchers(
        @Argument workspaceId: Long,
        @Argument finished: Boolean?,
        @Argument page: Int?,
        @Argument size: Int?,
    ): WatcherPageView {
        access.requireVisible(workspaceId)
        val asked = PageRequest.of((page ?: 0).coerceAtLeast(0), (size ?: DEFAULT_PAGE).coerceIn(1, LARGEST_PAGE))
        val found = if (finished == true) {
            watchers.findByWorkspaceIdAndStatusNotOrderByFinishedAtDescIdDesc(workspaceId, WatcherStatus.ACTIVE, asked)
        } else {
            watchers.findByWorkspaceIdAndStatusOrderByCreatedAtDescIdDesc(workspaceId, WatcherStatus.ACTIVE, asked)
        }
        val titles = sessions.findAllById(found.content.map { it.sessionId }.distinct())
            .associate { requireNotNull(it.id) to it.title }
        return WatcherPageView(
            content = found.content.map { WatcherView.of(it, titles[it.sessionId]) },
            page = found.number,
            size = found.size,
            totalElements = found.totalElements.toInt(),
            totalPages = found.totalPages,
        )
    }

    @MutationMapping
    fun stopWatcher(@Argument id: Long): WatcherView {
        val watcher = watchers.findByIdOrNull(id) ?: throw WatcherNotFoundException(id)
        access.requireVisible(watcher.workspaceId)
        val stopped = service.stop(id, currentUser())
        auditRecorder.record(
            stopped.workspaceId,
            WorkspaceAuditCategory.AGENT,
            "Watcher #$id on ${stopped.tool} stopped",
        )
        val title = sessions.findByIdOrNull(stopped.sessionId)?.title
        return WatcherView.of(stopped, title)
    }

    @QueryMapping
    fun watcherSettings(): WatcherSettingsView {
        access.requireAdmin()
        return view()
    }

    @MutationMapping
    fun setWatcherMaxSeconds(@Argument seconds: Int): WatcherSettingsView {
        access.requireAdmin()
        settings.setMaxSeconds(seconds, currentUser())
        auditRecorder.record(null, WorkspaceAuditCategory.AGENT, "Longest a watcher may run set to $seconds seconds")
        return view()
    }

    @MutationMapping
    fun setWatcherMinIntervalSeconds(@Argument seconds: Int): WatcherSettingsView {
        access.requireAdmin()
        settings.setMinIntervalSeconds(seconds, currentUser())
        auditRecorder.record(null, WorkspaceAuditCategory.AGENT, "Shortest watcher interval set to $seconds seconds")
        return view()
    }

    @MutationMapping
    fun setWatcherMaxPerAgent(@Argument count: Int): WatcherSettingsView {
        access.requireAdmin()
        settings.setMaxPerAgent(count, currentUser())
        auditRecorder.record(null, WorkspaceAuditCategory.AGENT, "Watchers per agent set to $count")
        return view()
    }

    private fun view() = WatcherSettingsView(
        maxSeconds = settings.maxSeconds(),
        maxSecondsConfigured = settings.maxSecondsConfigured(),
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

data class WatcherPageView(
    val content: List<WatcherView>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
)

/** A watcher as the page reads it; times as ISO strings, the schema's way. */
data class WatcherView(
    val id: Long,
    val sessionId: Long,
    val sessionTitle: String?,
    val agentName: String,
    val tool: String,
    val arguments: String,
    val conditionKind: WatcherConditionKind,
    val condition: String,
    val toolResultPath: String,
    val intervalSeconds: Int,
    val timeoutSeconds: Int,
    val note: String?,
    val status: WatcherStatus,
    val checks: Int,
    val matched: String?,
    val outcome: String?,
    val createdAt: String,
    val expiresAt: String,
    val nextCheckAt: String,
    val lastCheckedAt: String?,
    val lastResult: String?,
    val finishedAt: String?,
) {
    companion object {
        fun of(watcher: Watcher, sessionTitle: String?) = WatcherView(
            id = requireNotNull(watcher.id),
            sessionId = watcher.sessionId,
            sessionTitle = sessionTitle,
            agentName = watcher.agentName,
            tool = watcher.tool,
            arguments = watcher.arguments,
            conditionKind = watcher.conditionKind,
            condition = watcher.condition,
            toolResultPath = watcher.toolResultPath ?: WatcherCondition.WHOLE,
            intervalSeconds = watcher.intervalSeconds,
            timeoutSeconds = watcher.timeoutSeconds,
            note = watcher.note,
            status = watcher.status,
            checks = watcher.checks,
            matched = watcher.matched,
            outcome = watcher.outcome,
            createdAt = watcher.createdAt.toString(),
            expiresAt = watcher.expiresAt.toString(),
            nextCheckAt = watcher.nextCheckAt.toString(),
            lastCheckedAt = watcher.lastCheckedAt?.toString(),
            lastResult = watcher.lastResult,
            finishedAt = watcher.finishedAt?.toString(),
        )
    }
}

data class WatcherSettingsView(
    val maxSeconds: Int,
    val maxSecondsConfigured: Int,
    val minIntervalSeconds: Int,
    val minIntervalSecondsConfigured: Int,
    val maxPerAgent: Int,
    val maxPerAgentConfigured: Int,
)

@Component
class WatcherExceptionResolver : DataFetcherExceptionResolverAdapter() {
    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? =
        when (exception) {
            is WatcherNotFoundException -> refused(exception, ErrorType.NOT_FOUND, environment)

            is WatcherNotActiveException,
            is WatcherMaxSecondsOutOfRangeException,
            is WatcherMinIntervalOutOfRangeException,
            is WatcherMaxPerAgentOutOfRangeException,
            -> refused(exception, ErrorType.BAD_REQUEST, environment)

            else -> null
        }
}
