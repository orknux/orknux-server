package io.mszymanski.orknux.server.design

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.text.PDFTextStripper
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * A PDF read into a document: its words, through PDFBox, the library the
 * server already reads and writes PDFs with.
 *
 * A PDF is a page of placed glyphs, not a text with a structure, so what can
 * honestly come back is its paragraphs - PDFBox's text stripper finds where
 * one ends - with the lines a page wrapped joined again, and the pictures each
 * page holds, after that page's words. Headings, lists and tables are not
 * guessed at from the size and place of the letters: a guess wrong is worse
 * than a paragraph somebody turns into a heading.
 */
class DocumentPdfReader(mapper: ObjectMapper) {

    private val nodes = DocumentNodes(mapper)

    fun read(bytes: ByteArray, keep: PictureKeeper): ObjectNode {
        Loader.loadPDF(bytes).use { pdf ->
            val blocks = mutableListOf<ObjectNode>()
            val stripper = PDFTextStripper().apply {
                sortByPosition = true
                paragraphStart = ""
                paragraphEnd = PARAGRAPH
                lineSeparator = "\n"
            }
            for (page in 1..pdf.numberOfPages) {
                stripper.startPage = page
                stripper.endPage = page
                val text = stripper.getText(pdf)
                text.split(PARAGRAPH, "\n\n").map { it.trim() }.filter { it.isNotEmpty() }.forEach { paragraph ->
                    val joined = paragraph.lines().map { it.trim() }.filter { it.isNotEmpty() }
                        .fold(StringBuilder()) { out, line ->
                            when {
                                out.isEmpty() -> out.append(line)
                                // A word the page hyphenated at the end of a line is one word again.
                                out.endsWith("-") && line.firstOrNull()?.isLowerCase() == true -> {
                                    out.setLength(out.length - 1)
                                    out.append(line)
                                }
                                else -> out.append(' ').append(line)
                            }
                        }.toString()
                    blocks += nodes.paragraph(listOf(nodes.text(joined, emptyList())))
                }
                val resources = pdf.getPage(page - 1).resources ?: continue
                for (name in resources.xObjectNames) {
                    val image = runCatching { resources.getXObject(name) }.getOrNull() as? PDImageXObject ?: continue
                    val png = runCatching {
                        ByteArrayOutputStream().also { ImageIO.write(image.image, "png", it) }.toByteArray()
                    }.getOrNull() ?: continue
                    keep(png, "page-$page-${name.name}")?.let { blocks += nodes.image(it, null) }
                }
            }
            return nodes.doc(blocks)
        }
    }

    private companion object {
        /** What the stripper is told to end a paragraph with, so a paragraph is found by it rather than by blank lines alone. */
        const val PARAGRAPH = " "
    }
}
