package io.mszymanski.orknux.workflow.temporal

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.mszymanski.orknux.workflow.execution.BranchGate
import io.mszymanski.orknux.workflow.execution.EdgeBranch
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import io.mszymanski.orknux.workflow.execution.GraphEdge
import io.mszymanski.orknux.workflow.execution.StepStatus
import io.temporal.failure.ActivityFailure
import io.temporal.failure.ApplicationFailure
import io.temporal.workflow.SignalMethod
import io.temporal.workflow.Workflow
import io.temporal.workflow.WorkflowInterface
import io.temporal.workflow.WorkflowMethod
import java.time.Duration

/**
 * The one workflow this service has: it walks a graph.
 *
 * The graph is an argument rather than a definition registered with Temporal,
 * so a workflow drawn in orknux-ui needs nothing registering, deploying or
 * versioning — orknux-server stays the only place a definition lives. It also
 * keeps this code still: what changes between runs is data, which is what makes
 * Temporal's determinism requirement cheap to live with here.
 */
@WorkflowInterface
interface ExecutionWorkflow {

    @WorkflowMethod
    fun run(plan: RunPlan): ExecutionStatus

    /** Cuts short the wait of a parked step; see [ExecutionEngine.wake]. */
    @SignalMethod
    fun wake()
}

/**
 * What the workflow is handed. Steps are node keys only — the rest is in the
 * database the activities write to, and a payload carries what it must.
 *
 * The Temporal SDK converts payloads with Jackson 2 and no Kotlin module, so
 * the creator is bound explicitly, as everywhere else in this service.
 */
data class RunPlan @JsonCreator constructor(
    @JsonProperty("executionId") val executionId: Long,
    @JsonProperty("workflowName") val workflowName: String,
    @JsonProperty("steps") val steps: List<String>,
    @JsonProperty("input") val input: String? = null,
    /**
     * The graph's edges, so the workflow can tell what leads where.
     *
     * Sent once, with the plan, rather than asked for again at each step: a
     * workflow's own decisions have to be replayable from history, and a graph
     * fetched mid-run could answer differently the second time it is walked.
     */
    @JsonProperty("edges") val edges: List<PlanEdge> = emptyList(),
    /**
     * The exits an earlier run already took, for a plan that begins partway
     * down the graph.
     *
     * Empty for an ordinary run. Sent with the plan for the same reason the
     * edges are: what the workflow decides has to be replayable from history,
     * and reading the earlier run again mid-flight could answer differently.
     */
    @JsonProperty("carried") val carried: List<PlanExit> = emptyList(),
    /**
     * The nodes this run does not begin at, though nothing points at them:
     * every trigger node but the one that fired.
     *
     * Defaulted, and deliberately so. A run already in flight when this field
     * was added replays from a history written without it, and an absent list
     * has to read as the run it has been all along — every trigger a beginning
     * — rather than as a plan that cannot be understood.
     */
    @JsonProperty("blocked") val blocked: List<String> = emptyList(),
)

/** One step an earlier run took, and which way out of it that run went. */
data class PlanExit @JsonCreator constructor(
    @JsonProperty("nodeKey") val nodeKey: String,
    @JsonProperty("branch") val branch: EdgeBranch? = null,
    /** Which option, on an OPTION branch; absent from any history written before decisions. */
    @JsonProperty("option") val option: String? = null,
)

/** One edge as the plan carries it, with the answer it leaves by. */
data class PlanEdge @JsonCreator constructor(
    @JsonProperty("source") val source: String,
    @JsonProperty("target") val target: String,
    @JsonProperty("branch") val branch: EdgeBranch? = null,
    /** Which option an OPTION edge carries; absent from any history written before decisions. */
    @JsonProperty("option") val option: String? = null,
)

/** A step that failed, and whose failure edge is the way the run went on. */
data class RecordFailureExitCommand @JsonCreator constructor(
    @JsonProperty("executionId") val executionId: Long,
    @JsonProperty("nodeKey") val nodeKey: String,
)

