package io.mszymanski.orknux.server.integration

import io.mszymanski.orknux.connector.connection.ConnectionType
import io.mszymanski.orknux.connector.connection.InMemorySlackReconnectRequests
import io.mszymanski.orknux.connector.connection.SlackBotUsers
import io.mszymanski.orknux.connector.connection.SlackClients
import io.mszymanski.orknux.connector.connection.SlackListener
import io.mszymanski.orknux.connector.connection.SlackProperties
import io.mszymanski.orknux.connector.connection.SlackReconnectRequests
import io.mszymanski.orknux.connector.connection.SlackSocket
import io.mszymanski.orknux.connector.connection.SlackSocketRequest
import io.mszymanski.orknux.connector.connection.SlackSocketStatus
import io.mszymanski.orknux.connector.connection.SlackSockets
import io.mszymanski.orknux.connector.connection.WorkspaceConnection
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.connector.proxy.ProxyRuleSource
import io.mszymanski.orknux.server.security.plainCredentials
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.context.ApplicationEventPublisher
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Reopening a Slack socket: by a press, on another replica's press, and by
 * itself once it has gone quiet and stopped answering. #592.
 *
 * Nothing here dials Slack. [SlackSockets] is the seam: a stand-in records each
 * socket the listener asks for, says whether it is alive, and hands frames back
 * the way a real one would - which is all of the listener's half, and the half
 * that used to be missing.
 */
class SlackReconnectTest {

    /** Every socket opened, in order, across the listeners built from one factory. */
    private class Factory : SlackSockets {
        val opened = CopyOnWriteArrayList<Stub>()
        var refuseWith: String? = null
        var alive = true

        override fun open(request: SlackSocketRequest): SlackSocket {
            refuseWith?.let { throw IllegalStateException(it) }
            return Stub(request, this).also { opened += it }
        }
    }

    private class Stub(val request: SlackSocketRequest, val factory: Factory) : SlackSocket {
        var closed = false
        override fun alive() = factory.alive
        override fun close() {
            closed = true
        }
    }

    @Test
    fun `a reconnect closes the socket and opens another at once`() {
        val sockets = Factory()
        val listener = listener(sockets)
        listener.reconcile()
        assertThat(sockets.opened).hasSize(1)

        val state = listener.reconnect(CONNECTION_ID)

        assertThat(sockets.opened).hasSize(2)
        assertThat(sockets.opened[0].closed).isTrue()
        assertThat(sockets.opened[1].closed).isFalse()
        assertThat(state.status).isEqualTo(SlackSocketStatus.CONNECTED)
        assertThat(state.connectedSince).isNotNull()

        // And the pass after it does not reopen it a second time for the same press.
        listener.reconcile()
        assertThat(sockets.opened).hasSize(2)
    }

    /**
     * The wait after a refusal is what keeps a bad token from being tried twice
     * a minute, and it is exactly what a person pressing Reconnect is asking to
     * skip.
     */
    @Test
    fun `a reconnect clears the wait after a failure`() {
        val sockets = Factory().apply { refuseWith = "invalid_auth" }
        val listener = listener(sockets)
        listener.reconcile()

        val failed = listener.stateOf(CONNECTION_ID)
        assertThat(failed.status).isEqualTo(SlackSocketStatus.FAILED)
        assertThat(failed.lastFailure).isEqualTo("invalid_auth")

        // The token was fixed on Slack's side; the timer would still wait.
        sockets.refuseWith = null
        listener.reconcile()
        assertThat(sockets.opened).isEmpty()

        val state = listener.reconnect(CONNECTION_ID)

        assertThat(sockets.opened).hasSize(1)
        assertThat(state.status).isEqualTo(SlackSocketStatus.CONNECTED)
        // Kept after it recovers, so the page can still say what went wrong.
        assertThat(state.lastFailure).isEqualTo("invalid_auth")
    }

    /** The press reaches one replica; the other has to find it on its own pass. */
    @Test
    fun `a reconnect made on one server reopens the socket on another`() {
        val ledger = InMemorySlackReconnectRequests()
        val here = Factory()
        val there = Factory()
        val first = listener(here, ledger)
        val second = listener(there, ledger)
        first.reconcile()
        second.reconcile()

        first.reconnect(CONNECTION_ID)
        assertThat(there.opened).hasSize(1)

        second.reconcile()

        assertThat(there.opened).hasSize(2)
        assertThat(there.opened[0].closed).isTrue()
        // Honoured once: neither replica reopens again for the same press.
        second.reconcile()
        first.reconcile()
        assertThat(there.opened).hasSize(2)
        assertThat(here.opened).hasSize(2)
    }

