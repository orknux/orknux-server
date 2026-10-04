package io.mszymanski.orknux.connector.connection

import io.mszymanski.orknux.connector.cluster.ClusterLeader
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

@ConfigurationProperties(prefix = "orknux.mcp.check")
data class McpServerCheckProperties(
    /** False checks nothing on a timer; the Check button still works. */
    val enabled: Boolean = true,
    /**
     * How often every registered MCP server is asked whether it still answers.
     * Long enough not to be traffic, short enough that a token revoked this
     * morning is not still reported as working this afternoon.
     */
    val interval: Duration = Duration.ofMinutes(5),
    /** How long after startup the first sweep runs, so booting stays quick. */
    val initialDelay: Duration = Duration.ofSeconds(30),
)

/**
 * Asks every registered MCP server, on a timer, whether it is still answering.
 *
 * A registered server was only ever known reachable at the moment somebody
 * opened its page and pressed Check. Between those presses a server can stop
 * answering, move behind a proxy, or have its credential expire, and nothing
 * said so until an agent tried to use it mid-run and the run was what failed.
 * So nobody has to press it: the sweep re-runs the same `tools/list` the button
 * runs and writes down what it found, which is also what puts a date beside the
 * server worth reading. Issue #329.
 *
 * The twin of [io.mszymanski.orknux.connector.model.ModelProviderMonitor], and
 * built the same way for the same reason: a status is only true when it was
 * made. One unreachable server does not end the sweep - it is checked on its
 * own and its failure is caught - so a server near the top that will not answer
 * cannot leave every server below it unchecked.
 */
@Component
@ConditionalOnProperty(name = ["orknux.mcp.check.enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(McpServerCheckProperties::class)
class McpServerMonitor(
    private val servers: McpServerRepository,
    private val service: McpServerService,
    private val properties: McpServerCheckProperties,
    /** Which replica sweeps; alone where nothing says otherwise. Issue #597. */
    private val leader: ClusterLeader = ClusterLeader.alone(),
) : SmartLifecycle {

    /*
     * Made in start() rather than held for the bean's life, because a stopped
     * executor is terminated for good and this bean has to survive stop() then
     * start() - which is exactly what the test framework does to a cached
     * context it paused and picked up again.
     */
    private var sweeper: ScheduledExecutorService? = null
    private var running = false

    override fun start() {
        val pool = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "mcp-server-check").apply { isDaemon = true }
        }
        pool.scheduleWithFixedDelay(
            { runCatching(::timedPass).onFailure { log.warn("Could not check the MCP servers", it) } },
            properties.initialDelay.toSeconds(),
            properties.interval.toSeconds(),
            TimeUnit.SECONDS,
        )
        sweeper = pool
        running = true
        log.info("Checking MCP servers every {}", properties.interval)
    }

    override fun stop() {
        if (!running) return
        sweeper?.shutdownNow()
        sweeper = null
        running = false
    }

    override fun isRunning(): Boolean = running

    /**
     * What the timer calls: a pass, on the one replica that leads. Issue #597.
     * Every replica keeps its timer, so the work moves when the leader dies;
     * a test asks [sweep] directly, or this to see the gate.
     */
    fun timedPass(): Boolean {
        if (!leader.leads()) return false
        sweep()
        return true
    }

    /** One pass over every registered server. */
    fun sweep() {
        val all = servers.findAll()
        if (all.isEmpty()) return

        for (server in all) {
            val id = server.id ?: continue
            runCatching { service.checkMcpServer(id) }
                .onFailure { log.warn("Could not check MCP server {}", server.name, it) }
        }
        log.debug("Checked {} MCP server(s)", all.size)
    }

    private companion object {
        val log = LoggerFactory.getLogger(McpServerMonitor::class.java)
    }
}
