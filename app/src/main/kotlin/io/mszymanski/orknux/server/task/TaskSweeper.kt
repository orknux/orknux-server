package io.mszymanski.orknux.server.task

import io.mszymanski.orknux.connector.cluster.ClusterLeader
import io.mszymanski.orknux.server.attachment.InstallationSettings
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.SmartLifecycle
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * How often something looks for a task nothing is carrying.
 *
 * Its own settings rather than three more fields on [TaskProperties]: that one
 * is about what a task may spend, every value in it is copied onto the row when
 * the task is created, and none of that is true here. This is about the machine
 * noticing it dropped something.
 */
@ConfigurationProperties(prefix = "orknux.task.sweep")
data class TaskSweepProperties(
    /**
     * How many minutes a task may sit at QUEUED before something hands it over
     * again - and, because they are the same number, how often that is looked
     * for.
     *
     * Where a fresh installation starts and not the last word: on an
     * installation running the inline engine it is a field on the Admin screen,
     * and once an administrator has set it theirs is the answer. See
     * [InstallationSettings.taskSweepMinutes].
     *
     * Five minutes. A task leaves QUEUED in the time it takes a worker to read
     * its row, so five minutes is three hundred times the ordinary case and
     * still short enough that somebody who started a task and went to make
     * coffee comes back to it working. Shorter would not be *unsafe* - what
     * stops a second turn is the engine, not the clock - it would only be a
     * query asked oftener for nothing.
     */
    val minutes: Int = 5,
    /** False sweeps nothing on a timer. The suite sets it and calls [TaskSweeper.sweep]. */
    val enabled: Boolean = true,
    /**
     * How long after the process comes up the first pass is.
     *
     * A minute, and after the inline engine's own revival rather than racing
     * it: that runs from a lifecycle callback on the way up and picks up
     * everything left in flight, so a pass a second later would find nothing
     * and a pass a minute later finds only what the revival could not.
     */
    val initialDelay: Duration = Duration.ofMinutes(1),
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TaskSweepProperties::class)
class TaskSweepConfig

/**
 * The net under the hand-over.
 *
 * A task is recorded and then handed to whatever carries it, and #296 fixed the
 * *cause* of that hand-over going missing - it ran before the row it announced
 * had committed, so the worker read nothing and gave up. What it did not add is
 * anything that notices when a hand-over is lost for one of the reasons still
 * left, and there are three:
 *
 *  - a process killed between the commit and the callback that hands over;
 *  - a thread pool that refuses the work, which logged a warning and stopped;
 *  - and, on Temporal, a workflow that starts and cannot run. That is not
 *    hypothetical: registering the task workflow without its activity left
 *    every task failing on `Activity Type "AdvanceTask" is not registered`,
 *    with the row still at QUEUED and nothing anywhere that would ever look at
 *    it again. See [TemporalTaskEngine].
 *
 * All three end the same way - a row that says QUEUED for ever - and until now
 * the only thing that recovered from any of them was restarting an inline
 * installation, whose engine sweeps on the way up. A Temporal installation had
 * no net at all, restart included.
 *
 * So: one query on a timer, and the engine decides whether the task it names is
 * really loose. **Handing over a task somebody already holds is the whole of
 * the risk here**, and it is answered in [TaskEngine.recover] rather than here,
 * because the two engines can answer it and a clock cannot. Nothing in this
 * class knows or guesses what is running.
 *
 * It sweeps on Temporal as well as inline, which is deliberate and is not what
 * the *setting* does - the field on the Admin screen is offered only where the
 * inline engine is running, because that is what was asked for. The net is
 * needed in both places and needed most in the one that had none.
 */
