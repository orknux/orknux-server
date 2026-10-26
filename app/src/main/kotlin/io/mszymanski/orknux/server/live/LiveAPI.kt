package io.mszymanski.orknux.server.live

import io.mszymanski.orknux.server.design.DesignDocumentRepository
import io.mszymanski.orknux.server.design.WireframeRepository
import io.mszymanski.orknux.server.diagram.DiagramRepository
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.stream.ReaderWatch
import io.mszymanski.orknux.server.stream.ServerSentEvents
import io.mszymanski.orknux.server.stream.Untimed
import io.mszymanski.orknux.server.workflow.live.PointerRequest
import io.mszymanski.orknux.server.workflow.live.ViewerInvalidException
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

/**
 * The live channel of the diagram, document and wireframe editors: who else
 * has the same one open, where their pointers are, and that somebody saved it.
 * Issue #683; the workflow editor's is `WorkflowLiveAPI`, on the same rooms.
 *
 * Server-sent events one way and a plain POST the other, as the workflow
 * editor does: a save goes through the GraphQL mutation it always went
 * through, and the save itself is what announces it, from every door.
 */
@RestController
class LiveAPI(
    private val access: WorkspaceAccess,
    private val diagrams: DiagramRepository,
    private val documents: DesignDocumentRepository,
    private val wireframes: WireframeRepository,
    private val rooms: LiveRooms,
    private val readers: ReaderWatch,
    private val mapper: ObjectMapper,
) {

    /**
     * The stream an open editor reads, for as long as it is open. Access is
     * checked before the first byte: the workspace that owns the thing, as
     * every read of it checks.
     */
    @Untimed
    @GetMapping("/api/live/{kind}/{id}", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun live(
        @PathVariable kind: String,
        @PathVariable id: Long,
        @RequestParam viewer: String,
        response: HttpServletResponse,
    ): StreamingResponseBody {
        val key = LiveRooms.Key(kindOf(kind), id)
        val revision = revisionOf(key)
        if (!LiveRooms.VIEWER.matches(viewer)) throw ViewerInvalidException()
        val login = currentUser()

        response.setHeader("Cache-Control", "no-cache, no-transform")
        response.setHeader("X-Accel-Buffering", "no")

        return StreamingResponseBody { _ ->
            val sse = ServerSentEvents(response, mapper)
            val seat = rooms.join(key, viewer, login)
            try {
                readers.whileReading(sse, gone = { rooms.close(seat) }) {
                    // Where it is as this opens, so an editor that loaded it a
                    // moment ago knows whether it has already moved on.
                    sse.send("hello", mapOf("revision" to revision))
                    while (true) {
                        val frames = seat.next() ?: break
                        frames.forEach { sse.send(it.event, it.payload) }
                    }
                }
            } catch (closed: Exception) {
                log.debug("{} {} live stream for {} ended", kind, id, viewer, closed)
            } finally {
                rooms.leave(seat)
            }
        }
    }

    /** Where this viewer's pointer is, for everybody else in the room; dropped unless it is sitting there. */
    @PostMapping("/api/live/{kind}/{id}/pointer")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun pointer(@PathVariable kind: String, @PathVariable id: Long, @RequestBody at: PointerRequest) {
        rooms.pointer(LiveRooms.Key(kindOf(kind), id), at.viewer, currentUser(), at.x, at.y)
    }

    /** The thing's revision, once the workspace that owns it is known to be visible to whoever asks. */
    private fun revisionOf(key: LiveRooms.Key): Long {
        val (workspaceId, revision) = when (key.kind) {
            LiveKind.DIAGRAM -> diagrams.findByIdOrNull(key.id)?.let { it.workspaceId to it.revision }
            LiveKind.DOCUMENT -> documents.findByIdOrNull(key.id)?.let { it.workspaceId to it.revision }
            LiveKind.WIREFRAME -> wireframes.findByIdOrNull(key.id)?.let { it.workspaceId to it.revision }
            LiveKind.WORKFLOW -> null
        } ?: throw LiveItemNotFoundException(key.kind.path, key.id)
        access.requireVisible(workspaceId)
        return revision.toLong()
    }

    private fun kindOf(path: String): LiveKind =
        LiveKind.of(path)?.takeIf { it != LiveKind.WORKFLOW } ?: throw LiveItemNotFoundException(path, 0)

    private fun currentUser(): String = SecurityContextHolder.getContext().authentication?.name ?: "system"

    private companion object {
        val log = LoggerFactory.getLogger(LiveAPI::class.java)
    }
}

@ResponseStatus(HttpStatus.NOT_FOUND)
class LiveItemNotFoundException(kind: String, id: Long) : RuntimeException("There is no $kind $id to open")
