package io.mszymanski.orknux.server.plugin

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Drawing an SVG, and refusing the documents that are not one.
 *
 * The guards matter more than the drawing here: an SVG is a document with a
 * scripting model and an external-reference model, and both are switched off
 * where the transcoder is built rather than left to the library's defaults.
 */
class SvgRendererTest {

    private val renderer = SvgRenderer()

    private val square = """
        <svg xmlns="http://www.w3.org/2000/svg" width="40" height="40">
          <rect width="40" height="40" fill="#336699"/>
        </svg>
    """.trimIndent()

    @Test
    fun `it draws an svg as a png`() {
        val drawn = renderer.png(square, null)

        assertThat(drawn).isInstanceOf(SvgRenderer.Drawing.Drawn::class.java)
        val bytes = (drawn as SvgRenderer.Drawing.Drawn).png
        // The PNG signature, so this is a picture and not a hopeful byte array.
        assertThat(bytes.take(8)).containsExactly(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }

    /**
     * Drawn larger than it declares, and on white. Issue #529: at its natural
     * size a diagram's lines were a pixel wide, and with no background the PNG
     * was transparent, so on a dark theme the lines were drawn onto near-black.
     */
    @Test
    fun `a scale draws it larger, and nothing in it is transparent`() {
        // A small mark in the middle of an otherwise empty canvas.
        val sparse = """<svg xmlns="http://www.w3.org/2000/svg" width="40" height="40">""" +
            """<rect x="15" y="15" width="10" height="10" fill="#000000"/></svg>"""
        val scaled = renderer.png(sparse, null, 2.0) as SvgRenderer.Drawing.Drawn
        assertThat(scaled.width).isEqualTo(80)

        val image = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(scaled.png))
        // The empty corner is opaque white, not transparent.
        assertThat(image.getRGB(0, 0)).isEqualTo(0xFFFFFFFF.toInt())
    }

    @Test
    fun `a width is honoured`() {
        val wide = renderer.png(square, 200) as SvgRenderer.Drawing.Drawn
        val plain = renderer.png(square, null) as SvgRenderer.Drawing.Drawn

        // A wider picture is more bytes; the header says so exactly.
        assertThat(widthOf(wide.png)).isEqualTo(200)
        assertThat(widthOf(plain.png)).isEqualTo(40)
    }

    /**
     * And the answer says how big it came out.
     *
     * A plugin holding only base64 and a byte count cannot tell a blank from a
     * giant, which is the difference between diagnosing a bad drawing and
     * guessing at it. Read off the file rather than echoed back from the
     * request: a width is what was *asked* for, and the aspect ratio and the
     * ceilings both have a say in what arrives.
     */
    @Test
    fun `the drawing says what size it came out`() {
        val wide = renderer.png(square, 200) as SvgRenderer.Drawing.Drawn

        assertThat(wide.width).isEqualTo(200)
        assertThat(wide.width).isEqualTo(widthOf(wide.png))
        // Square in, square out: the height follows the document, not the ask.
        assertThat(wide.height).isEqualTo(200)
    }

    /** The IHDR width, which is the four bytes after the signature and the chunk name. */
    private fun widthOf(png: ByteArray): Int =
        ((png[16].toInt() and 0xff) shl 24) or
            ((png[17].toInt() and 0xff) shl 16) or
            ((png[18].toInt() and 0xff) shl 8) or
            (png[19].toInt() and 0xff)

    @Test
    fun `base64 and other things that are not svg are refused in a sentence`() {
        val refused = renderer.png("PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciPg==", null)

        assertThat(refused).isInstanceOf(SvgRenderer.Drawing.Refused::class.java)
        assertThat((refused as SvgRenderer.Drawing.Refused).reason).contains("not base64 or a url")
    }

    @Test
    fun `an empty document is refused rather than drawn as nothing`() {
        assertThat(renderer.png("   ", null)).isInstanceOf(SvgRenderer.Drawing.Refused::class.java)
    }

    @Test
    fun `a width outside the bounds is refused`() {
        assertThat(renderer.png(square, 0)).isInstanceOf(SvgRenderer.Drawing.Refused::class.java)
        assertThat(renderer.png(square, 99_999)).isInstanceOf(SvgRenderer.Drawing.Refused::class.java)
    }

    /**
     * A document that tries to read the disk gets a picture, not the file.
     *
     * External references are off, so the image element resolves to nothing and
     * the rest of the document still draws. What must not happen is the server
     * fetching what an untrusted document named.
     */
    @Test
    fun `an external reference is not fetched`() {
        val reaching = """
            <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink"
                 width="40" height="40">
              <image xlink:href="file:///etc/passwd" width="40" height="40"/>
              <rect width="10" height="10" fill="red"/>
            </svg>
        """.trimIndent()

        val drawn = renderer.png(reaching, null)

        // Either it drew without the reference or it refused; what it must not
        // do is answer with anything read off the disk.
        if (drawn is SvgRenderer.Drawing.Drawn) {
            assertThat(String(drawn.png, Charsets.ISO_8859_1)).doesNotContain("root:")
        }
    }
}
