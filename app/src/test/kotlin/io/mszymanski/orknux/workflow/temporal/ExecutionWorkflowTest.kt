package io.mszymanski.orknux.workflow.temporal

import io.mszymanski.orknux.server.OrknuxServer
import io.mszymanski.orknux.workflow.execution.Blocking
import io.mszymanski.orknux.workflow.execution.ExecutionPlanner
import io.mszymanski.orknux.workflow.execution.ExecutionService
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.EdgeBranch
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.ExecutionLogRepository
import io.mszymanski.orknux.workflow.execution.FakeWorkflowGraphSource
import io.mszymanski.orknux.workflow.execution.ExecutionTestConfig
import io.mszymanski.orknux.workflow.execution.GraphEdge
import io.mszymanski.orknux.workflow.execution.GraphNode
import io.mszymanski.orknux.workflow.execution.LogLevel
import io.mszymanski.orknux.workflow.execution.NodeKind
import io.mszymanski.orknux.workflow.execution.StepStatus
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import io.mszymanski.orknux.workflow.execution.WorkflowGraph
import io.mszymanski.orknux.workflow.execution.WorkflowGraphSource
import io.temporal.activity.ActivityOptions
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.common.RetryOptions
import io.temporal.testing.TestWorkflowEnvironment
import io.temporal.worker.WorkflowImplementationOptions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * The interpreter, against Temporal's in-process test service: a real workflow
 * execution, real activities and the real database, without a Temporal service
 * to bring up.
 *
 * Temporal is disabled in the context, so nothing tries to reach one at
 * startup; the client under test is the environment's own.
 */
