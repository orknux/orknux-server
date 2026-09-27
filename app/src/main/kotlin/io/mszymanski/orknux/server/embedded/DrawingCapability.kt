package io.mszymanski.orknux.server.embedded

import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.llm.LlmSessionStore
import io.mszymanski.orknux.server.plugin.SvgRenderer
import io.mszymanski.orknux.workflow.script.ScriptResult
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.Base64

/**
 * Drawing a diagram, on its own. Issues #487 and #505.
 *
 * Mostly a diagram wants to be *in* something - a report, a page - and that is
 * what a `<pre class="mermaid">` block in [PageBlocks] is for. This is for the
 * other case: somebody asked for a picture of a diagram, to look at or to send.
 *
 * So it answers a picture rather than a key, the way `pdf_preview` does and for
 * the same reason: seeing it is the point of asking. The SVG is available too,
 * for putting into a page by hand.
 */
@Component
class DiagramCapability(
    private val diagrams: DiagramRenderer,
    private val svgs: SvgRenderer,
    private val scratch: LlmSessionStore,
    private val mapper: ObjectMapper,
) : EmbeddedCapability {

    override val key = "diagram"
    override val name = "Diagrams"

    override fun tools(): List<EmbeddedTool> = listOf(
        EmbeddedTool(
            name = RENDER,
            summary = "Draws a diagram from mermaid or PlantUML source.",
            description = "Draws a diagram and answers it as a picture you can look at or send. Takes " +
                "mermaid - flowchart, sequenceDiagram, classDiagram, erDiagram, stateDiagram-v2, mindmap - " +
                "or PlantUML written out, which opens everything else: gantt, activity, component, " +
                "deployment, wireframes, json trees. A pie or an xy plot is a chart, not a diagram: call " +
                "charts_render for those. To put a diagram *inside* a report, do not call this - write the " +
                "source into a <pre class=\"mermaid\"> block in the HTML and pdf_fromHtml draws it onto the " +
                "page as vectors.",
            params = listOf(
                EmbeddedParam(SOURCE, ValueType.STRING, "The diagram source.", required = true),
                EmbeddedParam(FORMAT, ValueType.STRING, "\"png\" to look at, or \"svg\" to put in a page."),
            ),
        ),
    )

    override fun functions(): List<EmbeddedFunction> = listOf(
        EmbeddedFunction(
            name = RENDER,
            description = "Draws a diagram and answers the SVG, with the size it came out.",
            params = listOf(
                EmbeddedParam(SOURCE, ValueType.STRING, "The diagram source.", required = true),
            ),
        ),
    )

    override fun run(name: String, arguments: String, workspaceId: Long, sessionId: Long?): String {
        if (name != RENDER) return refusal("There is no tool called diagram_$name.")
        val asked = runCatching { mapper.readTree(arguments) }.getOrNull()
            ?: return refusal("That is not valid JSON.")
        val source = text(asked, SOURCE)?.takeIf { it.isNotBlank() }
            ?: return refusal("Give the diagram $SOURCE.")

        val drawn = when (val answered = diagrams.svg(source)) {
            is DiagramRenderer.Drawing.Refused -> return refusal(answered.reason)
            is DiagramRenderer.Drawing.Drawn -> answered
        }

        val wanted = text(asked, FORMAT)?.trim()?.lowercase()?.ifEmpty { null } ?: "png"
        if (wanted == "svg") {
            return mapper.writeValueAsString(linkedMapOf("svg" to drawn.svg, "kind" to drawn.kind))
        }

        return when (val picture = svgs.png(drawn.svg, null)) {
            is SvgRenderer.Drawing.Refused -> refusal(picture.reason)
            is SvgRenderer.Drawing.Drawn -> mapper.writeValueAsString(
                linkedMapOf(
                    "picture" to Base64.getEncoder().encodeToString(picture.png),
                    "pictureType" to "image/png",
                    "width" to picture.width,
                    "height" to picture.height,
                    "kind" to drawn.kind,
                ),
            )
        }
    }

    override fun call(name: String, arguments: List<String>, workspaceId: Long, sessionId: Long?): ScriptResult? {
        if (name != RENDER) return null
        val source = unquoted(mapper, arguments.getOrNull(0)).orEmpty()
        return when (val drawn = diagrams.svg(source)) {
            is DiagramRenderer.Drawing.Refused -> ScriptResult.Failed(drawn.reason, 0)
            is DiagramRenderer.Drawing.Drawn -> ScriptResult.Returned(
                mapper.writeValueAsString(linkedMapOf("svg" to drawn.svg, "kind" to drawn.kind)),
                0,
            )
        }
    }

    private fun text(node: JsonNode, name: String): String? =
        node.path(name).takeIf { it.isTextual }?.stringValue()

    private fun refusal(said: String): String = mapper.writeValueAsString(mapOf("error" to said))

    private companion object {
        const val RENDER = "render"
        const val SOURCE = "source"
        const val FORMAT = "format"
    }
}

/**
 * Drawing a chart, on its own. Issues #507 and #505.
 *
 * The same shape as the diagram above and for the same reasons - a chart in a
 * report goes in a `<pre class="chart">` block, and this is for the picture
 * somebody wants to look at or send.
 *
 * Kept as `charts_render`, the name the bundle used, so an agent that knew it
 * knows it still.
 */
