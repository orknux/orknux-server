package io.mszymanski.orknux.server.design

import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * Reads, checks and writes a document's content: ProseMirror JSON, in the
 * vocabulary of the editor's TipTap extensions.
 *
 * Everything that reaches the table comes through [read], from the editor and
 * from a model alike, so the two cannot come to store different shapes. A node
 * of a kind the editor has no extension for is refused rather than kept, since
 * the editor would drop it silently the next time the document was opened and
 * saved; an attribute nobody reads is dropped. What it refuses it says, because
 * the reader of the refusal is as often a model as a person.
 *
 * A picture is held as an artifact id (`DESIGN-12`), never as its bytes: [read]
 * refuses a `data:` address, and writes the `src` an editor draws from itself,
 * from the id - so what a picture is called is the server's to say, and an
 * address a document carries cannot point anywhere else. Whether the picture is
 * there is [DocumentAPI]'s to ask, of a picture newly put in: one deleted since
 * it was put in a document must not make the document unsaveable.
 */
class DocumentContent(private val mapper: ObjectMapper) {

    /** The content as stored or as sent; an empty text is an empty document. */
    fun read(content: String?, workspaceId: Long): ObjectNode {
        if (content.isNullOrBlank()) return empty()
        val tree = try {
            mapper.readTree(content)
        } catch (_: Exception) {
            throw DocumentContentInvalidException("it is not JSON")
        }
        return checked(tree, workspaceId)
    }

    /** A tree as sent, checked and given back in the shape that is stored. */
    fun checked(tree: JsonNode, workspaceId: Long): ObjectNode {
        if (!tree.isObject || text(tree.path("type")) != "doc") {
            throw DocumentContentInvalidException("it has to be a doc node, {\"type\":\"doc\",\"content\":[...]}")
        }
        val made = mapper.createObjectNode().put("type", "doc")
        val blocks = made.putArray("content")
        children(tree, "doc", 0, workspaceId).forEach { blocks.add(it) }
        if (blocks.isEmpty) blocks.add(paragraph())
        return made
    }

    fun empty(): ObjectNode = mapper.createObjectNode().put("type", "doc").also { it.putArray("content").add(paragraph()) }

    fun write(doc: ObjectNode): String = mapper.writeValueAsString(doc)

    /** The words of a document, a block to a line: what the search reads. */
    fun plainText(doc: JsonNode): String {
        val out = StringBuilder()
        fun walk(node: JsonNode) {
            when (text(node.path("type"))) {
                "text" -> out.append(text(node.path("text")).orEmpty())
                "hardBreak" -> out.append('\n')
                "image" -> text(node.path("attrs").path("caption"))?.let { out.append(it).append('\n') }
                else -> {
                    val inner = node.path("content")
                    for (at in 0 until inner.size()) walk(inner.get(at))
                    if (text(node.path("type")) in LINE_ENDED && out.isNotEmpty() && out.last() != '\n') out.append('\n')
                }
            }
        }
        walk(doc)
        return out.toString().trim()
    }

    /** The artifact ids of the pictures a document shows, in order, each once. */
    fun images(doc: JsonNode): List<String> {
        val found = linkedSetOf<String>()
        fun walk(node: JsonNode) {
            if (text(node.path("type")) == "image") text(node.path("attrs").path("artifact"))?.let { found += it }
            val inner = node.path("content")
            for (at in 0 until inner.size()) walk(inner.get(at))
        }
        walk(doc)
        return found.toList()
    }

    /** The paragraph a new or emptied document holds, so the editor has somewhere to put the caret. */
    fun paragraph(): ObjectNode = mapper.createObjectNode().put("type", "paragraph")

    /** Where an editor draws a picture from: the artifact's bytes, for whoever may see the workspace. */
    fun source(workspaceId: Long, artifactId: String): String = "/api/workspaces/$workspaceId/document-images/$artifactId"

    private fun children(parent: JsonNode, parentType: String, depth: Int, workspaceId: Long): List<ObjectNode> {
        val inner = parent.path("content")
        if (inner.isMissingNode || inner.isNull) return emptyList()
        if (!inner.isArray) throw DocumentContentInvalidException("the content of a $parentType has to be a list")
        return (0 until inner.size()).mapNotNull { at -> node(inner.get(at), parentType, depth + 1, workspaceId) }
    }

