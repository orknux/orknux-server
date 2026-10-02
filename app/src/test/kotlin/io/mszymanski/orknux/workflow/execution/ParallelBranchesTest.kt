package io.mszymanski.orknux.workflow.execution

import io.mszymanski.orknux.server.OrknuxServer
import io.mszymanski.orknux.server.attachment.InstallationSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.ObjectMapper
import java.time.Duration

/**
 * A node with lines to several others, walked side by side. Issue #285.
 *
 * Which nodes run is exactly what it was; what changed is that the paths of a
 * fan-out go at the same time instead of one after the other, and that a
 * failure on one of them no longer stops the others. Where the paths meet the
 * node there waits for all of them and runs once.
 *
 * The inline engine, so a run has finished by the time `start` returns. `nap`
 * nodes work for a second each, which is what tells side by side from one
 * after the other on a clock; see [ScriptedNodeRunner].
 */
@SpringBootTest(classes = [OrknuxServer::class])
@Import(ExecutionTestConfig::class)
@TestPropertySource(properties = ["orknux.temporal.enabled=false"])
class ParallelBranchesTest(
    @Autowired val engine: ExecutionEngine,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val logs: ExecutionLogRepository,
    @Autowired val graphs: WorkflowGraphSource,
    @Autowired val settings: InstallationSettings,
    @Autowired val mapper: ObjectMapper,
    @Autowired val service: ExecutionService,
) {

    @BeforeEach
    fun reset() {
        logs.deleteAll()
        steps.deleteAll()
        executions.deleteAll()
        (graphs as FakeWorkflowGraphSource).graphs.clear()
        settings.setWorkflowStepsAtOnce(settings.workflowStepsAtOnceConfigured(), "test")
    }

    @AfterEach
    fun restore() {
        settings.setWorkflowStepsAtOnce(settings.workflowStepsAtOnceConfigured(), "test")
    }

    @Test
    fun `both paths of a fan-out run, and the node where they meet runs once, after both`() {
        diamond(left = "nap-left", right = "nap-right")
        val run = engine.start(WORKSPACE, WORKFLOW, ExecutionTrigger.API, INPUT)

        assertThat(run.status).isEqualTo(ExecutionStatus.COMPLETED)
        val recorded = stepsBy(run)
        assertThat(recorded.values.map { it.status }).allMatch { it == StepStatus.COMPLETED || it == StepStatus.SKIPPED }
        assertThat(recorded.getValue("left").status).isEqualTo(StepStatus.COMPLETED)
        assertThat(recorded.getValue("right").status).isEqualTo(StepStatus.COMPLETED)

        val join = recorded.getValue("join")
        assertThat(join.status).isEqualTo(StepStatus.COMPLETED)
        assertThat(join.attempts).isEqualTo(1)
        assertThat(join.startedAt).isAfterOrEqualTo(recorded.getValue("left").finishedAt)
        assertThat(join.startedAt).isAfterOrEqualTo(recorded.getValue("right").finishedAt)
        // Once in the log, too: a meeting node that ran per arriving path
        // would have said so twice.
        assertThat(linesOf(run).filter { it.nodeKey == "join" && it.level == LogLevel.SUCCESS }).hasSize(1)

        // What both paths produced reaches it, along with what started the run.
        val handed = mapper.readTree(join.input)
        assertThat(handed.get("left").asString()).isEqualTo("nap-left")
        assertThat(handed.get("right").asString()).isEqualTo("nap-right")
        assertThat(handed.get("ticket").asString()).isEqualTo("T-1")
        // And the run carries all of it on to the end.
        val carried = mapper.readTree(executions.findById(requireNotNull(run.id)).orElseThrow().carried)
        assertThat(carried.get("join").asString()).isEqualTo("put-join")
        assertThat(carried.get("left").asString()).isEqualTo("nap-left")
    }

    /**
     * The measurement the issue is about. Two paths that take a second each
     * finish together in about one second, and their steps overlap on the clock.
     */
    @Test
    fun `the paths of a fan-out run at the same time`() {
        diamond(left = "nap-left", right = "nap-right")
        val began = System.nanoTime()
        val run = engine.start(WORKSPACE, WORKFLOW, ExecutionTrigger.API, INPUT)
        val took = Duration.ofNanos(System.nanoTime() - began)

        assertThat(run.status).isEqualTo(ExecutionStatus.COMPLETED)
        assertThat(took).isLessThan(Duration.ofMillis(1800))

        val left = stepsBy(run).getValue("left")
        val right = stepsBy(run).getValue("right")
        assertThat(left.startedAt).isBefore(right.finishedAt)
        assertThat(right.startedAt).isBefore(left.finishedAt)
    }

    /** The administrator's ceiling: at one, the same graph is walked one path after the other. */
    @Test
    fun `at one step at a time the paths take turns`() {
        settings.setWorkflowStepsAtOnce(1, "test")
        diamond(left = "nap-left", right = "nap-right")
        val began = System.nanoTime()
        val run = engine.start(WORKSPACE, WORKFLOW, ExecutionTrigger.API, INPUT)

        assertThat(run.status).isEqualTo(ExecutionStatus.COMPLETED)
        assertThat(Duration.ofNanos(System.nanoTime() - began)).isGreaterThanOrEqualTo(ScriptedNodeRunner.NAP.multipliedBy(2))
        val left = stepsBy(run).getValue("left")
        val right = stepsBy(run).getValue("right")
        assertThat(left.finishedAt!!.isAfter(right.startedAt) && right.finishedAt!!.isAfter(left.startedAt)).isFalse()
    }

    /**
     * What a split did not fan out stays one after the other: two nodes nothing
     * connects are not a fan-out, and are walked exactly as before.
     */
    @Test
    fun `nodes no fan-out separates still run one after the other`() {
        graph(nodes = listOf(node("nap-one"), node("nap-two")), edges = emptyList())
        val run = engine.start(WORKSPACE, WORKFLOW, ExecutionTrigger.API, INPUT)

        assertThat(run.status).isEqualTo(ExecutionStatus.COMPLETED)
        val one = stepsBy(run).getValue("nap-one")
        val two = stepsBy(run).getValue("nap-two")
        assertThat(two.startedAt).isAfterOrEqualTo(one.finishedAt)
    }

    /**
     * A path a condition refused is not one of the ways into the meeting node,
     * and does not hold it up.
     */
    @Test
    fun `a path a condition closed does not hold up the node where the paths meet`() {
        graph(
            nodes = listOf(
                GraphNode(key = "start", kind = NodeKind.TRIGGER, name = "start"),
                node("left", "nap-left"),
                GraphNode(key = "gate", kind = NodeKind.CONDITION, name = "asks-no-gate"),
                node("right", "put-right"),
                node("join", "put-join"),
            ),
            edges = listOf(
                GraphEdge("start", "left"),
                GraphEdge("start", "gate"),
                GraphEdge("gate", "right", EdgeBranch.YES),
                GraphEdge("left", "join"),
                GraphEdge("right", "join"),
            ),
        )
        val run = engine.start(WORKSPACE, WORKFLOW, ExecutionTrigger.API, INPUT)

        assertThat(run.status).isEqualTo(ExecutionStatus.COMPLETED)
        val recorded = stepsBy(run)
        assertThat(recorded.getValue("gate").branch).isEqualTo(EdgeBranch.NO)
        assertThat(recorded.getValue("right").status).isEqualTo(StepStatus.SKIPPED)
        assertThat(recorded.getValue("join").status).isEqualTo(StepStatus.COMPLETED)
        assertThat(mapper.readTree(recorded.getValue("join").input).has("left")).isTrue()
    }

    /**
     * A failure nothing catches ends its own path and, once the others are
     * done, the run: failed, though the other path went on to its end, and the
     * node where the paths meet never runs.
     */
    @Test
    fun `a failure on one path lets the other finish, and fails the run`() {
        graph(
            nodes = listOf(
                GraphNode(key = "start", kind = NodeKind.TRIGGER, name = "start"),
                node("left", "boom"),
                node("right", "nap-right"),
                node("after-right", "put-after-right"),
                node("join", "put-join"),
            ),
            edges = listOf(
                GraphEdge("start", "left"),
                GraphEdge("start", "right"),
                GraphEdge("right", "after-right"),
                GraphEdge("left", "join"),
                GraphEdge("after-right", "join"),
            ),
        )
        val run = engine.start(WORKSPACE, WORKFLOW, ExecutionTrigger.API, INPUT)

        assertThat(run.status).isEqualTo(ExecutionStatus.FAILED)
        assertThat(run.error).contains("boom has no answer")
        val recorded = stepsBy(run)
        assertThat(recorded.getValue("left").status).isEqualTo(StepStatus.FAILED)
        assertThat(recorded.getValue("right").status).isEqualTo(StepStatus.COMPLETED)
        assertThat(recorded.getValue("after-right").status).isEqualTo(StepStatus.COMPLETED)
        assertThat(recorded.getValue("join").status).isEqualTo(StepStatus.PENDING)
        assertThat(linesOf(run).map { it.message }).anyMatch { it.contains("stopped at left with 1 steps unreached") }
    }

    /** A failure the graph has an answer for is a direction, here as anywhere. */
    @Test
    fun `a failure on one path with a failure edge is taken, and the run finishes`() {
        graph(
            nodes = listOf(
                GraphNode(key = "start", kind = NodeKind.TRIGGER, name = "start"),
                node("left", "boom"),
                node("rescue", "put-rescue"),
                node("right", "nap-right"),
                node("join", "put-join"),
            ),
            edges = listOf(
                GraphEdge("start", "left"),
                GraphEdge("start", "right"),
                GraphEdge("left", "join"),
                GraphEdge("left", "rescue", EdgeBranch.FAILURE),
                GraphEdge("rescue", "join"),
                GraphEdge("right", "join"),
            ),
        )
        val run = engine.start(WORKSPACE, WORKFLOW, ExecutionTrigger.API, INPUT)

        assertThat(run.status).isEqualTo(ExecutionStatus.COMPLETED)
        val recorded = stepsBy(run)
        assertThat(recorded.getValue("left").branch).isEqualTo(EdgeBranch.FAILURE)
        assertThat(recorded.getValue("rescue").status).isEqualTo(StepStatus.COMPLETED)
        assertThat(recorded.getValue("join").status).isEqualTo(StepStatus.COMPLETED)
        val handed = mapper.readTree(recorded.getValue("join").input)
        assertThat(handed.has("rescue") && handed.has("right")).isTrue()
    }

    /**
     * A stop reaches every path that is running, not only one: each step in
     * flight is cut short, and the run ends stopped. Issue #440, said again for
     * a run with more than one step in flight.
     */
    @Test
    fun `a stop cuts short every path in flight`() {
        Blocking.reset()
        graph(
            nodes = listOf(
                GraphNode(key = "start", kind = NodeKind.TRIGGER, name = "start"),
                node("left", "sleep-left"),
                node("right", "sleep-right"),
                node("join", "put-join"),
            ),
            edges = listOf(
                GraphEdge("start", "left"),
                GraphEdge("start", "right"),
                GraphEdge("left", "join"),
                GraphEdge("right", "join"),
            ),
        )
        val running = Thread { engine.start(WORKSPACE, WORKFLOW, ExecutionTrigger.API, INPUT) }
        running.start()
        val executionId = Blocking.entered(Duration.ofSeconds(10))
        assertThat(Blocking.entered(Duration.ofSeconds(10))).isEqualTo(executionId)

        service.requestStop(executionId)
        running.join(Duration.ofSeconds(10).toMillis())
        assertThat(running.isAlive).isFalse()

        val run = executions.findById(executionId).orElseThrow()
        assertThat(run.status).isEqualTo(ExecutionStatus.STOPPED)
        val recorded = steps.findByExecutionIdOrderByOrderAsc(executionId).associateBy { it.nodeKey }
        assertThat(recorded.getValue("left").status).isEqualTo(StepStatus.SKIPPED)
        assertThat(recorded.getValue("right").status).isEqualTo(StepStatus.SKIPPED)
        assertThat(recorded.getValue("join").status).isEqualTo(StepStatus.PENDING)
    }

    /** start fans out to [left] and [right], which meet again at `join`. */
    private fun diamond(left: String, right: String) = graph(
        nodes = listOf(
            GraphNode(key = "start", kind = NodeKind.TRIGGER, name = "start"),
            node("left", left),
            node("right", right),
            node("join", "put-join"),
        ),
        edges = listOf(
            GraphEdge("start", "left"),
            GraphEdge("start", "right"),
            GraphEdge("left", "join"),
            GraphEdge("right", "join"),
        ),
    )

    private fun graph(nodes: List<GraphNode>, edges: List<GraphEdge>) {
        (graphs as FakeWorkflowGraphSource).graphs[WORKFLOW] =
            WorkflowGraph(workflowId = WORKFLOW, name = "Fan-out", nodes = nodes, edges = edges)
    }

    private fun node(key: String, name: String = key) = GraphNode(key = key, kind = NodeKind.ACTION, name = name)

    private fun stepsBy(execution: WorkflowExecution) =
        steps.findByExecutionIdOrderByOrderAsc(requireNotNull(execution.id)).associateBy { it.nodeKey }

    private fun linesOf(execution: WorkflowExecution) =
        logs.findByExecutionIdOrderBySequenceAsc(requireNotNull(execution.id))

    private companion object {
        const val WORKSPACE = 7L
        const val WORKFLOW = 1L
        const val INPUT = """{"ticket":"T-1"}"""
    }
}
