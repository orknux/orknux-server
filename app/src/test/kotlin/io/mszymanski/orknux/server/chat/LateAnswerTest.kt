package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.SessionEventKind
import io.mszymanski.orknux.server.llm.SessionInbox
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A subagent's late answer is not read as the reply to the person's new message.
 *
 * Reported from a Slack thread: the person wrote "stop writing that", and in the
 * same turn the agent was handed "Summarizer has answered your follow-up about
 * ..." - an answer to something it had asked twenty minutes earlier - placed
 * after the person's message, where the last thing in a prompt reads as what
 * the turn is about, and with nothing saying how old it was.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class LateAnswerTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val conversation: AgentConversation,
    @Autowired val runTools: AgentRunTools,
    @Autowired val inbox: SessionInbox,
    @Autowired val agents: AgentRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val recorder: LlmSessionRecorder,
) {

    private var workspaceId = 0L
    private var agentId = 0L
    private var modelId = 0L
    private var server: HttpServer? = null
    private val received = CopyOnWriteArrayList<String>()
    private val mapper = ObjectMapper()

    @BeforeEach
    fun reset() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "late-${System.nanoTime()}")).id)
        received.clear()
    }

    @AfterEach
    fun stop() {
        server?.stop(0)
        server = null
    }

    @Test
    fun `an answer waiting in the inbox is read before the person's new message, not after it`() {
        setUp(type = "OPENAI")
        val session = recorder.open(workspaceId, "slack_thread", "t-${System.nanoTime()}")
        inbox.post(session, SessionEventKind.ANSWER, "Summarizer has answered your follow-up about \"Audit\": done")

        val turns = listOf(ChatTurn("system", "You are OKO Bot."), ChatTurn("user", "Stop writing that footer."))
        conversation.answer(modelId, agentId, turns, into = session)

        val said = mapper.readTree(received.first()).path("messages").values()
            .filter { it.path("role").asString() == "user" }.map { it.path("content").asString() }
        assertThat(said.last()).isEqualTo("Stop writing that footer.")
        assertThat(said).anyMatch { it.startsWith("Summarizer has answered") }
    }

    @Test
    fun `an old answer says when it was asked, and a fresh one does not`() {
        val now = Instant.now()
        assertThat(runTools.asked(now.minus(Duration.ofMinutes(20)), now))
            .startsWith(", which you asked at ").endsWith(", 20 minutes ago")
        assertThat(runTools.asked(now.minusSeconds(30), now)).isEmpty()
        assertThat(runTools.asked(now.minus(Duration.ofHours(3)), now)).endsWith("3 hours ago")
    }

    private fun endpoint(): String {
        val running = server ?: HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).also { made ->
            made.createContext("/chat/completions") { exchange ->
                received += exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
                val bytes = """{"choices":[{"message":{"role":"assistant","content":"Done."}}],"usage":{"prompt_tokens":9,"completion_tokens":1}}"""
                    .toByteArray(StandardCharsets.UTF_8)
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
    }
}
