package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tunables
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * Documents in the Design perspective, and their catalogs.
 *
 * Pinned: catalogs made, renamed and refused while they hold anything; a
 * document made, filed, moved, saved and removed, each audited; the content a
 * save refuses and says why, what it drops, and that a picture is held by its
 * artifact id with the address worked out here; a save over a document changed
 * since it was opened; the Tunable that caps one; the search with the words
 * around what it found; and nobody outside the workspace seeing any of it.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class DocumentAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val documents: DesignDocumentRepository,
    @Autowired val catalogs: DocumentCatalogRepository,
    @Autowired val images: DesignDocumentImageRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val tunables: Tunables,
    @Autowired val mapper: ObjectMapper,
    @Autowired val context: WebApplicationContext,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        audit.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "documents-${System.nanoTime()}")).id)
        tunables.set(Tunable.DOCUMENT_CHARACTERS_MOST, null, "alice")
    }

    @AfterEach
    fun put() {
        tunables.set(Tunable.DOCUMENT_CHARACTERS_MOST, null, "alice")
    }

    private fun catalog(name: String): Long = graphQlTester.document(
        """mutation(${'$'}w: ID!, ${'$'}n: String!) { createDocumentCatalog(workspaceId: ${'$'}w, name: ${'$'}n) { id } }""",
    ).variable("w", workspaceId).variable("n", name).execute().path("createDocumentCatalog.id").entity(Long::class.java).get()

    private fun create(name: String, catalogId: Long? = null, content: String? = null): Long = graphQlTester.document(
        """mutation(${'$'}w: ID!, ${'$'}c: ID, ${'$'}n: String!, ${'$'}d: String) {
             createDocument(workspaceId: ${'$'}w, catalogId: ${'$'}c, name: ${'$'}n, content: ${'$'}d) { id } }""",
    ).variable("w", workspaceId).variable("c", catalogId).variable("n", name).variable("d", content)
        .execute().path("createDocument.id").entity(Long::class.java).get()

    private fun save(id: Long, content: String, revision: Int? = null, pageSize: String? = null) = graphQlTester.document(
        """mutation(${'$'}id: ID!, ${'$'}d: String!, ${'$'}r: Int, ${'$'}p: String) {
             saveDocument(id: ${'$'}id, content: ${'$'}d, revision: ${'$'}r, pageSize: ${'$'}p) { revision words pageSize images content } }""",
    ).variable("id", id).variable("d", content).variable("r", revision).variable("p", pageSize).execute()

    private fun stored(id: Long): JsonNode = mapper.readTree(documents.findById(id).get().content)

    @Test
    fun `catalogs are made, renamed, listed with their counts, and refused while they hold anything`() {
        val notes = catalog("Notes")
        val plans = catalog("Plans")
        graphQlTester.document("""mutation { createDocumentCatalog(workspaceId: $workspaceId, name: "Notes") { id } }""")
            .execute().errors().expect { it.message == "This workspace already has a document catalog called Notes" }.verify()

        create("Kickoff", notes)
        graphQlTester.document("""{ documentCatalogs(workspaceId: $workspaceId) { name documentCount } }""").execute()
            .path("documentCatalogs[*].name").entityList(String::class.java).containsExactly("Notes", "Plans")
            .path("documentCatalogs[0].documentCount").entity(Int::class.java).isEqualTo(1)

        graphQlTester.document("""mutation { renameDocumentCatalog(id: $plans, name: "Roadmaps") { name } }""")
            .execute().path("renameDocumentCatalog.name").entity(String::class.java).isEqualTo("Roadmaps")
        graphQlTester.document("""mutation { deleteDocumentCatalog(id: $notes) }""").execute().errors()
            .expect { it.message == "Notes holds 1 documents; move or delete them before deleting it" }.verify()
        graphQlTester.document("""mutation { deleteDocumentCatalog(id: $plans) }""")
            .execute().path("deleteDocumentCatalog").entity(Boolean::class.java).isEqualTo(true)

        assertThat(audit.findAll().map { it.message }).contains(
            "Document catalog Notes added",
            "Document catalog Plans renamed to Roadmaps",
            "Document catalog Roadmaps removed",
        )
    }

    @Test
    fun `a document is made, filed, moved, saved and removed, and each is audited`() {
        // Made with no catalog named: filed in the workspace's first, which is made for it.
        val id = create("Brief")
        val general = requireNotNull(catalogs.findByWorkspaceIdAndName(workspaceId, Documents.FIRST_CATALOG)).id
        assertThat(documents.findById(id).get().catalogId).isEqualTo(general)
        assertThat(stored(id).path("content").get(0).path("type").stringValue()).isEqualTo("paragraph")

        val archive = catalog("Archive")
        graphQlTester.document("""mutation { moveDocument(id: $id, catalogId: $archive) { catalogName } }""")
            .execute().path("moveDocument.catalogName").entity(String::class.java).isEqualTo("Archive")
        graphQlTester.document("""mutation { renameDocument(id: $id, name: "Project brief") { name } }""")
            .execute().path("renameDocument.name").entity(String::class.java).isEqualTo("Project brief")

        save(
            id,
            """{"type":"doc","content":[
                {"type":"heading","attrs":{"level":9,"id":"x"},"content":[{"type":"text","text":"Goals"}]},
                {"type":"paragraph","content":[{"type":"text","text":"Ship the ","marks":[{"type":"bold"}]},
                  {"type":"text","text":"editor","marks":[{"type":"link","attrs":{"href":"javascript:alert(1)"}}]}]},
                {"type":"pageBreak"},
                {"type":"table","content":[{"type":"tableRow","content":[{"type":"tableHeader","attrs":{"colspan":1},
                  "content":[{"type":"paragraph","content":[{"type":"text","text":"When"}]}]}]}]}]}""",
            revision = 1,
            pageSize = "letter",
        ).path("saveDocument.revision").entity(Int::class.java).isEqualTo(2)
            .path("saveDocument.pageSize").entity(String::class.java).isEqualTo("LETTER")
            .path("saveDocument.words").entity(Int::class.java).isEqualTo(5)

        val content = stored(id).path("content")
        // A heading past h6 is an h6, and attributes nobody reads are dropped.
        assertThat(content.get(0).path("attrs").path("level").asInt()).isEqualTo(6)
        assertThat(content.get(0).path("attrs").has("id")).isFalse()
        // A link that would run a script keeps its words and loses the link.
        assertThat(content.get(1).path("content").get(1).has("marks")).isFalse()
        assertThat(content.get(2).path("type").stringValue()).isEqualTo("pageBreak")
        assertThat(documents.findById(id).get().plainText).isEqualTo("Goals\nShip the editor\nWhen")

        graphQlTester.document("""{ documents(workspaceId: $workspaceId, catalogId: $archive) { name catalogName excerpt } }""").execute()
            .path("documents[*].name").entityList(String::class.java).containsExactly("Project brief")
            .path("documents[0].excerpt").entity(String::class.java).isEqualTo("Goals Ship the editor When")
        graphQlTester.document("""{ documents(workspaceId: $workspaceId, catalogId: $general) { name } }""").execute()
            .path("documents").entityList(Any::class.java).hasSize(0)

        graphQlTester.document("""mutation { deleteDocument(id: $id) }""")
            .execute().path("deleteDocument").entity(Boolean::class.java).isEqualTo(true)
        graphQlTester.document("""{ document(id: $id) { name } }""").execute().path("document").valueIsNull()

        assertThat(audit.findAll().map { it.message }).contains(
            "Document catalog General added",
            "Document Brief added",
            "Document Brief moved to Archive",
            "Document Brief renamed to Project brief",
            "Document Project brief saved",
            "Document Project brief removed",
        )
    }

    @Test
    fun `a save made against an older revision is refused rather than written over the newer one`() {
        val id = create("Minutes")
        save(id, PARAGRAPH("First"), revision = 1).path("saveDocument.revision").entity(Int::class.java).isEqualTo(2)
        save(id, PARAGRAPH("Second"), revision = 1).errors()
            .expect { it.message == "Document Minutes was changed since it was opened; open it again before saving" }.verify()
        assertThat(documents.findById(id).get().plainText).isEqualTo("First")
    }

    @Test
    fun `content that is not a document is refused, saying what is wrong with it`() {
        val id = create("Broken")
        save(id, "not json").errors().expect { it.message == "The document cannot be saved: it is not JSON" }.verify()
        save(id, """{"type":"paragraph"}""").errors().expect { it.message?.contains("has to be a doc node") == true }.verify()
        save(id, """{"type":"doc","content":[{"type":"video"}]}""").errors()
            .expect { it.message?.startsWith("The document cannot be saved: a doc holds a node of type video, which is not one of paragraph") == true }
            .verify()
        save(id, """{"type":"doc","content":[{"type":"image","attrs":{"src":"data:image/png;base64,AAAA"}}]}""").errors()
            .expect { it.message?.contains("never written into the document") == true }.verify()
        save(id, """{"type":"doc","content":[{"type":"image","attrs":{"artifact":"DESIGN-999999999"}}]}""").errors()
            .expect { it.message == "The document cannot be saved: there is no picture DESIGN-999999999 in this workspace" }.verify()
        save(id, PARAGRAPH("x"), pageSize = "B5").errors().expect { it.message == "A page is A3, A4, A5, LETTER or LEGAL, not B5" }.verify()
        graphQlTester.document("""mutation { createDocument(workspaceId: $workspaceId, name: "  ") { id } }""")
            .execute().errors().expect { it.message?.contains("needs a name") == true }.verify()
    }

    @Test
    fun `a document past the characters an installation allows is refused with the count`() {
        tunables.set(Tunable.DOCUMENT_CHARACTERS_MOST, 10_000, "alice")
        val id = create("Long")
        save(id, PARAGRAPH("w".repeat(12_000))).errors()
            .expect { it.message?.startsWith("A document holds at most 10000 characters, and this one would hold") == true }.verify()
    }

    /** A picture is its artifact id; the address an editor draws it from is the server's, and serves the bytes. */
    @Test
    fun `a picture is held by its artifact id and served to whoever may see the workspace`() {
        val picture = importPicture()
        val id = create("Illustrated")
        save(
            id,
            """{"type":"doc","content":[{"type":"image","attrs":{"artifact":"$picture","src":"https://elsewhere.invalid/x.png",
               "caption":"The red field","width":"60"}}]}""",
        ).path("saveDocument.images").entityList(String::class.java).containsExactly(picture)

        val attrs = stored(id).path("content").get(0).path("attrs")
        assertThat(attrs.path("src").stringValue()).isEqualTo("/api/workspaces/$workspaceId/document-images/$picture")
        assertThat(attrs.path("width").asInt()).isEqualTo(60)
        assertThat(images.findByDocumentId(id).map { it.artifactId }).containsExactly(picture)
        assertThat(documents.findById(id).get().plainText).isEqualTo("The red field")

        val answer = mvc().perform(get("/api/workspaces/$workspaceId/document-images/$picture")).andReturn().response
        assertThat(answer.status).isEqualTo(200)
        assertThat(answer.contentType).isEqualTo("image/png")
        assertThat(mvc().perform(get("/api/workspaces/$workspaceId/document-images/DESIGN-999999999")).andReturn().response.status)
            .isEqualTo(404)
    }

    @Test
    fun `the search finds a document by its name and by its words, with the words around what it found`() {
        val words = "Our quarterly review covers revenue, hiring and the migration of the billing service to the new cluster."
        create("Quarterly review", content = PARAGRAPH(words))
        create("Billing notes", content = PARAGRAPH("Nothing about it here."))
        create("Unrelated", content = PARAGRAPH("Lunch menu."))

        val hits = graphQlTester.document(
            """{ searchDocuments(workspaceId: $workspaceId, text: "BILLING") { document { name catalogName } snippet } }""",
        ).execute().path("searchDocuments").entityList(Map::class.java).get()
        assertThat(hits.map { (it["document"] as Map<*, *>)["name"] }).containsExactly("Billing notes", "Quarterly review")
        assertThat(hits.map { (it["document"] as Map<*, *>)["catalogName"] }.toSet()).containsExactly(Documents.FIRST_CATALOG)
        assertThat(hits[1]["snippet"] as String).contains("migration of the billing service")

        graphQlTester.document("""{ documents(workspaceId: $workspaceId, search: "lunch") { name } }""").execute()
            .path("documents[*].name").entityList(String::class.java).containsExactly("Unrelated")
        graphQlTester.document("""{ searchDocuments(workspaceId: $workspaceId, text: "100%") { snippet } }""").execute()
            .path("searchDocuments").entityList(Any::class.java).hasSize(0)
    }

    @Test
    @WithMockUser(username = "mallory", roles = ["USERS"])
    fun `somebody who cannot see the workspace can neither read nor write its documents`() {
        val catalog = catalogs.save(DocumentCatalog(workspaceId = workspaceId, name = "Secret", createdBy = "alice"))
        val id = requireNotNull(
            documents.save(
                DesignDocument(
                    workspaceId = workspaceId, catalogId = requireNotNull(catalog.id), name = "Secret plan",
                    content = PARAGRAPH("hidden"), plainText = "hidden", createdBy = "alice", updatedBy = "alice",
                ),
            ).id,
        )
        graphQlTester.document("""{ documents(workspaceId: $workspaceId) { name } }""").execute().errors().expect { true }.verify()
        graphQlTester.document("""{ document(id: $id) { name } }""").execute().path("document").valueIsNull()
        graphQlTester.document("""{ searchDocuments(workspaceId: $workspaceId, text: "hidden") { snippet } }""").execute()
            .errors().expect { true }.verify()
        save(id, PARAGRAPH("mine now")).errors().expect { true }.verify()
        assertThat(documents.findById(id).get().revision).isEqualTo(1)
    }

    private fun importPicture(): String {
        val png = Base64.getEncoder().encodeToString(png())
        return graphQlTester.document(
            """mutation { importDesignImage(input: { workspaceId: $workspaceId, image: "$png", name: "Red" }) { id } }""",
        ).execute().path("importDesignImage.id").entity(String::class.java).get()
    }

    private fun mvc() = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()

    companion object {
        /** A document of one paragraph. */
        @Suppress("FunctionName")
        fun PARAGRAPH(text: String) = """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"$text"}]}]}"""

        /** A red picture, big enough to find again in a PDF. */
        fun png(): ByteArray {
            val image = BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB)
            val pen = image.createGraphics()
            pen.color = Color.RED
            pen.fillRect(0, 0, 40, 30)
            pen.dispose()
            return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        }
    }
}
