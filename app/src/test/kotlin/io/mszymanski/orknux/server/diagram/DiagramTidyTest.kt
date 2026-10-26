package io.mszymanski.orknux.server.diagram

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

/**
 * What made a large diagram look untidy: shapes whose side features grew with
 * their width, and lines that could only run straight across whatever lay
 * between their ends.
 */
class DiagramTidyTest {

    private val mapper = ObjectMapper()
    private val document = DiagramDocument(mapper)
    private val a = DiagramShape("a", DiagramNodeKind.BOX, "A", 0, 0, 160, 60)
    private val b = DiagramShape("b", DiagramNodeKind.BOX, "B", 400, 200, 160, 60)

    /** How far in from the node's left a rim point is, in pixels. */
    private fun px(units: Double, w: Int) = units * w / 100

    /**
     * Diagram 15's "Sign-in & access": a hexagon 1,000 pixels wide and 64 tall
     * drew slants of 150 pixels. A slant is now no more than 0.4 of the height,
     * and a hexagon of the size a new one starts at is drawn as before.
     */
    @Test
    fun `a wide shape keeps its side features to its height, and a shape of the usual size is as it was`() {
        val wide = DiagramOutline.of(DiagramNodeKind.HEXAGON, 1000, 64)!!
        assertThat(px(wide.rim!!.first().first, 1000)).isLessThanOrEqualTo(64 / 2.0)
        assertThat(DiagramOutline.of(DiagramNodeKind.HEXAGON, 160, 70)).isEqualTo(DiagramOutline.OUTLINES[DiagramNodeKind.HEXAGON])

        // A component's tabs, a package's tab and a device's depth the same way.
        val component = DiagramOutline.of(DiagramNodeKind.COMPONENT, 800, 60)!!
        assertThat(px(component.inside.left - 2, 800)).isLessThanOrEqualTo(0.35 * 60 + 0.01)
        val packaged = DiagramOutline.of(DiagramNodeKind.PACKAGE, 800, 60)!!
        assertThat(px(packaged.rim!![1].first, 800)).isLessThanOrEqualTo(1.2 * 60 + 0.01)
        val device = DiagramOutline.of(DiagramNodeKind.DEVICE, 800, 60)!!
        assertThat(px(device.rim!![1].first, 800)).isCloseTo(0.12 * 60, org.assertj.core.data.Offset.offset(0.01))

        // And the picture is drawn from the fitted outline.
        val svg = DiagramSvg().drawing(DiagramDrawing(listOf(DiagramShape("h", DiagramNodeKind.HEXAGON, "Sign-in", 0, 0, 1000, 64)), emptyList()))
        val first = Regex("""<path d="M ([0-9.]+) """).find(svg)!!.groupValues[1].toDouble()
        assertThat(first).isLessThanOrEqualTo(32.0)
    }

    @Test
    fun `a line's routing round-trips through the document, and a line that names none is straight`() {
        fun read(edge: String) =
            document.read("""{"nodes":[{"id":"a"},{"id":"b"}],"edges":[{"id":"e","from":"a","to":"b"$edge}]}""").edges.single()

        assertThat(read("").routing).isEqualTo(DiagramRouting.STRAIGHT)
        val bent = read(""","routing":"right-angles"""")
        assertThat(bent.routing).isEqualTo(DiagramRouting.ORTHOGONAL)
        val written = mapper.readTree(document.write(DiagramDrawing(listOf(a, b), listOf(bent)))).path("edges").single()
        assertThat(written.path("routing").stringValue()).isEqualTo("right-angles")
        assertThat(read(""","routing":"curved"""").routing).isEqualTo(DiagramRouting.CURVED)
    }

    @Test
    fun `right angles run only across and down, curved bends, and straight is drawn as it always was`() {
        fun svg(routing: DiagramRouting, label: String? = null) =
            DiagramSvg().drawing(DiagramDrawing(listOf(a, b), listOf(DiagramLink("e", "a", "b", label, routing = routing))))

        val straight = svg(DiagramRouting.STRAIGHT)
        assertThat(straight).contains("<line ")

        val bent = Regex("""<path d="(M [^"]+)" fill="none" stroke="[^"]+" stroke-width="1.5"""").find(svg(DiagramRouting.ORTHOGONAL))!!.groupValues[1]
        val points = Regex("""[ML] ([0-9.-]+) ([0-9.-]+)""").findAll(bent).map { it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }.toList()
        assertThat(points).hasSizeGreaterThanOrEqualTo(2)
        points.zipWithNext().forEach { (p, q) ->
            assertThat(p.first == q.first || p.second == q.second).describedAs("$p to $q").isTrue()
        }
        // It leaves A's right side, level with A's middle, and arrives at B's left, level with B's.
        assertThat(points.first()).isEqualTo(160.0 to 30.0)
        assertThat(points.last()).isEqualTo(400.0 to 230.0)

        assertThat(svg(DiagramRouting.CURVED)).contains(" C ")

        // The label sits on its pill where the bent line turns, not where a straight one would have crossed.
        val labelled = svg(DiagramRouting.ORTHOGONAL, "calls")
        assertThat(labelled).contains("""<rect x="""").contains(">calls<")
        val pill = Regex("""<rect x="([0-9.]+)" y="([0-9.]+)" width="([0-9.]+)" height="16" rx="3"""").find(labelled)!!
        val middle = pill.groupValues[1].toDouble() + pill.groupValues[3].toDouble() / 2
        assertThat(middle).isCloseTo(280.0, org.assertj.core.data.Offset.offset(1.0))
    }
}
