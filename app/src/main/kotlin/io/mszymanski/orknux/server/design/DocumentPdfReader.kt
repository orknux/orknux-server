package io.mszymanski.orknux.server.design

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
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
 * was empty. Lists and tables are still not guessed at. The pictures each page
 * holds come after that page's words.
 */
class DocumentPdfReader(mapper: ObjectMapper) {

    private val nodes = DocumentNodes(mapper)

    /** One printed line: its words, where its top is, how tall, and what it is set in. */
    private data class Line(val text: String, val page: Int, val top: Float, val bottom: Float, val size: Float, val bold: Boolean)

    fun read(bytes: ByteArray, keep: PictureKeeper): ObjectNode {
        Loader.loadPDF(bytes).use { pdf ->
            val blocks = mutableListOf<ObjectNode>()
            val pages = (1..pdf.numberOfPages).map { page -> linesOf(pdf, page) }
            val all = pages.flatten()
            val body = bodySize(all)
            val spacing = lineSpacing(all, body)
            val headingSizes = all.map { round(it.size) }.filter { it >= body * HEADING_RATIO }.distinct().sortedDescending()

            var open: MutableList<Line> = mutableListOf()
            fun flush() {
                if (open.isEmpty()) return
                blocks += blockOf(open, body, headingSizes)
                open = mutableListOf()
            }
            for ((index, lines) in pages.withIndex()) {
                val page = index + 1
                for (line in lines) {
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
    private fun linesOf(pdf: org.apache.pdfbox.pdmodel.PDDocument, page: Int): List<Line> {
        val lines = mutableListOf<Line>()
        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                val letters = textPositions.filter { !it.unicode.isNullOrBlank() }
                if (text.isBlank() || letters.isEmpty()) return
                val size = letters.groupingBy { round(it.fontSizeInPt) }.eachCount().maxBy { it.value }.key
                val bold = letters.count { boldFont(it) } * 2 > letters.size
                val top = letters.minOf { it.yDirAdj - it.heightDir }
                val bottom = letters.maxOf { it.yDirAdj }
                val previous = lines.lastOrNull()
                // A line the stripper hands over in pieces - words in another font - is one line again.
                if (previous != null && previous.page == page && abs(previous.bottom - bottom) < 1f) {
                    lines[lines.lastIndex] = previous.copy(text = previous.text + " " + text.trim(), size = maxOf(previous.size, size))
                } else {
                    lines += Line(text.trim(), page, top, bottom, size, bold)
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

    private fun boldFont(position: TextPosition): Boolean {
        val font = position.font ?: return false
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
    }
}
