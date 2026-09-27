package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.workflow.execution.ExecutionLogRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

@ConfigurationProperties(prefix = "orknux.execution.retention")
data class ExecutionRetentionProperties(
    /**
     * How long a finished run is kept, unless an administrator says otherwise.
     *
     * Ninety days rather than the fourteen a component's history gets, because
     * these are two different questions. A revision is a copy of source nobody
     * reads twice; a run is the record of something that actually happened, and
     * the questions asked of it - did it run, what did it do, why did it fail -
     * are asked weeks later. It is the floor a fresh installation starts at
     * rather than a limit on what can be chosen.
     */
    val retentionDays: Int = 90,
    /** False sweeps nothing on a timer. The suite sets it and calls [ExecutionSweeper.sweep]. */
    val sweepEnabled: Boolean = true,
    val sweepInterval: Duration = Duration.ofHours(6),
    val sweepInitialDelay: Duration = Duration.ofMinutes(3),
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExecutionRetentionProperties::class)
class ExecutionRetentionConfig

/**
 * Throws away runs that finished longer ago than was asked, and their steps.
 *
 * Nothing deleted a run before this - no retention, no sweep, and no cascade
 * either, because `workflow_execution` deliberately carries no foreign key on
 * the workspace or the workflow. So the table grew without bound, and 13% of
 * one workspace's runs belonged to workflows it no longer listed. Issue #167.
 *
 * **What it is not is a cascade on removing a workflow.** That mutation means
 * "this workspace stops using this workflow": the definition survives on
 * purpose and may be assigned elsewhere, so deleting the record of runs that
 * really happened as a side effect of an unassign would be a destructive act
 * behind a non-destructive verb.
 *
 * **A run still going is never swept**, whatever the clock says. It is measured
 * from `finishedAt`, which is null until it stops, and the status is asked as
 * well so that a run left unfinished by a crash is not made immortal by it.
 *
 * The steps and the log lines go with it. They are separate tables keyed by the
 * run, with nothing cascading, so a sweep that took only the run would leave
 * both behind - which is the same bug this exists to fix, one table down.
 */
@Component
class ExecutionSweeper(
    private val executions: WorkflowExecutionRepository,
    private val steps: ExecutionStepRepository,
    private val logs: ExecutionLogRepository,
    private val settings: InstallationSettings,
    private val properties: ExecutionRetentionProperties,
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

    /**
     * Armed only where the timer is wanted, and built either way - the reason
     * `RevisionSweeper` gives: the suite turns the timer off so no clock
     * deletes a row mid-test, and still has to be able to call [sweep].
     */
    override fun start() {
        if (!properties.sweepEnabled) {
            log.info("Run history is not swept on a timer")
            return
        }
        val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "execution-sweep").apply { isDaemon = true }
        }
        executor.scheduleWithFixedDelay(
            { runCatching(::sweep).onFailure { log.warn("Could not sweep run history", it) } },
            properties.sweepInitialDelay.toSeconds(),
            properties.sweepInterval.toSeconds(),
            TimeUnit.SECONDS,
        )
        sweeper = executor
        running = true
        log.info("Sweeping run history finished more than {} days ago every {}", retentionDays(), properties.sweepInterval)
    }

    override fun stop() {
        if (!running) return
        sweeper?.shutdownNow()
        sweeper = null
        running = false
    }

    override fun isRunning(): Boolean = running

    /** One pass. Returns how many runs went, which is what a test asserts on. */
    @Transactional
    fun sweep(): Int {
        val cutoff = OffsetDateTime.now().minusDays(retentionDays().toLong())
        val stale = executions.idsFinishedBefore(cutoff)
        if (stale.isEmpty()) return 0

        forget(stale)
        log.info("Swept {} runs that finished before {}", stale.size, cutoff)
        return stale.size
    }

    /**
     * Every run of a workspace that is being deleted.
     *
     * `deleteWorkspace` already has the precedent line for exactly this reason -
     * it calls the connection module's `forgetWorkspace` because nothing
     * cascades across a module boundary. Without the same for runs, a deleted
     * workspace's history stays in the table for ever, reachable by nothing.
     */
    @Transactional
    fun forgetWorkspace(workspaceId: Long): Int {
        val held = executions.idsOfWorkspace(workspaceId)
        if (held.isEmpty()) return 0
        forget(held)
        return held.size
    }

    /**
     * Children first, then the runs.
     *
     * In batches, because a workspace with years of history is one `in (...)`
     * clause no database wants in a single statement.
     */
    private fun forget(ids: List<Long>) {
        ids.chunked(BATCH).forEach { batch ->
            logs.deleteByExecutionIdIn(batch)
            steps.deleteByExecutionIdIn(batch)
            executions.deleteAllByIdInBatch(batch)
        }
    }

    /** What the administrator chose, or what the file said if nobody has. */
    private fun retentionDays(): Int = settings.executionRetentionDays()

    private companion object {
        val log = LoggerFactory.getLogger(ExecutionSweeper::class.java)

        /** Bounded so one pass is many statements rather than one enormous one. */
        const val BATCH = 500
    }
}
