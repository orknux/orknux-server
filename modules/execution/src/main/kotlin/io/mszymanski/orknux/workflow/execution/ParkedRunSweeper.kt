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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * How the parked-run net is timed. Issue #406.
 *
 * Its own settings, not more fields on [InlineExecutionProperties]: that is
 * about what one run may wait, and this is about the machine noticing a run
 * whose worker died still owes a wake.
 */
@ConfigurationProperties(prefix = "orknux.execution.inline.sweep")
data class ParkedRunSweepProperties(
    /**
     * How often a pass looks for a parked run nothing is watching.
     *
     * Thirty seconds. A wait a live thread is carrying wakes within the engine's
     * own poll of it, so a pass this often costs one small query and catches a
     * restart's orphans a half-minute after the process is up.
     */
    val interval: Duration = Duration.ofSeconds(30),
    /**
     * How far past its wake a step must be before a pass touches it.
     *
     * The guard against sweeping a wait out from under the thread that is about
     * to answer it: a live worker resumes within [InlineExecutionEngine.STOP_POLL]
     * of the wake, so a step still WAITING a good margin past its wake is one no
     * thread is watching any more. Comfortably longer than that poll.
     */
    val grace: Duration = Duration.ofSeconds(30),
    /**
     * How long after the process comes up the first pass is.
     *
     * Twenty seconds: long enough that the context is up and the graph is
     * readable, short enough that a run a restart orphaned is picked up while
     * whoever started it is still watching for it.
     */
    val initialDelay: Duration = Duration.ofSeconds(20),
    /** False sweeps nothing on a timer. The suite sets it and calls [ParkedRunSweeper.sweep]. */
    val enabled: Boolean = true,
)

/**
 * The net under a wait. Issue #406.
 *
 * A step that parks on the inline engine records when to wake and is then
 * waited out on the thread carrying the run. A restart kills that thread, and
 * because nothing else reads the wake back, the run sits RUNNING with a WAITING
 * step for ever. Temporal has a durable timer and needs none of this; the
 * inline engine, which backs the dev and one-container installations, had
 * nothing.
 *
 * So: one query on a timer for a run still RUNNING whose open step was due to
 * wake a good while ago, and [InlineExecutionEngine.resume] to carry each on
 * from where it parked. The margin is what keeps it off a wait a live worker is
 * about to answer itself - only a wake left unanswered long enough that no
 * thread can still be watching it is swept.
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
    private val engine: InlineExecutionEngine,
    private val properties: ParkedRunSweepProperties,
) : SmartLifecycle {

    private val clock = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "parked-run-sweep").apply { isDaemon = true }
    }

    private val carriers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "parked-run-resume").apply { isDaemon = true }
    }

    /** Runs being carried on right now, so a pass does not hand one out twice. */
    private val inFlight = ConcurrentHashMap.newKeySet<Long>()

    @Volatile
    private var running = false

    override fun start() {
        if (!properties.enabled) {
            log.info("Parked runs are not swept on a timer")
            return
        }
        running = true
        arm(properties.initialDelay.toSeconds())
        log.info("Looking for parked runs left past their wake, every {}s", properties.interval.toSeconds())
    }

    override fun stop() {
        if (!running) return
        running = false
        clock.shutdownNow()
        carriers.shutdownNow()
    }

    override fun isRunning(): Boolean = running

    /**
     * One pass. Returns how many parked runs this call handed to a carrier,
     * which is what a test asserts on.
     *
     * Nothing here is transactional. It reads a list of ids and hands each to a
     * thread that drives the run through its own step transactions - which is
     * precisely what must not be done from inside an open one.
     */
    fun sweep(): Int {
        val cutoff = OffsetDateTime.now().minus(properties.grace)
        val parked = steps.parkedPast(cutoff)
        if (parked.isEmpty()) return 0

        val handed = parked.count { executionId ->
            // Already being carried, or picked up by a pass still running: leave
            // it be, or two threads would walk the same run at once.
            if (!inFlight.add(executionId)) return@count false
            runCatching { carriers.execute { carry(executionId) } }
                .onFailure {
                    inFlight.remove(executionId)
                    log.warn("Parked run {} could not be handed to a carrier", executionId, it)
                }
                .isSuccess
        }
        if (handed > 0) log.warn("Carrying on {} parked run(s) left past their wake before {}", handed, cutoff)
        return handed
    }

    private fun carry(executionId: Long) {
        try {
            engine.resume(executionId)
        } catch (failure: Exception) {
            log.warn("Parked run {} was left past its wake and could not be carried on", executionId, failure)
        } finally {
            inFlight.remove(executionId)
        }
    }

    private fun arm(afterSeconds: Long) {
        if (!running) return
        runCatching { clock.schedule(Runnable { pass() }, afterSeconds, TimeUnit.SECONDS) }
            .onFailure { log.warn("The parked-run sweep could not be scheduled", it) }
    }

    private fun pass() {
        try {
            sweep()
        } catch (failure: Exception) {
            // Nothing above this catches, so a pass that threw would disappear
            // into the executor and take every later pass with it, since the
            // re-arm below is what keeps the timer alive.
            log.warn("Could not sweep parked runs", failure)
        } finally {
            arm(properties.interval.toSeconds())
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(ParkedRunSweeper::class.java)
    }
}
