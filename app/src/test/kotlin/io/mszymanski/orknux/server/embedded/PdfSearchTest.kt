package io.mszymanski.orknux.server.embedded

import io.mszymanski.orknux.server.llm.LlmSession
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.llm.LlmSessionStore
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tunables
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.script.StoredKind
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * pdf_search: finding words in a long document without reading it into the
 * turn, and pdf_read reading only the pages a search named.
 */
@SpringBootTest
class PdfSearchTest(
    @Autowired val embedded: EmbeddedCapabilities,
    @Autowired val scratch: LlmSessionStore,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val mapper: ObjectMapper,
    @Autowired val tunables: Tunables,
) {

    private var workspaceId = 0L
    private var sessionId = 0L

    @BeforeEach
    fun keep() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "Pdf ${System.nanoTime()}")).id)
        sessionId = requireNotNull(sessions.save(LlmSession(workspaceId = workspaceId, sessionKey = "pdf-${System.nanoTime()}")).id)
        put("report.pdf", document(PAGES))
        put("scan.pdf", scanned())
    }

    @Test
    fun `matches are found on the pages they are on, case-insensitively`() {
        val found = search("total")
        assertThat(found.path("count").asInt()).isEqualTo(2)
        assertThat(found.path("pages").asInt()).isEqualTo(4)
        assertThat(found.path("matches").values().map { it.path("page").asInt() }).containsExactly(2, 3)
        assertThat(found.path("matches").get(0).path("text").stringValue()).contains("«total»")
        assertThat(found.path("next").stringValue()).contains(PdfCapability.READ_TOOL)

        assertThat(search("Total", "case_sensitive" to true).path("count").asInt()).isEqualTo(1)
        // A phrase matches across the line the page broke it on.
        assertThat(search("amount due").path("matches").get(0).path("page").asInt()).isEqualTo(3)
    }

    @Test
    fun `a regular expression and a whole word`() {
        val sums = search("\\d+ EUR", "regex" to true)
        assertThat(sums.path("count").asInt()).isEqualTo(2)
        assertThat(sums.path("matches").values().map { it.path("text").stringValue() }).anySatisfy { assertThat(it).contains("«42 EUR»") }

        assertThat(search("net").path("count").asInt()).isEqualTo(3)
        val whole = search("net", "whole_word" to true)
        assertThat(whole.path("count").asInt()).isEqualTo(2)
        assertThat(whole.path("matches").values().map { it.path("page").asInt() }).containsExactly(2, 3)

        assertThat(search("(a|", "regex" to true).path("error").stringValue()).contains("not a regular expression")
    }

    @Test
    fun `every match is counted, past the ones listed`() {
        val found = search("alpha", "limit" to 5)
        assertThat(found.path("count").asInt()).isEqualTo(30)
        assertThat(found.path("matches").size()).isEqualTo(5)
        assertThat(found.path("more").stringValue()).startsWith("25 more")
        assertThat(found.path("onPages").toString()).isEqualTo("[[4,30]]")
    }

    @Test
    fun `pdf_read reads only the pages asked for`() {
        val read = call(PdfCapability.READ_TOOL, mapOf("contentKey" to "report.pdf", "from" to 2, "to" to 2))
        assertThat(read.path("from").asInt()).isEqualTo(2)
        assertThat(read.path("to").asInt()).isEqualTo(2)
        val html = read.path("html").stringValue()
        assertThat(html).contains("42 EUR").doesNotContain("Introduction").doesNotContain("amount due")
        assertThat(read.has("note")).isFalse()
    }

    @Test
    fun `a scan says it has no text rather than finding nothing`() {
        val found = call(PdfCapability.SEARCH_TOOL, mapOf("contentKey" to "scan.pdf", "query" to "anything"))
        assertThat(found.path("textLayer").asBoolean(true)).isFalse()
        assertThat(found.path("note").stringValue()).contains("no text layer").contains(PdfCapability.PREVIEW_TOOL)

        val read = call(PdfCapability.READ_TOOL, mapOf("contentKey" to "scan.pdf"))
        assertThat(read.path("note").stringValue()).contains("no text layer")
    }

    /**
     * Issue #664: a document past the bound used to be refused whole. Now the
     * read stops at the last page that fits and names the page to read on from.
     */
    @Test
    fun `a long read stops between pages and says which page to read on from`() {
        tunables.set(Tunable.PDF_READ_CHARACTERS, 1_000, "alice")
        put("long.pdf", document(List(4) { page -> List(6) { "page ${page + 1} line $it " + "word ".repeat(16) } }))

        val first = call(PdfCapability.READ_TOOL, mapOf("contentKey" to "long.pdf"))
        assertThat(first.path("to").asInt()).isEqualTo(1)
        assertThat(first.path("next").asInt()).isEqualTo(2)
        assertThat(first.path("html").stringValue()).contains("page 1 line 5").doesNotContain("page 2 line")
            .endsWith("</section>\n")
        assertThat(first.path("note").stringValue()).contains("${PdfCapability.READ_TOOL}'s from 2")

        val second = call(PdfCapability.READ_TOOL, mapOf("contentKey" to "long.pdf", "from" to 2))
        assertThat(second.path("from").asInt()).isEqualTo(2)
        assertThat(second.path("next").asInt()).isEqualTo(3)
        assertThat(second.path("html").stringValue()).contains("page 2 line 0").doesNotContain("page 1 line")
    }

    @AfterEach
    fun unlimit() = tunables.set(Tunable.PDF_READ_CHARACTERS, null, "alice")

    /**
     * Issue #665: a chart is not in a page's text, and a model told nothing
     * about it answers from the words around it as if they were all there was.
     */
    @Test
    fun `a page with a picture says the picture is not in the text, and how to look at it`() {
        put("illustrated.pdf", illustrated())

        val read = call(PdfCapability.READ_TOOL, mapOf("contentKey" to "illustrated.pdf"))

        assertThat(read.path("figures").toString()).isEqualTo("[2]")
        assertThat(read.path("note").stringValue())
            .contains("Page 2 has pictures").contains("not in this text")
            .contains("${PdfCapability.PREVIEW_TOOL}'s page")
        // And a page without one says nothing of the kind.
        val plain = call(PdfCapability.READ_TOOL, mapOf("contentKey" to "illustrated.pdf", "to" to 1))
        assertThat(plain.has("figures")).isFalse()
        assertThat(plain.has("note")).isFalse()
    }

    /** The way to look is named only to an agent that holds it: a tool named and not held is one it cannot call. */
    @Test
    fun `the preview is named only where it is held`() {
        put("illustrated.pdf", illustrated())
        val withoutPreview = { name: String -> name != PdfCapability.PREVIEW_TOOL }

        val read = mapper.readTree(
            embedded.run(PdfCapability.READ_TOOL, """{"contentKey":"illustrated.pdf"}""", workspaceId, sessionId, withoutPreview),
        )
        assertThat(read.path("note").stringValue()).contains("Page 2 has pictures").doesNotContain(PdfCapability.PREVIEW_TOOL)

        val scan = mapper.readTree(
            embedded.run(PdfCapability.READ_TOOL, """{"contentKey":"scan.pdf"}""", workspaceId, sessionId, withoutPreview),
        )
        assertThat(scan.path("note").stringValue()).contains("no text layer").doesNotContain(PdfCapability.PREVIEW_TOOL)
    }

    @Test
    fun `pdf_read's description sends a long document to the search`() {
        val read = embedded.toolSpecs().single { it.name == PdfCapability.READ_TOOL }
        assertThat(read.description).contains(PdfCapability.SEARCH_TOOL)
    }

    private fun search(query: String, vararg also: Pair<String, Any>): JsonNode =
        call(PdfCapability.SEARCH_TOOL, mapOf("contentKey" to "report.pdf", "query" to query) + also)

    private fun call(name: String, arguments: Map<String, Any?>): JsonNode =
        mapper.readTree(embedded.run(name, mapper.writeValueAsString(arguments), workspaceId, sessionId))

    private fun put(key: String, pdf: ByteArray) {
        scratch.put(sessionId, key, mapper.writeValueAsString(Base64.getEncoder().encodeToString(pdf)), StoredKind("application/pdf", true))
    }

    companion object {
        /** Four pages; each inner list is the page's lines. */
        val PAGES = listOf(
            listOf("Introduction to the network"),
            listOf("The net total is 42 EUR"),
            listOf("Total amount", "due: 100 EUR", "net"),
            List(3) { (1..10).joinToString(" ") { "alpha" } },
        )

        fun document(pages: List<List<String>>): ByteArray = PDDocument().use { document ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            pages.forEach { lines ->
                val page = PDPage()
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText()
                    stream.setFont(font, 10f)
                    stream.newLineAtOffset(50f, 700f)
                    lines.forEach { line ->
                        // Long lines wrap at 90 characters, the way a page would.
                        line.chunked(90).forEach {
                            stream.showText(it)
                            stream.newLineAtOffset(0f, -14f)
                        }
                    }
                    stream.endText()
                }
            }
            ByteArrayOutputStream().also { document.save(it) }.toByteArray()
        }

        /** Two pages of text, the second with a chart drawn beside it as a picture. */
        fun illustrated(): ByteArray = PDDocument().use { document ->
            val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            listOf("Revenue by quarter", "As the chart shows, the third quarter grew").forEachIndexed { index, line ->
                val page = PDPage()
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.beginText()
                    stream.setFont(font, 10f)
                    stream.newLineAtOffset(50f, 700f)
                    stream.showText(line)
                    stream.endText()
                    if (index == 1) {
                        val chart = BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB)
                        stream.drawImage(LosslessFactory.createFromImage(document, chart), 50f, 500f)
                    }
                }
            }
            ByteArrayOutputStream().also { document.save(it) }.toByteArray()
        }

        /** One page that is a picture and nothing else, as a scan's is. */
        fun scanned(): ByteArray = PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            val picture = BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB).apply {
                createGraphics().apply { drawString("not text", 20, 50); dispose() }
            }
            val image = LosslessFactory.createFromImage(document, picture)
            PDPageContentStream(document, page).use { it.drawImage(image, 50f, 600f) }
            ByteArrayOutputStream().also { document.save(it) }.toByteArray()
        }
    }
}
