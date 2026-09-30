package io.mszymanski.orknux.server.model

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.connection.ConnectionProbe
import io.mszymanski.orknux.connector.connection.ConnectionProperties
import io.mszymanski.orknux.connector.model.ChatApi
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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An Azure OpenAI provider's chats, asked through the Responses API.
 *
 * Chat completions refuse a reasoning model its tools, so an Azure provider
 * speaks Responses unless it is set back. What crosses into [OpenAiChat] is the
 * same [ChatTurn] and [ToolSpec] either way, and what comes back is the same
 * outcome; these pin the half in between - the request each shape of turn
 * becomes, and what is read back out of a response and a stream.
 */
class OpenAiResponsesTest {

    private lateinit var server: HttpServer

    private val paths = CopyOnWriteArrayList<String>()
    private val bodies = CopyOnWriteArrayList<String>()

    private var answer: String = words("Hello.")
    private var streamed: List<String>? = null

    private val mapper = ObjectMapper()

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            paths += exchange.requestURI.path
            bodies += exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val frames = streamed
            if (frames == null) reply(exchange, "application/json", answer) else send(exchange, frames)
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `a provider on Responses sends to responses, and one on chat completions to chat completions`() {
        chat().complete(azure(), model(), listOf(ChatTurn("user", "Hi")), emptyList())
        answer = chatWords("Hello.")
        chat().complete(azure(ChatApi.CHAT_COMPLETIONS), model(), listOf(ChatTurn("user", "Hi")), emptyList())
        chat().complete(openAi(), model(), listOf(ChatTurn("user", "Hi")), emptyList())

        // Azure's v1 surface, which is where its Responses are served.
        assertThat(paths).containsExactly("/openai/v1/responses", "/chat/completions", "/chat/completions")
    }

    @Test
    fun `words go out as input and come back with their counts`() {
        val outcome = chat().complete(
            azure(),
            model(),
            listOf(ChatTurn("system", "Be brief."), ChatTurn("user", "Hi")),
            emptyList(),
        )

        val answered = outcome as OpenAiChat.Outcome.Answered
        assertThat(answered.said).isEqualTo("Hello.")
        assertThat(answered.calls).isEmpty()
        assertThat(answered.inputTokens).isEqualTo(4)
        assertThat(answered.outputTokens).isEqualTo(2)

        val sent = sent(0)
        assertThat(sent.path("model").asString()).isEqualTo("gpt-6-sol")
        // Stateless: nothing is kept at the provider, the conversation goes out whole.
        assertThat(sent.path("store").asBoolean(true)).isFalse()
        assertThat(sent.has("previous_response_id")).isFalse()
        assertThat(sent.path("input").toList().map { it.path("role").asString() + ":" + it.path("content").asString() })
            .containsExactly("system:Be brief.", "user:Hi")
        // Nothing about reasoning where the model has no effort: a model that does not reason refuses it.
        assertThat(sent.has("reasoning")).isFalse()
        assertThat(sent.has("include")).isFalse()
    }

    @Test
    fun `a reasoning effort asks for a summary and sealed reasoning, and the summary is the thinking`() {
        answer = """
            {"id":"r","object":"response","created_at":1,"model":"m","status":"completed","output":[
              {"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"First, the weather."},
                {"type":"summary_text","text":"Then the answer."}],"encrypted_content":"sealed"},
              {"type":"message","id":"m1","role":"assistant","status":"completed",
               "content":[{"type":"output_text","text":"Sunny.","annotations":[]}]}],
             "usage":{"input_tokens":10,"output_tokens":30,"total_tokens":40}}
        """.trimIndent()

        val outcome = chat().complete(azure(), model("high"), listOf(ChatTurn("user", "Weather?")), emptyList())

        val answered = outcome as OpenAiChat.Outcome.Answered
        assertThat(answered.said).isEqualTo("Sunny.")
        assertThat(answered.thought).isEqualTo("First, the weather.\n\nThen the answer.")
        assertThat(answered.reasoningItems).singleElement().asString().contains("sealed").contains("rs_1")
        assertThat(answered.outputTokens).isEqualTo(30)

        val sent = sent(0)
        assertThat(sent.path("reasoning").path("effort").asString()).isEqualTo("high")
        assertThat(sent.path("reasoning").path("summary").asString()).isEqualTo("auto")
        assertThat(sent.path("include").toList().map { it.asString() }).containsExactly("reasoning.encrypted_content")
    }

