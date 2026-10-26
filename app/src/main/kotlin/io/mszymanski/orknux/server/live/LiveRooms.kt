package io.mszymanski.orknux.server.live

import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Something an editor shows was saved; published once the save has committed,
 * so nobody is told to read what is not there yet. [viewer] is the editor that
 * saved it, so it can tell its own save from somebody else's, and null for a
 * door that has no editor - an `orknux_*` tool, a chat with a model. Issue #683.
 */
data class ItemSaved(val kind: LiveKind, val id: Long, val revision: Long, val viewer: String?, val by: String)

/** What an editor is open on: the room is one per kind and id. */
enum class LiveKind(val path: String) {
    WORKFLOW("workflow"),
    DIAGRAM("diagram"),
    DOCUMENT("document"),
    WIREFRAME("wireframe"),
    ;

    companion object {
        fun of(path: String): LiveKind? = entries.firstOrNull { it.path == path }
    }
}

/**
 * Who has something open in an editor, and what reaches each of them while
 * they do: who else is there, where their pointers are, and that it has been
 * saved by somebody. Issue #645 for workflows, #683 for diagrams, documents
 * and wireframes, which is when it stopped being the workflow's own.
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
class LiveRooms {

    /** Which room: a kind of thing, and which one of them. */
    data class Key(val kind: LiveKind, val id: Long)

    /** One open editor, and what is waiting to be sent to it. */
    class Seat internal constructor(val key: Key, val viewer: String, val login: String) {

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

    /**
     * Where a viewer's pointer is, in the editor's own coordinates - a canvas's
     * for a drawing, the page's for a document; null x and y is off it.
     */
    data class PointerView(val viewer: String, val login: String, val x: Double?, val y: Double?)

    /** That it was saved: its revision now, by which editor (null for a tool) and whose login. */
    data class SavedView(val revision: Long, val viewer: String?, val login: String)

    private val rooms = ConcurrentHashMap<Key, CopyOnWriteArrayList<Seat>>()

    /** The editors whose first save has been written to the audit; see [firstSave]. */
    private val audited = ConcurrentHashMap.newKeySet<Pair<Key, String>>()

    /** Sits [viewer] in the room and tells everybody in it, the newcomer too, who is there. */
    fun join(key: Key, viewer: String, login: String): Seat {
        val seat = Seat(key, viewer, login)
        rooms.computeIfAbsent(key) { CopyOnWriteArrayList() }.add(seat)
        present(key)
        return seat
    }

    /** Ends the seat's stream; [leave] is what takes it out of the room. */
    fun close(seat: Seat) {
        seat.frames.offer(Frame.CLOSED)
    }

    fun leave(seat: Seat) {
        val room = rooms[seat.key] ?: return
        room.remove(seat)
        rooms.computeIfPresent(seat.key) { _, held -> held.takeIf { it.isNotEmpty() } }
        if (room.none { it.viewer == seat.viewer }) audited.remove(seat.key to seat.viewer)
        // Its pointer goes with it, or the others draw it where it was for ever.
        others(seat.key, seat.viewer).forEach { tellPointer(it, PointerView(seat.viewer, seat.login, null, null)) }
        present(seat.key)
    }

    /** Who is in the room, one row per open editor. */
    fun viewers(key: Key): List<ViewerView> =
        rooms[key].orEmpty().map { ViewerView(it.viewer, it.login) }.distinct()

    /**
     * Where [viewer]'s pointer is, to everybody else in the room.
     *
     * Only from a viewer that is sitting there under the same login: the
     * stream checked who it was when it opened, and this is how a pointer
     * moved forty times a second is not a database read forty times a second.
     * Anything else is dropped without a word.
     */
    fun pointer(key: Key, viewer: String, login: String, x: Double?, y: Double?) {
        val seat = rooms[key].orEmpty().firstOrNull { it.viewer == viewer } ?: return
        if (seat.login != login) return
        val at = PointerView(viewer, login, x, y)
        others(key, viewer).forEach { tellPointer(it, at) }
    }

    /**
     * Whether this is the first save [viewer] has made of it since it opened
     * it. What an editor saves as it is edited is saved every time something
     * moves, and one line in the audit per visit says who changed it without
     * burying everything else under a line per drag.
     */
    fun firstSave(key: Key, viewer: String): Boolean = audited.add(key to viewer)

    /**
     * Tells everybody in the room it was saved, under [event]: `graph` for a
     * workflow, whose editor was written before the others and reads that
     * name, `saved` for the rest.
     */
    fun saved(key: Key, revision: Long, viewer: String?, by: String, event: String = SAVED) {
        val moved = Frame(event, SavedView(revision, viewer, by))
        rooms[key].orEmpty().forEach { it.frames.offer(moved) }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun saved(event: ItemSaved) = saved(Key(event.kind, event.id), event.revision, event.viewer, event.by)

    private fun others(key: Key, viewer: String) = rooms[key].orEmpty().filter { it.viewer != viewer }

    private fun tellPointer(seat: Seat, at: PointerView) {
        seat.pointers[at.viewer] = at
        if (seat.pointerWaiting.compareAndSet(false, true)) seat.frames.offer(Frame.POINTERS)
    }

    private fun present(key: Key) {
        val here = Frame("presence", mapOf("viewers" to viewers(key)))
        rooms[key].orEmpty().forEach { it.frames.offer(here) }
    }

    companion object {
        /** The frame a save is announced under, outside a workflow. */
        const val SAVED = "saved"

        /** What an editor names itself: an id it made up, which goes back out to everybody in the room. */
        val VIEWER = Regex("[A-Za-z0-9-]{1,64}")
    }
}
