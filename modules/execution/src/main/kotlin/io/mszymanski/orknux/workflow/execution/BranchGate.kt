package io.mszymanski.orknux.workflow.execution

/**
 * Which nodes still have a reason to run, once a condition has answered.
 *
 * A workflow used to be a straight line: every node ran, and a condition that
 * did not hold ended the whole thing. With branches a run has to be able to go
 * one way and not the other, which means something has to decide, at each step,
 * whether anything that actually happened leads to it.
 *
 * The rule is that an edge is *taken* or it is not. Every edge out of a node
 * that ran is taken, except at a condition with branches, where only the edges
 * carrying the answer it gave are. A node runs when it has no edges in at all -
 * it is a beginning - or when at least one edge into it was taken.
 *
 * One taken path is enough, deliberately. Where two paths meet, "either of
 * these happened" is what somebody drawing a diamond means, it cannot wait
 * forever for a branch that was never going to arrive, and "only when both"
 * is what a second condition is for. Written down here rather than assumed,
 * because the other rule is defensible and this is the one that was chosen.
 *
 * Shared by both engines. The inline one walks the plan on a thread and the
 * Temporal one walks it across activities, and a run that took different paths
 * depending on which engine carried it would be the worst kind of difference.
 */
class BranchGate(
    edges: List<GraphEdge>,
    /**
     * The nodes that are not beginnings of *this* run, though the graph would
     * otherwise make them one.
     *
     * A workflow may be drawn with two triggers, and only one of them fires.
     * Both have nothing pointing at them, so without this both are beginnings
     * and both branches run — which is one message sent by the trigger that did
     * not fire, and an agent charged for an answer nobody asked for.
     *
     * Named for what it does rather than for triggers, because the question is
     * general: which nodes does this run start from. A run that begins partway
     * down and, one day, a graph that says what runs beside what are the same
     * question asked again, and neither should have to undo this.
     */
    private val blocked: Set<String> = emptySet(),
) {

    private val incoming: Map<String, List<GraphEdge>> = edges.groupBy { it.target }
    private val outgoing: Map<String, List<GraphEdge>> = edges.groupBy { it.source }

    /** The edges whose answer has been given, and which therefore lead somewhere. */
    private val taken = mutableSetOf<GraphEdge>()

    /**
     * A node nothing points at is a beginning, and beginnings always run —
     * unless this run was told it does not begin here.
     *
     * Nothing else is needed to close the branch behind it. A node the gate
     * refuses never gets to [follow], so no edge out of it is taken, and
     * everything it led to is refused in turn when the order reaches it.
     */
    fun mayRun(nodeKey: String): Boolean {
        if (nodeKey in blocked) return false
        val into = incoming[nodeKey] ?: return true
        return into.isEmpty() || into.any { it in taken }
    }

    /**
     * Why a node the gate refused is not running, in the words the run's log
     * uses.
     *
     * Here rather than in either engine because there are two of them walking
     * the plan, and a run that explained itself differently depending on which
     * one carried it is the kind of difference nobody can act on.
     */
    fun refusal(nodeKey: String): String =
        if (nodeKey in blocked) "it is not the trigger that fired" else "the condition before it went the other way"

    /**
     * What a node's own outcome opens up.
     *
     * A condition that answered YES takes its YES edges; the NO ones are never
     * taken, so anything only they reach is skipped. An edge out of a condition
     * that carries no answer at all is taken either way - it is not part of the
     * question, and treating it as one would silently drop a path somebody drew
     * before branches existed.
     *
     * A failure edge is the one exception to that generosity, and in the other
     * direction: it is taken only where the node actually failed, and nothing
     * else out of the node is taken then. Otherwise a node that did its work
     * would open the path drawn for the case where it could not, which is the
     * one reading of an unmarked edge nobody means.
     */
    fun follow(nodeKey: String, branch: EdgeBranch?, option: String? = null) {
        val out = outgoing[nodeKey] ?: return
        taken += when (branch) {
            EdgeBranch.FAILURE -> out.filter { it.branch == EdgeBranch.FAILURE }
            null -> out.filterNot { it.branch == EdgeBranch.FAILURE }
            /*
             * A decision's option: the edge carrying that option, and the
             * unmarked ones - never another option's, and never the unsure
             * edge, which is for the answer that was not taken.
             */
            EdgeBranch.OPTION -> out.filter { it.branch == null || (it.branch == EdgeBranch.OPTION && it.option == option) }
            else -> out.filter { it.branch == null || it.branch == branch }
        }
    }

    /**
     * Whether this node's answer decides anything: a condition with branch
     * edges, or a decision with an edge for an option or for being unsure.
     */
    fun branches(nodeKey: String): Boolean =
        outgoing[nodeKey].orEmpty().any { it.branch != null && it.branch != EdgeBranch.FAILURE }

    /**
     * Whether a failure here is something the graph has an answer for.
     *
     * Asked by both engines the moment a step throws: with an edge to follow the
     * run carries on down it, and without one the failure ends the run exactly
     * as it always has.
     */
    fun catchesFailure(nodeKey: String): Boolean =
        outgoing[nodeKey].orEmpty().any { it.branch == EdgeBranch.FAILURE }
}