/** A step the run went past, because the branch reaching it was not taken. */
data class SkipStepCommand @JsonCreator constructor(
    @JsonProperty("executionId") val executionId: Long,
    @JsonProperty("nodeKey") val nodeKey: String,
    @JsonProperty("reason") val reason: String,
)

/**
 * Which step to run, and nothing else.
 *
 * Deliberately no payload: an activity's arguments and results are written into
 * Temporal's event history and kept for the life of the run, so handing a
 * growing payload back and forth would record it again at every step. The run
 * carries it in the database instead, and this carries an id.
 */
data class RunStepCommand @JsonCreator constructor(
    @JsonProperty("executionId") val executionId: Long,
    @JsonProperty("nodeKey") val nodeKey: String,
)

data class StepReport @JsonCreator constructor(
    @JsonProperty("status") val status: StepStatus,
    @JsonProperty("output") val output: String? = null,
    /** True when the step decided the run has nothing further to do. */
    @JsonProperty("halt") val halt: Boolean = false,
    /** Which way out of a condition the run went; null for every other node. */
    @JsonProperty("branch") val branch: EdgeBranch? = null,
    /** Which option of a decision the run went by, on an OPTION branch. */
    @JsonProperty("option") val option: String? = null,
    /**
     * Set when the step parked: how long before it is asked again.
     *
     * The activity answers straight away either way, so the wait is the
     * workflow's and not the activity's — which is what lets a run wait for
     * hours without holding a worker for any of them.
     */
    @JsonProperty("resumeAfterSeconds") val resumeAfterSeconds: Long? = null,
    /**
     * True when the step was cut short because the run was asked to stop, and
     * the workflow should end the run rather than go on. Issue #440.
     *
     * Defaulted, like [RunPlan.blocked] and for the same reason: a run in
     * flight when this was added replays from a history written without it,
     * and an absent flag has to read as the report it always was.
     */
    @JsonProperty("stopped") val stopped: Boolean = false,
)

/** A run that was asked to stop, to be ended where it stands. Issue #440. */
data class StopRunCommand @JsonCreator constructor(
    @JsonProperty("executionId") val executionId: Long,
)

/** What ended the run, when something decided there was nothing further to do. */
data class FinishRunCommand @JsonCreator constructor(
    @JsonProperty("executionId") val executionId: Long,
    @JsonProperty("stoppedAt") val stoppedAt: String? = null,
    @JsonProperty("reason") val reason: String? = null,
)

data class FailRunCommand @JsonCreator constructor(
    @JsonProperty("executionId") val executionId: Long,
    @JsonProperty("nodeKey") val nodeKey: String,
    @JsonProperty("reason") val reason: String,
    @JsonProperty("unreached") val unreached: Int,
)

/**
 * One step at a time, each one an activity, so a worker that dies takes at most
 * the step it was on with it — and Temporal hands that step to another worker
 * rather than losing the run.
 */
class ExecutionWorkflowImpl : ExecutionWorkflow {

    /** Options come from the worker's registration, so this stays free of configuration. */
    private val activities = Workflow.newActivityStub(ExecutionActivities::class.java)

    /** Set by [wake], and read by a parked step's wait. */
    private var woken = false

    override fun wake() {
        woken = true
    }

