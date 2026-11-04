package io.mszymanski.orknux.server.diagram

import io.mszymanski.orknux.server.plugin.SvgRenderer
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

/**
 * A diagram's PDF is the drawing, not a picture of it, and its links open.
 *
 * It was the PNG on a page: soft past its own zoom, and a box's link only a
 * picture of a link. Now the page is drawn as vectors, and every box that
 * leads somewhere is an area a PDF reader follows.
 */
class DiagramPdfTest {

    private val export = DiagramExport(SvgRenderer(), ObjectMapper())

    private val drawing = DiagramDrawing(
        listOf(
            DiagramShape("a", DiagramNodeKind.BOX, "Orders", 0, 0, 160, 60),
            DiagramShape("t", DiagramNodeKind.TEXT, "Spec", 240, 0, 120, 40, link = "document:7"),
            DiagramShape("w", DiagramNodeKind.LINK, "Handbook", 0, 120, 220, 64, link = "https://example.test/handbook"),
        ),
        emptyList(),
    )

    private fun pdf(): ByteArray {
        val made = export.export(drawing, DiagramFormat.PDF, links = { link ->
            if (link.startsWith("https://")) link else "https://orknux.example/workspace/1/design/documents/" + link.substringAfter(':')
        })
        assertThat(made).isInstanceOf(DiagramExport.Made.Written::class.java)
        return (made as DiagramExport.Made.Written).file.bytes
    }

    @Test
    fun `the page is drawn as vectors rather than as one picture`() {
        Loader.loadPDF(pdf()).use { document ->
            val page = document.getPage(0)
            val pictures = page.resources.xObjectNames.count { page.resources.isImageXObject(it) }
            assertThat(pictures).describedAs("no picture of the diagram pasted on the page").isZero()
            // Batik draws the SVG into a form the page shows, so the instructions are counted there too.
            val drawn = page.contents.readAllBytes().size + page.resources.xObjectNames.sumOf { name ->
                runCatching { page.resources.getXObject(name).stream.length }.getOrDefault(0)
            }
            assertThat(drawn).describedAs("drawing instructions on the page").isGreaterThan(1000)
        }
    }

    @Test
    fun `every box that leads somewhere is a link a reader follows`() {
        Loader.loadPDF(pdf()).use { document ->
            val targets = document.getPage(0).annotations
                .filterIsInstance<PDAnnotationLink>()
                .mapNotNull { (it.action as? PDActionURI)?.uri }
                .distinct()
            assertThat(targets).containsExactlyInAnyOrder(
                "https://orknux.example/workspace/1/design/documents/7",
                "https://example.test/handbook",
            )
        }
    }

    /**
     * A diagram with a picture in it came out a blank page: the picture was
     * refused as an outside fetch, and the whole drawing went with it.
     */
    @Test
    fun `a diagram with a picture in it is drawn, picture and all`() {
        val pixel = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        val pictured = DiagramDrawing(
            drawing.nodes + DiagramShape("p", DiagramNodeKind.IMAGE, "Logo", 400, 120, 120, 80, image = "https://example.test/logo.png"),
            emptyList(),
        )
        val built = DiagramExport(SvgRenderer(), ObjectMapper())
        val svgOnly = DiagramSvg().drawing(pictured) { if (it.id == "p") "data:image/png;base64,$pixel" else null }
        assertThat(svgOnly).contains("data:image/png")
        // Through the export, with the picture handed in as the export hands it.
        val made = built.pdfOf(pictured, { if (it.id == "p") "data:image/png;base64,$pixel" else null })
        Loader.loadPDF(made).use { document ->
            val page = document.getPage(0)
            val drawn = page.resources.xObjectNames.sumOf { name -> runCatching { page.resources.getXObject(name).stream.length }.getOrDefault(0) }
            assertThat(drawn).describedAs("the drawing is on the page, not a blank").isGreaterThan(1000)
        }
    }
}
