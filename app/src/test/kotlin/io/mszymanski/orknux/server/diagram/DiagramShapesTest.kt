package io.mszymanski.orknux.server.diagram

import io.mszymanski.orknux.server.plugin.SvgRenderer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

/** The flowchart and UML shapes: read, drawn, met by a line at their outline, and exported. */
class DiagramShapesTest {

    private val added = DiagramOutline.OUTLINES.keys

    private fun shape(kind: DiagramNodeKind, label: String = kind.key, x: Int = 0, y: Int = 0) =
        DiagramShape(kind.key, kind, label, x, y, kind.w, kind.h)

    @Test
    fun `every shape a node can be is drawn, the first six as they were and the rest from an outline`() {
        assertThat(added.map { it.key }).containsExactlyInAnyOrder(
            "database", "document", "hexagon", "parallelogram", "trapezoid", "triangle", "cloud",
            "actor", "class", "component", "package", "interface", "device", "boundary", "control", "entity",
        )
        assertThat(DiagramNodeKind.entries - added).containsExactly(
            DiagramNodeKind.BOX, DiagramNodeKind.ROUNDED, DiagramNodeKind.ELLIPSE,
            DiagramNodeKind.DIAMOND, DiagramNodeKind.NOTE, DiagramNodeKind.GROUP,
        )
    }

    @Test
    fun `a document naming the new kinds is read with each kind's own size`() {
        val document = DiagramDocument(ObjectMapper())
        val nodes = added.joinToString(",") { """{"id":"${it.key}","kind":"${it.key}","label":"x","x":0,"y":0}""" }
        val read = document.read("""{"nodes":[$nodes],"edges":[]}""")
        assertThat(read.nodes.map { it.kind }).containsExactlyElementsOf(added)
        read.nodes.forEach { assertThat(it.w to it.h).isEqualTo(it.kind.w to it.kind.h) }
    }

    @Test
    fun `each new shape is drawn as its outline, with its words, and the picture renders`() {
        added.forEach { kind ->
            val svg = DiagramSvg().drawing(DiagramDrawing(listOf(shape(kind, "Ledger")), emptyList()))
            assertThat(svg).describedAs(kind.key).containsAnyOf("<path", "<ellipse")
            // Only the page's own background is a rectangle: the shape is not a box.
            assertThat(Regex("<rect ").findAll(svg).count()).describedAs(kind.key).isEqualTo(1)
            assertThat(svg).describedAs(kind.key).contains(">Ledger</text>")
        }
        val all = added.mapIndexed { at, kind -> shape(kind, x = (at % 4) * 240, y = (at / 4) * 160) }
        val drawn = SvgRenderer().png(DiagramSvg().drawing(DiagramDrawing(all, emptyList())), null)
        assertThat(drawn).isInstanceOf(SvgRenderer.Drawing.Drawn::class.java)
    }

    @Test
    fun `an outline is moved to the node's pixels, so a cylinder is the node's size where it is`() {
        val svg = DiagramSvg().drawing(DiagramDrawing(listOf(DiagramShape("db", DiagramNodeKind.DATABASE, "", 100, 200, 120, 80)), emptyList()))
        assertThat(svg).contains("M 100.0 206.0 C 100.0 198.0 220.0 198.0 220.0 206.0 L 220.0 274.0")
    }

