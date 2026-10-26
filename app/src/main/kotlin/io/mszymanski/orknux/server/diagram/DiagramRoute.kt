package io.mszymanski.orknux.server.diagram

import kotlin.math.abs
import kotlin.math.max

/**
 * The way a line runs between its two nodes, as an SVG path and the point its
 * label sits on.
 *
 * Straight runs from outline to outline along the line between the centres,
 * as every line did before there was a choice. Right angles and curved leave
 * from a side and arrive at a side - the pair facing each other across the
 * wider of the two gaps between the boxes - so a line between two boxes side
 * by side runs across and one between boxes stacked runs down; right angles
 * turns once in the middle of that gap, and curved bends through it.
 *
 * The interface draws lines the same way (`routeOf` in `DiagramRoutes.ts`),
 * so the editor and the pictures exported from it agree.
 */
object DiagramRoute {

    /** A drawn line: its path, and where its label goes. */
    data class Route(val d: String, val labelX: Double, val labelY: Double)

    fun of(from: DiagramShape, to: DiagramShape, routing: DiagramRouting): Route {
        val ax = from.x + from.w / 2.0
        val ay = from.y + from.h / 2.0
        val bx = to.x + to.w / 2.0
        val by = to.y + to.h / 2.0
        if (routing == DiagramRouting.STRAIGHT) {
            val (x1, y1) = DiagramOutline.border(from, bx, by)
            val (x2, y2) = DiagramOutline.border(to, ax, ay)
            return Route("M ${r(x1)} ${r(y1)} L ${r(x2)} ${r(y2)}", (x1 + x2) / 2, (y1 + y2) / 2)
        }
        val across = max(to.x - (from.x + from.w), from.x - (to.x + to.w))
        val down = max(to.y - (from.y + from.h), from.y - (to.y + to.h))
        // Far out along the axis, so the outline's own edge on that side is where the line leaves it.
        val far = 1_000_000.0
        return if (across >= down) {
            val toward = if (bx >= ax) 1 else -1
            val (x1, y1) = DiagramOutline.border(from, ax + toward * far, ay)
            val (x2, y2) = DiagramOutline.border(to, bx - toward * far, by)
            val mx = (x1 + x2) / 2
            val d = if (routing == DiagramRouting.ORTHOGONAL) {
                if (abs(y1 - y2) < 0.5) "M ${r(x1)} ${r(y1)} L ${r(x2)} ${r(y2)}"
                else "M ${r(x1)} ${r(y1)} L ${r(mx)} ${r(y1)} L ${r(mx)} ${r(y2)} L ${r(x2)} ${r(y2)}"
            } else {
                "M ${r(x1)} ${r(y1)} C ${r(mx)} ${r(y1)} ${r(mx)} ${r(y2)} ${r(x2)} ${r(y2)}"
            }
            Route(d, mx, (y1 + y2) / 2)
        } else {
            val toward = if (by >= ay) 1 else -1
            val (x1, y1) = DiagramOutline.border(from, ax, ay + toward * far)
            val (x2, y2) = DiagramOutline.border(to, bx, by - toward * far)
            val my = (y1 + y2) / 2
            val d = if (routing == DiagramRouting.ORTHOGONAL) {
                if (abs(x1 - x2) < 0.5) "M ${r(x1)} ${r(y1)} L ${r(x2)} ${r(y2)}"
                else "M ${r(x1)} ${r(y1)} L ${r(x1)} ${r(my)} L ${r(x2)} ${r(my)} L ${r(x2)} ${r(y2)}"
            } else {
                "M ${r(x1)} ${r(y1)} C ${r(x1)} ${r(my)} ${r(x2)} ${r(my)} ${r(x2)} ${r(y2)}"
            }
            Route(d, (x1 + x2) / 2, my)
        }
    }

    private fun r(value: Double): String = "%.1f".format(java.util.Locale.ROOT, value)
}
