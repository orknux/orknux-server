package io.mszymanski.orknux.server.diagram

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The server draws the same line the editor does, for its exports and
 * thumbnails - so a line pinned to a side of its box has to leave that side
 * here too, not the side facing the other box. [DiagramRoute] is the renderer's
 * router; this holds it to the pin.
 */
class DiagramRouteTest {

    private fun box(id: String, x: Int, y: Int) = DiagramShape(id, DiagramNodeKind.BOX, id, x, y, 160, 60)

    @Test
    fun `a floating end leaves the side facing the other box, a pinned end leaves its pinned side`() {
        // b sits low and to the right, so a floating end out of a faces down and across, well below a's top.
        val a = box("a", 0, 0)
        val b = box("b", 360, 260)

        val floating = DiagramRoute.of(a, b, DiagramRouting.STRAIGHT)
        val (_, floatY) = floating.points!!.first()
        assertThat(floatY).isGreaterThan(20.0)

        // Pinned to the top, it leaves a's top-centre (80, 0) instead, whatever is where.
        val pinned = DiagramRoute.of(a, b, DiagramRouting.STRAIGHT, fromSide = DiagramSide.TOP)
        val (pinX, pinY) = pinned.points!!.first()
        assertThat(pinY).isLessThan(1.0)
        assertThat(pinX).isBetween(70.0, 90.0)

        // And the far end pinned to the left leaves b's left-centre (360, 290).
        val both = DiagramRoute.of(a, b, DiagramRouting.STRAIGHT, fromSide = DiagramSide.TOP, toSide = DiagramSide.LEFT)
        val (toX, toY) = both.points!!.last()
        assertThat(toX).isLessThan(361.0)
        assertThat(toY).isBetween(280.0, 300.0)
    }

    @Test
    fun `a curve meets a side it is pinned to square on, and bends across the gap where it is pinned nowhere`() {
        val a = box("a", 0, 0)
        val b = box("b", 600, 400)

        // Pinned nowhere, as every curve was drawn: out of a's right, into b's left, bent half way across.
        assertThat(DiagramRoute.of(a, b, DiagramRouting.CURVED).d).isEqualTo("M 160.0 30.0 C 380.0 30.0 380.0 430.0 600.0 430.0")

        // Pinned to b's top it comes down into it: the point it bends from is straight above where it lands, not beside it.
        val down = DiagramRoute.of(a, b, DiagramRouting.CURVED, toSide = DiagramSide.TOP)
        assertThat(down.d).isEqualTo("M 160.0 30.0 C 420.0 30.0 680.0 215.0 680.0 400.0")
        // And its label sits on the curve, half way along it.
        assertThat(down.labelX).isEqualTo((160 + 3 * 420 + 3 * 680 + 680) / 8.0)
        assertThat(down.labelY).isEqualTo((30 + 3 * 30 + 3 * 215 + 400) / 8.0)

        // Both ends pinned: out of a's bottom going down, into b's top coming down.
        assertThat(DiagramRoute.of(a, b, DiagramRouting.CURVED, fromSide = DiagramSide.BOTTOM, toSide = DiagramSide.TOP).d)
            .isEqualTo("M 80.0 60.0 C 80.0 230.0 680.0 230.0 680.0 400.0")

        // An end pinned to the side facing away still leaves by it, and comes round.
        val away = DiagramRoute.of(a, b, DiagramRouting.CURVED, fromSide = DiagramSide.LEFT).d
        assertThat(away).startsWith("M 0.0 30.0 C -")

        // A side turns with its box: the top of one turned a quarter is on its right, and the curve leaves it going right.
        val turned = DiagramRoute.of(a.copy(rotation = 90), b, DiagramRouting.CURVED, fromSide = DiagramSide.TOP).d
        val numbers = Regex("-?\\d+(?:\\.\\d+)?").findAll(turned).map { it.value.toDouble() }.toList()
        assertThat(numbers[2]).isGreaterThan(numbers[0])
        assertThat(numbers[3]).isCloseTo(numbers[1], org.assertj.core.api.Assertions.within(0.11))
    }

