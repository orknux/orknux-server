package io.mszymanski.orknux.server.design

import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * How an element looks, past where it is: its background, the colour of its
 * words, its border and its shadow. Each is an optional prop, so a drawing
 * made before there were any is drawn as it always was.
 *
 * A colour or a gradient is written into a stylesheet and an SVG by the
 * exports, and the drawing is untrusted, so nothing here is ever handed on as
 * it was given: each value is parsed against a small syntax and written out
 * again from what was parsed - a colour as lowercase hex, a gradient as an
 * angle and its stops - and what does not parse is refused. A `url(...)`, an
 * `expression(...)` or a stray `;` cannot survive the round trip, because the
 * round trip only knows how to write digits.
 */
object WireframeStyle {

    /** The background: a colour, or `linear-gradient(<angle>deg, <colour> <at>%, ...)`. */
    const val FILL = "fill"

    /** The colour of the element's words. */
    const val COLOR = "color"

    const val BORDER_WIDTH = "borderWidth"
    const val BORDER_STYLE = "borderStyle"
    const val BORDER_COLOR = "borderColor"

    /** How round the corners are, in pixels. */
    const val RADIUS = "radius"

    const val SHADOW = "shadow"

    /** Whether a checkbox is ticked, a radio button chosen or a switch on. */
    const val CHECKED = "checked"

    /** The props this object checks; any other prop is the element's own and is kept as it came. */
    val NAMES = setOf(FILL, COLOR, BORDER_WIDTH, BORDER_STYLE, BORDER_COLOR, RADIUS, SHADOW, CHECKED)

    /** Formats, not behaviours: the range a border and a corner are held to, and how many stops a gradient may have. */
    const val BORDER_MOST = 20
    const val RADIUS_MOST = 500
    const val STOPS_LEAST = 2
    const val STOPS_MOST = 8

    enum class BorderStyle(val key: String) { SOLID("solid"), DASHED("dashed"), DOTTED("dotted"), NONE("none") }

    /** The shadows there are, each as CSS draws it; an SVG draws the same offset, blur and opacity. */
    enum class Shadow(val key: String, val dy: Int, val blur: Int, val opacity: Double) {
        NONE("none", 0, 0, 0.0),
        SMALL("small", 1, 3, 0.2),
        MEDIUM("medium", 4, 10, 0.22),
        LARGE("large", 12, 28, 0.26),
        ;

        /** The CSS of it; nothing but numbers this enum holds. */
        fun css(): String = if (this == NONE) "none" else "0 ${dy}px ${blur}px rgba(0,0,0,$opacity)"
    }

    /** One stop of a gradient: a colour, and how far along it is, 0 to 100. */
    data class Stop(val colour: String, val at: Double)

    /** A background: one colour, or a linear gradient at an angle. */
    sealed interface Fill {
        data class Solid(val colour: String) : Fill
        data class Gradient(val angle: Double, val stops: List<Stop>) : Fill
    }

    /** What the props say about how an element looks, each null where nothing is said. */
    data class Look(
        val fill: Fill?,
        val color: String?,
        val borderWidth: Int?,
        val borderStyle: BorderStyle?,
        val borderColor: String?,
        val radius: Int?,
        val shadow: Shadow?,
        val checked: Boolean,
    )

    /**
     * What a model is told the style props are, read by the tools' descriptions
     * from here so they say what this object accepts.
     */
    val SAID: String =
        "Every element may also carry style props, each optional: $FILL - its background, a colour or " +
            "linear-gradient(<angle>deg, <colour> <at>%, <colour> <at>%, ...) with $STOPS_LEAST to $STOPS_MOST stops; " +
            "$COLOR - the colour of its words; $BORDER_WIDTH - 0 to $BORDER_MOST pixels; $BORDER_STYLE - " +
            BorderStyle.entries.joinToString(", ") { it.key } + "; $BORDER_COLOR; $RADIUS - corner radius, 0 to $RADIUS_MOST pixels; " +
            "$SHADOW - " + Shadow.entries.joinToString(", ") { it.key } + ". A colour is #rgb, #rrggbb, #rrggbbaa, " +
            "rgb(r, g, b), rgba(r, g, b, a) or a plain name such as white or navy. On a line or an arrow the border is the line itself."

    /** The look of an element whose props have been through [checked]. */
    fun look(props: Map<String, Any?>): Look = Look(
        fill = (props[FILL] as? String)?.let(::fill),
        color = (props[COLOR] as? String)?.let(::colour),
        borderWidth = (props[BORDER_WIDTH] as? Number)?.toInt()?.coerceIn(0, BORDER_MOST),
        borderStyle = (props[BORDER_STYLE] as? String)?.let { asked -> BorderStyle.entries.firstOrNull { it.key == asked } },
        borderColor = (props[BORDER_COLOR] as? String)?.let(::colour),
        radius = (props[RADIUS] as? Number)?.toInt()?.coerceIn(0, RADIUS_MOST),
        shadow = (props[SHADOW] as? String)?.let { asked -> Shadow.entries.firstOrNull { it.key == asked } },
        checked = props[CHECKED] == true,
    )

