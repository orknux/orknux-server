package io.mszymanski.orknux.server.design

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * The model's change, inside the selection only.
 *
 * An edit model is handed a mask and some treat it as a hint: the picture comes
 * back redrawn everywhere, a little off where nothing was asked of it. So the
 * answer is laid over the original through the mask - the changed pixels where
 * the mask is transparent, the original's own everywhere else - and what was
 * not selected is exactly what it was. Always rather than for some models,
 * because which models respect the mask is not something the endpoint says.
 *
 * The answer is scaled to the original's size first: a model may answer at a
 * size of its own.
 */
object MaskedComposite {

    /** Null where either picture cannot be read, in which case the model's answer is kept as it is. */
    fun keepOutside(original: ByteArray, changed: ByteArray, mask: ByteArray): ByteArray? {
        val base = read(original) ?: return null
        val answer = read(changed)?.let { fitted(it, base.width, base.height) } ?: return null
        val where = read(mask)?.let { fitted(it, base.width, base.height) } ?: return null

        val out = BufferedImage(base.width, base.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until base.height) {
            for (x in 0 until base.width) {
                val transparent = (where.getRGB(x, y) ushr 24) < OPAQUE_ENOUGH
                out.setRGB(x, y, if (transparent) answer.getRGB(x, y) else base.getRGB(x, y))
            }
        }
        return ByteArrayOutputStream().use { bytes ->
            ImageIO.write(out, "png", bytes)
            bytes.toByteArray()
        }
    }

    private fun read(bytes: ByteArray): BufferedImage? = runCatching { ImageIO.read(bytes.inputStream()) }.getOrNull()

    private fun fitted(image: BufferedImage, width: Int, height: Int): BufferedImage {
        if (image.width == width && image.height == height) return image
        val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        scaled.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(image, 0, 0, width, height, null)
            dispose()
        }
        return scaled
    }

    /** Alpha below this is "change here": a mask drawn with soft edges still has a clear inside. */
    private const val OPAQUE_ENOUGH = 128
}