    override fun run(plan: RunPlan): ExecutionStatus {
        /*
         * What still has a reason to run. Built from the edges the plan
         * carries, so this workflow decides the same way the inline engine
         * does - a run that took different paths depending on which engine
         * carried it would be the worst kind of difference.
         */
        val gate = BranchGate(plan.edges.map { GraphEdge(it.source, it.target, it.branch, it.option) }, plan.blocked.toSet())

        // A run that begins partway down starts with the exits an earlier run
        // took already open, or the first step it walks would have nothing
        // leading to it and be skipped as unreachable.
        plan.carried.forEach { gate.follow(it.nodeKey, it.branch, it.option) }

        for ((index, nodeKey) in plan.steps.withIndex()) {
            val unreached = plan.steps.size - index - 1

            if (!gate.mayRun(nodeKey)) {
                activities.skipStep(SkipStepCommand(plan.executionId, nodeKey, gate.refusal(nodeKey)))
                continue
            }

            // Ask the step, and sleep on Temporal's clock for as long as it says
            // it is not ready. This is what makes a wait first class: the
            // activity returns immediately whether or not the step is done, so
            // the step timeout bounds the work a node does rather than the time
            // it waits, no worker is held while the timer runs down, and the
            // timer outlives every process involved. What it costs is history —
            // a wait that asks every thirty seconds writes an event each time —
            // which is what the run timeout is there to bound.
            // The report stays null while the step is still parking and asking
            // again, and where the run left by a failure edge instead of the
            // step ever answering at all.
            var report: StepReport? = null
            var diverted = false
            while (true) {
                val attempt = try {
                    activities.runStep(RunStepCommand(plan.executionId, nodeKey))
                } catch (failure: ActivityFailure) {
                    /*
                     * Every attempt is spent by the time this is thrown - and a
                     * failure the graph has an answer for is a direction rather
                     * than an ending, the same one the inline engine takes: the
                     * step stays failed and the run carries on down the edge
                     * drawn for it.
                     */
                    if (gate.catchesFailure(nodeKey)) {
                        activities.recordFailureExit(RecordFailureExitCommand(plan.executionId, nodeKey))
                        diverted = true
                        break
                    }
                    activities.failRun(
                        FailRunCommand(
                            executionId = plan.executionId,
                            nodeKey = nodeKey,
                            reason = failure.reason(),
                            unreached = unreached,
                        ),
                    )
                    return ExecutionStatus.FAILED
                }

                /*
                 * The step was cut short because somebody asked the run to
                 * stop: the same ending the inline engine gives it, so a run
                 * stops the same way whichever engine carries it. Issue #440.
                 */
                if (attempt.stopped) {
                    activities.stopRun(StopRunCommand(plan.executionId))
                    return ExecutionStatus.STOPPED
                }

                if (attempt.status != StepStatus.WAITING) {
                    report = attempt
                    break
                }

                val pause = attempt.resumeAfterSeconds
                if (pause == null) {
                    // Unreachable — a parked node says when to come back — but
                    // spinning on it, or leaving the step open for ever, would
                    // both be worse than stopping and saying so.
                    activities.failRun(
                        FailRunCommand(
                            executionId = plan.executionId,
                            nodeKey = nodeKey,
                            reason = "$nodeKey parked without saying when to come back",
                            unreached = unreached,
                        ),
                    )
                    return ExecutionStatus.FAILED
                }
                /*
                 * A wait that something arriving can cut short. Versioned, because
                 * a run already in its sleep when this shipped replays a timer and
                 * must go on seeing one.
                 */
                if (Workflow.getVersion(WAKE_ON_EVENT, Workflow.DEFAULT_VERSION, 1) == Workflow.DEFAULT_VERSION) {
                    Workflow.sleep(Duration.ofSeconds(pause))
                } else {
                    Workflow.await(Duration.ofSeconds(pause)) { woken }
                    woken = false
                }
            }

            if (diverted) {
                gate.follow(nodeKey, EdgeBranch.FAILURE)
                continue
            }

            val outcome = requireNotNull(report)
            gate.follow(nodeKey, outcome.branch, outcome.option)

            /*
             * A condition that did not hold ends the run - unless it has
             * branches, where it chose a direction rather than an ending.
             */
            if (outcome.halt && !gate.branches(nodeKey)) {
                activities.finishRun(FinishRunCommand(plan.executionId, nodeKey, outcome.output))
                return ExecutionStatus.COMPLETED
            }
        }

        activities.finishRun(FinishRunCommand(plan.executionId))
        return ExecutionStatus.COMPLETED
    }

    /** What the step said, rather than Temporal's wrapper around it. */
    private fun ActivityFailure.reason(): String =
        (cause as? ApplicationFailure)?.originalMessage ?: cause?.message ?: message ?: "the step failed"
}

/** The change id for a step wait that a signal can cut short; see [ExecutionWorkflow.wake]. */
private const val WAKE_ON_EVENT = "wake-on-event"
