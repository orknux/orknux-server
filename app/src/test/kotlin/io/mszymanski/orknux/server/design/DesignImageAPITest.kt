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
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/images/generations") { exchange ->
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
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `a picture is queued, drawn in turn with the model chosen, and kept as a draft until saved`() {
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
                 status image { id kind draft drawnWith { modelId model size quality style drawnBy } } } }""",
        ).execute()
            .path("designImageJob.status").entity(String::class.java).isEqualTo("DRAWN")
            .path("designImageJob.image.kind").entity(String::class.java).isEqualTo("DESIGN")
            .path("designImageJob.image.draft").entity(Boolean::class.java).isEqualTo(true)
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

        // A draft is on Design's gallery and not on Artifacts; saved, it is on both.
        fun listed(drawnOnly: Boolean) = graphQlTester.document(
            """query { workspaceArtifacts(workspaceId: $workspaceId, drawn: $drawnOnly) { content { id } } }""",
        ).execute().path("workspaceArtifacts.content[*].id").entityList(String::class.java).get()
        assertThat(listed(true)).containsExactly(id)
        assertThat(listed(false)).isEmpty()

        graphQlTester.document("""mutation { saveDesignImage(workspaceId: $workspaceId, id: "$id") { draft } }""")
            .execute().path("saveDesignImage.draft").entity(Boolean::class.java).isEqualTo(false)
        assertThat(listed(false)).containsExactly(id)
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

    private fun model(name: String, kind: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "$name provider", type: OPENAI,
                 endpoint: "http://${server.address.hostString}:${server.address.port}", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "$name", modelId: "stub-$kind", kind: $kind }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }

    private companion object {
        const val PIXEL =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    }
}
