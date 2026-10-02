package io.mszymanski.orknux.workflow.execution

import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * The steps being carried out right now, so one can be cut short from outside
 * the thread that is carrying it. Issue #440.
 *
 * **Why the flag was not enough.** A stop request sets [WorkflowExecution.stopRequested],
 * and the engine reads it between steps and during a wait. A step in the middle
 * of a model call reads nothing: the thread is blocked on a socket for as long
 * as the model takes to answer, and the run only noticed the stop once the
 * answer had arrived, been recorded, and the next step was about to start.
 * Pressing Stop on a two-minute call meant two minutes of run and two minutes
 * of tokens for an answer nobody wanted. So a running step is registered here
 * while it runs, and a stop reaches in.
 *
 * **Two ways in, because there are two kinds of blocking.** The thread is
 * interrupted, which wakes every sleep on the way - the throttle's, a
 * Retry-After's - and every interruptible wait, the JDK's own HTTP client
 * among them. And anything that knows a better way to end what it is doing
 * hands it over through [Handle.holding]: a model call hands over its hangup,
 * because a socket read does not wake on an interrupt and closing the stream
 * is what ends it; a script hands over its context, which is cancelled from
 * outside exactly as the watchdog cancels an overrun. A hook that arrives
 * after the stop has already landed runs at once, so a call opened a moment
 * too late is not left running.
 *
 * **The thread is left as it was found.** An interrupt is a flag on the thread,
 * and a thread that carries on with it set fails its next blocking call - which
 * for the step's runner would be the database write that records what
 * happened. So [stoppable] clears it on the way out, under the same lock the
 * stop takes, and after that no interrupt from here can reach the thread.
 *
 * **It knows only this process.** A run carried by a Temporal worker in another
 * JVM is not here to be reached, and such a step finishes on its own as it did
 * before; the inline engine, and a Temporal worker sharing the server's JVM,
 * are what this is for.
 */
@Component
class StepInterrupts {

    /**
     * One step's work, as the thing that may cut it short sees it.
     *
     * Created by [stoppable] for the thread carrying the step, and reached by
     * whoever is stopping the run through [stop]. Everything on it is behind
     * one lock, because the two threads are never the same one.
     */
    class Handle internal constructor(val executionId: Long, val nodeKey: String) {

        private val lock = Any()
        private var carrier: Thread? = Thread.currentThread()
        private var stopped = false
        private val hooks = ArrayList<() -> Unit>()

        /** Whether the run this step belongs to has been asked to stop while the step was running. */
        val wasStopped: Boolean
            get() = synchronized(lock) { stopped }

        /**
         * Runs [body] with [letGo] on hand as the way to end it early.
         *
         * The runner that knows how its own blocking call is torn down hands the
         * tearing down over here, for as long as the call lasts: a stop that
         * lands during [body] runs [letGo] and then interrupts the thread; one
         * that landed before [body] began runs it straight away, so a call that
         * opened a moment too late is ended too. Forgotten when [body] returns,
         * however it returns, so a step that makes many calls holds only the
         * one in flight.
         */
        fun <T> holding(letGo: () -> Unit, body: () -> T): T {
            val already = synchronized(lock) {
                if (!stopped) hooks += letGo
                stopped
            }
            if (already) runCatching(letGo)
            try {
                return body()
            } finally {
                synchronized(lock) { hooks.remove(letGo) }
            }
        }

        /** Ends the work: every hook first, and then the thread's own interrupt. */
        internal fun stop() {
            val (held, thread) = synchronized(lock) {
                if (stopped) return
                stopped = true
                val taken = hooks.toList()
                hooks.clear()
                taken to carrier
            }
            held.forEach { runCatching(it) }
            thread?.interrupt()
        }

        /** The step is over; nothing here may reach the thread again. */
        internal fun done() {
            synchronized(lock) {
                carrier = null
                hooks.clear()
            }
        }
    }

