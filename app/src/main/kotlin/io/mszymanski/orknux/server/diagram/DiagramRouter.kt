package io.mszymanski.orknux.server.diagram

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Routes lines around the boxes in their way and off each other, in right
 * angles, as draw.io's orthogonal router does, and keeps the corners it found
 * on each line ([DiagramLink.points]).
 *
 * The search is A* over a sparse grid: the lines just outside every box -
 * [MARGIN] clear of it - the middles of the gaps between boxes, and where the
 * lines could leave and arrive. A step costs its length, a turn [BEND], a
 * crossing over a line already drawn [CROSS], and running along one [OVERLAP],
 * so a route takes a longer way rather than lie on another line and a turn
 * rather than a crossing - but neither is forbidden, because a diagram can be
 * drawn too tight for any route to avoid them, and a line drawn badly is still
 * a line, where none is a lie. Through a box is forbidden: there it would hide
 * words.
 *
 * Lines are routed shortest first, each steering clear of those drawn before
 * it; one that still ends up on another line or through a box is routed again
 * once every other is in place. Each line may leave and arrive on any side of
 * its box, along a flat side at a quarter as well as the middle, and a place
 * on a side another line already uses costs [PORT_TAKEN], so the lines into
 * one box spread out instead of sharing their last run.
 */
object DiagramRouter {

    const val MARGIN = 16.0
    const val BEND = 30.0
    const val CROSS = 60.0
    const val OVERLAP = 1500.0
    const val PORT_TAKEN = 1500.0

    /** What happened to one line. */
    data class Routed(
        val edge: String,
        /** routed, unchanged (asked for, but no way was found), or skipped (a line from a box to itself, or between boxes on each other). */
        val outcome: String,
        val bends: Int = 0,
        val length: Int = 0,
        val says: String? = null,
    )

    data class Outcome(val drawing: DiagramDrawing, val lines: List<Routed>)

    /** [drawing] with the lines named in [only] - or every line - routed; the rest as they were, steered around. */
    fun route(drawing: DiagramDrawing, only: Set<String>? = null): Outcome {
        val byId = drawing.nodes.associateBy { it.id }
        val parents = drawing.nodes.mapNotNull { it.parent }.toSet()
        val solid = drawing.nodes.filter { it.kind != DiagramNodeKind.GROUP && it.id !in parents }
        val edges = drawing.edges.toMutableList()
        val asked = edges.indices.filter { only == null || edges[it].id in only }
        val skipped = mutableMapOf<String, String>()
        val todo = asked.filter { at ->
            val edge = edges[at]
            val from = byId.getValue(edge.from)
            val to = byId.getValue(edge.to)
            when {
                edge.from == edge.to -> { skipped[edge.id] = "it runs from a box to itself"; false }
                overlap(from, to) -> { skipped[edge.id] = "its two boxes lie on each other, so there is no gap to route through"; false }
                else -> true
            }
        }.sortedBy { at ->
            val from = byId.getValue(edges[at].from)
            val to = byId.getValue(edges[at].to)
            abs(from.x + from.w / 2.0 - to.x - to.w / 2.0) + abs(from.y + from.h / 2.0 - to.y - to.h / 2.0)
        }
        val routing = todo.toSet()
        val result = mutableMapOf<String, Routed>()

        fun others(except: Int): List<List<Pair<Double, Double>>> = edges.indices
            // A line still waiting its turn is not steered around: where it is now is not where it will be.
            .filter { it != except && (it !in routing || result.containsKey(edges[it].id)) }
            .mapNotNull { at ->
                val edge = edges[at]
                if (edge.from == edge.to) null else DiagramRoute.of(edge, byId.getValue(edge.from), byId.getValue(edge.to)).trace
            }

        fun one(at: Int) {
            val edge = edges[at]
            val from = byId.getValue(edge.from)
            val to = byId.getValue(edge.to)
            val drawn = others(at)
            val found = MARGINS.firstNotNullOfOrNull { margin -> search(from, to, solid, drawn, margin) }
            if (found == null) {
                result[edge.id] = Routed(edge.id, UNCHANGED, says = "no way around the boxes was found; it is drawn as it was")
                return
            }
            edges[at] = edge.copy(routing = DiagramRouting.ORTHOGONAL, points = found, labelAt = null)
            val route = DiagramRoute.through(from, to, found)
            val length = (1 until route.points!!.size).sumOf { hypot(route.points[it].first - route.points[it - 1].first, route.points[it].second - route.points[it - 1].second) }
            result[edge.id] = Routed(edge.id, ROUTED, bends = route.points.size - 2, length = length.toInt())
        }

        todo.forEach(::one)
        // Once every line is in place, again for any still on another or through a box: it was routed before some of them.
        val placed = drawing.copy(edges = edges)
        val troubled = DiagramCheck.of(placed)
            .filter { it.kind == DiagramCheck.Kind.LINES_OVERLAP || it.kind == DiagramCheck.Kind.LINE_THROUGH_SHAPE }
            .flatMap { it.edges }.toSet()
        todo.filter { edges[it].id in troubled && result[edges[it].id]?.outcome == ROUTED }.forEach(::one)
        placeLabels(edges, todo.filter { result[edges[it].id]?.outcome == ROUTED }, byId, solid)

        val lines = asked.map { at ->
            val id = drawing.edges[at].id
            result[id] ?: Routed(id, SKIPPED, says = skipped[id])
        }
        return Outcome(drawing.copy(edges = edges), lines)
    }

