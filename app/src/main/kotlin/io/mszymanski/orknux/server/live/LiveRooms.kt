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
    SHEET("sheet"),
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
class LiveRooms(
    /** How long a viewer stays listed as away; see [away]. */
    private val tuning: io.mszymanski.orknux.server.tuning.Tuning,
) {

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

    /**
     * One viewer as the others are shown it. [active] false is a tab that is
     * open and not being looked at, since [awaySince] (ISO-8601).
     */
    data class ViewerView(val viewer: String, val login: String, val active: Boolean = true, val awaySince: String? = null)

    /** A viewer whose tab went into the background: no stream held, still in the room. */
    private data class Away(val login: String, val since: java.time.OffsetDateTime)

    /**
     * Where a viewer's pointer is, in the editor's own coordinates - a canvas's
     * for a drawing, the page's for a document; null x and y is off it.
     */
    data class PointerView(val viewer: String, val login: String, val x: Double?, val y: Double?)

    /** That it was saved: its revision now, by which editor (null for a tool) and whose login. */
    data class SavedView(val revision: Long, val viewer: String?, val login: String)

    private val rooms = ConcurrentHashMap<Key, CopyOnWriteArrayList<Seat>>()

    /**
     * Who is in a room with their tab in the background, by viewer.
     *
     * A tab out of sight lets go of its stream. A browser keeps six
     * connections to one host over plain HTTP, every open editor held one, and
     * past six tabs every request queued in the browser: the server was idle
     * and Orknux read as frozen. Gone from the room, though, a person who had
     * only switched tabs vanished from everybody else's editor - so they stay,
     * marked inactive, until the tab is looked at again, closed, or has been
     * away longer than an installation lists anybody.
     */
    private val away = ConcurrentHashMap<Key, ConcurrentHashMap<String, Away>>()

    /** The editors whose first save has been written to the audit; see [firstSave]. */
    private val audited = ConcurrentHashMap.newKeySet<Pair<Key, String>>()

    /** Sits [viewer] in the room and tells everybody in it, the newcomer too, who is there. */
    fun join(key: Key, viewer: String, login: String): Seat {
        val seat = Seat(key, viewer, login)
        // Back: active again, and no longer listed as away.
        away[key]?.remove(viewer)
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

    /**
     * [viewer]'s tab went into the background: it is listed as away, and the
     * stream it is about to close takes it out of the room without taking it
     * off the list. Only from a viewer sitting there under the same login, as
     * a pointer is.
     *
     * Its seat is let go of here rather than when the stream is seen to end:
     * that is noticed only at the next thing written to it, and a seated tab
     * outranks an away one, so a tab out of sight stayed listed as looking at
     * the diagram until somebody happened to move.
     */
    fun away(key: Key, viewer: String, login: String) {
        val seats = rooms[key].orEmpty().filter { it.viewer == viewer }
        if (seats.isEmpty() || seats.any { it.login != login }) return
        forgetLongAway()
        away.computeIfAbsent(key) { ConcurrentHashMap() }[viewer] = Away(login, java.time.OffsetDateTime.now())
        seats.forEach(::close)
        present(key)
    }

    /** When somebody went away longer ago than this, they are no longer listed. */
    private fun awayCutoff(): java.time.OffsetDateTime =
        java.time.OffsetDateTime.now().minusHours(tuning[io.mszymanski.orknux.server.tuning.Tunable.LIVE_AWAY_HOURS].toLong())

    /**
     * Drops everybody away past the cutoff, in every room, and the rooms left
     * with nobody. Read only when a room is drawn, an editor nobody opened
     * again kept its away list for as long as the server ran - a closed tab's
     * beacon is not promised to arrive. Done whenever somebody goes away,
     * which is often enough and costs a look at a handful of maps.
     */
    private fun forgetLongAway() {
        val cutoff = awayCutoff()
        away.keys.forEach { key ->
            away.computeIfPresent(key) { _, held ->
                held.entries.removeIf { it.value.since.isBefore(cutoff) }
                held.takeIf { it.isNotEmpty() }
            }
        }
    }

    /**
     * [viewer]'s tab was closed: off the list at once, rather than away for
     * hours. A tab still sitting in the room is let go of too: its stream's end
     * is only noticed at the next thing written to it, and until then a closed
     * or reloaded tab stayed among the avatars as a second copy of somebody.
     */
    fun gone(key: Key, viewer: String, login: String) {
        rooms[key].orEmpty().filter { it.viewer == viewer && it.login == login }.forEach(::close)
        val held = away[key] ?: return
        if (held[viewer]?.login != login) return
        held.remove(viewer)
        away.computeIfPresent(key) { _, left -> left.takeIf { it.isNotEmpty() } }
        present(key)
    }

    /** Who is in the room, one row per open editor: those looking at it, then those away. */
    fun viewers(key: Key): List<ViewerView> {
        val seated = rooms[key].orEmpty().map { ViewerView(it.viewer, it.login) }.distinct()
        val cutoff = awayCutoff()
        val held = away[key] ?: return seated
        held.entries.removeIf { it.value.since.isBefore(cutoff) }
        away.computeIfPresent(key) { _, left -> left.takeIf { it.isNotEmpty() } }
        val here = seated.map { it.viewer }.toSet()
        return seated + held.filterKeys { it !in here }.map { (viewer, gone) ->
            ViewerView(viewer, gone.login, active = false, awaySince = gone.since.toString())
        }
    }

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