    /**
     * Every step of a run in flight on this process. More than one where the
     * run fans out (#285), and a stop has to reach each of them.
     */
    private val running = ConcurrentHashMap<Long, Set<Handle>>()

    /**
     * Carries out [body] as the work of one step, cut short if the run is
     * asked to stop while it runs.
     *
     * The step is on the register for exactly as long as [body] runs, and the
     * handle is on the carrying thread for the same time, where [current] finds
     * it for whatever deep inside the runner wants to hand over a hook.
     *
     * @throws StepInterruptedException in place of whatever [body] threw, when
     *   the run was asked to stop while it ran. What the body threw is the
     *   torn socket, the cancelled context, the interrupted sleep - a symptom,
     *   and the caller needs the cause. A body that returns anyway, its work
     *   done before the stop reached it, returns: what it did is a fact, and
     *   the engine ends the run before the next step as it always has.
     */
    fun <T> stoppable(executionId: Long, nodeKey: String, body: () -> T): T {
        val handle = Handle(executionId, nodeKey)
        val outer = current.get()
        running.compute(executionId) { _, held -> held.orEmpty() + handle }
        current.set(handle)
        try {
            return body()
        } catch (failure: Exception) {
            if (handle.wasStopped) throw StepInterruptedException(nodeKey, failure)
            throw failure
        } finally {
            handle.done()
            running.computeIfPresent(executionId) { _, held -> (held - handle).ifEmpty { null } }
            if (outer == null) current.remove() else current.set(outer)
            // A stop that landed leaves the interrupt on the thread, and a thread
            // that carries on with it set fails its next blocking call - which
            // is the write recording what happened to this step. Cleared here,
            // after done(), so nothing can set it again.
            Thread.interrupted()
        }
    }

    /**
     * Whether a step of this run is in flight on a thread of this process.
     * Issue #448.
     *
     * Read by the stranded-run sweep, which must never hand a run to a second
     * thread. The record cannot answer this on its own: a step in the middle of
     * a model call has stamped nothing for minutes, and looks from the outside
     * exactly like one whose worker died. The register can, because a step is
     * on it for precisely as long as its runner is running, and whatever is
     * carrying it puts it there - the inline engine walking a run, or a Temporal
     * activity sharing this JVM.
     *
     * What it does not know is the gaps: between two steps, and during a wait,
     * nothing is registered here, and a live run spends a good deal of its time
     * there. So this is one of the two things the sweep asks; see
     * [ParkedRunSweeper] for the other and for why neither alone is enough.
     */
    fun isCarrying(executionId: Long): Boolean = running.containsKey(executionId)

    /**
     * Cuts short whatever steps of this run are in flight, if any are.
     *
     * Only ends the work: the thread carrying the step records what happened
     * and ends the run itself. Nothing here waits for it - the caller is a
     * button press, not the run. A run between steps, or waiting, has nothing
     * here to cut, and the flag the engine already reads is what stops it.
     */
    fun stop(executionId: Long) {
        running[executionId]?.forEach { it.stop() }
    }

    companion object {

        private val current = ThreadLocal<Handle?>()

        /**
         * The step this thread is carrying, or null off any step.
         *
         * Static because the code that wants it is deep inside a runner - a
         * script's guard, an agent's model call - with no bean in reach and
         * six layers of parameters between it and the engine. A thread that is
         * not carrying a step is a chat, a probe, a test run from an editor,
         * and gets null: nothing it does is anybody's to stop from here.
         */
        fun current(): Handle? = current.get()
    }
}

/**
 * Raised in place of whatever a step's runner threw, when it threw because the
 * run was asked to stop. Issue #440.
 *
 * The symptom is kept as the cause; what the caller needs is that this was a
 * stop, so the step is recorded as stopped rather than failed and no retry
 * policy spends an attempt on it.
 */
class StepInterruptedException(val nodeKey: String, cause: Exception) :
    RuntimeException("$nodeKey was cut short: its run was asked to stop", cause)
