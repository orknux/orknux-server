package io.mszymanski.orknux.workflow.execution

import io.mszymanski.orknux.server.OrknuxServer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.time.OffsetDateTime

/**
 * A run left waiting by a restart is carried on. Issue #406.
 *
 * The inline engine spends a wait on the thread carrying the run, so a restart
 * mid-wait kills the only thing that would have woken it and the run sits
 * RUNNING with a WAITING step for ever. The fix reads the wake back: a run still
 * RUNNING whose open step was due long ago is walked on from where it parked.
 *
 * `start` cannot leave such a state - it blocks the wait out and returns a run
 * that finished - so these manufacture the record a dead worker leaves behind
 * and then resume it, which is exactly the situation being recovered.
 *
 * The `enabled=false` keeps the timer off so a pass never fires under a test on
 * its own; the pass is called by hand where it is what is under test.
 */
@SpringBootTest(classes = [OrknuxServer::class])
@Import(ExecutionTestConfig::class)
@TestPropertySource(
    properties = [
        "orknux.temporal.enabled=false",
        "orknux.execution.inline.sweep.enabled=false",
    ],
)
class ParkedRunResumeTest(
    @Autowired val engine: InlineExecutionEngine,
    @Autowired val planner: ExecutionPlanner,
    @Autowired val sweeper: ParkedRunSweeper,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val logs: ExecutionLogRepository,
    @Autowired val graphs: WorkflowGraphSource,
) {

    @BeforeEach
    fun reset() {
        logs.deleteAll()
        steps.deleteAll()
        executions.deleteAll()
        (graphs as FakeWorkflowGraphSource).graphs.clear()
    }

    @Test
    fun `replan carries the finished steps and leaves the parked step and the tail`() {
        straightLineThroughAWait()
        val parked = parkedRun(wokeAt = OffsetDateTime.now().minusHours(1))

        val plan = requireNotNull(planner.replan(requireNotNull(parked.id)))

        // The finished step is carried, not re-run; the parked step and the step
        // it never reached are what is left to do.
        assertThat(plan.steps.map { it.nodeKey }).containsExactly("wait-there", "ok-after")
        assertThat(plan.carried.map { it.nodeKey }).containsExactly("ok-before")
    }

    @Test
    fun `resume finishes a run left parked by a dead worker`() {
        straightLineThroughAWait()
        val parked = parkedRun(wokeAt = OffsetDateTime.now().minusHours(1))

        val finished = requireNotNull(engine.resume(requireNotNull(parked.id)))

        assertThat(finished.status).isEqualTo(ExecutionStatus.COMPLETED)
        val after = stepsOf(finished).associateBy { it.nodeKey }
        // The carried step is untouched; the parked step wakes and finishes, and
        // the run walks on to the step it had never reached.
        assertThat(after.getValue("ok-before").status).isEqualTo(StepStatus.COMPLETED)
        assertThat(after.getValue("wait-there").status).isEqualTo(StepStatus.COMPLETED)
        assertThat(after.getValue("ok-after").status).isEqualTo(StepStatus.COMPLETED)
        assertThat(after.getValue("ok-after").output).isEqualTo("ok-after did the work")
    }

    @Test
    fun `resume carries the branch the parked run had taken, and does not revive the other`() {
        branchingThroughAWait()
        val parked = branchedParkedRun(wokeAt = OffsetDateTime.now().minusHours(1))

        val finished = requireNotNull(engine.resume(requireNotNull(parked.id)))

        assertThat(finished.status).isEqualTo(ExecutionStatus.COMPLETED)
        val after = stepsOf(finished).associateBy { it.nodeKey }
        // The wait sat on the YES side; on resume that side is walked and the NO
        // side stays refused - the condition's recorded answer is replayed into
        // the gate, or the taken node would look like one nothing leads to.
        assertThat(after.getValue("wait-taken").status).isEqualTo(StepStatus.COMPLETED)
        assertThat(after.getValue("ok-taken").status).isEqualTo(StepStatus.COMPLETED)
        assertThat(after.getValue("ok-refused").status).isEqualTo(StepStatus.SKIPPED)
    }

    @Test
    fun `the sweep finds a run parked past its wake and carries it on`() {
        straightLineThroughAWait()
        val parked = parkedRun(wokeAt = OffsetDateTime.now().minusHours(1))

        assertThat(sweeper.sweep()).isEqualTo(1)

        // The carrier runs on its own thread; the run reaches its end there.
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            val now = requireNotNull(executions.findById(requireNotNull(parked.id)).orElse(null))
            assertThat(now.status).isEqualTo(ExecutionStatus.COMPLETED)
        }
    }

    @Test
    fun `a wait still short of its wake is left alone`() {
        straightLineThroughAWait()
        // Due to wake in an hour: a live worker is carrying this, and sweeping it
        // would set a second thread walking the same run.
        val waiting = parkedRun(wokeAt = OffsetDateTime.now().plusHours(1))

        assertThat(sweeper.sweep()).isEqualTo(0)

        val now = requireNotNull(executions.findById(requireNotNull(waiting.id)).orElse(null))
        assertThat(now.status).isEqualTo(ExecutionStatus.RUNNING)
    }

    /** A node, a wait, a node - the shape a run parks in the middle of. */
    private fun straightLineThroughAWait() = graph(
        nodes = listOf(node("ok-before"), node("wait-there"), node("ok-after")),
        edges = listOf(GraphEdge("ok-before", "wait-there"), GraphEdge("wait-there", "ok-after")),
    )

    /** A condition, a wait on its YES side, a node it refused on the NO side. */
    private fun branchingThroughAWait() = graph(
        nodes = listOf(
            node("asks-yes-approved", NodeKind.CONDITION),
            node("wait-taken"),
            node("ok-taken"),
            node("ok-refused"),
        ),
        edges = listOf(
            GraphEdge("asks-yes-approved", "wait-taken", EdgeBranch.YES),
            GraphEdge("wait-taken", "ok-taken"),
            GraphEdge("asks-yes-approved", "ok-refused", EdgeBranch.NO),
        ),
    )

    /**
     * The record a dead worker leaves: the run still RUNNING, the first step
     * done, the middle step WAITING with a wake time on it, the last untouched.
     */
    private fun parkedRun(wokeAt: OffsetDateTime): WorkflowExecution {
        val execution = executions.save(running())
        val id = requireNotNull(execution.id)
        steps.save(done(id, "ok-before", order = 0))
        steps.save(waiting(id, "wait-there", order = 1, wokeAt = wokeAt))
        steps.save(pending(id, "ok-after", order = 2))
        return execution
    }

    private fun branchedParkedRun(wokeAt: OffsetDateTime): WorkflowExecution {
        val execution = executions.save(running())
        val id = requireNotNull(execution.id)
        steps.save(done(id, "asks-yes-approved", order = 0, kind = NodeKind.CONDITION, branch = EdgeBranch.YES))
        steps.save(waiting(id, "wait-taken", order = 1, wokeAt = wokeAt))
        steps.save(pending(id, "ok-taken", order = 2))
        steps.save(pending(id, "ok-refused", order = 3))
        return execution
    }

    private fun running() = WorkflowExecution(
        workspaceId = WORKSPACE,
        workflowId = WORKFLOW,
        workflowName = "Answer the customer",
        status = ExecutionStatus.RUNNING,
        trigger = ExecutionTrigger.API,
        startedAt = OffsetDateTime.now().minusHours(1),
        input = INPUT,
    )

    private fun done(
        executionId: Long,
        nodeKey: String,
        order: Int,
        kind: NodeKind = NodeKind.ACTION,
        branch: EdgeBranch? = null,
    ) = ExecutionStep(
        executionId = executionId,
        nodeKey = nodeKey,
        kind = kind,
        name = nodeKey,
        order = order,
        x = 0.0,
        y = 0.0,
        status = StepStatus.COMPLETED,
        branch = branch,
        input = INPUT,
        output = "$nodeKey did the work",
        startedAt = OffsetDateTime.now().minusHours(1),
        finishedAt = OffsetDateTime.now().minusHours(1),
    )

    private fun waiting(executionId: Long, nodeKey: String, order: Int, wokeAt: OffsetDateTime) = ExecutionStep(
        executionId = executionId,
        nodeKey = nodeKey,
        kind = NodeKind.ACTION,
        name = nodeKey,
        order = order,
        x = 0.0,
        y = 0.0,
        status = StepStatus.WAITING,
        waitUntil = wokeAt,
        startedAt = OffsetDateTime.now().minusHours(1),
    )

    private fun pending(executionId: Long, nodeKey: String, order: Int) = ExecutionStep(
        executionId = executionId,
        nodeKey = nodeKey,
        kind = NodeKind.ACTION,
        name = nodeKey,
        order = order,
        x = 0.0,
        y = 0.0,
        status = StepStatus.PENDING,
    )

    private fun graph(nodes: List<GraphNode>, edges: List<GraphEdge>) {
        (graphs as FakeWorkflowGraphSource).graphs[WORKFLOW] =
            WorkflowGraph(workflowId = WORKFLOW, name = "Answer the customer", nodes = nodes, edges = edges)
    }

    private fun node(key: String, kind: NodeKind = NodeKind.ACTION) =
        GraphNode(key = key, kind = kind, name = key)

    private fun stepsOf(execution: WorkflowExecution) =
        steps.findByExecutionIdOrderByOrderAsc(requireNotNull(execution.id))

    private companion object {
        const val WORKSPACE = 7L
        const val WORKFLOW = 1L
        const val INPUT = """{"ticket":"T-1"}"""
    }
}
