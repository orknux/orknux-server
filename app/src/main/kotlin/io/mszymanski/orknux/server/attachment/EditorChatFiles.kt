package io.mszymanski.orknux.server.attachment

import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.server.embedded.PdfText
import io.mszymanski.orknux.server.embedded.SheetReader
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** One turn of an editor's chat as the browser sends it: who spoke, what, and the files that went with it. */
data class EditorChatTurn(val role: String, val content: String, val attachmentIds: List<Long> = emptyList())

/**
 * The files sent into the chat down the right of the document, wireframe and
 * diagram editors, turned into what the model is sent.
 *
 * The files are uploaded as the main chat's are - the same endpoint, store and
 * size limit, so they are `chat_attachment` rows not yet tied to a chat - and
 * only the ids travel with the turn. A picture goes as a picture, read by
 * [ChatAttachments] exactly as the main chat reads one, so a provider that
 * cannot carry it refuses in the same sentence. Everything else goes as its
 * text, because these chats offer the model only the editor's own tools and
 * no reader it could hand a key to: a text file as itself, a PDF through
 * [PdfText], a CSV or a workbook through [SheetReader]. A file none of those
 * reads is named, with the reason, so the model says it cannot read it rather
 * than answering about something it was never shown.
 *
 * The editor keeps the conversation and sends it whole each turn, so a turn's
 * files are read again on every turn after it - a picture somebody asked
 * about stays in front of the model while the conversation goes on.
 */
