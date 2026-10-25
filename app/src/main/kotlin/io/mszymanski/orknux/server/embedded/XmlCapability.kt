package io.mszymanski.orknux.server.embedded

import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import org.springframework.stereotype.Component
import org.w3c.dom.Attr
import org.w3c.dom.Element
import org.w3c.dom.Node
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * Questions about an XML file, answered without the file entering the turn.
 *
 * Chat 981: an agent on a local model was asked about a 465 KB KiCad copper
 * layer, read it whole with `svg_read`, and spent every round after that
 * re-reading about a hundred thousand tokens of coordinates - minutes a round
 * on the owner's hardware - before asking for a Python sandbox to count pads
 * in. A count is one number. So the file stays in the session's store and an
 * XPath 1.0 expression goes to it: what comes back is the count, a page of the
 * matching nodes with their attributes, or one value.
 *
 * Parsed by [HostileXml], which treats the file as the stranger's document it
 * is. The geometry XPath cannot do - where a shape is once its groups'
 * transforms are applied - is `svg_measure`, in [SvgCapability].
 */
@Component
class XmlCapability(
    private val files: StoredFiles,
    private val tuning: Tuning,
    private val mapper: ObjectMapper,
) : EmbeddedCapability {

    override val key = CAPABILITY
    override val name = "XML"

    override fun tools(): List<EmbeddedTool> = listOf(
        EmbeddedTool(
            name = QUERY,
            readOnly = true,
            summary = "Asks an XPath question of an XML or SVG file without reading it whole.",
            description = "Answers an XPath 1.0 question about an XML or SVG file kept under a $KEY in this session - " +
                "an attachment, a scratchpad, a kept answer - without the file entering the conversation: only the " +
                "answer does. Use it instead of reading a large file whole with ${SvgCapability.READ_TOOL}. " +
                "A node-set answers its $COUNT and a page of the nodes with their attributes and short text " +
                "(//svg:circle[@r > 0.5], //svg:g[@id='U3']/*); a number, string or boolean answers as a value " +
                "(count(//svg:path), sum(//item/@qty), string(//svg:title)). On an SVG write //svg:path or " +
                "//*[local-name()='path']; //path works too. Long attribute values such as a path's d are cut with " +
                "their length said, unless $FULL is true. For where SVG shapes are - sizes, positions, spacing, " +
                "groups - use ${SvgCapability.MEASURE_TOOL}.",
            params = listOf(
                EmbeddedParam(KEY, ValueType.STRING, "The key the XML or SVG file is kept under.", required = true),
                EmbeddedParam(XPATH, ValueType.STRING, "The XPath 1.0 expression.", required = true),
                EmbeddedParam(OFFSET, ValueType.NUMBER, "How many matching nodes to skip, for the next page. Left out, 0."),
                EmbeddedParam(LIMIT, ValueType.NUMBER, "How many matching nodes to list. Left out, as many as allowed."),
                EmbeddedParam(FULL, ValueType.BOOLEAN, "true to have long attribute values and text whole."),
            ),
            asks = StoredReader(ASKS, KEY),
        ),
    )

    override fun run(name: String, arguments: String, workspaceId: Long, sessionId: Long?): String {
        if (name != QUERY) return refusal("There is no tool called ${CAPABILITY}_$name.")
        val asked = runCatching { mapper.readTree(arguments) }.getOrNull() ?: return refusal("That is not valid JSON.")
        val expression = text(asked, XPATH)?.trim()?.ifEmpty { null } ?: return refusal("Give the $XPATH expression.")
        val bytes = when (val file = xmlFile(files, tuning, text(asked, KEY), sessionId)) {
            is Loaded.Refused -> return refusal(file.reason)
            is Loaded.File -> file.bytes
        }
        val (answer, plain) = try {
            HostileXml.query(bytes, expression).let { it.answer to it.namespacesIgnored }
        } catch (refused: HostileXml.Refused) {
            return refusal(refused.message.orEmpty())
        }
        val said = linkedMapOf<String, Any?>()
        when (answer) {
            is HostileXml.Answer.Value -> said[VALUE] = answer.value
            is HostileXml.Answer.Nodes -> {
                val most = tuning[Tunable.XML_MATCHES_MOST]
                val offset = number(asked, OFFSET)?.coerceAtLeast(0) ?: 0
                val limit = (number(asked, LIMIT)?.takeIf { it > 0 } ?: most).coerceAtMost(most)
                val cap = if (flag(asked, FULL)) Int.MAX_VALUE else tuning[Tunable.XML_ATTRIBUTE_CHARACTERS]
                val page = answer.nodes.drop(offset).take(limit)
                said[COUNT] = answer.nodes.size
                if (offset > 0) said[OFFSET] = offset
                said["nodes"] = page.map { described(it, cap) }
                val after = offset + page.size
                if (after < answer.nodes.size) {
                    said["more"] = "${answer.nodes.size - after} more; pass $OFFSET $after for the next ones"
                }
                if (plain) {
                    said["namespaces"] = "matched with namespaces ignored: the document's elements are in a namespace, " +
                        "so on it //svg:name or //*[local-name()='name'] is the strict spelling"
                }
            }
        }
        return mapper.writeValueAsString(said)
    }

    /** One node as a model reads it: what it is, its attributes, and its text where it has some. */
    private fun described(node: Node, cap: Int): Map<String, Any?> = when (node) {
        is Element -> linkedMapOf<String, Any?>("element" to node.nodeName).apply {
            val attributes = node.attributes
            if (attributes.length > 0) {
                put("attributes", (0 until attributes.length).map { attributes.item(it) }.associate { it.nodeName to cut(it.nodeValue, cap) })
            }
            val children = (0 until node.childNodes.length).count { node.childNodes.item(it).nodeType == Node.ELEMENT_NODE }
            if (children > 0) put("children", children)
            node.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { put("text", cut(it.replace(SPACES, " "), cap)) }
        }
        is Attr -> linkedMapOf("attribute" to node.name, "value" to cut(node.value, cap), "of" to node.ownerElement?.nodeName)
        else -> when (node.nodeType) {
            Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> mapOf("text" to cut(node.nodeValue.orEmpty(), cap))
            Node.COMMENT_NODE -> mapOf("comment" to cut(node.nodeValue.orEmpty(), cap))
            Node.DOCUMENT_NODE -> mapOf("document" to node.ownerDocumentOrSelf()?.documentElement?.nodeName)
            else -> mapOf("node" to node.nodeName, "value" to node.nodeValue?.let { cut(it, cap) })
        }
    }

    private fun Node.ownerDocumentOrSelf() = ownerDocument ?: (this as? org.w3c.dom.Document)

    private fun text(node: JsonNode, name: String): String? = node.path(name).takeIf { it.isString }?.stringValue()

    private fun refusal(said: String): String = mapper.writeValueAsString(mapOf("error" to said))

    companion object {
        const val CAPABILITY = "xml"
        const val QUERY = "query"

        /** What a model calls to ask an XPath question of a file, as the tool list names it. */
        const val QUERY_TOOL = CAPABILITY + "_" + QUERY

        const val KEY = "key"
        const val XPATH = "xpath"
        const val OFFSET = "offset"
        const val LIMIT = "limit"
        const val FULL = "full"
        const val COUNT = "count"
        const val VALUE = "value"

        /** The kinds of stored file it answers questions about. */
        val ASKS = setOf("application/xml", "text/xml", "image/svg+xml")

        private val SPACES = Regex("\\s+")

        /** A value cut to [cap] characters, with its length said where it was cut. */
        fun cut(value: String, cap: Int): String =
            if (value.length <= cap) value else value.take(cap) + "… (${value.length} characters)"

        fun number(node: JsonNode, name: String): Int? = node.path(name).let {
            when {
                it.isNumber -> it.intValue()
                it.isString -> it.stringValue().trim().toIntOrNull()
                else -> null
            }
        }

        fun flag(node: JsonNode, name: String): Boolean = node.path(name).let {
            (it.isBoolean && it.booleanValue()) || (it.isString && it.stringValue().trim().equals("true", ignoreCase = true))
        }
    }
}

/** A file read for a question, or why not. */
sealed interface Loaded {
    class File(val bytes: ByteArray) : Loaded
    class Refused(val reason: String) : Loaded
}

/** The file under [named], held to the size the installation parses. */
fun xmlFile(files: StoredFiles, tuning: Tuning, named: String?, sessionId: Long?): Loaded {
    if (named.isNullOrBlank()) return Loaded.Refused("Give the key the file is kept under.")
    val held = files.find(named, sessionId) ?: return Loaded.Refused(files.nothingUnder(named, sessionId))
    val most = tuning[Tunable.XML_FILE_MB]
    if (held.bytes.size > most.toLong() * 1024 * 1024) {
        return Loaded.Refused("\"$named\" is ${held.bytes.size / (1024 * 1024)} MB, and $most MB is the largest file this reads.")
    }
    return Loaded.File(held.bytes)
}
