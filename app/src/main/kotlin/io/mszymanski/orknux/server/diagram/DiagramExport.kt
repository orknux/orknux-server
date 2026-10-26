package io.mszymanski.orknux.server.diagram

import io.mszymanski.orknux.server.plugin.SvgRenderer
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** What a diagram can be written out as. [text] formats are held as text, the rest as bytes. */
enum class DiagramFormat(val key: String, val contentType: String, val extension: String, val text: Boolean) {
    PNG("png", "image/png", "png", false),
    SVG("svg", "image/svg+xml", "svg", true),
    PDF("pdf", "application/pdf", "pdf", false),
    MERMAID("mermaid", "text/plain", "mmd", true),
    JSON("json", "application/json", "json", true),
    ;

    companion object {
        fun byKey(key: String?): DiagramFormat? = entries.firstOrNull { it.key == key?.trim()?.lowercase() }
    }
}

/** A diagram written out: the file's bytes and what they are. */
class DiagramFile(val format: DiagramFormat, val bytes: ByteArray) {
    val text: String get() = String(bytes, Charsets.UTF_8)
}

/**
 * A diagram as a file, for the export tool, the download link and the chat's
 * picture alike: one [DiagramSvg] drawing, which the PNG is rendered from by the
 * same [SvgRenderer] every drawn picture goes through, and the PDF is that PNG
 * on a page of its size. Mermaid and JSON are the diagram's structure as text,
 * for somebody who wants to put it in a repository or another tool.
 */
