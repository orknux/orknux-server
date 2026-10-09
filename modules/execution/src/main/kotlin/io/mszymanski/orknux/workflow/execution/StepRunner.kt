package io.mszymanski.orknux.workflow.execution

import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.OffsetDateTime
import kotlin.math.pow
import kotlin.random.Random

/** What one step did, as the thing carrying the run needs to know it. */
data class StepOutcome(
    val status: StepStatus,
    val output: String? = null,
    val halt: Boolean = false,
    /** Which way out of a condition the run went; null for every other node. */
    val branch: EdgeBranch? = null,
    /** Which option of a decision the run went by, on an [EdgeBranch.OPTION] branch. */
    val option: String? = null,
    /**
     * Set on [StepStatus.WAITING]: how long to leave the step alone before
     * running it again. Whatever is carrying the run spends it.
     */
    val resumeAfter: Duration? = null,
)

/**
 * What a node does about failing, and how long it leaves between the goes.
 *
 * Six numbers rather than a wait and a flag, because a backoff is a curve and a
 * flag can name two of them. The arithmetic is here rather than inline where it
 * is spent, so the curve can be asserted without a test that actually sits out
 * the waits it is checking - and so the interface can draw the same sentence
 * about it that the engine will act on.
 */
/**
 * A step has parked, and its WAITING is written.
 *
 * Whatever wakes a parked step looks for WAITING, so something that arrived
 * while the step was still deciding to park found nothing to wake - the turn
 * had read its inbox, the answer landed, and then the step parked for the
 * whole of its wait. Told after the fact, whoever owns [sessionId] can look
 * again. Published rather than called, because this module knows nothing of
 * sessions.
 */
data class StepParked(val executionId: Long, val nodeKey: String, val sessionId: Long?)

/**
 * A step that wrote into a session has finished, however it finished.
 *
 * Something that arrived at the session while the step was mid-turn and after
 * it last read its inbox would otherwise wait for nobody: the step is not parked
 * to be woken, and whatever wakes a session with no step on it saw one running
 * and stood aside. Told after the fact, as [StepParked] is. Published only for a
 * step with a session, since nothing else listens.
 */
data class StepFinished(val executionId: Long, val nodeKey: String, val sessionId: Long)

