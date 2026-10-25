package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.design.WireframeAPI
import io.mszymanski.orknux.server.design.WireframeDocument
import io.mszymanski.orknux.server.design.WireframeDocumentInvalidException
import io.mszymanski.orknux.server.design.WireframeElementType
import io.mszymanski.orknux.server.design.WireframeRepository
import io.mszymanski.orknux.server.design.WireframeStyle
import io.mszymanski.orknux.server.design.WireframeSvg
import io.mszymanski.orknux.server.chat.AgentTools
import io.mszymanski.orknux.server.llm.LlmSessionStore
import io.mszymanski.orknux.server.plugin.SvgRenderer
import io.mszymanski.orknux.workflow.script.StoredKind
import tools.jackson.databind.node.ObjectNode
import java.util.Base64
import io.mszymanski.orknux.server.design.WireframeView
import io.mszymanski.orknux.server.security.InstallationAddress
import io.mszymanski.orknux.server.security.WebProperties
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * The orknux_* tools that read and draw wireframes. Issue #655.
 *
 * Offered wherever the rest of the surface is - an agent with Orknux access, an
 * MCP client, and the chat in the wireframe editor, which offers these and no
 * others. Every write goes through [WireframeAPI], so what a model draws is
 * checked, counted and audited as the editor's own save is, and the editor
 * draws it the moment it is taken up.
 *
 * The element vocabulary in the descriptions is read off [WireframeElementType],
 * which is also what the document is checked against, so the two cannot differ.
 */
