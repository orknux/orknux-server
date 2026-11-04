package io.mszymanski.orknux.server.diagram

import io.mszymanski.orknux.server.plugin.SvgRenderer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * A diagram holding a WebP picture exported as nothing at all: Batik reads PNG
 * and JPEG only, and one picture it could not read failed the whole PNG.
 */
class DiagramPictureDataTest {

    /** A one-pixel lossless WebP. */
    private val webp = Base64.getDecoder().decode("UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==")

    @Test
    fun `a WebP picture is handed over as PNG`() {
        val data = DiagramPictureData.of("image/webp", webp)
        assertThat(data).startsWith("data:image/png;base64,")
    }

    @Test
    fun `bytes that are no picture are answered with nothing, which draws an empty frame`() {
        assertThat(DiagramPictureData.of("image/webp", "not a picture".toByteArray())).isNull()
    }

    @Test
    fun `a diagram with a WebP picture exports as a PNG`() {
        val shot = DiagramShape("p", DiagramNodeKind.IMAGE, "Screenshot", 0, 0, 240, 160, image = "7")
        val svg = DiagramSvg().drawing(DiagramDrawing(listOf(shot), emptyList())) { DiagramPictureData.of("image/webp", webp) }
        val png = SvgRenderer().png(svg, null)
        assertThat(png).isNotNull
    }
}
