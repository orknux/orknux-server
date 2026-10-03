package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelChatClient
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
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/**
 * A 429's Retry-After winning over a node's own retry policy. Issue #426.
 *
 * Azure often answers 429 and names a time to come back. The client obeys it
 * where the model accepts it: it waits exactly that long and asks again, below
 * any node's retry - the node never sees the 429 if this recovers. Turned off,
 * the 429 is handed straight back as a failure for the node to decide on.
 *
 * Driven against a stub that answers 429 once and then succeeds, through the SDK
 * path an OpenAI-shaped provider takes, which is the path Azure is spoken on.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class RetryAfterTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val chat: ModelChatClient,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer
    private val calls = AtomicInteger(0)

    @BeforeEach
    fun reset() {
        models.deleteAll()
        providers.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        calls.set(0)
    }

    @AfterEach
    fun stop() = server.stop(0)

    private val asked = listOf(ChatTurn(role = "user", content = "Two and two?"))

    @Test
    fun `a 429 with a Retry-After is waited out and the call retried`() {
        val modelId = openAi(rateLimitedOnce(retryAfter = "1"))

        val answer = chat.complete(modelId, asked)

        // It recovered on its own: the caller sees the answer, never the 429.
        assertThat(answer).isInstanceOf(ChatCompletion.Answered::class.java)
        assertThat((answer as ChatCompletion.Answered).content).isEqualTo("Four.")
        // Which took the two requests: the one that was turned away, and the one
        // after the wait.
        assertThat(calls.get()).isEqualTo(2)
    }

    @Test
    fun `a provider that does not accept Retry-After hands the 429 back`() {
        val modelId = openAi(rateLimitedOnce(retryAfter = "1"))
        val provider = requireNotNull(providers.findByIdOrNull(model(modelId).providerId))
        provider.acceptRetryAfter = false
        providers.save(provider)

        val answer = chat.complete(modelId, asked)

        // No retry: the 429 is the answer, for the node's policy to decide on.
        assertThat(answer).isInstanceOf(ChatCompletion.Failed::class.java)
        assertThat((answer as ChatCompletion.Failed).permanent).isFalse()
        assertThat(calls.get()).isEqualTo(1)
    }

    /**
     * Azure rate limits a streaming call after answering 200: the refusal is an
     * error event inside the stream, with no Retry-After and no time named.
     * Read as a 200 it was a settled refusal and the agent step failed on it in
     * production ("200: Your requests to gpt-6-sol ... have exceeded token rate
     * limit"); it is a rate limit, waited out on a short backoff and asked again.
     */
    @Test
    fun `a rate limit inside a 200 stream is waited out and the call retried`() {
        val modelId = openAi(rateLimitedInStreamOnce())

        val answer = chat.stream(modelId, asked) { }

        assertThat(answer).isInstanceOf(ChatCompletion.Answered::class.java)
        assertThat((answer as ChatCompletion.Answered).content).isEqualTo("Four.")
        assertThat(calls.get()).isEqualTo(2)
    }

    private fun rateLimitedInStreamOnce(): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val first = calls.getAndIncrement() == 0
            val events = if (first) {
                """data: {"error":{"code":"429","message":"Your requests to stub for stub in polandcentral """ +
                    """have exceeded token rate limit."}}""" + "\n\n"
            } else {
                """data: {"id":"c","object":"chat.completion.chunk","created":1,"model":"stub",""" +
                    """"choices":[{"index":0,"delta":{"role":"assistant","content":"Four."},"finish_reason":null}]}""" +
                    "\n\n" +
                    """data: {"id":"c","object":"chat.completion.chunk","created":1,"model":"stub",""" +
                    """"choices":[{"index":0,"delta":{},"finish_reason":"stop"}],""" +
                    """"usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}}""" + "\n\n" +
                    "data: [DONE]\n\n"
            }
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            val bytes = events.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}"
    }

    private fun model(id: Long) = requireNotNull(models.findByIdOrNull(id))

    /** Answers 429 with a Retry-After the first time, then a plain answer. */
    private fun rateLimitedOnce(retryAfter: String): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            exchange.responseHeaders.add("Content-Type", "application/json")
            val status: Int
            val body: String
            if (calls.getAndIncrement() == 0) {
                exchange.responseHeaders.add("Retry-After", retryAfter)
                status = 429
                body = """{"error":{"message":"slow down"}}"""
            } else {
                status = 200
                body = """{"choices":[{"message":{"role":"assistant","content":"Four."}}],""" +
                    """"usage":{"prompt_tokens":3,"completion_tokens":1}}"""
            }
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}"
    }

    private fun openAi(endpoint: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub", endpoint: "$endpoint",
                 type: OPENAI, secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        return graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT
               }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }
}
