package io.mszymanski.orknux.server.diagram

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.math.roundToInt

/** One box, as the editor and the tools hold it. Pixels from the top left, absolute - a group's children too. */
data class DiagramShape(
    val id: String,
    val kind: DiagramNodeKind,
    val label: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val color: String? = null,
    val notes: String? = null,
    val parent: String? = null,
)

/** One line between two boxes, by their ids: how it is drawn along its length, and what each end carries. */
data class DiagramLink(
    val id: String,
    val from: String,
    val to: String,
    val label: String? = null,
    val line: DiagramLineStyle = DiagramLineStyle.SOLID,
    /** At [from]. */
    val start: DiagramEndStyle = DiagramEndStyle.NONE,
    /** At [to]. */
    val end: DiagramEndStyle = DiagramEndStyle.ARROW,
) {
    /** The nearest of the four kinds a line had before it had a style and two ends. */
    val kind: DiagramEdgeKind get() = DiagramEdgeKind.nearest(line, start, end)

    companion object {
        /** A line drawn as one of the four kinds, exactly as it was drawn then. */
        fun of(id: String, from: String, to: String, label: String?, kind: DiagramEdgeKind) =
            DiagramLink(id, from, to, label, kind.line, kind.start, kind.end)
    }
}

/** A whole drawing. */
data class DiagramDrawing(val nodes: List<DiagramShape>, val edges: List<DiagramLink>) {
    val size: Int get() = nodes.size + edges.size

    companion object {
        val EMPTY = DiagramDrawing(emptyList(), emptyList())
    }
}

/**
 * Reads, checks and writes a diagram as JSON: `{"nodes": [...], "edges": [...]}`.
 *
 * Everything that reaches the tables comes through here, from the editor and
 * from a model alike, so the two cannot come to store different shapes. What it
 * refuses it says node by node, because the reader of the refusal is as often a
 * model as a person and a model can only correct what it is told.
 *
 * How long notes may be and how much a diagram may hold are the installation's
 * numbers, and are checked by [DiagramAPI] on every save rather than here, so
 * that a drawing can be read whatever the numbers were when it was written.
 */
class DiagramDocument(private val mapper: ObjectMapper) {

    /** A drawing as sent; an empty text is an empty drawing. */
    fun read(document: String?): DiagramDrawing {
        if (document.isNullOrBlank()) return DiagramDrawing.EMPTY
        val tree = try {
            mapper.readTree(document)
        } catch (_: Exception) {
            throw DiagramDocumentInvalidException("it is not JSON")
        }
        if (!tree.isObject) throw DiagramDocumentInvalidException("it has to be an object with nodes and edges")
        val nodes = nodes(tree.path("nodes").takeUnless { it.isMissingNode || it.isNull } ?: mapper.createArrayNode())
        val edges = edges(tree.path("edges").takeUnless { it.isMissingNode || it.isNull } ?: mapper.createArrayNode(), nodes)
        return checked(DiagramDrawing(nodes, edges))
    }

    fun nodes(list: JsonNode): List<DiagramShape> {
        if (!list.isArray) throw DiagramDocumentInvalidException("nodes has to be a list")
        val taken = mutableSetOf<String>()
        return (0 until list.size()).map { at -> node(list.get(at), at, taken).also { taken += it.id } }
    }

    fun edges(list: JsonNode, nodes: List<DiagramShape>): List<DiagramLink> {
        if (!list.isArray) throw DiagramDocumentInvalidException("edges has to be a list")
        val taken = mutableSetOf<String>()
        return (0 until list.size()).map { at -> edge(list.get(at), at, taken).also { taken += it.id } }
    }

