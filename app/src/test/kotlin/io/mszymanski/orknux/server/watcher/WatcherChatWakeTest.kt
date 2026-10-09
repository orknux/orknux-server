package io.mszymanski.orknux.server.watcher

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentTool
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.chat.ChatSessionRepository
import java.time.OffsetDateTime
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.llm.SessionDueSweeper
import io.mszymanski.orknux.server.llm.SessionEventRepository
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
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * A chat whose agent set a watcher answers again when the watcher fires, with
 * nobody typing. Issue #606.
 *
 * The agent calls `watcher_set` on a tool of its own and answers straight
 * away. The tick - by hand, as the suite runs without db-scheduler - calls the
 * tool, the result matches, the watcher posts to the session's inbox, and the
 * chat, idle by then, starts a turn of its own that reads the firing first and
 * answers from the result it carried.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class WatcherChatWakeTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val chats: ChatSessionRepository,
    @Autowired val history: ChatMemoryRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val lines: LlmSessionEventRepository,
    @Autowired val inbox: SessionEventRepository,
    @Autowired val sweeper: SessionDueSweeper,
    @Autowired val agentTools: AgentToolRepository,
    @Autowired val watchers: WatcherRepository,
    @Autowired val service: WatcherService,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer

    @BeforeEach
    fun reset() {
        chats.findAll().forEach { history.deleteByConversationId(it.conversationId) }
        chats.deleteAll()
        watchers.deleteAll()
        agentTools.deleteAll()
        inbox.deleteAll()
        lines.deleteAll()
        sessions.deleteAll()
        agents.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        sweeper.sweep()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `a watcher that fires after the turn wakes the chat, and it answers from the result`() {
        val chatId = chatWithAgent(serve())

        graphQlTester.document("""mutation { sendChatMessage(id: $chatId, text: "Tell me when the build is done") { millis } }""")
            .execute().path("sendChatMessage.millis").hasValue()
        val chat = chats.findById(chatId).orElseThrow()
        assertThat(assistantSaid(chat.conversationId)).containsExactly("Watching the build; I will tell you.")

        // Done now, after the watcher was set while it was still running.
        agentTools.findAll().single { it.name == "buildStatus" }.let {
            it.source = "export default async function buildStatus() { return { status: 'done', build: 41 }; }"; it.typescript = "export default async function buildStatus() { return { status: 'done', build: 41 }; }"; agentTools.save(it)
        }
        val watcher = watchers.findAll().single()
        assertThat(watcher.sessionId).isEqualTo(chat.llmSessionId)
        watcher.nextCheckAt = OffsetDateTime.now().minusSeconds(1)
        watchers.save(watcher)
        assertThat(service.tick()).isEqualTo(1)
        assertThat(watchers.findAll().single().status).isEqualTo(WatcherStatus.FIRED)

        await().atMost(Duration.ofSeconds(15)).untilAsserted {
            assertThat(assistantSaid(chat.conversationId))
                .containsExactly("Watching the build; I will tell you.", "Build 41 is done.")
        }
    }

    private fun assistantSaid(conversation: String): List<String> =
        history.findByConversationId(conversation).filter { it.messageType == MessageType.ASSISTANT }.map { it.text.orEmpty() }

    /** Sets a watcher, answers, and - handed the firing - says what it found. */
    private fun serve(): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val reply = when {
                body.contains("fired. You asked") -> said("Build 41 is done.")
                body.contains("\"tool_call_id\"") -> said("Watching the build; I will tell you.")
                else -> """
                    {"choices":[{"message":{"role":"assistant","content":null,
                      "tool_calls":[{"id":"call_1","type":"function","function":{"name":"watcher_set",
                        "arguments":"{\"tool\":\"buildStatus\",\"tool_result_path\":\"$\",\"condition_type\":\"regex\",\"condition\":\"done\",\"interval_seconds\":15,\"timeout_seconds\":600,\"agent_check_interval_seconds\":300,\"note\":\"the build\"}"}}]}}],
                     "usage":{"prompt_tokens":9,"completion_tokens":4}}
                """.trimIndent()
            }.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
            exchange.close()
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}"
    }

    private fun said(content: String) =
        """{"choices":[{"message":{"role":"assistant","content":"$content"}}],"usage":{"prompt_tokens":9,"completion_tokens":4}}"""

    private fun chatWithAgent(endpoint: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub", endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        val modelId = graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
        val agentId = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Worker", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        val status = "export default async function buildStatus() { return { status: 'running', build: 41 }; }"
        agentTools.save(AgentTool(workspaceId = workspaceId, name = "buildStatus", source = status, typescript = status))
        graphQlTester.document(
            """mutation { updateAgent(id: $agentId, input: { name: "Worker", modelId: $modelId, tools: ["buildStatus"] }) { id } }""",
        )
            .execute()
        val chatId = graphQlTester.document(
            """mutation { startChat(input: { workspaceId: $workspaceId, title: "Build" }) { id } }""",
        ).execute().path("startChat.id").entity(Long::class.java).get()
        graphQlTester.document("""mutation { chooseChatAgent(id: $chatId, agentId: $agentId) { id } }""")
            .execute().path("chooseChatAgent.id").hasValue()
        return chatId
    }
}
