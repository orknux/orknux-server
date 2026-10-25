package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tunables
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.poi.util.Units
import org.apache.poi.xwpf.usermodel.Document
import org.apache.poi.xwpf.usermodel.XWPFAbstractNum
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.graphql.test.tester.GraphQlTester
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.util.Base64

/**
 * A file read into a document by the editor's Import, one format at a time.
 *
 * What Export writes comes back: the document is exported as PDF, Markdown
 * and ODT through the same address the editor uses, and each file imported
 * again - the words, the structure the format can carry, and the picture,
 * which comes back as a new Design picture of the workspace rather than as a
 * reference to the old one. DOCX is written here with POI, the way Word lays
 * one out; HTML and text are written out by hand.
 *
 * Pinned on the tree that comes back, not on the document: an import saves
 * nothing, which is what lets the editor make it an unsaved change.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class DocumentImportTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val documents: DesignDocumentRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val tunables: Tunables,
    @Autowired val mapper: ObjectMapper,
    @Autowired val context: WebApplicationContext,
) {

    private var workspaceId: Long = 0
    private var picture: String = ""
    private var documentId: Long = 0

    @BeforeEach
    fun make() {
        audit.deleteAll()
        tunables.set(Tunable.DOCUMENT_IMPORT_MB, null, "alice")
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "document-import-${System.nanoTime()}")).id)
        val png = Base64.getEncoder().encodeToString(DocumentAPITest.png())
        picture = graphQlTester.document(
            """mutation { importDesignImage(input: { workspaceId: $workspaceId, image: "$png", name: "Red" }) { id } }""",
        ).execute().path("importDesignImage.id").entity(String::class.java).get()
        documentId = graphQlTester.document(
            """mutation(${'$'}w: ID!, ${'$'}d: String) { createDocument(workspaceId: ${'$'}w, name: "Plan", content: ${'$'}d) { id } }""",
        ).variable("w", workspaceId).variable("d", CONTENT.replace("PICTURE", picture))
            .execute().path("createDocument.id").entity(Long::class.java).get()
    }

    @AfterEach
    fun tidy() {
        tunables.set(Tunable.DOCUMENT_IMPORT_MB, null, "alice")
    }

    /** Downloaded as the editor does; a request through the security filters leaves this thread signed out, so alice is put back. */
    private fun exported(format: String, id: Long = documentId): ByteArray {
        val signedIn = SecurityContextHolder.getContext().authentication
        try {
            return MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
                .perform(get("/api/documents/$id/export/$format")).andReturn().response.contentAsByteArray
        } finally {
            SecurityContextHolder.getContext().authentication = signedIn
        }
    }

    private fun importing(filename: String, bytes: ByteArray, id: Long = documentId): GraphQlTester.Response =
        graphQlTester.document(
            """mutation(${'$'}id: ID!, ${'$'}name: String!, ${'$'}file: String!) {
                 importDocumentFile(id: ${'$'}id, filename: ${'$'}name, file: ${'$'}file) { content format pictures picturesLeftOut } }""",
        ).variable("id", id).variable("name", filename).variable("file", "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(bytes))
            .execute()

    private fun imported(filename: String, bytes: ByteArray): Imported {
        val answer = importing(filename, bytes)
        answer.errors().verify()
        return Imported(
            mapper.readTree(answer.path("importDocumentFile.content").entity(String::class.java).get()),
            answer.path("importDocumentFile.format").entity(String::class.java).get(),
            answer.path("importDocumentFile.pictures").entity(Int::class.java).get(),
        )
    }

    class Imported(val doc: JsonNode, val format: String, val pictures: Int) {
        val types: List<String> get() = (0 until doc.path("content").size()).map { doc.path("content").get(it).path("type").asText() }

        fun all(type: String): List<JsonNode> {
            val found = mutableListOf<JsonNode>()
            fun walk(node: JsonNode) {
                if (node.path("type").asText() == type) found.add(node)
                val inner = node.path("content")
                for (at in 0 until inner.size()) walk(inner.get(at))
            }
            walk(doc)
            return found
        }

        fun text(): String = DocumentContent(tools.jackson.databind.json.JsonMapper.builder().build()).plainText(doc)

        /** The marks on the text node holding [words]. */
        fun marksOn(words: String): List<String> = all("text").first { it.path("text").asText().contains(words) }
            .path("marks").let { marks -> (0 until marks.size()).map { marks.get(it).path("type").asText() } }
    }

    @Test
    fun `Export's Markdown zip comes back with its structure and its picture, kept as a new picture`() {
        val back = imported("Plan.zip", exported("md"))
        assertThat(back.format).isEqualTo("Markdown")
        assertThat(back.types).containsSubsequence("heading", "paragraph", "bulletList", "table", "image", "pageBreak", "paragraph")
        assertThat(back.marksOn("the editor")).contains("bold")
        val image = back.all("image").single()
        val artifact = image.path("attrs").path("artifact").asText()
        assertThat(artifact).startsWith("DESIGN-").isNotEqualTo(picture)
        assertThat(image.path("attrs").path("src").asText()).isEqualTo("/api/workspaces/$workspaceId/document-images/$artifact")
        assertThat(image.path("attrs").path("caption").asText()).isEqualTo("The red field")
        assertThat(back.pictures).isEqualTo(1)
        assertThat(audit.findAll().map { it.message }).contains("Document Plan imported from Plan.zip")
        // Nothing was written to the document: the editor puts it on the page.
        assertThat(documents.findById(documentId).get().revision).isEqualTo(1)
    }

    @Test
    fun `Export's ODT comes back with its heading, marks, list, table, picture and page break`() {
        val back = imported("Plan.odt", exported("odt"))
        assertThat(back.format).isEqualTo("ODT")
        assertThat(back.all("heading").single().path("attrs").path("level").asInt()).isEqualTo(1)
        assertThat(back.marksOn("the editor")).contains("bold")
        assertThat(back.marksOn("check")).contains("underline")
        assertThat(back.all("bulletList")).hasSize(1)
        assertThat(back.all("listItem")).hasSize(2)
        assertThat(back.all("tableRow")).hasSize(2)
        assertThat(back.all("image")).hasSize(1)
        assertThat(back.all("pageBreak")).hasSize(1)
        assertThat(back.text()).contains("Plan for the quarter", "One", "Two", "When", "Pages", "After the break")
    }

    @Test
    fun `Export's PDF comes back as its heading, its paragraphs, its table and its picture`() {
        val back = imported("Plan.pdf", exported("pdf"))
        assertThat(back.format).isEqualTo("PDF")
        assertThat(back.text()).contains("Plan for the quarter", "Ship the editor and check it.", "After the break")
        // Read off the size it is set in: a PDF imported with no headings had an empty outline.
        assertThat(back.all("heading").map { it.path("content").get(0).path("text").asText() }).contains("Plan for the quarter")
        assertThat(back.all("paragraph").map { it.path("content").get(0)?.path("text")?.asText().orEmpty() })
            .anyMatch { it.startsWith("Ship the editor") }
        // Export rules its tables as the editor draws them, so one comes back a table, its bold first row the header.
        assertThat(back.types).containsSubsequence("heading", "paragraph", "table", "image")
        assertThat(cells(back.all("table").single())).containsExactly(listOf("H:When", "H:What"), listOf("May", "Pages"))
        assertThat(paragraphs(back)).noneMatch { it.contains("When") || it.contains("Pages") }
        assertThat(back.all("image")).hasSize(1)
        assertThat(back.pictures).isEqualTo(1)
        // Its list too: export paints the bullets as dots, and "One" and "Two" ran into the paragraph above them.
        assertThat(back.types).containsSubsequence("paragraph", "bulletList", "table")
        assertThat(items(back.all("bulletList").single())).containsExactly("One", "Two")
        assertThat(paragraphs(back)).noneMatch { it.contains("One") }
    }

    @Test
    fun `Export's PDF of merged cells, a nested list and a list counted from three comes back the same shape`() {
        val shaped = graphQlTester.document(
            """mutation(${'$'}w: ID!, ${'$'}d: String) { createDocument(workspaceId: ${'$'}w, name: "Shaped", content: ${'$'}d) { id } }""",
        ).variable("w", workspaceId).variable("d", SHAPED).execute().path("createDocument.id").entity(Long::class.java).get()
        val back = imported("Shaped.pdf", exported("pdf", shaped))
        assertThat(back.types).containsExactly("paragraph", "bulletList", "orderedList", "table", "paragraph")
        val bullets = back.all("bulletList")
        assertThat(bullets).hasSize(2)
        assertThat(items(bullets.first())).containsExactly("One", "Two")
        assertThat(items(bullets.last())).containsExactly("Deeper")
        val numbered = back.all("orderedList").single()
        assertThat(numbered.path("attrs").path("start").asInt()).isEqualTo(3)
        assertThat(items(numbered)).containsExactly("Third", "Fourth")
        assertThat(spans(back.all("table").single())).containsExactly(
            listOf("H:Both/2x1", "H:Third"), listOf("Tall/1x2", "b1", "c1"), listOf("b2", "c2"),
        )
    }

    @Test
    fun `a ruled table with merged cells comes back with the cells spanning what they span`() {
        val pdf = drawn {
            words("Sales by quarter", 72f, 760f, 18f, bold = true)
            val columns = listOf(72f, 222f, 372f, 522f)
            val rows = listOf(700f, 680f, 660f, 640f)
            // The first row's two left cells are one, and so are the left cells of the last two rows.
            line(72f, 700f, 522f, 700f)
            line(72f, 680f, 522f, 680f)
            line(222f, 660f, 522f, 660f)
            line(72f, 640f, 522f, 640f)
            line(72f, 700f, 72f, 640f)
            line(222f, 680f, 222f, 640f)
            line(372f, 700f, 372f, 640f)
            line(522f, 700f, 522f, 640f)
            words("Quarter", columns[0] + 6f, rows[0] - 14f, bold = true)
            words("Total", columns[2] + 6f, rows[0] - 14f, bold = true)
            words("North", columns[0] + 6f, rows[1] - 14f)
            words("3", columns[1] + 6f, rows[1] - 14f)
            words("5", columns[2] + 6f, rows[1] - 14f)
            words("7", columns[1] + 6f, rows[2] - 14f)
            words("9", columns[2] + 6f, rows[2] - 14f)
        }
        val back = imported("Sales.pdf", pdf)
        assertThat(back.types).containsExactly("heading", "table")
        assertThat(spans(back.all("table").single())).containsExactly(
            listOf("H:Quarter/2x1", "H:Total"), listOf("North/1x2", "3", "5"), listOf("7", "9"),
        )
    }

    @Test
    fun `lines a PDF opens with bullets and numbers come back lists, nested by their indent, and prose opening with a dash does not`() {
        val pdf = drawn {
            words("Prices rose sharply in the year to March, by", 72f, 760f)
            words("- as the bank put it - four percent.", 72f, 746f)
            words("The market keeps these:", 72f, 716f)
            words("•", 72f, 700f)
            words("Apples, which", 84f, 700f)
            words("keep well", 84f, 686f)
            words("–", 84f, 672f)
            words("Bramley", 96f, 672f)
            words("•", 72f, 658f)
            words("Pears", 84f, 658f)
            words("3.", 72f, 628f)
            words("Third step", 86f, 628f)
            words("4.", 72f, 614f)
            words("Fourth step", 86f, 614f)
            words("And that was all.", 72f, 584f)
            // A paragraph opening with a number, wrapped back to the margin rather than hung at its words.
            words("2. The second reason was that the vote", 72f, 554f)
            words("was close, and nobody wanted it.", 72f, 540f)
        }
        val back = imported("Market.pdf", pdf)
        assertThat(back.types).containsExactly("paragraph", "paragraph", "bulletList", "orderedList", "paragraph", "paragraph")
        assertThat(paragraphs(back)).containsExactly(
            "Prices rose sharply in the year to March, by - as the bank put it - four percent.", "The market keeps these:", "And that was all.",
            "2. The second reason was that the vote was close, and nobody wanted it.",
        )
        val bullets = back.doc.path("content").get(2)
        assertThat(items(bullets)).containsExactly("Apples, which keep well", "Pears")
        assertThat(items(bullets.path("content").get(0).path("content").get(1))).containsExactly("Bramley")
        val numbered = back.all("orderedList").single()
        assertThat(numbered.path("attrs").path("start").asInt()).isEqualTo(3)
        assertThat(items(numbered)).containsExactly("Third step", "Fourth step")
    }

    @Test
    fun `words lined up in three columns with no lines come back a table though one cell is empty`() {
        val pdf = drawn {
            words("The shelves were counted again on Friday.", 72f, 760f)
            listOf(listOf("Name", "Count", "Shelf"), listOf("Apples", "3", "North"), listOf("Pears", "", "South")).forEachIndexed { at, row ->
                row.forEachIndexed { column, text -> if (text.isNotEmpty()) words(text, 72f + column * 150f, 730f - at * 16f, bold = at == 0) }
            }
            words("Nothing else was counted that day.", 72f, 670f)
        }
        val back = imported("Shelves.pdf", pdf)
        assertThat(back.types).containsExactly("paragraph", "table", "paragraph")
        assertThat(cells(back.all("table").single())).containsExactly(
            listOf("H:Name", "H:Count", "H:Shelf"), listOf("Apples", "3", "North"), listOf("Pears", "", "South"),
        )
    }

    @Test
    fun `a table a PDF draws with ruling lines comes back a table, in its place among the paragraphs`() {
        val pdf = drawn {
            words("Fruit in stock", 72f, 760f, 18f, bold = true)
            words("The counts below were taken on Monday morning.", 72f, 730f)
            val columns = listOf(72f, 222f, 372f, 522f)
            val rows = listOf(700f, 680f, 660f, 640f)
            rows.forEach { y -> line(columns.first(), y, columns.last(), y) }
            columns.forEach { x -> line(x, rows.first(), x, rows.last()) }
            listOf(listOf("Name", "Count", "Shelf"), listOf("Apples", "3", "North"), listOf("Pears", "5", "South")).forEachIndexed { at, row ->
                row.forEachIndexed { column, text -> words(text, columns[column] + 6f, rows[at] - 14f, bold = at == 0) }
            }
            words("Nothing else was counted that day.", 72f, 610f)
        }
        val back = imported("Stock.pdf", pdf)
        assertThat(back.types).containsExactly("heading", "paragraph", "table", "paragraph")
        assertThat(back.all("heading").single().path("content").get(0).path("text").asText()).isEqualTo("Fruit in stock")
        assertThat(cells(back.all("table").single())).containsExactly(
            listOf("H:Name", "H:Count", "H:Shelf"), listOf("Apples", "3", "North"), listOf("Pears", "5", "South"),
        )
        assertThat(paragraphs(back)).containsExactly("The counts below were taken on Monday morning.", "Nothing else was counted that day.")
    }

    @Test
    fun `words a PDF lines up in two columns with no lines come back a table`() {
        val pdf = drawn {
            words("Opening hours are kept as follows, and change only at holidays.", 72f, 760f)
            listOf("Day" to "Hours", "Monday" to "9 to 5", "Tuesday" to "9 to 5", "Saturday" to "10 to 2").forEachIndexed { at, (day, hours) ->
                words(day, 72f, 730f - at * 16f, bold = at == 0)
                words(hours, 300f, 730f - at * 16f, bold = at == 0)
            }
            words("The shop is shut on Sundays.", 72f, 650f)
        }
        val back = imported("Hours.pdf", pdf)
        assertThat(back.types).containsExactly("paragraph", "table", "paragraph")
        assertThat(cells(back.all("table").single())).containsExactly(
            listOf("H:Day", "H:Hours"), listOf("Monday", "9 to 5"), listOf("Tuesday", "9 to 5"), listOf("Saturday", "10 to 2"),
        )
        assertThat(paragraphs(back)).containsExactly(
            "Opening hours are kept as follows, and change only at holidays.", "The shop is shut on Sundays.",
        )
    }

    @Test
    fun `prose with a wide gap in its lines stays paragraphs`() {
        val pdf = drawn {
            words("Minutes", 72f, 760f, 18f, bold = true)
            // Two lines running on with a wide gap in each, not lined up: a sentence, not a table.
            words("The committee met on Tuesday and", 72f, 730f)
            words("agreed to defer the vote until", 330f, 730f)
            words("the spring, when the budget for", 72f, 716f)
            words("the following year would be known.", 280f, 716f)
            // And one line with a tab in it, alone.
            words("Chair:", 72f, 690f)
            words("Ms Example, who opened the meeting at ten.", 160f, 690f)
        }
        val back = imported("Minutes.pdf", pdf)
        assertThat(back.all("table")).isEmpty()
        assertThat(back.types.first()).isEqualTo("heading")
        assertThat(back.text()).contains("The committee met on Tuesday and agreed to defer the vote until", "Chair: Ms Example")
    }

    @Test
    fun `a Word document comes back with its heading, marks, link, list, table and picture`() {
        val docx = XWPFDocument().use { word ->
            word.createParagraph().apply { style = "Heading1" }.createRun().setText("Quarterly plan")
            word.createParagraph().apply {
                createRun().setText("Ship ")
                createRun().apply { isBold = true; setText("the editor") }
                createRun().setText(" and read ")
                createHyperlinkRun("https://example.com/notes").apply { setText("the notes"); isItalic = true }
            }
            val abstract = CTAbstractNum.Factory.newInstance().apply {
                abstractNumId = BigInteger.ZERO
                addNewLvl().apply {
                    ilvl = BigInteger.ZERO
                    addNewNumFmt().`val` = STNumberFormat.DECIMAL
                    addNewLvlText().`val` = "%1."
                }
            }
            val numbering = word.createNumbering()
            val num = numbering.addNum(numbering.addAbstractNum(XWPFAbstractNum(abstract)))
            listOf("First", "Second").forEach { item -> word.createParagraph().apply { numID = num; setNumILvl(BigInteger.ZERO) }.createRun().setText(item) }
            word.createTable(2, 2).apply {
                getRow(0).getCell(0).text = "When"
                getRow(0).getCell(1).text = "What"
                getRow(1).getCell(0).text = "May"
                getRow(1).getCell(1).text = "Pages"
            }
            word.createParagraph().createRun().addPicture(ByteArrayInputStream(DocumentAPITest.png()), Document.PICTURE_TYPE_PNG, "red.png", Units.toEMU(40.0), Units.toEMU(30.0))
            ByteArrayOutputStream().also { word.write(it) }.toByteArray()
        }
        val back = imported("Plan.docx", docx)
        assertThat(back.format).isEqualTo("DOCX")
        assertThat(back.types.first()).isEqualTo("heading")
        assertThat(back.all("heading").single().path("content").get(0).path("text").asText()).isEqualTo("Quarterly plan")
        assertThat(back.marksOn("the editor")).containsExactly("bold")
        assertThat(back.marksOn("the notes")).contains("italic", "link")
        val link = back.all("text").first { it.path("text").asText() == "the notes" }.path("marks").first { it.path("type").asText() == "link" }
        assertThat(link.path("attrs").path("href").asText()).isEqualTo("https://example.com/notes")
        assertThat(back.all("orderedList")).hasSize(1)
        assertThat(back.all("listItem")).hasSize(2)
        assertThat(back.all("tableRow")).hasSize(2)
        assertThat(back.all("tableCell")).hasSize(4)
        assertThat(back.all("image")).hasSize(1)
        assertThat(back.text()).contains("First", "Second", "When", "Pages")
    }

    @Test
    fun `an HTML page comes back as its blocks, its own pictures kept and one on the web left a link`() {
        val png = Base64.getEncoder().encodeToString(DocumentAPITest.png())
        val page = """<!doctype html><html><head><title>x</title><style>p{color:red}</style><script>alert(1)</script></head><body>
            <h2>Notes</h2>
            <p>Words in <b>bold</b>, <span style="font-style: italic">italic</span> and <a href="https://example.com">a link</a>.<br>Next line</p>
            <ul><li>One<ul><li>Deeper</li></ul></li><li>Two</li></ul>
            <pre><code class="language-kotlin">val x = 1
println(x)</code></pre>
            <table><thead><tr><th>A</th><th>B</th></tr></thead><tbody><tr><td colspan="2">Both</td></tr></tbody></table>
            <p>Before <img src="data:image/png;base64,$png" alt="Red"> after</p>
            <img src="https://example.com/far.png" alt="Far away">
            </body></html>"""
        val back = imported("notes.html", page.toByteArray())
        assertThat(back.format).isEqualTo("HTML")
        assertThat(back.all("heading").single().path("attrs").path("level").asInt()).isEqualTo(2)
        assertThat(back.marksOn("bold")).containsExactly("bold")
        assertThat(back.marksOn("italic")).containsExactly("italic")
        assertThat(back.marksOn("a link")).containsExactly("link")
        assertThat(back.all("hardBreak")).hasSize(1)
        assertThat(back.all("bulletList")).hasSize(2)
        assertThat(back.all("codeBlock").single().path("attrs").path("language").asText()).isEqualTo("kotlin")
        assertThat(back.all("tableHeader")).hasSize(2)
        assertThat(back.all("tableCell").single().path("attrs").path("colspan").asInt()).isEqualTo(2)
        // The picture in the page stands between the words it was among, a block of its own.
        val types = back.types
        assertThat(types).containsSubsequence("paragraph", "image", "paragraph")
        assertThat(back.all("image")).hasSize(1)
        assertThat(back.marksOn("Far away")).containsExactly("link")
        assertThat(back.text()).doesNotContain("alert", "color:red")
    }

    @Test
    fun `plain text comes back a paragraph to each run of lines, its line breaks kept`() {
        val back = imported("notes.txt", "First line\nsecond line\n\nAnother paragraph\n".toByteArray())
        assertThat(back.format).isEqualTo("Text")
        assertThat(back.types).containsExactly("paragraph", "paragraph")
        assertThat(back.all("hardBreak")).hasSize(1)
    }

    @Test
    fun `a Markdown file naming another workspace's picture leaves it out`() {
        val elsewhere = requireNotNull(workspaces.save(Workspace(name = "document-import-other-${System.nanoTime()}")).id)
        val png = Base64.getEncoder().encodeToString(DocumentAPITest.png())
        val theirs = graphQlTester.document(
            """mutation { importDesignImage(input: { workspaceId: $elsewhere, image: "$png", name: "Theirs" }) { id } }""",
        ).execute().path("importDesignImage.id").entity(String::class.java).get()
        val back = imported("notes.md", "# Title\n\n![ours](artifact:$picture)\n\n![theirs](artifact:$theirs)\n".toByteArray())
        assertThat(back.all("image").map { it.path("attrs").path("artifact").asText() }).containsExactly(picture)
    }

    @Test
    fun `a file that is none of the formats, one that is broken and one too large are refused, saying so`() {
        importing("picture.xyz", byteArrayOf(1, 2, 3)).errors()
            .expect { it.message == "A document is imported from PDF, Markdown, ODT, DOCX, HTML or text, and picture.xyz is none of them" &&
                it.extensions["code"] == "DocumentImportFormatUnknown" }.verify()
        importing("broken.docx", "not a word document".toByteArray()).errors()
            .expect { it.message?.startsWith("broken.docx could not be read:") == true && it.extensions["code"] == "DocumentImportUnreadable" }.verify()
        tunables.set(Tunable.DOCUMENT_IMPORT_MB, 1, "alice")
        importing("big.txt", ByteArray(1024 * 1024 + 10) { 'a'.code.toByte() }).errors()
            .expect { it.message == "big.txt is 2 MB, and a document imports at most 1 MB" && it.extensions["code"] == "DocumentImportTooLarge" }.verify()
        assertThat(audit.findAll().map { it.message }).noneMatch { it.contains("imported from") }
    }

    /** Mallory, for the request only: the document is made beforehand by somebody who may. */
    @Test
    fun `somebody who cannot see the document cannot import into it`() {
        val signedIn = SecurityContextHolder.getContext().authentication
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken("mallory", "x", listOf(SimpleGrantedAuthority("ROLE_USERS")))
        try {
            importing("notes.txt", "Hello".toByteArray()).errors().expect { it.message == "No document with id $documentId" }.verify()
        } finally {
            SecurityContextHolder.getContext().authentication = signedIn
        }
        assertThat(audit.findAll().map { it.message }).noneMatch { it.contains("imported from") }
    }

    /** A table's rows as the words in each cell, a header cell marked `H:`. */
    private fun cells(table: JsonNode): List<List<String>> = (0 until table.path("content").size()).map { row ->
        val list = table.path("content").get(row).path("content")
        (0 until list.size()).map { at ->
            val cell = list.get(at)
            val words = cell.path("content").get(0).path("content").let { inline -> (0 until inline.size()).joinToString("") { inline.get(it).path("text").asText() } }
            if (cell.path("type").asText() == "tableHeader") "H:$words" else words
        }
    }

    /** A table's rows as [cells] says them, a cell spanning more than one place followed by `/columns x rows`. */
    private fun spans(table: JsonNode): List<List<String>> = cells(table).mapIndexed { row, words ->
        words.mapIndexed { at, said ->
            val attrs = table.path("content").get(row).path("content").get(at).path("attrs")
            val columns = attrs.path("colspan").asInt(1)
            val rows = attrs.path("rowspan").asInt(1)
            if (columns > 1 || rows > 1) "$said/${columns}x$rows" else said
        }
    }

    /** The words of a list's own items, each item's first paragraph; a list inside one is left out. */
    private fun items(list: JsonNode): List<String> = (0 until list.path("content").size()).map { at ->
        val inline = list.path("content").get(at).path("content").get(0).path("content")
        (0 until inline.size()).joinToString("") { inline.get(it).path("text").asText() }
    }

    /** The words of the paragraphs at the top of the document, outside any table. */
    private fun paragraphs(back: Imported): List<String> = (0 until back.doc.path("content").size()).map { back.doc.path("content").get(it) }
        .filter { it.path("type").asText() == "paragraph" }
        .map { node -> node.path("content").let { inline -> (0 until inline.size()).joinToString("") { inline.get(it).path("text").asText() } } }

    /** A one-page PDF drawn with PDFBox, in Helvetica, its origin at the page's bottom left. */
    private fun drawn(draw: Drawing.() -> Unit): ByteArray = PDDocument().use { pdf ->
        val page = PDPage(PDRectangle.A4)
        pdf.addPage(page)
        PDPageContentStream(pdf, page).use { Drawing(it).draw() }
        ByteArrayOutputStream().also { pdf.save(it) }.toByteArray()
    }

    class Drawing(private val stream: PDPageContentStream) {
        private val regular = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        private val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)

        fun words(text: String, x: Float, y: Float, size: Float = 11f, bold: Boolean = false) {
            stream.beginText()
            stream.setFont(if (bold) this.bold else regular, size)
            stream.newLineAtOffset(x, y)
            stream.showText(text)
            stream.endText()
        }

        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            stream.setLineWidth(0.75f)
            stream.moveTo(x1, y1)
            stream.lineTo(x2, y2)
            stream.stroke()
        }
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

        /** A document whose shapes a PDF has to carry in its lines: a nested list, a list counted from three, and merged cells. */
        val SHAPED = """{"type":"doc","content":[
            {"type":"paragraph","content":[{"type":"text","text":"Before the lists."}]},
            {"type":"bulletList","content":[
              {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"One"}]},
                {"type":"bulletList","content":[{"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Deeper"}]}]}]}]},
              {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Two"}]}]}]},
            {"type":"orderedList","attrs":{"start":3},"content":[
              {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Third"}]}]},
              {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"Fourth"}]}]}]},
            {"type":"table","content":[
              {"type":"tableRow","content":[
                {"type":"tableHeader","attrs":{"colspan":2},"content":[{"type":"paragraph","content":[{"type":"text","text":"Both"}]}]},
                {"type":"tableHeader","content":[{"type":"paragraph","content":[{"type":"text","text":"Third"}]}]}]},
              {"type":"tableRow","content":[
                {"type":"tableCell","attrs":{"rowspan":2},"content":[{"type":"paragraph","content":[{"type":"text","text":"Tall"}]}]},
                {"type":"tableCell","content":[{"type":"paragraph","content":[{"type":"text","text":"b1"}]}]},
                {"type":"tableCell","content":[{"type":"paragraph","content":[{"type":"text","text":"c1"}]}]}]},
              {"type":"tableRow","content":[
                {"type":"tableCell","content":[{"type":"paragraph","content":[{"type":"text","text":"b2"}]}]},
                {"type":"tableCell","content":[{"type":"paragraph","content":[{"type":"text","text":"c2"}]}]}]}]},
            {"type":"paragraph","content":[{"type":"text","text":"After."}]}
        ]}"""
    }
}
