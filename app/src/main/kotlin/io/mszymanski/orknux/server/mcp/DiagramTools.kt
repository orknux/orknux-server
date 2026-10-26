package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.chat.AgentTools
import io.mszymanski.orknux.server.chat.ChatFileTools
import io.mszymanski.orknux.server.diagram.Diagram
import io.mszymanski.orknux.server.diagram.DiagramAPI
import io.mszymanski.orknux.server.diagram.DiagramAgentAccess
import io.mszymanski.orknux.server.diagram.DiagramDocumentInvalidException
import io.mszymanski.orknux.server.diagram.DiagramDrawing
import io.mszymanski.orknux.server.diagram.DiagramEdgeKind
import io.mszymanski.orknux.server.diagram.DiagramEndStyle
import io.mszymanski.orknux.server.diagram.DiagramExport
import io.mszymanski.orknux.server.diagram.DiagramExportAPI
import io.mszymanski.orknux.server.diagram.DiagramFormat
import io.mszymanski.orknux.server.diagram.DiagramLineStyle
import io.mszymanski.orknux.server.diagram.DiagramLink
import io.mszymanski.orknux.server.diagram.DiagramNodeKind
import io.mszymanski.orknux.server.diagram.DiagramReadOnlyException
import io.mszymanski.orknux.server.diagram.DiagramRepository
import io.mszymanski.orknux.server.diagram.DiagramSearch
import io.mszymanski.orknux.server.diagram.DiagramShape
import io.mszymanski.orknux.server.diagram.DiagramView
import io.mszymanski.orknux.server.llm.LlmSessionStore
import io.mszymanski.orknux.server.plugin.SvgRenderer
import io.mszymanski.orknux.server.security.InstallationAddress
import io.mszymanski.orknux.server.security.WebProperties
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.workflow.script.StoredKind
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.util.Base64

/**
 * The orknux_* tools that search, read, draw, export and delete diagrams.
 *
 * Offered wherever the rest of the surface is - an agent with Orknux access, an
 * MCP client - and, the editing ones, to the chat in the diagram editor. Every
 * write goes through [DiagramAPI], so what a model draws is checked, held to the
 * note cap and audited as the editor's own save is.
 *
 * Two things hold an agent back here that do not hold a person. A diagram set
 * to Read only for agents is refused to [CHANGE] and [DELETE] - said before it
 * tries, in what [LIST] and [READ] answer. And [DELETE] starts hidden on every
 * agent (see [OrknuxTools.STARTS_HIDDEN]): taking a diagram away loses what
 * people drew, and an owner turns it on per agent. The editor's own chat passes
 * `personAsking`, because a person is there asking for the change.
 *
 * The vocabulary in the descriptions is read off [DiagramNodeKind],
 * [DiagramLineStyle] and [DiagramEndStyle], which the drawing is checked
 * against, so the two cannot differ. [DiagramEdgeKind] is still taken, as a
 * shorthand for all three.
 */
