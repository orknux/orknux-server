package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.attachment.AttachmentTypes
import io.mszymanski.orknux.server.attachment.AttachmentsDisabledException
import io.mszymanski.orknux.server.attachment.ChatAttachment
import io.mszymanski.orknux.server.attachment.ChatAttachmentRepository
import io.mszymanski.orknux.server.attachment.ChatAttachmentShelf
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.llm.LlmSessionStore
import io.mszymanski.orknux.server.security.TokenAccess
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.workflow.script.StoredKind
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.io.InputStream

/**
 * Where an MCP client's files are kept for the tools it calls.
 *
 * A tool that sends a file - `jira_attach`, Slack's uploads - takes a
 * contentKey, a short name for bytes already kept, because a model copying
 * kilobytes of base64 into an argument gets one character wrong. Inside a
 * conversation the key is whatever the tool that made the file answered. Over
 * MCP there was no conversation, so no store, so nothing a client held could
 * reach such a tool at all.
 *
 * So each caller has a store of its own per workspace: a session keyed
 * `mcp:<who>`, opened the first time they upload. What [McpFilesAPI] takes in
 * is kept there under `upload.<id>`, by reference to the attachment, as a file
 * attached to a chat is; and the tools a client calls over MCP are run in it,
 * so a plugin reads the key as it would in a chat.
 */
@Component
class McpFiles(
    private val recorder: LlmSessionRecorder,
    private val sessions: LlmSessionRepository,
) {

    /** The caller's store in [workspaceId], opened now if [create] and it is not there yet; null otherwise. */
    fun sessionOf(workspaceId: Long, who: String, create: Boolean): Long? {
        if (who.isBlank()) return null
        if (create) return recorder.open(workspaceId, PREFIX, who)
        return sessions.findByWorkspaceIdAndSessionKey(workspaceId, "$PREFIX:$who")?.id
    }

    companion object {
        /** The session key's prefix, so a person's MCP store is not one of their conversations. */
        const val PREFIX = "mcp"

        /** What an uploaded file is kept under, before its attachment id. */
        const val KEY_PREFIX = "upload."

        /** The upload door, under a workspace's MCP address. */
        fun path(workspaceId: Long) = "/mcp/$workspaceId/files"
    }
}

/**
 * The door an MCP client sends a file's bytes through, and is handed the
 * contentKey a tool takes.
 *
 * Bytes as they are - `curl --data-binary @report.pdf "…/files?filename=report.pdf"`
 * - or a multipart form's `file`, with the caller's token as on every MCP call.
 * The file is filed as a chat attachment is, under the same upload limit and
 * the same switch that turns attachments off, and only the person who sent it
 * can name it: the key lives in their own store.
 *
 * A read-only token sends nothing. A Custom one may, because what it may change
 * is decided by the tools it names, and a file nobody's tool takes changes
 * nothing.
 */
