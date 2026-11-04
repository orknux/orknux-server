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
    /** Where a diagram's uploaded pictures are, to draw them into its picture; none in a test that builds this by hand. */
    private val images: DiagramImageRepository? = null,
    private val store: io.mszymanski.orknux.server.attachment.AttachmentStore? = null,
    /** And its pictures from the web, fetched under the rules there; none in a test that builds this by hand. */
    private val web: DiagramWebPictures? = null,
) {

    /**
     * A diagram's own uploaded pictures as data addresses, for a picture drawn
     * on the server, which has no session to fetch them with. Only [diagramId]'s
     * own: a box naming another diagram's picture - in another workspace, say -
     * is drawn as an empty frame. One from the web is fetched by
     * [DiagramWebPictures], which says what it may reach.
     */
    private fun embedded(diagramId: Long?): (DiagramShape) -> String? = { node ->
        val id = node.uploadedImage
        if (id == null) {
            node.image?.let { address -> web?.data(address) }
        } else if (diagramId == null || images == null || store == null) {
            null
        } else {
            images.findById(id).orElse(null)?.takeIf { it.diagramId == diagramId && store.exists(it.location) }?.let { held ->
                DiagramPictureData.of(held.contentType, store.open(held.location).use { it.readAllBytes() })
            }
        }
    }

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
    fun export(
        drawing: DiagramDrawing,
        format: DiagramFormat,
        scale: Int? = null,
        diagramId: Long? = null,
        /** Where a box's link leads, as an address a PDF reader can open; null where it can be followed nowhere. */
        links: (String) -> String? = { null },
    ): Made = when (format) {
        DiagramFormat.SVG -> Made.Written(DiagramFile(format, pictures.drawing(drawing, embedded(diagramId)).toByteArray()))
        DiagramFormat.PNG -> when (val png = if (scale == null) png(drawing, diagramId) else scaled(drawing, scale, diagramId)) {
            is SvgRenderer.Drawing.Drawn -> Made.Written(DiagramFile(format, png.png))
            is SvgRenderer.Drawing.Refused -> Made.Refused(png.reason)
        }
        DiagramFormat.PDF -> runCatching { Made.Written(DiagramFile(format, vectorPdf(drawing, diagramId, links))) }
            .getOrElse { why ->
                // Drawn as a picture where the vector page will not lay out, rather than not at all.
                org.slf4j.LoggerFactory.getLogger(DiagramExport::class.java).warn("A diagram's PDF was drawn as a picture: {}", why.message)
                when (val png = png(drawing, diagramId)) {
                    is SvgRenderer.Drawing.Drawn -> Made.Written(DiagramFile(format, pdf(png.png)))
                    is SvgRenderer.Drawing.Refused -> Made.Refused(png.reason)
                }
            }
        // What is shown: a hidden layer is out of the picture, as it is out of the editor. JSON keeps it all, layers and all.
        DiagramFormat.MERMAID -> Made.Written(DiagramFile(format, mermaid(drawing.visible()).toByteArray()))
        DiagramFormat.JSON -> Made.Written(DiagramFile(format, DiagramDocument(mapper).write(drawing).toByteArray()))
    }

    /** The picture, twice its drawn size for a sharp screen, held to the Tunable. */
    fun png(drawing: DiagramDrawing, diagramId: Long? = null): SvgRenderer.Drawing {
        val svg = pictures.drawing(drawing, embedded(diagramId))
        val natural = svg.substringAfter("width=\"").substringBefore('"').toIntOrNull() ?: 400
        return svgs.png(svg, minOf(natural * 2, tuning[Tunable.DIAGRAM_PICTURE_PIXELS]))
    }

    /** The picture at [scale] times its drawn size, as wide as the renderer draws at most. */
    fun scaled(drawing: DiagramDrawing, scale: Int, diagramId: Long? = null): SvgRenderer.Drawing {
        require(scale in SCALES) { "a scale is one of $SCALES" }
        val svg = pictures.drawing(drawing, embedded(diagramId))
        val natural = svg.substringAfter("width=\"").substringBefore('"').toIntOrNull() ?: 400
        return svgs.png(svg, (natural * scale).coerceIn(1, SvgRenderer.MOST_WIDTH))
    }

    /**
     * One page the size of the diagram, drawn as vectors, with every box that
     * links somewhere a link a PDF reader opens.
     *
     * It was the PNG on a page: soft at any zoom past its own, text that could
     * be neither found nor copied, and a link on a box nothing more than a
     * picture of one. The SVG goes onto the page as the SVG, through the same
     * openhtmltopdf and Batik the documents' PDFs use, and over each linked box
     * lies an empty link the size of the box - a link in a PDF is an area of
     * the page, and these are that area.
     */
    private fun vectorPdf(drawing: DiagramDrawing, diagramId: Long?, links: (String) -> String?): ByteArray =
        pdfOf(drawing, embedded(diagramId), links)

    /** [vectorPdf] with the drawing's pictures answered by [drawn]; apart so a test can hand pictures in. */
    internal fun pdfOf(drawing: DiagramDrawing, drawn: (DiagramShape) -> String?, links: (String) -> String? = { null }): ByteArray {
        val svg = pictures.drawing(drawing, drawn)
        val (left, top, width, height) = pictures.frame(drawing)
        val areas = drawing.visible().nodes.mapNotNull { node ->
            val target = node.link?.takeIf { it.isNotBlank() }?.let(links) ?: return@mapNotNull null
            """<a href="${xml(target)}" style="left:${node.x - left}px;top:${node.y - top}px;width:${node.w}px;height:${node.h}px"></a>"""
        }
        val page = """<html xmlns="http://www.w3.org/1999/xhtml"><head><style>
            @page { size: ${width}px ${height}px; margin: 0 }
            body { margin: 0 }
            .frame { position: relative; width: ${width}px; height: ${height}px }
            .frame > svg { position: absolute; left: 0; top: 0 }
            .frame > a { position: absolute; display: block }
            </style></head><body><div class="frame">${svg.removePrefix("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")}${areas.joinToString("")}</div></body></html>"""
        val out = ByteArrayOutputStream()
        com.openhtmltopdf.pdfboxout.PdfRendererBuilder()
            .useFastMode()
            /*
             * A picture in the drawing is a data address, and only that is let
             * through: by default every one was refused as an outside fetch, and
             * a refused picture threw away the whole SVG - a diagram with one
             * picture in it came out a blank page, and nothing said so.
             */
            .useSVGDrawer(com.openhtmltopdf.svgsupport.BatikSVGDrawer(com.openhtmltopdf.svgsupport.BatikSVGDrawer.SvgScriptMode.SECURE, setOf("data")))
            // Nothing is fetched: every picture in the drawing is a data address by now.
            .useExternalResourceAccessControl({ uri, _ -> uri.startsWith("data:", ignoreCase = true) }, com.openhtmltopdf.outputdevice.helper.ExternalResourceControlPriority.RUN_BEFORE_RESOLVING_URI)
            .withHtmlContent(page, null)
            .toStream(out)
            .run()
        return out.toByteArray()
    }

    private fun xml(text: String): String =
        text.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

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
                DiagramNodeKind.SUBROUTINE, DiagramNodeKind.SERVER -> "$id[[$label]]"
                DiagramNodeKind.QUEUE, DiagramNodeKind.STORAGE -> "$id[($label)]"
                DiagramNodeKind.MANUAL_INPUT, DiagramNodeKind.DISPLAY -> "$id[/$label/]"
                DiagramNodeKind.DELAY, DiagramNodeKind.STAR, DiagramNodeKind.MOBILE, DiagramNodeKind.BROWSER -> "$id($label)"
                DiagramNodeKind.OCTAGON -> "$id{{$label}}"
                DiagramNodeKind.STEP, DiagramNodeKind.CALLOUT, DiagramNodeKind.OFF_PAGE -> "$id>$label]"
                DiagramNodeKind.DOCUMENTS, DiagramNodeKind.IMAGE -> "$id[$label]"
                DiagramNodeKind.LINK -> "$id($label)"
                DiagramNodeKind.TEXT -> "$id[$label]"
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