@Component
class ChartCapability(
    private val charts: ChartRenderer,
    private val svgs: SvgRenderer,
    private val mapper: ObjectMapper,
) : EmbeddedCapability {

    override val key = "charts"
    override val name = "Charts"

    override fun tools(): List<EmbeddedTool> = listOf(
        EmbeddedTool(
            name = RENDER,
            summary = "Draws a bar, column, line, area, pie, donut or scatter chart.",
            description = "Draws a chart and answers it as a picture you can look at or send. $KIND is one " +
                "of ${ChartRenderer.Kind.offered()}; $VALUES is a label and a number each, like " +
                "{\"Rent\":45,\"Food\":30}. To put a chart *inside* a report, do not call this - write " +
                "<pre class=\"chart\">{\"kind\":\"pie\",\"values\":{…}}</pre> into the HTML and pdf_fromHtml " +
                "draws it onto the page as vectors.",
            params = listOf(
                EmbeddedParam(KIND, ValueType.STRING, "One of ${ChartRenderer.Kind.offered()}.", required = true),
                EmbeddedParam(VALUES, ValueType.MAP, "A label and a number each.", required = true),
                EmbeddedParam(TITLE, ValueType.STRING, "What the chart is called."),
                EmbeddedParam(FORMAT, ValueType.STRING, "\"png\" to look at, or \"svg\" to put in a page."),
            ),
        ),
    )

    override fun functions(): List<EmbeddedFunction> = listOf(
        EmbeddedFunction(
            name = RENDER,
            description = "Draws a chart and answers the SVG.",
            params = listOf(
                EmbeddedParam(KIND, ValueType.STRING, "The chart kind.", required = true),
                EmbeddedParam(VALUES, ValueType.MAP, "A label and a number each.", required = true),
                EmbeddedParam(TITLE, ValueType.STRING, "What the chart is called."),
            ),
        ),
    )

    override fun run(name: String, arguments: String, workspaceId: Long, sessionId: Long?): String {
        if (name != RENDER) return refusal("There is no tool called charts_$name.")
        val asked = runCatching { mapper.readTree(arguments) }.getOrNull()
            ?: return refusal("That is not valid JSON.")

        val points = pointsIn(asked.path(VALUES))
            ?: return refusal("Give $VALUES as a label and a number each, like {\"Rent\":45,\"Food\":30}.")
        val kind = asked.path(KIND).takeIf { it.isTextual }?.stringValue()?.trim().orEmpty()
        val title = asked.path(TITLE).takeIf { it.isTextual }?.stringValue()?.trim()?.ifEmpty { null }

        val drawn = when (val answered = charts.svg(kind.ifEmpty { "bar" }, points, title)) {
            is ChartRenderer.Drawing.Refused -> return refusal(answered.reason)
            is ChartRenderer.Drawing.Drawn -> answered
        }

        val wanted = asked.path(FORMAT).takeIf { it.isTextual }?.stringValue()?.trim()?.lowercase()?.ifEmpty { null }
        if (wanted == "svg") {
            return mapper.writeValueAsString(linkedMapOf("svg" to drawn.svg, "kind" to drawn.kind))
        }

        return when (val picture = svgs.png(drawn.svg, null)) {
            is SvgRenderer.Drawing.Refused -> refusal(picture.reason)
            is SvgRenderer.Drawing.Drawn -> mapper.writeValueAsString(
                linkedMapOf(
                    "picture" to Base64.getEncoder().encodeToString(picture.png),
                    "pictureType" to "image/png",
                    "width" to picture.width,
                    "height" to picture.height,
                    "kind" to drawn.kind,
                ),
            )
        }
    }

    override fun call(name: String, arguments: List<String>, workspaceId: Long, sessionId: Long?): ScriptResult? {
        if (name != RENDER) return null
        val kind = unquoted(mapper, arguments.getOrNull(0)).orEmpty()
        val values = runCatching { mapper.readTree(arguments.getOrNull(1).orEmpty()) }.getOrNull()
        val points = values?.let { pointsIn(it) }
            ?: return ScriptResult.Failed("values is a label and a number each", 0)
        val title = unquoted(mapper, arguments.getOrNull(2))

        return when (val drawn = charts.svg(kind.ifEmpty { "bar" }, points, title)) {
            is ChartRenderer.Drawing.Refused -> ScriptResult.Failed(drawn.reason, 0)
            is ChartRenderer.Drawing.Drawn -> ScriptResult.Returned(
                mapper.writeValueAsString(linkedMapOf("svg" to drawn.svg, "kind" to drawn.kind)),
                0,
            )
        }
    }

    /** The labelled numbers, or null where that is not what arrived. */
    private fun pointsIn(values: JsonNode): List<ChartRenderer.Point>? {
        if (!values.isObject || values.isEmpty) return null
        val points = values.properties().mapNotNull { (label, number) ->
            number.takeIf { it.isNumber }?.let { ChartRenderer.Point(label, it.doubleValue()) }
        }
        return points.takeIf { it.isNotEmpty() }
    }

    private fun refusal(said: String): String = mapper.writeValueAsString(mapOf("error" to said))

    private companion object {
        const val RENDER = "render"
        const val KIND = "kind"
        const val VALUES = "values"
        const val TITLE = "title"
        const val FORMAT = "format"
    }
}

/**
 * An argument as a graph wrote it, which is JSON, so a string arrives quoted.
 *
 * Shared by the two above rather than written twice; they take the same shape
 * of argument because a workflow passes every function's the same way.
 */
private fun unquoted(mapper: ObjectMapper, argument: String?): String? {
    val given = argument?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
    return runCatching { mapper.readTree(given) }.getOrNull()?.takeIf { it.isTextual }?.stringValue() ?: given
}
