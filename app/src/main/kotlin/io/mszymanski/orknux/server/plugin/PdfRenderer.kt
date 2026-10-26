package io.mszymanski.orknux.server.plugin

import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * One page of a PDF, drawn as a PNG.
 *
 * ## Why a server does this
 *
 * An agent that writes a document cannot look at it. A PDF is bytes; a model
 * that can see reads pictures, and the two are different things in a request -
 * so an agent handed its own output in base64 knows only that it exists. It
 * says "the report is ready" because that is what it did, not because that is
 * what came out, and a document with its diagram off the page or its table cut
 * in half is reported as finished.
 *
 * This is the other half of [SvgRenderer], and it is there for the same reason:
 * the sandbox cannot do it. Rasterising a PDF means a font stack, a colour
 * model and a path renderer; there is no such thing in pure JavaScript worth
 * having, and GraalJS here has no WebAssembly to borrow one through.
 *
 * ## What it will draw
 *
 * One page at a time, of a document small enough to hold in memory, at a width
 * a screen would show. A PDF is an untrusted document format with an
 * embedded-file model and an encryption model, and PDFBox is a parser like any
 * other - so the bounds are here rather than assumed, and what arrives is
 * always something this installation made a moment ago.
 */
@Component
class PdfRenderer {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @param pdf the document itself.
     * @param page which page, counting from one - the way a person counts them,
     *   and the way an agent will say it.
     * @param width how wide the picture should be in pixels, or null for
     *   something a screen can read.
     */
    fun png(pdf: ByteArray, page: Int, width: Int?): Drawing = png(PdfSource.of(pdf), page, width)

    /** The same, from wherever the document is - a file on disk is drawn without reading it whole. */
    fun png(pdf: PdfSource, page: Int, width: Int?): Drawing {
        if (pdf.size == 0L) return Drawing.Refused("there is nothing to draw: the pdf is empty")
        if (pdf.inMemory && pdf.size > MOST_BYTES) {
            // The cap in megabytes, as the contract's table asks: the caller is
            // usually a model, and a number it can act on beats "too large".
            return Drawing.Refused("that pdf is larger than ${MOST_BYTES / (1024 * 1024)} MB, which is the most this draws")
        }
        if (page < 1) return Drawing.Refused("pages are counted from 1")
        if (width != null && (width < MOST_NARROW || width > MOST_WIDE)) {
            return Drawing.Refused("a width has to be between $MOST_NARROW and $MOST_WIDE pixels")
        }

        return try {
            pdf.load().use { document ->
                /*
                 * An encrypted document is refused rather than opened.
                 *
                 * PDFBox will decrypt one with an empty password, which is how
                 * most "protected" PDFs are made - and doing that quietly would
                 * mean this server stepping around a restriction somebody set.
                 * What reaches here is a document this installation made, so
                 * the honest answer is to say what it is.
                 */
                if (document.isEncrypted) {
                    return Drawing.Refused("that pdf is encrypted, and this does not open one")
                }
                if (page > document.numberOfPages) {
                    return Drawing.Refused(
                        "that pdf has ${document.numberOfPages} page(s), and page $page was asked for",
                    )
                }

                /*
                 * Sized by width rather than by dpi, because a width is what
                 * the caller can reason about: a model is shown a picture and
                 * the question is how much of the page it can read, not how
                 * many dots an inch of it has.
                 */
                val scale = if (width == null) {
                    // 96 dpi against the PDF's own 72, which is what
                    // RENDERING.md names for a call that asks for no width.
                    SCREEN_DPI / PDF_DPI
                } else {
                    val box = document.getPage(page - 1).cropBox
                    (width.toFloat() / box.width.coerceAtLeast(1f)).coerceIn(MOST_SHRUNK, MOST_BLOWN_UP)
                }

                val image = PDFRenderer(document).renderImage(page - 1, scale, ImageType.RGB)
                val out = ByteArrayOutputStream()
                ImageIO.write(image, "png", out)
                val bytes = out.toByteArray()

                if (bytes.isEmpty()) {
                    Drawing.Refused("the renderer produced nothing for page $page")
                } else {
                    Drawing.Drawn(bytes, image.width, image.height, document.numberOfPages)
                }
            }
        } catch (failure: OutOfMemoryError) {
            // A page past what the bounds caught. Reported rather than allowed
            // to take a request thread down with it.
            log.warn("a pdf page asked for more memory than this server would give it", failure)
            Drawing.Refused("that page asks for a picture too large to draw")
        } catch (failure: java.io.IOException) {
            /*
             * Said as what it is rather than in the parser's words, which the
             * contract's table asks for: "End-of-File, expected line at offset
             * 16" is PDFBox talking to itself, and the caller is usually a
             * model deciding what to do next.
             */
            log.warn("a pdf could not be read", failure)
            Drawing.Refused("that is not a pdf, or it is damaged: the document could not be read")
        } catch (failure: Exception) {
            log.warn("a pdf page could not be drawn", failure)
            Drawing.Refused("could not draw that pdf: ${failure.message ?: failure.javaClass.simpleName}")
        }
    }