    @Test
    fun `a model asking for a tool is heard, and the tool is declared as not strict`() {
        answer = """
            {"id":"r","object":"response","created_at":1,"model":"m","status":"completed","output":[
              {"type":"function_call","id":"fc_1","call_id":"call_1","name":"weather",
               "arguments":"{\"city\":\"Warsaw\"}","status":"completed"}],
             "usage":{"input_tokens":4,"output_tokens":2,"total_tokens":6}}
        """.trimIndent()
        val one = model().apply { parallelToolCalls = false }

        val outcome = chat().complete(
            azure(),
            one,
            listOf(ChatTurn("user", "Weather?")),
            listOf(ToolSpec("weather", "Look it up", listOf(ToolParameterSpec("city", "Which city", true), ToolParameterSpec("unit", "C or F")))),
        )

        val answered = outcome as OpenAiChat.Outcome.Answered
        // The call id is what the answer to it will name.
        assertThat(answered.calls).containsExactly(ToolCall("call_1", "weather", """{"city":"Warsaw"}"""))

        val tool = sent(0).path("tools").single()
        assertThat(tool.path("type").asString()).isEqualTo("function")
        assertThat(tool.path("name").asString()).isEqualTo("weather")
        // Responses read an unset strict as true, and a strict schema refuses an optional parameter.
        assertThat(tool.path("strict").asBoolean(true)).isFalse()
        assertThat(tool.path("parameters").path("required").toList().map { it.asString() }).containsExactly("city")
        assertThat(sent(0).path("parallel_tool_calls").asBoolean(true)).isFalse()
    }

    @Test
    fun `an earlier round goes back as its reasoning, its calls and their outputs, paired by call id`() {
        val sealed = """{"id":"rs_1","type":"reasoning","summary":[],"encrypted_content":"sealed"}"""
        val turns = listOf(
            ChatTurn("user", "Weather?"),
            ChatTurn(
                "assistant",
                "Let me look.",
                asked = listOf(ToolCall("call_1", "weather", """{"city":"Warsaw"}""")),
                reasoningItems = listOf(sealed),
            ),
            ChatTurn("tool", "Raining.", respondingTo = "call_1"),
        )

        chat().complete(azure(), model("low"), turns, emptyList())

        val input = sent(0).path("input").toList()
        assertThat(input.map { it.path("type").asString("message") + "/" + it.path("role").asString("-") })
            .containsExactly("message/user", "reasoning/-", "message/assistant", "function_call/-", "function_call_output/-")
        assertThat(input[1].path("encrypted_content").asString()).isEqualTo("sealed")
        assertThat(input[3].path("call_id").asString()).isEqualTo("call_1")
        assertThat(input[3].path("arguments").asString()).isEqualTo("""{"city":"Warsaw"}""")
        assertThat(input[3].has("id")).isFalse()
        assertThat(input[4].path("call_id").asString()).isEqualTo("call_1")
        assertThat(input[4].path("output").asString()).isEqualTo("Raining.")
    }

    @Test
    fun `a picture is sent as an input image beside the words`() {
        val turns = listOf(ChatTurn("user", "What is this?", images = listOf("data:image/png;base64,AAAA")))

        chat().complete(azure(), model(), turns, emptyList())

        val content = sent(0).path("input").single().path("content").toList()
        assertThat(content.map { it.path("type").asString() }).containsExactly("input_text", "input_image")
        assertThat(content[1].path("image_url").asString()).isEqualTo("data:image/png;base64,AAAA")
    }

