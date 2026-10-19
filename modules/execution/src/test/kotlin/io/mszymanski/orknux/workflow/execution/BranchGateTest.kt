package io.mszymanski.orknux.workflow.execution

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * What runs after a condition answers.
 *
 * The gate is the whole of branching: both engines ask it the same two
 * questions, so a run takes the same path whichever one carries it. These are
 * the shapes somebody actually draws.
 */
class BranchGateTest {

    /** trigger -> ask -> (yes) shout, (no) file */
    private fun fork() = listOf(
        GraphEdge("trigger", "ask"),
        GraphEdge("ask", "shout", EdgeBranch.YES),
        GraphEdge("ask", "file", EdgeBranch.NO),
    )

    @Test
    fun `a yes runs the yes side and leaves the no side alone`() {
        val gate = BranchGate(fork())

        // A beginning always runs; so does what the trigger leads to.
        assertThat(gate.mayRun("trigger")).isTrue()
        gate.follow("trigger", null)
        assertThat(gate.mayRun("ask")).isTrue()

        gate.follow("ask", EdgeBranch.YES)
        assertThat(gate.mayRun("shout")).isTrue()
        assertThat(gate.mayRun("file")).isFalse()
    }

    @Test
    fun `a no runs the other one`() {
        val gate = BranchGate(fork())
        gate.follow("trigger", null)
        gate.follow("ask", EdgeBranch.NO)

        assertThat(gate.mayRun("shout")).isFalse()
        assertThat(gate.mayRun("file")).isTrue()
    }

    /**
     * A join: both sides lead to the same node.
     *
     * One taken path is enough - which is what somebody drawing a diamond
     * means, and what cannot deadlock waiting for a branch that was never
     * going to arrive.
     */
    @Test
    fun `a node both sides reach runs when either side does`() {
        val gate = BranchGate(fork() + GraphEdge("shout", "log") + GraphEdge("file", "log"))
        gate.follow("trigger", null)
        gate.follow("ask", EdgeBranch.NO)

        assertThat(gate.mayRun("log")).isFalse()
        // Only once the side that was taken has actually run.
        gate.follow("file", null)
        assertThat(gate.mayRun("log")).isTrue()
    }

    /**
     * An edge out of a condition that carries no answer.
     *
     * Every graph drawn before branches existed is made of these, so they run
     * whichever way the condition went - treating them as part of the question
     * would silently drop a path somebody already relies on.
     */
    @Test
    fun `an unlabelled edge out of a condition is taken either way`() {
        val gate = BranchGate(
            listOf(
                GraphEdge("ask", "always"),
                GraphEdge("ask", "onlyYes", EdgeBranch.YES),
            ),
        )

        gate.follow("ask", EdgeBranch.NO)
        assertThat(gate.mayRun("always")).isTrue()
        assertThat(gate.mayRun("onlyYes")).isFalse()
    }

    @Test
    fun `a condition says whether it decides anything`() {
        assertThat(BranchGate(fork()).branches("ask")).isTrue()
        // The old shape: a condition with a plain edge out of it halts instead.
        assertThat(BranchGate(listOf(GraphEdge("ask", "next"))).branches("ask")).isFalse()
    }

    /** post -> onwards, and post -> rescue for the case where it could not. */
    private fun fallback() = listOf(
        GraphEdge("post", "onwards"),
        GraphEdge("post", "rescue", EdgeBranch.FAILURE),
    )

    /**
     * The happy path stays the unmarked edge it has always been, so a node that
     * did its work must not also open the one drawn for the case where it did
     * not - which is the one reading of an unmarked edge that would be wrong.
     */
    @Test
    fun `a node that did its work leaves its failure edge shut`() {
        val gate = BranchGate(fallback())
        gate.follow("post", null)

        assertThat(gate.mayRun("onwards")).isTrue()
        assertThat(gate.mayRun("rescue")).isFalse()
    }

    @Test
    fun `a node that failed takes its failure edge and nothing else`() {
        val gate = BranchGate(fallback())
        gate.follow("post", EdgeBranch.FAILURE)

        assertThat(gate.mayRun("rescue")).isTrue()
        assertThat(gate.mayRun("onwards")).isFalse()
    }

    @Test
    fun `whether a failure has somewhere to go is what the engines ask`() {
        assertThat(BranchGate(fallback()).catchesFailure("post")).isTrue()
        assertThat(BranchGate(listOf(GraphEdge("post", "onwards"))).catchesFailure("post")).isFalse()
    }