@SpringBootTest(classes = [OrknuxServer::class])
@Import(ExecutionTestConfig::class)
@TestPropertySource(properties = ["orknux.temporal.enabled=false"])
class ExecutionWorkflowTest(
    @Autowired val planner: ExecutionPlanner,
    @Autowired val activities: ExecutionActivities,
    @Autowired val runs: ExecutionService,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val logs: ExecutionLogRepository,
    @Autowired val graphs: WorkflowGraphSource,
) {

    private lateinit var environment: TestWorkflowEnvironment

    @BeforeEach
    fun start() {
        executions.deleteAll()
        (graphs as FakeWorkflowGraphSource).graphs.clear()

        environment = TestWorkflowEnvironment.newInstance()
        val worker = environment.newWorker(QUEUE)
        worker.registerWorkflowImplementationTypes(
            WorkflowImplementationOptions.newBuilder()
                .setDefaultActivityOptions(
                    ActivityOptions.newBuilder()
                        .setStartToCloseTimeout(Duration.ofSeconds(30))
                        // Two, so a step that keeps failing proves it is tried
                        // again rather than only that it failed.
                        .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
                        .build(),
                )
                .build(),
            ExecutionWorkflowImpl::class.java,
        )
        worker.registerActivitiesImplementations(activities)
        environment.start()
    }

    @AfterEach
    fun stop() {
        environment.close()
    }

    @Test
    fun `walks the graph, hands each step's output to the next, and finishes the run`() {
        val plan = plan(
            WorkflowGraph(
                workflowId = WORKFLOW,
                name = "Data Processing Pipeline",
                nodes = listOf(node("ok"), node("ok-2"), node("nothing")),
                edges = listOf(GraphEdge("ok", "ok-2"), GraphEdge("ok-2", "nothing")),
            ),
        )

        val status = workflow().run(plan)

        assertThat(status).isEqualTo(ExecutionStatus.COMPLETED)
        val executionId = plan.executionId
        assertThat(executions.findById(executionId).orElseThrow().status).isEqualTo(ExecutionStatus.COMPLETED)

        val recorded = steps.findByExecutionIdOrderByOrderAsc(executionId)
        assertThat(recorded.map { it.nodeKey }).containsExactly("ok", "ok-2", "nothing")
        assertThat(recorded.map { it.status })
            .containsExactly(StepStatus.COMPLETED, StepStatus.COMPLETED, StepStatus.SKIPPED)
        assertThat(recorded[0].input).isEqualTo("start here")
        assertThat(recorded[1].input).isEqualTo("ok did the work")
        // The step with no runtime passed on what it was handed.
        assertThat(recorded[2].input).isEqualTo("ok-2 did the work")
    }

    @Test
    fun `a step that parks is asked again on a timer, holding no worker while it waits`() {
        val plan = plan(
            WorkflowGraph(
                workflowId = WORKFLOW,
                name = "Change Approval",
                nodes = listOf(node("ok"), node("wait-for-approval"), node("ok-2")),
                edges = listOf(GraphEdge("ok", "wait-for-approval"), GraphEdge("wait-for-approval", "ok-2")),
            ),
        )

        val status = workflow().run(plan)

        // The node parked for an hour, and the worker here is registered with a
        // thirty-second step timeout: the run could only have got past it by
        // waiting on Temporal's clock rather than in the activity.
        assertThat(status).isEqualTo(ExecutionStatus.COMPLETED)

        val recorded = steps.findByExecutionIdOrderByOrderAsc(plan.executionId)
        assertThat(recorded.map { it.status })
            .containsExactly(StepStatus.COMPLETED, StepStatus.COMPLETED, StepStatus.COMPLETED)

        // The deadline it parked against is still on the step, which is what a
        // wait resumed on another worker reads instead of starting again.
        val waited = recorded[1]
        assertThat(waited.waitUntil).isNotNull()
        assertThat(waited.output).isEqualTo("wait-for-approval did the work")
        // And what it was waiting on reached the node after it.
        assertThat(recorded[2].input).isEqualTo("wait-for-approval did the work")

        // Twice through the node, but the log says it is waiting once: a wait
        // that asks every thirty seconds must not bury the run's own lines.
        val lines = logs.findByExecutionIdOrderBySequenceAsc(plan.executionId).map { it.message }
        assertThat(lines.filter { it.contains("is waiting") }).hasSize(1)
    }

    @Test
    fun `tries a failing step again, then stops the run where it failed`() {
        val plan = plan(
            WorkflowGraph(
                workflowId = WORKFLOW,
                name = "Nightly Report",
                nodes = listOf(node("ok"), node("boom"), node("after")),
                edges = listOf(GraphEdge("ok", "boom"), GraphEdge("boom", "after")),
            ),
        )

        val status = workflow().run(plan)

        assertThat(status).isEqualTo(ExecutionStatus.FAILED)
        val execution = executions.findById(plan.executionId).orElseThrow()
        assertThat(execution.status).isEqualTo(ExecutionStatus.FAILED)
        assertThat(execution.error).isEqualTo("boom has no answer")

        assertThat(steps.findByExecutionIdOrderByOrderAsc(plan.executionId).map { it.status })
            .containsExactly(StepStatus.COMPLETED, StepStatus.FAILED, StepStatus.PENDING)

        // Two attempts at the step, so two failures in the log, and the run's
        // own line after them.
        val failures = logs.findByExecutionIdOrderBySequenceAsc(plan.executionId)
            .filter { it.level == LogLevel.ERROR }
        assertThat(failures.filter { it.nodeKey == "boom" }).hasSize(2)
        assertThat(failures.last().message).contains("stopped at boom with 1 steps unreached")
    }

    /**
     * The same run the inline engine takes, carried by Temporal: a step whose
     * failure the graph has an answer for leaves by its failure edge, and the
     * run finishes rather than stopping. A run that took different paths
     * depending on which engine carried it would be the worst kind of
     * difference, so this is the inline test said again here.
     */
    @Test
    fun `a failure the graph has an answer for takes the failure edge and the run finishes`() {
        val plan = plan(
            WorkflowGraph(
                workflowId = WORKFLOW,
                name = "Nightly Report",
                nodes = listOf(node("boom"), node("ok-onwards"), node("ok-rescue")),
                edges = listOf(
                    GraphEdge("boom", "ok-onwards"),
                    GraphEdge("boom", "ok-rescue", EdgeBranch.FAILURE),
                ),
            ),
        )

        val status = workflow().run(plan)

        assertThat(status).isEqualTo(ExecutionStatus.COMPLETED)
        val recorded = steps.findByExecutionIdOrderByOrderAsc(plan.executionId).associateBy { it.nodeKey }
        assertThat(recorded.getValue("boom").status).isEqualTo(StepStatus.FAILED)
        assertThat(recorded.getValue("boom").branch).isEqualTo(EdgeBranch.FAILURE)
        assertThat(recorded.getValue("ok-rescue").status).isEqualTo(StepStatus.COMPLETED)
        assertThat(recorded.getValue("ok-onwards").status).isEqualTo(StepStatus.SKIPPED)
    }

    /**
     * The reconciliation, asserted where it could go wrong.
     *
     * Temporal retries an activity of its own accord — twice, in this worker —
     * so a node's own policy left to throw would be multiplied by that. It is
     * not, because a policy retries by parking the step and asking again, which
     * the activity reports as an ordinary answer: three attempts asked for,
     * three performed, and the third one works.
     */
    @Test
    fun `a node's own retry policy is what it gets, and not that times Temporal's`() {
        val plan = plan(
            WorkflowGraph(
                workflowId = WORKFLOW,
                name = "Nightly Report",
                nodes = listOf(retrying("flaky-post", attempts = 3)),
                edges = emptyList(),
            ),
        )

        val status = workflow().run(plan)

        assertThat(status).isEqualTo(ExecutionStatus.COMPLETED)
        val flaky = steps.findByExecutionIdOrderByOrderAsc(plan.executionId).single()
        assertThat(flaky.status).isEqualTo(StepStatus.COMPLETED)
        assertThat(flaky.attempts).isEqualTo(3)
    }

    /**
     * And the other end of it: a policy that runs out is a settled failure, so
     * Temporal does not start again underneath it. Two attempts asked for, two
     * performed — not two more on top.
     */
    @Test
    fun `a policy that runs out is not tried again by Temporal`() {
        val plan = plan(
            WorkflowGraph(
                workflowId = WORKFLOW,
                name = "Nightly Report",
                nodes = listOf(retrying("boom", attempts = 2)),
                edges = emptyList(),
            ),
        )

        val status = workflow().run(plan)

        assertThat(status).isEqualTo(ExecutionStatus.FAILED)
        assertThat(steps.findByExecutionIdOrderByOrderAsc(plan.executionId).single().attempts).isEqualTo(2)
    }

    /**
     * A node switched off is walked through, not run. Issue #439.
     *
     * The middle node is `boom`, which fails the moment any runner is asked
     * about it - so a run that finishes is a run that asked no runner. The
     * step is on the record as skipped, saying why, and the node after it is
     * handed exactly what the node before it produced.
     */
    @Test
    fun `a disabled node is skipped, saying so, and its input goes on to the next node unchanged`() {
        val plan = plan(
            WorkflowGraph(
                workflowId = WORKFLOW,
                name = "Nightly Report",
                nodes = listOf(node("ok"), node("boom", enabled = false), node("ok-2")),
                edges = listOf(GraphEdge("ok", "boom"), GraphEdge("boom", "ok-2")),
            ),
        )

        val status = workflow().run(plan)

        assertThat(status).isEqualTo(ExecutionStatus.COMPLETED)
        val recorded = steps.findByExecutionIdOrderByOrderAsc(plan.executionId)
        assertThat(recorded.map { it.status })
            .containsExactly(StepStatus.COMPLETED, StepStatus.SKIPPED, StepStatus.COMPLETED)
        assertThat(recorded[1].output).isEqualTo("Skipped: the node is disabled")
        assertThat(recorded[2].input).isEqualTo("ok did the work")

        val lines = logs.findByExecutionIdOrderBySequenceAsc(plan.executionId).map { it.message }
        assertThat(lines).contains("Skipped: the node is disabled")
    }

    /**
     * A disabled condition lets the run through. The node here would answer no
     * and stop the run if it were asked, so the "yes" path running is the proof
     * that it was not - and the "no" path is skipped for the reason a branch
     * not taken always is.
     */
    @Test
    fun `a disabled condition takes its yes way out`() {
        val plan = plan(
            WorkflowGraph(
                workflowId = WORKFLOW,
                name = "Nightly Report",
                nodes = listOf(
                    node("ok"),
                    GraphNode(key = "gate", kind = NodeKind.CONDITION, name = "asks-no", enabled = false),
                    node("ok-through"),
                    node("ok-refused"),
                ),
                edges = listOf(
                    GraphEdge("ok", "gate"),
                    GraphEdge("gate", "ok-through", EdgeBranch.YES),
                    GraphEdge("gate", "ok-refused", EdgeBranch.NO),
                ),
            ),
        )

        val status = workflow().run(plan)

        assertThat(status).isEqualTo(ExecutionStatus.COMPLETED)
        val recorded = steps.findByExecutionIdOrderByOrderAsc(plan.executionId).associateBy { it.nodeKey }
        assertThat(recorded.getValue("gate").status).isEqualTo(StepStatus.SKIPPED)
        assertThat(recorded.getValue("gate").branch).isEqualTo(EdgeBranch.YES)
        assertThat(recorded.getValue("ok-through").status).isEqualTo(StepStatus.COMPLETED)
        assertThat(recorded.getValue("ok-through").input).isEqualTo("ok did the work")
        assertThat(recorded.getValue("ok-refused").status).isEqualTo(StepStatus.SKIPPED)
    }

    /**
     * A stop that lands mid-step, carried by Temporal. Issue #440.
     *
     * The activity runs on a worker thread in this process, where the step's
     * interrupt can reach it; what is asserted is that the activity answers a
     * stop as a report rather than a failure - so Temporal has nothing to retry
     * - and that the workflow ends the run STOPPED on reading it, the same
     * ending the inline engine gives.
     */
    @Test
    fun `a stop cuts short a step mid-call, and the workflow ends the run stopped`() {
        Blocking.reset()
        val plan = plan(
            WorkflowGraph(
                workflowId = WORKFLOW,
                name = "Nightly Report",
                nodes = listOf(retrying("block-model", attempts = 3), node("ok-onwards")),
                edges = listOf(GraphEdge("block-model", "ok-onwards")),
            ),
        )
        val stub = workflow()
        WorkflowClient.start(stub::run, plan)
        assertThat(Blocking.entered(within = Duration.ofSeconds(10))).isEqualTo(plan.executionId)

        runs.requestStop(plan.executionId)
        val status = WorkflowStub.fromTyped(stub).getResult(5, TimeUnit.SECONDS, ExecutionStatus::class.java)

        assertThat(status).isEqualTo(ExecutionStatus.STOPPED)
        val execution = executions.findById(plan.executionId).orElseThrow()
        assertThat(execution.status).isEqualTo(ExecutionStatus.STOPPED)
        assertThat(execution.stoppedReason).isEqualTo("stopped by request")

        val recorded = steps.findByExecutionIdOrderByOrderAsc(plan.executionId).associateBy { it.nodeKey }
        assertThat(recorded.getValue("block-model").status).isEqualTo(StepStatus.SKIPPED)
        assertThat(recorded.getValue("block-model").output).isEqualTo("Stopped by request before it finished")
        // Once: neither the node's policy nor Temporal's retries spent another attempt on it.
        assertThat(recorded.getValue("block-model").attempts).isEqualTo(1)
        assertThat(recorded.getValue("ok-onwards").status).isEqualTo(StepStatus.PENDING)
    }

    private fun workflow() = environment.workflowClient.newWorkflowStub(
        ExecutionWorkflow::class.java,
        WorkflowOptions.newBuilder().setTaskQueue(QUEUE).build(),
    )

    /** Records the run the way [TemporalExecutionEngine] would, then hands it over. */
    private fun plan(graph: WorkflowGraph): RunPlan {
        (graphs as FakeWorkflowGraphSource).graphs[WORKFLOW] = graph
        val planned = planner.plan(WORKSPACE, WORKFLOW, ExecutionTrigger.API, "start here")
        return RunPlan(
            executionId = requireNotNull(planned.execution.id),
            workflowName = planned.execution.workflowName,
            steps = planned.steps.map { it.nodeKey },
            input = "start here",
            edges = planned.edges.map { PlanEdge(it.source, it.target, it.branch) },
        )
    }

    private fun node(key: String, enabled: Boolean = true) =
        GraphNode(key = key, kind = NodeKind.ACTION, name = key, enabled = enabled)

    /** No wait between attempts: what is under test is the count, not the clock. */
    private fun retrying(key: String, attempts: Int) =
        GraphNode(key = key, kind = NodeKind.ACTION, name = key, retryAttempts = attempts, retryBackoffSeconds = 0)

    private companion object {
        const val QUEUE = "execution-workflow-test"
        const val WORKSPACE = 7L
        const val WORKFLOW = 1L
    }
}
