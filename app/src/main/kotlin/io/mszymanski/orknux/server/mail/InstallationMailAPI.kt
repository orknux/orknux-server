package io.mszymanski.orknux.server.mail

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.connector.connection.MailDelivery
import io.mszymanski.orknux.connector.connection.MailMessage
import io.mszymanski.orknux.connector.connection.MailSecurity
import io.mszymanski.orknux.connector.connection.defaultPort
import io.mszymanski.orknux.server.graphql.refused
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.EmailInvalidException
import io.mszymanski.orknux.server.user.UserAPI
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
 * Admin -> Settings -> Mail: the server the installation sends its own mail
 * through - password resets, issue news - and never a workspace's connection.
 *
 * What is in force is shown whichever half it comes from, so an administrator
 * can see what the environment set before deciding to override it. No answer
 * here carries a password, stored or configured; the page is told only whether
 * one is set.
 */
@Controller
class InstallationMailAPI(
    private val mail: InstallationMail,
    private val settings: InstallationMailSettings,
    private val environment: InstallationMailProperties,
    private val users: AppUserRepository,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    @QueryMapping
    fun installationMail(): InstallationMailView {
        access.requireAdmin()
        return view()
    }

    @MutationMapping
    fun setInstallationMail(@Argument input: InstallationMailInput): InstallationMailView {
        access.requireAdmin()
        val held = settings.set(input, caller())
        val port = held.port ?: held.security.defaultPort
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.INTEGRATION,
            "Installation mail server set to ${held.host}:$port",
        )
        return view()
    }

    /** "Use environment settings": forgets the stored server. Audited only when there was one to forget. */
    @MutationMapping
    fun clearInstallationMail(): InstallationMailView {
        access.requireAdmin()
        if (settings.clear()) {
            auditRecorder.record(null, WorkspaceAuditCategory.INTEGRATION, "Installation mail server cleared")
        }
        return view()
    }

    /**
     * Sends one mail through the server in force, to [to] or to the caller's own
     * address. What the relay said comes back either way, because a test that
     * fails is an answer the administrator asked for, not an error.
     */
    @MutationMapping
    fun sendInstallationTestMail(@Argument to: String?): InstallationMailTestView {
        access.requireAdmin()
        val name = caller()
        val address = to?.trim()?.ifEmpty { null }
            ?: users.findByUsername(name)?.email?.trim()?.ifEmpty { null }
            ?: throw InstallationMailRecipientMissingException()
        if (address.length > UserAPI.LONGEST_EMAIL || !UserAPI.EMAIL.matches(address)) {
            throw EmailInvalidException(address)
        }

        val delivery = mail.send(
            MailMessage(
                to = listOf(address),
                subject = "Orknux test mail",
                body = "This is a test mail from Orknux, sent by $name from Admin Settings -> Mail. " +
                    "It arrived, so the installation's mail server works.",
            ),
        )
        val result = when (delivery) {
            is MailDelivery.Sent -> InstallationMailTestView(sent = true, to = address, message = "Sent to $address")
            is MailDelivery.NotPossible -> InstallationMailTestView(sent = false, to = address, message = delivery.reason)
            is MailDelivery.Refused -> InstallationMailTestView(sent = false, to = address, message = delivery.reason)
        }
        val said = if (result.sent) "Test mail sent to $address" else "Test mail to $address refused: ${result.message}"
        auditRecorder.record(null, WorkspaceAuditCategory.INTEGRATION, said.take(AUDIT_LENGTH))
        return result
    }

    private fun view(): InstallationMailView {
        settings.stored()?.let { stored ->
            return InstallationMailView(
                source = InstallationMailSource.STORED,
                host = stored.host,
                port = stored.port,
                username = stored.username,
                from = stored.from,
                security = stored.security,
                passwordSet = stored.username != null && !stored.password.isNullOrBlank(),
                updatedBy = stored.lastModifiedBy,
                updatedAt = stored.lastModifiedAt.toString(),
            )
        }
        if (mail.environment() == null) return InstallationMailView(source = InstallationMailSource.NONE)
        val username = environment.username.trim().ifEmpty { null }
        return InstallationMailView(
            source = InstallationMailSource.ENVIRONMENT,
            host = environment.host.trim(),
            port = environment.port,
            username = username,
            from = environment.from.trim(),
            security = environment.security,
            passwordSet = username != null && environment.password.isNotBlank(),
        )
    }

    private fun caller(): String = SecurityContextHolder.getContext().authentication?.name ?: "system"

    private companion object {
        /** The audit table's message column. */
        const val AUDIT_LENGTH = 500
    }
}

/**
 * The server in force and where it comes from. Every field but [source] and
 * [passwordSet] is null for [InstallationMailSource.NONE]; [updatedBy] and
 * [updatedAt] are only ever set for a stored one.
 */
data class InstallationMailView(
    val source: InstallationMailSource,
    val host: String? = null,
    /** Null where the security's usual port is used. */
    val port: Int? = null,
    val username: String? = null,
    val from: String? = null,
    val security: MailSecurity? = null,
    /** Whether there is a password; never what it is. */
    val passwordSet: Boolean = false,
    val updatedBy: String? = null,
    val updatedAt: String? = null,
)

/** What a test mail came to. */
data class InstallationMailTestView(
    val sent: Boolean,
    val to: String,
    /** "Sent to ..." or the relay's own words. */
    val message: String,
)

@Component
class InstallationMailExceptionResolver : DataFetcherExceptionResolverAdapter() {
    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? =
        when (exception) {
            is InstallationMailIncompleteException,
            is InstallationMailHostInvalidException,
            is InstallationMailPortInvalidException,
            is InstallationMailRecipientMissingException,
            -> refused(exception, ErrorType.BAD_REQUEST, environment)

            else -> null
        }
}
