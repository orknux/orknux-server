package io.mszymanski.orknux.server.embedded

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.util.Base64

/**
 * A page laid out, with its drawings in it. Issues #506 and #488.
 *
 * What is pinned here is mostly what the old writer could not do, because that
 * is what the change is for: CSS, tables, pictures, and a chart that reaches
 * the page at all. The report that started #488 had three charts turned into
 * HTML tables by an agent that could see no other way.
 *
 * The assertions read the finished PDF back rather than trusting the call to
 * have worked - the whole lesson of the diagram work is that a renderer will
 * hand back a perfectly valid document with the drawing missing.
 */
class PdfWriterTest {

    private val mapper = JsonMapper.builder().build()
    private val blocks = PageBlocks(DiagramRenderer(), ChartRenderer(), mapper)
    private val writer = PdfWriter(blocks)

    /** A one-pixel PNG, which is a real picture and small enough to read by eye. */
    private val pixel = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    )

    private fun made(html: String, pictures: (String) -> PageBlocks.Picture? = { null }): PdfWriter.Written.Made {
        val answer = writer.fromHtml(html, title = "Report", pictures = pictures)
        assertThat(answer).isInstanceOf(PdfWriter.Written.Made::class.java)
        return answer as PdfWriter.Written.Made
    }

    /** What the finished document says. */
    private fun textOf(pdf: ByteArray): String =
        Loader.loadPDF(pdf).use { PDFTextStripper().getText(it) }

    /**
     * How much drawing there is on the page, in bytes of content stream.
     *
     * Needed because a diagram's labels are *not* readable text in the result.
     * Batik draws SVG text as glyph outlines - paths, not characters - so
     * `PDFTextStripper` correctly finds nothing, and asserting on the labels
     * fails whether the drawing is there or not. Measured rather than assumed:
     * the same page came to 1011 bytes bare, 4867 with a flowchart and 11538
     * with a pie, and reported no problems either time.
     *
     * So the claim is about the drawing instructions rather than the words:
     * a page that had a drawing put on it carries far more of them than the
     * same page without one. That still fails if the SVG is dropped, which is
     * the fault worth catching - the document stays valid and opens fine while
     * the diagram is simply absent.
     */
    private fun drawnBytesOf(pdf: ByteArray): Int = Loader.loadPDF(pdf).use { document ->
        document.pages.sumOf { page ->
            /*
             * The page's own instructions plus everything they draw *through*.
             * Batik puts an SVG into a form rather than onto the page, so the
             * page stream barely grows - it came to 264 bytes with a pie on it
             * against 442 without, which read as the drawing being missing when
             * it was simply somewhere else. The form is where the drawing is.
             */
            val own = page.contents.use { it.readBytes().size }
            val drawn = page.resources.xObjectNames.sumOf { name ->
                runCatching { page.resources.getXObject(name).stream.length }.getOrDefault(0)
            }
            own + drawn
        }
    }

    @Test
    fun `a page becomes a document that says what the page said`() {
        val made = made("<h1>Quarterly report</h1><p>Revenue rose.</p>")
        assertThat(made.problems).isEmpty()
        assertThat(textOf(made.pdf)).contains("Quarterly report").contains("Revenue rose")
    }

    /**
     * A table, which the old writer ran together as text because it had no
     * layout - cells arrived as one line and a reader could not tell columns
     * apart.
     */
    @Test
    fun `a table lays out as a table`() {
        val made = made(
            "<table><tr><th>Region</th><th>Revenue</th></tr>" +
                "<tr><td>North</td><td>1200</td></tr></table>",
        )
        val said = textOf(made.pdf)
        assertThat(said).contains("Region").contains("North").contains("1200")
    }

    /**
     * CSS, which used to be documented as doing nothing at all: "a style
     * attribute is not an error, it simply does nothing. Do not spend a turn
     * writing one."
     */
    @Test
    fun `a page that styles itself is laid out with that styling`() {
        val made = made("<p style=\"page-break-after: always\">First</p><p>Second</p>")
        assertThat(Loader.loadPDF(made.pdf).use { it.numberOfPages }).isEqualTo(2)
    }

    /* ------------------------------------------------------------- drawings */

    @Test
    fun `a diagram block is drawn into the document`() {
        val made = made(
            "<h1>How it works</h1><pre class=\"mermaid\">flowchart LR" + n +
                "A[Webhook] --> B[Describe]" + n + "</pre>",
        )
        assertThat(made.problems).isEmpty()
        assertThat(drawnBytesOf(made.pdf))
            .describedAs("the flowchart's drawing instructions are on the page")
            .isGreaterThan(drawnBytesOf(made("<h1>How it works</h1>").pdf) * TIMES_MORE)
    }

    /** The one #488 is about: a chart in the HTML, drawn onto the page. */
    @Test
    fun `a chart block is drawn into the document`() {
        val made = made(
            "<h1>Spend</h1><pre class=\"chart\">" +
                """{"kind":"pie","title":"Where it went","values":{"Rent":45,"Food":30}}""" +
                "</pre>",
        )
        assertThat(made.problems).isEmpty()
        assertThat(drawnBytesOf(made.pdf))
            .describedAs("the pie's drawing instructions are on the page")
            .isGreaterThan(drawnBytesOf(made("<h1>Spend</h1>").pdf) * TIMES_MORE)
    }

    @Test
    fun `a picture this session holds is drawn into the document`() {
        val made = made("<p>Before</p><img src=\"chart.png\"><p>After</p>") { name ->
            if (name == "chart.png") PageBlocks.Picture(pixel, "image/png") else null
        }
        assertThat(made.problems).isEmpty()
        assertThat(textOf(made.pdf)).contains("Before").contains("After")
    }

    /* ------------------------------------------------- what goes wrong, safely */

    /**
     * A drawing that will not draw costs the drawing, not the document.
     *
     * Throwing instead meant one bad diagram cost the whole call, and a caller
     * answered by retrying with it written slightly differently, three or four
     * times, having already been handed a working document.
     */
    @Test
    fun `a diagram that will not draw leaves a note and the rest of the document`() {
        val made = made("<h1>Report</h1><pre class=\"mermaid\">pie title Spend</pre><p>The end.</p>")
        assertThat(made.problems).hasSize(1)
        assertThat(made.problems.first()).contains("charts_render")
        val said = textOf(made.pdf)
        assertThat(said).contains("Report").contains("The end")
        assertThat(said).contains("not drawn")
    }

    /**
     * And a page is never fetched while it is laid out.
     *
     * A layout engine that honours a remote image is a program that decides
     * what this server connects to, on the say-so of a document a model wrote.
     */
    @Test
    fun `a picture named by web address is refused rather than fetched`() {
        val made = made("<p>Report</p><img src=\"https://example.test/logo.png\">")
        assertThat(made.problems).hasSize(1)
        assertThat(made.problems.first()).contains("web address")
        assertThat(textOf(made.pdf)).contains("Report")
    }

    private val n = 10.toChar().toString()

    private companion object {
        /**
         * How much more drawing a page with a figure on it carries.
         *
         * Two, against a measured five- to elevenfold, so this fails when a
         * drawing goes missing and does not fail because a font or a margin
         * moved. The failure being guarded is total absence, not a few bytes.
         */
        const val TIMES_MORE = 2
    }

    @Test
    fun `an empty page is refused`() {
        assertThat(writer.fromHtml("   ")).isInstanceOf(PdfWriter.Written.Refused::class.java)
    }
}
