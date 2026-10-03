package io.mszymanski.orknux.workflow.temporal

import io.mszymanski.orknux.server.OrknuxServer
import io.mszymanski.orknux.workflow.execution.ExecutionPlanner
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.ExecutionTestConfig
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.FakeWorkflowGraphSource
import io.mszymanski.orknux.workflow.execution.GraphEdge
import io.mszymanski.orknux.workflow.execution.GraphNode
import io.mszymanski.orknux.workflow.execution.NodeKind
import io.mszymanski.orknux.workflow.execution.StepStatus
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import io.mszymanski.orknux.workflow.execution.WorkflowGraph
import io.mszymanski.orknux.workflow.execution.WorkflowGraphSource
import io.temporal.activity.Activity
import io.temporal.client.WorkflowOptions
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
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A step whose worker died in the middle of it is handed to a live one, and
 * answers. Issue #601.
 *
 * What a server killed in the middle of an agent's model call leaves Temporal
 * is an activity attempt that never reports back - no failure, no answer, no
 * word at all. Until the step heartbeated, the only thing that noticed was the
 * step's start-to-close timeout: five minutes of a run sitting on a step nothing
 * was running, with the person who asked waiting on the answer for all of them.
 *
 * The dying worker here does what the dead one did: begins the step - the
 * record says RUNNING, one attempt spent - and then says nothing ever again.
 * The activity options are the deployment's own, built by the same function,
 * so what is asserted is what a deployment does: the attempt is timed out by
 * its heartbeat rather than by the clock on the whole step, and the retry runs
 * the step for real and the run finishes with its answer, without a rerun.
 */
