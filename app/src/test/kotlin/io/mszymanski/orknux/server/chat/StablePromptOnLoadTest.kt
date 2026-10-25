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
 * Loading a tool changes nothing the next request of the same turn declares: it
 * answers with how the tool is called, and the tool is called through tool_run.
 * At the next turn it joins the tool array, once, and is called by its own name
 * - llama.cpp lets a model name only what the array declares - up to the
 * provider's cap, past which tool_run goes on reaching it. That join is the
 * provider's setting: off by default on llama.cpp, where the turn after a load
 * re-reads the whole conversation, and there the model is told tool_run is the
 * only way - in the system turn, and in what tool_find and tool_load answer.
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
    private var providerId: Long = 0
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
    fun `a tool_load leaves the array as it was for the rest of its turn, and the tool joins it at the next`() {
        val chat = chatWith(cap = null, ceiling = CEILING)
        var picked = ""
        // Turn one, round one: load a tool the request does not carry, chosen off what was sent.
        script += { body ->
            picked = behind(body).let { if (DateTools.NOW in it) DateTools.NOW else it.first() }
            called("call_1", ToolSearchTools.FIND, """{"names":["$picked"]}""")
        }
        // Round two: call it, through tool_run, the array being as it was.
        script += { _ -> called("call_2", ToolRun.NAME, """{"name":"$picked","arguments":{}}""") }
        script += { _ -> answered("Done.") }
        // Turn two: call it by its own name.
        script += { _ -> called("call_3", picked, "{}") }
        script += { _ -> answered("Again.") }
        // Turn three: nothing loaded.
        script += { _ -> answered("Still.") }

        send(chat, "What time is it?")
        send(chat, "And now?")
        send(chat, "Once more?")

        assertThat(received).hasSize(6)
        val turnOne = received.subList(0, 3)
        // Within the turn that loaded it, byte for byte the same system turn and array in every round.
        assertThat(turnOne.map { system(it) }.distinct()).hasSize(1)
        assertThat(turnOne.map { text(it, "tools") }.distinct()).hasSize(1)
        assertThat(tools(received[0])).doesNotContain(picked).contains(ToolSearchTools.FIND, ToolRun.NAME)
        // Callable through tool_run: answered by the tool, not refused as one the agent lacks.
        assertThat(toolResult(received[2], "call_2")).doesNotContain("no tool called").doesNotContain("not offered")
        // And the load said how it is called in this turn, and how from the next.
        assertThat(toolResult(received[1], "call_1")).contains("Loaded").contains(picked).contains(ToolRun.NAME)
            .contains("Arguments:").contains("next turn")

        // Turn two: the array is turn one's with the loaded tool appended, and the briefing marks it loaded.
        val before = tools(received[0])
        assertThat(tools(received[3])).isEqualTo(before + picked)
        assertThat(marks(received[0])[picked]).isEqualTo("load it first")
        assertThat(marks(received[3])[picked]).isEqualTo("loaded")
        // Called by its own name, it ran.
        assertThat(toolResult(received[4], "call_3")).doesNotContain("no tool called").doesNotContain("not offered")
        // Every round of turn two sent the same.
        assertThat(text(received[4], "tools")).isEqualTo(text(received[3], "tools"))
        assertThat(system(received[4])).isEqualTo(system(received[3]))

        // Turn three loaded nothing, and sends turn two's array and system turn unchanged.
        assertThat(text(received[5], "tools")).isEqualTo(text(received[3], "tools"))
        assertThat(system(received[5])).isEqualTo(system(received[3]))
    }

    @Test
    fun `what was loaded joins only up to the provider's cap, and the rest is still reached through tool_run`() {
        val chat = chatWith(cap = null, ceiling = CEILING)
        var pair = emptyList<String>()
        script += { body ->
            pair = behind(body).sorted().take(2)
            called("call_1", ToolSearchTools.FIND, mapper.writeValueAsString(mapOf("names" to pair)))
        }
        script += { _ -> answered("Loaded.") }
        send(chat, "Load two.")
        assertThat(pair).hasSize(2)

        // The provider now takes one tool more than the turn sent: room for one of the two.
        val carried = tools(received[0])
        val provider = providers.findById(providerId).orElseThrow()
        provider.maxTools = carried.size + 1
        providers.save(provider)

        script += { _ -> called("call_2", ToolRun.NAME, """{"name":"${pair[1]}","arguments":{}}""") }
        script += { _ -> answered("Done.") }
        send(chat, "Use them.")

        val next = tools(received[2])
        assertThat(next).hasSize(carried.size + 1).isEqualTo(carried + pair[0])
        assertThat(marks(received[2])[pair[0]]).isEqualTo("loaded")
        assertThat(marks(received[2])[pair[1]]).isEqualTo("load it first")
        // The one left out is still the agent's, through tool_run.
        assertThat(toolResult(received[3], "call_2")).doesNotContain("no tool called").doesNotContain("not offered")
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

    @Test
    fun `with the join switched off a loaded tool never joins, and is called through tool_run in every later turn`() {
        val chat = chatWith(cap = null, ceiling = CEILING, settings = "joinLoadedTools: false")
        var picked = ""
        script += { body ->
            picked = behind(body).first()
            called("call_1", ToolSearchTools.FIND, """{"names":["$picked"]}""")
        }
        script += { _ -> answered("Loaded.") }
        script += { _ -> called("call_2", ToolRun.NAME, """{"name":"$picked","arguments":{}}""") }
        script += { _ -> answered("Done.") }

        send(chat, "Load it.")
        send(chat, "Use it.")

        assertThat(received).hasSize(4)
        // One array and one system turn across both turns: nothing joined.
        assertThat(received.map { text(it, "tools") }.distinct()).hasSize(1)
        assertThat(received.map { system(it) }.distinct()).hasSize(1)
        assertThat(tools(received[2])).doesNotContain(picked)
        assertThat(marks(received[2])[picked]).isEqualTo("load it first")
        assertThat(toolResult(received[3], "call_2")).doesNotContain("no tool called").doesNotContain("not offered")
        // Told tool_run for the rest of the conversation - and, not being llama.cpp, without the rule.
        assertThat(toolResult(received[1], "call_1")).contains(ToolRun.NAME).contains("rest of this conversation")
            .doesNotContain("next turn").doesNotContain(RULE)
        assertThat(system(received[0])).doesNotContain(RULE)
    }

    @Test
    fun `on llama_cpp by default nothing joins, and the system turn, tool_find and tool_load say tool_run is the only way`() {
        val chat = chatWith(cap = null, ceiling = CEILING, type = "LLAMA_CPP")
        var picked = ""
        script += { body ->
            picked = behind(body).first()
            called("call_0", ToolSearchTools.SEARCH, mapper.writeValueAsString(mapOf("queries" to listOf(picked))))
        }
        script += { _ -> called("call_1", ToolSearchTools.FIND, """{"names":["$picked"]}""") }
        script += { _ -> answered("Loaded.") }
        script += { _ -> called("call_2", ToolRun.NAME, """{"name":"$picked","arguments":{}}""") }
        script += { _ -> answered("Done.") }

        send(chat, "Find and load it.")
        send(chat, "Use it.")

        assertThat(received).hasSize(5)
        assertThat(received.map { text(it, "tools") }.distinct()).hasSize(1)
        assertThat(tools(received[3])).doesNotContain(picked)
        // The system turn states the rule, with the call's shape, its names read off the declarations.
        val example = marks(received[0]).keys.min()
        assertThat(system(received[0])).contains(LoadedTools.rule(example))
            .contains("\"" + ToolRun.TOOL + "\": \"" + example + "\"").contains("\"" + ToolRun.ARGUMENTS + "\"")
        // And so do the answers that list a tool to load and that load it.
        assertThat(toolResult(received[1], "call_0")).contains(RULE)
        assertThat(toolResult(received[2], "call_1")).contains(RULE).contains("\"" + ToolRun.TOOL + "\": \"" + picked + "\"")
            .doesNotContain("next turn")
        // tool_load's own description says so too.
        assertThat(description(received[0], ToolSearchTools.FIND)).contains("never by its own name")
        assertThat(toolResult(received[4], "call_2")).doesNotContain("no tool called").doesNotContain("not offered")
    }

    @Test
    fun `on llama_cpp with the join switched on the tool joins at the next turn and the rule is not said`() {
        val chat = chatWith(cap = null, ceiling = CEILING, type = "LLAMA_CPP", settings = "joinLoadedTools: true")
        var picked = ""
        script += { body ->
            picked = behind(body).first()
            called("call_1", ToolSearchTools.FIND, """{"names":["$picked"]}""")
        }
        script += { _ -> answered("Loaded.") }
        script += { _ -> answered("Again.") }

        send(chat, "Load it.")
        send(chat, "And now?")

        assertThat(system(received[0])).doesNotContain(RULE)
        assertThat(toolResult(received[1], "call_1")).contains("next turn").doesNotContain(RULE)
        assertThat(tools(received[2])).isEqualTo(tools(received[0]) + picked)
    }

    @Test
    fun `with every tool up front nothing is marked to load and the rule is not said, on llama_cpp too`() {
        val chat = chatWith(cap = null, ceiling = CEILING, type = "LLAMA_CPP", settings = "offerAllTools: true")
        script += { _ -> answered("Hello.") }

        send(chat, "Hi")

        val body = received.single()
        // Past the agent's own ceiling: its Offer marks are set aside.
        assertThat(tools(body).size).isGreaterThan(CEILING)
        assertThat(tools(body)).doesNotContain(ToolSearchTools.FIND)
        assertThat(marks(body).values).doesNotContain("load it first")
        assertThat(system(body)).doesNotContain(RULE).contains(AgentBriefing.ALL_IN_HAND)
    }

    @Test
    fun `switching the join on holds from the session's next turn`() {
        val chat = chatWith(cap = null, ceiling = CEILING, type = "LLAMA_CPP")
        var picked = ""
        script += { body ->
            picked = behind(body).first()
            called("call_1", ToolSearchTools.FIND, """{"names":["$picked"]}""")
        }
        script += { _ -> answered("Loaded.") }
        send(chat, "Load it.")

        graphQlTester.document(
            """mutation { updateModelProvider(id: $providerId, input: { name: "renamed", endpoint: "${endpoint()}",
                 joinLoadedTools: true }) { joinsLoadedTools } }""",
        ).execute().path("updateModelProvider.joinsLoadedTools").entity(Boolean::class.java).isEqualTo(true)
        script += { _ -> answered("Again.") }
        send(chat, "And now?")

        assertThat(tools(received[2])).isEqualTo(tools(received[0]) + picked)
        assertThat(marks(received[2])[picked]).isEqualTo("loaded")
        // The rule it was given is withdrawn in so many words.
        assertThat(system(received[2])).doesNotContain(RULE)
    }

    private fun description(body: String, name: String): String =
        mapper.readTree(body).path("tools").values().first { it.path("function").path("name").asString() == name }
            .path("function").path("description").asString()

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

    /**
     * An OpenAI-compatible provider, as llama.cpp's is, capped at [cap]; the
     * agent has [ceiling] of its own or none, so only the cap puts a tool
     * behind the search. With a ceiling the array is short of the provider's
     * cap, and what is loaded has room to join it.
     */
    private fun chatWith(cap: Int?, ceiling: Int? = null, type: String = "OPENAI", settings: String = ""): Long {
        providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "local-${System.nanoTime()}", type: $type, endpoint: "${endpoint()}",
                 secret: "sk-test", maxTools: ${cap ?: "null"} $settings
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
        if (ceiling != null) {
            val agent = agents.findById(agentId).orElseThrow()
            agent.maxTools = ceiling
            agents.save(agent)
        }
        return requireNotNull(chats.start(workspaceId, "alice", "Tools", agentId).id)
    }

    private companion object {
        /** The agent's own ceiling: short of the provider's cap, so a loaded tool has somewhere to go. */
        const val CEILING = 10

        /** What every statement of the rule says, whichever tool it names. See [LoadedTools.rule]. */
        val RULE = "can only ever be called through " + ToolRun.NAME
        val ENTRY = Regex("^- ([A-Za-z0-9_.:-]+) [(](loaded|load it first|call it with tool_run)[)]")
    }
}
