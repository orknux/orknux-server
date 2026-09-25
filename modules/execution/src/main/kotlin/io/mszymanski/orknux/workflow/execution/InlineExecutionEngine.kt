package io.mszymanski.orknux.workflow.execution

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Service
import java.time.Duration

/**
 * Runs a workflow on the calling thread: a restart mid-step loses that step,
 * because the work itself was in flight on a thread that is now gone. A node's
 * own retry policy is honoured here as it is anywhere — it belongs to the step
 * rather than to the engine — but there is nothing underneath a step that is
 * actually running, so a worker that dies mid-step takes that step with it.
 *
 * A step that is only *waiting* is a different case, and it does survive: the
 * wait is recorded as a wake time on the step, and [resume] walks such a run on
 * from where it parked. [ParkedRunSweeper] is what calls it after a restart.
 * Issue #406.
 *
 * It is what runs when `orknux.temporal.enabled` is false — a development
 * machine, or a deployment that would rather not run a Temporal service — and
 * what the tests use, since a test that needs a service to be up is a test that
 * fails for reasons of its own. Anything that has to survive anything should be
 * on Temporal.
 *
 * A step that parks is waited out here on the calling thread rather than on a
 * timer, so what a run may wait for in total is bounded by
 * [InlineExecutionProperties.maxWait]. A workflow that has to wait for an hour
 * works on Temporal and fails here, which is the honest answer.
 */
