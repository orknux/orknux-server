package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.server.graphql.Refusal
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.math.roundToInt

/**
 * A wireframe: a low-fidelity drawing of a screen, kept in the Design
 * perspective beside the workspace's pictures. Issue #655.
 *
 * The drawing is one JSON document ([WireframeDocument]) rather than a row per
 * element, because it is only ever saved and read whole - by the editor, and by
 * a model drawing in it through the orknux_* tools - and nothing asks for an
 * element across wireframes. [revision] counts the saves, so a save made
 * against a drawing that has changed since is refused instead of written over
 * what changed it.
 */
@Entity
@Table(name = "wireframe")
class Wireframe(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Column(nullable = false, length = Wireframes.NAME_LENGTH)
    var name: String,

    /** `{"elements": [...]}`, as [WireframeDocument.write] writes it. */
    @Column(nullable = false, columnDefinition = "text")
    var document: String,

    @Column(nullable = false)
    var revision: Int = 1,

    @Column(name = "created_by", nullable = false, length = 255)
    val createdBy: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_by", nullable = false, length = 255)
    var updatedBy: String,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),

    /** The wireframe catalog it is filed in; null is the workspace's default, as it is for a row an older release wrote. */
    @Column(name = "catalog_id")
    var catalogId: Long? = null,
)

interface WireframeRepository : JpaRepository<Wireframe, Long> {

    /** Locks the row where it is still at [revision], changing nothing; 0 where somebody saved since. Issue #683. */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query("update Wireframe w set w.revision = w.revision where w.id = :id and w.revision = :revision")
    fun holdRevision(@org.springframework.data.repository.query.Param("id") id: Long, @org.springframework.data.repository.query.Param("revision") revision: Int): Int

    /** A workspace's wireframes, the one worked on last first. */
    fun findByWorkspaceIdOrderByUpdatedAtDescIdDesc(workspaceId: Long): List<Wireframe>
}

/** The formats a wireframe is held to; how many elements it may hold is a Tunable. */
object Wireframes {
    const val NAME_LENGTH = 200

    /** An element's own words: a button's label, a note's paragraph. */
    const val TEXT_LENGTH = 2_000

    /** How far from the origin anything may be put, either way, in pixels. */
    const val REACH = 100_000

    /** What an element is named by, which the tools and the editor's selection name it by too. */
    const val ID_LENGTH = 64

    /** Properties one element may carry. */
    const val PROPS_MOST = 20
}

/**
 * What a wireframe can be drawn with.
 *
 * The [said] of each is what a model is told the kind is for, read off here by
 * the tool that takes elements, so the editor's toolbar and the model's
 * vocabulary are the same list. A [box] is placed by its corner and its size; a
 * line or an arrow runs from (x, y) to (x + w, y + h), so its w and h may be
 * negative.
 */
enum class WireframeElementType(val key: String, val box: Boolean, val said: String) {
    FRAME("frame", true, "a screen or a panel, drawn as a titled outline the other elements sit inside; its text is the title"),
    RECT("rect", true, "a plain box: a card, an area, a block"),
    TEXT("text", true, "words with no outline; props.size is the font size in pixels, 14 when absent, and props.weight is bold or normal"),
    BUTTON("button", true, "a button whose text is its label; props.variant primary draws it filled"),
    INPUT("input", true, "a text field whose text is its placeholder"),
    IMAGE("image", true, "a picture: props.artifact names one of the workspace's pictures, such as DESIGN-12, drawn to fit the box; without it a placeholder drawn as a box with a cross; its text, if any, is a caption"),
    LINE("line", false, "a straight line from (x, y) to (x + w, y + h)"),
    ARROW("arrow", false, "a line from (x, y) to (x + w, y + h) with an arrowhead at its end"),
    NOTE("note", true, "a sticky note: an annotation about the design rather than part of it"),
    CHECKBOX("checkbox", true, "a checkbox with its label as its text; props.checked true draws it ticked"),
    RADIO("radio", true, "a radio button with its label as its text; props.checked true draws it chosen"),
    TOGGLE("toggle", true, "an on and off switch with its label as its text; props.checked true draws it on"),
    ;

