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
@SpringBootTest(properties = ["orknux.attachments.location=target/test-design-images"])
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
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer
    private val drawn = CopyOnWriteArrayList<String>()
    private val edited = CopyOnWriteArrayList<String>()
    private val authorised = CopyOnWriteArrayList<String>()

    /** True for a model that refuses, the way these providers do: a 400 with the reason. */
    @Volatile
    private var refusing = false

    @BeforeEach
    fun reset() {
        jobs.deleteAll()
        images.deleteAll()
        refusing = false
        usage.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        drawn.clear()
        edited.clear()
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

        // Saved: the picture itself takes the change, keeps its name, and the version is gone.
        graphQlTester.document("""mutation { saveDesignImageVersion(workspaceId: $workspaceId, id: "$id") { id name versionOf } }""")
            .execute()
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

        graphQlTester.document("""mutation { discardDesignImageVersions(workspaceId: $workspaceId, id: "DESIGN-${source.id}") }""")
            .execute().path("discardDesignImageVersions").entity(Boolean::class.java).isEqualTo(true)
        assertThat(images.findAll().map { it.id }).containsExactly(source.id)
        assertThat(javax.imageio.ImageIO.read(store.open(source.location)).getRGB(0, 0)).isEqualTo(WHITE)
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

    private fun model(name: String, kind: String, keyed: Boolean = true): Long {
        val key = if (keyed) """, secret: "sk-test"""" else ""
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "$name provider", type: OPENAI,
                 endpoint: "http://${server.address.hostString}:${server.address.port}"$key
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "$name", modelId: "stub-$kind", kind: $kind }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val PIXEL =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    }
}
