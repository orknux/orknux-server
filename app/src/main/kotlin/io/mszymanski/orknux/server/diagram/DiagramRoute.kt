package io.mszymanski.orknux.server.diagram

import kotlin.math.abs
import kotlin.math.hypot
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

    /**
     * A drawn line: its path, and where its label goes. [points] are its
     * corners, start to end, for a line made of straight runs - straight or
     * right angles - and null for a curve, which neither jumps nor is jumped.
     */
    data class Route(val d: String, val labelX: Double, val labelY: Double, val points: List<Pair<Double, Double>>? = null) {
        /**
         * The line as straight runs, start to end: its corners, or a curve
         * followed closely enough to tell what it passes over - which is what
         * [DiagramCheck] measures and [DiagramRouter] steers around.
         */
        val trace: List<Pair<Double, Double>> get() = points ?: curve(d)
    }

    /** One line as it is drawn: by its own corners where it has them, else the way its routing says. */
    fun of(edge: DiagramLink, from: DiagramShape, to: DiagramShape): Route =
        if (edge.routed) through(from, to, edge.points, edge.labelAt) else of(from, to, edge.routing, edge.fromSide, edge.toSide)

    /**
     * Where a line heading out of [node] aims, so its end lands where it should:
     * far off the pinned side where one is set, so the outline's own point on
     * that side is where it leaves; the floating target [fx], [fy] otherwise -
     * the other box, which is how every end found its side before one could be
     * pinned.
     */
    private fun aim(node: DiagramShape, side: DiagramSide?, fx: Double, fy: Double): Pair<Double, Double> {
        val cx = node.x + node.w / 2.0
        val cy = node.y + node.h / 2.0
        val far = 1_000_000.0
        return when (side) {
            DiagramSide.TOP -> cx to cy - far
            DiagramSide.BOTTOM -> cx to cy + far
            DiagramSide.LEFT -> cx - far to cy
            DiagramSide.RIGHT -> cx + far to cy
            null -> fx to fy
        }
    }

    /** How near a side's corner a line may leave a shape: past a rounded corner, where the side is no longer one. */
    const val SIDE_INSET = 12.0

    /**
     * Where a line leaves [node] heading for [toward], and the corner it adds
     * to get there, if it needs one.
     *
     * A corner straight above or below the box is left from the top or the
     * bottom, at its own x where the shape is flat there; one beside it, from
     * that side at its own y; one off at a slant - a box moved since the line
     * was routed - from the side facing it, with a turn to reach it.
     */
    fun leave(node: DiagramShape, toward: Pair<Double, Double>): Pair<Pair<Double, Double>, Pair<Double, Double>?> {
        val (px, py) = toward
        val left = node.x.toDouble()
        val right = (node.x + node.w).toDouble()
        val top = node.y.toDouble()
        val bottom = (node.y + node.h).toDouble()
        val cx = node.x + node.w / 2.0
        val cy = node.y + node.h / 2.0
        val far = 1_000_000.0
        fun along(at: Double, low: Double, high: Double, middle: Double) =
            if (at >= low + SIDE_INSET && at <= high - SIDE_INSET) at else middle
        return when {
            px in left..right && (py < top || py > bottom) -> {
                val down = py > bottom
                val x = along(px, left, right, cx)
                // Straight out of the side from that point inside: the outline itself, whatever the shape.
                val rim = DiagramOutline.borderFrom(node, x, cy, x, if (down) cy + far else cy - far)
                // Left at the middle where it is not flat there, then across onto the corner's line.
                rim to if (abs(x - px) < 1.0) null else (x to py)
            }
            py in top..bottom && (px < left || px > right) -> {
                val east = px > right
                val y = along(py, top, bottom, cy)
                val rim = DiagramOutline.borderFrom(node, cx, y, if (east) cx + far else cx - far, y)
                rim to if (abs(y - py) < 1.0) null else (px to y)
            }
            else -> {
                // At a slant, or inside: from the side facing it, then a turn onto its line.
                val dx = px - cx
                val dy = py - cy
                if (abs(dx) / maxOf(node.w, 1) >= abs(dy) / maxOf(node.h, 1)) {
                    val rim = DiagramOutline.border(node, if (dx > 0) cx + far else cx - far, cy)
                    rim to (px to rim.second)
                } else {
                    val rim = DiagramOutline.border(node, cx, if (dy > 0) cy + far else cy - far)
                    rim to (rim.first to py)
                }
            }
        }
    }

    /**
     * A right-angle line through its own [corners]: left from [from] toward
     * the first, through each, and into [to] from the last. Its label sits on
     * the middle of its longest run, where it has the most room.
     */
    fun through(from: DiagramShape, to: DiagramShape, corners: List<Pair<Int, Int>>, labelAt: Double? = null): Route {
        val inner = corners.map { it.first.toDouble() to it.second.toDouble() }
        val (start, turnOut) = leave(from, inner.first())
        val (end, turnIn) = leave(to, inner.last())
        val all = buildList {
            add(start)
            turnOut?.let(::add)
            addAll(inner)
            turnIn?.let(::add)
            add(end)
        }
        val points = straightened(all)
        var longest = 1
        var most = -1.0
        for (at in 1 until points.size) {
            val length = hypot(points[at].first - points[at - 1].first, points[at].second - points[at - 1].second)
            if (length > most) { most = length; longest = at }
        }
        val (ax, ay) = points[longest - 1]
        val (bx, by) = points[longest]
        val (lx, ly) = labelAt?.let { along(points, it) } ?: ((ax + bx) / 2 to (ay + by) / 2)
        return Route(jumped(points, emptyList()), lx, ly, points)
    }

    /** The point [share] of the way along [points], by length. The editor's `along`. */
    fun along(points: List<Pair<Double, Double>>, share: Double): Pair<Double, Double> {
        val lengths = (1 until points.size).map { hypot(points[it].first - points[it - 1].first, points[it].second - points[it - 1].second) }
        var left = lengths.sum() * share.coerceIn(0.0, 1.0)
        for (at in lengths.indices) {
            if (left <= lengths[at] || at == lengths.size - 1) {
                val part = if (lengths[at] == 0.0) 0.0 else (left / lengths[at]).coerceIn(0.0, 1.0)
                val (ax, ay) = points[at]
                val (bx, by) = points[at + 1]
                return (ax + (bx - ax) * part) to (ay + (by - ay) * part)
            }
            left -= lengths[at]
        }
        return points.last()
    }

    /** [points] without a repeat or a point in the middle of a straight run, which would only round a corner that is not there. */
    fun straightened(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val kept = mutableListOf<Pair<Double, Double>>()
        for (point in points) {
            if (kept.isNotEmpty() && abs(kept.last().first - point.first) < 0.5 && abs(kept.last().second - point.second) < 0.5) continue
            if (kept.size >= 2) {
                val (ax, ay) = kept[kept.size - 2]
                val (bx, by) = kept.last()
                val cross = (bx - ax) * (point.second - ay) - (by - ay) * (point.first - ax)
                val onward = (bx - ax) * (point.first - bx) + (by - ay) * (point.second - by)
                if (abs(cross) < 0.5 && onward >= 0) kept.removeAt(kept.size - 1)
            }
            kept += point
        }
        return kept
    }

    /** A path's cubic curve followed in short straight runs; a path of lines alone is its own points. */
    private fun curve(d: String): List<Pair<Double, Double>> {
        val numbers = Regex("-?\\d+(?:\\.\\d+)?").findAll(d).map { it.value.toDouble() }.toList()
        if (" C " !in d || numbers.size < 8) return numbers.chunked(2).filter { it.size == 2 }.map { it[0] to it[1] }
        val (x0, y0, x1, y1) = numbers.subList(0, 4)
        val (x2, y2, x3, y3) = numbers.subList(4, 8)
        return (0..CURVE_STEPS).map { step ->
            val t = step.toDouble() / CURVE_STEPS
            val u = 1 - t
            (u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3) to
                (u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3)
        }
    }

    private const val CURVE_STEPS = 24

    fun of(from: DiagramShape, to: DiagramShape, routing: DiagramRouting, fromSide: DiagramSide? = null, toSide: DiagramSide? = null): Route {
        val ax = from.x + from.w / 2.0
        val ay = from.y + from.h / 2.0
        val bx = to.x + to.w / 2.0
        val by = to.y + to.h / 2.0
        if (routing == DiagramRouting.STRAIGHT) {
            val (fax, fay) = aim(from, fromSide, bx, by)
            val (tax, tay) = aim(to, toSide, ax, ay)
            val (x1, y1) = DiagramOutline.border(from, fax, fay)
            val (x2, y2) = DiagramOutline.border(to, tax, tay)
            return Route("M ${r(x1)} ${r(y1)} L ${r(x2)} ${r(y2)}", (x1 + x2) / 2, (y1 + y2) / 2, listOf(x1 to y1, x2 to y2))
        }
        val across = max(to.x - (from.x + from.w), from.x - (to.x + to.w))
        val down = max(to.y - (from.y + from.h), from.y - (to.y + to.h))
        // Far out along the axis, so the outline's own edge on that side is where the line leaves it.
        val far = 1_000_000.0
        return if (across >= down) {
            val toward = if (bx >= ax) 1 else -1
            val (fax, fay) = aim(from, fromSide, ax + toward * far, ay)
            val (tax, tay) = aim(to, toSide, bx - toward * far, by)
            val (x1, y1) = DiagramOutline.border(from, fax, fay)
            val (x2, y2) = DiagramOutline.border(to, tax, tay)
            val mx = (x1 + x2) / 2
            val corners = if (routing != DiagramRouting.ORTHOGONAL) null
            else if (abs(y1 - y2) < 0.5) listOf(x1 to y1, x2 to y2)
            else listOf(x1 to y1, mx to y1, mx to y2, x2 to y2)
            val d = corners?.let { jumped(it, emptyList()) } ?: "M ${r(x1)} ${r(y1)} C ${r(mx)} ${r(y1)} ${r(mx)} ${r(y2)} ${r(x2)} ${r(y2)}"
            Route(d, mx, (y1 + y2) / 2, corners)
        } else {
            val toward = if (by >= ay) 1 else -1
            val (fax, fay) = aim(from, fromSide, ax, ay + toward * far)
            val (tax, tay) = aim(to, toSide, bx, by - toward * far)
            val (x1, y1) = DiagramOutline.border(from, fax, fay)
            val (x2, y2) = DiagramOutline.border(to, tax, tay)
            val my = (y1 + y2) / 2
            val corners = if (routing != DiagramRouting.ORTHOGONAL) null
            else if (abs(x1 - x2) < 0.5) listOf(x1 to y1, x2 to y2)
            else listOf(x1 to y1, x1 to my, x2 to my, x2 to y2)
            val d = corners?.let { jumped(it, emptyList()) } ?: "M ${r(x1)} ${r(y1)} C ${r(x1)} ${r(my)} ${r(x2)} ${r(my)} ${r(x2)} ${r(y2)}"
            Route(d, (x1 + x2) / 2, my, corners)
        }
    }

    /** How far a jump reaches either side of the line it crosses: half its width. */
    const val HOP = 5.0

    /** How round a right-angle line's corners are, less where a run is too short for it; the editor's `CORNER`. */
    const val CORNER = 10.0

    /**
     * [points] as a path that hops over every line in [under] it crosses.
     *
     * Where two lines cross, the one drawn later - on top - lifts over the
     * other in a half circle, so a crossing reads as two lines passing and not
     * as a junction. A hop is never put at a line's end, on a corner, nor over
     * another: a crossing that close is drawn straight through. Each corner is
     * rounded, a quarter turn of [corner] or less where a run is shorter than
     * two of them. The editor makes the same path (`pathOf` in `DiagramRoutes.ts`).
     */
    fun jumped(
        points: List<Pair<Double, Double>>,
        under: List<List<Pair<Double, Double>>>,
        hop: Double = HOP,
        corner: Double = CORNER,
    ): String {
        val lengths = (1 until points.size).map { hypot(points[it].first - points[it - 1].first, points[it].second - points[it - 1].second) }
        val radius = points.indices.map { at ->
            if (at == 0 || at == points.size - 1) 0.0 else minOf(corner, lengths[at - 1] / 2, lengths[at] / 2)
        }
        val path = StringBuilder("M ${r(points[0].first)} ${r(points[0].second)}")
        for (at in 1 until points.size) {
            val (ax, ay) = points[at - 1]
            val (bx, by) = points[at]
            val length = lengths[at - 1]
            if (length == 0.0) continue
            val ux = (bx - ax) / length
            val uy = (by - ay) / length
            val across = under.flatMap { line ->
                (1 until line.size).mapNotNull { j -> crossing(ax, ay, bx, by, line[j - 1], line[j]) }
            }.sorted()
            var reached = radius[at - 1]
            val until = length - radius[at]
            for (distance in across) {
                if (distance - hop < reached + 0.5 || distance + hop > until - 0.5) continue
                val sx = ax + ux * (distance - hop)
                val sy = ay + uy * (distance - hop)
                val ex = ax + ux * (distance + hop)
                val ey = ay + uy * (distance + hop)
                // Up over a line running across, to the left over one running down.
                val sweep = if (abs(ux) >= abs(uy)) (if (ux > 0) 1 else 0) else (if (uy > 0) 0 else 1)
                path.append(" L ${r(sx)} ${r(sy)} A ${r(hop)} ${r(hop)} 0 0 $sweep ${r(ex)} ${r(ey)}")
                reached = distance + hop
            }
            if (at == points.size - 1 || radius[at] == 0.0) {
                path.append(" L ${r(bx)} ${r(by)}")
            } else {
                // Up to the corner, round it through its point, and on along the next run.
                val (cx, cy) = points[at + 1]
                val next = lengths[at]
                val turn = radius[at]
                path.append(" L ${r(bx - ux * turn)} ${r(by - uy * turn)} Q ${r(bx)} ${r(by)} ${r(bx + (cx - bx) / next * turn)} ${r(by + (cy - by) / next * turn)}")
            }
        }
        return path.toString()
    }

    /** How far along a-b the segment c-d crosses it, where it does inside both; null where it does not. */
    private fun crossing(ax: Double, ay: Double, bx: Double, by: Double, c: Pair<Double, Double>, d: Pair<Double, Double>): Double? {
        val rx = bx - ax
        val ry = by - ay
        val sx = d.first - c.first
        val sy = d.second - c.second
        val den = rx * sy - ry * sx
        if (abs(den) < 1e-9) return null
        val t = ((c.first - ax) * sy - (c.second - ay) * sx) / den
        val u = ((c.first - ax) * ry - (c.second - ay) * rx) / den
        if (t <= 0 || t >= 1 || u <= 0 || u >= 1) return null
        return t * hypot(rx, ry)
    }

    private fun r(value: Double): String = "%.1f".format(java.util.Locale.ROOT, value)
}
