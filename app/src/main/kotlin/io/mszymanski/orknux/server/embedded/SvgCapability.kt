package io.mszymanski.orknux.server.embedded

import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.llm.LlmSessionStore
import io.mszymanski.orknux.server.mcp.WireframeTools
import io.mszymanski.orknux.server.plugin.SvgRenderer
import io.mszymanski.orknux.workflow.script.ScriptResult
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.awt.Color
import java.util.Base64

/**
 * Drawing in SVG, and turning an SVG into a PNG.
 *
 * A diagram and a chart are drawn from a description of one; this is for
 * everything else - an icon, a badge, a floor plan, a picture made of shapes -
 * where the model writes the SVG itself. Any colour SVG can say is drawn as
 * said: names, hex, rgb() and hsl() with alpha, opacity, and linear and radial
 * gradients, all of it the renderer's and none of it filtered here.
 *
 * `svg_toPng` is the other half: an SVG somebody already has - a diagram kept
 * as svg, an uploaded file, markup in hand - as a picture a chat can show,
 * because Slack and most readers will not draw an SVG.
 *
 * The answers are the diagram's: a picture to look at plus a key to send, or for
 * `format: svg` a key to the markup. Drawn by [SvgRenderer], which fetches
 * nothing and runs nothing a document asks it to.
 */
