package io.mszymanski.orknux.server.diagram

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * How the shapes past the first six are drawn: the flowchart and UML ones,
 * each an outline in a box a hundred units on a side, stretched to the node.
 *
 * The interface draws the same outlines from its own copy
 * (`orknux-ui/src/pages/explore/DiagramShapes.ts`), so the two are changed
 * together - a database that is a cylinder in the editor and a box in the
 * picture a chat shows is two diagrams.
 *
 * A path here holds only absolute `M`, `L`, `C` and `Z`, every number one half
 * of an x,y pair, because that is what lets [scaled] move it to pixels by
 * reading the numbers two at a time - an arc's flags and radii would not
 * survive that, which is why the curves are cubic.
 */
object DiagramOutline {

    /** One stroke of an outline: a path, or an oval at a centre. [filled] takes the node's fill. */
    sealed interface Piece { val filled: Boolean }
    data class Path(val d: String, override val filled: Boolean = true) : Piece
    data class Oval(val cx: Double, val cy: Double, val rx: Double, val ry: Double, override val filled: Boolean = true) : Piece

    /** Where the words go, as fractions of a hundred of the node's box. */
    data class Inside(val left: Double, val top: Double, val right: Double, val bottom: Double)

    /**
     * A shape's outline. [rim] is the polygon a line ends at, in the same
     * units; null is the node's own rectangle. An [icon] keeps its proportions
     * in the box above the label rather than stretching, and its label sits
     * beneath it - an actor squashed to a node's width is no longer a person.
     */
    data class Outline(val pieces: List<Piece>, val inside: Inside, val rim: List<Pair<Double, Double>>? = null, val icon: Boolean = false)

    /** How tall the band under an icon is, for its label, in pixels. */
    const val ICON_LABEL = 22

    private val FULL = Inside(4.0, 4.0, 96.0, 96.0)
    private val BOX = listOf(0.0 to 0.0, 100.0 to 0.0, 100.0 to 100.0, 0.0 to 100.0)

    private fun poly(vararg points: Pair<Double, Double>): Path =
        Path("M " + points.joinToString(" L ") { "${it.first} ${it.second}" } + " Z")

    val OUTLINES: Map<DiagramNodeKind, Outline> = mapOf(
        DiagramNodeKind.DATABASE to Outline(
            listOf(
                Path("M 0 7.5 C 0 -2.5 100 -2.5 100 7.5 L 100 92.5 C 100 102.5 0 102.5 0 92.5 Z"),
                Path("M 0 7.5 C 0 17.5 100 17.5 100 7.5", filled = false),
            ),
            Inside(4.0, 18.0, 96.0, 94.0),
            listOf(0.0 to 7.5, 50.0 to 0.0, 100.0 to 7.5, 100.0 to 92.5, 50.0 to 100.0, 0.0 to 92.5),
        ),
        DiagramNodeKind.DOCUMENT to Outline(
            listOf(Path("M 0 0 L 100 0 L 100 85 C 70 70 35 105 0 88 Z")),
            Inside(4.0, 4.0, 96.0, 80.0),
            listOf(0.0 to 0.0, 100.0 to 0.0, 100.0 to 85.0, 50.0 to 90.0, 0.0 to 88.0),
        ),
        DiagramNodeKind.HEXAGON to hexagon(SLANT),
        DiagramNodeKind.PARALLELOGRAM to parallelogram(SLANT),
        DiagramNodeKind.TRAPEZOID to trapezoid(SLANT),
        DiagramNodeKind.TRIANGLE to Outline(
            listOf(poly(50.0 to 0.0, 100.0 to 100.0, 0.0 to 100.0)),
            Inside(18.0, 45.0, 82.0, 96.0),
            listOf(50.0 to 0.0, 100.0 to 100.0, 0.0 to 100.0),
        ),
        DiagramNodeKind.CLOUD to Outline(
            listOf(
                Path(
                    "M 25 85 C 5 85 0 60 15 52 C 5 35 25 15 42 25 C 50 5 80 5 85 28 " +
                        "C 100 30 105 55 90 65 C 100 80 85 95 70 88 C 60 100 35 100 25 85 Z",
                ),
            ),
            Inside(18.0, 30.0, 82.0, 84.0),
            listOf(
                25.0 to 85.0, 6.0 to 70.0, 15.0 to 52.0, 18.0 to 30.0, 42.0 to 25.0, 62.0 to 9.0,
                85.0 to 28.0, 98.0 to 45.0, 90.0 to 65.0, 90.0 to 84.0, 70.0 to 88.0, 47.0 to 96.0,
            ),
        ),
        DiagramNodeKind.COMPONENT to component(TABS),
        DiagramNodeKind.PACKAGE to packaged(TAB),
        DiagramNodeKind.DEVICE to device(DEPTH),
        DiagramNodeKind.CLASS to Outline(listOf(poly(*BOX.toTypedArray())), FULL),
        DiagramNodeKind.ACTOR to Outline(
            listOf(
                Oval(50.0, 14.0, 11.0, 11.0),
                Path("M 50 25 L 50 62 M 22 38 L 78 38 M 50 62 L 26 96 M 50 62 L 74 96", filled = false),
            ),
            FULL, icon = true,
        ),
        DiagramNodeKind.INTERFACE to Outline(listOf(Oval(50.0, 50.0, 30.0, 30.0)), FULL, icon = true),
        DiagramNodeKind.BOUNDARY to Outline(
            listOf(Oval(60.0, 50.0, 32.0, 32.0), Path("M 8 18 L 8 82 M 8 50 L 28 50", filled = false)),
            FULL, icon = true,
        ),
        DiagramNodeKind.CONTROL to Outline(
            listOf(Oval(50.0, 55.0, 35.0, 35.0), Path("M 58 11 L 47 20 L 58 29", filled = false)),
            FULL, icon = true,
        ),
        DiagramNodeKind.ENTITY to Outline(
            listOf(Oval(50.0, 45.0, 35.0, 35.0), Path("M 15 88 L 85 88", filled = false)),
            FULL, icon = true,
        ),
    )

