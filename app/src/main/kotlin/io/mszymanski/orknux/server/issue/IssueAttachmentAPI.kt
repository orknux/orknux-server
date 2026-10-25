package io.mszymanski.orknux.server.issue

import io.mszymanski.orknux.server.attachment.AttachmentDownloads
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.attachment.AttachmentsDisabledException
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.core.io.InputStreamResource
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.time.format.DateTimeFormatter

/**
 * Putting files on an issue, and reading them back.
 *
 * REST rather than GraphQL for the reason the chat's upload is: what crosses
 * here is bytes, and a multipart form is what a browser makes of a file picker.
 *
 * Uploaded against the workspace rather than against the issue, because there
 * is not always an issue yet - a screenshot is picked while the report is still
 * being written, and waiting until "File Issue" is pressed would mean waiting
 * for the upload then. Which issue it belongs to is settled afterwards, by
 * `attachToIssue`, exactly as a chat's files are settled when the message is
 * sent.
 *
 * Whether files may be attached at all is the installation's answer, not this
 * tracker's: the same switch, the same size limit and the same storage as the
 * chat, so an operator who has said no has said no once.
 */
@RestController
class IssueAttachmentAPI(
    private val attachments: IssueAttachmentRepository,
    private val workspaces: WorkspaceRepository,
    private val store: AttachmentStore,
    private val settings: InstallationSettings,
    private val access: WorkspaceAccess,
    private val downloads: AttachmentDownloads,
) {

    /** Several at once, because a file picker hands over several at once. */
    @PostMapping("/api/workspaces/{workspaceId}/issue-attachments")
    @Transactional
    fun upload(
        @PathVariable workspaceId: Long,
        @RequestParam("files") files: List<MultipartFile>,
    ): ResponseEntity<Any> {
        val workspace = access.requireVisible(workspaceId)
        if (!settings.attachmentsEnabled()) throw AttachmentsDisabledException()

        val picked = files.filterNot { it.isEmpty }.map { (it.originalFilename?.trim()?.ifEmpty { null } ?: "file") to it }
        // All of them before any is kept, so a refusal leaves nothing half-uploaded behind it.
        picked.forEach { (name, file) -> settings.requireUploadable(name, file.size) }
        val kept = picked.map { (name, file) ->
            val location = file.inputStream.use { store.putFrom(workspaceId, name, it) }
            attachments.save(
                IssueAttachment(
                    workspaceId = workspaceId,
                    filename = name.takeLast(MAX_NAME),
                    contentType = io.mszymanski.orknux.server.attachment.AttachmentTypes.of(name, file.contentType),
                    sizeBytes = file.size,
                    location = location,
                    uploadedBy = currentUser(),
                ),
            )
        }

        /*
         * Not recorded in the audit here, unlike the chat's upload: at this
         * moment the file belongs to nothing, and "somebody uploaded a file"
         * without saying what it went on is a line that answers no question.
         * `attachToIssue` records it, where the issue number can be named.
         */
        return ResponseEntity.ok(mapOf("attachments" to kept.map(::describe)))
    }

    /**
     * Hands the file back, to anybody who can see the workspace it is in.
     *
     * Checked against the workspace rather than against who uploaded it: a file
     * on an issue belongs to the people working the issue. What may be shown
     * rather than downloaded is [AttachmentDownloads]' business, so a chat and
     * an issue agree about what is safe to render.
     */
    @GetMapping("/api/issue-attachments/{id}")
    fun download(@PathVariable id: Long): ResponseEntity<InputStreamResource> {
        // A file in a workspace the caller cannot see is answered as a file that
        // is not there. The refusal used to say which, and this endpoint takes a
        // plain number over HTTP, so the difference between the two answers was
        // a way of counting what other teams have uploaded.
        val attachment = attachments.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw IssueAttachmentNotFoundException(id)

        return downloads.serve(
            filename = attachment.filename,
            contentType = attachment.contentType,
            sizeBytes = attachment.sizeBytes,
            location = attachment.location,
        )
    }

    /**
     * The same shape the issue's own query answers with, so the page can show a
     * file it has just uploaded without waiting to be told about it again.
     *
     * `mine` is true because it cannot be anything else: this is the answer to
     * whoever just uploaded it, and they are the person who may remove it.
     */
    private fun describe(attachment: IssueAttachment) = mapOf(
        "id" to requireNotNull(attachment.id).toString(),
        "filename" to attachment.filename,
        "contentType" to attachment.contentType,
        "sizeBytes" to attachment.sizeBytes,
        "uploadedBy" to attachment.uploadedBy,
        "uploadedAt" to attachment.uploadedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        "mine" to true,
    )

    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"

    private companion object {
        /** Long enough for a real name, short enough for the column. */
        const val MAX_NAME = 255
    }
}
