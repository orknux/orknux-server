package io.mszymanski.orknux.connector.connection

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.SmartLifecycle
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * A workspace connection was created or edited, which is the moment its status is
 * worth knowing again.
 */
data class WorkspaceConnectionSaved(val connectionId: Long)

@ConfigurationProperties(prefix = "orknux.connection.check")
data class ConnectionCheckProperties(
    /** False checks nothing on a timer; the button still works. */
    val enabled: Boolean = true,
    val interval: Duration = Duration.ofMinutes(5),
    val initialDelay: Duration = Duration.ofSeconds(30),
)

/**
 * Asks every configured workspace connection, on a timer, whether it still answers.
 *
 * The same argument as [io.mszymanski.orknux.connector.model.ModelProviderMonitor],
 * for the same reason: a token is revoked, an MCP server is stopped, a webhook
 * moves — and a status recorded this morning goes on saying "Connected" until
 * somebody presses the button. "Not checked" is worse still, because it is what
 * a connection says from the moment it is saved until somebody thinks to ask.
 *
 * Connections with nothing to check with are skipped rather than reported as
 * failing: not configured is not broken.
 */
@Component
@ConditionalOnProperty(name = ["orknux.connection.check.enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ConnectionCheckProperties::class)
class ConnectionMonitor(
    private val connections: WorkspaceConnectionRepository,
    private val service: WorkspaceConnectionService,
    private val properties: ConnectionCheckProperties,
) : SmartLifecycle {

    /**
     * Built on each start rather than held as a field, because a stopped
     * executor cannot be started again - `shutdownNow` is permanent, and
     * scheduling on one afterwards throws. A context that is stopped and
     * started again is not hypothetical: the test suite does it between
     * classes, and so does anything that restarts the application context in
     * place. Issue #504.
     */
    private var sweeper: ScheduledExecutorService? = null
    private var running = false

    override fun start() {
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "connection-check").apply { isDaemon = true }
        }
        executor.scheduleWithFixedDelay(
            { runCatching(::sweep).onFailure { log.warn("Could not check the workspace connections", it) } },
            properties.initialDelay.toSeconds(),
            properties.interval.toSeconds(),
            TimeUnit.SECONDS,
        )
        sweeper = executor
        running = true
        log.info("Checking workspace connections every {}", properties.interval)
    }

    override fun stop() {
        if (!running) return
        sweeper?.shutdownNow()
        sweeper = null
        running = false
    }

    override fun isRunning(): Boolean = running

    /**
     * Saving a connection checks it, rather than leaving it on "Not checked"
     * until the next sweep. After the commit, so the check cannot race the
     * transaction that wrote the URL it is about to call; off the caller's
     * thread, so a form does not wait on an endpoint that may be timing out.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onConnectionSaved(event: WorkspaceConnectionSaved) {
        if (!running) return
        sweeper?.execute {
            runCatching { check(event.connectionId) }
                .onFailure { log.warn("Could not check workspace connection {}", event.connectionId, it) }
        }
    }

    /**
     * One pass over everything worth asking. A failure to reach one connection is
     * not allowed to end the sweep.
     */
    fun sweep() {
        val worth = connections.findAll().filter { it.configured }
        if (worth.isEmpty()) return

        for (connection in worth) {
            val id = connection.id ?: continue
            runCatching { check(id) }
                .onFailure { log.warn("Could not check workspace connection {}", connection.name, it) }
        }
        log.debug("Checked {} workspace connections", worth.size)
    }

    /** Skipped rather than failed when there is nothing to check with. */
    private fun check(connectionId: Long) {
        val connection = connections.findByIdOrNull(connectionId) ?: return
        if (!connection.configured) return
        service.testWorkspaceConnection(connectionId)
    }

    private companion object {
        val log = LoggerFactory.getLogger(ConnectionMonitor::class.java)
    }
}