    /**
     * What a PDF says, as HTML.
     *
     * The other question an agent has about a document it just made, and the
     * one a picture answers badly: *what does it say*. A page drawn as a PNG
     * is for looking at - is the table cut in half, did the diagram land on
     * the page - and reading a thousand words back out of an image costs a
     * vision model a thousand words' worth of tokens and some guessing. The
     * text is already in the file.
     *
     * **Text, laid out; not a reproduction.** What comes back is the document's
     * words in reading order, a paragraph per block and a heading per page. It
     * is not the page's design: columns, tables and anything positioned rather
     * than written are flattened into the order PDFBox reads them in. Where the
     * layout is the question, draw the page and look at it.
     *
     * @param pdf the document itself.
     * @param from the first page to read, counting from one; null starts at the
     *   beginning.
     * @param to the last page to read; null reads to the end. A range is here
     *   because a caller checking one section of a long report should not have
     *   to hold the whole of it.
     * @param most how much HTML one read answers. The read stops at the last
     *   whole page under it and says which page comes next, rather than
     *   refusing a document for being long - issue #664. The first page asked
     *   for is always read, however long, because a page is the smallest part
     *   there is to ask for.
     */
    fun html(pdf: ByteArray, from: Int?, to: Int?, most: Int = MOST_CHARACTERS): Reading =
        html(PdfSource.of(pdf), from, to, most)

    /**
     * How many pages a document has, or null where it cannot be opened - an
     * encrypted one included, which the readers refuse. Read off the page tree
     * without reading a page.
     */
    fun pages(pdf: PdfSource): Int? = runCatching {
        pdf.load().use { document -> document.numberOfPages.takeUnless { document.isEncrypted } }
    }.getOrNull()

    /**
     * The same, from wherever the document is. A file on disk is opened in
     * place and only the pages asked for are read, so a range of a long
     * attachment costs that range and not the document.
     */
    fun html(pdf: PdfSource, from: Int?, to: Int?, most: Int = MOST_CHARACTERS): Reading {
        if (pdf.size == 0L) return Reading.Refused("there is nothing to read: the pdf is empty")
        if (pdf.inMemory && pdf.size > MOST_BYTES) {
            return Reading.Refused("that pdf is larger than ${MOST_BYTES / (1024 * 1024)} MB, which is the most this reads")
        }
        if (from != null && from < 1) return Reading.Refused("pages are counted from 1")
        if (from != null && to != null && to < from) {
            return Reading.Refused("the last page asked for ($to) comes before the first ($from)")
        }

        return try {
            pdf.load().use { document ->
                // Refused rather than opened with an empty password, for the
                // reason `png` gives at length.
                if (document.isEncrypted) {
                    return Reading.Refused("that pdf is encrypted, and this does not open one")
                }

                val pages = document.numberOfPages
                val first = from ?: 1
                if (first > pages) {
                    return Reading.Refused("that pdf has $pages page(s), and page $first was asked for")
                }
                val last = (to ?: pages).coerceAtMost(pages)

                val written = StringBuilder()
                var characters = 0
                var textless = true
                var read = last
                var next: Int? = null
                val figures = mutableListOf<Int>()
                for (page in first..last) {
                    val stripper = PDFTextStripper().apply {
                        startPage = page
                        endPage = page
                        // Reading order, where the document says what its order
                        // is. Without this a two-column page comes back as one
                        // line of the left column, one of the right, all the way
                        // down - which reads as nonsense rather than as text.
                        sortByPosition = true
                    }
                    val said = stripper.getText(document)
                    val section = StringBuilder()
                    section.append("<section data-page=\"").append(page).append("\">\n")
                    section.append("<h2>Page ").append(page).append("</h2>\n")
                    /*
                     * A paragraph per block of lines, which is what a blank
                     * line means in what PDFBox hands back. Inside one, the
                     * single newlines are where the page wrapped rather than
                     * where a sentence ended, so they become spaces: a reader
                     * asked for the text, not for the column width.
                     */
                    said.split(Regex("\n\\s*\n")).forEach { block ->
                        val paragraph = block.trim().replace(Regex("\\s*\n\\s*"), " ")
                        if (paragraph.isNotEmpty()) {
                            section.append("<p>").append(escaped(paragraph)).append("</p>\n")
                        }
                    }
                    section.append("</section>\n")

                    // Stopped between pages, never inside one: a page cut part-way reads as a page that ended there.
                    if (page > first && written.length + section.length > most) {
                        read = page - 1
                        next = page
                        break
                    }
                    written.append(section)
                    characters += said.length
                    if (said.isNotBlank()) textless = false
                    if (pictures(document.getPage(page - 1).resources, 0) > 0) figures += page
                }

                Reading.Read(
                    written.toString(), pages = pages, from = first, to = read, characters = characters,
                    textless = textless, next = next, figures = figures,
                )
            }
        } catch (failure: java.io.IOException) {
            log.warn("a pdf could not be read", failure)
            Reading.Refused("that is not a pdf, or it is damaged: the document could not be read")
        } catch (failure: Exception) {
            log.warn("a pdf could not be read as text", failure)
            Reading.Refused("could not read that pdf: ${failure.message ?: failure.javaClass.simpleName}")
        }
    }