    /**
     * The style props of [props] checked and written in their one spelling,
     * the rest left as they were. A value that cannot be read is refused,
     * saying what was expected - or, where [lenient], dropped, which is how a
     * drawing stored before these were checked still opens.
     */
    fun checked(props: Map<String, Any?>, of: String, lenient: Boolean): Map<String, Any?> {
        val kept = linkedMapOf<String, Any?>()
        props.forEach { (key, value) ->
            if (key !in NAMES) {
                kept[key] = value
                return@forEach
            }
            val read: Any? = when (key) {
                FILL -> (value as? String)?.let(::fill)?.let(::written)
                COLOR, BORDER_COLOR -> (value as? String)?.let(::colour)
                BORDER_WIDTH -> whole(value)?.takeIf { it in 0..BORDER_MOST }
                RADIUS -> whole(value)?.takeIf { it in 0..RADIUS_MOST }
                BORDER_STYLE -> BorderStyle.entries.firstOrNull { it.key == value?.toString()?.trim()?.lowercase(Locale.ROOT) }?.key
                SHADOW -> Shadow.entries.firstOrNull { it.key == value?.toString()?.trim()?.lowercase(Locale.ROOT) }?.key
                CHECKED -> when (value) {
                    is Boolean -> value
                    "true" -> true
                    "false" -> false
                    else -> null
                }
                else -> null
            }
            when {
                read != null -> kept[key] = read
                lenient -> Unit
                else -> throw WireframeDocumentInvalidException("element $of has $key ${value}, which is not ${expected(key)}")
            }
        }
        return kept
    }

    private fun expected(key: String): String = when (key) {
        FILL -> "a colour or a linear-gradient(<angle>deg, <colour> <at>%, ...) of $STOPS_LEAST to $STOPS_MOST stops"
        COLOR, BORDER_COLOR -> "a colour: #rgb, #rrggbb, #rrggbbaa, rgb(r, g, b), rgba(r, g, b, a) or a plain name"
        BORDER_WIDTH -> "a whole number of pixels from 0 to $BORDER_MOST"
        RADIUS -> "a whole number of pixels from 0 to $RADIUS_MOST"
        BORDER_STYLE -> "one of " + BorderStyle.entries.joinToString(", ") { it.key }
        SHADOW -> "one of " + Shadow.entries.joinToString(", ") { it.key }
        CHECKED -> "true or false"
        else -> "allowed"
    }

    private fun whole(value: Any?): Int? = when (value) {
        is Number -> value.toDouble().takeIf { it.isFinite() }?.roundToInt()
        is String -> value.trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.roundToInt()
        else -> null
    }

    /**
     * A colour as lowercase hex - `#rrggbb`, or `#rrggbbaa` where it is not
     * opaque - or null where [said] is not one. Hex of three, four, six or
     * eight digits, `rgb()` and `rgba()` of plain numbers, and the names in
     * [NAMED].
     */
    fun colour(said: String): String? {
        val text = said.trim().lowercase(Locale.ROOT)
        if (text.length > 40) return null
        NAMED[text]?.let { return it }
        HEX.matchEntire(text)?.let { match ->
            val digits = match.groupValues[1]
            val long = if (digits.length <= 4) digits.map { "$it$it" }.joinToString("") else digits
            return "#" + if (long.length == 8 && long.endsWith("ff")) long.take(6) else long
        }
        RGB.matchEntire(text)?.let { match ->
            val channels = listOf(match.groupValues[1], match.groupValues[2], match.groupValues[3]).map { it.toDoubleOrNull() ?: return null }
            if (channels.any { it < 0 || it > 255 }) return null
            val alpha = match.groupValues[4].takeIf { it.isNotEmpty() }?.let { written ->
                val read = if (written.endsWith("%")) written.dropLast(1).toDoubleOrNull()?.div(100) else written.toDoubleOrNull()
                read?.takeIf { it in 0.0..1.0 } ?: return null
            } ?: 1.0
            val hex = channels.joinToString("") { "%02x".format(it.roundToInt()) }
            val a = (alpha * 255).roundToInt()
            return "#" + hex + if (a == 255) "" else "%02x".format(a)
        }
        return null
    }

