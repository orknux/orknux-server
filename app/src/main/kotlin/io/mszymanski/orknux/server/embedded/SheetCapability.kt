package io.mszymanski.orknux.server.embedded

import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.attachment.AttachmentTypes
import io.mszymanski.orknux.server.llm.LlmSessionStore
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.Base64

/**
 * Reading a CSV or a workbook somebody attached - .xlsx, .xls or .ods - a page
 * of rows at a time.
 *
 * Before this an attached .xlsx was base64 under a key nothing read, and a CSV
 * was readable only as one long string - a model asked about row 4,000 of an
 * export was handed the whole file or nothing. `sheet_read` answers rows as
 * cells, the sheet's own row numbers, how many there are and where the next
 * page starts; the size of a page is Admin Settings' (`sheet.*`).
 *
 * Declared as the reader of every sheet type ([StoredReader]), so the note on
 * an attached file names this tool by its declaration rather than by a name
 * written into the note.
 */
@Component
class SheetCapability(
    private val reader: SheetReader,
    private val scratch: LlmSessionStore,
    private val tuning: Tuning,
    private val mapper: ObjectMapper,
) : EmbeddedCapability {

    override val key = "sheet"
    override val name = "Sheets"

    override fun tools(): List<EmbeddedTool> = listOf(
        EmbeddedTool(
            name = READ,
            readOnly = true,
            summary = "Reads rows of a CSV, an Excel workbook or an OpenDocument spreadsheet kept under a key.",
            description = "Reads an attached CSV, .xlsx, .xls or .ods (LibreOffice, OpenOffice) kept under a $KEY in this session and answers a page " +
                "of its rows as cells, numbered as the sheet numbers them from 1, with how many rows there are. " +
                "Dates come as ISO 8601 and formulas as their saved values. A workbook's answer lists its sheets; " +
                "pass $SHEET to read another. Pass $FROM for the row to start at to read further - the answer says " +
                "where the next page starts, and repeats the header row when the page starts past it.",
            params = listOf(
                EmbeddedParam(KEY, ValueType.STRING, "The key the file is kept under.", required = true),
                EmbeddedParam(SHEET, ValueType.STRING, "A workbook's sheet, by name. Left out, the first."),
                EmbeddedParam(FROM, ValueType.NUMBER, "The first row, counting from 1. Left out, the start."),
                EmbeddedParam(TO, ValueType.NUMBER, "The last row. Left out, as many as one page holds."),
                EmbeddedParam(DELIMITER, ValueType.STRING, "A CSV's delimiter, where the one found is wrong: \",\", \";\", \"\\t\" or \"|\"."),
            ),
            reads = StoredReader(READS, KEY),
        ),
    )

    override fun run(name: String, arguments: String, workspaceId: Long, sessionId: Long?): String {
        if (name != READ) return refusal("There is no tool called ${key}_$name.")
        val asked = runCatching { mapper.readTree(arguments) }.getOrNull() ?: return refusal("That is not valid JSON.")
        val named = text(asked, KEY)?.trim()?.ifEmpty { null } ?: return refusal("Give the $KEY of a CSV or a workbook.")
        if (sessionId == null) return refusal("Nothing is kept outside a session, so there is no \"$named\" to read.")
        val stored = scratch.get(sessionId, named) ?: return refusal("Nothing in this session is kept under \"$named\".")
        val kind = scratch.kindOf(sessionId, named)
        val value = runCatching { mapper.readTree(stored) }.getOrNull()?.takeIf { it.isTextual }?.stringValue() ?: stored

        val from = number(asked, FROM) ?: 1
        val to = number(asked, TO)
        if (to != null && to < from) return refusal("$TO is before $FROM.")
        val delimiter = when (val said = text(asked, DELIMITER)) {
            null, "" -> null
            "\\t", "tab" -> '\t'
            else -> said.singleOrNull() ?: return refusal("$DELIMITER is one character, such as \",\" or \";\".")
        }
        val limits = SheetReader.Limits(
            rows = tuning[Tunable.SHEET_ROWS_MOST],
            characters = tuning[Tunable.SHEET_ANSWER_CHARACTERS],
            unpackedBytes = tuning[Tunable.SHEET_UNPACKED_MB].toLong() * 1024 * 1024,
        )

        /*
         * Held as text, it is a CSV. Held as bytes, it is a workbook where the
         * bytes say so, and otherwise a CSV kept as bytes - one filed before
         * its type was corrected, say.
         */
        val reading = if (kind?.binary == false) {
            reader.csv(value, from, to, limits, delimiter ?: '\t'.takeIf { isTsv(kind.contentType) })
        } else {
            val bytes = runCatching { Base64.getDecoder().decode(value.trim()) }.getOrNull()
            when {
                bytes == null -> reader.csv(value, from, to, limits, delimiter)
                reader.isWorkbook(bytes) -> reader.workbook(bytes, text(asked, SHEET)?.ifBlank { null }, from, to, limits)
                else -> reader.csv(bytes.decodeToString(), from, to, limits, delimiter)
            }
        }
        return when (reading) {
            is SheetReader.Reading.Refused -> refusal(reading.reason)
            is SheetReader.Reading.Read -> answer(reading.page)
        }
    }

    private fun answer(page: SheetReader.Page): String {
        val answer = linkedMapOf<String, Any?>()
        page.sheets?.let { answer["sheets"] = it }
        page.sheet?.let { answer[SHEET] = it }
        page.delimiter?.let { answer[DELIMITER] = if (it == '\t') "\\t" else it.toString() }
        page.header?.let { answer["header"] = it }
        answer["rows"] = page.rows
        answer[FROM] = page.from
        answer[TO] = page.to
        answer["totalRows"] = page.total
        if (page.to < page.total) {
            answer["next"] = "Rows ${page.to + 1} to ${page.total} are not in this answer; ask again with $FROM ${page.to + 1}."
        }
        return mapper.writeValueAsString(answer)
    }

    private fun isTsv(contentType: String?): Boolean =
        contentType?.substringBefore(';')?.trim()?.lowercase() == AttachmentTypes.TSV

    private fun text(node: JsonNode, name: String): String? =
        node.path(name).takeIf { it.isTextual }?.stringValue()

    /** A whole number of at least 1, sent as a number or as its text. */
    private fun number(node: JsonNode, name: String): Int? {
        val given = node.path(name)
        val said = when {
            given.isNumber -> given.intValue()
            given.isTextual -> given.stringValue().trim().toIntOrNull()
            else -> null
        }
        return said?.takeIf { it > 0 }
    }

    private fun refusal(said: String): String = mapper.writeValueAsString(mapOf("error" to said))

    internal companion object {
        const val READ = "read"
        const val KEY = "key"
        const val SHEET = "sheet"
        const val FROM = "from"
        const val TO = "to"
        const val DELIMITER = "delimiter"

        /** The stored types this reads. */
        val READS = setOf(
            AttachmentTypes.CSV,
            AttachmentTypes.TSV,
            AttachmentTypes.XLSX,
            AttachmentTypes.XLS,
            AttachmentTypes.ODS,
            "application/csv",
        )
    }
}