data class RetryPolicy(
    val attempts: Int,
    /** The wait before the second attempt, and the whole of it at [NO_GROWTH]. */
    val backoff: Duration,
    /**
     * What the wait is multiplied by after each attempt.
     *
     * One is the fixed wait every policy was until now; two is the doubling that
     * replaced the flag; 1.5 is the curve neither word could say. Below one is
     * read as one rather than shrinking a wait, because a retry that comes back
     * sooner each time is nobody's intent and the editor cannot express it.
     */
    val multiplier: Double = NO_GROWTH,
    /**
     * The most any one wait may come to.
     *
     * Never above [MAX_WAIT] however it is set: a run cannot be made to
     * disappear for a day by a number typed into a box.
     */
    val maxWait: Duration = MAX_WAIT,
    /**
     * The fraction of a wait that may be taken off it at random.
     *
     * Downward only, which is what makes every other number here an upper bound
     * that stays one: a policy with jitter never waits longer than the same
     * policy without it, so the ceiling and the budget go on meaning what they
     * say. Nought is the old behaviour, and one is a wait drawn uniformly from
     * nothing up to what the curve asked for.
     */
    val jitter: Double = NO_JITTER,
    /**
     * The longest the whole business may take, work included; null is no limit.
     *
     * The one bound the other five cannot express between them. They bound the
     * waiting, and what happens between two waits is an action calling something
     * outside this installation, which takes as long as it takes: five attempts
     * at a request that times out after sixty seconds is five minutes of run
     * that no arrangement of waits accounts for.
     */
    val budget: Duration? = null,
) {

    /** The ceiling actually enforced: what was asked for, or the hour, whichever is less. */
    private val ceiling: Duration get() = if (maxWait < MAX_WAIT) maxWait else MAX_WAIT

    /**
     * How long to leave the step alone, having spent [attemptsSpent] attempts.
     *
     * Growth counts from the first retry, so the number written on the node is
     * what it waits before its second attempt on any curve; only the waits after
     * that one differ. Which also means steepening a node's curve never makes
     * its first retry later than it was.
     */
    fun waitAfter(attemptsSpent: Int): Duration {
        val ceiling = ceiling
        if (backoff >= ceiling) return ceiling
        if (multiplier <= NO_GROWTH) return backoff
        val grown = backoff.toMillis() * multiplier.pow((attemptsSpent - 1).coerceAtLeast(0))
        // Not a comparison of Durations, because far enough out the growth is
        // larger than a Duration holds - and infinity is still above the hour.
        return if (!grown.isFinite() || grown >= ceiling.toMillis()) ceiling else Duration.ofMillis(grown.toLong())
    }

    /**
     * The same wait with [jitter] taken off it, by however much [random] says.
     *
     * Separate from the arithmetic rather than folded into it, so what the curve
     * asks for stays something a test can assert exactly.
     */
    fun waitAfter(attemptsSpent: Int, random: Random): Duration {
        val full = waitAfter(attemptsSpent)
        if (jitter <= NO_JITTER || full.isZero) return full
        val kept = 1.0 - jitter.coerceIn(NO_JITTER, FULL_JITTER) * random.nextDouble()
        return Duration.ofMillis((full.toMillis() * kept).toLong())
    }

    companion object {

        /** A multiplier of one: the same wait before every retry. */
        const val NO_GROWTH: Double = 1.0

        /** No jitter: the wait the curve asked for, exactly. */
        const val NO_JITTER: Double = 0.0

        /** All of it: a wait drawn uniformly from nothing up to what the curve asked for. */
        const val FULL_JITTER: Double = 1.0

        /**
         * The longest a single wait may come to, whatever the curve.
         *
         * The same hour the editor already refuses to take more than for a fixed
         * wait: an hour is what one wait is allowed to cost the person waiting,
         * and which curve arrived at it does not change that. Left uncapped, ten
         * attempts doubling off that hour is three weeks of run - and the two
         * numbers that produced it are "3600" and "10", neither of which looks
         * like three weeks to whoever typed them.
         */
        val MAX_WAIT: Duration = Duration.ofHours(1)
    }
}

/**
 * The node's retry policy, or null where it has none.
 *
 * One attempt is no policy at all rather than a policy of one, so a node
 * carrying the number the editor shows by default costs nothing. Public because
 * the step is not the only thing its agent answers in: a watcher it set wakes it
 * after the step has finished, and that turn is retried by the same policy.
 */
fun ExecutionStep.retryPolicy(): RetryPolicy? = retryAttempts
    ?.takeIf { it > 1 }
    ?.let {
        RetryPolicy(
            attempts = it,
            backoff = Duration.ofSeconds((retryBackoffSeconds ?: 0).coerceAtLeast(0).toLong()),
            // Every null below is what the step meant before there was a
            // field for it: a wait that does not grow, the engine's own
            // ceiling, no jitter and no budget. So a step written by an
            // older version, or by a node nobody has opened since, waits
            // exactly what it waited then.
            multiplier = retryMultiplier ?: RetryPolicy.NO_GROWTH,
            maxWait = retryMaxWaitSeconds?.let { seconds -> Duration.ofSeconds(seconds.toLong()) }
                ?: RetryPolicy.MAX_WAIT,
            jitter = retryJitter ?: RetryPolicy.NO_JITTER,
            budget = retryBudgetSeconds?.let { seconds -> Duration.ofSeconds(seconds.toLong()) },
        )
    }

