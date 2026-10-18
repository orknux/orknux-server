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
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer
    private val drawn = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun reset() {
        images.deleteAll()
        usage.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        drawn.clear()
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/images/generations") { exchange ->
            drawn += exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val bytes = """{"data":[{"b64_json":"$PIXEL"}]}""".toByteArray(StandardCharsets.UTF_8)
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
    fun `a picture is drawn with the model chosen, kept with its parameters, and listed as drawn`() {
        val modelId = model("Drawer", "IMAGE")

        val id = graphQlTester.document(
            """mutation { drawDesignImage(input: {
                 workspaceId: $workspaceId, modelId: $modelId, prompt: "A red square",
                 size: "1024x1024", quality: "hd"
               }) { id kind url drawnWith { modelId model size quality style drawnBy } } }""",
        ).execute()
            .path("drawDesignImage.kind").entity(String::class.java).isEqualTo("DESIGN")
            .path("drawDesignImage.drawnWith.model").entity(String::class.java).isEqualTo("Drawer")
            .path("drawDesignImage.drawnWith.modelId").entity(Long::class.java).isEqualTo(modelId)
            .path("drawDesignImage.drawnWith.size").entity(String::class.java).isEqualTo("1024x1024")
            .path("drawDesignImage.drawnWith.quality").entity(String::class.java).isEqualTo("hd")
            .path("drawDesignImage.drawnWith.style").valueIsNull()
            .path("drawDesignImage.drawnWith.drawnBy").entity(String::class.java).isEqualTo("alice")
            .path("drawDesignImage.id").entity(String::class.java).get()

        // What was asked of the model is what the page said.
        assertThat(drawn.single()).contains("A red square", "1024x1024", "hd")
        val kept = images.findAll().single()
        assertThat(store.open(kept.location).use { it.readBytes() }).isEqualTo(Base64.getDecoder().decode(PIXEL))
        assertThat(api.download(requireNotNull(kept.id)).statusCode.value()).isEqualTo(200)

        // On the Images page, and on Artifacts.
        graphQlTester.document(
            """query { workspaceArtifacts(workspaceId: $workspaceId, drawn: true) { content { id } } }""",
        ).execute().path("workspaceArtifacts.content[*].id").entityList(String::class.java).containsExactly(id)
        graphQlTester.document(
            """query { workspaceArtifact(workspaceId: $workspaceId, id: "$id") { drawnWith { model } } }""",
        ).execute().path("workspaceArtifact.drawnWith.model").entity(String::class.java).isEqualTo("Drawer")
    }

    @Test
    fun `a model that does not draw is refused, and nothing is asked of it`() {
        val chat = model("Talker", "CHAT")

        graphQlTester.document(
            """mutation { drawDesignImage(input: { workspaceId: $workspaceId, modelId: $chat, prompt: "A red square" }) { id } }""",
        ).execute().errors().satisfy { assertThat(it).hasSize(1) }

        graphQlTester.document(
            """mutation { drawDesignImage(input: { workspaceId: $workspaceId, modelId: ${model("Drawer", "IMAGE")}, prompt: "  " }) { id } }""",
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
                """mutation { drawDesignImage(input: { workspaceId: $workspaceId, modelId: $drawer, prompt: "${"x".repeat(101)}" }) { id } }""",
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
