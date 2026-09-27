package io.mszymanski.orknux.workflow.execution

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * How the stranded-run net is timed. Issues #406, #448.
 *
 * Its own settings, not more fields on [InlineExecutionProperties]: that is
 * about what one run may wait, and this is about the machine noticing a run
 * whose worker died was left with something still to do.
 */
@ConfigurationProperties(prefix = "orknux.execution.inline.sweep")
data class ParkedRunSweepProperties(
    /**
     * How often a pass looks for a stranded run nothing is carrying.
     *
     * Thirty seconds. A wait a live thread is carrying wakes within the engine's
     * own poll of it, and a live run moves between steps in milliseconds, so a
     * pass this often costs two small queries and catches a restart's orphans a
     * half-minute after the process is up.
     */
    val interval: Duration = Duration.ofSeconds(30),
    /**
     * How long a run must have sat still before a pass touches it.
     *
     * For a parked step, how far past its wake: a live worker resumes within
     * [InlineExecutionEngine.STOP_POLL] of the wake, so a step still WAITING a
     * good margin past its wake is one no thread is watching any more. For a
     * run with no wake to read - between two steps, or inside one - how long
     * since anything on its record was stamped. Comfortably longer than that
     * poll, and than the gap a live run leaves between finishing one step and
     * starting the next.
     */
    val grace: Duration = Duration.ofSeconds(30),
    /**
     * How long after the process comes up the first pass is.
     *
     * Twenty seconds: long enough that the context is up and the graph is
     * readable, short enough that a run a restart orphaned is picked up while
     * whoever started it is still watching for it. The first pass is an
     * ordinary pass - it looks for everything a later one does - so a restart's
     * orphans are found on it rather than an interval later.
     */
    val initialDelay: Duration = Duration.ofSeconds(20),
    /** False sweeps nothing on a timer. The suite sets it and calls [ParkedRunSweeper.sweep]. */
    val enabled: Boolean = true,
)

/**
 * The net under a run the process died under. Issues #406, #448.
 *
 * The inline engine carries a run on one thread, from its first step to its
 * last, and a restart kills that thread. Whatever the run was doing at that
 * moment is left exactly as it was written down: RUNNING, with something still
 * to do and nothing left to do it. Temporal has a durable history and needs none
 * of this; the inline engine, which backs the dev and one-container
 * installations, had nothing.
 *
 * Three states, found by two queries. A step that parks records when to wake
 * and is waited out on the thread, so a restart mid-wait leaves a WAITING step
 * whose wake nothing will read back (#406): [ExecutionStepRepository.parkedPast]
 * finds a run whose open step was due to wake a good while ago. A restart
 * between two steps leaves one COMPLETED and the next PENDING with nothing to
 * dispatch it, and a restart mid-step leaves a RUNNING step with no thread
 * underneath (#448): [WorkflowExecutionRepository.stalledBefore] finds a run
 * whose record has not moved for the whole grace and which is not parked. Both
 * hand their ids to [InlineExecutionEngine.resume], which reads the state the
 * open step was left in and does the right thing for it - asks a wait again,
 * fails an interrupted step so its retry policy can answer, walks on from a
 * finished one.
 *
 * Guards against racing a live worker, because a pass that swept a run
 * somebody is carrying would set two threads walking it at once - and the
 * second would fail a step the first is in the middle of.
 *
 * The grace is the first: only a run left alone long enough that no thread can
 * still be on it is looked at - a live wait wakes within the engine's poll of
 * its wake, a live run stamps a step every few milliseconds between two. On its
 * own it is not enough, because a step in a long model call stamps nothing for
 * minutes and a run whose approval wait is asked again every half-minute never
 * restamps the step it is asking about.
 *
 * So this process is asked directly, in two places, because a run is carried in
 * two ways. [StepInterrupts.isCarrying] knows a step in flight - whatever is
 * running it, the engine here or a Temporal activity in this JVM - and that is
 * the case the grace gets wrong. [InlineExecutionEngine.isDriving] knows a run
 * an engine thread is walking, gaps and sleeps included, which is where a run
 * spends most of its life and where no step register can see it. Neither alone
 * is the answer, and neither replaces the grace: a register emptied by a
 * restart says nothing is being carried the moment the process is up, long
 * before the record can be trusted to have stopped moving. Together: a run is
 * swept only when nobody here is holding it and it has sat still long enough
 * that nobody anywhere is.
 *
 * Each recovery runs on its own thread rather than the timer's: a resumed run
 * that walks on and parks again would otherwise hold the timer for its whole
 * next wait, and a slow one would stall every other recovery behind it. An id
 * already being carried is not handed out twice - the pass skips what is still
 * in flight, so a recovery that outlasts an interval is not started again.
 */
