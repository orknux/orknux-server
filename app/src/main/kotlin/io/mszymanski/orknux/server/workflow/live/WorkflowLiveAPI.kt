package io.mszymanski.orknux.server.workflow.live

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.stream.ReaderWatch
import io.mszymanski.orknux.server.stream.ServerSentEvents
import io.mszymanski.orknux.server.stream.Untimed
import io.mszymanski.orknux.server.workflow.WorkflowNotFoundException
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import tools.jackson.databind.ObjectMapper

/** Where a viewer's pointer is on the canvas; both null when it has left it. */
data class PointerRequest @JsonCreator constructor(
    @JsonProperty("viewer") val viewer: String,
    @JsonProperty("x") val x: Double? = null,
    @JsonProperty("y") val y: Double? = null,
)

/**
 * The workflow editor's live channel: who else has the workflow open, where
 * their pointers are, and that the graph has been saved by somebody. Issue #645.
 *
 * Server-sent events one way and a plain POST the other, as the chat and the
 * task page already do: the editor has nothing to say down the stream but where
 * its pointer is, and a save goes through the GraphQL mutation it always went
 * through, which is what announces it.
 */
@RestController
class WorkflowLiveAPI(
    private val access: WorkspaceAccess,
    private val assignments: WorkspaceWorkflowRepository,
    private val workflows: WorkflowRepository,
    private val room: WorkflowRoom,
    private val readers: ReaderWatch,
    private val mapper: ObjectMapper,
) {

    /**
     * The stream an open editor reads, for as long as it is open.
     *
     * [Untimed]: an editor stays open all afternoon. What ends it is the reader
     * going - [ReaderWatch] pings every second and gives up on the first write
     * that fails - and then the seat is left and everybody else is told.
     *
     * Access is checked before the first byte, as on every stream here.
     */
    @Untimed
    @GetMapping("/api/workspaces/{workspaceId}/workflows/{workflowId}/live", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun live(
        @PathVariable workspaceId: Long,
        @PathVariable workflowId: Long,
        @RequestParam viewer: String,
        response: HttpServletResponse,
    ): StreamingResponseBody {
        access.requireVisible(workspaceId)
        if (!assignments.existsByWorkspaceIdAndWorkflowId(workspaceId, workflowId)) throw WorkflowNotFoundException(workflowId)
        if (!VIEWER.matches(viewer)) throw ViewerInvalidException()
        val login = currentUser()
        val revision = workflows.findByIdOrNull(workflowId)?.graphRevision ?: 0

        response.setHeader("Cache-Control", "no-cache, no-transform")
        response.setHeader("X-Accel-Buffering", "no")

        return StreamingResponseBody { _ ->
            val sse = ServerSentEvents(response, mapper)
            val seat = room.join(workflowId, viewer, login)
            try {
                readers.whileReading(sse, gone = { room.close(seat) }) {
                    // Where the graph is as this opens, so an editor that loaded
                    // it a moment ago knows whether it has already moved on.
                    sse.send("hello", mapOf("revision" to revision))
                    while (true) {
                        val frames = seat.next() ?: break
                        frames.forEach { sse.send(it.event, it.payload) }
                    }
                }
            } catch (closed: Exception) {
                log.debug("Workflow {} live stream for {} ended", workflowId, viewer, closed)
            } finally {
                room.leave(seat)
            }
        }
    }

    /** Where this viewer's pointer is, for everybody else in the room. */
    @PostMapping("/api/workspaces/{workspaceId}/workflows/{workflowId}/live/pointer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun pointer(@PathVariable workspaceId: Long, @PathVariable workflowId: Long, @RequestBody at: PointerRequest) {
        room.pointer(workflowId, at.viewer, currentUser(), at.x, at.y)
    }

    private fun currentUser(): String = SecurityContextHolder.getContext().authentication?.name ?: "system"

    private companion object {
        val log = LoggerFactory.getLogger(WorkflowLiveAPI::class.java)

        /** What an editor names itself: an id it made up, which goes back out to everybody in the room. */
        val VIEWER = Regex("[A-Za-z0-9-]{1,64}")
    }
}

@ResponseStatus(HttpStatus.BAD_REQUEST)
class ViewerInvalidException : RuntimeException("A viewer is named with letters, digits and dashes, 64 at most")
