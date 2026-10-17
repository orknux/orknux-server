package io.mszymanski.orknux.server.obj

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode

/**
 * A field of an object defined in place - inside the property that holds it
 * rather than as a named object of its own - and, where it is an object
 * itself, the fields inside that.
 *
 * The same four things a stored property says, plus the children. A nested
 * field may still point at a named object by id, which is how an inline shape
 * reaches a shared one; it may not be a plugin's or another workspace's, and
 * the same check that guards a top-level reference guards these.
 */
data class InlineProperty(
    val name: String,
    val kind: PropertyKind,
    val refObjectId: Long? = null,
    val elementKind: PropertyKind? = null,
    val description: String? = null,
    /** The fields of an object defined here; null where this is not one. */
    val properties: List<InlineProperty>? = null,
) {
    /** The same field as a stored property would hold it, for the code that walks stored properties. */
    fun asProperty(): ObjectProperty = ObjectProperty(
        name = name,
        kind = kind,
        refObjectId = refObjectId,
        elementKind = elementKind,
        description = description,
        inlineProperties = InlineShapes.write(properties),
    )
}

/**
 * The JSON an inline shape is kept as in `object_property.inline_properties`.
 *
 * Read and written by hand rather than bound, because a shape in a database
 * outlives the class it came from, and because the application's mapper does
 * not construct Kotlin data classes from JSON. Its own plain mapper: this is
 * JSON and nothing else.
 */
object InlineShapes {

    /** How deep an inline shape may go; the same ceiling the describers and checkers stop at. */
    const val MAX_DEPTH = 5

    private val mapper = JsonMapper.builder().build()

    fun read(json: String?): List<InlineProperty>? {
        if (json.isNullOrBlank()) return null
        val node = runCatching { mapper.readTree(json) }.getOrNull() ?: return null
        return if (node.isArray) readAll(node) else null
    }

    private fun readAll(node: JsonNode): List<InlineProperty> = node.values().map { held ->
        InlineProperty(
            name = held.path("name").asText(""),
            kind = PropertyKind.entries.firstOrNull { it.name == held.path("kind").asText("") } ?: PropertyKind.STRING,
            refObjectId = held.path("refObjectId").takeIf { it.isNumber }?.asLong()?.takeIf { it > 0 },
            elementKind = PropertyKind.entries.firstOrNull { it.name == held.path("elementKind").asText("") },
            description = held.path("description").takeIf { it.isString }?.asText(),
            properties = held.path("properties").takeIf { it.isArray }?.let(::readAll),
        )
    }

    fun write(properties: List<InlineProperty>?): String? {
        if (properties == null) return null
        return mapper.writeValueAsString(writeAll(properties))
    }

    private fun writeAll(properties: List<InlineProperty>): ArrayNode {
        val array = mapper.createArrayNode()
        properties.forEach { held ->
            array.addObject().apply {
                put("name", held.name)
                put("kind", held.kind.name)
                held.refObjectId?.let { put("refObjectId", it) }
                held.elementKind?.let { put("elementKind", it.name) }
                held.description?.let { put("description", it) }
                held.properties?.let { set("properties", writeAll(it)) }
            }
        }
        return array
    }

    /** Every named object an inline shape points at, at any depth. */
    fun references(properties: List<InlineProperty>?): Set<Long> = buildSet {
        properties?.forEach { held ->
            held.refObjectId?.let(::add)
            addAll(references(held.properties))
        }
    }
}

/** The fields of an object this property defines in place, or null where it defines none. */
fun ObjectProperty.inline(): List<InlineProperty>? = InlineShapes.read(inlineProperties)

/** Every named object this property points at: its own reference and any inside an inline shape. */
fun ObjectProperty.references(): Set<Long> = buildSet {
    refObjectId?.let(::add)
    addAll(InlineShapes.references(inline()))
}
