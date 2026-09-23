package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.attachment.AttachmentDownloads
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.security.WorkspaceAccess
import org.springframework.core.io.InputStreamResource
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/**
 * Handing back something a run said out loud.
 *
 * REST rather than GraphQL for the reason [ExecutionPictureAPI] is: what crosses
 * here is bytes, and the link that asks for them is an `<audio src>` on the run
 * graph and a download beside it.
 *
 * Nothing writes here. Audio arrives when a run reaches a node that speaks,
 * which is not a request anybody made - there is no browser on the other end of
 * a workflow run - so there is no upload half.
 */
@RestController
class ExecutionSpeechAPI(
    private val speeches: ExecutionSpeechRepository,
    private val store: AttachmentStore,
    private val access: WorkspaceAccess,
    private val downloads: AttachmentDownloads,
) {

    /**
     * To anybody who can see the workspace the run is in, the same bar as
     * reading the run itself. Audio in a workspace the caller cannot see is
     * answered as audio that is not there, so a plain number over HTTP cannot be
     * used to count what other teams have produced - the rule the pictures keep.
     */
    @GetMapping("/api/executions/speech/{id}")
    fun download(@PathVariable id: Long): ResponseEntity<InputStreamResource> {
        val speech = speeches.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw ExecutionSpeechNotFoundException(id)

        // A row whose bytes have gone is answered as a file that is not here.
        if (!store.exists(speech.location)) throw ExecutionSpeechNotFoundException(id)

        return downloads.serve(
            filename = speech.filename,
            contentType = speech.contentType,
            sizeBytes = speech.sizeBytes,
            location = speech.location,
        )
    }
}
