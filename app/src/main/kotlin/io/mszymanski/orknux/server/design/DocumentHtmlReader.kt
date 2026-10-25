package io.mszymanski.orknux.server.design

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayInputStream
import java.util.Base64

/**
 * An HTML page, and a plain text file, read into a document.
 *
 * Parsed by jsoup, the parser the server already reads pages with - its
 * character set from the page's own meta, a page that is not well formed
 * mended as a browser would - and its tree walked into the editor's nodes:
 * headings, paragraphs, lists, quotes, code, rules, tables, and the marks a
 * page writes as elements or as a span's style, which is how Google Docs and
 * Word write them. A script, a style sheet and anything else that is not
 * words is left out.
 *
 * A picture written into the page as a `data:` address is kept; one at a web
 * address is not fetched, and stays a link to it, as a Markdown file's does.
 */
class DocumentHtmlReader(mapper: ObjectMapper) {

    private val nodes = DocumentNodes(mapper)

    fun read(bytes: ByteArray, keep: PictureKeeper): ObjectNode {
        val page = Jsoup.parse(ByteArrayInputStream(bytes), null, "")
        return nodes.doc(Walker(keep).blocks(page.body()))
    }

    /** Plain text: a paragraph to each run of lines between blank ones, each line break kept. */
    fun plain(text: String): ObjectNode {
        val paragraphs = text.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n[ \t]*\n+"))
        return nodes.doc(
            paragraphs.filter { it.isNotBlank() }.map { block ->
                val inline = mutableListOf<ObjectNode>()
                block.trim('\n').split('\n').forEachIndexed { at, line ->
                    if (at > 0) inline += nodes.hardBreak()
                    if (line.isNotEmpty()) inline += nodes.text(line, emptyList())
                }
                nodes.paragraph(inline)
            },
        )
    }