    @Test
    fun `an icon keeps its proportions above its label rather than stretching`() {
        val actor = DiagramShape("a", DiagramNodeKind.ACTOR, "Customer", 0, 0, 200, 110)
        val frame = DiagramOutline.frame(actor, DiagramOutline.OUTLINES.getValue(DiagramNodeKind.ACTOR))
        assertThat(frame.toList()).containsExactly(56.0, 0.0, 88.0, 88.0)
        val svg = DiagramSvg().drawing(DiagramDrawing(listOf(actor), emptyList()))
        assertThat(svg).contains("""<ellipse cx="100.0" cy="12.3" rx="9.7" ry="9.7"""")
        assertThat(svg).contains("""y="104" font-size="14" fill="#3f3f46" text-anchor="middle">Customer</text>""")
    }

    @Test
    fun `a line ends where the outline is, not at the box around it`() {
        fun meets(kind: DiagramNodeKind, w: Int, h: Int, tx: Double, ty: Double, x: Double, y: Double) {
            val (bx, by) = DiagramOutline.border(DiagramShape("s", kind, "", 0, 0, w, h), tx, ty)
            assertThat(bx).describedAs("${kind.key} x").isCloseTo(x, Offset.offset(0.01))
            assertThat(by).describedAs("${kind.key} y").isCloseTo(y, Offset.offset(0.01))
        }
        // From the right at mid height the triangle's side is three quarters across, where the box's is at its edge.
        meets(DiagramNodeKind.TRIANGLE, 120, 100, 1000.0, 50.0, 90.0, 50.0)
        meets(DiagramNodeKind.TRIANGLE, 120, 100, 60.0, -1000.0, 60.0, 0.0)
        meets(DiagramNodeKind.HEXAGON, 100, 100, 1000.0, 1000.0, 50 + 50 * 50 / 65.0, 50 + 50 * 50 / 65.0)
        meets(DiagramNodeKind.DATABASE, 100, 100, 50.0, -1000.0, 50.0, 0.0)
        meets(DiagramNodeKind.DIAMOND, 100, 100, 1000.0, 1000.0, 75.0, 75.0)
        meets(DiagramNodeKind.ELLIPSE, 100, 100, 1000.0, 1000.0, 50 + 50 / Math.sqrt(2.0), 50 + 50 / Math.sqrt(2.0))
        meets(DiagramNodeKind.BOX, 100, 100, 1000.0, 1000.0, 100.0, 100.0)
    }

    @Test
    fun `a class is its name, then a compartment under a rule for each run of lines between --`() {
        assertThat(DiagramOutline.compartments("Order\n--\nid: Long\ntotal: Money\n--\nplace()"))
            .containsExactly(listOf("Order"), listOf("id: Long", "total: Money"), listOf("place()"))
        assertThat(DiagramOutline.compartments("Order\nid: Long")).containsExactly(listOf("Order"), listOf("id: Long"))
        assertThat(DiagramOutline.compartments("Order")).containsExactly(listOf("Order"))
        val svg = DiagramSvg().drawing(
            DiagramDrawing(listOf(DiagramShape("c", DiagramNodeKind.CLASS, "Order\n--\nid: Long\n--\nplace()", 0, 0, 200, 120)), emptyList()),
        )
        assertThat(Regex("<line ").findAll(svg).count()).isEqualTo(2)
        assertThat(svg).contains("""font-weight="600" text-anchor="middle">Order</text>""", ">id: Long</text>", ">place()</text>")
    }

    @Test
    fun `the Mermaid export gives each new shape the nearest Mermaid has`() {
        val nodes = added.map { shape(it, "L") }
        val text = DiagramExport(SvgRenderer(), ObjectMapper()).mermaid(DiagramDrawing(nodes, emptyList()))
        fun line(kind: DiagramNodeKind) = text.lines().single { it.contains("_${kind.key}") }.trim().substringAfter(kind.key)
        assertThat(line(DiagramNodeKind.DATABASE)).isEqualTo("""[("L")]""")
        assertThat(line(DiagramNodeKind.HEXAGON)).isEqualTo("""{{"L"}}""")
        assertThat(line(DiagramNodeKind.PARALLELOGRAM)).isEqualTo("""[/"L"/]""")
        assertThat(line(DiagramNodeKind.TRAPEZOID)).isEqualTo("""[\"L"/]""")
        assertThat(line(DiagramNodeKind.TRIANGLE)).isEqualTo("""[/"L"\]""")
        assertThat(line(DiagramNodeKind.COMPONENT)).isEqualTo("""[["L"]]""")
        assertThat(line(DiagramNodeKind.ACTOR)).isEqualTo("""(("L"))""")
        assertThat(line(DiagramNodeKind.CLASS)).isEqualTo("""["L"]""")
    }
}
