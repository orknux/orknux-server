package io.mszymanski.orknux.server.diagram

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Where two lines cross, the one drawn later hops over the other, so a
 * crossing reads as two lines passing rather than as a junction. The editor
 * draws the same hops (`jumped` in DiagramRoutes.ts).
 */
class DiagramLineJumpsTest {

    private fun box(id: String, x: Int, y: Int) = DiagramShape(id, DiagramNodeKind.BOX, id, x, y, 80, 40)

    /** Two lines in a cross: left to right through the middle, and top to bottom through it. */
    private val across = listOf(box("l", 0, 200), box("r", 400, 200))
    private val down = listOf(box("t", 200, 0), box("b", 200, 400))

    @Test
    fun `a line crossing one drawn before it hops over it, once, where they cross`() {
        val under = listOf(listOf(240.0 to 40.0, 240.0 to 400.0))
        val path = DiagramRoute.jumped(listOf(80.0 to 220.0, 400.0 to 220.0), under)

        assertThat(Regex(" A ").findAll(path).count()).isEqualTo(1)
        // From five short of the crossing to five past it, up over a line running across.
        assertThat(path).contains("L 235.0 220.0 A 5.0 5.0 0 0 1 245.0 220.0")
        assertThat(path).endsWith("L 400.0 220.0")
    }

    @Test
    fun `lines that do not cross, or only meet at an end, draw straight`() {
        val apart = DiagramRoute.jumped(listOf(0.0 to 0.0, 100.0 to 0.0), listOf(listOf(0.0 to 50.0, 100.0 to 50.0)))
        assertThat(apart).doesNotContain(" A ")
        val atTheEnd = DiagramRoute.jumped(listOf(0.0 to 0.0, 100.0 to 0.0), listOf(listOf(100.0 to -50.0, 100.0 to 50.0)))
        assertThat(atTheEnd).doesNotContain(" A ")
        // Too near an end to fit a hop: drawn through.
        val nearTheEnd = DiagramRoute.jumped(listOf(0.0 to 0.0, 100.0 to 0.0), listOf(listOf(97.0 to -50.0, 97.0 to 50.0)))
        assertThat(nearTheEnd).doesNotContain(" A ")
    }

    @Test
    fun `the exported picture hops the later line over the earlier, and not the other way`() {
        val drawing = DiagramDrawing(
            across + down,
            listOf(DiagramLink("first", "l", "r"), DiagramLink("second", "t", "b")),
        )
        val svg = DiagramSvg().drawing(drawing)

        // The first is drawn as it always was; the second, crossing it, as a path with a hop.
        assertThat(svg).contains("<line ")
        assertThat(Regex("<path d=\"M [^\"]* A 5\\.0 5\\.0 ").findAll(svg).count()).isEqualTo(1)
    }

    @Test
    fun `right-angle lines hop on their straight runs, and curves neither hop nor are hopped`() {
        val curve = DiagramDrawing(
            across + down,
            listOf(
                DiagramLink("first", "l", "r", routing = DiagramRouting.CURVED),
                DiagramLink("second", "t", "b", routing = DiagramRouting.ORTHOGONAL),
            ),
        )
        assertThat(DiagramSvg().drawing(curve)).doesNotContain(" A 5.0 5.0 ")

        val angles = DiagramDrawing(
            across + down,
            listOf(
                DiagramLink("first", "l", "r", routing = DiagramRouting.ORTHOGONAL),
                DiagramLink("second", "t", "b", routing = DiagramRouting.ORTHOGONAL),
            ),
        )
        assertThat(DiagramSvg().drawing(angles)).contains(" A 5.0 5.0 ")
    }

    /** A right-angle line's corners are rounded, in the picture as in the editor; a straight one has none to round. */
    @Test
    fun `a right-angle line rounds its corners, and a straight one stays straight`() {
        val (l, r) = listOf(box("l", 0, 0), box("r", 400, 200))
        val turned = DiagramRoute.of(l, r, DiagramRouting.ORTHOGONAL)
        assertThat(turned.d.split(" Q ")).describedAs(turned.d).hasSize(3)
        assertThat(DiagramRoute.of(l, r, DiagramRouting.STRAIGHT).d).doesNotContain(" Q ")
    }
}
