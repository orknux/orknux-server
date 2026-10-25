package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.server.plugin.SvgRenderer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * A wireframe's arrow keeps its head on the way to a PNG.
 *
 * The chat's picture of a wireframe is its SVG drawn by the server's renderer,
 * which does not read `orient="auto-start-reverse"` - the diagram's arrows
 * already avoided it - so a wireframe arrow reached the chat as a bare line.
 * Measured off the pixels: ink across the arrow's width just before its tip,
 * where a line alone is two or three pixels thick.
 */
class WireframeArrowPngTest {

    @Test
    fun `an arrow drawn to a PNG has its head`() {
        val arrow = WireframeElement("a", WireframeElementType.ARROW, 20, 100, 360, 0, null, emptyMap())
        val svg = requireNotNull(WireframeSvg().drawing(listOf(arrow)))
        val made = SvgRenderer().png(svg, null)
        assertThat(made).describedAs((made as? SvgRenderer.Drawing.Refused)?.toString() ?: "").isInstanceOf(SvgRenderer.Drawing.Drawn::class.java)
        val drawn = made as SvgRenderer.Drawing.Drawn
        val image = ImageIO.read(ByteArrayInputStream(drawn.png))

        // The head's column just behind the tip: count the dark pixels top to bottom.
        val scale = image.width / svgWidth(svg)
        val column = ((20 + 360 - 6) * scale).toInt().coerceIn(0, image.width - 1)
        val inked = (0 until image.height).count { y ->
            val rgb = image.getRGB(column, y)
            val r = (rgb shr 16) and 0xff
            val g = (rgb shr 8) and 0xff
            val b = rgb and 0xff
            r + g + b < 3 * 160
        }
        assertThat(inked.toDouble() / scale).describedAs("dark pixels across the arrow just behind its tip").isGreaterThan(5.0)
    }

    private fun svgWidth(svg: String): Double =
        Regex("""viewBox="[-\d.]+ [-\d.]+ ([\d.]+)""").find(svg)?.groupValues?.get(1)?.toDouble()
            ?: Regex("""width="([\d.]+)""").find(svg)!!.groupValues[1].toDouble()
}
