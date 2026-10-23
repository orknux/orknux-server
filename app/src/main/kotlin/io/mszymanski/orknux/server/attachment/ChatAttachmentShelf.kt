package io.mszymanski.orknux.server.attachment

import io.mszymanski.orknux.server.chat.ZipTools
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
    /** Which of our tools reads a type; provided, since the capabilities reach back into much of the app. */
    private val readers: org.springframework.beans.factory.ObjectProvider<io.mszymanski.orknux.server.embedded.EmbeddedCapabilities>,
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
            if (refused == null) "- $key: $described${readWith(file.contentType, key)}" else "- $described could not be kept for tools: $refused"
        }
        return (listOf(NOTE) + lines).joinToString("\n")
    }

    /**
     * Which tool reads a file of this type, and the call written out - a small
     * model told only the tool's name still set up a scratchpad first. Asked of
     * the tools' own declarations rather than spelled here, so a renamed tool
     * or parameter is said by its new name; a type nothing declares gets no
     * line, and the note's general sentence covers it.
     */
    private fun readWith(contentType: String, key: String): String {
        val type = contentType.substringBefore(';').trim().lowercase()
        val reader = readers.ifAvailable?.readerOf(type)
            ?: (ZipTools.ZIP_EXTRACT to ZipTools.KEY).takeIf { type in ZIP_TYPES }
            ?: return ""
        return " - read it with ${reader.first} {\"${reader.second}\": \"$key\"}"
    }

    private fun kilobytes(size: Long): String = if (size < 1024) "$size B" else "${size / 1024} KB"

    companion object {
        /**
         * Whether a file of this type is held as its text rather than as
         * base64 - for an attachment put in a session's store, and for a Slack
         * file a plugin downloads, so the two cannot disagree about a CSV.
         */
        fun isText(contentType: String): Boolean {
            val type = contentType.substringBefore(';').trim().lowercase()
            return type.startsWith("text/") || type in TEXT_TYPES || type.endsWith("+json") || type.endsWith("+xml")
        }

        /** What an attached file is kept under in the session's store, before its attachment id. */
        const val KEY_PREFIX = "attachment."

        /**
         * Says what a tool needs - the key - and where the file
         * is not: an agent with a scratchpad took "this session's store" for it,
         * and asked the scratchpad for the key three times. Which tool reads a
         * type is said per file, by [readWith].
         */
        const val NOTE = "Files attached to this message are kept in this session's value store - not in a " +
            "scratchpad. A tool reads one when you pass its key; pass the key below, not the file's name:"

        /** What a zip is sent as; the zip tool reads one by key. */
        private val ZIP_TYPES = setOf("application/zip", "application/x-zip-compressed")

        private val TEXT_TYPES = setOf("application/json", "application/xml", "application/x-yaml", "application/yaml", "application/csv")
        private val log = LoggerFactory.getLogger(ChatAttachmentShelf::class.java)
    }
}
