package io.mszymanski.orknux.connector.connection

import com.slack.api.bolt.App
import com.slack.api.bolt.AppConfig
import com.slack.api.bolt.socket_mode.SocketModeApp
import com.slack.api.socket_mode.SocketModeClient
import org.springframework.stereotype.Component

/**
 * What opens a Socket Mode session, so that [SlackListener] can be driven
 * without one.
 *
 * The listener decides when a socket opens, closes and is judged dead; this is
 * only the part that dials Slack. Kept apart because that part cannot run in a
 * test - the suite has no network - and everything worth testing about
 * reconnecting is the listener's half.
 */
fun interface SlackSockets {

    /** Opens one session and answers it, or throws why it would not open. */
    fun open(request: SlackSocketRequest): SlackSocket
}

/**
 * One connection's tokens and the hooks the listener wants called.
 *
 * Not a data class, and [toString] carries neither token, for the reason the
 * listener's own holder gives: a generated one would put both into every log
 * line that ever interpolated it.
 */
class SlackSocketRequest(
    val connectionId: Long,
    val name: String,
    val botToken: String,
    val appToken: String,
    /** Registers the event handlers on the Bolt app the session is built on. */
    val register: (App) -> Unit,
    /**
     * Handed every frame that arrives, events and Slack's own housekeeping
     * alike - the `hello` among them, which says how many connections Slack
     * holds for this app.
     */
    val heard: (String) -> Unit,
) {
    override fun toString(): String = "SlackSocketRequest($name)"
}

/** An open session, as far as the listener needs to know one. */
interface SlackSocket {

    /**
     * Whether the far end still answers, asked now.
     *
     * Asked only after a long silence, never on a timer of its own: Slack's
     * client already pings every few seconds and reconnects what it finds dead,
     * and this is the question put when that has evidently not happened.
     */
    fun alive(): Boolean

    fun close()
}

/** The real thing: Bolt over Tyrus, routed through the proxy rules. */
@Component
class SocketModeSockets(private val slackClients: SlackClients) : SlackSockets {

    override fun open(request: SlackSocketRequest): SlackSocket {
        // The Slack instance the app is built on is the one the whole
        // session runs through - the `apps.connections.open` that issues the
        // websocket URL, every call a handler makes, and the socket itself.
        // Giving it one that consults the proxy rules is what puts Slack
        // under the same rules as everything else outbound.
        val routed = slackClients.forSocketMode()
        val app = App(
            AppConfig.builder()
                .singleTeamBotToken(request.botToken)
                .slack(routed.slack)
                .build(),
        )
        request.register(app)

        // Tyrus is the websocket client the standalone bundle provides; the
        // JDK has none of its own. It takes a proxy, but only one address
        // and only when it connects, so it is pointed at the URL Slack has
        // by then issued this session rather than at a rule chosen now.
        val socket = SocketModeApp(request.appToken, SocketModeClient.Backend.Tyrus, app)
        routed.routeAgainst { socket.client?.wssUri?.toString() }
        socket.startAsync()
        // Every frame, the `hello` Slack sends on each connect among them, is a
        // sign of life; the silence detector and the shared-app count read them.
        // Added once the client exists, which is just after the handshake: the
        // first `hello` is sent by Slack after that and goes through a queue the
        // client drains every few milliseconds, so it lands behind this line in
        // all but a freak of scheduling - and every one after one of Slack's
        // periodic refreshes arrives with the listener long in place.
        socket.client?.addWebSocketMessageListener { frame -> request.heard(frame) }
        return OpenSocket(socket)
    }

    private class OpenSocket(private val socket: SocketModeApp) : SlackSocket {

        /**
         * A ping, and up to three seconds for its pong - the same question the
         * client's own session monitor asks, put once more by somebody who has
         * stopped believing it.
         */
        override fun alive(): Boolean = socket.client?.verifyConnection() ?: false

        override fun close() = socket.close()
    }
}
