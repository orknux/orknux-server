package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.OrknuxServer
import io.mszymanski.orknux.workflow.execution.Blocking
import io.mszymanski.orknux.workflow.execution.ExecutionEngine
import io.mszymanski.orknux.workflow.execution.ExecutionLogRepository
import io.mszymanski.orknux.workflow.execution.ExecutionService
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.ExecutionTestConfig
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.FakeWorkflowGraphSource
import io.mszymanski.orknux.workflow.execution.GraphEdge
import io.mszymanski.orknux.workflow.execution.GraphNode
import io.mszymanski.orknux.workflow.execution.NodeKind
import io.mszymanski.orknux.workflow.execution.StepRunner
import io.mszymanski.orknux.workflow.execution.StepStatus
import io.mszymanski.orknux.workflow.execution.WorkflowExecution
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import io.mszymanski.orknux.workflow.execution.WorkflowGraph
import io.mszymanski.orknux.workflow.execution.WorkflowGraphSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Stopping a running execution. Issues #395 and #440.
 *
 * The engine's own loop - read the flag, end the run - is a line, and what is
 * pinned here is the state it moves through: a request sets the flag on a run
 * that is still going and nowhere else, and ending a run by request leaves it
 * STOPPED, finished, and saying why. A stopped run is terminal; nothing here
 * resumes it.
 *
 * And the step that is running when the request lands (#440): a step blocked
 * in a model call is cut short rather than left to finish, the run ends within
 * a moment, and the step is recorded as stopped - not completed, and not failed
 * with a retry policy about to spend an attempt on it. The inline engine, on a
 * thread of its own, because the request has to arrive while the step is in
 * flight and the engine does not return until the run is over.
 */
@SpringBootTest(classes = [OrknuxServer::class])
@Import(ExecutionTestConfig::class)
@TestPropertySource(properties = ["orknux.temporal.enabled=false"])
class StopExecutionTest(
    @Autowired val runs: ExecutionService,
    @Autowired val steps: StepRunner,
    @Autowired val engine: ExecutionEngine,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val recorded: ExecutionStepRepository,
    @Autowired val logs: ExecutionLogRepository,
    @Autowired val graphs: WorkflowGraphSource,
) {

    private lateinit var carrier: ExecutorService

    @BeforeEach
    fun reset() {
        (graphs as FakeWorkflowGraphSource).graphs.clear()
        Blocking.reset()
        carrier = Executors.newSingleThreadExecutor()
    }

    @AfterEach
    fun release() {
        carrier.shutdownNow()
    }

    private fun running(): Long = requireNotNull(
        executions.save(
            WorkflowExecution(
                workspaceId = WORKSPACE,
                workflowId = WORKFLOW,
                workflowName = "Nightly report",
                status = ExecutionStatus.RUNNING,
                trigger = ExecutionTrigger.WEBHOOK,
                startedAt = OffsetDateTime.now(),
            ),
        ).id,
    )

    @Test
    fun `a request sets the flag on a running run, and the engine reads it`() {
        val id = running()

        assertThat(steps.wasStopAsked(id)).isFalse()
        runs.requestStop(id)

        assertThat(requireNotNull(executions.findById(id).orElse(null)).stopRequested).isTrue()
        assertThat(steps.wasStopAsked(id)).isTrue()
    }

    @Test
    fun `stopping a run leaves it STOPPED, finished, and saying why`() {
        val id = running()

        val stopped = steps.stopRun(id)

        assertThat(stopped.status).isEqualTo(ExecutionStatus.STOPPED)
        assertThat(stopped.finishedAt).isNotNull()
        assertThat(stopped.stoppedReason).isEqualTo("stopped by request")
    }

    @Test
    fun `a run that has already ended is not stopped, and its flag is left alone`() {
        val id = running()
        // It finished on its own before anyone asked.
        val held = requireNotNull(executions.findById(id).orElse(null))
        held.status = ExecutionStatus.COMPLETED
        executions.save(held)

        runs.requestStop(id)

        val after = requireNotNull(executions.findById(id).orElse(null))
        assertThat(after.status).isEqualTo(ExecutionStatus.COMPLETED)
        assertThat(after.stopRequested).isFalse()
    }

    @Test
    fun `stopping a run that is no longer running changes nothing`() {
        val id = running()
        steps.stopRun(id)

        // A second stop - a duplicate request, a race - does not re-stamp it.
        val again = steps.stopRun(id)
        assertThat(again.status).isEqualTo(ExecutionStatus.STOPPED)
    }

    /**
     * The step is in a model call that honours a hangup and nothing else -
     * an interrupt does not wake a socket read - and it has a retry policy, so
     * a stop mistaken for a failure would park it and try again.
     */
    @Test
    fun `a stop cuts short a step blocked in a model call, and the run ends within a moment`() {
        graph(nodes = listOf(retrying("block-model", attempts = 3), node("ok-onwards")))
        val carried = start()
        val id = Blocking.entered(within = Duration.ofSeconds(10))

        val asked = System.nanoTime()
        runs.requestStop(id)
        val ended = carried.get(1, TimeUnit.SECONDS)

        assertThat(Duration.ofNanos(System.nanoTime() - asked)).isLessThan(Duration.ofSeconds(1))
        assertThat(ended.status).isEqualTo(ExecutionStatus.STOPPED)
        assertThat(ended.stoppedReason).isEqualTo("stopped by request")
        assertThat(ended.finishedAt).isNotNull()

        val cut = stepsBy(ended).getValue("block-model")
        // Stopped: not done, not failed, and saying so where a skipped step says why.
        assertThat(cut.status).isEqualTo(StepStatus.SKIPPED)
        assertThat(cut.output).isEqualTo("Stopped by request before it finished")
        assertThat(cut.error).isNull()
        assertThat(cut.finishedAt).isNotNull()
        // One attempt, and nothing parked: a stop is not a failure to retry.
        assertThat(cut.attempts).isEqualTo(1)
        assertThat(linesOf(ended)).noneMatch { it.contains("Trying again") }
        assertThat(linesOf(ended)).anyMatch { it.contains("block-model was stopped before it finished") }
        // The step after it was never reached.
        assertThat(stepsBy(ended).getValue("ok-onwards").status).isEqualTo(StepStatus.PENDING)
    }

    /**
     * The other way in: a step asleep - the throttle's wait, a Retry-After -
     * hands over no hook, and only the interrupt ends it.
     */
    @Test
    fun `a stop wakes a step that is only sleeping, and the run ends within a moment`() {
        graph(nodes = listOf(retrying("sleep-throttle", attempts = 3), node("ok-onwards")))
        val carried = start()
        val id = Blocking.entered(within = Duration.ofSeconds(10))

        runs.requestStop(id)
        val ended = carried.get(1, TimeUnit.SECONDS)

        assertThat(ended.status).isEqualTo(ExecutionStatus.STOPPED)
        val cut = stepsBy(ended).getValue("sleep-throttle")
        assertThat(cut.status).isEqualTo(StepStatus.SKIPPED)
        assertThat(cut.output).isEqualTo("Stopped by request before it finished")
        assertThat(cut.attempts).isEqualTo(1)
        assertThat(stepsBy(ended).getValue("ok-onwards").status).isEqualTo(StepStatus.PENDING)
    }

    /** A stop that arrives between steps still ends the run the way #395 did. */
    @Test
    fun `a stop asked before a step starts ends the run before it`() {
        graph(nodes = listOf(node("ok-first"), node("ok-second")))
        val id = running()
        runs.requestStop(id)

        // The engine reads the flag on its way into every step; a run already
        // asked to stop has nothing more to do than end.
        assertThat(steps.wasStopAsked(id)).isTrue()
        assertThat(steps.stopRun(id).status).isEqualTo(ExecutionStatus.STOPPED)
    }

    /** The run, started on a thread of its own so a stop can reach it mid-step. */
    private fun start(): Future<WorkflowExecution> =
        carrier.submit(Callable { engine.start(WORKSPACE, WORKFLOW, ExecutionTrigger.API, INPUT) })

    private fun graph(nodes: List<GraphNode>) {
        (graphs as FakeWorkflowGraphSource).graphs[WORKFLOW] = WorkflowGraph(
            workflowId = WORKFLOW,
            name = "Nightly report",
            nodes = nodes,
            edges = nodes.zipWithNext { from, to -> GraphEdge(from.key, to.key) },
        )
    }

    private fun node(key: String) = GraphNode(key = key, kind = NodeKind.ACTION, name = key)

    /** A policy with attempts to spend, so a stop taken for a failure would show as a park. */
    private fun retrying(key: String, attempts: Int) = GraphNode(
        key = key,
        kind = NodeKind.ACTION,
        name = key,
        retryAttempts = attempts,
        retryBackoffSeconds = 0,
    )

    private fun stepsBy(execution: WorkflowExecution) =
        recorded.findByExecutionIdOrderByOrderAsc(requireNotNull(execution.id)).associateBy { it.nodeKey }

    private fun linesOf(execution: WorkflowExecution) =
        logs.findByExecutionIdOrderBySequenceAsc(requireNotNull(execution.id)).map { it.message }

    private companion object {
        const val WORKSPACE = 9L
        const val WORKFLOW = 1L
        const val INPUT = """{"report":"nightly"}"""
    }
}
