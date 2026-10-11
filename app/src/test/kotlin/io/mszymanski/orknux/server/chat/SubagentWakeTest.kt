package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentTool
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.llm.SessionDueSweeper
import io.mszymanski.orknux.server.llm.SessionEventKind
import io.mszymanski.orknux.server.llm.SessionEventRepository
import io.mszymanski.orknux.server.watcher.WatcherRepository
import io.mszymanski.orknux.server.watcher.WatcherService
import io.mszymanski.orknux.server.watcher.WatcherStatus
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
import java.time.OffsetDateTime

/**
 * A subagent's reminder or watcher wakes the subagent, and what it then says
 * reaches the agent that asked it.
 *
 * A chat's agent asks a helper; the helper sets a reminder (or a watcher) and
 * answers that it will report back; the chat wakes on that answer as it always
 * has. Then the reminder comes due - the sweep, by hand - or the watcher fires
 * - the tick, by hand - with the helper's turn long over. The helper runs again
 * in its own session, reads what arrived, and answers; that answer lands in the
 * chat's inbox, and the chat, idle, wakes and says it.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class SubagentWakeTest(
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
    fun `a reminder a subagent set wakes it, and its answer reaches the agent that asked`() {
        val chatId = chatAskingHelper(serve(SET_TIMER, "The reminder you set"))
        graphQlTester.document("""mutation { sendChatMessage(id: $chatId, text: "Is the build green?") { millis } }""")
            .execute().path("sendChatMessage.millis").hasValue()
        // Read after the first message, which is what opens the chat's session.
        val chat = chats.findById(chatId).orElseThrow()
        // The helper's first answer, delivered to the chat the way it always was.
        await().atMost(Duration.ofSeconds(15)).untilAsserted {
            assertThat(assistantSaid(chat.conversationId)).contains("Helper is on it.")
        }
        val helperSession = sessions.findAll().single { it.parentSessionId == chat.llmSessionId }
        assertThat(inbox.findAll().filter { it.sessionId == helperSession.id && it.kind == SessionEventKind.TIMER })
            .singleElement().matches { it.deliveredAt == null }

        Thread.sleep(1200)
        sweeper.sweep()

        await().atMost(Duration.ofSeconds(15)).untilAsserted {
            assertThat(assistantSaid(chat.conversationId)).contains("The helper says it is green.")
        }
        assertThat(inbox.findAll().filter { it.sessionId == chat.llmSessionId })
            .anyMatch { it.body.contains("Helper has come back about") && it.body.contains("The build is green.") }
        assertThat(lines.findAll().filter { it.sessionId == helperSession.id }.mapNotNull { it.content })
            .anyMatch { it.contains("was started again by something it set") }
    }

    @Test
    fun `a watcher a subagent set wakes it rather than a turn whose answer goes nowhere`() {
        val chatId = chatAskingHelper(serve(SET_WATCHER, "fired. You asked"), withTool = true)
        graphQlTester.document("""mutation { sendChatMessage(id: $chatId, text: "Is the build green?") { millis } }""")
            .execute().path("sendChatMessage.millis").hasValue()
        // Read after the first message, which is what opens the chat's session.
        val chat = chats.findById(chatId).orElseThrow()
        await().atMost(Duration.ofSeconds(15)).untilAsserted {
            assertThat(assistantSaid(chat.conversationId)).contains("Helper is on it.")
        }
        val helperSession = sessions.findAll().single { it.parentSessionId == chat.llmSessionId }

        agentTools.findAll().single { it.name == "buildStatus" }.let {
            it.source = DONE
            it.typescript = DONE
            agentTools.save(it)
        }
        val watcher = watchers.findAll().single()
        assertThat(watcher.sessionId).isEqualTo(helperSession.id)
        watcher.nextCheckAt = OffsetDateTime.now().minusSeconds(1)
        watchers.save(watcher)
        assertThat(service.tick()).isEqualTo(1)
        assertThat(watchers.findAll().single().status).isEqualTo(WatcherStatus.FIRED)

        await().atMost(Duration.ofSeconds(15)).untilAsserted {
            assertThat(assistantSaid(chat.conversationId)).contains("The helper says it is green.")
        }
        assertThat(lines.findAll().filter { it.sessionId == helperSession.id }.mapNotNull { it.content })
            .noneMatch { it.contains("went no further") }
    }

    private fun assistantSaid(conversation: String): List<String> =
        history.findByConversationId(conversation).filter { it.messageType == MessageType.ASSISTANT }.map { it.text.orEmpty() }

    /**
     * One provider for both agents, told apart by their system prompts. The
     * asker asks the helper and passes on what it hears; the helper sets what
     * [setting] calls, answers that it will report back, and - handed [woken] -
     * says the build is green.
     */
    private fun serve(setting: String, woken: String): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val reply = if (body.contains("You are the helper.")) {
                when {
                    body.contains(woken) -> said("The build is green.")
                    body.contains("\"tool_call_id\"") -> said("I am watching the build and will report back.")
                    else -> call(setting)
                }
            } else {
                when {
                    body.contains("has come back about") -> said("The helper says it is green.")
                    body.contains("has answered what you asked") -> said("Helper is on it.")
                    body.contains("\"tool_call_id\"") -> said("I asked the helper.")
                    else -> call("""{"name":"ask_agent","arguments":"{\"agent\":\"Helper\",\"question\":\"Tell me when the build is green.\"}"}""")
                }
            }.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
            exchange.close()
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}"
    }

    private fun call(function: String) = """
        {"choices":[{"message":{"role":"assistant","content":null,
          "tool_calls":[{"id":"call_1","type":"function","function":$function}]}}],
         "usage":{"prompt_tokens":9,"completion_tokens":4}}
    """.trimIndent()

    private fun said(content: String) =
        """{"choices":[{"message":{"role":"assistant","content":"$content"}}],"usage":{"prompt_tokens":9,"completion_tokens":4}}"""

    private fun chatAskingHelper(endpoint: String, withTool: Boolean = false): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub", endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        val modelId = graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
        if (withTool) {
            agentTools.save(AgentTool(workspaceId = workspaceId, name = "buildStatus", source = RUNNING, typescript = RUNNING))
        }
        val helper = agents.save(
            Agent(
                workspaceId = workspaceId, name = "Helper", type = AgentType.LLM, modelId = modelId,
                systemPrompt = "You are the helper.",
                tools = (BuiltInTools.GRANTED + listOfNotNull("buildStatus".takeIf { withTool })).toMutableList(),
            ),
        )
        val asker = agents.save(
            Agent(
                workspaceId = workspaceId, name = "Asker", type = AgentType.LLM, modelId = modelId,
                systemPrompt = "You are the asker.", agents = mutableListOf(requireNotNull(helper.id)),
                tools = BuiltInTools.GRANTED.toMutableList(),
            ),
        )
        val chatId = graphQlTester.document(
            """mutation { startChat(input: { workspaceId: $workspaceId, title: "Build" }) { id } }""",
        ).execute().path("startChat.id").entity(Long::class.java).get()
        graphQlTester.document("""mutation { chooseChatAgent(id: $chatId, agentId: ${asker.id}) { id } }""")
            .execute().path("chooseChatAgent.id").hasValue()
        return chatId
    }

    private companion object {
        const val SET_TIMER =
            """{"name":"timer_set","arguments":"{\"seconds\":1,\"note\":\"check the build\"}"}"""
        const val SET_WATCHER =
            """{"name":"watcher_set","arguments":"{\"tool\":\"buildStatus\",\"tool_result_path\":\"$\",\"condition_type\":\"regex\",\"condition\":\"done\",\"interval_seconds\":15,\"timeout_seconds\":600,\"agent_check_interval_seconds\":300,\"note\":\"the build\"}"}"""
        const val RUNNING = "export default async function buildStatus() { return { status: 'running', build: 41 }; }"
        const val DONE = "export default async function buildStatus() { return { status: 'done', build: 41 }; }"
    }
}