    /**
     * Each routed line's label slid along it to where it covers no box, no
     * other label and no other line, where its usual place - the middle of its
     * longest run - does. A share of the line's length, kept on the line, so
     * the editor and every picture put it in the same place.
     */
    private fun placeLabels(edges: MutableList<DiagramLink>, routed: List<Int>, byId: Map<String, DiagramShape>, solid: List<DiagramShape>) {
        fun routeOf(edge: DiagramLink) = DiagramRoute.of(edge, byId.getValue(edge.from), byId.getValue(edge.to))
        fun boxAt(label: String, x: Double, y: Double): DoubleArray {
            val half = DiagramSvg.labelHalfWidth(label)
            return doubleArrayOf(x - half, y - 9, x + half, y - 9 + DiagramSvg.LABEL_HEIGHT)
        }
        val lines = edges.indices.filter { edges[it].from != edges[it].to }
        val traces = lines.associateWith { routeOf(edges[it]).trace }.toMutableMap()
        val labels = lines.filter { !edges[it].label.isNullOrBlank() }.associateWith { at ->
            routeOf(edges[at]).let { boxAt(edges[at].label.orEmpty(), it.labelX, it.labelY) }
        }.toMutableMap()
        fun conflicts(at: Int, box: DoubleArray): Int =
            solid.count { DiagramCheck.overlaps(box, DiagramCheck.box(it, 0.0)) } +
                labels.count { (other, theirs) -> other != at && DiagramCheck.overlaps(box, theirs) } +
                traces.count { (other, trace) -> other != at && DiagramCheck.through(trace, box) != null }
        routed.filter { !edges[it].label.isNullOrBlank() }.forEach { at ->
            val edge = edges[at]
            val now = labels[at] ?: return@forEach
            val already = conflicts(at, now)
            if (already == 0) return@forEach
            val points = routeOf(edge).points ?: return@forEach
            val best = SHARES.map { share ->
                val (x, y) = DiagramRoute.along(points, share)
                share to boxAt(edge.label.orEmpty(), x, y)
            }.minByOrNull { (_, box) -> conflicts(at, box) } ?: return@forEach
            if (conflicts(at, best.second) < already) {
                edges[at] = edge.copy(labelAt = best.first)
                labels[at] = best.second
            }
        }
    }

    /** Where along a line its label is tried, nearest the middle first. */
    private val SHARES = listOf(0.5, 0.4, 0.6, 0.3, 0.7, 0.2, 0.8, 0.12, 0.88)

    const val ROUTED = "routed"
    const val UNCHANGED = "unchanged"
    const val SKIPPED = "skipped"

    /** Tried in turn: the full clearance, then less where boxes stand too close for it. */
    private val MARGINS = listOf(MARGIN, MARGIN / 2, 3.0)

    private fun overlap(a: DiagramShape, b: DiagramShape): Boolean =
        min(a.x + a.w, b.x + b.w) > max(a.x, b.x) && min(a.y + a.h, b.y + b.h) > max(a.y, b.y)

    /** A side of a box: which way out of it is, as a step on the grid. */
    private enum class Side(val dx: Int, val dy: Int) { RIGHT(1, 0), DOWN(0, 1), LEFT(-1, 0), UP(0, -1) }

    /** Where a line may leave or arrive: on the rim, and [MARGIN] out from the box, which is where the search starts or ends. */
    private class Port(val rim: Pair<Double, Double>, val stub: Pair<Double, Double>, val side: Side, val cost: Double)

