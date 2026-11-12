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
    /** The layer it is on, by id; null is the drawing's first. */
    val layer: String? = null,
    /**
     * What a reader opens from it: a web address (http, https or mailto), or
     * another diagram as `diagram:<id>`. Nothing else is kept - a link is
     * opened by a click, and a `javascript:` one would run in the reader's page.
     */
    val link: String? = null,
    /** Its outline's colour, #rrggbb; null is the ink every box had. */
    val border: String? = null,
    /** Its outline's thickness in pixels; null is the one every box had. */
    val borderWidth: Int? = null,
    /** The picture it shows, where it is an image: a [DiagramImage]'s id, or an address on the web. */
    val image: String? = null,
) {
    /** The uploaded picture it shows, by id; null for an address on the web, or none. */
    val uploadedImage: Long? get() = image?.takeIf { it.all(Char::isDigit) }?.toLongOrNull()
    /** The diagram it links to, by id; null where it links to none. */
    val linkedDiagram: Long? get() = linked(Diagrams.DIAGRAM_LINK)

    /** What of the workspace's own it links to - diagram, document or wireframe - and its id; null for an address or none. */
    val linkedItem: Pair<String, Long>? get() = listOf(Diagrams.DIAGRAM_LINK, Diagrams.DOCUMENT_LINK, Diagrams.WIREFRAME_LINK)
        .firstNotNullOfOrNull { prefix -> linked(prefix)?.let { prefix.dropLast(1) to it } }

    private fun linked(prefix: String): Long? = link?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.toLongOrNull()
}

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
    val routing: DiagramRouting = DiagramRouting.STRAIGHT,
    /** The layer it is on, by id; null is the drawing's first. */
    val layer: String? = null,
    /**
     * Its own corners, start to end, where it was routed around what is in its
     * way ([DiagramRouter]); empty is a line drawn the one way [routing] names.
     * Only a right-angle line follows them. Its two ends are not among them:
     * they stay on the boxes, worked out from the first and last corner, so a
     * box moved by hand still carries the line.
     */
    val points: List<Pair<Int, Int>> = emptyList(),
    /**
     * Where along it its label sits, as a share of its length from its start,
     * where [DiagramRouter] moved it clear of a box or another label; null is
     * the middle of its longest run. Only a routed line has one.
     */
    val labelAt: Double? = null,
    /** Its colour, #rrggbb, ends included; null is the ink every line had. */
    val color: String? = null,
    /** Its thickness in pixels; null is the one every line had. */
    val width: Int? = null,
    /** The side of [from] it is pinned to; null is the floating end that finds the side facing [to]. */
    val fromSide: DiagramSide? = null,
    /** The side of [to] it is pinned to; null is the floating end that finds the side facing [from]. */
    val toSide: DiagramSide? = null,
) {
    /** The nearest of the four kinds a line had before it had a style and two ends. */
    val kind: DiagramEdgeKind get() = DiagramEdgeKind.nearest(line, start, end)

    /** Whether it runs through corners of its own rather than the way its routing alone would. */
    val routed: Boolean get() = routing == DiagramRouting.ORTHOGONAL && points.isNotEmpty()

    companion object {
        /** How many corners one line may have; a format, not a behaviour - a routed line has a handful. */
        const val MOST_POINTS = 64

        /** Corners as a column holds them: "x,y x,y"; null for none. */
        fun writePoints(points: List<Pair<Int, Int>>): String? =
            points.takeIf { it.isNotEmpty() }?.joinToString(" ") { "${it.first},${it.second}" }

        /** Corners as a column held them; anything unreadable is none, so the line is drawn the plain way. */
        fun readPoints(stored: String?): List<Pair<Int, Int>> {
            if (stored.isNullOrBlank()) return emptyList()
            return runCatching {
                stored.trim().split(Regex("\\s+")).map { pair ->
                    val (x, y) = pair.split(',').map { it.trim().toInt() }
                    x to y
                }
            }.getOrDefault(emptyList())
        }

        /** A line drawn as one of the four kinds, exactly as it was drawn then. */
        fun of(id: String, from: String, to: String, label: String?, kind: DiagramEdgeKind) =
            DiagramLink(id, from, to, label, kind.line, kind.start, kind.end)
    }
}

