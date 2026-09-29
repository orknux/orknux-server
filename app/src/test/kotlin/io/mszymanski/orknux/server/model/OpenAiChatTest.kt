package io.mszymanski.orknux.server.model

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.connection.ConnectionProbe
import io.mszymanski.orknux.connector.connection.ConnectionProperties
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.LlmModel
import io.mszymanski.orknux.connector.model.ModelClients
import io.mszymanski.orknux.connector.model.ModelKind
import io.mszymanski.orknux.connector.model.ModelProvider
import io.mszymanski.orknux.connector.model.ModelProviderProbe
import io.mszymanski.orknux.connector.model.OpenAiChat
import io.mszymanski.orknux.connector.model.ProviderType
import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.connector.proxy.ProxyRuleSource
import io.mszymanski.orknux.connector.security.SecretCipher
import io.mszymanski.orknux.connector.security.SecretReferences
import io.mszymanski.orknux.connector.security.SecretVariables
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A chat spoken through the SDK says and hears what the hand-built one did.
 *
 * The point of these is the boundary rather than the wire. What crosses into
 * [OpenAiChat] is this application's [ChatTurn] and [ToolSpec]; what comes back
 * is its own outcome. Both were built by hand out of Jackson trees until the SDK
 * replaced the middle, and every screen above depends on the two ends being
 * unchanged - so each test drives one of the shapes that actually occur in a
 * round: words, a call, an answer to a call, a picture, and a stream.
 *
 * The request bodies are kept because the shape sent is half of what broke: a
 * tool result that does not name the call it answers, or a picture sent as a
 * string, is a request a provider rejects in a way that reads as a model
 * problem.
 */
class OpenAiChatTest {

    private lateinit var server: HttpServer

    /** Every request body the stub received, in order. */
    private val bodies = CopyOnWriteArrayList<String>()

    /** What the stub answers next. */
    private var answer: String = words("Hello.")

    /** Answered as an event stream when set. */
    private var streamed: List<String>? = null

