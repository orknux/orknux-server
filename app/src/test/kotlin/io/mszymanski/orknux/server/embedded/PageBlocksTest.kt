package io.mszymanski.orknux.server.embedded

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.util.Base64

/**
 * The blocks a page asks for, drawn into the page. Issue #488.
 *
 * Separate from [PdfWriterTest] on purpose. That one asks whether a drawing
 * reached the finished document, and when it does not there are two possible
 * culprits - the block was never drawn into the HTML, or it was drawn and the
 * layout engine ignored it. This half answers the first, so the other test's
 * failures mean only one thing.
 */
class PageBlocksTest {

    private val blocks = PageBlocks(DiagramRenderer(), ChartRenderer(), JsonMapper.builder().build())

    private val n = 10.toChar().toString()

    private val pixel = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
    )

    @Test
    fun `a mermaid block becomes an inline svg`() {
        val drawn = blocks.draw("<pre class=\"mermaid\">flowchart LR" + n + "A[Webhook] --> B[Describe]" + n + "</pre>")
        assertThat(drawn.problems).isEmpty()
        assertThat(drawn.html).contains("<svg").contains("Webhook")
        // And the block itself is gone, rather than sitting beside the drawing.
        assertThat(drawn.html).doesNotContain("class=\"mermaid\"")
    }

    /** The way mermaid.js reads a page, as a div. Issue #544. */
    @Test
    fun `a mermaid div is drawn too, and a wrapper round other markup is left alone`() {
        val drawn = blocks.draw(
            "<div class=\"mermaid\">graph TD" + n + "UI[Console] --> S[Server]" + n + "</div>" +
                "<div class=\"mermaid\"><p>kept</p></div>",
        )
        assertThat(drawn.problems).isEmpty()
        assertThat(drawn.html).contains("<svg").contains("Console").contains("kept")
    }

    /** A report written for a browser: Chart.js into a canvas. Issue #544. */
    @Test
    fun `a canvas is named as not drawn, and the scripts are dropped`() {
        val drawn = blocks.draw(
            "<script src=\"https://cdn.jsdelivr.net/npm/chart.js\"></script>" +
                "<canvas id=\"projectChart\"></canvas><script>new Chart()</script>",
        )
        assertThat(drawn.problems).hasSize(1)
        assertThat(drawn.problems.first()).contains("<canvas>").contains("pre class=\"chart\"")
        assertThat(drawn.html).doesNotContain("<canvas").doesNotContain("<script").contains("not drawn")
    }

    @Test
    fun `a chart block becomes an inline svg`() {
        val drawn = blocks.draw(
            "<pre class=\"chart\">" + """{"kind":"pie","title":"Spend","values":{"Rent":45,"Food":30}}""" + "</pre>",
        )
        assertThat(drawn.problems).isEmpty()
        assertThat(drawn.html).contains("<svg").contains("Rent")
        assertThat(drawn.html).doesNotContain("class=\"chart\"")
    }

    /** No title and no kind, as a model writes one. Issue #547: the missing title failed the document. */
    @Test
    fun `a chart block with nothing but values draws`() {
        val drawn = blocks.draw("<pre class=\"chart\">" + """{"values":{"Server":871,"UI":912}}""" + "</pre>")
        assertThat(drawn.problems).isEmpty()
        assertThat(drawn.html).contains("<svg").contains("Server")
    }

    @Test
    fun `a picture this session holds becomes a data uri`() {
        val drawn = blocks.draw("<img src=\"chart.png\">") { name ->
            if (name == "chart.png") PageBlocks.Picture(pixel, "image/png") else null
        }
        assertThat(drawn.problems).isEmpty()
        assertThat(drawn.html).contains("data:image/png;base64,iVBOR")
    }

    @Test
    fun `a picture nothing holds leaves a note and says which name`() {
        val drawn = blocks.draw("<img src=\"missing.png\">")
        assertThat(drawn.problems).hasSize(1)
        assertThat(drawn.problems.first()).contains("missing.png")
        assertThat(drawn.html).contains("not drawn")
    }

    @Test
    fun `a web address is refused rather than fetched`() {
        val drawn = blocks.draw("<img src=\"https://example.test/logo.png\">")
        assertThat(drawn.problems).hasSize(1)
        assertThat(drawn.problems.first()).contains("web address")
    }

    /** A page with nothing to draw comes back as itself. */
    @Test
    fun `a page with no blocks is left alone`() {
        val drawn = blocks.draw("<h1>Plain</h1><p>Nothing to draw.</p>")
        assertThat(drawn.problems).isEmpty()
        assertThat(drawn.html).contains("Plain").contains("Nothing to draw")
    }
}
