package io.mszymanski.orknux.workflow.execution

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * What a run with a fan-out starts, skips and waits for. Issue #285.
 *
 * Both engines ask the frontier, so these are the decisions themselves rather
 * than either engine's way of carrying them out.
 */
class FrontierTest {

    /** start -> left, right -> join */
    private val diamond = listOf(
        GraphEdge("start", "left"),
        GraphEdge("start", "right"),
        GraphEdge("left", "join"),
        GraphEdge("right", "join"),
    )

    private fun node(key: String, kind: NodeKind = NodeKind.ACTION) = GraphNode(key = key, kind = kind, name = key)

    private fun frontier(edges: List<GraphEdge>, order: List<String>, limit: Int = 4, splits: Set<String> = setOf("start")) =
        Frontier(order, edges, BranchGate(edges), ParallelLanes(edges, splits), limit)

    private fun Frontier.runs() = next().filterIsInstance<Frontier.Run>().map { it.nodeKey }

    @Test
    fun `a node with lines to two others is a split, and a condition or a decision is not`() {
        val graph = WorkflowGraph(
            workflowId = 1,
            name = "shapes",
            nodes = listOf(
                node("start", NodeKind.TRIGGER), node("left"), node("right"), node("join"),
                node("ask", NodeKind.CONDITION), node("pick", NodeKind.DECISION), node("rescue"),
                node("yes"), node("no"), node("a"), node("b"),
            ),
            edges = diamond + listOf(
                GraphEdge("ask", "yes", EdgeBranch.YES),
                GraphEdge("ask", "no", EdgeBranch.NO),
                GraphEdge("pick", "a", EdgeBranch.OPTION, "a"),
                GraphEdge("pick", "b", EdgeBranch.OPTION, "b"),
                // A line out and a failure edge are not two paths walked together.
                GraphEdge("left", "rescue", EdgeBranch.FAILURE),
            ),
        )
        assertThat(graph.splits()).containsExactly("start")
    }

    @Test
    fun `a chain has no splits`() {
        val graph = WorkflowGraph(
            workflowId = 1,
            name = "chain",
            nodes = listOf(node("a"), node("b"), node("c")),
            edges = listOf(GraphEdge("a", "b"), GraphEdge("b", "c")),
        )
        assertThat(graph.splits()).isEmpty()
    }

    @Test
    fun `only nodes a split sent different ways may overlap`() {
        val edges = diamond + listOf(GraphEdge("left", "deeper"), GraphEdge("other", "join"))
        val lanes = ParallelLanes(edges, setOf("start"))

        assertThat(lanes.mayOverlap("left", "right")).isTrue()
        assertThat(lanes.mayOverlap("deeper", "right")).isTrue()
        // Where the paths meet is in neither, and the split itself in none.
        assertThat(lanes.mayOverlap("join", "left")).isFalse()
        assertThat(lanes.mayOverlap("start", "left")).isFalse()
        // One path's own nodes follow one another.
        assertThat(lanes.mayOverlap("left", "deeper")).isFalse()
        // And something no split fanned out overlaps nothing.
        assertThat(lanes.mayOverlap("other", "left")).isFalse()
    }

    @Test
    fun `both paths start together, and the meeting node waits for both`() {
        val frontier = frontier(diamond, listOf("start", "left", "right", "join"))

        assertThat(frontier.runs()).containsExactly("start")
        frontier.completed("start", null, null, halt = false, output = null)
        assertThat(frontier.runs()).containsExactly("left", "right")

        frontier.completed("left", null, null, halt = false, output = null)
        assertThat(frontier.runs()).isEmpty()
        frontier.completed("right", null, null, halt = false, output = null)
        assertThat(frontier.runs()).containsExactly("join")
        frontier.completed("join", null, null, halt = false, output = null)

        assertThat(frontier.next()).isEmpty()
        assertThat(frontier.idle).isTrue()
        assertThat(frontier.failure).isNull()
    }

    @Test
    fun `the limit holds the second path back until the first is done`() {
        val frontier = frontier(diamond, listOf("start", "left", "right", "join"), limit = 1)

        frontier.runs()
        frontier.completed("start", null, null, halt = false, output = null)
        assertThat(frontier.runs()).containsExactly("left")
        frontier.completed("left", null, null, halt = false, output = null)
        assertThat(frontier.runs()).containsExactly("right")
    }

    @Test
    fun `a failure ends its own path, and the run is failed with it once the rest is done`() {
        val edges = diamond + listOf(GraphEdge("right", "after"))
        val frontier = frontier(edges, listOf("start", "left", "right", "after", "join"))

        frontier.runs()
        frontier.completed("start", null, null, halt = false, output = null)
        frontier.runs()
        assertThat(frontier.failed("left", "left broke")).isFalse()
        frontier.completed("right", null, null, halt = false, output = null)
        assertThat(frontier.runs()).containsExactly("after")
        frontier.completed("after", null, null, halt = false, output = null)

        assertThat(frontier.runs()).isEmpty()
        assertThat(frontier.idle).isTrue()
        assertThat(frontier.failure).isEqualTo(DeadEnd("left", "left broke"))
        assertThat(frontier.unreachedCount).isEqualTo(1)
    }

    @Test
    fun `a failure with a failure edge goes down it`() {
        val edges = diamond + listOf(GraphEdge("left", "rescue", EdgeBranch.FAILURE), GraphEdge("rescue", "join"))
        val frontier = frontier(edges, listOf("start", "left", "rescue", "right", "join"))

        frontier.runs()
        frontier.completed("start", null, null, halt = false, output = null)
        frontier.runs()
        assertThat(frontier.failed("left", "left broke")).isTrue()
        assertThat(frontier.runs()).containsExactly("rescue")
        frontier.completed("rescue", null, null, halt = false, output = null)
        frontier.completed("right", null, null, halt = false, output = null)
        assertThat(frontier.runs()).containsExactly("join")
        assertThat(frontier.failure).isNull()
    }

    @Test
    fun `a path a condition closed is skipped, and does not hold up the meeting node`() {
        val edges = listOf(
            GraphEdge("start", "left"),
            GraphEdge("start", "ask"),
            GraphEdge("ask", "right", EdgeBranch.YES),
            GraphEdge("left", "join"),
            GraphEdge("right", "join"),
        )
        val frontier = frontier(edges, listOf("start", "left", "ask", "right", "join"))

        frontier.runs()
        frontier.completed("start", null, null, halt = false, output = null)
        assertThat(frontier.runs()).containsExactly("left", "ask")
        frontier.completed("ask", EdgeBranch.NO, null, halt = true, output = "no")
        assertThat(frontier.next()).containsExactly(Frontier.Skip("right", "the condition before it went the other way"))
        frontier.completed("left", null, null, halt = false, output = null)
        assertThat(frontier.runs()).containsExactly("join")
        assertThat(frontier.halt).isNull()
    }

    @Test
    fun `asked to stop, nothing new starts`() {
        val frontier = frontier(diamond, listOf("start", "left", "right", "join"))

        frontier.runs()
        frontier.completed("start", null, null, halt = false, output = null)
        frontier.runs()
        frontier.stopped("left")
        frontier.completed("right", null, null, halt = false, output = null)

        assertThat(frontier.runs()).isEmpty()
        assertThat(frontier.idle).isTrue()
        assertThat(frontier.stopping).isTrue()
    }
}
