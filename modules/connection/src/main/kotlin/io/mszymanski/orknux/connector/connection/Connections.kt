package io.mszymanski.orknux.connector.connection

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.ElementCollection
import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table
import io.mszymanski.orknux.connector.security.SECRET_COLUMN_LENGTH
import io.mszymanski.orknux.connector.security.SecretCipher
import io.mszymanski.orknux.connector.security.SecretConverter
import java.time.OffsetDateTime

/**
 * Where Slack's Web API lives.
 *
 * A constant rather than something the form asks for: there is one Slack, it has
 * one API base, and a person setting up a connection has no useful other answer.
 * It is only ever used by [ConnectionProbe], which calls `auth.test` under it -
 * [OutgoingMessages] posts through the Slack SDK, which carries its own endpoint
 * and never reads a connection's URL at all.
 */
const val SLACK_API_URL = "https://slack.com/api"

/** The external services a connection can point at. */
enum class ConnectionType {
    /**
     * Slack: a bot token to call the API with, and optionally an app-level
     * token to open a websocket with.
     *
     * One type rather than two, because there was only ever one thing. The
     * app-level token is not a different kind of connection, it is the extra
     * credential that makes orknux listen as well as send - which is exactly
     * what [SlackListener] has always decided by looking at the token rather
     * than at the type.
     */
    SLACK,

    /**
     * A mail server to send through. The only type that is not an HTTP endpoint,
     * so [WorkspaceConnection.url] holds a host name rather than a URL and the
     * settings beside it - port, user, from-address, how the session is secured -
     * are the ones a mail server actually asks for.
     */
    SMTP,

    /**
     * An HTTP endpoint this installation sends requests to.
     *
     * The generic outbound target, and the type every connection that is not
     * Slack or a mail server already was: the connector opens the URL, sends the
     * request with whatever [AuthType] and headers the connection carries, and
     * reads the response back. Nothing here asks what the far end calls itself.
     *
     * It was called WEBHOOK, which named the wrong end of the wire. A webhook is
     * something *this* installation exposes and somebody else calls - which is
     * what a webhook trigger still means, and now means on its own. Whether the
     * endpoint on the other side of an outgoing request is a webhook, an API or
     * a form handler is the receiver's business and was never visible from here.
     */
    HTTP,
}

/**
 * How the connection to a mail server is secured.
 *
 * Two ways, because mail has two: a session that starts in the clear and is
 * upgraded once the server offers it, and one that is encrypted from the first
 * byte. Which one applies is decided by the port the server listens on, not by
 * anything that can be negotiated, so it has to be configured.
 *
 * [NONE] is here for a relay inside a network that does not offer TLS at all -
 * refusing to speak to one would leave those deployments unable to send - and
 * is not what a new connection starts as.
 */
enum class MailSecurity {
    NONE,

    /** Port 587: plain to begin with, encrypted before the credentials are sent. */
    STARTTLS,

    /** Port 465: TLS before anything else, the way HTTPS is. */
    TLS,
}

/** What the workspace screen reports about a connection. */
enum class ConnectionStatus {
    /** No credentials stored, so there is nothing to check. */
    NOT_CONFIGURED,

    /** Configured, but no probe has reached the other end yet. */
    NOT_CHECKED,
    CONNECTED,
    FAILED,
}

/** How a request to the service is authenticated. */
enum class AuthType {
    NONE,
    API_KEY,
    BEARER_TOKEN,
    BASIC,
}

/** An extra HTTP header sent with every request. */
@Embeddable
class HttpHeader(
    @Column(name = "name", nullable = false)
    var name: String = "",

    @Column(name = "value", nullable = false, length = 1000)
    var value: String = "",
)

/**
 * An admin-level default connection. New workspaces are provisioned with a copy
 * of every default; the credentials are always the workspace's own.
 */
@Entity
@Table(name = "connection")
class Connection(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var type: ConnectionType,

    @Column(nullable = false, length = 1000)
    var url: String,
)

/**
 * A connection as one workspace holds it, with that workspace's credentials.
 *
 * [workspaceId] names a workspace in orknux-server; there is no foreign key to enforce it
 * and no cascade to clean it up, so a deleted workspace is forgotten only when the
 * server tells the connector about it.
 */
