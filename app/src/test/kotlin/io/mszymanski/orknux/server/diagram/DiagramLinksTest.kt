package io.mszymanski.orknux.server.diagram

import io.mszymanski.orknux.server.mcp.DiagramTools
import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.mcp.OrknuxTools
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * A box links somewhere a reader can open: a web address, or another diagram
 * - a box that stands for a system drawn elsewhere links to that drawing.
 * Only what may be opened by a click is kept, because a link is opened in the
 * reader's page; and an agent reading a box is told where its link goes by
 * name. And the shapes past the first ones each draw.
 */
@SpringBootTest
@WithMockUser(username = "alice", roles = ["ADMINS"])
class DiagramLinksTest(
    @Autowired val api: DiagramAPI,
    @Autowired val tools: OrknuxTools,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0
    private lateinit var scope: OrknuxScope

    @BeforeEach
    fun reset() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "diagram-links-${System.nanoTime()}")).id)
        scope = OrknuxScope(workspaceId = workspaceId, mayWrite = true, actor = "Cartographer")
    }

    private fun call(name: String, arguments: Map<String, Any?>): JsonNode =
        mapper.readTree(tools.run(scope, name, mapper.writeValueAsString(arguments)))

    @Test
    fun `a box links to another diagram or an address, and an agent is told where it goes`() {
        val payments = api.createDiagram(workspaceId, "Payments", null, """{"nodes": [{"id": "p", "label": "ledger"}], "edges": []}""", null)
        val platform = api.createDiagram(
            workspaceId, "Platform", null,
            """{"nodes": [
                 {"id": "pay", "label": "payments", "link": "diagram:${payments.id}"},
                 {"id": "docs", "label": "runbook", "link": "https://wiki.example.invalid/runbook"}
               ], "edges": []}""",
            null,
        )

        val held = api.drawing(platform.id)
        assertThat(held.nodes.single { it.id == "pay" }.linkedDiagram).isEqualTo(payments.id.toLong())
        assertThat(held.nodes.single { it.id == "docs" }.link).isEqualTo("https://wiki.example.invalid/runbook")

        val read = call(DiagramTools.NODE, mapOf("diagram" to platform.id.toString(), "node" to "pay"))
        assertThat(read.path("link").asString()).isEqualTo("diagram:${payments.id}")
        assertThat(read.path("opens").path("name").asString()).isEqualTo("Payments")
        assertThat(read.path("opens").path("url").asString()).endsWith("/explore/diagrams/${payments.id}")

        // Cleared with a null, as notes are.
        call(DiagramTools.CHANGE, mapOf("diagram" to platform.id.toString(), "updateNodes" to listOf(mapOf("id" to "docs", "link" to null))))
        assertThat(api.drawing(platform.id).nodes.single { it.id == "docs" }.link).isNull()
    }

    @Test
    fun `a link that would run in the reader's page is refused, in words`() {
        listOf("javascript:alert(1)", "data:text/html,hi", "diagram:payments", "ftp://x").forEach { bad ->
            assertThatThrownBy {
                api.createDiagram(workspaceId, "Bad $bad", null, """{"nodes": [{"id": "a", "label": "A", "link": "$bad"}], "edges": []}""", null)
            }.isInstanceOf(DiagramDocumentInvalidException::class.java).hasMessageContaining("not a web address")
        }
    }

    @Test
    fun `every shape draws, and a model is told what each is for`() {
        val nodes = DiagramNodeKind.entries.mapIndexed { at, kind ->
            mapOf("id" to kind.key, "kind" to kind.key, "label" to kind.key, "x" to (at % 6) * 260, "y" to (at / 6) * 200)
        }
        val made = call(DiagramTools.CREATE, mapOf("name" to "Every shape", "nodes" to nodes))
        assertThat(made.path("error").isMissingNode).describedAs(made.toString()).isTrue()

        val svg = DiagramSvg().drawing(api.drawing(made.path("id").asLong()))
        listOf("queue", "storage", "server", "browser", "mobile", "subroutine", "step", "octagon", "star", "callout").forEach {
            assertThat(svg).contains(">$it<")
        }
        val described = tools.specs(scope).single { it.name == DiagramTools.CREATE }.description
        assertThat(described).contains("queue - a cylinder on its side").contains("off-page - ")
    }
}
