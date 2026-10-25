package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.server.attachment.AttachmentDownloads
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workflow.WorkspaceArtifactAPI
import org.springframework.core.io.InputStreamResource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.nio.charset.StandardCharsets

/**
 * The files of documents: the pictures an editor draws in a page.
 *
 * REST because what crosses is bytes and what asks for them is an `<img>`. A
 * picture is addressed by the workspace and the artifact id the document names
 * it by, whatever kind of artifact it is - one drawn in Design, one an agent
 * saved, one a run drew - so a document does not have to know which table its
 * pictures came from. To anybody who may see the workspace, the bar the
 * Artifacts page itself sets; one that is not there, or not theirs, is a 404.
 */
@RestController
class DocumentFilesAPI(
    private val artifacts: WorkspaceArtifactAPI,
    private val store: AttachmentStore,
    private val access: WorkspaceAccess,
    private val downloads: AttachmentDownloads,
    private val exports: DocumentExports,
) {

    @GetMapping("/api/workspaces/{workspaceId}/document-images/{artifactId}")
    fun image(@PathVariable workspaceId: Long, @PathVariable artifactId: String): ResponseEntity<InputStreamResource> {
        if (!access.canSee(workspaceId)) throw DocumentImageNotFoundException(artifactId)
        val held = artifacts.storedOf(workspaceId, artifactId)?.takeIf { it.contentType.startsWith("image/") && store.exists(it.location) }
            ?: throw DocumentImageNotFoundException(artifactId)
        return downloads.serve(
            filename = held.view.filename,
            contentType = held.contentType,
            sizeBytes = held.view.sizeBytes,
            location = held.location,
        )
    }

    /**
     * The stored document as a file to keep: pdf, md, odt or ods - the address
     * orknux_document_export hands out and the editor's Export opens. Always an
     * attachment; what is in it was written by people and models.
     */
    @GetMapping("/api/documents/{id}/export/{format}")
    fun export(@PathVariable id: Long, @PathVariable format: String): ResponseEntity<ByteArray> {
        val made = try {
            exports.export(id, format)
        } catch (refused: DocumentFormatUnknownException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, refused.message)
        } catch (failed: DocumentExportFailedException) {
            throw ResponseStatusException(HttpStatus.valueOf(422), failed.message)
        }
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(made.contentType))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(made.filename, StandardCharsets.UTF_8).build().toString(),
            )
            .header("X-Content-Type-Options", "nosniff")
            .body(made.bytes)
    }
}

/** A picture asked for by a page that is not here, or not this caller's: answered with a status, for an `<img>`. */
class DocumentImageNotFoundException(val artifactId: String) : RuntimeException("No picture $artifactId here")
