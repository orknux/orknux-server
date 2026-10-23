package io.mszymanski.orknux.server.attachment

import io.mszymanski.orknux.server.chat.ChatSession
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.Base64

/**
 * What a message brought with it, for the model that is about to answer it.
 *
 * Two jobs, and they happen in the same breath: the files are tied to the chat,
 * and the ones a model can look at are read back out as `data:` URLs. Doing it
 * when the message is sent rather than afterwards is the whole point — a picture
 * that arrives after the answer is a picture the answer could not have seen.
 */
@Service
class ChatAttachments(
    private val attachments: ChatAttachmentRepository,
    private val store: AttachmentStore,
    private val svgs: io.mszymanski.orknux.server.plugin.SvgRenderer,
) {

    /**
     * Says which chat these belong to, and hands back the ones that do.
     *
     * Only files of the chat's own workspace, and only ones its owner uploaded:
     * an id from anywhere else is dropped rather than argued with, since the
     * message it came with is already on its way. The uploader is asked as well
     * as the workspace because a file waiting in somebody's composer has no
     * chat to protect it yet, and an id guessed from there would otherwise be
     * readable by pulling it into a chat of one's own.
     */
    @Transactional
    fun attach(session: ChatSession, ids: List<Long>): List<ChatAttachment> {
        if (ids.isEmpty()) return emptyList()

        return ids.mapNotNull { attachments.findByIdOrNull(it) }
            .filter { it.workspaceId == session.workspaceId && it.uploadedBy == session.userId }
            .onEach { it.chatSessionId = session.id }
    }

    /**
     * The pictures among them, as `data:` URLs.
     *
     * Images only. A model that can see takes a picture as part of the message;
     * a PDF is not something to put in front of one whole, and pretending
     * otherwise would send a megabyte of base64 that comes back as an apology.
     *
     * Read from storage each time rather than kept: this happens once per
     * message, and a cache of decoded images is a cache of somebody's documents.
     */
    fun imagesOf(attached: List<ChatAttachment>): List<String> = attached
        .filter { it.contentType.startsWith("image/") }
        .mapNotNull { attachment ->
            runCatching {
                val bytes = store.open(attachment.location).use { it.readBytes() }
                if (attachment.contentType.substringBefore(';').trim().equals(SVG, ignoreCase = true)) {
                    /*
                     * Drawn first. No provider takes an SVG as a picture - it was
                     * sent as image/svg+xml and refused, or read as nothing - so
                     * the model is shown it as a PNG; its markup is in the
                     * session's store for the tool that reads it.
                     */
                    when (val drawn = svgs.png(bytes.decodeToString(), null)) {
                        is io.mszymanski.orknux.server.plugin.SvgRenderer.Drawing.Drawn ->
                            "data:image/png;base64,${Base64.getEncoder().encodeToString(drawn.png)}"
                        is io.mszymanski.orknux.server.plugin.SvgRenderer.Drawing.Refused -> {
                            log.warn("Attachment {} is an SVG that could not be drawn: {}", attachment.filename, drawn.reason)
                            null
                        }
                    }
                } else {
                    "data:${attachment.contentType};base64,${Base64.getEncoder().encodeToString(bytes)}"
                }
            }.onFailure {
                log.warn("Attachment {} could not be read for the model", attachment.filename, it)
            }.getOrNull()
        }

    private companion object {
        val log = LoggerFactory.getLogger(ChatAttachments::class.java)
        const val SVG = "image/svg+xml"
    }
}