    /** Whether the kind is a control drawn with its label beside it, which props.checked turns on. */
    val checkable: Boolean get() = this == CHECKBOX || this == RADIO || this == TOGGLE

    companion object {
        fun byKey(key: String?): WireframeElementType? = entries.firstOrNull { it.key == key?.trim()?.lowercase() }
    }
}

/** One thing on a wireframe. Pixels, from the top left. */
data class WireframeElement(
    val id: String,
    val type: WireframeElementType,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    val text: String?,
    val props: Map<String, Any?>,
)

/**
 * Reads, checks and writes a wireframe's document.
 *
 * Everything that reaches the table comes through [read], from the editor and
 * from a model alike, so the two cannot come to store different shapes. What it
 * refuses it says, element by element, because the reader of the refusal is as
 * often a model as a person and a model can only correct what it is told.
 */
class WireframeDocument(private val mapper: ObjectMapper) {

    /**
     * The elements of a document as it is stored: a style prop that cannot be
     * read is dropped rather than refusing the drawing, so one stored before
     * styles were checked still opens. What arrives is read by [read].
     */
    fun stored(document: String?): List<WireframeElement> = read(document, lenient = true)

    /** The elements of a document as stored or as sent; an empty text is an empty drawing. */
    fun read(document: String?, lenient: Boolean = false): List<WireframeElement> {
        if (document.isNullOrBlank()) return emptyList()
        val tree = try {
            mapper.readTree(document)
        } catch (_: Exception) {
            throw WireframeDocumentInvalidException("it is not JSON")
        }
        val list = when {
            tree.isArray -> tree
            tree.isObject && tree.path("elements").isArray -> tree.path("elements")
            tree.isObject && tree.path("elements").isMissingNode -> return emptyList()
            else -> throw WireframeDocumentInvalidException("elements has to be a list")
        }
        return elements(list, lenient)
    }

    /** A list of elements as a model or the editor sends it, each checked and given an id where it has none. */
    fun elements(list: JsonNode, lenient: Boolean = false): List<WireframeElement> {
        if (!list.isArray) throw WireframeDocumentInvalidException("elements has to be a list")
        val taken = mutableSetOf<String>()
        return (0 until list.size()).map { at ->
            val made = element(list.get(at), at, taken, lenient)
            taken += made.id
            made
        }
    }

    /**
     * One element. [taken] are the ids already used in the drawing, so a
     * repeated one is refused rather than leaving two things nobody can tell
     * apart.
     */
    fun element(node: JsonNode, at: Int, taken: Set<String>, lenient: Boolean = false): WireframeElement {
        if (!node.isObject) throw WireframeDocumentInvalidException("element ${at + 1} is not an object")
        val asked = text(node.path("type"))
        val type = WireframeElementType.byKey(asked)
            ?: throw WireframeDocumentInvalidException(
                "element ${at + 1} has type ${asked ?: "(none)"}, which is not one of ${WireframeElementType.entries.joinToString(", ") { it.key }}",
            )
        val id = text(node.path("id"))?.trim()?.takeIf { it.isNotEmpty() }
        if (id != null && id.length > Wireframes.ID_LENGTH) {
            throw WireframeDocumentInvalidException("element ${at + 1} has an id longer than ${Wireframes.ID_LENGTH} characters")
        }
        if (id != null && id in taken) throw WireframeDocumentInvalidException("two elements have the id $id")
        val words = text(node.path("text"))
        if (words != null && words.length > Wireframes.TEXT_LENGTH) {
            throw WireframeDocumentInvalidException("element ${id ?: at + 1} has more than ${Wireframes.TEXT_LENGTH} characters of text")
        }
        val w = number(node, "w", at, 100)
        val h = number(node, "h", at, if (type.box) 40 else 0)
        return WireframeElement(
            id = id ?: freshId(taken),
            type = type,
            x = number(node, "x", at, 0),
            y = number(node, "y", at, 0),
            // A box is at least a pixel each way, or it cannot be seen, selected or found again.
            w = if (type.box) w.coerceAtLeast(1) else w,
            /*
             * A note at least as tall as its words. A note is read, and one
             * drawn shorter than what is written in it showed the start of a
             * sentence and hid the rest - which is how a mockup's notes came
             * out of a model, and out of a person dragging one smaller. Grown,
             * never shrunk: a taller note is somebody's choice.
             */
            h = when {
                type == WireframeElementType.NOTE -> maxOf(h.coerceAtLeast(1), WireframeNotes.heightFor(words, w.coerceAtLeast(1)))
                type.box -> h.coerceAtLeast(1)
                else -> h
            },
            text = words?.takeIf { it.isNotEmpty() },
            props = props(node.path("props"), id ?: "${at + 1}", lenient),
        )
    }

