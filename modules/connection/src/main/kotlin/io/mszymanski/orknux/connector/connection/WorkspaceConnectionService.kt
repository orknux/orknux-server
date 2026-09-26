package io.mszymanski.orknux.connector.connection

import io.mszymanski.orknux.connector.CredentialReader
import io.mszymanski.orknux.connector.security.HeldSecret
import io.mszymanski.orknux.connector.security.SecretReferences
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * The connections one workspace holds: the admin defaults it was provisioned
 * with plus any it added itself. Credentials are always the workspace's own and are
 * never returned by a listing; [revealWorkspaceConnectionSecret] and
 * [revealWorkspaceConnectionAppToken] hand them over once and log that they did.
 *
 * Workspace visibility is orknux-server's to enforce — it knows the directory groups
 * and the connector does not — so a `workspaceId` that arrives here is already
 * allowed.
 */
@Service
class WorkspaceConnectionService(
    private val workspaceConnections: WorkspaceConnectionRepository,
    private val probe: ConnectionProbe,
    private val mail: OutgoingMail,
    private val events: ApplicationEventPublisher,
    /** The one place a stored credential is read; see [ConnectionCredentials]. */
    private val credentials: ConnectionCredentials,
    /**
     * The rule a secret field follows when it may keep its own value or read a
     * workspace one. Shared, because a connection has two such fields and they
     * answer separately - see [SecretReferences].
     */
    private val references: SecretReferences,
) {

    fun workspaceConnections(workspaceId: Long): List<WorkspaceConnectionView> =
        workspaceConnections.findByWorkspaceId(workspaceId, Sort.by("name")).map(::view)

    fun workspaceConnection(id: Long): WorkspaceConnectionView? =
        workspaceConnections.findByIdOrNull(id)?.let(::view)

    /**
     * The connections in this workspace reading [variableId].
     *
     * What `VariableAPI` asks before it removes a variable or takes its secrecy
     * away. A [CredentialReader] rather than the row: the answer is read by
     * somebody, and a connection row is a credential holder this has no business
     * handing out. Either credential counts, and a connection reading it with both
     * is named once — deduplicated by id now rather than by name, which is the
     * same answer and a better question.
     */
    fun connectionsReading(workspaceId: Long, variableId: Long): List<CredentialReader> = (
        workspaceConnections.findByWorkspaceIdAndSecretVariableId(workspaceId, variableId) +
            workspaceConnections.findByWorkspaceIdAndAppTokenVariableId(workspaceId, variableId) +
            workspaceConnections.findByWorkspaceIdAndUserTokenVariableId(workspaceId, variableId)
        ).map { CredentialReader(requireNotNull(it.id), it.name) }.distinctBy { it.id }.sortedBy { it.name }

    @Transactional
    fun createWorkspaceConnection(input: CreateWorkspaceConnectionInput): WorkspaceConnectionView {
        val name = input.name.trim()
        val slack = input.type == ConnectionType.SLACK
        // A Slack connection has one endpoint and one way of authenticating, so
        // neither is asked for: the form that asks has only one answer to offer
        // and one more field to get wrong. Filled in here rather than in the
        // caller so that every caller gets it.
        val url = if (slack) SLACK_API_URL else input.url.orEmpty().trim()
        if (name.isEmpty()) throw ConnectionNameInvalidException()
        if (url.isEmpty()) throw ConnectionUrlInvalidException()
        // A plugin's kind of host is an HTTP endpoint with a label over it; on
        // any other type the label would be a lie about the wire. Issue #363.
        val pluginType = input.pluginType?.trim()?.ifEmpty { null }
        if (pluginType != null && input.type != ConnectionType.HTTP) {
            throw ConnectionPluginTypeInvalidException("A plugin's kind of connection is an HTTP connection")
        }
        if (workspaceConnections.findByWorkspaceIdAndName(input.workspaceId, name) != null) {
            throw ConnectionNameTakenException(name)
        }

        // Each credential asks its own question, so each is bound on its own.
        val ownSecret = input.secret?.trim()?.ifEmpty { null }
        val secretVariable = references.bind(input.workspaceId, input.secretVariableId, ownSecret)
        val ownAppToken = input.appToken?.trim()?.ifEmpty { null }
        val appTokenVariable = references.bind(input.workspaceId, input.appTokenVariableId, ownAppToken)
        val ownUserToken = input.userToken?.trim()?.ifEmpty { null }
        val userTokenVariable = references.bind(input.workspaceId, input.userTokenVariableId, ownUserToken)

        val connection = workspaceConnections.save(
            WorkspaceConnection(
                workspaceId = input.workspaceId,
                name = name,
                type = input.type,
                pluginType = pluginType,
                url = url,
                authType = if (slack) AuthType.BEARER_TOKEN else input.authType ?: AuthType.NONE,
                secret = if (secretVariable == null) ownSecret else null,
                secretVariableId = secretVariable,
                appToken = if (appTokenVariable == null) ownAppToken else null,
                appTokenVariableId = appTokenVariable,
                userToken = if (userTokenVariable == null) ownUserToken else null,
                userTokenVariableId = userTokenVariable,
                smtpPort = input.smtpPort,
                smtpUsername = input.smtpUsername?.trim()?.ifEmpty { null },
                smtpFrom = input.smtpFrom?.trim()?.ifEmpty { null },
                smtpSecurity = input.smtpSecurity ?: MailSecurity.STARTTLS,
                headers = input.headers.orEmpty().toHttpHeaders(),
            ),
        )
        // Checked as soon as the transaction lands, so a connection that was
        // just given a credential does not sit on "Not checked".
        events.publishEvent(WorkspaceConnectionSaved(requireNotNull(connection.id)))
        return view(connection)
    }

    /**
     * Backs the connection settings form. An inherited connection keeps the
     * default's name, type and URL; everything else is the workspace's to set.
     * A null secret leaves the stored credentials alone, an empty one clears them.
     */
    @Transactional
    fun updateWorkspaceConnection(id: Long, input: UpdateWorkspaceConnectionInput): WorkspaceConnectionView {
        val connection = workspaceConnections.findByIdOrNull(id) ?: throw ConnectionNotFoundException(id)

        if (!connection.inherited) {
            input.name?.trim()?.let { name ->
                if (name.isEmpty()) throw ConnectionNameInvalidException()
                if (name != connection.name && workspaceConnections.findByWorkspaceIdAndName(connection.workspaceId, name) != null) {
                    throw ConnectionNameTakenException(name)
                }
                connection.name = name
            }
            input.type?.let { connection.type = it }
            // Null leaves the plugin kind alone; an empty string takes it off. Issue #363.
            input.pluginType?.let { connection.pluginType = it.trim().ifEmpty { null } }
            if (connection.pluginType != null && connection.type != ConnectionType.HTTP) {
                throw ConnectionPluginTypeInvalidException("A plugin's kind of connection is an HTTP connection")
            }
            input.url?.trim()?.let { url ->
                if (url.isEmpty()) throw ConnectionUrlInvalidException()
                connection.url = url
            }
        }

        input.authType?.let { connection.authType = it }
        input.urlOverride?.let { connection.urlOverride = it.trim().ifEmpty { null } }

        /*
         * The two credentials, each answering for itself.
         *
         * A value given keeps a copy here and drops any reference that field
         * held; a variable given reads it and drops any copy; nothing given
         * leaves that field exactly as it was, which is what a masked box sends.
         * An emptied box clears the field whichever kind it was, and is the only
         * way back to a connection with nothing configured.
         *
         * One switch for the whole card cannot say this. A Slack connection
         * keeps a bot token and an app-level token, and "this connection reads a
         * workspace secret" cannot mean one of them without meaning the other -
         * which is #244 in a sentence.
         */
        val ownSecret = input.secret?.trim()
        val secretVariable =
            references.bind(connection.workspaceId, input.secretVariableId, ownSecret?.ifEmpty { null })
        when {
            secretVariable != null -> {
                connection.secretVariableId = secretVariable
                connection.secret = null
            }

            ownSecret != null -> {
                connection.secret = ownSecret.ifEmpty { null }
                connection.secretVariableId = null
            }
        }

        val ownAppToken = input.appToken?.trim()
        val appTokenVariable =
            references.bind(connection.workspaceId, input.appTokenVariableId, ownAppToken?.ifEmpty { null })
        when {
            appTokenVariable != null -> {
                connection.appTokenVariableId = appTokenVariable
                connection.appToken = null
            }

            ownAppToken != null -> {
                connection.appToken = ownAppToken.ifEmpty { null }
                connection.appTokenVariableId = null
            }
        }

        val ownUserToken = input.userToken?.trim()
        val userTokenVariable =
            references.bind(connection.workspaceId, input.userTokenVariableId, ownUserToken?.ifEmpty { null })
        when {
            userTokenVariable != null -> {
                connection.userTokenVariableId = userTokenVariable
                connection.userToken = null
            }

            ownUserToken != null -> {
                connection.userToken = ownUserToken.ifEmpty { null }
                connection.userTokenVariableId = null
            }
        }
        input.smtpPort?.let { connection.smtpPort = it.takeIf { port -> port > 0 } }
        input.smtpUsername?.let { connection.smtpUsername = it.trim().ifEmpty { null } }
        input.smtpFrom?.let { connection.smtpFrom = it.trim().ifEmpty { null } }
        input.smtpSecurity?.let { connection.smtpSecurity = it }
        input.headers?.let { connection.headers = it.toHttpHeaders() }
        // Last, so that neither a URL in the input nor a type changed by this
        // very call can leave a Slack connection pointing anywhere but Slack.
        // Same reason as on create: there is one answer, so nothing asks.
        if (connection.type == ConnectionType.SLACK) {
            connection.url = SLACK_API_URL
            connection.authType = AuthType.BEARER_TOKEN
        }
        // Whatever the last probe found described the old configuration.
        connection.forgetLastCheck()

        events.publishEvent(WorkspaceConnectionSaved(id))
        return view(connection)
    }

    /**
     * Clears the workspace's credentials. A connection the workspace added itself has
     * nothing to fall back on, so it goes; an inherited one returns to the
     * admin default.
     */
    @Transactional
    fun disconnectWorkspaceConnection(id: Long): Boolean {
        val connection = workspaceConnections.findByIdOrNull(id) ?: return false

        if (connection.inherited) {
            connection.secret = null
            connection.secretVariableId = null
            connection.appToken = null
            connection.appTokenVariableId = null
            connection.userToken = null
            connection.userTokenVariableId = null
            // Who the workspace logged in as and sent from is as much its own as
            // the password was, so disconnecting leaves none of it behind.
            connection.smtpUsername = null
            connection.smtpFrom = null
            connection.smtpPort = null
            connection.urlOverride = null
            connection.headers = mutableListOf()
            connection.forgetLastCheck()
        } else {
            workspaceConnections.delete(connection)
        }
        return true
    }

    /**
     * Calls the other end and keeps what came back, so the workspace screen reports an
     * observation rather than the mere presence of a credential.
     */
    @Transactional
    fun testWorkspaceConnection(id: Long): WorkspaceConnectionView {
        val connection = workspaceConnections.findByIdOrNull(id) ?: throw ConnectionNotFoundException(id)

        // A mail server is not asked whether it serves a page. It is asked
        // whether it opens a session and takes the credentials, which is the only
        // question about it that a check can answer without sending anybody a mail.
        val result = if (connection.type == ConnectionType.SMTP) {
            mail.check(connection)
        } else {
            probe.check(credentials.target(connection), connection.type)
        }
        connection.lastCheckStatus = result.outcome
        connection.lastCheckMessage = result.message
        connection.lastCheckedAt = OffsetDateTime.now()
        return view(connection)
    }

    /**
     * Hands the stored credentials back, for the settings form's "Reveal" action.
     *
     * The audit entry belongs in orknux-server, where it can be attributed to
     * the person who asked; this log line is the connector's own record that a
     * credential left it.
     */
    @Transactional
    fun revealWorkspaceConnectionSecret(id: Long): String? {
        val connection = workspaceConnections.findByIdOrNull(id) ?: throw ConnectionNotFoundException(id)
        // A field reading a variable reveals nothing here. Revealing a secret is
        // a deliberate act recorded against the secret - `revealVariable` writes
        // "Variable X revealed" into the audit log - and a second door onto the
        // same value through the connection would be that value read with the
        // wrong thing's name on the record.
        if (connection.secretVariableId != null) return null
        log.info("Credentials for connection {} (workspace {}) revealed", connection.name, connection.workspaceId)
        return connection.secret
    }

    /**
     * The same for the app-level token, which is stored the same way and until
     * now could only be written: a token nobody can read back cannot be compared
     * with the one in Slack, so the only way to answer "is the right one in
     * there" was to type it again.
     *
     * A method of its own rather than a flag on [revealWorkspaceConnectionSecret],
     * because the log line is the point of both and it has to say which of the
     * two credentials left.
     */
    @Transactional
    fun revealWorkspaceConnectionAppToken(id: Long): String? {
        val connection = workspaceConnections.findByIdOrNull(id) ?: throw ConnectionNotFoundException(id)
        if (connection.appTokenVariableId != null) return null
        log.info("App-level token for connection {} (workspace {}) revealed", connection.name, connection.workspaceId)
        return connection.appToken
    }

    /** And for the user token, for the same reason and under the same rule. */
    @Transactional
    fun revealWorkspaceConnectionUserToken(id: Long): String? {
        val connection = workspaceConnections.findByIdOrNull(id) ?: throw ConnectionNotFoundException(id)
        if (connection.userTokenVariableId != null) return null
        log.info("User token for connection {} (workspace {}) revealed", connection.name, connection.workspaceId)
        return connection.userToken
    }

    /**
     * A connection as a screen sees it, with the variables its two credentials
     * read named.
     *
     * The names are read here rather than left to the caller so that a broken
     * reference has somewhere to be reported from. Two lookups per connection at
     * worst, and a workspace has a handful of them.
     */
    private fun view(connection: WorkspaceConnection) = WorkspaceConnectionView(
        connection,
        references.describe(connection.workspaceId, connection.secretVariableId),
        references.describe(connection.workspaceId, connection.appTokenVariableId),
        references.describe(connection.workspaceId, connection.userTokenVariableId),
    )

    private companion object {
        val log = LoggerFactory.getLogger(WorkspaceConnectionService::class.java)
    }
}

