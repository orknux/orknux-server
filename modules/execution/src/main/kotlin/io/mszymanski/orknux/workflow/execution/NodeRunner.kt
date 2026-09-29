package io.mszymanski.orknux.workflow.execution

import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * What a runner made of one node. A runner that could not do its work throws;
 * the engine turns that into a failed step and a stopped run.
 */
data class StepResult(
    /** [StepStatus.COMPLETED], [StepStatus.SKIPPED] or [StepStatus.WAITING]. */
    val status: StepStatus,
    /**
     * What the node produced, and what the next node is handed. On a parked
     * node it is the line the run's log shows for the wait, since a node that
     * has not finished has produced nothing.
     */
    val output: String? = null,
    /**
     * Ends the run here, without failing it.
     *
     * A condition node that does not hold is the reason this exists: the run did
     * what it was asked and there is nothing further to do, which is a different
     * outcome from a step that could not be carried out.
     */
    val halt: Boolean = false,
    /**
     * How long until the node is asked again, on a parked node and nothing else.
     *
     * The runner does not spend it — that is the whole point. It says how long
     * it wants to wait and returns, and whatever is carrying the run holds the
     * delay: a Temporal timer costs nothing while it runs down and survives a
     * restart of every process involved.
     */
    val resumeAfter: Duration? = null,
    /**
     * Which way out of a condition the run went.
     *
     * Set only by a condition node, and only where the graph gives it
     * somewhere to go: what the engine does with it is follow the edges that
     * carry the same answer and skip whatever only the other answer reached.
     * Null everywhere else, which is every node that asks nothing.
     */
    val branch: EdgeBranch? = null,
    /**
     * Which option, where [branch] is [EdgeBranch.OPTION]: the one a decision
     * node's choice picked. Null everywhere else.
     */
    val option: String? = null,
) {
    init {
        require(status == StepStatus.COMPLETED || status == StepStatus.SKIPPED || status == StepStatus.WAITING) {
            "A runner reports what it did, not that it failed: $status"
        }
        require((resumeAfter != null) == (status == StepStatus.WAITING)) {
            "A parked node says when to come back, and only a parked one does: $status"
        }
    }

    companion object {

        /**
         * Nothing was decided; ask this node again in [after].
         *
         * A runner that parks records its own deadline on the step — see
         * [ExecutionStep.waitUntil] — because it may be asked again by a
         * different worker, in a different process, hours later.
         */
        fun waiting(after: Duration, note: String? = null): StepResult =
            StepResult(StepStatus.WAITING, note, resumeAfter = after)
    }
}

/**
 * Carries out one node of a graph.
 *
 * This is the seam the node kinds land on: an agent runner will call the model,
 * a publish runner will call orknux-connector. The engine picks the first
 * runner that claims the kind, so a new one is a `@Component` and nothing else.
 */
interface NodeRunner {

    fun supports(kind: NodeKind): Boolean

    /**
     * @param step the run's own copy of the node. A runner that parks may write
     *   to it — [ExecutionStep.waitUntil] is what a wait counts down to — and
     *   what it wrote is saved with the step.
     * @param input what the node before it produced, or the run's own input for
     *   the first node. A parked node is handed the same input again.
     * @param trigger what the run started from, unchanged however deep the step
     *   is. [input] is replaced at every step, so by the time an agent has
     *   answered, the event that began the run is no longer in it; a node that
     *   needs to answer whoever asked has nowhere else to read that from.
     */
    fun run(step: ExecutionStep, input: String?, trigger: String? = null): StepResult
}

/**
 * Where a runner that implements a kind sits.
 *
 * Deliberately not [Ordered.HIGHEST_PRECEDENCE]: nothing can be placed above
 * that, so a test that has to steer the engine — or an installation replacing
 * one kind's runtime — would be tied with the runner it means to pre-empt, and
 * a tie is settled by whatever order the beans happened to be registered in.
 * That is how three interpreter tests came to see every node skipped.
 *
 * Far ahead of [UnimplementedNodeRunner], which is the floor.
 */
const val KIND_RUNNER_ORDER: Int = Ordered.HIGHEST_PRECEDENCE + 100

/**
 * Claims every kind, last, so a graph can be run before any kind has a runtime.
 * It performs nothing and says so: the step is skipped rather than completed,
 * because a run that reports success without doing the work is worse than one
 * that reports it did not.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class UnimplementedNodeRunner : NodeRunner {

    override fun supports(kind: NodeKind): Boolean = true

    override fun run(step: ExecutionStep, input: String?, trigger: String?): StepResult =
        StepResult(StepStatus.SKIPPED, "${step.kind} nodes have no runtime yet; nothing was performed.")
}
