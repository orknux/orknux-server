package io.mszymanski.orknux.server.diagram

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * What makes a drawn diagram hard to read, found by measuring it as it is
 * drawn: lines lying on top of each other, lines running through boxes they do
 * not join, and words that cannot be read - a label cut short, boxes on top of
 * each other, a line's label on a box or on another label.
 *
 * Measured off [DiagramRoute], which the editor and every export draw by, so a
 * problem said here is one a person would see. Every layer is measured as
 * though shown: a layer hidden today is shown tomorrow. A model cannot look at
 * a picture closely enough to find two lines sharing a run, and a person
 * finds it only after the diagram has been sent - which is why this exists.
 */
object DiagramCheck {

    /** What kind of problem, as the tool names it. */
    enum class Kind(val key: String, val error: Boolean, val said: String) {
        LINES_OVERLAP("lines-overlap", true, "two lines run along each other, so a reader cannot tell which goes where"),
        LINE_THROUGH_SHAPE("line-through-shape", true, "a line runs through a box it does not join"),
        SHAPES_OVERLAP("shapes-overlap", true, "two boxes lie on each other, covering each other's words"),
        LABEL_CUT("label-cut", true, "a label is longer than the room it is drawn in, and is cut short"),
        LABEL_COVERED("label-covered", true, "a line's label lies on a box or on another label"),
        LABEL_ON_LINE("label-on-line", false, "another line runs through a line's label"),
        LINES_CROSS("lines-cross", false, "two lines cross; fine now and then, hard to follow where there are many"),
    }

    data class Problem(
        val kind: Kind,
        val says: String,
        val edges: List<String> = emptyList(),
        val nodes: List<String> = emptyList(),
        val x: Int,
        val y: Int,
    )

    /** How near two runs must be to count as one on the other, and how long the shared part, in pixels. */
    const val TOUCHING = 3.0
    const val SHARED = 6.0

    /** How far into a box a line must reach to be through it, so one grazing its rim is not. */
    const val INSIDE = 3.0

    private val svg = DiagramSvg()

