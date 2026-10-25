package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Java2DFrameConverter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.http.HttpHeaders
import org.springframework.security.test.context.support.WithMockUser
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.ShortBuffer
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Exporting a finished video from the video editor as a GIF, an MP4 or an
 * MKV: a real video, made here with the library the server converts it with,
 * exported through the mutation and downloaded from the address it answers -
 * and the file that comes out read back the same way.
 */
@SpringBootTest(
    properties = [
        "orknux.attachments.location=target/test-design-video-export",
        // Eight frames a second for one second, at 64 by 48: a GIF of it is halved in both.
        "orknux.tuning.video.export.gif.fps=4",
        "orknux.tuning.video.export.gif.width=32",
    ],
)
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class DesignVideoExportTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val videos: DesignVideoRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audits: WorkspaceAuditRepository,
    @Autowired val store: AttachmentStore,
    @Autowired val api: DesignVideoAPI,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        videos.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "video export ${java.util.UUID.randomUUID()}")).id)
    }

    @Test
    fun `a GIF is the video at the GIF's rate and width, without sound, in a palette of its own colours`() {
        val video = finishedVideo()
        val file = exported(video, "GIF", "gif")
        try {
            assertThat(String(Files.readAllBytes(file).copyOf(6), Charsets.US_ASCII)).isEqualTo("GIF89a")
            val reader = ImageIO.getImageReadersByFormatName("gif").next()
            ImageIO.createImageInputStream(file.toFile()).use { input ->
                reader.input = input
                // Eight frames at eight a second, made four a second: four.
                assertThat(reader.getNumImages(true)).isEqualTo(FRAMES * 4 / FPS)
                val first = reader.read(0)
                assertThat(first.width to first.height).isEqualTo(32 to 24)
                /*
                 * The first frame is a red ramp from black to full across its
                 * width. One fixed palette for every video has eight reds; a
                 * palette made from this video has a red for nearly every
                 * column. The ramp counted is what proves which was used.
                 */
                val reds = (0 until first.width).map { x -> Color(first.getRGB(x, first.height / 4)).red }.toSet()
                assertThat(reds.size).isGreaterThanOrEqualTo(16)
            }
            reader.dispose()
        } finally {
            Files.deleteIfExists(file)
        }
        assertThat(audits.findAll().map { it.message }).contains("Video Ramp exported as GIF from Design")
    }

    @Test
    fun `an MP4 and an MKV are the video re-encoded as H264 with its sound, every frame kept`() {
        val video = finishedVideo()
        for ((format, extension, container) in listOf(Triple("MP4", "mp4", "mp4"), Triple("MKV", "mkv", "matroska"))) {
            val file = exported(video, format, extension)
            try {
                FFmpegFrameGrabber(file.toFile()).use { grabber ->
                    grabber.start()
                    assertThat(grabber.format).contains(container)
                    assertThat(grabber.videoCodec).isEqualTo(avcodec.AV_CODEC_ID_H264)
                    assertThat(grabber.imageWidth to grabber.imageHeight).isEqualTo(WIDTH to HEIGHT)
                    assertThat(grabber.audioChannels).isEqualTo(1)
                    assertThat(grabber.audioCodec).isEqualTo(avcodec.AV_CODEC_ID_AAC)
                    var frames = 0
                    while (grabber.grabImage() != null) frames++
                    assertThat(frames).isEqualTo(FRAMES)
                }
            } finally {
                Files.deleteIfExists(file)
            }
        }
    }

    @Test
    fun `a video not finished is refused in a sentence a client can translate`() {
        val unfinished = videos.save(
            DesignVideo(workspaceId = workspaceId, modelId = null, modelName = "Wan", prompt = "A cat", name = "Cat", requestedBy = "alice"),
        )
        graphQlTester.document("""mutation { exportDesignVideo(workspaceId: $workspaceId, id: ${unfinished.id}, format: GIF) { url } }""")
            .execute().errors()
            .expect { it.message == "Only a finished video can be exported." && it.extensions["code"] == "DesignVideoExportUnfinished" }
            .verify()
        assertThat(api.exported(requireNotNull(unfinished.id), "gif").statusCode.value()).isEqualTo(404)
    }

    @Test
    fun `an export over its time or its size is stopped where it is`() {
        val out = Files.createTempFile("limits-", ".gif")
        try {
            var now = 0L
            val timed = ExportLimits(seconds = 5, megabytes = 1, out = out, clock = { now })
            timed.check()
            now = 6_000_000_000L
            assertThatThrownBy { timed.check() }.isInstanceOf(DesignVideoExportTimedOutException::class.java)
                .hasMessage("The export took longer than the 5 seconds an export may take.")

            Files.write(out, ByteArray(1024 * 1024 + 1))
            val sized = ExportLimits(seconds = 5, megabytes = 1, out = out, clock = { 0L })
            assertThatThrownBy { sized.check(final = true) }.isInstanceOf(DesignVideoExportTooLargeException::class.java)
                .hasMessage("The export came to more than the 1 MB an export may be.")
        } finally {
            Files.deleteIfExists(out)
        }
    }

    @Test
    fun `a GIF is narrowed to its width at the video's shape, each side even`() {
        assertThat(VideoFrames.gifSize(1280, 720, 480)).isEqualTo(480 to 270)
        assertThat(VideoFrames.gifSize(333, 201, 480)).isEqualTo(332 to 200)
        assertThat(VideoFrames.gifSize(1000, 333, 480)).isEqualTo(480 to 160)
    }

    /** The video exported as [format] through the mutation, and the file downloaded from the address it answered. */
    private fun exported(video: DesignVideo, format: String, extension: String): Path {
        graphQlTester.document("""mutation { exportDesignVideo(workspaceId: $workspaceId, id: ${video.id}, format: $format) { url filename contentType sizeBytes } }""")
            .execute()
            .path("exportDesignVideo.url").entity(String::class.java).isEqualTo("/api/design-videos/${video.id}/export/$extension")
            .path("exportDesignVideo.filename").entity(String::class.java).isEqualTo("ramp.$extension")
        val answer = api.exported(requireNotNull(video.id), extension)
        assertThat(answer.statusCode.value()).isEqualTo(200)
        assertThat(answer.headers.getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment").contains("ramp.$extension")
        val file = Files.createTempFile("exported-", ".$extension")
        requireNotNull(answer.body).inputStream.use { Files.copy(it, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        assertThat(Files.size(file)).isEqualTo(answer.headers.contentLength)
        return file
    }

    /** A finished second of video: a red ramp that slides, with silence under it, as a video model would have left it. */
    private fun finishedVideo(): DesignVideo {
        val file = Files.createTempFile("source-", ".webm")
        try {
            write(file)
            val bytes = Files.readAllBytes(file)
            return videos.save(
                DesignVideo(
                    workspaceId = workspaceId, modelId = null, modelName = "Wan", prompt = "A red ramp", name = "Ramp",
                    fps = FPS, frames = FRAMES, requestedBy = "alice", status = DesignVideoStatus.DONE,
                    filename = "ramp.webm", contentType = "video/webm", sizeBytes = bytes.size.toLong(),
                    location = store.put(workspaceId, "ramp.webm", bytes), durationSeconds = FRAMES.toDouble() / FPS,
                ),
            )
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun write(file: Path) {
        FFmpegFrameRecorder(file.toFile(), WIDTH, HEIGHT, 1).use { recorder ->
            recorder.format = "webm"
            recorder.frameRate = FPS.toDouble()
            recorder.videoCodec = avcodec.AV_CODEC_ID_VP8
            recorder.audioCodec = avcodec.AV_CODEC_ID_OPUS
            recorder.sampleRate = 48_000
            recorder.pixelFormat = avutil.AV_PIX_FMT_YUV420P
            recorder.videoBitrate = 2_000_000
            recorder.start()
            Java2DFrameConverter().use { converter ->
                repeat(FRAMES) { index ->
                    val picture = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_3BYTE_BGR)
                    for (x in 0 until WIDTH) {
                        val red = x * 255 / (WIDTH - 1)
                        for (y in 0 until HEIGHT / 2) picture.setRGB(x, y, Color(red, 0, 0).rgb)
                        for (y in HEIGHT / 2 until HEIGHT) picture.setRGB(x, y, Color(0, (x * 8 + index * 20) % 256, 255 - red).rgb)
                    }
                    recorder.record(converter.convert(picture))
                    recorder.recordSamples(48_000, 1, ShortBuffer.wrap(ShortArray(48_000 / FPS)))
                }
            }
            recorder.stop()
        }
    }

    private companion object {
        const val WIDTH = 64
        const val HEIGHT = 48
        const val FPS = 8
        const val FRAMES = 8
    }
}
