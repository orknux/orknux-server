package io.mszymanski.orknux.server.attachment

import io.mszymanski.orknux.server.chat.ChatOwnership
import io.mszymanski.orknux.server.chat.ChatSessionRepository
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
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

/**
 * Uploading files to a chat, and reading them back.
 *
 * REST for the same reason transcription is: what crosses here is bytes, and a
 * multipart form is what a browser produces from a file picker.
 *
 * Every file is filed under the workspace that uploaded it, and a chat is not
 * the workspace's: it belongs to the person who started it. So reading one back
 * asks the chat it hangs on whether this caller may have it, which is the same
 * question [ChatOwnership] is asked about the conversation itself.
 */
@RestController
class AttachmentAPI(
    private val attachments: ChatAttachmentRepository,
    private val chats: ChatSessionRepository,
    private val workspaces: WorkspaceRepository,
    private val store: AttachmentStore,
    private val settings: InstallationSettings,
    private val access: WorkspaceAccess,
    private val ownership: ChatOwnership,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val downloads: AttachmentDownloads,
) {

    /** Several at once, because a file picker hands over several at once. */
    @PostMapping("/api/workspaces/{workspaceId}/attachments")
    @Transactional
    fun upload(
        @PathVariable workspaceId: Long,
        @RequestParam("files") files: List<MultipartFile>,
    ): ResponseEntity<Any> {
        val workspace = access.requireVisible(workspaceId)
        if (!settings.attachmentsEnabled()) throw AttachmentsDisabledException()

        val limit = settings.maxFileSizeMb() * 1024 * 1024
        val kept = files.filterNot { it.isEmpty }.map { file ->
            val name = file.originalFilename?.trim()?.ifEmpty { null } ?: "file"
            if (file.size > limit) throw AttachmentTooLargeException(name, settings.maxFileSizeMb())

            val location = store.put(workspaceId, name, file.bytes)
            attachments.save(
                ChatAttachment(
                    workspaceId = workspaceId,
                    filename = name.takeLast(MAX_NAME),
                    contentType = file.contentType?.ifBlank { null } ?: "application/octet-stream",
                    sizeBytes = file.size,
                    location = location,
                    uploadedBy = currentUser(),
                ),
            )
        }

        if (kept.isNotEmpty()) {
            val what = if (kept.size == 1) kept.first().filename else "${kept.size} files"
            auditRecorder.record(workspaceId, WorkspaceAuditCategory.CHAT, "Attached $what to a chat")
        }
        return ResponseEntity.ok(mapOf("attachments" to kept.map(::describe)))
    }

    /**
     * Hands the file back, to the person whose chat it is on.
     *
     * The workspace is not the question here. A chat is private to whoever
     * started it, so a document sent into one is private to the same person,
     * and checking only the workspace let a colleague walk the ids and read
     * everything anybody had ever attached. A file with no chat yet is one
     * still sitting in somebody's composer, so it stays theirs until the
     * message that carries it is sent.
     *
     * Refused as missing rather than as forbidden, because "you may not have
     * this" confirms there is something to have.
     *
     * What may be shown rather than downloaded, and the headers that go with
     * it, are [AttachmentDownloads]' business: an issue's files are served the
     * same way, and one list of what is safe to render is the only number of
     * lists that stays right.
     */
    @GetMapping("/api/attachments/{id}")
    fun download(@PathVariable id: Long): ResponseEntity<InputStreamResource> {
        val attachment = attachments.findByIdOrNull(id) ?: throw AttachmentNotFoundException(id)
        val chatId = attachment.chatSessionId
        if (chatId == null) {
            if (attachment.uploadedBy != currentUser()) throw AttachmentNotFoundException(id)
        } else {
            val chat = chats.findByIdOrNull(chatId) ?: throw AttachmentNotFoundException(id)
            if (!ownership.owns(chat)) throw AttachmentNotFoundException(id)
        }

        /*
         * A row whose bytes have gone is answered as a file that is not here.
         *
         * Left alone this opened the stream inside the response body and threw
         * from there: a 500 with a stack trace, which says the server broke when
         * what happened is that a file was deleted out from under a row. It
         * matters more since a chat can hold a picture it drew - the answer
         * itself is an `<img>` at this URL, and a browser handed a 500 draws the
         * broken-image icon, while a 404 is what the interface says one line
         * about.
         */
        if (!store.exists(attachment.location)) throw AttachmentNotFoundException(id)

        return downloads.serve(
            filename = attachment.filename,
            contentType = attachment.contentType,
            sizeBytes = attachment.sizeBytes,
            location = attachment.location,
        )
    }

    private fun describe(attachment: ChatAttachment) = mapOf(
        "id" to requireNotNull(attachment.id).toString(),
        "filename" to attachment.filename,
        "contentType" to attachment.contentType,
        "sizeBytes" to attachment.sizeBytes,
    )

    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"

    private companion object {
        /** Long enough for a real name, short enough for the column. */
        const val MAX_NAME = 255
    }
}

