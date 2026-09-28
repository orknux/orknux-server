package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.AgentRepository
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
 * A chat whose agent set a reminder answers again when it comes due, with
 * nobody typing.
 *
 * The timer does not end the turn - the agent answers straight away - and when
 * the time is up the sweep announces it, and the chat, idle by then, starts a
 * turn of its own that reads the reminder first. What it answers lands in the
 * chat like any other answer.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ChatWakeTest(
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
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer

    @BeforeEach
    fun reset() {
        chats.findAll().forEach { history.deleteByConversationId(it.conversationId) }
        chats.deleteAll()
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
    fun `a reminder that comes due after the turn wakes the chat, and it answers`() {
        val chatId = chatWithAgent(serve())

        graphQlTester.document("""mutation { sendChatMessage(id: $chatId, text: "Check the build in a second") { millis } }""")
            .execute().path("sendChatMessage.millis").hasValue()
        val chat = chats.findById(chatId).orElseThrow()
        assertThat(assistantSaid(chat.conversationId)).containsExactly("Okay, I will look again in a moment.")

        Thread.sleep(1200)
        assertThat(sweeper.sweep()).isEqualTo(1)

        await().atMost(Duration.ofSeconds(15)).untilAsserted {
            assertThat(assistantSaid(chat.conversationId))
                .containsExactly("Okay, I will look again in a moment.", "Build checked: green.")
        }
    }

    private fun assistantSaid(conversation: String): List<String> =
        history.findByConversationId(conversation).filter { it.messageType == MessageType.ASSISTANT }.map { it.text.orEmpty() }

    /** Sets a one-second reminder, answers, and - handed the reminder - says what it found. */
    private fun serve(): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val reply = when {
                body.contains("The reminder you set") -> said("Build checked: green.")
                body.contains("\"tool_call_id\"") -> said("Okay, I will look again in a moment.")
                else -> """
                    {"choices":[{"message":{"role":"assistant","content":null,
                      "tool_calls":[{"id":"call_1","type":"function","function":{"name":"timer_set",
                        "arguments":"{\"seconds\":1,\"note\":\"check the build\"}"}}]}}],
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
        graphQlTester.document("""mutation { updateAgent(id: $agentId, input: { name: "Worker", modelId: $modelId }) { id } }""")
            .execute()
        val chatId = graphQlTester.document(
            """mutation { startChat(input: { workspaceId: $workspaceId, title: "Build" }) { id } }""",
        ).execute().path("startChat.id").entity(Long::class.java).get()
        graphQlTester.document("""mutation { chooseChatAgent(id: $chatId, agentId: $agentId) { id } }""")
            .execute().path("chooseChatAgent.id").hasValue()
        return chatId
    }
}