    /**
     * How many pictures a page's resources hold, forms followed a few levels
     * down. Issue #665: a chart on a page is not in its text, and a model told
     * nothing about it answers from the words around it as if they were all.
     *
     * Counted off the resources rather than the drawing, so a picture a page
     * names and never draws counts too - rare, and the cost is a model told to
     * look at a page that turns out to be plain. A chart drawn as lines rather
     * than as an image is not found at all.
     */
    private fun pictures(resources: PDResources?, depth: Int): Int {
        if (resources == null || depth > PICTURE_DEPTH) return 0
        return resources.xObjectNames.sumOf { name ->
            when (val drawn = runCatching { resources.getXObject(name) }.getOrNull()) {
                is PDImageXObject -> 1
                is PDFormXObject -> pictures(drawn.resources, depth + 1)
                else -> 0
            }
        }
    }

    /**
     * The five characters that would otherwise make somebody's document into
     * markup.
     *
     * The text arrives from a file this installation was handed, so it is
     * untrusted in exactly the way any upload is: a PDF whose text is
     * `<script>` must come back as characters, not as a tag. The quotes go too
     * - what this produces is pasted into attributes by things downstream of
     * it, and escaping four and a half characters is a rule nobody remembers.
     */
    private fun escaped(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    /** What came of reading a PDF: its text as HTML, or why there is none. */
    sealed interface Reading {

        data class Read(
            val html: String,
            /** The document's own page count, not how many were read. */
            val pages: Int,
            val from: Int,
            val to: Int,
            val characters: Int,
            /** Whether no page read carries any text: pictures only, as a scan's are. */
            val textless: Boolean = false,
            /** The page to read on from, where the read stopped before the last page asked for; null where it did not. */
            val next: Int? = null,
            /** The pages read that hold pictures, whose content the text does not carry. */
            val figures: List<Int> = emptyList(),
        ) : Reading

        data class Refused(val reason: String) : Reading
    }

    sealed interface Drawing {

        data class Drawn(val png: ByteArray, val width: Int, val height: Int, val pages: Int) : Drawing

        /** Said back to the caller as it stands, so it knows what to do differently. */
        data class Refused(val reason: String) : Drawing
    }

    private companion object {
        /**
         * As large a document as this holds in memory.
         *
         * The number `http.upload` already caps at, which RENDERING.md picks
         * on purpose: one bound to remember rather than two.
         */
        const val MOST_BYTES = 10 * 1024 * 1024

        /** What a page is drawn at when nobody asked for a width: 96 dpi. */
        const val SCREEN_DPI = 96f
        const val PDF_DPI = 72f

        /**
         * As much text as this hands back at once, where the caller names no
         * bound of its own. `pdf_read` passes `pdf.read.characters`, which an
         * installation sets; this is that setting's default.
         */
        const val MOST_CHARACTERS = 200_000

        /** How far forms inside forms are followed looking for pictures: a format's nesting, not a behaviour. */
        const val PICTURE_DEPTH = 4

        const val MOST_NARROW = 64
        const val MOST_WIDE = 4096

        /**
         * And what the scale is held to whatever the page says it is.
         *
         * A PDF page can declare a crop box of almost any size, so a width in
         * pixels can ask for a scale of five hundred. These are the bounds on
         * the picture that comes out rather than on the number that went in.
         */
        const val MOST_SHRUNK = 0.05f
        const val MOST_BLOWN_UP = 8f
    }
}
