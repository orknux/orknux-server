package io.mszymanski.orknux.workflow.execution

/**
 * The nodes whose lines out are walked at the same time. Issue #285.
 *
 * A node that is not a question - anything but a condition or a decision - with
 * lines to two or more nodes sends the run down every one of them, and has
 * always done so: a run used to walk them one after the other in the order the
 * graph sorted them. Nothing about which nodes run changes here, only that the
 * paths are walked side by side. A condition or a decision is left out because
 * its lines are a choice between paths rather than a fan of them, and the way
 * it routes is its own; a failure edge is left out because it is the way out
 * when the node could not do its work, which is never taken alongside the rest.
 *
 * Empty for every graph without such a node, which is what tells both engines
 * to walk the plan exactly as they always have.
 */
fun WorkflowGraph.splits(): Set<String> {
    val known = nodes.mapTo(HashSet()) { it.key }
    return nodes
        .filter { it.kind != NodeKind.CONDITION && it.kind != NodeKind.DECISION }
        .filter { node ->
            edges.asSequence()
                .filter { it.source == node.key && it.branch != EdgeBranch.FAILURE && it.target in known }
                .map { it.target }
                .distinct()
                .count() >= 2
        }
        .mapTo(LinkedHashSet()) { it.key }
}

/**
 * Which two nodes may be carried out at the same time.
 *
 * Each split's lines out start a lane: everything reachable from that line.
 * A node reachable from two lines of one split is where they meet again, and
 * belongs to neither - it runs once both are done, which the order already
 * guarantees. Two nodes may overlap only where some split put them in two
 * different lanes. That is what keeps everything a split did not fan out
 * exactly as sequential as it was: two triggers in a run nobody fired, two
 * nodes nothing connects, the halves of a condition's choice.
 */
class ParallelLanes(edges: List<GraphEdge>, splits: Collection<String>) {

    /** Per split, the lane each node it fans out to is in; a node in two lanes is in none. */
    private val lanes: List<Map<String, Int>>

    init {
        val outgoing = edges.groupBy { it.source }
        lanes = splits.map { split ->
            val starts = outgoing[split].orEmpty()
                .filter { it.branch != EdgeBranch.FAILURE }
                .map { it.target }
                .distinct()
            val owner = HashMap<String, Int>()
            val shared = HashSet<String>()
            starts.forEachIndexed { lane, start ->
                reach(start, outgoing).forEach { key ->
                    val already = owner.putIfAbsent(key, lane)
                    if (already != null && already != lane) shared += key
                }
            }
            owner - shared
        }
    }

    /** Whether some split sent [a] and [b] down different lines. */
    fun mayOverlap(a: String, b: String): Boolean = lanes.any { lane ->
        val first = lane[a]
        val second = lane[b]
        first != null && second != null && first != second
    }

    private fun reach(start: String, outgoing: Map<String, List<GraphEdge>>): Set<String> {
        val seen = LinkedHashSet<String>()
        val next = ArrayDeque(listOf(start))
        while (next.isNotEmpty()) {
            val key = next.removeFirst()
            if (seen.add(key)) outgoing[key].orEmpty().forEach { next += it.target }
        }
        return seen
    }
}

/**
 * A step that failed with no failure edge to take, in a run that went on
 * walking its other paths. What the run is failed with once they are done.
 */
data class DeadEnd(val nodeKey: String, val reason: String)

/**
 * What a run with splits does next, decided the same way by both engines.
 * Issue #285.
 *
 * The inline engine and the Temporal workflow each carry the steps out their
 * own way - a thread per step, an activity per step - but which step may start,
 * which is skipped and how the run ends is decided here, so a run cannot take
 * different paths depending on which engine carried it. The same reason
 * [BranchGate] is shared; this asks the gate rather than replacing it.
 *
 * The rules:
 *
 * - A node is reached once every node in the plan that leads to it has
 *   finished, however it finished. Where paths meet, the node waits for all of
 *   them and runs once - which is what the old order did too, since a node was
 *   only ever sorted after everything that fed it.
 * - The gate then decides, as it always has, whether anything that happened
 *   leads to it: a path a condition refused does not hold up the meeting node,
 *   it just is not one of the ways in.
 * - A step starts when it is reached, fewer than [limit] are running, and every
 *   step already running is in another lane of some split ([ParallelLanes]).
 * - A failure with no failure edge ends its own path, not the others: nothing
 *   after it runs, the paths that do not depend on it go on to their ends, and
 *   the run is failed once they have. A condition that ends the run ends its
 *   own path the same way, and the run finishes saying where it stopped.
 * - Asked to stop, nothing new starts, and the run ends once what is running
 *   has finished or been cut short.
 *
 * Not thread safe, and does not need to be: the engine's own loop is the one
 * thing that touches it.
 */
