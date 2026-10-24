package io.mszymanski.orknux.server.design

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.connector.model.ModelUsageRepository
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.workspace.Workspace
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
import java.awt.image.BufferedImage
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import javax.imageio.ImageIO

/**
 * A picture keeps its layers from the image editor: with every version, over
 * the picture once saved, in its .orkx archive, and under a model's change -
 * while its own file stays the layers flattened, which is what everything else
 * reads. They used to be the page's alone, and Save flattened them.
 */
@SpringBootTest(
    properties = [
        // The same as DesignImageAPITest's, so the two share one context.
        "orknux.attachments.location=target/test-design-images",
        "orknux.tuning.images.feather.pixels=0",
    ],
)
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class DesignImageLayersTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val images: DesignImageRepository,
    @Autowired val jobs: DesignImageJobRepository,
    @Autowired val layerRows: DesignImageLayerRepository,
    @Autowired val layers: DesignImageLayers,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val usage: ModelUsageRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val store: AttachmentStore,
    @Autowired val api: DesignImageAPI,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer

    @BeforeEach
    fun reset() {
        layerRows.deleteAll()
        jobs.deleteAll()
        images.deleteAll()
        usage.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "layers")).id)
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        // An edit model that answers a red picture, whatever it was handed.
        server.createContext("/images/edits") { exchange ->
            exchange.requestBody.readBytes()
            answer(exchange, """{"data":[{"b64_json":"${Base64.getEncoder().encodeToString(png(2, 2) { _, _ -> RED })}"}]}""")
        }
        // A WebUI that makes a picture twice as large, red.
        server.createContext("/sdapi/v1/upscalers") { exchange -> answer(exchange, """[{"name":"Lanczos"}]""") }
        server.createContext("/sdapi/v1/extra-single-image") { exchange ->
            exchange.requestBody.readBytes()
            answer(exchange, """{"image":"${Base64.getEncoder().encodeToString(png(4, 4) { _, _ -> RED })}","html_info":""}""")
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `a change kept with layers keeps them in order, and Save gives them to the picture`() {
        val source = whiteSource()
        val eyes = png(2, 2) { x, y -> if (x == 0 && y == 0) RED else CLEAR }
        val hidden = png(2, 2) { _, _ -> BLUE }
        val flattened = png(2, 2) { x, y -> if (x == 0 && y == 0) RED else WHITE }

        val version = keep(
            "DESIGN-${source.id}",
            flattened,
            "Painted on a layer",
            listOf(Sent(null, true, true, png(2, 2) { _, _ -> WHITE }), Sent("Eyes", true, false, eyes), Sent("Hidden", false, false, hidden)),
        )
        // The version's file is what it looks like; its layers are what the editor opens again.
        val kept = requireNotNull(images.findByIdOrNull(version.removePrefix("DESIGN-").toLong()))
        assertThat(store.open(kept.location).use { it.readBytes() }).isEqualTo(flattened)
        val listed = graphQlTester.document("""query { designImageLayers(workspaceId: $workspaceId, imageId: "$version") { id name visible picture url } }""")
            .execute()
        val names: List<String?> = listed.path("designImageLayers[*].name").entityList(String::class.java).get()
        assertThat(names).containsExactly(null, "Eyes", "Hidden")
        listed
            .path("designImageLayers[*].picture").entityList(Boolean::class.java).containsExactly(true, false, false)
            .path("designImageLayers[*].visible").entityList(Boolean::class.java).containsExactly(true, true, false)
        // Each served as the PNG it was sent as, paint only where it was painted.
        val served = layers.of(workspaceId, version)[1]
        val eyesBack = ImageIO.read(api.layer(requireNotNull(served.id)).body!!.inputStream)
        assertThat(eyesBack.getRGB(0, 0)).isEqualTo(RED)
        assertThat(eyesBack.getRGB(1, 1) ushr 24).isZero()
        // The picture itself has none until Save.
        assertThat(layers.of(workspaceId, "DESIGN-${source.id}")).isEmpty()

        graphQlTester.document("""mutation { saveDesignImageVersion(workspaceId: $workspaceId, id: "$version") { id } }""").execute()
        assertThat(layers.of(workspaceId, "DESIGN-${source.id}").map { it.name }).containsExactly(null, "Eyes", "Hidden")
        assertThat(layers.of(workspaceId, version)).isEmpty()
        val saved = requireNotNull(images.findByIdOrNull(requireNotNull(source.id)))
        assertThat(store.open(saved.location).use { it.readBytes() }).isEqualTo(flattened)

        // Flattened - a change kept with no layers - and saved: the picture has none any more, nor their files.
        val before = layers.of(workspaceId, "DESIGN-${source.id}").map { it.location }
        val flat = keep("DESIGN-${source.id}", flattened, "Layers flattened", null)
        graphQlTester.document("""mutation { saveDesignImageVersion(workspaceId: $workspaceId, id: "$flat") { id } }""").execute()
        assertThat(layers.of(workspaceId, "DESIGN-${source.id}")).isEmpty()
        assertThat(before.none { store.exists(it) }).isTrue()
    }

    @Test
    fun `Discard throws a version's layers away and leaves the picture's as they were saved`() {
        val source = whiteSource()
        layers.keep(workspaceId, "DESIGN-${source.id}", listOf(base(), LayerPixels("Saved", true, false, png(2, 2) { _, _ -> CLEAR })))
        val version = keep(
            "DESIGN-${source.id}",
            png(2, 2) { _, _ -> WHITE },
            "Layer added",
            listOf(Sent(null, true, true, png(2, 2) { _, _ -> WHITE }), Sent("Saved", true, false, png(2, 2) { _, _ -> CLEAR }), Sent("New", true, false, png(2, 2) { _, _ -> CLEAR })),
        )
        assertThat(layers.of(workspaceId, version).map { it.name }).containsExactly(null, "Saved", "New")

        graphQlTester.document("""mutation { discardDesignImageVersions(workspaceId: $workspaceId, id: "DESIGN-${source.id}") }""").execute()
        assertThat(layerRows.findAll().filter { it.imageId == version }).isEmpty()
        assertThat(layers.of(workspaceId, "DESIGN-${source.id}").map { it.name }).containsExactly(null, "Saved")
    }

    @Test
    fun `layers that could not be the picture's are refused, and nothing is kept`() {
        val source = whiteSource()
        val white = png(2, 2) { _, _ -> WHITE }
        fun refused(sent: List<Sent>): String {
            var said = ""
            graphQlTester.document(document("DESIGN-${source.id}", white, "Painted", sent)).execute()
                .errors().satisfy { said = it.single().message.orEmpty() }
            return said
        }

        assertThat(refused(listOf(Sent(null, true, true, png(3, 2) { _, _ -> WHITE })))).isEqualTo("Every layer is the picture's own size.")
        assertThat(refused(listOf(Sent("Eyes", true, false, white), Sent(null, true, true, white))))
            .isEqualTo("The picture itself is the lowest layer, and the only one.")
        assertThat(refused(listOf(Sent(null, true, true, white), Sent(" ", true, false, white)))).isEqualTo("Every layer has a name.")
        graphQlTester.document("""mutation { setTunable(key: "images.layers.most", value: 1) { value } }""").execute()
        try {
            // The editor is told the same number, so New layer stops where the server would refuse.
            graphQlTester.document("""query { designImageEditorSettings(workspaceId: $workspaceId) { layersMost } }""")
                .execute().path("designImageEditorSettings.layersMost").entity(Int::class.java).isEqualTo(1)
            assertThat(refused(listOf(Sent(null, true, true, white), Sent("One", true, false, white), Sent("Two", true, false, white))))
                .isEqualTo("A picture keeps at most 1 layers over it.")
        } finally {
            graphQlTester.document("""mutation { setTunable(key: "images.layers.most", value: null) { value } }""").execute()
        }
        assertThat(images.findAll().map { it.id }).containsExactly(source.id)
        assertThat(layerRows.count()).isZero()
    }

    @Test
    fun `an orkx archive carries the layers, and importing it brings them back`() {
        val source = whiteSource()
        val eyes = png(2, 2) { x, y -> if (x == 1 && y == 1) BLUE else CLEAR }
        layers.keep(workspaceId, "DESIGN-${source.id}", listOf(base(), LayerPixels("Eyes", false, false, eyes)))

        val archive = requireNotNull(api.archive(workspaceId, "DESIGN-${source.id}").body)
        val entries = java.util.zip.ZipInputStream(archive.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.map { it.name to zip.readBytes() }.toMap()
        }
        assertThat(entries.keys).containsExactlyInAnyOrder("project.json", "picture.png", "layers/0.png", "layers/1.png")
        assertThat(entries["layers/1.png"]).isEqualTo(eyes)

        val imported = graphQlTester.document(
            """mutation { importDesignImage(input: { workspaceId: $workspaceId, archive: "${Base64.getEncoder().encodeToString(archive)}" }) { id } }""",
        ).execute().path("importDesignImage.id").entity(String::class.java).get()
        val back = layers.of(workspaceId, imported)
        assertThat(back.map { it.name }).containsExactly(null, "Eyes")
        assertThat(back.map { it.visible }).containsExactly(true, false)
        assertThat(store.open(back[1].location).use { it.readBytes() }).isEqualTo(eyes)
    }

    @Test
    fun `a Magic edit of a picture with layers lands as a layer of its own over them`() {
        val modelId = model("Editor", "IMAGE")
        // A white picture with a blue dot on a layer, its file the two flattened.
        val source = picture(png(2, 2) { x, y -> if (x == 1 && y == 1) BLUE else WHITE })
        val dot = png(2, 2) { x, y -> if (x == 1 && y == 1) BLUE else CLEAR }
        layers.keep(workspaceId, "DESIGN-${source.id}", listOf(base(), LayerPixels("Dot", true, false, dot)))
        val mask = Base64.getEncoder().encodeToString(png(2, 2) { x, _ -> if (x == 0) 0x00000000 else 0xFF000000.toInt() })

        val version = drawnPicture(
            graphQlTester.document(
                """mutation { queueDesignImageEdit(input: {
                     workspaceId: $workspaceId, imageId: "DESIGN-${source.id}", modelId: $modelId, prompt: "Make it red", mask: "$mask"
                   }) { id } }""",
            ).execute().path("queueDesignImageEdit.id").entity(String::class.java).get(),
        )

        // The layers it was asked of, untouched, and the change on top - only where it was asked for.
        val stack = layers.of(workspaceId, version)
        assertThat(stack.map { it.name }).containsExactly(null, "Dot", "Make it red")
        assertThat(store.open(stack[1].location).use { it.readBytes() }).isEqualTo(dot)
        val change = ImageIO.read(store.open(stack[2].location))
        assertThat(change.getRGB(0, 0)).isEqualTo(RED)
        assertThat(change.getRGB(1, 0) ushr 24).isZero()
        // And the version's file is the stack flattened, as before.
        val flat = ImageIO.read(store.open(requireNotNull(images.findByIdOrNull(version.removePrefix("DESIGN-").toLong())).location))
        assertThat(flat.getRGB(0, 0)).isEqualTo(RED)
        assertThat(flat.getRGB(1, 1)).isEqualTo(BLUE)
    }

    @Test
    fun `an upscale of a picture with layers makes every layer larger and lays the result over them`() {
        val upscaler = model("Enlarger", "UPSCALE", type = "STABLE_DIFFUSION_WEBUI", keyed = false, modelId = "Lanczos")
        val source = whiteSource()
        layers.keep(workspaceId, "DESIGN-${source.id}", listOf(base(), LayerPixels("Dot", true, false, png(2, 2) { _, _ -> CLEAR })))

        val version = drawnPicture(
            graphQlTester.document(
                """mutation { queueDesignImageUpscale(input: { workspaceId: $workspaceId, imageId: "DESIGN-${source.id}", modelId: $upscaler, scale: 2 }) { id } }""",
            ).execute().path("queueDesignImageUpscale.id").entity(String::class.java).get(),
        )

        val stack = layers.of(workspaceId, version)
        assertThat(stack.map { it.name }).containsExactly(null, "Dot", "Upscaled 2x")
        stack.forEach { assertThat(ImageIO.read(store.open(it.location)).width).isEqualTo(4) }
    }

    @Test
    fun `a picture deleted takes its layers with it, and its drafts' too`() {
        val source = whiteSource()
        layers.keep(workspaceId, "DESIGN-${source.id}", listOf(base(), LayerPixels("Saved", true, false, png(2, 2) { _, _ -> CLEAR })))
        val version = keep(
            "DESIGN-${source.id}",
            png(2, 2) { _, _ -> WHITE },
            "Layer added",
            listOf(Sent(null, true, true, png(2, 2) { _, _ -> WHITE }), Sent("New", true, false, png(2, 2) { _, _ -> CLEAR })),
        )
        val files = layerRows.findAll().map { it.location }
        assertThat(files).hasSize(4)

        graphQlTester.document("""mutation { deleteArtifact(id: "DESIGN-${source.id}") }""").execute()
        assertThat(layerRows.findAll().filter { it.imageId == version || it.imageId == "DESIGN-${source.id}" }).isEmpty()
        assertThat(files.none { store.exists(it) }).isTrue()

        // A workspace deleted takes every layer in it.
        val other = whiteSource()
        layers.keep(workspaceId, "DESIGN-${other.id}", listOf(base()))
        graphQlTester.document("""mutation { deleteWorkspace(id: $workspaceId) }""").execute()
        assertThat(layerRows.findAll().filter { it.workspaceId == workspaceId }).isEmpty()
    }

    /** One layer as the page sends it. */
    private data class Sent(val name: String?, val visible: Boolean, val picture: Boolean, val png: ByteArray)

    private fun document(of: String, image: ByteArray, change: String, sent: List<Sent>?): String {
        val stack = sent?.joinToString(",", prefix = ", layers: [", postfix = "]") {
            val name = it.name?.let { called -> "\"$called\"" } ?: "null"
            """{ name: $name, visible: ${it.visible}, picture: ${it.picture}, image: "${Base64.getEncoder().encodeToString(it.png)}" }"""
        }.orEmpty()
        return """mutation { keepDesignImageChange(input: {
                 workspaceId: $workspaceId, imageId: "$of", image: "${Base64.getEncoder().encodeToString(image)}", change: "$change"$stack
               }) { image { id } } }"""
    }

    private fun keep(of: String, image: ByteArray, change: String, sent: List<Sent>?): String =
        graphQlTester.document(document(of, image, change, sent)).execute()
            .path("keepDesignImageChange.image.id").entity(String::class.java).get()

    private fun base() = LayerPixels(null, true, true, png(2, 2) { _, _ -> WHITE })

    private fun whiteSource(): DesignImage = picture(png(2, 2) { _, _ -> WHITE })

    private fun picture(bytes: ByteArray): DesignImage = images.save(
        DesignImage(
            workspaceId = workspaceId, modelId = null, modelName = "Drawer", prompt = "A white square",
            size = null, quality = null, style = null, filename = "white.png", contentType = "image/png",
            sizeBytes = bytes.size.toLong(), location = store.put(workspaceId, "white.png", bytes), drawnBy = "alice",
            name = "White",
        ),
    )

    private fun drawnPicture(jobId: String): String {
        await().atMost(Duration.ofSeconds(10)).until { jobs.findByIdOrNull(jobId.toLong())?.status == DesignImageJobStatus.DRAWN }
        return "DESIGN-" + requireNotNull(jobs.findByIdOrNull(jobId.toLong())?.imageId)
    }

    private fun model(name: String, kind: String, keyed: Boolean = true, type: String = "OPENAI", modelId: String = "stub-$kind"): Long {
        val key = if (keyed) """, secret: "sk-test"""" else ""
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "$name provider", type: $type,
                 endpoint: "http://${server.address.hostString}:${server.address.port}"$key
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "$name", modelId: "$modelId", kind: $kind }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }

    private fun answer(exchange: com.sun.net.httpserver.HttpExchange, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private fun png(width: Int, height: Int, colour: (Int, Int) -> Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, colour(x, y))
        return java.io.ByteArrayOutputStream().use { out ->
            ImageIO.write(image, "png", out)
            out.toByteArray()
        }
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val CLEAR = 0x00000000
    }
}
