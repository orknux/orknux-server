package io.mszymanski.orknux.server.design

import org.odftoolkit.odfdom.doc.OdfTextDocument
import org.w3c.dom.Element
import org.w3c.dom.Node
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.io.ByteArrayInputStream

/**
 * An OpenDocument text read into a document, through the ODF Toolkit - the
 * library [DocumentOdt] writes one with, so what Export writes comes back.
 *
 * The package is the toolkit's to open; its content is walked as the elements
 * the format names. A heading has its outline level; a paragraph is code where
 * its style's font is a monospace one, and ends its page where its style says
 * so; a list is numbered or bulleted by its list style, nested as it nests; a
 * table is its rows and cells, a cell repeated as many times as it says and its
 * spans kept; a picture is read from the package. The marks are the automatic
 * styles a span names - bold, italic, underline, strike - and the styles they
 * inherit from.
 */
class DocumentOdtReader(mapper: ObjectMapper) {

    private val nodes = DocumentNodes(mapper)

    fun read(bytes: ByteArray, keep: PictureKeeper): ObjectNode {
        val odt = OdfTextDocument.loadDocument(ByteArrayInputStream(bytes))
        try {
            val styles = Styles(listOf(odt.contentDom, odt.stylesDom))
            return nodes.doc(Walker(odt, styles, keep).blocks(odt.contentRoot))
        } finally {
            odt.close()
        }
    }

    /** What a style, by name, makes of the words it is given, following the styles it inherits from. */
    private class Styles(doms: List<org.w3c.dom.Document>) {
        private val styles = mutableMapOf<String, Element>()
        private val lists = mutableMapOf<String, Boolean>()

        init {
            doms.forEach { dom ->
                elements(dom.getElementsByTagNameNS(STYLE, "style")).forEach { styles[it.getAttributeNS(STYLE, "name")] = it }
                elements(dom.getElementsByTagNameNS(TEXT, "list-style")).forEach { list ->
                    val first = children(list).firstOrNull()
                    lists[list.getAttributeNS(STYLE, "name")] = first?.localName == "list-level-style-number"
                }
            }
        }

        /** A property of [name]'s text or paragraph, or of the style it inherits it from. */
        fun property(name: String?, ns: String, property: String, depth: Int = 0): String? {
            if (name.isNullOrEmpty() || depth > 10) return null
            val style = styles[name] ?: return null
            for (props in children(style)) {
                if (props.localName == "text-properties" || props.localName == "paragraph-properties") {
                    props.getAttributeNS(ns, property).takeIf { it.isNotEmpty() }?.let { return it }
                }
            }
            return property(style.getAttributeNS(STYLE, "parent-style-name"), ns, property, depth + 1)
        }

        fun marks(name: String?): List<ImportMark> {
            if (name.isNullOrEmpty()) return emptyList()
            val out = mutableListOf<ImportMark>()
            if (property(name, FO, "font-weight")?.let { it == "bold" || (it.toIntOrNull() ?: 0) >= 600 } == true) out += ImportMark("bold")
            if (property(name, FO, "font-style") == "italic") out += ImportMark("italic")
            if (property(name, STYLE, "text-underline-style")?.let { it != "none" } == true) out += ImportMark("underline")
            if (property(name, STYLE, "text-line-through-style")?.let { it != "none" } == true) out += ImportMark("strike")
            if (monospace(name)) out += ImportMark("code")
            return out
        }

        fun monospace(name: String?): Boolean {
            val family = property(name, FO, "font-family") ?: property(name, STYLE, "font-name") ?: return false
            return MONOSPACE.any { family.contains(it, ignoreCase = true) }
        }

        fun breaksAfter(name: String?) = property(name, FO, "break-after") == "page"
        fun breaksBefore(name: String?) = property(name, FO, "break-before") == "page"

        /** Whether a list style numbers its items; null for a list that names none, which takes its parent's. */
        fun ordered(name: String?): Boolean? = if (name.isNullOrEmpty()) null else lists[name]
    }

