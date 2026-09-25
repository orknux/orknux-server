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
     * A tool call cut off at the output limit arrives with truncated JSON for
     * its arguments. That must come back as a cause somebody can act on - raise
     * the limit - rather than a JSON parse error downstream. Issue #392.
     */
    @Test
    fun `a tool call with truncated arguments is a clear failure, not a parse error`() {
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

        val failed = outcome as OpenAiChat.Outcome.Failed
        assertThat(failed.reason).contains("weather").contains("cut off at the output limit")
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