@Component
@ConditionalOnProperty(name = ["orknux.temporal.enabled"], havingValue = "false", matchIfMissing = true)
@EnableConfigurationProperties(ParkedRunSweepProperties::class)
class ParkedRunSweeper(
    private val steps: ExecutionStepRepository,
    private val executions: WorkflowExecutionRepository,
    private val engine: InlineExecutionEngine,
    /** Where a step in flight is registered, so one being run is not swept. Issue #440. */
    private val interrupts: StepInterrupts,
    private val properties: ParkedRunSweepProperties,
) : SmartLifecycle {

    /**
     * Built on each start rather than held as fields, because a stopped
     * executor cannot be started again - `shutdownNow` is permanent, and
     * scheduling on one afterwards throws. A context that is stopped and
     * started again is not hypothetical: the test suite does it between
     * classes, and so does anything that restarts the application context in
     * place. Issue #504.
     */
    private var clock: ScheduledExecutorService? = null

    private var carriers: ExecutorService? = null

    /** Runs being carried on right now, so a pass does not hand one out twice. */
    private val inFlight = ConcurrentHashMap.newKeySet<Long>()

    @Volatile
    private var running = false

    override fun start() {
        if (!properties.enabled) {
            log.info("Parked runs are not swept on a timer")
            return
        }
        clock = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "parked-run-sweep").apply { isDaemon = true }
        }
        carriers = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "parked-run-resume").apply { isDaemon = true }
        }
        running = true
        arm(properties.initialDelay.toSeconds())
        log.info(
            "Looking for runs left stranded by a restart, first in {}s and then every {}s",
            properties.initialDelay.toSeconds(),
            properties.interval.toSeconds(),
        )
    }

    override fun stop() {
        if (!running) return
        running = false
        clock?.shutdownNow()
        clock = null
        carriers?.shutdownNow()
        carriers = null
    }

    override fun isRunning(): Boolean = running

    /**
     * One pass. Returns how many stranded runs this call handed to a carrier,
     * which is what a test asserts on.
     *
     * Nothing here is transactional. It reads two lists of ids and hands each
     * to a thread that drives the run through its own step transactions - which
     * is precisely what must not be done from inside an open one.
     */
    fun sweep(): Int {
        val cutoff = OffsetDateTime.now().minus(properties.grace)
        val parked = steps.parkedPast(cutoff)
        val stalled = executions.stalledBefore(cutoff)
        // A run this process is working on is alive whatever its record says - a
        // step in a long model call stamps nothing for minutes - and is nobody
        // else's to carry. Both registers are asked because each sees half of
        // it; see the class comment.
        val stranded = (parked + stalled)
            .distinct()
            .filterNot { engine.isDriving(it) || interrupts.isCarrying(it) }
        if (stranded.isEmpty()) return 0

        val handed = stranded.count { executionId ->
            // Already being carried, or picked up by a pass still running: leave
            // it be, or two threads would walk the same run at once.
            if (!inFlight.add(executionId)) return@count false
            runCatching { carriers?.execute { carry(executionId) } }
                .onFailure {
                    inFlight.remove(executionId)
                    log.warn("Stranded run {} could not be handed to a carrier", executionId, it)
                }
                .isSuccess
        }
        if (handed > 0) {
            log.warn(
                "Carrying on {} run(s) left stranded before {}: {} parked past a wake, {} with no thread underneath",
                handed,
                cutoff,
                parked.size,
                stalled.size,
            )
        }
        return handed
    }

    private fun carry(executionId: Long) {
        try {
            engine.resume(executionId)
        } catch (failure: Exception) {
            log.warn("Stranded run {} could not be carried on", executionId, failure)
        } finally {
            inFlight.remove(executionId)
        }
    }

    private fun arm(afterSeconds: Long) {
        if (!running) return
        runCatching { clock?.schedule(Runnable { pass() }, afterSeconds, TimeUnit.SECONDS) }
            .onFailure { log.warn("The parked-run sweep could not be scheduled", it) }
    }

    private fun pass() {
        try {
            sweep()
        } catch (failure: Exception) {
            // Nothing above this catches, so a pass that threw would disappear
            // into the executor and take every later pass with it, since the
            // re-arm below is what keeps the timer alive.
            log.warn("Could not sweep stranded runs", failure)
        } finally {
            arm(properties.interval.toSeconds())
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(ParkedRunSweeper::class.java)
    }
}
