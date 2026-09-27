package io.mszymanski.orknux.server.embedded

import com.openhtmltopdf.outputdevice.helper.ExternalResourceControlPriority
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import com.openhtmltopdf.svgsupport.BatikSVGDrawer
import org.jsoup.Jsoup
import org.jsoup.helper.W3CDom
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream

/**
 * A page laid out as a PDF. Issue #506.
 *
 * ## What this replaces
 *
 * jsPDF in a sandbox, driven by a hand-written HTML reader that understood
 * `h1`-`h3`, `p`, `br`, `hr`, `ul`/`ol`, `b`/`strong` and table cells run
 * together as text, placed at fixed points on A4. Everything else in the page
 * was dropped and its text kept. The skill that shipped with it had to say, in
 * as many words: **no CSS, no images, no links** - "a `style` attribute is not
 * an error, it simply does nothing. Do not spend a turn writing one."
 *
 * That is the whole argument for this change. A model writing a report reaches
 * for a table with borders, a heading with a colour, two columns, a page break
 * before the appendix - and every one of those was silently nothing.
 *
 * openhtmltopdf lays out real CSS 2.1 into the PDFBox document this build
 * already carried for reading them. Tables work. Page breaks work. Fonts come
 * from the JVM rather than from a base64 subset compiled into a bundle, so the
 * alphabets that used to come back blank come back.
 *
 * ## The page is prepared before it is laid out
 *
 * [PageBlocks] draws the diagrams, the charts and the pictures a page asks for
 * and hands back a page with no blocks left in it - see issue #488. So this
 * only ever sees ordinary HTML, and the two halves stay separable: the same
 * prepared page is what an agent writes to a scratchpad or zips up to send.
 *
 * ## What it will not do
 *
 * Reach anything. A document arriving here was written by a model a moment ago,
 * and a layout engine that honours an external stylesheet or a remote image is
 * a program that fetches what the document tells it to fetch. Pictures are
 * already inlined as data URIs by [PageBlocks], which leaves nothing that needs
 * resolving - so the resolver refuses every external reference rather than
 * being trusted not to meet one.
 */
@Component
class PdfWriter(private val blocks: PageBlocks) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** What came of asking for a document. */
    sealed interface Written {
        /**
         * @param problems what could not be drawn. The document is still a
         *   document: a report with a note in it beats no report.
         */
        data class Made(val pdf: ByteArray, val problems: List<String>) : Written

        data class Refused(val reason: String) : Written
    }

    /**
     * One document from one page.
     *
     * @param pictures answers the bytes behind a name the page used in an
     *   `img` src - a scratchpad, or a key something handed over.
     */
    fun fromHtml(
        html: String,
        title: String? = null,
        pictures: (String) -> PageBlocks.Picture? = { null },
    ): Written {
        val written = html.trim()
        if (written.isEmpty()) return Written.Refused("there is nothing to lay out: the page is empty")
        if (written.length > MOST_CHARS) {
            return Written.Refused(
                "that page is longer than $MOST_CHARS characters, which is the most this lays out",
            )
        }

        val prepared = blocks.draw(written, pictures)
        val page = styled(prepared.html, title)

        return try {
            val made = ByteArrayOutputStream()
            PdfRendererBuilder()
                .useFastMode()
                /*
                 * Batik draws the inline SVG the diagrams and charts became.
                 * Without it they lay out as empty boxes - which is the failure
                 * that looks like success, since the document is produced and
                 * the drawing is simply absent.
                 */
                .useSVGDrawer(BatikSVGDrawer())
                /*
                 * Nothing is fetched. Every picture is already a data URI by
                 * the time this runs, so a reference to anywhere else is a page
                 * asking this server to make a connection on its behalf.
                 */
                .useExternalResourceAccessControl(
                    { uri, kind ->
                        log.warn("A document asked for {} ({}) while being laid out; it was not fetched", uri, kind)
                        false
                    },
                    /*
                     * Before the URI is resolved rather than after, so a
                     * relative reference is refused as written instead of being
                     * turned into something absolute against a base document
                     * that does not exist.
                     */
                    ExternalResourceControlPriority.RUN_BEFORE_RESOLVING_URI,
                )
                .withW3cDocument(W3CDom().fromJsoup(Jsoup.parse(page)), "")
                .toStream(made)
                .run()
            Written.Made(made.toByteArray(), prepared.problems)
        } catch (failure: Exception) {
            log.warn("A document could not be laid out: {}", failure.message)
            Written.Refused(failure.message ?: "the page could not be laid out")
        }
    }

    /**
     * The page with a stylesheet in front of it, and its title.
     *
     * A model writes the content and should not have to write the typography;
     * a page that arrives with no styling at all would otherwise lay out as
     * Times at the browser's defaults, which is what an unstyled document looks
     * like and not what a report looks like. Anything the page sets for itself
     * wins, because this goes first.
     */
    private fun styled(html: String, title: String?): String {
        val page = Jsoup.parse(html)
        title?.trim()?.takeIf { it.isNotEmpty() }?.let { page.title(it) }
        page.head().prependElement("style").text(HOUSE)
        return page.outerHtml()
    }

    private companion object {
        /** Past this it is a book, and the layout will take minutes over it. */
        const val MOST_CHARS = 2_000_000

        /**
         * The house style, which is deliberately short.
         *
         * Margins, a readable face, tables that look like tables and drawings
         * that fit their column. Not a design system: a page that wants one
         * brings its own, and everything here is overridden by anything the
         * document sets.
         */
        val HOUSE = """
            @page { size: A4; margin: 18mm 16mm; }
            body { font-family: sans-serif; font-size: 10.5pt; line-height: 1.45; color: #1a1a1a; }
            h1, h2, h3, h4 { line-height: 1.25; page-break-after: avoid; }
            h1 { font-size: 20pt; margin: 0 0 12pt; }
            h2 { font-size: 15pt; margin: 18pt 0 8pt; }
            h3 { font-size: 12pt; margin: 14pt 0 6pt; }
            p { margin: 0 0 8pt; }
            ul, ol { margin: 0 0 8pt; padding-left: 16pt; }
            table { border-collapse: collapse; width: 100%; margin: 0 0 10pt; page-break-inside: avoid; }
            th, td { border: 0.5pt solid #b9b9b9; padding: 4pt 6pt; text-align: left; vertical-align: top; }
            th { background: #f1f1f1; font-weight: 600; }
            code, pre { font-family: monospace; font-size: 9.5pt; }
            pre { background: #f6f6f6; padding: 6pt; page-break-inside: avoid; white-space: pre-wrap; }
            hr { border: none; border-top: 0.5pt solid #b9b9b9; margin: 12pt 0; }
            svg { max-width: 100%; page-break-inside: avoid; }
            img { max-width: 100%; }
            .orknux-not-drawn { color: #8a8a8a; font-style: italic; }
        """.trimIndent()
    }
}
