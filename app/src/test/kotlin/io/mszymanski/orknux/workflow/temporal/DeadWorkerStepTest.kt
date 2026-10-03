package io.mszymanski.orknux.workflow.temporal

import io.mszymanski.orknux.server.OrknuxServer
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.workflow.execution.StepRecovery
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
import io.temporal.api.enums.v1.EventType
import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowOptions
import io.temporal.client.WorkflowStub
import io.temporal.testing.WorkflowReplayer
import io.temporal.testing.TestWorkflowEnvironment
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
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
    @Autowired val settings: InstallationSettings,
    @Autowired val recovery: StepRecovery,
) {

    private lateinit var environment: TestWorkflowEnvironment

    /** What the dead attempt is parked on; released only so the test leaves no thread behind. */
    private val dead = CountDownLatch(1)

    /** Released when the dying attempt has begun the step, which is when the process "dies". */
    private val begun = CountDownLatch(1)

    @BeforeEach
    fun start() {
        // Short, so the test is quick; set the way an administrator sets it.
        settings.setWorkflowStepHeartbeatSeconds(HEARTBEAT_SECONDS, "alice")
        executions.deleteAll()
        (graphs as FakeWorkflowGraphSource).graphs.clear()

        environment = TestWorkflowEnvironment.newInstance()
        val worker = environment.newWorker(QUEUE)
        // As TemporalConfig registers it: the deployment's shape - a step
        // timeout and three attempts - with a step timeout long enough to tell
        // from the heartbeat, which comes from the setting by way of the plan.
        TemporalConfig.registerExecutionWorkflow(worker, STEP_OPTIONS)
        worker.registerActivitiesImplementations(DyingWorker(activities))
        environment.start()
    }

    @AfterEach
    fun stop() {
        dead.countDown()
        environment.close()
        // Rows outlive the class that wrote them.
        settings.setWorkflowStepHeartbeatSeconds(settings.workflowStepHeartbeatSecondsConfigured(), "alice")
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
        // As TemporalExecutionEngine starts a run: the heartbeat read from the
        // setting as the run begins.
        val plan = runPlanOf(planned, "what is the capital of France", recovery.stepHeartbeatSeconds())
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
     * A change to the setting reaches a run already going, from its next step,
     * and the run replays as it ran. Issue #601.
     *
     * Activity options are fixed when a stub is made, and workflow code may not
     * read a setting: so the number comes in with the plan and back with every
     * step's report, which are both history. The run parks on its first step;
     * the setting is changed while it waits; the step asked after the change
     * is held to the new number. Then the whole history is replayed against
     * the workflow, which fails on any command the code would issue
     * differently - and passes, because a heartbeat is an option on a command
     * rather than a command.
     */
    @Test
    fun `a heartbeat changed mid-run applies to the steps asked after it, and the run replays`() {
        (graphs as FakeWorkflowGraphSource).graphs[WORKFLOW] = WorkflowGraph(
            workflowId = WORKFLOW,
            name = "Wait, then answer",
            nodes = listOf(
                GraphNode(key = "wait-first", kind = NodeKind.ACTION, name = "wait-first"),
                GraphNode(key = "reply", kind = NodeKind.AGENT, name = "ok-reply"),
            ),
            edges = listOf(GraphEdge("wait-first", "reply")),
        )
        val planned = planner.plan(WORKSPACE, WORKFLOW, ExecutionTrigger.API, "hello")
        val executionId = requireNotNull(planned.execution.id)
        val workflowId = "heartbeat-change-$executionId"
        val stub = environment.workflowClient.newWorkflowStub(
            ExecutionWorkflow::class.java,
            WorkflowOptions.newBuilder().setTaskQueue(QUEUE).setWorkflowId(workflowId).build(),
        )
        WorkflowClient.start(stub::run, runPlanOf(planned, "hello", recovery.stepHeartbeatSeconds()))

        // Parked on its hour-long wait: the first step has been asked once.
        await().atMost(java.time.Duration.ofSeconds(20)).until {
            steps.findByExecutionIdAndNodeKey(executionId, "wait-first")?.status == StepStatus.WAITING
        }
        settings.setWorkflowStepHeartbeatSeconds(CHANGED_SECONDS, "alice")

        val status = WorkflowStub.fromTyped(stub).getResult(ExecutionStatus::class.java)
        assertThat(status).isEqualTo(ExecutionStatus.COMPLETED)

        val history = environment.workflowClient.fetchHistory(workflowId)
        val heartbeats = history.events
            .filter { it.eventType == EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED }
            .map { it.activityTaskScheduledEventAttributes }
            .filter { it.activityType.name.equals("runStep", ignoreCase = true) }
            .map { it.heartbeatTimeout.seconds }
        // The wait asked, the wait asked again after its timer - both on the
        // number the run started with, since the report that carried the
        // change came back from the second - and the step after it on the new.
        assertThat(heartbeats).containsExactly(HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, CHANGED_SECONDS)

        // And the history replays against the workflow as it is, with the
        // setting changed again since: a replay reads the numbers back from the
        // history rather than from the setting.
        settings.setWorkflowStepHeartbeatSeconds(0, "alice")
        // On a worker of its own, registered as a deployment registers it.
        TestWorkflowEnvironment.newInstance().use { replaying ->
            val replayer = replaying.newWorker("replay-$workflowId")
            TemporalConfig.registerExecutionWorkflow(replayer, STEP_OPTIONS)
            WorkflowReplayer.replayWorkflowExecution(history, replayer)
        }
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
        const val CHANGED_SECONDS = 9L
        const val STEP_TIMEOUT_SECONDS = 60L
        val STEP_OPTIONS = TemporalConfig.activityOptions(stepTimeoutSeconds = STEP_TIMEOUT_SECONDS, stepAttempts = 3)
    }
}
