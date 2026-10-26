package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.Chattiness
import io.mszymanski.orknux.server.trigger.ProgressCommands
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `!progress-none` and its siblings: how much an agent says while it works,
 * set from the message for the turn it starts.
 *
 * The workflow half - a Slack mention through an agent node - is held in
 * `ForcedSkillsTest` and `StreamOutputTest`; this is the list itself and a chat.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ProgressCommandTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val agents: AgentRepository,
    @Autowired val sessions: ChatSessionRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer
    private val received = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun reset() {
        sessions.deleteAll()
        agents.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        received.clear()
    }

    @AfterEach
    fun stop() {
        if (::server.isInitialized) server.stop(0)
    }

    /** One command a level, named from the enum, so a level added is a command the same day. */
    @Test
    fun `there is one command for every level, and they are read from a message`() {
        assertThat(ProgressCommands.ALL.keys).containsExactlyElementsOf(Chattiness.entries.map { "progress-" + it.name.lowercase() })

        assertThat(ProgressCommands.levelIn("look into it !progress-none please", "!")).isEqualTo(Chattiness.NONE)
        // The last one said is the one meant.
        assertThat(ProgressCommands.levelIn("!progress-low no, !progress-high", "!")).isEqualTo(Chattiness.HIGH)
        assertThat(ProgressCommands.levelIn("::progress-low", "::")).isEqualTo(Chattiness.LOW)
        // A word that only starts like one, or one without the marker, is nothing.
        assertThat(ProgressCommands.levelIn("!progressive !review progress-none", "!")).isNull()
        assertThat(ProgressCommands.isOne("review")).isFalse()
    }

    /** The composer's menu reads them from here, so the interface never spells them again. */
    @Test
    fun `the commands are offered to the interface from the one list`() {
        graphQlTester.document("""query { progressCommands { key level } }""").execute()
            .path("progressCommands[*].key").entityList(String::class.java).containsExactly(*ProgressCommands.ALL.keys.toTypedArray())
            .path("progressCommands[*].level").entityList(String::class.java).containsExactly(*Chattiness.entries.map { it.name }.toTypedArray())
    }

    /**
     * Said with the message the model reads in the turn that asked, and only
     * that one: the next message is answered at the agent's own level again.
     */
    @Test
    fun `in a chat the level asked for is told to the model for that turn only`() {
        val agentId = agent(model(serve()))
        val chatId = startChat()
        graphQlTester.document("""mutation { chooseChatAgent(id: $chatId, agentId: $agentId) { agentId } }""").execute()

        send(chatId, "!progress-high how is the build?")
        send(chatId, "and the deploy?")

        assertThat(received).hasSize(2)
        assertThat(received[0]).contains("For this turn only").contains(ProgressCommands.note(Chattiness.HIGH).take(80))
        // The earlier turn is re-sent as it was sent, note and all, so the prompt stays the one already read;
        // the new message carries none of its own.
        assertThat(received[1].split("For this turn only").size - 1).isEqualTo(1)
        assertThat(received[1]).contains(""""content":"and the deploy?"""")
        // What the person wrote is kept as they wrote it.
        val said = graphQlTester.document("""query { chatMessages(id: $chatId) { content } }""").execute()
            .path("chatMessages[*].content").entityList(String::class.java).get()
        assertThat(said).contains("!progress-high how is the build?").noneMatch { it.contains("For this turn only") }
    }

    @Test
    fun `a message with no progress command is sent as it was`() {
        val agentId = agent(model(serve()))
        val chatId = startChat()
        graphQlTester.document("""mutation { chooseChatAgent(id: $chatId, agentId: $agentId) { agentId } }""").execute()

        send(chatId, "!review how is the build?")

        assertThat(received.single()).doesNotContain("For this turn only")
    }

    private fun send(chatId: Long, text: String) {
        graphQlTester.document("""mutation { sendChatMessage(id: $chatId, text: "$text") { answer { content } } }""")
            .execute().errors().verify()
    }

    private fun serve(): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            received += exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val bytes = """{"choices":[{"message":{"role":"assistant","content":"Green."}}],"usage":{"prompt_tokens":9,"completion_tokens":1}}"""
                .toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}"
    }

    private fun agent(modelId: Long): Long {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Builder", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        graphQlTester.document("""mutation { updateAgent(id: $id, input: { name: "Builder", modelId: $modelId }) { id } }""").execute()
        return id
    }

    private fun model(endpoint: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub", endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT })
               { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }

    private fun startChat(): Long = graphQlTester.document(
        """mutation { startChat(input: { workspaceId: $workspaceId, title: "Build" }) { id } }""",
    ).execute().path("startChat.id").entity(Long::class.java).get()
}