    /** The document as it is stored. */
    fun write(elements: List<WireframeElement>): String = mapper.writeValueAsString(
        mapOf("elements" to elements.map(::shown)),
    )

    /** One element as a map, the way the document and the tools both give it. */
    fun shown(element: WireframeElement): Map<String, Any?> = buildMap {
        put("id", element.id)
        put("type", element.type.key)
        put("x", element.x)
        put("y", element.y)
        put("w", element.w)
        put("h", element.h)
        element.text?.let { put("text", it) }
        if (element.props.isNotEmpty()) put("props", element.props)
    }

    /**
     * An element changed in place: whatever [change] names is replaced, the
     * rest kept, and its props merged - a null prop takes that one away.
     */
    fun changed(element: WireframeElement, change: JsonNode, others: Set<String>): WireframeElement {
        val merged = mapper.createObjectNode()
        mapper.valueToTree<JsonNode>(shown(element)).properties().forEach { (key, value) -> merged.set(key, value) }
        change.properties().forEach { (key, value) ->
            if (key == "props") return@forEach
            merged.set(key, value)
        }
        val props = element.props.toMutableMap()
        change.path("props").takeIf { it.isObject }?.properties()?.forEach { (key, value) ->
            if (value.isNull) props.remove(key) else props[key] = scalar(value)
        }
        merged.set("props", mapper.valueToTree<JsonNode>(props))
        merged.put("id", element.id)
        return element(merged, 0, others)
    }

    private fun props(node: JsonNode, of: String, lenient: Boolean): Map<String, Any?> {
        if (node.isMissingNode || node.isNull) return emptyMap()
        if (!node.isObject) throw WireframeDocumentInvalidException("element $of has props that are not an object")
        val kept = linkedMapOf<String, Any?>()
        node.properties().forEach { (key, value) ->
            // Words, numbers and switches; anything deeper is not a property of a box.
            if (value.isValueNode && !value.isNull) kept[key] = scalar(value)
        }
        if (kept.size > Wireframes.PROPS_MOST) {
            throw WireframeDocumentInvalidException("element $of has more than ${Wireframes.PROPS_MOST} props")
        }
        // How it looks is written into the exports' CSS and SVG, so it is read in its one spelling or not at all.
        return WireframeStyle.checked(kept, of, lenient)
    }

    private fun scalar(value: JsonNode): Any? = when {
        value.isBoolean -> value.booleanValue()
        value.isIntegralNumber -> value.longValue()
        value.isNumber -> value.doubleValue()
        value.isNull -> null
        else -> text(value) ?: value.toString()
    }

