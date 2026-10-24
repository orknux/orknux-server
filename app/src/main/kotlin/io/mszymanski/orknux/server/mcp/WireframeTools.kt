package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.design.WireframeAPI
import io.mszymanski.orknux.server.design.WireframeDocument
import io.mszymanski.orknux.server.design.WireframeDocumentInvalidException
import io.mszymanski.orknux.server.design.WireframeElementType
import io.mszymanski.orknux.server.design.WireframeRepository
import io.mszymanski.orknux.server.design.WireframeView
import io.mszymanski.orknux.server.security.InstallationAddress
import io.mszymanski.orknux.server.security.WebProperties
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
) {

    private val document = WireframeDocument(mapper)

    fun specs(scope: OrknuxScope): List<ToolSpec> = buildList {
        add(
            ToolSpec(
                name = LIST,
                description = "The wireframes in this workspace, the one worked on last first: id, name, how many elements each holds, and where it opens.",
                parameters = emptyList(),
            ),
        )
        add(
            ToolSpec(
                name = READ,
                description = "One wireframe in full: every element with its id, type, position, size, text and props. " +
                    "Read it before changing it.",
                parameters = listOf(ToolParameterSpec(WIREFRAME, "The wireframe's name, or its id.", required = true)),
            ),
        )
        if (!scope.mayWrite) return@buildList
        add(
            ToolSpec(
                name = CREATE,
                description = "Makes a new wireframe in this workspace and answers its id and where it opens. " +
                    "Give it elements to draw it at once, or draw it afterwards. $ELEMENTS_SAID",
                parameters = listOf(
                    ToolParameterSpec("name", "What it is called.", required = true),
                    ToolParameterSpec(ADD, "The elements to start it with. $ELEMENT_SAID", required = false, schema = ELEMENT_LIST),
                ),
            ),
        )
        add(
            ToolSpec(
                name = CHANGE,
                description = "Changes a wireframe in one save: adds elements, changes some by id, and removes some by id, " +
                    "leaving everything else where it is. $ELEMENTS_SAID",
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
                description = "Replaces everything on a wireframe with the elements given. Use it to start a drawing over; " +
                    "to change part of one, use $CHANGE. $ELEMENTS_SAID",
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
                else -> if (!scope.mayWrite) refuse("This conversation may read what is here, but not change it") else when (name) {
                    CREATE -> create(scope, asked)
                    CHANGE -> withWireframe(scope, asked) { change(scope, it, asked) }
                    DRAW -> withWireframe(scope, asked) { held ->
                        val elements = list(asked.path(ELEMENTS)) ?: return@withWireframe refuse("Give $ELEMENTS, as a list.")
                        shown(scope, api.saveElements(held.id, document.elements(elements)))
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
        return shown(scope, made)
    }

    private fun change(scope: OrknuxScope, held: WireframeView, asked: JsonNode): String {
        val before = document.read(held.document)
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
        return shown(scope, api.saveElements(held.id, changed + added))
    }

    private fun shown(scope: OrknuxScope, view: WireframeView): String = mapper.writeValueAsString(
        mapOf(
            "id" to view.id,
            "name" to view.name,
            "url" to link(view),
            "revision" to view.revision,
            "elements" to document.read(view.document).map(document::shown),
        ),
    )

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

    private fun link(view: WireframeView): String =
        address.base().ifEmpty { web.allowedOrigins.firstOrNull()?.trimEnd('/').orEmpty() } +
            "/workspace/${view.workspaceId}/design/wireframes/${view.id}"

    private fun refuse(says: String): String = mapper.writeValueAsString(mapOf("error" to says))

    companion object {
        const val LIST = "orknux_wireframes"
        const val READ = "orknux_wireframe"
        const val CREATE = "orknux_create_wireframe"
        const val CHANGE = "orknux_change_wireframe"
        const val DRAW = "orknux_draw_wireframe"

        /** What the editor's own chat is offered: drawing in the wireframe it has open. */
        val DRAWING = setOf(READ, CHANGE, DRAW)

        val NAMES = setOf(LIST, READ, CREATE, CHANGE, DRAW)

        private const val WIREFRAME = "wireframe"
        private const val ELEMENTS = "elements"
        private const val ADD = "add"
        private const val UPDATE = "update"
        private const val REMOVE = "remove"

        /** The kinds there are, from the enum the document is checked against. */
        private val ELEMENTS_SAID: String =
            "Element types: " + WireframeElementType.entries.joinToString("; ") { "${it.key} - ${it.said}" } + "."

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
                    "props" to mapOf("type" to "object"),
                ),
            ),
        )
    }
}
