package io.mszymanski.orknux.server.design

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt

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
 *
 * A feather softens the selection's edge, so the redrawn part blends into the
 * picture rather than meeting it at a seam: across about [feather] pixels
 * either side of the edge the two are mixed, the change fading out as the
 * original fades in. Done here rather than by blurring the mask the model is
 * sent, because the standard edit endpoint reads a mask's alpha as yes or no -
 * a soft mask is a smaller selection, not a softer one. Issue #638.
 */
object MaskedComposite {

    /** Null where either picture cannot be read, in which case the model's answer is kept as it is. */
    fun keepOutside(original: ByteArray, changed: ByteArray, mask: ByteArray, feather: Int = 0): ByteArray? {
        val base = read(original) ?: return null
        val answer = read(changed)?.let { fitted(it, base.width, base.height) } ?: return null
        val width = base.width
        val height = base.height
        val weight = weightsOf(mask, width, height, feather) ?: return null

        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val share = weight[y * width + x]
                out.setRGB(
                    x,
                    y,
                    when {
                        share >= 1f -> answer.getRGB(x, y)
                        share <= 0f -> base.getRGB(x, y)
                        else -> mixed(base.getRGB(x, y), answer.getRGB(x, y), share)
                    },
                )
            }
        }
        return ByteArrayOutputStream().use { bytes ->
            ImageIO.write(out, "png", bytes)
            bytes.toByteArray()
        }
    }

    /**
     * The model's change alone, on a layer of its own: its pixels where the
     * selection is, fading out across the feather, and transparent everywhere
     * else - what a picture with layers gets a Magic edit as, over the layers
     * it was asked of. Laid over the picture as it was, it is exactly what
     * [keepOutside] makes. Null where either picture cannot be read.
     */
    fun changeOnly(original: ByteArray, changed: ByteArray, mask: ByteArray, feather: Int = 0): ByteArray? {
        val base = read(original) ?: return null
        val answer = read(changed)?.let { fitted(it, base.width, base.height) } ?: return null
        val width = base.width
        val height = base.height
        val weight = weightsOf(mask, width, height, feather) ?: return null
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = answer.getRGB(x, y)
                val alpha = ((pixel ushr 24) * weight[y * width + x]).roundToInt().coerceIn(0, 255)
                out.setRGB(x, y, (alpha shl 24) or (pixel and 0xFFFFFF))
            }
        }
        return PictureScaling.png(out)
    }

    /** How much of the change each pixel takes: all of it inside the selection, none outside, softened across the feather. */
    private fun weightsOf(mask: ByteArray, width: Int, height: Int, feather: Int): FloatArray? {
        val where = read(mask)?.let { fitted(it, width, height) } ?: return null
        val weight = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                if ((where.getRGB(x, y) ushr 24) < OPAQUE_ENOUGH) weight[y * width + x] = 1f
            }
        }
        if (feather > 0) softened(weight, width, height, feather)
        return weight
    }

    /** Each channel of the two, [share] of the way from [from] to [to]. */
    private fun mixed(from: Int, to: Int, share: Float): Int {
        var pixel = 0
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val a = (from ushr shift) and 0xFF
            val b = (to ushr shift) and 0xFF
            pixel = pixel or ((a + (b - a) * share).roundToInt().coerceIn(0, 255) shl shift)
        }
        return pixel
    }

    /**
     * The weights blurred in place: three box blurs a row and a column at a
     * time, which is close to a Gaussian and costs the same for any radius.
     * Each box reaches half the feather either way, so the three together
     * spread the edge over about the feather either side.
     */
    private fun softened(weight: FloatArray, width: Int, height: Int, feather: Int) {
        val reach = reachOf(feather, maxOf(width, height))
        val line = FloatArray(maxOf(width, height))
        repeat(PASSES) {
            for (y in 0 until height) boxed(weight, y * width, 1, width, reach, line)
            for (x in 0 until width) boxed(weight, x, width, height, reach, line)
        }
    }

    /**
     * Half the feather either way, at least a pixel - and no further than the
     * picture's longer side. The feather has no ceiling of its own, and a box
     * reaching past every pixel of its line only averages the same pixels again:
     * held here, a feather of Int.MAX_VALUE neither wraps negative in `2 * reach`
     * nor loops two billion times a pixel.
     */
    internal fun reachOf(feather: Int, longest: Int): Int =
        ((feather.toLong() + 1) / 2).coerceIn(1L, maxOf(1, longest).toLong()).toInt()

    /** One row or column, from [start] by [step] for [count], averaged over [reach] either side; the edge pixel stands in past the edge. */
    private fun boxed(weight: FloatArray, start: Int, step: Int, count: Int, reach: Int, line: FloatArray) {
        for (i in 0 until count) line[i] = weight[start + i * step]
        val span = 2 * reach + 1
        var sum = 0f
        for (j in -reach..reach) sum += line[j.coerceIn(0, count - 1)]
        for (i in 0 until count) {
            weight[start + i * step] = sum / span
            sum += line[(i + reach + 1).coerceAtMost(count - 1)] - line[(i - reach).coerceAtLeast(0)]
        }
    }

    private fun read(bytes: ByteArray): BufferedImage? = PictureScaling.read(bytes)

    private fun fitted(image: BufferedImage, width: Int, height: Int): BufferedImage = PictureScaling.fitted(image, width, height)

    /** Alpha below this is "change here": a mask drawn with soft edges still has a clear inside. */
    private const val OPAQUE_ENOUGH = 128

    /** Three box blurs make a curve close to a Gaussian's. */
    private const val PASSES = 3
}
