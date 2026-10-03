package io.mszymanski.orknux.connector.connection

import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.connector.proxy.mailAddress
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/** Everything needed to reach one mail server, resolved from a connection. */
data class SmtpServer(
    val host: String,
    val port: Int,
    /** Null sends without authenticating. */
    val username: String?,
    val password: String?,
    val from: String,
    val security: MailSecurity,
)

/**
 * One mail, as the rest of orknux describes it.
 *
 * The body is text and only text. HTML is deliberately left out: an HTML mail is
 * two bodies, an alternative part and a set of rules about what a client may
 * strip, and a workflow that wants to say "the build failed" needs none of it.
 * Adding it later is a field here and a checkbox on the action, and nothing
 * stored now would have to change.
 */
data class MailMessage(
    val to: List<String>,
    val subject: String,
    val body: String,
    val cc: List<String> = emptyList(),
    /** Where an answer should go, when that is not the address it was sent from. */
    val replyTo: String? = null,
)

/** What became of a mail a workflow asked to send. */
sealed interface MailDelivery {

    /** Handed to the server. [messageId] is what a mail log is searched by. */
    data class Sent(val to: List<String>, val messageId: String?) : MailDelivery

    /**
     * Nothing was sent, and it was not a failure: a connection with no server
     * configured, or one that is not a mail connection at all. The step reports
     * it and the run carries on, as it does for a message that has no target.
     */
    data class NotPossible(val reason: String) : MailDelivery

    /**
     * Tried and refused. [permanent] separates the answers that will be the same
     * on the third attempt - a password the server rejected, an address it will
     * not accept - from a server that was busy or unreachable, which is the only
     * kind worth coming back to. Retrying the first kind only spends the run's
     * time arriving at the same answer, and against a provider that counts failed
     * logins it does worse than that.
     */
    data class Refused(val reason: String, val permanent: Boolean) : MailDelivery
}

/**
 * The point at which a mail leaves the process.
 *
 * An interface because it is the one thing in sending that cannot be exercised
 * without a mail server: everything above it - what a connection means, which
 * failures are worth retrying, what the run is handed afterwards - is decided in
 * [OutgoingMail] and in the action, and a test that had to open a socket to
 * reach those would be testing the socket.
 */
interface MailTransport {

    fun deliver(server: SmtpServer, message: MailMessage): MailDelivery

    /** Opens a session and authenticates, without sending anything. */
    fun check(server: SmtpServer): CheckResult
}

/** What a server listening for this kind of session is nearly always on. */
val MailSecurity.defaultPort: Int
    get() = when (this) {
        MailSecurity.TLS -> 465
        MailSecurity.STARTTLS -> 587
        MailSecurity.NONE -> 25
    }

/**
 * Sends the mail a workflow decided to send.
 *
 * Here rather than in the server for the same reason [OutgoingMessages] is: this
 * is where the credentials are. The server asks for a mail to go out through a
 * connection it names and is told what happened; it never sees the password.
 *
 * The host is vetted through [ConnectionProbe.vetHost] rather than by a second
 * copy of the rule, so what a workflow may open a connection to is decided in
 * one place whether the connection carries HTTP or mail.
 */
@Component
class OutgoingMail(
    private val connections: WorkspaceConnectionRepository,
    private val transport: MailTransport,
    private val probe: ConnectionProbe,
    /** Where the password comes from: the connection's own copy, or a workspace secret. */
    private val credentials: ConnectionCredentials,
    /** Only to know whether this process resolves the mail server's name, or the proxy does. */
    private val proxies: ProxyRouter,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @param on the only workspace whose connections this may send through, the
     *   same boundary [OutgoingMessages.send] takes: an action names a
     *   connection by id, and an id is a number anybody can type. Issue #588.
     */
    fun send(connectionId: Long, message: MailMessage, on: Long? = null): MailDelivery {
        val connection = connections.findByIdOrNull(connectionId)
            ?.takeIf { on == null || it.workspaceId == on }
            ?: return MailDelivery.NotPossible("the connection it sends through has been deleted")

        if (connection.type != ConnectionType.SMTP) {
            return MailDelivery.NotPossible("${connection.name} is a ${connection.type} connection, not a mail server")
        }

        val server = credentials.smtpServer(connection)
            ?: return MailDelivery.NotPossible("${connection.name} has no mail server and from-address stored")

        // A host that must not be reached is not a transient failure, and the
        // message says what to edit rather than what timed out.
        probe.vetHost(server.host, viaProxy = routed(server))?.let {
            return MailDelivery.Refused(it, permanent = true)
        }

        return try {
            transport.deliver(server, message)
        } catch (failure: Exception) {
            // The transport answers with a delivery for everything it expects;
            // this is the one it did not, and losing a run to it would say
            // nothing about which connection was involved.
            log.warn("Could not send mail on connection {}", connectionId, failure)
            MailDelivery.Refused(failure.message ?: "the mail server could not be reached", permanent = false)
        }
    }

    /** Whether the server answers and accepts the credentials, for the check button. */
    fun check(connection: WorkspaceConnection): CheckResult {
        val server = credentials.smtpServer(connection)
            ?: return CheckResult(CheckOutcome.FAILED, "No mail server and from-address are configured")
        probe.vetHost(server.host, viaProxy = routed(server))?.let { return CheckResult(CheckOutcome.FAILED, it) }

        return try {
            transport.check(server)
        } catch (failure: Exception) {
            CheckResult(CheckOutcome.FAILED, failure.message ?: "The mail server could not be reached")
        }
    }

    /**
     * Whether a proxy rule carries this server, which decides who resolves its
     * name. Asked here so that a mail server only the proxy can look up is not
     * refused by a lookup made from this process first.
     */
    private fun routed(server: SmtpServer): Boolean =
        proxies.resolve(mailAddress(server.host, server.port)) != null
}
