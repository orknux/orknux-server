package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.embedded.EmbeddedCapabilities
import io.mszymanski.orknux.server.embedded.PdfCapability
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.ObjectMapper

/**
 * An answer read in pages, held to the model's window between pages.
 *
 * Issue #664: `pdf_read` cut to a share of the window stopped in the middle of
 * a sentence, and the note sent the model to a stored copy when the tool it
 * had just called reads from any page.
 */
@SpringBootTest
class TurnWindowPagesTest(
    @Autowired val window: TurnWindow,
    @Autowired val embedded: EmbeddedCapabilities,
    @Autowired val mapper: ObjectMapper,
) {

    private val call = ToolCall("call_1", PdfCapability.READ_TOOL, """{"contentKey":"report.pdf"}""")

    @Test
    fun `a paged answer too long for the window is cut after a whole page and says where to read on`() {
        val said = answer(pages = 6, each = 1_000)
        val longest = 3_200

        val cut = mapper.readTree(window.fit(said, call, embedded.toolSpecs(), session = null, longest = longest))

        val html = cut.path("html").stringValue()
        assertThat(html).endsWith("</section>\n").doesNotContain("(trimmed from")
        assertThat(html).contains("data-page=\"2\"").doesNotContain("data-page=\"4\"")
        val last = cut.path("to").asInt()
        assertThat(cut.path("next").asInt()).isEqualTo(last + 1)
        assertThat(cut.path("note").stringValue()).contains("${PdfCapability.READ_TOOL}'s from ${last + 1}")
        assertThat(mapper.writeValueAsString(cut).length).isLessThanOrEqualTo(longest)
    }

    @Test
    fun `an answer that is not in pages is cut as before`() {
        val said = mapper.writeValueAsString(mapOf("text" to "x".repeat(10_000)))

        val cut = window.fit(said, call, embedded.toolSpecs(), session = null, longest = 3_000)

        assertThat(cut).contains("(trimmed from").doesNotContain("\"next\"")
    }

    private fun answer(pages: Int, each: Int): String {
        val html = (1..pages).joinToString("") { page ->
            "<section data-page=\"$page\">\n<h2>Page $page</h2>\n<p>" + "w".repeat(each) + "</p>\n</section>\n"
        }
        return mapper.writeValueAsString(linkedMapOf("html" to html, "pages" to pages, "from" to 1, "to" to pages, "characters" to pages * each))
    }
}