@Component
class SvgCapability(
    private val svgs: SvgRenderer,
    private val scratch: LlmSessionStore,
    private val mapper: ObjectMapper,
    private val installation: InstallationSettings,
    private val measuring: SvgMeasure,
) : EmbeddedCapability {

    override val key = CAPABILITY
    override val name = "SVG"

    override fun tools(): List<EmbeddedTool> = listOf(
        EmbeddedTool(
            name = DRAW,
            readOnly = true,
            // The front of it is what survives in a long tool list, so the not-a-mockup half is up there.
            summary = "Pictures in SVG, not screen mockups",
            description = "Draws SVG markup you write and answers it as a picture you can look at or send. " +
                "Start with <svg xmlns=\"http://www.w3.org/2000/svg\" width=\"…\" height=\"…\" viewBox=\"…\">. " +
                "Colour with fill and stroke: names (teal), #hex, rgb() and hsl() with alpha, fill-opacity, and " +
                "<linearGradient> or <radialGradient> under <defs>. Nothing outside the markup is fetched, so " +
                "no linked images or web fonts. A diagram or a chart has a tool of its own that draws it from a description. " +
                "For an illustration, an icon or a picture - not for a mockup or wireframe of a screen, a page, a form " +
                "or an app's UI: where you have ${WireframeTools.CREATE}, whether in hand or to load, draw that as a wireframe " +
                "with it, which a person can open and change in the wireframe editor.",
            params = listOf(
                EmbeddedParam(SVG, ValueType.STRING, "The SVG markup.", required = true),
                EmbeddedParam(FORMAT, ValueType.STRING, "\"png\" (the default) or \"svg\". Either way the answer is a contentKey."),
                EmbeddedParam(WIDTH, ValueType.NUMBER, "How wide the PNG is, in pixels; the height follows."),
                EmbeddedParam(BACKGROUND, ValueType.STRING, "Behind the PNG: a #hex colour, or \"transparent\". White by default."),
                SHOW_PICTURE,
            ),
        ),
        EmbeddedTool(
            name = READ,
            readOnly = true,
            summary = "Reads the SVG markup kept under a key.",
            description = "Answers the markup of an SVG kept under a $KEY in this session - a drawing made as svg, " +
                "an attached .svg file - so it can be read or changed and drawn again. To look at it as a picture, " +
                "ask for it as a picture instead. Do not read a large SVG whole to answer a question about it - a " +
                "drawing exported from a CAD tool is hundreds of thousands of characters of coordinates: count or " +
                "list its elements with ${XmlCapability.QUERY_TOOL}, and measure sizes, positions, spacing and groups " +
                "of its shapes with $MEASURE_TOOL; only their answers enter the conversation.",
            params = listOf(EmbeddedParam(KEY, ValueType.STRING, "The contentKey of the SVG.", required = true)),
            reads = StoredReader(setOf("image/svg+xml"), KEY),
        ),
        EmbeddedTool(
            name = MEASURE,
            readOnly = true,
            summary = "Measures an SVG's shapes - boxes, sizes, pitch, spacing, groups - without reading it whole.",
            description = "Measures the shapes of an SVG kept under a $KEY in this session without the file entering " +
                "the conversation: only a table of numbers does. Use it instead of reading a large SVG whole with " +
                "$READ_TOOL - a PCB layer, a floor plan, a chart. Answers each shape's bounding box (x, y, width, " +
                "height) in user units with every transform of its groups applied, the overall extent, and how many " +
                "millimetres a user unit is where the file says (width=\"..mm\" over a viewBox). Chooses every " +
                "path, rect, circle, ellipse, line, polyline and polygon, or what an XPath in ${SvgMeasure.SELECT} " +
                "chooses (as ${XmlCapability.QUERY_TOOL} writes one). A box includes the stroke's width unless " +
                "${SvgMeasure.STROKE} is false. ${SvgMeasure.STATS} adds, for the chosen shapes: " +
                "${SvgMeasure.STAT_SIZES} (distinct width x height with counts), ${SvgMeasure.STAT_PITCH} (each " +
                "one's nearest neighbour, centre to centre), ${SvgMeasure.STAT_SPACING} (the closest pairs edge to " +
                "edge, and how many touch). ${SvgMeasure.GROUP_BY} \"${SvgMeasure.PROXIMITY}\" groups shapes " +
                "whose edges are within ${SvgMeasure.GAP} of each other - pads into footprints; leave out a pour or a " +
                "background that touches everything with ${SvgMeasure.MAX_SIZE}. Totals are always " +
                "given; lists are paged with ${SvgMeasure.OFFSET} and ${SvgMeasure.LIMIT}, and ${SvgMeasure.LIMIT} 0 " +
                "leaves the per-shape rows out.",
            params = listOf(
                EmbeddedParam(KEY, ValueType.STRING, "The contentKey of the SVG.", required = true),
                EmbeddedParam(SvgMeasure.SELECT, ValueType.STRING, "An XPath 1.0 expression choosing the elements to measure, such as //svg:circle | //svg:path[contains(@style,'stroke:none')]. Left out, every shape."),
                EmbeddedParam(SvgMeasure.STATS, ValueType.STRING, "Which of \"${SvgMeasure.STAT_SIZES}\", \"${SvgMeasure.STAT_PITCH}\" and \"${SvgMeasure.STAT_SPACING}\" to add, comma separated, or \"${SvgMeasure.ALL}\"."),
                EmbeddedParam(SvgMeasure.GROUP_BY, ValueType.STRING, "\"${SvgMeasure.PROXIMITY}\" to group shapes that are close together."),
                EmbeddedParam(SvgMeasure.GAP, ValueType.NUMBER, "How close, edge to edge, two shapes are to be grouped, in the answer's units. Left out, worked out from the shapes' pitch."),
                EmbeddedParam(SvgMeasure.MAX_SIZE, ValueType.NUMBER, "Leave out shapes whose width or height is larger than this, in the answer's units - a copper pour or a background the size of the drawing, which every other shape touches."),
                EmbeddedParam(SvgMeasure.MM, ValueType.BOOLEAN, "true to answer in millimetres, where the file says how large a user unit is."),
                EmbeddedParam(SvgMeasure.STROKE, ValueType.BOOLEAN, "false to measure the geometry without the stroke's width."),
                EmbeddedParam(SvgMeasure.PRECISION, ValueType.NUMBER, "Decimal places in the answer. Left out, 4."),
                EmbeddedParam(SvgMeasure.OFFSET, ValueType.NUMBER, "How many rows to skip, for the next page."),
                EmbeddedParam(SvgMeasure.LIMIT, ValueType.NUMBER, "How many rows to list; 0 for none, for totals and statistics only."),
            ),
            asks = StoredReader(setOf("image/svg+xml"), KEY),
        ),
        EmbeddedTool(
            name = TO_PNG,
            readOnly = true,
            summary = "Turns an SVG into a PNG.",
            description = "Turns an SVG into a PNG picture you can look at or send. Give the $KEY an SVG is " +
                "kept under in this session - a diagram or a drawing made as svg, an uploaded .svg - or the markup as $SVG.",
            params = listOf(
                EmbeddedParam(KEY, ValueType.STRING, "The contentKey of the SVG."),
                EmbeddedParam(SVG, ValueType.STRING, "The SVG markup, where there is no key."),
                EmbeddedParam(WIDTH, ValueType.NUMBER, "How wide the PNG is, in pixels; the height follows."),
                EmbeddedParam(BACKGROUND, ValueType.STRING, "Behind the PNG: a #hex colour, or \"transparent\". White by default."),
                SHOW_PICTURE,
            ),
            reads = StoredReader(setOf("image/svg+xml"), KEY),
        ),
    )

    override fun functions(): List<EmbeddedFunction> = listOf(
        EmbeddedFunction(
            name = TO_PNG,
            description = "Draws SVG markup as a PNG and answers it as base64, with the size it came out.",
            params = listOf(
                EmbeddedParam(SVG, ValueType.STRING, "The SVG markup.", required = true),
                EmbeddedParam(WIDTH, ValueType.NUMBER, "How wide the PNG is, in pixels."),
                EmbeddedParam(BACKGROUND, ValueType.STRING, "A #hex colour, or \"transparent\"."),
            ),
        ),
    )

    override fun run(name: String, arguments: String, workspaceId: Long, sessionId: Long?): String {
        val asked = runCatching { mapper.readTree(arguments) }.getOrNull()
            ?: return refusal("That is not valid JSON.")
        return when (name) {
            DRAW -> {
                val svg = text(asked, SVG)?.takeIf { it.isNotBlank() } ?: return refusal("Give the $SVG markup.")
                val wanted = text(asked, FORMAT)?.trim()?.lowercase()?.ifEmpty { null } ?: "png"
                // Drawn either way, so markup that cannot be drawn is refused now rather than when somebody opens it.
                val picture = when (val drawn = png(svg, asked)) {
                    is Png.Refused -> return refusal(drawn.reason)
                    is Png.Drawn -> drawn.picture
                }
                if (wanted == "svg") return vectors(mapper, scratch, sessionId, "drawing", svg, "svg")
                drawing(mapper, scratch, sessionId, "drawing", picture.png, picture.width, picture.height, "svg")
            }
            READ -> {
                val key = text(asked, KEY)?.trim()?.ifEmpty { null } ?: return refusal("Give the $KEY of an SVG.")
                val svg = held(asked, sessionId) ?: return refusal(
                    sessionId?.let { scratch.kindOf(it, key)?.contentType }?.let { "\"$key\" holds $it, not an SVG." }
                        ?: scratch.nothingUnder(sessionId, key, "Nothing in this session is kept under \"$key\" as an SVG."),
                )
                mapper.writeValueAsString(linkedMapOf(SVG to svg, "characters" to svg.length))
            }
            MEASURE -> measuring.run(asked, sessionId)
            TO_PNG -> {
                // A key that already holds a PNG - a drawing made in the default format - is the answer as it is.
                pngHeld(asked, sessionId)?.let { return it }
                val svg = held(asked, sessionId) ?: text(asked, SVG)?.takeIf { it.isNotBlank() }
                    ?: return refusal(
                        text(asked, KEY)?.let { key ->
                            val kind = sessionId?.let { scratch.kindOf(it, key.trim())?.contentType }
                            if (kind == null) scratch.nothingUnder(sessionId, key)
                            else "\"$key\" holds $kind, not an SVG."
                        } ?: "Give the $KEY of an SVG, or its markup as $SVG.",
                    )
                when (val drawn = png(svg, asked)) {
                    is Png.Refused -> refusal(drawn.reason)
                    is Png.Drawn -> drawing(
                        mapper, scratch, sessionId, "picture",
                        drawn.picture.png, drawn.picture.width, drawn.picture.height, "png",
                    )
                }
            }
            else -> refusal("There is no tool called svg_$name.")
        }
    }

    override fun call(name: String, arguments: List<String>, workspaceId: Long, sessionId: Long?): ScriptResult? {
        if (name != TO_PNG) return null
        val asked = mapper.createObjectNode().apply {
            put(SVG, unquotedText(arguments.getOrNull(0)))
            arguments.getOrNull(1)?.trim()?.toIntOrNull()?.let { put(WIDTH, it) }
            unquotedText(arguments.getOrNull(2)).takeIf { it.isNotEmpty() }?.let { put(BACKGROUND, it) }
        }
        return when (val drawn = png(asked.path(SVG).stringValue(), asked)) {
            is Png.Refused -> ScriptResult.Failed(drawn.reason, 0)
            is Png.Drawn -> ScriptResult.Returned(
                mapper.writeValueAsString(
                    linkedMapOf(
                        "png" to Base64.getEncoder().encodeToString(drawn.picture.png),
                        "width" to drawn.picture.width,
                        "height" to drawn.picture.height,
                    ),
                ),
                0,
            )
        }
    }

    private sealed interface Png {
        data class Drawn(val picture: SvgRenderer.Drawing.Drawn) : Png
        data class Refused(val reason: String) : Png
    }

    private fun png(svg: String, asked: JsonNode): Png {
        val width = asked.path(WIDTH).let { node ->
            when {
                node.isNumber -> node.intValue()
                node.isString -> node.stringValue().trim().ifEmpty { null }?.toIntOrNull()
                    ?: return Png.Refused("$WIDTH is a number of pixels.")
                else -> null
            }
        }
        val background = when (val said = text(asked, BACKGROUND)?.trim()?.lowercase()?.ifEmpty { null }) {
            null -> Color.WHITE
            "transparent", "none" -> null
            else -> colourOf(said) ?: return Png.Refused("$BACKGROUND is a #hex colour like #1e293b, or \"transparent\".")
        }
        val scale = if (width == null) installation.drawingScale().toDouble() else 1.0
        return when (val drawn = svgs.png(svg, width, scale, background)) {
            is SvgRenderer.Drawing.Refused -> Png.Refused(drawn.reason)
            is SvgRenderer.Drawing.Drawn -> Png.Drawn(drawn)
        }
    }

    /** The picture kept under the key given where it is a PNG already, answered as a drawing is; null otherwise. */
    private fun pngHeld(asked: JsonNode, sessionId: Long?): String? {
        val named = text(asked, KEY)?.trim()?.ifEmpty { null } ?: return null
        if (sessionId == null || scratch.kindOf(sessionId, named)?.contentType != "image/png") return null
        val stored = scratch.get(sessionId, named) ?: return null
        val base64 = runCatching { mapper.readTree(stored) }.getOrNull()?.takeIf { it.isTextual }?.stringValue() ?: stored
        val bytes = runCatching { Base64.getDecoder().decode(base64.trim()) }.getOrNull() ?: return null
        val image = runCatching { javax.imageio.ImageIO.read(bytes.inputStream()) }.getOrNull() ?: return null
        return drawing(mapper, scratch, sessionId, "picture", bytes, image.width, image.height, "png")
    }

    /** The SVG kept under the key given, or null where none was given or it holds something else. */
    private fun held(asked: JsonNode, sessionId: Long?): String? {
        val named = text(asked, KEY)?.trim()?.ifEmpty { null } ?: return null
        if (sessionId == null) return null
        val stored = scratch.get(sessionId, named) ?: return null
        val kind = scratch.kindOf(sessionId, named)
        val value = runCatching { mapper.readTree(stored) }.getOrNull()?.takeIf { it.isTextual }?.stringValue() ?: stored
        val text = if (kind?.binary == false) value else {
            runCatching { String(Base64.getDecoder().decode(value.trim()), Charsets.UTF_8) }.getOrNull() ?: value
        }
        return text.takeIf { it.trimStart().let { start -> start.startsWith("<svg") || start.startsWith("<?xml") || start.startsWith("<!") } }
    }

    private fun unquotedText(argument: String?): String {
        val given = argument?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return ""
        return runCatching { mapper.readTree(given) }.getOrNull()?.takeIf { it.isTextual }?.stringValue() ?: given
    }

    private fun text(node: JsonNode, name: String): String? =
        node.path(name).takeIf { it.isTextual }?.stringValue()

    private fun refusal(said: String): String = mapper.writeValueAsString(mapOf("error" to said))

    companion object {
        const val CAPABILITY = "svg"
        const val DRAW = "draw"
        const val TO_PNG = "toPng"

        /** What a model calls to have SVG markup drawn as a picture, as the tool list names it. */
        const val RENDER_TOOL = CAPABILITY + "_" + TO_PNG

        const val READ = "read"
        const val MEASURE = "measure"

        /** The tool that answers an SVG's markup whole, as the tool list names it. */
        const val READ_TOOL = CAPABILITY + "_" + READ

        /** The tool that measures an SVG's shapes without reading it whole, as the tool list names it. */
        const val MEASURE_TOOL = CAPABILITY + "_" + MEASURE
        const val SVG = "svg"
        const val KEY = "key"
        const val FORMAT = "format"
        const val WIDTH = "width"
        const val BACKGROUND = "background"

        /** #rgb, #rrggbb or #rrggbbaa; null for anything else. */
        fun colourOf(said: String): Color? {
            val hex = said.removePrefix("#").takeIf { said.startsWith("#") && it.all { c -> c.isDigit() || c in 'a'..'f' } }
                ?: return null
            val full = when (hex.length) {
                3 -> hex.map { "$it$it" }.joinToString("")
                6, 8 -> hex
                else -> return null
            }
            val rgb = full.substring(0, 6).toInt(16)
            val alpha = if (full.length == 8) full.substring(6).toInt(16) else 255
            return Color((rgb shr 16) and 0xff, (rgb shr 8) and 0xff, rgb and 0xff, alpha)
        }
    }
}