@RestController
class McpFilesAPI(
    private val files: McpFiles,
    private val attachments: ChatAttachmentRepository,
    private val store: AttachmentStore,
    private val scratch: LlmSessionStore,
    private val settings: InstallationSettings,
    private val access: WorkspaceAccess,
    private val audit: WorkspaceAuditRecorder,
    private val userTokens: io.mszymanski.orknux.server.user.AppUserTokenRepository,
) {

    @PostMapping("/mcp/{workspaceId}/files", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @Transactional
    fun form(
        @PathVariable workspaceId: Long,
        @RequestParam("file") file: MultipartFile,
        @RequestParam("filename", required = false) filename: String?,
    ): ResponseEntity<Any> {
        val name = filename?.trim()?.ifEmpty { null } ?: file.originalFilename?.trim()?.ifEmpty { null }
        return file.inputStream.use { keep(workspaceId, name, file.contentType, file.size, it) }
    }

    /**
     * The bytes as the body. The filename is read off the address by hand, never
     * through the request's parameters: curl's `--data-binary` labels a body as
     * a form, and asking for a parameter makes the container parse a PNG as
     * form fields - which fails, and answered 400 before anything here ran.
     */
    @PostMapping("/mcp/{workspaceId}/files")
    @Transactional
    fun raw(@PathVariable workspaceId: Long, request: HttpServletRequest): ResponseEntity<Any> = request.inputStream.use {
        keep(workspaceId, filenameOf(request.queryString), request.contentType, request.contentLengthLong, it)
    }

    /** `filename` from a query string, decoded; null where it is not there or blank. */
    private fun filenameOf(query: String?): String? = query.orEmpty().split('&')
        .map { it.substringBefore('=') to it.substringAfter('=', "") }
        .firstOrNull { (name, _) -> name == "filename" }
        ?.second
        ?.let { runCatching { java.net.URLDecoder.decode(it, Charsets.UTF_8) }.getOrNull() }
        ?.trim()
        ?.ifEmpty { null }

    private fun keep(workspaceId: Long, filename: String?, declared: String?, size: Long, bytes: InputStream): ResponseEntity<Any> {
        // Answered as the MCP endpoint answers a workspace it cannot have: as not there.
        if (!access.canSee(workspaceId)) return refused(HttpStatus.NOT_FOUND, "There is no workspace $workspaceId that you can see")
        if (TokenAccess.readOnly() && !custom()) return refused(HttpStatus.FORBIDDEN, TokenAccess.REFUSAL)
        if (filename == null) {
            return refused(HttpStatus.BAD_REQUEST, "Say what the file is called: ?filename=report.pdf, whose extension says what it is")
        }
        if (size <= 0) return refused(HttpStatus.BAD_REQUEST, "There are no bytes to keep: send the file as the body, with its length")
        if (!settings.attachmentsEnabled()) throw AttachmentsDisabledException()
        settings.requireUploadable(filename, size)
        val who = SecurityContextHolder.getContext().authentication?.name.orEmpty()
        val session = files.sessionOf(workspaceId, who, create = true)
            ?: return refused(HttpStatus.FORBIDDEN, "Only somebody signed in can keep a file")

        /*
         * What a raw body is said to be is whatever the client defaults to -
         * curl's --data-binary says form-urlencoded - so a type that names no
         * format gives way to the one the filename's extension says.
         */
        val said = declared?.substringBefore(';')?.trim()?.lowercase()
        val named = if (said.isNullOrEmpty() || said in GENERIC) java.net.URLConnection.guessContentTypeFromName(filename) else declared
        val type = AttachmentTypes.of(filename, named)
        val location = store.putFrom(workspaceId, filename, bytes)
        val kept = attachments.save(
            ChatAttachment(
                workspaceId = workspaceId,
                filename = filename.takeLast(MAX_NAME),
                contentType = type,
                sizeBytes = size,
                location = location,
                uploadedBy = who,
            ),
        )
        val key = McpFiles.KEY_PREFIX + requireNotNull(kept.id)
        scratch.putAttachment(session, key, requireNotNull(kept.id), StoredKind(type, !ChatAttachmentShelf.isText(type)))
            ?.let { reason -> return refused(HttpStatus.CONFLICT, "The file could not be kept for your tools: $reason") }
        audit.record(workspaceId, WorkspaceAuditCategory.CHAT, "Uploaded ${kept.filename} over MCP")
        return ResponseEntity.ok(
            mapOf(
                "contentKey" to key,
                "filename" to kept.filename,
                "contentType" to type,
                "size" to size,
                "note" to "Pass contentKey to a tool that takes one - jira_attach, say - rather than the file's content.",
            ),
        )
    }

    /** Whether the caller came with a Custom token, which may send a file although it is held to reading elsewhere. */
    private fun custom(): Boolean {
        val tokenId = (SecurityContextHolder.getContext().authentication?.details as? io.mszymanski.orknux.server.user.ByToken)?.tokenId
            ?: return false
        return userTokens.findById(tokenId).orElse(null)?.tools != null
    }

    private fun refused(status: HttpStatus, said: String): ResponseEntity<Any> = ResponseEntity.status(status).body(mapOf("error" to said))

    private companion object {
        const val MAX_NAME = 255

        /** Types a client sends when it does not know, which say nothing about the file. */
        val GENERIC = setOf(MediaType.APPLICATION_OCTET_STREAM_VALUE, MediaType.APPLICATION_FORM_URLENCODED_VALUE, "binary/octet-stream")
    }
}
