package io.mszymanski.orknux.server.logging

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.graphql.refused
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller

/**
 * Admin -> Settings -> Logging. Issue #591.
 *
 * Administrators only, reading as well as writing: which loggers are turned up
 * says what somebody is chasing, and that is nobody else's business.
 */
@Controller
class LogLevelAPI(
    private val levels: LogLevels,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    @QueryMapping
    fun logLevels(): LogLevelsView {
        access.requireAdmin()
        return levels.view()
    }

    @MutationMapping
    fun setLogLevel(@Argument name: String, @Argument level: String): LogLevelsView {
        access.requireAdmin()
        val (logger, token) = levels.set(name, level, currentUser())
        val said = if (token == LogLevels.INHERIT) "inherit" else token
        auditRecorder.record(null, WorkspaceAuditCategory.WORKSPACE, "Log level of $logger set to $said")
        return levels.view()
    }

    @MutationMapping
    fun clearLogLevel(@Argument name: String): LogLevelsView {
        access.requireAdmin()
        val logger = levels.clear(name)
        auditRecorder.record(null, WorkspaceAuditCategory.WORKSPACE, "Log level of $logger back to the configuration's")
        return levels.view()
    }

    @MutationMapping
    fun resetLogLevels(): LogLevelsView {
        access.requireAdmin()
        levels.reset()
        auditRecorder.record(null, WorkspaceAuditCategory.WORKSPACE, "Log levels reset to the configuration's defaults")
        return levels.view()
    }

    @MutationMapping
    fun setLogRootRevertMinutes(@Argument minutes: Int): LogLevelsView {
        access.requireAdmin()
        levels.setRootRevertMinutes(minutes, currentUser())
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            "A root log level below INFO goes back after $minutes minutes",
        )
        return levels.view()
    }

    @MutationMapping
    fun setLogFollowSeconds(@Argument seconds: Int): LogLevelsView {
        access.requireAdmin()
        levels.setFollowSeconds(seconds, currentUser())
        auditRecorder.record(null, WorkspaceAuditCategory.WORKSPACE, "Servers re-read the log levels every $seconds seconds")
        return levels.view()
    }

    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"
}

@Component
class LogLevelExceptionResolver : DataFetcherExceptionResolverAdapter() {
    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? =
        when (exception) {
            is LogLevelUnknownException,
            is LoggerNameInvalidException,
            is RootLogLevelInheritException,
            is LogRootRevertOutOfRangeException,
            is LogFollowOutOfRangeException,
            -> refused(exception, ErrorType.BAD_REQUEST, environment)

            else -> null
        }
}
