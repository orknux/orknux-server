package io.mszymanski.orknux.server.embedded

import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import org.springframework.stereotype.Component
import org.w3c.dom.Element
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.awt.geom.Rectangle2D
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * `svg_measure`: the boxes of an SVG's shapes, and the analyses an agent would
 * otherwise want Python for - grouping by proximity, distinct sizes, the
 * nearest-neighbour pitch, the closest gaps - answered as small tables of
 * numbers, with totals always given and every list capped.
 *
 * Chat 981 asked for exactly these about a KiCad copper layer: pad sizes,
 * pitch, footprints and minimum spacing. The geometry is [SvgGeometry]'s.
 */
@Component
class SvgMeasure(
    private val files: StoredFiles,
    private val tuning: Tuning,
    private val mapper: ObjectMapper,
) {

    fun run(asked: JsonNode, sessionId: Long?): String {
        val bytes = when (val file = xmlFile(files, tuning, text(asked, KEY), sessionId)) {
            is Loaded.Refused -> return refusal(file.reason)
            is Loaded.File -> file.bytes
        }
        val select = text(asked, SELECT)?.trim()?.ifEmpty { null }
        val (document, elements, blind) = try {
            if (select == null) {
                val document = HostileXml.parse(bytes)
                Triple(document, SvgGeometry(document).drawable(), false)
            } else {
                val queried = HostileXml.query(bytes, select)
                val nodes = (queried.answer as? HostileXml.Answer.Nodes)?.nodes
                    ?: return refusal("$SELECT has to choose elements, and that expression answers a value.")
                Triple(queried.document, nodes.filterIsInstance<Element>(), queried.namespacesIgnored)
            }
        } catch (refused: HostileXml.Refused) {
            return refusal(refused.message.orEmpty())
        }

        val geometry = SvgGeometry(document)
        val measured = geometry.measure(elements, stroke = !switchedOff(asked, STROKE))
        val skipped = LinkedHashMap(measured.skipped)
        val scale = geometry.scale()
        val inMm = XmlCapability.flag(asked, MM)
        if (inMm && scale == null) {
            return refusal("This SVG does not say how large a user unit is - its width and height carry no absolute unit - so there are no millimetres to answer in.")
        }
        if (inMm && !scale!!.uniform) {
            return refusal("This SVG's user unit is ${scale.mmPerUnitX} mm across and ${scale.mmPerUnitY} mm down, so one millimetre figure would be wrong; ask in user units.")
        }
        val factor = if (inMm) scale!!.mmPerUnitX else 1.0
        val decimals = XmlCapability.number(asked, PRECISION)?.coerceIn(0, 9) ?: DEFAULT_DECIMALS
        fun n(value: Double): Double = BigDecimal(value * factor).setScale(decimals, RoundingMode.HALF_UP).toDouble()
        /*
         * Shapes larger than max_size are left out: a copper pour is one
         * stroke:none path the size of the board, and every pad touches it,
         * so with it in, a whole layer is one group and every gap is 0.
         */
        val largest = number(asked, MAX_SIZE)?.takeIf { it > 0 }?.let { it / factor }
        val shapes = if (largest == null) measured.shapes else measured.shapes.filter { maxOf(it.box.width, it.box.height) <= largest }
        if (shapes.size < measured.shapes.size) skipped["larger than $MAX_SIZE"] = measured.shapes.size - shapes.size
        val boxes = shapes.map { it.box }

        val most = tuning[Tunable.XML_MATCHES_MOST]
        val offset = XmlCapability.number(asked, OFFSET)?.coerceAtLeast(0) ?: 0
        val limit = (XmlCapability.number(asked, LIMIT) ?: most).coerceIn(0, most)

        val said = linkedMapOf<String, Any?>()
        said[COUNT] = shapes.size
        if (skipped.isNotEmpty()) said["skipped"] = skipped
        if (blind) said["namespaces"] = "$SELECT matched with namespaces ignored"
        said["units"] = if (inMm) "mm" else "user units"
        said["scale"] = scale?.let {
            linkedMapOf("mmPerUnit" to if (it.uniform) it.mmPerUnitX else listOf(it.mmPerUnitX, it.mmPerUnitY), "from" to it.from)
        } ?: "not given: the root's width and height carry no absolute unit"
        if (boxes.isNotEmpty()) {
            val all = boxes.drop(1).fold(boxes.first().bounds2D) { acc, box -> acc.createUnion(box) }
            said["extent"] = box(all, ::n)
        }

        said["columns"] = listOf("n", "element", "id", "paint", "x", "y", "width", "height")
        val page = shapes.withIndex().drop(offset).take(limit)
        said["rows"] = page.map { (index, shape) ->
            listOf(index + 1, shape.tag, shape.id, shape.paint, n(shape.box.x), n(shape.box.y), n(shape.box.width), n(shape.box.height))
        }
        val after = offset + page.size
        if (limit > 0 && after < shapes.size) said["more"] = "${shapes.size - after} more rows; pass $OFFSET $after for the next ones, or $LIMIT 0 for none"

        val asks = statsAsked(asked)
        val nearest by lazy { SvgGeometry.nearest(boxes).filter { it.isFinite() }.toDoubleArray() }

        if (text(asked, GROUP_BY)?.trim()?.lowercase()?.let { it == PROXIMITY } == true) {
            val given = number(asked, GAP)
            val gapUnits = given?.let { it / factor }
                ?: (SvgGeometry.median(nearest) * tuning[Tunable.SVG_GROUP_GAP_PERCENT] / 100.0).takeIf { it.isFinite() }
                ?: 0.0
            val groups = SvgGeometry.groups(boxes, gapUnits)
            val rows = groups.withIndex().take(limit).map { (index, members) ->
                val extent = members.drop(1).fold(boxes[members.first()].bounds2D) { acc, i -> acc.createUnion(boxes[i]) }
                val pitch = SvgGeometry.median(SvgGeometry.nearest(members.map { boxes[it] }).filter { it.isFinite() }.toDoubleArray())
                listOf(
                    index + 1, members.size, n(extent.x), n(extent.y), n(extent.width), n(extent.height),
                    pitch.takeIf { it.isFinite() }?.let(::n), members.first() + 1,
                )
            }
            said["groups"] = linkedMapOf(
                "gap" to n(gapUnits),
                "gapFrom" to if (given != null) "given" else "${tuning[Tunable.SVG_GROUP_GAP_PERCENT]}% of the median nearest-neighbour pitch",
                COUNT to groups.size,
                "sizes" to groups.groupingBy { it.size }.eachCount().toSortedMap().map { (size, count) -> listOf(size, count) },
                "columns" to listOf("group", "elements", "x", "y", "width", "height", "pitch", "first n"),
                "rows" to rows,
            ).apply { if (groups.size > rows.size) put("more", "${groups.size - rows.size} more groups not listed") }
        }

        if (STAT_SIZES in asks) {
            val counted = shapes.groupingBy { n(it.box.width) to n(it.box.height) }.eachCount()
                .entries.sortedWith(compareByDescending<Map.Entry<kotlin.Pair<Double, Double>, Int>> { it.value }.thenBy { it.key.first }.thenBy { it.key.second })
            said[STAT_SIZES] = linkedMapOf(
                "distinct" to counted.size,
                "columns" to listOf("width", "height", "count"),
                "rows" to counted.take(limit.coerceAtLeast(1)).map { listOf(it.key.first, it.key.second, it.value) },
            )
        }

        if (STAT_PITCH in asks && nearest.isNotEmpty()) {
            val histogram = nearest.map(::n).groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            said[STAT_PITCH] = linkedMapOf(
                "min" to n(nearest.min()),
                "median" to n(SvgGeometry.median(nearest)),
                "max" to n(nearest.max()),
                "distinct" to histogram.size,
                "coincident" to SvgGeometry.coincident(boxes),
                "note" to "centre to centre to the nearest shape at another place; coincident counts shapes drawn on the same spot as another",
                "columns" to listOf("pitch", "count"),
                "rows" to histogram.take(limit.coerceAtLeast(1)).map { listOf(it.key, it.value) },
            )
        }

        if (STAT_SPACING in asks && boxes.size > 1) {
            val (pairs, touching) = SvgGeometry.closest(boxes, limit.coerceAtLeast(1))
            said[STAT_SPACING] = linkedMapOf(
                "minGap" to pairs.firstOrNull()?.let { n(it.gap) },
                "touchingPairs" to touching,
                "columns" to listOf("gap", "a", "b", "a id", "b id"),
                "rows" to pairs.map { listOf(n(it.gap), it.a + 1, it.b + 1, shapes[it.a].id, shapes[it.b].id) },
                "note" to "edge to edge between boxes; a pair that touches or overlaps is counted, not listed",
            )
        }
        return mapper.writeValueAsString(said)
    }

    private fun number(node: JsonNode, name: String): Double? = node.path(name).let {
        when {
            it.isNumber -> it.doubleValue()
            it.isString -> it.stringValue().trim().toDoubleOrNull()
            else -> null
        }
    }

    private fun box(box: Rectangle2D, n: (Double) -> Double) =
        linkedMapOf("x" to n(box.x), "y" to n(box.y), "width" to n(box.width), "height" to n(box.height))

    private fun statsAsked(asked: JsonNode): Set<String> {
        val node = asked.path(STATS)
        val said = when {
            node.isArray -> node.values().mapNotNull { if (it.isString) it.stringValue() else null }
            node.isString -> node.stringValue().split(',')
            node.isBoolean && node.booleanValue() -> listOf(ALL)
            else -> emptyList()
        }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        return if (ALL in said) setOf(STAT_SIZES, STAT_PITCH, STAT_SPACING) else said.toSet()
    }

    /** Whether a switch that is on unless said otherwise was said to be off. */
    private fun switchedOff(node: JsonNode, name: String): Boolean = node.path(name).let {
        (it.isBoolean && !it.booleanValue()) || (it.isString && it.stringValue().trim().equals("false", ignoreCase = true))
    }

    private fun text(node: JsonNode, name: String): String? = node.path(name).takeIf { it.isString }?.stringValue()

    private fun refusal(said: String): String = mapper.writeValueAsString(mapOf("error" to said))

    companion object {
        const val KEY = "key"
        const val SELECT = "select"
        const val STROKE = "stroke"
        const val MM = "mm"
        const val PRECISION = "precision"
        const val OFFSET = "offset"
        const val LIMIT = "limit"
        const val GROUP_BY = "group_by"
        const val PROXIMITY = "proximity"
        const val GAP = "gap"
        const val MAX_SIZE = "max_size"
        const val STATS = "stats"
        const val STAT_SIZES = "sizes"
        const val STAT_PITCH = "pitch"
        const val STAT_SPACING = "spacing"
        const val ALL = "all"
        const val COUNT = "count"

        private const val DEFAULT_DECIMALS = 4
    }
}
