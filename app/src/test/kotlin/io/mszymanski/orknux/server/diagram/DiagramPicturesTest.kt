package io.mszymanski.orknux.server.diagram

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.WithMockUser
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import tools.jackson.databind.ObjectMapper

/**
 * Pictures on a diagram, and the colour and thickness of its outlines and
 * lines. A picture uploaded is kept beside the diagram, served to whoever sees
 * the diagram, and drawn into its exports; one from an address on the web is
 * kept as that address; a box naming anything else is refused. An outline and
 * a line each keep their own colour and thickness, and the exports draw them.
 */
@SpringBootTest
@WithMockUser(username = "alice", roles = ["ADMINS"])
class DiagramPicturesTest(
    @Autowired val api: DiagramAPI,
    @Autowired val pictures: DiagramImageAPI,
    @Autowired val export: DiagramExport,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val tools: io.mszymanski.orknux.server.mcp.OrknuxTools,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "diagram-pictures-${System.nanoTime()}")).id)
    }

    private fun png(width: Int, height: Int): ByteArray = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()

    @Test
    fun `a picture uploaded is kept, served, and drawn into the diagram's exports`() {
        val made = api.createDiagram(workspaceId, "With a logo", null, """{"nodes": [], "edges": []}""", null)

        @Suppress("UNCHECKED_CAST")
        val kept = pictures.upload(made.id.toLong(), MockMultipartFile("file", "logo.png", "image/png", png(40, 20))).body as Map<String, Any?>
        assertThat(kept["width"]).isEqualTo(40)
        assertThat(kept["height"]).isEqualTo(20)
        val id = kept["id"] as String

        api.saveDrawing(made.id.toLong(), api.document.read("""{"nodes": [{"id": "logo", "kind": "image", "image": "$id", "label": "Our logo", "w": 200, "h": 100}], "edges": []}"""))
        val held = api.drawing(made.id.toLong())
        assertThat(held.nodes.single().image).isEqualTo(id)

        assertThat(pictures.show(id.toLong()).statusCode.value()).isEqualTo(200)
        val svg = String((export.export(held, DiagramFormat.SVG, diagramId = made.id.toLong()) as DiagramExport.Made.Written).file.bytes)
        assertThat(svg).contains("<image ").contains("data:image/png;base64,").contains("Our logo")
        // And as a PNG, which Batik draws: it reads only xlink:href, and refused a picture given by href alone.
        val png = export.export(held, DiagramFormat.PNG, diagramId = made.id.toLong())
        assertThat(png).describedAs(png.toString()).isInstanceOf(DiagramExport.Made.Written::class.java)
        // Drawn for another diagram, it is an empty frame: a picture is as private as its own.
        val elsewhere = String((export.export(held, DiagramFormat.SVG, diagramId = -1) as DiagramExport.Made.Written).file.bytes)
        assertThat(elsewhere).doesNotContain("data:image/png")
    }

    @Test
    fun `what is not a picture is refused, and a box takes an uploaded id or an address on the web`() {
        val made = api.createDiagram(workspaceId, "Refusals", null, """{"nodes": [], "edges": []}""", null)
        assertThatThrownBy { pictures.upload(made.id.toLong(), MockMultipartFile("file", "page.svg", "image/svg+xml", "<svg/>".toByteArray())) }
            .isInstanceOf(DiagramImageNotAPictureException::class.java)

        val web = api.document.read("""{"nodes": [{"id": "a", "kind": "image", "image": "https://pictures.example.invalid/a.png"}], "edges": []}""")
        assertThat(web.nodes.single().image).isEqualTo("https://pictures.example.invalid/a.png")
        assertThat(web.nodes.single().uploadedImage).isNull()
        listOf("javascript:alert(1)", "data:image/png;base64,AAAA", "file:///etc/passwd").forEach { bad ->
            assertThatThrownBy { api.document.read("""{"nodes": [{"id": "a", "kind": "image", "image": "$bad"}], "edges": []}""") }
                .isInstanceOf(DiagramDocumentInvalidException::class.java).hasMessageContaining("address on the web")
        }
    }

    /**
     * An agent asked for a logo beside a box drew a node of kind image and had
     * nowhere to say which picture: the tools offered no image field, so it put
     * the address in link, then in notes, then in the label, and the export
     * drew an empty frame each time.
     */
    @Test
    fun `an agent gives a picture node its address through the tools, and changes it`() {
        val scope = io.mszymanski.orknux.server.mcp.OrknuxScope(workspaceId = workspaceId, mayWrite = true, actor = "Painter")
        fun call(name: String, arguments: Map<String, Any?>) = mapper.readTree(tools.run(scope, name, mapper.writeValueAsString(arguments)))
        val create = tools.specs(scope).single { it.name == io.mszymanski.orknux.server.mcp.DiagramTools.CREATE }
        assertThat(mapper.writeValueAsString(create)).contains("\"image\"")

        val made = call(
            io.mszymanski.orknux.server.mcp.DiagramTools.CREATE,
            mapOf("name" to "Logo", "nodes" to listOf(mapOf("id" to "logo", "kind" to "image", "label" to "Our logo", "image" to "https://pictures.example.invalid/a.png"))),
        )
        assertThat(made.path("error").isMissingNode).describedAs(made.toString()).isTrue()
        val id = made.path("id").asLong()
        assertThat(api.drawing(id).nodes.single().image).isEqualTo("https://pictures.example.invalid/a.png")

        call(io.mszymanski.orknux.server.mcp.DiagramTools.CHANGE, mapOf("diagram" to id.toString(), "updateNodes" to listOf(mapOf("id" to "logo", "image" to "https://pictures.example.invalid/b.png"))))
        assertThat(api.drawing(id).nodes.single().image).isEqualTo("https://pictures.example.invalid/b.png")
    }

    @Test
    fun `outlines and lines keep their own colour and thickness, and the exports draw them`() {
        val made = api.createDiagram(
            workspaceId, "Strokes", null,
            """{"nodes": [
                 {"id": "a", "label": "A", "x": 0, "y": 0, "border": "#DC2626", "borderWidth": 4},
                 {"id": "b", "label": "B", "x": 400, "y": 0}
               ],
               "edges": [{"id": "e", "from": "a", "to": "b", "color": "#2563eb", "width": 3}]}""",
            null,
        )
        val held = api.drawing(made.id.toLong())
        assertThat(held.nodes.single { it.id == "a" }.border).isEqualTo("#dc2626")
        assertThat(held.nodes.single { it.id == "a" }.borderWidth).isEqualTo(4)
        assertThat(held.edges.single().color).isEqualTo("#2563eb")
        assertThat(held.edges.single().width).isEqualTo(3)

        val svg = DiagramSvg().drawing(held)
        assertThat(svg).contains("stroke=\"#dc2626\" stroke-width=\"4\"").contains("stroke=\"#2563eb\" stroke-width=\"3\"")
        // The arrowhead in the line's colour.
        assertThat(svg).contains("id=\"dg-head-2563eb\"").contains("url(#dg-head-2563eb)")
        // Sized in the drawing's pixels, not by the line: on a thick line a head sized by it was four times too big.
        Regex("<marker [^>]*>").findAll(svg).forEach { assertThat(it.value).contains("markerUnits=\"userSpaceOnUse\"") }

        assertThatThrownBy { api.document.read("""{"nodes": [{"id": "a", "label": "A", "borderWidth": 40}], "edges": []}""") }
            .isInstanceOf(DiagramDocumentInvalidException::class.java).hasMessageContaining("1 to 12")
    }
}
