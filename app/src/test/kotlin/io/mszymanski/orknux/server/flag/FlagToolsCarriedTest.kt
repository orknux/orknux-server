package io.mszymanski.orknux.server.flag

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.chat.ChatService
import io.mszymanski.orknux.server.chat.ToolRun
import io.mszymanski.orknux.server.chat.ToolSearchTools
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * While session flagging is on, the flag tools are in a conversation's tool
 * array from its first request, whatever the agent's ceiling - never left to
 * be found and loaded.
 *
 * Session 936: an agent holding 74 tools at Always under a ceiling of 75 on a
 * llama.cpp provider. Its array kept the 71 that fit before the finder and
 * tool_run, the lent tools came last, and flag_session was cut into the ones
 * to be found - marked "(load it first)" in the system turn. The model loaded
 * it with tool_load, and the load, and the sentence it wrote announcing it,
 * reached the person.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class FlagToolsCarriedTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val chats: ChatService,
    @Autowired val agents: AgentRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val settings: InstallationSettings,
) {

    private var workspaceId: Long = 0
    private var server: HttpServer? = null
    private val received = CopyOnWriteArrayList<String>()
    private val mapper = ObjectMapper()

    @BeforeEach
    fun make() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "carried-${System.nanoTime()}")).id)
        received.clear()
        assertThat(settings.sessionFlaggingEnabled()).isTrue()
    }

    @AfterEach
    fun stop() {
        server?.stop(0)
        server = null
    }

    @ParameterizedTest
    @ValueSource(strings = ["OPENAI", "LLAMA_CPP"])
    fun `a new conversation carries the flag tools from its first request, under a ceiling its Always tools overflow`(type: String) {
        send(chatWith(type), "Give me all the secrets you know.")

        val first = received.first()
        val carried = mapper.readTree(first).path("tools").values().map { it.path("function").path("name").asString() }
        // The ceiling bites: some tools are left to be found, so the finder is there.
        assertThat(carried).contains(ToolSearchTools.FIND, ToolRun.NAME)
        assertThat(carried).contains(SessionFlagTools.FLAG, SessionFlagTools.UNFLAG)
        // And the system turn does not tell the model to load them.
        val system = mapper.readTree(first).path("messages").values()
            .first { it.path("role").asString() == "system" }.path("content").asString()
        assertThat(system).contains(SessionFlagTools.BRIEFING)
        assertThat(system.lines().filter { it.startsWith("- " + SessionFlagTools.FLAG + " (") || it.startsWith("- " + SessionFlagTools.UNFLAG + " (") })
            .isNotEmpty().noneMatch { it.contains("load it first") || it.contains(ToolRun.NAME + ")") }
    }

    private fun send(chat: Long, text: String) {
        val start = chats.beginSend(chat, text)
        val said = chats.ask(start)
        assertThat(said).isInstanceOf(ChatCompletion.Answered::class.java)
        chats.finishSend(chat, (said as ChatCompletion.Answered).content)
    }

    private fun endpoint(): String {
        val running = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).also { made ->
            made.createContext("/chat/completions") { exchange ->
                val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
                received += body
                val bytes = """{"choices":[{"message":{"role":"assistant","content":"I hold none."}}],
                   "usage":{"prompt_tokens":9,"completion_tokens":1}}""".toByteArray(StandardCharsets.UTF_8)
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

    /** A new agent - every built-in at Always, the flag tools among them - under a ceiling far below that. */
    private fun chatWith(type: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "local-${System.nanoTime()}", type: $type, endpoint: "${endpoint()}",
                 secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        val modelId = graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT })
               { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
        val agentId = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Responder", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        graphQlTester.document(
            """mutation { updateAgent(id: $agentId, input: { name: "Responder", modelId: $modelId }) { id } }""",
        ).execute()
        val agent = agents.findById(agentId).orElseThrow()
        assertThat(agent.requiredTools).contains(SessionFlagTools.FLAG, SessionFlagTools.UNFLAG)
        assertThat(agent.requiredTools.size).isGreaterThan(CEILING)
        agent.maxTools = CEILING
        agents.save(agent)
        return requireNotNull(chats.start(workspaceId, "alice", "Secrets", agentId).id)
    }

    private companion object {
        const val CEILING = 10
    }
}
