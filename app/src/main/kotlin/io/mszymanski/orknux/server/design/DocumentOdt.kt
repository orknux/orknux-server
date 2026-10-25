package io.mszymanski.orknux.server.design

import org.odftoolkit.odfdom.doc.OdfTextDocument
import org.odftoolkit.odfdom.dom.OdfContentDom
import org.odftoolkit.odfdom.dom.element.draw.DrawFrameElement
import org.odftoolkit.odfdom.dom.element.draw.DrawImageElement
import org.odftoolkit.odfdom.dom.element.style.StyleListLevelPropertiesElement
import org.odftoolkit.odfdom.dom.element.table.TableCoveredTableCellElement
import org.odftoolkit.odfdom.dom.element.table.TableTableCellElement
import org.odftoolkit.odfdom.dom.element.table.TableTableColumnElement
import org.odftoolkit.odfdom.dom.element.table.TableTableElement
import org.odftoolkit.odfdom.dom.element.table.TableTableRowElement
import org.odftoolkit.odfdom.dom.element.text.TextAElement
import org.odftoolkit.odfdom.dom.element.text.TextHElement
import org.odftoolkit.odfdom.dom.element.text.TextLineBreakElement
import org.odftoolkit.odfdom.dom.element.text.TextListElement
import org.odftoolkit.odfdom.dom.element.text.TextListItemElement
import org.odftoolkit.odfdom.dom.element.text.TextListLevelStyleBulletElement
import org.odftoolkit.odfdom.dom.element.text.TextListLevelStyleNumberElement
import org.odftoolkit.odfdom.dom.element.text.TextPElement
import org.odftoolkit.odfdom.dom.element.text.TextSElement
import org.odftoolkit.odfdom.dom.element.text.TextSpanElement
import org.odftoolkit.odfdom.dom.element.text.TextTabElement
import org.odftoolkit.odfdom.dom.style.OdfStyleFamily
import org.odftoolkit.odfdom.dom.style.props.OdfParagraphProperties
import org.odftoolkit.odfdom.dom.style.props.OdfTextProperties
import org.odftoolkit.odfdom.incubator.doc.office.OdfOfficeAutomaticStyles
import org.odftoolkit.odfdom.pkg.OdfElement
import tools.jackson.databind.JsonNode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import javax.imageio.ImageIO

/**
 * A document as OpenDocument text, through the ODF Toolkit's DOM.
 *
 * The toolkit makes the package - the stored `mimetype` first, the manifest,
 * the styles a word processor reads - and this walks the document's blocks into
 * its elements: headings with an outline level, paragraphs whose marks are
 * automatic text styles, lists with a bullet or a number, tables, pictures put
 * into the package and framed at the width they were drawn at, and a page
 * break as a paragraph that ends its page. The page is the document's own size
 * with the margins the editor draws.
 */
class DocumentOdt {

    fun write(doc: JsonNode, size: DocumentPage, title: String, picture: (String) -> DocumentExports.Picture?): ByteArray {
        val odt = OdfTextDocument.newTextDocument()
        try {
            val content = odt.contentDom
            val root = odt.contentRoot
            // The template's empty paragraph would be a blank line above the document.
            root.childNodes.let { nodes -> (0 until nodes.length).map { nodes.item(it) }.filter { it is TextPElement }.forEach { root.removeChild(it) } }
            pageSize(odt, size)
            Writer(odt, content, content.orCreateAutomaticStyles, size, picture).blocks(doc.path("content"), root, null)
            // The title a word processor shows in its window; a document without one is still a document.
            runCatching { org.odftoolkit.odfdom.incubator.meta.OdfOfficeMeta(odt.metaDom).title = title }
            return ByteArrayOutputStream().also { odt.save(it) }.toByteArray()
        } finally {
            odt.close()
        }
    }