@SpringBootTest(classes = [OrknuxServer::class])
@Import(ExecutionTestConfig::class)
@TestPropertySource(properties = ["orknux.temporal.enabled=false"])
class DeadWorkerStepTest(
    @Autowired val planner: ExecutionPlanner,
    @Autowired val activities: ExecutionActivities,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val graphs: WorkflowGraphSource,
) {

    private lateinit var environment: TestWorkflowEnvironment

    /** What the dead attempt is parked on; released only so the test leaves no thread behind. */
    private val dead = CountDownLatch(1)

    /** Released when the dying attempt has begun the step, which is when the process "dies". */
    private val begun = CountDownLatch(1)

    @BeforeEach
    fun start() {
        executions.deleteAll()
        (graphs as FakeWorkflowGraphSource).graphs.clear()

        environment = TestWorkflowEnvironment.newInstance()
        val worker = environment.newWorker(QUEUE)
        worker.registerWorkflowImplementationTypes(
            WorkflowImplementationOptions.newBuilder()
                .setDefaultActivityOptions(
                    // The deployment's shape - a step timeout, three attempts, a
                    // heartbeat - with short numbers so the test is quick, and a
                    // step timeout long enough to tell the two apart.
                    TemporalConfig.activityOptions(
                        stepTimeoutSeconds = STEP_TIMEOUT_SECONDS,
                        stepAttempts = 3,
                        stepHeartbeatSeconds = HEARTBEAT_SECONDS,
                    ),
                )
                .build(),
            ExecutionWorkflowImpl::class.java,
        )
        worker.registerActivitiesImplementations(DyingWorker(activities))
        environment.start()
    }

    @AfterEach
    fun stop() {
        dead.countDown()
        environment.close()
    }

    @Test
    fun `a step whose worker died mid-call is retried on a live one when its heartbeat stops, and answers`() {
        (graphs as FakeWorkflowGraphSource).graphs[WORKFLOW] = WorkflowGraph(
            workflowId = WORKFLOW,
            name = "Answer the message",
            nodes = listOf(
                GraphNode(key = "answer", kind = NodeKind.AGENT, name = "ok-answer"),
                GraphNode(key = "after", kind = NodeKind.ACTION, name = "ok-after"),
            ),
            edges = listOf(GraphEdge("answer", "after")),
        )
        val planned = planner.plan(WORKSPACE, WORKFLOW, ExecutionTrigger.API, "what is the capital of France")
        val executionId = requireNotNull(planned.execution.id)
        val plan = RunPlan(
            executionId = executionId,
            workflowName = planned.execution.workflowName,
            steps = planned.steps.map { it.nodeKey },
            input = "what is the capital of France",
            edges = planned.edges.map { PlanEdge(it.source, it.target, it.branch) },
        )
        val workflowId = "dead-worker-$executionId"
        val stub = environment.workflowClient.newWorkflowStub(
            ExecutionWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(QUEUE).setWorkflowId(workflowId).build(),
        )

        val began = System.nanoTime()
        val status = stub.run(plan)

        assertThat(begun.await(0, TimeUnit.SECONDS)).describedAs("the dying attempt began the step").isTrue()
        assertThat(status).isEqualTo(ExecutionStatus.COMPLETED)
        assertThat(executions.findById(executionId).orElseThrow().status).isEqualTo(ExecutionStatus.COMPLETED)
        val after = steps.findByExecutionIdOrderByOrderAsc(executionId).associateBy { it.nodeKey }
        // Run again for real, on the attempt after the one that died, and its
        // answer is what the run carried on with.
        assertThat(after.getValue("answer").status).isEqualTo(StepStatus.COMPLETED)
        assertThat(after.getValue("answer").output).isEqualTo("ok-answer did the work")
        assertThat(after.getValue("answer").attempts).isEqualTo(2)
        assertThat(after.getValue("after").status).isEqualTo(StepStatus.COMPLETED)
        // One run, not a rerun.
        assertThat(executions.findAll()).hasSize(1)

        // And it was the heartbeat that noticed, not the step timeout. Timed by
        // the wall clock: a running activity holds the test service's clock
        // still, so without a heartbeat this waits out the whole step timeout
        // for real - which is what a deployment waited, at five minutes.
        assertThat(Duration.ofNanos(System.nanoTime() - began))
            .isLessThan(Duration.ofSeconds(STEP_TIMEOUT_SECONDS / 2))
    }

    /**
     * The real activities, except that the first attempt at `answer` is a
     * worker that dies: it begins the step as [io.mszymanski.orknux.workflow.execution.StepRunner]
     * does - RUNNING, an attempt spent - and is then never heard from again,
     * neither an answer nor a heartbeat. Its thread is parked rather than
     * killed only because a test cannot kill one; nothing it does after this
     * point reaches the record.
     */
    private inner class DyingWorker(private val real: ExecutionActivities) : ExecutionActivities {
        // Written out rather than `by real`, which copies the interface's
        // annotations onto the class and Temporal refuses them there.
        override fun failRun(command: FailRunCommand) = real.failRun(command)
        override fun finishRun(command: FinishRunCommand) = real.finishRun(command)
        override fun skipStep(command: SkipStepCommand) = real.skipStep(command)
        override fun recordFailureExit(command: RecordFailureExitCommand) = real.recordFailureExit(command)
        override fun stopRun(command: StopRunCommand) = real.stopRun(command)

        override fun runStep(command: RunStepCommand): StepReport {
            if (command.nodeKey == "answer" && Activity.getExecutionContext().info.attempt == 1) {
                val step = requireNotNull(steps.findByExecutionIdAndNodeKey(command.executionId, command.nodeKey))
                step.status = StepStatus.RUNNING
                step.attempts += 1
                step.startedAt = OffsetDateTime.now()
                steps.save(step)
                begun.countDown()
                dead.await()
                return StepReport(status = StepStatus.SKIPPED)
            }
            return real.runStep(command)
        }
    }

    private companion object {
        const val QUEUE = "dead-worker-test"
        const val WORKSPACE = 7L
        const val WORKFLOW = 1L
        const val HEARTBEAT_SECONDS = 2L
        const val STEP_TIMEOUT_SECONDS = 60L
    }
}
