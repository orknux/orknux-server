package io.mszymanski.orknux.server.obj

import io.mszymanski.orknux.server.graphql.Refusal
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * An object's properties read off a JSON Schema.
 *
 * What is kept is what a property here can say: a name, one of the five
 * kinds, a description, and for an object its fields in place. `integer` is
 * a number; a `type` given as a list is read as its first entry that is not
 * `null`; a `$ref` into the same document's `definitions` or `$defs` is
 * followed, in place, and one that points elsewhere is read as an object with
 * no fields. Everything else a schema can say - `required`, `enum`, formats,
 * bounds - is left behind, because nothing here could hold it to.
 *
 * Refused rather than guessed at where the text is not JSON, or is a schema of
 * something other than an object: the result would be an object with no
 * properties, which is not what anybody pasting a schema meant.
 */
object JsonSchemaObjects {

    private val mapper = JsonMapper.builder().build()

    /** @param maxDepth how deep the schema is read - the administrator's number; what lies deeper is an object with no fields. */
    fun read(schema: String, maxDepth: Int): List<InlineProperty> {
        val root = runCatching { mapper.readTree(schema) }.getOrNull()
            ?: throw ObjectSchemaUnreadableException("The text is not JSON.")
        if (!root.isObject) throw ObjectSchemaUnreadableException("A JSON Schema is a JSON object.")
        if (typeOf(root) != "object" && !root.has("properties")) {
            throw ObjectSchemaUnreadableException("The schema describes a ${typeOf(root) ?: "value of no stated type"}, not an object.")
        }
        val fields = Reading(root, maxDepth).propertiesOf(root, depth = 0)
        if (fields.isEmpty()) throw ObjectSchemaUnreadableException("The schema names no properties.")
        return fields
    }

    private class Reading(private val root: JsonNode, private val maxDepth: Int) {

        fun propertiesOf(node: JsonNode, depth: Int): List<InlineProperty> {
            val properties = resolve(node).path("properties")
            if (!properties.isObject) return emptyList()
            return properties.properties().map { (name, held) -> propertyOf(name, resolve(held), depth) }
        }

        private fun propertyOf(name: String, node: JsonNode, depth: Int): InlineProperty {
            val description = node.path("description").takeIf { it.isString }?.asText()?.take(DESCRIPTION_LIMIT)
            return when (val type = typeOf(node)) {
                "string", "number", "integer", "boolean" ->
                    InlineProperty(name = name, kind = kindOf(type), description = description)
                "array" -> {
                    val items = resolve(node.path("items"))
                    when (val element = typeOf(items) ?: if (items.path("properties").isObject) "object" else null) {
                        "object" -> InlineProperty(
                            name = name, kind = PropertyKind.ARRAY, elementKind = PropertyKind.OBJECT, description = description,
                            properties = fieldsOrNone(items, depth + 1),
                        )
                        "string", "number", "integer", "boolean", "array" ->
                            InlineProperty(name = name, kind = PropertyKind.ARRAY, elementKind = kindOf(element), description = description)
                        // Items of no stated type, or none at all: a list of something, said as strings.
                        else -> InlineProperty(name = name, kind = PropertyKind.ARRAY, elementKind = PropertyKind.STRING, description = description)
                    }
                }
                "object" -> InlineProperty(
                    name = name, kind = PropertyKind.OBJECT, description = description,
                    properties = fieldsOrNone(node, depth + 1),
                )
                // No type said: an object if it has properties, else a string, which holds anything a model writes.
                else -> if (node.path("properties").isObject) {
                    InlineProperty(name = name, kind = PropertyKind.OBJECT, description = description, properties = fieldsOrNone(node, depth + 1))
                } else {
                    InlineProperty(name = name, kind = PropertyKind.STRING, description = description)
                }
            }
        }

        /** An object's fields, or none past the depth the installation reads to. */
        private fun fieldsOrNone(node: JsonNode, depth: Int): List<InlineProperty> =
            if (depth > maxDepth) emptyList() else propertiesOf(node, depth)

        /** `{"$ref": "#/definitions/Address"}` read as the definition itself; anything else as it is. */
        private fun resolve(node: JsonNode, hops: Int = 0): JsonNode {
            val ref = node.path("\$ref").takeIf { it.isString }?.asText() ?: return node
            if (hops > maxDepth || !ref.startsWith("#/")) return mapper.createObjectNode().put("type", "object")
            var at: JsonNode = root
            ref.removePrefix("#/").split('/').forEach { step ->
                at = at.path(step.replace("~1", "/").replace("~0", "~"))
            }
            return if (at.isMissingNode) mapper.createObjectNode().put("type", "object") else resolve(at, hops + 1)
        }
    }

    private fun kindOf(type: String): PropertyKind = when (type) {
        "number", "integer" -> PropertyKind.NUMBER
        "boolean" -> PropertyKind.BOOLEAN
        "array" -> PropertyKind.ARRAY
        else -> PropertyKind.STRING
    }

    /** The schema's `type`, the first non-null one where it is a list, or null where it says none. */
    private fun typeOf(node: JsonNode): String? {
        val type = node.path("type")
        return when {
            type.isString -> type.asText()
            type.isArray -> type.values().map { it.asText() }.firstOrNull { it != "null" }
            else -> null
        }
    }

    private const val DESCRIPTION_LIMIT = 500
}

/** The text pasted is not a JSON Schema of an object, and [says] which way. */
class ObjectSchemaUnreadableException(val says: String) : RuntimeException("The schema could not be read: $says"), Refusal {
    override val arguments: Map<String, Any?> get() = mapOf("says" to says)
}