    /** Every page layout the template has, made this document's size, with the editor's margins. */
    private fun pageSize(odt: OdfTextDocument, size: DocumentPage) {
        val layouts = odt.stylesDom.getElementsByTagNameNS(STYLE, "page-layout-properties")
        for (at in 0 until layouts.length) {
            val layout = layouts.item(at) as org.w3c.dom.Element
            layout.setAttributeNS(FO, "fo:page-width", mm(size.widthMm))
            layout.setAttributeNS(FO, "fo:page-height", mm(size.heightMm))
            listOf("top", "bottom", "left", "right").forEach { layout.setAttributeNS(FO, "fo:margin-$it", mm(size.marginMm)) }
            layout.setAttributeNS(STYLE, "style:print-orientation", if (size.widthMm > size.heightMm) "landscape" else "portrait")
        }
    }

    private class Writer(
        private val odt: OdfTextDocument,
        private val dom: OdfContentDom,
        private val styles: OdfOfficeAutomaticStyles,
        private val size: DocumentPage,
        private val picture: (String) -> DocumentExports.Picture?,
    ) {
        private val textStyles = mutableMapOf<Set<String>, String>()
        private val paragraphStyles = mutableMapOf<String, String>()
        private var bullets: String? = null
        private var numbers: String? = null
        private var tables = 0
        private var pictures = 0

        fun blocks(list: JsonNode, parent: OdfElement, style: String?) {
            for (at in 0 until list.size()) block(list.get(at), parent, style)
        }

        private fun block(node: JsonNode, parent: OdfElement, style: String?) {
            when (node.path("type").asText("")) {
                "paragraph" -> paragraph(parent, style).also { inline(node.path("content"), it) }
                "heading" -> {
                    val level = node.path("attrs").path("level").asInt(1).coerceIn(1, 6)
                    val h = dom.newOdfElement(TextHElement::class.java)
                    h.setTextOutlineLevelAttribute(level)
                    h.setTextStyleNameAttribute(heading(level))
                    parent.appendChild(h)
                    inline(node.path("content"), h)
                }
                "bulletList", "orderedList" -> list(node, parent, style)
                "blockquote" -> blocks(node.path("content"), parent, quote())
                "codeBlock" -> {
                    val inner = node.path("content")
                    val code = (0 until inner.size()).joinToString("") { inner.get(it).path("text").asText("") }
                    val into = paragraph(parent, code())
                    DocumentCode.runs(code, node.path("attrs").path("language").asText("")).forEach { run ->
                        if (run.colour == null) {
                            text(into, run.text)
                        } else {
                            val span = dom.newOdfElement(TextSpanElement::class.java)
                            span.setTextStyleNameAttribute(colour(run.colour))
                            into.appendChild(span)
                            text(span, run.text)
                        }
                    }
                }
                "horizontalRule" -> paragraph(parent, rule())
                "pageBreak" -> paragraph(parent, pageBreak())
                "image" -> image(node.path("attrs"), parent)
                "table" -> table(node, parent)
                else -> paragraph(parent, style).also { inline(node.path("content"), it) }
            }
        }

        private fun paragraph(parent: OdfElement, style: String?): TextPElement {
            val p = dom.newOdfElement(TextPElement::class.java)
            style?.let { p.setTextStyleNameAttribute(it) }
            parent.appendChild(p)
            return p
        }

        private fun list(node: JsonNode, parent: OdfElement, style: String?) {
            val list = dom.newOdfElement(TextListElement::class.java)
            list.setTextStyleNameAttribute(if (node.path("type").asText("") == "orderedList") numbered() else bulleted())
            parent.appendChild(list)
            val items = node.path("content")
            val start = node.path("attrs").path("start").asInt(1)
            for (at in 0 until items.size()) {
                val item = dom.newOdfElement(TextListItemElement::class.java)
                if (at == 0 && start > 1) item.setTextStartValueAttribute(start)
                list.appendChild(item)
                blocks(items.get(at).path("content"), item, style)
            }
        }

        private fun table(node: JsonNode, parent: OdfElement) {
            val rows = node.path("content")
            val width = (0 until rows.size()).maxOfOrNull { row ->
                val cells = rows.get(row).path("content")
                (0 until cells.size()).sumOf { cells.get(it).path("attrs").path("colspan").asInt(1).coerceAtLeast(1) }
            }?.coerceAtLeast(1) ?: return
            val table = dom.newOdfElement(TableTableElement::class.java)
            table.setTableNameAttribute("Table${++tables}")
            parent.appendChild(table)
            val column = dom.newOdfElement(TableTableColumnElement::class.java)
            column.setTableNumberColumnsRepeatedAttribute(width)
            table.appendChild(column)
            for (r in 0 until rows.size()) {
                val row = dom.newOdfElement(TableTableRowElement::class.java)
                table.appendChild(row)
                val cells = rows.get(r).path("content")
                var used = 0
                for (c in 0 until cells.size()) {
                    val one = cells.get(c)
                    val span = one.path("attrs").path("colspan").asInt(1).coerceIn(1, width - used)
                    val cell = dom.newOdfElement(TableTableCellElement::class.java)
                    cell.setAttributeNS(OFFICE, "office:value-type", "string")
                    if (span > 1) cell.setTableNumberColumnsSpannedAttribute(span)
                    row.appendChild(cell)
                    blocks(one.path("content"), cell, if (one.path("type").asText("") == "tableHeader") tableHeading() else null)
                    if (!cell.hasChildNodes()) paragraph(cell, null)
                    repeat(span - 1) { row.appendChild(dom.newOdfElement(TableCoveredTableCellElement::class.java)) }
                    used += span
                    if (used >= width) break
                }
                // Every row as wide as the table, or a word processor shifts the next ones.
                while (used < width) {
                    val cell = dom.newOdfElement(TableTableCellElement::class.java)
                    row.appendChild(cell)
                    paragraph(cell, null)
                    used++
                }
            }
        }

        private fun image(attrs: JsonNode, parent: OdfElement) {
            val held = picture(attrs.path("artifact").asText("")) ?: return
            val extension = when (held.contentType) {
                "image/png" -> "png"
                "image/jpeg" -> "jpg"
                "image/gif" -> "gif"
                "image/webp" -> "webp"
                else -> "img"
            }
            val path = "Pictures/picture${++pictures}.$extension"
            odt.`package`.insert(held.bytes, path, held.contentType)
            val read = runCatching { ImageIO.read(ByteArrayInputStream(held.bytes)) }.getOrNull()
            val textWidth = size.widthMm - 2 * size.marginMm
            val natural = read?.let { it.width / PIXELS_PER_MM } ?: textWidth
            val percent = attrs.path("width").asInt(0).takeIf { it in 5..100 }
            val widthMm = percent?.let { textWidth * it / 100.0 } ?: minOf(natural, textWidth)
            val heightMm = read?.let { widthMm * it.height / it.width.coerceAtLeast(1) } ?: (widthMm * 3 / 4)
            val p = paragraph(parent, centred())
            val frame = dom.newOdfElement(DrawFrameElement::class.java)
            frame.setAttributeNS(TEXT, "text:anchor-type", "as-char")
            frame.setSvgWidthAttribute(mm(widthMm))
            frame.setSvgHeightAttribute(mm(heightMm))
            frame.setAttributeNS(DRAW, "draw:name", "Picture$pictures")
            p.appendChild(frame)
            val image = dom.newOdfElement(DrawImageElement::class.java)
            image.setXlinkHrefAttribute(path)
            image.setXlinkTypeAttribute("simple")
            image.setXlinkShowAttribute("embed")
            image.setXlinkActuateAttribute("onLoad")
            frame.appendChild(image)
            attrs.path("caption").asText("").takeIf { it.isNotEmpty() }?.let { text(paragraph(parent, caption()), it) }
        }

        private fun inline(list: JsonNode, into: OdfElement) {
            for (at in 0 until list.size()) {
                val node = list.get(at)
                when (node.path("type").asText("")) {
                    "hardBreak" -> into.appendChild(dom.newOdfElement(TextLineBreakElement::class.java))
                    "text" -> {
                        val marks = node.path("marks")
                        // A colour is part of what a style is, so it is named in the set the styles are kept by.
                        val kinds = (0 until marks.size()).mapNotNull { at ->
                            val mark = marks.get(at)
                            when (val type = mark.path("type").asText("")) {
                                "textStyle" -> DocumentContent.colour(mark.path("attrs").path("color").asText(""))?.let { "$COLOUR$it" }
                                else -> type
                            }
                        }.toSet()
                        val href = (0 until marks.size()).map { marks.get(it) }.firstOrNull { it.path("type").asText("") == "link" }
                            ?.path("attrs")?.path("href")?.asText("")
                        var target: OdfElement = into
                        if (href != null) {
                            val link = dom.newOdfElement(TextAElement::class.java)
                            link.setXlinkHrefAttribute(href)
                            link.setXlinkTypeAttribute("simple")
                            target.appendChild(link)
                            target = link
                        }
                        val styled = kinds - "link"
                        if (styled.isNotEmpty()) {
                            val span = dom.newOdfElement(TextSpanElement::class.java)
                            span.setTextStyleNameAttribute(textStyle(styled))
                            target.appendChild(span)
                            target = span
                        }
                        text(target, node.path("text").asText(""))
                    }
                }
            }
        }

        /** Words as ODF keeps them: runs of spaces, tabs and line ends are elements of their own, or a reader collapses them. */
        private fun text(into: OdfElement, words: String) {
            val run = StringBuilder()
            fun flush() {
                if (run.isNotEmpty()) into.appendChild(dom.createTextNode(run.toString()))
                run.setLength(0)
            }
            var at = 0
            while (at < words.length) {
                val c = words[at]
                when {
                    c == '\n' -> {
                        flush()
                        into.appendChild(dom.newOdfElement(TextLineBreakElement::class.java))
                    }
                    c == '\t' -> {
                        flush()
                        into.appendChild(dom.newOdfElement(TextTabElement::class.java))
                    }
                    c == ' ' && at + 1 < words.length && words[at + 1] == ' ' -> {
                        var spaces = 0
                        while (at < words.length && words[at] == ' ') {
                            spaces++
                            at++
                        }
                        run.append(' ')
                        flush()
                        val s = dom.newOdfElement(TextSElement::class.java)
                        s.setAttributeNS(TEXT, "text:c", (spaces - 1).toString())
                        into.appendChild(s)
                        continue
                    }
                    else -> run.append(c)
                }
                at++
            }
            flush()
        }

        private fun textStyle(kinds: Set<String>): String = textStyles.getOrPut(kinds) {
            val style = styles.newStyle(OdfStyleFamily.Text)
            if ("bold" in kinds) style.setProperty(OdfTextProperties.FontWeight, "bold")
            if ("italic" in kinds) style.setProperty(OdfTextProperties.FontStyle, "italic")
            if ("underline" in kinds) {
                style.setProperty(OdfTextProperties.TextUnderlineStyle, "solid")
                style.setProperty(OdfTextProperties.TextUnderlineWidth, "auto")
                style.setProperty(OdfTextProperties.TextUnderlineColor, "font-color")
            }
            if ("strike" in kinds) style.setProperty(OdfTextProperties.TextLineThroughStyle, "solid")
            if ("code" in kinds) style.setProperty(OdfTextProperties.FontFamily, "monospace")
            kinds.firstOrNull { it.startsWith(COLOUR) }?.let { style.setProperty(OdfTextProperties.Color, it.removePrefix(COLOUR)) }
            if ("note" in kinds) style.setProperty(OdfTextProperties.BackgroundColor, DocumentHtml.NOTE_COLOUR)
            style.styleNameAttribute
        }

        /** A code token's colour, as a text style; one per colour. */
        private fun colour(hex: String): String = colours.getOrPut(hex) {
            val style = styles.newStyle(OdfStyleFamily.Text)
            style.setProperty(OdfTextProperties.Color, hex)
            style.styleNameAttribute
        }

        private val colours = mutableMapOf<String, String>()

        private fun paragraphStyle(key: String, set: (org.odftoolkit.odfdom.incubator.doc.style.OdfStyle) -> Unit): String = paragraphStyles.getOrPut(key) {
            val style = styles.newStyle(OdfStyleFamily.Paragraph)
            set(style)
            style.styleNameAttribute
        }

        private fun heading(level: Int) = paragraphStyle("h$level") {
            it.setProperty(OdfTextProperties.FontSize, HEADING_POINTS[level - 1])
            it.setProperty(OdfTextProperties.FontWeight, "bold")
            it.setProperty(OdfParagraphProperties.MarginTop, "0.35cm")
            it.setProperty(OdfParagraphProperties.MarginBottom, "0.2cm")
        }

        private fun quote() = paragraphStyle("quote") {
            it.setProperty(OdfParagraphProperties.MarginLeft, "1cm")
            it.setProperty(OdfTextProperties.FontStyle, "italic")
        }

        private fun code() = paragraphStyle("code") {
            it.setProperty(OdfTextProperties.FontFamily, "monospace")
            it.setProperty(OdfParagraphProperties.MarginBottom, "0.2cm")
        }

        private fun rule() = paragraphStyle("rule") { it.setProperty(OdfParagraphProperties.BorderBottom, "0.5pt solid #b9b9b9") }

        /** A paragraph of nothing that ends its page: what follows starts the next one. */
        private fun pageBreak() = paragraphStyle("break") { it.setProperty(OdfParagraphProperties.BreakAfter, "page") }

        private fun centred() = paragraphStyle("centred") { it.setProperty(OdfParagraphProperties.TextAlign, "center") }

        private fun caption() = paragraphStyle("caption") {
            it.setProperty(OdfParagraphProperties.TextAlign, "center")
            it.setProperty(OdfTextProperties.FontSize, "9pt")
            it.setProperty(OdfTextProperties.FontStyle, "italic")
        }

        private fun tableHeading() = paragraphStyle("th") { it.setProperty(OdfTextProperties.FontWeight, "bold") }

        private fun bulleted(): String = bullets ?: listStyle(numbered = false).also { bullets = it }

        private fun numbered(): String = numbers ?: listStyle(numbered = true).also { numbers = it }

        private fun listStyle(numbered: Boolean): String {
            val style = styles.newListStyle()
            for (level in 1..LIST_LEVELS) {
                val marker = if (numbered) {
                    style.getOrCreateListLevel(level, TextListLevelStyleNumberElement::class.java).also {
                        it.setAttributeNS(STYLE, "style:num-format", "1")
                        it.setAttributeNS(STYLE, "style:num-suffix", ".")
                    }
                } else {
                    style.getOrCreateListLevel(level, TextListLevelStyleBulletElement::class.java).also {
                        it.setAttributeNS(TEXT, "text:bullet-char", if (level % 2 == 1) "•" else "◦")
                    }
                }
                val properties = dom.newOdfElement(StyleListLevelPropertiesElement::class.java)
                properties.setAttributeNS(TEXT, "text:space-before", String.format(Locale.ROOT, "%.2fcm", (level - 1) * 0.6 + 0.3))
                properties.setAttributeNS(TEXT, "text:min-label-width", "0.6cm")
                marker.appendChild(properties)
            }
            return style.styleNameAttribute
        }
    }

    private companion object {
        const val TEXT = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"
        const val STYLE = "urn:oasis:names:tc:opendocument:xmlns:style:1.0"
        const val FO = "urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
        const val DRAW = "urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
        const val OFFICE = "urn:oasis:names:tc:opendocument:xmlns:office:1.0"

        /** A picture's own size on paper, at the 96 pixels to the inch a screen shows it at. */
        const val PIXELS_PER_MM = 96 / 25.4

        const val LIST_LEVELS = 6

        /** How a text colour is named among a run's marks. */
        const val COLOUR = "color:"

        val HEADING_POINTS = listOf("20pt", "16pt", "14pt", "12pt", "11pt", "11pt")

        fun mm(value: Double): String = String.format(Locale.ROOT, "%.2fmm", value)
    }
}
