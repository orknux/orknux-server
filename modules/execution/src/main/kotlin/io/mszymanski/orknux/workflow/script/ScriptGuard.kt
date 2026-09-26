package io.mszymanski.orknux.workflow.script

import io.mszymanski.orknux.workflow.execution.StepInterrupts
import org.graalvm.polyglot.Context
import java.lang.management.ManagementFactory
import java.lang.management.MemoryPoolMXBean
import java.lang.management.MemoryType
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The bounds a sandbox cannot enforce on itself.
 *
 * The statement limit and the language's own refusals live inside the guest and
 * are the engine's business. These are the host's, because they are about what a
 * run costs *this* process rather than what the script is allowed to say: how
 * long it may hold a thread, how much of the heap it may take with it, and how
 * many runs may be doing either at once.
 *
 * **Why the heap needs a bound of its own.** A GraalJS context on the community
 * distribution shares the server's heap; there is no guest heap for a script to
 * run out of separately. `sandbox.MaxHeapMemory` is the option that would give it
 * one, and it is not available here — it needs `truffle-enterprise`, published
 * under Oracle's terms rather than a licence this project can carry.
 *
 * **Why it is not measured by counting allocations.** The obvious substitute is
 * the JVM's per-thread allocation counter, and it does not work. Community
 * GraalJS runs in the interpreter, where ordinary arithmetic boxes: a perfectly
 * well-behaved loop that keeps nothing at all allocates something like a
 * gigabyte a second, all of it garbage, all of it collected as fast as it is
 * made. There is no number that separates that from an attack, because the
 * attack allocates *less*. Cumulative allocation measures how hard a script is
 * working, not how much of the heap it is holding.
 *
 * **What is measured instead.** How full the heap is *after the last
 * collection* — which is live data by definition, since garbage does not survive
 * a collection. A script that churns and keeps nothing never moves that number;
 * a script that holds what it allocates moves nothing else.
 *
 * That reading is about the process, though, not about any one script, so on its
 * own it would stop whichever script happened to be running when an unrelated
 * part of the server filled the heap. So the allocation counter comes back, in
 * the job it is actually good at: not deciding *whether* there is a problem, but
 * deciding *who* is a plausible cause of it. A run is stopped when the heap is in
 * trouble **and** that run has done enough allocating to be a suspect. A small
 * script running innocently beside a memory leak somewhere else is left alone.
 *
 * **Why the count matters as much as the bound.** One script held to a share of
 * the heap is contained; forty of them at once is the whole heap again, arrived
 * at politely. The permit count is what turns a bound on a run into a bound on
 * the installation.
 */
internal data class Bounds(
    /** How long one run may hold its thread. */
    val timeoutMillis: Long,

    /**
     * How full the heap may be, after a collection, before runs start being
     * stopped. A percentage of the largest heap pool the JVM reports.
     */
    val heapPressurePercent: Int,

    /**
     * How much a run must have allocated before the heap being in trouble is
     * held against it. Not a limit — a run may allocate any amount while there
     * is room — but the line between a bystander and a suspect.
     */
    val suspectAfterBytes: Long,

    /** How many runs may be in a sandbox at once. */
    val concurrency: Int,

    /** How long a run waits for its turn before it is told the server is full. */
    val queueMillis: Long,
)

/**
 * Which of the host's bounds a run ran into - or, [STOPPED], that it ran into
 * none and was cancelled because the workflow run it belongs to was asked to
 * stop. Issue #440. Carried here because the cancelled context says only that
 * somebody stopped it, and the sentence for a stop must not be the timeout's.
 */
internal enum class Overrun { TIME, MEMORY, STOPPED }

/** There was no permit to be had in time. Not the script's fault, and worth retrying. */
internal class ScriptBusyException(message: String) : RuntimeException(message)

/**
 * Runs a body inside the bounds, and stops it from outside when it leaves them.
 *
 * Shared by both sandboxes, and sharing it is safe in a way sharing their
 * configuration would not be: everything here takes capability away. There is no
 * branch in this class that could hand a workspace's function something only a
 * plugin was meant to have — it holds no capabilities to hand out.
 *
 * The script stays on the calling thread. Moving it to a pool of its own would
 * buy nothing the sampler does not already have — both of the numbers it reads
 * are readable for any thread, by id — and would cost a hand-off on every call
 * plus a second place a run can be orphaned.
 */
internal class ScriptGuard(name: String, private val bounds: Bounds) {

    private val permits = Semaphore(bounds.concurrency)

