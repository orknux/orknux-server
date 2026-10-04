package io.mszymanski.orknux.connector.shell

import io.mszymanski.orknux.connector.cluster.ClusterLeader
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Removes the directories nobody closed, and asks the shells whether they still
 * answer.
 *
 * **Why this has to exist.** An agent that opens a session and never closes it
 * is the ordinary case rather than the exception: a run fails, a model changes
 * its mind, a conversation ends, somebody stops the workflow. Every one of those
 * leaves a directory on a machine that is not ours, and a feature that leaks a
 * directory on every failure fills somebody's disk without anybody deciding to.
 * So a session has an expiry measured from its last use, and this is what
 * enforces it.
 *
 * **A restart is not one of the cases.** It used to be, in the design where a
 * session was an open connection: the connection died with the process and the
 * directory outlived it anonymously. A session is a row here, so a restart loses
 * a socket and nothing else - the sweep that runs a minute after the process
 * comes back finds every directory the old process made, including the ones it
 * would have swept had it lived.
 *
 * **A host that will not answer.** Retried on each sweep, because a machine
 * being rebooted this minute is fine next minute. Given up on after
 * [ShellProperties.sessionAbandonAfter], because a decommissioned host would
 * otherwise be a warning every ten minutes for the life of the installation, and
 * the session is then closed in the database with one plain line saying the
 * directory may still be out there. That is a true statement and a silent
 * retry loop is not.
 *
 * The status check rides along on the same timer for the same reason the
 * connection monitor exists: a status recorded this morning goes on saying
 * "Connected" about a machine that was turned off at lunchtime until somebody
 * presses a button, and the page's whole job is to say which hosts are reachable
 * *now*.
 */
@Component
@ConditionalOnProperty(name = ["orknux.shell.sweep-enabled"], havingValue = "true", matchIfMissing = true)
class ShellSessionSweeper(
    private val shells: ShellRepository,
    private val sessions: ShellSessionRepository,
    private val service: ShellService,
    private val sessionService: ShellSessionService,
    private val properties: ShellProperties,
    /** Which replica sweeps; alone where nothing says otherwise. Issue #597. */
    private val leader: ClusterLeader = ClusterLeader.alone(),
) : SmartLifecycle {

    /**
     * Built on each start rather than held as a field, because a stopped
     * executor cannot be started again - `shutdownNow` is permanent, and
     * scheduling on one afterwards throws. A context that is stopped and started
     * again is not hypothetical: the test suite does it between classes, and so
     * does anything that restarts the application context in place.
     */
    private var sweeper: ScheduledExecutorService? = null
    private var running = false

    override fun start() {
        if (running) return

        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "shell-sweep").apply { isDaemon = true }
        }
        executor.scheduleWithFixedDelay(
            { runCatching(::timedPass).onFailure { log.warn("The shell sweep did not finish", it) } },
            properties.sweepInitialDelay.toSeconds(),
            properties.sweepInterval.toSeconds(),
            TimeUnit.SECONDS,
        )
        sweeper = executor
        running = true
        log.info(
            "Sweeping shell sessions every {}, idle after {}",
            properties.sweepInterval,
            properties.sessionIdleTimeout,
        )
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

    /** One pass. A shell that will not answer must not end it for the others. */
    fun sweep() {
        checkShells()
        expireSessions()
    }

    private fun checkShells() {
        val all = shells.findAll()
        for (shell in all) {
            runCatching { service.checkShell(shell) }
                .onFailure { log.warn("Shell {} could not be checked", shell.name, it) }
        }
        // Written back once, on the entities that were actually checked. Reading
        // them again here would quietly discard everything the loop just found.
        runCatching { shells.saveAll(all) }
            .onFailure { log.warn("The shell statuses could not be written down", it) }
    }

    private fun expireSessions() {
        val now = OffsetDateTime.now()
        val idleBefore = now.minus(properties.sessionIdleTimeout)
        val abandonBefore = now.minus(properties.sessionAbandonAfter)

        val stale = sessions.findAllByStateOrderByLastUsedAtAsc(ShellSessionState.OPEN)
            .filter { it.lastUsedAt.isBefore(idleBefore) }
        if (stale.isEmpty()) return

        for (session in stale) {
            if (sessionService.expire(session)) continue

            if (session.lastUsedAt.isBefore(abandonBefore)) {
                /*
                 * Given up on, and said out loud once. An administrator reading
                 * this has the host, the path and the reason, which is
                 * everything needed to remove it by hand - and that is the
                 * honest end of this, rather than a retry every ten minutes
                 * about a machine that no longer exists.
                 */
                val shell = shells.findById(session.shellId).map { it.name }.orElse("a removed shell")
                log.warn(
                    "Giving up on shell session {}: {} on {} has not been reachable since {} and the directory " +
                        "may still be there. Remove it by hand if that machine is still yours.",
                    session.id,
                    session.directory,
                    shell,
                    session.lastUsedAt,
                )
                session.state = ShellSessionState.EXPIRED
                session.closedAt = now
                sessions.save(session)
            }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(ShellSessionSweeper::class.java)
    }
}
