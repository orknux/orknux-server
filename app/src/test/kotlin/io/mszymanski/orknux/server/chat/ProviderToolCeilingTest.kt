package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.AgentRepository
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
 * A provider carries the agent's own ceiling and its Offer marks, with the
 * provider's own cap on a request on top - unless it is set to offer every
 * tool up front, which sets the ceiling and the marks aside up to the cap.
 *
 * That setting went in 1.2.0 and came back off by default: on, an agent with a
 * ceiling of 75 sent 59k tokens of tools on its first request. And whether a
 * loaded tool joins the array at the next turn, which is off by default on
 * llama.cpp alone.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ProviderToolCeilingTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val chats: ChatService,
    @Autowired val chatSessions: ChatSessionRepository,
    @Autowired val history: ChatMemoryRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
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
    fun `the agent's own ceiling holds on a provider that does not offer every tool`() {
        val chat = chatWith(provider(), agentCeiling = 5)
        script += called("call_1")
        script += answered("Done.")

        send(chat, "What can you do?")

        assertThat(received).hasSize(2)
        val first = tools(received[0])
        // The rest behind a search, and no more than the ceiling in the array.
        assertThat(first).contains(ToolSearchTools.SEARCH, ToolSearchTools.FIND)
        assertThat(first.size).isLessThanOrEqualTo(5)
        // The same array in the next round: it is the session's.
        assertThat(text(received[1], "tools")).isEqualTo(text(received[0], "tools"))
        assertThat(system(received[0])).contains("(load it first)").doesNotContain(AgentBriefing.ALL_IN_HAND)
    }

    @Test
    fun `a provider set to offer every tool carries them all from the first request, past the agent's ceiling`() {
        val chat = chatWith(provider(offerAllTools = "true"), agentCeiling = 5)
        script += answered("Hello.")

        send(chat, "What can you do?")

        val first = tools(received.single())
        assertThat(first.size).isGreaterThan(5)
        assertThat(first).doesNotContain(ToolSearchTools.SEARCH, ToolSearchTools.FIND)
        assertThat(system(received.single())).doesNotContain("(load it first)").contains(AgentBriefing.ALL_IN_HAND)
    }

    @Test
    fun `past the provider's own cap the rest are found, even with every tool up front`() {
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
    }

    @Test
    fun `every tool up front is off by default on every kind, and the join is off on llama_cpp alone`() {
        val kinds = listOf("OPENAI", "ANTHROPIC", "AZURE_OPENAI", "OLLAMA", "LLAMA_CPP")
        val made = kinds.associateWith { provider(type = it) }
        made.forEach { (kind, id) ->
            assertThat(resolved(id)).describedAs(kind).isFalse()
            assertThat(joins(id)).describedAs(kind).isEqualTo(kind != "LLAMA_CPP")
        }
        // Left unset, so a provider follows its type's default.
        assertThat(providers.findByIdOrNull(made.getValue("LLAMA_CPP"))?.joinLoadedTools).isNull()
        // And the type's own defaults agree with what is answered.
        io.mszymanski.orknux.connector.model.ProviderType.entries.forEach {
            assertThat(it.joinsLoadedTools).describedAs(it.name).isEqualTo(it != io.mszymanski.orknux.connector.model.ProviderType.LLAMA_CPP)
        }
    }

    @Test
    fun `both settings go in and come back out, and a save that does not send them leaves them`() {
        val llama = provider(type = "LLAMA_CPP", offerAllTools = "true", extra = "joinLoadedTools: true")
        graphQlTester.document("""{ modelProvider(id: $llama) { offerAllTools offersAllTools joinLoadedTools joinsLoadedTools } }""")
            .execute()
            .path("modelProvider.offerAllTools").entity(Boolean::class.java).isEqualTo(true)
            .path("modelProvider.offersAllTools").entity(Boolean::class.java).isEqualTo(true)
            .path("modelProvider.joinLoadedTools").entity(Boolean::class.java).isEqualTo(true)
            .path("modelProvider.joinsLoadedTools").entity(Boolean::class.java).isEqualTo(true)

        // A save that does not name them - an older client - changes neither.
        graphQlTester.document(
            """mutation { updateModelProvider(id: $llama, input: { name: "renamed", endpoint: "http://x.invalid" })
                 { offersAllTools joinsLoadedTools } }""",
        ).execute()
            .path("updateModelProvider.offersAllTools").entity(Boolean::class.java).isEqualTo(true)
            .path("updateModelProvider.joinsLoadedTools").entity(Boolean::class.java).isEqualTo(true)

        graphQlTester.document(
            """mutation { updateModelProvider(id: $llama, input: { name: "renamed", endpoint: "http://x.invalid",
                 offerAllTools: false, joinLoadedTools: false }) { offerAllTools offersAllTools joinLoadedTools joinsLoadedTools } }""",
        ).execute()
            .path("updateModelProvider.offerAllTools").entity(Boolean::class.java).isEqualTo(false)
            .path("updateModelProvider.offersAllTools").entity(Boolean::class.java).isEqualTo(false)
            .path("updateModelProvider.joinLoadedTools").entity(Boolean::class.java).isEqualTo(false)
            .path("updateModelProvider.joinsLoadedTools").entity(Boolean::class.java).isEqualTo(false)
        assertThat(providers.findByIdOrNull(llama)?.offerAllTools).isFalse()
        assertThat(providers.findByIdOrNull(llama)?.joinLoadedTools).isFalse()
    }

    @Test
    fun `the tool cap is the type's, and refused outside its range`() {
        val ollama = provider(type = "OLLAMA")
        val openai = provider(type = "OPENAI")
        // An OpenAI-compatible server carries 200; OpenAI's own API still refuses past 128.
        assertThat(cap(openai)).isEqualTo(200)
        val openAiItself = graphQlTester.document(
            """mutation { createModelProvider(input: { workspaceId: $workspaceId, name: "openai-${System.nanoTime()}", type: OPENAI,
                 endpoint: "https://api.openai.com/v1", secret: "sk-test" }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        assertThat(cap(openAiItself)).isEqualTo(128)

        graphQlTester.document(
            """mutation { updateModelProvider(id: $ollama, input: { name: "renamed", endpoint: "http://x.invalid",
                 maxTools: 40 }) { maxTools toolCap } }""",
        ).execute()
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

    private fun joins(provider: Long): Boolean =
        graphQlTester.document("""{ modelProvider(id: $provider) { joinsLoadedTools } }""")
            .execute().path("modelProvider.joinsLoadedTools").entity(Boolean::class.java).get()

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

    private fun provider(offerAllTools: String = "null", maxTools: Int? = null, type: String = "OPENAI", extra: String = ""): Long =
        graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "p-$type-${System.nanoTime()}", type: $type, endpoint: "${endpoint()}",
                 secret: "sk-test", offerAllTools: $offerAllTools, maxTools: ${maxTools ?: "null"} $extra
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
