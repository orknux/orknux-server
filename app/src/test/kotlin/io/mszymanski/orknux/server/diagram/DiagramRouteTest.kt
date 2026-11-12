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
}
