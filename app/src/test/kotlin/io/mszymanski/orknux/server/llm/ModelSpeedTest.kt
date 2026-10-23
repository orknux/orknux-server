package io.mszymanski.orknux.server.llm

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
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

/**
 * The speeds a provider reports, kept per call and averaged on the session and
 * on the model.
 *
 * The stub answers as llama.cpp's server does: a `timings` object at the top of
 * a plain completion, and on the last frame of a stream - the usage frame, with
 * no choices - when usage was asked for. A model called `plain` is answered as
 * OpenAI or vLLM answer, with usage and no timings, and has no speed at all
 * rather than one worked out from how long the stub took.
 *
 * The calls go through the real client and are then added to the session the
 * way `AgentConversation` adds every round, through [SessionSpend].
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ModelSpeedTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val client: ModelChatClient,
    @Autowired val spend: SessionSpend,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val callRows: SessionModelCallRepository,
    @Autowired val settings: io.mszymanski.orknux.server.attachment.InstallationSettings,
) {

    private var workspaceId: Long = 0
    private var session: Long = 0
    private lateinit var server: HttpServer

    @BeforeEach
    fun start() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "speed-${System.nanoTime()}")).id)
        session = recorder.open(workspaceId, "test", "speed-${System.nanoTime()}")
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        listOf("/chat/completions", "/v1/chat/completions").forEach { path -> server.createContext(path, ::answer) }
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
        graphQlTester.document("""mutation { setTunable(key: "model.speed.calls") { value } }""").execute()
    }

    @Test
    fun `a plain completion's timings are kept with the call, as the provider reported them`() {
        val model = model("LLAMA_CPP", "fast")

        val answer = client.complete(model, listOf(ChatTurn("user", "Hi")))
        assertThat(answer).isInstanceOf(ChatCompletion.Answered::class.java)
        spend.add(session, model, answer)

        val call = Under(calls(), "llmSession.modelCalls[0]")
        call.path("promptTokens").entity(Double::class.java).isEqualTo(1000.0)
        call.path("promptMillis").entity(Double::class.java).isEqualTo(500.0)
        call.path("promptTokensPerSecond").entity(Double::class.java).isEqualTo(2000.0)
        call.path("generatedTokens").entity(Double::class.java).isEqualTo(40.0)
        call.path("generatedMillis").entity(Double::class.java).isEqualTo(1000.0)
        call.path("generatedTokensPerSecond").entity(Double::class.java).isEqualTo(40.0)
        call.path("inputTokens").entity(Double::class.java).isEqualTo(1200.0)
        call.path("outputTokens").entity(Double::class.java).isEqualTo(40.0)
    }

    @Test
    fun `a stream's timings are read off its last frame`() {
        val model = model("LLAMA_CPP", "fast")

        val pieces = StringBuilder()
        val answer = client.stream(model, listOf(ChatTurn("user", "Hi"))) { pieces.append(it) }
        assertThat(answer).isInstanceOf(ChatCompletion.Answered::class.java)
        assertThat(pieces.toString()).isEqualTo("Hello.")
        spend.add(session, model, answer)

        val call = Under(calls(), "llmSession.modelCalls[0]")
        call.path("promptTokens").entity(Double::class.java).isEqualTo(3000.0)
        call.path("promptTokensPerSecond").entity(Double::class.java).isEqualTo(3000.0)
        call.path("generatedTokens").entity(Double::class.java).isEqualTo(160.0)
        call.path("generatedTokensPerSecond").entity(Double::class.java).isEqualTo(80.0)
    }

    /**
     * Every token over every millisecond, not a mean of the two rates: 4000
     * prompt tokens over 1.5 s is 2667/s where the mean of 2000 and 3000 would
     * be 2500, and 200 generated over 3 s is 66.7/s where the mean is 60. A call
     * without timings in the same session does not move either.
     */
    @Test
    fun `the session and the model average by tokens over the calls that reported`() {
        val fast = model("LLAMA_CPP", "fast")
        val plain = model("OPENAI", "plain")
        spend.add(session, fast, client.complete(fast, listOf(ChatTurn("user", "Hi"))))
        spend.add(session, fast, client.stream(fast, listOf(ChatTurn("user", "Hi"))) {})
        spend.add(session, plain, client.complete(plain, listOf(ChatTurn("user", "Hi"))))

        val speed = graphQlTester.document(
            """{ llmSession(id: $session) { speed { calls promptTokens promptMillis generatedTokens generatedMillis
                 promptTokensPerSecond generatedTokensPerSecond } } }""",
        ).execute().let { Under(it, "llmSession.speed") }
        speed.path("calls").entity(Int::class.java).isEqualTo(2)
        speed.path("promptTokens").entity(Double::class.java).isEqualTo(4000.0)
        speed.path("generatedMillis").entity(Double::class.java).isEqualTo(3000.0)
        assertThat(speed.path("promptTokensPerSecond").entity(Double::class.java).get()).isCloseTo(2666.67, within(0.01))
        assertThat(speed.path("generatedTokensPerSecond").entity(Double::class.java).get()).isCloseTo(66.67, within(0.01))

        // All three calls are listed; only the timed ones when asked for those.
        calls().path("llmSession.modelCalls[*].id").entityList(Long::class.java).hasSize(3)
        graphQlTester.document("""{ llmSession(id: $session) { modelCalls(timedOnly: true) { id } } }""").execute()
            .path("llmSession.modelCalls[*].id").entityList(Long::class.java).hasSize(2)

        val modelSpeed = modelSpeed(fast)
        modelSpeed.path("calls").entity(Int::class.java).isEqualTo(2)
        assertThat(modelSpeed.path("promptTokensPerSecond").entity(Double::class.java).get()).isCloseTo(2666.67, within(0.01))
        assertThat(modelSpeed.path("generatedTokensPerSecond").entity(Double::class.java).get()).isCloseTo(66.67, within(0.01))

        // The model's window is Admin Settings': at one call, it is the newest call alone - the stream.
        graphQlTester.document("""mutation { setTunable(key: "model.speed.calls", value: 1) { value } }""").execute()
            .path("setTunable.value").entity(Int::class.java).isEqualTo(1)
        modelSpeed(fast).path("calls").entity(Int::class.java).isEqualTo(1)
        modelSpeed(fast).path("promptTokensPerSecond").entity(Double::class.java).isEqualTo(3000.0)
        modelSpeed(fast).path("generatedTokensPerSecond").entity(Double::class.java).isEqualTo(80.0)
    }

    @Test
    fun `a provider that reports no timings has no speed, on the call, the session or the model`() {
        val plain = model("OPENAI", "plain")
        spend.add(session, plain, client.complete(plain, listOf(ChatTurn("user", "Hi"))))
        spend.add(session, plain, client.stream(plain, listOf(ChatTurn("user", "Hi"))) {})

        val call = Under(calls(), "llmSession.modelCalls[0]")
        call.path("inputTokens").entity(Double::class.java).isEqualTo(9.0)
        call.path("promptTokensPerSecond").valueIsNull()
        call.path("generatedTokensPerSecond").valueIsNull()
        call.path("promptTokens").valueIsNull()
        graphQlTester.document("""{ llmSession(id: $session) { speed { calls } } }""").execute()
            .path("llmSession.speed").valueIsNull()
        graphQlTester.document("""{ model(id: $plain) { speed { calls } } }""").execute()
            .path("model.speed").valueIsNull()
    }

    /**
     * A session's calls go with it, and with its workspace: on SQLite as well,
     * where a cascade is a foreign key the engine has to be holding.
     */
    @Test
    fun `removing a session or its workspace takes its calls with it`() {
        val fast = model("LLAMA_CPP", "fast")
        spend.add(session, fast, client.complete(fast, listOf(ChatTurn("user", "Hi"))))
        assertThat(callRows.findAll().filter { it.sessionId == session }).hasSize(1)

        val was = settings.sessionsRemovable()
        settings.setSessionsRemovable(true, "alice")
        try {
            graphQlTester.document("mutation { removeLlmSession(id: $session) }").execute()
                .path("removeLlmSession").entity(Boolean::class.java).isEqualTo(true)
        } finally {
            settings.setSessionsRemovable(was, "alice")
        }
        assertThat(callRows.findAll().filter { it.sessionId == session }).isEmpty()

        val other = recorder.open(workspaceId, "test", "speed-${System.nanoTime()}")
        spend.add(other, fast, client.complete(fast, listOf(ChatTurn("user", "Hi"))))
        graphQlTester.document("mutation { deleteWorkspace(id: $workspaceId) }").execute()
            .path("deleteWorkspace").entity(Boolean::class.java).isEqualTo(true)
        assertThat(callRows.findAll().filter { it.sessionId == other }).isEmpty()
    }

    /* ---------------------------------------------------------- the fixtures */

    private fun calls() = graphQlTester.document(
        """{ llmSession(id: $session) { modelCalls { id inputTokens outputTokens millis promptTokens promptMillis
             promptTokensPerSecond generatedTokens generatedMillis generatedTokensPerSecond } } }""",
    ).execute()

    private fun modelSpeed(model: Long) = graphQlTester.document(
        """{ model(id: $model) { speed { calls promptTokensPerSecond generatedTokensPerSecond } } }""",
    ).execute().let { Under(it, "model.speed") }

    /** Paths under one object of a response, since a response's own paths always start at `data`. */
    private class Under(val response: org.springframework.graphql.test.tester.GraphQlTester.Response, val at: String) {
        fun path(field: String) = response.path("$at.$field")
    }

    private fun model(type: String, modelId: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "$type ${System.nanoTime()}", type: $type,
                 endpoint: "http://${server.address.hostString}:${server.address.port}", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        return graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $providerId, name: "$modelId ${System.nanoTime()}", modelId: "$modelId", kind: CHAT
               }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }

    /** llama.cpp's shapes for the model `fast`; OpenAI's, with no timings, for `plain`. */
    private fun answer(exchange: HttpExchange) {
        val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }.replace(" ", "")
        val timed = body.contains("\"model\":\"fast\"")
        val streamed = body.contains("\"stream\":true")
        val text = when {
            streamed -> streamOf(timed)
            timed -> """{"id":"c1","object":"chat.completion","created":1,"model":"fast",""" +
                """"choices":[{"index":0,"message":{"role":"assistant","content":"Hi."},"finish_reason":"stop"}],""" +
                """"usage":{"prompt_tokens":1200,"completion_tokens":40,"total_tokens":1240},""" +
                """"timings":{"cache_n":200,"prompt_n":1000,"prompt_ms":500.0,"prompt_per_token_ms":0.5,""" +
                """"prompt_per_second":2000.0,"predicted_n":40,"predicted_ms":1000.0,""" +
                """"predicted_per_token_ms":25.0,"predicted_per_second":40.0}}"""
            else -> """{"id":"c1","object":"chat.completion","created":1,"model":"plain",""" +
                """"choices":[{"index":0,"message":{"role":"assistant","content":"Hi."},"finish_reason":"stop"}],""" +
                """"usage":{"prompt_tokens":9,"completion_tokens":2,"total_tokens":11}}"""
        }
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", if (streamed) "text/event-stream" else "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    /**
     * A stream as llama.cpp writes one with `include_usage`: the words, the
     * frame that says why it stopped, then a frame with no choices carrying the
     * usage - and, for llama.cpp, the timings, which go on the last frame.
     */
    private fun streamOf(timed: Boolean): String {
        val head = """"id":"c2","object":"chat.completion.chunk","created":1,"model":"${if (timed) "fast" else "plain"}""""
        val timings = if (!timed) "" else {
            ""","timings":{"cache_n":0,"prompt_n":3000,"prompt_ms":1000.0,"prompt_per_token_ms":0.333,""" +
                """"prompt_per_second":3000.0,"predicted_n":160,"predicted_ms":2000.0,""" +
                """"predicted_per_token_ms":12.5,"predicted_per_second":80.0}"""
        }
        val usage = if (timed) """{"prompt_tokens":3000,"completion_tokens":160,"total_tokens":3160}"""
        else """{"prompt_tokens":9,"completion_tokens":2,"total_tokens":11}"""
        return listOf(
            """{$head,"choices":[{"index":0,"delta":{"role":"assistant","content":"Hel"},"finish_reason":null}]}""",
            """{$head,"choices":[{"index":0,"delta":{"content":"lo."},"finish_reason":null}]}""",
            """{$head,"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
            """{$head,"choices":[],"usage":$usage$timings}""",
        ).joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"
    }
}
