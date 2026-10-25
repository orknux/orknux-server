package io.mszymanski.orknux.server.design

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.server.mcp.DocumentTools
import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.mcp.OrknuxTools
import io.mszymanski.orknux.server.revision.ComponentRevision
import io.mszymanski.orknux.server.revision.ComponentRevisionKind
import io.mszymanski.orknux.server.revision.ComponentRevisionRepository
import io.mszymanski.orknux.server.revision.RevisionSweeper
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
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A document's history: what every door's save keeps, and putting a version back.
 *
 * Pinned: the editor's Save, its Save of an import, the orknux_* tools and the
 * document chat each keep the state they displace, saying which door saved it;
 * the saves one turn of the chat makes keep one version between them; a
 * version reads as Markdown; a restore puts the content back as a save of its
 * own, keeps what it replaced and is audited; the count kept per document is
 * a Tunable, honoured as a save adds one and by the sweep, and the days the
 * other kinds are kept for do not take a document's; deleting a document takes
 * its history; and nobody outside the workspace reads or restores any of it.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class DocumentHistoryTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val documents: DesignDocumentRepository,
    @Autowired val catalogs: DocumentCatalogRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val revisions: ComponentRevisionRepository,
    @Autowired val tools: OrknuxTools,
    @Autowired val tunables: Tunables,
    @Autowired val sweeper: RevisionSweeper,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0
    private var server: HttpServer? = null
    private val received = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun reset() {
        audit.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "document-history-${System.nanoTime()}")).id)
        tunables.set(Tunable.DOCUMENT_VERSIONS_KEPT, null, "alice")
        received.clear()
    }

    @AfterEach
    fun put() {
        tunables.set(Tunable.DOCUMENT_VERSIONS_KEPT, null, "alice")
        server?.stop(0)
    }

    private fun create(name: String, text: String): Long = graphQlTester.document(
        """mutation(${'$'}w: ID!, ${'$'}n: String!, ${'$'}d: String) { createDocument(workspaceId: ${'$'}w, name: ${'$'}n, content: ${'$'}d) { id } }""",
    ).variable("w", workspaceId).variable("n", name).variable("d", DocumentAPITest.PARAGRAPH(text))
        .execute().path("createDocument.id").entity(Long::class.java).get()

    private fun save(id: Long, text: String, via: String? = null) = graphQlTester.document(
        """mutation(${'$'}id: ID!, ${'$'}d: String!, ${'$'}v: String) { saveDocument(id: ${'$'}id, content: ${'$'}d, via: ${'$'}v) { revision updatedVia } }""",
    ).variable("id", id).variable("d", DocumentAPITest.PARAGRAPH(text)).variable("v", via).execute()

    private fun history(id: Long) = graphQlTester.document(
        """{ componentRevisions(kind: DOCUMENT, componentId: $id, limit: 100) { id savedBy door changes { field } } }""",
    ).execute()

    private fun kept(id: Long): List<ComponentRevision> =
        revisions.findAll().filter { it.kind == ComponentRevisionKind.DOCUMENT && it.componentId == id }.sortedByDescending { it.id }

    private fun markdownOf(revision: ComponentRevision): String = graphQlTester.document(
        """{ componentRevision(id: ${revision.id}) { content contentLanguage readable } }""",
    ).execute().path("componentRevision.contentLanguage").entity(String::class.java).isEqualTo("markdown")
        .path("componentRevision.readable").entity(String::class.java).get()

    @Test
    fun `every save of the editor keeps what it displaced, saying which door saved it`() {
        val id = create("Plan", "First")
        save(id, "Second").path("saveDocument.updatedVia").entity(String::class.java).isEqualTo("EDITOR")
        save(id, "Imported", via = "IMPORT").path("saveDocument.updatedVia").entity(String::class.java).isEqualTo("IMPORT")
        // A door the editor may not claim is its own Save.
        save(id, "Third", via = "RESTORE").path("saveDocument.updatedVia").entity(String::class.java).isEqualTo("EDITOR")

        history(id)
            .path("componentRevisions[*].door").entityList(String::class.java).containsExactly("IMPORT", "EDITOR", "EDITOR")
            .path("componentRevisions[*].savedBy").entityList(String::class.java).containsExactly("alice", "alice", "alice")
            .path("componentRevisions[0].changes[*].field").entityList(String::class.java).containsExactly("content")
        assertThat(kept(id).map { markdownOf(it) }).containsExactly("Imported\n", "Second\n", "First\n")

        graphQlTester.document("""{ componentCurrentVersion(kind: DOCUMENT, componentId: $id) { savedBy door readable changes { field } } }""")
            .execute()
            .path("componentCurrentVersion.door").entity(String::class.java).isEqualTo("EDITOR")
            .path("componentCurrentVersion.readable").entity(String::class.java).isEqualTo("Third\n")
            .path("componentCurrentVersion.changes[*].field").entityList(String::class.java).containsExactly("content")

        // A rename is not a version of what it says.
        graphQlTester.document("""mutation { renameDocument(id: $id, name: "Roadmap") { name } }""").execute()
        assertThat(kept(id)).hasSize(3)
    }

    @Test
    fun `the tools keep a version for every save, as the tools' door`() {
        val id = create("Notes", "Intro")
        SecurityContextHolder.clearContext()
        val scope = OrknuxScope(workspaceId = workspaceId, mayWrite = true, actor = "Writer")
        tools.run(scope, DocumentTools.APPEND, """{"document":"$id","heading":"One","markdown":"a"}""")
        tools.run(scope, DocumentTools.APPEND, """{"document":"$id","heading":"Two","markdown":"b"}""")

        val held = kept(id)
        assertThat(held.map { it.door }).containsExactly("TOOL", "EDITOR")
        assertThat(held.map { it.savedBy }).containsExactly("Writer", "alice")
        assertThat(documents.findById(id).get().updatedVia).isEqualTo("TOOL")

        // A document a tool makes says so too.
        val made = mapper.readTree(tools.run(scope, DocumentTools.CREATE, """{"name":"Made by a tool","markdown":"x"}""")).path("id").asLong()
        assertThat(documents.findById(made).get().updatedVia).isEqualTo("TOOL")
    }

    /** Two saves in one turn of the document chat: one version, the state before the turn, and the live one is the chat's. */
    @Test
    fun `the saves of one chat turn keep one version between them`() {
        val id = create("Brief", "Intro")
        val modelId = model(
            serve { _ ->
                when (received.size) {
                    1, 2 -> {
                        val arguments = mapper.writeValueAsString(mapOf("document" to id.toString(), "heading" to "Part ${received.size}", "markdown" to "Text."))
                        """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                           {"id":"call_${received.size}","type":"function","function":{"name":"${DocumentTools.APPEND}","arguments":${mapper.writeValueAsString(arguments)}}}
                         ]}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}"""
                    }
                    else -> """{"choices":[{"message":{"role":"assistant","content":"Added two parts."}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}"""
                }
            },
        )
        val ask = """mutation { askDocument(id: $id, modelId: $modelId, messages: [{role: "user", content: "Add parts"}]) { changed } }"""
        graphQlTester.document(ask).execute().path("askDocument.changed").entity(Boolean::class.java).isEqualTo(true)

        val held = kept(id)
        assertThat(held).hasSize(1)
        assertThat(held.single().door).isEqualTo("EDITOR")
        assertThat(markdownOf(held.single())).isEqualTo("Intro\n")
        val now = documents.findById(id).get()
        assertThat(now.updatedVia).isEqualTo("CHAT")
        assertThat(now.plainText).contains("Part 1", "Part 2")

        // The next turn is another version: what the last one left.
        received.clear()
        graphQlTester.document(ask).execute()
        assertThat(kept(id).map { it.door }).containsExactly("CHAT", "EDITOR")
    }

    @Test
    fun `a version restored is a save of its own, keeps what it replaced and is audited`() {
        val id = create("Charter", "Original")
        save(id, "Rewritten")
        graphQlTester.document("""mutation { renameDocument(id: $id, name: "Team charter") { name } }""").execute()
        val original = kept(id).single()

        graphQlTester.document("""mutation { restoreComponentRevision(id: ${original.id}) }""")
            .execute().path("restoreComponentRevision").entity(Boolean::class.java).isEqualTo(true)

        val now = documents.findById(id).get()
        assertThat(now.plainText).isEqualTo("Original")
        assertThat(now.updatedVia).isEqualTo("RESTORE")
        // The name stays as it is now; a version is of what it says.
        assertThat(now.name).isEqualTo("Team charter")
        assertThat(now.revision).isEqualTo(3)
        val held = kept(id)
        assertThat(held).hasSize(2)
        assertThat(markdownOf(held.first())).isEqualTo("Rewritten\n")
        assertThat(revisions.findById(requireNotNull(original.id))).isPresent()
        assertThat(audit.findAll().map { it.message })
            .anyMatch { it.startsWith("Document Team charter restored to the version saved on ") }
            .noneMatch { it == "Document Team charter saved" }

        // And the restore is itself undone by restoring what it replaced.
        graphQlTester.document("""mutation { restoreComponentRevision(id: ${held.first().id}) }""").execute()
        assertThat(documents.findById(id).get().plainText).isEqualTo("Rewritten")
        assertThat(kept(id).first().door).isEqualTo("RESTORE")
    }

    @Test
    fun `how many versions are kept is a Tunable, honoured as a save adds one and by the sweep`() {
        tunables.set(Tunable.DOCUMENT_VERSIONS_KEPT, 2, "alice")
        val id = create("Log", "v0")
        (1..5).forEach { save(id, "v$it") }
        assertThat(kept(id).map { markdownOf(it) }).containsExactly("v4\n", "v3\n")

        tunables.set(Tunable.DOCUMENT_VERSIONS_KEPT, 0, "alice")
        (6..8).forEach { save(id, "v$it") }
        assertThat(kept(id)).hasSize(5)

        // Lowered with no save since: the sweep prunes to the count.
        tunables.set(Tunable.DOCUMENT_VERSIONS_KEPT, 1, "alice")
        sweeper.sweep()
        assertThat(kept(id).map { markdownOf(it) }).containsExactly("v7\n")
    }

    @Test
    fun `a document's history is not taken by the days the other kinds are kept for`() {
        val id = create("Old", "Long ago")
        save(id, "Now")
        val held = kept(id).single()
        revisions.save(
            ComponentRevision(
                workspaceId = workspaceId, kind = ComponentRevisionKind.DOCUMENT, componentId = id, name = "Old",
                savedAt = OffsetDateTime.now().minusYears(1), savedBy = "alice",
                recordedAt = OffsetDateTime.now().minusYears(1), snapshot = held.snapshot, door = "EDITOR",
            ),
        )
        sweeper.sweep()
        assertThat(kept(id)).hasSize(2)
    }

    @Test
    fun `deleting a document takes its history`() {
        val id = create("Scratch", "a")
        save(id, "b")
        assertThat(kept(id)).hasSize(1)
        graphQlTester.document("""mutation { deleteDocument(id: $id) }""").execute()
        assertThat(kept(id)).isEmpty()
    }

    @Test
    @WithMockUser(username = "mallory", roles = ["USERS"])
    fun `somebody who cannot see the workspace can neither read nor restore a document's history`() {
        val catalog = catalogs.save(DocumentCatalog(workspaceId = workspaceId, name = "Secret", createdBy = "alice"))
        val held = documents.save(
            DesignDocument(
                workspaceId = workspaceId, catalogId = requireNotNull(catalog.id), name = "Secret plan",
                content = DocumentAPITest.PARAGRAPH("b"), plainText = "b", createdBy = "alice", updatedBy = "alice",
            ),
        )
        val id = requireNotNull(held.id)
        val revision = revisions.save(
            ComponentRevision(
                workspaceId = workspaceId, kind = ComponentRevisionKind.DOCUMENT, componentId = id, name = held.name,
                savedAt = OffsetDateTime.now(), savedBy = "alice", snapshot = """{"version":1,"name":"Secret plan","content":${mapper.writeValueAsString(DocumentAPITest.PARAGRAPH("a"))}}""",
            ),
        )
        graphQlTester.document("""{ componentRevisions(kind: DOCUMENT, componentId: $id) { id } }""").execute().errors().expect { true }.verify()
        graphQlTester.document("""{ componentRevision(id: ${revision.id}) { content } }""").execute().errors().expect { true }.verify()
        graphQlTester.document("""mutation { restoreComponentRevision(id: ${revision.id}) }""").execute().errors().expect { true }.verify()
        assertThat(documents.findById(id).get().plainText).isEqualTo("b")
    }

    private fun serve(answer: (String) -> String): String {
        val started = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        started.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            received += body
            val bytes = answer(body).toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        started.start()
        server = started
        return "http://${started.address.hostString}:${started.address.port}"
    }

    private fun model(endpoint: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub ${System.nanoTime()}", endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }
}