    /**
     * A failure edge is not an answer to a question, so it must not turn a
     * condition that did not hold into a condition that chose a direction.
     */
    @Test
    fun `a failure edge does not count as the node deciding anything`() {
        assertThat(BranchGate(fallback()).branches("post")).isFalse()
    }

    @Test
    fun `a chain behind the branch that was not taken stays shut`() {
        val gate = BranchGate(fork() + GraphEdge("file", "andThen") + GraphEdge("andThen", "later"))
        gate.follow("trigger", null)
        gate.follow("ask", EdgeBranch.YES)

        assertThat(gate.mayRun("file")).isFalse()
        // `file` never runs, so nothing behind it opens either.
        assertThat(gate.mayRun("andThen")).isFalse()
        assertThat(gate.mayRun("later")).isFalse()
    }
    /** decide -> (billing) bill, (returns) refund, (unsure) ask a person, and a log after it. Issue #577. */
    private fun decision() = listOf(
        GraphEdge("decide", "bill", EdgeBranch.OPTION, "billing"),
        GraphEdge("decide", "refund", EdgeBranch.OPTION, "returns"),
        GraphEdge("decide", "person", EdgeBranch.UNSURE),
        GraphEdge("decide", "log"),
    )

    @Test
    fun `a decision's option runs its own edge, and no other option's or the unsure one`() {
        val gate = BranchGate(decision())
        gate.follow("decide", EdgeBranch.OPTION, "returns")

        assertThat(gate.mayRun("refund")).isTrue()
        assertThat(gate.mayRun("bill")).isFalse()
        assertThat(gate.mayRun("person")).isFalse()
        // An unmarked edge is not part of the question, and is taken either way.
        assertThat(gate.mayRun("log")).isTrue()
        assertThat(gate.branches("decide")).isTrue()
    }

    @Test
    fun `an unsure decision runs only the unsure edge`() {
        val gate = BranchGate(decision())
        gate.follow("decide", EdgeBranch.UNSURE)

        assertThat(gate.mayRun("person")).isTrue()
        assertThat(gate.mayRun("bill")).isFalse()
        assertThat(gate.mayRun("refund")).isFalse()
    }

    @Test
    fun `an option with no edge of its own opens none of the others`() {
        val gate = BranchGate(decision())
        gate.follow("decide", EdgeBranch.OPTION, "shipping")

        assertThat(gate.mayRun("bill")).isFalse()
        assertThat(gate.mayRun("refund")).isFalse()
        assertThat(gate.mayRun("person")).isFalse()
    }

    /** A condition that could not be answered halts with no branch: an ending, not both ways. */
    @Test
    fun `a condition that answers nothing ends its path rather than going both ways`() {
        val gate = BranchGate(fork())

        assertThat(gate.ends("ask", halt = true, branch = null)).isTrue()
        // A "no" with somewhere to go is a direction; without branches it is an ending.
        assertThat(gate.ends("ask", halt = true, branch = EdgeBranch.NO)).isFalse()
        assertThat(BranchGate(listOf(GraphEdge("ask", "file"))).ends("ask", halt = true, branch = EdgeBranch.NO)).isTrue()
        assertThat(gate.ends("ask", halt = false, branch = EdgeBranch.YES)).isFalse()
    }

    @Test
    fun `a path the trigger does not lead to is not a beginning`() {
        val graph = WorkflowGraph(
            workflowId = 1,
            name = "detached",
            nodes = listOf(
                GraphNode("trigger", NodeKind.TRIGGER, "trigger"),
                GraphNode("prepare", NodeKind.ACTION, "prepare"),
                GraphNode("ask", NodeKind.CONDITION, "ask"),
            ),
            edges = listOf(GraphEdge("prepare", "ask")),
        )
        assertThat(graph.detached()).containsExactly("prepare")
        val gate = BranchGate(graph.edges, detached = graph.detached())

        assertThat(gate.mayRun("trigger")).isTrue()
        assertThat(gate.mayRun("prepare")).isFalse()
        assertThat(gate.refusal("prepare")).contains("from the trigger")
        assertThat(gate.mayRun("ask")).isFalse()

        // No trigger drawn at all: every node nothing points at still begins a run.
        assertThat(graph.copy(nodes = graph.nodes.drop(1)).detached()).isEmpty()
    }
}
