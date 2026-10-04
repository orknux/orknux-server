package io.mszymanski.orknux.connector.connection

import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import java.time.Instant

/** Where a Slack connection's socket stands, on the server that answers. */
enum class SlackSocketStatus {
    /** Open, and as far as anything here can tell, listening. */
    CONNECTED,

    /** Holds both tokens and has not been opened yet; the next pass will. */
    CONNECTING,

    /** The last attempt was refused or unreachable; `lastFailure` says which. */
    FAILED,

    /** No app-level token, so it sends and does not listen. */
    NOT_LISTENING,

    /** This server listens to no Slack at all - `orknux.slack.enabled` is false. */
    DISABLED,

    /**
     * Listened to by another server of this installation: the one holding the
     * cluster lease, which is the only one that opens sockets. Issue #597.
     */
    ELSEWHERE,
}

/**
 * What a Slack connection's page shows about its socket.
 *
 * In memory, and one server's: a socket is held by a process, and what it last
 * heard is that process's to say.
 */
class SlackSocketState(
    val status: SlackSocketStatus,
    val connectedSince: Instant? = null,
    /** When an event last arrived on this connection, across reconnects. */
    val lastEventAt: Instant? = null,
    /** Why it last would not open, kept after it recovers. */
    val lastFailure: String? = null,
    val lastFailureAt: Instant? = null,
    /** How many connections Slack's latest `hello` said it holds for this app. */
    val appConnections: Int? = null,
    /** More of them than this server holds: somebody else is listening with this app. */
    val sharedWithOthers: Boolean = false,
)

/**
 * The socket half of a Slack connection, whether or not this server listens.
 *
 * [SlackListener] exists only where Slack is enabled; a press of Reconnect
 * should still reach the replicas that do listen, so the request is recorded
 * here whatever this one does with it.
 */
@Service
class SlackSocketService(
    private val listener: ObjectProvider<SlackListener>,
    private val reconnects: SlackReconnectRequests,
) {

    /** Closes and reopens the socket everywhere, and answers how it stands here. */
    fun reconnect(connectionId: Long): SlackSocketState {
        val here = listener.ifAvailable ?: run {
            reconnects.request(connectionId)
            return SlackSocketState(SlackSocketStatus.DISABLED)
        }
        return here.reconnect(connectionId)
    }

    fun stateOf(connectionId: Long): SlackSocketState =
        listener.ifAvailable?.stateOf(connectionId) ?: SlackSocketState(SlackSocketStatus.DISABLED)
}