    private val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "$name-watchdog").apply { isDaemon = true }
    }

    /**
     * Builds a context, runs [body] in it, and cancels it if it overruns.
     *
     * @param stopped written with which bound was hit, if one was. The
     *   `PolyglotException` that comes back says only that the run was
     *   cancelled, never why — and "took too long" and "was taking the heap" are
     *   different sentences to whoever has to fix the script.
     * @param timeoutMillis how long this one run may hold its thread, where the
     *   caller knows better than the installation's number — a tool or a
     *   function with a timeout of its own, or a workspace with a default. Null
     *   means the installation's bound, which is what every run had before.
     */
    fun <T> bounded(
        stopped: AtomicReference<Overrun?>,
        newContext: () -> Context,
        timeoutMillis: Long? = null,
        body: (Context) -> T,
    ): T {
        acquire()
        try {
            return newContext().use { polyglot ->
                val watch = Watch(stopped, timeoutMillis ?: bounds.timeoutMillis)
                /*
                 * Polled rather than scheduled once, because there are now two
                 * ways to overrun and only one of them is a point in time. Both
                 * readings are counters the JVM already keeps, so a sample is a
                 * field read and this costs nothing to do often.
                 */
                val sampler = watchdog.scheduleAtFixedRate(
                    { if (watch.overrun()) runCatching { polyglot.close(true) } },
                    SAMPLE_MILLIS,
                    SAMPLE_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
                try {
                    /*
                     * And cancelled the same way, from outside, if the workflow
                     * run this script belongs to is asked to stop while it is
                     * running. A script blocks nothing the interrupt could wake
                     * - it is computing, or waiting on the host - and the one
                     * thing that ends it is the context being closed, which is
                     * exactly what the watchdog does for an overrun. Off any
                     * workflow step - the editor's Run, a chat's tool - there is
                     * nothing to register with and the body simply runs. #440.
                     */
                    val interrupt = StepInterrupts.current()
                    if (interrupt == null) {
                        body(polyglot)
                    } else {
                        interrupt.holding({
                            stopped.compareAndSet(null, Overrun.STOPPED)
                            runCatching { polyglot.close(true) }
                        }) { body(polyglot) }
                    }
                } finally {
                    sampler.cancel(false)
                }
            }
        } finally {
            permits.release()
        }
    }

    /** How the overrun should be described to whoever has to act on it. */
    fun overrunReason(stopped: Overrun?, timeoutMillis: Long? = null): String? = when (stopped) {
        Overrun.MEMORY -> "was taking more of the heap than the server could spare"
        Overrun.TIME -> "took longer than ${timeoutMillis ?: bounds.timeoutMillis} ms"
        Overrun.STOPPED -> "was cut short because its run was asked to stop"
        null -> null
    }

    private fun acquire() {
        val got = try {
            permits.tryAcquire(bounds.queueMillis, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ScriptBusyException("was interrupted while waiting for its turn to run")
        }
        if (!got) {
            throw ScriptBusyException(
                "waited ${bounds.queueMillis} ms and the server was already running " +
                    "the ${bounds.concurrency} scripts it allows at once",
            )
        }
    }

    /**
     * What the sampler asks, from the watchdog thread, about the run in progress.
     *
     * Constructed on the calling thread, so the thread id and the starting
     * allocation count are the script's own.
     */
    private inner class Watch(private val stopped: AtomicReference<Overrun?>, timeoutMillis: Long) {

        private val thread = Thread.currentThread().threadId()
        private val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        private val allocatedAtStart = allocated(thread)

        fun overrun(): Boolean {
            if (stopped.get() != null) return true
            if (System.nanoTime() >= deadline) return stop(Overrun.TIME)
            if (underPressure() && isSuspect()) return stop(Overrun.MEMORY)
            return false
        }

        /** Has this run allocated enough for the heap's trouble to be its doing? */
        private fun isSuspect(): Boolean {
            // -1 is "this JVM will not say". Without attribution the only honest
            // choice is to stop whoever is running, since the alternative is the
            // process dying on some other thread.
            if (allocatedAtStart < 0) return true
            val now = allocated(thread)
            if (now < 0) return true
            return now - allocatedAtStart >= bounds.suspectAfterBytes
        }

        private fun stop(overrun: Overrun): Boolean {
            stopped.set(overrun)
            return true
        }
    }

    /**
     * How full the heap was after the last collection, against what it may be.
     *
     * Read off the largest heap pool the JVM reports, which is the tenured one on
     * every collector that has more than one — the only pool whose maximum is the
     * whole heap, and the only one whose post-collection occupancy is the live
     * set rather than a nursery's high-water mark.
     */
    private fun underPressure(): Boolean {
        val pool = tenured ?: return false
        val after = pool.collectionUsage ?: return false
        val max = if (after.max > 0) after.max else return false
        return after.used * 100 >= max * bounds.heapPressurePercent
    }

    private companion object {

        const val SAMPLE_MILLIS = 20L

        /**
         * The heap pool with a real maximum, held once: the list does not change
         * over the life of a JVM, and the lookup is not free.
         */
        val tenured: MemoryPoolMXBean? = runCatching {
            ManagementFactory.getMemoryPoolMXBeans()
                .filter { it.type == MemoryType.HEAP && it.isValid }
                .filter { it.usage?.max ?: -1 > 0 }
                .maxByOrNull { it.usage.max }
        }.getOrNull()

        /**
         * The JVM's own count of what a thread has allocated since it started.
         *
         * Standard on HotSpot and on by default; a JVM that will not say answers
         * -1, and the run is treated as a suspect rather than being excused.
         */
        val threads: com.sun.management.ThreadMXBean? =
            ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean

        fun allocated(thread: Long): Long {
            val bean = threads ?: return -1
            if (!bean.isThreadAllocatedMemorySupported || !bean.isThreadAllocatedMemoryEnabled) return -1
            return runCatching { bean.getThreadAllocatedBytes(thread) }.getOrDefault(-1)
        }
    }
}