    /**
     * Where a line may leave or arrive on [node]: the middle of each side and,
     * where the side is long enough, places along it either way - on the
     * outline itself, so a cylinder or a cloud has as many as a box. A place
     * another line already uses costs [PORT_TAKEN], as much as running along
     * that line would, since sharing a place is sharing the run out of it.
     */
    private fun ports(node: DiagramShape, margin: Double, taken: List<Pair<Double, Double>>): List<Port> {
        val cx = node.x + node.w / 2.0
        val cy = node.y + node.h / 2.0
        val far = 1_000_000.0
        return Side.entries.flatMap { side ->
            val across = side.dx == 0
            val span = if (across) node.w.toDouble() else node.h.toDouble()
            val room = span / 2 - DiagramRoute.SIDE_INSET
            val offsets = when {
                room >= span / 3 && span >= 120 -> listOf(0.0, -span / 6, span / 6, -span / 3, span / 3)
                room >= span / 4 -> listOf(0.0, -span / 4, span / 4)
                else -> listOf(0.0)
            }
            offsets.map { offset ->
                val rim = if (across) {
                    DiagramOutline.borderFrom(node, cx + offset, cy, cx + offset, cy + side.dy * far)
                } else {
                    DiagramOutline.borderFrom(node, cx, cy + offset, cx + side.dx * far, cy + offset)
                }
                val stub = when (side) {
                    Side.RIGHT -> node.x + node.w + margin to rim.second
                    Side.LEFT -> node.x - margin to rim.second
                    Side.DOWN -> rim.first to node.y + node.h + margin
                    Side.UP -> rim.first to node.y - margin
                }
                val used = taken.any { abs(it.first - rim.first) < 2 && abs(it.second - rim.second) < 2 }
                Port(rim, stub, side, (if (used) PORT_TAKEN else 0.0) + abs(offset) / span * 8)
            }
        }
    }

