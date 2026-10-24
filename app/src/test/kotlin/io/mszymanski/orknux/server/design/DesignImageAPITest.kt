package io.mszymanski.orknux.server.design

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.connector.model.ModelUsageRepository
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.time.Duration
import org.awaitility.Awaitility.await
import org.springframework.data.repository.findByIdOrNull

/**
 * Drawing on Design's Images page: straight to the image model chosen, kept
 * with what it was drawn with, and listed beside everything else that was drawn.
 */
@SpringBootTest(
    properties = [
        "orknux.attachments.location=target/test-design-images",
        // Hard edges unless a test asks otherwise, so an edit's pixels are exactly the original's or the model's.
        "orknux.tuning.images.feather.pixels=0",
    ],
)
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class DesignImageAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val images: DesignImageRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val usage: ModelUsageRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val store: AttachmentStore,
    @Autowired val api: DesignImageAPI,
    @Autowired val jobs: DesignImageJobRepository,
    @Autowired val queue: DesignDrawQueue,
    @Autowired val savedArtifacts: io.mszymanski.orknux.server.workflow.SavedArtifacts,
    @Autowired val savedRows: io.mszymanski.orknux.server.workflow.SavedArtifactRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer
    private val drawn = CopyOnWriteArrayList<String>()
    private val edited = CopyOnWriteArrayList<String>()
    private val authorised = CopyOnWriteArrayList<String>()
    private val upscaled = CopyOnWriteArrayList<String>()
    private val harmonized = CopyOnWriteArrayList<String>()
    private val interrupted = CopyOnWriteArrayList<String>()

    /** Set to hold img2img open until the WebUI is interrupted, as a long redraw would be. */
    @Volatile
    private var hold: java.util.concurrent.CountDownLatch? = null

    /** True for a model that refuses, the way these providers do: a 400 with the reason. */
    @Volatile
    private var refusing = false

    @BeforeEach
    fun reset() {
        jobs.deleteAll()
        images.deleteAll()
        savedRows.deleteAll()
        refusing = false
        usage.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        drawn.clear()
        edited.clear()
        upscaled.clear()
        harmonized.clear()
        interrupted.clear()
        hold = null
        authorised.clear()
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/images/generations") { exchange ->
            authorised += exchange.requestHeaders.getFirst("Authorization").orEmpty()
            drawn += exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val bytes = (
                if (refusing) """{"error":{"message":"That is something it would not draw"}}"""
                else """{"data":[{"b64_json":"$PIXEL"}]}"""
                ).toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(if (refusing) 400 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        // The edit endpoint: a red picture of its own, whatever it was handed - as a model that redraws everything would.
        server.createContext("/images/edits") { exchange ->
            edited += exchange.requestBody.readBytes().toString(StandardCharsets.ISO_8859_1)
            val bytes = """{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(png(2, 2) { _, _ -> RED })}"}]}"""
                .toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        // A Stable Diffusion WebUI: its algorithms, and a picture made larger - four pixels for every one.
        server.createContext("/sdapi/v1/upscalers") { exchange ->
            authorised += exchange.requestHeaders.getFirst("Authorization").orEmpty()
            val bytes = """[{"name":"None"},{"name":"Lanczos"},{"name":"R-ESRGAN 4x+"},{"name":"ScuNET GAN"},{"name":"1x_JPEGDestroyer"}]"""
                .toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.createContext("/sdapi/v1/extra-single-image") { exchange ->
            authorised += exchange.requestHeaders.getFirst("Authorization").orEmpty()
            upscaled += exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val bytes = """{"image":"${Base64.getEncoder().encodeToString(png(4, 4) { _, _ -> RED })}","html_info":""}"""
                .toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        // Its checkpoints, the one it has loaded, and img2img: a blue picture the size it was asked for.
        server.createContext("/sdapi/v1/sd-models") { exchange ->
            val bytes = """[{"title":"alpha.safetensors [aaaa]","model_name":"alpha"},{"title":"beta.safetensors [bbbb]","model_name":"beta"}]"""
                .toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.createContext("/sdapi/v1/options") { exchange ->
            val bytes = """{"sd_model_checkpoint":"beta.safetensors [bbbb]"}""".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.createContext("/sdapi/v1/img2img") { exchange ->
            harmonized += exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            hold?.await(10, java.util.concurrent.TimeUnit.SECONDS)
            val bytes = """{"images":["${Base64.getEncoder().encodeToString(png(2, 2) { _, _ -> BLUE })}"],"parameters":{},"info":""}"""
                .toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        // Stops what the WebUI is drawing; the held img2img then answers, as a WebUI does with what it had.
        server.createContext("/sdapi/v1/interrupt") { exchange ->
            interrupted += exchange.requestMethod
            hold?.countDown()
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write("{}".toByteArray()) }
            exchange.close()
        }
        // More than one request at a time: an interrupt arrives while img2img is held open.
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `a picture is queued, drawn in turn with the model chosen, and kept with what it was drawn with`() {
        val modelId = model("Drawer", "IMAGE")

        val jobId = graphQlTester.document(
            """mutation { queueDesignImage(input: {
                 workspaceId: $workspaceId, modelId: $modelId, prompt: "A red square",
                 size: "1024x1024", quality: "hd"
               }) { id status model requestedBy } }""",
        ).execute()
            .path("queueDesignImage.model").entity(String::class.java).isEqualTo("Drawer")
            .path("queueDesignImage.requestedBy").entity(String::class.java).isEqualTo("alice")
            .path("queueDesignImage.id").entity(String::class.java).get()

        // Answered at once and drawn behind it; the request comes back with its picture.
        val id = drawnPicture(jobId)
        graphQlTester.document(
            """query { designImageJob(workspaceId: $workspaceId, id: $jobId) {
                 status image { id kind drawnWith { modelId model size quality style drawnBy } } } }""",
        ).execute()
            .path("designImageJob.status").entity(String::class.java).isEqualTo("DRAWN")
            .path("designImageJob.image.kind").entity(String::class.java).isEqualTo("DESIGN")
            .path("designImageJob.image.drawnWith.modelId").entity(Long::class.java).isEqualTo(modelId)
            .path("designImageJob.image.drawnWith.size").entity(String::class.java).isEqualTo("1024x1024")
            .path("designImageJob.image.drawnWith.quality").entity(String::class.java).isEqualTo("hd")
            .path("designImageJob.image.drawnWith.style").valueIsNull()
            .path("designImageJob.image.drawnWith.drawnBy").entity(String::class.java).isEqualTo("alice")

        // What was asked of the model is what the page said.
        assertThat(drawn.single()).contains("A red square", "1024x1024", "hd")
        val kept = images.findAll().single()
        assertThat(store.open(kept.location).use { it.readBytes() }).isEqualTo(Base64.getDecoder().decode(PIXEL))
        assertThat(api.download(requireNotNull(kept.id)).statusCode.value()).isEqualTo(200)

        // On Design's gallery and on Artifacts alike.
        fun listed(drawnOnly: Boolean) = graphQlTester.document(
            """query { workspaceArtifacts(workspaceId: $workspaceId, drawn: $drawnOnly) { content { id } } }""",
        ).execute().path("workspaceArtifacts.content[*].id").entityList(String::class.java).get()
        assertThat(listed(true)).containsExactly(id)
        assertThat(listed(false)).containsExactly(id)

        // Called after its prompt's first words, and renamed, with its file.
        graphQlTester.document("""query { workspaceArtifact(workspaceId: $workspaceId, id: "$id") { name } }""")
            .execute().path("workspaceArtifact.name").entity(String::class.java).isEqualTo("A red square")
        graphQlTester.document("""mutation { renameDesignImage(workspaceId: $workspaceId, id: "$id", name: "  Brand mark ") { name filename } }""")
            .execute()
            .path("renameDesignImage.name").entity(String::class.java).isEqualTo("Brand mark")
            .path("renameDesignImage.filename").entity(String::class.java).isEqualTo("brand-mark.png")
        graphQlTester.document("""mutation { renameDesignImage(workspaceId: $workspaceId, id: "$id", name: " ") { name } }""")
            .execute().errors().satisfy { assertThat(it.single().message).isEqualTo("A picture needs a name.") }
    }

    @Test
    fun `what the model refuses is kept on the queue as failed, with its reason, until taken off`() {
        refusing = true
        val modelId = model("Drawer", "IMAGE")
        val jobId = graphQlTester.document(
            """mutation { queueDesignImage(input: { workspaceId: $workspaceId, modelId: $modelId, prompt: "A red square" }) { id } }""",
        ).execute().path("queueDesignImage.id").entity(String::class.java).get()

        await().atMost(Duration.ofSeconds(10)).until { jobs.findByIdOrNull(jobId.toLong())?.status == DesignImageJobStatus.FAILED }
        graphQlTester.document("""query { designImageQueue(workspaceId: $workspaceId) { id status reason } }""")
            .execute()
            .path("designImageQueue[0].status").entity(String::class.java).isEqualTo("FAILED")
            .path("designImageQueue[0].reason").entity(String::class.java).satisfies { assertThat(it).contains("would not draw") }

        graphQlTester.document("""mutation { dismissDesignImageJob(id: $jobId) }""")
            .execute().path("dismissDesignImageJob").entity(Boolean::class.java).isEqualTo(true)
        graphQlTester.document("""query { designImageQueue(workspaceId: $workspaceId) { id } }""")
            .execute().path("designImageQueue").entityList(Any::class.java).hasSize(0)
        assertThat(images.count()).isZero()
    }

    @Test
    fun `a picture named before it is drawn keeps that name, and its file is named after it`() {
        val modelId = model("Drawer", "IMAGE")
        val jobId = graphQlTester.document(
            """mutation { queueDesignImage(input: {
                 workspaceId: $workspaceId, modelId: $modelId, prompt: "A red square", name: "Brand mark"
               }) { id name } }""",
        ).execute()
            .path("queueDesignImage.name").entity(String::class.java).isEqualTo("Brand mark")
            .path("queueDesignImage.id").entity(String::class.java).get()

        val id = drawnPicture(jobId)
        graphQlTester.document("""query { workspaceArtifact(workspaceId: $workspaceId, id: "$id") { name filename } }""")
            .execute()
            .path("workspaceArtifact.name").entity(String::class.java).isEqualTo("Brand mark")
            .path("workspaceArtifact.filename").entity(String::class.java).isEqualTo("brand-mark.png")
    }

    @Test
    fun `an edit changes only the selection, through images edits, into a new picture`() {
        val modelId = model("Editor", "IMAGE")
        // A white two-by-two picture to change.
        val white = png(2, 2) { _, _ -> WHITE }
        val source = images.save(
            DesignImage(
                workspaceId = workspaceId, modelId = modelId, modelName = "Editor", prompt = "A white square",
                size = null, quality = null, style = null, filename = "white.png", contentType = "image/png",
                sizeBytes = white.size.toLong(), location = store.put(workspaceId, "white.png", white), drawnBy = "alice",
                name = "Blank",
            ),
        )
        // Transparent - change here - on the left column only.
        val mask = png(2, 2) { x, _ -> if (x == 0) 0x00000000 else 0xFF000000.toInt() }

        val jobId = graphQlTester.document(
            """mutation { queueDesignImageEdit(input: {
                 workspaceId: $workspaceId, imageId: "DESIGN-${source.id}", modelId: $modelId,
                 prompt: "Make it red", mask: "data:image/png;base64,${Base64.getEncoder().encodeToString(mask)}"
               }) { id sourceId name } }""",
        ).execute()
            .path("queueDesignImageEdit.sourceId").entity(String::class.java).isEqualTo("DESIGN-${source.id}")
            .path("queueDesignImageEdit.id").entity(String::class.java).get()

        val id = drawnPicture(jobId)
        // Through the standard edit interface, with the picture and the mask as named PNG parts.
        assertThat(edited.single()).contains("Make it red", "filename=\"image.png\"", "filename=\"mask.png\"")
        assertThat(drawn).isEmpty()

        // A version of the picture changed, on no list: red where selected, the original's white elsewhere.
        val made = requireNotNull(images.findByIdOrNull(id.removePrefix("DESIGN-").toLong()))
        assertThat(made.id).isNotEqualTo(source.id)
        assertThat(made.versionOf).isEqualTo("DESIGN-${source.id}")
        val result = javax.imageio.ImageIO.read(store.open(made.location))
        assertThat(result.getRGB(0, 0)).isEqualTo(RED)
        assertThat(result.getRGB(0, 1)).isEqualTo(RED)
        assertThat(result.getRGB(1, 0)).isEqualTo(WHITE)
        assertThat(result.getRGB(1, 1)).isEqualTo(WHITE)
        graphQlTester.document("""query { workspaceArtifacts(workspaceId: $workspaceId, drawn: true) { content { id } } }""")
            .execute().path("workspaceArtifacts.content[*].id").entityList(String::class.java).containsExactly("DESIGN-${source.id}")
        graphQlTester.document("""query { designImageQueue(workspaceId: $workspaceId) { id } }""")
            .execute().path("designImageQueue").entityList(Any::class.java).hasSize(0)

        val urlBefore = graphQlTester.document("""query { workspaceArtifact(workspaceId: $workspaceId, id: "DESIGN-${source.id}") { url } }""")
            .execute().path("workspaceArtifact.url").entity(String::class.java).get()
        // Saved: the picture itself takes the change, keeps its name, and the version is gone.
        graphQlTester.document("""mutation { saveDesignImageVersion(workspaceId: $workspaceId, id: "$id") { id name versionOf url } }""")
            .execute()
            // A new address for the new bytes, or a browser shows the picture from its cache as it was.
            .path("saveDesignImageVersion.url").entity(String::class.java).satisfies { assertThat(it).isNotEqualTo(urlBefore) }
            .path("saveDesignImageVersion.id").entity(String::class.java).isEqualTo("DESIGN-${source.id}")
            .path("saveDesignImageVersion.name").entity(String::class.java).isEqualTo("Blank")
            .path("saveDesignImageVersion.versionOf").valueIsNull()
        val saved = requireNotNull(images.findByIdOrNull(requireNotNull(source.id)))
        assertThat(javax.imageio.ImageIO.read(store.open(saved.location)).getRGB(0, 0)).isEqualTo(RED)
        assertThat(images.findAll().map { it.id }).containsExactly(source.id)
    }

    @Test
    fun `discarding throws every unsaved version of a picture away, and the picture is as it was`() {
        val modelId = model("Editor", "IMAGE")
        val white = png(2, 2) { _, _ -> WHITE }
        val source = images.save(
            DesignImage(
                workspaceId = workspaceId, modelId = modelId, modelName = "Editor", prompt = "A white square",
                size = null, quality = null, style = null, filename = "white.png", contentType = "image/png",
                sizeBytes = white.size.toLong(), location = store.put(workspaceId, "white.png", white), drawnBy = "alice",
                name = "Blank",
            ),
        )
        val mask = Base64.getEncoder().encodeToString(png(2, 2) { x, _ -> if (x == 0) 0x00000000 else 0xFF000000.toInt() })
        fun edit(of: String): String = drawnPicture(
            graphQlTester.document(
                """mutation { queueDesignImageEdit(input: {
                     workspaceId: $workspaceId, imageId: "$of", modelId: $modelId, prompt: "Make it red", mask: "$mask"
                   }) { id } }""",
            ).execute().path("queueDesignImageEdit.id").entity(String::class.java).get(),
        )
        // Two rounds, the second over the first: both versions of the picture itself.
        val first = edit("DESIGN-${source.id}")
        val second = edit(first)
        assertThat(images.findByIdOrNull(second.removePrefix("DESIGN-").toLong())?.versionOf).isEqualTo("DESIGN-${source.id}")
        // The picture's draft is its newest version, wherever it is asked for.
        graphQlTester.document("""query { workspaceArtifact(workspaceId: $workspaceId, id: "DESIGN-${source.id}") { draft } }""")
            .execute().path("workspaceArtifact.draft").entity(String::class.java).isEqualTo(second)
        graphQlTester.document("""query { workspaceArtifacts(workspaceId: $workspaceId, drawn: true) { content { id draft } } }""")
            .execute().path("workspaceArtifacts.content[0].draft").entity(String::class.java).isEqualTo(second)
        // And where the draft's picture is, so a card marked Draft shows the changes rather than the picture before them.
        val secondUrl = graphQlTester.document("""query { workspaceArtifact(workspaceId: $workspaceId, id: "$second") { url } }""")
            .execute().path("workspaceArtifact.url").entity(String::class.java).get()
        assertThat(secondUrl).startsWith("/api/design-images/${second.removePrefix("DESIGN-")}?")
        graphQlTester.document("""query { workspaceArtifacts(workspaceId: $workspaceId, drawn: true) { content { url draftUrl } } }""")
            .execute()
            .path("workspaceArtifacts.content[0].draftUrl").entity(String::class.java).isEqualTo(secondUrl)
            .path("workspaceArtifacts.content[0].url").entity(String::class.java).satisfies { assertThat(it).isNotEqualTo(secondUrl) }
        graphQlTester.document("""query { workspaceArtifact(workspaceId: $workspaceId, id: "DESIGN-${source.id}") { draftUrl } }""")
            .execute().path("workspaceArtifact.draftUrl").entity(String::class.java).isEqualTo(secondUrl)

        // Saved as a copy: a picture of its own, the original as it was, and no version left.
        val copy = graphQlTester.document("""mutation { saveDesignImageVersion(workspaceId: $workspaceId, id: "$second", asCopy: true) { id name versionOf } }""")
            .execute()
            .path("saveDesignImageVersion.name").entity(String::class.java).isEqualTo("Blank (copy)")
            .path("saveDesignImageVersion.versionOf").valueIsNull()
            .path("saveDesignImageVersion.id").entity(String::class.java).get()
        assertThat(copy).isNotEqualTo("DESIGN-${source.id}")
        assertThat(images.findAll().map { "DESIGN-${it.id}" }).containsExactlyInAnyOrder("DESIGN-${source.id}", copy)
        assertThat(javax.imageio.ImageIO.read(store.open(source.location)).getRGB(0, 0)).isEqualTo(WHITE)
        images.deleteById(copy.removePrefix("DESIGN-").toLong())
        edit("DESIGN-${source.id}")

        graphQlTester.document("""mutation { discardDesignImageVersions(workspaceId: $workspaceId, id: "DESIGN-${source.id}") }""")
            .execute().path("discardDesignImageVersions").entity(Boolean::class.java).isEqualTo(true)
        assertThat(images.findAll().map { it.id }).containsExactly(source.id)
        assertThat(javax.imageio.ImageIO.read(store.open(source.location)).getRGB(0, 0)).isEqualTo(WHITE)
    }

    @Test
    fun `a change the editor made itself is kept as a drawn version, in the history, and saved like a model's`() {
        val source = whiteSource()
        // Rotated in the browser: a picture of another shape, two wide becoming one wide and three tall.
        val turned = Base64.getEncoder().encodeToString(png(1, 3) { _, _ -> RED })
        fun keep(of: String, change: String) = graphQlTester.document(
            """mutation { keepDesignImageChange(input: {
                 workspaceId: $workspaceId, imageId: "$of", image: "data:image/png;base64,$turned", change: "$change"
               }) { id status prompt model sourceId image { id versionOf name } } }""",
        ).execute()
        val first = keep("DESIGN-${source.id}", "Rotated right")
            .path("keepDesignImageChange.status").entity(String::class.java).isEqualTo("DRAWN")
            .path("keepDesignImageChange.prompt").entity(String::class.java).isEqualTo("Rotated right")
            .path("keepDesignImageChange.model").entity(String::class.java).isEqualTo("Image editor")
            .path("keepDesignImageChange.image.versionOf").entity(String::class.java).isEqualTo("DESIGN-${source.id}")
            .path("keepDesignImageChange.image.name").entity(String::class.java).isEqualTo("White")
            .path("keepDesignImageChange.image.id").entity(String::class.java).get()
        // A change over that version is a version of the picture itself, not of the round before.
        val second = keep(first, "Mirrored")
            .path("keepDesignImageChange.image.versionOf").entity(String::class.java).isEqualTo("DESIGN-${source.id}")
            .path("keepDesignImageChange.image.id").entity(String::class.java).get()
        graphQlTester.document("""query { designImageHistory(workspaceId: $workspaceId, imageId: "DESIGN-${source.id}") { prompt status } }""")
            .execute().path("designImageHistory[*].prompt").entityList(String::class.java).containsExactly("Mirrored", "Rotated right")
        // Nothing waits: the queue the gallery shows has none of it.
        graphQlTester.document("""query { designImageQueue(workspaceId: $workspaceId) { id } }""")
            .execute().path("designImageQueue").entityList(Any::class.java).hasSize(0)

        graphQlTester.document("""mutation { saveDesignImageVersion(workspaceId: $workspaceId, id: "$second") { id } }""").execute()
        val saved = javax.imageio.ImageIO.read(store.open(requireNotNull(images.findByIdOrNull(requireNotNull(source.id))).location))
        assertThat(saved.width to saved.height).isEqualTo(1 to 3)
        assertThat(saved.getRGB(0, 0)).isEqualTo(RED)
        assertThat(images.findAll().map { it.id }).containsExactly(source.id)
    }

    @Test
    fun `the image editor's tools start where Admin Settings says`() {
        fun settings() = graphQlTester.document(
            """query { designImageEditorSettings(workspaceId: $workspaceId) {
                 brush brushMost blur blurMost blurFalloff blurFalloffMost feather featherMost brushOpacity brushHardness markerOpacity } }""",
        ).execute()
        settings()
            .path("designImageEditorSettings.brush").entity(Int::class.java).isEqualTo(12)
            // Each tip's start, which the brush's bar opens at: the marker see-through, the soft tip half hard.
            .path("designImageEditorSettings.brushOpacity").entity(Int::class.java).isEqualTo(100)
            .path("designImageEditorSettings.brushHardness").entity(Int::class.java).isEqualTo(50)
            .path("designImageEditorSettings.markerOpacity").entity(Int::class.java).isEqualTo(50)
            .path("designImageEditorSettings.brushMost").entity(Int::class.java).isEqualTo(500)
            .path("designImageEditorSettings.blur").entity(Int::class.java).isEqualTo(12)
            .path("designImageEditorSettings.blurMost").entity(Int::class.java).isEqualTo(254)
            .path("designImageEditorSettings.blurFalloff").entity(Int::class.java).isEqualTo(0)
            .path("designImageEditorSettings.blurFalloffMost").entity(Int::class.java).isEqualTo(500)
            .path("designImageEditorSettings.featherMost").entity(Int::class.java).isEqualTo(256)
        try {
            graphQlTester.document("""mutation { setTunable(key: "images.brush.pixels", value: 40) { value } }""").execute()
            graphQlTester.document("""mutation { setTunable(key: "images.blur.falloff.pixels", value: 16) { value } }""").execute()
            settings()
                .path("designImageEditorSettings.brush").entity(Int::class.java).isEqualTo(40)
                .path("designImageEditorSettings.blurFalloff").entity(Int::class.java).isEqualTo(16)
        } finally {
            graphQlTester.document("""mutation { setTunable(key: "images.brush.pixels", value: null) { value } }""").execute()
            graphQlTester.document("""mutation { setTunable(key: "images.blur.falloff.pixels", value: null) { value } }""").execute()
        }
    }

    @Test
    fun `a feathered edit blends the change into the picture across the selection's edge`() {
        val modelId = model("Editor", "IMAGE")
        val white = png(20, 2) { _, _ -> WHITE }
        val source = images.save(
            DesignImage(
                workspaceId = workspaceId, modelId = modelId, modelName = "Editor", prompt = "A white strip",
                size = null, quality = null, style = null, filename = "strip.png", contentType = "image/png",
                sizeBytes = white.size.toLong(), location = store.put(workspaceId, "strip.png", white), drawnBy = "alice",
                name = "Strip",
            ),
        )
        // The left half selected; the model answers red everywhere.
        val mask = Base64.getEncoder().encodeToString(png(20, 2) { x, _ -> if (x < 10) 0x00000000 else 0xFF000000.toInt() })
        fun edit(feather: String): java.awt.image.BufferedImage {
            val asked = graphQlTester.document(
                """mutation { queueDesignImageEdit(input: {
                     workspaceId: $workspaceId, imageId: "DESIGN-${source.id}", modelId: $modelId, prompt: "Make it red", mask: "$mask"$feather
                   }) { id feather } }""",
            ).execute().path("queueDesignImageEdit.id").entity(String::class.java).get()
            val id = drawnPicture(asked)
            return javax.imageio.ImageIO.read(store.open(requireNotNull(images.findByIdOrNull(id.removePrefix("DESIGN-").toLong())).location))
        }
        fun blended(rgb: Int) = rgb != RED && rgb != WHITE

        // A hard edge, as before: red to the tenth column, white from the eleventh.
        val hard = edit(", feather: 0")
        assertThat(listOf(hard.getRGB(9, 0), hard.getRGB(10, 0))).containsExactly(RED, WHITE)

        // Feathered by four: the two meet in a blend across the edge, and far from it each is itself.
        val soft = edit(", feather: 4")
        assertThat(soft.getRGB(0, 0)).isEqualTo(RED)
        assertThat(soft.getRGB(19, 0)).isEqualTo(WHITE)
        assertThat(blended(soft.getRGB(9, 0)) && blended(soft.getRGB(10, 0))).isTrue()
        assertThat(jobs.findAll().map { it.feather }).contains(4)

        // None asked: the installation's.
        try {
            graphQlTester.document("""mutation { setTunable(key: "images.feather.pixels", value: 4) { value } }""").execute()
            assertThat(blended(edit("").getRGB(10, 0))).isTrue()
        } finally {
            graphQlTester.document("""mutation { setTunable(key: "images.feather.pixels", value: null) { value } }""").execute()
        }

        graphQlTester.document(
            """mutation { queueDesignImageEdit(input: {
                 workspaceId: $workspaceId, imageId: "DESIGN-${source.id}", modelId: $modelId, prompt: "Make it red", mask: "$mask", feather: 999
               }) { id } }""",
        ).execute().errors().satisfy { assertThat(it.single().message).contains("A feather is 0 to 256 pixels") }
    }

    @Test
    fun `a change the editor made is refused when it is not a picture, or the picture is gone`() {
        val source = whiteSource()
        val text = Base64.getEncoder().encodeToString("not a picture".toByteArray())
        graphQlTester.document(
            """mutation { keepDesignImageChange(input: {
                 workspaceId: $workspaceId, imageId: "DESIGN-${source.id}", image: "$text", change: "Painted"
               }) { id } }""",
        ).execute().errors().satisfy { assertThat(it.single().message).contains("not a picture") }
        val pixel = Base64.getEncoder().encodeToString(png(1, 1) { _, _ -> RED })
        graphQlTester.document(
            """mutation { keepDesignImageChange(input: {
                 workspaceId: $workspaceId, imageId: "DESIGN-999999", image: "$pixel", change: "Painted"
               }) { id } }""",
        ).execute().errors().satisfy { assertThat(it.single().message).contains("not here any more") }
        assertThat(images.findAll().map { it.id }).containsExactly(source.id)
    }

    private fun png(width: Int, height: Int, colour: (Int, Int) -> Int): ByteArray {
        val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, colour(x, y))
        return java.io.ByteArrayOutputStream().use { out ->
            javax.imageio.ImageIO.write(image, "png", out)
            out.toByteArray()
        }
    }

    @Test
    fun `a server on somebody's own machine is called with no key at all`() {
        val modelId = model("Local", "IMAGE", keyed = false)
        val jobId = graphQlTester.document(
            """mutation { queueDesignImage(input: { workspaceId: $workspaceId, modelId: $modelId, prompt: "A red square" }) { id } }""",
        ).execute().path("queueDesignImage.id").entity(String::class.java).get()

        drawnPicture(jobId)
        // A stand-in bearer, which a server that checks no key ignores.
        assertThat(authorised.single()).isEqualTo("Bearer no-key")
    }

    @Test
    fun `stable-diffusion cpp's server is saved as its own type, and sent the mask it reads unless the provider says otherwise`() {
        // What stable-diffusion.cpp's server answers, and nothing else does.
        server.createContext("/sdcpp/v1/capabilities") { exchange ->
            val bytes = """{"current_mode":"img_gen"}""".toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        val modelId = model("Editor", "IMAGE")
        // Added as a generic OpenAI provider, saved as the server it is.
        val providerId = requireNotNull(models.findById(modelId).orElse(null)).providerId
        assertThat(providers.findById(providerId).get().type)
            .isEqualTo(io.mszymanski.orknux.connector.model.ProviderType.STABLE_DIFFUSION_CPP)
        val white = png(2, 2) { _, _ -> WHITE }
        val source = images.save(
            DesignImage(
                workspaceId = workspaceId, modelId = modelId, modelName = "Editor", prompt = "A white square",
                size = null, quality = null, style = null, filename = "white.png", contentType = "image/png",
                sizeBytes = white.size.toLong(), location = store.put(workspaceId, "white.png", white), drawnBy = "alice",
            ),
        )
        val mask = Base64.getEncoder().encodeToString(png(2, 2) { x, _ -> if (x == 0) 0x00000000 else 0xFF000000.toInt() })
        fun edit(): String = graphQlTester.document(
            """mutation { queueDesignImageEdit(input: {
                 workspaceId: $workspaceId, imageId: "DESIGN-${source.id}", modelId: $modelId, prompt: "Make it red", mask: "$mask"
               }) { id } }""",
        ).execute().path("queueDesignImageEdit.id").entity(String::class.java).get()

        // Nobody said: its type's mask - one greyscale channel, white for the change.
        drawnPicture(edit())
        assertThat(sentMask(edited.last())).isEqualTo(listOf(0xFFFFFF, 0x000000))

        // Said on the provider: the standard, transparent for the change, whatever the type.
        providers.findById(providerId).get().also {
            it.editMask = io.mszymanski.orknux.connector.model.EditMask.TRANSPARENT
            providers.save(it)
        }
        drawnPicture(edit())
        assertThat(sentMask(edited.last())).isEqualTo(listOf(0x00000000, 0xFF000000.toInt()))
    }

    /** The mask part of a multipart body, read back: its top row's two pixels, alpha and all. */
    private fun sentMask(body: String): List<Int> {
        val part = body.indexOf("filename=\"mask.png\"")
        val start = body.indexOf("PNG", part)
        val bytes = body.substring(start).toByteArray(StandardCharsets.ISO_8859_1)
        val image = requireNotNull(javax.imageio.ImageIO.read(bytes.inputStream()))
        val alpha = image.colorModel.hasAlpha()
        return (0 until 2).map { x -> if (alpha) image.getRGB(x, 0) else image.getRGB(x, 0) and 0xFFFFFF }
    }

    @Test
    fun `saving changes to a picture an agent saved writes over that picture`() {
        val modelId = model("Editor", "IMAGE")
        val white = png(2, 2) { _, _ -> WHITE }
        val filed = savedArtifacts.save(
            workspaceId = workspaceId,
            savedBy = "Support responder",
            name = "white.png",
            description = "A white square",
            content = Base64.getEncoder().encodeToString(white),
            base64 = true,
        )
        val kept = (filed as io.mszymanski.orknux.server.workflow.SavedArtifacts.Saving.Saved).artifact
        val mask = Base64.getEncoder().encodeToString(png(2, 2) { x, _ -> if (x == 0) 0x00000000 else 0xFF000000.toInt() })
        val version = drawnPicture(
            graphQlTester.document(
                """mutation { queueDesignImageEdit(input: {
                     workspaceId: $workspaceId, imageId: "SAVED-${kept.id}", modelId: $modelId, prompt: "Make it red", mask: "$mask"
                   }) { id } }""",
            ).execute().path("queueDesignImageEdit.id").entity(String::class.java).get(),
        )

        graphQlTester.document("""mutation { saveDesignImageVersion(workspaceId: $workspaceId, id: "$version") { id } }""")
            .execute().path("saveDesignImageVersion.id").entity(String::class.java).isEqualTo("SAVED-${kept.id}")
        val after = requireNotNull(savedRows.findByIdOrNull(requireNotNull(kept.id)))
        assertThat(javax.imageio.ImageIO.read(store.open(after.location)).getRGB(0, 0)).isEqualTo(RED)
        assertThat(images.count()).isZero()
    }

    @Test
    fun `a picture's history in the editor is read again from the server, until it is saved or discarded`() {
        val modelId = model("Editor", "IMAGE")
        val source = whiteSource()
        val mask = Base64.getEncoder().encodeToString(png(2, 2) { x, _ -> if (x == 0) 0x00000000 else 0xFF000000.toInt() })
        fun queue(of: String): String = graphQlTester.document(
            """mutation { queueDesignImageEdit(input: {
                 workspaceId: $workspaceId, imageId: "$of", modelId: $modelId, prompt: "Make it red", mask: "$mask"
               }) { id } }""",
        ).execute().path("queueDesignImageEdit.id").entity(String::class.java).get()
        val firstJob = queue("DESIGN-${source.id}")
        val first = drawnPicture(firstJob)
        val secondJob = queue(first)
        val second = drawnPicture(secondJob)
        // One that failed over the newest version is history too; one over another picture is not.
        val failed = jobs.save(
            DesignImageJob(
                workspaceId = workspaceId, modelId = modelId, modelName = "Editor", prompt = "Make it blue",
                size = null, quality = null, style = null, requestedBy = "alice", sourceId = second,
                status = DesignImageJobStatus.FAILED, reason = "no",
            ),
        )
        val elsewhere = whiteSource()
        jobs.save(
            DesignImageJob(
                workspaceId = workspaceId, modelId = modelId, modelName = "Editor", prompt = "Elsewhere",
                size = null, quality = null, style = null, requestedBy = "alice", sourceId = "DESIGN-${elsewhere.id}",
                status = DesignImageJobStatus.FAILED, reason = "no",
            ),
        )
        fun history() = graphQlTester.document(
            """query { designImageHistory(workspaceId: $workspaceId, imageId: "DESIGN-${source.id}") { id status image { id } } }""",
        ).execute()

        // Newest first, each drawn one with its version.
        history()
            .path("designImageHistory[*].id").entityList(String::class.java).containsExactly(failed.id.toString(), secondJob, firstJob)
            .path("designImageHistory[1].image.id").entity(String::class.java).isEqualTo(second)
            .path("designImageHistory[2].image.id").entity(String::class.java).isEqualTo(first)

        // Saved, the history is over: the versions are gone, and what failed on the way with them.
        graphQlTester.document("""mutation { saveDesignImageVersion(workspaceId: $workspaceId, id: "$second") { id } }""").execute()
        history().path("designImageHistory").entityList(Any::class.java).hasSize(0)
        assertThat(jobs.findByIdOrNull(requireNotNull(failed.id))).isNull()
    }

    /** Waits for the request to be drawn, and answers its picture's id. */
    private fun drawnPicture(jobId: String): String {
        await().atMost(Duration.ofSeconds(10)).until { jobs.findByIdOrNull(jobId.toLong())?.status == DesignImageJobStatus.DRAWN }
        return "DESIGN-" + requireNotNull(jobs.findByIdOrNull(jobId.toLong())?.imageId)
    }

    @Test
    fun `a model that does not draw is refused, and nothing is asked of it`() {
        val chat = model("Talker", "CHAT")

        graphQlTester.document(
            """mutation { queueDesignImage(input: { workspaceId: $workspaceId, modelId: $chat, prompt: "A red square" }) { id } }""",
        ).execute().errors().satisfy { assertThat(it).hasSize(1) }

        graphQlTester.document(
            """mutation { queueDesignImage(input: { workspaceId: $workspaceId, modelId: ${model("Drawer", "IMAGE")}, prompt: "  " }) { id } }""",
        ).execute().errors().satisfy {
            assertThat(it.single().message).isEqualTo("Say what the picture should be of.")
            assertThat(it.single().extensions["code"]).isEqualTo("DesignImageRefused")
        }

        assertThat(drawn).isEmpty()
        assertThat(images.count()).isZero()
    }

    @Test
    fun `how long a description may be is the installation's setting`() {
        val drawer = model("Drawer", "IMAGE")
        graphQlTester.document("mutation { setImageMaxPrompt(characters: 100) { imageMaxPrompt } }").execute()
            .path("setImageMaxPrompt.imageMaxPrompt").entity(Int::class.java).isEqualTo(100)
        try {
            graphQlTester.document(
                """mutation { queueDesignImage(input: { workspaceId: $workspaceId, modelId: $drawer, prompt: "${"x".repeat(101)}" }) { id } }""",
            ).execute().errors().satisfy {
                assertThat(it.single().message).isEqualTo("That description is too long to draw from; say it in under 100 characters.")
            }
            assertThat(drawn).isEmpty()

            graphQlTester.document("mutation { setImageMaxPrompt(characters: 99) { imageMaxPrompt } }").execute()
                .errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("ImagePromptOutOfRange") }
        } finally {
            graphQlTester.document("mutation { setImageMaxPrompt(characters: 2000) { imageMaxPrompt } }").execute()
        }
    }

    @Test
    fun `an upscale model offers its server's algorithms and makes a picture larger as a draft`() {
        // Upscaling is the WebUI's alone: an upscale model under any other provider is refused.
        val openAi = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Other", type: OPENAI, endpoint: "http://${server.address.hostString}:${server.address.port}"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        graphQlTester.document("""mutation { createModel(input: { providerId: $openAi, name: "Big", modelId: "x", kind: UPSCALE }) { id } }""")
            .execute().errors().satisfy { assertThat(it.single().message).contains("not a Stable Diffusion WebUI") }

        val upscaler = model("Enlarger", "UPSCALE", type = "STABLE_DIFFUSION_WEBUI", keyed = false, modelId = "Lanczos")
        // The server's own list, the model's own first, and "None" - no upscaling at all - left out.
        graphQlTester.document("""query { designUpscaleAlgorithms(workspaceId: $workspaceId, modelId: $upscaler) { names preferred reason restorers } }""")
            .execute()
            .path("designUpscaleAlgorithms.names").entityList(String::class.java)
            .containsExactly("Lanczos", "R-ESRGAN 4x+", "ScuNET GAN", "1x_JPEGDestroyer")
            // The restoration models among them, what Clean up offers: not an upscaler that only enlarges.
            .path("designUpscaleAlgorithms.restorers").entityList(String::class.java).containsExactly("ScuNET GAN", "1x_JPEGDestroyer")
            .path("designUpscaleAlgorithms.preferred").entity(String::class.java).isEqualTo("Lanczos")
            .path("designUpscaleAlgorithms.reason").valueIsNull()

        val source = images.save(
            DesignImage(
                workspaceId = workspaceId, modelId = null, modelName = "Drawer", prompt = "A white square",
                size = null, quality = null, style = null, filename = "white.png", contentType = "image/png",
                sizeBytes = 1, location = store.put(workspaceId, "white.png", png(2, 2) { _, _ -> WHITE }), drawnBy = "alice",
                name = "White",
            ),
        )
        val version = drawnPicture(
            graphQlTester.document(
                """mutation { queueDesignImageUpscale(input: {
                     workspaceId: $workspaceId, imageId: "DESIGN-${source.id}", modelId: $upscaler, algorithm: "R-ESRGAN 4x+", scale: 2
                   }) { id scale upscaler } }""",
            ).execute()
                .path("queueDesignImageUpscale.upscaler").entity(String::class.java).isEqualTo("R-ESRGAN 4x+")
                .path("queueDesignImageUpscale.id").entity(String::class.java).get(),
        )
        assertThat(upscaled.single()).contains("\"upscaler_1\":\"R-ESRGAN 4x+\"", "\"upscaling_resize\":2.0")
        // A draft of the picture, larger, with its name - not a picture of its own in the gallery.
        val kept = requireNotNull(images.findByIdOrNull(version.removePrefix("DESIGN-").toLong()))
        assertThat(kept.versionOf).isEqualTo("DESIGN-${source.id}")
        assertThat(kept.name).isEqualTo("White")
        assertThat(javax.imageio.ImageIO.read(store.open(kept.location)).width).isEqualTo(4)
    }

    @Test
    fun `cleaning up runs a restoration model at 1x and keeps the draft at the picture's own size`() {
        val upscaler = model("Enlarger", "UPSCALE", type = "STABLE_DIFFUSION_WEBUI", keyed = false, modelId = "Lanczos")
        val source = whiteSource()
        val version = drawnPicture(
            graphQlTester.document(
                """mutation { queueDesignImageUpscale(input: {
                     workspaceId: $workspaceId, imageId: "DESIGN-${source.id}", modelId: $upscaler, algorithm: "ScuNET GAN", scale: 1
                   }) { id } }""",
            ).execute().path("queueDesignImageUpscale.id").entity(String::class.java).get(),
        )
        assertThat(upscaled.single()).contains("\"upscaler_1\":\"ScuNET GAN\"", "\"upscaling_resize\":1.0")
        // The WebUI answered four by four; the draft is the picture's own two by two, restored.
        val kept = requireNotNull(images.findByIdOrNull(version.removePrefix("DESIGN-").toLong()))
        val read = javax.imageio.ImageIO.read(store.open(kept.location))
        assertThat(read.width to read.height).isEqualTo(2 to 2)
        assertThat(read.getRGB(0, 0)).isEqualTo(RED)
        assertThat(kept.versionOf).isEqualTo("DESIGN-${source.id}")
    }

    @Test
    fun `harmonizing through a WebUI redraws the whole picture by img2img at the installation's strength, as a draft`() {
        val webUi = model("Forge", "UPSCALE", type = "STABLE_DIFFUSION_WEBUI", keyed = false, modelId = "Lanczos")
        // A strength, starting at the installation's, and the WebUI's checkpoints - the loaded one first.
        graphQlTester.document(
            """query { designHarmonizeOptions(workspaceId: $workspaceId, modelId: $webUi) { strengthTaken strength checkpoints preferredCheckpoint reason } }""",
        ).execute()
            .path("designHarmonizeOptions.strengthTaken").entity(Boolean::class.java).isEqualTo(true)
            .path("designHarmonizeOptions.strength").entity(Double::class.java).isEqualTo(0.3)
            .path("designHarmonizeOptions.checkpoints").entityList(String::class.java)
            .containsExactly("beta.safetensors [bbbb]", "alpha.safetensors [aaaa]")
            .path("designHarmonizeOptions.preferredCheckpoint").entity(String::class.java).isEqualTo("beta.safetensors [bbbb]")
            .path("designHarmonizeOptions.reason").valueIsNull()

        val white = png(2, 2) { _, _ -> WHITE }
        val source = images.save(
            DesignImage(
                workspaceId = workspaceId, modelId = null, modelName = "Drawer", prompt = "A white square",
                size = null, quality = null, style = null, filename = "white.png", contentType = "image/png",
                sizeBytes = white.size.toLong(), location = store.put(workspaceId, "white.png", white), drawnBy = "alice",
                name = "White",
            ),
        )
        val version = drawnPicture(
            graphQlTester.document(
                """mutation { queueDesignImageHarmonize(input: {
                     workspaceId: $workspaceId, imageId: "DESIGN-${source.id}", modelId: $webUi,
                     prompt: "A white square, evenly lit", checkpoint: "alpha.safetensors [aaaa]"
                   }) { id strength checkpoint } }""",
            ).execute()
                .path("queueDesignImageHarmonize.strength").entity(Double::class.java).isEqualTo(0.3)
                .path("queueDesignImageHarmonize.checkpoint").entity(String::class.java).isEqualTo("alpha.safetensors [aaaa]")
                .path("queueDesignImageHarmonize.id").entity(String::class.java).get(),
        )
        // The whole picture, the prompt, the strength, the checkpoint, and the picture's own size.
        val asked = tools.jackson.databind.json.JsonMapper.builder().build().readTree(harmonized.single())
        assertThat(Base64.getDecoder().decode(asked.path("init_images").path(0).stringValue())).isEqualTo(white)
        assertThat(asked.path("prompt").stringValue()).isEqualTo("A white square, evenly lit")
        assertThat(asked.path("denoising_strength").doubleValue()).isEqualTo(0.3)
        assertThat(asked.path("override_settings").path("sd_model_checkpoint").stringValue()).isEqualTo("alpha.safetensors [aaaa]")
        assertThat(asked.path("width").intValue()).isEqualTo(2)
        assertThat(asked.path("height").intValue()).isEqualTo(2)
        // A draft of the picture, redrawn everywhere - no part of the original kept.
        val kept = requireNotNull(images.findByIdOrNull(version.removePrefix("DESIGN-").toLong()))
        assertThat(kept.versionOf).isEqualTo("DESIGN-${source.id}")
        assertThat(kept.name).isEqualTo("White")
        assertThat(javax.imageio.ImageIO.read(store.open(kept.location)).getRGB(1, 1)).isEqualTo(BLUE)

        // The installation's setting is where it starts; a strength asked for overrides it.
        try {
            graphQlTester.document("mutation { setImageHarmonizeStrength(strength: 0.5) { imageHarmonizeStrength imageHarmonizeStrengthConfigured } }")
                .execute()
                .path("setImageHarmonizeStrength.imageHarmonizeStrength").entity(Double::class.java).isEqualTo(0.5)
                .path("setImageHarmonizeStrength.imageHarmonizeStrengthConfigured").entity(Double::class.java).isEqualTo(0.3)
            graphQlTester.document("""query { designHarmonizeOptions(workspaceId: $workspaceId, modelId: $webUi) { strength } }""")
                .execute().path("designHarmonizeOptions.strength").entity(Double::class.java).isEqualTo(0.5)
            harmonized.clear()
            drawnPicture(
                graphQlTester.document(
                    """mutation { queueDesignImageHarmonize(input: {
                         workspaceId: $workspaceId, imageId: "DESIGN-${source.id}", modelId: $webUi, prompt: "A white square"
                       }) { id } }""",
                ).execute().path("queueDesignImageHarmonize.id").entity(String::class.java).get(),
            )
            assertThat(harmonized.single()).contains("\"denoising_strength\":0.5").doesNotContain("override_settings")
            harmonized.clear()
            drawnPicture(
                graphQlTester.document(
                    """mutation { queueDesignImageHarmonize(input: {
                         workspaceId: $workspaceId, imageId: "DESIGN-${source.id}", modelId: $webUi, prompt: "A white square", strength: 0.2
                       }) { id } }""",
                ).execute().path("queueDesignImageHarmonize.id").entity(String::class.java).get(),
            )
            assertThat(harmonized.single()).contains("\"denoising_strength\":0.2")

            graphQlTester.document("mutation { setImageHarmonizeStrength(strength: 0.95) { imageHarmonizeStrength } }").execute()
                .errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("ImageHarmonizeStrengthOutOfRange") }
            graphQlTester.document(
                """mutation { queueDesignImageHarmonize(input: {
                     workspaceId: $workspaceId, imageId: "DESIGN-${source.id}", modelId: $webUi, prompt: "A white square", strength: 0.95
                   }) { id } }""",
            ).execute().errors().satisfy { assertThat(it.single().message).isEqualTo("A strength is between 0.05 and 0.9.") }
        } finally {
            graphQlTester.document("mutation { setImageHarmonizeStrength(strength: 0.3) { imageHarmonizeStrength } }").execute()
        }
    }

    /**
     * A picture longer than the working size is redrawn smaller - a 2048
     * square sent to an SD 1.5 checkpoint estimated thirteen hours - and the
     * redraw scaled back up, so the draft is the picture's own size.
     */
    @Test
    fun `harmonizing a picture larger than the working size sends it smaller and keeps the draft at its own size`() {
        val webUi = model("Forge", "UPSCALE", type = "STABLE_DIFFUSION_WEBUI", keyed = false, modelId = "Lanczos")
        val wide = png(600, 302) { _, _ -> WHITE }
        val source = images.save(
            DesignImage(
                workspaceId = workspaceId, modelId = null, modelName = "Drawer", prompt = "A white band",
                size = null, quality = null, style = null, filename = "band.png", contentType = "image/png",
                sizeBytes = wide.size.toLong(), location = store.put(workspaceId, "band.png", wide), drawnBy = "alice",
                name = "Band",
            ),
        )
        fun harmonize() = drawnPicture(
            graphQlTester.document(
                """mutation { queueDesignImageHarmonize(input: {
                     workspaceId: $workspaceId, imageId: "DESIGN-${source.id}", modelId: $webUi, prompt: "A white band"
                   }) { id } }""",
            ).execute().path("queueDesignImageHarmonize.id").entity(String::class.java).get(),
        )
        val json = tools.jackson.databind.json.JsonMapper.builder().build()
        try {
            graphQlTester.document("mutation { setImageHarmonizeSize(pixels: 256) { imageHarmonizeSize imageHarmonizeSizeConfigured } }")
                .execute()
                .path("setImageHarmonizeSize.imageHarmonizeSize").entity(Int::class.java).isEqualTo(256)
                .path("setImageHarmonizeSize.imageHarmonizeSizeConfigured").entity(Int::class.java).isEqualTo(1024)
            val version = harmonize()
            // The long side at the setting, the shape kept, each side a multiple of eight.
            val asked = json.readTree(harmonized.single())
            assertThat(asked.path("width").intValue()).isEqualTo(256)
            assertThat(asked.path("height").intValue()).isEqualTo(128)
            val sent = javax.imageio.ImageIO.read(Base64.getDecoder().decode(asked.path("init_images").path(0).stringValue()).inputStream())
            assertThat(sent.width to sent.height).isEqualTo(256 to 128)
            // The WebUI answered 2 by 2; the draft is the picture's own size, redrawn.
            val kept = javax.imageio.ImageIO.read(store.open(requireNotNull(images.findByIdOrNull(version.removePrefix("DESIGN-").toLong())).location))
            assertThat(kept.width to kept.height).isEqualTo(600 to 302)
            assertThat(kept.getRGB(300, 150)).isEqualTo(BLUE)

            // No longer than the setting: sent as it is, never made larger.
            graphQlTester.document("mutation { setImageHarmonizeSize(pixels: 1024) { imageHarmonizeSize } }").execute()
            harmonized.clear()
            harmonize()
            val whole = json.readTree(harmonized.single())
            assertThat(Base64.getDecoder().decode(whole.path("init_images").path(0).stringValue())).isEqualTo(wide)
            assertThat(whole.path("width").intValue() to whole.path("height").intValue()).isEqualTo(600 to 302)

            graphQlTester.document("mutation { setImageHarmonizeSize(pixels: 100) { imageHarmonizeSize } }").execute()
                .errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("ImageHarmonizeSizeOutOfRange") }
        } finally {
            graphQlTester.document("mutation { setImageHarmonizeSize(pixels: 1024) { imageHarmonizeSize } }").execute()
        }
    }

    @Test
    fun `a queued picture that is stopped is never started`() {
        val webUi = model("Forge", "UPSCALE", type = "STABLE_DIFFUSION_WEBUI", keyed = false, modelId = "Lanczos")
        val source = whiteSource()
        // On the queue and not yet handed to the draw queue, so nothing claims it before the stop.
        val job = jobs.save(
            DesignImageJob(
                workspaceId = workspaceId, modelId = webUi, modelName = "Forge", prompt = "A white square",
                size = null, quality = null, style = null, requestedBy = "alice",
                sourceId = "DESIGN-${source.id}", strength = 0.3,
            ),
        )
        graphQlTester.document("""mutation { stopDesignImageJob(workspaceId: $workspaceId, id: ${job.id}) { status } }""")
            .execute().path("stopDesignImageJob.status").entity(String::class.java).isEqualTo("STOPPED")
        queue.dispatch()

        assertThat(jobs.findByIdOrNull(requireNotNull(job.id))?.status).isEqualTo(DesignImageJobStatus.STOPPED)
        assertThat(harmonized).isEmpty()
        // Nothing was being drawn, so nothing is interrupted.
        assertThat(interrupted).isEmpty()
        // Stopped once is stopped: there is nothing left to stop.
        graphQlTester.document("""mutation { stopDesignImageJob(workspaceId: $workspaceId, id: ${job.id}) { status } }""")
            .execute().errors().satisfy { assertThat(it.single().message).isEqualTo("This picture is not waiting or being drawn any more.") }
    }

    @Test
    fun `a picture stopped while it is drawn is interrupted at the WebUI and what comes back is thrown away`() {
        val webUi = model("Forge", "UPSCALE", type = "STABLE_DIFFUSION_WEBUI", keyed = false, modelId = "Lanczos")
        val drawer = model("Drawer", "IMAGE")
        val source = whiteSource()
        hold = java.util.concurrent.CountDownLatch(1)
        val jobId = graphQlTester.document(
            """mutation { queueDesignImageHarmonize(input: {
                 workspaceId: $workspaceId, imageId: "DESIGN-${source.id}", modelId: $webUi, prompt: "A white square"
               }) { id } }""",
        ).execute().path("queueDesignImageHarmonize.id").entity(String::class.java).get().toLong()
        await().atMost(Duration.ofSeconds(10)).until { harmonized.size == 1 }
        assertThat(jobs.findByIdOrNull(jobId)?.status).isEqualTo(DesignImageJobStatus.DRAWING)

        graphQlTester.document("""mutation { stopDesignImageJob(workspaceId: $workspaceId, id: $jobId) { status } }""")
            .execute().path("stopDesignImageJob.status").entity(String::class.java).isEqualTo("STOPPED")
        await().atMost(Duration.ofSeconds(10)).until { interrupted.isNotEmpty() }
        assertThat(interrupted).containsExactly("POST")

        // One picture drawn at a time: the next is drawn only once the stopped one's drawing has come back.
        drawnPicture(
            graphQlTester.document(
                """mutation { queueDesignImage(input: { workspaceId: $workspaceId, modelId: $drawer, prompt: "A red square" }) { id } }""",
            ).execute().path("queueDesignImage.id").entity(String::class.java).get(),
        )
        assertThat(jobs.findByIdOrNull(jobId)?.status).isEqualTo(DesignImageJobStatus.STOPPED)
        assertThat(jobs.findByIdOrNull(jobId)?.imageId).isNull()
        assertThat(images.findAll().filter { it.versionOf == "DESIGN-${source.id}" }).isEmpty()
    }

    @Test
    fun `a picture in another workspace cannot be stopped from this one`() {
        val other = requireNotNull(workspaces.save(Workspace(name = "elsewhere")).id)
        val job = jobs.save(
            DesignImageJob(
                workspaceId = other, modelId = null, modelName = "Drawer", prompt = "A red square",
                size = null, quality = null, style = null, requestedBy = "alice",
            ),
        )
        graphQlTester.document("""mutation { stopDesignImageJob(workspaceId: $workspaceId, id: ${job.id}) { status } }""")
            .execute().errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("DesignImageRefused") }
        assertThat(jobs.findByIdOrNull(requireNotNull(job.id))?.status).isEqualTo(DesignImageJobStatus.QUEUED)
    }

    @Test
    @WithMockUser(username = "bob", roles = ["USERS"])
    fun `somebody who cannot see the workspace cannot stop its pictures`() {
        val job = jobs.save(
            DesignImageJob(
                workspaceId = workspaceId, modelId = null, modelName = "Drawer", prompt = "A red square",
                size = null, quality = null, style = null, requestedBy = "alice",
            ),
        )
        graphQlTester.document("""mutation { stopDesignImageJob(workspaceId: $workspaceId, id: ${job.id}) { status } }""")
            .execute().errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("WorkspaceNotFound") }
        assertThat(jobs.findByIdOrNull(requireNotNull(job.id))?.status).isEqualTo(DesignImageJobStatus.QUEUED)
    }

    private fun whiteSource(): DesignImage {
        val white = png(2, 2) { _, _ -> WHITE }
        return images.save(
            DesignImage(
                workspaceId = workspaceId, modelId = null, modelName = "Drawer", prompt = "A white square",
                size = null, quality = null, style = null, filename = "white.png", contentType = "image/png",
                sizeBytes = white.size.toLong(), location = store.put(workspaceId, "white.png", white), drawnBy = "alice",
                name = "White",
            ),
        )
    }

    /**
     * An image model cannot harmonize: its edit endpoint takes no strength, so
     * it redrew the whole picture rather than lightly - "add birds flying" put
     * birds over all of it. Refused, saying where the light redraw is.
     */
    @Test
    fun `harmonizing with an image model is refused, since it cannot redraw lightly`() {
        val editor = model("Editor", "IMAGE")
        val white = png(2, 2) { _, _ -> WHITE }
        val source = images.save(
            DesignImage(
                workspaceId = workspaceId, modelId = editor, modelName = "Editor", prompt = "A white square",
                size = null, quality = null, style = null, filename = "white.png", contentType = "image/png",
                sizeBytes = white.size.toLong(), location = store.put(workspaceId, "white.png", white), drawnBy = "alice",
                name = "Blank",
            ),
        )
        graphQlTester.document(
            """mutation { queueDesignImageHarmonize(input: {
                 workspaceId: $workspaceId, imageId: "DESIGN-${source.id}", modelId: $editor, prompt: "A white square"
               }) { id } }""",
        ).execute().errors().satisfy { assertThat(it.single().message).contains("Stable Diffusion WebUI") }
        assertThat(edited).isEmpty()
        assertThat(harmonized).isEmpty()
    }

    @Test
    fun `a picture brought in from outside is kept as a Design picture, by what its bytes are`() {
        val png = Base64.getEncoder().encodeToString(png(3, 2) { _, _ -> RED })
        graphQlTester.document(
            """mutation { importDesignImage(input: { workspaceId: $workspaceId, image: "$png", name: "Holiday", prompt: "A beach" }) {
                 kind name prompt drawnWith { model } } }""",
        ).execute()
            .path("importDesignImage.kind").entity(String::class.java).isEqualTo("DESIGN")
            .path("importDesignImage.name").entity(String::class.java).isEqualTo("Holiday")
            .path("importDesignImage.prompt").entity(String::class.java).isEqualTo("A beach")
            .path("importDesignImage.drawnWith.model").entity(String::class.java).isEqualTo("Imported")
        assertThat(images.findAll().single().contentType).isEqualTo("image/png")

        // As an .orkx archive and back: the picture byte for byte, with its name, prompt and what drew it.
        val drawer = model("Drawer", "IMAGE")
        val kept = images.save(
            DesignImage(
                workspaceId = workspaceId, modelId = drawer, modelName = "Drawer", prompt = "A red field",
                size = "1024x1024", quality = "hd", style = null, filename = "red.png", contentType = "image/png",
                sizeBytes = 1, location = store.put(workspaceId, "red.png", png(3, 2) { _, _ -> RED }), drawnBy = "bob",
                name = "Red field",
            ),
        )
        val archive = api.archive(workspaceId, "DESIGN-${kept.id}")
        assertThat(archive.headers.contentDisposition.filename).isEqualTo("red-field.orkx")
        val entries = java.util.zip.ZipInputStream(archive.body!!.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.map { it.name to zip.readBytes() }.toMap()
        }
        assertThat(entries.keys).containsExactlyInAnyOrder("project.json", "picture.png")
        assertThat(entries["picture.png"]).isEqualTo(store.open(kept.location).use { it.readBytes() })
        graphQlTester.document(
            """mutation { importDesignImage(input: { workspaceId: $workspaceId, archive: "${Base64.getEncoder().encodeToString(archive.body)}" }) {
                 name prompt drawnWith { modelId model size quality drawnBy } } }""",
        ).execute()
            .path("importDesignImage.name").entity(String::class.java).isEqualTo("Red field")
            .path("importDesignImage.prompt").entity(String::class.java).isEqualTo("A red field")
            .path("importDesignImage.drawnWith.modelId").entity(Long::class.java).isEqualTo(drawer)
            .path("importDesignImage.drawnWith.size").entity(String::class.java).isEqualTo("1024x1024")
            .path("importDesignImage.drawnWith.quality").entity(String::class.java).isEqualTo("hd")
            .path("importDesignImage.drawnWith.drawnBy").entity(String::class.java).isEqualTo("bob")
        images.deleteById(requireNotNull(kept.id))
        images.findAll().filter { it.name == "Red field" }.forEach { images.delete(it) }

        val text = Base64.getEncoder().encodeToString("not a picture".toByteArray())
        graphQlTester.document("""mutation { importDesignImage(input: { workspaceId: $workspaceId, image: "$text" }) { id } }""")
            .execute().errors().satisfy { assertThat(it.single().message).contains("not a picture") }
        assertThat(images.count()).isEqualTo(1)
    }

    /**
     * A negative prompt reaches stable-diffusion.cpp's server the one way its
     * OpenAI-shaped routes read one - inside the prompt, as `<sd_cpp_extra_args>`
     * - on a drawing and on an edit; and the model's own default stands in
     * where a request names none. Issue #657.
     */
    @Test
    fun `a negative prompt reaches stable-diffusion cpp's server inside the prompt, and the model's default stands in for none`() {
        val modelId = model("Local", "IMAGE", type = "STABLE_DIFFUSION_CPP", extra = """, negativePrompt: "  blurry  " """)
        graphQlTester.document("""query { model(id: $modelId) { negativePrompt takesNegativePrompt } }""").execute()
            .path("model.negativePrompt").entity(String::class.java).isEqualTo("blurry")
            .path("model.takesNegativePrompt").entity(Boolean::class.java).isEqualTo(true)

        val asked = graphQlTester.document(
            """mutation { queueDesignImage(input: {
                 workspaceId: $workspaceId, modelId: $modelId, prompt: "A red square", negativePrompt: "text, \"watermark\""
               }) { id negativePrompt } }""",
        ).execute()
            .path("queueDesignImage.negativePrompt").entity(String::class.java).isEqualTo("text, \"watermark\"")
            .path("queueDesignImage.id").entity(String::class.java).get()
        drawnPicture(asked)
        val json = tools.jackson.databind.json.JsonMapper.builder().build()
        assertThat(json.readTree(drawn.single()).path("prompt").stringValue())
            .isEqualTo("""A red square <sd_cpp_extra_args>{"negative_prompt":"text, \"watermark\""}</sd_cpp_extra_args>""")
        // No top-level field: the server reads none on this route, and a stub cannot tell one ignored from one read.
        assertThat(json.readTree(drawn.single()).has("negative_prompt")).isFalse()

        drawn.clear()
        drawnPicture(
            graphQlTester.document(
                """mutation { queueDesignImage(input: { workspaceId: $workspaceId, modelId: $modelId, prompt: "A red square" }) { id } }""",
            ).execute().path("queueDesignImage.id").entity(String::class.java).get(),
        )
        assertThat(json.readTree(drawn.single()).path("prompt").stringValue())
            .isEqualTo("""A red square <sd_cpp_extra_args>{"negative_prompt":"blurry"}</sd_cpp_extra_args>""")

        val source = whiteSource()
        val mask = Base64.getEncoder().encodeToString(png(2, 2) { x, _ -> if (x == 0) 0x00000000 else 0xFF000000.toInt() })
        drawnPicture(
            graphQlTester.document(
                """mutation { queueDesignImageEdit(input: {
                     workspaceId: $workspaceId, imageId: "DESIGN-${source.id}", modelId: $modelId, prompt: "Make it red",
                     mask: "$mask", negativePrompt: "birds"
                   }) { id } }""",
            ).execute().path("queueDesignImageEdit.id").entity(String::class.java).get(),
        )
        assertThat(edited.single()).contains("""Make it red <sd_cpp_extra_args>{"negative_prompt":"birds"}</sd_cpp_extra_args>""")
    }

    /** img2img takes `negative_prompt` in its body; a WebUI model's default stands in where none is given. Issue #657. */
    @Test
    fun `harmonizing through a WebUI sends its negative prompt to img2img`() {
        val webUi = model(
            "Forge", "UPSCALE", type = "STABLE_DIFFUSION_WEBUI", keyed = false, modelId = "Lanczos",
            extra = """, negativePrompt: "seams" """,
        )
        val source = whiteSource()
        fun harmonize(negative: String) = drawnPicture(
            graphQlTester.document(
                """mutation { queueDesignImageHarmonize(input: {
                     workspaceId: $workspaceId, imageId: "DESIGN-${source.id}", modelId: $webUi, prompt: "A white square"$negative
                   }) { id } }""",
            ).execute().path("queueDesignImageHarmonize.id").entity(String::class.java).get(),
        )
        val json = tools.jackson.databind.json.JsonMapper.builder().build()

        harmonize(""", negativePrompt: "people" """)
        assertThat(json.readTree(harmonized.single()).path("negative_prompt").stringValue()).isEqualTo("people")
        assertThat(json.readTree(harmonized.single()).path("prompt").stringValue()).isEqualTo("A white square")

        harmonized.clear()
        harmonize("")
        assertThat(json.readTree(harmonized.single()).path("negative_prompt").stringValue()).isEqualTo("seams")
    }

    /**
     * OpenAI's image API has no negative prompt: the model says it takes none,
     * a default cannot be saved on it, and a request naming one is refused
     * before anything is asked - never drawn without it. Issue #657.
     */
    @Test
    fun `a model whose provider takes no negative prompt says so, and one asked of it is refused`() {
        val modelId = model("Drawer", "IMAGE")
        graphQlTester.document("""query { model(id: $modelId) { negativePrompt takesNegativePrompt } }""").execute()
            .path("model.takesNegativePrompt").entity(Boolean::class.java).isEqualTo(false)
            .path("model.negativePrompt").valueIsNull()

        graphQlTester.document(
            """mutation { queueDesignImage(input: {
                 workspaceId: $workspaceId, modelId: $modelId, prompt: "A red square", negativePrompt: "text"
               }) { id } }""",
        ).execute().errors().satisfy {
            assertThat(it.single().message).isEqualTo("Drawer does not take a negative prompt; leave it out.")
        }
        val providerId = requireNotNull(models.findByIdOrNull(modelId)).providerId
        graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Other", modelId: "x", kind: IMAGE, negativePrompt: "text" }) { id } }""",
        ).execute().errors().satisfy {
            assertThat(it.single().extensions["code"]).isEqualTo("NegativePromptNotTaken")
        }
        graphQlTester.document(
            """mutation { updateModel(id: $modelId, input: { name: "Drawer", modelId: "stub-IMAGE", kind: IMAGE, negativePrompt: "text" }) { id } }""",
        ).execute().errors().satisfy {
            assertThat(it.single().extensions["code"]).isEqualTo("NegativePromptNotTaken")
        }
        // Blank is none, and is no reason to refuse.
        graphQlTester.document(
            """mutation { queueDesignImage(input: {
                 workspaceId: $workspaceId, modelId: $modelId, prompt: "A red square", negativePrompt: "  "
               }) { id } }""",
        ).execute().path("queueDesignImage.id").entity(String::class.java).get().let(::drawnPicture)
        assertThat(drawn.single()).doesNotContain("sd_cpp_extra_args").doesNotContain("negative")
    }

    private fun model(
        name: String,
        kind: String,
        keyed: Boolean = true,
        type: String = "OPENAI",
        modelId: String = "stub-$kind",
        extra: String = "",
    ): Long {
        val key = if (keyed) """, secret: "sk-test"""" else ""
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "$name provider", type: $type,
                 endpoint: "http://${server.address.hostString}:${server.address.port}"$key
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "$name", modelId: "$modelId", kind: $kind$extra }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val PIXEL =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    }
}
