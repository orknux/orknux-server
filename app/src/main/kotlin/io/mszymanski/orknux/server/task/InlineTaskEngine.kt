package io.mszymanski.orknux.server.task

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.SmartLifecycle
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tasks carried by this process, for an installation with no Temporal.
 *
 * That is not a corner: the all-in-one image runs with
 * `ORKNUX_TEMPORAL_ENABLED=false`, so this is what most people who try the
 * product are actually running, and the suite runs on it too.
 *
 * It is a thread pool and a clock, and it keeps nothing in either of them. A
 * task's whole state is its row and its session, which is what lets this be as
 * simple as it is: a turn that is lost to a restart is a turn that was not
 * written down, and the one after it starts from what was. The revival on the
 * way back up is the other half of that - without it a task interrupted by a
 * restart would sit at RUNNING for ever, which is exactly what the inline
 * workflow engine beside this does and is worth not repeating.
 *
 * Unlike the workflow engine, a turn is **not** taken on the calling thread. A
 * task runs for as long as the work takes and nobody is waiting for it; a
 * mutation that returned when the task was finished would be an HTTP request
 * held open for an hour.
 *
 * What it cannot do is survive the machine. A task in flight when the process
 * dies loses the turn it was on, and one whose model call was half-answered
 * loses that answer. Anything that has to survive anything should be on
 * Temporal, which is the same sentence the workflow engine's own note ends with.
 */