@Service
class DiagramTools(
    private val diagrams: DiagramRepository,
    /** Lazy: the API holds the editor's chat, which reaches these tools. */
    @param:Lazy private val api: DiagramAPI,
    private val search: DiagramSearch,
    private val export: DiagramExport,
    private val web: WebProperties,
    private val address: InstallationAddress,
    private val mapper: ObjectMapper,
    /** Where the chat picture's PNG and an export are kept, under a key, as every file in a session is. */
    private val scratch: LlmSessionStore,
    private val tuning: Tuning = Tuning.DEFAULTS,
) {

    fun specs(scope: OrknuxScope): List<ToolSpec> = buildList {
        add(
            ToolSpec(
                name = LIST,
                description = "The diagrams in this workspace - architecture, systems, processes and how they connect - by " +
                    "catalog: id, name, $DESCRIPTION - what each is for, to pick the right one by - how many nodes and " +
                    "connections each holds, whether agents may change it ($AGENTS_SAID), and where it opens.",
                parameters = listOf(ToolParameterSpec(CATALOG, "Only this catalog: its name or id.", required = false)),
                summary = "Lists the workspace's diagrams by catalog",
            ),
        )
        add(
            ToolSpec(
                name = SEARCH,
                description = "Searches the diagrams for words: node labels, node notes, connection labels and diagram " +
                    "names and descriptions, best match first. Across every diagram, or within one. Answers the diagram, the node and the " +
                    "words around the match; read a node that looks relevant with $NODE.",
                parameters = listOf(
                    ToolParameterSpec(QUERY, "The words to look for.", required = true),
                    ToolParameterSpec(DIAGRAM, "Only this diagram: its name or id.", required = false),
                ),
                summary = "Searches diagrams' nodes, notes and connections",
            ),
        )
        add(
            ToolSpec(
                name = READ,
                description = "One diagram in full: its $DESCRIPTION, every node with its id, kind, label, position, size, colour, group and " +
                    "the start of its notes, and every connection with its ends, label and kind. Read it before changing it.",
                parameters = listOf(ToolParameterSpec(DIAGRAM, "The diagram's name, or its id.", required = true)),
                summary = "Reads one diagram",
            ),
        )
        add(
            ToolSpec(
                name = NODE,
                description = "One node of a diagram in full: its notes whole, the group it sits in, what sits in it, and " +
                    "every connection to and from it with the label and the node at the other end.",
                parameters = listOf(
                    ToolParameterSpec(DIAGRAM, "The diagram's name, or its id.", required = true),
                    ToolParameterSpec(NODE_ARG, "The node's id, or its label.", required = true),
                ),
                summary = "Reads one diagram node with its notes and connections",
            ),
        )
        add(
            ToolSpec(
                name = EXPORT,
                description = "Writes a diagram out as a file: png, svg or pdf to look at, mermaid or json to put somewhere " +
                    "else. Answers a download link; in a conversation the file is also kept under contentKey, which " +
                    "whatever attaches or saves a file takes.",
                parameters = listOf(
                    ToolParameterSpec(DIAGRAM, "The diagram's name, or its id.", required = true),
                    ToolParameterSpec(
                        FORMAT,
                        "One of ${DiagramFormat.entries.joinToString(", ") { it.key }}.",
                        required = true,
                        schema = mapOf("type" to "string", "enum" to DiagramFormat.entries.map { it.key }),
                    ),
                ),
                summary = "Exports a diagram as PNG, SVG, PDF, Mermaid or JSON",
            ),
        )
        if (!scope.mayWrite) return@buildList
        add(
            ToolSpec(
                name = CREATE,
                description = "Draws a new diagram in this workspace - of systems and how they talk, a process, an " +
                    "architecture - which a person can open and change in the diagram editor. Give it nodes and edges " +
                    "to draw it at once. $ANSWERED $SHAPES_SAID",
                summary = "Draws a new diagram",
                parameters = listOf(
                    ToolParameterSpec("name", "What it is called.", required = true),
                    ToolParameterSpec(DESCRIPTION, "What it is for, in a sentence or two, so the next reader picks the right diagram.", required = false),
                    ToolParameterSpec(CATALOG, "The catalog to put it in: its name or id. The first catalog when left out.", required = false),
                    ToolParameterSpec(NODES, "The nodes. $NODE_SAID", required = false, schema = NODE_LIST),
                    ToolParameterSpec(EDGES, "The connections. $EDGE_SAID", required = false, schema = EDGE_LIST),
                    AgentTools.SHOW_PARAM,
                ),
            ),
        )
        add(
            ToolSpec(
                name = CHANGE,
                description = "Changes a diagram in one save: adds, changes and removes nodes and connections by id, " +
                    "leaving everything else where it is. Removing a node removes its connections. Refused on a diagram " +
                    "that is read only for agents. $ANSWERED $SHAPES_SAID",
                summary = "Changes part of a diagram",
                parameters = listOf(
                    ToolParameterSpec(DIAGRAM, "The diagram's name, or its id.", required = true),
                    ToolParameterSpec(DESCRIPTION, "A new description of what the diagram is for; empty takes it away.", required = false),
                    ToolParameterSpec(ADD_NODES, "Nodes to add. $NODE_SAID", required = false, schema = NODE_LIST),
                    ToolParameterSpec(UPDATE_NODES, "Nodes to change, each with its id and only the fields that change; a null clears notes, colour or parent.", required = false, schema = NODE_LIST),
                    ToolParameterSpec(REMOVE_NODES, "The ids of nodes to take away.", required = false, schema = IDS),
                    ToolParameterSpec(ADD_EDGES, "Connections to add. $EDGE_SAID", required = false, schema = EDGE_LIST),
                    ToolParameterSpec(UPDATE_EDGES, "Connections to change, each with its id and only the fields that change.", required = false, schema = EDGE_LIST),
                    ToolParameterSpec(REMOVE_EDGES, "The ids of connections to take away.", required = false, schema = IDS),
                    AgentTools.SHOW_PARAM,
                ),
            ),
        )
        add(
            ToolSpec(
                name = DELETE,
                description = "Deletes a diagram, with everything on it. It cannot be undone. Refused on a diagram that " +
                    "is read only for agents.",
                summary = "Deletes a diagram",
                parameters = listOf(ToolParameterSpec(DIAGRAM, "The diagram's name, or its id.", required = true)),
            ),
        )
    }

    fun handles(name: String): Boolean = name in NAMES

    /**
     * One call. [personAsking] is the editor's chat: a person is there asking,
     * so a diagram's Read only for agents does not stand in their way.
     */
    fun run(scope: OrknuxScope, name: String, arguments: String, personAsking: Boolean = false): String {
        val asked = runCatching { mapper.readTree(arguments.ifBlank { "{}" }) }.getOrNull()
            ?: return refuse("The arguments are not JSON")
        return try {
            when (name) {
                LIST -> list(scope, asked)
                SEARCH -> search(scope, asked)
                READ -> withDiagram(scope, asked) { read(it) }
                NODE -> withDiagram(scope, asked) { node(it, asked) }
                EXPORT -> withDiagram(scope, asked) { export(scope, it, asked) }
                else -> if (!scope.mayWrite) refuse("This conversation may read what is here, but not change it") else when (name) {
                    CREATE -> create(scope, asked)
                    CHANGE -> withDiagram(scope, asked) { held ->
                        if (!personAsking) api.requireAgentMayChange(held)
                        change(scope, held, asked)
                    }
                    DELETE -> withDiagram(scope, asked) { held ->
                        if (!personAsking) api.requireAgentMayChange(held)
                        api.deleteDiagram(requireNotNull(held.id))
                        mapper.writeValueAsString(mapOf("deleted" to held.name, "id" to held.id))
                    }
                    else -> refuse("There is no tool called $name")
                }
            }
        } catch (refused: DiagramDocumentInvalidException) {
            refuse(refused.message ?: "That cannot be drawn")
        } catch (refused: DiagramReadOnlyException) {
            mapper.writeValueAsString(mapOf("error" to refused.message, "code" to "DiagramReadOnly"))
        }
    }

    private fun list(scope: OrknuxScope, asked: JsonNode): String {
        val only = text(asked.path(CATALOG))?.let { named ->
            api.catalogNamed(scope.workspaceId, named) ?: return refuse("There is no diagram catalog called $named here")
        }
        val all = api.diagrams(scope.workspaceId, only?.id)
        val catalogs = api.diagramCatalogs(scope.workspaceId).filter { only == null || it.id == only.id }
        return mapper.writeValueAsString(
            mapOf(
                "catalogs" to catalogs.map { catalog ->
                    mapOf(
                        "id" to catalog.id,
                        "name" to catalog.name,
                        "diagrams" to all.filter { it.catalogId == catalog.id }.map { view ->
                            mapOf(
                                "id" to view.id,
                                "name" to view.name,
                                DESCRIPTION to view.description,
                                "nodes" to view.nodeCount,
                                "edges" to view.edgeCount,
                                AGENTS to said(view.agentAccess),
                                "updatedAt" to view.updatedAt,
                                "url" to link(view.workspaceId, view.id),
                            )
                        },
                    )
                },
            ),
        )
    }

    private fun search(scope: OrknuxScope, asked: JsonNode): String {
        val query = text(asked.path(QUERY)) ?: return refuse("What should be looked for? Give $QUERY.")
        val within = text(asked.path(DIAGRAM))?.let { named -> one(scope, named) ?: return refuse("There is no diagram called $named here; $LIST lists them") }
        val hits = search.search(scope.workspaceId, query, within?.id, tuning[Tunable.DIAGRAM_SEARCH_HITS])
        return mapper.writeValueAsString(
            mapOf(
                "query" to query,
                "hits" to hits.map { hit ->
                    buildMap {
                        put("diagram", mapOf("id" to hit.diagramId, "name" to hit.diagramName, "catalog" to hit.catalogName))
                        put("found", hit.field)
                        hit.nodeId?.let { put("node", mapOf("id" to it, "label" to hit.nodeLabel)) }
                        hit.edgeId?.let { put("edge", it) }
                        put("snippet", hit.snippet)
                        put("url", link(scope.workspaceId, hit.diagramId, hit.nodeId))
                    }
                },
                "note" to if (hits.isEmpty()) "Nothing matched; try fewer or other words." else "Read a node with $NODE for its notes and connections.",
            ),
        )
    }

    private fun read(held: Diagram): String {
        val view = api.view(held)
        val drawing = api.drawing(view.id)
        val upTo = tuning[Tunable.DIAGRAM_NOTES_SHOWN_CHARACTERS]
        return mapper.writeValueAsString(
            mapOf(
                "id" to view.id,
                "name" to view.name,
                DESCRIPTION to view.description,
                "catalog" to view.catalogName,
                AGENTS to said(view.agentAccess),
                "revision" to view.revision,
                "url" to link(view.workspaceId, view.id),
                "nodes" to drawing.nodes.map { api.document.shown(it, upTo) },
                "edges" to drawing.edges.map { api.document.shown(it) },
            ),
        )
    }

    private fun node(held: Diagram, asked: JsonNode): String {
        val named = text(asked.path(NODE_ARG)) ?: return refuse("Which node? Give its id or label; $READ lists them.")
        val drawing = api.drawing(requireNotNull(held.id))
        val matches = drawing.nodes.filter { it.id == named }.ifEmpty { drawing.nodes.filter { it.label.equals(named, ignoreCase = true) } }
        val node = when {
            matches.isEmpty() -> return refuse("${held.name} has no node $named; $READ lists them")
            matches.size > 1 -> return refuse("More than one node on ${held.name} is labelled $named: ${matches.joinToString(", ") { it.id }}; use its id")
            else -> matches.single()
        }
        val byId = drawing.nodes.associateBy { it.id }
        fun brief(other: DiagramShape) = mapOf("id" to other.id, "label" to other.label, "kind" to other.kind.key)
        val connections = drawing.edges.filter { it.from == node.id || it.to == node.id }.map { edge ->
            val outgoing = edge.from == node.id
            val other = byId.getValue(if (outgoing) edge.to else edge.from)
            buildMap {
                put("edge", edge.id)
                put("direction", if (edge.from == edge.to) "self" else if (outgoing) "out" else "in")
                put("kind", edge.kind.key)
                put("line", edge.line.key)
                put("start", edge.start.key)
                put("end", edge.end.key)
                edge.label?.let { put("label", it) }
                put("node", brief(other))
            }
        }
        return mapper.writeValueAsString(
            buildMap {
                put("diagram", mapOf("id" to held.id, "name" to held.name, AGENTS to said(held.agentAccess)))
                putAll(api.document.shown(node))
                node.parent?.let { put("group", byId[it]?.let(::brief)) }
                val inside = drawing.nodes.filter { it.parent == node.id }
                if (inside.isNotEmpty()) put("contains", inside.map(::brief))
                put("connections", connections)
                put("url", link(held.workspaceId, requireNotNull(held.id), node.id))
            },
        )
    }

    /**
     * The file, and where to download it. In a conversation the file goes in the
     * session's store under a key with what it is, because that key is what every
     * tool that sends or saves a file takes - a link pasted into a chat is not a
     * file. Through the MCP endpoint there is no store, and the link is the answer.
     */
    private fun export(scope: OrknuxScope, held: Diagram, asked: JsonNode): String {
        val format = DiagramFormat.byKey(text(asked.path(FORMAT)))
            ?: return refuse("Give $FORMAT: one of ${DiagramFormat.entries.joinToString(", ") { it.key }}")
        val id = requireNotNull(held.id)
        val file = when (val made = export.export(api.drawing(id), format)) {
            is DiagramExport.Made.Written -> made.file
            is DiagramExport.Made.Refused -> return refuse("It could not be drawn: ${made.reason}")
        }
        val answer = linkedMapOf<String, Any?>(
            "id" to id,
            "name" to held.name,
            "format" to format.key,
            "contentType" to format.contentType,
            "fileName" to DiagramExportAPI.fileName(held.name, format.extension),
            "bytes" to file.bytes.size,
            "download" to base() + "/api/diagrams/$id/export/${format.key}",
        )
        scope.session?.let { session ->
            val key = "diagram." + id + "." + java.lang.Long.toString(System.nanoTime(), 36) + "." + format.extension
            val value = if (format.text) file.text else Base64.getEncoder().encodeToString(file.bytes)
            val failed = scratch.put(session, key, mapper.writeValueAsString(value), StoredKind(format.contentType, !format.text))
            if (failed == null) {
                answer["contentKey"] = key
                answer["note"] = "The file is kept under contentKey: hand that key to ${ChatFileTools.ATTACH} to send it in " +
                    "this chat, or to ${AgentTools.SAVE_ARTIFACT} to keep it. Give the person the download link."
            } else {
                answer["note"] = "The file could not be kept in this conversation ($failed); give the person the download link."
            }
        }
        return mapper.writeValueAsString(answer)
    }

    private fun create(scope: OrknuxScope, asked: JsonNode): String {
        val name = text(asked.path("name")) ?: return refuse("What is it called? Give it a name.")
        val catalog = text(asked.path(CATALOG))?.let { named ->
            api.catalogNamed(scope.workspaceId, named) ?: return refuse("There is no diagram catalog called $named here; $LIST lists them")
        }
        val nodes = list(asked.path(NODES))?.let(api.document::nodes).orEmpty()
        val edges = list(asked.path(EDGES))?.let { api.document.edges(it, nodes) }.orEmpty()
        val made = api.create(scope.workspaceId, name, catalog?.id, api.document.checked(DiagramDrawing(nodes, edges)), text(asked.path(DESCRIPTION)))
        return drawn(scope, made, asked)
    }

    private fun change(scope: OrknuxScope, held: Diagram, asked: JsonNode): String {
        val id = requireNotNull(held.id)
        val before = api.drawing(id)
        val goneNodes = ids(asked.path(REMOVE_NODES))
        val goneEdges = ids(asked.path(REMOVE_EDGES))
        (goneNodes - before.nodes.map { it.id }.toSet()).takeIf { it.isNotEmpty() }?.let {
            return refuse("${held.name} has no node ${it.joinToString(", ")}; read it with $READ")
        }
        (goneEdges - before.edges.map { it.id }.toSet()).takeIf { it.isNotEmpty() }?.let {
            return refuse("${held.name} has no connection ${it.joinToString(", ")}; read it with $READ")
        }
        val nodeChanges = byId(asked.path(UPDATE_NODES)) { key -> before.nodes.any { it.id == key } }
            ?: return refuse("Each node in $UPDATE_NODES needs the id of one on ${held.name}; read it with $READ")
        val edgeChanges = byId(asked.path(UPDATE_EDGES)) { key -> before.edges.any { it.id == key } }
            ?: return refuse("Each connection in $UPDATE_EDGES needs the id of one on ${held.name}; read it with $READ")

        // A removed group's children stay, out of it; a removed node's connections go with it.
        val keptNodes = before.nodes.filter { it.id !in goneNodes }.map { if (it.parent in goneNodes) it.copy(parent = null) else it }
        val nodeIds = keptNodes.map { it.id }.toSet()
        val changedNodes = keptNodes.map { node -> nodeChanges[node.id]?.let { api.document.changed(node, it, nodeIds - node.id) } ?: node }
        val addedNodes = list(asked.path(ADD_NODES))?.let { more ->
            val taken = nodeIds.toMutableSet()
            (0 until more.size()).map { at -> api.document.node(more.get(at), changedNodes.size + at, taken).also { taken += it.id } }
        }.orEmpty()

        val keptEdges = before.edges.filter { it.id !in goneEdges && it.from !in goneNodes && it.to !in goneNodes }
        val edgeIds = keptEdges.map { it.id }.toSet()
        val changedEdges = keptEdges.map { edge -> edgeChanges[edge.id]?.let { api.document.changed(edge, it, edgeIds - edge.id) } ?: edge }
        val addedEdges: List<DiagramLink> = list(asked.path(ADD_EDGES))?.let { more ->
            val taken = edgeIds.toMutableSet()
            (0 until more.size()).map { at -> api.document.edge(more.get(at), changedEdges.size + at, taken).also { taken += it.id } }
        }.orEmpty()

        val redrawn = !(goneNodes.isEmpty() && goneEdges.isEmpty() && nodeChanges.isEmpty() && edgeChanges.isEmpty() && addedNodes.isEmpty() && addedEdges.isEmpty())
        // Present and null, or empty, takes the description away; absent leaves it.
        val describing = asked.has(DESCRIPTION)
        if (!redrawn && !describing) {
            return refuse("Nothing to change: give $DESCRIPTION, $ADD_NODES, $UPDATE_NODES, $REMOVE_NODES, $ADD_EDGES, $UPDATE_EDGES or $REMOVE_EDGES")
        }
        val described = text(asked.path(DESCRIPTION))
        // Said before anything is written, so a description too long cannot leave the drawing saved without it.
        val most = tuning[Tunable.DIAGRAM_DESCRIPTION_CHARACTERS]
        if (described != null && described.length > most) {
            return refuse("A diagram's description may be $most characters at most, and this one is ${described.length}")
        }
        if (redrawn) api.saveDrawing(id, DiagramDrawing(changedNodes + addedNodes, changedEdges + addedEdges))
        if (describing) api.describeDiagram(id, described)
        return drawn(scope, api.view(api.find(id)), asked)
    }

    /**
     * What a drawing tool answers: the diagram as [read] has it, and a picture of
     * it where the call came from a conversation - one per diagram per turn, its
     * latest, as a wireframe's is.
     */
    private fun drawn(scope: OrknuxScope, view: DiagramView, asked: JsonNode): String {
        val answer = read(api.find(view.id))
        scope.session ?: return answer
        val png = when (val made = export.png(api.drawing(view.id))) {
            is SvgRenderer.Drawing.Drawn -> made
            is SvgRenderer.Drawing.Refused -> return answer
        }
        val tree = mapper.readTree(answer) as ObjectNode
        tree.put(AgentTools.PICTURE, Base64.getEncoder().encodeToString(png.png))
        tree.put(AgentTools.PICTURE_TYPE, "image/png")
        tree.put(AgentTools.PICTURE_KEY, "diagram:" + view.id)
        // Kept from the person where the call said so; the round reads the same argument. See AgentTools.SHOW.
        tree.put(
            "note",
            if (AgentTools.keptFromPerson(asked)) {
                "The picture of the diagram is for you alone and was not put in the conversation. The person can " +
                    "open it at the url, in the diagram editor."
            } else {
                "A picture of the diagram goes into the conversation with this answer. In your answer give the person the " +
                    "url, which opens it in the diagram editor, and say what you changed."
            },
        )
        return mapper.writeValueAsString(tree)
    }

    private fun withDiagram(scope: OrknuxScope, asked: JsonNode, then: (Diagram) -> String): String {
        val named = text(asked.path(DIAGRAM)) ?: return refuse("Which diagram? Give its name or id; $LIST lists them.")
        val matches = diagrams.findByWorkspaceIdOrderByUpdatedAtDescIdDesc(scope.workspaceId)
            .filter { it.name.equals(named, ignoreCase = true) || it.id?.toString() == named }
        return when {
            matches.isEmpty() -> refuse("There is no diagram called $named here; $LIST lists them")
            matches.size > 1 -> refuse("More than one diagram is called $named; use its id")
            else -> then(matches.single())
        }
    }

    private fun one(scope: OrknuxScope, named: String): Diagram? =
        diagrams.findByWorkspaceIdOrderByUpdatedAtDescIdDesc(scope.workspaceId)
            .filter { it.name.equals(named, ignoreCase = true) || it.id?.toString() == named }
            .singleOrNull()

    /** Changes by the id each names; null where one names none, or one that is not there. */
    private fun byId(node: JsonNode, exists: (String) -> Boolean): Map<String, JsonNode>? {
        val changes = list(node) ?: return emptyMap()
        val found = linkedMapOf<String, JsonNode>()
        for (at in 0 until changes.size()) {
            val change = changes.get(at)
            val id = text(change.path("id")) ?: return null
            if (!exists(id)) return null
            found[id] = change
        }
        return found
    }

    private fun ids(node: JsonNode): Set<String> = list(node)?.let { ids -> (0 until ids.size()).mapNotNull { text(ids.get(it)) }.toSet() }.orEmpty()

    /** A list as given, or as the text of one - a model will send either. */
    private fun list(node: JsonNode): JsonNode? = when {
        node.isArray -> node
        node.isString -> runCatching { mapper.readTree(node.stringValue()) }.getOrNull()?.takeIf { it.isArray }
            ?: throw DiagramDocumentInvalidException("a list was expected and got text")
        else -> null
    }

    private fun text(node: JsonNode): String? = when {
        node.isString -> node.stringValue()?.trim()?.takeIf { it.isNotEmpty() }
        node.isNumber -> node.asText()
        else -> null
    }

    private fun said(access: DiagramAgentAccess): String = if (access == DiagramAgentAccess.CHANGE) CHANGES else READS

    private fun link(workspaceId: Long, id: Long, node: String? = null): String =
        base() + "/workspace/$workspaceId/explore/diagrams/$id" + (node?.let { "?node=" + java.net.URLEncoder.encode(it, Charsets.UTF_8) } ?: "")

    private fun base(): String = address.base().ifEmpty { web.allowedOrigins.firstOrNull()?.trimEnd('/').orEmpty() }

    private fun refuse(says: String): String = mapper.writeValueAsString(mapOf("error" to says))

    companion object {
        const val LIST = "orknux_diagrams"
        const val READ = "orknux_diagram"
        const val NODE = "orknux_diagram_node"
        const val SEARCH = "orknux_diagram_search"
        const val EXPORT = "orknux_diagram_export"
        const val CREATE = "orknux_create_diagram"
        const val CHANGE = "orknux_change_diagram"
        const val DELETE = "orknux_delete_diagram"

        /** What the editor's own chat is offered: working on the diagram it has open. */
        val EDITING = setOf(READ, NODE, SEARCH, CHANGE)

        val NAMES = setOf(LIST, READ, NODE, SEARCH, EXPORT, CREATE, CHANGE, DELETE)

        /** Off until an owner turns it on for an agent: a deleted diagram is everything drawn on it, gone. */
        val STARTS_HIDDEN = setOf(DELETE)

        /** What every answer about a diagram calls whether an agent may change it, and its two values. */
        const val AGENTS = "agents"
        const val CHANGES = "change"
        const val READS = "read"

        const val DIAGRAM = "diagram"
        const val NODE_ARG = "node"
        const val QUERY = "query"
        const val CATALOG = "catalog"
        const val FORMAT = "format"
        const val DESCRIPTION = "description"
        const val NODES = "nodes"
        const val EDGES = "edges"
        const val ADD_NODES = "addNodes"
        const val UPDATE_NODES = "updateNodes"
        const val REMOVE_NODES = "removeNodes"
        const val ADD_EDGES = "addEdges"
        const val UPDATE_EDGES = "updateEdges"
        const val REMOVE_EDGES = "removeEdges"

        private const val AGENTS_SAID = "$AGENTS: $CHANGES, or $READS where it is read only for agents"

        private val SHAPES_SAID: String =
            "Node kinds: " + DiagramNodeKind.entries.joinToString("; ") { "${it.key} - ${it.said}" } + ". " +
                "A connection's line: " + DiagramLineStyle.entries.joinToString("; ") { "${it.key} - ${it.said}" } + ". " +
                "Each end of it, start at from and end at to: " +
                DiagramEndStyle.entries.joinToString("; ") { "${it.key} - ${it.said}" } + ". " +
                "Or a kind, standing for all three: " +
                DiagramEdgeKind.entries.joinToString("; ") { "${it.key} - ${it.said}" } + "."

        private const val ANSWERED =
            "Answers the diagram with its url - where it opens in the editor, to give the person - and, in a " +
                "conversation, puts a picture of it there."

        private const val NODE_SAID =
            "Each is {id, kind, label, x, y, w, h, color, notes, parent}: whole pixels from the top left, absolute even " +
                "inside a group; id optional on a new one; color #rrggbb; notes are free text about the node; parent " +
                "is the id of the group it sits in. Lay them out left to right or top to bottom, about 80 pixels apart."

        private const val EDGE_SAID =
            "Each is {id, from, to, label, line, start, end}: from and to are node ids; id optional on a new one; " +
                "kind may stand in for line, start and end."

        private val NODE_LIST: Map<String, Any?> = mapOf(
            "type" to "array",
            "items" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "id" to mapOf("type" to "string"),
                    "kind" to mapOf("type" to "string", "enum" to DiagramNodeKind.entries.map { it.key }),
                    "label" to mapOf("type" to "string"),
                    "x" to mapOf("type" to "number"),
                    "y" to mapOf("type" to "number"),
                    "w" to mapOf("type" to "number"),
                    "h" to mapOf("type" to "number"),
                    "color" to mapOf("type" to "string"),
                    "notes" to mapOf("type" to "string"),
                    "parent" to mapOf("type" to "string"),
                ),
            ),
        )

        private val EDGE_LIST: Map<String, Any?> = mapOf(
            "type" to "array",
            "items" to mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "id" to mapOf("type" to "string"),
                    "from" to mapOf("type" to "string"),
                    "to" to mapOf("type" to "string"),
                    "label" to mapOf("type" to "string"),
                    "kind" to mapOf("type" to "string", "enum" to DiagramEdgeKind.entries.map { it.key }),
                    "line" to mapOf("type" to "string", "enum" to DiagramLineStyle.entries.map { it.key }),
                    "start" to mapOf("type" to "string", "enum" to DiagramEndStyle.entries.map { it.key }),
                    "end" to mapOf("type" to "string", "enum" to DiagramEndStyle.entries.map { it.key }),
                ),
            ),
        )

        private val IDS: Map<String, Any?> = mapOf("type" to "array", "items" to mapOf("type" to "string"))
    }
}
