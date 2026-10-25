package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import org.apache.pdfbox.Loader
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.graphics.image.PDImage
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import technology.tabula.Cell
import technology.tabula.ObjectExtractor
import technology.tabula.RectangularTextContainer
import technology.tabula.Table
import technology.tabula.TextElement
import technology.tabula.extractors.BasicExtractionAlgorithm
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.awt.geom.Point2D
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
 * was empty. The pictures each page holds come after that page's words.
 *
 * **Lists are read off where their lines start.** A line opening with a
 * bullet - set as a letter, or painted as a small shape just left of its
 * words, which is how the server's own export draws one - or with a number,
 * letter or Roman numeral and its stop, is an item; the lines under it set in
 * to its words are the rest of it, and an item whose words start further
 * right is a list inside the one before. A line of prose may open with a dash
 * or a number, so a run of items is a list only with as many items as the
 * Tunable says, or lines hanging at its words, or a drawn bullet; otherwise
 * it goes back into its paragraph.
 *
 * **Tables are Tabula's**, the library that exists to find them, and found two
 * ways. A table drawn with ruling lines is read off its lines (Tabula's lattice
 * mode), which is as sure as a PDF gets. A table with none is only words lined
 * up in columns, and so is read only where it is plainly one: a run of lines
 * that each have a gap wider than a space - the Tunable says how wide - is
 * handed to Tabula's stream mode, and what comes back is kept as a table only
 * with enough rows and columns, nearly every row filled in every column - or,
 * where a cell is left empty, each column's words lined up down the rows -
 * short cells (a page set in two columns of prose is lines with a gap too),
 * and not a list's markers down its first column. A ruled cell spanning
 * columns or rows comes back spanning them, read off the cell's own lines. Anything less stays the paragraphs it
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

    /**
     * What starts a list item: whether the list is numbered, the number it
     * says, where the item's words start across the page - which is what
     * says how deep it is nested - and its first line's words without the
     * marker. [drawn] is a bullet painted as a shape rather than set as a
     * letter, which is how the server's own export and most HTML renderers
     * draw one.
     */
    private data class Marker(val ordered: Boolean, val number: Int, val textX: Float, val body: String, val drawn: Boolean)

    /**
     * A run of lines read as one block: a paragraph or heading, or a list item
     * when it opens with a [marker]. [joinable] says an item would have run on
     * from the paragraph before it had it not been an item, and [continues]
     * that a paragraph would have run on from the item before it had it been
     * indented to the item's words - so an item that turns out to be prose is
     * put back where it was.
     */
    private class Words(val lines: MutableList<Line>, val marker: Marker?, val joinable: Boolean = false, val continues: Boolean = false)

    /** What a document is read as before lists are made: runs of lines, and the blocks that are not words. */
    private sealed interface Part
    private data class Said(val words: Words) : Part
    private data class Placed(val node: ObjectNode) : Part

    fun read(bytes: ByteArray, keep: PictureKeeper): ObjectNode {
        Loader.loadPDF(bytes).use { pdf ->
            val units = mutableListOf<Part>()
            val printed = (1..pdf.numberOfPages).map { page -> linesOf(pdf, page) }
            val all = printed.flatten()
            val body = bodySize(all)
            val spacing = lineSpacing(all, body)
            val headingSizes = all.map { round(it.size) }.filter { it >= body * HEADING_RATIO }.distinct().sortedDescending()

            var open: Words? = null
            fun flush() {
                open?.takeIf { it.lines.isNotEmpty() }?.let { units += Said(it) }
                open = null
            }
            // Not closed: closing it closes the document, which is read again below for its pictures.
            val extractor = ObjectExtractor(pdf)
            for ((index, lines) in printed.withIndex()) {
                val page = index + 1
                val dots = runCatching { dotsOn(pdf, page) }.getOrDefault(emptyList())
                for (item in withTables(extractor, page, lines)) {
                    if (item is Found) {
                        flush()
                        units += Placed(item.table)
                        continue
                    }
                    val line = (item as Printed).line
                    val last = open
                    val runsOn = last != null && !breaks(last.lines.last(), line, body, spacing)
                    val marker = markerOf(line, dots, body)
                    if (marker != null) {
                        val joinable = runsOn && last.marker == null && !isHeading(last.lines, body)
                        flush()
                        open = Words(mutableListOf(line), marker, joinable = joinable)
                        continue
                    }
                    val under = last?.marker
                    if (last != null && runsOn && under != null && left(line) < under.textX - ALIGNED) {
                        // Run on but not indented to the item's words: the item has ended, or was never one.
                        flush()
                        open = Words(mutableListOf(line), null, continues = true)
                        continue
                    }
                    if (last != null && !runsOn) flush()
                    open = (open ?: Words(mutableListOf(), null)).also { it.lines += line }
                }
                // A paragraph runs on over a page break unless it had ended a sentence; a heading never does.
                val last = open?.lines?.lastOrNull()
                if (last != null && (isHeading(open!!.lines, body) || last.text.trimEnd().endsWith('.') || last.text.trimEnd().endsWith(':'))) flush()
                val resources = pdf.getPage(page - 1).resources ?: continue
                for (name in resources.xObjectNames) {
                    val image = runCatching { resources.getXObject(name) }.getOrNull() as? PDImageXObject ?: continue
                    val png = runCatching {
                        ByteArrayOutputStream().also { ImageIO.write(image.image, "png", it) }.toByteArray()
                    }.getOrNull() ?: continue
                    flush()
                    keep(png, "page-$page-${name.name}")?.let { units += Placed(nodes.image(it, null)) }
                }
            }
            flush()
            return nodes.doc(blocksOf(units, body, headingSizes))
        }
    }

    /**
     * The document's blocks, with its list items made into lists. A run of
     * items - and the paragraphs indented under them - is a list only when it
     * is plainly one: as many items as the Tunable says, or an item whose
     * lines hang at its words rather than under its marker, or a bullet drawn
     * as a shape. Otherwise it is the prose it was, a line of which happened
     * to open with a dash or a number, and it is put back into the paragraph
     * it was read out of.
     */
    private fun blocksOf(units: List<Part>, body: Float, headingSizes: List<Float>): List<ObjectNode> {
        val out = mutableListOf<ObjectNode>()
        // The paragraph most recently put out, while it may still be run on into.
        var prose: Words? = null
        fun said(words: Words) {
            val previous = prose
            if (previous != null && (words.joinable || words.continues)) {
                previous.lines += words.lines
                out[out.lastIndex] = blockOf(previous.lines, body, headingSizes)
                return
            }
            val kept = Words(words.lines.toMutableList(), null)
            out += blockOf(kept.lines, body, headingSizes)
            prose = kept.takeUnless { isHeading(it.lines, body) }
        }
        var at = 0
        while (at < units.size) {
            val unit = units[at]
            val words = (unit as? Said)?.words
            if (words?.marker == null) {
                when (unit) {
                    is Placed -> { out += unit.node; prose = null }
                    is Said -> said(unit.words)
                }
                at++
                continue
            }
            // The run of items, and the paragraphs indented under them.
            val run = mutableListOf(words)
            var next = at + 1
            while (next < units.size) {
                val more = (units[next] as? Said)?.words ?: break
                val marker = more.marker
                if (marker == null) {
                    val deepest = run.last { it.marker != null }.marker!!
                    if (more.continues || isHeading(more.lines, body) || left(more.lines.first()) < deepest.textX - ALIGNED) break
                }
                run += more
                next++
            }
            val items = run.filter { it.marker != null }
            val plainly = items.size >= tuning[Tunable.DOCUMENT_IMPORT_LIST_ITEMS_LEAST] ||
                items.any { it.marker!!.drawn || it.lines.size > 1 }
            if (plainly) {
                out += listsOf(run)
                prose = null
            } else {
                run.forEach { said(it) }
            }
            at = next
        }
        return out
    }

    /** A run of items as lists, nested by where each item's words start: further right is a list inside the item before. */
    private fun listsOf(run: List<Words>): List<ObjectNode> {
        class Level(val textX: Float, val ordered: Boolean, val start: Int) {
            val items = mutableListOf<MutableList<ObjectNode>>()
        }
        val out = mutableListOf<ObjectNode>()
        val open = ArrayDeque<Level>()
        fun close() {
            val level = open.removeLast()
            val list = nodes.list(level.ordered, level.items.map { nodes.listItem(it) }, level.start)
            open.lastOrNull()?.items?.last()?.add(list) ?: out.add(list)
        }
        for (words in run) {
            val marker = words.marker
            if (marker == null) {
                val left = left(words.lines.first())
                while (open.size > 1 && open.last().textX > left + ALIGNED) close()
                open.last().items.last() += nodes.paragraph(listOf(nodes.text(joined(words.lines), emptyList())))
                continue
            }
            while (open.isNotEmpty() && marker.textX < open.last().textX - ALIGNED) close()
            val same = open.lastOrNull()?.takeIf { abs(it.textX - marker.textX) <= ALIGNED }
            if (same != null && same.ordered != marker.ordered) close()
            if (open.isEmpty() || abs(open.last().textX - marker.textX) > ALIGNED) {
                open.addLast(Level(marker.textX, marker.ordered, marker.number))
            }
            val text = joined(listOf(words.lines.first().copy(text = marker.body)) + words.lines.drop(1))
            open.last().items += mutableListOf(nodes.paragraph(listOf(nodes.text(text, emptyList()))))
        }
        while (open.isNotEmpty()) close()
        return out
    }

    /**
     * The line's list marker, if it opens with one: a bullet or a number set
     * as letters and followed by words, or a bullet drawn just left of its
     * first letter. A line set as large as a heading is a numbered heading,
     * not an item.
     */
    private fun markerOf(line: Line, dots: List<Dot>, body: Float): Marker? {
        if (line.pieces.isEmpty() || line.size >= body * HEADING_RATIO) return null
        val letters = line.pieces.flatMap { it.letters }
        val left = letters.minOf { it.first }
        val middle = (line.top + line.bottom) / 2
        val drawn = dots.any { dot ->
            dot.right <= left + EDGE && left - dot.right <= line.size * 2 && middle >= dot.top - EDGE && middle <= dot.bottom + EDGE &&
                dot.width <= line.size * DOT_LARGEST && dot.height <= line.size * DOT_LARGEST
        }
        if (drawn) return Marker(ordered = false, number = 1, textX = left, body = line.text, drawn = true)
        val found = ITEM.find(line.text) ?: return null
        val sign = found.groupValues[1]
        val textX = letters.getOrNull(sign.count { !it.isWhitespace() })?.first ?: return null
        val rest = line.text.substring(found.range.last + 1)
        val number = sign.trim('(', ')', '.')
        if (BULLETS.contains(sign)) return Marker(ordered = false, number = 1, textX = textX, body = rest, drawn = false)
        return Marker(ordered = true, number = numberOf(number), textX = textX, body = rest, drawn = false)
    }

    /** What a list marker counts as: a number, a letter by its place in the alphabet, or a Roman numeral. */
    private fun numberOf(sign: String): Int {
        sign.toIntOrNull()?.let { return it }
        val lower = sign.lowercase()
        if (lower.length == 1 && lower != "i") return lower[0] - 'a' + 1
        var total = 0
        var previous = 0
        for (letter in lower.reversed()) {
            val value = ROMAN[letter] ?: return 1
            total += if (value < previous) -value else value
            previous = maxOf(previous, value)
        }
        return total.coerceAtLeast(1)
    }

    /** A small shape painted on a page - where a bullet drawn rather than set would be - in the coordinates the lines are measured in. */
    private data class Dot(val top: Float, val left: Float, val bottom: Float, val right: Float) {
        val width get() = right - left
        val height get() = bottom - top
    }

    /**
     * The small, roughly square shapes a page paints: a filled circle or
     * square, or an outlined one. Lines, rules and a cell's borders are long
     * and thin and are not among them.
     */
    private fun dotsOn(pdf: PDDocument, page: Int): List<Dot> {
        val sheet = pdf.getPage(page - 1)
        val box = sheet.cropBox
        val found = mutableListOf<Dot>()
        val engine = object : PDFGraphicsStreamEngine(sheet) {
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            var current = Point2D.Float()
            fun take(x: Float, y: Float) {
                minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
                current = Point2D.Float(x, y)
            }
            fun reset() { minX = Float.MAX_VALUE; minY = Float.MAX_VALUE; maxX = -Float.MAX_VALUE; maxY = -Float.MAX_VALUE }
            fun painted() {
                if (minX <= maxX) {
                    val width = maxX - minX
                    val height = maxY - minY
                    if (width > 0.5f && height > 0.5f && width <= DOT_POINTS && height <= DOT_POINTS && width / height in 0.5f..2f) {
                        found += Dot(box.upperRightY - maxY, minX - box.lowerLeftX, box.upperRightY - minY, maxX - box.lowerLeftX)
                    }
                }
                reset()
            }
            override fun appendRectangle(p0: Point2D, p1: Point2D, p2: Point2D, p3: Point2D) {
                listOf(p0, p1, p2, p3).forEach { take(it.x.toFloat(), it.y.toFloat()) }
            }
            override fun drawImage(pdImage: PDImage) {}
            override fun clip(windingRule: Int) {}
            override fun moveTo(x: Float, y: Float) = take(x, y)
            override fun lineTo(x: Float, y: Float) = take(x, y)
            override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
                take(x1, y1); take(x2, y2); take(x3, y3)
            }
            override fun getCurrentPoint(): Point2D = current
            override fun closePath() {}
            override fun endPath() = reset()
            override fun strokePath() = painted()
            override fun fillPath(windingRule: Int) = painted()
            override fun fillAndStrokePath(windingRule: Int) = painted()
            override fun shadingFill(shadingName: COSName?) {}
        }
        engine.processPage(sheet)
        return found
    }

    /** Where a line's first letter is across the page. */
    private fun left(line: Line): Float = line.pieces.minOfOrNull { it.left } ?: 0f

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
        val spots = pruned(if (ruled) spanned(table) else table.rows.flatMapIndexed { row, cells ->
            cells.mapIndexed { column, cell -> Spot(cell, row, column) }
        })
        if (spots.isEmpty()) return null
        val rows = spots.maxOf { it.row + it.rows }
        val columns = spots.maxOf { it.column + it.columns }
        if (rows < tuning[Tunable.DOCUMENT_IMPORT_TABLE_ROWS_LEAST]) return null
        if (columns < tuning[Tunable.DOCUMENT_IMPORT_TABLE_COLUMNS_LEAST]) return null
        // Every place in the grid a cell covers; one no cell covers is an empty cell of its own, so the table is whole.
        val covered = Array(rows) { arrayOfNulls<Spot>(columns) }
        spots.forEach { spot -> for (r in spot.row until spot.row + spot.rows) for (c in spot.column until spot.column + spot.columns) covered[r][c] = spot }
        val whole = spots + (0 until rows).flatMap { r -> (0 until columns).filter { covered[r][it] == null }.map { Spot(null, r, it) } }
        // Each place by the cell that starts there, null where a cell spanning into it does not.
        val grid = (0 until rows).map { r -> (0 until columns).map { c -> covered[r][c]?.takeIf { it.row == r && it.column == c }?.cell } }
        if (!ruled && !plainlyTabular(grid)) return null
        val byRow = whole.groupBy { it.row }
        val header = headed(byRow.toSortedMap().values.map { row -> row.mapNotNull { it.cell } }, ruled, sheet)
        return nodes.table(
            (0 until rows).map { r ->
                byRow[r].orEmpty().sortedBy { it.column }.map { spot ->
                    val text = spot.cell?.let { cellText(it) }.orEmpty()
                    val words = if (text.isEmpty()) emptyList() else listOf(nodes.text(text, emptyList()))
                    nodes.cell(header = header && r == 0, blocks = listOf(nodes.paragraph(words)), colspan = spot.columns, rowspan = spot.rows)
                }
            },
        )
    }

    /** A cell of a table, at its row and column, spanning so many of each. */
    private data class Spot(val cell: RectangularTextContainer<*>?, val row: Int, val column: Int, val rows: Int = 1, val columns: Int = 1)

    /**
     * A ruled table's cells where its lines put them. Tabula reads a cell
     * that spans two columns as one cell and fills the place beside it with an
     * empty one, so the grid is rebuilt from the cells themselves: every edge
     * of every cell is a line of the grid, and a cell spans the columns and
     * rows between its own edges.
     */
    private fun spanned(table: Table): List<Spot> {
        val cells = table.rows.flatten().filterIsInstance<Cell>().distinct()
        if (cells.isEmpty()) return emptyList()
        fun edges(of: List<Float>): List<Float> = of.sorted().fold(mutableListOf()) { kept, at ->
            if (kept.isEmpty() || at - kept.last() > EDGE) kept.add(at)
            kept
        }
        val across = edges(cells.flatMap { listOf(it.left, it.right) })
        val down = edges(cells.flatMap { listOf(it.top, it.bottom) })
        fun index(edges: List<Float>, at: Float) = edges.indices.minBy { abs(edges[it] - at) }
        return cells.mapNotNull { cell ->
            val column = index(across, cell.left)
            val row = index(down, cell.top)
            val columns = index(across, cell.right) - column
            val rows = index(down, cell.bottom) - row
            if (columns < 1 || rows < 1) null else Spot(cell, row, column, rows, columns)
        }
    }

    /**
     * The table without its rows and columns that hold no words - a ruled
     * frame around a block is not a table of empty cells. A row or column
     * goes where every cell with words in it spans past it as well; an empty
     * cell held only there goes with it, and one spanning past it is a place
     * narrower.
     */
    private fun pruned(spots: List<Spot>): List<Spot> {
        if (spots.isEmpty()) return spots
        val worded = spots.associateWith { spot -> spot.cell?.let { cellText(it).isNotEmpty() } == true }
        fun keep(count: Int, from: (Spot) -> Int, span: (Spot) -> Int): BooleanArray {
            val kept = BooleanArray(count) { true }
            fun left(spot: Spot) = (from(spot) until from(spot) + span(spot)).count { kept[it] }
            for (at in 0 until count) {
                val holding = spots.filter { worded[it] == true && at in from(it) until from(it) + span(it) }
                if (holding.all { left(it) > 1 }) kept[at] = false
            }
            return kept
        }
        val rows = keep(spots.maxOf { it.row + it.rows }, { it.row }, { it.rows })
        val columns = keep(spots.maxOf { it.column + it.columns }, { it.column }, { it.columns })
        fun moved(kept: BooleanArray, from: Int) = (0 until from).count { kept[it] }
        fun spanning(kept: BooleanArray, from: Int, span: Int) = (from until from + span).count { kept[it] }
        return spots.mapNotNull { spot ->
            val r = spanning(rows, spot.row, spot.rows)
            val c = spanning(columns, spot.column, spot.columns)
            if (r == 0 || c == 0) null else spot.copy(row = moved(rows, spot.row), column = moved(columns, spot.column), rows = r, columns = c)
        }
    }

    /**
     * Whether words lined up with no ruling lines are a table: nearly every
     * row full - or, where a cell is left empty, every column's words lined
     * up down the rows - short cells, and not a list.
     */
    private fun plainlyTabular(cells: List<List<RectangularTextContainer<*>?>>): Boolean {
        val full = cells.count { row -> row.all { it != null && cellText(it).isNotEmpty() } }
        if (full * 100 < cells.size * tuning[Tunable.DOCUMENT_IMPORT_TABLE_ROWS_FULL_PERCENT] && !aligned(cells)) return false
        val words = cells.flatten().mapNotNull { it?.let { cell -> cellText(cell) } }.filter { it.isNotEmpty() }
        if (words.sumOf { it.length } > words.size * tuning[Tunable.DOCUMENT_IMPORT_TABLE_CELL_CHARACTERS]) return false
        val first = cells.mapNotNull { row -> row.firstOrNull()?.let { cellText(it) } }.filter { it.isNotEmpty() }
        return !first.all { LIST_MARKER.matches(it) }
    }

    /**
     * Whether each column's words stand in one line down the page - their
     * left edges, their right edges or their middles together, as a table
     * sets them - with words in at least two rows of every column and every
     * row; prose broken by a gap is not lined up like that.
     */
    private fun aligned(cells: List<List<RectangularTextContainer<*>?>>): Boolean {
        val width = cells.maxOf { it.size }
        fun worded(cell: RectangularTextContainer<*>?) = cell != null && cellText(cell).isNotEmpty()
        if (cells.any { row -> row.count(::worded) < 2 }) return false
        return (0 until width).all { at ->
            val column = cells.mapNotNull { row -> row.getOrNull(at)?.takeIf(::worded) }
            fun together(of: List<Float>) = of.max() - of.min() <= ALIGNED
            column.size >= 2 && (together(column.map { it.left }) || together(column.map { it.right }) ||
                together(column.map { (it.left + it.right) / 2 }))
        }
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

        /** How far apart, in points, two edges may be and still be one line down the page: rounding, not a setting. */
        const val ALIGNED = 2.5f

        /** The largest a bullet painted as a shape is, against the type size of its line, and in points at all. */
        const val DOT_LARGEST = 0.7f
        const val DOT_POINTS = 12f

        val SPACES = Regex("\\s+")

        /**
         * The letters a bullet is set in: the usual ones, a dash or an
         * asterisk, and the private-use bullets of Symbol and Wingdings that
         * Word's PDFs carry.
         */
        val BULLETS = setOf("•", "◦", "▪", "▫", "‣", "∙", "·", "○", "●", "■", "□", "*", "–", "—", "-", "", "", "", "")

        /** A first column all of these is a list's markers, not a table's first column. */
        val LIST_MARKER = Regex("^([•◦▪▫‣∙·○●■□*–—-]|\\(?[0-9]{1,3}[.)]|\\(?[a-zA-Z][.)]|\\(?[ivxlcIVXLC]{1,5}[.)])$")

        /** A line opening with a list's marker - a bullet, or a number, letter or Roman numeral with its stop - and then words. */
        val ITEM = Regex(
            "^\\s*(" + BULLETS.joinToString("|") { Regex.escape(it) } +
                "|\\(?[0-9]{1,3}[.)]|\\(?[a-zA-Z][.)]|\\(?[ivxlcIVXLC]{1,5}[.)])\\s+(?=\\S)",
        )

        val ROMAN = mapOf('i' to 1, 'v' to 5, 'x' to 10, 'l' to 50, 'c' to 100)
    }
}