/**
 * Implemented by a failure that knows whether it is worth trying again.
 *
 * Declared here so a runner in another module can say so without the execution
 * module knowing what a Slack channel is.
 */
interface PermanentFailure {
    val permanent: Boolean
}

/**
 * Raised when a step could not be carried out; carries a message worth showing.
 *
 * @param permanent whether trying again could ever give a different answer. A
 *   channel that does not exist will not start existing on the second attempt,
 *   and a run that retries it three times only takes longer to say so; a network
 *   that dropped might well work. Read twice: by a node's own retry policy,
 *   which will not spend an attempt on something already settled, and by
 *   Temporal, which will not retry an activity that says so. A policy the step
 *   has already exhausted is settled by the same definition, which is what
 *   stops the two layers being multiplied together.
 */
class StepFailedException(
    val nodeKey: String,
    reason: String,
    override val permanent: Boolean = false,
) : RuntimeException(reason), PermanentFailure

/**
 * Raised when a step ended because its run was asked to stop. Issue #440.
 *
 * Not a failure, and deliberately not a subclass of one: nothing went wrong
 * with the step, somebody decided the run should end, and a catch written for
 * failures - a retry policy, a failure edge, Temporal's own retries - must not
 * see it. The step is already recorded by the time this is thrown; what is
 * left is for whichever engine is carrying the run to end the run.
 */
class StepStoppedException(val nodeKey: String) : RuntimeException("$nodeKey was stopped by request")

/**
 * The work of one step, and the two ways a run ends.
 *
 * Everything here is a single step's worth of work with its own writes, so it
 * can be called by the inline engine in a loop or by a Temporal activity one
 * call at a time — including a second time, if the first attempt died before
 * saying what happened.
 */