@Component
class EditorChatFiles(
    private val attachments: ChatAttachmentRepository,
    private val pictures: ChatAttachments,
    private val store: AttachmentStore,
    private val pdfs: PdfText,
    private val sheets: SheetReader,
    private val tuning: Tuning,
) {

    /**
     * The conversation as a model is sent it. A turn with neither words nor
     * files is dropped; files are read only where they are [asker]'s, in
     * [workspaceId] - an id from anywhere else is ignored, as the main chat
     * ignores one, because a file still waiting in somebody's composer has no
     * chat to protect it.
     */
    fun turns(workspaceId: Long, asker: String, said: List<EditorChatTurn>): List<ChatTurn> = said
        .filter { it.content.isNotBlank() || it.attachmentIds.isNotEmpty() }
        .mapNotNull { turn ->
            val role = if (turn.role.equals("assistant", ignoreCase = true)) "assistant" else "user"
            if (role == "assistant") return@mapNotNull ChatTurn(role = role, content = turn.content).takeIf { turn.content.isNotBlank() }
            val files = owned(workspaceId, asker, turn.attachmentIds)
            val images = pictures.imagesOf(files.filter { isPicture(it) })
            val read = described(files.filterNot { isPicture(it) })
            val content = listOfNotNull(turn.content.trim().ifEmpty { null }, read).joinToString("\n\n")
            if (content.isEmpty() && images.isEmpty()) null else ChatTurn(role = role, content = content, images = images)
        }

    private fun owned(workspaceId: Long, asker: String, ids: List<Long>): List<ChatAttachment> {
        if (ids.isEmpty()) return emptyList()
        val found = attachments.findAllById(ids.distinct()).associateBy { it.id }
        return ids.distinct().mapNotNull { found[it] }.filter { it.workspaceId == workspaceId && it.uploadedBy == asker }
    }

    /** The files that are not pictures, each as its text, under one budget of characters; null where there are none. */
    private fun described(files: List<ChatAttachment>): String? {
        if (files.isEmpty()) return null
        var left = tuning[Tunable.EDITOR_CHAT_FILE_CHARACTERS]
        return buildString {
            append("Files sent with this message:")
            files.forEach { file ->
                val type = AttachmentTypes.of(file.filename, file.contentType)
                append("\n\n--- File: ${file.filename} ($type) ---\n")
                when (val read = text(file, type)) {
                    is Read.Refused -> append("(This file could not be read here: ${read.reason}.)")
                    is Read.Text -> {
                        val body = read.text.trim()
                        when {
                            left <= 0 -> append("(Left out: the files on this message reached the most a model is handed.)")
                            body.length > left -> {
                                append(body.take(left))
                                append("\n(The rest of this file was left out: the files on this message reached the most a model is handed.)")
                                left = 0
                            }
                            else -> {
                                append(body)
                                left -= body.length
                            }
                        }
                    }
                }
                append("\n--- End of ${file.filename} ---")
            }
        }
    }

    private sealed interface Read {
        data class Text(val text: String) : Read
        data class Refused(val reason: String) : Read
    }

    private fun text(file: ChatAttachment, type: String): Read {
        val bare = type.substringBefore(';').trim().lowercase()
        val sheet = bare == AttachmentTypes.CSV || bare == AttachmentTypes.TSV
        if (!sheet && bare !in WORKBOOKS && bare != PDF && !ChatAttachmentShelf.isText(bare)) {
            return Read.Refused("this chat reads pictures, text, PDFs and spreadsheets, and $bare is none of them")
        }
        val bytes = runCatching { store.open(file.location).use { it.readBytes() } }
            .onFailure { log.warn("Attachment {} could not be read for an editor chat", file.filename, it) }
            .getOrNull() ?: return Read.Refused("it could not be read from storage")
        return when {
            sheet -> sheet(sheets.csv(bytes.decodeToString(), 1, null, limits(), '\t'.takeIf { bare == AttachmentTypes.TSV }))
            bare in WORKBOOKS -> workbook(bytes)
            ChatAttachmentShelf.isText(bare) -> Read.Text(bytes.decodeToString())
            bare == PDF -> when (val pages = pdfs.pages(bytes, null)) {
                is PdfText.Pages.Refused -> Read.Refused(pages.reason)
                is PdfText.Pages.Read ->
                    if (pages.textless) {
                        Read.Refused("the pdf holds no text - it is a scan or pictures only")
                    } else {
                        Read.Text(pages.pages.mapIndexed { at, page -> "[Page ${at + 1}]\n${page.trim()}" }.joinToString("\n\n"))
                    }
            }
            else -> Read.Refused("it could not be read")
        }
    }

    /** Every sheet of a workbook, one after another, as far as the rows of one read go. */
    private fun workbook(bytes: ByteArray): Read {
        val first = sheets.workbook(bytes, null, 1, null, limits())
        if (first is SheetReader.Reading.Refused) return Read.Refused(first.reason.trimEnd('.'))
        val names = (first as SheetReader.Reading.Read).page.sheets.orEmpty()
        if (names.size <= 1) return sheet(first)
        return Read.Text(
            names.joinToString("\n\n") { name ->
                val one = sheets.workbook(bytes, name, 1, null, limits())
                "[Sheet $name]\n" + when (val read = sheet(one)) {
                    is Read.Text -> read.text
                    is Read.Refused -> "(This sheet could not be read: ${read.reason}.)"
                }
            },
        )
    }

    /** A page of rows as lines of cells, tab between them, and how many rows were left out. */
    private fun sheet(reading: SheetReader.Reading): Read = when (reading) {
        is SheetReader.Reading.Refused -> Read.Refused(reading.reason.trimEnd('.'))
        is SheetReader.Reading.Read -> {
            val page = reading.page
            val rows = page.rows.joinToString("\n") { it.joinToString("\t") }
            Read.Text(if (page.to < page.total) "$rows\n(Rows ${page.to + 1} to ${page.total} were left out.)" else rows)
        }
    }

    private fun limits() = SheetReader.Limits(
        rows = tuning[Tunable.SHEET_ROWS_MOST],
        characters = tuning[Tunable.EDITOR_CHAT_FILE_CHARACTERS],
        unpackedBytes = tuning[Tunable.SHEET_UNPACKED_MB].toLong() * 1024 * 1024,
    )

    private fun isPicture(file: ChatAttachment) = file.contentType.trim().lowercase().startsWith("image/")

    private companion object {
        val log = LoggerFactory.getLogger(EditorChatFiles::class.java)

        const val PDF = "application/pdf"
        val WORKBOOKS = setOf(AttachmentTypes.XLSX, AttachmentTypes.XLS, AttachmentTypes.ODS)
    }
}
