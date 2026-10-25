package io.mszymanski.orknux.server.design

import com.github.miachm.sods.Sheet
import com.github.miachm.sods.SpreadSheet
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.embedded.PageBlocks
import io.mszymanski.orknux.server.embedded.PdfWriter
import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.workflow.WorkspaceArtifactAPI
import org.jsoup.nodes.Entities
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A document written out as a file: PDF, Markdown, OpenDocument text or
 * OpenDocument spreadsheet. On the server, so an agent exports exactly what the
 * editor's Export does, through orknux_document_export, and a download link is
 * something a person in a chat can click.
 *
 * Always the stored document: the editor saves what is on the screen before it
 * asks for one. Every export is said in the audit log, because a file leaving
 * the server is a copy somebody now holds.
 *
 * - **PDF** is laid out by [PdfWriter], as a research report is, from the page
 *   [DocumentHtml] writes: the document's own page size and margins, a page
 *   break a CSS break, and each picture read from the artifact store and put in
 *   as its bytes - nothing is fetched.
 * - **Markdown** is [DocumentMarkdown]'s. A document with pictures is a zip of
 *   the Markdown and an `images` folder its links point into, since a link to
 *   this server opens only for somebody signed in to it; one without is the
 *   `.md` alone.
 * - **ODT** is [DocumentOdt]'s, through the ODF Toolkit.
 * - **ODS** puts each table of the document on a sheet of its own, through
 *   SODS, the library the server already reads spreadsheets with; a document
 *   with no table is one sheet, a block of its text to a row.
 */
@Service
class DocumentExports(
    /** Lazy: the API reaches the tools, which reach this. */
    @param:Lazy private val api: DocumentAPI,
    @param:Lazy private val artifacts: WorkspaceArtifactAPI,
    private val store: AttachmentStore,
    private val pdf: PdfWriter,
    private val mapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val markdown = DocumentMarkdown(mapper)

    /** A file, what it is, and what it is called. */
    class Exported(val filename: String, val contentType: String, val extension: String, val bytes: ByteArray)

    /** One picture's bytes, as the store holds them. */
    class Picture(val artifact: String, val filename: String, val contentType: String, val bytes: ByteArray)

    /** The stored document [id] as [format]: pdf, md, odt or ods. Access is checked by [DocumentAPI.find]. */
    fun export(id: Long, format: String): Exported {
        val held = api.find(id)
        val tree = api.tree(held)
        val size = DocumentPage.of(held)
        val made = when (format.lowercase()) {
            "pdf" -> pdf(held, tree, size)
            "md", "markdown" -> markdown(held, tree)
            "odt" -> Exported(
                WireframeHtml.fileName(held.name, "odt"), ODT, "odt",
                DocumentOdt().write(tree, size, held.name) { pictureOf(held.workspaceId, it) },
            )
            "ods" -> Exported(WireframeHtml.fileName(held.name, "ods"), ODS, "ods", ods(tree))
            else -> throw DocumentFormatUnknownException(format)
        }
        api.exported(held, made.extension.uppercase())
        return made
    }

    private fun pdf(held: DesignDocument, tree: JsonNode, size: DocumentPage): Exported {
        val page = DocumentHtml().page(held.name, tree, size)
        return when (val written = pdf.fromHtml(page, held.name) { name -> pictureOf(held.workspaceId, name)?.let { PageBlocks.Picture(it.bytes, it.contentType) } }) {
            is PdfWriter.Written.Refused -> throw DocumentExportFailedException("PDF", written.reason)
            is PdfWriter.Written.Made -> Exported(WireframeHtml.fileName(held.name, "pdf"), "application/pdf", "pdf", written.pdf)
        }
    }

    private fun markdown(held: DesignDocument, tree: JsonNode): Exported {
        val pictures = api.content.images(tree).mapIndexedNotNull { at, artifact ->
            pictureOf(held.workspaceId, artifact)?.let { artifact to "images/${at + 1}-${it.filename}" }
        }.toMap()
        val text = markdown.write(tree) { artifact -> pictures[artifact]?.replace(" ", "%20") ?: "${DocumentMarkdown.ARTIFACT_SCHEME}$artifact" }
        if (pictures.isEmpty()) {
            return Exported(WireframeHtml.fileName(held.name, "md"), "text/markdown; charset=utf-8", "md", text.toByteArray(Charsets.UTF_8))
        }
        val zipped = ByteArrayOutputStream()
        ZipOutputStream(zipped).use { zip ->
            zip.putNextEntry(ZipEntry(WireframeHtml.fileName(held.name, "md")))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            pictures.forEach { (artifact, path) ->
                val picture = pictureOf(held.workspaceId, artifact) ?: return@forEach
                zip.putNextEntry(ZipEntry(path))
                zip.write(picture.bytes)
                zip.closeEntry()
            }
        }
        return Exported(WireframeHtml.fileName(held.name, "zip"), "application/zip", "zip", zipped.toByteArray())
    }

    private fun ods(tree: JsonNode): ByteArray {
        val book = SpreadSheet()
        val tables = mutableListOf<Pair<String, List<List<String>>>>()
        var heading: String? = null
        val blocks = tree.path("content")
        for (at in 0 until blocks.size()) {
            val block = blocks.get(at)
            when (block.path("type").asText("")) {
                "heading" -> heading = api.content.plainText(block).take(SHEET_NAME)
                "table" -> {
                    val rows = block.path("content")
                    val read = (0 until rows.size()).map { row ->
                        val cells = rows.get(row).path("content")
                        (0 until cells.size()).map { api.content.plainText(cells.get(it)) }
                    }
                    if (read.isNotEmpty()) tables += (heading ?: "Table ${tables.size + 1}") to read
                }
            }
        }
        if (tables.isEmpty()) {
            val lines = (0 until blocks.size()).map { api.content.plainText(blocks.get(it)) }.filter { it.isNotBlank() }.ifEmpty { listOf("") }
            tables += "Text" to lines.map { listOf(it) }
        }
        val taken = mutableSetOf<String>()
        tables.forEach { (name, rows) ->
            val width = rows.maxOf { it.size }.coerceAtLeast(1)
            var title = name.replace(Regex("[\\[\\]*?:/\\\\']"), " ").trim().ifEmpty { "Sheet" }.take(SHEET_NAME)
            var n = 2
            while (!taken.add(title.lowercase())) title = "${name.take(SHEET_NAME - 4)} ${n++}"
            val sheet = Sheet(title, rows.size, width)
            rows.forEachIndexed { r, row -> row.forEachIndexed { c, value -> sheet.getRange(r, c).setValue(value) } }
            sheet.getRange(0, 0, 1, width).setFontBold(true)
            book.appendSheet(sheet)
        }
        return ByteArrayOutputStream().also { book.save(it) }.toByteArray()
    }

    /** A picture a document names, read from the store; null for one that is gone, which leaves a gap rather than no file. */
    fun pictureOf(workspaceId: Long, artifact: String): Picture? {
        val held = artifacts.storedOf(workspaceId, artifact)?.takeIf { it.contentType.startsWith("image/") } ?: return null
        return runCatching { Picture(artifact, held.view.filename, held.contentType, store.open(held.location).use { it.readBytes() }) }
            .onFailure { log.warn("Could not read picture {} for a document export", artifact, it) }
            .getOrNull()
    }

    companion object {
        const val ODT = "application/vnd.oasis.opendocument.text"
        const val ODS = "application/vnd.oasis.opendocument.spreadsheet"

        /** The longest a sheet's name may be in a spreadsheet. A format. */
        private const val SHEET_NAME = 31
    }
}