    /** One line's corners from [from] to [to], without its two ends; null where no way was found at this clearance. */
    private fun search(
        from: DiagramShape,
        to: DiagramShape,
        solid: List<DiagramShape>,
        drawn: List<List<Pair<Double, Double>>>,
        margin: Double,
    ): List<Pair<Int, Int>>? {
        val ends = drawn.flatMap { listOf(it.first(), it.last()) }
        val starts = ports(from, margin, ends)
        val goals = ports(to, margin, ends)
        val walls = solid.map { doubleArrayOf(it.x - margin, it.y - margin, it.x + it.w + margin, it.y + it.h + margin) }

        // The grid: the lines along every box's clearance, the middles of the gaps, and the ports.
        val lowX = solid.minOf { it.x } - margin * 4
        val highX = solid.maxOf { it.x + it.w } + margin * 4
        val lowY = solid.minOf { it.y } - margin * 4
        val highY = solid.maxOf { it.y + it.h } + margin * 4
        val xs = sortedSetOf(lowX, highX)
        val ys = sortedSetOf(lowY, highY)
        walls.forEach { xs += it[0]; xs += it[2]; ys += it[1]; ys += it[3] }
        (starts + goals).forEach { xs += it.stub.first; ys += it.stub.second }
        fun gaps(values: java.util.TreeSet<Double>) = values.zipWithNext { a, b -> (a + b) / 2 }.filter { gap -> values.none { abs(it - gap) < 1 } }
        gaps(xs).forEach { xs += it }
        gaps(ys).forEach { ys += it }
        val gx = xs.toDoubleArray()
        val gy = ys.toDoubleArray()
        val width = gx.size
        val height = gy.size
        if (width.toLong() * height > MOST_GRID) return null
        /** The grid line nearest [value], found by halving: every port and box edge is one exactly. */
        fun indexOf(values: DoubleArray, value: Double): Int {
            val found = java.util.Arrays.binarySearch(values, value)
            if (found >= 0) return found
            val after = -found - 1
            return if (after == 0) 0 else if (after >= values.size) values.size - 1 else if (value - values[after - 1] <= values[after] - value) after - 1 else after
        }
        /** The grid lines strictly between [low] and [high]. */
        fun between(values: DoubleArray, low: Double, high: Double): IntRange {
            var from = java.util.Arrays.binarySearch(values, low).let { if (it >= 0) it + 1 else -it - 1 }
            var until = java.util.Arrays.binarySearch(values, high).let { if (it >= 0) it - 1 else -it - 2 }
            while (from <= until && values[from] <= low) from++
            while (until >= from && values[until] >= high) until--
            return from..until
        }

        /*
         * What each step between two neighbouring points costs, worked out once
         * for the whole grid rather than at every step the search takes - a
         * step is looked at many times over, and measuring it against every box
         * and every line each time took minutes on a diagram of thirty boxes.
         * Across: from (i, j) to (i + 1, j), at i * height + j; down: from
         * (i, j) to (i, j + 1), at the same index.
         */
        val cells = width * height
        val blockedAcross = BooleanArray(cells)
        val blockedDown = BooleanArray(cells)
        val onAcross = BooleanArray(cells)
        val onDown = BooleanArray(cells)
        val crossAcross = IntArray(cells)
        val crossDown = IntArray(cells)
        walls.forEach { wall ->
            // A step is in a box where its middle is: inside on both axes, the edges themselves free.
            val rows = between(gy, wall[1], wall[3])
            val columns = between(gx, wall[0], wall[2])
            for (i in (columns.first - 1).coerceAtLeast(0)..columns.last.coerceAtMost(width - 2)) {
                val middle = (gx[i] + gx[i + 1]) / 2
                if (middle <= wall[0] + 0.01 || middle >= wall[2] - 0.01) continue
                for (j in rows) blockedAcross[i * height + j] = true
            }
            for (j in (rows.first - 1).coerceAtLeast(0)..rows.last.coerceAtMost(height - 2)) {
                val middle = (gy[j] + gy[j + 1]) / 2
                if (middle <= wall[1] + 0.01 || middle >= wall[3] - 0.01) continue
                for (i in columns) blockedDown[i * height + j] = true
            }
        }
        val runs = drawn.flatMap { line -> line.zipWithNext() }
        runs.forEach { (c, d) ->
            val level = abs(c.second - d.second) < 0.5
            val upright = abs(c.first - d.first) < 0.5
            if (level) {
                val y = c.second
                val left = min(c.first, d.first)
                val right = max(c.first, d.first)
                // Along it: the steps on its row, or one within touching distance, sharing more than a pixel of it.
                for (j in between(gy, y - DiagramCheck.TOUCHING, y + DiagramCheck.TOUCHING)) {
                    for (i in (between(gx, left, right).first - 1).coerceAtLeast(0)..(between(gx, left, right).last).coerceAtMost(width - 2)) {
                        if (min(right, gx[i + 1]) - max(left, gx[i]) > 1) onAcross[i * height + j] = true
                    }
                }
                // Across it: the step down that passes its height at a column it spans - the one leaving its row
                // where it lies on a grid line, so a path crossing it there pays once, whichever way it goes.
                val row = indexOf(gy, y).let { near -> if (abs(gy[near] - y) < 0.5) near else between(gy, Double.NEGATIVE_INFINITY, y).last }
                if (row in 0 until height - 1 && gy[row] <= y + 0.5 && gy[row + 1] > y) {
                    for (i in between(gx, left, right)) crossDown[i * height + row]++
                }
            } else if (upright) {
                val x = c.first
                val top = min(c.second, d.second)
                val bottom = max(c.second, d.second)
                for (i in between(gx, x - DiagramCheck.TOUCHING, x + DiagramCheck.TOUCHING)) {
                    for (j in (between(gy, top, bottom).first - 1).coerceAtLeast(0)..(between(gy, top, bottom).last).coerceAtMost(height - 2)) {
                        if (min(bottom, gy[j + 1]) - max(top, gy[j]) > 1) onDown[i * height + j] = true
                    }
                }
                val column = indexOf(gx, x).let { near -> if (abs(gx[near] - x) < 0.5) near else between(gx, Double.NEGATIVE_INFINITY, x).last }
                if (column in 0 until width - 1 && gx[column] <= x + 0.5 && gx[column + 1] > x) {
                    for (j in between(gy, top, bottom)) crossAcross[column * height + j]++
                }
            } else {
                // A slanted run - a straight or curved line left as it is: every step in its reach, measured.
                for (i in between(gx, min(c.first, d.first) - 1, max(c.first, d.first) + 1)) for (j in between(gy, min(c.second, d.second) - 1, max(c.second, d.second) + 1)) {
                    if (i + 1 < width && DiagramCheck.crossings(listOf(gx[i] to gy[j], gx[i + 1] to gy[j]), listOf(c, d)).isNotEmpty()) crossAcross[i * height + j]++
                    if (j + 1 < height && DiagramCheck.crossings(listOf(gx[i] to gy[j], gx[i] to gy[j + 1]), listOf(c, d)).isNotEmpty()) crossDown[i * height + j]++
                }
            }
        }
        /** What the step from (i, j) one [side] costs beyond its length; null where it goes into a box. */
        fun extra(i: Int, j: Int, side: Side): Double? {
            val (at, across) = when (side) {
                Side.RIGHT -> i * height + j to true
                Side.LEFT -> (i - 1) * height + j to true
                Side.DOWN -> i * height + j to false
                Side.UP -> i * height + (j - 1) to false
            }
            return if (across) {
                if (blockedAcross[at]) null else (if (onAcross[at]) OVERLAP else 0.0) + crossAcross[at] * CROSS
            } else {
                if (blockedDown[at]) null else (if (onDown[at]) OVERLAP else 0.0) + crossDown[at] * CROSS
            }
        }
        fun inside(i: Int, j: Int) = walls.any { gx[i] > it[0] + 0.01 && gx[i] < it[2] - 0.01 && gy[j] > it[1] + 0.01 && gy[j] < it[3] - 0.01 }

        val goalAt = goals.groupBy { indexOf(gx, it.stub.first) * height + indexOf(gy, it.stub.second) }
        fun guess(i: Int, j: Int) = goals.minOf { abs(gx[i] - it.stub.first) + abs(gy[j] - it.stub.second) }

        // States: a point and the way the last step went, by index into flat arrays.
        val sides = Side.entries
        val best = DoubleArray(cells * 4) { Double.MAX_VALUE }
        val came = IntArray(cells * 4) { -1 }
        fun key(i: Int, j: Int, d: Int) = ((i * height + j) shl 2) or d
        val endedBy = HashMap<Int, Port>()
        val startedBy = HashMap<Int, Port>()
        val open = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        starts.forEach { port ->
            val i = indexOf(gx, port.stub.first)
            val j = indexOf(gy, port.stub.second)
            if (inside(i, j)) return@forEach
            val k = key(i, j, port.side.ordinal)
            if (port.cost < best[k]) {
                best[k] = port.cost
                startedBy[k] = port
                open += (port.cost + guess(i, j)) to k
            }
        }
        var sinkCost = Double.MAX_VALUE
        var sinkFrom = 0
        var steps = 0
        while (open.isNotEmpty()) {
            val (f, k) = open.poll()
            if (f >= sinkCost) break
            if (++steps > MOST_STEPS) break
            val g = best[k]
            val d = k and 3
            val cell = k shr 2
            val i = cell / height
            val j = cell % height
            if (f - guess(i, j) > g + 1e-6) continue
            goalAt[cell]?.forEach { port ->
                // In through the side: the last step points into the box, which is the way out turned round.
                val inward = sides[(port.side.ordinal + 2) % 4]
                val turn = if (sides[d] == inward) 0.0 else if (sides[d] == port.side) return@forEach else BEND
                val total = g + turn + port.cost
                if (total < sinkCost) { sinkCost = total; sinkFrom = k; endedBy[k] = port }
            }
            for (nd in 0 until 4) {
                if (nd == (d + 2) % 4) continue
                val side = sides[nd]
                val ni = i + side.dx
                val nj = j + side.dy
                if (ni !in 0 until width || nj !in 0 until height) continue
                val cost = extra(i, j, side) ?: continue
                val total = g + abs(gx[ni] - gx[i]) + abs(gy[nj] - gy[j]) + cost + if (nd == d) 0.0 else BEND
                val nk = key(ni, nj, nd)
                if (total < best[nk]) {
                    best[nk] = total
                    came[nk] = k
                    open += (total + guess(ni, nj)) to nk
                }
            }
        }
        if (sinkCost == Double.MAX_VALUE) return null
        // Back from the end to the start, then the two rims around it.
        val path = mutableListOf<Pair<Double, Double>>()
        var at = sinkFrom
        var first = sinkFrom
        while (at >= 0) {
            val cell = at shr 2
            path += gx[cell / height] to gy[cell % height]
            first = at
            at = came[at]
        }
        path.reverse()
        val start = startedBy[first] ?: return null
        val end = endedBy[sinkFrom] ?: return null
        val whole = DiagramRoute.straightened(listOf(start.rim) + path + listOf(end.rim))
        val inner = whole.drop(1).dropLast(1).ifEmpty {
            listOf(((whole.first().first + whole.last().first) / 2) to ((whole.first().second + whole.last().second) / 2))
        }
        return inner.map { Math.round(it.first).toInt() to Math.round(it.second).toInt() }
    }

    /** How big a grid one line may search, and how many states; a drawing past either is left as it is. */
    private const val MOST_GRID = 2_000_000L
    private const val MOST_STEPS = 2_000_000
}
