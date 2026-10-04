package io.mszymanski.orknux.server.integration

import io.mszymanski.orknux.connector.cluster.ClusterLeader
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
import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.core.SimpleLock
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.context.ApplicationEventPublisher
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Only the replica holding the cluster lease opens Slack sockets. Issue #597.
 *
 * Two listeners here are two replicas, each with its own stand-in for Slack and
 * its own [ClusterLeader]; who holds the lease is decided by a switch rather
 * than a table, because what is under test is what the listener does with the
 * answer. The lease itself, on both databases, is ClusterLeaderTest.
 */
class SlackLeaderTest {

    private class Factory : SlackSockets {
        val opened = CopyOnWriteArrayList<Stub>()
        override fun open(request: SlackSocketRequest): SlackSocket = Stub().also { opened += it }
        fun open() = opened.count { !it.closed }
    }

    private class Stub : SlackSocket {
        @Volatile
        var closed = false
        override fun alive() = true
        override fun close() {
            closed = true
        }
    }

    /** A lease that is granted while [granted] says so, and extended on the same terms. */
    private class Switch(@Volatile var granted: Boolean) : LockProvider {
        override fun lock(configuration: net.javacrumbs.shedlock.core.LockConfiguration): Optional<SimpleLock> =
            if (granted) Optional.of(held()) else Optional.empty()

        private fun held(): SimpleLock = object : SimpleLock {
            override fun unlock() {}
            override fun extend(lockAtMostFor: Duration, lockAtLeastFor: Duration): Optional<SimpleLock> =
                if (granted) Optional.of(this) else Optional.empty()
        }
    }

    @Test
    fun `the replica that leads opens the socket and the other does not`() {
        val (first, firstSockets, _) = replica(leading = true)
        val (second, secondSockets, _) = replica(leading = false)

        first.reconcile()
        second.reconcile()

        assertThat(firstSockets.open()).isEqualTo(1)
        assertThat(secondSockets.opened).isEmpty()
        assertThat(first.stateOf(CONNECTION_ID).status).isEqualTo(SlackSocketStatus.CONNECTED)
        assertThat(second.stateOf(CONNECTION_ID).status).isEqualTo(SlackSocketStatus.ELSEWHERE)
    }

    @Test
    fun `losing the lease closes the sockets, and the replica that takes it opens them`() {
        val (first, firstSockets, firstLease) = replica(leading = true)
        val (second, secondSockets, secondLease) = replica(leading = false)
        first.reconcile()
        second.reconcile()

        firstLease.lose()
        secondLease.take()

        // Closed when the lease went, not at the next pass.
        assertThat(firstSockets.open()).isZero()
        await().atMost(Duration.ofSeconds(5)).until { secondSockets.open() == 1 }

        // And the old leader's next pass leaves it closed.
        first.reconcile()
        assertThat(firstSockets.open()).isZero()
        assertThat(first.stateOf(CONNECTION_ID).status).isEqualTo(SlackSocketStatus.ELSEWHERE)
    }

    @Test
    fun `a reconnect pressed on a follower opens nothing there and reaches the leader`() {
        val ledger = InMemorySlackReconnectRequests()
        val (first, firstSockets, _) = replica(leading = true, reconnects = ledger)
        val (second, secondSockets, _) = replica(leading = false, reconnects = ledger)
        first.reconcile()

        val state = second.reconnect(CONNECTION_ID)

        assertThat(state.status).isEqualTo(SlackSocketStatus.ELSEWHERE)
        assertThat(secondSockets.opened).isEmpty()
        first.reconcile()
        assertThat(firstSockets.opened).hasSize(2)
        assertThat(firstSockets.open()).isEqualTo(1)
    }

    private class Lease(val switch: Switch, val leader: ClusterLeader) {
        fun lose() {
            switch.granted = false
            leader.renew()
        }

        fun take() {
            switch.granted = true
            leader.renew()
        }
    }

    private fun replica(
        leading: Boolean,
        reconnects: SlackReconnectRequests = InMemorySlackReconnectRequests(),
    ): Triple<SlackListener, Factory, Lease> {
        val switch = Switch(leading)
        val leader = ClusterLeader(locks = switch, timings = { 30 }, instance = if (leading) "first" else "second")
        leader.renew()
        val sockets = Factory()
        val clients = SlackClients(ProxyRouter(ProxyRuleSource { emptyList() }))
        val repository = connections()
        val listener = SlackListener(
            repository,
            ApplicationEventPublisher { },
            SlackProperties(),
            plainCredentials(),
            clients,
            SlackBotUsers(repository, plainCredentials(), clients),
            sockets,
            reconnects,
            leader,
        )
        return Triple(listener, sockets, Lease(switch, leader))
    }

    private fun connections(): WorkspaceConnectionRepository {
        val connection = WorkspaceConnection(
            id = CONNECTION_ID,
            workspaceId = WORKSPACE_ID,
            name = "Support Slack",
            type = ConnectionType.SLACK,
            url = "https://slack.com/api",
            secret = "xoxb-not-a-real-token",
        )
        connection.appToken = "xapp-not-a-real-token"
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
