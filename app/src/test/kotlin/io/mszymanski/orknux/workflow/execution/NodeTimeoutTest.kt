package io.mszymanski.orknux.workflow.execution

import io.mszymanski.orknux.server.OrknuxServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource

/**
 * A node's timeout: one attempt of its work may take so long and no longer.
 *
 * Every step here would run far past the test's patience on its own - a call
 * deaf to the interrupt that only a hangup ends, a sleep that only the
 * interrupt ends, a wait of an hour - and each is given a second. What has to
 * hold is that the step is cut short rather than abandoned, fails saying why,
 * and that the failure is an ordinary one: the failure edge is taken, the
 * retry policy tries again.
 */
@SpringBootTest(classes = [OrknuxServer::class])
@Import(ExecutionTestConfig::class)
@TestPropertySource(properties = ["orknux.temporal.enabled=false"])
class NodeTimeoutTest(
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
        Blocking.reset()
    }

    /** An action stuck in a call only a hangup ends, the way a socket read is. */
    @Test
    fun `an action that runs past its timeout is hung up on and fails, saying so`() {
        graph(listOf(GraphNode(key = "block-call", kind = NodeKind.ACTION, name = "block-call", timeoutSeconds = 1)))
        val started = System.nanoTime()
        val run = engine.start(WORKSPACE, WORKFLOW, ExecutionTrigger.API, INPUT)

        assertThat(elapsedSeconds(started)).isLessThan(10)
        assertThat(run.status).isEqualTo(ExecutionStatus.FAILED)
        val step = stepsBy(run).getValue("block-call")
        assertThat(step.status).isEqualTo(StepStatus.FAILED)
        assertThat(step.error).isEqualTo("block-call took longer than its timeout of 1s")
    }

    /** An agent's model call is a hangup too; this one only wakes on the interrupt. */
    @Test
    fun `an agent that runs past its timeout fails, and its failure edge is taken`() {
        graph(
            nodes = listOf(
                GraphNode(key = "sleep-agent", kind = NodeKind.AGENT, name = "sleep-agent", timeoutSeconds = 1),
                GraphNode(key = "ok-rescue", kind = NodeKind.ACTION, name = "ok-rescue"),
            ),
            edges = listOf(GraphEdge("sleep-agent", "ok-rescue", EdgeBranch.FAILURE)),
        )
        val run = engine.start(WORKSPACE, WORKFLOW, ExecutionTrigger.API, INPUT)

        val recorded = stepsBy(run)
        assertThat(recorded.getValue("sleep-agent").error).isEqualTo("sleep-agent took longer than its timeout of 1s")
        assertThat(recorded.getValue("sleep-agent").branch).isEqualTo(EdgeBranch.FAILURE)
        assertThat(recorded.getValue("ok-rescue").status).isEqualTo(StepStatus.COMPLETED)
        assertThat(run.status).isEqualTo(ExecutionStatus.COMPLETED)
    }

    /** A wait inside the work counts: an hour's park is woken at the deadline and fails. */
    @Test
    fun `a wait inside the work counts against the timeout`() {
        graph(listOf(GraphNode(key = "wait-reply", kind = NodeKind.ACTION, name = "wait-reply", timeoutSeconds = 1)))
        val started = System.nanoTime()
        val run = engine.start(WORKSPACE, WORKFLOW, ExecutionTrigger.API, INPUT)

        assertThat(elapsedSeconds(started)).isLessThan(10)
        assertThat(stepsBy(run).getValue("wait-reply").error).isEqualTo("wait-reply took longer than its timeout of 1s")
    }

    /** Each attempt has a timeout of its own, and a timed-out one is retried as any failure is. */
    @Test
    fun `a retry policy tries a timed-out step again, each attempt with its own time`() {
        graph(
            listOf(
                GraphNode(
                    key = "block-call", kind = NodeKind.ACTION, name = "block-call",
                    timeoutSeconds = 1, retryAttempts = 2, retryBackoffSeconds = 0,
                ),
            ),
        )
        val run = engine.start(WORKSPACE, WORKFLOW, ExecutionTrigger.API, INPUT)

        assertThat(run.status).isEqualTo(ExecutionStatus.FAILED)
        assertThat(stepsBy(run).getValue("block-call").attempts).isEqualTo(2)
        assertThat(linesOf(run)).anyMatch { it.contains("failed on attempt 1 of 2: block-call took longer than its timeout of 1s") }
    }

    @Test
    fun `a node with no timeout runs as long as its work takes`() {
        graph(listOf(GraphNode(key = "nap-a", kind = NodeKind.ACTION, name = "nap-a")))
        val run = engine.start(WORKSPACE, WORKFLOW, ExecutionTrigger.API, INPUT)

        assertThat(run.status).isEqualTo(ExecutionStatus.COMPLETED)
    }

    @Test
    fun `work done within its timeout is done`() {
        graph(listOf(GraphNode(key = "nap-a", kind = NodeKind.ACTION, name = "nap-a", timeoutSeconds = 5)))
        val run = engine.start(WORKSPACE, WORKFLOW, ExecutionTrigger.API, INPUT)

        assertThat(run.status).isEqualTo(ExecutionStatus.COMPLETED)
        assertThat(stepsBy(run).getValue("nap-a").error).isNull()
    }

    private fun graph(nodes: List<GraphNode>, edges: List<GraphEdge> = emptyList()) {
        (graphs as FakeWorkflowGraphSource).graphs[WORKFLOW] =
            WorkflowGraph(workflowId = WORKFLOW, name = "Timed", nodes = nodes, edges = edges)
    }

    private fun elapsedSeconds(since: Long) = (System.nanoTime() - since) / 1_000_000_000

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
