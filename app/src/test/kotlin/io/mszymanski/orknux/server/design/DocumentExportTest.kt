package io.mszymanski.orknux.server.design

import com.github.miachm.sods.SpreadSheet
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.ZipInputStream

/**
 * A document written out as PDF, Markdown, ODT and ODS, from the address the
 * editor's Export and orknux_document_export both hand out.
 *
 * Pinned on what each file holds rather than on its size: the PDF has the
 * picture as an image and starts a page where the document breaks one; the
 * Markdown says what the document says, its picture beside it in a zip; the
 * ODT is a package a word processor opens - the stored mimetype first, the
 * words in content.xml, the picture in the package, the page break and the
 * page size; the ODS has the table on a sheet named after its heading.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class DocumentExportTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val context: WebApplicationContext,
) {

    private var workspaceId: Long = 0
    private var picture: String = ""
    private var documentId: Long = 0

    @BeforeEach
    fun make() {
        audit.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "document-export-${System.nanoTime()}")).id)
        val png = Base64.getEncoder().encodeToString(DocumentAPITest.png())
        picture = graphQlTester.document(
            """mutation { importDesignImage(input: { workspaceId: $workspaceId, image: "$png", name: "Red" }) { id } }""",
        ).execute().path("importDesignImage.id").entity(String::class.java).get()
        documentId = graphQlTester.document(
            """mutation(${'$'}w: ID!, ${'$'}d: String) { createDocument(workspaceId: ${'$'}w, name: "Plan", content: ${'$'}d) { id } }""",
        ).variable("w", workspaceId).variable("d", CONTENT.replace("PICTURE", picture))
            .execute().path("createDocument.id").entity(Long::class.java).get()
    }

    private fun download(format: String, id: Long = documentId): MockHttpServletResponse =
        MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
            .perform(get("/api/documents/$id/export/$format")).andReturn().response

    @Test
    fun `the PDF holds the picture and starts a new page where the document breaks one`() {
        val answer = download("pdf")
        assertThat(answer.status).isEqualTo(200)
        assertThat(answer.contentType).isEqualTo("application/pdf")
        assertThat(answer.getHeader("Content-Disposition")).startsWith("attachment").contains("Plan.pdf")

        Loader.loadPDF(answer.contentAsByteArray).use { pdf ->
            assertThat(pdf.numberOfPages).isEqualTo(2)
            val first = pdf.getPage(0)
            val pictures = first.resources.xObjectNames.map { first.resources.getXObject(it) }.filterIsInstance<PDImageXObject>()
            assertThat(pictures).describedAs("the picture, from its stored bytes").isNotEmpty
            fun text(page: Int) = PDFTextStripper().apply { startPage = page; endPage = page }.getText(pdf)
            assertThat(text(1)).contains("Plan for the quarter", "The red field").doesNotContain("After the break")
            assertThat(text(2)).contains("After the break")
        }
        assertThat(audit.findAll().map { it.message }).contains("Document Plan exported as PDF")
    }

    @Test
    fun `the Markdown says what the document says, its picture beside it in a zip`() {
        // Made before any download: a request through the security filters leaves this thread signed out.
        val plain = graphQlTester.document(
            """mutation(${'$'}w: ID!, ${'$'}d: String) { createDocument(workspaceId: ${'$'}w, name: "Notes", content: ${'$'}d) { id } }""",
        ).variable("w", workspaceId).variable("d", DocumentAPITest.PARAGRAPH("Just words"))
            .execute().path("createDocument.id").entity(Long::class.java).get()
        val answer = download("md")
        assertThat(answer.contentType).isEqualTo("application/zip")
        assertThat(answer.getHeader("Content-Disposition")).contains("Plan.zip")
        val entries = unzip(answer.contentAsByteArray)
        val picturePath = entries.keys.single { it.startsWith("images/") }
        assertThat(entries[picturePath]).isEqualTo(DocumentAPITest.png())
        val md = String(requireNotNull(entries["Plan.md"]), Charsets.UTF_8)
        assertThat(md).contains(
            "# Plan for the quarter",
            "Ship **the editor** and <u>check</u> it.",
            "- One\n- Two",
            "| When | What |",
            "| --- | --- |",
            "| May | Pages |",
            "![A red field]($picturePath \"The red field\")",
            "<!-- pagebreak -->",
            "After the break",
        )

        // Without pictures it is the Markdown alone.
        val alone = download("md", plain)
        assertThat(alone.contentType).startsWith("text/markdown")
        assertThat(alone.getContentAsString(Charsets.UTF_8)).isEqualTo("Just words\n")
    }

    @Test
    fun `the ODT is a package a word processor opens, holding the words, the picture, the break and the page`() {
        val answer = download("odt")
        assertThat(answer.contentType).describedAs(answer.contentAsString).isEqualTo(DocumentExports.ODT)
        val bytes = answer.contentAsByteArray
        // The stored mimetype is the first entry, as the format requires.
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            val first = requireNotNull(zip.nextEntry)
            assertThat(first.name).isEqualTo("mimetype")
            assertThat(String(zip.readAllBytes(), Charsets.US_ASCII)).isEqualTo("application/vnd.oasis.opendocument.text")
        }
        val entries = unzip(bytes)
        val content = String(requireNotNull(entries["content.xml"]), Charsets.UTF_8)
        assertThat(content).contains("Plan for the quarter", "the editor", "After the break", "When", "Pages", "The red field")
        assertThat(content).contains("fo:break-after=\"page\"").contains("text:outline-level=\"1\"").contains("Pictures/picture1.png")
        assertThat(entries["Pictures/picture1.png"]).isEqualTo(DocumentAPITest.png())
        assertThat(String(requireNotNull(entries["styles.xml"]), Charsets.UTF_8)).contains("fo:page-width=\"210.00mm\"")
        assertThat(String(requireNotNull(entries["META-INF/manifest.xml"]), Charsets.UTF_8)).contains("Pictures/picture1.png")
    }

    @Test
    fun `the ODS puts each table on a sheet named after the heading above it`() {
        val answer = download("ods")
        assertThat(answer.contentType).isEqualTo(DocumentExports.ODS)
        val book = SpreadSheet(ByteArrayInputStream(answer.contentAsByteArray))
        assertThat(book.numSheets).isEqualTo(1)
        val sheet = book.getSheet(0)
        assertThat(sheet.name).isEqualTo("Plan for the quarter")
        assertThat(sheet.getRange(0, 0).value).isEqualTo("When")
        assertThat(sheet.getRange(1, 1).value).isEqualTo("Pages")
    }

    /** A Kotlin block: fenced with its language in Markdown, coloured and monospace in the PDF's page and the ODT. */
    @Test
    fun `a code block keeps its language in Markdown and its colours in the PDF and the ODT`() {
        val code = """{"type":"doc","content":[{"type":"codeBlock","attrs":{"language":"kt"},
            "content":[{"type":"text","text":"fun main() {\n    println(\"hi\") // greet\n}"}]}]}"""
        graphQlTester.document("""mutation(${'$'}id: ID!, ${'$'}d: String!) { saveDocument(id: ${'$'}id, content: ${'$'}d) { id } }""")
            .variable("id", documentId).variable("d", code).execute().errors().verify()

        val md = download("md").getContentAsString(Charsets.UTF_8)
        assertThat(md).isEqualTo("```kotlin\nfun main() {\n    println(\"hi\") // greet\n}\n```\n")

        val page = DocumentHtml().page("Code", tools.jackson.databind.json.JsonMapper.builder().build().readTree(code), DocumentPage(210.0, 297.0, 25.0))
        assertThat(page).contains("<pre class=\"code\" data-language=\"kotlin\">", "<span style=\"color: #cf222e\">fun</span>", "<span style=\"color: #6a737d\">// greet</span>")
        Loader.loadPDF(download("pdf").contentAsByteArray).use { pdf ->
            assertThat(PDFTextStripper().apply { sortByPosition = true }.getText(pdf)).contains("println", "\"hi\"", "// greet")
        }
        val content = String(requireNotNull(unzip(download("odt").contentAsByteArray)["content.xml"]), Charsets.UTF_8)
        assertThat(content).contains("fo:color=\"#cf222e\"", "monospace", "println")
    }

    /** A coloured run and a highlight: drawn in their colours in the PDF's page and the ODT, and words in the Markdown. */
    @Test
    fun `a text colour and a highlight are kept in the PDF and the ODT and do not stop the Markdown`() {
        val coloured = """{"type":"doc","content":[{"type":"paragraph","content":[
            {"type":"text","text":"Red words","marks":[{"type":"textStyle","attrs":{"color":"#c00000"}}]},
            {"type":"text","text":" and "},
            {"type":"text","text":"a noted one","marks":[{"type":"note","attrs":{"note":"Why?","id":"n1"}},{"type":"italic"}]}]}]}"""
        graphQlTester.document("""mutation(${'$'}id: ID!, ${'$'}d: String!) { saveDocument(id: ${'$'}id, content: ${'$'}d) { id } }""")
            .variable("id", documentId).variable("d", coloured).execute().errors().verify()

        assertThat(download("md").getContentAsString(Charsets.UTF_8)).isEqualTo("Red words and *a noted one*\n")
        val page = DocumentHtml().page("Colours", tools.jackson.databind.json.JsonMapper.builder().build().readTree(coloured), DocumentPage(210.0, 297.0, 25.0))
        assertThat(page).contains("<span style=\"color: #c00000\">Red words</span>", "<em><span style=\"background-color: ${DocumentHtml.NOTE_COLOUR}\">a noted one</span></em>")
        val pdf = download("pdf")
        assertThat(pdf.status).isEqualTo(200)
        Loader.loadPDF(pdf.contentAsByteArray).use { assertThat(PDFTextStripper().getText(it)).contains("Red words", "a noted one") }
        val content = String(requireNotNull(unzip(download("odt").contentAsByteArray)["content.xml"]), Charsets.UTF_8)
        assertThat(content).contains("fo:color=\"#c00000\"", "fo:background-color=\"${DocumentHtml.NOTE_COLOUR}\"", "Red words", "a noted one")
    }

    /** A5 turned on its side, with wide margins: the PDF's pages are that size, and the ODT's page says so. */
    @Test
    fun `the page a document is set to is the page its PDF and ODT are laid out on`() {
        graphQlTester.document(
            """mutation(${'$'}id: ID!, ${'$'}d: String!) { saveDocument(id: ${'$'}id, content: ${'$'}d, pageSize: "A5", orientation: "LANDSCAPE", margins: "WIDE") {
                 page { widthMm heightMm marginMm } } }""",
        ).variable("id", documentId).variable("d", DocumentAPITest.PARAGRAPH("Small and wide")).execute()
            .path("saveDocument.page.widthMm").entity(Double::class.java).isEqualTo(210.0)
            .path("saveDocument.page.heightMm").entity(Double::class.java).isEqualTo(148.0)
            .path("saveDocument.page.marginMm").entity(Double::class.java).isEqualTo(38.1)
        // A new document starts on the workspace's own page.
        graphQlTester.document("""mutation { setDocumentPageSizeDefault(workspaceId: $workspaceId, pageSize: "LEGAL") }""").execute().errors().verify()
        graphQlTester.document("""mutation { createDocument(workspaceId: $workspaceId, name: "Long") { pageSize page { heightMm } } }""").execute()
            .path("createDocument.pageSize").entity(String::class.java).isEqualTo("LEGAL")
            .path("createDocument.page.heightMm").entity(Double::class.java).isEqualTo(355.6)

        val pdf = download("pdf")
        Loader.loadPDF(pdf.contentAsByteArray).use { laid ->
            val box = laid.getPage(0).mediaBox
            val points = { mm: Double -> mm / 25.4 * 72 }
            assertThat(box.width.toDouble()).isCloseTo(points(210.0), org.assertj.core.data.Offset.offset(1.5))
            assertThat(box.height.toDouble()).isCloseTo(points(148.0), org.assertj.core.data.Offset.offset(1.5))
        }
        val styles = String(requireNotNull(unzip(download("odt").contentAsByteArray)["styles.xml"]), Charsets.UTF_8)
        assertThat(styles).contains("fo:page-width=\"210.00mm\"", "fo:page-height=\"148.00mm\"", "fo:margin-left=\"38.10mm\"", "style:print-orientation=\"landscape\"")
    }

    @Test
    fun `a format that is not one is refused, and a document nobody may see is not there`() {
        assertThat(download("docx").status).isEqualTo(400)
        assertThat(download("pdf", 999_999_999).status).isEqualTo(404)
    }

    /** Mallory only for the request itself: the document is made beforehand, by somebody who may. */
    @Test
    fun `somebody who cannot see the workspace cannot export its documents`() {
        val answer = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
            .perform(get("/api/documents/$documentId/export/pdf").with(user("mallory").roles("USERS"))).andReturn().response
        assertThat(answer.status).isEqualTo(404)
        assertThat(audit.findAll().map { it.message }).noneMatch { it.contains("exported") }
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                out[entry.name] = zip.readAllBytes()
            }
        }
        return out
    }

    private companion object {
        val CONTENT = """{"type":"doc","content":[
            {"type":"heading","attrs":{"level":1},"content":[{"type":"text","text":"Plan for the quarter"}]},
            {"type":"paragraph","content":[{"type":"text","text":"Ship "},{"type":"text","text":"the editor","marks":[{"type":"bold"}]},
              {"type":"text","text":" and "},{"type":"text","text":"check","marks":[{"type":"underline"}]},{"type":"text","text":" it."}]},
            {"type":"bulletList","content":[
              {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"One"}]}]},
              {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Two"}]}]}]},
            {"type":"table","content":[
              {"type":"tableRow","content":[
                {"type":"tableHeader","content":[{"type":"paragraph","content":[{"type":"text","text":"When"}]}]},
                {"type":"tableHeader","content":[{"type":"paragraph","content":[{"type":"text","text":"What"}]}]}]},
              {"type":"tableRow","content":[
                {"type":"tableCell","content":[{"type":"paragraph","content":[{"type":"text","text":"May"}]}]},
                {"type":"tableCell","content":[{"type":"paragraph","content":[{"type":"text","text":"Pages"}]}]}]}]},
            {"type":"image","attrs":{"artifact":"PICTURE","alt":"A red field","caption":"The red field","width":50}},
            {"type":"pageBreak"},
            {"type":"paragraph","content":[{"type":"text","text":"After the break"}]}
        ]}"""
    }
}
