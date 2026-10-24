package io.mszymanski.orknux.server.design

import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.Java2DFrameConverter
import java.awt.image.BufferedImage
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

    private const val MICROS = 1_000_000.0

    /** The one rate Opus encodes at. */
    private const val OPUS_RATE = 48_000
}
