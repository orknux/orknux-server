package io.mszymanski.orknux.server.diagram

import io.mszymanski.orknux.server.mcp.DiagramTools
import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.mcp.OrknuxTools
import io.mszymanski.orknux.server.revision.ComponentRevision
import io.mszymanski.orknux.server.revision.ComponentRevisionKind
import io.mszymanski.orknux.server.revision.ComponentRevisionRepository
import io.mszymanski.orknux.server.revision.ComponentSnapshot
import io.mszymanski.orknux.server.revision.RevisionSweeper
import io.mszymanski.orknux.server.security.ActingIn
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tunables
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import java.time.OffsetDateTime

/**
 * A diagram's history: what every door's save keeps, and putting a version back.
 *
 * Pinned: the editor's save, the orknux_* tools and the diagram chat each keep
 * the drawing they displace, saying which door saved it; one visit of an
 * editor and one turn of the chat each keep one version; a version holds the
 * whole drawing - nodes, lines with their corners, colours and widths, layers -
 * and reads as an SVG; a restore puts the drawing and its description back as
 * a save of its own, keeps what it replaced, is audited, and is refused to an
 * agent on a diagram read only for agents while a person may always; the count
 * kept per diagram is a Tunable, honoured as a save adds one and by the sweep,
 * and the days the other kinds are kept for do not take it; deleting a diagram
 * takes its history.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class DiagramHistoryTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val diagrams: DiagramRepository,
    @Autowired val api: DiagramAPI,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val revisions: ComponentRevisionRepository,
    @Autowired val tools: OrknuxTools,
    @Autowired val tunables: Tunables,
    @Autowired val sweeper: RevisionSweeper,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "diagram-history-${System.nanoTime()}")).id)
        tunables.set(Tunable.DIAGRAM_VERSIONS_KEPT, null, "alice")
    }

    @AfterEach
    fun put() {
        tunables.set(Tunable.DIAGRAM_VERSIONS_KEPT, null, "alice")
    }

    /** A drawing of [labels], one box each, joined in a line, the first line styled and bent. */
    private fun drawing(vararg labels: String): String {
        val nodes = labels.mapIndexed { at, label -> """{"id":"n$at","label":"$label","x":${at * 200},"y":0,"border":"#112233","borderWidth":3}""" }
        val edges = (1 until labels.size).map { at ->
            """{"id":"e$at","from":"n${at - 1}","to":"n$at","color":"#aa0000","width":4,"points":[{"x":${at * 200 - 40},"y":120}]}"""
        }
        return """{"layers":[{"id":"base","name":"Base"},{"id":"notes","name":"Notes"}],"nodes":[${nodes.joinToString(",")}],"edges":[${edges.joinToString(",")}]}"""
    }

    private fun create(name: String, document: String): Long = graphQlTester.document(
        """mutation(${'$'}w: ID!, ${'$'}n: String!, ${'$'}d: String) { createDiagram(workspaceId: ${'$'}w, name: ${'$'}n, document: ${'$'}d, description: "First") { id } }""",
    ).variable("w", workspaceId).variable("n", name).variable("d", document)
        .execute().path("createDiagram.id").entity(Long::class.java).get()

    private fun save(id: Long, document: String, viewer: String? = null) = graphQlTester.document(
        """mutation(${'$'}id: ID!, ${'$'}d: String!, ${'$'}v: String) { saveDiagram(id: ${'$'}id, document: ${'$'}d, viewer: ${'$'}v) { revision } }""",
    ).variable("id", id).variable("d", document).variable("v", viewer).execute().path("saveDiagram.revision").hasValue()

    private fun kept(id: Long): List<ComponentRevision> =
        revisions.findAll().filter { it.kind == ComponentRevisionKind.DIAGRAM && it.componentId == id }.sortedByDescending { it.id }

    /** The labels a version's drawing holds, in order. */
    private fun labelsOf(revision: ComponentRevision): List<String> =
        api.document.read(ComponentSnapshot.diagramIn(revision.snapshot, mapper).document).nodes.map { it.label }

    private fun liveLabels(id: Long): List<String> = api.drawing(id).nodes.map { it.label }

    @Test
    fun `every save of the editor keeps the whole drawing it displaced, saying which door saved it`() {
        val id = create("Flow", drawing("A", "B"))
        save(id, drawing("A", "B", "C"))
        save(id, drawing("A", "B", "C", "D"))

        val held = kept(id)
        assertThat(held.map { labelsOf(it) }).containsExactly(listOf("A", "B", "C"), listOf("A", "B"))
        assertThat(held.map { it.door }).containsExactly("EDITOR", "EDITOR")
        assertThat(held.map { it.savedBy }).containsExactly("alice", "alice")

        // The whole drawing: the lines' corners, colours and widths, the boxes' outlines and the layers.
        val oldest = api.document.read(ComponentSnapshot.diagramIn(held.last().snapshot, mapper).document)
        assertThat(oldest.edges.single().points).isNotEmpty()
        assertThat(oldest.edges.single().color).isEqualTo("#aa0000")
        assertThat(oldest.edges.single().width).isEqualTo(4)
        assertThat(oldest.nodes.first().border).isEqualTo("#112233")
        assertThat(oldest.nodes.first().borderWidth).isEqualTo(3)
        assertThat(oldest.layers.map { it.id }).containsExactly("base", "notes")

        graphQlTester.document("""{ componentRevisions(kind: DIAGRAM, componentId: $id, limit: 10) { door changes { field before after } } }""")
            .execute()
            .path("componentRevisions[*].door").entityList(String::class.java).containsExactly("EDITOR", "EDITOR")
            .path("componentRevisions[0].changes[*].field").entityList(String::class.java).containsExactly("document", "nodeCount", "edgeCount")
        graphQlTester.document("""{ componentCurrentVersion(kind: DIAGRAM, componentId: $id) { door changes { field before after } } }""")
            .execute()
            .path("componentCurrentVersion.door").entity(String::class.java).isEqualTo("EDITOR")
            .path("componentCurrentVersion.changes[1].before").entity(String::class.java).isEqualTo("3")
            .path("componentCurrentVersion.changes[1].after").entity(String::class.java).isEqualTo("4")

        // A version reads as the picture.
        graphQlTester.document("""{ componentRevision(id: ${held.first().id}) { content contentLanguage } }""").execute()
            .path("componentRevision.contentLanguage").entity(String::class.java).isEqualTo("svg")
            .path("componentRevision.content").entity(String::class.java).satisfies { assertThat(it).contains("<svg") }

        // A rename and a new description are not versions of the drawing.
        graphQlTester.document("""mutation { renameDiagram(id: $id, name: "Process") { name } }""").execute()
        graphQlTester.document("""mutation { describeDiagram(id: $id, description: "Second") { id } }""").execute()
        assertThat(kept(id)).hasSize(2)
    }

    @Test
    fun `an editor's visit keeps one version, and a chat turn one, however often they save`() {
        val id = create("Visit", drawing("A"))
        save(id, drawing("A", "B"), viewer = "tab-1")
        save(id, drawing("A", "B", "C"), viewer = "tab-1")
        save(id, drawing("A", "B", "C", "D"), viewer = "tab-1")
        assertThat(kept(id).map { labelsOf(it) }).containsExactly(listOf("A"))

        // The chat: two saves in one turn keep the state before the turn, once.
        val turn = OrknuxScope(workspaceId = workspaceId, mayWrite = true, diagramTurn = "turn-1")
        tools.run(turn, DiagramTools.CHANGE, """{"diagram":"$id","addNodes":[{"id":"x","label":"X","x":0,"y":300}]}""")
        tools.run(turn, DiagramTools.CHANGE, """{"diagram":"$id","addNodes":[{"id":"y","label":"Y","x":0,"y":400}]}""")
        assertThat(kept(id).map { labelsOf(it) }).containsExactly(listOf("A", "B", "C", "D"), listOf("A"))
        assertThat(diagrams.findById(id).get().updatedVia).isEqualTo("CHAT")

        // An agent through the tools keeps a version every time, as the tools' door.
        SecurityContextHolder.clearContext()
        val agent = OrknuxScope(workspaceId = workspaceId, mayWrite = true, actor = "Cartographer")
        tools.run(agent, DiagramTools.CHANGE, """{"diagram":"$id","removeNodes":["x"]}""")
        tools.run(agent, DiagramTools.CHANGE, """{"diagram":"$id","removeNodes":["y"]}""")
        val held = kept(id)
        assertThat(held.map { it.door }).containsExactly("TOOL", "CHAT", "EDITOR", "EDITOR")
        assertThat(held.first().savedBy).isEqualTo("Cartographer")
        assertThat(liveLabels(id)).containsExactly("A", "B", "C", "D")
    }

    @Test
    fun `a restore puts the drawing back as a save of its own, and is itself undone by restoring again`() {
        val id = create("Plan", drawing("A", "B"))
        save(id, drawing("A", "B", "C"))
        graphQlTester.document("""mutation { describeDiagram(id: $id, description: "Later") { id } }""").execute()
        val first = kept(id).single()
        val revisionBefore = diagrams.findById(id).get().revision

        graphQlTester.document("""mutation { restoreComponentRevision(id: ${first.id}) }""").execute()
            .path("restoreComponentRevision").entity(Boolean::class.java).isEqualTo(true)

        val now = diagrams.findById(id).get()
        assertThat(liveLabels(id)).containsExactly("A", "B")
        assertThat(api.drawing(id).edges.single().points).isNotEmpty()
        assertThat(now.description).isEqualTo("First")
        assertThat(now.name).isEqualTo("Plan")
        assertThat(now.updatedVia).isEqualTo("RESTORE")
        // The revision moves, which is how an open editor knows to take it in.
        assertThat(now.revision).isGreaterThan(revisionBefore)
        // What it replaced is kept, and the version restored is left where it was.
        val held = kept(id)
        assertThat(held.map { labelsOf(it) }).containsExactly(listOf("A", "B", "C"), listOf("A", "B"))
        assertThat(audit.findAll().filter { it.workspaceId == workspaceId }.map { it.message })
            .anyMatch { it.startsWith("Diagram Plan restored to the version saved on ") }

        graphQlTester.document("""mutation { restoreComponentRevision(id: ${held.first().id}) }""").execute()
        assertThat(liveLabels(id)).containsExactly("A", "B", "C")
        assertThat(diagrams.findById(id).get().description).isEqualTo("Later")
        assertThat(kept(id).first().door).isEqualTo("RESTORE")
    }

    @Test
    fun `an agent is refused a restore on a diagram read only for agents, and a person is not`() {
        val id = create("Guarded", drawing("A"))
        save(id, drawing("A", "B"))
        graphQlTester.document("""mutation { setDiagramAgentAccess(id: $id, access: READ) { id } }""").execute()
        val first = kept(id).single()

        // A person may always.
        api.restore(id, first.snapshot)
        assertThat(liveLabels(id)).containsExactly("A")

        SecurityContextHolder.clearContext()
        assertThatThrownBy { ActingIn.run(workspaceId, "Cartographer") { api.restore(id, kept(id).first().snapshot) } }
            .isInstanceOf(DiagramReadOnlyException::class.java)
        assertThat(liveLabels(id)).containsExactly("A")
    }

    @Test
    fun `how many versions are kept is a Tunable, honoured as a save adds one and by the sweep`() {
        tunables.set(Tunable.DIAGRAM_VERSIONS_KEPT, 2, "alice")
        val id = create("Log", drawing("v0"))
        (1..5).forEach { save(id, drawing("v$it")) }
        assertThat(kept(id).map { labelsOf(it) }).containsExactly(listOf("v4"), listOf("v3"))

        tunables.set(Tunable.DIAGRAM_VERSIONS_KEPT, 0, "alice")
        (6..8).forEach { save(id, drawing("v$it")) }
        assertThat(kept(id)).hasSize(5)

        tunables.set(Tunable.DIAGRAM_VERSIONS_KEPT, 1, "alice")
        sweeper.sweep()
        assertThat(kept(id).map { labelsOf(it) }).containsExactly(listOf("v7"))
    }

    @Test
    fun `a diagram's history is not taken by the days, and goes with the diagram`() {
        val id = create("Old", drawing("Long ago"))
        save(id, drawing("Now"))
        val held = kept(id).single()
        revisions.save(
            ComponentRevision(
                workspaceId = workspaceId, kind = ComponentRevisionKind.DIAGRAM, componentId = id, name = "Old",
                savedAt = OffsetDateTime.now().minusYears(1), savedBy = "alice",
                recordedAt = OffsetDateTime.now().minusYears(1), snapshot = held.snapshot, door = "EDITOR",
            ),
        )
        sweeper.sweep()
        assertThat(kept(id)).hasSize(2)

        graphQlTester.document("""mutation { deleteDiagram(id: $id) }""").execute()
        assertThat(kept(id)).isEmpty()
    }
}
