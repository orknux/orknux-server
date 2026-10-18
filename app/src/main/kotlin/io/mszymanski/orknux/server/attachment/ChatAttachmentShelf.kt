package io.mszymanski.orknux.server.attachment

import io.mszymanski.orknux.workflow.script.SessionScratch
import io.mszymanski.orknux.workflow.script.StoredKind
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.Base64

/**
 * What came with a chat message and is not a picture, put where the agent's
 * tools can reach it.
 *
 * A picture goes to the model as part of the turn. Everything else - a PDF, a
 * log, a spreadsheet - was only named in the message ("Attached: guide.pdf"),
 * and nothing put the bytes anywhere a tool could read them: an agent asked
 * about the PDF called its reader with the file's name as the key and was told
 * the session holds nothing under it. Now each file is put in the session's
 * store under `attachment.<id>`, as a drawn picture is put under
 * `picture.<id>`, and the agent is told the key.
 *
 * Stored as [StoredKind] says a value must be: a text type holds the text, any
 * other the base64 of its bytes. A file the store refuses - over the session's
 * value size, say - is named with the reason, so the agent says it cannot read
 * it rather than promising to.
 */
@Component
class ChatAttachmentShelf(
    private val store: AttachmentStore,
    private val scratch: SessionScratch,
    private val mapper: ObjectMapper,
) {

    /**
     * Puts [attached] in session [sessionId]'s store, pictures aside, and says
     * where - a note for the model, or null where there was nothing to put.
     */
    fun shelve(sessionId: Long, attached: List<ChatAttachment>): String? {
        val files = attached.filterNot { it.contentType.startsWith("image/") }
        if (files.isEmpty()) return null

        val lines = files.map { file ->
            val key = "$KEY_PREFIX${file.id}"
            val described = "${file.filename} (${file.contentType}, ${kilobytes(file.sizeBytes)})"
            val refused = runCatching {
                val bytes = store.open(file.location).use { it.readBytes() }
                val text = isText(file.contentType)
                val value = if (text) bytes.decodeToString() else Base64.getEncoder().encodeToString(bytes)
                scratch.put(sessionId, key, mapper.writeValueAsString(value), StoredKind(file.contentType, !text))
            }.getOrElse { failure ->
                log.warn("Attachment {} could not be put in session {}'s store", file.filename, sessionId, failure)
                "it could not be read from storage"
            }
            if (refused == null) "- $key: $described" else "- $described could not be kept for tools: $refused"
        }
        return (listOf(NOTE) + lines).joinToString("\n")
    }

    private fun isText(contentType: String): Boolean {
        val type = contentType.substringBefore(';').trim().lowercase()
        return type.startsWith("text/") || type in TEXT_TYPES || type.endsWith("+json") || type.endsWith("+xml")
    }

    private fun kilobytes(size: Long): String = if (size < 1024) "$size B" else "${size / 1024} KB"

    companion object {
        /** What an attached file is kept under in the session's store, before its attachment id. */
        const val KEY_PREFIX = "attachment."

        /**
         * Says what a tool needs and nothing about which tool: which ones can
         * read a stored file depends on what this agent was granted.
         */
        const val NOTE = "Files attached to this message are in this session's store. " +
            "To read one, pass its key to a tool that reads a stored file - not the file's name:"

        private val TEXT_TYPES = setOf("application/json", "application/xml", "application/x-yaml", "application/yaml", "application/csv")
        private val log = LoggerFactory.getLogger(ChatAttachmentShelf::class.java)
    }
}
