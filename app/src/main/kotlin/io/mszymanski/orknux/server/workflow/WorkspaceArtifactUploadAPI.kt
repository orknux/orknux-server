package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.Base64

/**
 * A file put onto a workspace's Artifacts page by somebody signed in: kept as
 * a saved artifact, as an agent's `save_artifact` keeps one, under the same
 * limits, and answered with the address the Artifacts page opens it at.
 *
 * The research dialog's upload did the same for a research's sources, but
 * filed every file as "Uploaded for research"; something else putting a page
 * on Artifacts - the unreleased report's HTML - would have been recorded as
 * that.
 */
@RestController
class WorkspaceArtifactUploadAPI(
    private val saved: SavedArtifacts,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    @PostMapping("/api/workspaces/{workspaceId}/artifacts")
    fun upload(
        @PathVariable workspaceId: Long,
        @RequestParam("file") file: MultipartFile,
        @RequestParam("description", required = false) description: String?,
    ): ResponseEntity<Any> {
        access.requireVisible(workspaceId)
        if (file.isEmpty) return refused("The file is empty.")
        val by = SecurityContextHolder.getContext().authentication?.name ?: "system"
        val name = file.originalFilename?.trim()?.substringAfterLast('/')?.substringAfterLast('\\')?.ifEmpty { null } ?: "file"
        val kept = when (
            val saving = saved.save(
                workspaceId = workspaceId,
                savedBy = by,
                name = name,
                // What a person dropped onto the page is described by its name until they say more; the store asks for one.
                description = description?.trim()?.take(DESCRIPTION_MOST)?.ifEmpty { null } ?: name,
                content = Base64.getEncoder().encodeToString(file.bytes),
                base64 = true,
            )
        ) {
            is SavedArtifacts.Saving.Refused -> return refused(saving.reason)
            is SavedArtifacts.Saving.Saved -> saving.artifact
        }
        auditRecorder.record(workspaceId, WorkspaceAuditCategory.WORKFLOW, "Artifact ${kept.name} uploaded")
        val key = "${ArtifactKind.SAVED.name}-${kept.id}"
        return ResponseEntity.ok(
            mapOf("id" to key, "name" to kept.name, "url" to "/workspace/$workspaceId/artifacts/$key", "download" to "/api/artifacts/${kept.id}"),
        )
    }

    private fun refused(reason: String): ResponseEntity<Any> = ResponseEntity.badRequest().body(mapOf("error" to reason))

    private companion object {
        /** A description is a line, as an agent's save_artifact writes one. A format, not a behaviour. */
        const val DESCRIPTION_MOST = 500
    }
}
