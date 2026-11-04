package io.mszymanski.orknux.server.diagram

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * A picture as a data address an export can draw.
 *
 * Batik, which draws a diagram into a PNG or a PDF, reads PNG and JPEG and
 * nothing else, and one picture it cannot read fails the whole export rather
 * than the picture: a diagram holding one WebP screenshot exported as nothing
 * at all, the browser saying only that the server failed. So anything else -
 * WebP, GIF - is decoded here and handed over as PNG, and a picture that
 * cannot be decoded is answered with null, which draws the empty frame a
 * missing picture gets.
 */
object DiagramPictureData {

    /** What Batik reads as it is. */
    private val DRAWN = setOf("image/png", "image/jpeg")

    init {
        // ImageIO finds its readers once, on the class loader of whoever asks first; inside the
        // packaged jar that is not the one holding the WebP reader, so it is asked to look again.
        val previous = Thread.currentThread().contextClassLoader
        try {
            Thread.currentThread().contextClassLoader = DiagramPictureData::class.java.classLoader
            ImageIO.scanForPlugins()
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
    }

    /** [bytes] of [type] as a data address Batik can draw, or null where they are no picture this can read. */
    fun of(type: String, bytes: ByteArray): String? {
        val said = type.substringBefore(';').trim().lowercase()
        if (said in DRAWN) return address(said, bytes)
        val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        val png = ByteArrayOutputStream()
        if (!ImageIO.write(image, "png", png)) return null
        return address("image/png", png.toByteArray())
    }

    private fun address(type: String, bytes: ByteArray) = "data:$type;base64," + Base64.getEncoder().encodeToString(bytes)
}
