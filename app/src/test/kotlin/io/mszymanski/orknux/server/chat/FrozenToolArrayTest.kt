package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.SkillKeys
import io.mszymanski.orknux.server.interaction.InteractionRequestRepository
import io.mszymanski.orknux.server.llm.LlmSessionEventKind
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.SessionEventRepository
import io.mszymanski.orknux.server.llm.SessionPrompt
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.memory.ChatMemoryRepository
import org.springframework.ai.chat.messages.MessageType
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
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Within a session the tool array and the system turn do not move, whatever
 * happens between rounds and turns: a grant accepted in the chat, a tool loaded
 * past the provider's cap, a skill read. What arrives late travels as messages
 * and is called through tool_run - or by its own name - and runs as itself.
 *
 * Measured on llama.cpp with a hybrid model: one tool added to the array, or
 * one line to the system turn, and the whole prompt was read again - 167,792
 * tokens in the session where a person granted the agent two tools.
 *
 * Names are written out rather than read off the classes that introduced them,
 * so this compiles against the code before them and fails there.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class FrozenToolArrayTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val chats: ChatService,
    @Autowired val chatSessions: ChatSessionRepository,
    @Autowired val history: ChatMemoryRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val requests: InteractionRequestRepository,
    @Autowired val inbox: SessionEventRepository,
    @Autowired val lines: LlmSessionEventRepository,
    @Autowired val prompt: SessionPrompt,
    @Autowired val chatTools: ChatTools,
) {

    private var workspaceId: Long = 0
    private var agentId: Long = 0
    private var server: HttpServer? = null
    private val received = CopyOnWriteArrayList<String>()
    private val script = ConcurrentLinkedQueue<(String) -> String>()
    private val mapper = ObjectMapper()

    @BeforeEach
    fun reset() {
        chatSessions.findAll().forEach { history.deleteByConversationId(it.conversationId) }
        chatSessions.deleteAll()
        requests.deleteAll()
        inbox.deleteAll()
        lines.deleteAll()
        agents.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "frozen-${System.nanoTime()}")).id)
        received.clear()
        script.clear()
    }

    @AfterEach
    fun stop() {
        server?.stop(0)
        server = null
    }

    @Test
    fun `a grant, a tool_load and a skill_load leave the tool array and the system turn as they were`() {
        val chat = chatWith(cap = 16)
        val html = SkillKeys.derive("Complex HTML")

        // Turn one: ask for the clock, which this agent has switched off, and stop.
        script += { _ ->
            called("call_ask", "tools_request", mapOf("tools" to listOf(NOW), "why" to "to tell the time"))
        }
        script += { _ -> answered("I asked for the clock.") }
        send(chat, "What time is it?")

        // The person accepts in the chat; the chat wakes to the answer and goes on in a second turn.
        var behind = ""
        script += { body ->
            behind = marks(system(body)).filterValues { it == "load it first" }.keys.first { it != NOW }
            called("call_run", RUN, mapOf("name" to NOW, "arguments" to emptyMap<String, Any>()))
        }
        script += { _ -> called("call_direct", NOW, emptyMap()) }
        script += { _ -> called("call_load", "tool_load", mapOf("names" to listOf(behind))) }
        script += { _ -> called("call_loaded", RUN, mapOf("name" to behind, "arguments" to emptyMap<String, Any>())) }
        script += { _ -> called("call_skill", "skill_load", mapOf("name" to html)) }
        script += { _ -> answered("It is late.") }
        graphQlTester.document(
            """mutation { answerRequest(id: ${requests.findAll().single().id}, option: "accept") { outcome } }""",
        ).execute().path("answerRequest.outcome").entity(String::class.java).satisfies { assertThat(it).startsWith("Granted") }
        val conversation = chatSessions.findById(chat).orElseThrow().conversationId
        await().atMost(Duration.ofSeconds(20)).untilAsserted { assertThat(assistantSaid(conversation)).hasSize(2) }

        // Turn three, asked in the ordinary way.
        script += { _ -> answered("Still late.") }
        send(chat, "And now?")

        assertThat(received).hasSize(9)
        // Byte for byte: one tool array and one system turn across every request of the session.
        assertThat(received.map { text(it, "tools") }.distinct()).hasSize(1)
        assertThat(received.map { system(it) }.distinct()).hasSize(1)
        assertThat(tools(received.first())).contains(RUN).doesNotContain(NOW)

        // The grant arrived as a message, with how the clock is called.
        val woken = received[2]
        assertThat(userSaid(woken)).anyMatch { it.contains("These tools are yours now") && it.contains(NOW) && it.contains("Arguments:") }

        // Through tool_run and by its own name alike, the clock answered as itself.
        assertThat(toolResult(received[3], "call_run")).doesNotContain("no tool called").doesNotContain("error")
        assertThat(toolResult(received[4], "call_direct")).doesNotContain("no tool called").doesNotContain("error")
        // The load answered with how the tool is called, and calling it reached the tool rather than a refusal.
        assertThat(toolResult(received[5], "call_load")).contains("Loaded").contains(behind).contains("Arguments:")
        assertThat(toolResult(received[6], "call_loaded")).doesNotContain("no tool called")
        assertThat(toolResult(received[7], "call_skill")).contains("content")

        // The session's record names the real tool, never the dispatcher.
        val session = requireNotNull(chatSessions.findById(chat).orElseThrow().llmSessionId)
        val called = lines.findAll().filter { it.sessionId == session && it.kind == LlmSessionEventKind.TOOL }.map { it.actor }
        assertThat(called).contains(NOW, behind).doesNotContain(RUN)
        assertThat(called.count { it == NOW }).isEqualTo(2)
    }

    @Test
    fun `a new session and a compaction may make the frame again, with what arrived late in it`() {
        val chat = chatWith(cap = null)
        script += { _ -> called("call_ask", "tools_request", mapOf("tools" to listOf(NOW), "why" to "the time")) }
        script += { _ -> answered("Asked.") }
        send(chat, "What time is it?")
        script += { _ -> answered("Granted, thanks.") }
        graphQlTester.document(
            """mutation { answerRequest(id: ${requests.findAll().single().id}, option: "accept") { outcome } }""",
        ).execute()
        val conversation = chatSessions.findById(chat).orElseThrow().conversationId
        await().atMost(Duration.ofSeconds(20)).untilAsserted { assertThat(assistantSaid(conversation)).hasSize(2) }
        assertThat(received).hasSize(3)
        // The same session: the clock is not in the array, though nothing is past the cap.
        assertThat(text(received[2], "tools")).isEqualTo(text(received[0], "tools"))
        assertThat(tools(received[2])).doesNotContain(NOW)

        // A new conversation with the same agent is framed afresh, the clock in its array.
        val other = requireNotNull(chats.start(workspaceId, "alice", "Other", agentId).id)
        script += { _ -> answered("Hello.") }
        send(other, "Hi")
        assertThat(tools(received[3])).contains(NOW)

        // And a compaction - which drops what the session was sent - frames the first one again too.
        prompt.reset(requireNotNull(chatSessions.findById(chat).orElseThrow().llmSessionId))
        script += { _ -> answered("Compacted.") }
        send(chat, "Still there?")
        assertThat(tools(received[4])).contains(NOW)
        assertThat(system(received[4])).isNotEqualTo(system(received[0]))
    }

    private fun assistantSaid(conversation: String): List<String> =
        history.findByConversationId(conversation).filter { it.messageType == MessageType.ASSISTANT }.map { it.text.orEmpty() }

    private fun marks(system: String): Map<String, String> =
        system.lines().mapNotNull { ENTRY.find(it) }.associate { it.groupValues[1] to it.groupValues[2] }

    private fun tools(body: String): List<String> =
        mapper.readTree(body).path("tools").values().map { it.path("function").path("name").asString() }

    private fun text(body: String, field: String): String = mapper.writeValueAsString(mapper.readTree(body).path(field))

    private fun system(body: String): String =
        mapper.readTree(body).path("messages").values()
            .first { it.path("role").asString() == "system" }.path("content").asString()

    private fun userSaid(body: String): List<String> =
        mapper.readTree(body).path("messages").values()
            .filter { it.path("role").asString() == "user" }.map { it.path("content").asString() }

    private fun toolResult(body: String, id: String): String =
        mapper.readTree(body).path("messages").values()
            .first { it.path("role").asString() == "tool" && it.path("tool_call_id").asString() == id }
            .path("content").asString()

    /** As the chat's own door sends: with the chat's shed, which a woken turn lends too. */
    private fun send(chat: Long, text: String) {
        val start = chats.beginSend(chat, text)
        val said = chats.ask(start, shed = chatTools.shed(chatSessions.findById(chat).orElseThrow()))
        assertThat(said).isInstanceOf(ChatCompletion.Answered::class.java)
        chats.finishSend(chat, (said as ChatCompletion.Answered).content)
    }

    private fun called(id: String, name: String, arguments: Map<String, Any?>) =
        """{"choices":[{"message":{"role":"assistant","content":null,
           "tool_calls":[{"id":"$id","type":"function","function":{"name":"$name","arguments":${mapper.writeValueAsString(mapper.writeValueAsString(arguments))}}}]}}],
           "usage":{"prompt_tokens":7,"completion_tokens":2}}"""

    private fun answered(text: String) =
        """{"choices":[{"message":{"role":"assistant","content":"$text"}}],
           "usage":{"prompt_tokens":9,"completion_tokens":1}}"""

    private fun endpoint(): String {
        val running = server ?: HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).also { made ->
            made.createContext("/chat/completions") { exchange ->
                val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
                // A woken chat streams; one plain body is a whole answer to a streamed request too.
                received += body
                val bytes = (script.poll()?.invoke(body) ?: answered("Out of script.")).toByteArray(StandardCharsets.UTF_8)
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

    /** An OpenAI-compatible provider, as llama.cpp's is, capped at [cap]; the agent has no ceiling of its own and the clock switched off. */
    private fun chatWith(cap: Int?): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "local-${System.nanoTime()}", type: OPENAI, endpoint: "${endpoint()}",
                 secret: "sk-test", maxTools: ${cap ?: "null"}
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        val modelId = graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT })
               { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
        agentId = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Worker", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        graphQlTester.document(
            """mutation { updateAgent(id: $agentId, input: { name: "Worker", modelId: $modelId }) { id } }""",
        ).execute()
        agents.findByIdOrNull(agentId)!!.let { agent ->
            agent.hiddenTools = (agent.hiddenTools + NOW).toMutableList()
            agents.save(agent)
        }
        return requireNotNull(chats.start(workspaceId, "alice", "Tools", agentId).id)
    }

    private companion object {
        const val NOW = "current_time"
        const val RUN = "tool_run"
        val ENTRY = Regex("^- ([A-Za-z0-9_.:-]+) [(](loaded|load it first|call it with tool_run)[)]")
    }
}