    private inner class Walker(private val odt: OdfTextDocument, private val styles: Styles, private val keep: PictureKeeper) {

        fun blocks(parent: Node): List<ObjectNode> {
            val flow = nodes.Flow()
            blocksInto(parent, flow, ordered = false)
            return flow.done()
        }

        private fun blocksInto(parent: Node, flow: DocumentNodes.Flow, ordered: Boolean) {
            for (child in children(parent)) block(child, flow, ordered)
        }

        private fun block(element: Element, flow: DocumentNodes.Flow, orderedAbove: Boolean) {
            val style = element.getAttributeNS(TEXT, "style-name")
            when (element.localName) {
                "h" -> if (element.namespaceURI == TEXT) {
                    val level = element.getAttributeNS(TEXT, "outline-level").toIntOrNull() ?: 1
                    val inner = nodes.Flow()
                    inline(element, inner, emptyList())
                    val made = inner.done()
                    val words = made.firstOrNull { it.path("type").asText() == "paragraph" }
                    val inline = words?.path("content")?.let { list -> (0 until list.size()).map { list.get(it) as ObjectNode } }.orEmpty()
                    flow.block(nodes.heading(level, inline))
                    made.filter { it.path("type").asText() != "paragraph" }.forEach { flow.block(it) }
                }
                "p" -> if (element.namespaceURI == TEXT) {
                    if (styles.breaksBefore(style)) flow.block(nodes.node("pageBreak"))
                    if (styles.monospace(style)) {
                        flow.block(nodes.code(textOf(element)))
                    } else {
                        flow.end()
                        inline(element, flow, styles.marks(style).filter { it.type != "code" })
                        flow.end()
                    }
                    if (styles.breaksAfter(style)) flow.block(nodes.node("pageBreak"))
                }
                "list" -> if (element.namespaceURI == TEXT) {
                    val ordered = styles.ordered(style) ?: orderedAbove
                    val items = children(element).filter { it.localName == "list-item" || it.localName == "list-header" }.map { item ->
                        val inner = nodes.Flow()
                        blocksInto(item, inner, ordered)
                        nodes.listItem(inner.done())
                    }
                    flow.block(nodes.list(ordered, items))
                }
                "table" -> if (element.namespaceURI == TABLE) table(element)?.let { flow.block(it) }
                "section", "index-body", "table-of-content", "illustration-index", "alphabetical-index", "user-index", "bibliography" ->
                    blocksInto(element, flow, orderedAbove)
                "soft-page-break", "sequence-decls", "variable-decls", "user-field-decls", "tracked-changes", "forms", "index-title-template" -> Unit
                "frame" -> frame(element, flow, emptyList())
                else -> blocksInto(element, flow, orderedAbove)
            }
        }

        private fun inline(parent: Node, flow: DocumentNodes.Flow, marks: List<ImportMark>) {
            val nodesList = parent.childNodes
            for (at in 0 until nodesList.length) {
                val child = nodesList.item(at)
                when (child.nodeType) {
                    Node.TEXT_NODE -> flow.words(child.nodeValue.replace(WHITE, " "), marks)
                    Node.ELEMENT_NODE -> {
                        val element = child as Element
                        when (element.localName) {
                            "span" -> inline(element, flow, marks + styles.marks(element.getAttributeNS(TEXT, "style-name")))
                            "a" -> {
                                val href = element.getAttributeNS(XLINK, "href").trim()
                                inline(element, flow, if (href.isEmpty()) marks else marks + ImportMark("link", href))
                            }
                            "s" -> flow.words(" ".repeat((element.getAttributeNS(TEXT, "c").toIntOrNull() ?: 1).coerceIn(1, 100)), marks)
                            "tab" -> flow.words(" ", marks)
                            "line-break" -> flow.lineBreak()
                            "frame" -> frame(element, flow, marks)
                            "note", "annotation", "bookmark", "bookmark-start", "bookmark-end", "soft-page-break", "reference-mark-start", "reference-mark-end" -> Unit
                            else -> inline(element, flow, marks)
                        }
                    }
                }
            }
        }

        /** A frame: a picture read from the package, or a text box's own words. */
        private fun frame(element: Element, flow: DocumentNodes.Flow, marks: List<ImportMark>) {
            val alt = children(element).firstOrNull { it.localName == "title" || it.localName == "desc" }?.textContent?.trim()?.takeIf { it.isNotEmpty() }
            for (child in children(element)) {
                when (child.localName) {
                    "image" -> {
                        val href = child.getAttributeNS(XLINK, "href").trim().removePrefix("./")
                        if (href.isEmpty() || href.contains("://")) continue
                        val bytes = runCatching { odt.`package`.getBytes(href) }.getOrNull() ?: continue
                        keep(bytes, alt ?: href.substringAfterLast('/'))?.let { flow.block(nodes.image(it, alt)) }
                        return
                    }
                    "text-box" -> {
                        flow.end()
                        blocksInto(child, flow, false)
                        return
                    }
                }
            }
        }

        private fun table(element: Element): ObjectNode? {
            val rows = mutableListOf<List<ObjectNode>>()
            fun rowsOf(parent: Element, header: Boolean) {
                for (child in children(parent)) {
                    when (child.localName) {
                        "table-header-rows" -> rowsOf(child, true)
                        "table-rows", "table-row-group" -> rowsOf(child, header)
                        "table-row" -> {
                            val cells = mutableListOf<ObjectNode>()
                            for (cell in children(child)) {
                                if (cell.localName != "table-cell") continue
                                val blocks = blocks(cell)
                                val repeated = (cell.getAttributeNS(TABLE, "number-columns-repeated").toIntOrNull() ?: 1).coerceIn(1, 50)
                                val colspan = cell.getAttributeNS(TABLE, "number-columns-spanned").toIntOrNull() ?: 1
                                val rowspan = cell.getAttributeNS(TABLE, "number-rows-spanned").toIntOrNull() ?: 1
                                // A run of empty cells repeated to the edge of the sheet is the format's padding, not columns.
                                val times = if (repeated > 1 && blocks.isEmpty()) 1 else repeated
                                repeat(times) { cells += nodes.cell(header, blocks, colspan, rowspan) }
                            }
                            val repeatedRows = (child.getAttributeNS(TABLE, "number-rows-repeated").toIntOrNull() ?: 1).coerceIn(1, 50)
                            repeat(repeatedRows) { rows += cells.map { it.deepCopy() } }
                        }
                    }
                }
            }
            rowsOf(element, false)
            return nodes.table(rows)
        }

        private fun textOf(element: Element): String {
            val out = StringBuilder()
            fun walk(node: Node) {
                val list = node.childNodes
                for (at in 0 until list.length) {
                    val child = list.item(at)
                    when {
                        child.nodeType == Node.TEXT_NODE -> out.append(child.nodeValue)
                        child is Element && child.localName == "s" -> out.append(" ".repeat((child.getAttributeNS(TEXT, "c").toIntOrNull() ?: 1).coerceIn(1, 100)))
                        child is Element && child.localName == "tab" -> out.append('\t')
                        child is Element && child.localName == "line-break" -> out.append('\n')
                        else -> walk(child)
                    }
                }
            }
            walk(element)
            return out.toString()
        }
    }

    private companion object {
        const val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
        const val STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
        const val FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
        const val TABLE = "urn:oasis:names:tc:opendocument:xmlns:table:1.0"
        const val XLINK = "http://www.w3.org/1999/xlink"

        val WHITE = Regex("\\s+")
        val MONOSPACE = listOf("monospace", "Courier", "Consolas", "Mono", "Menlo", "Monaco")

        fun elements(list: org.w3c.dom.NodeList): List<Element> = (0 until list.length).mapNotNull { list.item(it) as? Element }

        fun children(parent: Node): List<Element> = elements(parent.childNodes)
    }
}