data class HttpHeaderInput(val name: String, val value: String)

/** Drops blank names so an empty row in the form does not become a header. */
fun List<HttpHeaderInput>.toHttpHeaders(): MutableList<HttpHeader> = this
    .map { HttpHeader(name = it.name.trim(), value = it.value.trim()) }
    .filter { it.name.isNotEmpty() }
    .toMutableList()

data class CreateWorkspaceConnectionInput(
    val workspaceId: Long,
    val name: String,
    val type: ConnectionType,
    /** Which plugin-declared kind of host this is, as `key/name`; only with [ConnectionType.HTTP]. Issue #363. */
    val pluginType: String? = null,
    /** Required, except for the types that have one address: Slack is filled in. */
    val url: String? = null,
    /** Ignored for Slack, which is always a bearer token. */
    val authType: AuthType? = null,
    val secret: String? = null,
    /** A workspace secret to read the bot token, credential or password from. */
    val secretVariableId: Long? = null,
    /** Slack's app-level token. Optional: with one the connection also listens. */
    val appToken: String? = null,
    /** A workspace secret to read the app-level token from; its own choice. */
    val appTokenVariableId: Long? = null,
    /** Slack's user token (`xoxp-`). Optional: with one the connection can also search. */
    val userToken: String? = null,
    /** A workspace secret to read the user token from; its own choice. */
    val userTokenVariableId: Long? = null,
    /** Where the mail server listens; null takes the port [smtpSecurity] implies. */
    val smtpPort: Int? = null,
    /** Null sends without authenticating; the password arrives as [secret]. */
    val smtpUsername: String? = null,
    val smtpFrom: String? = null,
    val smtpSecurity: MailSecurity? = null,
    val headers: List<HttpHeaderInput>? = null,
)

