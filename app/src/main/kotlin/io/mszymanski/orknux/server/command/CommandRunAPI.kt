package io.mszymanski.orknux.server.command

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.graphql.refused
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
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
import java.time.format.DateTimeFormatter

/**
 * The Run button on a chat answer's shell blocks: a command, run on the
 * machine this server is on.
 *
 * Administrators only, and only while the installation's switch is on - off
 * for a server and its image, on in the desktop app, where the machine is the
 * person's own. A person who may run anything here may already change what
 * the server runs, so the gate is the role and the switch, not a second
 * question. Elevation is asked of the operating system and never of Orknux:
 * UAC on Windows, pkexec's polkit dialog on Linux.
 *
 * Every run is audited twice: as it starts, with the person's name, and as it
 * ends, with how - the command through [io.mszymanski.orknux.server.workspace.AuditRedaction]
 * both times.
 */
@Controller
class CommandRunAPI(
    private val settings: InstallationSettings,
    private val runner: CommandRunner,
    private val access: WorkspaceAccess,
    private val audit: WorkspaceAuditRecorder,
    private val tuning: Tuning,
) {

    @QueryMapping
    fun commandRunsEnabled(): CommandRunSwitchView {
        access.requireAdmin()
        return switchView()
    }

    @MutationMapping
    fun setCommandRuns(@Argument enabled: Boolean): CommandRunSwitchView {
        access.requireAdmin()
        settings.setChatRunCommands(enabled, currentUser())
        audit.record(
            null,
            WorkspaceAuditCategory.SHELL,
            if (enabled) "Running commands from the chat turned on" else "Running commands from the chat turned off",
        )
        return switchView()
    }

    /**
     * What the chat may draw for this person: nothing unless they administer
     * and the switch is on, so the button is never offered to be refused.
     */
    @QueryMapping
    fun commandRunsOffered(): CommandRunOfferView {
        access.requireSignedIn()
        if (!access.isAdmin() || !settings.chatRunCommands()) return CommandRunOfferView(emptyList(), false)
        return CommandRunOfferView(runner.shells().flatMap { it.languages }.sorted(), runner.canElevate())
    }

    @MutationMapping
    fun runCommand(@Argument command: String, @Argument language: String, @Argument elevated: Boolean?): CommandRunView {
        access.requireAdmin()
        if (!settings.chatRunCommands()) throw CommandRunsOffException()
        if (command.isBlank()) throw CommandEmptyException()
        val shell = CommandShell.of(language)?.takeIf { it in runner.shells() } ?: throw CommandLanguageUnrunnableException(language)
        val asAdmin = elevated == true
        if (asAdmin && !runner.canElevate()) throw CommandElevationUnavailableException()

        val by = currentUser()
        val run = runner.start(command, language, shell, asAdmin, by)
        audit.record(null, WorkspaceAuditCategory.SHELL, CommandRunner.auditLine("Command run from the chat", run))
        return view(run, 0)
    }

    @QueryMapping
    fun commandRun(@Argument id: String, @Argument from: Int?): CommandRunView {
        access.requireAdmin()
        return view(own(id), from ?: 0)
    }

    @MutationMapping
    fun stopCommandRun(@Argument id: String): CommandRunView {
        access.requireAdmin()
        val run = own(id)
        runner.stop(run)
        return view(run, run.outputLength())
    }

    /** A run of somebody else's is answered as one that does not exist. */
    private fun own(id: String): CommandRun =
        runner.find(id)?.takeIf { it.by == currentUser() } ?: throw CommandRunNotFoundException()

    private fun view(run: CommandRun, from: Int): CommandRunView {
        // The state first: output read after a final state is read is the whole of it.
        val state = run.state
        val (output, next) = run.outputFrom(from)
        return CommandRunView(
            id = run.id,
            state = state,
            output = output,
            next = next,
            exitCode = if (state == CommandRunState.RUNNING) null else run.exitCode,
            message = run.message,
            truncated = run.truncated,
            elevated = run.elevated,
            timeoutSeconds = tuning[Tunable.COMMAND_TIMEOUT_SECONDS],
        )
    }

    private fun switchView(): CommandRunSwitchView {
        val changed = settings.chatRunCommandsChanged()
        return CommandRunSwitchView(
            enabled = settings.chatRunCommands(),
            configured = settings.chatRunCommandsConfigured(),
            changedAt = changed?.lastModifiedAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            changedBy = changed?.lastModifiedBy?.takeIf { it.isNotBlank() },
        )
    }

    private fun currentUser(): String = SecurityContextHolder.getContext().authentication?.name ?: "system"
}

data class CommandRunSwitchView(val enabled: Boolean, val configured: Boolean, val changedAt: String?, val changedBy: String?)

data class CommandRunOfferView(val languages: List<String>, val elevation: Boolean)

data class CommandRunView(
    val id: String,
    val state: CommandRunState,
    val output: String,
    val next: Int,
    val exitCode: Int?,
    val message: String?,
    val truncated: Boolean,
    val elevated: Boolean,
    val timeoutSeconds: Int,
)

class CommandRunsOffException : RuntimeException(
    "Running commands from the chat is switched off in Admin > Settings.",
)

class CommandEmptyException : RuntimeException("There is no command to run.")

class CommandLanguageUnrunnableException(val language: String) : RuntimeException(
    "A block tagged \"$language\" cannot be run on this server.",
), Refusal {
    override val arguments get() = mapOf("language" to language)
}

class CommandElevationUnavailableException : RuntimeException(
    "This server's operating system offers no way to ask for administrator rights here.",
)

class CommandRunNotFoundException : RuntimeException("That command run is not here any more.")

@Component
class CommandRunExceptionResolver : DataFetcherExceptionResolverAdapter() {
    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? =
        when (exception) {
            is CommandRunsOffException -> refused(exception, ErrorType.FORBIDDEN, environment)
            is CommandEmptyException -> refused(exception, ErrorType.BAD_REQUEST, environment)
            is CommandLanguageUnrunnableException -> refused(exception, ErrorType.BAD_REQUEST, environment)
            is CommandElevationUnavailableException -> refused(exception, ErrorType.BAD_REQUEST, environment)
            is CommandRunNotFoundException -> refused(exception, ErrorType.NOT_FOUND, environment)
            else -> null
        }
}
