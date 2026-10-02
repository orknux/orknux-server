package io.mszymanski.orknux.server.transfer

import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceNotFoundException
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets

/**
 * A whole workspace, downloaded as one file. Issue #590.
 *
 * REST rather than GraphQL because what crosses here is a file the browser
 * saves: an address the Export button can point at, answered with a name to
 * save it under. The import is the other way round - a file the page has
 * already read, and an answer the page draws - so it is a mutation beside
 * `duplicateWorkspace` and answers the same shape.
 *
 * An administrator's, like Duplicate: it reads every component of a workspace,
 * and the file is meant for making another one.
 */
@RestController
class WorkspaceExportAPI(
    private val duplicator: WorkspaceDuplicator,
    private val workspaces: WorkspaceRepository,
    private val access: WorkspaceAccess,
    private val audit: WorkspaceAuditRecorder,
) {

    @GetMapping("/api/workspaces/{id}/export")
    fun export(@PathVariable id: Long): ResponseEntity<ByteArray> {
        access.requireAdmin()
        val workspace = workspaces.findByIdOrNull(id) ?: throw WorkspaceNotFoundException(id)
        val bytes = duplicator.export(id).toByteArray(StandardCharsets.UTF_8)
        audit.record(id, WorkspaceAuditCategory.WORKSPACE, "Workspace ${workspace.name} exported")
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(duplicator.fileNameFor(id), StandardCharsets.UTF_8).build().toString(),
            )
            .body(bytes)
    }
}