    /** Every problem on [drawing], errors first. */
    fun of(drawing: DiagramDrawing): List<Problem> {
        val byId = drawing.nodes.associateBy { it.id }
        val parents = drawing.nodes.mapNotNull { it.parent }.toSet()
        val solid = drawing.nodes.filter { it.kind != DiagramNodeKind.GROUP && it.id !in parents }
        val lines = drawing.edges.mapNotNull { edge ->
            val from = byId[edge.from] ?: return@mapNotNull null
            val to = byId[edge.to] ?: return@mapNotNull null
            if (edge.from == edge.to) return@mapNotNull null
            Drawn(edge, DiagramRoute.of(edge, from, to))
        }
        val found = mutableListOf<Problem>()

        // Lines on each other, and lines crossing.
        for (i in lines.indices) for (j in i + 1 until lines.size) {
            val a = lines[i]
            val b = lines[j]
            val shared = shared(a.route.trace, b.route.trace)
            if (shared != null) {
                found += Problem(
                    Kind.LINES_OVERLAP,
                    "${a.said()} and ${b.said()} run along each other for ${shared.third.toInt()} pixels",
                    edges = listOf(a.edge.id, b.edge.id), x = shared.first.toInt(), y = shared.second.toInt(),
                )
            }
            crossings(a.route.trace, b.route.trace).forEach { (x, y) ->
                found += Problem(Kind.LINES_CROSS, "${a.said()} crosses ${b.said()}", edges = listOf(a.edge.id, b.edge.id), x = x.toInt(), y = y.toInt())
            }
        }

        // Lines through boxes they do not join.
        lines.forEach { line ->
            solid.filter { it.id != line.edge.from && it.id != line.edge.to }.forEach { node ->
                through(line.route.trace, box(node, INSIDE))?.let { (x, y) ->
                    found += Problem(
                        Kind.LINE_THROUGH_SHAPE,
                        "${line.said()} runs through ${said(node)}",
                        edges = listOf(line.edge.id), nodes = listOf(node.id), x = x.toInt(), y = y.toInt(),
                    )
                }
            }
        }

        // Boxes on boxes: a group holds its own, which is not this.
        for (i in solid.indices) for (j in i + 1 until solid.size) {
            val a = solid[i]
            val b = solid[j]
            val l = max(a.x, b.x)
            val t = max(a.y, b.y)
            val r = min(a.x + a.w, b.x + b.w)
            val bo = min(a.y + a.h, b.y + b.h)
            if (r - l > INSIDE && bo - t > INSIDE) {
                found += Problem(Kind.SHAPES_OVERLAP, "${said(a)} and ${said(b)} lie on each other", nodes = listOf(a.id, b.id), x = (l + r) / 2, y = (t + bo) / 2)
            }
        }

        // Words cut short.
        drawing.nodes.filter { svg.cut(it) }.forEach { node ->
            found += Problem(
                Kind.LABEL_CUT,
                "${said(node)} is cut short in its ${node.w}×${node.h} box: make the box bigger, or the label shorter and the rest notes",
                nodes = listOf(node.id), x = node.x + node.w / 2, y = node.y + node.h / 2,
            )
        }
        lines.filter { it.edge.label != null && svg.cut(it.edge.label) }.forEach { line ->
            found += Problem(
                Kind.LABEL_CUT,
                "the label of ${line.said()} is cut short: a line's label is one line of about ${DiagramSvg.EDGE_LABEL_WIDTH / 7} characters",
                edges = listOf(line.edge.id), x = line.route.labelX.toInt(), y = line.route.labelY.toInt(),
            )
        }

        // A line's label on a box, on another label, or with another line through it.
        val labelled = lines.filter { !it.edge.label.isNullOrBlank() }
        labelled.forEach { line ->
            val mine = labelBox(line)
            solid.firstOrNull { node -> overlaps(mine, box(node, 0.0)) }?.let { node ->
                found += Problem(
                    Kind.LABEL_COVERED,
                    "the label of ${line.said()} lies on ${said(node)}",
                    edges = listOf(line.edge.id), nodes = listOf(node.id), x = line.route.labelX.toInt(), y = line.route.labelY.toInt(),
                )
            }
            lines.filter { it !== line }.firstOrNull { other -> through(other.route.trace, mine) != null }?.let { other ->
                found += Problem(
                    Kind.LABEL_ON_LINE,
                    "${other.said()} runs through the label of ${line.said()}",
                    edges = listOf(line.edge.id, other.edge.id), x = line.route.labelX.toInt(), y = line.route.labelY.toInt(),
                )
            }
        }
        for (i in labelled.indices) for (j in i + 1 until labelled.size) {
            if (overlaps(labelBox(labelled[i]), labelBox(labelled[j]))) {
                found += Problem(
                    Kind.LABEL_COVERED,
                    "the labels of ${labelled[i].said()} and ${labelled[j].said()} lie on each other",
                    edges = listOf(labelled[i].edge.id, labelled[j].edge.id),
                    x = labelled[i].route.labelX.toInt(), y = labelled[i].route.labelY.toInt(),
                )
            }
        }
        return found.sortedBy { if (it.kind.error) 0 else 1 }
    }

