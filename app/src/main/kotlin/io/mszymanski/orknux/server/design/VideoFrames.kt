package io.mszymanski.orknux.server.design

import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameFilter
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * A video taken apart into pictures and put back together, through FFmpeg as
 * JavaCV wraps it - see app/pom.xml for why that library. What a video upscale
 * needs and nothing more: what a file is, its frames written out one PNG each,
 * and frames from disk encoded at the same rate with the original's sound
 * carried across.
 *
 * Frames go through files rather than memory: a few seconds of video is a
 * hundred pictures, and four times larger each is more than a heap should hold.
 */
object VideoFrames {

    /** What a video is, read off its file. */
    data class Probe(val width: Int, val height: Int, val fps: Double, val hasAudio: Boolean, val format: String?)

    /** FFmpeg talks on stderr at every open; only what went wrong is worth a line in the server's output. */
    private val quiet by lazy { avutil.av_log_set_level(avutil.AV_LOG_ERROR) }

    /**
     * Whether this server can read and write video at all - FFmpeg's libraries
     * are carried for Linux and Windows on x86-64 - and why not where it cannot.
     */
    fun unavailable(): String? = try {
        quiet
        null
    } catch (missing: Throwable) {
        // UnsatisfiedLinkError and NoClassDefFoundError are both errors, and both mean the same here.
        "This server cannot read video on ${System.getProperty("os.name")} ${System.getProperty("os.arch")}: ${missing.message ?: missing.javaClass.simpleName}"
    }

    fun probe(file: Path): Probe {
        quiet
        FFmpegFrameGrabber(file.toFile()).use { grabber ->
            grabber.start()
            return Probe(grabber.imageWidth, grabber.imageHeight, grabber.frameRate, grabber.audioChannels > 0, grabber.format)
        }
    }

    /**
     * Every frame of [file] written into [into] as `frame-000001.png` and on,
     * [onFrame] told the count as each is; answers how many there were.
     */
    fun explode(file: Path, into: Path, onFrame: (Int) -> Unit = {}): Int {
        quiet
        Files.createDirectories(into)
        var count = 0
        FFmpegFrameGrabber(file.toFile()).use { grabber ->
            grabber.start()
            Java2DFrameConverter().use { converter ->
                while (true) {
                    val frame = grabber.grabImage() ?: break
                    val picture = converter.convert(frame) ?: continue
                    count++
                    ImageIO.write(picture, "png", framePath(into, count).toFile())
                    onFrame(count)
                }
            }
            grabber.stop()
        }
        return count
    }

    /**
     * Frames 1 to [count] from [frames] encoded into [out] at [fps], each made
     * [width] by [height] where it is not already, with [audioFrom]'s sound
     * where it has any. [format] is "mp4" (H.264 and AAC) or "webm" (VP8 and
     * Opus). [bitrate] is the video's, in bits a second.
     */
    fun assemble(
        frames: Path,
        count: Int,
        fps: Double,
        width: Int,
        height: Int,
        audioFrom: Path?,
        out: Path,
        format: String,
        bitrate: Int,
    ) {
        quiet
        val mp4 = format == MP4
        val audio = audioFrom?.let { source ->
            FFmpegFrameGrabber(source.toFile()).also { grabber ->
                grabber.start()
                if (grabber.audioChannels <= 0) {
                    grabber.close()
                    return@let null
                }
            }
        }
        try {
            FFmpegFrameRecorder(out.toFile(), width, height, audio?.audioChannels ?: 0).use { recorder ->
                recorder.format = format
                recorder.frameRate = fps
                recorder.videoCodec = if (mp4) avcodec.AV_CODEC_ID_H264 else avcodec.AV_CODEC_ID_VP8
                recorder.pixelFormat = avutil.AV_PIX_FMT_YUV420P
                recorder.videoBitrate = bitrate
                if (audio != null) {
                    recorder.audioCodec = if (mp4) avcodec.AV_CODEC_ID_AAC else avcodec.AV_CODEC_ID_OPUS
                    // Opus is 48 kHz and nothing else; AAC keeps what the sound was.
                    recorder.sampleRate = if (mp4 && audio.sampleRate > 0) audio.sampleRate else OPUS_RATE
                    if (audio.audioBitrate > 0) recorder.audioBitrate = audio.audioBitrate
                }
                recorder.start()
                Java2DFrameConverter().use { converter ->
                    // The sound is written beside the pictures in time order, so the file interleaves as it is written.
                    var sound: Frame? = audio?.grabSamples()
                    for (index in 1..count) {
                        val at = ((index - 1) * MICROS / fps).toLong()
                        while (sound != null && sound.timestamp <= at) {
                            recorder.record(sound)
                            sound = audio?.grabSamples()
                        }
                        val picture = ImageIO.read(framePath(frames, index).toFile())
                            ?: throw IllegalStateException("Frame $index could not be read back")
                        recorder.record(converter.convert(fitted(picture, width, height)))
                    }
                    while (sound != null) {
                        recorder.record(sound)
                        sound = audio?.grabSamples()
                    }
                }
                recorder.stop()
            }
        } finally {
            audio?.close()
        }
    }