    private inner class Walker(private val keep: PictureKeeper) {

        fun blocks(parent: Element): List<ObjectNode> {
            val flow = nodes.Flow()
            walk(parent, flow, emptyList())
            return flow.done()
        }

        private fun walk(parent: Node, flow: DocumentNodes.Flow, marks: List<ImportMark>) {
            for (child in parent.childNodes()) {
                when (child) {
                    is TextNode -> flow.words(child.text().replace(WHITE, " "), marks)
                    is Element -> element(child, flow, marks)
                }
            }
        }

        private fun element(element: Element, flow: DocumentNodes.Flow, marks: List<ImportMark>) {
            when (val tag = element.normalName()) {
                in SKIPPED -> Unit
                "h1", "h2", "h3", "h4", "h5", "h6" -> heading(tag[1].digitToInt(), element, flow, marks)
                "p" -> {
                    flow.end()
                    walk(element, flow, marks + styled(element))
                    flow.end()
                    if (breaksPage(element)) flow.block(nodes.node("pageBreak"))
                }
                "br" -> flow.lineBreak()
                "ul", "ol" -> flow.block(list(element))
                "li" -> flow.block(nodes.list(false, listOf(nodes.listItem(blocks(element)))))
                "blockquote" -> flow.block(nodes.container("blockquote", blocks(element)))
                "pre" -> {
                    val language = element.selectFirst("code")?.classNames()?.firstOrNull { it.startsWith("language-") }?.removePrefix("language-")
                        ?: element.attr("data-language").takeIf { it.isNotBlank() }
                    flow.block(nodes.code(element.wholeText(), language))
                }
                "hr" -> flow.block(nodes.node("horizontalRule"))
                "table" -> table(element)?.let { flow.block(it) }
                "img" -> picture(element, flow, marks)
                "figcaption" -> {
                    val caption = element.text().trim()
                    val last = flow.blocks.lastOrNull()
                    if (!flow.writing() && last != null && last.path("type").asText() == "image" && caption.isNotEmpty()) {
                        (last.get("attrs") as ObjectNode).put("caption", caption)
                    } else {
                        flow.end()
                        walk(element, flow, marks)
                        flow.end()
                    }
                }
                "strong", "b" -> walk(element, flow, marks + ImportMark("bold") + styled(element))
                "em", "i", "cite", "dfn" -> walk(element, flow, marks + ImportMark("italic") + styled(element))
                "u", "ins" -> walk(element, flow, marks + ImportMark("underline"))
                "s", "strike", "del" -> walk(element, flow, marks + ImportMark("strike"))
                "code", "kbd", "samp", "tt" -> walk(element, flow, marks + ImportMark("code"))
                "a" -> {
                    val href = element.attr("href").trim()
                    walk(element, flow, if (href.isEmpty()) marks else marks + ImportMark("link", href))
                }
                "span", "font", "mark", "small", "big", "sub", "sup", "abbr", "q", "label", "time" -> walk(element, flow, marks + styled(element))
                else -> if (element.isBlock) {
                    // A div, a section, a figure, a header: its own blocks, in the run it stands in.
                    flow.end()
                    if (breaksBefore(element)) flow.block(nodes.node("pageBreak"))
                    walk(element, flow, marks + styled(element))
                    flow.end()
                    if (breaksPage(element)) flow.block(nodes.node("pageBreak"))
                } else {
                    walk(element, flow, marks + styled(element))
                }
            }
        }

        private fun heading(level: Int, element: Element, flow: DocumentNodes.Flow, marks: List<ImportMark>) {
            val inner = nodes.Flow()
            walk(element, inner, marks)
            val made = inner.done()
            // A heading holds words; a picture inside one comes after it.
            val words = made.firstOrNull { it.path("type").asText() == "paragraph" }
            val inline = words?.path("content")?.let { list -> (0 until list.size()).map { list.get(it) as ObjectNode } }.orEmpty()
            flow.block(nodes.heading(level, inline))
            made.filter { it.path("type").asText() != "paragraph" }.forEach { flow.block(it) }
        }

        private fun list(element: Element): ObjectNode {
            val ordered = element.normalName() == "ol"
            val items = element.children().filter { it.normalName() == "li" }.map { nodes.listItem(blocks(it)) }
            val start = element.attr("start").toIntOrNull()
            return nodes.list(ordered, items, start)
        }

        private fun table(element: Element): ObjectNode? {
            val rows = mutableListOf<List<ObjectNode>>()
            fun rowsOf(parent: Element) {
                for (child in parent.children()) {
                    when (child.normalName()) {
                        "thead", "tbody", "tfoot" -> rowsOf(child)
                        "tr" -> rows += child.children().filter { it.normalName() == "td" || it.normalName() == "th" }.map { cell ->
                            nodes.cell(
                                header = cell.normalName() == "th",
                                blocks = blocks(cell),
                                colspan = cell.attr("colspan").toIntOrNull() ?: 1,
                                rowspan = cell.attr("rowspan").toIntOrNull() ?: 1,
                            )
                        }
                    }
                }
            }
            rowsOf(element)
            return nodes.table(rows)
        }

        private fun picture(element: Element, flow: DocumentNodes.Flow, marks: List<ImportMark>) {
            val source = element.attr("src").trim()
            val alt = element.attr("alt").trim()
            val title = element.attr("title").trim().takeIf { it.isNotEmpty() }
            if (source.startsWith("data:", ignoreCase = true) && source.contains(";base64,")) {
                val bytes = runCatching { Base64.getMimeDecoder().decode(source.substringAfter(";base64,")) }.getOrNull()
                val artifact = bytes?.let { keep(it, alt.ifEmpty { null }) }
                if (artifact != null) flow.block(nodes.image(artifact, alt, title))
                else if (alt.isNotEmpty()) flow.words(alt, marks)
                return
            }
            if (source.startsWith("http://", ignoreCase = true) || source.startsWith("https://", ignoreCase = true)) {
                flow.words(alt.ifEmpty { source }, marks + ImportMark("link", source))
                return
            }
            if (alt.isNotEmpty()) flow.words(alt, marks)
        }

        /** The marks a span's style writes rather than an element: how Google Docs and Word say bold. */
        private fun styled(element: Element): List<ImportMark> {
            val style = element.attr("style").lowercase().replace(" ", "")
            if (style.isEmpty()) return emptyList()
            val out = mutableListOf<ImportMark>()
            Regex("font-weight:(bold|bolder|[6-9]00)").find(style)?.let { out += ImportMark("bold") }
            if (style.contains("font-style:italic")) out += ImportMark("italic")
            if (Regex("text-decoration(-line)?:[^;]*underline").containsMatchIn(style)) out += ImportMark("underline")
            if (Regex("text-decoration(-line)?:[^;]*line-through").containsMatchIn(style)) out += ImportMark("strike")
            return out
        }

        private fun breaksPage(element: Element): Boolean {
            val style = element.attr("style").lowercase().replace(" ", "")
            return style.contains("page-break-after:always") || style.contains("break-after:page") || element.hasClass("page-break")
        }

        private fun breaksBefore(element: Element): Boolean {
            val style = element.attr("style").lowercase().replace(" ", "")
            return style.contains("page-break-before:always") || style.contains("break-before:page")
        }
    }

    private companion object {
        val WHITE = Regex("\\s+")

        /** Not words: what a page holds for a browser rather than a reader. */
        val SKIPPED = setOf("script", "style", "head", "title", "meta", "link", "noscript", "template", "svg", "math", "iframe", "object", "embed", "canvas", "button", "input", "select", "textarea")
    }
}
