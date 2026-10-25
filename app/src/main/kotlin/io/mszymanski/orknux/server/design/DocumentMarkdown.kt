package io.mszymanski.orknux.server.design

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.util.concurrent.ThreadLocalRandom

/**
 * A document as Markdown, and Markdown as a document.
 *
 * Markdown is what a model reads a document as and writes one in: it is the
 * shape of text a model knows best, and a ProseMirror tree spelled out in JSON
 * costs it four times the tokens to read and is easy to get subtly wrong when
 * written. So the orknux_* tools read and write Markdown, and the content that
 * is stored is still the editor's tree - this is the translation between them,
 * and the Markdown export is the same writer with the pictures addressed as
 * files beside it.
 *
 * Read with commonmark-java and its GFM table and strikethrough extensions, the
 * parser the server already reads reports with, rather than by hand. What
 * Markdown has no word for is written as the one HTML a reader can ignore: a
 * page break is `<!-- pagebreak -->` on a line of its own, underlined words
 * are `<u>...</u>`, coloured words `<span style="color:#c0392b">...</span>`
 * and a highlight with its note `<mark data-note="..." data-note-id="...">...</mark>`
 * - the editor's own HTML for its two marks, so a model that reads them can
 * write them back. Reading them back keeps only what the editor would: a
 * colour [DocumentContent.colour] accepts, a note cut to [Documents.NOTE_LENGTH],
 * an id of the editor's shape; anything else a tag carries is dropped, and a
 * tag of any other name stays as the words it was written as. A picture is `![alt](artifact:DESIGN-12 "caption")`; a
 * picture at a web address is not fetched, so it is kept as a link to it.
 *
 * @param pictureOf the artifact id a picture's address names, or null for one
 *   kept as a link; an import of Export's zip answers for the files beside it.
 * @param tags whether colours and highlights are written as their tags: for
 *   the Markdown a model reads and writes back, and not for an export or a
 *   version's history, which a person reads as words.
 */