    /**
     * [file] encoded again into [out] as H.264 with its sound as AAC, in
     * [container] - FFmpeg's name for it, "mp4" or "matroska". [onFrame] is
     * told after each picture, and is where a caller that wants to stop throws.
     */
    fun transcode(file: Path, out: Path, container: String, onFrame: () -> Unit = {}) {
        quiet
        FFmpegFrameGrabber(file.toFile()).use { grabber ->
            grabber.start()
            val fps = grabber.frameRate.takeIf { it > 0 } ?: FALLBACK_FPS
            val seconds = grabber.lengthInTime / MICROS
            // What the source spent a second, kept: an export is the same video in another box, not a cheaper one.
            val bitrate = if (seconds > 0) (Files.size(file) * 8 / seconds).toLong() else 0L
            FFmpegFrameRecorder(out.toFile(), even(grabber.imageWidth), even(grabber.imageHeight), grabber.audioChannels).use { recorder ->
                recorder.format = container
                recorder.frameRate = fps
                recorder.videoCodec = avcodec.AV_CODEC_ID_H264
                recorder.pixelFormat = avutil.AV_PIX_FMT_YUV420P
                recorder.videoBitrate = bitrate.coerceIn(LEAST_BITRATE.toLong(), Int.MAX_VALUE.toLong()).toInt()
                if (grabber.audioChannels > 0) {
                    recorder.audioCodec = avcodec.AV_CODEC_ID_AAC
                    recorder.sampleRate = grabber.sampleRate.takeIf { it > 0 } ?: OPUS_RATE
                    recorder.audioBitrate = grabber.audioBitrate.takeIf { it > 0 } ?: AAC_BITRATE
                }
                recorder.start()
                while (true) {
                    val frame = grabber.grab() ?: break
                    if (frame.image != null) {
                        // A frame that comes late keeps its place in time rather than closing the gap.
                        if (frame.timestamp > recorder.timestamp) recorder.timestamp = frame.timestamp
                        recorder.record(frame, grabber.pixelFormat)
                        onFrame()
                    } else if (frame.samples != null) {
                        recorder.record(frame)
                    }
                }
                recorder.stop()
            }
            grabber.stop()
        }
    }