    @Test
    fun `a right-angle line leaves by the side it is pinned to and arrives by the other's`() {
        val a = box("a", 0, 0)
        val b = box("b", 600, 400)

        // Pinned nowhere, as every such line was drawn: across, one turn in the middle of the gap.
        assertThat(DiagramRoute.of(a, b, DiagramRouting.ORTHOGONAL).points)
            .containsExactly(160.0 to 30.0, 380.0 to 30.0, 380.0 to 430.0, 600.0 to 430.0)

        // Right side to right side: out past the further box and back into its right, not back through it from the gap.
        assertThat(DiagramRoute.of(a, b, DiagramRouting.ORTHOGONAL, fromSide = DiagramSide.RIGHT, toSide = DiagramSide.RIGHT).points)
            .containsExactly(160.0 to 30.0, 784.0 to 30.0, 784.0 to 430.0, 760.0 to 430.0)

        // Into a top from the side: one corner, straight above where it lands.
        assertThat(DiagramRoute.of(a, b, DiagramRouting.ORTHOGONAL, toSide = DiagramSide.TOP).points)
            .containsExactly(160.0 to 30.0, 680.0 to 30.0, 680.0 to 400.0)

        // Out of the side facing away: a step out of it first, then round.
        assertThat(DiagramRoute.of(a, b, DiagramRouting.ORTHOGONAL, fromSide = DiagramSide.LEFT).points)
            .containsExactly(0.0 to 30.0, -24.0 to 30.0, -24.0 to 430.0, 600.0 to 430.0)

        // Two right sides in one row: the line goes under the box that stands in its way, not through it.
        val beside = box("c", 400, 0)
        assertThat(DiagramRoute.of(a, beside, DiagramRouting.ORTHOGONAL, fromSide = DiagramSide.RIGHT, toSide = DiagramSide.RIGHT).points)
            .containsExactly(160.0 to 30.0, 184.0 to 30.0, 184.0 to 84.0, 584.0 to 84.0, 584.0 to 30.0, 560.0 to 30.0)

        // Bottom to top of a box below and to the side: down, across in the gap, down.
        assertThat(DiagramRoute.of(a, b, DiagramRouting.ORTHOGONAL, fromSide = DiagramSide.BOTTOM, toSide = DiagramSide.TOP).points)
            .containsExactly(80.0 to 60.0, 80.0 to 230.0, 680.0 to 230.0, 680.0 to 400.0)
    }

    @Test
    fun `the exported picture is framed round a run dragged out past every box`() {
        // Right side to right side, its upright run dragged out to 1024 - past both boxes, whose right edges are at 920.
        val f = box("f", 760, 100)
        val g = box("g", 760, 300)
        val run = DiagramLink(
            "r", "f", "g", routing = DiagramRouting.ORTHOGONAL,
            fromSide = DiagramSide.RIGHT, toSide = DiagramSide.RIGHT, points = listOf(1024 to 130, 1024 to 330),
        )
        val (left, _, width, _) = DiagramSvg().frame(DiagramDrawing(listOf(f, g), listOf(run)))

        // Framed from the boxes alone it ended at 944, and the run was not in the picture.
        assertThat(left + width).isGreaterThanOrEqualTo(1024)
        assertThat(DiagramSvg().drawing(DiagramDrawing(listOf(f, g), listOf(run)))).contains("1024.0 140.0")
    }

    @Test
    fun `a straight line bent by hand runs from bend to bend, each end leaving toward its nearest bend`() {
        // Boxes side by side, bent down under them: out of a's bottom toward the bend, along, and up into b's bottom.
        val a = box("a", 0, 0)
        val b = box("b", 400, 0)
        val line = DiagramLink("l", "a", "b", routing = DiagramRouting.STRAIGHT, points = listOf(80 to 200, 480 to 200))
        val points = DiagramRoute.of(line, a, b).points!!

        assertThat(points).hasSize(4)
        assertThat(points[0]).isEqualTo(80.0 to 60.0)
        assertThat(points[1]).isEqualTo(80.0 to 200.0)
        assertThat(points[2]).isEqualTo(480.0 to 200.0)
        assertThat(points[3]).isEqualTo(480.0 to 60.0)
    }

    @Test
    fun `a curved line bent by hand passes through its bend, and is traced to its far end`() {
        val a = box("a", 0, 0)
        val b = box("b", 400, 0)
        val line = DiagramLink("l", "a", "b", routing = DiagramRouting.CURVED, points = listOf(280 to 240))
        val route = DiagramRoute.of(line, a, b)

        // Two curves, the first ending on the bend; the label on the bend, the middle of the line.
        assertThat(Regex(" C ").findAll(route.d).count()).isEqualTo(2)
        assertThat(route.d).contains(" 280.0 240.0 C ")
        assertThat(route.labelX to route.labelY).isEqualTo(280.0 to 240.0)
        // Followed all the way: its trace reaches the bend and ends on b, not where the first curve ends.
        assertThat(route.trace.maxOf { it.second }).isGreaterThanOrEqualTo(239.0)
        assertThat(route.trace.last().first).isGreaterThan(400.0)
        // And a picture of it is framed round the bend, below both boxes.
        val (_, top, _, height) = DiagramSvg().frame(DiagramDrawing(listOf(a, b), listOf(line)))
        assertThat(top + height).isGreaterThanOrEqualTo(240)
    }
}
