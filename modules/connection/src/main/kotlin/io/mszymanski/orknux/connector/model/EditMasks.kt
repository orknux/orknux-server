package io.mszymanski.orknux.connector.model

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** A standard edit mask redrawn for a server that reads it another way. See [EditMask]. */
object EditMasks {

    /**
     * Transparent-marks-the-change as one greyscale channel, white for the
     * change and black elsewhere - the mask stable-diffusion.cpp's server
     * documents. The mask as it came where it cannot be read, so a server is
     * never sent nothing.
     */
    fun whiteOnBlack(mask: ByteArray): ByteArray {
        val read = runCatching { ImageIO.read(mask.inputStream()) }.getOrNull() ?: return mask
        val out = BufferedImage(read.width, read.height, BufferedImage.TYPE_BYTE_GRAY)
        for (y in 0 until read.height) {
            for (x in 0 until read.width) {
                val transparent = (read.getRGB(x, y) ushr 24) < OPAQUE_ENOUGH
                out.setRGB(x, y, if (transparent) WHITE else BLACK)
            }
        }
        return ByteArrayOutputStream().use { bytes ->
            ImageIO.write(out, "png", bytes)
            bytes.toByteArray()
        }
    }

    /** Alpha below this is "change here": a mask drawn with soft edges still has a clear inside. */
    private const val OPAQUE_ENOUGH = 128
    private const val WHITE = 0xFFFFFF
    private const val BLACK = 0x000000
}
