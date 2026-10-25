package io.mszymanski.orknux.server.plugin

import org.apache.pdfbox.Loader
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
    fun png(pdf: ByteArray, page: Int, width: Int?): Drawing {
        if (pdf.isEmpty()) return Drawing.Refused("there is nothing to draw: the pdf is empty")
        if (pdf.size > MOST_BYTES) {
            // The cap in megabytes, as the contract's table asks: the caller is
            // usually a model, and a number it can act on beats "too large".
            return Drawing.Refused("that pdf is larger than ${MOST_BYTES / (1024 * 1024)} MB, which is the most this draws")
        }
        if (page < 1) return Drawing.Refused("pages are counted from 1")
        if (width != null && (width < MOST_NARROW || width > MOST_WIDE)) {
            return Drawing.Refused("a width has to be between $MOST_NARROW and $MOST_WIDE pixels")
        }

        return try {
            Loader.loadPDF(pdf).use { document ->
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
     */
    fun html(pdf: ByteArray, from: Int?, to: Int?): Reading {
        if (pdf.isEmpty()) return Reading.Refused("there is nothing to read: the pdf is empty")
        if (pdf.size > MOST_BYTES) {
            return Reading.Refused("that pdf is larger than ${MOST_BYTES / (1024 * 1024)} MB, which is the most this reads")
        }
        if (from != null && from < 1) return Reading.Refused("pages are counted from 1")
        if (from != null && to != null && to < from) {
            return Reading.Refused("the last page asked for ($to) comes before the first ($from)")
        }

        return try {
            Loader.loadPDF(pdf).use { document ->
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
                    characters += said.length
                    if (said.isNotBlank()) textless = false
                    if (characters > MOST_CHARACTERS) {
                        return Reading.Refused(
                            "that pdf holds more than ${MOST_CHARACTERS / 1000}k characters of text, which is more " +
                                "than this reads at once; ask for a range of pages",
                            tooLong = true,
                        )
                    }

                    written.append("<section data-page=\"").append(page).append("\">\n")
                    written.append("<h2>Page ").append(page).append("</h2>\n")
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
                            written.append("<p>").append(escaped(paragraph)).append("</p>\n")
                        }
                    }
                    written.append("</section>\n")
                }

                Reading.Read(written.toString(), pages = pages, from = first, to = last, characters = characters, textless = textless)
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
        ) : Reading

        /** [tooLong] where the text was refused for its length, which a narrower read or a search answers. */
        data class Refused(val reason: String, val tooLong: Boolean = false) : Reading
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
         * As much text as this hands back at once.
         *
         * A model reads the answer, and a hundred pages of a contract is not a
         * thing to put in one turn - so the refusal names the number and says
         * to ask for a range, which is a sentence the caller can act on.
         */
        const val MOST_CHARACTERS = 200_000

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
