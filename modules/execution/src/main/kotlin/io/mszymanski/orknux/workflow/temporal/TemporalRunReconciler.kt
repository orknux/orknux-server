package io.mszymanski.orknux.workflow.temporal

import io.mszymanski.orknux.workflow.execution.StepRunner
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import io.temporal.api.enums.v1.WorkflowExecutionStatus
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Holds the runs still going against what Temporal says about their workflows.
 *
 * A run's ending is written by its workflow, and there are endings a workflow
 * never gets to write: Terminate in Temporal's interface and the run timeout
 * both close it with no more of its code run, and a run cancelled before the
 * workflow learned to record that was closed the same way. The row then says
 * RUNNING for ever, with a Stop button that can reach nothing and no sweep to
 * notice, since `ParkedRunSweeper` is the inline engine's. So this asks.
 *
 * Harmless on every replica at once: an ending is written only over a run still
 * RUNNING, and re-read to find out, so two of these write one ending.
 */
@Component
@ConditionalOnProperty(name = ["orknux.temporal.enabled"], havingValue = "true", matchIfMissing = true)
class TemporalRunReconciler(
    private val client: WorkflowClient,
    private val executions: WorkflowExecutionRepository,
    private val steps: StepRunner,
    private val properties: TemporalProperties,
) : SmartLifecycle {

    /** Built on each start, because a stopped executor cannot be started again. */
    private var clock: ScheduledExecutorService? = null

    override fun start() {
        val every = properties.reconcileInterval.toSeconds().coerceAtLeast(1)
        clock = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "temporal-run-reconcile").apply { isDaemon = true }
        }.also {
            it.scheduleWithFixedDelay(
                { runCatching { reconcile() }.onFailure { failure -> log.warn("Could not reconcile runs", failure) } },
                every,
                every,
                TimeUnit.SECONDS,
            )
        }
    }

    override fun stop() {
        clock?.shutdownNow()
        clock = null
    }

    override fun isRunning(): Boolean = clock != null

    /**
     * One pass. Returns how many runs it ended, which is what a test asserts on.
     *
     * @param before only runs started before this: a run is recorded a moment
     *   before its workflow is started, and must not be found with none.
     */
    fun reconcile(before: OffsetDateTime = OffsetDateTime.now().minus(GRACE)): Int {
        var ended = 0
        for (id in executions.runningStartedBefore(before)) {
            val status = try {
                client.newUntypedWorkflowStub(temporalWorkflowId(id)).describe().status
            } catch (gone: WorkflowNotFoundException) {
                null
            } catch (unreachable: Exception) {
                // Temporal not answering says nothing about any run, and each
                // of the rest would only wait out the same timeout.
                log.debug("Temporal could not say how run {} stands: {}", id, unreachable.message)
                return ended
            }
            when (status) {
                WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_RUNNING,
                WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CONTINUED_AS_NEW,
                -> continue
                // Somebody ended it on purpose, which is what a stop is.
                WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_CANCELED,
                WorkflowExecutionStatus.WORKFLOW_EXECUTION_STATUS_TERMINATED,
                -> steps.stopRun(id)
                null -> steps.abandonRun(id, "Temporal has no record of this run's workflow")
                else -> steps.abandonRun(id, "its workflow ended in Temporal as ${describe(status)}, without finishing the run")
            }
            log.info("Run {} was still marked running, and Temporal says {}", id, status?.let(::describe) ?: "nothing")
            ended += 1
        }
        return ended
    }

    private fun describe(status: WorkflowExecutionStatus): String =
        status.name.removePrefix("WORKFLOW_EXECUTION_STATUS_").lowercase().replace('_', ' ')

    private companion object {
        val log = LoggerFactory.getLogger(TemporalRunReconciler::class.java)

        val GRACE: Duration = Duration.ofMinutes(1)
    }
}
