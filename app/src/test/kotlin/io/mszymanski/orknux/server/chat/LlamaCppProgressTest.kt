package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.connector.model.ModelUsageRepository
import io.mszymanski.orknux.connector.model.ProviderType
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.memory.ChatMemoryRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A llama.cpp provider reports how far it has read the prompt, and the chat says
 * so. Issue #627.
 *
 * On a long session whose cached prefix no longer matched, llama.cpp read a
 * 208k-token prompt for minutes, and the chat and the session page showed
 * nothing at all - only the server's own log said it was working.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class LlamaCppProgressTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val streaming: ChatStreamAPI,
    @Autowired val sessions: ChatSessionRepository,
    @Autowired val history: ChatMemoryRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val usage: ModelUsageRepository,
    @Autowired val llmSessions: LlmSessionRepository,
    @Autowired val llmEvents: LlmSessionEventRepository,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer

    /** Every body sent to the stub, so what a request asked for can be read off it. */
    private val bodies = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun reset() {
        sessions.findAll().forEach { history.deleteByConversationId(it.conversationId) }
        sessions.deleteAll()
        llmEvents.deleteAll()
        llmSessions.deleteAll()
        agents.deleteAll()
        usage.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "llama")).id)
        bodies.clear()
        serve()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `a llama cpp provider is asked for its reading, and the chat is told it before the answer`() {
        val chatId = chat("LLAMA_CPP")

        val frames = stream(chatId, "How big is the pool?")

        assertThat(bodies.single().replace(" ", "")).contains("\"return_progress\":true")
        val reading = Regex("""event:reading\ndata:\{"total":(\d+),"cached":(\d+),"processed":(\d+)\}""")
            .findAll(frames).map { it.groupValues.drop(1).map(String::toLong) }.toList()
        assertThat(reading).isNotEmpty()
        assertThat(reading.first()).containsExactly(1000L, 600L, 600L)
        assertThat(reading.last()).containsExactly(1000L, 600L, 1000L)
        assertThat(frames.indexOf("event:reading")).isLessThan(frames.indexOf("event:chunk"))
    }

    /**
     * A chat's input counted every round's whole prompt, so a long session read
     * as 16 million tokens a local server had mostly reused from its cache. What
     * the server says it reused is kept beside it.
     */
    @Test
    fun `what the server reused from its cache is kept apart from the input`() {
        val chatId = chat("LLAMA_CPP")

        stream(chatId, "How big is the pool?")

        val chat = requireNotNull(sessions.findById(chatId).orElse(null))
        assertThat(chat.spentInputTokens).isEqualTo(1000)
        assertThat(chat.spentCachedInputTokens).isEqualTo(600)
        graphQlTester.document("{ chatSession(id: $chatId) { spentCachedInputTokens } }")
            .execute().path("chatSession.spentCachedInputTokens").entity(Long::class.java).isEqualTo(600L)
    }

    @Test
    fun `an OpenAI provider is not asked for a reading it does not give`() {
        val chatId = chat("OPENAI")

        val frames = stream(chatId, "How big is the pool?")

        assertThat(bodies.single()).doesNotContain("return_progress")
        assertThat(frames).contains("event:chunk")
    }

    @Test
    fun `the type is saved and read back`() {
        chat("LLAMA_CPP")
        graphQlTester.document("{ modelProviders(workspaceId: $workspaceId) { type } }")
            .execute().path("modelProviders[0].type").entity(String::class.java).isEqualTo("LLAMA_CPP")
        assertThat(providers.findAll().single().type).isEqualTo(ProviderType.LLAMA_CPP)
    }

    @Test
    fun `the session page reads how far the prompt has been read, until something is written`() {
        recorder.reading(42, "Worker", total = 1000, cached = 600, processed = 800)
        val read = recorder.writingNow(42)
        assertThat(read?.reading?.processed).isEqualTo(800)

        recorder.writing(42, "Worker", null, 12)
        assertThat(recorder.writingNow(42)?.reading).isNull()
        recorder.quiet(42)
    }

    /* ---------------------------------------------------------- the fixtures */

    /**
     * A chat-completions stub that reads the prompt in three reports when asked
     * to - the shape llama.cpp sends, a frame with an empty choice and
     * `prompt_progress` beside it - and then answers.
     */
    private fun serve() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            bodies += body
            val frames = buildString {
                if (body.replace(" ", "").contains("\"return_progress\":true")) {
                    listOf(600, 800, 1000).forEach { processed ->
                        append(
                            """data: {"id":"c","object":"chat.completion.chunk","created":1,"model":"m",""" +
                                """"choices":[{"index":0,"delta":{}}],""" +
                                """"prompt_progress":{"total":1000,"cache":600,"processed":$processed,"time_ms":5}}""",
                        ).append(BLANK)
                    }
                }
                append("""data: {"id":"c","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"content":"Twenty."}}]}""")
                    .append(BLANK)
                append("""data: {"id":"c","object":"chat.completion.chunk","created":1,"model":"m","choices":[],"usage":{"prompt_tokens":1000,"completion_tokens":2,"total_tokens":1002},"timings":{"cache_n":600,"prompt_n":400}}""")
                    .append(BLANK)
                append("data: [DONE]").append(BLANK)
            }.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, frames.size.toLong())
            exchange.responseBody.use { it.write(frames) }
            exchange.close()
        }
        server.start()
    }

    /** A chat with an agent on a provider of [type] at the stub. */
    private fun chat(type: String): Long {
        val endpoint = "http://${server.address.hostString}:${server.address.port}"
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Local", type: $type, endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        val modelId = graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Qwen", modelId: "qwen", kind: CHAT }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
        val agentId = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Worker", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        graphQlTester.document("""mutation { updateAgent(id: $agentId, input: { name: "Worker", modelId: $modelId }) { id } }""")
            .execute()
        val chatId = graphQlTester.document(
            """mutation { startChat(input: { workspaceId: $workspaceId, title: "Pool" }) { id } }""",
        ).execute().path("startChat.id").entity(Long::class.java).get()
        graphQlTester.document("""mutation { chooseChatAgent(id: $chatId, agentId: $agentId) { id } }""")
            .execute().path("chooseChatAgent.id").hasValue()
        return chatId
    }

    private fun stream(chatId: Long, said: String): String {
        val response = MockHttpServletResponse()
        streaming.stream(chatId, ChatStreamRequest(said), response).writeTo(response.outputStream)
        return response.contentAsString.replace(" ", "")
    }

    private companion object {
        const val BLANK = "\n\n"
    }
}
