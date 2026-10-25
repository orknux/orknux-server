package io.mszymanski.orknux.server.design

import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

/** What an imported file's picture is kept as: its bytes and a name go in, an artifact id - or null, left out - comes back. */
typealias PictureKeeper = (bytes: ByteArray, name: String?) -> String?

/** A mark on imported words: bold, italic, underline, strike, code, or a link to [href]. */
data class ImportMark(val type: String, val href: String? = null)

/**
 * The nodes of a document as an imported file is read into them, shared by
 * every reader so each writes the shape [DocumentContent] checks - a list item
 * opening with a paragraph, a picture a block of its own, a table cell holding
 * blocks - without each saying it again.
 *
 * A [Flow] is one run of blocks being written: words gather into the paragraph
 * being written until a block arrives, which ends it, so a picture standing in
 * the middle of a paragraph splits it in two as the editor would draw it.
 */
class DocumentNodes(private val mapper: ObjectMapper) {

    fun node(type: String): ObjectNode = mapper.createObjectNode().put("type", type)

    fun doc(blocks: List<ObjectNode>): ObjectNode {
        val made = node("doc")
        val content = made.putArray("content")
        blocks.forEach { content.add(it) }
        if (content.isEmpty) content.add(node("paragraph"))
        return made
    }

    fun text(words: String, marks: Collection<ImportMark>): ObjectNode {
        val made = node("text").put("text", words)
        if (marks.isNotEmpty()) {
            val list = made.putArray("marks")
            marks.distinctBy { it.type }.forEach { mark ->
                val one = list.addObject().put("type", mark.type)
                mark.href?.let { one.putObject("attrs").put("href", it) }
            }
        }
        return made
    }

    fun hardBreak(): ObjectNode = node("hardBreak")

    fun withInline(type: String, inline: List<ObjectNode>): ObjectNode {
        val made = node(type)
        val trimmed = trimmed(inline)
        if (trimmed.isNotEmpty()) made.putArray("content").also { list -> trimmed.forEach { list.add(it) } }
        return made
    }

    fun paragraph(inline: List<ObjectNode> = emptyList()): ObjectNode = withInline("paragraph", inline)

    fun heading(level: Int, inline: List<ObjectNode>): ObjectNode =
        withInline("heading", inline).also { it.putObject("attrs").put("level", level.coerceIn(1, 6)) }

    fun container(type: String, blocks: List<ObjectNode>): ObjectNode {
        val made = node(type)
        val content = made.putArray("content")
        blocks.forEach { content.add(it) }
        if (content.isEmpty) content.add(paragraph())
        return made
    }

    /** A list item: what the editor's opens with, a paragraph, then whatever else it holds. */
    fun listItem(blocks: List<ObjectNode>): ObjectNode {
        val inner = blocks.toMutableList()
        if (inner.firstOrNull()?.path("type")?.asText() != "paragraph") inner.add(0, paragraph())
        return container("listItem", inner)
    }

    fun list(ordered: Boolean, items: List<ObjectNode>, start: Int? = null): ObjectNode {
        val made = node(if (ordered) "orderedList" else "bulletList")
        if (ordered && start != null && start > 1) made.putObject("attrs").put("start", start)
        val content = made.putArray("content")
        items.forEach { content.add(it) }
        if (content.isEmpty) content.add(listItem(emptyList()))
        return made
    }

    fun code(text: String, language: String? = null): ObjectNode {
        val made = node("codeBlock")
        language?.trim()?.takeIf { it.isNotEmpty() && it.length <= 40 }?.let { made.putObject("attrs").put("language", it) }
        val body = text.trimEnd('\n')
        if (body.isNotEmpty()) made.putArray("content").add(node("text").put("text", body))
        return made
    }

    fun image(artifact: String, alt: String?, caption: String? = null): ObjectNode {
        val made = node("image")
        val attrs = made.putObject("attrs").put("artifact", artifact)
        alt?.trim()?.takeIf { it.isNotEmpty() }?.let { attrs.put("alt", it) }
        caption?.trim()?.takeIf { it.isNotEmpty() }?.let { attrs.put("caption", it) }
        return made
    }

    /** A cell: a header or not, spanning what it spans, holding blocks. */
    fun cell(header: Boolean, blocks: List<ObjectNode>, colspan: Int = 1, rowspan: Int = 1): ObjectNode {
        val made = container(if (header) "tableHeader" else "tableCell", blocks)
        if (colspan > 1 || rowspan > 1) {
            val attrs = mapper.createObjectNode()
            if (colspan > 1) attrs.put("colspan", colspan.coerceAtMost(50))
            if (rowspan > 1) attrs.put("rowspan", rowspan.coerceAtMost(50))
            made.set("attrs", attrs)
        }
        return made
    }

    /** A table of rows of cells; null for one with no cells at all, which the editor could not draw. */
    fun table(rows: List<List<ObjectNode>>): ObjectNode? {
        val kept = rows.filter { it.isNotEmpty() }
        if (kept.isEmpty()) return null
        val made = node("table")
        val content = made.putArray("content")
        kept.forEach { cells -> content.add(node("tableRow").also { row -> row.putArray("content").also { list -> cells.forEach { list.add(it) } } }) }
        return made
    }

    /**
     * Inline nodes with the white space a file's layout left at their ends
     * taken off, and runs of it inside folded to one space - but not inside
     * code, where it is the code.
     */
    private fun trimmed(inline: List<ObjectNode>): List<ObjectNode> {
        val out = inline.toMutableList()
        while (out.firstOrNull()?.let { isText(it) && it.path("text").asText("").isBlank() } == true) out.removeAt(0)
        while (out.lastOrNull()?.let { (isText(it) && it.path("text").asText("").isBlank()) || it.path("type").asText() == "hardBreak" } == true) {
            out.removeAt(out.size - 1)
        }
        out.firstOrNull()?.takeIf { isText(it) }?.let { it.put("text", it.path("text").asText("").trimStart()) }
        out.lastOrNull()?.takeIf { isText(it) }?.let { it.put("text", it.path("text").asText("").trimEnd()) }
        return out.filter { !isText(it) || it.path("text").asText("").isNotEmpty() }
    }

    private fun isText(node: ObjectNode) = node.path("type").asText() == "text"

    /** One run of blocks being written. */
    inner class Flow {
        val blocks = mutableListOf<ObjectNode>()
        private val inline = mutableListOf<ObjectNode>()

        fun words(words: String, marks: Collection<ImportMark>) {
            if (words.isEmpty()) return
            // Two words either side of a space that ends one node and starts the next are one space apart.
            val last = inline.lastOrNull()
            val said = if (last != null && isText(last) && last.path("text").asText("").endsWith(" ") && words.startsWith(" ")) words.trimStart() else words
            if (said.isNotEmpty()) inline += text(said, marks)
        }

        fun lineBreak() {
            inline += hardBreak()
        }

        /** Whether the paragraph being written holds anything but white space yet. */
        fun writing(): Boolean = inline.any { !isText(it) || it.path("text").asText("").isNotBlank() }

        fun block(node: ObjectNode) {
            end()
            blocks += node
        }

        /** Ends the paragraph being written, if it holds anything. */
        fun end() {
            if (writing()) blocks += paragraph(inline.toList())
            inline.clear()
        }

        fun done(): List<ObjectNode> {
            end()
            return blocks
        }
    }
}
