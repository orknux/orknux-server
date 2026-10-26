package io.mszymanski.orknux.workflow.execution

import io.mszymanski.orknux.server.OrknuxServer
import io.mszymanski.orknux.workflow.ExecutionKnob
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource

/**
 * A node that does its work more than once in a pass. Issue #661.
 *
 * Each time is handed what the one before produced, so a node that adds one
 * to what it is given, repeated three times, ends on three. A retry policy is
 * the same node's answer to a failure inside one of those times, and counts
 * afresh for each.
 */
@SpringBootTest(classes = [OrknuxServer::class])
@Import(ExecutionTestConfig::class)
@TestPropertySource(properties = ["orknux.temporal.enabled=false"])
class NodeRepeatTest(
    @Autowired val engine: ExecutionEngine,
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
    fun `a repeated node is handed what it last produced, and the node after it gets the last`() {
        graph(
            nodes = listOf(GraphNode(key = "count", kind = NodeKind.ACTION, name = "count", repeatTimes = 3), node("ok-onwards")),
            edges = listOf(GraphEdge("count", "ok-onwards")),
        )
        val run = engine.start(WORKSPACE, WORKFLOW, ExecutionTrigger.API, INPUT)

        assertThat(run.status).isEqualTo(ExecutionStatus.COMPLETED)
        val counted = stepsBy(run).getValue("count")
        assertThat(counted.output).isEqualTo("3")
        assertThat(counted.repeatsDone).isEqualTo(2)
        assertThat(stepsBy(run).getValue("ok-onwards").input).isEqualTo("3")
        assertThat(linesOf(run)).contains(
            "count ran 1 of 3 times; running it again on what it produced",
            "count ran 2 of 3 times; running it again on what it produced",
        )
    }

    @Test
    fun `a node with no repeat runs once`() {
        graph(nodes = listOf(node("count")), edges = emptyList())
        val run = engine.start(WORKSPACE, WORKFLOW, ExecutionTrigger.API, INPUT)

        assertThat(stepsBy(run).getValue("count").output).isEqualTo("1")
        assertThat(linesOf(run)).noneMatch { it.contains("running it again") }
    }

    @Test
    fun `each time a repeated node runs, its retry policy counts afresh`() {
        graph(
            nodes = listOf(
                GraphNode(
                    key = "flaky-post", kind = NodeKind.ACTION, name = "flaky-post",
                    retryAttempts = 3, retryBackoffSeconds = 0, repeatTimes = 2,
                ),
            ),
            edges = emptyList(),
        )
        val run = engine.start(WORKSPACE, WORKFLOW, ExecutionTrigger.API, INPUT)

        assertThat(run.status).isEqualTo(ExecutionStatus.COMPLETED)
        assertThat(stepsBy(run).getValue("flaky-post").repeatsDone).isEqualTo(1)
        assertThat(linesOf(run).count { it.contains("failed on attempt 1 of 3") }).isEqualTo(2)
    }

    @Test
    fun `a node that fails while repeating fails the run, having done what it did`() {
        graph(nodes = listOf(GraphNode(key = "boom", kind = NodeKind.ACTION, name = "boom", repeatTimes = 3)), edges = emptyList())
        val run = engine.start(WORKSPACE, WORKFLOW, ExecutionTrigger.API, INPUT)

        assertThat(run.status).isEqualTo(ExecutionStatus.FAILED)
        assertThat(stepsBy(run).getValue("boom").repeatsDone).isEqualTo(0)
    }

    @Test
    fun `a run never repeats a node past the installation's most`() {
        graph(
            nodes = listOf(GraphNode(key = "count", kind = NodeKind.ACTION, name = "count", repeatTimes = 1_000_000)),
            edges = emptyList(),
        )
        val run = engine.start(WORKSPACE, WORKFLOW, ExecutionTrigger.API, INPUT)

        assertThat(stepsBy(run).getValue("count").output).isEqualTo(ExecutionKnob.NODE_REPEATS_MOST.fallback.toString())
    }

    private fun graph(nodes: List<GraphNode>, edges: List<GraphEdge>) {
        (graphs as FakeWorkflowGraphSource).graphs[WORKFLOW] =
            WorkflowGraph(workflowId = WORKFLOW, name = "Count to three", nodes = nodes, edges = edges)
    }

    private fun node(key: String) = GraphNode(key = key, kind = NodeKind.ACTION, name = key)

    private fun stepsBy(execution: WorkflowExecution) =
        steps.findByExecutionIdOrderByOrderAsc(requireNotNull(execution.id)).associateBy { it.nodeKey }

    private fun linesOf(execution: WorkflowExecution) =
        logs.findByExecutionIdOrderBySequenceAsc(requireNotNull(execution.id)).map { it.message }

    private companion object {
        const val WORKSPACE = 7L
        const val WORKFLOW = 1L
        const val INPUT = """{"ticket":"T-1"}"""
    }
}
