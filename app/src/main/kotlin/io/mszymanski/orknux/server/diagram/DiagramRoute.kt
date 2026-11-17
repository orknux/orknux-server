package io.mszymanski.orknux.server.diagram

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

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

    /** One line as it is drawn: by its own corners or bends where it has them, else the way its routing says. */
    fun of(edge: DiagramLink, from: DiagramShape, to: DiagramShape): Route = when {
        edge.routed -> through(from, to, edge.points, edge.labelAt)
        edge.points.isNotEmpty() -> bent(from, to, edge.routing, edge.points, edge.fromSide, edge.toSide, edge.labelAt)
        else -> of(from, to, edge.routing, edge.fromSide, edge.toSide)
    }

    /**
     * A straight or curved line bent through points of its own - dragged there
     * by hand in the editor - the editor's `routeBent`, so the pictures agree
     * with it. Each end leaves its box toward the bend nearest it, or by the
     * side it is pinned to. Straight, it runs bend to bend, its corners rounded
     * as a right-angle line's are; curved, it passes smoothly through every
     * bend - a Catmull-Rom spline, each part a cubic - square out of a pinned
     * side as an unbent curve is.
     *
     * The label sits on the middle bend where there is one in the middle, and
     * half way along the middle part where there is not; [labelAt] puts it a
     * share of the way along instead, on a straight line.
     */
    fun bent(
        from: DiagramShape,
        to: DiagramShape,
        routing: DiagramRouting,
        bends: List<Pair<Int, Int>>,
        fromSide: DiagramSide? = null,
        toSide: DiagramSide? = null,
        labelAt: Double? = null,
    ): Route {
        val inner = bends.map { it.first.toDouble() to it.second.toDouble() }
        val (fax, fay) = aim(from, fromSide, inner.first().first, inner.first().second)
        val (tax, tay) = aim(to, toSide, inner.last().first, inner.last().second)
        val all = listOf(DiagramOutline.border(from, fax, fay)) + inner + listOf(DiagramOutline.border(to, tax, tay))
        if (routing != DiagramRouting.CURVED) {
            var longest = 1
            var most = -1.0
            for (at in 1 until all.size) {
                val length = hypot(all[at].first - all[at - 1].first, all[at].second - all[at - 1].second)
                if (length > most) { most = length; longest = at }
            }
            val (lx, ly) = labelAt?.let { along(all, it) }
                ?: (((all[longest - 1].first + all[longest].first) / 2) to ((all[longest - 1].second + all[longest].second) / 2))
            return Route(jumped(all, emptyList()), lx, ly, all)
        }
        val outFrom = outOf(from, fromSide)
        val outTo = outOf(to, toSide)
        val d = StringBuilder("M ${r(all[0].first)} ${r(all[0].second)}")
        val mids = mutableListOf<Pair<Double, Double>>()
        for (at in 0 until all.size - 1) {
            val p = all[at]
            val q = all[at + 1]
            val before = if (at == 0) null else all[at - 1]
            val after = if (at + 2 < all.size) all[at + 2] else null
            val c1 = when {
                before != null -> (p.first + (q.first - before.first) / 6) to (p.second + (q.second - before.second) / 6)
                outFrom != null -> bend(p.first, p.second, outFrom, q)
                else -> (p.first + (q.first - p.first) / 6) to (p.second + (q.second - p.second) / 6)
            }
            val c2 = when {
                after != null -> (q.first - (after.first - p.first) / 6) to (q.second - (after.second - p.second) / 6)
                outTo != null -> bend(q.first, q.second, outTo, p)
                else -> (q.first - (q.first - p.first) / 6) to (q.second - (q.second - p.second) / 6)
            }
            d.append(" C ${r(c1.first)} ${r(c1.second)} ${r(c2.first)} ${r(c2.second)} ${r(q.first)} ${r(q.second)}")
            mids += ((p.first + 3 * c1.first + 3 * c2.first + q.first) / 8) to ((p.second + 3 * c1.second + 3 * c2.second + q.second) / 8)
        }
        val parts = all.size - 1
        val (lx, ly) = if (parts % 2 == 0) all[parts / 2] else mids[(parts - 1) / 2]
        return Route(d.toString(), lx, ly, null)
    }

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
        val (dx, dy) = when (side) {
            DiagramSide.TOP -> 0.0 to -far
            DiagramSide.BOTTOM -> 0.0 to far
            DiagramSide.LEFT -> -far to 0.0
            DiagramSide.RIGHT -> far to 0.0
            null -> return fx to fy
        }
        // A side turns with its box: the top of one turned a quarter is on its right.
        val (tx, ty) = node.rotation?.let { DiagramOutline.turned(dx, dy, it) } ?: (dx to dy)
        return (cx + tx) to (cy + ty)
    }

    /** The way out of [node] through a side an end is pinned to, a step long and turned with its box; null for an end pinned nowhere. */
    private fun outOf(node: DiagramShape, side: DiagramSide?): Pair<Double, Double>? {
        val (dx, dy) = when (side) {
            DiagramSide.TOP -> 0.0 to -1.0
            DiagramSide.BOTTOM -> 0.0 to 1.0
            DiagramSide.LEFT -> -1.0 to 0.0
            DiagramSide.RIGHT -> 1.0 to 0.0
            null -> return null
        }
        return node.rotation?.let { DiagramOutline.turned(dx, dy, it) } ?: (dx to dy)
    }

    /**
     * Where a curve bends from an end pinned to a side: straight out of that
     * side - [out] - before it turns for the [other] end, so it meets the side
     * square on. A curve bent along the gap between the boxes instead ran into
     * a top it was pinned to nearly flat, its arrowhead lying along the outline
     * it pointed at. Half the way there, as an unpinned end goes; never less
     * than [BEND] where there is room for it, and further out for an end that
     * lies behind the side, which the curve has to come round to.
     */
    private fun bend(x: Double, y: Double, out: Pair<Double, Double>, other: Pair<Double, Double>): Pair<Double, Double> {
        val dx = other.first - x
        val dy = other.second - y
        val gap = hypot(dx, dy)
        val ahead = dx * out.first + dy * out.second
        val reach = if (ahead >= 0) max(ahead / 2, min(BEND, gap / 2)) else min(gap, BEND + sqrt(-ahead) * 4)
        return (x + out.first * reach) to (y + out.second * reach)
    }

    /** How far out of a pinned side a curve runs before it turns, at the least; the editor's `BEND`. */
    const val BEND = 40.0

    /** A curve from one end to the other by way of the two points it bends from, and the point half way along it, where its label sits. */
    private fun curved(x1: Double, y1: Double, c1: Pair<Double, Double>, c2: Pair<Double, Double>, x2: Double, y2: Double): Route = Route(
        "M ${r(x1)} ${r(y1)} C ${r(c1.first)} ${r(c1.second)} ${r(c2.first)} ${r(c2.second)} ${r(x2)} ${r(y2)}",
        (x1 + 3 * c1.first + 3 * c2.first + x2) / 8,
        (y1 + 3 * c1.second + 3 * c2.second + y2) / 8,
        null,
    )

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
        // A turned box is left from its middle: its sides no longer run along the axes a corner's line does.
        fun along(at: Double, low: Double, high: Double, middle: Double) =
            if (node.rotation == null && at >= low + SIDE_INSET && at <= high - SIDE_INSET) at else middle
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

    /**
     * A path of cubic curves followed in short straight runs - one curve, or
     * one after another through a bent line's bends; a path of lines alone is
     * its own points.
     */
    private fun curve(d: String): List<Pair<Double, Double>> {
        val numbers = Regex("-?\\d+(?:\\.\\d+)?").findAll(d).map { it.value.toDouble() }.toList()
        if (" C " !in d || numbers.size < 8) return numbers.chunked(2).filter { it.size == 2 }.map { it[0] to it[1] }
        val traced = mutableListOf(numbers[0] to numbers[1])
        var at = 2
        while (at + 6 <= numbers.size) {
            val (x0, y0) = traced.last()
            val (x1, y1, x2, y2) = numbers.subList(at, at + 4)
            val (x3, y3) = numbers.subList(at + 4, at + 6)
            (1..CURVE_STEPS).forEach { step ->
                val t = step.toDouble() / CURVE_STEPS
                val u = 1 - t
                traced += (u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x3) to
                    (u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y3)
            }
            at += 6
        }
        return traced
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
        val flat = across >= down
        if (routing == DiagramRouting.ORTHOGONAL && (fromSide != null || toSide != null)) {
            val toward = if (flat) (if (bx >= ax) 1.0 else -1.0) else (if (by >= ay) 1.0 else -1.0)
            // An end pinned nowhere leaves by the side facing the other box, as it does on a line pinned at neither end.
            val out = if (flat) toward to 0.0 else 0.0 to toward
            val (fax, fay) = aim(from, fromSide, ax + out.first * far, ay + out.second * far)
            val (tax, tay) = aim(to, toSide, bx - out.first * far, by - out.second * far)
            val start = DiagramOutline.border(from, fax, fay)
            val end = DiagramOutline.border(to, tax, tay)
            val corners = straightened(
                squared(
                    Box(ax, ay, from.w / 2.0, from.h / 2.0), Box(bx, by, to.w / 2.0, to.h / 2.0),
                    start, outOf(from, fromSide)?.let(::axisOf) ?: out,
                    end, outOf(to, toSide)?.let(::axisOf) ?: (-out.first to -out.second),
                ),
            )
            var longest = 1
            var most = -1.0
            for (at in 1 until corners.size) {
                val length = hypot(corners[at].first - corners[at - 1].first, corners[at].second - corners[at - 1].second)
                if (length > most) { most = length; longest = at }
            }
            return Route(
                jumped(corners, emptyList()),
                (corners[longest - 1].first + corners[longest].first) / 2,
                (corners[longest - 1].second + corners[longest].second) / 2,
                corners,
            )
        }
        return if (flat) {
            val toward = if (bx >= ax) 1 else -1
            val (fax, fay) = aim(from, fromSide, ax + toward * far, ay)
            val (tax, tay) = aim(to, toSide, bx - toward * far, by)
            val (x1, y1) = DiagramOutline.border(from, fax, fay)
            val (x2, y2) = DiagramOutline.border(to, tax, tay)
            val mx = (x1 + x2) / 2
            if (routing != DiagramRouting.ORTHOGONAL) {
                // An end pinned to a side leaves square to that side; one pinned nowhere bends across the gap, as it always did.
                return curved(
                    x1, y1,
                    outOf(from, fromSide)?.let { bend(x1, y1, it, x2 to y2) } ?: (mx to y1),
                    outOf(to, toSide)?.let { bend(x2, y2, it, x1 to y1) } ?: (mx to y2),
                    x2, y2,
                )
            }
            val corners = if (abs(y1 - y2) < 0.5) listOf(x1 to y1, x2 to y2) else listOf(x1 to y1, mx to y1, mx to y2, x2 to y2)
            Route(jumped(corners, emptyList()), mx, (y1 + y2) / 2, corners)
        } else {
            val toward = if (by >= ay) 1 else -1
            val (fax, fay) = aim(from, fromSide, ax, ay + toward * far)
            val (tax, tay) = aim(to, toSide, bx, by - toward * far)
            val (x1, y1) = DiagramOutline.border(from, fax, fay)
            val (x2, y2) = DiagramOutline.border(to, tax, tay)
            val my = (y1 + y2) / 2
            if (routing != DiagramRouting.ORTHOGONAL) {
                return curved(
                    x1, y1,
                    outOf(from, fromSide)?.let { bend(x1, y1, it, x2 to y2) } ?: (x1 to my),
                    outOf(to, toSide)?.let { bend(x2, y2, it, x1 to y1) } ?: (x2 to my),
                    x2, y2,
                )
            }
            val corners = if (abs(x1 - x2) < 0.5) listOf(x1 to y1, x2 to y2) else listOf(x1 to y1, x1 to my, x2 to my, x2 to y2)
            Route(jumped(corners, emptyList()), (x1 + x2) / 2, my, corners)
        }
    }

    /** A box by its middle and half sizes, upright: what a right-angle line is routed round. */
    private data class Box(val cx: Double, val cy: Double, val hw: Double, val hh: Double)

    /** How far out of a pinned side a right-angle line runs before it may turn; the editor's `STUB`. */
    const val STUB = 24.0

    /** The least room a right-angle line needs ahead of an end to turn there; the editor's `ROOM`. */
    private const val ROOM = 16.0

    /** The way out of a side as one of the four ways a right-angle line can run: a turned box's side leaves by the nearest. */
    private fun axisOf(out: Pair<Double, Double>): Pair<Double, Double> =
        if (abs(out.first) >= abs(out.second)) (if (out.first < 0) -1.0 else 1.0) to 0.0 else 0.0 to (if (out.second < 0) -1.0 else 1.0)

    private fun flipped(point: Pair<Double, Double>) = point.second to point.first

    /**
     * A right-angle line from [s], leaving the way [dS] points, to [e], which
     * it reaches coming in against [dE]; each is one of the four ways along
     * the axes. The editor's `squared`.
     *
     * Two ends that face each other with room between turn once in the
     * middle, as a line pinned nowhere does; two that face away go out a
     * [STUB] each and round between the boxes, or under them where they
     * overlap. Two that leave the same way - right side to right side - run
     * out past the further of the two and come back, round the other box
     * where it stands in the way: drawn as though pinned nowhere, such a line
     * ran back along the outline of its own box. And two at right angles meet
     * in one corner where both have room ahead for it, and in three where one
     * does not.
     */
    private fun squared(
        a: Box, b: Box,
        s: Pair<Double, Double>, dS: Pair<Double, Double>,
        e: Pair<Double, Double>, dE: Pair<Double, Double>,
    ): List<Pair<Double, Double>> {
        // Written for a start that leaves across; one that leaves up or down is the same line with x and y exchanged.
        if (dS.first == 0.0) {
            return squared(Box(a.cy, a.cx, a.hh, a.hw), Box(b.cy, b.cx, b.hh, b.hw), flipped(s), flipped(dS), flipped(e), flipped(dE)).map(::flipped)
        }
        val s1 = (s.first + dS.first * STUB) to s.second
        val e1 = (e.first + dE.first * STUB) to (e.second + dE.second * STUB)
        if (dE.first == 0.0) {
            val corner = e.first to s.second
            if ((corner.first - s.first) * dS.first >= ROOM && (corner.second - e.second) * dE.second >= ROOM) return listOf(s, corner, e)
            return listOf(s, s1, s1.first to e1.second, e1, e)
        }
        if (dE.first == -dS.first) {
            if ((e.first - s.first) * dS.first >= ROOM) {
                val mx = (s.first + e.first) / 2
                return listOf(s, mx to s.second, mx to e.second, e)
            }
            val my = between(a, b)
            return listOf(s, s1, s1.first to my, e1.first to my, e1, e)
        }
        val x = if (dS.first > 0) max(s1.first, e1.first) else min(s1.first, e1.first)
        if (crosses(b, s.second, s.first, x)) {
            val y = around(b, s.second)
            return listOf(s, s1, s1.first to y, x to y, x to e.second, e)
        }
        if (crosses(a, e.second, e.first, x)) {
            val y = around(a, e.second)
            return listOf(s, x to s.second, x to y, e1.first to y, e1, e)
        }
        return listOf(s, x to s.second, x to e.second, e)
    }

    /** Whether a run across at [y], between [x1] and [x2], passes through [box]. */
    private fun crosses(box: Box, y: Double, x1: Double, x2: Double): Boolean =
        y > box.cy - box.hh && y < box.cy + box.hh && max(x1, x2) > box.cx - box.hw && min(x1, x2) < box.cx + box.hw

    /** The height to pass [box] at, a [STUB] clear of it: over it from a line nearer its top, under it otherwise. */
    private fun around(box: Box, y: Double): Double = if (y < box.cy) box.cy - box.hh - STUB else box.cy + box.hh + STUB

    /** The height a line runs at between two boxes it cannot cross straight between: the middle of the gap, or under both where there is none. */
    private fun between(a: Box, b: Box): Double = when {
        b.cy - b.hh - (a.cy + a.hh) >= ROOM -> (a.cy + a.hh + b.cy - b.hh) / 2
        a.cy - a.hh - (b.cy + b.hh) >= ROOM -> (b.cy + b.hh + a.cy - a.hh) / 2
        else -> max(a.cy + a.hh, b.cy + b.hh) + STUB
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
