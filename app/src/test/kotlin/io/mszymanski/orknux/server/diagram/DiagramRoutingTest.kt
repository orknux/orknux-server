package io.mszymanski.orknux.server.diagram

import io.mszymanski.orknux.server.mcp.DiagramTools
import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.mcp.OrknuxTools
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Finding what makes a diagram hard to read, and routing its lines around it.
 *
 * A model draws a diagram it cannot look at closely: two lines laid on one
 * run, a line straight through the box between its two ends, a label cut to
 * an ellipsis. orknux_check_diagram measures the drawing as it is drawn and
 * names each problem; orknux_route_diagram routes the lines in right angles
 * around the boxes and off each other, keeps the corners it found on each
 * line, and says line by line what came of it.
 */
@SpringBootTest
@WithMockUser(username = "alice", roles = ["ADMINS"])
class DiagramRoutingTest(
    @Autowired val api: DiagramAPI,
    @Autowired val tools: OrknuxTools,
    @Autowired val export: DiagramExport,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0
    private lateinit var scope: OrknuxScope

    @BeforeEach
    fun reset() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "diagram-routing-${System.nanoTime()}")).id)
        scope = OrknuxScope(workspaceId = workspaceId, mayWrite = true, actor = "Cartographer")
    }

    private fun call(name: String, arguments: Map<String, Any?>): JsonNode =
        mapper.readTree(tools.run(scope, name, mapper.writeValueAsString(arguments)))

    /** Three boxes in a row, the outer two joined straight through the middle one, twice over. */
    private val tangled = """
        {"nodes": [
           {"id": "web", "label": "web", "x": 0, "y": 0, "w": 160, "h": 60},
           {"id": "cache", "label": "cache", "x": 300, "y": 0, "w": 160, "h": 60},
           {"id": "db", "kind": "database", "label": "orders", "x": 600, "y": 0, "w": 160, "h": 60},
           {"id": "auth", "label": "auth", "x": 300, "y": 200, "w": 160, "h": 60}
         ],
         "edges": [
           {"id": "read", "from": "web", "to": "db", "label": "SQL"},
           {"id": "write", "from": "web", "to": "db"},
           {"id": "login", "from": "web", "to": "auth", "routing": "right-angles"}
         ]}
    """.trimIndent()

    private fun types(answer: JsonNode): List<String> = answer.path("problems").values().map { it.path("type").asString() }

    @Test
    fun `a check names the lines on each other and the line through a box, by id`() {
        val made = api.createDiagram(workspaceId, "Tangled", null, tangled, null)

        val checked = call(DiagramTools.CHECK, mapOf("diagram" to made.id.toString()))

        assertThat(checked.path("readable").booleanValue()).isFalse()
        assertThat(types(checked)).contains("lines-overlap", "line-through-shape")
        val through = checked.path("problems").values().first { it.path("type").asString() == "line-through-shape" }
        assertThat(through.path("nodes").values().map { it.asString() }).containsExactly("cache")
        assertThat(through.path("severity").asString()).isEqualTo("error")
        val overlap = checked.path("problems").values().first { it.path("type").asString() == "lines-overlap" }
        assertThat(overlap.path("edges").values().map { it.asString() }).containsExactlyInAnyOrder("read", "write")
    }

    @Test
    fun `routing takes every line around the boxes and off each other, and says what came of each`() {
        val made = api.createDiagram(workspaceId, "Tangled", null, tangled, null)

        val routed = call(DiagramTools.ROUTE, mapOf("diagram" to made.id.toString()))

        assertThat(routed.path("error").isMissingNode).describedAs(routed.toString()).isTrue()
        val report = routed.path("routing")
        assertThat(report.path("lines").values().map { it.path("result").asString() }).containsOnly(DiagramRouter.ROUTED)
        assertThat(report.path("before").path("errors").intValue()).isGreaterThan(0)
        assertThat(report.path("after").path("errors").intValue()).describedAs(report.toString()).isZero()
        assertThat(report.path("after").path("readable").booleanValue()).isTrue()

        // Kept on the lines, in right angles, and every run of each straight across or straight down.
        val held = api.drawing(made.id)
        held.edges.forEach { edge ->
            assertThat(edge.routing).isEqualTo(DiagramRouting.ORTHOGONAL)
            assertThat(edge.points).isNotEmpty()
            val drawn = DiagramRoute.of(edge, held.nodes.single { it.id == edge.from }, held.nodes.single { it.id == edge.to })
            drawn.points!!.zipWithNext().forEach { (a, b) ->
                assertThat(kotlin.math.abs(a.first - b.first) < 1 || kotlin.math.abs(a.second - b.second) < 1).describedAs("${edge.id}: $a to $b").isTrue()
            }
        }
        assertThat(DiagramCheck.of(held).filter { it.kind.error }).isEmpty()

        // And what the editor reads back carries them.
        val read = mapper.readTree(api.view(api.find(made.id)).document)
        assertThat(read.path("edges").values().first().path("points").isArray).isTrue()
    }

    @Test
    fun `only the lines named are routed, and the rest are left as they were`() {
        val made = api.createDiagram(workspaceId, "Tangled", null, tangled, null)

        val routed = call(DiagramTools.ROUTE, mapOf("diagram" to made.id.toString(), "edges" to listOf("read")))

        assertThat(routed.path("routing").path("lines").values().map { it.path("edge").asString() }).containsExactly("read")
        val held = api.drawing(made.id)
        assertThat(held.edges.single { it.id == "read" }.points).isNotEmpty()
        assertThat(held.edges.single { it.id == "write" }.points).isEmpty()
        assertThat(held.edges.single { it.id == "write" }.routing).isEqualTo(DiagramRouting.STRAIGHT)

        val unknown = call(DiagramTools.ROUTE, mapOf("diagram" to made.id.toString(), "edges" to listOf("nowhere")))
        assertThat(unknown.path("error").asString()).contains("nowhere")
    }

    @Test
    fun `a box moved after routing still carries its line, and a new routing drops the corners`() {
        val made = api.createDiagram(workspaceId, "Tangled", null, tangled, null)
        call(DiagramTools.ROUTE, mapOf("diagram" to made.id.toString()))

        call(DiagramTools.CHANGE, mapOf("diagram" to made.id.toString(), "updateNodes" to listOf(mapOf("id" to "db", "x" to 640, "y" to 90))))
        val moved = api.drawing(made.id)
        val db = moved.nodes.single { it.id == "db" }
        val line = moved.edges.single { it.id == "read" }
        val end = DiagramRoute.of(line, moved.nodes.single { it.id == "web" }, db).points!!.last()
        // It still ends on the box, wherever the box went.
        assertThat(end.first).isBetween(db.x - 1.0, db.x + db.w + 1.0)
        assertThat(end.second).isBetween(db.y - 1.0, db.y + db.h + 1.0)

        call(DiagramTools.CHANGE, mapOf("diagram" to made.id.toString(), "updateEdges" to listOf(mapOf("id" to "read", "routing" to "curved"))))
        assertThat(api.drawing(made.id).edges.single { it.id == "read" }.points).isEmpty()
    }

    @Test
    fun `boxes on each other and a label cut short are words nobody can read`() {
        val made = api.createDiagram(
            workspaceId, "Crowded", null,
            """
            {"nodes": [
               {"id": "a", "label": "billing", "x": 0, "y": 0, "w": 160, "h": 60},
               {"id": "b", "label": "invoices", "x": 40, "y": 20, "w": 160, "h": 60},
               {"id": "c", "label": "the service that reconciles every payment against the ledger at night", "x": 400, "y": 0, "w": 100, "h": 40}
             ],
             "edges": []}
            """.trimIndent(),
            null,
        )

        val checked = call(DiagramTools.CHECK, mapOf("diagram" to made.id.toString()))

        assertThat(types(checked)).contains("shapes-overlap", "label-cut")
        assertThat(checked.path("problems").values().first { it.path("type").asString() == "label-cut" }.path("nodes").values().map { it.asString() })
            .containsExactly("c")
    }

    @Test
    fun `a routed line is drawn through its corners in every picture`() {
        val made = api.createDiagram(workspaceId, "Tangled", null, tangled, null)
        call(DiagramTools.ROUTE, mapOf("diagram" to made.id.toString()))
        val drawing = api.drawing(made.id)
        val line = drawing.edges.single { it.id == "read" }
        val corner = line.points.first()

        val svg = String((export.export(drawing, DiagramFormat.SVG) as DiagramExport.Made.Written).file.bytes)

        assertThat(svg).contains("${corner.first}.0")
    }

    @Test
    fun `reading the tools needs no write access, routing does`() {
        val made = api.createDiagram(workspaceId, "Tangled", null, tangled, null)
        val reader = OrknuxScope(workspaceId = workspaceId, mayWrite = false, actor = "Reader")

        assertThat(tools.specs(reader).map { it.name }).contains(DiagramTools.CHECK).doesNotContain(DiagramTools.ROUTE)
        val refused = mapper.readTree(tools.run(reader, DiagramTools.ROUTE, mapper.writeValueAsString(mapOf("diagram" to made.id.toString()))))
        assertThat(refused.path("error").isMissingNode).isFalse()
    }

    @Test
    fun `labels that would lie on each other are slid apart along their lines, and stay there`() {
        // Three lines out of one box to three boxes stacked on the right: their labels meet at the same height.
        val made = api.createDiagram(
            workspaceId, "Fan", null,
            """{"nodes": [
                 {"id": "hub", "label": "hub", "x": 0, "y": 160, "w": 160, "h": 60},
                 {"id": "a", "label": "a", "x": 420, "y": 0, "w": 160, "h": 60},
                 {"id": "b", "label": "b", "x": 420, "y": 160, "w": 160, "h": 60},
                 {"id": "c", "label": "c", "x": 420, "y": 320, "w": 160, "h": 60}
               ],
               "edges": [
                 {"id": "ha", "from": "hub", "to": "a", "label": "Kafka: orders_created"},
                 {"id": "hb", "from": "hub", "to": "b", "label": "REST: /payments"},
                 {"id": "hc", "from": "hub", "to": "c", "label": "Kafka: orders_paid"}
               ]}""",
            null,
        )

        call(DiagramTools.ROUTE, mapOf("diagram" to made.id.toString()))

        val held = api.drawing(made.id)
        assertThat(DiagramCheck.of(held).filter { it.kind == DiagramCheck.Kind.LABEL_COVERED }).describedAs(DiagramCheck.of(held).toString()).isEmpty()
        // Kept on the line as a share of its length, which the editor reads back.
        val read = mapper.readTree(api.view(api.find(made.id)).document)
        read.path("edges").values().filter { it.has("labelAt") }.forEach { assertThat(it.path("labelAt").doubleValue()).isBetween(0.0, 1.0) }
    }
}
