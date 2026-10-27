package io.mszymanski.orknux.server.attachment

import io.mszymanski.orknux.server.chat.ZipTools
import io.mszymanski.orknux.server.llm.LlmSessionStore
import io.mszymanski.orknux.server.plugin.PdfRenderer
import io.mszymanski.orknux.server.plugin.PdfSource
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.workflow.script.StoredKind
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

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
 * Held by reference - the key names the attachment, and its bytes are read from
 * the attachment store when a tool asks ([LlmSessionStore.content]) - with the
 * [StoredKind] a value of its type would have: a text type reads as text, any
 * other as bytes. A file the store refuses - its keys all taken, say - is named
 * with the reason, so the agent says it cannot read it rather than promising to.
 */
@Component
class ChatAttachmentShelf(
    private val store: AttachmentStore,
    private val scratch: LlmSessionStore,
    private val renderer: PdfRenderer,
    private val tuning: Tuning,
    /** Which of our tools reads a type; provided, since the capabilities reach back into much of the app. */
    private val readers: org.springframework.beans.factory.ObjectProvider<io.mszymanski.orknux.server.embedded.EmbeddedCapabilities>,
) {

    /**
     * Puts [attached] in session [sessionId]'s store, pictures aside, and says
     * where - a note for the model, or null where there was nothing to put.
     *
     * By reference, not by copy: the key names the attachment and a tool reads
     * its bytes from the attachment store. Copied in as base64 JSON, a file
     * over Session Value Size got no key at all - session 911, a 28 MB PDF
     * the agent then hunted for with `find /` - though the upload limit had
     * already said it was a file this installation takes.
     */
    fun shelve(sessionId: Long, attached: List<ChatAttachment>): String? {
        // Pictures go to the model as part of the turn; an SVG goes drawn, and its markup is kept here too, to be read or edited.
        val files = attached.filter { !it.contentType.startsWith("image/") || it.contentType.substringBefore(';').trim().equals("image/svg+xml", ignoreCase = true) }
        if (files.isEmpty()) return null

        val lines = files.map { file ->
            val key = "$KEY_PREFIX${file.id}"
            // Asked again here, so a CSV filed under the browser's word for it before 1.2.0 is still read as one.
            val type = AttachmentTypes.of(file.filename, file.contentType)
            val pages = if (type == PDF) pagesOf(file) else null
            val described = "${file.filename} ($type, ${sized(file.sizeBytes)}${pages?.let { ", $it page${if (it == 1) "" else "s"}" }.orEmpty()})"
            val refused = if (!store.exists(file.location)) {
                "it could not be read from storage"
            } else {
                scratch.putAttachment(sessionId, key, requireNotNull(file.id), StoredKind(type, !isText(type)))
            }
            if (refused != null) log.warn("Attachment {} could not be put in session {}'s store: {}", file.filename, sessionId, refused)
            if (refused == null) "- $key: $described${readWith(type, key, pages)}" else "- $described could not be kept for tools: $refused"
        }
        return (listOf(NOTE) + lines).joinToString("\n")
    }

    /** A PDF's page count, read off its page tree in place; null where it cannot be opened. */
    private fun pagesOf(file: ChatAttachment): Int? = runCatching {
        val identity = "attachment:${file.id}"
        val source = store.pathOf(file.location)?.let { PdfSource.of(it, file.sizeBytes, identity) }
            ?: PdfSource.of(file.sizeBytes, identity) { store.open(file.location) }
        renderer.pages(source)
    }.getOrNull()

    /**
     * Which tool reads a file of this type, and the call written out - a small
     * model told only the tool's name still set up a scratchpad first. Asked of
     * the tools' own declarations rather than spelled here, so a renamed tool
     * or parameter is said by its new name; a type nothing declares gets no
     * line, and the note's general sentence covers it.
     */
    private fun readWith(contentType: String, key: String, pages: Int?): String {
        val type = contentType.substringBefore(';').trim().lowercase()
        val reader = readers.ifAvailable?.readerOf(type)
            ?: (ZipTools.ZIP_EXTRACT to ZipTools.KEY).takeIf { type in ZIP_TYPES }
        /*
         * A long document is read in parts, each summarised before the next.
         * Session 911: a 400-page PDF read whole is more text than one answer
         * holds and more than a model keeps hold of, and what the person
         * wanted was the document gone through step by step.
         */
        val range = readers.ifAvailable?.rangeOf(type)
        if (reader != null && range != null && pages != null && pages >= tuning[Tunable.PDF_LONG_PAGES]) {
            val step = tuning[Tunable.PDF_STEP_PAGES]
            val (from, to) = range
            val askers = readers.ifAvailable?.askersOf(type).orEmpty().filter { it.first != reader.first }
            val finding = askers.joinToString(", ") { "${it.first} {\"${it.second}\": \"$key\", …}" }
            // Held rather than wrapped: a step of Int.MAX_VALUE pages is "the whole file", not a negative page.
            return " - it is long: read it $step pages at a time with ${reader.first} " +
                "{\"${reader.second}\": \"$key\", \"$from\": 1, \"$to\": ${minOf(step, pages)}}, then \"$from\": ${io.mszymanski.orknux.server.tuning.TunableArithmetic.plus(step, 1)}, " +
                "\"$to\": ${io.mszymanski.orknux.server.tuning.TunableArithmetic.times(2, step)} and so on to page $pages, and summarise each part before reading the next rather than reading it whole" +
                (if (finding.isNotEmpty()) "; to find something in it, use $finding" else "")
        }
        /*
         * And the tools that answer a question about it without reading it
         * whole. Chat 981: told only "read it with svg_read", an agent read a
         * 465 KB copper layer into every round after; a count of its pads was
         * one xml_query away.
         */
        val askers = readers.ifAvailable?.askersOf(type).orEmpty().filter { it.first != reader?.first }
        val asking = askers.joinToString(", ") { "${it.first} {\"${it.second}\": \"$key\", …}" }
        return when {
            reader != null && askers.isNotEmpty() ->
                " - read it with ${reader.first} {\"${reader.second}\": \"$key\"}, or, rather than reading a large one " +
                    "whole, ask about it with $asking"
            reader != null -> " - read it with ${reader.first} {\"${reader.second}\": \"$key\"}"
            askers.isNotEmpty() -> " - ask about it with $asking"
            else -> ""
        }
    }

    private fun sized(size: Long): String = when {
        size < 1024 -> "$size B"
        size < 10L * 1024 * 1024 -> "${size / 1024} KB"
        else -> "%.1f MB".format(java.util.Locale.ROOT, size / (1024.0 * 1024.0))
    }

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

        private const val PDF = "application/pdf"

        /** What a zip is sent as; the zip tool reads one by key. */
        private val ZIP_TYPES = setOf("application/zip", "application/x-zip-compressed")

        private val TEXT_TYPES = setOf("application/json", "application/xml", "application/x-yaml", "application/yaml", "application/csv")
        private val log = LoggerFactory.getLogger(ChatAttachmentShelf::class.java)
    }
}
