package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import technology.tabula.ObjectExtractor
import technology.tabula.RectangularTextContainer
import technology.tabula.Table
import technology.tabula.TextElement
import technology.tabula.extractors.BasicExtractionAlgorithm
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayOutputStream
import java.io.StringWriter
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * A PDF read into a document: its words, through PDFBox, the library the
 * server already reads and writes PDFs with.
 *
 * A PDF is a page of placed glyphs, not a text with a structure, so the
 * structure is read off where the lines sit and what they are set in. PDFBox
 * answers each line with the size and font of its letters; a paragraph ends
 * where the gap to the next line is wider than the text's own line spacing or
 * the type changes, and a block set larger than the body text - or in bold at
 * its size, and short, and not ending a sentence - is a heading, levelled by
 * size. The stripper's own paragraph marks were not enough: a research report
 * came back as one paragraph a printed line, and with no headings its outline
 * was empty. Lists are still not guessed at. The pictures each page holds come
 * after that page's words.
 *
 * **Tables are Tabula's**, the library that exists to find them, and found two
 * ways. A table drawn with ruling lines is read off its lines (Tabula's lattice
 * mode), which is as sure as a PDF gets. A table with none is only words lined
 * up in columns, and so is read only where it is plainly one: a run of lines
 * that each have a gap wider than a space - the Tunable says how wide - is
 * handed to Tabula's stream mode, and what comes back is kept as a table only
 * with enough rows and columns, nearly every row filled in every column, short
 * cells (a page set in two columns of prose is lines with a gap too), and not
 * a list's markers down its first column. Anything less stays the paragraphs it
 * would have been, because a table invented out of prose is worse than one
 * missed. The words a table holds are taken out of the page's lines, so they
 * are not said twice, and the table takes its place among the paragraphs by
 * where its top is. Its first row is header cells where it is set apart - bold
 * or larger than the rows under it, or, in a table with no other lines, ruled
 * off from them.
 */
class DocumentPdfReader(mapper: ObjectMapper, private val tuning: Tuning = Tuning.DEFAULTS) {

    private val nodes = DocumentNodes(mapper)

    /** A run of letters the stripper handed over, where it starts and ends across the page, and each letter's extent. */
    private data class Piece(val text: String, val left: Float, val right: Float, val letters: List<Pair<Float, Float>>)

    /** One printed line: its words, where its top is, how tall, and what it is set in. */
    private data class Line(
        val text: String, val page: Int, val top: Float, val bottom: Float, val size: Float, val bold: Boolean,
        val pieces: List<Piece> = emptyList(),
    )

    /** What a page holds, in the order it is read: a line, or a table found among them. */
    private sealed interface Item { val top: Float }
    private data class Printed(val line: Line) : Item { override val top get() = line.top }
    private data class Found(val table: ObjectNode, override val top: Float) : Item

    /** A table's place on the page, in the coordinates the lines are measured in. */
    private data class Area(val top: Float, val left: Float, val bottom: Float, val right: Float) {
        fun holds(x: Float, y: Float) = x >= left - EDGE && x <= right + EDGE && y >= top - EDGE && y <= bottom + EDGE
    }

    fun read(bytes: ByteArray, keep: PictureKeeper): ObjectNode {
        Loader.loadPDF(bytes).use { pdf ->
            val blocks = mutableListOf<ObjectNode>()
            val printed = (1..pdf.numberOfPages).map { page -> linesOf(pdf, page) }
            val all = printed.flatten()
            val body = bodySize(all)
            val spacing = lineSpacing(all, body)
            val headingSizes = all.map { round(it.size) }.filter { it >= body * HEADING_RATIO }.distinct().sortedDescending()

            var open: MutableList<Line> = mutableListOf()
            fun flush() {
                if (open.isEmpty()) return
                blocks += blockOf(open, body, headingSizes)
                open = mutableListOf()
            }
            // Not closed: closing it closes the document, which is read again below for its pictures.
            val extractor = ObjectExtractor(pdf)
            for ((index, lines) in printed.withIndex()) {
                val page = index + 1
                for (item in withTables(extractor, page, lines)) {
                    if (item is Found) {
                        flush()
                        blocks += item.table
                        continue
                    }
                    val line = (item as Printed).line
                    val last = open.lastOrNull()
                    if (last != null && breaks(last, line, body, spacing)) flush()
                    open += line
                }
                // A paragraph runs on over a page break unless it had ended a sentence; a heading never does.
                val last = open.lastOrNull()
                if (last != null && (isHeading(open, body) || last.text.trimEnd().endsWith('.') || last.text.trimEnd().endsWith(':'))) flush()
                val resources = pdf.getPage(page - 1).resources ?: continue
                for (name in resources.xObjectNames) {
                    val image = runCatching { resources.getXObject(name) }.getOrNull() as? PDImageXObject ?: continue
                    val png = runCatching {
                        ByteArrayOutputStream().also { ImageIO.write(image.image, "png", it) }.toByteArray()
                    }.getOrNull() ?: continue
                    flush()
                    keep(png, "page-$page-${name.name}")?.let { blocks += nodes.image(it, null) }
                }
            }
            flush()
            return nodes.doc(blocks)
        }
    }