class DocumentMarkdown(
    private val mapper: ObjectMapper,
    private val pictureOf: (String?) -> String? = { artifactOf(it) },
    private val tags: Boolean = false,
) {

    private val parser: Parser = Parser.builder()
        .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
        .build()

    // -- Writing -------------------------------------------------------------

    /**
     * The document as Markdown.
     *
     * @param picture where a picture is addressed, from its artifact id; the
     *   model is given `artifact:DESIGN-12`, an export the file it is saved as.
     */
    fun write(doc: JsonNode, picture: (String) -> String = { "$ARTIFACT_SCHEME$it" }): String =
        blocks(doc.path("content"), picture).joinToString("\n\n").trimEnd() + "\n"

    /** Each top-level block on its own, as written: what the outline a model reads is numbered by. */
    fun blocksOf(doc: JsonNode, picture: (String) -> String = { "$ARTIFACT_SCHEME$it" }): List<String> =
        (0 until doc.path("content").size()).map { block(doc.path("content").get(it), picture) }

    private fun blocks(list: JsonNode, picture: (String) -> String): List<String> =
        (0 until list.size()).map { block(list.get(it), picture) }.filter { it.isNotEmpty() }

    private fun block(node: JsonNode, picture: (String) -> String): String = when (type(node)) {
        "paragraph" -> inline(node.path("content"))
        "heading" -> "#".repeat(node.path("attrs").path("level").asInt(1).coerceIn(1, 6)) + " " + inline(node.path("content"))
        "blockquote" -> blocks(node.path("content"), picture).joinToString("\n\n").lines().joinToString("\n") { if (it.isEmpty()) ">" else "> $it" }
        "bulletList" -> items(node, picture) { "- " }
        "orderedList" -> {
            val start = node.path("attrs").path("start").asInt(1)
            items(node, picture) { at -> "${start + at}. " }
        }
        "codeBlock" -> {
            val code = text(node)
            val fence = if (code.contains("```")) "~~~~" else "```"
            fence + node.path("attrs").path("language").asText("") + "\n" + code.trimEnd('\n') + "\n" + fence
        }
        "horizontalRule" -> "---"
        "pageBreak" -> PAGE_BREAK
        "image" -> image(node.path("attrs"), picture)
        "table" -> table(node)
        else -> inline(node.path("content"))
    }

    private fun items(list: JsonNode, picture: (String) -> String, marker: (Int) -> String): String {
        val items = list.path("content")
        return (0 until items.size()).joinToString("\n") { at ->
            val lead = marker(at)
            val inner = blocks(items.get(at).path("content"), picture).joinToString("\n\n").ifEmpty { "" }
            inner.lines().mapIndexed { line, text ->
                when {
                    line == 0 -> lead + text
                    text.isEmpty() -> ""
                    else -> " ".repeat(lead.length) + text
                }
            }.joinToString("\n")
        }
    }

    private fun image(attrs: JsonNode, picture: (String) -> String): String {
        val artifact = attrs.path("artifact").asText("")
        val alt = attrs.path("alt").asText("").ifEmpty { attrs.path("caption").asText("") }
        val caption = attrs.path("caption").asText("")
        val title = if (caption.isEmpty()) "" else " \"" + caption.replace("\"", "\\\"") + "\""
        return "![" + escape(alt) + "](" + picture(artifact) + title + ")"
    }

    private fun table(node: JsonNode): String {
        val rows = node.path("content")
        if (rows.isEmpty) return ""
        val cells = (0 until rows.size()).map { row ->
            val inner = rows.get(row).path("content")
            (0 until inner.size()).map { cell ->
                val paragraphs = inner.get(cell).path("content")
                (0 until paragraphs.size()).joinToString("<br>") { inline(paragraphs.get(it).path("content")) }
                    .replace("|", "\\|").replace("\n", "<br>")
            }
        }
        val width = cells.maxOf { it.size }.coerceAtLeast(1)
        fun line(row: List<String>) = "| " + (0 until width).joinToString(" | ") { row.getOrElse(it) { "" } } + " |"
        return buildList {
            add(line(cells.first()))
            add("|" + (0 until width).joinToString("|") { " --- " } + "|")
            cells.drop(1).forEach { add(line(it)) }
        }.joinToString("\n")
    }

    private fun inline(list: JsonNode): String {
        val out = StringBuilder()
        for (at in 0 until list.size()) {
            val node = list.get(at)
            when (type(node)) {
                "text" -> out.append(marked(node))
                "hardBreak" -> out.append("\\\n")
                else -> out.append(inline(node.path("content")))
            }
        }
        return out.toString()
    }

    private fun marked(node: JsonNode): String {
        val raw = node.path("text").asText("")
        val marks = node.path("marks")
        val has = (0 until marks.size()).associate { type(marks.get(it)) to marks.get(it) }
        if (raw.isBlank()) return raw
        // Emphasis has to hug the words, so the spaces around them stay outside the marks.
        val lead = raw.takeWhile { it == ' ' }
        val tail = raw.takeLastWhile { it == ' ' }
        var text = if ("code" in has) {
            val ticks = if (raw.contains('`')) "``" else "`"
            "$ticks${raw.trim()}$ticks"
        } else {
            escape(raw.trim())
        }
        if ("bold" in has) text = "**$text**"
        if ("italic" in has) text = "*$text*"
        if ("strike" in has) text = "~~$text~~"
        if ("underline" in has) text = "<u>$text</u>"
        has["link"]?.let { text = "[$text](" + it.path("attrs").path("href").asText("") + ")" }
        if (!tags) return lead + text + tail
        has["textStyle"]?.let { DocumentContent.colour(it.path("attrs").path("color").asText("")) }?.let {
            text = "<span style=\"color:$it\">$text</span>"
        }
        has["note"]?.path("attrs")?.let { attrs ->
            val id = attrs.path("id").asText("").takeIf { DocumentContent.NOTE_ID.matches(it) }
            text = "<mark data-note=\"" + attribute(attrs.path("note").asText("")) + "\"" +
                (if (id == null) "" else " data-note-id=\"$id\"") + ">$text</mark>"
        }
        return lead + text + tail
    }

    // -- Reading -------------------------------------------------------------

    /** Markdown as the blocks of a document; checked afterwards as anything stored is. */
    fun read(markdown: String): List<ObjectNode> {
        val root = parser.parse(markdown.replace("\r\n", "\n"))
        return blockChildren(root)
    }

    /** Markdown as a whole document. */
    fun document(markdown: String): ObjectNode {
        val doc = mapper.createObjectNode().put("type", "doc")
        val content = doc.putArray("content")
        read(markdown).forEach { content.add(it) }
        if (content.isEmpty) content.add(mapper.createObjectNode().put("type", "paragraph"))
        return doc
    }

    private fun blockChildren(parent: Node): List<ObjectNode> {
        val out = mutableListOf<ObjectNode>()
        var child = parent.firstChild
        while (child != null) {
            out += readBlock(child)
            child = child.next
        }
        return out
    }

    private fun readBlock(node: Node): List<ObjectNode> = when (node) {
        is Heading -> listOf(withInline(mapper.createObjectNode().put("type", "heading").also { it.putObject("attrs").put("level", node.level.coerceIn(1, 6)) }, node))
        is Paragraph -> paragraphs(node)
        is BlockQuote -> listOf(container("blockquote", blockChildren(node)))
        is BulletList -> listOf(list("bulletList", node, null))
        is OrderedList -> listOf(list("orderedList", node, node.markerStartNumber ?: 1))
        is FencedCodeBlock -> listOf(code(node.literal, node.info?.trim()?.substringBefore(' ')))
        is IndentedCodeBlock -> listOf(code(node.literal, null))
        is ThematicBreak -> listOf(mapper.createObjectNode().put("type", "horizontalRule"))
        is HtmlBlock -> if (PAGE_BREAK_HTML.matches(node.literal.trim())) {
            listOf(mapper.createObjectNode().put("type", "pageBreak"))
        } else {
            listOf(paragraphOf(node.literal.trim()))
        }
        is TableBlock -> listOf(table(node))
        else -> paragraphs(node)
    }

    private fun container(type: String, children: List<ObjectNode>): ObjectNode {
        val made = mapper.createObjectNode().put("type", type)
        val content = made.putArray("content")
        children.forEach { content.add(it) }
        if (content.isEmpty) content.add(mapper.createObjectNode().put("type", "paragraph"))
        return made
    }

    private fun list(type: String, node: Node, start: Int?): ObjectNode {
        val made = mapper.createObjectNode().put("type", type)
        if (start != null && start > 1) made.putObject("attrs").put("start", start)
        val items = made.putArray("content")
        var item = node.firstChild
        while (item != null) {
            if (item is ListItem) {
                val inner = blockChildren(item).toMutableList()
                // An item opens with a paragraph, as the editor's list item does.
                if (inner.firstOrNull()?.path("type")?.asText() != "paragraph") inner.add(0, mapper.createObjectNode().put("type", "paragraph"))
                items.add(container("listItem", inner))
            }
            item = item.next
        }
        return made
    }

    private fun code(literal: String, language: String?): ObjectNode {
        val made = mapper.createObjectNode().put("type", "codeBlock")
        language?.takeIf { it.isNotEmpty() }?.let { made.putObject("attrs").put("language", it) }
        val text = literal.trimEnd('\n')
        if (text.isNotEmpty()) made.putArray("content").add(mapper.createObjectNode().put("type", "text").put("text", text))
        return made
    }

    private fun table(node: TableBlock): ObjectNode {
        val made = mapper.createObjectNode().put("type", "table")
        val rows = made.putArray("content")
        fun walk(parent: Node) {
            var child = parent.firstChild
            while (child != null) {
                if (child is TableRow) {
                    val row = mapper.createObjectNode().put("type", "tableRow")
                    val cells = row.putArray("content")
                    var cell = child.firstChild
                    while (cell != null) {
                        if (cell is TableCell) {
                            val paragraph = mapper.createObjectNode().put("type", "paragraph")
                            val inline = inlineOf(cell)
                            if (!inline.isEmpty) paragraph.set("content", inline)
                            cells.add(container(if (cell.isHeader) "tableHeader" else "tableCell", listOf(paragraph)))
                        }
                        cell = cell.next
                    }
                    rows.add(row)
                } else {
                    walk(child)
                }
                child = child.next
            }
        }
        walk(node)
        return made
    }

    /** A paragraph, split where a picture stands in it: a picture is a block of its own in a page. */
    private fun paragraphs(node: Node): List<ObjectNode> {
        val out = mutableListOf<ObjectNode>()
        var current = mapper.createArrayNode()
        fun flush() {
            if (current.isEmpty) return
            out += mapper.createObjectNode().put("type", "paragraph").also { it.set("content", current) }
            current = mapper.createArrayNode()
        }
        walkInline(node, emptyList(), current) { picture ->
            flush()
            out += picture
            current
        }.let { current = it }
        flush()
        if (out.isEmpty()) out += mapper.createObjectNode().put("type", "paragraph")
        return out
    }

    private fun withInline(made: ObjectNode, node: Node): ObjectNode {
        val inline = inlineOf(node)
        if (!inline.isEmpty) made.set("content", inline)
        return made
    }

    /** The inline content of a node whose pictures, if any, are kept as their alt text. */
    private fun inlineOf(node: Node): ArrayNode {
        var into = mapper.createArrayNode()
        into = walkInline(node, emptyList(), into) { picture ->
            val alt = picture.path("attrs").path("alt").asText("")
            if (alt.isNotEmpty()) into.add(mapper.createObjectNode().put("type", "text").put("text", alt))
            into
        }
        return into
    }

    /**
     * Walks inline Markdown into text nodes carrying their marks. A picture is
     * handed to [onPicture], which answers the list to go on writing into.
     */
    private fun walkInline(parent: Node, marks: List<ObjectNode>, start: ArrayNode, onPicture: (ObjectNode) -> ArrayNode): ArrayNode {
        var into = start
        // The tags open at this level, innermost last: <u>, and the editor's <span> and <mark>, each with the
        // mark it stands for - or none, for a span whose colour was not one, which still has to be closed.
        val opened = mutableListOf<Pair<String, ObjectNode?>>()
        var child = parent.firstChild
        while (child != null) {
            val active = opened.fold(marks) { held, open ->
                val mark: ObjectNode = open.second ?: return@fold held
                held.filterNot { type(it) == type(mark) } + listOf(mark)
            }
            when (child) {
                is Text -> into.add(textNode(child.literal, active))
                is Code -> into.add(textNode(child.literal, active + listOf(mark("code"))))
                is Emphasis -> into = walkInline(child, active + listOf(mark("italic")), into, onPicture)
                is StrongEmphasis -> into = walkInline(child, active + listOf(mark("bold")), into, onPicture)
                is Strikethrough -> into = walkInline(child, active + listOf(mark("strike")), into, onPicture)
                is Link -> into = walkInline(child, active + listOf(mark("link").also { it.putObject("attrs").put("href", child.destination) }), into, onPicture)
                is SoftLineBreak -> into.add(textNode(" ", active))
                is HardLineBreak -> into.add(mapper.createObjectNode().put("type", "hardBreak"))
                is HtmlInline -> {
                    val tag = child.literal.trim()
                    val open = OPEN_TAG.matchEntire(tag)?.groupValues?.get(1)?.lowercase()
                    val close = CLOSE_TAG.matchEntire(tag)?.groupValues?.get(1)?.lowercase()
                    when {
                        open == "u" -> opened += "u" to mark("underline")
                        open == "span" -> opened += "span" to colourOf(tag)
                        open == "mark" -> opened += "mark" to noteOf(tag)
                        close != null -> opened.indexOfLast { it.first == close }.takeIf { it >= 0 }?.let { opened.removeAt(it) }
                        tag.lowercase() in BREAKS -> into.add(mapper.createObjectNode().put("type", "hardBreak"))
                        else -> into.add(textNode(child.literal, active))
                    }
                }
                is Image -> {
                    val alt = plain(child)
                    val artifact = pictureOf(child.destination)
                    if (artifact == null) {
                        // Not fetched: a picture elsewhere stays a link to it.
                        into.add(textNode(alt.ifEmpty { child.destination }, active + listOf(mark("link").also { it.putObject("attrs").put("href", child.destination) })))
                    } else {
                        val picture = mapper.createObjectNode().put("type", "image")
                        val attrs = picture.putObject("attrs").put("artifact", artifact)
                        if (alt.isNotEmpty()) attrs.put("alt", alt)
                        child.title?.takeIf { it.isNotBlank() }?.let { attrs.put("caption", it) }
                        into = onPicture(picture)
                    }
                }
                else -> into = walkInline(child, active, into, onPicture)
            }
            child = child.next
        }
        return into
    }

    private fun plain(node: Node): String {
        val out = StringBuilder()
        var child = node.firstChild
        while (child != null) {
            when (child) {
                is Text -> out.append(child.literal)
                is Code -> out.append(child.literal)
                else -> out.append(plain(child))
            }
            child = child.next
        }
        return out.toString()
    }

    private fun paragraphOf(text: String): ObjectNode {
        val made = mapper.createObjectNode().put("type", "paragraph")
        if (text.isNotEmpty()) made.putArray("content").add(textNode(text, emptyList()))
        return made
    }

    private fun textNode(text: String, marks: List<ObjectNode>): ObjectNode {
        val made = mapper.createObjectNode().put("type", "text").put("text", text)
        if (marks.isNotEmpty()) made.putArray("marks").also { list -> marks.distinctBy { it.path("type").asText() }.forEach { list.add(it.deepCopy()) } }
        return made
    }

    private fun mark(type: String): ObjectNode = mapper.createObjectNode().put("type", type)

    /** The colour a `<span>` gives its words, read as the editor reads one; null when it gives none. */
    private fun colourOf(tag: String): ObjectNode? {
        val style = attributesOf(tag, "span")?.attr("style") ?: return null
        val colour = style.split(';')
            .map { it.substringBefore(':').trim().lowercase() to it.substringAfter(':', "") }
            .lastOrNull { it.first == "color" }?.second
            ?.let { DocumentContent.colour(it) } ?: return null
        return mark("textStyle").also { it.putObject("attrs").put("color", colour) }
    }

    /** The highlight a `<mark>` is: its note, cut to what a note may be, and its id, or a new one. */
    private fun noteOf(tag: String): ObjectNode {
        val element = attributesOf(tag, "mark")
        val note = element?.attr("data-note")?.trim().orEmpty().take(Documents.NOTE_LENGTH)
        val id = element?.attr("data-note-id")?.trim()?.takeIf { DocumentContent.NOTE_ID.matches(it) } ?: newNoteId()
        return mark("note").also { it.putObject("attrs").put("note", note).put("id", id) }
    }

    /** One opening tag's attributes, entities decoded, read by the HTML parser rather than by a pattern. */
    private fun attributesOf(tag: String, name: String): Element? = Jsoup.parseBodyFragment(tag).body().selectFirst(name)

    /** A value written into a tag's attribute: whatever would end it, or a table cell, as an entity. */
    private fun attribute(value: String): String = value
        .replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("|", "&#124;").replace("\r", "").replace("\n", "&#10;")

    private fun type(node: JsonNode): String = node.path("type").asText("")

    private fun text(node: JsonNode): String {
        val inner = node.path("content")
        return (0 until inner.size()).joinToString("") { inner.get(it).path("text").asText("") }
    }

    /** Backslashes in front of what Markdown would read as markup. */
    private fun escape(text: String): String = text.replace(MARKUP) { "\\" + it.value }

    companion object {
        /** How a picture in this workspace is addressed in Markdown a model reads or writes. */
        const val ARTIFACT_SCHEME = "artifact:"

        const val PAGE_BREAK = "<!-- pagebreak -->"

        private val PAGE_BREAK_HTML = Regex("<!--\\s*page[ -]?break\\s*-->", RegexOption.IGNORE_CASE)

        private val MARKUP = Regex("[\\\\`*_\\[\\]<>]")

        /** The tags that stand for a mark: the editor's own, and no others. */
        private val OPEN_TAG = Regex("^<(u|span|mark)(\\s[^<>]*)?>$", RegexOption.IGNORE_CASE)

        private val CLOSE_TAG = Regex("^</(u|span|mark)\\s*>$", RegexOption.IGNORE_CASE)

        private val BREAKS = setOf("<br>", "<br/>", "<br />")

        /** A highlight's id in the shape the editor makes one (`newNoteId`), for a highlight a model wrote without one. */
        fun newNoteId(): String =
            "n" + java.lang.Long.toString(System.currentTimeMillis(), 36) +
                java.lang.Long.toString(ThreadLocalRandom.current().nextLong(36L * 36 * 36 * 36), 36).padStart(4, '0')

        /** How a colour and a highlight read in the Markdown a model is handed, and are written back: for a tool's description. */
        const val MARKS_SAID =
            "coloured words are <span style=\"color:#c0392b\">words</span> (a #rrggbb colour), and highlighted words with " +
                "a note on them are <mark data-note=\"the note\" data-note-id=\"n1\">words</mark> - keep a highlight's " +
                "data-note-id as you read it, and leave it out on a new one"

        /** The artifact id an image's address names: artifact:DESIGN-12, orknux:DESIGN-12, or the id alone. */
        fun artifactOf(destination: String?): String? {
            val bare = destination?.trim()?.removePrefix(ARTIFACT_SCHEME)?.removePrefix("orknux:") ?: return null
            return bare.takeIf { DocumentContent.ARTIFACT.matches(it) }
        }
    }
}