    /*
     * The features that run across a shape - a hexagon's and a parallelogram's
     * slant, a component's tabs, a package's tab, a device's depth - in the
     * hundred-unit box of a square node, and how many of the node's heights
     * each may be at most. Stretched with the width alone, a hexagon a
     * thousand pixels wide had slants of a hundred and fifty, and a wide
     * component tabs that were half of it; so each is the smaller of the two,
     * and a wide shape stays a box with small features at its sides.
     */
    private const val SLANT = 15.0
    private const val SLANT_HEIGHTS = 0.4
    private const val TABS = 20.0
    private const val TABS_HEIGHTS = 0.35
    private const val TAB = 40.0
    private const val TAB_HEIGHTS = 1.2
    private const val DEPTH = 12.0

    private fun hexagon(s: Double): Outline {
        val points = listOf(s to 0.0, 100 - s to 0.0, 100.0 to 50.0, 100 - s to 100.0, s to 100.0, 0.0 to 50.0)
        return Outline(listOf(poly(*points.toTypedArray())), Inside(s + 1, 4.0, 99 - s, 96.0), points)
    }

    private fun parallelogram(s: Double): Outline =
        slanted(listOf(s to 0.0, 100.0 to 0.0, 100 - s to 100.0, 0.0 to 100.0), s)

    private fun trapezoid(s: Double): Outline =
        slanted(listOf(0.0 to 0.0, 100.0 to 0.0, 100 - s to 100.0, s to 100.0), s)

    private fun slanted(points: List<Pair<Double, Double>>, s: Double): Outline =
        Outline(listOf(poly(*points.toTypedArray())), Inside(s + 2, 4.0, 98 - s, 96.0), points)

    private fun component(t: Double): Outline {
        val body = t / 2
        return Outline(
            listOf(
                poly(body to 0.0, 100.0 to 0.0, 100.0 to 100.0, body to 100.0),
                poly(0.0 to 20.0, t to 20.0, t to 35.0, 0.0 to 35.0),
                poly(0.0 to 55.0, t to 55.0, t to 70.0, 0.0 to 70.0),
            ),
            Inside(t + 2, 4.0, 96.0, 96.0),
            listOf(body to 0.0, 100.0 to 0.0, 100.0 to 100.0, body to 100.0),
        )
    }

    private fun packaged(tab: Double): Outline = Outline(
        listOf(
            poly(0.0 to 0.0, tab to 0.0, tab to 15.0, 0.0 to 15.0),
            poly(0.0 to 15.0, 100.0 to 15.0, 100.0 to 100.0, 0.0 to 100.0),
        ),
        Inside(4.0, 19.0, 96.0, 96.0),
        listOf(0.0 to 0.0, tab to 0.0, tab to 15.0, 100.0 to 15.0, 100.0 to 100.0, 0.0 to 100.0),
    )

    /** A box seen from a corner: [d] across, as deep as the 12 units it is down, in pixels. */
    private fun device(d: Double): Outline {
        val far = 100 - d
        return Outline(
            listOf(
                poly(0.0 to 12.0, far to 12.0, far to 100.0, 0.0 to 100.0),
                poly(0.0 to 12.0, d to 0.0, 100.0 to 0.0, far to 12.0),
                poly(far to 12.0, 100.0 to 0.0, 100.0 to 88.0, far to 100.0),
            ),
            Inside(4.0, 16.0, far - 4, 96.0),
            listOf(0.0 to 12.0, d to 0.0, 100.0 to 0.0, 100.0 to 88.0, far to 100.0, 0.0 to 100.0),
        )
    }