    /** And a replica that was not listening when the press came still honours it when it does. */
    @Test
    fun `a failed socket on another server is retried on the press, not after the wait`() {
        val ledger = InMemorySlackReconnectRequests()
        val there = Factory().apply { refuseWith = "Slack is unreachable" }
        val second = listener(there, ledger)
        second.reconcile()
        there.refuseWith = null

        listener(Factory(), ledger).reconnect(CONNECTION_ID)
        second.reconcile()

        assertThat(there.opened).hasSize(1)
        assertThat(second.stateOf(CONNECTION_ID).status).isEqualTo(SlackSocketStatus.CONNECTED)
    }

    /**
     * A socket that died without the client noticing - the case that needed a
     * restart before. Quiet, and no answer to a ping.
     */
    @Test
    fun `a socket that has gone quiet and does not answer is reopened by itself`() {
        val sockets = Factory()
        val listener = listener(sockets, quiet = Duration.ofMillis(50))
        listener.reconcile()
        sockets.alive = false
        Thread.sleep(150)

        listener.reconcile()

        assertThat(sockets.opened).hasSize(2)
        assertThat(sockets.opened[0].closed).isTrue()
    }

    /** Quiet alone is not dead: a workspace nobody types in sends nothing. */
    @Test
    fun `a quiet socket that answers a ping is left open`() {
        val sockets = Factory()
        val listener = listener(sockets, quiet = Duration.ofMillis(50))
        listener.reconcile()
        Thread.sleep(150)

        listener.reconcile()

        assertThat(sockets.opened).hasSize(1)
    }

    /** And a frame arriving is hearing from it, so it is not even asked. */
    @Test
    fun `a socket that hears frames is never pinged`() {
        val sockets = Factory()
        val listener = listener(sockets, quiet = Duration.ofMillis(200))
        listener.reconcile()
        sockets.alive = false
        Thread.sleep(120)
        sockets.opened[0].request.heard("""{"type":"events_api"}""")
        Thread.sleep(120)

        listener.reconcile()

        assertThat(sockets.opened).hasSize(1)
    }

    /**
     * Slack splits one app's events between every connection opened for it, so
     * a second server listening with the same app - an old development server,
     * in the incident this is for - takes a share of a trigger's mentions. Its
     * `hello` says how many there are.
     */
    @Test
    fun `a hello counting more connections than this server holds says somebody else is listening`() {
        val sockets = Factory()
        val listener = listener(sockets)
        listener.reconcile()

        sockets.opened[0].request.heard(hello(connections = 2))

        val state = listener.stateOf(CONNECTION_ID)
        assertThat(state.appConnections).isEqualTo(2)
        assertThat(state.sharedWithOthers).isTrue()
    }

    @Test
    fun `a hello counting only this server's connection says nothing is shared`() {
        val sockets = Factory()
        val listener = listener(sockets)
        listener.reconcile()

        sockets.opened[0].request.heard(hello(connections = 1))

        val state = listener.stateOf(CONNECTION_ID)
        assertThat(state.appConnections).isEqualTo(1)
        assertThat(state.sharedWithOthers).isFalse()
    }

    /** A reconnect of something that only sends is answered, not refused: there is nothing to open. */
    @Test
    fun `a Slack connection without an app-level token has nothing to reconnect`() {
        val sockets = Factory()
        val listener = listener(sockets, appToken = null)

        val state = listener.reconnect(CONNECTION_ID)

        assertThat(sockets.opened).isEmpty()
        assertThat(state.status).isEqualTo(SlackSocketStatus.NOT_LISTENING)
    }

    private fun hello(connections: Int) =
        """{"type":"hello","num_connections":$connections,"connection_info":{"app_id":"A0000000001"},""" +
            """"debug_info":{"host":"applink-1"}}"""

    private fun listener(
        sockets: SlackSockets,
        reconnects: SlackReconnectRequests = InMemorySlackReconnectRequests(),
        quiet: Duration = Duration.ofMinutes(10),
        appToken: String? = "xapp-not-a-real-token",
    ): SlackListener {
        val clients = SlackClients(ProxyRouter(ProxyRuleSource { emptyList() }))
        val repository = connections(appToken)
        return SlackListener(
            repository,
            ApplicationEventPublisher { },
            SlackProperties(quietPeriod = quiet),
            plainCredentials(),
            clients,
            SlackBotUsers(repository, plainCredentials(), clients),
            sockets,
            reconnects,
        )
    }

    private fun connections(appToken: String?): WorkspaceConnectionRepository {
        val connection = WorkspaceConnection(
            id = CONNECTION_ID,
            workspaceId = WORKSPACE_ID,
            name = "Support Slack",
            type = ConnectionType.SLACK,
            url = "https://slack.com/api",
            secret = "xoxb-not-a-real-token",
        )
        connection.appToken = appToken
        val repository = mock(WorkspaceConnectionRepository::class.java)
        `when`(repository.findById(CONNECTION_ID)).thenReturn(Optional.of(connection))
        `when`(repository.findByType(ConnectionType.SLACK)).thenReturn(listOf(connection))
        return repository
    }

    private companion object {
        const val CONNECTION_ID = 7L
        const val WORKSPACE_ID = 3L
    }
}