    /** A whole number of pixels, from a number or a string holding one; rounded, and held to [Wireframes.REACH]. */
    private fun number(node: JsonNode, field: String, at: Int, otherwise: Int): Int {
        val value = node.path(field)
        if (value.isMissingNode || value.isNull) return otherwise
        val read = when {
            value.isNumber -> value.doubleValue()
            value.isString -> value.stringValue()?.trim()?.toDoubleOrNull()
            else -> null
        }
        if (read == null || read.isNaN() || read.isInfinite()) {
            throw WireframeDocumentInvalidException("element ${at + 1} has $field ${value}, which is not a number")
        }
        if (read > Wireframes.REACH || read < -Wireframes.REACH) {
            throw WireframeDocumentInvalidException("element ${at + 1} has $field $read, past ${Wireframes.REACH} pixels")
        }
        return read.roundToInt()
    }

    private fun text(node: JsonNode): String? = when {
        node.isString -> node.stringValue()
        node.isNumber || node.isBoolean -> node.asText()
        else -> null
    }

    private fun freshId(taken: Set<String>): String {
        while (true) {
            val made = "e" + UUID.randomUUID().toString().replace("-", "").take(8)
            if (made !in taken) return made
        }
    }
}

class WireframeNotFoundException(val id: Long) : RuntimeException("No wireframe with id $id"), Refusal {
    override val arguments get() = mapOf("id" to id)
}

class WireframeNameInvalidException :
    RuntimeException("A wireframe needs a name, ${Wireframes.NAME_LENGTH} characters at most")

class WireframeDocumentInvalidException(val why: String) :
    RuntimeException("The wireframe cannot be saved: $why"), Refusal {
    override val arguments get() = mapOf("why" to why)
}

class WireframeTooLargeException(val count: Int, val most: Int) :
    RuntimeException("A wireframe holds at most $most elements, and this one would hold $count"), Refusal {
    override val arguments get() = mapOf("count" to count, "most" to most)
}

class WireframeChangedException(val name: String) :
    RuntimeException("Wireframe $name was changed since it was opened; open it again before saving"), Refusal {
    override val arguments get() = mapOf("name" to name)
}

class WireframeAnswerFailedException(val reason: String) :
    RuntimeException("The model could not answer: $reason"), Refusal {
    override val arguments get() = mapOf("reason" to reason)
}

/**
 * How tall a sticky note has to be for its words, as every renderer draws one:
 * 13px type, 10px padding all round, lines about 1.4 of the type apart, words
 * wrapped at the width. An estimate on the generous side - a character is taken
 * as 0.6 of the type, wider than most - because a note a line too tall reads
 * fine and one a line too short hides the end of what it says. The editor
 * holds the same numbers in `wireframeNotes.ts`.
 */
object WireframeNotes {
    const val TYPE = 13.0
    const val PADDING = 10
    const val LINE = 1.4
    const val CHARACTER = 0.6

    fun heightFor(text: String?, width: Int): Int {
        if (text.isNullOrBlank()) return 0
        val perLine = ((width - 2 * PADDING) / (TYPE * CHARACTER)).toInt().coerceAtLeast(1)
        val lines = text.split('\n').sumOf { paragraph -> linesOf(paragraph, perLine) }
        return kotlin.math.ceil(lines * TYPE * LINE + 2 * PADDING + 2).toInt()
    }

    private fun linesOf(paragraph: String, perLine: Int): Int {
        if (paragraph.isBlank()) return 1
        var lines = 1
        var used = 0
        paragraph.trim().split(Regex("\\s+")).forEach { word ->
            // A word longer than a line breaks across lines, as overflow-wrap: anywhere does.
            val length = word.length
            when {
                used == 0 -> {
                    lines += (length - 1) / perLine
                    used = (length - 1) % perLine + 1
                }
                used + 1 + length <= perLine -> used += 1 + length
                else -> {
                    lines += 1 + (length - 1) / perLine
                    used = (length - 1) % perLine + 1
                }
            }
        }
        return lines
    }
}
