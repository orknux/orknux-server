package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
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
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * How fast an answer is read aloud: chosen in voice mode, sent with the text,
 * and handed to the speech model as `speed`. Issue #673.
 *
 * What is measured is the request that leaves for the provider, because the
 * speed is the provider's to apply - nothing here plays anything. A stub on the
 * loopback stands in for it.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class SpeechSpeedTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val speech: SpeechAPI,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val context: WebApplicationContext,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer
    private val asked = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun start() {
        models.deleteAll()
        providers.deleteAll()
        workspaces.deleteAll()
        asked.clear()

        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/audio/speech") { exchange ->
            asked += exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val bytes = "ID3-not-really-an-mp3".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "audio/mpeg")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
        val modelId = speechModel("http://${server.address.hostString}:${server.address.port}")
        val workspace = workspaces.findById(workspaceId).orElseThrow()
        workspace.speechModelId = modelId
        workspaces.save(workspace)
    }

    @AfterEach
    fun stop() = server.stop(0)

    /** Nobody chose: the request is the one it always was, with no speed in it. */
    @Test
    fun `a reading nobody set a speed for sends none`() {
        speech.speak(workspaceId, SpeechRequest("Hello.", null))

        assertThat(sent().has("speed")).isFalse()
    }

    @Test
    fun `the speed chosen is the speed the model is asked for`() {
        speech.speak(workspaceId, SpeechRequest("Hello.", 1.5))

        assertThat(sent().get("speed").asDouble()).isEqualTo(1.5)
    }

    /** A speed the API would refuse is held at its edge, and the answer is still read. */
    @Test
    fun `a speed past what the API takes is held at its edge`() {
        speech.speak(workspaceId, SpeechRequest("Hello.", 9.0))
        speech.speak(workspaceId, SpeechRequest("Hello.", 0.01))

        assertThat(asked.map { reader.readTree(it).get("speed").asDouble() }).containsExactly(4.0, 0.25)
    }

    /**
     * Through the door the browser uses, so the field is proven to bind - a
     * request body that drops it reads as a speed nobody chose.
     */
    @Test
    fun `the speed the browser sends reaches the model`() {
        val mvc = MockMvcBuilders.webAppContextSetup(context).build()

        val answer = mvc.perform(
            post("/api/workspaces/$workspaceId/speech")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"text":"Hello.","speed":1.25}"""),
        ).andReturn().response

        assertThat(answer.status).isEqualTo(200)
        assertThat(sent().get("speed").asDouble()).isEqualTo(1.25)
    }

    private fun sent() = reader.readTree(asked.single())

    private fun speechModel(endpoint: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Reader", endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()

        return graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $providerId, name: "Reader", modelId: "tts-1", kind: SPEECH
               }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }

    private val reader = ObjectMapper()
}
