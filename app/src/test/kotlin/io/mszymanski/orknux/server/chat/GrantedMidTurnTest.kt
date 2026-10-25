package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.interaction.InteractionRequestRepository
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.SessionEventRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A tool a person grants while the agent's turn is still running is the
 * agent's in that turn: tool_load finds it and tool_run runs it, the tool array
 * stays as it was, and the next turn carries the grant as it always did.
 *
 * Session 910: three tools asked for with tools_request and granted mid-turn,
 * the note saying they were the agent's "from your next turn", and four seconds
 * later tool_load answered "You have no tool called" and tool_run "There is no
 * tool called" - so the model told the person the tools had not arrived.
 *
 * Driven through [AgentConversation] on a plain session rather than a chat, so
 * the grant's note cannot wake a second turn beside the one being watched. The
 * grant is made through the person's own door, the answerRequest mutation, from
 * inside the stub provider while it is being asked. Names are written out.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class GrantedMidTurnTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val conversation: AgentConversation,
    @Autowired val agents: AgentRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val requests: InteractionRequestRepository,
    @Autowired val inbox: SessionEventRepository,
    @Autowired val lines: LlmSessionEventRepository,
    @Autowired val recorder: LlmSessionRecorder,
) {

    private var workspaceId = 0L
    private var agentId = 0L
    private var modelId = 0L
    private var server: HttpServer? = null
    private val received = CopyOnWriteArrayList<String>()
    private val script = ConcurrentLinkedQueue<(String) -> String>()
    private val mapper = ObjectMapper()

    @BeforeEach
    fun reset() {
        requests.deleteAll()
        inbox.deleteAll()
        lines.deleteAll()
        agents.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "granted-${System.nanoTime()}")).id)
        received.clear()
        script.clear()
    }

    @AfterEach
    fun stop() {
        server?.stop(0)
        server = null
    }

    @Test
    fun `a tool granted mid-turn loads and runs in that turn, the array unmoved, and the next turn carries it`() {
        setUp(type = "OPENAI")
        val session = recorder.open(workspaceId, "grant", "now-${System.nanoTime()}")
        val person = SecurityContextHolder.getContext()

        script += { _ -> called("call_ask", "tools_request", mapOf("tools" to listOf(CHECK), "why" to "to check a document")) }
        // The person accepts while the model is still being asked; it ends its turn, and what arrived keeps it going.
        script += { _ ->
            SecurityContextHolder.setContext(person)
            try {
                accept()
            } finally {
                SecurityContextHolder.clearContext()
            }
            answered("I asked for the format check.")
        }
        script += { _ -> called("call_load", "tool_load", mapOf("names" to listOf(CHECK))) }
        script += { _ -> called("call_run", RUN, mapOf("name" to CHECK, "arguments" to ARGS)) }
        script += { _ -> answered("It is late.") }
        val turns = listOf(ChatTurn("system", "You are Worker."), ChatTurn("user", "Is this valid?"))
        assertThat(conversation.answer(modelId, agentId, turns, into = session)).isInstanceOf(ChatCompletion.Answered::class.java)

        assertThat(received).hasSize(5)
        // One array and one system turn through the whole turn, the tool not in it.
        assertThat(received.map { text(it, "tools") }.distinct()).hasSize(1)
        assertThat(received.map { system(it) }.distinct()).hasSize(1)
        assertThat(tools(received.first())).contains(RUN, "tool_load").doesNotContain(CHECK)

        // The grant's note says it is callable now, through tool_run, with the call's shape.
        val told = userSaid(received[2])
        assertThat(told).anyMatch {
            it.contains("alice granted you $CHECK") && it.contains("call now, in this turn, through $RUN") &&
                it.contains("{\"name\": \"$CHECK\", \"arguments\":")
        }
        assertThat(told).noneMatch { it.contains("yours from your next turn") }
        // And how it is called, whole, in the same turn.
        assertThat(told).anyMatch { it.contains("Granted to you during this turn") && it.contains(CHECK) && it.contains("Arguments:") }

        // tool_load found it, and tool_run ran it as itself.
        assertThat(toolResult(received[3], "call_load")).contains("Loaded").contains(CHECK).doesNotContain("no tool called")
        assertThat(toolResult(received[4], "call_run")).doesNotContain("no tool called").doesNotContain("error")
        val called = lines.findAll().filter { it.sessionId == session && it.kind == io.mszymanski.orknux.server.llm.LlmSessionEventKind.TOOL }.map { it.actor }
        assertThat(called).contains(CHECK).doesNotContain(RUN)

        // The next turn: the tool loaded in the last one joins the array, and is called by its own name.
        script += { _ -> called("call_direct", CHECK, ARGS) }
        script += { _ -> answered("Still late.") }
        conversation.answer(
            modelId, agentId,
            turns + ChatTurn("assistant", "It is late.") + ChatTurn("user", "And now?"),
            into = session,
        )
        assertThat(received).hasSize(7)
        assertThat(tools(received[5])).contains(CHECK)
        assertThat(toolResult(received[6], "call_direct")).doesNotContain("no tool called").doesNotContain("error")
    }

    @Test
    fun `on llama_cpp a tool granted mid-turn runs through tool_run at once, and nothing says to call it by name`() {
        setUp(type = "LLAMA_CPP")
        val session = recorder.open(workspaceId, "grant", "llama-${System.nanoTime()}")
        val person = SecurityContextHolder.getContext()

        script += { _ -> called("call_ask", "tools_request", mapOf("tools" to listOf(CHECK), "why" to "the time")) }
        script += { _ ->
            SecurityContextHolder.setContext(person)
            try {
                accept()
            } finally {
                SecurityContextHolder.clearContext()
            }
            answered("Asked.")
        }
        script += { _ -> called("call_run", RUN, mapOf("name" to CHECK, "arguments" to ARGS)) }
        script += { _ -> answered("It is late.") }
        conversation.answer(modelId, agentId, listOf(ChatTurn("system", "Work."), ChatTurn("user", "Time?")), into = session)

        assertThat(received).hasSize(4)
        assertThat(received.map { text(it, "tools") }.distinct()).hasSize(1)
        assertThat(toolResult(received[3], "call_run")).doesNotContain("no tool called").doesNotContain("error")
        val told = userSaid(received[3]).filter { CHECK in it }
        assertThat(told).anyMatch { it.contains("through $RUN") }
        assertThat(told).noneMatch { it.contains("by its own name") || it.contains("by their own names") }
    }

    private fun accept() {
        graphQlTester.document(
            """mutation { answerRequest(id: ${requests.findAll().single().id}, option: "accept") { outcome } }""",
        ).execute().path("answerRequest.outcome").entity(String::class.java).satisfies { assertThat(it).startsWith("Granted") }
    }

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

    /** A provider of [type] with no cap, and an agent with a ceiling of its own - so it finds - and the format check hidden. */
    private fun setUp(type: String) {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "local-${System.nanoTime()}", type: $type, endpoint: "${endpoint()}",
                 secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        modelId = graphQlTester.document(
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
            agent.hiddenTools = (agent.hiddenTools + CHECK).toMutableList()
            agent.maxTools = 10
            agents.save(agent)
        }
    }

    private companion object {
        const val CHECK = "validate_format"
        val ARGS = mapOf("format" to "json", "text" to "{}")
        const val RUN = "tool_run"
    }
}