    private fun node(node: JsonNode, parentType: String, depth: Int, workspaceId: Long): ObjectNode? {
        if (depth > Documents.DEPTH) throw DocumentContentInvalidException("it nests deeper than ${Documents.DEPTH} levels")
        if (!node.isObject) throw DocumentContentInvalidException("a node in a $parentType is not an object")
        val type = text(node.path("type")) ?: throw DocumentContentInvalidException("a node in a $parentType has no type")
        if (type !in KINDS) {
            throw DocumentContentInvalidException("a $parentType holds a node of type $type, which is not one of ${KINDS.joinToString(", ")}")
        }
        val made = mapper.createObjectNode().put("type", type)
        val attrs = node.path("attrs")
        when (type) {
            "text" -> {
                val words = text(node.path("text")) ?: return null
                if (words.isEmpty()) return null
                made.put("text", words)
                marks(node.path("marks"))?.let { made.set("marks", it) }
                return made
            }
            "heading" -> made.putObject("attrs").put("level", (int(attrs.path("level")) ?: 1).coerceIn(1, 6))
            "orderedList" -> int(attrs.path("start"))?.takeIf { it > 1 }?.let { made.putObject("attrs").put("start", it) }
            "codeBlock" -> text(attrs.path("language"))?.trim()?.takeIf { it.isNotEmpty() && it.length <= 40 }
                ?.let { made.putObject("attrs").put("language", it) }
            "tableCell", "tableHeader" -> {
                val kept = mapper.createObjectNode()
                int(attrs.path("colspan"))?.takeIf { it in 2..50 }?.let { kept.put("colspan", it) }
                int(attrs.path("rowspan"))?.takeIf { it in 2..50 }?.let { kept.put("rowspan", it) }
                val widths = attrs.path("colwidth")
                if (widths.isArray && widths.size() in 1..50) {
                    val list = kept.putArray("colwidth")
                    for (at in 0 until widths.size()) list.add((int(widths.get(at)) ?: 0).coerceIn(0, 4000))
                }
                if (!kept.isEmpty) made.set("attrs", kept)
            }
            "image" -> {
                made.set("attrs", picture(attrs, workspaceId))
                return made
            }
        }
        if (type in LEAVES) return made
        val inner = children(node, type, depth, workspaceId)
        if (inner.isNotEmpty()) made.putArray("content").also { list -> inner.forEach { list.add(it) } }
        return made
    }

    private fun picture(attrs: JsonNode, workspaceId: Long): ObjectNode {
        val artifact = text(attrs.path("artifact"))?.trim()
            ?: throw DocumentContentInvalidException(
                if (text(attrs.path("src"))?.startsWith("data:") == true) {
                    "a picture is kept in the artifact store and named by its id, never written into the document"
                } else {
                    "a picture names no artifact; give attrs.artifact, such as DESIGN-12"
                },
            )
        if (!ARTIFACT.matches(artifact)) throw DocumentContentInvalidException("$artifact is not an artifact id, such as DESIGN-12")
        val kept = mapper.createObjectNode()
        kept.put("artifact", artifact)
        kept.put("src", source(workspaceId, artifact))
        text(attrs.path("alt"))?.trim()?.takeIf { it.isNotEmpty() }?.let { kept.put("alt", it.take(500)) }
        text(attrs.path("caption"))?.trim()?.takeIf { it.isNotEmpty() }?.let { kept.put("caption", it.take(1000)) }
        int(attrs.path("width"))?.let { kept.put("width", it.coerceIn(5, 100)) }
        return kept
    }

    private fun marks(node: JsonNode): ArrayNode? {
        if (!node.isArray || node.isEmpty) return null
        val kept = mapper.createArrayNode()
        val seen = mutableSetOf<String>()
        for (at in 0 until node.size()) {
            val mark = node.get(at)
            val type = text(mark.path("type")) ?: continue
            if (type !in MARKS) throw DocumentContentInvalidException("a mark of type $type is not one of ${MARKS.joinToString(", ")}")
            if (!seen.add(type)) continue
            val made = mapper.createObjectNode().put("type", type)
            if (type == "link") {
                // An address that runs something when followed is not kept; the words stay, unlinked.
                val href = text(mark.path("attrs").path("href"))?.trim()?.takeIf { safeLink(it) } ?: continue
                made.putObject("attrs").put("href", href.take(2000))
            }
            kept.add(made)
        }
        return kept.takeIf { !it.isEmpty }
    }

    private fun text(node: JsonNode): String? = when {
        node.isString -> node.stringValue()
        node.isNumber || node.isBoolean -> node.asText()
        else -> null
    }

    private fun int(node: JsonNode): Int? = when {
        node.isNumber -> node.asDouble().takeIf { it.isFinite() }?.let { Math.round(it).toInt() }
        node.isString -> node.stringValue()?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { Math.round(it).toInt() }
        else -> null
    }

    companion object {
        /** Every node the editor has an extension for. */
        val KINDS = listOf(
            "paragraph", "heading", "text", "bulletList", "orderedList", "listItem", "blockquote", "codeBlock",
            "horizontalRule", "hardBreak", "pageBreak", "image", "table", "tableRow", "tableHeader", "tableCell",
        )

        val MARKS = listOf("bold", "italic", "underline", "strike", "code", "link")

        /** Nodes that hold nothing. */
        private val LEAVES = setOf("horizontalRule", "hardBreak", "pageBreak")

        /** Nodes that end a line in the plain text. */
        private val LINE_ENDED = setOf("paragraph", "heading", "codeBlock", "tableCell", "tableHeader", "listItem")

        /** An id the Artifacts page gives: its kind, and the row in that kind's table. */
        val ARTIFACT = Regex("^(IMAGE|TASK|SAVED|DESIGN)-[0-9]{1,18}$")

        /** A web or mail address, or one on this installation; never a script. */
        fun safeLink(href: String): Boolean {
            val lower = href.lowercase()
            return lower.startsWith("https://") || lower.startsWith("http://") || lower.startsWith("mailto:") ||
                (lower.startsWith("/") && !lower.startsWith("//")) || lower.startsWith("#")
        }
    }
}
