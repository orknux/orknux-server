package io.mszymanski.orknux.server.plugin

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The colours a model writes, in the words the renderer knows.
 *
 * Batik reads SVG 1.1, whose colours are a name, `#rgb`, `#rrggbb` and
 * `rgb()`. Everything CSS has said since - `hsl()`, `rgba()`, an alpha in the
 * hex, `transparent`, `rgb(0 128 255 / 50%)` - it does not read, and it does
 * not refuse either: the shape is drawn black. A model writes those as readily
 * as the old ones, and a browser draws them, so a drawing that looked right in
 * a page came out black in the PNG of it.
 *
 * So each is rewritten before drawing as the hex it means, and an alpha as the
 * matching `*-opacity` beside it - multiplied into one already there. In
 * attributes, in `style`, and in a `<style>` block. Nothing else in the
 * document is touched.
 */
object SvgColours {

    /** Each colour property and the opacity that carries its alpha. */
    private val OPACITY = mapOf(
        "fill" to "fill-opacity",
        "stroke" to "stroke-opacity",
        "stop-color" to "stop-opacity",
        "flood-color" to "flood-opacity",
        "lighting-color" to null,
        "color" to null,
    )

    private val TAG = Regex("<([A-Za-z][\\w:.-]*)(\\s[^<>]*?)?(/?)>")
    private val ATTRIBUTE = Regex("([\\w:.-]+)(\\s*=\\s*)(\"([^\"]*)\"|'([^']*)')")
    private val STYLE_BLOCK = Regex("(<style[^>]*>)(.*?)(</style>)", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val BLOCK = Regex("\\{([^{}]*)\\}")
    private val FUNCTION = Regex("^(rgba?|hsla?)\\(([^)]*)\\)$", RegexOption.IGNORE_CASE)

    fun normalized(svg: String): String {
        // Quick way out for the common case: nothing written in the newer words.
        if (!NEWER.containsMatchIn(svg)) return svg
        val styled = STYLE_BLOCK.replace(svg) { block ->
            block.groupValues[1] + BLOCK.replace(block.groupValues[2]) { "{" + declarations(it.groupValues[1]) + "}" } + block.groupValues[3]
        }
        return TAG.replace(styled) { tag -> tag.groupValues[0].let(::element) }
    }

    private val NEWER = Regex("hsla?\\(|rgba\\(|rgb\\([^)]*/|#[0-9a-fA-F]{4}\\b|#[0-9a-fA-F]{8}\\b|transparent|rgb\\(\\s*[\\d.]+%?\\s+[\\d.]", RegexOption.IGNORE_CASE)

    private fun element(tag: String): String {
        val attributes = ATTRIBUTE.findAll(tag).toList()
        if (attributes.isEmpty()) return tag
        val opacities = mutableMapOf<String, Double>()
        var out = tag
        for (attribute in attributes.asReversed()) {
            val name = attribute.groupValues[1]
            val value = attribute.groupValues[4].ifEmpty { attribute.groupValues[5] }
            val replaced = when {
                name.equals("style", ignoreCase = true) -> declarations(value)
                name in OPACITY -> parsed(value)?.let { (hex, alpha) ->
                    OPACITY[name]?.let { if (alpha < 1.0) opacities[it] = alpha }
                    hex
                }
                else -> null
            } ?: continue
            val quote = attribute.groupValues[3].first()
            out = out.replaceRange(attribute.range, "$name${attribute.groupValues[2]}$quote$replaced$quote")
        }
        if (opacities.isEmpty()) return out
        // Into an opacity already written, or added before the end of the tag.
        for ((name, alpha) in opacities) {
            val held = ATTRIBUTE.findAll(out).firstOrNull { it.groupValues[1] == name }
            out = if (held != null) {
                val was = held.groupValues[4].ifEmpty { held.groupValues[5] }.trim().toDoubleOrNull() ?: 1.0
                out.replaceRange(held.range, "$name=\"${number(was * alpha)}\"")
            } else {
                val end = if (out.endsWith("/>")) out.length - 2 else out.length - 1
                out.substring(0, end).trimEnd() + " $name=\"${number(alpha)}\"" + out.substring(end)
            }
        }
        return out
    }

    /** `fill: hsl(...); stroke: #0008` as `fill: #...; stroke: #...; stroke-opacity: 0.53`. */
    private fun declarations(css: String): String {
        val parts = css.split(';')
        val opacities = mutableMapOf<String, Double>()
        val rewritten = parts.map { part ->
            val colon = part.indexOf(':')
            if (colon < 0) return@map part
            val name = part.substring(0, colon).trim().lowercase()
            if (name !in OPACITY) return@map part
            val value = part.substring(colon + 1)
            val important = value.contains("!important")
            val (hex, alpha) = parsed(value.replace("!important", "").trim()) ?: return@map part
            OPACITY[name]?.let { if (alpha < 1.0) opacities[it] = alpha }
            part.substring(0, colon + 1) + " " + hex + if (important) " !important" else ""
        }.toMutableList()
        for ((name, alpha) in opacities) {
            val at = rewritten.indexOfFirst { it.substringBefore(':').trim().lowercase() == name }
            if (at >= 0) {
                val was = rewritten[at].substringAfter(':').trim().toDoubleOrNull() ?: 1.0
                rewritten[at] = " $name: ${number(was * alpha)}"
            } else {
                val last = rewritten.indexOfLast { it.isNotBlank() }
                rewritten.add(last + 1, " $name: ${number(alpha)}")
            }
        }
        return rewritten.joinToString(";")
    }

    /** A colour in one of the newer words, as `#rrggbb` and its alpha; null for anything else, left as written. */
    internal fun parsed(value: String): Pair<String, Double>? {
        val said = value.trim()
        if (said.equals("transparent", ignoreCase = true)) return "#000000" to 0.0
        if (said.startsWith("#") && (said.length == 5 || said.length == 9) && said.drop(1).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            val hex = if (said.length == 5) said.drop(1).map { "$it$it" }.joinToString("") else said.drop(1)
            return "#" + hex.take(6).lowercase() to hex.substring(6).toInt(16) / 255.0
        }
        val function = FUNCTION.find(said) ?: return null
        val kind = function.groupValues[1].lowercase()
        val body = function.groupValues[2]
        val (channels, slashed) = body.split('/').let { it[0] to it.getOrNull(1) }
        val parts = channels.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        val alphaText = slashed?.trim() ?: parts.getOrNull(3)
        if (parts.size < 3) return null
        // rgb(1,2,3) without an alpha is SVG 1.1 already, and is left as it is.
        if (kind == "rgb" && alphaText == null && body.contains(',')) return null
        val alpha = alphaText?.let(::fraction)?.coerceIn(0.0, 1.0) ?: 1.0
        val (r, g, b) = if (kind.startsWith("rgb")) {
            val (red, green, blue) = parts.take(3).map { channel(it) ?: return null }
            Triple(red, green, blue)
        } else {
            val hue = parts[0].removeSuffix("deg").toDoubleOrNull() ?: return null
            val saturation = parts[1].removeSuffix("%").toDoubleOrNull()?.div(100) ?: return null
            val lightness = parts[2].removeSuffix("%").toDoubleOrNull()?.div(100) ?: return null
            hsl(hue, saturation.coerceIn(0.0, 1.0), lightness.coerceIn(0.0, 1.0))
        }
        return "#%02x%02x%02x".format(r, g, b) to alpha
    }

    private fun channel(said: String): Int? =
        if (said.endsWith("%")) said.removeSuffix("%").toDoubleOrNull()?.let { (it * 2.55).roundToInt().coerceIn(0, 255) }
        else said.toDoubleOrNull()?.roundToInt()?.coerceIn(0, 255)

    private fun fraction(said: String): Double? =
        if (said.endsWith("%")) said.removeSuffix("%").toDoubleOrNull()?.div(100) else said.toDoubleOrNull()

    private fun hsl(hue: Double, saturation: Double, lightness: Double): Triple<Int, Int, Int> {
        val chroma = (1 - abs(2 * lightness - 1)) * saturation
        val h = ((hue % 360) + 360) % 360 / 60
        val x = chroma * (1 - abs(h % 2 - 1))
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(chroma, x, 0.0)
            1 -> Triple(x, chroma, 0.0)
            2 -> Triple(0.0, chroma, x)
            3 -> Triple(0.0, x, chroma)
            4 -> Triple(x, 0.0, chroma)
            else -> Triple(chroma, 0.0, x)
        }
        val m = lightness - chroma / 2
        fun byte(v: Double) = ((v + m) * 255).roundToInt().coerceIn(0, 255)
        return Triple(byte(r), byte(g), byte(b))
    }

    private fun number(value: Double): String = "%.3f".format(java.util.Locale.ROOT, value).trimEnd('0').trimEnd('.').ifEmpty { "0" }
}
