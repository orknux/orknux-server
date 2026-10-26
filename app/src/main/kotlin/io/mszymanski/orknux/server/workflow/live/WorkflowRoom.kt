package io.mszymanski.orknux.server.workflow.live

import io.mszymanski.orknux.server.live.LiveKind
import io.mszymanski.orknux.server.live.LiveRooms
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * A workflow's draft graph was saved; published once the save has committed,
 * so nobody is told to read a graph that is not there yet. [viewer] is the
 * editor that saved it, so it can tell its own save from somebody else's, and
 * null for a door that has no editor - an `orknux_*` tool. Issue #645.
 */
data class WorkflowGraphSaved(val workflowId: Long, val revision: Long, val viewer: String?, val by: String)

/**
 * A workflow's room in [LiveRooms]: who has it open in the editor, where their
 * pointers are, and that the graph has been saved. Issue #645.
 *
 * The room itself is shared with the diagram, document and wireframe editors
 * since #683; this keeps the workflow's own names for it, and announces a save
 * as `graph`, which is what the workflow editor reads.
 */
@Component
class WorkflowRoom(private val rooms: LiveRooms) {

    fun join(workflowId: Long, viewer: String, login: String): LiveRooms.Seat = rooms.join(key(workflowId), viewer, login)

    fun close(seat: LiveRooms.Seat) = rooms.close(seat)

    fun leave(seat: LiveRooms.Seat) = rooms.leave(seat)

    fun viewers(workflowId: Long): List<LiveRooms.ViewerView> = rooms.viewers(key(workflowId))

    fun pointer(workflowId: Long, viewer: String, login: String, x: Double?, y: Double?) =
        rooms.pointer(key(workflowId), viewer, login, x, y)

    /** See [LiveRooms.firstSave]: one audit line per visit to the editor. */
    fun firstSave(workflowId: Long, viewer: String): Boolean = rooms.firstSave(key(workflowId), viewer)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun saved(event: WorkflowGraphSaved) =
        rooms.saved(key(event.workflowId), event.revision, event.viewer, event.by, event = "graph")

    private fun key(workflowId: Long) = LiveRooms.Key(LiveKind.WORKFLOW, workflowId)
}