@Service
@ConditionalOnProperty(name = ["orknux.temporal.enabled"], havingValue = "false", matchIfMissing = true)
@EnableConfigurationProperties(InlineExecutionProperties::class)
class InlineExecutionEngine(
    private val planner: ExecutionPlanner,
    private val steps: StepRunner,
    private val properties: InlineExecutionProperties,
) : ExecutionEngine {

    override fun start(
        workspaceId: Long,
        workflowId: Long,
        trigger: ExecutionTrigger,
        input: String?,
        version: GraphVersion?,
        resumeFrom: ResumePoint?,
        startedFrom: Long?,
        firedTriggerId: Long?,
    ): WorkflowExecution {
        return drive(
            planner.plan(
                workspaceId, workflowId, trigger, input, version, resumeFrom, startedFrom, firedTriggerId,
            ),
        )
    }

    /**
     * Carries a parked run on from where its worker died. Issue #406.
     *
     * The wake sweeper's way back in: a run left WAITING by a restart has its
     * plan rebuilt from what is recorded and is walked from the parked step, on
     * a fresh thread, exactly as it would have been walked had the first one not
     * died. Nothing to carry on - the run finished, or is no longer running - is
     * a run already seen to, and answers null.
     */
    fun resume(executionId: Long): WorkflowExecution? = planner.replan(executionId)?.let(::drive)

    private fun drive(plan: ExecutionPlan): WorkflowExecution {
        val executionId = requireNotNull(plan.execution.id)

        /*
         * What still has a reason to run.
         *
         * Every node used to run, in order. With branches a step is only
         * reached if something that actually happened leads to it, so the gate
         * is asked before each one and told what each one decided.
         */
        val gate = BranchGate(plan.edges, plan.blocked)

        // A run that begins partway down starts with the exits an earlier run
        // took already open, or the first step it walks would have nothing
        // leading to it and be skipped as unreachable.
        plan.carried.forEach { gate.follow(it.nodeKey, it.branch) }

        for ((index, step) in plan.steps.withIndex()) {
            // Asked to stop between steps: end the run where it stands rather
            // than start the next. A stopped run is terminal. Issue #395.
            if (steps.wasStopAsked(executionId)) {
                log.info("Execution {} was asked to stop; ending before {}", executionId, step.nodeKey)
                return steps.stopRun(executionId)
            }

            if (!gate.mayRun(step.nodeKey)) {
                steps.skipStep(executionId, step.nodeKey, gate.refusal(step.nodeKey))
                continue
            }

            val outcome = try {
                runToDecision(executionId, step.nodeKey)
            } catch (stopped: RunStopped) {
                // Asked to stop while this step was waiting. Issue #395.
                log.info("Execution {} was stopped while waiting at {}", executionId, step.nodeKey)
                return steps.stopRun(executionId)
            } catch (failure: StepFailedException) {
                /*
                 * A failure the graph has an answer for is a direction, not an
                 * ending: the step stays failed and says why, and the run
                 * carries on down the edge drawn for exactly this.
                 */
                if (gate.catchesFailure(step.nodeKey)) {
                    steps.recordFailureExit(executionId, step.nodeKey)
                    gate.follow(step.nodeKey, EdgeBranch.FAILURE)
                    continue
                }
                log.warn("Execution {} failed at {}", executionId, step.nodeKey, failure)
                return steps.failRun(
                    executionId = executionId,
                    nodeKey = step.nodeKey,
                    reason = failure.message ?: "the step failed",
                    unreached = plan.steps.size - index - 1,
                )
            }

            gate.follow(step.nodeKey, outcome.branch)

            /*
             * A condition that did not hold ends the run - unless it has
             * branches, in which case it decided a direction rather than an
             * ending, and the gate has already closed the way not taken.
             */
            if (outcome.halt && !gate.branches(step.nodeKey)) {
                log.info("Execution {} stopped at {}: the run has nothing further to do", executionId, step.nodeKey)
                return steps.finishRun(executionId, stoppedAt = step.nodeKey, reason = outcome.output)
            }
        }

        return steps.finishRun(executionId)
    }

    /**
     * Runs one step, and keeps running it for as long as it parks.
     *
     * The delay is spent on this thread, which is the whole of the difference
     * between this engine and the Temporal one: a durable timer costs nothing
     * while it runs down, and this costs a thread for every second of it. So it
     * is bounded — a run that wants to wait longer than the engine allows fails
     * where it waited, and says what would have carried it.
     */
    private fun runToDecision(executionId: Long, nodeKey: String): StepOutcome {
        var waited = Duration.ZERO

        while (true) {
            val outcome = steps.runStep(executionId, nodeKey)
            if (outcome.status != StepStatus.WAITING) return outcome

            // Unreachable — a parked node says when to come back — but leaving
            // the step open and carrying on would be worse than stopping.
            val pause = outcome.resumeAfter
                ?: steps.failStep(executionId, nodeKey, "$nodeKey parked without saying when to come back")

            waited += pause
            if (waited > properties.maxWait) {
                steps.failStep(
                    executionId,
                    nodeKey,
                    "$nodeKey asked to wait longer than the inline engine allows (${properties.maxWait}); " +
                        "a wait that long needs orknux.temporal.enabled",
                )
            }
            log.debug("Execution {} is waiting {}s at {}", executionId, pause.toSeconds(), nodeKey)
            // Slept in chunks so a stop asked during a long wait is noticed
            // within a chunk rather than only when the wait is over. Issue #395.
            sleepUnlessStopped(executionId, pause)
        }
    }

    /**
     * Sleeps for [pause], in chunks, and throws [RunStopped] the moment the run
     * is asked to stop. Issue #395.
     *
     * The chunk is what makes a stop take effect during a wait rather than only
     * after it: a node parked for ten minutes would otherwise be un-stoppable
     * until it woke on its own.
     */
    private fun sleepUnlessStopped(executionId: Long, pause: Duration) {
        var left = pause
        while (left > Duration.ZERO) {
            if (steps.wasStopAsked(executionId)) throw RunStopped()
            val chunk = if (left < STOP_POLL) left else STOP_POLL
            Thread.sleep(chunk.toMillis())
            left -= chunk
        }
    }

    /** Thrown to end a run that was asked to stop while it was waiting. Issue #395. */
    private class RunStopped : RuntimeException()

    private companion object {
        val log = LoggerFactory.getLogger(InlineExecutionEngine::class.java)

        /** How often a wait looks up to see whether it has been asked to stop. Issue #395. */
        val STOP_POLL: Duration = Duration.ofSeconds(2)
    }
}

/** How patient the inline engine is, which is only ever a development concern. */
@ConfigurationProperties(prefix = "orknux.execution.inline")
data class InlineExecutionProperties(
    /**
     * The longest one run may spend parked, added up over all its waits.
     *
     * Temporal waits with a timer and needs no such bound — a run there waits
     * for as long as the run timeout allows. This engine waits with the thread
     * carrying the run, so an unbounded wait is an unbounded thread.
     */
    val maxWait: Duration = Duration.ofMinutes(5),
)