/**
 * A document as one HTML page for the PDF writer: its own page size and
 * margins, a page break as a CSS break, and each picture by its artifact id as
 * the `src`, which the writer is handed the bytes of. Everything a person or a
 * model wrote is escaped.
 */
class DocumentHtml {

    fun page(name: String, doc: JsonNode, size: DocumentPage): String = buildString {
        append("<!doctype html><html><head><meta charset=\"utf-8\"><title>").append(escape(name)).append("</title><style>")
        append("@page { size: ${mm(size.widthMm)} ${mm(size.heightMm)}; margin: ${mm(size.marginMm)}; }")
        append("body { font-size: 11pt; }")
        append("figure { margin: 0 0 10pt; text-align: center; page-break-inside: avoid; }")
        append("figcaption { font-size: 9pt; color: #555555; margin-top: 4pt; }")
        append("blockquote { margin: 0 0 8pt; padding-left: 10pt; border-left: 2pt solid #c8c8c8; color: #444444; }")
        append(".page-break { page-break-after: always; height: 0; margin: 0; }")
        // A page cannot scroll sideways as the editor does, so a line too long for it wraps; its own breaks stay.
        append("pre.code { font-family: monospace; font-size: 9pt; background: #f6f8fa; border: 0.5pt solid #d0d7de; white-space: pre-wrap; }")
        append("</style></head><body>")
        blocks(doc.path("content"), this)
        append("</body></html>")
    }

    private fun blocks(list: JsonNode, out: StringBuilder) {
        for (at in 0 until list.size()) block(list.get(at), out)
    }

