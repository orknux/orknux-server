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
    /** How large the picture is drawn. Issue #529. */
    private val installation: io.mszymanski.orknux.server.attachment.InstallationSettings,
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
                "charts_render for those. Send the source alone: no code fence and no mermaid line on top. " +
                // A PDF, not a report: said of any report, an HTML page got mermaid source. Issue #555.
                "For a PDF, do not call this - write the source into a <pre class=\"mermaid\"> block and " +
                "pdf_fromHtml draws it. For an HTML page, draw it as svg and add it with scratchpad_append " +
                "(key) - a <pre class=\"mermaid\"> block draws only in a PDF. The Diagrams and charts skill " +
                "has the syntax and the rest.",
            params = listOf(
                EmbeddedParam(SOURCE, ValueType.STRING, "The diagram source.", required = true),
                EmbeddedParam(FORMAT, ValueType.STRING, "\"png\" (the default) or \"svg\". Either way the answer is a contentKey, not the picture."),
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
        if (wanted == "svg") return vectors(mapper, scratch, sessionId, "diagram", drawn.svg, drawn.kind)

        return when (val picture = svgs.png(drawn.svg, null, installation.drawingScale().toDouble())) {
            is SvgRenderer.Drawing.Refused -> refusal(picture.reason)
            is SvgRenderer.Drawing.Drawn ->
                drawing(mapper, scratch, sessionId, "diagram", picture.png, picture.width, picture.height, drawn.kind)
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
    private val scratch: LlmSessionStore,
    private val mapper: ObjectMapper,
    /** How large the picture is drawn. Issue #529. */
    private val installation: io.mszymanski.orknux.server.attachment.InstallationSettings,
) : EmbeddedCapability {

    override val key = "charts"
    override val name = "Charts"

    override fun tools(): List<EmbeddedTool> = listOf(
        EmbeddedTool(
            name = RENDER,
            summary = "Draws a bar, column, line, area, pie, donut or scatter chart.",
            description = "Draws a chart and answers it as a picture you can look at or send. $KIND is one " +
                "of ${ChartRenderer.Kind.offered()}; $VALUES is a label and a number each, like " +
                "{\"Rent\":45,\"Food\":30}. For a PDF, do not call this - write " +
                "<pre class=\"chart\">{\"kind\":\"pie\",\"values\":{…}}</pre> into the HTML and pdf_fromHtml " +
                "draws it. For an HTML page, draw it as svg and add it with scratchpad_append (key) - a " +
                "<pre class=\"chart\"> block draws only in a PDF. The Diagrams and charts skill has the rest.",
            params = listOf(
                EmbeddedParam(KIND, ValueType.STRING, "One of ${ChartRenderer.Kind.offered()}.", required = true),
                EmbeddedParam(VALUES, ValueType.MAP, "A label and a number each.", required = true),
                EmbeddedParam(TITLE, ValueType.STRING, "What the chart is called."),
                EmbeddedParam(FORMAT, ValueType.STRING, "\"png\" (the default) or \"svg\". Either way the answer is a contentKey, not the picture."),
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

        /*
         * An object, or a string holding one. Issue #537: every tool parameter
         * goes to the model typed as a string, so values arrived as the text
         * {"Rent":45,"Food":30} - exactly the example the refusal gave - and
         * was refused as not being an object. Session 513 sent the example back
         * word for word and got the same refusal, seven minutes of it.
         */
        val given = asked.path(VALUES).let { node ->
            if (node.isString) runCatching { mapper.readTree(node.stringValue()) }.getOrNull() ?: node else node
        }
        val points = pointsIn(given)
            ?: return refusal("Give $VALUES as a label and a number each, like {\"Rent\":45,\"Food\":30}.")
        val kind = asked.path(KIND).takeIf { it.isTextual }?.stringValue()?.trim().orEmpty()
        val title = asked.path(TITLE).takeIf { it.isTextual }?.stringValue()?.trim()?.ifEmpty { null }

        val drawn = when (val answered = charts.svg(kind.ifEmpty { "bar" }, points, title)) {
            is ChartRenderer.Drawing.Refused -> return refusal(answered.reason)
            is ChartRenderer.Drawing.Drawn -> answered
        }

        val wanted = asked.path(FORMAT).takeIf { it.isTextual }?.stringValue()?.trim()?.lowercase()?.ifEmpty { null }
        if (wanted == "svg") return vectors(mapper, scratch, sessionId, "chart", drawn.svg, drawn.kind)

        return when (val picture = svgs.png(drawn.svg, null, installation.drawingScale().toDouble())) {
            is SvgRenderer.Drawing.Refused -> refusal(picture.reason)
            is SvgRenderer.Drawing.Drawn ->
                drawing(mapper, scratch, sessionId, "chart", picture.png, picture.width, picture.height, drawn.kind)
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
 * A drawing, answered so it can be both seen and sent. Issue #513.
 *
 * The picture goes in the session store under a key and the key goes in the
 * answer beside the bytes. Both, deliberately:
 *
 *  - the bytes are what the round lifts out and shows the model, and are then
 *    taken back out of the answer, because base64 is the largest thing that can
 *    land in a context window and no model can read it;
 *  - the key is what survives that, and is what `slack_uploadBinary` and every
 *    other sender takes.
 *
 * Without the key a picture could be seen and not used. A run hit exactly that:
 * the answer came back `picture: ""` with `pictureBytes: 9051`, which reads as
 * a drawing that exists somewhere out of reach, and the agent called the tool
 * again looking for a key, reasoned about where one might be hiding, and gave
 * up. It had drawn the diagram correctly three times.
 *
 * Where there is no session there is nowhere to keep it, so the bytes are the
 * only copy and travel alone - which is the workflow case, and there is no
 * model there to spend a context window on.
 */
private fun drawing(
    mapper: ObjectMapper,
    scratch: LlmSessionStore,
    sessionId: Long?,
    named: String,
    png: ByteArray,
    width: Int,
    height: Int,
    kind: String,
): String {
    val base64 = Base64.getEncoder().encodeToString(png)
    val answer = linkedMapOf<String, Any?>(
        "picture" to base64,
        "pictureType" to "image/png",
        "width" to width,
        "height" to height,
        "kind" to kind,
    )
    if (sessionId != null) {
        val key = named + "." + java.lang.Long.toString(System.nanoTime(), 36)
        scratch.put(sessionId, key, mapper.writeValueAsString(base64), io.mszymanski.orknux.workflow.script.StoredKind("image/png", true))
        answer["contentKey"] = key
        // Where the rest is written down, found by the word this answer carries. Issue #558.
        answer["note"] = "Pass contentKey to whatever sends, uploads or saves a file. For an HTML page, a PDF " +
            "or an archive, call skill_search with contentKey: the skills say how a key is used in each."
    }
    return mapper.writeValueAsString(answer)
}

/**
 * An SVG drawing, kept under a key like a PNG. Issue #549.
 *
 * "svg" used to answer the SVG itself - fifteen thousand characters of markup
 * in the model's context, trimmed in its history, and useful only if it typed
 * all of it back into a page, which is what the output cap cuts off. What makes
 * bytes answers a key: the SVG is kept as base64 like every picture, so it
 * uploads, zips and goes into a PDF as <img src="key"> the same way. Where
 * there is no session - a workflow - there is nowhere to keep it, and the
 * markup is the answer as it was.
 */
private fun vectors(
    mapper: ObjectMapper,
    scratch: LlmSessionStore,
    sessionId: Long?,
    named: String,
    svg: String,
    kind: String,
): String {
    if (sessionId == null) return mapper.writeValueAsString(linkedMapOf("svg" to svg, "kind" to kind))
    val key = named + "." + java.lang.Long.toString(System.nanoTime(), 36) + ".svg"
    scratch.put(
        sessionId, key, mapper.writeValueAsString(Base64.getEncoder().encodeToString(svg.toByteArray())),
        io.mszymanski.orknux.workflow.script.StoredKind("image/svg+xml", true),
    )
    return mapper.writeValueAsString(
        linkedMapOf(
            "contentKey" to key,
            "pictureType" to "image/svg+xml",
            "kind" to kind,
            /*
             * And how it reaches a page, which the markup used to do by being
             * pasted. Issue #555: handed a key instead, a model writing an HTML
             * report put the mermaid source into the page, which draws nowhere
             * but in pdf_fromHtml.
             */
            "note" to "Pass contentKey to whatever sends, uploads or saves a file. To put the drawing into " +
                "an HTML page you are writing in a scratchpad, call scratchpad_append with this key as key, or " +
                "scratchpad_replace with it as newKey - the SVG goes in as markup and draws in any browser. " +
                "For pdf_fromHtml, <img src=\"" + key + "\"> works too.",
        ),
    )
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