    /** One line and the way it is drawn. */
    private class Drawn(val edge: DiagramLink, val route: DiagramRoute.Route) {
        fun said(): String = "line ${edge.id} (${edge.from} → ${edge.to}${edge.label?.let { ", \"$it\"" } ?: ""})"
    }

    private fun said(node: DiagramShape): String = "box ${node.id}" + (node.label.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() }?.let { " \"$it\"" } ?: "")

    /** A box as left, top, right, bottom, drawn in by [inset] on every side. */
    fun box(node: DiagramShape, inset: Double): DoubleArray =
        doubleArrayOf(node.x + inset, node.y + inset, node.x + node.w - inset, node.y + node.h - inset)

    private fun labelBox(line: Drawn): DoubleArray {
        val half = DiagramSvg.labelHalfWidth(line.edge.label.orEmpty())
        return doubleArrayOf(line.route.labelX - half, line.route.labelY - 9, line.route.labelX + half, line.route.labelY - 9 + DiagramSvg.LABEL_HEIGHT)
    }

    fun overlaps(a: DoubleArray, b: DoubleArray): Boolean =
        min(a[2], b[2]) - max(a[0], b[0]) > 0.5 && min(a[3], b[3]) - max(a[1], b[1]) > 0.5

    /**
     * Where two lines run along each other the longest, and for how long: two
     * runs pointing the same way, within [TOUCHING] of one line, sharing more
     * than [SHARED] of their length. Null where they do not.
     */
    fun shared(a: List<Pair<Double, Double>>, b: List<Pair<Double, Double>>): Triple<Double, Double, Double>? {
        var best: Triple<Double, Double, Double>? = null
        for (i in 1 until a.size) for (j in 1 until b.size) {
            val (ax, ay) = a[i - 1]
            val (bx, by) = a[i]
            val length = hypot(bx - ax, by - ay)
            if (length < 0.5) continue
            val ux = (bx - ax) / length
            val uy = (by - ay) / length
            val (cx, cy) = b[j - 1]
            val (dx, dy) = b[j]
            val other = hypot(dx - cx, dy - cy)
            if (other < 0.5) continue
            // Pointing the same way, or opposite ways, along one line.
            if (abs(ux * (dy - cy) / other - uy * (dx - cx) / other) > 0.02) continue
            if (abs(ux * (cy - ay) - uy * (cx - ax)) > TOUCHING) continue
            val t1 = (cx - ax) * ux + (cy - ay) * uy
            val t2 = (dx - ax) * ux + (dy - ay) * uy
            val from = max(0.0, min(t1, t2))
            val to = min(length, max(t1, t2))
            val shared = to - from
            if (shared > SHARED && (best == null || shared > best.third)) {
                val middle = (from + to) / 2
                best = Triple(ax + ux * middle, ay + uy * middle, shared)
            }
        }
        return best
    }

    /** Where two lines cross, each point once; not where they only touch at an end. */
    fun crossings(a: List<Pair<Double, Double>>, b: List<Pair<Double, Double>>): List<Pair<Double, Double>> = buildList {
        for (i in 1 until a.size) for (j in 1 until b.size) {
            val (ax, ay) = a[i - 1]
            val (bx, by) = a[i]
            val (cx, cy) = b[j - 1]
            val (dx, dy) = b[j]
            val rx = bx - ax
            val ry = by - ay
            val sx = dx - cx
            val sy = dy - cy
            val den = rx * sy - ry * sx
            if (abs(den) < 1e-9) continue
            val t = ((cx - ax) * sy - (cy - ay) * sx) / den
            val u = ((cx - ax) * ry - (cy - ay) * rx) / den
            if (t <= 0.01 || t >= 0.99 || u <= 0.01 || u >= 0.99) continue
            val point = (ax + t * rx) to (ay + t * ry)
            if (none { abs(it.first - point.first) < 1 && abs(it.second - point.second) < 1 }) add(point)
        }
    }

    /** Where a line first goes inside [box] (left, top, right, bottom); null where it never does. */
    fun through(line: List<Pair<Double, Double>>, box: DoubleArray): Pair<Double, Double>? {
        if (box[2] <= box[0] || box[3] <= box[1]) return null
        for (i in 1 until line.size) {
            val (ax, ay) = line[i - 1]
            val (bx, by) = line[i]
            // Liang-Barsky: the part of the run inside the box, as fractions of it.
            var low = 0.0
            var high = 1.0
            val dx = bx - ax
            val dy = by - ay
            val tests = listOf(-dx to ax - box[0], dx to box[2] - ax, -dy to ay - box[1], dy to box[3] - ay)
            var outside = false
            for ((p, q) in tests) {
                if (abs(p) < 1e-12) {
                    if (q <= 0) { outside = true; break }
                } else {
                    val r = q / p
                    if (p < 0) low = max(low, r) else high = min(high, r)
                }
            }
            if (!outside && high - low > 1e-6) {
                val middle = (low + high) / 2
                return (ax + dx * middle) to (ay + dy * middle)
            }
        }
        return null
    }
}