/**
 * One layer of a drawing, as draw.io has them: a name, whether what is on it is
 * shown, and whether it can be changed. Hidden, it is left out of the editor
 * and of every export; locked, it is drawn and cannot be selected or moved.
 */
data class DiagramLayer(val id: String, val name: String, val visible: Boolean = true, val locked: Boolean = false)

/**
 * A whole drawing. [layers] bottom first; empty is a drawing with none of its
 * own, which is one layer, [DiagramLayer] Base, that everything is on.
 */
data class DiagramDrawing(
    val nodes: List<DiagramShape>,
    val edges: List<DiagramLink>,
    val layers: List<DiagramLayer> = emptyList(),
) {
    val size: Int get() = nodes.size + edges.size

    /** The layers there are: its own, or the one every drawing has without them. */
    val effectiveLayers: List<DiagramLayer> get() = layers.ifEmpty { listOf(BASE) }

    /** The id of the layer [layer] names, or the first where it names none. */
    fun layerOf(layer: String?): String = layer ?: effectiveLayers.first().id

    /**
     * What is shown: every box on a visible layer, and every line on one whose
     * two ends are both shown. What an export draws, so a file is what the
     * person looking at the editor sees.
     */
    fun visible(): DiagramDrawing {
        val hidden = effectiveLayers.filterNot { it.visible }.map { it.id }.toSet()
        if (hidden.isEmpty()) return this
        val shown = nodes.filter { layerOf(it.layer) !in hidden }
        val ids = shown.map { it.id }.toSet()
        return copy(nodes = shown, edges = edges.filter { layerOf(it.layer) !in hidden && it.from in ids && it.to in ids })
    }

    companion object {
        val EMPTY = DiagramDrawing(emptyList(), emptyList())

        /** The layer of a drawing that has none of its own. */
        val BASE = DiagramLayer(id = "base", name = "Base")
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
        val layers = layers(tree.path("layers").takeUnless { it.isMissingNode || it.isNull } ?: mapper.createArrayNode())
        return checked(DiagramDrawing(nodes, edges, layers))
    }

    /** A drawing's layers, bottom first: each an object with an id and a name, and whether it is shown and locked. */
    fun layers(list: JsonNode): List<DiagramLayer> {
        if (!list.isArray) throw DiagramDocumentInvalidException("layers has to be a list")
        if (list.size() > MOST_LAYERS) throw DiagramDocumentInvalidException("a diagram holds at most $MOST_LAYERS layers")
        val taken = mutableSetOf<String>()
        return (0 until list.size()).map { at ->
            val layer = list.get(at)
            if (!layer.isObject) throw DiagramDocumentInvalidException("layer ${at + 1} is not an object")
            val id = id(layer, "layer ${at + 1}", taken).also { taken += it }
            val name = text(layer.path("name"))?.trim()?.takeIf { it.isNotEmpty() } ?: "Layer ${at + 1}"
            if (name.length > Diagrams.LABEL_LENGTH) {
                throw DiagramDocumentInvalidException("layer $id has a name longer than ${Diagrams.LABEL_LENGTH} characters")
            }
            DiagramLayer(
                id = id,
                name = name,
                visible = layer.path("visible").let { if (it.isBoolean) it.booleanValue() else true },
                locked = layer.path("locked").let { if (it.isBoolean) it.booleanValue() else false },
            )
        }
    }

    /** Layers as they are written: a list of objects, or nothing where the drawing has none of its own. */
    fun writeLayers(layers: List<DiagramLayer>): String? =
        if (layers.isEmpty()) null else mapper.writeValueAsString(layers.map(::shown))

    /** Layers as they were stored; nothing readable is none of its own. */
    fun readLayers(stored: String?): List<DiagramLayer> =
        if (stored.isNullOrBlank()) emptyList() else runCatching { layers(mapper.readTree(stored)) }.getOrDefault(emptyList())

    fun shown(layer: DiagramLayer): Map<String, Any?> =
        mapOf("id" to layer.id, "name" to layer.name, "visible" to layer.visible, "locked" to layer.locked)

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
            layer = text(node.path("layer"))?.trim()?.takeIf { it.isNotEmpty() },
            link = link(node.path("link"), id),
            border = color(node.path("border"), id),
            borderWidth = thickness(node.path("borderWidth"), "node $id", "borderWidth"),
            image = text(node.path("image"))?.trim()?.takeIf { it.isNotEmpty() }?.also { said ->
                if (said.length > Diagrams.LINK_LENGTH || !(said.all(Char::isDigit) || PICTURE_ADDRESS.matches(said))) {
                    throw DiagramDocumentInvalidException("node $id has image $said, which is neither an uploaded picture's id nor an address on the web (https://...)")
                }
            },
        )
    }

    /** A stroke's thickness: whole pixels from 1 to [MOST_THICKNESS]; absent or null is the usual one. */
    private fun thickness(value: JsonNode, of: String, field: String): Int? {
        if (value.isMissingNode || value.isNull) return null
        val read = when {
            value.isNumber -> value.doubleValue()
            value.isString -> value.stringValue()?.trim()?.toDoubleOrNull()
            else -> null
        }?.roundToInt()
        if (read == null || read < 1 || read > MOST_THICKNESS) {
            throw DiagramDocumentInvalidException("$of has $field $value, which is not a whole number of pixels from 1 to $MOST_THICKNESS")
        }
        return read
    }

    /** A box's link, held to what may be opened by a click; empty is none. */
    private fun link(value: JsonNode, of: String): String? {
        val said = text(value)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (said.length > Diagrams.LINK_LENGTH) throw DiagramDocumentInvalidException("node $of has a link longer than ${Diagrams.LINK_LENGTH} characters")
        if (!LINK.matches(said)) {
            throw DiagramDocumentInvalidException(
                "node $of links to $said, which is not a web address (http, https or mailto) nor another diagram, a document or a " +
                    "wireframe as ${Diagrams.DIAGRAM_LINK}<id>, ${Diagrams.DOCUMENT_LINK}<id> or ${Diagrams.WIREFRAME_LINK}<id>",
            )
        }
        return said
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
        val routing = text(node.path("routing"))?.let { said ->
            DiagramRouting.byKey(said) ?: throw DiagramDocumentInvalidException(
                "edge $id has routing $said, which is not one of ${DiagramRouting.entries.joinToString(", ") { it.key }}",
            )
        } ?: DiagramRouting.STRAIGHT
        return DiagramLink(
            id = id, from = from, to = to, label = label, line = line,
            start = end(node, "start", id) ?: kind.start,
            end = end(node, "end", id) ?: kind.end,
            routing = routing,
            layer = text(node.path("layer"))?.trim()?.takeIf { it.isNotEmpty() },
            points = points(node.path("points"), id),
            labelAt = node.path("labelAt").takeIf { it.isNumber }?.doubleValue()?.takeIf { it in 0.0..1.0 },
            color = color(node.path("color"), id),
            width = thickness(node.path("width"), "edge $id", "width"),
            fromSide = side(node, "fromSide", id),
            toSide = side(node, "toSide", id),
        )
    }

    /** The side one end is pinned to, or null where it is absent; a word that is no side is refused. */
    private fun side(node: JsonNode, field: String, of: String): DiagramSide? = text(node.path(field))?.let { said ->
        DiagramSide.byKey(said) ?: throw DiagramDocumentInvalidException(
            "edge $of has $field $said, which is not one of ${DiagramSide.entries.joinToString(", ") { it.key }}",
        )
    }

    /** A line's corners: a list of [x, y] pairs or {x, y} objects, whole pixels; null or absent is none. */
    private fun points(list: JsonNode, of: String): List<Pair<Int, Int>> {
        if (list.isMissingNode || list.isNull) return emptyList()
        if (!list.isArray) throw DiagramDocumentInvalidException("edge $of has points that are not a list of [x, y] pairs")
        if (list.size() > DiagramLink.MOST_POINTS) {
            throw DiagramDocumentInvalidException("edge $of has more than ${DiagramLink.MOST_POINTS} points")
        }
        return (0 until list.size()).map { at ->
            val point = list.get(at)
            val (x, y) = when {
                point.isArray && point.size() == 2 -> point.get(0) to point.get(1)
                point.isObject -> point.path("x") to point.path("y")
                else -> throw DiagramDocumentInvalidException("edge $of has point ${at + 1} that is not [x, y]")
            }
            fun one(value: JsonNode): Int {
                val read = if (value.isNumber) value.doubleValue() else null
                if (read == null || read.isNaN() || read > Diagrams.REACH || read < -Diagrams.REACH) {
                    throw DiagramDocumentInvalidException("edge $of has point ${at + 1} that is not [x, y] in pixels")
                }
                return read.roundToInt()
            }
            one(x) to one(y)
        }
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
        // On a layer that is there: a name it does not have would put a box nowhere anybody could show it.
        val layerIds = drawing.effectiveLayers.map { it.id }.toSet()
        val said = drawing.effectiveLayers.joinToString(", ") { "${it.id} (${it.name})" }
        drawing.nodes.forEach { node ->
            if (node.layer != null && node.layer !in layerIds) {
                throw DiagramDocumentInvalidException("node ${node.id} is on layer ${node.layer}, which is not one of $said")
            }
        }
        drawing.edges.forEach { edge ->
            if (edge.layer != null && edge.layer !in layerIds) {
                throw DiagramDocumentInvalidException("edge ${edge.id} is on layer ${edge.layer}, which is not one of $said")
            }
        }
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
        buildMap {
            if (drawing.layers.isNotEmpty()) put("layers", drawing.layers.map(::shown))
            put("nodes", drawing.nodes.map { shown(it) })
            put("edges", drawing.edges.map(::shown))
        },
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
        node.layer?.let { put("layer", it) }
        node.link?.let { put("link", it) }
        node.border?.let { put("border", it) }
        node.borderWidth?.let { put("borderWidth", it) }
        node.image?.let { put("image", it) }
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
        put("routing", edge.routing.key)
        edge.label?.let { put("label", it) }
        edge.layer?.let { put("layer", it) }
        if (edge.points.isNotEmpty()) put("points", edge.points.map { listOf(it.first, it.second) })
        edge.labelAt?.let { put("labelAt", it) }
        edge.color?.let { put("color", it) }
        edge.width?.let { put("width", it) }
        edge.fromSide?.let { put("fromSide", it.key) }
        edge.toSide?.let { put("toSide", it.key) }
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
        // A line given a new way to run, or new ends, has a new path: its old corners were found for the old one.
        if (!change.has("points") && ROUTED.any { change.has(it) }) {
            merged.remove("points")
            merged.remove("labelAt")
        }
        // New corners are a new path: where its label sat along the old one says nothing about the new.
        if (change.has("points") && !change.has("labelAt")) merged.remove("labelAt")
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

        /** How many layers one diagram may have; a format, not a behaviour - draw.io's own lists run to dozens. */
        const val MOST_LAYERS = 50

        /** How thick a stroke may be, in pixels; a format - past this an outline is a fill. */
        const val MOST_THICKNESS = 12

        val COLOR = Regex("^#[0-9a-fA-F]{6}$")

        /** A picture's address on the web: http or https, and nothing a page could be made to run. */
        val PICTURE_ADDRESS = Regex("^https?://[^\\s<>\"]+$", RegexOption.IGNORE_CASE)

        /** What a box may link to: a web address, a mail address, or another diagram by id. */
        val LINK = Regex("^(?:(?:https?://|mailto:)[^\\s<>\"]+|(?:diagram|document|wireframe):\\d+)$", RegexOption.IGNORE_CASE)

        /** What an edge says beyond its kind: the style along it and its two ends. */
        val STYLED = listOf("line", "start", "end")

        /** What a line's corners were found for: change one and they are dropped. */
        val ROUTED = listOf("routing", "from", "to", "source", "target", "fromSide", "toSide")
    }
}