    @BeforeEach
    fun start() {
        bodies.clear()
        streamed = null
        answer = words("Hello.")

        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            bodies += exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val frames = streamed
            if (frames == null) reply(exchange, "application/json", answer) else send(exchange, frames)
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `words go out and come back`() {
        val outcome = chat().complete(provider(), model(), listOf(ChatTurn("user", "Hi")), emptyList())

        val answered = outcome as OpenAiChat.Outcome.Answered
        assertThat(answered.said).isEqualTo("Hello.")
        assertThat(answered.calls).isEmpty()
        assertThat(bodies).singleElement().asString().contains(""""content":"Hi"""")
    }

    @Test
    fun `the counts a provider reports are carried out`() {
        val outcome = chat().complete(provider(), model(), listOf(ChatTurn("user", "Hi")), emptyList())

        val answered = outcome as OpenAiChat.Outcome.Answered
        assertThat(answered.inputTokens).isEqualTo(4)
        assertThat(answered.outputTokens).isEqualTo(2)
    }

    @Test
    fun `a model asking for a tool is heard`() {
        answer = """
            {"id":"c","object":"chat.completion","created":1,"model":"m","choices":[{"index":0,"message":
            {"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":
            {"name":"weather","arguments":"{\"city\":\"Warsaw\"}"}}]},"finish_reason":"tool_calls"}],
            "usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}
        """.trimIndent()

        val outcome = chat().complete(
            provider(),
            model(),
            listOf(ChatTurn("user", "Weather?")),
            listOf(ToolSpec("weather", "Look it up", listOf(ToolParameterSpec("city", "Which city", true)))),
        )

        val answered = outcome as OpenAiChat.Outcome.Answered
        assertThat(answered.calls).containsExactly(ToolCall("call_1", "weather", """{"city":"Warsaw"}"""))
        // Declared as a function with its schema, or the model cannot choose it.
        assertThat(bodies.single()).contains(""""name":"weather"""").contains(""""required":["city"]""")
    }

    /**
     * A tool call cut off at the output limit is handed on rather than failing
     * the whole answer. Issue #528: it used to end the turn permanently over one
     * truncated call, while the same answer streamed went through. The agent
     * loop answers it as not run and sends it back as `{}` - AgentToolCallTest
     * pins that half.
     */
    @Test
    fun `a tool call with truncated arguments is handed on, not a failure of the answer`() {
        answer = """
            {"id":"c","object":"chat.completion","created":1,"model":"m","choices":[{"index":0,"message":
            {"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function","function":
            {"name":"weather","arguments":"{\"city\":\"War"}}]},"finish_reason":"length"}],
            "usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}
        """.trimIndent()

        val outcome = chat().complete(
            provider(),
            model(),
            listOf(ChatTurn("user", "Weather?")),
            listOf(ToolSpec("weather", "Look it up", listOf(ToolParameterSpec("city", "Which city", true)))),
        )

        val answered = outcome as OpenAiChat.Outcome.Answered
        assertThat(answered.calls).containsExactly(ToolCall("call_1", "weather", """{"city":"War"""))
    }

    /**
     * One call per reply, when the model says so - and only beside tools.
     * Issue #530: nothing was ever sent, so a local server's grammar allowed
     * unlimited calls per reply, and a looping model filled its whole output
     * with the same few.
     */
    @Test
    fun `a model limited to one call per reply says so beside its tools`() {
        answer = words("ok")
        val one = model().apply { parallelToolCalls = false }
        val weather = listOf(ToolSpec("weather", "Look it up"))

        chat().complete(provider(), one, listOf(ChatTurn("user", "Weather?")), weather)
        chat().complete(provider(), one, listOf(ChatTurn("user", "Hello")), emptyList())
        chat().complete(provider(), model(), listOf(ChatTurn("user", "Weather?")), weather)

        assertThat(bodies[0]).contains(""""parallel_tool_calls":false""")
        // Not in a request that offers no tools: a provider refuses it there.
        assertThat(bodies[1]).doesNotContain("parallel_tool_calls")
        // And not at all where the model left it to the provider.
        assertThat(bodies[2]).doesNotContain("parallel_tool_calls")
    }

    /**
     * What a model is set to pick its words by, and nothing where it is not.
     * Issue #533: nothing was ever sent, so a local model ran on whatever its
     * file said - temperature 1.0, no repeat penalty - without anybody choosing.
     */
    @Test
    fun `a model's sampling is sent where it is set, and only there`() {
        answer = words("ok")
        val tuned = model().apply {
            temperature = 0.2
            topP = 0.9
            topK = 40
            minP = 0.05
            repeatPenalty = 1.05
        }

        chat().complete(provider(), tuned, listOf(ChatTurn("user", "Hello")), emptyList())
        chat().complete(provider(), model(), listOf(ChatTurn("user", "Hello")), emptyList())

        assertThat(bodies[0]).contains(""""temperature":0.2""").contains(""""top_p":0.9""")
            .contains(""""top_k":40""").contains(""""min_p":0.05""").contains(""""repeat_penalty":1.05""")
        // Left alone, none of it: a hosted model refuses the ones it does not know.
        assertThat(bodies[1]).doesNotContain("temperature").doesNotContain("top_k").doesNotContain("repeat_penalty")
    }

    /**
     * How hard an Azure reasoning deployment thinks, on both paths, and nothing
     * where the model leaves it to the deployment: a model that is not a
     * reasoning one refuses a request carrying the field.
     */
    @Test
    fun `a reasoning effort is sent where it is set, streamed or not, and only there`() {
        answer = words("ok")
        val azure = azureProvider()
        val thoughtful = model().apply { reasoningEffort = "high" }

        chat().complete(azure, thoughtful, listOf(ChatTurn("user", "Hello")), emptyList())
        chat().complete(azure, model(), listOf(ChatTurn("user", "Hello")), emptyList())
        streamed = listOf(piece("""{"content":"ok"}"""), "data: [DONE]")
        chat().stream(azure, thoughtful, listOf(ChatTurn("user", "Hello")), emptyList(), {}) {}
        chat().stream(azure, model(), listOf(ChatTurn("user", "Hello")), emptyList(), {}) {}

        assertThat(bodies).hasSize(4)
        assertThat(bodies[0]).contains(""""reasoning_effort":"high"""")
        assertThat(bodies[1]).doesNotContain("reasoning_effort")
        assertThat(bodies[2]).contains(""""reasoning_effort":"high"""").contains("\"stream\":true")
        assertThat(bodies[3]).doesNotContain("reasoning_effort")
    }

    /**
     * A stored setting the provider does not read stays stored and is not sent.
     * A model moved from a llama.cpp server to Azure keeps its top-k, min-p and
     * repeat penalty; Azure refuses a request carrying them, and Ollama's `/v1`
     * silently drops them. See ChatParameters.
     */
    @Test
    fun `a setting the provider does not take is not sent, even where it is stored`() {
        answer = words("ok")
        val carried = model().apply {
            temperature = 0.2
            topP = 0.9
            topK = 40
            minP = 0.05
            repeatPenalty = 1.05
            reasoningEffort = "low"
        }

        chat().complete(azureProvider(), carried, listOf(ChatTurn("user", "Hello")), emptyList())
        streamed = listOf(piece("""{"content":"ok"}"""), "data: [DONE]")
        chat().stream(azureProvider(), carried, listOf(ChatTurn("user", "Hello")), emptyList(), {}) {}
        streamed = null
        chat().complete(ollamaProvider(), carried, listOf(ChatTurn("user", "Hello")), emptyList())

        bodies.forEach { body ->
            assertThat(body).contains(""""temperature":0.2""").contains(""""top_p":0.9""")
                .doesNotContain("top_k").doesNotContain("min_p").doesNotContain("repeat_penalty")
        }
        assertThat(bodies[0]).contains(""""reasoning_effort":"low"""")
        assertThat(bodies[1]).contains(""""reasoning_effort":"low"""")
        // Ollama takes no reasoning effort from this product either.
        assertThat(bodies[2]).doesNotContain("reasoning_effort")
    }

    @Test
    fun `an answer to a call names the call it answers`() {
        val turns = listOf(
            ChatTurn("user", "Weather?"),
            ChatTurn("assistant", "", asked = listOf(ToolCall("call_1", "weather", "{}"))),
            ChatTurn("tool", "Raining.", respondingTo = "call_1"),
        )

        chat().complete(provider(), model(), turns, emptyList())

        // Unpaired, the model cannot tell which of its calls was answered.
        assertThat(bodies.single()).contains(""""tool_call_id":"call_1"""").contains(""""role":"tool"""")
    }

    @Test
    fun `a picture is sent as its own part beside the words`() {
        val turns = listOf(ChatTurn("user", "What is this?", images = listOf("data:image/png;base64,AAAA")))

        chat().complete(provider(), model(), turns, emptyList())

        assertThat(bodies.single()).contains(""""type":"image_url"""").contains("data:image/png;base64,AAAA")
    }

    @Test
    fun `a streamed answer arrives in pieces and is whole at the end`() {
        streamed = listOf(
            piece("""{"content":"Hel"}"""),
            piece("""{"content":"lo."}"""),
            """data: {"id":"c","object":"chat.completion.chunk","created":1,"model":"m","choices":[],""" +
                """"usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}""",
            "data: [DONE]",
        )

        val seen = mutableListOf<String>()
        val outcome = chat().stream(provider(), model(), listOf(ChatTurn("user", "Hi")), emptyList(), {}) { seen += it }

        val answered = outcome as OpenAiChat.Outcome.Answered
        assertThat(seen).containsExactly("Hel", "lo.")
        assertThat(answered.said).isEqualTo("Hello.")
        // The counts a stream sends only when they were asked for.
        assertThat(answered.inputTokens).isEqualTo(4)
        assertThat(answered.outputTokens).isEqualTo(2)
    }

    /**
     * A choice with no delta in it, the way Azure OpenAI sends one.
     *
     * Its content filter reports on a choice of its own - `content_filter_results`
     * and nothing said - and the SDK calls `delta` required, so reading it threw
     * "`delta` is not set" and every streamed turn behind Azure failed with it.
     */
    @Test
    fun `a streamed choice with no delta, as Azure's filter sends, is passed over`() {
        streamed = listOf(
            """data: {"id":"","object":"","created":0,"model":"","choices":[],""" +
                """"prompt_filter_results":[{"prompt_index":0,"content_filter_results":{}}]}""",
            piece("""{"content":"Hel"}"""),
            """data: {"id":"c","object":"chat.completion.chunk","created":1,"model":"m",""" +
                """"choices":[{"index":0,"finish_reason":null,"content_filter_results":{"hate":{"filtered":false,"severity":"safe"}}}]}""",
            piece("""{"content":"lo."}"""),
            "data: [DONE]",
        )

        val seen = mutableListOf<String>()
        val outcome = chat().stream(provider(), model(), listOf(ChatTurn("user", "Hi")), emptyList(), {}) { seen += it }

        val answered = outcome as OpenAiChat.Outcome.Answered
        assertThat(seen).containsExactly("Hel", "lo.")
        assertThat(answered.said).isEqualTo("Hello.")
    }

    /**
     * A provider that was asked to stream and answered with a whole body.
     *
     * `stream: true` is a request, not a guarantee: a local server, or a proxy
     * in front of one, may answer with the completion in one ordinary JSON
     * body. Read for frames that are not there, that came back as an empty
     * answer - and empty reaches the run as "the provider answered with no
     * message", a silence this reader invented rather than one the model
     * produced. So it is asked once more without streaming.
     */
    @Test
    fun `a provider that ignores the streaming flag is still read`() {
        // `streamed` stays null, so the stub answers with ordinary JSON.
        answer = words("Hello anyway.")

        val seen = mutableListOf<String>()
        val outcome = chat().stream(provider(), model(), listOf(ChatTurn("user", "Hi")), emptyList(), {}) { seen += it }

        val answered = outcome as OpenAiChat.Outcome.Answered
        assertThat(answered.said).isEqualTo("Hello anyway.")
        // The watcher is told once, because that is how it arrived: one piece,
        // which is what the provider sent.
        assertThat(seen).containsExactly("Hello anyway.")
        // Twice: the streaming attempt, then the one that was answered.
        assertThat(bodies).hasSize(2)
        assertThat(bodies.first()).contains("\"stream\":true")
        assertThat(bodies.last()).doesNotContain("\"stream\":true")
    }

    @Test
    fun `a call streamed a fragment at a time is put back together`() {
        streamed = listOf(
            piece("""{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"weather","arguments":""}}]}"""),
            piece("""{"tool_calls":[{"index":0,"function":{"arguments":"{\"city\":"}}]}"""),
            piece("""{"tool_calls":[{"index":0,"function":{"arguments":"\"Warsaw\"}"}}]}"""),
            "data: [DONE]",
        )

        val outcome = chat().stream(provider(), model(), listOf(ChatTurn("user", "Weather?")), emptyList(), {}) {}

        val answered = outcome as OpenAiChat.Outcome.Answered
        assertThat(answered.calls).containsExactly(ToolCall("call_1", "weather", """{"city":"Warsaw"}"""))
    }

    private fun chat(): OpenAiChat {
        val router = ProxyRouter(ProxyRuleSource { emptyList() })
        val properties = ConnectionProperties()
        val probe = ModelProviderProbe(
            ConnectionProbe(properties, router, SecretCipher(TEST_KEY)),
            properties,
            ObjectMapper(),
            SecretReferences(SecretVariables { _, _ -> null }, SecretCipher(TEST_KEY)),
            router,
            ModelClients(router)
        )
        return OpenAiChat(ModelClients(router), probe, ObjectMapper())
    }

    private fun provider() = ModelProvider(
        workspaceId = 1,
        name = "Provider",
        type = ProviderType.OPENAI,
        endpoint = "http://${server.address.hostString}:${server.address.port}",
        secret = "sk-test",
    )

    private fun azureProvider() = ModelProvider(
        workspaceId = 1,
        name = "Azure",
        type = ProviderType.AZURE_OPENAI,
        endpoint = "http://${server.address.hostString}:${server.address.port}",
        apiVersion = "2024-10-21",
        deploymentName = "o4-mini",
        secret = "azure-test",
    )

    private fun ollamaProvider() = ModelProvider(
        workspaceId = 1,
        name = "Ollama",
        type = ProviderType.OLLAMA,
        endpoint = "http://${server.address.hostString}:${server.address.port}",
        secret = "ollama",
    )

    private fun model() = LlmModel(providerId = 1, name = "Model", modelId = "gpt-4o", kind = ModelKind.CHAT)

    private fun words(said: String): String =
        """{"id":"c","object":"chat.completion","created":1,"model":"m","choices":[{"index":0,"message":""" +
            """{"role":"assistant","content":"$said"},"finish_reason":"stop"}],""" +
            """"usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}"""

    private fun piece(delta: String): String =
        """data: {"id":"c","object":"chat.completion.chunk","created":1,"model":"m",""" +
            """"choices":[{"index":0,"delta":$delta,"finish_reason":null}]}"""

    private fun reply(exchange: HttpExchange, type: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", type)
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private fun send(exchange: HttpExchange, frames: List<String>) {
        val body = frames.joinToString("\n\n", postfix = "\n\n")
        reply(exchange, "text/event-stream", body)
    }

    private companion object {
        const val TEST_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    }
}
