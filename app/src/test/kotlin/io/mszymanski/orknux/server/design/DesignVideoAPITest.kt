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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Design's Videos page, against stub servers playing each backend a video
 * model can be: stable-diffusion.cpp's native job API, and OpenAI's `/videos`
 * as the official SDK speaks it. Issue #658.
 *
 * Asserted at the wire - what each backend is sent - and at the end - the file
 * kept, what the page is told on the way - because a video that never comes
 * back is otherwise a page saying "Making…" for ever.
 */
@SpringBootTest(
    properties = [
        "orknux.attachments.location=target/test-design-videos",
        // Asked about every second rather than every five: a test that waits must not really wait.
        "orknux.tuning.video.poll.seconds=1",
    ],
)
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class DesignVideoAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val videos: DesignVideoRepository,
    @Autowired val images: DesignImageRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val usage: ModelUsageRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audits: WorkspaceAuditRepository,
    @Autowired val store: AttachmentStore,
    @Autowired val api: DesignVideoAPI,
    @Autowired val queue: DesignVideoQueue,
    @Autowired val client: io.mszymanski.orknux.connector.model.ModelVideoClient,
) {

    private var workspaceId: Long = 0
    private lateinit var sdcpp: HttpServer
    private lateinit var openai: HttpServer

    /** Bodies stable-diffusion.cpp was sent, by path. */
    private val sdAsked = CopyOnWriteArrayList<Pair<String, String>>()
    private val openAiAsked = CopyOnWriteArrayList<Pair<String, String>>()

    /** How many times the job has been asked about; it is made on the third. */
    private val polls = AtomicInteger()

    @Volatile
    private var takesStartImage = true

    @Volatile
    private var failing = false

    /** Held at "generating" until let go, as a long video would be. */
    @Volatile
    private var holding = false

    @BeforeEach
    fun reset() {
        videos.deleteAll()
        images.deleteAll()
        usage.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "videos")).id)
        sdAsked.clear()
        openAiAsked.clear()
        polls.set(0)
        takesStartImage = true
        failing = false
        holding = false

        sdcpp = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        sdcpp.createContext("/sdcpp/v1/capabilities") { exchange ->
            json(
                exchange, 200,
                """{
                  "supported_modes": ["vid_gen"],
                  "defaults_by_mode": {"vid_gen": {"width": 832, "height": 480, "seed": -1, "video_frames": 33, "fps": 16}},
                  "output_formats_by_mode": {"vid_gen": ["avi", "webm", "webp"]},
                  "features_by_mode": {"vid_gen": {"init_image": $takesStartImage, "end_image": false}},
                  "preview_modes": ["none", "proj", "tae", "vae"],
                  "limits": {"min_width": 64, "max_width": 2048, "min_height": 64, "max_height": 2048}
                }""",
            )
        }
        sdcpp.createContext("/sdcpp/v1/vid_gen") { exchange ->
            sdAsked += exchange.requestURI.path to exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            json(exchange, 202, """{"id":"job_V1","kind":"vid_gen","status":"queued","created":1,"poll_url":"/sdcpp/v1/jobs/job_V1"}""")
        }
        sdcpp.createContext("/sdcpp/v1/jobs/") { exchange ->
            if (exchange.requestURI.path.endsWith("/cancel")) {
                sdAsked += exchange.requestURI.path to ""
                json(exchange, 200, """{"id":"job_V1","status":"generating","cancel_requested":true}""")
                return@createContext
            }
            val asked = polls.incrementAndGet()
            when {
                failing -> json(
                    exchange, 200,
                    """{"id":"job_V1","kind":"vid_gen","status":"failed","result":null,"error":{"code":"generation_failed","message":"generate_video returned no results"}}""",
                )
                asked == 1 -> json(exchange, 200, """{"id":"job_V1","kind":"vid_gen","status":"queued","queue_position":1,"result":null,"error":null}""")
                asked == 2 || holding -> json(
                    exchange, 200,
                    """{"id":"job_V1","kind":"vid_gen","status":"generating","result":null,"error":null,
                        "preview":{"pass":1,"step":5,"total_steps":10,"b64_json":"$PIXEL"}}""",
                )
                else -> json(
                    exchange, 200,
                    """{"id":"job_V1","kind":"vid_gen","status":"completed","error":null,
                        "result":{"output_format":"webm","mime_type":"video/webm","fps":16,"frame_count":33,
                        "b64_json":"${Base64.getEncoder().encodeToString(WEBM)}"}}""",
                )
            }
        }
        sdcpp.start()

        // OpenAI's /videos, as the SDK asks it: a multipart create, a retrieve, and the content.
        openai = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        openai.createContext("/videos") { exchange ->
            val path = exchange.requestURI.path
            val query = exchange.requestURI.query.orEmpty()
            openAiAsked += "${exchange.requestMethod} $path?$query" to
                exchange.requestBody.readBytes().toString(StandardCharsets.ISO_8859_1)
            when {
                exchange.requestMethod == "POST" && path == "/videos" -> json(exchange, 200, videoJson("queued", 0))
                path == "/videos/video_A/content" -> {
                    val bytes = if (query.contains("variant=thumbnail")) POSTER else MP4
                    exchange.responseHeaders.add("Content-Type", if (query.contains("thumbnail")) "image/webp" else "video/mp4")
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                    exchange.close()
                }
                path == "/videos/video_A" -> {
                    val asked = polls.incrementAndGet()
                    json(exchange, 200, if (asked < 2) videoJson("in_progress", 40) else videoJson("completed", 100))
                }
                else -> json(exchange, 404, """{"error":{"message":"No such thing"}}""")
            }
        }
        // Not stable-diffusion.cpp: its capabilities are not here, so the OpenAI shape is spoken.
        openai.createContext("/sdcpp/v1/capabilities") { exchange -> json(exchange, 404, "{}") }
        openai.start()
    }

    @AfterEach
    fun stop() {
        sdcpp.stop(0)
        openai.stop(0)
    }

    @Test
    fun `stable-diffusion cpp offers what its loaded model takes, and nothing it does not`() {
        val modelId = model("Wan", sdcpp)

        graphQlTester.document(
            """query { designVideoSettings(workspaceId: $workspaceId, modelId: $modelId) { reason settings { name kind least most preset } } }""",
        ).execute()
            .path("designVideoSettings.reason").valueIsNull()
            .path("designVideoSettings.settings[*].name").entityList(String::class.java)
            .containsExactly("frames", "fps", "width", "height", "seed", "startImage")
            .path("designVideoSettings.settings[0].preset").entity(String::class.java).isEqualTo("33")
            .path("designVideoSettings.settings[2].most").entity(String::class.java).isEqualTo("2048")

        // A text-to-video model takes no picture to start from, so none is offered.
        takesStartImage = false
        graphQlTester.document(
            """query { designVideoSettings(workspaceId: $workspaceId, modelId: $modelId) { settings { name } } }""",
        ).execute()
            .path("designVideoSettings.settings[*].name").entityList(String::class.java)
            .containsExactly("frames", "fps", "width", "height", "seed")
    }

    @Test
    fun `a video is asked of stable-diffusion cpp, followed to the end, and kept with its start picture as its poster`() {
        val modelId = model("Wan", sdcpp)
        val start = picture()

        val id = graphQlTester.document(
            """mutation { queueDesignVideo(input: {
                 workspaceId: $workspaceId, modelId: $modelId, prompt: "A cat walking in the rain",
                 frames: 33, fps: 16, width: 832, height: 480, seed: "42", startImageId: "$start"
               }) { id status model name requestedBy } }""",
        ).execute()
            .path("queueDesignVideo.model").entity(String::class.java).isEqualTo("Wan")
            .path("queueDesignVideo.name").entity(String::class.java).isEqualTo("A cat walking in the rain")
            .path("queueDesignVideo.requestedBy").entity(String::class.java).isEqualTo("alice")
            .path("queueDesignVideo.id").entity(String::class.java).get()

        // Half way: what the page is told while it is made.
        await().atMost(Duration.ofSeconds(10)).until { videos.findByIdOrNull(id.toLong())?.progress == 50 }
        val before = java.time.OffsetDateTime.now().minusSeconds(1)
        val halfWay = graphQlTester.document(
            """query { designVideo(workspaceId: $workspaceId, id: $id) { status progress url startedAt asOf progressSamplingOnly } }""",
        )
            .execute()
            .path("designVideo.status").entity(String::class.java).isEqualTo("GENERATING")
            .path("designVideo.url").valueIsNull()
            // stable-diffusion.cpp's progress is its sampling steps: the decode after them is not in it, and the page says so.
            .path("designVideo.progressSamplingOnly").entity(Boolean::class.java).isEqualTo(true)
        // The server's clock beside when it started, so a page counts how long it has taken on one clock.
        val startedAt = java.time.OffsetDateTime.parse(halfWay.path("designVideo.startedAt").entity(String::class.java).get())
        val asOf = java.time.OffsetDateTime.parse(halfWay.path("designVideo.asOf").entity(String::class.java).get())
        assertThat(asOf).isAfter(before)
        assertThat(asOf).isAfterOrEqualTo(startedAt)

        await().atMost(Duration.ofSeconds(15)).until { videos.findByIdOrNull(id.toLong())?.status == DesignVideoStatus.DONE }

        // What the server was sent is what the page said, in its native names.
        val (path, body) = sdAsked.first { it.first == "/sdcpp/v1/vid_gen" }
        assertThat(path).isEqualTo("/sdcpp/v1/vid_gen")
        assertThat(body).contains(
            "\"prompt\":\"A cat walking in the rain\"", "\"video_frames\":33", "\"fps\":16", "\"width\":832",
            "\"height\":480", "\"seed\":42", "\"init_image\":\"data:image/png;base64,", "\"output_format\":\"webm\"",
            "\"preview\":\"proj\"",
        )
        assertThat(body).doesNotContain("\"seconds\"")

        val kept = requireNotNull(videos.findByIdOrNull(id.toLong()))
        assertThat(kept.contentType).isEqualTo("video/webm")
        assertThat(kept.filename).isEqualTo("a-cat-walking-in-the-rain.webm")
        assertThat(kept.durationSeconds).isEqualTo(33.0 / 16)
        assertThat(store.open(requireNotNull(kept.location)).use { it.readBytes() }).isEqualTo(WEBM)
        // stable-diffusion.cpp gives no poster; the picture it started from is its first frame.
        assertThat(store.open(requireNotNull(kept.posterLocation)).use { it.readBytes() }).isEqualTo(Base64.getDecoder().decode(PIXEL))

        graphQlTester.document(
            """query { designVideos(workspaceId: $workspaceId) { id status url posterUrl contentType startImageId durationSeconds } }""",
        ).execute()
            .path("designVideos[0].status").entity(String::class.java).isEqualTo("DONE")
            .path("designVideos[0].url").entity(String::class.java).isEqualTo("/api/design-videos/$id")
            .path("designVideos[0].posterUrl").entity(String::class.java).isEqualTo("/api/design-videos/$id/poster")
            .path("designVideos[0].startImageId").entity(String::class.java).isEqualTo(start)

        // Served as a video a <video> plays, not as a download.
        val served = api.download(id.toLong())
        assertThat(served.headers.contentType.toString()).isEqualTo("video/webm")
        assertThat(served.body!!.inputStream.use { it.readBytes() }).isEqualTo(WEBM)

        // Counted as one request answered, as a picture is.
        assertThat(usage.findAll().sumOf { it.requests }).isEqualTo(1)
        assertThat(audits.findAll().map { it.message }).contains(
            "Video queued in Design with Wan",
            "Video a-cat-walking-in-the-rain.webm made in Design with Wan",
        )
    }

    @Test
    fun `a setting the model does not take is refused, never sent and dropped`() {
        val modelId = model("Wan", sdcpp)
        takesStartImage = false

        graphQlTester.document(
            """mutation { queueDesignVideo(input: { workspaceId: $workspaceId, modelId: $modelId, prompt: "A cat", seconds: 8 }) { id } }""",
        ).execute().errors().satisfy {
            assertThat(it.single().message).isEqualTo("Wan does not take seconds.")
            assertThat(it.single().extensions["code"]).isEqualTo("DesignVideoSettingNotTaken")
        }
        graphQlTester.document(
            """mutation { queueDesignVideo(input: { workspaceId: $workspaceId, modelId: $modelId, prompt: "A cat", startImageId: "${picture()}" }) { id } }""",
        ).execute().errors().satisfy { assertThat(it.single().message).isEqualTo("Wan does not take startImage.") }
        graphQlTester.document(
            """mutation { queueDesignVideo(input: { workspaceId: $workspaceId, modelId: $modelId, prompt: "A cat", width: 9000 }) { id } }""",
        ).execute().errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("DesignVideoRefused") }

        // And a model that makes no video is no video model at all.
        graphQlTester.document(
            """mutation { queueDesignVideo(input: { workspaceId: $workspaceId, modelId: ${model("Drawer", sdcpp, kind = "IMAGE")}, prompt: "A cat" }) { id } }""",
        ).execute().errors().satisfy { assertThat(it.single().message).isEqualTo("Drawer is not a video model.") }

        assertThat(videos.findAll()).isEmpty()
        assertThat(sdAsked.filter { it.first == "/sdcpp/v1/vid_gen" }).isEmpty()
    }

    @Test
    fun `a video the server fails says why in its words`() {
        failing = true
        val id = queued(model("Wan", sdcpp))

        await().atMost(Duration.ofSeconds(10)).until { videos.findByIdOrNull(id)?.status == DesignVideoStatus.FAILED }
        assertThat(videos.findByIdOrNull(id)?.reason).isEqualTo("generate_video returned no results")
        assertThat(videos.findByIdOrNull(id)?.location).isNull()
    }

    @Test
    fun `a video stopped while it is made is cancelled at its server and nothing is kept`() {
        holding = true
        val id = queued(model("Wan", sdcpp))
        await().atMost(Duration.ofSeconds(10)).until { videos.findByIdOrNull(id)?.progress == 50 }

        graphQlTester.document("""mutation { stopDesignVideo(workspaceId: $workspaceId, id: $id) { status } }""")
            .execute().path("stopDesignVideo.status").entity(String::class.java).isEqualTo("STOPPED")

        await().atMost(Duration.ofSeconds(10)).until { sdAsked.any { it.first == "/sdcpp/v1/jobs/job_V1/cancel" } }
        holding = false
        Thread.sleep(1500)
        assertThat(videos.findByIdOrNull(id)?.status).isEqualTo(DesignVideoStatus.STOPPED)
        assertThat(videos.findByIdOrNull(id)?.location).isNull()
    }

    @Test
    fun `a video being made when the server stopped is asked about again, not asked for again`() {
        val modelId = model("Wan", sdcpp)
        val id = requireNotNull(
            videos.save(
                DesignVideo(
                    workspaceId = workspaceId, modelId = modelId, modelName = "Wan", prompt = "A cat", name = "A cat",
                    requestedBy = "alice", status = DesignVideoStatus.GENERATING, remoteId = "sdcpp:job_V1",
                    startedAt = java.time.OffsetDateTime.now(),
                ),
            ).id,
        )

        // What a starting server does with what it finds.
        queue.start()

        await().atMost(Duration.ofSeconds(15)).until { videos.findByIdOrNull(id)?.status == DesignVideoStatus.DONE }
        assertThat(sdAsked.filter { it.first == "/sdcpp/v1/vid_gen" }).isEmpty()
    }

    @Test
    fun `OpenAI's videos are asked through the SDK and collected with their thumbnail`() {
        val modelId = model("Sora", openai, modelId = "sora-2")

        graphQlTester.document(
            """query { designVideoSettings(workspaceId: $workspaceId, modelId: $modelId) { settings { name kind choices } } }""",
        ).execute()
            .path("designVideoSettings.settings[*].name").entityList(String::class.java).containsExactly("seconds", "size", "startImage")
            .path("designVideoSettings.settings[0].choices").entityList(String::class.java).containsExactly("4", "8", "12")

        val id = graphQlTester.document(
            """mutation { queueDesignVideo(input: {
                 workspaceId: $workspaceId, modelId: $modelId, prompt: "A paper boat", seconds: 8, size: "1280x720"
               }) { id } }""",
        ).execute().path("queueDesignVideo.id").entity(String::class.java).get().toLong()

        await().atMost(Duration.ofSeconds(15)).until { videos.findByIdOrNull(id)?.status == DesignVideoStatus.DONE }

        val created = openAiAsked.first { it.first.startsWith("POST /videos") }.second
        assertThat(created).contains("name=\"prompt\"", "A paper boat", "name=\"model\"", "sora-2", "name=\"seconds\"", "8", "name=\"size\"", "1280x720")
        assertThat(openAiAsked.map { it.first }).contains("GET /videos/video_A?", "GET /videos/video_A/content?", "GET /videos/video_A/content?variant=thumbnail")

        // OpenAI's progress is the whole job's.
        graphQlTester.document("""query { designVideo(workspaceId: $workspaceId, id: $id) { progressSamplingOnly } }""")
            .execute()
            .path("designVideo.progressSamplingOnly").entity(Boolean::class.java).isEqualTo(false)

        val kept = requireNotNull(videos.findByIdOrNull(id))
        assertThat(kept.contentType).isEqualTo("video/mp4")
        assertThat(kept.filename).isEqualTo("a-paper-boat.mp4")
        assertThat(kept.durationSeconds).isEqualTo(8.0)
        assertThat(store.open(requireNotNull(kept.location)).use { it.readBytes() }).isEqualTo(MP4)
        assertThat(kept.posterContentType).isEqualTo("image/webp")
        assertThat(store.open(requireNotNull(kept.posterLocation)).use { it.readBytes() }).isEqualTo(POSTER)
    }

    @Test
    fun `a video too large for the installation is not kept`() {
        // A megabyte is the least the setting allows and the stub answers less, so the client is asked with a smaller limit.
        val modelId = model("Wan", sdcpp)
        val started = client.start(modelId, io.mszymanski.orknux.connector.model.VideoAsk("A cat"))
        assertThat(started).isInstanceOf(io.mszymanski.orknux.connector.model.VideoStart.Started::class.java)
        polls.set(5)
        val state = client.poll(modelId, (started as io.mszymanski.orknux.connector.model.VideoStart.Started).remoteId, maxBytes = 4)
        assertThat(state).isInstanceOf(io.mszymanski.orknux.connector.model.VideoState.Failed::class.java)
        assertThat((state as io.mszymanski.orknux.connector.model.VideoState.Failed).reason).contains("larger than this installation keeps")
    }

    private fun queued(modelId: Long): Long = graphQlTester.document(
        """mutation { queueDesignVideo(input: { workspaceId: $workspaceId, modelId: $modelId, prompt: "A cat" }) { id } }""",
    ).execute().path("queueDesignVideo.id").entity(String::class.java).get().toLong()

    /** A picture in the workspace's gallery, to start a video from. */
    private fun picture(): String {
        val bytes = Base64.getDecoder().decode(PIXEL)
        val saved = images.save(
            DesignImage(
                workspaceId = workspaceId, modelId = null, modelName = "Imported", prompt = "A start", size = null, quality = null,
                style = null, filename = "start.png", contentType = "image/png", sizeBytes = bytes.size.toLong(),
                location = store.put(workspaceId, "start.png", bytes), drawnBy = "alice", name = "Start",
            ),
        )
        return "DESIGN-${saved.id}"
    }

    private fun model(name: String, server: HttpServer, kind: String = "VIDEO", type: String = "OPENAI", modelId: String = "stub"): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "$name provider", type: $type,
                 endpoint: "http://${server.address.hostString}:${server.address.port}", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "$name", modelId: "$modelId", kind: $kind }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }

    private fun videoJson(status: String, progress: Int) =
        """{"id":"video_A","object":"video","created_at":1,"completed_at":null,"expires_at":null,"error":null,
            "model":"sora-2","progress":$progress,"prompt":"A paper boat","remixed_from_video_id":null,
            "seconds":"8","size":"1280x720","status":"$status"}"""

    private fun json(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private companion object {
        /** Not a playable file - nothing here plays it - but bytes that must come back exactly. */
        val WEBM = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 1, 2, 3, 4, 5, 6)
        val MP4 = byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, 9, 9)
        val POSTER = "RIFF0000WEBPVP8 ".toByteArray(StandardCharsets.US_ASCII)
        const val PIXEL =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    }
}