data class UpdateWorkspaceConnectionInput(
    /** Ignored for inherited connections, which follow the admin default. */
    val name: String? = null,
    val type: ConnectionType? = null,
    /** Null leaves the plugin kind as it is; an empty string clears it. Issue #363. */
    val pluginType: String? = null,
    val url: String? = null,
    val urlOverride: String? = null,
    val authType: AuthType? = null,
    /**
     * Null leaves the stored credential alone; empty clears it, reference and
     * all. A value stores a copy here and drops any reference.
     */
    val secret: String? = null,
    /**
     * Points the bot token, credential or password at a workspace secret,
     * dropping any copy it held. Null leaves it as it is; sending it with
     * [secret] is refused.
     */
    val secretVariableId: Long? = null,
    /** The Slack app-level token, with the same null and empty meaning. */
    val appToken: String? = null,
    /** And the app-level token's own reference, chosen separately from [secretVariableId]. */
    val appTokenVariableId: Long? = null,
    /** The Slack user token, with the same null and empty meaning. */
    val userToken: String? = null,
    /** And the user token's own reference, chosen separately again. */
    val userTokenVariableId: Long? = null,
    val smtpPort: Int? = null,
    val smtpUsername: String? = null,
    val smtpFrom: String? = null,
    val smtpSecurity: MailSecurity? = null,
    val headers: List<HttpHeaderInput>? = null,
)

