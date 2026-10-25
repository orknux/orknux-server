package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.llm.LlmSessionEventKind
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.memory.ChatMemoryRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A provider that offers every tool up front hands an agent all of them from
 * its first round, with nothing to find or load, and the same list every round.
 *
 * Session 635: an agent with a ceiling of 75 on llama.cpp kept loading tools
 * and dropping others to make room, and each load changed the tool list at the
 * top of the prompt - so the server read the whole session again every time.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ProviderOfferAllToolsTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val chats: ChatService,
    @Autowired val chatSessions: ChatSessionRepository,
    @Autowired val history: ChatMemoryRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val events: LlmSessionEventRepository,
) {

    private var workspaceId: Long = 0
    private var server: HttpServer? = null
    private val received = CopyOnWriteArrayList<String>()
    private val script = ConcurrentLinkedQueue<String>()
    private val mapper = ObjectMapper()

    @BeforeEach
    fun reset() {
        chatSessions.findAll().forEach { history.deleteByConversationId(it.conversationId) }
        chatSessions.deleteAll()
        agents.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        received.clear()
        script.clear()
    }

    @AfterEach
    fun stop() {
        server?.stop(0)
        server = null
    }

    @Test
    fun `every tool goes out from the first round, with no search, and the same in the next round`() {
        val chat = chatWith(provider(offerAllTools = "true"), agentCeiling = 5)
        script += called("call_1")
        script += answered("Done.")

        send(chat, "What can you do?")

        assertThat(received).hasSize(2)
        val first = tools(received[0])
        assertThat(first).doesNotContain(ToolSearchTools.SEARCH, ToolSearchTools.FIND)
        // Past the agent's own ceiling of five: the provider's switch overrides it.
        assertThat(first.size).isGreaterThan(5)
        assertThat(text(received[1], "tools")).isEqualTo(text(received[0], "tools"))
        // And the briefing does not send the model looking for tools it holds.
        assertThat(system(received[0])).doesNotContain("(load it first)").contains("every one is in front of you")
    }

    @Test
    fun `without it the agent's own ceiling still puts the rest behind a search`() {
        val chat = chatWith(provider(offerAllTools = "false"), agentCeiling = 5)
        script += answered("Done.")

        send(chat, "What can you do?")

        assertThat(tools(received[0])).contains(ToolSearchTools.SEARCH)
    }

    @Test
    fun `past the provider's own cap the rest are found, and the session says why, once`() {
        val chat = chatWith(provider(offerAllTools = "true", maxTools = 8), agentCeiling = null)
        script += called("call_1")
        script += answered("Done.")
        script += answered("Again.")

        send(chat, "What can you do?")
        send(chat, "And now?")

        assertThat(received).hasSize(3)
        received.forEach { body ->
            assertThat(tools(body).size).isLessThanOrEqualTo(8)
            assertThat(tools(body)).contains(ToolSearchTools.SEARCH)
        }
        val session = requireNotNull(chatSessions.findByIdOrNull(chat)?.llmSessionId)
        val notes = events.findAll().filter { it.sessionId == session && it.kind == LlmSessionEventKind.SYSTEM }
            .mapNotNull { it.content }.filter { it.contains("not every tool goes up front") }
        assertThat(notes).hasSize(1)
        assertThat(notes.single()).contains("takes 8 in one request")
    }

    @Test
    fun `left unset, no provider offers every tool up front`() {
        val ollama = provider(type = "OLLAMA")
        // OpenAI's type is also every OpenAI-compatible local server.
        val openai = provider(type = "OPENAI")
        val llama = provider(type = "LLAMA_CPP")
        val anthropic = provider(type = "ANTHROPIC")
        // The frozen tool array made it pointless, and it switched off the agent's ceiling and Offer marks.
        assertThat(listOf(ollama, openai, llama, anthropic).map(::resolved)).containsOnly(false)
        // An OpenAI-compatible server carries 200; OpenAI's own API still refuses past 128.
        assertThat(cap(openai)).isEqualTo(200)
        val openAiItself = graphQlTester.document(
            """mutation { createModelProvider(input: { workspaceId: $workspaceId, name: "openai-${System.nanoTime()}", type: OPENAI,
                 endpoint: "https://api.openai.com/v1", secret: "sk-test" }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        assertThat(cap(openAiItself)).isEqualTo(128)

        graphQlTester.document(
            """mutation { updateModelProvider(id: $ollama, input: { name: "renamed", endpoint: "http://x.invalid",
                 offerAllTools: false, maxTools: 40 }) { offerAllTools offersAllTools maxTools toolCap } }""",
        ).execute()
            .path("updateModelProvider.offersAllTools").entity(Boolean::class.java).isEqualTo(false)
            .path("updateModelProvider.toolCap").entity(Int::class.java).isEqualTo(40)
        graphQlTester.document(
            """mutation { updateModelProvider(id: $ollama, input: { name: "renamed", endpoint: "http://x.invalid",
                 maxTools: 3 }) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().extensions["code"]).isEqualTo("ModelProviderToolCapInvalid")
        }
    }

    private fun resolved(provider: Long): Boolean =
        graphQlTester.document("""{ modelProvider(id: $provider) { offersAllTools } }""")
            .execute().path("modelProvider.offersAllTools").entity(Boolean::class.java).get()

    private fun cap(provider: Long): Int =
        graphQlTester.document("""{ modelProvider(id: $provider) { toolCap } }""")
            .execute().path("modelProvider.toolCap").entity(Int::class.java).get()

    private fun tools(body: String): List<String> =
        mapper.readTree(body).path("tools").values().map { it.path("function").path("name").asString() }

    private fun text(body: String, field: String): String = mapper.writeValueAsString(mapper.readTree(body).path(field))

    private fun system(body: String): String =
        mapper.readTree(body).path("messages").values()
            .first { it.path("role").asString() == "system" }.path("content").asString()

    private fun send(chat: Long, text: String) {
        val start = chats.beginSend(chat, text)
        val said = chats.ask(start)
        assertThat(said).isInstanceOf(ChatCompletion.Answered::class.java)
        chats.finishSend(chat, (said as ChatCompletion.Answered).content)
    }

    private fun called(id: String) =
        """{"choices":[{"message":{"role":"assistant","content":null,
           "tool_calls":[{"id":"$id","type":"function","function":{"name":"skill_list","arguments":"{}"}}]}}],
           "usage":{"prompt_tokens":7,"completion_tokens":2}}"""

    private fun answered(text: String) =
        """{"choices":[{"message":{"role":"assistant","content":"$text"}}],
           "usage":{"prompt_tokens":9,"completion_tokens":1}}"""

    private fun endpoint(): String {
        val running = server ?: HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).also { made ->
            made.createContext("/chat/completions") { exchange ->
                val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
                received += body
                val bytes = (script.poll() ?: answered("Out of script.")).toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            made.start()
            server = made
        }
        return "http://${running.address.hostString}:${running.address.port}"
    }

    private fun provider(offerAllTools: String = "null", maxTools: Int? = null, type: String = "OPENAI"): Long =
        graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "p-$type-${System.nanoTime()}", type: $type, endpoint: "${endpoint()}",
                 secret: "sk-test", offerAllTools: $offerAllTools, maxTools: ${maxTools ?: "null"}
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()

    private fun chatWith(providerId: Long, agentCeiling: Int?): Long {
        val modelId = graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT })
               { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
        val agentId = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Worker", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        graphQlTester.document(
            """mutation { updateAgent(id: $agentId, input: { name: "Worker", modelId: $modelId,
                 maxTools: ${agentCeiling ?: "null"} }) { id } }""",
        ).execute()
        return requireNotNull(chats.start(workspaceId, "alice", "Tools", agentId).id)
    }
}
