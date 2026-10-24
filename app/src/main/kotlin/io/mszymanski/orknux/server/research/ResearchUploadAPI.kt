package io.mszymanski.orknux.server.research

import io.mszymanski.orknux.server.attachment.AttachmentTypes
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workflow.SavedArtifacts
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * A document uploaded from the New research dialog. Issue #659.
 *
 * Filed on the workspace's Artifacts page like any file an agent saved - the
 * same table, the same store, the same limits - so it is a document of the
 * workspace from then on, there to be picked for the next research too, rather
 * than something that belongs to one research and is lost with it.
 *
 * REST rather than GraphQL because what crosses is bytes, the reason the chat's
 * attachments give. A file whose text a research cannot read is refused before
 * it is kept: offering it in the picker would be offering a source with nothing
 * in it.
 */
@RestController
class ResearchUploadAPI(
    private val saved: SavedArtifacts,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    @PostMapping("/api/workspaces/{workspaceId}/research-documents")
    fun upload(
        @PathVariable workspaceId: Long,
        @RequestParam("files") files: List<MultipartFile>,
    ): ResponseEntity<Any> {
        access.requireVisible(workspaceId)
        val by = SecurityContextHolder.getContext().authentication?.name ?: "system"

        val kept = files.filterNot { it.isEmpty }.map { file ->
            val name = file.originalFilename?.trim()?.substringAfterLast('/')?.substringAfterLast('\\')?.ifEmpty { null } ?: "document"
            if (!ResearchDocuments.readable(AttachmentTypes.of(name, file.contentType))) {
                return refused("$name is not a document a research can read: upload text, Markdown, HTML, JSON or PDF.")
            }
            when (
                val saving = saved.save(
                    workspaceId = workspaceId,
                    savedBy = by,
                    name = name,
                    description = "Uploaded for research",
                    content = Base64.getEncoder().encodeToString(file.bytes),
                    base64 = true,
                )
            ) {
                is SavedArtifacts.Saving.Refused -> return refused(saving.reason)
                is SavedArtifacts.Saving.Saved -> saving.artifact
            }
        }

        if (kept.isNotEmpty()) {
            val what = if (kept.size == 1) kept.first().name else "${kept.size} documents"
            auditRecorder.record(workspaceId, WorkspaceAuditCategory.WORKFLOW, "Uploaded $what for research")
        }
        return ResponseEntity.ok(
            mapOf(
                "documents" to kept.map {
                    ResearchDocumentView(
                        id = requireNotNull(it.id),
                        name = it.name,
                        description = it.description,
                        contentType = it.contentType,
                        sizeBytes = it.sizeBytes,
                        savedAt = it.savedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    )
                },
            ),
        )
    }

    private fun refused(reason: String): ResponseEntity<Any> = ResponseEntity.badRequest().body(mapOf("error" to reason))
}
