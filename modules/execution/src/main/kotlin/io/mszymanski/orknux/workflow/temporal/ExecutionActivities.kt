package io.mszymanski.orknux.workflow.temporal

import io.mszymanski.orknux.workflow.execution.StepFailedException
import io.mszymanski.orknux.workflow.execution.StepRunner
import io.mszymanski.orknux.workflow.execution.StepStatus
import io.mszymanski.orknux.workflow.execution.StepStoppedException
import io.temporal.failure.ApplicationFailure
import io.temporal.activity.Activity
import io.temporal.activity.ActivityExecutionContext
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The parts of a run that touch the world: they read and write the database and
 * call whatever a node does. Nothing here may be called from the workflow's own
 * code path other than through the stub, which is what lets Temporal replay a
 * run without doing any of it twice.
 */
@ActivityInterface
interface ExecutionActivities {

    /**
     * Carries out one step, or as much of it as it is ready to do: a step that
     * is waiting for something reports that and says when to ask again, rather
     * than holding this activity until it is ready. Throwing is how a step
     * reports it could not.
     */
    @ActivityMethod
    fun runStep(command: RunStepCommand): StepReport

    @ActivityMethod
    fun failRun(command: FailRunCommand)

    @ActivityMethod
    fun finishRun(command: FinishRunCommand)

    /** Writes down a step the run went past, because its branch was not taken. */
    @ActivityMethod
    fun skipStep(command: SkipStepCommand)

    /** Writes down that a failed step's failure edge is where the run went next. */
    @ActivityMethod
    fun recordFailureExit(command: RecordFailureExitCommand)

    /** Ends a run that was asked to stop, where it stands. Issue #440. */
    @ActivityMethod
    fun stopRun(command: StopRunCommand)
}

/**
 * Delegates to [StepRunner], which is also what the inline engine uses: a step
 * has to do the same thing and record the same thing whichever engine is
 * driving it, or the two would drift.
 */
@Component
class ExecutionActivitiesImpl(private val steps: StepRunner) : ExecutionActivities {

    /**
     * Says the step is still being worked on, while it is. Issue #601.
     *
     * A killed worker cannot say it died, so what Temporal notices is that it
     * has stopped saying it is alive - within the heartbeat timeout, rather
     * than when the step's whole start-to-close timeout runs out. Off its own
     * thread, because the activity's thread is inside a model call that may
     * not come back for minutes and has nothing to say until it does.
     */
    private val heart = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "orknux-step-heartbeat").apply { isDaemon = true }
    }

    @PreDestroy
    fun shutdown() {
        heart.shutdownNow()
    }

    override fun runStep(command: RunStepCommand): StepReport {
        val beating = heartbeat()
        try {
            return carryOut(command)
        } finally {
            beating?.cancel(false)
        }
    }

    /**
     * Starts heartbeating for the activity on this thread, where it was given a
     * heartbeat timeout; null where it was not, or where this is not an activity
     * at all - the suite calls [runStep] directly too.
     */
    private fun heartbeat(): ScheduledFuture<*>? {
        val context = runCatching { Activity.getExecutionContext() }.getOrNull() ?: return null
        val timeout = context.info.heartbeatTimeout
        if (timeout == null || timeout.isZero || timeout.isNegative) return null
        // A third of the timeout apart, so two can be lost before Temporal
        // counts the worker dead. The SDK throttles what it sends on to the
        // service, so this is a ceiling on the calls rather than a rate of them.
        val every = (timeout.toMillis() / 3).coerceAtLeast(MIN_BEAT_MILLIS)
        return heart.scheduleAtFixedRate({ beat(context) }, 0, every, TimeUnit.MILLISECONDS)
    }

    private fun beat(context: ActivityExecutionContext) {
        try {
            context.heartbeat(null)
        } catch (gone: Exception) {
            // Temporal no longer counts this attempt as the step - it timed out
            // or was cancelled, and another attempt has it or will. Saying it is
            // alive any more changes nothing; the work it is in finishes and its
            // answer is thrown away. Thrown on, because a task that throws is
            // one the executor runs no more.
            log.info("Stopped heartbeating a step Temporal has given up on: {}", gone.message)
            throw gone
        }
    }

    private fun carryOut(command: RunStepCommand): StepReport {
        val outcome = try {
            steps.runStep(command.executionId, command.nodeKey)
        } catch (failure: StepFailedException) {
            // Retrying a channel that does not exist only reaches the same
            // conclusion three times, a second apart, filling the log on the way.
            // Temporal will not retry a failure marked non-retryable, so a step
            // that knows it is final says so here.
            if (failure.permanent) {
                throw ApplicationFailure.newNonRetryableFailureWithCause(
                    failure.message ?: "the step failed",
                    failure::class.java.name,
                    failure,
                )
            }
            throw failure
        } catch (stopped: StepStoppedException) {
            /*
             * Cut short because the run was asked to stop. Answered normally
             * rather than thrown, for the reason a parked step is: a thrown
             * activity is one Temporal retries, and a stop that was retried
             * would not be a stop. The step is already recorded; the workflow
             * reads the flag and ends the run. Issue #440.
             */
            return StepReport(status = StepStatus.SKIPPED, stopped = true)
        }
        return StepReport(
            status = outcome.status,
            output = outcome.output,
            halt = outcome.halt,
            branch = outcome.branch,
            option = outcome.option,
            // A timer is the granularity Temporal deals in, so anything under a
            // second becomes one: sleeping for none of it would only spin.
            resumeAfterSeconds = outcome.resumeAfter?.toSeconds()?.coerceAtLeast(1),
        )
    }

    override fun skipStep(command: SkipStepCommand) {
        steps.skipStep(command.executionId, command.nodeKey, command.reason)
    }

    override fun recordFailureExit(command: RecordFailureExitCommand) {
        steps.recordFailureExit(command.executionId, command.nodeKey)
    }

    override fun failRun(command: FailRunCommand) {
        steps.failRun(command.executionId, command.nodeKey, command.reason, command.unreached)
    }

    override fun finishRun(command: FinishRunCommand) {
        steps.finishRun(command.executionId, command.stoppedAt, command.reason)
    }

    override fun stopRun(command: StopRunCommand) {
        steps.stopRun(command.executionId)
    }

    private companion object {
        val log = LoggerFactory.getLogger(ExecutionActivitiesImpl::class.java)

        /** However short the timeout, not a heartbeat call in a tight loop. */
        const val MIN_BEAT_MILLIS = 200L
    }
}
