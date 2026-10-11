package io.mszymanski.orknux.server.box

import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * What `orkbox_undo` and `orkbox_redo` take back and put again: per box
 * session, the files `orkbox_edit` and `orkbox_write` changed, each with what
 * it held before and after.
 *
 * In memory only, and on purpose. A history is worth having while an agent is
 * at work on a file, and a restart ends that work; written to the database it
 * would be a copy of every file an agent touched, kept for nobody. So it is
 * bounded three ways: by the number of steps Admin Settings allows per session
 * ([BoxSettings.editUndoSteps]), by leaving out any file larger than an agent
 * is handed of a command's output ([BoxSettings.outputBytes]), and by dropping
 * a session's history when it is closed - or, for one nobody saw close, once it
 * has been left alone longer than a local box session may live at all.
 */
@Component
class BoxEditHistory {

    /**
     * One file change. [before] null is a file that did not exist; the modes
     * are what the file had, so putting it back does not make a script
     * unrunnable.
     */
    class Change(
        val path: String,
        val before: ByteArray?,
        val beforeMode: String?,
        val after: ByteArray,
        val afterMode: String?,
    )

    /** One session's two stacks; the newest change is last in each. */
    class Stacks {
        val undo = ArrayDeque<Change>()
        val redo = ArrayDeque<Change>()

        @Volatile
        var usedAt: Instant = Instant.now()
    }

    /**
     * By the row and orkbox's own id together: a row id alone can come round
     * again on a database that reuses deleted keys, and would hand a new
     * session an old one's history.
     */
    private val held = ConcurrentHashMap<String, Stacks>()

    /**
     * Keeps [change] as the newest step, at most [keep] of them, and clears
     * what could be redone: after a new change, a redo would put back a state
     * the agent has moved on from. [keep] 0 keeps nothing.
     */
    fun record(session: BoxSession, change: Change, keep: Int, staleAfter: Duration) {
        prune(staleAfter)
        if (keep <= 0) {
            held.remove(keyOf(session))
            return
        }
        val stacks = held.computeIfAbsent(keyOf(session)) { Stacks() }
        synchronized(stacks) {
            stacks.undo.addLast(change)
            while (stacks.undo.size > keep) stacks.undo.removeFirst()
            stacks.redo.clear()
            stacks.usedAt = Instant.now()
        }
    }

    /** A change that was made but not kept still ends what could be redone. */
    fun changedUnrecorded(session: BoxSession) {
        held[keyOf(session)]?.let { stacks -> synchronized(stacks) { stacks.redo.clear() } }
    }

    /** Runs [work] with the session's stacks held, so two undos cannot interleave. */
    fun <T> using(session: BoxSession, work: (Stacks) -> T): T {
        val stacks = held.computeIfAbsent(keyOf(session)) { Stacks() }
        return synchronized(stacks) {
            stacks.usedAt = Instant.now()
            work(stacks)
        }
    }

    /** The session has closed; nothing in it can be undone any more. */
    fun forget(session: BoxSession) {
        held.remove(keyOf(session))
    }

    /** How many steps a session can undo and redo; for tests and for the answer. */
    fun depth(session: BoxSession): Pair<Int, Int> =
        held[keyOf(session)]?.let { synchronized(it) { it.undo.size to it.redo.size } } ?: (0 to 0)

    private fun keyOf(session: BoxSession) = "${session.id}/${session.remoteSessionId}"

    private fun prune(staleAfter: Duration) {
        val cutoff = Instant.now().minus(staleAfter)
        held.entries.removeIf { it.value.usedAt.isBefore(cutoff) }
    }
}