    /** A background as written, or null where it is neither a colour nor a gradient this reads. */
    fun fill(said: String): Fill? {
        val text = said.trim().lowercase(Locale.ROOT)
        if (text.length > 400) return null
        if (!text.startsWith("linear-gradient")) return colour(text)?.let { Fill.Solid(it) }
        val inside = Regex("^linear-gradient\\s*\\((.*)\\)$").matchEntire(text)?.groupValues?.get(1) ?: return null
        val parts = split(inside)
        var angle = 180.0
        var stops = parts
        ANGLE.matchEntire(parts.firstOrNull().orEmpty())?.let { match ->
            angle = match.groupValues[1].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
            stops = parts.drop(1)
        }
        if (stops.size !in STOPS_LEAST..STOPS_MOST) return null
        val read = stops.map { part ->
            val match = STOP.matchEntire(part) ?: return null
            val colour = colour(match.groupValues[1]) ?: return null
            val at = match.groupValues[2].takeIf { it.isNotEmpty() }?.toDoubleOrNull()
            if (at != null && (at < 0 || at > 100)) return null
            colour to at
        }
        // Stops with no place are spread between their neighbours, as CSS spreads them.
        val places = read.mapIndexed { index, (_, at) -> at ?: if (index == 0) 0.0 else if (index == read.lastIndex) 100.0 else null }.toMutableList()
        var index = 0
        while (index < places.size) {
            if (places[index] == null) {
                val from = index - 1
                var to = index
                while (places[to] == null) to++
                val start = places[from]!!
                val end = places[to]!!
                for (between in index until to) places[between] = start + (end - start) * (between - from) / (to - from)
                index = to
            }
            index++
        }
        // A stop placed before the one ahead of it is drawn where that one is, as CSS draws it.
        var least = 0.0
        val placed = read.indices.map { at ->
            val place = maxOf(least, places[at]!!)
            least = place
            Stop(read[at].first, place)
        }
        val turned = ((angle % 360) + 360) % 360
        return Fill.Gradient(turned, placed)
    }

    /** A background in its one spelling, which is also valid CSS. */
    fun written(fill: Fill): String = when (fill) {
        is Fill.Solid -> fill.colour
        is Fill.Gradient -> "linear-gradient(${number(fill.angle)}deg, " +
            fill.stops.joinToString(", ") { "${it.colour} ${number(it.at)}%" } + ")"
    }

    /**
     * Where an SVG gradient runs to draw what CSS draws for a box: through the
     * middle at [Fill.Gradient.angle] (0 up, 90 right), as long as the box's
     * extent along that line.
     */
    fun line(angle: Double, x: Double, y: Double, w: Double, h: Double): DoubleArray {
        val radians = Math.toRadians(angle)
        val dx = sin(radians)
        val dy = -cos(radians)
        val half = (abs(w * dx) + abs(h * dy)) / 2
        val cx = x + w / 2
        val cy = y + h / 2
        return doubleArrayOf(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half)
    }

    /** A number as CSS takes it: no exponent, no trailing zeros. */
    fun number(value: Double): String {
        val rounded = (value * 100).roundToInt() / 100.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
    }

    /** A colour split into its hex and its opacity, for SVG, whose fill does not take eight digits everywhere. */
    fun opaque(colour: String): Pair<String, Double> =
        if (colour.length == 9) colour.take(7) to (colour.substring(7).toInt(16) / 255.0) else colour to 1.0

    /** Commas at the top level only, so `rgb(1, 2, 3)` stays one part. */
    private fun split(text: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        val part = StringBuilder()
        text.forEach { c ->
            when {
                c == '(' -> { depth++; part.append(c) }
                c == ')' -> { depth--; part.append(c) }
                c == ',' && depth == 0 -> { parts += part.toString().trim(); part.clear() }
                else -> part.append(c)
            }
        }
        parts += part.toString().trim()
        return parts
    }

    private val HEX = Regex("^#([0-9a-f]{3}|[0-9a-f]{4}|[0-9a-f]{6}|[0-9a-f]{8})$")
    private val RGB = Regex("^rgba?\\(\\s*(\\d{1,3}(?:\\.\\d+)?)\\s*,\\s*(\\d{1,3}(?:\\.\\d+)?)\\s*,\\s*(\\d{1,3}(?:\\.\\d+)?)\\s*(?:,\\s*(\\d*\\.?\\d+%?)\\s*)?\\)$")
    private val ANGLE = Regex("^(-?\\d{1,4}(?:\\.\\d+)?)\\s*deg$")
    private val STOP = Regex("^(#[0-9a-f]{3,8}|rgba?\\([^()]*\\)|[a-z]+)(?:\\s+(\\d{1,3}(?:\\.\\d+)?)%)?$")

    /** The plain names a colour may be given by: CSS's basic sixteen, and a few anybody would say. */
    val NAMED: Map<String, String> = mapOf(
        "black" to "#000000", "silver" to "#c0c0c0", "gray" to "#808080", "grey" to "#808080", "white" to "#ffffff",
        "maroon" to "#800000", "red" to "#ff0000", "purple" to "#800080", "fuchsia" to "#ff00ff", "green" to "#008000",
        "lime" to "#00ff00", "olive" to "#808000", "yellow" to "#ffff00", "navy" to "#000080", "blue" to "#0000ff",
        "teal" to "#008080", "aqua" to "#00ffff", "orange" to "#ffa500", "pink" to "#ffc0cb", "transparent" to "#00000000",
    )
}