@Service
class WireframeTools(
    private val wireframes: WireframeRepository,
    /** Lazy: the API holds the editor's chat, which reaches these tools through OrknuxTools. */
    @param:Lazy private val api: WireframeAPI,
    private val web: WebProperties,
    private val address: InstallationAddress,
    private val mapper: ObjectMapper,
    /** For the picture of what was drawn, put in the conversation beside the link. */
    private val svgs: SvgRenderer,
    /** Where that picture's SVG is kept, under a key, as every drawing in a session is. */
    private val scratch: LlmSessionStore,
    private val tuning: Tuning = Tuning.DEFAULTS,
) {

    private val document = WireframeDocument(mapper)

    private val pictures = WireframeSvg()

    fun specs(scope: OrknuxScope): List<ToolSpec> = buildList {
        add(
            ToolSpec(
                name = LIST,
                description = "The wireframes in this workspace - the mockups of screens, pages and forms drawn here - the one " +
                    "worked on last first: id, name, how many elements each holds, and where it opens.",
                parameters = emptyList(),
                summary = "Lists the workspace's wireframes (mockups)",
            ),
        )
        add(
            ToolSpec(
                name = READ,
                description = "One wireframe in full: every element with its id, type, position, size, text and props. " +
                    "Read it before changing it.",
                parameters = listOf(ToolParameterSpec(WIREFRAME, "The wireframe's name, or its id.", required = true)),
                summary = "Reads one wireframe (mockup) in full",
            ),
        )
        add(
            ToolSpec(
                name = HTML,
                description = "One wireframe written out as a single HTML page - styles inline, no script, each frame a screen " +
                    "with its elements placed where they were drawn, buttons, fields and pictures as real HTML elements. " +
                    "Answers the page and a link to download it; a page too long to hand over is answered with the link alone.",
                parameters = listOf(ToolParameterSpec(WIREFRAME, "The wireframe's name, or its id.", required = true)),
                summary = "A wireframe (mockup) as one HTML page",
            ),
        )
        if (!scope.mayWrite) return@buildList
        add(
            ToolSpec(
                name = CREATE,
                description = "Draws a mockup or wireframe of a screen, a page, a form or an app's UI - a sign-up form, a " +
                    "dashboard, a settings screen - as a new wireframe in this workspace, which a person can open and " +
                    "change in the wireframe editor. This is what to use when somebody asks for a mockup, a wireframe or " +
                    "a layout of an interface; a picture drawn in SVG is not one. Give it elements to draw it at once, " +
                    "or draw it afterwards. $ANSWERED $ELEMENTS_SAID",
                summary = "Draws a mockup of a screen or form",
                parameters = listOf(
                    ToolParameterSpec("name", "What it is called.", required = true),
                    ToolParameterSpec(ADD, "The elements to start it with. $ELEMENT_SAID", required = false, schema = ELEMENT_LIST),
                ),
            ),
        )
        add(
            ToolSpec(
                name = CHANGE,
                description = "Changes a wireframe (a mockup of a screen or form) in one save: adds elements, changes some " +
                    "by id, and removes some by id, leaving everything else where it is. $ANSWERED $ELEMENTS_SAID",
                summary = "Changes part of a wireframe (mockup)",
                parameters = listOf(
                    ToolParameterSpec(WIREFRAME, "The wireframe's name, or its id.", required = true),
                    ToolParameterSpec(ADD, "Elements to add. $ELEMENT_SAID", required = false, schema = ELEMENT_LIST),
                    ToolParameterSpec(
                        UPDATE,
                        "Elements to change, each with its id and only the fields that change; props are merged, a null prop removed.",
                        required = false,
                        schema = ELEMENT_LIST,
                    ),
                    ToolParameterSpec(
                        REMOVE,
                        "The ids of elements to take away.",
                        required = false,
                        schema = mapOf("type" to "array", "items" to mapOf("type" to "string")),
                    ),
                ),
            ),
        )
        add(
            ToolSpec(
                name = DRAW,
                description = "Replaces everything on a wireframe (a mockup of a screen or form) with the elements given. " +
                    "Use it to start a drawing over; to change part of one, use $CHANGE. $ANSWERED $ELEMENTS_SAID",
                summary = "Redraws a whole wireframe (mockup)",
                parameters = listOf(
                    ToolParameterSpec(WIREFRAME, "The wireframe's name, or its id.", required = true),
                    ToolParameterSpec(ELEMENTS, "Every element the wireframe should hold. $ELEMENT_SAID", required = true, schema = ELEMENT_LIST),
                ),
            ),
        )
    }

    fun handles(name: String): Boolean = name in NAMES

    fun run(scope: OrknuxScope, name: String, arguments: String): String {
        val asked = runCatching { mapper.readTree(arguments.ifBlank { "{}" }) }.getOrNull()
            ?: return refuse("The arguments are not JSON")
        return try {
            when (name) {
                LIST -> list(scope)
                READ -> withWireframe(scope, asked) { shown(scope, it) }
                HTML -> withWireframe(scope, asked) { html(it) }
                else -> if (!scope.mayWrite) refuse("This conversation may read what is here, but not change it") else when (name) {
                    CREATE -> create(scope, asked)
                    CHANGE -> withWireframe(scope, asked) { change(scope, it, asked) }
                    DRAW -> withWireframe(scope, asked) { held ->
                        val elements = list(asked.path(ELEMENTS)) ?: return@withWireframe refuse("Give $ELEMENTS, as a list.")
                        drawn(scope, api.saveElements(held.id, document.elements(elements)))
                    }
                    else -> refuse("There is no tool called $name")
                }
            }
        } catch (refused: WireframeDocumentInvalidException) {
            refuse(refused.message ?: "That cannot be drawn")
        }
    }

    private fun list(scope: OrknuxScope): String = mapper.writeValueAsString(
        mapOf(
            "wireframes" to wireframes.findByWorkspaceIdOrderByUpdatedAtDescIdDesc(scope.workspaceId).map { held ->
                val view = api.view(held)
                mapOf("id" to view.id, "name" to view.name, "elements" to view.elementCount, "updatedAt" to view.updatedAt, "url" to link(view))
            },
        ),
    )

    private fun create(scope: OrknuxScope, asked: JsonNode): String {
        val name = text(asked.path("name")) ?: return refuse("What is it called? Give it a name.")
        val elements = list(asked.path(ADD))?.let(document::elements).orEmpty()
        val made = api.createWireframe(scope.workspaceId, name, document.write(elements))
        return drawn(scope, made)
    }

    private fun change(scope: OrknuxScope, held: WireframeView, asked: JsonNode): String {
        val before = document.stored(held.document)
        val gone = list(asked.path(REMOVE))?.let { ids -> (0 until ids.size()).mapNotNull { text(ids.get(it)) }.toSet() }.orEmpty()
        val unknown = gone - before.map { it.id }.toSet()
        if (unknown.isNotEmpty()) return refuse("${held.name} has no element ${unknown.joinToString(", ")}; read it with $READ")

        val changes = list(asked.path(UPDATE))
        val byId = mutableMapOf<String, JsonNode>()
        if (changes != null) {
            for (at in 0 until changes.size()) {
                val change = changes.get(at)
                val id = text(change.path("id")) ?: return refuse("Each element in $UPDATE needs the id of the one it changes")
                if (before.none { it.id == id }) return refuse("${held.name} has no element $id; read it with $READ")
                byId[id] = change
            }
        }
        val kept = before.filter { it.id !in gone }
        val ids = kept.map { it.id }.toSet()
        val changed = kept.map { element ->
            byId[element.id]?.let { document.changed(element, it, ids - element.id) } ?: element
        }
        val added = list(asked.path(ADD))?.let { more ->
            val taken = ids.toMutableSet()
            (0 until more.size()).map { at ->
                document.element(more.get(at), changed.size + at, taken).also { taken += it.id }
            }
        }.orEmpty()
        if (gone.isEmpty() && byId.isEmpty() && added.isEmpty()) return refuse("Nothing to change: give $ADD, $UPDATE or $REMOVE")
        return drawn(scope, api.saveElements(held.id, changed + added))
    }

    /**
     * The page, and where to download it. The page itself is text a model can
     * use - put in a repository, saved as an artifact, read for its structure -
     * so it is handed over while it is short enough to read; the link is for
     * the person, and is all there is past the Tunable.
     */
    private fun html(view: WireframeView): String {
        val page = api.html(view.id)
        val most = tuning[Tunable.WIREFRAME_HTML_INLINE_CHARACTERS]
        return mapper.writeValueAsString(
            buildMap {
                put("id", view.id)
                put("name", view.name)
                put("url", link(view))
                put("download", base() + "/api/wireframes/${view.id}/html")
                put("characters", page.html.length)
                if (page.html.length <= most) {
                    put("html", page.html)
                } else {
                    put("note", "The page is ${page.html.length} characters, past the $most this installation hands to a model; give the person the download link.")
                }
            },
        )
    }

    private fun shown(scope: OrknuxScope, view: WireframeView): String = mapper.writeValueAsString(
        mapOf(
            "id" to view.id,
            "name" to view.name,
            "url" to link(view),
            "revision" to view.revision,
            "elements" to document.stored(view.document).map(document::shown),
        ),
    )

    /**
     * What a drawing tool answers: the wireframe as [shown] has it, and a
     * picture of it where the call came from a conversation.
     *
     * Asked for after chat 974, where an agent drew a sign-up form properly and
     * answered with the link and a paragraph about it: the person wanted to see
     * the mockup where they had asked for it. The picture goes in the answer's
     * `picture`, which the round lifts out and puts in the conversation above
     * the agent's answer - as every drawn picture is - so the model only has to
     * add the link. A PNG, because that is what a chat and a model can both look
     * at; the SVG it was drawn from is kept under `contentKey`, for a model that
     * is asked for the file. Through the MCP endpoint and in the editor's own
     * chat there is no conversation to put it in, and the editor is already
     * showing it, so the answer is as it was.
     */
    private fun drawn(scope: OrknuxScope, view: WireframeView): String {
        val answer = shown(scope, view)
        val session = scope.session ?: return answer
        val svg = runCatching { pictures.drawing(document.stored(view.document)) }.getOrNull() ?: return answer
        val natural = svg.substringAfter("width=\"").substringBefore('"').toIntOrNull() ?: return answer
        // The Tunable stops at the renderer's own ceiling, 4096 pixels.
        val width = minOf(natural * 2, tuning[Tunable.WIREFRAME_PICTURE_PIXELS])
        val png = when (val made = svgs.png(svg, width)) {
            is SvgRenderer.Drawing.Drawn -> made
            is SvgRenderer.Drawing.Refused -> return answer
        }
        val key = "wireframe." + view.id + "." + java.lang.Long.toString(System.nanoTime(), 36) + ".svg"
        scratch.put(
            session, key, mapper.writeValueAsString(Base64.getEncoder().encodeToString(svg.toByteArray())),
            StoredKind("image/svg+xml", true),
        )
        val tree = mapper.readTree(answer) as ObjectNode
        tree.put(AgentTools.PICTURE, Base64.getEncoder().encodeToString(png.png))
        tree.put(AgentTools.PICTURE_TYPE, "image/png")
        // One picture per wireframe per turn, its latest: a create and a change in one turn are one mockup. Chat 979.
        tree.put(AgentTools.PICTURE_KEY, "wireframe:" + view.id)
        tree.put("contentKey", key)
        tree.put(
            "note",
            "A picture of the wireframe goes into the conversation with this answer. In your answer give the " +
                "person the url, which opens it in the wireframe editor; contentKey holds the picture as SVG, " +
                "for whatever sends or saves a file.",
        )
        return mapper.writeValueAsString(tree)
    }

    private fun withWireframe(scope: OrknuxScope, asked: JsonNode, then: (WireframeView) -> String): String {
        val named = text(asked.path(WIREFRAME)) ?: return refuse("Which wireframe? Give its name or id; $LIST lists them.")
        val matches = wireframes.findByWorkspaceIdOrderByUpdatedAtDescIdDesc(scope.workspaceId)
            .filter { it.name.equals(named, ignoreCase = true) || it.id?.toString() == named }
        return when {
            matches.isEmpty() -> refuse("There is no wireframe called $named here; $LIST lists them")
            matches.size > 1 -> refuse("More than one wireframe is called $named; use its id")
            else -> then(api.view(matches.single()))
        }
    }

    /** A list as given, or as the text of one - a model will send either. */
    private fun list(node: JsonNode): JsonNode? = when {
        node.isArray -> node
        node.isString -> runCatching { mapper.readTree(node.stringValue()) }.getOrNull()?.takeIf { it.isArray }
            ?: throw WireframeDocumentInvalidException("a list was expected and got text")
        else -> null
    }

    private fun text(node: JsonNode): String? = when {
        node.isString -> node.stringValue()?.trim()?.takeIf { it.isNotEmpty() }
        node.isNumber -> node.asText()
        else -> null
    }

    private fun link(view: WireframeView): String = base() + "/workspace/${view.workspaceId}/design/wireframes/${view.id}"

    private fun base(): String = address.base().ifEmpty { web.allowedOrigins.firstOrNull()?.trimEnd('/').orEmpty() }

    private fun refuse(says: String): String = mapper.writeValueAsString(mapOf("error" to says))

    companion object {
        const val LIST = "orknux_wireframes"
        const val READ = "orknux_wireframe"
        const val CREATE = "orknux_create_wireframe"
        const val CHANGE = "orknux_change_wireframe"
        const val DRAW = "orknux_draw_wireframe"
        const val HTML = "orknux_wireframe_html"

        /** What the editor's own chat is offered: drawing in the wireframe it has open. */
        val DRAWING = setOf(READ, CHANGE, DRAW)

        val NAMES = setOf(LIST, READ, HTML, CREATE, CHANGE, DRAW)

        private const val WIREFRAME = "wireframe"
        private const val ELEMENTS = "elements"
        private const val ADD = "add"
        private const val UPDATE = "update"
        private const val REMOVE = "remove"

        /** The kinds there are, from the enum the document is checked against. */
        private val ELEMENTS_SAID: String =
            "Element types: " + WireframeElementType.entries.joinToString("; ") { "${it.key} - ${it.said}" } + ". " + WireframeStyle.SAID

        /** What the drawing tools answer, said in each so a model knows to hand the link on. */
        private const val ANSWERED =
            "Answers the wireframe with its url - where it opens in the editor, to give the person - and, in a " +
                "conversation, puts a picture of it there."

        private const val ELEMENT_SAID =
            "Each is {id, type, x, y, w, h, text, props}: whole pixels from the top left, id optional on a new one."

        private val ELEMENT_LIST: Map<String, Any?> = mapOf(
            "type" to "array",
            "items" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "id" to mapOf("type" to "string"),
                    "type" to mapOf("type" to "string", "enum" to WireframeElementType.entries.map { it.key }),
                    "x" to mapOf("type" to "number"),
                    "y" to mapOf("type" to "number"),
                    "w" to mapOf("type" to "number"),
                    "h" to mapOf("type" to "number"),
                    "text" to mapOf("type" to "string"),
                    "props" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            WireframeStyle.FILL to mapOf("type" to "string"),
                            WireframeStyle.COLOR to mapOf("type" to "string"),
                            WireframeStyle.BORDER_WIDTH to mapOf("type" to "number"),
                            WireframeStyle.BORDER_STYLE to mapOf("type" to "string", "enum" to WireframeStyle.BorderStyle.entries.map { it.key }),
                            WireframeStyle.BORDER_COLOR to mapOf("type" to "string"),
                            WireframeStyle.RADIUS to mapOf("type" to "number"),
                            WireframeStyle.SHADOW to mapOf("type" to "string", "enum" to WireframeStyle.Shadow.entries.map { it.key }),
                            WireframeStyle.CHECKED to mapOf("type" to "boolean"),
                        ),
                    ),
                ),
            ),
        )
    }
}