    /** A page's lines, top to bottom, each with the size and weight most of its letters are set in. */
    private fun linesOf(pdf: PDDocument, page: Int): List<Line> {
        val lines = mutableListOf<Line>()
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                val letters = textPositions.filter { !it.unicode.isNullOrBlank() }
                if (text.isBlank() || letters.isEmpty()) return
                val size = letters.groupingBy { round(it.fontSizeInPt) }.eachCount().maxBy { it.value }.key
                val bold = letters.count { boldFont(it.font) } * 2 > letters.size
                val top = letters.minOf { it.yDirAdj - it.heightDir }
                val bottom = letters.maxOf { it.yDirAdj }
                val spans = letters.map { it.xDirAdj to it.xDirAdj + it.widthDirAdj }
                val piece = Piece(text.trim(), spans.minOf { it.first }, spans.maxOf { it.second }, spans)
                val previous = lines.lastOrNull()
                // A line the stripper hands over in pieces - words in another font - is one line again.
                if (previous != null && previous.page == page && abs(previous.bottom - bottom) < 1f) {
                    lines[lines.lastIndex] = previous.copy(
                        text = previous.text + " " + text.trim(), size = maxOf(previous.size, size), pieces = previous.pieces + piece,
                    )
                } else {
                    lines += Line(text.trim(), page, top, bottom, size, bold, listOf(piece))
                }
            }
        }.apply {
            sortByPosition = true
            startPage = page
            endPage = page
        }
        stripper.writeText(pdf, StringWriter())
        return lines
    }

    /**
     * A page's lines with the tables among them found: each table in its
     * place by its top, and its words taken out of the lines. Ruled tables
     * first, since they are surer, then unruled ones among what is left.
     */
    private fun withTables(extractor: ObjectExtractor, page: Int, lines: List<Line>): List<Item> {
        if (lines.isEmpty()) return emptyList()
        val sheet = runCatching { extractor.extract(page) }.getOrNull() ?: return lines.map(::Printed)
        val ruled = runCatching { SpreadsheetExtractionAlgorithm().extract(sheet) }.getOrDefault(emptyList())
            .mapNotNull { table -> tableOf(table, ruled = true, sheet)?.let { it to Area(table.top, table.left, table.bottom, table.right) } }
        val found = ruled.map { (node, area) -> Found(node, area.top) }.toMutableList()
        val areas = ruled.map { it.second }
        val rest = lines.mapNotNull { line -> outside(line, areas) }
        val taken = mutableSetOf<Line>()
        for (run in gappedRuns(rest, areas)) {
            val area = Area(
                run.minOf { it.top } - EDGE, run.minOf { line -> line.pieces.minOf { it.left } } - EDGE,
                run.maxOf { it.bottom } + EDGE, run.maxOf { line -> line.pieces.maxOf { it.right } } + EDGE,
            )
            val table = runCatching {
                BasicExtractionAlgorithm().extract(sheet.getArea(area.top, area.left, area.bottom, area.right)).firstOrNull()
            }.getOrNull() ?: continue
            val node = tableOf(table, ruled = false, sheet) ?: continue
            found += Found(node, area.top)
            taken += run
        }
        return (rest.filter { it !in taken }.map<Line, Item>(::Printed) + found).sortedBy { it.top }
    }

    /** The line without the words a ruled table holds; null where it held nothing else. */
    private fun outside(line: Line, tables: List<Area>): Line? {
        if (tables.isEmpty()) return line
        val middle = (line.top + line.bottom) / 2
        val kept = line.pieces.filter { piece -> tables.none { it.holds((piece.left + piece.right) / 2, middle) } }
        return when {
            kept.size == line.pieces.size -> line
            kept.isEmpty() -> null
            else -> line.copy(text = kept.joinToString(" ") { it.text }, pieces = kept)
        }
    }

    /**
     * Runs of consecutive lines each with a gap between its words as wide as
     * the Tunable says a column starts at, and no ruled table between them:
     * where an unruled table could be. A run shorter than a table may be is
     * not one.
     */
    private fun gappedRuns(lines: List<Line>, tables: List<Area>): List<List<Line>> {
        val gap = tuning[Tunable.DOCUMENT_IMPORT_TABLE_COLUMN_GAP_PERCENT] / 100f
        fun gapped(line: Line): Boolean {
            val letters = line.pieces.flatMap { it.letters }.sortedBy { it.first }
            return letters.zipWithNext().any { (a, b) -> b.first - a.second >= line.size * gap }
        }
        val runs = mutableListOf<List<Line>>()
        var open = mutableListOf<Line>()
        for (line in lines) {
            val last = open.lastOrNull()
            val between = last != null && tables.any { it.top >= last.bottom && it.bottom <= line.top }
            val wide = gapped(line)
            if (!wide || between) {
                if (open.isNotEmpty()) runs += open
                open = mutableListOf()
            }
            if (wide) open += line
        }
        if (open.isNotEmpty()) runs += open
        return runs.filter { it.size >= tuning[Tunable.DOCUMENT_IMPORT_TABLE_ROWS_LEAST] }
    }

    /**
     * What Tabula found, as a table of the document, or null where it is not
     * sure enough to be one. Rows and columns with no words in them are
     * dropped first - a ruled frame around a block is not a table of empty
     * cells.
     */
    private fun tableOf(table: Table, ruled: Boolean, sheet: technology.tabula.Page): ObjectNode? {
        val grid = table.rows.map { row -> row.map { it as RectangularTextContainer<*>? } }
            .filter { row -> row.any { it != null && cellText(it).isNotEmpty() } }
        if (grid.isEmpty()) return null
        val width = grid.maxOf { it.size }
        val columns = (0 until width).filter { at -> grid.any { row -> row.getOrNull(at)?.let { cellText(it).isNotEmpty() } == true } }
        val cells = grid.map { row -> columns.map { at -> row.getOrNull(at) } }
        if (cells.size < tuning[Tunable.DOCUMENT_IMPORT_TABLE_ROWS_LEAST]) return null
        if (columns.size < tuning[Tunable.DOCUMENT_IMPORT_TABLE_COLUMNS_LEAST]) return null
        if (!ruled && !plainlyTabular(cells)) return null
        val header = headed(cells, ruled, sheet)
        return nodes.table(
            cells.mapIndexed { at, row ->
                row.map { cell ->
                    val text = cell?.let { cellText(it) }.orEmpty()
                    val words = if (text.isEmpty()) emptyList() else listOf(nodes.text(text, emptyList()))
                    nodes.cell(header = header && at == 0, blocks = listOf(nodes.paragraph(words)))
                }
            },
        )
    }

    /** Whether words lined up with no ruling lines are a table: nearly every row full, short cells, not a list. */
    private fun plainlyTabular(cells: List<List<RectangularTextContainer<*>?>>): Boolean {
        val full = cells.count { row -> row.all { it != null && cellText(it).isNotEmpty() } }
        if (full * 100 < cells.size * tuning[Tunable.DOCUMENT_IMPORT_TABLE_ROWS_FULL_PERCENT]) return false
        val words = cells.flatten().mapNotNull { it?.let { cell -> cellText(cell) } }.filter { it.isNotEmpty() }
        if (words.sumOf { it.length } > words.size * tuning[Tunable.DOCUMENT_IMPORT_TABLE_CELL_CHARACTERS]) return false
        val first = cells.mapNotNull { row -> row.firstOrNull()?.let { cellText(it) } }.filter { it.isNotEmpty() }
        return !first.all { LIST_MARKER.matches(it) }
    }

    /**
     * Whether the first row is the table's header: its letters bold where the
     * rows under it are not, or set larger; or, in a table with no ruling lines
     * of its own, a line drawn under it and under no other row.
     */
    private fun headed(cells: List<List<RectangularTextContainer<*>?>>, ruled: Boolean, sheet: technology.tabula.Page): Boolean {
        val head = cells.first().filterNotNull().flatMap { letters(it) }
        val body = cells.drop(1).flatten().filterNotNull().flatMap { letters(it) }
        if (head.isEmpty() || body.isEmpty()) return false
        fun boldShare(of: List<TextElement>) = of.count { boldFont(it.font) }.toFloat() / of.size
        if (boldShare(head) > 0.5f && boldShare(body) <= 0.5f) return true
        if (head.map { it.fontSize }.average() - body.map { it.fontSize }.average() >= SIZE_STEP) return true
        if (ruled) return false
        val rows = cells.map { row -> row.filterNotNull().filter { cellText(it).isNotEmpty() } }
        val left = rows.flatten().minOf { it.left }
        val right = rows.flatten().maxOf { it.right }
        fun ruledUnder(at: Int): Boolean {
            val above = rows[at].maxOfOrNull { it.bottom } ?: return false
            val below = rows[at + 1].minOfOrNull { it.top } ?: return false
            return sheet.horizontalRulings.any { line ->
                line.position.toDouble() in (above - EDGE)..(below + EDGE) && line.left <= left + EDGE && line.right >= right - EDGE
            }
        }
        return ruledUnder(0) && (1 until rows.size - 1).none { ruledUnder(it) }
    }

    /** A cell's words, its lines joined as a paragraph's are. */
    private fun cellText(cell: RectangularTextContainer<*>): String =
        joined(cell.getText(true).split('\r').map { Line(it, 0, 0f, 0f, 0f, false) }).replace(SPACES, " ").trim()

    /** The letters a cell holds, through the chunks a ruled cell keeps them in. */
    private fun letters(of: RectangularTextContainer<*>): List<TextElement> = of.textElements.flatMap {
        when (it) {
            is TextElement -> listOf(it)
            is RectangularTextContainer<*> -> letters(it)
            else -> emptyList()
        }
    }.filter { !it.text.isNullOrBlank() }

    /** Whether a paragraph ends between these two lines. */
    private fun breaks(last: Line, next: Line, body: Float, spacing: Float): Boolean {
        if (next.page != last.page) return false
        if (abs(next.size - last.size) >= SIZE_STEP || next.bold != last.bold) return true
        val gap = next.top - last.bottom
        return gap > spacing * GAP_RATIO + (next.size - body).coerceAtLeast(0f)
    }

    private fun isHeading(lines: List<Line>, body: Float): Boolean {
        val size = lines.first().size
        val text = lines.joinToString(" ") { it.text }
        return size >= body * HEADING_RATIO ||
            (lines.all { it.bold } && text.length <= SHORT_HEADING && !text.trimEnd().endsWith('.'))
    }

    private fun blockOf(lines: List<Line>, body: Float, headingSizes: List<Float>): ObjectNode {
        val text = joined(lines)
        if (!isHeading(lines, body)) return nodes.paragraph(listOf(nodes.text(text, emptyList())))
        val size = round(lines.first().size)
        val level = if (size >= body * HEADING_RATIO) headingSizes.indexOf(size) + 1 else headingSizes.size + 1
        return nodes.heading(level.coerceIn(1, 6), listOf(nodes.text(text, emptyList())))
    }

    /** Lines joined into the sentence they were wrapped from; a word hyphenated at a line's end is one word again. */
    private fun joined(lines: List<Line>): String = lines.map { it.text.trim() }.filter { it.isNotEmpty() }
        .fold(StringBuilder()) { out, line ->
            when {
                out.isEmpty() -> out.append(line)
                out.endsWith("-") && line.firstOrNull()?.isLowerCase() == true -> {
                    out.setLength(out.length - 1)
                    out.append(line)
                }
                else -> out.append(' ').append(line)
            }
        }.toString()

    /** The size most of the text is set in, by letters. */
    private fun bodySize(lines: List<Line>): Float =
        lines.groupBy { round(it.size) }.maxByOrNull { (_, held) -> held.sumOf { it.text.length } }?.key ?: DEFAULT_BODY

    /** The usual gap between two lines of body text on a page, which a paragraph's end is measured against. */
    private fun lineSpacing(lines: List<Line>, body: Float): Float {
        val gaps = lines.zipWithNext()
            .filter { (a, b) -> a.page == b.page && round(a.size) == body && round(b.size) == body && b.top > a.bottom - 1f }
            .map { (a, b) -> (b.top - a.bottom).coerceAtLeast(0f) }
            .sorted()
        return if (gaps.isEmpty()) body * 0.3f else gaps[gaps.size / 2]
    }

    private fun boldFont(font: PDFont?): Boolean {
        if (font == null) return false
        val name = font.name.orEmpty().lowercase()
        return "bold" in name || "black" in name || "heavy" in name || (font.fontDescriptor?.fontWeight ?: 0f) >= 600f
    }

    private fun round(size: Float): Float = Math.round(size * 2) / 2f

    private companion object {
        /** How much larger than the body text a line is set before it is a heading. */
        const val HEADING_RATIO = 1.15f

        /** A size change smaller than this is the same type, rounded by the PDF. */
        const val SIZE_STEP = 0.75f

        /** How much wider than the text's own line gap a gap is before a paragraph has ended. */
        const val GAP_RATIO = 1.6f

        /** The longest a bold line at the body's size may be and still be read as a heading. */
        const val SHORT_HEADING = 120

        /** What the body is taken as in a PDF with no text to measure. */
        const val DEFAULT_BODY = 11f

        /** How far past a table's edge, in points, a word is still counted in it: rounding, not a setting. */
        const val EDGE = 1.5f

        val SPACES = Regex("\\s+")

        /** A first column all of these is a list's markers, not a table's first column. */
        val LIST_MARKER = Regex("^([•◦▪▫‣∙·○●■□*–—-]|\\(?[0-9]{1,3}[.)]|\\(?[a-zA-Z][.)]|\\(?[ivxlcIVXLC]{1,5}[.)])$")
    }
}