    @Test
    fun `a streamed answer arrives in pieces, thinking apart, and is whole at the end`() {
        streamed = listOf(
            event("""{"type":"response.created","sequence_number":0,"response":{"id":"r","object":"response","created_at":1,"model":"m","status":"in_progress","output":[]}}"""),
            event("""{"type":"response.reasoning_summary_text.delta","sequence_number":1,"item_id":"rs_1","output_index":0,"summary_index":0,"delta":"Thinking "}"""),
            event("""{"type":"response.reasoning_summary_text.delta","sequence_number":2,"item_id":"rs_1","output_index":0,"summary_index":0,"delta":"hard."}"""),
            event("""{"type":"response.output_text.delta","sequence_number":3,"item_id":"m1","output_index":1,"content_index":0,"delta":"Hel","logprobs":[]}"""),
            event("""{"type":"response.output_text.delta","sequence_number":4,"item_id":"m1","output_index":1,"content_index":0,"delta":"lo.","logprobs":[]}"""),
            event(
                """{"type":"response.completed","sequence_number":5,"response":{"id":"r","object":"response","created_at":1,"model":"m","status":"completed","output":[""" +
                    """{"type":"reasoning","id":"rs_1","summary":[{"type":"summary_text","text":"Thinking hard."}],"encrypted_content":"sealed"}],""" +
                    """"usage":{"input_tokens":4,"output_tokens":2,"total_tokens":6}}}""",
            ),
        )

        val said = mutableListOf<String>()
        val thought = mutableListOf<String>()
        val outcome = chat().stream(azure(), model("medium"), listOf(ChatTurn("user", "Hi")), emptyList(), { thought += it }) { said += it }

        val answered = outcome as OpenAiChat.Outcome.Answered
        assertThat(said).containsExactly("Hel", "lo.")
        assertThat(thought).containsExactly("Thinking ", "hard.")
        assertThat(answered.said).isEqualTo("Hello.")
        assertThat(answered.thought).isEqualTo("Thinking hard.")
        assertThat(answered.inputTokens).isEqualTo(4)
        assertThat(answered.outputTokens).isEqualTo(2)
        assertThat(answered.reasoningItems).singleElement().asString().contains("sealed")
        assertThat(sent(0).path("stream").asBoolean(false)).isTrue()
    }

    @Test
    fun `a call streamed a fragment at a time is put back together`() {
        streamed = listOf(
            event("""{"type":"response.output_item.added","sequence_number":1,"output_index":0,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"weather","arguments":"","status":"in_progress"}}"""),
            event("""{"type":"response.function_call_arguments.delta","sequence_number":2,"item_id":"fc_1","output_index":0,"delta":"{\"city\":"}"""),
            event("""{"type":"response.function_call_arguments.delta","sequence_number":3,"item_id":"fc_1","output_index":0,"delta":"\"Warsaw\"}"}"""),
            event("""{"type":"response.completed","sequence_number":4,"response":{"id":"r","object":"response","created_at":1,"model":"m","status":"completed","output":[],"usage":{"input_tokens":4,"output_tokens":2,"total_tokens":6}}}"""),
        )

        val outcome = chat().stream(azure(), model(), listOf(ChatTurn("user", "Weather?")), emptyList(), {}) {}

        val answered = outcome as OpenAiChat.Outcome.Answered
        assertThat(answered.calls).containsExactly(ToolCall("call_1", "weather", """{"city":"Warsaw"}"""))
    }

    @Test
    fun `a provider that ignores the streaming flag is still read`() {
        answer = words("Hello anyway.")

        val seen = mutableListOf<String>()
        val outcome = chat().stream(azure(), model(), listOf(ChatTurn("user", "Hi")), emptyList(), {}) { seen += it }

        val answered = outcome as OpenAiChat.Outcome.Answered
        assertThat(answered.said).isEqualTo("Hello anyway.")
        assertThat(seen).containsExactly("Hello anyway.")
        assertThat(bodies).hasSize(2)
        assertThat(sent(0).path("stream").asBoolean(false)).isTrue()
        assertThat(sent(1).has("stream")).isFalse()
    }

