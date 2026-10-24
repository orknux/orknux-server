package io.mszymanski.orknux.server.design

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.connector.model.ModelUsageRepository
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Java2DFrameConverter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ShortBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Upscaling a finished video on Design's Videos page: a real video, made here
 * with the same library the server reads it with, taken apart, sent to a stub
 * Stable Diffusion WebUI's `extra-batch-images` a batch at a time, and put back
 * together - asserted on the file that comes out, read back the same way: as
 * many frames, twice the size, the same rate, the sound kept.
 */
@SpringBootTest(
    properties = [
        "orknux.attachments.location=target/test-design-video-upscale",
        // Four frames in batches of three: two calls, the second one short.
        "orknux.tuning.video.upscale.batch.frames=3",
        // 64 by 48 may go to 128 by 96 and no further than 200 on a side.
        "orknux.tuning.video.upscale.max.pixels=200",
    ],
)
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class DesignVideoUpscaleTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val videos: DesignVideoRepository,
    @Autowired val images: DesignImageRepository,
    @Autowired val jobs: DesignImageJobRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val usage: ModelUsageRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audits: WorkspaceAuditRepository,
    @Autowired val store: AttachmentStore,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0
    private lateinit var webUi: HttpServer

    /** How many frames each batch call carried, and what it was asked with. */
    private val batches = CopyOnWriteArrayList<Int>()
    private val bodies = CopyOnWriteArrayList<String>()

    /** Held until let go, as a slow WebUI would be; open unless a test closes it. */
    @Volatile
    private var gate = CountDownLatch(0)

    @BeforeEach
    fun reset() {
        videos.deleteAll()
        jobs.deleteAll()
        images.deleteAll()
        usage.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "video upscale")).id)
        batches.clear()
        bodies.clear()
        gate = CountDownLatch(0)

        webUi = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        // Each picture twice its size, in the order sent, as A1111 answers.
        webUi.createContext("/sdapi/v1/extra-batch-images") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            gate.await(10, TimeUnit.SECONDS)
            val sent = mapper.readTree(body).path("imageList")
            batches += sent.size()
            bodies += body
            val answered = (0 until sent.size()).map { index ->
                val picture = ImageIO.read(Base64.getDecoder().decode(sent.get(index).path("data").stringValue()).inputStream())
                "\"" + Base64.getEncoder().encodeToString(png(doubled(picture))) + "\""
            }
            json(exchange, 200, """{"html_info":"","images":[${answered.joinToString(",")}]}""")
        }
        webUi.createContext("/sdapi/v1/extra-single-image") { exchange ->
            exchange.requestBody.readBytes()
            json(exchange, 200, """{"html_info":"","image":"${Base64.getEncoder().encodeToString(png(BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)))}"}""")
        }
        webUi.createContext("/sdapi/v1/interrupt") { exchange -> json(exchange, 200, "{}") }
        webUi.start()
    }

    @AfterEach
    fun stop() {
        gate.countDown()
        webUi.stop(0)
    }

    @Test
    fun `a video is made twice as large frame by frame, at the same rate with its sound, as a version beside the original`() {
        val upscaler = upscaleModel()
        val source = finishedVideo()

        val queued = graphQlTester.document(
            """mutation { queueDesignVideoUpscale(input: {
                 workspaceId: $workspaceId, videoId: ${source.id}, modelId: $upscaler, algorithm: "R-ESRGAN 4x+", scale: 2
               }) { id status upscaledFrom versionOf scale upscaler width height fps } }""",
        ).execute()
            .path("queueDesignVideoUpscale.upscaledFrom").entity(String::class.java).isEqualTo(source.id.toString())
            .path("queueDesignVideoUpscale.versionOf").entity(String::class.java).isEqualTo(source.id.toString())
            .path("queueDesignVideoUpscale.upscaler").entity(String::class.java).isEqualTo("R-ESRGAN 4x+")
            .path("queueDesignVideoUpscale.width").entity(Int::class.java).isEqualTo(128)
            .path("queueDesignVideoUpscale.height").entity(Int::class.java).isEqualTo(96)
            .path("queueDesignVideoUpscale.id").entity(String::class.java).get().toLong()

        await().atMost(Duration.ofSeconds(60)).until { videos.findByIdOrNull(queued)?.status in setOf(DesignVideoStatus.DONE, DesignVideoStatus.FAILED) }
        val made = requireNotNull(videos.findByIdOrNull(queued))
        assertThat(made.reason).isNull()
        assertThat(made.status).isEqualTo(DesignVideoStatus.DONE)

        // Two calls, three frames and then one, each with the algorithm and scale asked for.
        assertThat(batches).containsExactly(3, 1)
        assertThat(bodies).allSatisfy { assertThat(it).contains("\"upscaler_1\":\"R-ESRGAN 4x+\"", "\"upscaling_resize\":2.0") }

        // What the page counts its progress from.
        graphQlTester.document("""query { designVideo(workspaceId: $workspaceId, id: $queued) { framesDone framesTotal progress contentType url posterUrl } }""")
            .execute()
            .path("designVideo.framesDone").entity(Int::class.java).isEqualTo(FRAMES)
            .path("designVideo.framesTotal").entity(Int::class.java).isEqualTo(FRAMES)
            .path("designVideo.progress").entity(Int::class.java).isEqualTo(100)
            .path("designVideo.contentType").entity(String::class.java).isEqualTo("video/webm")
            .path("designVideo.url").entity(String::class.java).isEqualTo("/api/design-videos/$queued")
            .path("designVideo.posterUrl").entity(String::class.java).isEqualTo("/api/design-videos/$queued/poster")

        // The file itself: as many frames, twice as wide and high, as many a second, and with sound.
        val out = Files.createTempFile("upscaled-", ".webm")
        try {
            store.open(requireNotNull(made.location)).use { Files.copy(it, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
            FFmpegFrameGrabber(out.toFile()).use { grabber ->
                grabber.start()
                assertThat(grabber.imageWidth to grabber.imageHeight).isEqualTo(128 to 96)
                assertThat(grabber.frameRate).isEqualTo(FPS.toDouble())
                assertThat(grabber.audioChannels).isEqualTo(1)
                var frames = 0
                while (grabber.grabImage() != null) frames++
                assertThat(frames).isEqualTo(FRAMES)
            }
        } finally {
            Files.deleteIfExists(out)
        }
        assertThat(made.durationSeconds).isEqualTo(FRAMES.toDouble() / FPS)
        assertThat(ImageIO.read(store.open(requireNotNull(made.posterLocation))).width).isEqualTo(128)

        // The original is as it was, and still there.
        val original = requireNotNull(videos.findByIdOrNull(requireNotNull(source.id)))
        assertThat(original.location).isEqualTo(source.location)
        assertThat(store.exists(requireNotNull(original.location))).isTrue()
        assertThat(audits.findAll().map { it.message }).anyMatch { it.contains("made 2.0x larger in Design with Enlarger") }
    }

    @Test
    fun `an upscale larger than the installation allows, of a video not finished or by a model that does not upscale, is refused`() {
        val upscaler = upscaleModel()
        val source = finishedVideo()

        graphQlTester.document(
            """mutation { queueDesignVideoUpscale(input: { workspaceId: $workspaceId, videoId: ${source.id}, modelId: $upscaler, scale: 4 }) { id } }""",
        ).execute().errors().expect { it.message == "It would be 256 × 192, longer than the 200 pixels an upscaled video may be." && it.extensions["code"] == "DesignVideoTooLarge" }
            .verify()

        val unfinished = videos.save(
            DesignVideo(workspaceId = workspaceId, modelId = null, modelName = "Wan", prompt = "A cat", name = "Cat", requestedBy = "alice"),
        )
        graphQlTester.document(
            """mutation { queueDesignVideoUpscale(input: { workspaceId: $workspaceId, videoId: ${unfinished.id}, modelId: $upscaler, scale: 2 }) { id } }""",
        ).execute().errors().expect { it.message == "Only a finished video can be made larger." }.verify()

        val chat = model("Talker", "CHAT", "OPENAI")
        graphQlTester.document(
            """mutation { queueDesignVideoUpscale(input: { workspaceId: $workspaceId, videoId: ${source.id}, modelId: $chat, scale: 2 }) { id } }""",
        ).execute().errors().expect { it.message == "Talker is not an upscale model." }.verify()

        assertThat(videos.findAll().filter { it.upscaledFrom != null }).isEmpty()
        assertThat(batches).isEmpty()
    }

    @Test
    fun `a video being upscaled holds a slot of the image queue, so a picture waits behind it`() {
        val upscaler = upscaleModel()
        val source = finishedVideo()
        gate = CountDownLatch(1)

        val queued = graphQlTester.document(
            """mutation { queueDesignVideoUpscale(input: { workspaceId: $workspaceId, videoId: ${source.id}, modelId: $upscaler, scale: 2 }) { id } }""",
        ).execute().path("queueDesignVideoUpscale.id").entity(String::class.java).get().toLong()
        await().atMost(Duration.ofSeconds(20)).until { videos.findByIdOrNull(queued)?.status == DesignVideoStatus.GENERATING }

        // One picture at a time is the installation's limit, and the video has it.
        val picture = whitePicture()
        val job = graphQlTester.document(
            """mutation { queueDesignImageUpscale(input: { workspaceId: $workspaceId, imageId: "DESIGN-${picture.id}", modelId: $upscaler, scale: 2 }) { id } }""",
        ).execute().path("queueDesignImageUpscale.id").entity(String::class.java).get().toLong()
        Thread.sleep(500)
        assertThat(jobs.findByIdOrNull(job)?.status).isEqualTo(DesignImageJobStatus.QUEUED)

        gate.countDown()
        await().atMost(Duration.ofSeconds(60)).until { videos.findByIdOrNull(queued)?.status == DesignVideoStatus.DONE }
        await().atMost(Duration.ofSeconds(20)).until { jobs.findByIdOrNull(job)?.status == DesignImageJobStatus.DRAWN }
    }

    /** A finished four-frame video with a second of silence under it, as a video model would have left it. */
    private fun finishedVideo(): DesignVideo {
        val file = Files.createTempFile("source-", ".webm")
        try {
            write(file)
            val bytes = Files.readAllBytes(file)
            return videos.save(
                DesignVideo(
                    workspaceId = workspaceId, modelId = null, modelName = "Wan", prompt = "A red bar growing", name = "Bar",
                    fps = FPS, frames = FRAMES, requestedBy = "alice", status = DesignVideoStatus.DONE,
                    filename = "bar.webm", contentType = "video/webm", sizeBytes = bytes.size.toLong(),
                    location = store.put(workspaceId, "bar.webm", bytes), durationSeconds = FRAMES.toDouble() / FPS,
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
            recorder.start()
            Java2DFrameConverter().use { converter ->
                repeat(FRAMES) { index ->
                    val picture = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_3BYTE_BGR)
                    picture.createGraphics().apply {
                        color = java.awt.Color.RED
                        fillRect(0, 0, 10 + index * 10, HEIGHT / 2)
                        dispose()
                    }
                    recorder.record(converter.convert(picture))
                    recorder.recordSamples(48_000, 1, ShortBuffer.wrap(ShortArray(48_000 / FPS)))
                }
            }
            recorder.stop()
        }
    }

    private fun whitePicture(): DesignImage {
        val white = png(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, -1) })
        return images.save(
            DesignImage(
                workspaceId = workspaceId, modelId = null, modelName = "Drawer", prompt = "A white square",
                size = null, quality = null, style = null, filename = "white.png", contentType = "image/png",
                sizeBytes = white.size.toLong(), location = store.put(workspaceId, "white.png", white), drawnBy = "alice", name = "White",
            ),
        )
    }

    private fun upscaleModel(): Long = model("Enlarger", "UPSCALE", "STABLE_DIFFUSION_WEBUI", modelId = "Lanczos")

    private fun model(name: String, kind: String, type: String, modelId: String = "stub"): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "$name provider", type: $type,
                 endpoint: "http://${webUi.address.hostString}:${webUi.address.port}"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "$name", modelId: "$modelId", kind: $kind }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }

    private fun doubled(picture: BufferedImage): BufferedImage {
        val larger = BufferedImage(picture.width * 2, picture.height * 2, BufferedImage.TYPE_INT_RGB)
        larger.createGraphics().apply {
            drawImage(picture, 0, 0, larger.width, larger.height, null)
            dispose()
        }
        return larger
    }

    private fun png(picture: BufferedImage): ByteArray = ByteArrayOutputStream().use { bytes ->
        ImageIO.write(picture, "png", bytes)
        bytes.toByteArray()
    }

    private fun json(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private companion object {
        const val WIDTH = 64
        const val HEIGHT = 48
        const val FPS = 8
        const val FRAMES = 4
    }
}