@Entity
@Table(name = "workspace_connection")
class WorkspaceConnection(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val workspaceId: Long,

    /** The admin default this was provisioned from; null when workspace-owned. */
    @Column(name = "connection_id")
    var connectionId: Long? = null,

    @Column(nullable = false)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    var type: ConnectionType,

    @Column(nullable = false, length = 1000)
    var url: String,

    /** Used instead of [url] when the workspace points at its own endpoint. */
    @Column(name = "url_override", length = 1000)
    var urlOverride: String? = null,

    /**
     * Which plugin-declared kind of host this is, where it is one. Issue #363.
     *
     * The plugin key and its declared name, joined - `prometheus/server` - so
     * two plugins declaring the same name cannot collide. Set only on an
     * [ConnectionType.HTTP] connection: the wire shape is the generic one, a
     * URL with an auth kind and a secret, and this is the label over it that
     * lets a plugin's picker offer only its own hosts, and a list say
     * "Prometheus" rather than "HTTP". Null for every core type and for a
     * plain HTTP endpoint nobody has typed.
     */
    @Column(name = "plugin_type", length = 120)
    var pluginType: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false, length = 16)
    var authType: AuthType = AuthType.NONE,

    /**
     * The bot token, the API credential or the mail password, depending on the
     * type. Encrypted in the database; see [SecretCipher].
     *
     * Null when this field reads [secretVariableId]'s value instead. The two are
     * exclusive, in the entity and in a CHECK constraint.
     */
    @Convert(converter = SecretConverter::class)
    @Column(length = SECRET_COLUMN_LENGTH)
    var secret: String? = null,

    /**
     * The workspace variable [secret] reads its value from, if it reads one.
     *
     * Beside the field it answers for, rather than one switch for the connection
     * - which is the whole of #244. A Slack connection holds two credentials and
     * "this connection uses a workspace secret" cannot mean one of them without
     * meaning the other, so each has a reference column of its own.
     *
     * By id, and not a foreign key: `workspace_variable` is the application's
     * table and this is the module's. See [io.mszymanski.orknux.connector.security.SecretReferences].
     */
    @Column(name = "secret_variable_id")
    var secretVariableId: Long? = null,

    /**
     * A second credential, for services that need one to open a listening
     * socket: Slack's Socket Mode wants an app-level token (`xapp-...`) as well
     * as the bot token that [secret] holds.
     *
     * Optional. A Slack connection without one sends and never listens, which is
     * a whole integration on its own; with one, [SlackListener] opens a socket.
     */
    @Convert(converter = SecretConverter::class)
    @Column(name = "app_token", length = SECRET_COLUMN_LENGTH)
    var appToken: String? = null,

    /** The workspace variable [appToken] reads its value from; its own choice. */
    @Column(name = "app_token_variable_id")
    var appTokenVariableId: Long? = null,

    /**
     * A third credential, for the one Slack call a bot cannot make: search.
     * `search.messages` answers only for a user token (`xoxp-...`, with
     * `search:read`), so a workspace that wants search stores one here and
     * everything else keeps using the bot token in [secret].
     */
    @Convert(converter = SecretConverter::class)
    @Column(name = "user_token", length = SECRET_COLUMN_LENGTH)
    var userToken: String? = null,

    /** The workspace variable [userToken] reads its value from; its own choice. */
    @Column(name = "user_token_variable_id")
    var userTokenVariableId: Long? = null,

    /**
     * Which port the mail server listens on. Null uses the one that goes with
     * [smtpSecurity], since a workspace that picked STARTTLS almost never means
     * anything other than 587.
     */
    @Column(name = "smtp_port")
    var smtpPort: Int? = null,

    /**
     * Who to log in as. Null sends without authenticating, which is what an
     * internal relay that trusts the network expects; the password is [secret],
     * so a mail credential is encrypted by the same converter as every other.
     */
    @Column(name = "smtp_username", length = 320)
    var smtpUsername: String? = null,

    /**
     * The address the mail is from.
     *
     * Stored rather than taken from [smtpUsername], because the two differ
     * whenever a service account sends as a team - and a provider that refuses a
     * From it has not authorised refuses it on the address, not on the login.
     */
    @Column(name = "smtp_from", length = 320)
    var smtpFrom: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "smtp_security", nullable = false, length = 16)
    var smtpSecurity: MailSecurity = MailSecurity.STARTTLS,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "workspace_connection_header",
        joinColumns = [JoinColumn(name = "workspace_connection_id")],
    )
    @OrderColumn(name = "position")
    var headers: MutableList<HttpHeader> = mutableListOf(),

    /** What the last probe found; null until one has run against this configuration. */
    @Enumerated(EnumType.STRING)
    @Column(name = "last_check_status", length = 16)
    var lastCheckStatus: CheckOutcome? = null,

    @Column(name = "last_check_message", length = 500)
    var lastCheckMessage: String? = null,

    @Column(name = "last_checked_at")
    var lastCheckedAt: OffsetDateTime? = null,
) {

    /** Inherited connections take their name, type and URL from the default. */
    val inherited: Boolean get() = connectionId != null

    val effectiveUrl: String get() = urlOverride?.takeIf { it.isNotBlank() } ?: url

    /**
     * Whether there is a credential to authenticate with - a copy of its own,
     * or a variable to read one from.
     *
     * Named apart from the view's `secretSet`, which is the narrower question a
     * screen asks: that one is "does this connection hold a copy", and is false
     * for one reading a variable. Two things called the same and meaning
     * different halves of this is exactly the trap worth not setting.
     *
     * A reference counts without the variable being read. What this decides is
     * whether the connection is worth checking, and one pointed at a variable
     * is: if the variable has gone or is still empty, the check is where that
     * gets said, in words about the variable. Reporting "Not configured" instead
     * would describe a connection nobody had finished setting up, which is not
     * what happened.
     */
    val credentialSet: Boolean get() = secretVariableId != null || !secret.isNullOrBlank()

    /** Whether anything has been configured to authenticate with. */
    val configured: Boolean
        get() = when (type) {
            /*
             * The bot token alone. [appToken] is what makes the connection
             * listen, not what makes it work, so a Slack connection that only
             * ever posts is configured and must not be reported otherwise.
             */
            ConnectionType.SLACK -> credentialSet

            /*
             * A mail server needs somewhere to send from before it needs a
             * password: a relay on an internal network authenticates nobody, and
             * calling that "not configured" would leave it unchecked forever. A
             * user name without a password is the half-filled form it looks like.
             */
            ConnectionType.SMTP -> !smtpFrom.isNullOrBlank() && (smtpUsername.isNullOrBlank() || credentialSet)

            else -> authType == AuthType.NONE || credentialSet
        }

    /**
     * What the workspace screen reports. A connection is only [ConnectionStatus.CONNECTED]
     * once a probe has reached the other end with this configuration.
     */
    val status: ConnectionStatus
        get() = when {
            !configured -> ConnectionStatus.NOT_CONFIGURED
            lastCheckStatus == CheckOutcome.CONNECTED -> ConnectionStatus.CONNECTED
            lastCheckStatus == CheckOutcome.FAILED -> ConnectionStatus.FAILED
            else -> ConnectionStatus.NOT_CHECKED
        }

    /** Called whenever the configuration changes, since the old result no longer describes it. */
    fun forgetLastCheck() {
        lastCheckStatus = null
        lastCheckMessage = null
        lastCheckedAt = null
    }
}