/**
 * Tying uploaded files to the chat they were sent with.
 *
 * Separate from the upload because the two happen at different moments: a file
 * goes up while the sentence is still being typed, and which chat it belongs to
 * is only settled when the message is sent — a first message makes the chat.
 */
@org.springframework.stereotype.Controller
class ChatAttachmentAPI(
    private val attachments: ChatAttachmentRepository,
    private val chats: ChatSessionRepository,
    private val ownership: ChatOwnership,
) {

    /**
     * What was attached to one chat, oldest first, for the person whose chat it
     * is.
     *
     * Somebody else's conversation answers the same nothing an id that was
     * never a chat answers. Anything louder than that - a refusal, or an error
     * naming the workspace - would say that the id is real and that somebody is
     * talking, which is exactly what walking the ids is looking for.
     */
    @org.springframework.graphql.data.method.annotation.QueryMapping
    fun chatAttachments(
        @org.springframework.graphql.data.method.annotation.Argument chatId: Long,
    ): List<ChatAttachmentView> {
        chats.findByIdOrNull(chatId)?.takeIf(ownership::owns) ?: return emptyList()
        return attachments.findByChatSessionIdOrderByUploadedAtAsc(chatId).map(::describeView)
    }

    /**
     * Says which chat these belong to.
     *
     * Only files of that chat's own workspace, and only ones not already spoken
     * for: an id from somewhere else is ignored rather than argued with, since
     * the message it came with has already been sent.
     *
     * The chat has to be the caller's, for the reason reading one back does:
     * putting a document into a conversation that is not yours is no more
     * yours to do than reading it.
     */
    @org.springframework.graphql.data.method.annotation.MutationMapping
    @Transactional
    fun attachToChat(
        @org.springframework.graphql.data.method.annotation.Argument chatId: Long,
        @org.springframework.graphql.data.method.annotation.Argument attachmentIds: List<Long>,
    ): List<ChatAttachmentView> {
        val chat = chats.findByIdOrNull(chatId) ?: throw AttachmentNotFoundException(chatId)
        ownership.requireOwn(chat)

        val kept = attachmentIds
            .mapNotNull { attachments.findByIdOrNull(it) }
            // The chat's owner uploaded it, it belongs to the same workspace,
            // and nothing has claimed it yet. Without the first of those, an id
            // guessed from somebody else's composer could be pulled into a chat
            // of one's own and read there.
            .filter { it.workspaceId == chat.workspaceId && it.chatSessionId == null }
            .filter { it.uploadedBy == chat.userId }
            .onEach { it.chatSessionId = chatId }
        return kept.map(::describeView)
    }

    private fun describeView(attachment: ChatAttachment) = attachment.view()
}

/** A chat's file as a screen is shown it: never where its bytes are kept. */
fun ChatAttachment.view() = ChatAttachmentView(
    id = requireNotNull(id),
    filename = filename,
    contentType = contentType,
    sizeBytes = sizeBytes,
    uploadedBy = uploadedBy,
)

data class ChatAttachmentView(
    val id: Long,
    val filename: String,
    val contentType: String,
    val sizeBytes: Long,
    val uploadedBy: String,
)
