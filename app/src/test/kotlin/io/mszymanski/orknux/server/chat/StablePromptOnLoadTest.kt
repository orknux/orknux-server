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
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Loading a tool changes nothing the next request declares: it answers with how
 * the tool is called, and the tool is called through tool_run.
 *
 * llama.cpp reuses a prompt only where it is the same from its first byte, and
 * measured against a Qwen hybrid model it reuses nothing at all past a
 * difference: one tool added to the array - at its end or in its middle - or
 * one line added to the system turn, and the whole prompt was read again. So
 * the system turn must not move when a tool is loaded, the tools a provider
 * could take go up front so that a load is rare, and the marks in the briefing
 * have to say what the request really carries, or the model loads what it
 * holds.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class StablePromptOnLoadTest(
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
    private val script = ConcurrentLinkedQueue<(String) -> String>()
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
    fun `the system turn and the tools are the same before and after a tool_load, across rounds and turns`() {
        val chat = chatWith(cap = 16)
        var picked = ""
        // Round one: load a tool the request does not carry, chosen off what was sent.
        script += { body ->
            picked = behind(body).let { if (DateTools.NOW in it) DateTools.NOW else it.first() }
            called("call_1", ToolSearchTools.FIND, """{"names":["$picked"]}""")
        }
        // Round two: call it, through tool_run.
        script += { _ -> called("call_2", ToolRun.NAME, """{"name":"$picked","arguments":{}}""") }
        script += { _ -> answered("Done.") }
        // A second turn in the same conversation.
        script += { _ -> answered("Again.") }

        send(chat, "What time is it?")
        send(chat, "And now?")

        assertThat(received).hasSize(4)
        // Byte for byte the same system turn in every request: the load moved nothing in it.
        assertThat(received.map { system(it) }.distinct()).hasSize(1)
        // Nor the array: a loaded tool is not added to it, in this turn or the next.
        assertThat(received.map { text(it, "tools") }.distinct()).hasSize(1)
        assertThat(tools(received[0])).doesNotContain(picked).contains(ToolSearchTools.FIND, ToolRun.NAME)

        // Callable: the call was answered by the tool, not refused as one the agent lacks.
        val answeredCall = toolResult(received[2], "call_2")
        assertThat(answeredCall).doesNotContain("no tool called").doesNotContain("not offered")
        // And the load said how it is called, and through what.
        assertThat(toolResult(received[1], "call_1")).contains("Loaded").contains(picked).contains(ToolRun.NAME).contains("Arguments:")
    }

    @Test
    fun `the marks in the briefing say what the request carries, and nothing it carries is marked to load`() {
        val chat = chatWith(cap = 16)
        script += { _ -> answered("Hello.") }

        send(chat, "Hi")

        val body = received.single()
        val declared = tools(body).toSet()
        val marks = marks(body)
        assertThat(marks).isNotEmpty()
        marks.forEach { (name, mark) ->
            if (mark == "loaded") assertThat(declared).describedAs(name).contains(name)
            else assertThat(declared).describedAs(name).doesNotContain(name)
        }
        assertThat(marks.values).contains("load it first")
        // As many as the provider takes go up front, short of the finder and room to load a few.
        assertThat(declared.size).isGreaterThan(ToolSearchTools.OFFERED + 1).isLessThanOrEqualTo(16)
    }

    @Test
    fun `under the provider's cap nothing is marked to load and tool_load is not mentioned`() {
        val chat = chatWith(cap = null)
        script += { _ -> answered("Hello.") }

        send(chat, "Hi")

        val body = received.single()
        assertThat(tools(body)).doesNotContain(ToolSearchTools.FIND)
        assertThat(marks(body).values).doesNotContain("load it first")
        assertThat(system(body)).doesNotContain(ToolSearchTools.FIND)
    }

    private fun behind(body: String): List<String> = marks(body).filterValues { it == "load it first" }.keys.toList()

    private fun marks(body: String): Map<String, String> =
        system(body).lines().mapNotNull { ENTRY.find(it) }.associate { it.groupValues[1] to it.groupValues[2] }

    private fun tools(body: String): List<String> =
        mapper.readTree(body).path("tools").values().map { it.path("function").path("name").asString() }

    private fun text(body: String, field: String): String = mapper.writeValueAsString(mapper.readTree(body).path(field))

    private fun system(body: String): String =
        mapper.readTree(body).path("messages").values()
            .first { it.path("role").asString() == "system" }.path("content").asString()

    private fun toolResult(body: String, id: String): String =
        mapper.readTree(body).path("messages").values()
            .first { it.path("role").asString() == "tool" && it.path("tool_call_id").asString() == id }
            .path("content").asString()

    private fun send(chat: Long, text: String) {
        val start = chats.beginSend(chat, text)
        val said = chats.ask(start)
        assertThat(said).isInstanceOf(ChatCompletion.Answered::class.java)
        chats.finishSend(chat, (said as ChatCompletion.Answered).content)
    }

    private fun called(id: String, name: String, arguments: String) =
        """{"choices":[{"message":{"role":"assistant","content":null,
           "tool_calls":[{"id":"$id","type":"function","function":{"name":"$name","arguments":${mapper.writeValueAsString(arguments)}}}]}}],
           "usage":{"prompt_tokens":7,"completion_tokens":2}}"""

    private fun answered(text: String) =
        """{"choices":[{"message":{"role":"assistant","content":"$text"}}],
           "usage":{"prompt_tokens":9,"completion_tokens":1}}"""

    private fun endpoint(): String {
        val running = server ?: HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).also { made ->
            made.createContext("/chat/completions") { exchange ->
                val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
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

    /** An OpenAI-compatible provider, as llama.cpp's is, capped at [cap]; the agent has no ceiling of its own, so only the cap puts a tool behind the search. */
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
        val agentId = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Worker", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        graphQlTester.document(
            """mutation { updateAgent(id: $agentId, input: { name: "Worker", modelId: $modelId }) { id } }""",
        ).execute()
        return requireNotNull(chats.start(workspaceId, "alice", "Tools", agentId).id)
    }

    private companion object {
        val ENTRY = Regex("^- ([A-Za-z0-9_.:-]+) [(](loaded|load it first|call it with tool_run)[)]")
    }
}