@Service
class StepRunner(
    private val executions: WorkflowExecutionRepository,
    private val steps: ExecutionStepRepository,
    private val log: RunLogger,
    /** Ordered by `@Order`; the first that claims a kind runs it. */
    private val runners: List<NodeRunner>,
    /**
     * Counted here because this is where both engines end a run: the inline one
     * calls [failRun] and [finishRun] directly, and Temporal reaches them through
     * an activity. Counting in either engine would count one deployment's runs.
     */
    private val metrics: WorkflowRunMetrics,
    /** Where a running step is registered, so a stop can reach it mid-call. Issue #440. */
    private val interrupts: StepInterrupts,
    /** Told when a step has parked; see [StepParked]. */
    private val published: org.springframework.context.ApplicationEventPublisher,
    transactionManager: org.springframework.transaction.PlatformTransactionManager,
) {

    /** What [carry] reads and writes the run under; see there. */
    private val transactions = org.springframework.transaction.support.TransactionTemplate(transactionManager)

    /**
     * Where a jittered wait's randomness comes from.
     *
     * Not injected, because there is nothing to configure and nothing to assert
     * through it: what jitter does to a wait is arithmetic on [RetryPolicy] and
     * is asserted there with a seed. All this has to be is different between two
     * runs that failed on the same outage, which is the whole point of it.
     */
    private val random: Random = Random.Default

    /**
     * Carries out one step and records what it did.
     *
     * A step that parks is not finished, so this is also how a parked step is
     * picked back up: the same call, with the same input, once the caller has
     * waited as long as the last outcome asked for.
     *
     * @throws StepFailedException if the runner could not; the step is left
     *   failed and the caller decides whether to try again.
     */
    fun runStep(executionId: Long, nodeKey: String): StepOutcome {
        val step = stepOf(executionId, nodeKey)
        val execution = executionOf(executionId)

        // Read here rather than handed in: the payload belongs to the run, not
        // to the message that asked for the step. A parked step picked up by
        // another worker reads the same thing this one did.
        val input = execution.carried ?: execution.input

        // A step that parked keeps the deadline it recorded, so a wait resumed
        // on another worker does not start its clock again. RUNNING with a
        // deadline is a wait whose worker died mid-question, which is the same
        // wait; anything else is this step starting.
        val resuming = step.waitUntil != null &&
            (step.status == StepStatus.WAITING || step.status == StepStatus.RUNNING)

        if (!resuming) {
            step.startedAt = OffsetDateTime.now()
            step.waitUntil = null
            // A resumed wait is the same attempt asking again; anything else is
            // a new one, which is what a retry policy is counting.
            step.attempts += 1
            // The first time this run reaches this step, whatever the attempt
            // count carried over from an earlier one says: a re-run starting
            // here gets the budget from where it starts, not what a previous
            // run had left of it.
            if (step.retryDeadline == null) {
                step.retryDeadline = step.retryBudgetSeconds?.let { step.startedAt?.plusSeconds(it.toLong()) }
            }
        }
        step.status = StepStatus.RUNNING
        step.finishedAt = null
        step.error = null
        step.input = input
        steps.save(step)

        val result = try {
            if (!step.enabled) {
                skippedAsDisabled(step)
            } else {
                // What began the run, alongside what it is carrying now. The second
                // is what lets a step deep in the graph still ask about the event
                // that started everything.
                //
                // Registered while it runs, so a stop asked mid-call reaches the
                // call rather than waiting for it to finish on its own. #440.
                interrupts.stoppable(executionId, nodeKey) {
                    runnerFor(step.kind).run(step, input, execution.input)
                }
            }
        } catch (interrupted: StepInterruptedException) {
            // The runner threw because the run was asked to stop and the call it
            // was in was cut short. Not a failure, so no policy is consulted and
            // nothing is parked: a stop that was retried would not be a stop.
            stopStep(executionId, step)
        } catch (failure: Exception) {
            // A runner that knows its failure is final says so, and that travels
            // with the step: Temporal reads it and stops retrying something that
            // cannot come out differently.
            val permanent = (failure as? PermanentFailure)?.permanent == true
            val reason = failure.message ?: failure::class.simpleName ?: "the step failed"
            return failed(executionId, step, input, reason, permanent)
        }

        if (result.status == StepStatus.WAITING) {
            step.status = StepStatus.WAITING
            steps.save(step)
            published.publishEvent(StepParked(executionId, nodeKey, step.sessionId))

            // Once, on the way in: a wait that asks the same question every
            // thirty seconds should not fill the log with what it is still doing.
            if (!resuming) {
                log.write(executionId, nodeKey, LogLevel.INFO, result.output ?: "${step.name} is waiting")
            }
            // It has produced nothing, so the next attempt is handed what this
            // one was.
            return StepOutcome(StepStatus.WAITING, input, resumeAfter = result.resumeAfter)
        }

        step.status = result.status
        step.output = result.output
        // Which way out of a condition the run went, written down rather than
        // only acted on: a later run that starts partway down this graph has to
        // know which edges this one took, and the answer is not recoverable
        // from the statuses alone - a node with no runtime is skipped too.
        step.branch = result.branch
        step.branchOption = result.option.takeIf { result.branch == EdgeBranch.OPTION }
        step.finishedAt = OffsetDateTime.now()
        steps.save(step)
        step.sessionId?.let { published.publishEvent(StepFinished(executionId, nodeKey, it)) }

        // What the run carries from here on. Written once, where it is read
        // from, so both engines carry the same thing and neither has to hand it
        // to the other.
        if (result.status == StepStatus.COMPLETED) carry(executionId, result.output)

        log.write(
            executionId,
            nodeKey,
            if (result.status == StepStatus.COMPLETED) LogLevel.SUCCESS else LogLevel.INFO,
            result.output ?: "${step.name} ${result.status.name.lowercase()}",
        )
        return StepOutcome(result.status, result.output, result.halt, result.branch, result.option)
    }

    /**
     * Settles a step that was RUNNING when the process died under it, as a
     * failure that says so. Issue #448.
     *
     * The other thing a restart leaves behind besides a wait. A step's work is
     * in flight on the thread carrying the run, and when that thread is gone
     * nothing can say how far the work got - the model may have answered, the
     * message may have been sent - so the step is not quietly run again, which
     * is the rule the planner already holds for a re-run: a step that charged a
     * card does not become repeatable because there is a good reason to repeat
     * it. It is failed with a reason that names the restart, and then treated
     * exactly as a runner that threw would be: a node with a retry policy and
     * attempts left parks for its next go, one without fails the run or takes
     * its failure edge. The dead attempt counts as spent, because it was
     * begun - `attempts` went up when the step started - which is what makes
     * "attempt 1 of 2" in the log true.
     *
     * Only for a step nobody is carrying: the sweeper asks the engine whether
     * this process still holds the run before it gets here, so a step that is
     * simply slow is not failed under a live thread. Refused, rather than
     * failing the wrong thing, where the step turns out not to be RUNNING any
     * more - the thread that was carrying it got to the record first.
     *
     * The exception is a step whose runner says asking again is its job -
     * [NodeRunner.asksAgainAfterRestart], an agent answering a message - which
     * is run again rather than failed, for as long as it has spent fewer than
     * [attempts] goes, the one that died included. Failing it left the message
     * unanswered until somebody pressed Rerun, which repeats the whole run;
     * Temporal had always asked such a step again. The bound is for a step
     * that is itself what kills the process: a model call that runs it out of
     * memory would otherwise restart it for ever. Issue #601.
     */
    fun interruptStep(executionId: Long, nodeKey: String, attempts: Int = 0): StepOutcome {
        val step = stepOf(executionId, nodeKey)
        check(step.status == StepStatus.RUNNING) {
            "Execution $executionId's step $nodeKey is ${step.status}, not left RUNNING by a restart"
        }
        if (step.enabled && step.attempts < attempts && runnerFor(step.kind).asksAgainAfterRestart(step)) {
            log.write(
                executionId,
                nodeKey,
                LogLevel.INFO,
                "${step.name} was interrupted by a restart on attempt ${step.attempts} of $attempts; asking it again",
            )
            return runStep(executionId, nodeKey)
        }
        val execution = executionOf(executionId)
        val input = execution.carried ?: execution.input
        return failed(executionId, step, input, "${step.name} was interrupted by a restart", permanent = false)
    }

    /**
     * What happens to a step that could not do its work: parked for another
     * go where its node's policy allows one, failed where it does not.
     *
     * One place for the runner that threw and the step a restart cut short,
     * so the two cannot come to read a policy two ways.
     */
    private fun failed(
        executionId: Long,
        step: ExecutionStep,
        input: String?,
        reason: String,
        permanent: Boolean,
    ): StepOutcome {
        val policy = step.retryPolicy()
        if (policy != null && !permanent && step.attempts < policy.attempts) {
            // Worked out here rather than inside parkForRetry, because the
            // budget is a question about this particular wait: a policy with
            // four attempts left and forty seconds of budget has to know
            // whether the next wait lands inside it before it parks.
            val wait = policy.waitAfter(step.attempts, random)
            val deadline = step.retryDeadline
            if (deadline == null || !OffsetDateTime.now().plus(wait).isAfter(deadline)) {
                return parkForRetry(executionId, step, input, reason, policy, wait)
            }
            // Parking would land past the budget, so it does not park. The
            // attempts it had left are not spent one at a time to arrive at
            // the same place: the budget is the answer, and it is already in.
            log.write(
                executionId,
                step.nodeKey,
                LogLevel.INFO,
                "${step.name} failed on attempt ${step.attempts} of ${policy.attempts}: $reason. " +
                    "Its ${policy.budget?.toSeconds() ?: 0}s budget for trying is spent, so it stops here",
            )
        }

        /*
         * A node's own policy is the whole of its retries.
         *
         * Temporal retries an activity three times of its own accord, so a
         * policy left to throw would be multiplied by three - five attempts
         * asked for and fifteen performed. Exhausting the policy settles the
         * failure by definition: there is nothing left to try, which is
         * exactly what `permanent` means to the activity that reads it.
         * A node with no policy keeps the arrangement it has always had.
         */
        failStep(executionId, step, reason, permanent || policy != null)
    }

    /**
     * What a step whose node is switched off did, which is nothing, said the
     * way a runner would say it.
     *
     * Here, before any runner is chosen, rather than inside each of the five:
     * one place that every kind passes through is one place that cannot be
     * forgotten when the sixth kind arrives. SKIPPED rather than COMPLETED,
     * because a step that performed no work and reports success is the lie
     * [UnimplementedNodeRunner] exists to avoid - and because a skipped step
     * leaves what the run carries untouched, which is exactly "hand on what
     * reached it unchanged" without a second way of saying so.
     *
     * A condition that is switched off takes its "yes" way out. A disabled
     * condition is one somebody wants out of the way while the rest is tried,
     * and the rest is down the path the condition would have let through; the
     * "no" path is the one that stops the run, which is the opposite of what
     * switching the question off is for. Issue #439.
     */
    private fun skippedAsDisabled(step: ExecutionStep): StepResult = StepResult(
        StepStatus.SKIPPED,
        DISABLED_REASON,
        branch = when (step.kind) {
            NodeKind.CONDITION -> EdgeBranch.YES
            // A decision nobody asked has no answer to go by, which is exactly
            // what its unsure edge is drawn for; following every option instead
            // would run the branches it exists to choose between. Issue #577.
            NodeKind.DECISION -> EdgeBranch.UNSURE
            else -> null
        },
    )

    /**
     * Parks a failed attempt instead of throwing, so the next one is asked for
     * the way a wait is.
     *
     * Retrying through the wait both engines already honour is what keeps the
     * policy from being applied twice: the activity answers normally, so
     * Temporal has no failure to retry on top, and the backoff runs down on a
     * durable timer rather than inside an activity holding a worker and burning
     * its start-to-close timeout.
     *
     * @param wait what the caller worked out this attempt had earned, already
     *   checked against the budget - both written down and spent here, so the
     *   log cannot quote one length of time while the run sits out another.
     */
    private fun parkForRetry(
        executionId: Long,
        step: ExecutionStep,
        input: String?,
        reason: String,
        policy: RetryPolicy,
        wait: Duration,
    ): StepOutcome {
        step.status = StepStatus.WAITING
        step.error = reason.take(ERROR_LENGTH)
        // The deadline a parked runner wrote belongs to the attempt that wrote
        // it; the next attempt starts its own clock.
        step.waitUntil = null
        steps.save(step)

        // The wait this attempt in particular earned, which on any curve but a
        // flat one is not what the last one waited - so the log quotes what the
        // run is about to sit out rather than the number written on the node.
        log.write(
            executionId,
            step.nodeKey,
            LogLevel.INFO,
            "${step.name} failed on attempt ${step.attempts} of ${policy.attempts}: $reason. " +
                "Trying again in ${wait.toSeconds()}s",
        )
        // It produced nothing, so the next attempt is handed what this one was.
        return StepOutcome(StepStatus.WAITING, input, resumeAfter = wait)
    }

    /**
     * Records that a failed step's failure edge is the way the run went on.
     *
     * Written down for the same reason a condition's answer is: a later run
     * starting partway down cannot otherwise tell a step whose failure was
     * handled from one that stopped the run where it stood.
     */
    fun recordFailureExit(executionId: Long, nodeKey: String): ExecutionStep {
        val step = stepOf(executionId, nodeKey)
        step.branch = EdgeBranch.FAILURE
        log.write(executionId, nodeKey, LogLevel.INFO, "${step.name} failed; the run is taking its failure branch")
        return steps.save(step)
    }

    /**
     * Records a step the engine gave up on, the way a thrown runner is recorded.
     *
     * An engine that will not wait as long as a step asked ends up here, so a
     * step that failed looks the same whichever side gave up on it.
     */
    fun failStep(executionId: Long, nodeKey: String, reason: String, permanent: Boolean = false): Nothing =
        failStep(executionId, stepOf(executionId, nodeKey), reason, permanent)

    private fun failStep(
        executionId: Long,
        step: ExecutionStep,
        reason: String,
        permanent: Boolean = false,
    ): Nothing {
        step.status = StepStatus.FAILED
        step.error = reason.take(ERROR_LENGTH)
        step.finishedAt = OffsetDateTime.now()
        steps.save(step)
        log.write(executionId, step.nodeKey, LogLevel.ERROR, "${step.name} failed: $reason")
        throw StepFailedException(step.nodeKey, reason, permanent)
    }

    /**
     * Records a step that was cut short because its run was asked to stop, and
     * hands the ending to the engine. Issue #440.
     *
     * SKIPPED, with a line saying why, rather than FAILED or PENDING. It did not
     * fail - nothing about it went wrong, and a red step saying "stopped by
     * request" would send somebody looking for a fault. It was not unreached
     * either: it started, and has the times to show it. Skipped is the status
     * the run already uses for a step that was not carried out for a reason
     * that is the run's rather than the step's, and the output is where such a
     * step says its reason. It does not go through [failStep], so no policy
     * sees it and no attempt is spent.
     */
    private fun stopStep(executionId: Long, step: ExecutionStep): Nothing {
        step.status = StepStatus.SKIPPED
        step.output = STOPPED_STEP
        step.error = null
        step.finishedAt = OffsetDateTime.now()
        steps.save(step)
        log.write(executionId, step.nodeKey, LogLevel.INFO, "${step.name} was stopped before it finished")
        throw StepStoppedException(step.nodeKey)
    }

    /**
     * Records a step the run went past, because the branch that reaches it was
     * not the one taken.
     *
     * Written down rather than left pending: "skipped, the condition went the
     * other way" is a fact about what happened, and a step silently absent from
     * a run is the kind of gap somebody debugging spends an afternoon on.
     */
    fun skipStep(executionId: Long, nodeKey: String, reason: String): ExecutionStep {
        val step = stepOf(executionId, nodeKey)
        step.status = StepStatus.SKIPPED
        step.output = reason.take(ERROR_LENGTH)
        step.startedAt = OffsetDateTime.now()
        step.finishedAt = OffsetDateTime.now()
        log.write(executionId, nodeKey, LogLevel.INFO, "${step.name} skipped: $reason")
        return steps.save(step)
    }

    /** Stops the run. The steps it never reached stay pending, because they were. */
    fun failRun(executionId: Long, nodeKey: String, reason: String, unreached: Int): WorkflowExecution {
        val execution = executionOf(executionId)
        /*
         * Read before the status is changed, because a Temporal activity is
         * delivered at least once: an attempt that ended the run and then died
         * before saying so arrives again, and the second one must not be a
         * second failure. Writing the same ending twice is harmless; counting
         * it twice is a failure rate nobody can trust.
         */
        val counts = execution.status == ExecutionStatus.RUNNING
        execution.status = ExecutionStatus.FAILED
        execution.error = reason.take(ERROR_LENGTH)
        execution.finishedAt = OffsetDateTime.now()

        log.write(
            executionId,
            null,
            LogLevel.ERROR,
            "${execution.workflowName} stopped at $nodeKey with $unreached steps unreached",
        )
        if (counts) metrics.runFailed()
        return executions.save(execution)
    }

    /**
     * Ends the run as completed.
     *
     * @param stoppedAt the node that decided there was nothing further to do,
     *   and [reason] what it said. Both null when the run simply reached the
     *   end, which is the ordinary case.
     */
    fun finishRun(executionId: Long, stoppedAt: String? = null, reason: String? = null): WorkflowExecution {
        val execution = executionOf(executionId)
        // See [failRun]: an activity that is delivered twice ends the run twice.
        val counts = execution.status == ExecutionStatus.RUNNING
        execution.status = ExecutionStatus.COMPLETED
        execution.finishedAt = OffsetDateTime.now()
        execution.stoppedAtNodeKey = stoppedAt
        execution.stoppedReason = reason?.take(ERROR_LENGTH)

        val ending = if (reason == null) {
            "${execution.workflowName} finished"
        } else {
            "${execution.workflowName} stopped after $stoppedAt: $reason"
        }
        log.write(executionId, null, if (reason == null) LogLevel.SUCCESS else LogLevel.INFO, ending)
        if (counts) metrics.runCompleted()
        return executions.save(execution)
    }

    /** Whether this run has been asked to stop; the engine reads it before each step. Issue #395. */
    fun wasStopAsked(executionId: Long): Boolean = executionOf(executionId).stopRequested

    /**
     * Ends a run because it was asked to, where it stands. Issue #395.
     *
     * A terminal state of its own, [ExecutionStatus.STOPPED], rather than a
     * completion or a failure: a stopped run did neither, and it does not
     * resume. The steps it had not reached stay PENDING, which on a run that
     * has ended reads as "never reached", the same as a run stopped by a
     * condition. A step the stop cut short is already recorded by [stopStep]
     * as skipped, saying so. Issue #440.
     */
    fun stopRun(executionId: Long): WorkflowExecution {
        val execution = executionOf(executionId)
        // Only a run still going is stopped; a request that lands after it has
        // already ended changes nothing, the way a twice-delivered ending does.
        if (execution.status != ExecutionStatus.RUNNING) return execution
        execution.status = ExecutionStatus.STOPPED
        execution.finishedAt = OffsetDateTime.now()
        execution.stoppedReason = STOP_REASON
        log.write(executionId, null, LogLevel.INFO, "${execution.workflowName} was stopped")
        return executions.save(execution)
    }

    /**
     * Adds what a step produced to what the run carries. Issue #285.
     *
     * Merged into the run as it stands now rather than into what the step was
     * handed, under a lock on the run. One path at a time, the two are the same
     * thing; with paths running side by side another step may have added its
     * own output since this one started, and merging into the older copy would
     * quietly drop it - which is the one thing a node after the paths meet
     * relies on finding. Written on its own, too, so nothing else on the run
     * this step read when it began - a stop asked for since - is written back.
     */
    private fun carry(executionId: Long, produced: String?) {
        transactions.executeWithoutResult {
            val run = executions.lockById(executionId) ?: throw ExecutionNotFoundException(executionId)
            run.carried = Payloads.carry(run.carried ?: run.input, produced)
            executions.save(run)
        }
    }

    private fun stepOf(executionId: Long, nodeKey: String) =
        steps.findByExecutionIdAndNodeKey(executionId, nodeKey)
            ?: error("Execution $executionId has no step $nodeKey")

    private fun executionOf(id: Long) = executions.findByIdOrNull(id) ?: throw ExecutionNotFoundException(id)

    private fun runnerFor(kind: NodeKind): NodeRunner =
        runners.firstOrNull { it.supports(kind) } ?: error("No runner claims $kind nodes")

    private companion object {
        /** Matches the column. */
        const val ERROR_LENGTH = 1000

        /** What a stopped run says for why it ended. Issue #395. */
        const val STOP_REASON = "stopped by request"

        /** What a step cut short by a stop says for itself. Issue #440. */
        const val STOPPED_STEP = "Stopped by request before it finished"

        /** What a step whose node is switched off says for itself. Issue #439. */
        const val DISABLED_REASON = "Skipped: the node is disabled"
    }
}
