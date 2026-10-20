package io.mszymanski.orknux.server.design

import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * A picture made another size, with the JDK's own scalers.
 *
 * Smaller by averaging every pixel that falls into one, so a picture halved
 * keeps its detail as a blend rather than losing every other line; larger by
 * bicubic interpolation, which is what a picture scaled back up after a redraw
 * at a smaller size needs to come back soft rather than blocky.
 */
object PictureScaling {

    /** Null for bytes that are not a picture the JDK reads. */
    fun read(bytes: ByteArray): BufferedImage? = runCatching { ImageIO.read(bytes.inputStream()) }.getOrNull()

    fun png(image: BufferedImage): ByteArray = ByteArrayOutputStream().use { bytes ->
        ImageIO.write(image, "png", bytes)
        bytes.toByteArray()
    }

    fun fitted(image: BufferedImage, width: Int, height: Int): BufferedImage {
        if (image.width == width && image.height == height) return image
        val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val smaller = width <= image.width && height <= image.height
        scaled.createGraphics().apply {
            if (smaller) {
                drawImage(image.getScaledInstance(width, height, Image.SCALE_AREA_AVERAGING), 0, 0, null)
            } else {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                drawImage(image, 0, 0, width, height, null)
            }
            dispose()
        }
        return scaled
    }

    /**
     * The size a [width] by [height] picture is redrawn at when no side may be
     * longer than [longest]: the same shape, each side a multiple of eight,
     * because a Stable Diffusion WebUI draws in blocks of eight and rounds a
     * size that is not. Null when the picture is no longer than that already -
     * it is never made larger to be redrawn.
     */
    fun workingSize(width: Int, height: Int, longest: Int): Pair<Int, Int>? {
        if (maxOf(width, height) <= longest) return null
        val ratio = longest.toDouble() / maxOf(width, height)
        fun side(of: Int) = maxOf(BLOCK, (of * ratio).toInt() / BLOCK * BLOCK)
        return side(width) to side(height)
    }

    private const val BLOCK = 8
}
