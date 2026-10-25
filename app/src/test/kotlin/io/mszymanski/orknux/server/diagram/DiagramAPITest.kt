package io.mszymanski.orknux.server.diagram

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.server.mcp.DiagramTools
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
import io.mszymanski.orknux.server.mcp.OrknuxScope
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Diagrams in Explore, through the editor's and the list's own door.
 *
 * Pinned: catalogs made, renamed, refused when taken or not empty, and removed;
 * a diagram made, listed, renamed, moved, saved and removed, each audited; the
 * note cap refused on a save and moved by its Tunable; the agents setting
 * round-tripping; the search; the download; and the editor's chat changing a
 * diagram that is read only for agents, because a person asked.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class DiagramAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val diagrams: DiagramRepository,
    @Autowired val catalogs: DiagramCatalogRepository,
    @Autowired val nodes: DiagramNodeRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val tunables: Tunables,
    @Autowired val mapper: ObjectMapper,
    @Autowired val context: WebApplicationContext,
    @Autowired val chat: DiagramChat,
) {

    private var workspaceId: Long = 0
    private var server: HttpServer? = null
    private val received = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun reset() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "diagrams-${System.nanoTime()}")).id)
        tunables.set(Tunable.DIAGRAM_NOTE_CHARACTERS, null, "alice")
        tunables.set(Tunable.DIAGRAM_ITEMS_MOST, null, "alice")
        received.clear()
    }

    @AfterEach
    fun stop() {
        server?.stop(0)
        tunables.set(Tunable.DIAGRAM_NOTE_CHARACTERS, null, "alice")
        tunables.set(Tunable.DIAGRAM_ITEMS_MOST, null, "alice")
    }

    private fun create(name: String, document: String? = null, catalogId: Long? = null): Long = graphQlTester.document(
        """mutation(${'$'}w: ID!, ${'$'}n: String!, ${'$'}c: ID, ${'$'}d: String) {
             createDiagram(workspaceId: ${'$'}w, name: ${'$'}n, catalogId: ${'$'}c, document: ${'$'}d) { id } }""",
    ).variable("w", workspaceId).variable("n", name).variable("c", catalogId).variable("d", document)
        .execute().path("createDiagram.id").entity(Long::class.java).get()

    private fun save(id: Long, document: String, revision: Int? = null) = graphQlTester.document(
        """mutation(${'$'}id: ID!, ${'$'}d: String!, ${'$'}r: Int) { saveDiagram(id: ${'$'}id, document: ${'$'}d, revision: ${'$'}r) { revision nodeCount edgeCount document } }""",
    ).variable("id", id).variable("d", document).variable("r", revision).execute()

    private fun catalog(name: String): Long = graphQlTester.document(
        """mutation { createDiagramCatalog(workspaceId: $workspaceId, name: "$name") { id } }""",
    ).execute().path("createDiagramCatalog.id").entity(Long::class.java).get()

    private fun messages(): List<String> = audit.findAll().filter { it.workspaceId == workspaceId }.map { it.message }

    private fun mvc() = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()

    @Test
    fun `catalogs are made, renamed, refused when taken or holding diagrams, and removed`() {
        val systems = catalog("Systems")
        graphQlTester.document("""mutation { createDiagramCatalog(workspaceId: $workspaceId, name: "Systems") { id } }""")
            .execute().errors().expect { it.extensions?.get("code") == "DiagramCatalogNameTaken" }.verify()
        graphQlTester.document("""mutation { renameDiagramCatalog(id: $systems, name: "Architecture") { name } }""")
            .execute().path("renameDiagramCatalog.name").entity(String::class.java).isEqualTo("Architecture")
        val id = create("Billing", catalogId = systems)
        graphQlTester.document("""mutation { deleteDiagramCatalog(id: $systems) }""")
            .execute().errors().expect { it.extensions?.get("code") == "DiagramCatalogNotEmpty" }.verify()
        graphQlTester.document("""{ diagramCatalogs(workspaceId: $workspaceId) { name diagramCount } }""").execute()
            .path("diagramCatalogs[0].diagramCount").entity(Int::class.java).isEqualTo(1)

        graphQlTester.document("""mutation { deleteDiagram(id: $id) }""").execute()
        graphQlTester.document("""mutation { deleteDiagramCatalog(id: $systems) }""").execute()
            .path("deleteDiagramCatalog").entity(Boolean::class.java).isEqualTo(true)
        assertThat(messages()).contains(
            "Diagram catalog Systems added",
            "Diagram catalog Systems renamed to Architecture",
            "Diagram catalog Architecture removed",
        )
    }

    @Test
    fun `a diagram is made in the first catalog, listed, renamed, moved, saved and removed, each audited`() {
        val id = create("Order flow")
        graphQlTester.document("""{ diagrams(workspaceId: $workspaceId) { name catalogName agentAccess nodeCount document } }""")
            .execute()
            .path("diagrams[*].name").entityList(String::class.java).containsExactly("Order flow")
            .path("diagrams[0].catalogName").entity(String::class.java).isEqualTo(Diagrams.FIRST_CATALOG)
            .path("diagrams[0].agentAccess").entity(String::class.java).isEqualTo("CHANGE")
            .path("diagrams[0].document").entity(String::class.java).isEqualTo("")

        graphQlTester.document("""mutation { renameDiagram(id: $id, name: "Orders") { name } }""")
            .execute().path("renameDiagram.name").entity(String::class.java).isEqualTo("Orders")
        val other = catalog("Processes")
        graphQlTester.document("""mutation { moveDiagram(id: $id, catalogId: $other) { catalogName } }""")
            .execute().path("moveDiagram.catalogName").entity(String::class.java).isEqualTo("Processes")

        save(
            id,
            """{"nodes":[{"id":"shop","kind":"rounded","label":"Shop","x":0,"y":0,"notes":"The web shop."},
                {"id":"pay","kind":"box","label":"Payments","x":240.4,"y":"0","color":"#AABBCC"},
                {"id":"zone","kind":"group","label":"Cloud","x":-20,"y":-20,"w":500,"h":120},
                {"id":"db","kind":"ellipse","label":"Ledger","x":480,"y":0,"parent":"zone"}],
               "edges":[{"id":"e1","from":"shop","to":"pay","label":"charges"},{"from":"pay","to":"db","kind":"dashed"}]}""",
            revision = 1,
        ).path("saveDiagram.revision").entity(Int::class.java).isEqualTo(2)
            .path("saveDiagram.nodeCount").entity(Int::class.java).isEqualTo(4)
            .path("saveDiagram.edgeCount").entity(Int::class.java).isEqualTo(2)

        val read = mapper.readTree(
            graphQlTester.document("""{ diagram(id: $id) { document } }""").execute().path("diagram.document").entity(String::class.java).get(),
        )
        val pay = read.path("nodes").get(1)
        assertThat(pay.path("x").asInt()).isEqualTo(240)
        assertThat(pay.path("color").stringValue()).isEqualTo("#aabbcc")
        assertThat(read.path("nodes").get(3).path("parent").stringValue()).isEqualTo("zone")
        assertThat(read.path("edges").get(1).path("id").stringValue()).isNotBlank()

        // A save made against the revision before is refused rather than written over this one.
        save(id, """{"nodes":[]}""", revision = 1).errors().expect { it.extensions?.get("code") == "DiagramChanged" }.verify()

        graphQlTester.document("""mutation { deleteDiagram(id: $id) }""").execute()
        assertThat(diagrams.findById(id)).isEmpty
        assertThat(nodes.findByDiagramIdOrderByPositionAsc(id)).isEmpty()
        assertThat(messages()).contains(
            "Diagram Order flow added",
            "Diagram Order flow renamed to Orders",
            "Diagram Orders moved to Processes",
            "Diagram Orders saved with 4 nodes and 2 connections",
            "Diagram Orders removed",
        )
    }

    @Test
    fun `a drawing that is not one is refused, saying what is wrong`() {
        val id = create("Broken")
        fun refused(document: String, why: String) =
            save(id, document).errors().expect { it.message?.contains(why) == true && it.extensions?.get("code") == "DiagramDocumentInvalid" }.verify()
        refused("""{"nodes":[{"kind":"blob"}]}""", "not one of")
        refused("""{"nodes":[{"id":"a"}],"edges":[{"from":"a","to":"b"}]}""", "ends at b")
        refused("""{"nodes":[{"id":"a"},{"id":"b","parent":"a"}]}""", "not a group")
        refused("""{"nodes":[{"id":"a","color":"red"}]}""", "#rrggbb")
        refused("""{"nodes":[{"id":"a"},{"id":"a"}]}""", "the id a")
    }

    /** From the editor's door: notes past the cap are refused and name the node; the cap is the installation's. */
    @Test
    fun `notes past the cap are refused from the editor, and the cap is a Tunable`() {
        val id = create("Notes")
        val long = "x".repeat(Tunable.DIAGRAM_NOTE_CHARACTERS.fallback + 1)
        save(id, """{"nodes":[{"id":"a","label":"API","notes":"$long"}]}""").errors()
            .expect { it.extensions?.get("code") == "DiagramNotesTooLong" && it.message?.contains("API") == true }.verify()
        save(id, """{"nodes":[{"id":"a","label":"API","notes":"${"x".repeat(Tunable.DIAGRAM_NOTE_CHARACTERS.fallback)}"}]}""")
            .path("saveDiagram.nodeCount").entity(Int::class.java).isEqualTo(1)

        tunables.set(Tunable.DIAGRAM_NOTE_CHARACTERS, 100, "alice")
        graphQlTester.document("""{ diagram(id: $id) { noteCharactersMost } }""").execute()
            .path("diagram.noteCharactersMost").entity(Int::class.java).isEqualTo(100)
        save(id, """{"nodes":[{"id":"a","label":"API","notes":"${"y".repeat(101)}"}]}""").errors()
            .expect { it.extensions?.get("code") == "DiagramNotesTooLong" }.verify()
        // And on a create too.
        graphQlTester.document(
            """mutation(${'$'}d: String) { createDiagram(workspaceId: $workspaceId, name: "Born long", document: ${'$'}d) { id } }""",
        ).variable("d", """{"nodes":[{"id":"a","notes":"${"z".repeat(101)}"}]}""").execute()
            .errors().expect { it.extensions?.get("code") == "DiagramNotesTooLong" }.verify()

        tunables.set(Tunable.DIAGRAM_ITEMS_MOST, 10, "alice")
        val many = (1..11).joinToString(",") { """{"id":"n$it"}""" }
        save(id, """{"nodes":[$many]}""").errors().expect { it.extensions?.get("code") == "DiagramTooLarge" }.verify()
    }

    @Test
    fun `a description is made with the diagram, changed, cleared, listed and held to its Tunable`() {
        val id = graphQlTester.document(
            """mutation { createDiagram(workspaceId: $workspaceId, name: "Described", description: "  How orders reach the ledger.  ") { id description descriptionCharactersMost } }""",
        ).execute()
            .path("createDiagram.description").entity(String::class.java).isEqualTo("How orders reach the ledger.")
            .path("createDiagram.descriptionCharactersMost").entity(Int::class.java).isEqualTo(Tunable.DIAGRAM_DESCRIPTION_CHARACTERS.fallback)
            .path("createDiagram.id").entity(Long::class.java).get()
        graphQlTester.document("""mutation { describeDiagram(id: $id, description: "Who pays whom.") { description } }""").execute()
            .path("describeDiagram.description").entity(String::class.java).isEqualTo("Who pays whom.")
        graphQlTester.document("""{ diagrams(workspaceId: $workspaceId) { description } }""").execute()
            .path("diagrams[0].description").entity(String::class.java).isEqualTo("Who pays whom.")
        assertThat(diagrams.findById(id).get().description).isEqualTo("Who pays whom.")

        tunables.set(Tunable.DIAGRAM_DESCRIPTION_CHARACTERS, 100, "alice")
        try {
            graphQlTester.document("""mutation { describeDiagram(id: $id, description: "${"d".repeat(101)}") { id } }""").execute()
                .errors().expect { it.extensions?.get("code") == "DiagramDescriptionTooLong" }.verify()
        } finally {
            tunables.set(Tunable.DIAGRAM_DESCRIPTION_CHARACTERS, null, "alice")
        }
        graphQlTester.document("""mutation { describeDiagram(id: $id, description: "   ") { description } }""").execute()
            .path("describeDiagram.description").valueIsNull()
        assertThat(messages()).contains("Diagram Described description changed")
    }

    @Test
    fun `the agents setting round-trips and is audited`() {
        val id = create("Locked")
        graphQlTester.document("""mutation { setDiagramAgentAccess(id: $id, access: READ) { agentAccess } }""").execute()
            .path("setDiagramAgentAccess.agentAccess").entity(String::class.java).isEqualTo("READ")
        graphQlTester.document("""{ diagrams(workspaceId: $workspaceId) { agentAccess } }""").execute()
            .path("diagrams[0].agentAccess").entity(String::class.java).isEqualTo("READ")
        assertThat(diagrams.findById(id).get().agentAccess).isEqualTo(DiagramAgentAccess.READ)
        graphQlTester.document("""mutation { setDiagramAgentAccess(id: $id, access: CHANGE) { agentAccess } }""").execute()
            .path("setDiagramAgentAccess.agentAccess").entity(String::class.java).isEqualTo("CHANGE")
        assertThat(messages()).contains("Diagram Locked made read only for agents", "Diagram Locked opened to changes by agents")
    }

    @Test
    fun `the search finds labels before notes, and the index lists every labelled node`() {
        val id = create(
            "Platform",
            """{"nodes":[{"id":"a","label":"Notifier","notes":"Sends the ledger totals each night."},
                {"id":"b","label":"Ledger"},{"id":"c","label":""}],"edges":[{"from":"a","to":"b","label":"reads ledger"}]}""",
        )
        val hits = graphQlTester.document("""{ searchDiagrams(workspaceId: $workspaceId, query: "ledger") { field nodeId snippet } }""")
            .execute().path("searchDiagrams[*].field").entityList(String::class.java).get()
        assertThat(hits).containsExactly("label", "edge", "notes")
        graphQlTester.document("""{ searchDiagrams(workspaceId: $workspaceId, query: "ledger", diagramId: $id) { nodeId } }""")
            .execute().path("searchDiagrams[0].nodeId").entity(String::class.java).isEqualTo("b")
        graphQlTester.document("""{ diagramNodeIndex(workspaceId: $workspaceId) { nodeId label diagramName } }""").execute()
            .path("diagramNodeIndex[*].label").entityList(String::class.java).containsExactly("Notifier", "Ledger")
    }

    @Test
    fun `each export downloads from its link, named after the diagram`() {
        val id = create("Edge & core", """{"nodes":[{"id":"a","label":"<Gateway>"},{"id":"b","label":"Core"}],"edges":[{"from":"a","to":"b"}]}""")
        val svg = mvc().perform(get("/api/diagrams/$id/export/svg")).andReturn().response
        assertThat(svg.status).isEqualTo(200)
        assertThat(svg.getHeader("Content-Disposition")).startsWith("attachment").contains("Edge & core.svg")
        assertThat(svg.getContentAsString(StandardCharsets.UTF_8)).contains("&lt;Gateway&gt;")
        val png = mvc().perform(get("/api/diagrams/$id/export/png")).andReturn().response
        assertThat(png.contentAsByteArray.take(4)).containsExactly(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        assertThat(mvc().perform(get("/api/diagrams/$id/export/gif")).andReturn().response.status).isEqualTo(404)
        assertThat(mvc().perform(get("/api/diagrams/999999999/export/png")).andReturn().response.status).isEqualTo(404)
        assertThat(messages()).contains("Diagram Edge & core exported as SVG")
    }

    /** The editor's PNG is so many pixels per drawn one, past the chat picture's Tunable; the SVG stays vector. */
    @Test
    fun `a PNG asked for at a scale is that many times the drawn width`() {
        val nodes = (0 until 12).joinToString(",") { """{"id":"n$it","label":"Part $it","x":${it * 160},"y":0,"w":140,"h":60}""" }
        val id = create("Wide", """{"nodes":[$nodes],"edges":[]}""")
        val svg = mvc().perform(get("/api/diagrams/$id/export/svg")).andReturn().response.getContentAsString(StandardCharsets.UTF_8)
        val drawn = Regex("<svg[^>]*?\\swidth=\"([0-9]+)\"").find(svg)!!.groupValues[1].toInt()
        assertThat(drawn).isGreaterThan(1600 / 2)
        assertThat(svg).doesNotContain("<image").doesNotContain("data:image")
        fun width(query: String): Int {
            val bytes = mvc().perform(get("/api/diagrams/$id/export/png$query")).andReturn().response.contentAsByteArray
            return javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes)).width
        }
        assertThat(width("?scale=1")).isEqualTo(drawn)
        assertThat(width("?scale=2")).isEqualTo(drawn * 2)
        assertThat(width("?scale=3")).isEqualTo(minOf(drawn * 3, 4096))
        // No scale: the link an agent hands out, held to the Tunable as before.
        assertThat(width("")).isEqualTo(minOf(drawn * 2, 1600))
        assertThat(mvc().perform(get("/api/diagrams/$id/export/png?scale=4")).andReturn().response.status).isEqualTo(400)
    }

    @Test
    @WithMockUser(username = "mallory", roles = ["USERS"])
    fun `somebody who cannot see the workspace cannot see or change its diagrams`() {
        val catalog = catalogs.save(DiagramCatalog(workspaceId = workspaceId, name = "Hidden", createdBy = "alice"))
        val id = requireNotNull(
            diagrams.save(Diagram(workspaceId = workspaceId, catalogId = requireNotNull(catalog.id), name = "Secret", createdBy = "alice", updatedBy = "alice")).id,
        )
        graphQlTester.document("""{ diagrams(workspaceId: $workspaceId) { name } }""").execute().errors().expect { true }.verify()
        save(id, """{"nodes":[]}""").errors().expect { true }.verify()
        assertThat(mvc().perform(get("/api/diagrams/$id/export/svg")).andReturn().response.status).isEqualTo(404)
        assertThat(diagrams.findById(id).get().revision).isEqualTo(1)
    }

    /** The editor's chat changes a diagram set to Read only for agents: a person is there asking. */
    @Test
    fun `a model asked in the editor changes the diagram, even one read only for agents`() {
        val id = create("Landscape", """{"nodes":[{"id":"web","label":"Web"}]}""")
        graphQlTester.document("""mutation { setDiagramAgentAccess(id: $id, access: READ) { id } }""").execute()
        val modelId = model(
            serve { body ->
                if (received.size == 1) {
                    val arguments = mapper.writeValueAsString(
                        mapOf("diagram" to id.toString(), "addNodes" to listOf(mapOf("id" to "api", "label" to "API", "x" to 240, "y" to 0)),
                            "addEdges" to listOf(mapOf("from" to "web", "to" to "api", "label" to "calls"))),
                    )
                    """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                       {"id":"call_1","type":"function","function":{"name":"${DiagramTools.CHANGE}","arguments":${mapper.writeValueAsString(arguments)}}}
                     ]}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}"""
                } else {
                    assertThat(body).contains("calls")
                    """{"choices":[{"message":{"role":"assistant","content":"Added the API."}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}"""
                }
            },
        )
        graphQlTester.document(
            """mutation { askDiagram(id: $id, modelId: $modelId, messages: [{role: "user", content: "Add the API"}]) {
                 answer changed diagram { nodeCount edgeCount } } }""",
        ).execute()
            .path("askDiagram.answer").entity(String::class.java).isEqualTo("Added the API.")
            .path("askDiagram.changed").entity(Boolean::class.java).isEqualTo(true)
            .path("askDiagram.diagram.nodeCount").entity(Int::class.java).isEqualTo(2)
            .path("askDiagram.diagram.edgeCount").entity(Int::class.java).isEqualTo(1)
        assertThat(received.first()).contains(DiagramTools.CHANGE).doesNotContain(DiagramTools.DELETE).doesNotContain("orknux_workflows")
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

    /** The editor's chat is told the person has the diagram open: the tools answer its url, and a model pasted it under the diagram. */
    @Test
    fun `the chat never links the diagram the person has open`() {
        val view = DiagramView(1, workspaceId, 1, "General", "Flow", null, 500, DiagramAgentAccess.CHANGE, "{}", 0, 0, 1, 2000, "alice", "", "alice", "")
        val offered = chat.offered(OrknuxScope(workspaceId = workspaceId, mayWrite = true))
        val briefing = chat.briefing(view, offered)
        assertThat(briefing).contains("never link to it")
    }
}
