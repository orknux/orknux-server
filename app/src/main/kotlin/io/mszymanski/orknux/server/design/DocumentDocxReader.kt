package io.mszymanski.orknux.server.design

import org.apache.poi.xwpf.usermodel.IBodyElement
import org.apache.poi.xwpf.usermodel.UnderlinePatterns
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFRun
import org.apache.poi.xwpf.usermodel.XWPFSDT
import org.apache.poi.xwpf.usermodel.XWPFTable
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBrType
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayInputStream

/**
 * A Word document read into a document, through Apache POI's XWPF model - the
 * library the server already writes spreadsheets with.
 *
 * A paragraph is a heading where its style says so (`Heading 1`, `Title`), and
 * an item of a list where it is numbered, a bullet or a number by what its
 * numbering's format is, nested by its level. A run keeps its bold, italic,
 * underline and strike, a hyperlink's address, and the pictures it carries,
 * each a block of its own after the words it stands among. A table is its rows
 * and cells, a cell its own blocks, its span across columns kept. A page break,
 * whether a paragraph starts a page or a run breaks one, is a page break.
 */
class DocumentDocxReader(mapper: ObjectMapper) {

    private val nodes = DocumentNodes(mapper)

    fun read(bytes: ByteArray, keep: PictureKeeper): ObjectNode =
        XWPFDocument(ByteArrayInputStream(bytes)).use { docx -> nodes.doc(Walker(docx, keep).blocks(docx.bodyElements)) }

    private inner class Walker(private val docx: XWPFDocument, private val keep: PictureKeeper) {

        /** A list being written, and the item a deeper one goes into. */
        private inner class Open(val ordered: Boolean, val items: MutableList<ObjectNode> = mutableListOf())

        fun blocks(elements: List<IBodyElement>): List<ObjectNode> {
            val out = mutableListOf<ObjectNode>()
            val open = mutableListOf<Open>()

            fun close(to: Int) {
                while (open.size > to) {
                    val list = open.removeAt(open.size - 1)
                    val made = nodes.list(list.ordered, list.items)
                    val parent = open.lastOrNull()
                    if (parent == null) {
                        out += made
                    } else {
                        // A deeper list belongs to the item above it.
                        val item = parent.items.lastOrNull() ?: nodes.listItem(emptyList()).also { parent.items += it }
                        (item.get("content") as tools.jackson.databind.node.ArrayNode).add(made)
                    }
                }
            }

            for (element in elements) {
                when (element) {
                    is XWPFParagraph -> {
                        val numbered = element.numID != null
                        if (!numbered) close(0)
                        if (element.isPageBreak) out += nodes.node("pageBreak")
                        val read = paragraph(element)
                        if (numbered) {
                            val level = (element.numIlvl?.toInt() ?: 0).coerceIn(0, 8)
                            val ordered = element.numFmt?.lowercase()?.let { it != "bullet" && it != "none" } ?: false
                            close(level + 1)
                            if (open.size == level + 1 && open.last().ordered != ordered) close(level)
                            while (open.size < level + 1) open += Open(ordered)
                            open.last().items += nodes.listItem(read.blocks)
                        } else {
                            out += read.blocks
                        }
                        if (read.breaksPage) {
                            close(0)
                            out += nodes.node("pageBreak")
                        }
                    }
                    is XWPFTable -> {
                        close(0)
                        table(element)?.let { out += it }
                    }
                    is XWPFSDT -> {
                        close(0)
                        element.content?.text?.takeIf { it.isNotBlank() }?.let { out += nodes.paragraph(listOf(nodes.text(it, emptyList()))) }
                    }
                }
            }
            close(0)
            return out
        }

        private inner class Read(val blocks: List<ObjectNode>, val breaksPage: Boolean)

        private fun paragraph(paragraph: XWPFParagraph): Read {
            val flow = nodes.Flow()
            var breaks = false
            val pictures = mutableListOf<ObjectNode>()
            for (run in paragraph.runs) {
                val marks = marks(run)
                val said = run.text().orEmpty()
                said.split('\n').forEachIndexed { at, line ->
                    if (at > 0) flow.lineBreak()
                    flow.words(line.replace('\t', ' '), marks)
                }
                if (run.ctr.brList.any { it.isSetType && it.type == STBrType.PAGE }) breaks = true
                run.embeddedPictures.forEach { picture ->
                    val data = picture.pictureData ?: return@forEach
                    keep(data.data, data.fileName)?.let { pictures += nodes.image(it, picture.description?.takeIf { d -> d.isNotBlank() }) }
                }
            }
            val words = flow.done()
            val level = headingLevel(paragraph)
            val blocks = if (level != null) {
                val inline = words.firstOrNull()?.path("content")?.let { list -> (0 until list.size()).map { list.get(it) as ObjectNode } }.orEmpty()
                listOf(nodes.heading(level, inline)) + words.drop(1)
            } else if (isCode(paragraph)) {
                listOf(nodes.code(paragraph.text))
            } else {
                words
            }
            return Read(blocks + pictures, breaks)
        }

        private fun marks(run: XWPFRun): List<ImportMark> {
            val out = mutableListOf<ImportMark>()
            if (run.isBold) out += ImportMark("bold")
            if (run.isItalic) out += ImportMark("italic")
            if (run.underline != UnderlinePatterns.NONE) out += ImportMark("underline")
            if (run.isStrikeThrough || run.isDoubleStrikeThrough) out += ImportMark("strike")
            if (run.fontFamily?.let { family -> MONOSPACE.any { family.contains(it, ignoreCase = true) } } == true) out += ImportMark("code")
            if (run is XWPFHyperlinkRun) run.getHyperlink(docx)?.url?.takeIf { it.isNotBlank() }?.let { out += ImportMark("link", it) }
            return out
        }

        /** The level a paragraph's style makes it a heading of, or null for an ordinary paragraph. */
        private fun headingLevel(paragraph: XWPFParagraph): Int? {
            val id = paragraph.style ?: return null
            val name = docx.styles?.getStyle(id)?.name ?: id
            HEADING.find(name)?.let { return it.groupValues[1].toInt().coerceIn(1, 6) }
            return when (name.lowercase().replace(" ", "")) {
                "title" -> 1
                "subtitle" -> 2
                else -> null
            }
        }

        private fun isCode(paragraph: XWPFParagraph): Boolean {
            val id = paragraph.style ?: return false
            val name = (docx.styles?.getStyle(id)?.name ?: id).lowercase()
            return name.contains("code") || name.contains("source text") || name == "html preformatted"
        }

        private fun table(table: XWPFTable): ObjectNode? {
            val rows = table.rows.mapIndexed { at, row ->
                row.tableCells.map { cell ->
                    val span = cell.ctTc?.tcPr?.gridSpan?.`val`?.toInt() ?: 1
                    nodes.cell(header = at == 0 && row.isRepeatHeader, blocks = blocks(cell.bodyElements), colspan = span)
                }
            }
            return nodes.table(rows)
        }
    }

    private companion object {
        val HEADING = Regex("^heading\\s*([1-9])$", RegexOption.IGNORE_CASE)
        val MONOSPACE = listOf("Courier", "Consolas", "Mono", "Menlo", "Monaco")
    }
}