    private fun block(node: JsonNode, out: StringBuilder) {
        when (node.path("type").asText("")) {
            "paragraph" -> out.append("<p>").append(inline(node.path("content"))).append("</p>")
            "heading" -> {
                val level = node.path("attrs").path("level").asInt(1).coerceIn(1, 6)
                out.append("<h$level>").append(inline(node.path("content"))).append("</h$level>")
            }
            "bulletList" -> list("ul", node, out, null)
            "orderedList" -> list("ol", node, out, node.path("attrs").path("start").asInt(1))
            "blockquote" -> {
                out.append("<blockquote>")
                blocks(node.path("content"), out)
                out.append("</blockquote>")
            }
            "codeBlock" -> {
                val inner = node.path("content")
                val code = (0 until inner.size()).joinToString("") { inner.get(it).path("text").asText("") }
                val language = DocumentCode.named(node.path("attrs").path("language").asText(""))
                out.append("<pre class=\"code\"")
                if (language != null) out.append(" data-language=\"").append(language).append("\"")
                out.append("><code>")
                DocumentCode.runs(code, language).forEach { run ->
                    if (run.colour == null) {
                        out.append(escape(run.text))
                    } else {
                        out.append("<span style=\"color: ").append(run.colour).append("\">").append(escape(run.text)).append("</span>")
                    }
                }
                out.append("</code></pre>")
            }
            "horizontalRule" -> out.append("<hr>")
            "pageBreak" -> out.append("<div class=\"page-break\"></div>")
            "image" -> {
                val attrs = node.path("attrs")
                val width = attrs.path("width").asInt(0).takeIf { it in 5..100 }
                out.append("<figure><img src=\"").append(escape(attrs.path("artifact").asText(""))).append("\" alt=\"")
                    .append(escape(attrs.path("alt").asText(""))).append("\"")
                if (width != null) out.append(" style=\"width: $width%\"")
                out.append(">")
                attrs.path("caption").asText("").takeIf { it.isNotEmpty() }?.let { out.append("<figcaption>").append(escape(it)).append("</figcaption>") }
                out.append("</figure>")
            }
            "table" -> {
                out.append("<table>")
                val rows = node.path("content")
                for (row in 0 until rows.size()) {
                    out.append("<tr>")
                    val cells = rows.get(row).path("content")
                    for (cell in 0 until cells.size()) {
                        val one = cells.get(cell)
                        val tag = if (one.path("type").asText("") == "tableHeader") "th" else "td"
                        out.append("<$tag")
                        one.path("attrs").path("colspan").asInt(1).takeIf { it > 1 }?.let { out.append(" colspan=\"$it\"") }
                        one.path("attrs").path("rowspan").asInt(1).takeIf { it > 1 }?.let { out.append(" rowspan=\"$it\"") }
                        out.append(">")
                        blocks(one.path("content"), out)
                        out.append("</$tag>")
                    }
                    out.append("</tr>")
                }
                out.append("</table>")
            }
            else -> out.append("<p>").append(inline(node.path("content"))).append("</p>")
        }
    }

    private fun list(tag: String, node: JsonNode, out: StringBuilder, start: Int?) {
        out.append("<$tag")
        if (start != null && start > 1) out.append(" start=\"$start\"")
        out.append(">")
        val items = node.path("content")
        for (at in 0 until items.size()) {
            out.append("<li>")
            blocks(items.get(at).path("content"), out)
            out.append("</li>")
        }
        out.append("</$tag>")
    }

    private fun inline(list: JsonNode): String {
        val out = StringBuilder()
        for (at in 0 until list.size()) {
            val node = list.get(at)
            when (node.path("type").asText("")) {
                "text" -> {
                    var text = escape(node.path("text").asText(""))
                    val marks = node.path("marks")
                    for (m in 0 until marks.size()) {
                        val mark = marks.get(m)
                        text = when (mark.path("type").asText("")) {
                            "bold" -> "<strong>$text</strong>"
                            "italic" -> "<em>$text</em>"
                            "underline" -> "<u>$text</u>"
                            "strike" -> "<s>$text</s>"
                            "code" -> "<code>$text</code>"
                            // A link stays words in a PDF: nothing in it is followed while it is laid out.
                            "link" -> "<a href=\"${escape(mark.path("attrs").path("href").asText(""))}\">$text</a>"
                            // Read through the same check a save is, so nothing but a colour reaches the style.
                            "textStyle" -> DocumentContent.colour(mark.path("attrs").path("color").asText(""))
                                ?.let { "<span style=\"color: $it\">$text</span>" } ?: text
                            "note" -> "<span style=\"background-color: $NOTE_COLOUR\">$text</span>"
                            else -> text
                        }
                    }
                    out.append(text)
                }
                "hardBreak" -> out.append("<br>")
            }
        }
        return out.toString()
    }

    private fun escape(text: String): String = Entities.escape(text)

    companion object {
        /** What a highlight is drawn in on paper: the editor's own yellow, which prints legibly under black. */
        const val NOTE_COLOUR = "#fde68a"

        fun mm(value: Double): String = String.format(java.util.Locale.ROOT, "%.2fmm", value)
    }
}

class DocumentFormatUnknownException(val format: String) :
    RuntimeException("A document is exported as pdf, md, odt or ods, not $format"), Refusal {
    override val arguments get() = mapOf("format" to format)
}

class DocumentExportFailedException(val format: String, val reason: String) :
    RuntimeException("The document could not be written as $format: $reason"), Refusal {
    override val arguments get() = mapOf("format" to format, "reason" to reason)
}
