package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * A file put onto a workspace's Artifacts page by somebody signed in: kept as
 * a saved artifact, as an agent's `save_artifact` keeps one, under the same
 * limit every upload is held to, and answered with the address the Artifacts
 * page opens it at.
 *
 * The research dialog's upload did the same for a research's sources, but
 * filed every file as "Uploaded for research"; something else putting a page
 * on Artifacts - the unreleased report's HTML - would have been recorded as
 * that.
 */
@RestController
class WorkspaceArtifactUploadAPI(
    private val saved: SavedArtifacts,
    private val settings: InstallationSettings,
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
        settings.requireUploadable(name, file.size)
        val kept = when (
            val saving = saved.upload(
                workspaceId = workspaceId,
                savedBy = by,
                name = name,
                // What a person dropped onto the page is described by its name until they say more; the store asks for one.
                description = description?.trim()?.take(DESCRIPTION_MOST)?.ifEmpty { null } ?: name,
                bytes = file.bytes,
            )
        ) {
            is SavedArtifacts.Saving.Refused -> return refused(saving.reason)
            is SavedArtifacts.Saving.Saved -> saving.artifact
        }
        auditRecorder.record(workspaceId, WorkspaceAuditCategory.WORKFLOW, "Artifact ${kept.name} uploaded")
        return ResponseEntity.ok(answer(workspaceId, kept))
    }

    /**
     * A new file behind a saved artifact that is already there: the same key,
     * the same page and the same download address, so a link somebody was
     * sent opens the new file. Answered as an upload is. The name follows the
     * new file; the description stays unless one is sent.
     *
     * POST rather than PUT because it is a form a browser sends, as the
     * upload's is - one shape of request for the two halves of the page.
     */
    @PostMapping("/api/workspaces/{workspaceId}/artifacts/{key}")
    fun replace(
        @PathVariable workspaceId: Long,
        @PathVariable key: String,
        @RequestParam("file") file: MultipartFile,
        @RequestParam("description", required = false) description: String?,
    ): ResponseEntity<Any> {
        access.requireVisible(workspaceId)
        val number = saved.numberOf(key)
        if (number == null) {
            if (ArtifactKind.entries.any { key.startsWith("${it.name}-") }) {
                return refused("Only a file saved to Artifacts can be replaced, and $key is not one.")
            }
            return missing("This workspace has no saved artifact $key.")
        }
        if (file.isEmpty) return refused("The file is empty.")
        val name = file.originalFilename?.trim()?.substringAfterLast('/')?.substringAfterLast('\\')?.ifEmpty { null } ?: "file"
        settings.requireUploadable(name, file.size)
        val replaced = when (
            val replacing = saved.replaceUpload(
                workspaceId = workspaceId,
                id = number,
                name = name,
                description = description?.trim()?.take(DESCRIPTION_MOST),
                bytes = file.bytes,
            )
        ) {
            is SavedArtifacts.Replacing.Missing -> return missing(replacing.reason)
            is SavedArtifacts.Replacing.Refused -> return refused(replacing.reason)
            is SavedArtifacts.Replacing.Replaced -> replacing
        }
        auditRecorder.record(workspaceId, WorkspaceAuditCategory.WORKFLOW, replacedLine(replaced))
        return ResponseEntity.ok(answer(workspaceId, replaced.artifact))
    }

    private fun answer(workspaceId: Long, kept: SavedArtifact): Map<String, Any> {
        val key = "${ArtifactKind.SAVED.name}-${kept.id}"
        return mapOf("id" to key, "name" to kept.name, "url" to "/workspace/$workspaceId/artifacts/$key", "download" to "/api/artifacts/${kept.id}")
    }

    private fun refused(reason: String): ResponseEntity<Any> = ResponseEntity.badRequest().body(mapOf("error" to reason))

    private fun missing(reason: String): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to reason))

    private companion object {
        /** A description is a line, as an agent's save_artifact writes one. A format, not a behaviour. */
        const val DESCRIPTION_MOST = 500
    }
}
