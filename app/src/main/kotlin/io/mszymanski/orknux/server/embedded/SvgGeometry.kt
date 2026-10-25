package io.mszymanski.orknux.server.embedded

import org.apache.batik.parser.AWTPathProducer
import org.apache.batik.parser.AWTPolygonProducer
import org.apache.batik.parser.AWTPolylineProducer
import org.apache.batik.parser.AWTTransformProducer
import org.apache.batik.parser.DefaultLengthHandler
import org.apache.batik.parser.LengthParser
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.awt.BasicStroke
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.PathIterator
import java.awt.geom.Rectangle2D
import java.io.StringReader
import java.util.IdentityHashMap
import kotlin.math.hypot
import kotlin.math.max

/**
 * Where the shapes of an SVG are, once every transform above them is applied.
 * What `svg_measure` answers from, and what XPath cannot say: a pad's position
 * is its `d` *and* every `transform` of every group it sits in.
 *
 * The parsing is Batik's - path data, transform lists, points and lengths go
 * through `batik-parser`'s producers into `java.awt.geom` shapes - and the
 * bounds are AWT's, which since Java 19 are tight around curves. What is
 * written here is the walk: the document's tree, each element's transform
 * composed with its parents', and the paint it inherits, which decides whether
 * a stroke widens what is drawn.
 *
 * Not covered, and counted as skipped rather than guessed: `<use>`, text, and
 * lengths in `%`, `em` or `ex`, which need a layout this does not do.
 */
class SvgGeometry(private val document: Document) {

    /** One measured element: where it is in the document's order, and its box in user units. */
    data class Measured(
        val element: Element,
        val tag: String,
        val id: String?,
        val box: Rectangle2D,
        /** "fill", "stroke", "fill+stroke" or "none": what of it is painted. */
        val paint: String,
    ) {
        val centreX get() = box.centerX
        val centreY get() = box.centerY
    }

    /** What was measured, and what was not with the reason. */
    data class Measurement(val shapes: List<Measured>, val skipped: Map<String, Int>)

    private val transforms = IdentityHashMap<Element, AffineTransform>()

    /** Every drawable element outside `<defs>` and the other containers that are not drawn where they stand. */
    fun drawable(): List<Element> {
        val found = mutableListOf<Element>()
        fun walk(node: Node) {
            var child = node.firstChild
            while (child != null) {
                if (child is Element) {
                    val name = local(child)
                    if (name in DRAWABLE) found += child
                    if (name !in NOT_DRAWN_HERE) walk(child)
                }
                child = child.nextSibling
            }
        }
        document.documentElement?.let { if (local(it) in DRAWABLE) found += it; walk(it) }
        return found
    }

    /** The boxes of [elements], widened by their stroke where [stroke] and they have one. */
    fun measure(elements: List<Element>, stroke: Boolean): Measurement {
        val skipped = linkedMapOf<String, Int>()
        fun skip(why: String) { skipped[why] = (skipped[why] ?: 0) + 1 }
        val shapes = elements.mapNotNull { element ->
            val tag = local(element)
            if (tag !in DRAWABLE) {
                skip("$tag is not a shape this measures")
                return@mapNotNull null
            }
            val shape = try {
                shapeOf(element, tag)
            } catch (unreadable: Exception) {
                skip("$tag whose geometry could not be read")
                return@mapNotNull null
            } ?: run {
                skip("$tag with a length in %, em or ex, or no geometry")
                return@mapNotNull null
            }
            val filled = paint(element, "fill", "black") != "none"
            val stroked = paint(element, "stroke", "none") != "none"
            val width = if (stroked) lengthOf(paint(element, "stroke-width", "1"))?.takeIf { it > 0 } else null
            val painted = if (stroke && width != null) {
                BasicStroke(width.toFloat(), capOf(element), joinOf(element)).createStrokedShape(shape)
            } else {
                shape
            }
            val box = ctm(element).createTransformedShape(painted).bounds2D
            val area = if (stroke && width != null) box.createUnion(ctm(element).createTransformedShape(shape).bounds2D) else box
            Measured(
                element, tag, element.getAttribute("id").ifEmpty { null }, area,
                when {
                    filled && stroked -> "fill+stroke"
                    filled -> "fill"
                    stroked -> "stroke"
                    else -> "none"
                },
            )
        }
        return Measurement(shapes, skipped)
    }

