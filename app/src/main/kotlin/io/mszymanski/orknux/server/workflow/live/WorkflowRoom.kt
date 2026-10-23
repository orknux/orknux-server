package io.mszymanski.orknux.server.workflow.live

import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A workflow's draft graph was saved; published once the save has committed,
 * so nobody is told to read a graph that is not there yet. [viewer] is the
 * editor that saved it, so it can tell its own save from somebody else's, and
 * null for a door that has no editor - an `orknux_*` tool. Issue #645.
 */
data class WorkflowGraphSaved(val workflowId: Long, val revision: Long, val viewer: String?, val by: String)

/**
 * Who has a workflow open in the editor, and what reaches each of them while
 * they do: who else is there, where their pointers are, and that the graph
 * has been saved by somebody. Issue #645.
 *
 * A viewer is one open editor - one tab - named by an id the page makes up
 * when it opens, so the same person in two tabs is two viewers with one
 * login, and each sees the other's pointer.
 *
 * **Held in this process's memory.** Nothing here outlives a restart or is
 * worth keeping: an editor reconnects and says it is there again. It does mean
 * two servers behind one address would each hold half the room; Orknux runs as
 * one server, and a second would need this carried through the database or a
 * broker first.
 */
@Component
class WorkflowRoom {

    /** One open editor, and what is waiting to be sent to it. */
    class Seat internal constructor(val workflowId: Long, val viewer: String, val login: String) {

        internal val frames = LinkedBlockingQueue<Frame>()

        /**
         * The newest place of each other viewer's pointer, by viewer.
         *
         * Kept as the latest rather than queued: a pointer moved a hundred
         * times while this reader was slow is one place to draw, not a hundred
         * to replay, so a slow reader costs a map entry per viewer and nothing
         * that grows.
         */
        internal val pointers = ConcurrentHashMap<String, PointerView>()
        internal val pointerWaiting = AtomicBoolean(false)

        /** Whether anything is waiting to be sent, so [next] would not wait. */
        fun waiting(): Boolean = frames.isNotEmpty()

        /**
         * What to send next, waiting for it; null once the seat is closed.
         * Pointers come out as one frame each, newest place only.
         */
        fun next(): List<Frame>? {
            val frame = frames.take()
            if (frame === Frame.CLOSED) return null
            if (frame !== Frame.POINTERS) return listOf(frame)
            pointerWaiting.set(false)
            return pointers.keys.mapNotNull { key -> pointers.remove(key)?.let { Frame("pointer", it) } }
        }
    }

    class Frame(val event: String, val payload: Any) {
        internal companion object {
            val CLOSED = Frame("", Unit)
            val POINTERS = Frame("", Unit)
        }
    }

    /** One viewer as the others are shown it. */
    data class ViewerView(val viewer: String, val login: String)

    /** Where a viewer's pointer is on the canvas, in its coordinates; null x and y is off it. */
    data class PointerView(val viewer: String, val login: String, val x: Double?, val y: Double?)

    data class GraphMovedView(val revision: Long, val viewer: String?, val login: String)

    private val rooms = ConcurrentHashMap<Long, CopyOnWriteArrayList<Seat>>()

    /** The editors whose first save has been written to the audit; see [firstSave]. */
    private val audited = ConcurrentHashMap.newKeySet<Pair<Long, String>>()

    /** Sits [viewer] in the workflow's room and tells everybody in it, the newcomer too, who is there. */
    fun join(workflowId: Long, viewer: String, login: String): Seat {
        val seat = Seat(workflowId, viewer, login)
        rooms.computeIfAbsent(workflowId) { CopyOnWriteArrayList() }.add(seat)
        present(workflowId)
        return seat
    }

    /** Ends the seat's stream; [leave] is what takes it out of the room. */
    fun close(seat: Seat) {
        seat.frames.offer(Frame.CLOSED)
    }

    fun leave(seat: Seat) {
        val room = rooms[seat.workflowId] ?: return
        room.remove(seat)
        rooms.computeIfPresent(seat.workflowId) { _, held -> held.takeIf { it.isNotEmpty() } }
        if (room.none { it.viewer == seat.viewer }) audited.remove(seat.workflowId to seat.viewer)
        // Its pointer goes with it, or the others draw it where it was for ever.
        others(seat.workflowId, seat.viewer).forEach { tellPointer(it, PointerView(seat.viewer, seat.login, null, null)) }
        present(seat.workflowId)
    }

    /** Who is in the room, one row per open editor. */
    fun viewers(workflowId: Long): List<ViewerView> =
        rooms[workflowId].orEmpty().map { ViewerView(it.viewer, it.login) }.distinct()

    /**
     * Where [viewer]'s pointer is, to everybody else in the room.
     *
     * Only from a viewer that is sitting there under the same login: the
     * stream checked who it was when it opened, and this is how a pointer
     * moved forty times a second is not a database read forty times a second.
     * Anything else is dropped without a word.
     */
    fun pointer(workflowId: Long, viewer: String, login: String, x: Double?, y: Double?) {
        val seat = rooms[workflowId].orEmpty().firstOrNull { it.viewer == viewer } ?: return
        if (seat.login != login) return
        val at = PointerView(viewer, login, x, y)
        others(workflowId, viewer).forEach { tellPointer(it, at) }
    }

    /**
     * Whether this is the first save [viewer] has made of the workflow since
     * it opened it. A graph saved as it is edited is saved every time a node
     * moves, and one line in the audit per visit says who changed it without
     * burying everything else under a line per drag.
     */
    fun firstSave(workflowId: Long, viewer: String): Boolean = audited.add(workflowId to viewer)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun saved(event: WorkflowGraphSaved) {
        val moved = Frame("graph", GraphMovedView(event.revision, event.viewer, event.by))
        rooms[event.workflowId].orEmpty().forEach { it.frames.offer(moved) }
    }

    private fun others(workflowId: Long, viewer: String) = rooms[workflowId].orEmpty().filter { it.viewer != viewer }

    private fun tellPointer(seat: Seat, at: PointerView) {
        seat.pointers[at.viewer] = at
        if (seat.pointerWaiting.compareAndSet(false, true)) seat.frames.offer(Frame.POINTERS)
    }

    private fun present(workflowId: Long) {
        val here = Frame("presence", mapOf("viewers" to viewers(workflowId)))
        rooms[workflowId].orEmpty().forEach { it.frames.offer(here) }
    }
}
