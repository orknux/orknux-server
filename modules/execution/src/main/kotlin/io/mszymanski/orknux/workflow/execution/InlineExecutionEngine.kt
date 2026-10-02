package io.mszymanski.orknux.workflow.execution

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Service
import java.time.Duration
import jakarta.annotation.PreDestroy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

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
 * Issue #406. The same call carries on a run the restart caught anywhere else
 * (#448): between two steps, where the next simply had not been dispatched,
 * or inside one, where the lost step is failed as interrupted and its node's
 * retry policy decides what happens next - the loss is recorded rather than
 * quietly repeated.
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

    /**
     * The runs this process is walking right now. Issue #448.
     *
     * What tells the sweeper a run is alive. A run's record says RUNNING whether
     * a thread is carrying it or the thread died an hour ago, and its steps'
     * timestamps only say when something last moved - a long model call moves
     * nothing for minutes. This does: a run is here from the moment an engine
     * thread picks its plan up to the moment that thread ends the run, through
     * every step, every sleep and every gap between two steps. [StepInterrupts]
     * knows only a step in the middle of its runner, which leaves the gaps.
     *
     * This process only, which for the inline engine is the whole world: it runs
     * where there is no Temporal, on one JVM, and a second server sharing the
     * database without one is not a deployment this engine supports.
     */
    private val driving = ConcurrentHashMap.newKeySet<Long>()

    /** Whether a thread of this process is carrying the run. */
    fun isDriving(executionId: Long): Boolean = executionId in driving

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
     * Carries a run on from where its worker died. Issues #406, #448.
     *
     * The sweeper's way back in: a run left RUNNING by a restart has its plan
     * rebuilt from what is recorded and is walked from where it stopped, on a
     * fresh thread, exactly as it would have been walked had the first one not
     * died. A step left WAITING is asked again once its wake has passed; one
     * left RUNNING is failed as interrupted first, and its node's retry policy
     * decides whether it is asked again - see [StepRunner.interruptStep]; a
     * run with no open step walks on to the next PENDING one. Nothing to carry
     * on - the run finished, or is no longer running - is a run already seen
     * to, and answers null.
     *
     * So does a run this process is still carrying. The sweeper checks before
     * it calls, but the check and the call are two moments, and a run picked
     * up by a live thread between them must not be walked twice.
     */
    fun resume(executionId: Long): WorkflowExecution? {
        if (isDriving(executionId)) return null
        return planner.replan(executionId)?.let(::drive)
    }

    private fun drive(plan: ExecutionPlan): WorkflowExecution {
        val executionId = requireNotNull(plan.execution.id)
        driving.add(executionId)
        try {
            // A graph that fans out walks its paths side by side (#285); every
            // other graph is walked exactly as it always was.
            return if (plan.splits.isEmpty()) walk(plan, executionId) else walkSideBySide(plan, executionId)
        } finally {
            driving.remove(executionId)
        }
    }

    private fun walk(plan: ExecutionPlan, executionId: Long): WorkflowExecution {
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
        plan.carried.forEach { gate.follow(it.nodeKey, it.branch, it.option) }

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
                // A step handed over still RUNNING was in flight when the
                // process died - only a replan hands one over like that, since a
                // fresh plan's steps are all PENDING. Issue #448.
                runToDecision(executionId, step.nodeKey, interrupted = step.status == StepStatus.RUNNING)
            } catch (stopped: StepStoppedException) {
                // Asked to stop while this step was waiting (#395), or while it
                // was in the middle of its work and the work was cut short (#440).
                // Either way the step is recorded and the run ends here.
                log.info("Execution {} was stopped at {}", executionId, step.nodeKey)
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

            gate.follow(step.nodeKey, outcome.branch, outcome.option)

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
     * Walks a run whose graph fans out, carrying the paths of each split at the
     * same time. Issue #285.
     *
     * What runs, what is skipped and how the run ends is the [Frontier]'s to
     * decide, which the Temporal workflow asks too; this thread only does what
     * it says. Each step is carried out on a thread of [branches], the same
     * [runToDecision] a sequential run calls - waits, retries and an
     * interrupted step's settling included - and reports back here, and this
     * thread is the only one that touches the gate or the frontier.
     *
     * How many run at once is the plan's [ExecutionPlan.parallelism], read
     * from the installation's settings when the run was planned.
     */
    private fun walkSideBySide(plan: ExecutionPlan, executionId: Long): WorkflowExecution {
        val gate = BranchGate(plan.edges, plan.blocked)
        plan.carried.forEach { gate.follow(it.nodeKey, it.branch, it.option) }

        val frontier = Frontier(
            order = plan.steps.map { it.nodeKey },
            edges = plan.edges,
            gate = gate,
            lanes = ParallelLanes(plan.edges, plan.splits),
            limit = plan.parallelism,
            deadEnds = plan.deadEnds,
        )
        // Handed over still RUNNING by a replan: in flight when the process died. #448.
        val interrupted = plan.steps.filter { it.status == StepStatus.RUNNING }.mapTo(HashSet()) { it.nodeKey }
        val reported = LinkedBlockingQueue<Reported>()
        var broken: Throwable? = null

        while (true) {
            if (!frontier.stopping && steps.wasStopAsked(executionId)) {
                log.info("Execution {} was asked to stop; starting nothing more", executionId)
                frontier.stop()
            }
            for (move in frontier.next()) {
                when (move) {
                    is Frontier.Skip -> steps.skipStep(executionId, move.nodeKey, move.reason)
                    is Frontier.Run -> branches.execute {
                        reported.put(carryOut(executionId, move.nodeKey, move.nodeKey in interrupted))
                    }
                }
            }
            if (frontier.idle) break

            when (val report = reported.take()) {
                is Reported.Done -> {
                    val outcome = report.outcome
                    frontier.completed(report.nodeKey, outcome.branch, outcome.option, outcome.halt, outcome.output)
                }
                is Reported.Failed ->
                    if (frontier.failed(report.nodeKey, report.reason)) {
                        steps.recordFailureExit(executionId, report.nodeKey)
                    } else {
                        log.warn("Execution {} failed at {}: {}", executionId, report.nodeKey, report.reason)
                    }
                is Reported.Stopped -> frontier.stopped(report.nodeKey)
                is Reported.Broken -> {
                    // Not a step's failure but the engine's own - a write that
                    // could not be made. Nothing more starts, what is running
                    // is let finish, and then it is thrown, as a sequential
                    // run would have thrown it.
                    broken = broken ?: report.cause
                    frontier.stopped(report.nodeKey)
                }
            }
        }

        broken?.let { throw it }
        val failure = frontier.failure
        val halt = frontier.halt
        return when {
            failure != null -> steps.failRun(executionId, failure.nodeKey, failure.reason, frontier.unreachedCount)
            frontier.stopping -> steps.stopRun(executionId)
            halt != null -> {
                log.info("Execution {} stopped at {}: that path has nothing further to do", executionId, halt.first)
                steps.finishRun(executionId, stoppedAt = halt.first, reason = halt.second)
            }
            else -> steps.finishRun(executionId)
        }
    }

    /** One step carried out on a thread of [branches], as the walking thread is told about it. */
    private fun carryOut(executionId: Long, nodeKey: String, interrupted: Boolean): Reported = try {
        Reported.Done(nodeKey, runToDecision(executionId, nodeKey, interrupted))
    } catch (stopped: StepStoppedException) {
        Reported.Stopped(nodeKey)
    } catch (failure: StepFailedException) {
        Reported.Failed(nodeKey, failure.message ?: "the step failed")
    } catch (cause: Throwable) {
        Reported.Broken(nodeKey, cause)
    }

    private sealed interface Reported {
        val nodeKey: String

        data class Done(override val nodeKey: String, val outcome: StepOutcome) : Reported
        data class Failed(override val nodeKey: String, val reason: String) : Reported
        data class Stopped(override val nodeKey: String) : Reported
        data class Broken(override val nodeKey: String, val cause: Throwable) : Reported
    }

    /**
     * The threads the paths of a split are carried on. Issue #285.
     *
     * Unbounded here because the bound is the run's: the frontier never has
     * more of one run's steps going than the installation's setting allows,
     * and a run's own thread is already one per run. Daemon threads, named so
     * a thread dump says what they are.
     */
    private val threads = AtomicInteger()
    private val branches = Executors.newCachedThreadPool { work ->
        Thread(work, "orknux-branch-${threads.incrementAndGet()}").apply { isDaemon = true }
    }

    @PreDestroy
    fun shutdown() {
        branches.shutdownNow()
    }

    /**
     * Runs one step, and keeps running it for as long as it parks.
     *
     * The delay is spent on this thread, which is the whole of the difference
     * between this engine and the Temporal one: a durable timer costs nothing
     * while it runs down, and this costs a thread for every second of it. So it
     * is bounded — a run that wants to wait longer than the engine allows fails
     * where it waited, and says what would have carried it.
     *
     * @param interrupted whether the step was left RUNNING by a restart. Its
     *   first go here is then not a run but a settling: the lost attempt is
     *   failed as interrupted, and what that leaves - a park for the next
     *   attempt, or a failure - is carried on from exactly as any other
     *   outcome is. Issue #448.
     */
    private fun runToDecision(executionId: Long, nodeKey: String, interrupted: Boolean = false): StepOutcome {
        var waited = Duration.ZERO
        var first = interrupted

        while (true) {
            val outcome = if (first) steps.interruptStep(executionId, nodeKey) else steps.runStep(executionId, nodeKey)
            first = false
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
            sleepUnlessStopped(executionId, nodeKey, pause)
        }
    }

    /**
     * Sleeps for [pause], in chunks, and throws [StepStoppedException] the
     * moment the run is asked to stop. Issue #395.
     *
     * The chunk is what makes a stop take effect during a wait rather than only
     * after it: a node parked for ten minutes would otherwise be un-stoppable
     * until it woke on its own. The same exception a step cut short mid-work
     * throws, so the loop above has one way of ending a stopped run. #440.
     */
    private fun sleepUnlessStopped(executionId: Long, nodeKey: String, pause: Duration) {
        var left = pause
        while (left > Duration.ZERO) {
            if (steps.wasStopAsked(executionId)) throw StepStoppedException(nodeKey)
            // Woken: the step is run again now. Looked at per chunk, like a stop.
            if (woken.remove(executionId)) return
            val chunk = if (left < STOP_POLL) left else STOP_POLL
            Thread.sleep(chunk.toMillis())
            left -= chunk
        }
    }

    /** Runs asked to stop waiting; see [wake]. */
    private val woken = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    override fun wake(executionId: Long) {
        woken += executionId
    }

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