data class HttpHeaderView(val name: String, val value: String)

data class WorkspaceConnectionView(
    val id: Long,
    val workspaceId: Long,
    val name: String,
    val type: ConnectionType,
    /** The admin default, or the workspace's own URL for a connection it added. */
    val url: String,
    val urlOverride: String?,
    /** Where requests actually go: the override when set, the default otherwise. */
    val effectiveUrl: String,
    val authType: AuthType,
    /** Which plugin-declared kind of host this is, as `key/name`, or null. Issue #363. */
    val pluginType: String?,
    val headers: List<HttpHeaderView>,
    /** True while the workspace follows an admin default. */
    val inherited: Boolean,
    /** Whether the connection holds a credential of its own. False for one reading a variable. */
    val secretSet: Boolean,
    /** The workspace secret the credential is read from, or null when it keeps its own copy. */
    val secretVariableId: Long?,
    /** What that variable is called, and which catalog holds it. */
    val secretVariableName: String?,
    val secretVariableCatalog: String?,
    /**
     * A reference pointing at nothing.
     *
     * Should not happen - a variable a connection reads cannot be deleted - but
     * a restore, a workspace removed out from under it or a hand-edited database
     * can each produce one, and a connection that cannot say why it has no token
     * is precisely the failure this design exists to avoid.
     */
    val secretVariableMissing: Boolean,
    /** Whether an app-level token is stored, which is what makes Slack listen as well as send. */
    val appTokenSet: Boolean,
    /** The app-level token's own answers to the same four questions. */
    val appTokenVariableId: Long?,
    val appTokenVariableName: String?,
    val appTokenVariableCatalog: String?,
    val appTokenVariableMissing: Boolean,
    /** Whether a user token is stored, which is what makes Slack searchable. */
    val userTokenSet: Boolean,
    /** The user token's own answers to the same four questions. */
    val userTokenVariableId: Long?,
    val userTokenVariableName: String?,
    val userTokenVariableCatalog: String?,
    val userTokenVariableMissing: Boolean,
    /** Where a mail connection sends: the port it uses, whoever it logs in as, and who it is from. */
    val smtpPort: Int?,
    val smtpUsername: String?,
    val smtpFrom: String?,
    val smtpSecurity: MailSecurity,
    val status: ConnectionStatus,
    /** What the last probe reported, for the settings screen. */
    val lastCheckMessage: String?,
    /** ISO-8601 offset date-time, or null when no probe has run. */
    val lastCheckedAt: String?,
) {
    /**
     * @param secretHeld the variable the credential reads, when it reads one and
     *   that variable is still there.
     * @param appTokenHeld the same for the app-level token, asked separately
     *   because the two fields choose separately.
     */
    constructor(
        connection: WorkspaceConnection,
        secretHeld: HeldSecret? = null,
        appTokenHeld: HeldSecret? = null,
        userTokenHeld: HeldSecret? = null,
    ) : this(
        id = requireNotNull(connection.id),
        workspaceId = connection.workspaceId,
        name = connection.name,
        type = connection.type,
        url = connection.url,
        urlOverride = connection.urlOverride,
        effectiveUrl = connection.effectiveUrl,
        authType = connection.authType,
        pluginType = connection.pluginType,
        headers = connection.headers.map { HttpHeaderView(it.name, it.value) },
        inherited = connection.inherited,
        secretSet = !connection.secret.isNullOrBlank(),
        secretVariableId = connection.secretVariableId,
        secretVariableName = secretHeld?.name,
        secretVariableCatalog = secretHeld?.catalog,
        secretVariableMissing = connection.secretVariableId != null && secretHeld == null,
        appTokenSet = !connection.appToken.isNullOrBlank(),
        appTokenVariableId = connection.appTokenVariableId,
        appTokenVariableName = appTokenHeld?.name,
        appTokenVariableCatalog = appTokenHeld?.catalog,
        appTokenVariableMissing = connection.appTokenVariableId != null && appTokenHeld == null,
        userTokenSet = !connection.userToken.isNullOrBlank(),
        userTokenVariableId = connection.userTokenVariableId,
        userTokenVariableName = userTokenHeld?.name,
        userTokenVariableCatalog = userTokenHeld?.catalog,
        userTokenVariableMissing = connection.userTokenVariableId != null && userTokenHeld == null,
        // The port as it will be used, so the form shows what sending will do
        // rather than an empty field meaning a default nobody wrote down.
        smtpPort = connection.smtpPort ?: connection.smtpSecurity.defaultPort,
        smtpUsername = connection.smtpUsername,
        smtpFrom = connection.smtpFrom,
        smtpSecurity = connection.smtpSecurity,
        status = connection.status,
        lastCheckMessage = connection.lastCheckMessage,
        lastCheckedAt = connection.lastCheckedAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
    )
}