    /**
     * [kind]'s outline for a node [w] by [h] pixels: the square one, with the
     * features that run across it no wider than their share of the height.
     * Null for a kind drawn without one. The interface fits them the same way
     * (`outlineOf` in `DiagramShapes.tsx`).
     */
    fun of(kind: DiagramNodeKind, w: Int, h: Int): Outline? {
        val square = OUTLINES[kind] ?: return null
        val heights = h.toDouble() / maxOf(w, 1) * 100
        fun across(units: Double, most: Double) = min(units, most * heights)
        return when (kind) {
            DiagramNodeKind.HEXAGON -> hexagon(across(SLANT, SLANT_HEIGHTS))
            DiagramNodeKind.PARALLELOGRAM -> parallelogram(across(SLANT, SLANT_HEIGHTS))
            DiagramNodeKind.TRAPEZOID -> trapezoid(across(SLANT, SLANT_HEIGHTS))
            DiagramNodeKind.COMPONENT -> component(across(TABS, TABS_HEIGHTS))
            DiagramNodeKind.PACKAGE -> packaged(across(TAB, TAB_HEIGHTS))
            DiagramNodeKind.DEVICE -> device(across(DEPTH, DEPTH / 100))
            else -> square
        }
    }

    /** The box an outline is drawn in, in pixels: the whole node, or an icon's square above its label. */
    fun frame(node: DiagramShape, outline: Outline): DoubleArray {
        if (!outline.icon) return doubleArrayOf(node.x.toDouble(), node.y.toDouble(), node.w.toDouble(), node.h.toDouble())
        val side = maxOf(8.0, min(node.w.toDouble(), (node.h - ICON_LABEL).toDouble()))
        return doubleArrayOf(node.x + (node.w - side) / 2, node.y.toDouble(), side, side)
    }

    /** A path's numbers moved from the hundred-unit box to [frame]'s pixels, two at a time. */
    fun scaled(d: String, frame: DoubleArray): String {
        var x = true
        return d.split(' ').filter { it.isNotEmpty() }.joinToString(" ") { part ->
            val number = part.toDoubleOrNull() ?: return@joinToString part
            val pixel = if (x) frame[0] + number * frame[2] / 100 else frame[1] + number * frame[3] / 100
            x = !x
            "%.1f".format(java.util.Locale.ROOT, pixel)
        }
    }

    /**
     * Where a line from [node]'s centre towards (tx, ty) leaves it: the outline
     * a person sees, so an arrow at a database touches the cylinder rather than
     * the corner of the box around it. Diamonds and ellipses are found the same
     * way; everything else is its rectangle.
     */
    fun border(node: DiagramShape, tx: Double, ty: Double): Pair<Double, Double> {
        val cx = node.x + node.w / 2.0
        val cy = node.y + node.h / 2.0
        val dx = tx - cx
        val dy = ty - cy
        if (dx == 0.0 && dy == 0.0) return cx to cy
        if (node.kind == DiagramNodeKind.ELLIPSE) {
            val a = node.w / 2.0
            val b = node.h / 2.0
            val t = 1 / sqrt((dx * dx) / (a * a) + (dy * dy) / (b * b))
            return (cx + dx * t) to (cy + dy * t)
        }
        val rim = when (node.kind) {
            DiagramNodeKind.DIAMOND -> listOf(50.0 to 0.0, 100.0 to 50.0, 50.0 to 100.0, 0.0 to 50.0)
            else -> of(node.kind, node.w, node.h)?.takeUnless { it.icon }?.rim
        }
        if (rim != null) {
            val points = rim.map { (u, v) -> (node.x + u * node.w / 100) to (node.y + v * node.h / 100) }
            var nearest = Double.MAX_VALUE
            points.indices.forEach { at ->
                val (ax, ay) = points[at]
                val (bx, by) = points[(at + 1) % points.size]
                // Where cx + t·dx = ax + s·(bx-ax), cy + t·dy = ay + s·(by-ay), with t > 0 and s in 0..1.
                val ex = bx - ax
                val ey = by - ay
                val det = dx * -ey - dy * -ex
                if (abs(det) < 1e-9) return@forEach
                val px = ax - cx
                val py = ay - cy
                val t = (px * -ey - py * -ex) / det
                val s = (dx * py - dy * px) / det
                if (t > 0 && s >= -1e-9 && s <= 1 + 1e-9 && t < nearest) nearest = t
            }
            if (nearest != Double.MAX_VALUE) return (cx + dx * nearest) to (cy + dy * nearest)
        }
        val scale = minOf(
            if (dx != 0.0) (node.w / 2.0) / abs(dx) else Double.MAX_VALUE,
            if (dy != 0.0) (node.h / 2.0) / abs(dy) else Double.MAX_VALUE,
        )
        return (cx + dx * scale) to (cy + dy * scale)
    }

    /**
     * A class's label as its compartments: the first line is the name, and a
     * line of just `--` starts the next one - attributes, then operations.
     * Lines after the name with no `--` among them are one compartment.
     */
    fun compartments(label: String): List<List<String>> {
        val lines = label.replace("\r\n", "\n").split('\n')
        val name = listOf(lines.first())
        val rest = lines.drop(1)
        if (rest.isEmpty()) return listOf(name)
        val parts = mutableListOf(mutableListOf<String>())
        rest.forEach { line -> if (line.trim() == "--") parts += mutableListOf<String>() else parts.last() += line }
        if (parts.first().isEmpty() && rest.first().trim() == "--") parts.removeAt(0)
        return listOf(name) + parts
    }
}