    /** One node. [taken] are the ids already used, so a repeated one is refused. */
    fun node(node: JsonNode, at: Int, taken: Set<String>): DiagramShape {
        if (!node.isObject) throw DiagramDocumentInvalidException("node ${at + 1} is not an object")
        val asked = text(node.path("kind")) ?: text(node.path("shape"))
        val kind = if (asked == null) DiagramNodeKind.BOX else DiagramNodeKind.byKey(asked)
            ?: throw DiagramDocumentInvalidException(
                "node ${at + 1} has kind $asked, which is not one of ${DiagramNodeKind.entries.joinToString(", ") { it.key }}",
            )
        val id = id(node, "node ${at + 1}", taken)
        val label = text(node.path("label"))?.trim().orEmpty()
        if (label.length > Diagrams.LABEL_LENGTH) {
            throw DiagramDocumentInvalidException("node $id has a label longer than ${Diagrams.LABEL_LENGTH} characters")
        }
        return DiagramShape(
            id = id,
            kind = kind,
            label = label,
            x = number(node, "x", id, 0),
            y = number(node, "y", id, 0),
            w = number(node, "w", id, kind.w).coerceAtLeast(MIN_SIZE),
            h = number(node, "h", id, kind.h).coerceAtLeast(MIN_SIZE),
            color = color(node.path("color"), id),
            notes = text(node.path("notes"))?.takeIf { it.isNotBlank() },
            parent = text(node.path("parent"))?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    /** One edge; whether its ends exist is the drawing's to say, in [checked]. */
    fun edge(node: JsonNode, at: Int, taken: Set<String>): DiagramLink {
        if (!node.isObject) throw DiagramDocumentInvalidException("edge ${at + 1} is not an object")
        val id = id(node, "edge ${at + 1}", taken)
        val from = text(node.path("from"))?.trim()?.takeIf { it.isNotEmpty() } ?: text(node.path("source"))?.trim()
            ?: throw DiagramDocumentInvalidException("edge $id needs from, the id of the node it starts at")
        val to = text(node.path("to"))?.trim()?.takeIf { it.isNotEmpty() } ?: text(node.path("target"))?.trim()
            ?: throw DiagramDocumentInvalidException("edge $id needs to, the id of the node it ends at")
        val asked = text(node.path("kind"))
        val kind = if (asked == null) DiagramEdgeKind.ARROW else DiagramEdgeKind.byKey(asked)
            ?: throw DiagramDocumentInvalidException(
                "edge $id has kind $asked, which is not one of ${DiagramEdgeKind.entries.joinToString(", ") { it.key }}",
            )
        val label = text(node.path("label"))?.trim()?.takeIf { it.isNotEmpty() }
        if (label != null && label.length > Diagrams.LABEL_LENGTH) {
            throw DiagramDocumentInvalidException("edge $id has a label longer than ${Diagrams.LABEL_LENGTH} characters")
        }
        // The kind says all three where nothing else does; each one named beside it is that one's own.
        val line = text(node.path("line"))?.let { said ->
            DiagramLineStyle.byKey(said) ?: throw DiagramDocumentInvalidException(
                "edge $id has line $said, which is not one of ${DiagramLineStyle.entries.joinToString(", ") { it.key }}",
            )
        } ?: kind.line
        return DiagramLink(
            id = id, from = from, to = to, label = label, line = line,
            start = end(node, "start", id) ?: kind.start,
            end = end(node, "end", id) ?: kind.end,
        )
    }

    private fun end(node: JsonNode, field: String, of: String): DiagramEndStyle? = text(node.path(field))?.let { said ->
        DiagramEndStyle.byKey(said) ?: throw DiagramDocumentInvalidException(
            "edge $of has $field $said, which is not one of ${DiagramEndStyle.entries.joinToString(", ") { it.key }}",
        )
    }

    /**
     * The drawing held together: every edge between two nodes that are there,
     * every parent a group that is there, and no group inside itself.
     */
    fun checked(drawing: DiagramDrawing): DiagramDrawing {
        val byId = drawing.nodes.associateBy { it.id }
        val edgeIds = mutableSetOf<String>()
        drawing.edges.forEach { edge ->
            if (!edgeIds.add(edge.id)) throw DiagramDocumentInvalidException("two edges have the id ${edge.id}")
            if (edge.from !in byId) throw DiagramDocumentInvalidException("edge ${edge.id} starts at ${edge.from}, which is not a node here")
            if (edge.to !in byId) throw DiagramDocumentInvalidException("edge ${edge.id} ends at ${edge.to}, which is not a node here")
        }
        drawing.nodes.forEach { node ->
            val parent = node.parent ?: return@forEach
            val held = byId[parent] ?: throw DiagramDocumentInvalidException("node ${node.id} sits in $parent, which is not a node here")
            if (held.kind != DiagramNodeKind.GROUP) {
                throw DiagramDocumentInvalidException("node ${node.id} sits in $parent, which is not a group")
            }
            val seen = mutableSetOf(node.id)
            var up: String? = parent
            while (up != null) {
                if (!seen.add(up)) throw DiagramDocumentInvalidException("group ${node.id} sits inside itself")
                up = byId[up]?.parent
            }
        }
        return drawing
    }

    /** The drawing as the editor and the GraphQL `document` read it. */
    fun write(drawing: DiagramDrawing): String = mapper.writeValueAsString(
        mapOf("nodes" to drawing.nodes.map { shown(it) }, "edges" to drawing.edges.map(::shown)),
    )

    /** One node as a map; with [notesUpTo], notes past it are cut and said to be. */
    fun shown(node: DiagramShape, notesUpTo: Int? = null): Map<String, Any?> = buildMap {
        put("id", node.id)
        put("kind", node.kind.key)
        put("label", node.label)
        put("x", node.x)
        put("y", node.y)
        put("w", node.w)
        put("h", node.h)
        node.color?.let { put("color", it) }
        node.parent?.let { put("parent", it) }
        node.notes?.let { notes ->
            if (notesUpTo != null && notes.length > notesUpTo) {
                put("notes", notes.take(notesUpTo).trimEnd() + "…")
                put("notesLength", notes.length)
            } else {
                put("notes", notes)
            }
        }
    }

    fun shown(edge: DiagramLink): Map<String, Any?> = buildMap {
        put("id", edge.id)
        put("from", edge.from)
        put("to", edge.to)
        put("kind", edge.kind.key)
        put("line", edge.line.key)
        put("start", edge.start.key)
        put("end", edge.end.key)
        edge.label?.let { put("label", it) }
    }

    /** A node changed in place: whatever [change] names is replaced, the rest kept; a null clears notes, colour or parent. */
    fun changed(node: DiagramShape, change: JsonNode, others: Set<String>): DiagramShape {
        val merged = mapper.valueToTree<JsonNode>(shown(node)) as tools.jackson.databind.node.ObjectNode
        change.properties().forEach { (key, value) -> merged.set(key, value) }
        merged.put("id", node.id)
        return node(merged, 0, others)
    }

    fun changed(edge: DiagramLink, change: JsonNode, others: Set<String>): DiagramLink {
        val merged = mapper.valueToTree<JsonNode>(shown(edge)) as tools.jackson.databind.node.ObjectNode
        // A change naming only a kind means that kind whole, not that kind under the style the line had.
        if (change.has("kind")) STYLED.filterNot { change.has(it) }.forEach { merged.remove(it) }
        change.properties().forEach { (key, value) -> merged.set(key, value) }
        merged.put("id", edge.id)
        return edge(merged, 0, others)
    }

    private fun id(node: JsonNode, what: String, taken: Set<String>): String {
        val id = text(node.path("id"))?.trim()?.takeIf { it.isNotEmpty() }
        if (id != null && id.length > Diagrams.ID_LENGTH) {
            throw DiagramDocumentInvalidException("$what has an id longer than ${Diagrams.ID_LENGTH} characters")
        }
        if (id != null && id in taken) throw DiagramDocumentInvalidException("two of them have the id $id")
        return id ?: fresh(taken)
    }

    private fun color(node: JsonNode, of: String): String? {
        val said = text(node)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!COLOR.matches(said)) throw DiagramDocumentInvalidException("node $of has colour $said, which is not #rrggbb")
        return said.lowercase()
    }

    /** A whole number of pixels, from a number or a string holding one; rounded, and held to [Diagrams.REACH]. */
    private fun number(node: JsonNode, field: String, of: String, otherwise: Int): Int {
        val value = node.path(field)
        if (value.isMissingNode || value.isNull) return otherwise
        val read = when {
            value.isNumber -> value.doubleValue()
            value.isString -> value.stringValue()?.trim()?.toDoubleOrNull()
            else -> null
        }
        if (read == null || read.isNaN() || read.isInfinite()) {
            throw DiagramDocumentInvalidException("node $of has $field $value, which is not a number")
        }
        if (read > Diagrams.REACH || read < -Diagrams.REACH) {
            throw DiagramDocumentInvalidException("node $of has $field $read, past ${Diagrams.REACH} pixels")
        }
        return read.roundToInt()
    }

    private fun text(node: JsonNode): String? = when {
        node.isString -> node.stringValue()
        node.isNumber || node.isBoolean -> node.asText()
        else -> null
    }

    private fun fresh(taken: Set<String>): String {
        while (true) {
            val made = "n" + UUID.randomUUID().toString().replace("-", "").take(8)
            if (made !in taken) return made
        }
    }

    private companion object {
        /** A box is at least this many pixels each way, or it cannot be seen, selected or found again. */
        const val MIN_SIZE = 8

        val COLOR = Regex("^#[0-9a-fA-F]{6}$")

        /** What an edge says beyond its kind: the style along it and its two ends. */
        val STYLED = listOf("line", "start", "end")
    }
}