    /** The element's own geometry in its own coordinates, or null where it has none this can read. */
    private fun shapeOf(element: Element, tag: String): Shape? = when (tag) {
        "path" -> element.getAttribute("d").takeIf { it.isNotBlank() }
            ?.let { AWTPathProducer.createShape(StringReader(it), PathIterator.WIND_NON_ZERO) }
        "rect" -> {
            val w = attribute(element, "width") ?: return null
            val h = attribute(element, "height") ?: return null
            Rectangle2D.Double(attribute(element, "x") ?: 0.0, attribute(element, "y") ?: 0.0, w, h)
        }
        "circle" -> {
            val r = attribute(element, "r") ?: return null
            val cx = attribute(element, "cx") ?: 0.0
            val cy = attribute(element, "cy") ?: 0.0
            Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r)
        }
        "ellipse" -> {
            val rx = attribute(element, "rx") ?: return null
            val ry = attribute(element, "ry") ?: return null
            val cx = attribute(element, "cx") ?: 0.0
            val cy = attribute(element, "cy") ?: 0.0
            Ellipse2D.Double(cx - rx, cy - ry, 2 * rx, 2 * ry)
        }
        "line" -> Line2D.Double(
            attribute(element, "x1") ?: 0.0, attribute(element, "y1") ?: 0.0,
            attribute(element, "x2") ?: 0.0, attribute(element, "y2") ?: 0.0,
        )
        "polyline" -> element.getAttribute("points").takeIf { it.isNotBlank() }
            ?.let { AWTPolylineProducer.createShape(StringReader(it), PathIterator.WIND_NON_ZERO) }
        "polygon" -> element.getAttribute("points").takeIf { it.isNotBlank() }
            ?.let { AWTPolygonProducer.createShape(StringReader(it), PathIterator.WIND_NON_ZERO) }
        else -> null
    }

    /** Everything from the element's own coordinates to the document's user units: its transform after its parents'. */
    fun ctm(element: Element): AffineTransform = transforms.getOrPut(element) {
        val parent = element.parentNode as? Element
        val above = if (parent == null) AffineTransform() else AffineTransform(ctm(parent))
        element.getAttribute("transform").takeIf { it.isNotBlank() }?.let { said ->
            above.concatenate(AWTTransformProducer.createAffineTransform(said))
        }
        above
    }

    /** A paint property as the element is drawn with it: its style, then its attribute, then its parent's. */
    private fun paint(element: Element, property: String, initial: String): String {
        var at: Element? = element
        while (at != null) {
            declared(at, property)?.let { return it }
            at = at.parentNode as? Element
        }
        return initial
    }

    private fun declared(element: Element, property: String): String? {
        val style = element.getAttribute("style")
        if (style.isNotBlank()) {
            style.split(';').forEach { declaration ->
                val name = declaration.substringBefore(':', "").trim()
                if (name.equals(property, ignoreCase = true)) {
                    return declaration.substringAfter(':').trim().removeSuffix("!important").trim().lowercase()
                }
            }
        }
        return element.getAttribute(property).trim().lowercase().ifEmpty { null }
    }

    private fun capOf(element: Element): Int = when (paint(element, "stroke-linecap", "butt")) {
        "round" -> BasicStroke.CAP_ROUND
        "square" -> BasicStroke.CAP_SQUARE
        else -> BasicStroke.CAP_BUTT
    }

    private fun joinOf(element: Element): Int = when (paint(element, "stroke-linejoin", "miter")) {
        "round" -> BasicStroke.JOIN_ROUND
        "bevel" -> BasicStroke.JOIN_BEVEL
        else -> BasicStroke.JOIN_MITER
    }

    private fun attribute(element: Element, name: String): Double? =
        element.getAttribute(name).takeIf { it.isNotBlank() }?.let { lengthOf(it) }

    /**
     * How many millimetres one user unit is, and what said so - the root's
     * width or height in an absolute unit, over its viewBox - or null where the
     * document does not say.
     */
    fun scale(): Scale? {
        val root = document.documentElement ?: return null
        val width = Length.of(root.getAttribute("width"))
        val height = Length.of(root.getAttribute("height"))
        val box = root.getAttribute("viewBox").trim().split(Regex("[\\s,]+")).mapNotNull { it.toDoubleOrNull() }
            .takeIf { it.size == 4 && it[2] > 0 && it[3] > 0 }
        val mmWide = width?.millimetres()
        val mmHigh = height?.millimetres()
        if (mmWide == null && mmHigh == null) return null
        if (box == null) {
            // No viewBox: a user unit is a CSS pixel.
            return Scale(MM_PER_PX, MM_PER_PX, "no viewBox, so a user unit is a CSS pixel, ${fmt(MM_PER_PX)} mm")
        }
        val x = mmWide?.let { it / box[2] }
        val y = mmHigh?.let { it / box[3] }
        return Scale(
            x ?: y!!, y ?: x!!,
            "width=\"${root.getAttribute("width")}\" height=\"${root.getAttribute("height")}\" over viewBox=\"${root.getAttribute("viewBox").trim()}\"",
        )
    }

    data class Scale(val mmPerUnitX: Double, val mmPerUnitY: Double, val from: String) {
        val uniform get() = Math.abs(mmPerUnitX - mmPerUnitY) <= 1e-9 * max(mmPerUnitX, mmPerUnitY)
    }

    /** A length as Batik reads it: its number, and the unit it was written in. */
    data class Length(val value: Double, val unit: String) {
        /** In millimetres, where the unit is an absolute one. */
        fun millimetres(): Double? = when (unit) {
            "mm" -> value
            "cm" -> value * 10
            "in" -> value * 25.4
            "pt" -> value * 25.4 / 72
            "pc" -> value * 25.4 / 6
            else -> null
        }

        /** In user units - CSS pixels - where the unit is not relative to a layout. */
        fun userUnits(): Double? = when (unit) {
            "", "px" -> value
            else -> millimetres()?.let { it / MM_PER_PX }
        }

        companion object {
            fun of(said: String?): Length? {
                if (said.isNullOrBlank()) return null
                var value = 0f
                var unit = ""
                val handler = object : DefaultLengthHandler() {
                    override fun lengthValue(v: Float) { value = v }
                    override fun em() { unit = "em" }
                    override fun ex() { unit = "ex" }
                    override fun `in`() { unit = "in" }
                    override fun cm() { unit = "cm" }
                    override fun mm() { unit = "mm" }
                    override fun pc() { unit = "pc" }
                    override fun pt() { unit = "pt" }
                    override fun px() { unit = "px" }
                    override fun percentage() { unit = "%" }
                }
                return runCatching {
                    LengthParser().apply { lengthHandler = handler }.parse(said.trim())
                    /*
                     * The unit as Batik read it, and the number again at full
                     * width: Batik's is a float, and 104.1146mm over a viewBox of
                     * 104.1146 came out a user unit of 1.0000000109 mm.
                     */
                    val number = said.trim().removeSuffix(unit).trim().toDoubleOrNull() ?: value.toDouble()
                    Length(number, unit)
                }.getOrNull()
            }
        }
    }

    companion object {
        /** What a shape is drawn by, by its element's name. */
        val DRAWABLE = setOf("path", "rect", "circle", "ellipse", "line", "polyline", "polygon")

        /** Containers whose children are drawn somewhere else, or not at all. */
        private val NOT_DRAWN_HERE = setOf("defs", "clipPath", "mask", "symbol", "pattern", "marker", "linearGradient", "radialGradient", "filter")

        const val MM_PER_PX = 25.4 / 96

        fun local(element: Element): String = element.localName ?: element.nodeName.substringAfter(':')

        /** A length in user units, or null where it is relative to a layout (%, em, ex). */
        fun lengthOf(said: String): Double? = Length.of(said)?.userUnits()

        fun fmt(value: Double): String = java.math.BigDecimal(value).setScale(6, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

        /* ------------------------------------------------------------ analyses */

        /** Edge to edge between two boxes; 0 where they touch or overlap. */
        fun gap(a: Rectangle2D, b: Rectangle2D): Double {
            val dx = max(0.0, max(b.minX - a.maxX, a.minX - b.maxX))
            val dy = max(0.0, max(b.minY - a.maxY, a.minY - b.maxY))
            return hypot(dx, dy)
        }

        /**
         * Each box's distance, centre to centre, to its nearest other at a
         * different place; empty for fewer than two, infinite for one with no
         * other place to be near. A shape drawn twice on the same spot - a
         * KiCad pad and its flash - is [coincident], not a pitch of 0.
         */
        fun nearest(boxes: List<Rectangle2D>): DoubleArray {
            if (boxes.size < 2) return DoubleArray(0)
            val order = boxes.indices.sortedBy { boxes[it].centerX }
            val found = DoubleArray(boxes.size)
            for (p in order.indices) {
                val a = boxes[order[p]]
                var best = Double.POSITIVE_INFINITY
                // Outwards from it along x, in both directions, until x alone is further than the best so far.
                for (step in listOf(1, -1)) {
                    var q = p + step
                    while (q in order.indices) {
                        val b = boxes[order[q]]
                        val dx = Math.abs(b.centerX - a.centerX)
                        if (dx >= best) break
                        val d = hypot(dx, b.centerY - a.centerY)
                        if (d > SAME_PLACE) best = minOf(best, d)
                        q += step
                    }
                }
                found[order[p]] = best
            }
            return found
        }

        /** How many boxes share their centre with another. */
        fun coincident(boxes: List<Rectangle2D>): Int =
            boxes.groupingBy { Math.round(it.centerX / SAME_PLACE) to Math.round(it.centerY / SAME_PLACE) }.eachCount()
                .values.filter { it > 1 }.sum()

        /** How close two centres are to be the same place, in user units: a millionth. */
        private const val SAME_PLACE = 1e-6

        /** Two boxes, by their index, and the gap between their edges. */
        data class Gap(val a: Int, val b: Int, val gap: Double)

        /**
         * The [most] closest pairs whose boxes do not touch, the smallest gap
         * first, and how many pairs do touch or overlap.
         *
         * Swept left to right by where each box starts: once the next box
         * starts further right of this one's end than the widest gap kept, no
         * later one can be nearer. A touching pair starts before that end, so
         * every one of them is reached and counted.
         */
        fun closest(boxes: List<Rectangle2D>, most: Int): kotlin.Pair<List<Gap>, Int> {
            val order = boxes.indices.sortedBy { boxes[it].minX }
            val kept = java.util.PriorityQueue<Gap>(compareByDescending { it.gap })
            var touching = 0
            for (i in order.indices) {
                val a = boxes[order[i]]
                for (j in i + 1 until order.size) {
                    val b = boxes[order[j]]
                    val bound = if (kept.size >= most) kept.peek().gap else Double.POSITIVE_INFINITY
                    if (b.minX - a.maxX > bound) break
                    val g = gap(a, b)
                    when {
                        g <= 0.0 -> touching++
                        kept.size < most -> kept += Gap(order[i], order[j], g)
                        g < kept.peek().gap -> {
                            kept.poll()
                            kept += Gap(order[i], order[j], g)
                        }
                    }
                }
            }
            return kept.sortedBy { it.gap } to touching
        }

        /** Groups of boxes whose edges are within [within] of one another, chained; each group's indices in order. */
        fun groups(boxes: List<Rectangle2D>, within: Double): List<List<Int>> {
            val parent = IntArray(boxes.size) { it }
            fun root(i: Int): Int {
                var at = i
                while (parent[at] != at) {
                    parent[at] = parent[parent[at]]
                    at = parent[at]
                }
                return at
            }
            val order = boxes.indices.sortedBy { boxes[it].minX }
            for (i in order.indices) {
                val a = boxes[order[i]]
                for (j in i + 1 until order.size) {
                    val b = boxes[order[j]]
                    if (b.minX - a.maxX > within) break
                    if (gap(a, b) <= within) {
                        val ra = root(order[i])
                        val rb = root(order[j])
                        if (ra != rb) parent[ra] = rb
                    }
                }
            }
            return boxes.indices.groupBy { root(it) }.values.map { it.sorted() }.sortedBy { it.first() }
        }

        /** The median of some numbers; NaN for none. */
        fun median(values: DoubleArray): Double {
            if (values.isEmpty()) return Double.NaN
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
        }
    }
}