@Component
class TaskSweeper(
    private val tasks: TaskRepository,
    private val engine: TaskEngine,
    private val settings: InstallationSettings,
    private val properties: TaskSweepProperties,
    /** Which replica sweeps; alone where nothing says otherwise. Issue #597. */
    private val leader: ClusterLeader = ClusterLeader.alone(),
) : SmartLifecycle {

    /**
     * Built on each start rather than held as a field, because a stopped
     * executor cannot be started again - `shutdownNow` is permanent, and
     * scheduling on one afterwards throws. A context that is stopped and
     * started again is not hypothetical: the test suite does it between
     * classes, and so does anything that restarts the application context in
     * place. Issue #504.
     */
    private var clock: ScheduledExecutorService? = null

    @Volatile
    private var running = false

    /**
     * Armed only where the timer is wanted, and built either way.
     *
     * The same bargain [io.mszymanski.orknux.server.revision.RevisionSweeper]
     * makes, for the same reason: the suite turns the clock off so that nothing
     * hands a task over in the middle of a test, and it still has to be able to
     * call [sweep] itself. A bean that does not exist cannot be asked.
     */
    override fun start() {
        if (!properties.enabled) {
            log.info("Queued tasks are not swept on a timer")
            return
        }
        clock = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "task-sweep").apply { isDaemon = true }
        }
        running = true
        arm(properties.initialDelay.toSeconds())
        log.info("Looking for tasks left queued longer than {} minutes, every {} minutes", minutes(), minutes())
    }

    override fun stop() {
        if (!running) return
        running = false
        clock?.shutdownNow()
        clock = null
    }

    override fun isRunning(): Boolean = running

    /**
     * One pass. Returns how many tasks this call put back to work, which is what
     * a test asserts on.
     *
     * Nothing here is transactional. It reads a list of ids and hands them to
     * the engine, and on the inline engine that means a worker on another thread
     * and another connection - which is precisely what must not be done from
     * inside an open transaction, and is why there is not one.
     */
    fun sweep(): Int {
        val cutoff = OffsetDateTime.now().minusMinutes(minutes().toLong())
        val stranded = tasks.idsInStateSince(TaskStatus.QUEUED, cutoff)
        if (stranded.isEmpty()) return 0

        /*
         * One task at a time, and a failure on one is not the end of the pass.
         * On Temporal the hand-over is a call across the network, so the way
         * this fails is one task's start being refused for a reason the next
         * task's would not be - and a pass that gave up on the first would
         * leave every task behind it stranded for another interval.
         */
        val taken = stranded.count { taskId ->
            runCatching { engine.recover(taskId) }
                .onFailure { log.warn("Task {} was left queued and could not be handed over", taskId, it) }
                .getOrDefault(false)
        }
        if (taken > 0) log.warn("Handed over {} task(s) left queued since before {}", taken, cutoff)
        return taken
    }

    /**
     * The next pass, at whatever the interval says now.
     *
     * Re-armed after each pass rather than fixed at start-up, which is what
     * makes the setting take effect without a restart: an administrator who
     * changes it is answered by the pass after the one already scheduled. The
     * cost of that is a detection window of up to twice the interval, which is
     * the right trade for a net that exists to catch something rare.
     */
    private fun arm(afterSeconds: Long) {
        if (!running) return
        runCatching { clock?.schedule(Runnable { pass() }, afterSeconds, TimeUnit.SECONDS) }
            .onFailure { log.warn("The task sweep could not be scheduled", it) }
    }

    private fun pass() {
        try {
            timedPass()
        } catch (failure: Exception) {
            // Nothing above this catches, so a pass that threw would otherwise
            // disappear into an executor - and take every later pass with it,
            // since the re-arm below is what keeps the timer alive.
            log.warn("Could not sweep queued tasks", failure)
        } finally {
            arm(minutes().toLong() * SECONDS_PER_MINUTE)
        }
    }

    /**
     * What the timer calls: a pass, on the one replica that leads. Issue #597.
     * Every replica keeps its timer, so the work moves when the leader dies;
     * a test asks [sweep] directly, or this to see the gate.
     */
    fun timedPass(): Boolean {
        if (!leader.leads()) return false
        sweep()
        return true
    }

    /** What the administrator chose, or what the file said if nobody has. */
    private fun minutes(): Int = settings.taskSweepMinutes()

    private companion object {
        const val SECONDS_PER_MINUTE = 60L

        val log = LoggerFactory.getLogger(TaskSweeper::class.java)
    }
}
