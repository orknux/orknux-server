package io.mszymanski.orknux.server.diagram

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Routing a diagram of the size people draw takes seconds, not minutes.
 *
 * The first router measured every step of its search against every box and
 * every line, and took nine minutes over the sample of thirty-four boxes; an
 * agent waited all of it, and the person behind it. Thirty-six boxes in a grid
 * with forty lines between far-apart ones is the shape that cost most.
 */
class DiagramRouterSpeedTest {

    @Test
    fun `forty lines across a grid of thirty-six boxes are routed in seconds, and clear`() {
        val nodes = (0 until 36).map { at ->
            DiagramShape("n$at", DiagramNodeKind.BOX, "box $at", (at % 6) * 260, (at / 6) * 180, 160, 60)
        }
        val edges = (0 until 40).map { at ->
            DiagramLink("e$at", "n${at % 36}", "n${(at * 7 + 11) % 36}".let { if (it == "n${at % 36}") "n${(at + 1) % 36}" else it })
        }
        val drawing = DiagramDrawing(nodes, edges)

        val started = System.nanoTime()
        val outcome = DiagramRouter.route(drawing)
        val seconds = (System.nanoTime() - started) / 1e9

        assertThat(seconds).describedAs("routing took ${"%.1f".format(seconds)}s").isLessThan(20.0)
        assertThat(outcome.lines.count { it.outcome == DiagramRouter.ROUTED }).isEqualTo(40)
        assertThat(DiagramCheck.of(outcome.drawing).filter { it.kind == DiagramCheck.Kind.LINE_THROUGH_SHAPE }).isEmpty()
    }
}