/** An MCP server a workspace's agents may connect to. */
@Entity
@Table(name = "mcp_server")
class McpServer(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val workspaceId: Long,

    @Column(nullable = false)
    var name: String,

    @Column(nullable = false, length = 1000)
    var address: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false, length = 16)
    var authType: AuthType = AuthType.NONE,

    /**
     * Encrypted in the database; see [SecretCipher]. Null when this field reads
     * [secretVariableId]'s value instead - the two are exclusive.
     */
    @Convert(converter = SecretConverter::class)
    @Column(length = SECRET_COLUMN_LENGTH)
    var secret: String? = null,

    /** The workspace variable [secret] reads its value from, if it reads one. */
    @Column(name = "secret_variable_id")
    var secretVariableId: Long? = null,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "mcp_server_header", joinColumns = [JoinColumn(name = "mcp_server_id")])
    @OrderColumn(name = "position")
    var headers: MutableList<HttpHeader> = mutableListOf(),

    /**
     * What the last check found, kept so the list can show reachability without
     * anybody pressing Check. Null until the first check, which reads as "not
     * checked yet" rather than as a failure. Issue #329.
     */
    @Column(name = "last_checked_at")
    var lastCheckedAt: java.time.OffsetDateTime? = null,

    /** Whether that check reached the server. Null before the first one. */
    @Column(name = "reachable")
    var reachable: Boolean? = null,

    /** The one sentence that check came back with, the same the button shows. */
    @Column(name = "check_detail", length = 1000)
    var checkDetail: String? = null,

    /** How many tools it offered, on a check that got that far. */
    @Column(name = "tool_count")
    var toolCount: Int? = null,

)