@Service
@ConditionalOnProperty(name = ["orknux.temporal.enabled"], havingValue = "false", matchIfMissing = true)
@EnableConfigurationProperties(TaskProperties::class)
class InlineTaskEngine(
    private val loop: TaskLoop,
    private val tasks: TaskRepository,
    private val properties: TaskProperties,
) : TaskEngine, SmartLifecycle {

    /**
     * How many tasks may be working at once.
     *
     * Small on purpose. Every turn is a model call, and an installation running
     * without Temporal is one machine; letting twenty tasks think at once would
     * spend the whole of its model allowance on whichever four were started
     * first anyway.
     */
    private val workers = ScheduledThreadPoolExecutor(THREADS, named("orknux-task")).apply {
        // A cancelled wake leaves the queue at once rather than sitting there until it was due. Issue #616.
        removeOnCancelPolicy = true
    }

    /**
     * The one wake each parked task has on the clock. Issue #616.
     *
     * Every park used to schedule a callback a week out and forget it, so a
     * task nudged twenty times while waiting left twenty callbacks queued for
     * next week, each holding its closure - and a busy installation never got
     * to next week before it ran out of memory. Now a task has at most one: a
     * new park replaces it, and picking the task up or the task being over
     * cancels it.
     */
    private val wakes = ConcurrentHashMap<Long, ScheduledFuture<*>>()

    /**
     * Which tasks are already in hand, so a second `begin` or a nudge that
     * arrives while a turn is running does not start a second loop over the same
     * row.
     */
    private val inHand = ConcurrentHashMap.newKeySet<Long>()

    private var running = false

    override fun begin(taskId: Long) = submit(taskId)

    override fun nudge(taskId: Long) = submit(taskId)

    /**
     * Takes a stranded task back, and says whether it was this call that did.
     *
     * [inHand] is the whole of the safety here, and it is why this can be
     * called from a clock that knows nothing about what the pool is doing. A
     * task is in that set from before it reaches a worker until after its last
     * turn - so a task waiting its turn behind four long ones is in it too, and
     * a sweep that meets it says no. Which matters more than it looks: four
     * threads and hour-long turns mean a fifth task legitimately sits at QUEUED
     * for an hour, and it is [inHand] rather than any interval that stops the
     * sweep starting it a second time.
     *
     * There is no transaction to wait for. This is called from a scheduled pass
     * that has already committed nothing, so it goes straight to a worker
     * rather than through [submit]'s deferral - the same reasoning [start] is
     * built on.
     */
    override fun recover(taskId: Long): Boolean {
        if (!running) return false
        return pickUp(taskId)
    }

    /**
     * Picks up whatever was in flight when this process last stopped.
     *
     * A task that was mid-turn is asked again from what it wrote down; a task
     * that was parked is put back on the clock, so its patience still runs out
     * even though the callback that would have noticed died with the process.
     *
     * Nothing is committing here, so the hand-over below fires straight
     * through: this is a lifecycle callback on the way up and there is no
     * transaction anywhere near it.
     */
    override fun start() {
        running = true
        val carried = tasks.inState(listOf(TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.WAITING))
        if (carried.isEmpty()) return
        log.info("Picking up {} task(s) left running", carried.size)
        carried.mapNotNull { it.id }.forEach(::submit)
    }

    override fun stop() {
        running = false
        workers.shutdownNow()
        wakes.clear()
    }

    /** How many callbacks are on the clock, for the test that a task holds one at most. Issue #616. */
    internal fun queued(): Int = workers.queue.size

    override fun isRunning(): Boolean = running

    /**
     * Hands the task over, once the row a worker will read is actually there.
     *
     * The wait for the commit is the whole of this method. A worker reads the
     * task by its id on a thread and a connection of its own, so it can only
     * see what has been committed - and both ways in here are called from
     * inside a transaction that has not. `TaskService.start` writes the row and
     * asks for the task to begin as its last act, so the worker read by id,
     * found nothing, and returned [TaskTurn.Over]: the task sat at QUEUED and
     * nothing looked at it again until the process restarted. `say`, `approve`,
     * `refuse` and `answer` are the same shape - the nudge went out before the
     * message or the decision it was announcing was readable, so the task read
     * the state it was already in and parked again.
     *
     * Not visible on Temporal, where `begin` hands the id to a server across
     * the network and the first turn is a worker polling for the workflow
     * afterwards - wide enough that the commit has always won. Which is why
     * this was every task an all-in-one installation started, and no task
     * anywhere else.
     *
     * Deferring also drops the hand-over when the transaction rolls back, which
     * is right: there is no row to work on, and nothing was asked for.
     */
    private fun submit(taskId: Long) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        pickUp(taskId)
                    }
                },
            )
        } else {
            pickUp(taskId)
        }
    }

    /**
     * Puts it on a worker, unless it is already on one.
     *
     * Answers whether it did, which is what [recover] passes on to the sweep.
     * A refusal from the pool answers false as well: the warning below was
     * where a task could be dropped for good, and now something looks again.
     */
    private fun pickUp(taskId: Long): Boolean {
        if (!inHand.add(taskId)) return false
        // Being worked on now; a turn that parks it again sets a fresh wake.
        wakes.remove(taskId)?.cancel(false)
        return try {
            workers.execute { work(taskId) }
            true
        } catch (refused: RuntimeException) {
            inHand.remove(taskId)
            log.warn("Task {} could not be picked up", taskId, refused)
            false
        }
    }

    /**
     * Turns until it stops, then lets go.
     *
     * A parked task is not slept on here. It is put back on the clock at its
     * patience deadline and otherwise waits to be nudged, which is what an
     * approval does - so an answered task carries on at once, and an unanswered
     * one costs one callback a week rather than one every thirty seconds. That
     * is the one place this differs from Temporal, which polls because nothing
     * can reach across processes to wake it.
     */
    private fun work(taskId: Long) {
        try {
            while (running) {
                when (loop.advance(taskId)) {
                    is TaskTurn.Working -> Unit
                    is TaskTurn.Over -> {
                        wakes.remove(taskId)?.cancel(false)
                        return
                    }
                    is TaskTurn.Parked -> {
                        val wake = workers.schedule({ submit(taskId) }, properties.patience.toSeconds(), TimeUnit.SECONDS)
                        wakes.put(taskId, wake)?.cancel(false)
                        return
                    }
                }
            }
        } catch (failure: Exception) {
            // Nothing above this catches, so a turn that threw something the
            // loop did not expect would otherwise disappear into an executor.
            log.error("Task {} stopped on an unexpected failure", taskId, failure)
        } finally {
            inHand.remove(taskId)
            /*
             * A nudge that arrived while this was letting go was dropped: the
             * task was still in hand when it came, and nothing was left to hear
             * it a moment later. Looking once more after letting go is what
             * closes that window, and it costs one query per task that stops.
             */
            if (running && tasks.findByIdOrNull(taskId)?.status == TaskStatus.RUNNING) submit(taskId)
        }
    }

    private fun named(prefix: String) = object : ThreadFactory {
        private val next = AtomicInteger(1)
        override fun newThread(runnable: Runnable): Thread =
            Thread(runnable, "$prefix-${next.getAndIncrement()}").apply { isDaemon = true }
    }

    private companion object {
        const val THREADS = 4

        val log = LoggerFactory.getLogger(InlineTaskEngine::class.java)
    }
}
