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
 * Layers in a diagram, as draw.io has them.
 *
 * A diagram of a whole platform - its services, its Kafka topics, its REST
 * calls - is one nobody reads all at once: the Kafka flows and the REST calls
 * want looking at one at a time. A layer is shown or hidden, and locked or not,
 * for everybody who opens the diagram; a box and a line each say which one they
 * are on, and one that says none is on the first. Hidden, a layer is out of
 * every picture of the diagram as it is out of the editor.
 */
@SpringBootTest
@WithMockUser(username = "alice", roles = ["ADMINS"])
class DiagramLayersTest(
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
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "diagram-layers-${System.nanoTime()}")).id)
        scope = OrknuxScope(workspaceId = workspaceId, mayWrite = true, actor = "Cartographer")
    }

    private val layered = """
        {"layers": [
           {"id": "base", "name": "Services"},
           {"id": "kafka", "name": "Kafka", "visible": false},
           {"id": "rest", "name": "REST", "locked": true}
         ],
         "nodes": [
           {"id": "orders", "label": "order-service", "x": 0, "y": 0},
           {"id": "topic", "kind": "database", "label": "orders_created", "x": 300, "y": 0, "layer": "kafka"},
           {"id": "pay", "label": "payment-service", "x": 0, "y": 200}
         ],
         "edges": [
           {"id": "write", "from": "orders", "to": "topic", "label": "Kafka: orders_created"},
           {"id": "call", "from": "orders", "to": "pay", "label": "REST: /payments", "layer": "rest"}
         ]}
    """.trimIndent()

    private fun call(name: String, arguments: Map<String, Any?>): JsonNode =
        mapper.readTree(tools.run(scope, name, mapper.writeValueAsString(arguments)))

    @Test
    fun `layers are kept with the diagram, and each box and line on its own`() {
        val made = api.createDiagram(workspaceId, "Platform", null, layered, null)
        val held = api.drawing(made.id)

        assertThat(held.layers.map { it.name }).containsExactly("Services", "Kafka", "REST")
        assertThat(held.layers.single { it.id == "kafka" }.visible).isFalse()
        assertThat(held.layers.single { it.id == "rest" }.locked).isTrue()
        assertThat(held.nodes.single { it.id == "topic" }.layer).isEqualTo("kafka")
        assertThat(held.nodes.single { it.id == "orders" }.layer).isNull()
        assertThat(held.edges.single { it.id == "call" }.layer).isEqualTo("rest")

        // And what the editor reads back carries them, so a reload draws the same layers.
        val read = mapper.readTree(api.view(api.find(made.id)).document)
        assertThat(read.path("layers").values().map { it.path("id").asString() }).containsExactly("base", "kafka", "rest")
        assertThat(read.path("nodes").values().first { it.path("id").asString() == "topic" }.path("layer").asString()).isEqualTo("kafka")
    }

    @Test
    fun `a diagram with no layers of its own has one, Base, and its document says none`() {
        val made = api.createDiagram(workspaceId, "Flat", null, """{"nodes": [{"id": "a", "label": "A"}], "edges": []}""", null)
        val held = api.drawing(made.id)

        assertThat(held.layers).isEmpty()
        assertThat(held.effectiveLayers).containsExactly(DiagramDrawing.BASE)
        assertThat(mapper.readTree(api.view(api.find(made.id)).document).has("layers")).isFalse()
    }

    @Test
    fun `a box on a layer the diagram does not have is refused, and says which there are`() {
        assertThatThrownBy {
            api.createDiagram(workspaceId, "Lost", null, """{"layers": [{"id": "base", "name": "Base"}], "nodes": [{"id": "a", "label": "A", "layer": "nowhere"}], "edges": []}""", null)
        }.isInstanceOf(DiagramDocumentInvalidException::class.java)
            .hasMessageContaining("nowhere").hasMessageContaining("base (Base)")
    }

    @Test
    fun `a hidden layer is out of every picture, and the json keeps it`() {
        val made = api.createDiagram(workspaceId, "Platform", null, layered, null)
        val drawing = api.drawing(made.id)

        val svg = String((export.export(drawing, DiagramFormat.SVG) as DiagramExport.Made.Written).file.bytes)
        assertThat(svg).contains("order-service").contains("payment-service").doesNotContain("orders_created")
        val mermaid = String((export.export(drawing, DiagramFormat.MERMAID) as DiagramExport.Made.Written).file.bytes)
        assertThat(mermaid).doesNotContain("orders_created")
        val json = String((export.export(drawing, DiagramFormat.JSON) as DiagramExport.Made.Written).file.bytes)
        assertThat(json).contains("orders_created").contains("\"kafka\"")
    }

    @Test
    fun `an agent reads the layers, puts a new box on one, and changes the list`() {
        val made = api.createDiagram(workspaceId, "Platform", null, layered, null)

        val read = call(DiagramTools.READ, mapOf("diagram" to made.id.toString()))
        assertThat(read.path("layers").values().map { it.path("name").asString() }).containsExactly("Services", "Kafka", "REST")

        val changed = call(
            DiagramTools.CHANGE,
            mapOf(
                "diagram" to made.id.toString(),
                "layers" to listOf(
                    mapOf("id" to "base", "name" to "Services"),
                    mapOf("id" to "kafka", "name" to "Kafka", "visible" to true),
                    mapOf("id" to "rest", "name" to "REST", "locked" to true),
                ),
                "addNodes" to listOf(mapOf("id" to "stock", "kind" to "database", "label" to "stock_changed", "x" to 300, "y" to 200, "layer" to "kafka")),
            ),
        )
        assertThat(changed.path("error").isMissingNode).describedAs(changed.toString()).isTrue()
        val held = api.drawing(made.id)
        assertThat(held.layers.single { it.id == "kafka" }.visible).isTrue()
        assertThat(held.nodes.single { it.id == "stock" }.layer).isEqualTo("kafka")

        // A change that leaves the layers out keeps them.
        call(DiagramTools.CHANGE, mapOf("diagram" to made.id.toString(), "addNodes" to listOf(mapOf("id" to "x", "label" to "X"))))
        assertThat(api.drawing(made.id).layers.map { it.id }).containsExactly("base", "kafka", "rest")

        // Taking away a layer something is still on is refused, in words.
        val refused = call(
            DiagramTools.CHANGE,
            mapOf("diagram" to made.id.toString(), "layers" to listOf(mapOf("id" to "base", "name" to "Services"))),
        )
        assertThat(refused.path("error").asString()).contains("kafka")
    }
}