    @Test
    fun `a stream the provider failed is a failure in its own words`() {
        streamed = listOf(
            event("""{"type":"response.failed","sequence_number":1,"response":{"id":"r","object":"response","created_at":1,"model":"m","status":"failed","output":[],"error":{"code":"server_error","message":"The model fell over."}}}"""),
        )

        val outcome = chat().stream(azure(), model(), listOf(ChatTurn("user", "Hi")), emptyList(), {}) {}

        assertThat(outcome).isEqualTo(OpenAiChat.Outcome.Failed("The model fell over."))
    }

    @Test
    fun `an Azure resource is reached on its v1 surface, whichever way its address was written`() {
        fun base(endpoint: String) = ModelClients.responsesBase(
            ModelProvider(workspaceId = 1, name = "A", type = ProviderType.AZURE_OPENAI, endpoint = endpoint),
        )

        assertThat(base("https://acme.openai.azure.com")).isEqualTo("https://acme.openai.azure.com/openai/v1")
        assertThat(base("https://acme.openai.azure.com/")).isEqualTo("https://acme.openai.azure.com/openai/v1")
        assertThat(base("https://acme.openai.azure.com/openai")).isEqualTo("https://acme.openai.azure.com/openai/v1")
        assertThat(base("https://acme.openai.azure.com/openai/v1")).isEqualTo("https://acme.openai.azure.com/openai/v1")
        // A gateway's own path is kept in front of it.
        assertThat(base("https://gw.azure-api.net/ai")).isEqualTo("https://gw.azure-api.net/ai/openai/v1")
    }

    private fun sent(index: Int): JsonNode = mapper.readTree(bodies[index])

    private fun chat(): OpenAiChat {
        val router = ProxyRouter(ProxyRuleSource { emptyList() })
        val properties = ConnectionProperties()
        val probe = ModelProviderProbe(
            ConnectionProbe(properties, router, SecretCipher(TEST_KEY)),
            properties,
            ObjectMapper(),
            SecretReferences(SecretVariables { _, _ -> null }, SecretCipher(TEST_KEY)),
            router,
            ModelClients(router),
        )
        return OpenAiChat(ModelClients(router), probe, ObjectMapper())
    }

    private fun azure(api: ChatApi? = null) = ModelProvider(
        workspaceId = 1,
        name = "Azure",
        type = ProviderType.AZURE_OPENAI,
        endpoint = "http://${server.address.hostString}:${server.address.port}",
        secret = "azure-test",
        chatApi = api,
    )

    private fun openAi() = ModelProvider(
        workspaceId = 1,
        name = "OpenAI",
        type = ProviderType.OPENAI,
        endpoint = "http://${server.address.hostString}:${server.address.port}",
        secret = "sk-test",
    )

    private fun model(effort: String? = null) =
        LlmModel(providerId = 1, name = "Sol", modelId = "gpt-6-sol", kind = ModelKind.CHAT).apply { reasoningEffort = effort }

    private fun words(said: String): String =
        """{"id":"r","object":"response","created_at":1,"model":"m","status":"completed","output":[""" +
            """{"type":"message","id":"m1","role":"assistant","status":"completed",""" +
            """"content":[{"type":"output_text","text":"$said","annotations":[]}]}],""" +
            """"usage":{"input_tokens":4,"output_tokens":2,"total_tokens":6}}"""

    private fun chatWords(said: String): String =
        """{"id":"c","object":"chat.completion","created":1,"model":"m","choices":[{"index":0,"message":""" +
            """{"role":"assistant","content":"$said"},"finish_reason":"stop"}],""" +
            """"usage":{"prompt_tokens":4,"completion_tokens":2,"total_tokens":6}}"""

    private fun event(data: String): String {
        val type = mapper.readTree(data).path("type").asString()
        return "event: $type\ndata: $data"
    }

    private fun reply(exchange: HttpExchange, type: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", type)
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private fun send(exchange: HttpExchange, frames: List<String>) {
        reply(exchange, "text/event-stream", frames.joinToString("\n\n", postfix = "\n\n"))
    }

    private companion object {
        const val TEST_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
    }
}