class Frontier(
    /** The plan's steps, in the run order. */
    order: List<String>,
    edges: List<GraphEdge>,
    private val gate: BranchGate,
    private val lanes: ParallelLanes,
    /** How many steps may be running at once; never less than one. */
    limit: Int,
    /**
     * Failures an earlier pass of this run already left behind - a run picked
     * up after a restart - so what lies past them stays unreached.
     */
    deadEnds: Collection<DeadEnd> = emptyList(),
) {

    /** One thing the engine is to do now. */
    sealed interface Move {
        val nodeKey: String
    }

    /** Record the step as skipped, for [reason]; nothing is run. */
    data class Skip(override val nodeKey: String, val reason: String) : Move

    /** Carry the step out, and report back how it went. */
    data class Run(override val nodeKey: String) : Move

    private val limit = limit.coerceAtLeast(1)

    /** Not yet decided, in run order. */
    private val waiting = LinkedHashSet(order)
    private val running = LinkedHashSet<String>()

    /** Finished, however: what a node waits on. */
    private val done = HashSet<String>()

    /** Finished in a way that nothing after it may run. */
    private val closed = HashSet<String>()

    /** Never reached, because something before them closed their path. */
    private val unreached = HashSet<String>()

    private val before: Map<String, List<String>>

    /** The first failure nothing caught, which is what the run fails with. */
    var failure: DeadEnd? = deadEnds.firstOrNull()
        private set

    /** The first condition that ended its path, and what it said. */
    var halt: Pair<String, String?>? = null
        private set

    /** Whether the run has been asked to stop, or one of its steps was cut short by one. */
    var stopping: Boolean = false
        private set

    init {
        deadEnds.forEach {
            done += it.nodeKey
            closed += it.nodeKey
        }
        val known = waiting + done
        before = edges
            .filter { it.target in waiting && it.source in known }
            .groupBy({ it.target }, { it.source })
            .mapValues { (_, sources) -> sources.distinct() }
    }

    /** Nothing is running, so nothing more can happen: the run is to be ended. */
    val idle: Boolean get() = running.isEmpty()

    /** The steps the run will not reach, for the line that ends a failed run. */
    val unreachedCount: Int get() = waiting.size + unreached.size

    /**
     * Everything that can be decided now: the steps to skip and the steps to
     * start. Called again after each step reports back.
     */
    fun next(): List<Move> {
        val moves = mutableListOf<Move>()
        var moved = true
        while (moved) {
            moved = false
            for (key in waiting.toList()) {
                val feeding = before[key].orEmpty()
                if (feeding.any { it !in done }) continue

                if (feeding.any { it in closed }) {
                    waiting -= key
                    done += key
                    closed += key
                    unreached += key
                    moved = true
                    continue
                }
                if (!gate.mayRun(key)) {
                    waiting -= key
                    done += key
                    moves += Skip(key, gate.refusal(key))
                    moved = true
                    continue
                }
                if (stopping || running.size >= limit || running.any { !lanes.mayOverlap(it, key) }) continue

                waiting -= key
                running += key
                moves += Run(key)
            }
        }
        return moves
    }

    /** The step did its work, or was skipped by its runner; [branch] is the way it went. */
    fun completed(key: String, branch: EdgeBranch?, option: String?, halt: Boolean, output: String?) {
        finish(key)
        gate.follow(key, branch, option)
        if (halt && !gate.branches(key)) {
            closed += key
            if (this.halt == null) this.halt = key to output
        }
    }

    /**
     * The step failed. True where the graph has an answer for that - the step
     * has a failure edge, which the run now goes down - and the engine records
     * the exit; false where it ends this path and, in the end, the run.
     */
    fun failed(key: String, reason: String): Boolean {
        finish(key)
        if (gate.catchesFailure(key)) {
            gate.follow(key, EdgeBranch.FAILURE)
            return true
        }
        closed += key
        if (failure == null) failure = DeadEnd(key, reason)
        return false
    }

    /** The step was cut short by a stop. */
    fun stopped(key: String) {
        finish(key)
        closed += key
        stopping = true
    }

    /** Start nothing more; what is running is let finish. */
    fun stop() {
        stopping = true
    }

    private fun finish(key: String) {
        check(running.remove(key)) { "$key was not running" }
        done += key
    }
}
