package io.mszymanski.orknux.server.diagram

import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.chat.AgentTools
import io.mszymanski.orknux.server.chat.BuiltInTools
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.LlmSessionStore
import io.mszymanski.orknux.server.mcp.DiagramTools
import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.mcp.OrknuxTools
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tunables
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.apache.pdfbox.Loader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The orknux_* diagram tools, as an agent with nobody signed in calls them.
 *
 * Pinned: what is offered to a scope that may write and one that may not; a
 * diagram drawn, changed and read back through the same door as the editor,
 * the note cap with it; one node's details with its notes whole and its
 * neighbours; the search's order; a diagram read only for agents refused to the
 * change and the delete, and said so in the list; the delete tool hidden on a
 * new agent and working once its owner shows it; each export format a file of
 * its kind, kept in the session's store with what it is.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class DiagramToolsTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val tools: OrknuxTools,
    @Autowired val agentTools: AgentTools,
    @Autowired val builtIns: BuiltInTools,
    @Autowired val agents: AgentRepository,
    @Autowired val diagrams: DiagramRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val tunables: Tunables,
    @Autowired val sessions: LlmSessionRecorder,
    @Autowired val store: LlmSessionStore,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0
    private lateinit var scope: OrknuxScope

    @BeforeEach
    fun reset() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "diagram-tools-${System.nanoTime()}")).id)
        scope = OrknuxScope(workspaceId = workspaceId, mayWrite = true, actor = "Cartographer")
        tunables.set(Tunable.DIAGRAM_NOTE_CHARACTERS, null, "alice")
        tunables.set(Tunable.DIAGRAM_NOTES_SHOWN_CHARACTERS, null, "alice")
    }

    @AfterEach
    fun back() {
        tunables.set(Tunable.DIAGRAM_NOTE_CHARACTERS, null, "alice")
        tunables.set(Tunable.DIAGRAM_NOTES_SHOWN_CHARACTERS, null, "alice")
    }

    private fun call(name: String, arguments: String, on: OrknuxScope = scope): JsonNode = mapper.readTree(tools.run(on, name, arguments))

    private fun landscape(): Long {
        val made = call(
            DiagramTools.CREATE,
            """{"name":"Landscape","nodes":[
                 {"id":"web","kind":"rounded","label":"Web shop","x":0,"y":0,"notes":"Customers order here. ${"It is long. ".repeat(40)}"},
                 {"id":"cloud","kind":"group","label":"Cloud","x":200,"y":-40,"w":420,"h":200},
                 {"id":"api","label":"Orders API","x":240,"y":0,"parent":"cloud","notes":"Takes orders and writes them to the ledger."},
                 {"id":"ledger","kind":"ellipse","label":"Ledger","x":440,"y":0,"parent":"cloud"}],
               "edges":[{"id":"e1","from":"web","to":"api","label":"places order"},{"id":"e2","from":"api","to":"ledger","kind":"dashed"}]}""",
        )
        assertThat(made.has("error")).describedAs(made.toString()).isFalse()
        return made.path("id").asLong()
    }

    @Test
    fun `a scope that may not write is offered the reading tools only, and refused the rest`() {
        assertThat(tools.specs(scope).map { it.name }).containsAll(DiagramTools.NAMES)
        val reading = scope.copy(mayWrite = false)
        assertThat(tools.specs(reading).map { it.name }.filter { it in DiagramTools.NAMES })
            .containsExactlyInAnyOrder(DiagramTools.LIST, DiagramTools.READ, DiagramTools.NODE, DiagramTools.SEARCH, DiagramTools.EXPORT)
        assertThat(call(DiagramTools.CREATE, """{"name":"Nope"}""", reading).path("error").stringValue()).contains("may read")
        assertThat(diagrams.findByWorkspaceIdOrderByUpdatedAtDescIdDesc(workspaceId)).isEmpty()
    }

    @Test
    fun `the tools draw, change and read a diagram through the editor's door, under the agent's name`() {
        SecurityContextHolder.clearContext()
        val id = landscape()
        val changed = call(
            DiagramTools.CHANGE,
            """{"diagram":"landscape","updateNodes":[{"id":"ledger","label":"General ledger","color":"#336699"}],
               "addNodes":[{"id":"mail","label":"Mailer","x":0,"y":200}],
               "addEdges":[{"from":"api","to":"mail","label":"notifies"}],"removeEdges":["e2"]}""",
        )
        assertThat(changed.has("error")).describedAs(changed.toString()).isFalse()
        assertThat(changed.path("url").stringValue()).endsWith("/workspace/$workspaceId/explore/diagrams/$id")
        val read = call(DiagramTools.READ, """{"diagram":"$id"}""")
        assertThat(read.path(DiagramTools.AGENTS).stringValue()).isEqualTo(DiagramTools.CHANGES)
        assertThat((0 until read.path("nodes").size()).map { read.path("nodes").get(it).path("label").stringValue() })
            .containsExactly("Web shop", "Cloud", "Orders API", "General ledger", "Mailer")
        assertThat(read.path("edges").size()).isEqualTo(2)
        // The whole read cuts long notes and says how long they were.
        val web = read.path("nodes").get(0)
        assertThat(web.path("notes").stringValue().length).isLessThanOrEqualTo(Tunable.DIAGRAM_NOTES_SHOWN_CHARACTERS.fallback + 1)
        assertThat(web.path("notesLength").asInt()).isGreaterThan(Tunable.DIAGRAM_NOTES_SHOWN_CHARACTERS.fallback)

        // Removing a node takes its connections and leaves its group's children in place.
        val removed = call(DiagramTools.CHANGE, """{"diagram":"$id","removeNodes":["cloud","mail"]}""")
        assertThat(removed.path("nodes").size()).isEqualTo(3)
        assertThat(removed.path("edges").size()).isEqualTo(1)
        assertThat(removed.path("nodes").get(1).has("parent")).isFalse()
        assertThat(call(DiagramTools.CHANGE, """{"diagram":"$id","removeNodes":["nothing"]}""").path("error").stringValue())
            .contains("has no node nothing")

        val listed = call(DiagramTools.LIST, "{}")
        assertThat(listed.path("catalogs").get(0).path("diagrams").get(0).path("name").stringValue()).isEqualTo("Landscape")
        assertThat(audit.findAll().filter { it.workspaceId == workspaceId && it.message.startsWith("Diagram Landscape") }.map { it.userId }.toSet())
            .containsExactly("Cartographer")
    }

    /** From the tools' door: notes past the cap are refused, on a create and on a change. */
    @Test
    fun `notes past the cap are refused from the tools`() {
        val id = landscape()
        // Above the web shop's notes, which are held, and below what is sent.
        tunables.set(Tunable.DIAGRAM_NOTE_CHARACTERS, 600, "alice")
        val long = "n".repeat(601)
        assertThat(call(DiagramTools.CHANGE, """{"diagram":"$id","updateNodes":[{"id":"ledger","notes":"$long"}]}""").path("error").stringValue())
            .contains("Ledger").contains("600")
        assertThat(call(DiagramTools.CREATE, """{"name":"Long","nodes":[{"label":"A","notes":"$long"}]}""").path("error").stringValue())
            .contains("600")
        assertThat(call(DiagramTools.CHANGE, """{"diagram":"$id","updateNodes":[{"id":"ledger","notes":"${"n".repeat(600)}"}]}""").has("error")).isFalse()
        assertThat(diagrams.findByWorkspaceIdOrderByUpdatedAtDescIdDesc(workspaceId).map { it.name }).doesNotContain("Long")
    }

    @Test
    fun `a node's details carry its whole notes, its group, what it holds and its connections`() {
        val id = landscape()
        val api = call(DiagramTools.NODE, """{"diagram":"Landscape","node":"Orders API"}""")
        assertThat(api.path("notes").stringValue()).isEqualTo("Takes orders and writes them to the ledger.")
        assertThat(api.path("group").path("label").stringValue()).isEqualTo("Cloud")
        val connections = api.path("connections")
        assertThat((0 until connections.size()).map { connections.get(it).path("direction").stringValue() + ":" + connections.get(it).path("node").path("id").stringValue() })
            .containsExactly("in:web", "out:ledger")
        assertThat(connections.get(0).path("label").stringValue()).isEqualTo("places order")
        assertThat(api.path("url").stringValue()).endsWith("/explore/diagrams/$id?node=api")

        val web = call(DiagramTools.NODE, """{"diagram":"$id","node":"web"}""")
        assertThat(web.path("notes").stringValue()).endsWith("It is long. ").hasSizeGreaterThan(400)
        val cloud = call(DiagramTools.NODE, """{"diagram":"$id","node":"cloud"}""")
        assertThat(cloud.path("contains").size()).isEqualTo(2)
        assertThat(call(DiagramTools.NODE, """{"diagram":"$id","node":"nowhere"}""").path("error").stringValue()).contains("has no node")
    }

    @Test
    fun `the search ranks labels over connections over notes, across diagrams or within one`() {
        val first = landscape()
        call(DiagramTools.CREATE, """{"name":"Finance","nodes":[{"id":"gl","label":"Ledger"},{"id":"x","label":"Reports","notes":"Built from the ledger."}]}""")
        val all = call(DiagramTools.SEARCH, """{"query":"ledger"}""").path("hits")
        val found = (0 until all.size()).map { all.get(it).path("found").stringValue() + ":" + (all.get(it).path("node").path("id").stringValue() ?: "") }
        assertThat(found.first()).isEqualTo("label:gl")
        assertThat(found).contains("label:ledger", "notes:api", "notes:x")
        assertThat(found.indexOf("label:ledger")).isLessThan(found.indexOf("notes:api"))
        val snippet = all.get(found.indexOf("notes:api")).path("snippet").stringValue()
        assertThat(snippet).containsIgnoringCase("ledger")

        val within = call(DiagramTools.SEARCH, """{"query":"places order","diagram":"$first"}""").path("hits")
        assertThat(within.get(0).path("found").stringValue()).isEqualTo("edge")
        assertThat(within.get(0).path("diagram").path("id").asLong()).isEqualTo(first)
        assertThat(call(DiagramTools.SEARCH, """{"query":"zzz-nothing"}""").path("hits").size()).isEqualTo(0)
    }

    @Test
    fun `the tools take a description, give it back, change it alone, and find it`() {
        val made = call(DiagramTools.CREATE, """{"name":"Payments","${DiagramTools.DESCRIPTION}":"How a card payment is settled."}""")
        assertThat(made.path(DiagramTools.DESCRIPTION).stringValue()).describedAs(made.toString()).isEqualTo("How a card payment is settled.")
        val id = made.path("id").asLong()
        val listed = call(DiagramTools.LIST, "{}").path("catalogs").get(0).path("diagrams").get(0)
        assertThat(listed.path(DiagramTools.DESCRIPTION).stringValue()).isEqualTo("How a card payment is settled.")

        val changed = call(DiagramTools.CHANGE, """{"diagram":"$id","${DiagramTools.DESCRIPTION}":"How a refund is settled."}""")
        assertThat(changed.has("error")).describedAs(changed.toString()).isFalse()
        assertThat(call(DiagramTools.READ, """{"diagram":"$id"}""").path(DiagramTools.DESCRIPTION).stringValue()).isEqualTo("How a refund is settled.")
        assertThat(diagrams.findById(id).get().revision).describedAs("the drawing was not written for a description alone").isEqualTo(1)

        val hits = call(DiagramTools.SEARCH, """{"query":"refund"}""").path("hits")
        assertThat(hits.get(0).path("found").stringValue()).isEqualTo("description")
        assertThat(hits.get(0).path("diagram").path("id").asLong()).isEqualTo(id)

        tunables.set(Tunable.DIAGRAM_DESCRIPTION_CHARACTERS, 100, "alice")
        try {
            assertThat(call(DiagramTools.CHANGE, """{"diagram":"$id","${DiagramTools.DESCRIPTION}":"${"x".repeat(101)}","addNodes":[{"label":"A"}]}""").path("error").stringValue())
                .contains("100")
            assertThat(call(DiagramTools.READ, """{"diagram":"$id"}""").path("nodes").size()).describedAs("nothing written").isEqualTo(0)
        } finally {
            tunables.set(Tunable.DIAGRAM_DESCRIPTION_CHARACTERS, null, "alice")
        }
        call(DiagramTools.CHANGE, """{"diagram":"$id","${DiagramTools.DESCRIPTION}":null}""")
        assertThat(call(DiagramTools.READ, """{"diagram":"$id"}""").path(DiagramTools.DESCRIPTION).isNull).isTrue()
    }

    @Test
    fun `a diagram read only for agents is said so, and its change and delete are refused`() {
        val id = landscape()
        graphQlTester.document("""mutation { setDiagramAgentAccess(id: $id, access: READ) { id } }""").execute()
        assertThat(call(DiagramTools.LIST, "{}").path("catalogs").get(0).path("diagrams").get(0).path(DiagramTools.AGENTS).stringValue())
            .isEqualTo(DiagramTools.READS)
        assertThat(call(DiagramTools.READ, """{"diagram":"$id"}""").path(DiagramTools.AGENTS).stringValue()).isEqualTo(DiagramTools.READS)
        val refused = call(DiagramTools.CHANGE, """{"diagram":"$id","removeNodes":["web"]}""")
        assertThat(refused.path("code").stringValue()).isEqualTo("DiagramReadOnly")
        assertThat(refused.path("error").stringValue()).contains("read only")
        assertThat(call(DiagramTools.DELETE, """{"diagram":"$id"}""").path("code").stringValue()).isEqualTo("DiagramReadOnly")
        assertThat(diagrams.findById(id)).isPresent
        assertThat(call(DiagramTools.READ, """{"diagram":"$id"}""").path("nodes").size()).isEqualTo(4)
    }

    @Test
    fun `the delete tool starts hidden on a new agent, and works once its owner shows it`() {
        assertThat(builtIns.orknuxStartsHidden()).containsExactly(DiagramTools.DELETE)
        // Said to the agent form too, so granting Orknux access afresh there leaves it hidden as well.
        graphQlTester.document("{ builtInTools { name startsHidden } }").execute()
            .path("builtInTools[?(@.startsHidden == true)].name").entityList(String::class.java).containsExactly(DiagramTools.DELETE)
        val agentId = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Mapper", type: LLM, orknuxAccess: true }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        val agent = requireNotNull(agents.findByIdOrNull(agentId))
        assertThat(agent.hiddenTools).contains(DiagramTools.DELETE)
        // The rest start at Offer, as every orknux_* tool does: found under a tool limit, carried without one.
        assertThat(agent.requiredTools).doesNotContain(DiagramTools.SEARCH, DiagramTools.CHANGE, DiagramTools.EXPORT, DiagramTools.DELETE)
        fun offered(of: io.mszymanski.orknux.server.agent.Agent) = agentTools.offeringFor(of).let { it.core + it.searchable }.map { it.name }
        assertThat(offered(agent)).contains(DiagramTools.SEARCH).doesNotContain(DiagramTools.DELETE)

        agent.hiddenTools.remove(DiagramTools.DELETE)
        agents.save(agent)
        assertThat(offered(requireNotNull(agents.findByIdOrNull(agentId)))).contains(DiagramTools.DELETE)
        val id = landscape()
        assertThat(call(DiagramTools.DELETE, """{"diagram":"$id"}""").path("deleted").stringValue()).isEqualTo("Landscape")
        assertThat(diagrams.findById(id)).isEmpty
    }

    @Test
    fun `each export is a file of its kind, kept in the conversation with what it is`() {
        val id = call(
            DiagramTools.CREATE,
            """{"name":"Ports & <adapters>","nodes":[{"id":"a","label":"In <&> out \"quoted\""},{"id":"g","kind":"group","label":"Core"},
               {"id":"b","kind":"diamond","label":"Decide","parent":"g"}],"edges":[{"from":"a","to":"b","label":"asks"}]}""",
        ).path("id").asLong()
        val session = sessions.open(workspaceId, "test", "diagram-export-${System.nanoTime()}")
        val inChat = scope.copy(session = session)
        fun exported(format: String): Pair<JsonNode, String> {
            val answer = call(DiagramTools.EXPORT, """{"diagram":"$id","format":"$format"}""", inChat)
            assertThat(answer.has("error")).describedAs(answer.toString()).isFalse()
            assertThat(answer.path("download").stringValue()).endsWith("/api/diagrams/$id/export/$format")
            val key = answer.path("contentKey").stringValue()
            assertThat(store.kindOf(session, key)?.contentType).isEqualTo(answer.path("contentType").stringValue())
            return answer to mapper.readTree(requireNotNull(store.get(session, key))).stringValue()
        }

        val (png, pngValue) = exported("png")
        assertThat(store.kindOf(session, png.path("contentKey").stringValue())?.binary).isTrue()
        assertThat(Base64.getDecoder().decode(pngValue).take(4))
            .containsExactly(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

        val (svg, svgValue) = exported("svg")
        assertThat(store.kindOf(session, svg.path("contentKey").stringValue())?.binary).isFalse()
        val parsed = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(svgValue.toByteArray()))
        assertThat(parsed.documentElement.tagName).isEqualTo("svg")
        // Read back as the words typed, which a parser only can where every one was escaped.
        assertThat(parsed.documentElement.textContent).contains("In <&> out").contains("\"quoted\"").contains("Decide")
        assertThat(svgValue).contains("&lt;&amp;&gt;")

        val (_, pdfValue) = exported("pdf")
        Loader.loadPDF(Base64.getDecoder().decode(pdfValue)).use { assertThat(it.numberOfPages).isEqualTo(1) }

        val (_, mermaid) = exported("mermaid")
        assertThat(mermaid).startsWith("flowchart LR").contains("subgraph").contains("{\"Decide\"}").contains("-->|\"asks\"|")
        val (_, json) = exported("json")
        assertThat(mapper.readTree(json).path("nodes").size()).isEqualTo(3)

        assertThat(call(DiagramTools.EXPORT, """{"diagram":"$id","format":"gif"}""").path("error").stringValue()).contains("png")
        // Through the MCP endpoint there is no conversation: the link is the answer.
        assertThat(call(DiagramTools.EXPORT, """{"diagram":"$id","format":"png"}""").has("contentKey")).isFalse()
    }

    @Test
    fun `a drawing tool puts one picture per diagram in a conversation`() {
        val session = sessions.open(workspaceId, "test", "diagram-picture-${System.nanoTime()}")
        val made = call(DiagramTools.CREATE, """{"name":"Pictured","nodes":[{"label":"One"}]}""", scope.copy(session = session))
        assertThat(made.path(AgentTools.PICTURE_KEY).stringValue()).isEqualTo("diagram:" + made.path("id").asLong())
        assertThat(Base64.getDecoder().decode(made.path(AgentTools.PICTURE).stringValue()).take(2))
            .containsExactly(0x89.toByte(), 'P'.code.toByte())
        assertThat(call(DiagramTools.CREATE, """{"name":"Unpictured"}""").has(AgentTools.PICTURE)).isFalse()
    }
}