    /**
     * [file] as an animated GIF in [out], at most [mostFps] frames a second and
     * [widest] pixels wide, without its sound.
     *
     * A GIF has 256 colours, and which 256 is the whole of whether it looks
     * like the video or like a poster of it. FFmpeg's own answer is two
     * passes, and this is that answer: `palettegen` reads every frame for the
     * 256 colours this video uses most, and `paletteuse` draws each frame in
     * those, dithered - rather than one fixed palette for every video, which
     * is what bands a sky into stripes. Two passes rather than one graph that
     * splits: one graph holds every frame until the palette exists at the end,
     * which is the whole video in memory, and two read the file twice instead.
     */
    fun toGif(file: Path, out: Path, mostFps: Int, widest: Int, onFrame: () -> Unit = {}) {
        quiet
        val (sourceFps, width, height) = FFmpegFrameGrabber(file.toFile()).use { grabber ->
            grabber.start()
            Triple(grabber.frameRate.takeIf { it > 0 } ?: FALLBACK_FPS, grabber.imageWidth, grabber.imageHeight)
        }
        val fps = minOf(mostFps.toDouble(), sourceFps)
        val (gifWidth, gifHeight) = gifSize(width, height, widest)
        val shaped = String.format(java.util.Locale.ROOT, "fps=%.3f,scale=%d:%d:flags=lanczos", fps, gifWidth, gifHeight)

        // The first pass: one picture, sixteen by sixteen, of the 256 colours.
        val palette = eachFrame(file) { grabber, frames ->
            FFmpegFrameFilter("$shaped,palettegen=stats_mode=full", width, height).use { filter ->
                filter.pixelFormat = grabber.pixelFormat
                filter.frameRate = sourceFps
                filter.start()
                frames { filter.push(it, grabber.pixelFormat) }
                filter.push(null)
                val drawn = filter.pullImage() ?: throw IllegalStateException("FFmpeg made no palette for the video")
                val held = (drawn.image[0] as ByteBuffer).duplicate()
                Palette(
                    ByteArray(held.remaining()).also { held.get(it) },
                    drawn.imageWidth,
                    drawn.imageHeight,
                    drawn.imageStride,
                    (drawn.opaque as AVFrame).format(),
                )
            }
        }

        // The second: every frame drawn in those colours, and written as it comes.
        eachFrame(file) { grabber, frames ->
            FFmpegFrameFilter("[0:v]$shaped[shaped];[shaped][1:v]paletteuse=dither=sierra2_4a[v]", width, height).use { filter ->
                filter.pixelFormat = grabber.pixelFormat
                filter.frameRate = sourceFps
                filter.videoInputs = 2
                filter.videoFilterArgs = arrayOf(
                    null,
                    "video_size=${palette.width}x${palette.height}:pix_fmt=${palette.format}:time_base=1/1:pixel_aspect=1/1",
                )
                filter.start()
                FFmpegFrameRecorder(out.toFile(), gifWidth, gifHeight, 0).use { recorder ->
                    recorder.format = GIF
                    recorder.frameRate = fps
                    recorder.videoCodec = avcodec.AV_CODEC_ID_GIF
                    recorder.pixelFormat = avutil.AV_PIX_FMT_PAL8
                    recorder.start()
                    fun drain() {
                        while (true) {
                            // PAL8 is two planes, which a Frame cannot say by itself: told, not guessed.
                            recorder.record(filter.pullImage() ?: break, avutil.AV_PIX_FMT_PAL8)
                            onFrame()
                        }
                    }
                    val paletteFrame = Frame(palette.width, palette.height, Frame.DEPTH_UBYTE, palette.stride / palette.width)
                    (paletteFrame.image[0] as ByteBuffer).put(palette.bytes).rewind()
                    paletteFrame.imageStride = palette.stride
                    filter.push(1, paletteFrame, palette.format)
                    filter.push(1, null)
                    frames {
                        filter.push(0, it, grabber.pixelFormat)
                        drain()
                    }
                    filter.push(0, null)
                    drain()
                    recorder.stop()
                }
            }
        }
    }

    /** A GIF's size: the video's, or [widest] across at the same shape; each side even, as PAL8 is laid out four bytes at a time. */
    fun gifSize(width: Int, height: Int, widest: Int): Pair<Int, Int> {
        if (width <= widest) return even(width) to even(height)
        return even(widest) to even(Math.round(height.toDouble() * widest / width).toInt())
    }

    /** [work] handed the opened file and a way to go through its pictures, one at a time. */
    private fun <T> eachFrame(file: Path, work: (FFmpegFrameGrabber, ((Frame) -> Unit) -> Unit) -> T): T =
        FFmpegFrameGrabber(file.toFile()).use { grabber ->
            grabber.start()
            val answer = work(grabber) { each ->
                while (true) each(grabber.grabImage() ?: break)
            }
            grabber.stop()
            answer
        }

    private class Palette(val bytes: ByteArray, val width: Int, val height: Int, val stride: Int, val format: Int)

    private fun even(side: Int) = maxOf(2, side / 2 * 2)

    fun framePath(dir: Path, index: Int): Path = dir.resolve("frame-%06d.png".format(index))

    /** [picture] as the encoder takes it: three bytes a pixel, at exactly the size the video is. */
    private fun fitted(picture: BufferedImage, width: Int, height: Int): BufferedImage {
        if (picture.width == width && picture.height == height && picture.type == BufferedImage.TYPE_3BYTE_BGR) return picture
        val drawn = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
        val graphics = drawn.createGraphics()
        try {
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.drawImage(picture, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return drawn
    }

    const val MP4 = "mp4"
    const val WEBM = "webm"
    const val GIF = "gif"
    const val MATROSKA = "matroska"

    private const val MICROS = 1_000_000.0

    /** A rate for a file that does not say its own, which an animated picture may not. */
    private const val FALLBACK_FPS = 25.0

    /** The least a re-encode is asked for, in bits a second; below it H.264 draws blocks. */
    private const val LEAST_BITRATE = 250_000

    /** AAC's for a source that did not say what its sound was spent at. */
    private const val AAC_BITRATE = 128_000

    /** The one rate Opus encodes at. */
    private const val OPUS_RATE = 48_000
}