@Service
class DiagramExport(
    private val svgs: SvgRenderer,
    private val mapper: ObjectMapper,
    private val tuning: Tuning = Tuning.DEFAULTS,
) {

    private val pictures = DiagramSvg()

    sealed interface Made {
        data class Written(val file: DiagramFile) : Made
        data class Refused(val reason: String) : Made
    }

    /**
     * [scale] is a person's choice of PNG from the editor's Export menu: so many
     * pixels for each one the diagram is drawn at, up to the renderer's own
     * ceiling and nothing lower - it is a file they asked for, not a picture
     * dropped into a chat. Without it the PNG is [png]'s, held to the Tunable.
     */
    fun export(drawing: DiagramDrawing, format: DiagramFormat, scale: Int? = null): Made = when (format) {
        DiagramFormat.SVG -> Made.Written(DiagramFile(format, pictures.drawing(drawing).toByteArray()))
        DiagramFormat.PNG -> when (val png = if (scale == null) png(drawing) else scaled(drawing, scale)) {
            is SvgRenderer.Drawing.Drawn -> Made.Written(DiagramFile(format, png.png))
            is SvgRenderer.Drawing.Refused -> Made.Refused(png.reason)
        }
        DiagramFormat.PDF -> when (val png = png(drawing)) {
            is SvgRenderer.Drawing.Drawn -> Made.Written(DiagramFile(format, pdf(png.png)))
            is SvgRenderer.Drawing.Refused -> Made.Refused(png.reason)
        }
        DiagramFormat.MERMAID -> Made.Written(DiagramFile(format, mermaid(drawing).toByteArray()))
        DiagramFormat.JSON -> Made.Written(DiagramFile(format, DiagramDocument(mapper).write(drawing).toByteArray()))
    }

    /** The picture, twice its drawn size for a sharp screen, held to the Tunable. */
    fun png(drawing: DiagramDrawing): SvgRenderer.Drawing {
        val svg = pictures.drawing(drawing)
        val natural = svg.substringAfter("width=\"").substringBefore('"').toIntOrNull() ?: 400
        return svgs.png(svg, minOf(natural * 2, tuning[Tunable.DIAGRAM_PICTURE_PIXELS]))
    }

    /** The picture at [scale] times its drawn size, as wide as the renderer draws at most. */
    fun scaled(drawing: DiagramDrawing, scale: Int): SvgRenderer.Drawing {
        require(scale in SCALES) { "a scale is one of $SCALES" }
        val svg = pictures.drawing(drawing)
        val natural = svg.substringAfter("width=\"").substringBefore('"').toIntOrNull() ?: 400
        return svgs.png(svg, (natural * scale).coerceIn(1, SvgRenderer.MOST_WIDTH))
    }

    /** One page the size of the picture, at 96 pixels to the inch. */
    private fun pdf(png: ByteArray): ByteArray {
        val image = ImageIO.read(ByteArrayInputStream(png))
        PDDocument().use { document ->
            val width = image.width * POINTS_PER_PIXEL
            val height = image.height * POINTS_PER_PIXEL
            val page = PDPage(PDRectangle(width, height))
            document.addPage(page)
            val drawn = LosslessFactory.createFromImage(document, image)
            PDPageContentStream(document, page).use { it.drawImage(drawn, 0f, 0f, width, height) }
            val out = ByteArrayOutputStream()
            document.save(out)
            return out.toByteArray()
        }
    }

    /**
     * A Mermaid flowchart: groups as subgraphs, each shape the nearest Mermaid
     * has, and the labels quoted - Mermaid reads `#quot;` for a quotation mark.
     */
    fun mermaid(drawing: DiagramDrawing): String {
        val ids = drawing.nodes.mapIndexed { at, node -> node.id to "n${at}_" + node.id.replace(Regex("[^A-Za-z0-9_]"), "_") }.toMap()
        fun quoted(words: String) = "\"" + words.replace("\"", "#quot;").replace(Regex("\\s*\\n\\s*"), "<br/>") + "\""
        fun shape(node: DiagramShape): String {
            val id = ids.getValue(node.id)
            val label = quoted(node.label.ifBlank { node.id })
            return when (node.kind) {
                DiagramNodeKind.BOX -> "$id[$label]"
                DiagramNodeKind.ROUNDED -> "$id($label)"
                DiagramNodeKind.ELLIPSE -> "$id([$label])"
                DiagramNodeKind.DIAMOND -> "$id{$label}"
                DiagramNodeKind.NOTE -> "$id>$label]"
                DiagramNodeKind.GROUP -> "$id[$label]"
                // Mermaid's classic shapes only, which every version reads; where it has none
                // like ours, the nearest it has.
                DiagramNodeKind.DATABASE -> "$id[($label)]"
                DiagramNodeKind.HEXAGON -> "$id{{$label}}"
                DiagramNodeKind.PARALLELOGRAM -> "$id[/$label/]"
                DiagramNodeKind.TRAPEZOID -> "$id[\\$label/]"
                DiagramNodeKind.TRIANGLE -> "$id[/$label\\]"
                DiagramNodeKind.COMPONENT, DiagramNodeKind.DEVICE -> "$id[[$label]]"
                DiagramNodeKind.CLOUD -> "$id($label)"
                DiagramNodeKind.ACTOR, DiagramNodeKind.INTERFACE, DiagramNodeKind.BOUNDARY,
                DiagramNodeKind.CONTROL, DiagramNodeKind.ENTITY -> "$id(($label))"
                DiagramNodeKind.DOCUMENT, DiagramNodeKind.CLASS, DiagramNodeKind.PACKAGE -> "$id[$label]"
            }
        }
        return buildString {
            appendLine("flowchart LR")
            fun place(parent: String?, indent: String) {
                drawing.nodes.filter { it.parent == parent }.forEach { node ->
                    if (node.kind == DiagramNodeKind.GROUP) {
                        appendLine("${indent}subgraph ${ids.getValue(node.id)}[${quoted(node.label.ifBlank { node.id })}]")
                        place(node.id, "$indent  ")
                        appendLine("${indent}end")
                    } else {
                        appendLine(indent + shape(node))
                    }
                }
            }
            place(null, "  ")
            drawing.edges.forEach { edge ->
                val from = ids[edge.from] ?: return@forEach
                val to = ids[edge.to] ?: return@forEach
                // Mermaid's flowchart has a solid and a dotted line, an arrowhead and a circle: each end
                // the nearest of those, so the four old kinds come out as they always did.
                val solid = edge.line == DiagramLineStyle.SOLID
                val start = when (edge.start) {
                    DiagramEndStyle.NONE -> ""
                    DiagramEndStyle.CIRCLE -> "o"
                    else -> "<"
                }
                val end = when (edge.end) {
                    DiagramEndStyle.NONE -> if (solid) "-" else ""
                    DiagramEndStyle.CIRCLE -> "o"
                    else -> ">"
                }
                val arrow = start + (if (solid) "--" else "-.-") + end
                val label = edge.label?.takeIf { it.isNotBlank() }?.let { "|" + quoted(it) + "|" }.orEmpty()
                appendLine("  $from $arrow$label $to")
            }
        }
    }

    companion object {
        private const val POINTS_PER_PIXEL = 72f / 96f

        /** The scales the editor offers a PNG at: a format, not a limit on work, so not a Tunable. */
        val SCALES = 1..3
    }
}
