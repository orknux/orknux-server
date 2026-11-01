package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.interaction.InteractionRequestRepository
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRepository
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
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Plan and auto mode in a chat, issue #653: switched from the composer, plan
 * mode refusing whatever changes something and ending in a plan the person
 * approves, which hands the plan back and runs it with every tool. The tool
 * array is the conversation's and does not move with the mode; see
 * [io.mszymanski.orknux.server.llm.SessionFrames].
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ChatModesTest(
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
    @Autowired val requests: InteractionRequestRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0
    private var server: HttpServer? = null

    /** The tool names each request offered, in the order the requests came. */
    private val offered = CopyOnWriteArrayList<List<String>>()

    /** Each request's system turn, in the order they came. */
    private val briefed = CopyOnWriteArrayList<String>()

    /** And each request whole. */
    private val bodies = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun reset() {
        chats.findAll().forEach { history.deleteByConversationId(it.conversationId) }
        chats.deleteAll()
        requests.deleteAll()
        inbox.deleteAll()
        lines.deleteAll()
        sessions.deleteAll()
        agents.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "modes")).id)
    }

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

    @Test
    fun `a new chat starts in Auto, the mode is set and read back, and Default stores nothing`() {
        val chatId = chatWithAgent(serve())
        graphQlTester.document("{ chatSession(id: $chatId) { mode } }").execute()
            .path("chatSession.mode").entity(String::class.java).isEqualTo("AUTO")

        graphQlTester.document("mutation { setChatMode(id: $chatId, mode: PLAN) { mode } }").execute()
            .path("setChatMode.mode").entity(String::class.java).isEqualTo("PLAN")
        assertThat(chats.findById(chatId).orElseThrow().mode).isEqualTo(ChatMode.PLAN)
        graphQlTester.document("{ chatSessions(workspaceId: $workspaceId) { mode } }").execute()
            .path("chatSessions[0].mode").entity(String::class.java).isEqualTo("PLAN")

        graphQlTester.document("mutation { setChatMode(id: $chatId, mode: DEFAULT) { mode } }").execute()
            .path("setChatMode.mode").entity(String::class.java).isEqualTo("DEFAULT")
        assertThat(chats.findById(chatId).orElseThrow().mode).isNull()
    }

    @Test
    @WithMockUser(username = "bob", roles = ["ADMINS"])
    fun `somebody else's chat cannot be switched`() {
        val chatId = chatWithAgent(serve())
        val theirs = chats.findById(chatId).orElseThrow()
        chats.save(
            ChatSession(
                workspaceId = workspaceId, conversationId = java.util.UUID.randomUUID().toString(), title = "alice's",
                userId = "alice", agentId = theirs.agentId, modelId = theirs.modelId,
            ),
        ).let { alices ->
            graphQlTester.document("mutation { setChatMode(id: ${alices.id}, mode: AUTO) { mode } }").execute()
                .errors().satisfy { assertThat(it).isNotEmpty() }
            assertThat(chats.findById(requireNotNull(alices.id)).orElseThrow().mode).isNull()
        }
    }

    @Test
    fun `plan mode refuses what changes something, and ends in a plan the person approves`() {
        val chatId = chatWithAgent(serve())
        graphQlTester.document("mutation { setChatMode(id: $chatId, mode: PLAN) { mode } }").execute()

        graphQlTester.document("""mutation { sendChatMessage(id: $chatId, text: "Remind me to deploy") { millis } }""")
            .execute().path("sendChatMessage.millis").hasValue()
        val chat = chats.findById(chatId).orElseThrow()
        assertThat(assistantSaid(chat.conversationId)).containsExactly(PLAN_WAITING)

        /*
         * Offered: the way to present a plan, and everything else the agent
         * holds - the conversation's tool array is made once, and leaving plan
         * mode does not make it again. A reminder, which changes something, is
         * refused when it is called.
         */
        val first = offered.first()
        assertThat(first).contains(ChatModes.PRESENT, DateTools.NOW, TimerTools.SET)
        assertThat(briefed.first()).contains("plan mode").contains(ChatModes.PRESENT_SPEC.name)
        // The reminder the model asked for anyway was refused, not set.
        assertThat(inbox.findAll()).noneMatch { it.kind == io.mszymanski.orknux.server.llm.SessionEventKind.TIMER }

        // The plan is in the conversation as an approval, with its two answers.
        val session = requireNotNull(chat.llmSessionId)
        graphQlTester.document("{ sessionRequests(sessionId: $session) { id kind question askedBy allowsText options { id label } } }")
            .execute()
            .path("sessionRequests[0].kind").entity(String::class.java).isEqualTo(ChatModes.KIND)
            .path("sessionRequests[0].question").entity(String::class.java).isEqualTo(PLAN_TEXT)
            .path("sessionRequests[0].askedBy").entity(String::class.java).isEqualTo("Worker")
            .path("sessionRequests[0].options[*].id").entityList(String::class.java)
            .containsExactly(ChatModes.APPROVE, ChatModes.KEEP_PLANNING, ChatModes.REJECT)
        val requestId = requests.findAll().single().id

        // Approving leaves plan mode and hands the plan back; the chat wakes and carries it out with everything offered.
        graphQlTester.document("""mutation { answerRequest(id: $requestId, option: "${ChatModes.APPROVE}") { outcome } }""")
            .execute().path("answerRequest.outcome").entity(String::class.java).isEqualTo("Approved")
        assertThat(chats.findById(chatId).orElseThrow().mode).isNull()

        await().atMost(Duration.ofSeconds(15)).untilAsserted {
            assertThat(assistantSaid(chat.conversationId)).containsExactly(PLAN_WAITING, "Done: reminder set.")
        }
        // The same array and system turn throughout; that plan mode is over is said in a message.
        assertThat(offered.last()).isEqualTo(first).contains(TimerTools.SET)
        assertThat(briefed.last()).isEqualTo(briefed.first())
        assertThat(bodies.last()).contains("No longer so").contains("This conversation is in plan mode")
    }

    @Test
    fun `keeping on planning tells the agent so, with what the person wrote, and stays in plan mode`() {
        val chatId = chatWithAgent(serve())
        graphQlTester.document("mutation { setChatMode(id: $chatId, mode: PLAN) { mode } }").execute()
        graphQlTester.document("""mutation { sendChatMessage(id: $chatId, text: "Remind me to deploy") { millis } }""")
            .execute().path("sendChatMessage.millis").hasValue()
        val requestId = requests.findAll().single().id

        graphQlTester.document(
            """mutation { answerRequest(id: $requestId, options: ["${ChatModes.KEEP_PLANNING}"], text: "check the calendar first") { outcome } }""",
        ).execute().path("answerRequest.outcome").entity(String::class.java).isEqualTo("Keep planning, \"check the calendar first\"")
        assertThat(chats.findById(chatId).orElseThrow().mode).isEqualTo(ChatMode.PLAN)
        assertThat(inbox.findAll().map { it.body }).anyMatch { it.contains("keep planning") && it.contains("check the calendar first") }
        // And the chat wakes to it, still planning.
        val conversation = chats.findById(chatId).orElseThrow().conversationId
        await().atMost(Duration.ofSeconds(15)).untilAsserted { assertThat(assistantSaid(conversation)).hasSize(2) }
        assertThat(offered.last()).contains(ChatModes.PRESENT).isEqualTo(offered.first())
    }

    @Test
    fun `rejecting a plan tells the agent not to carry it out, and stays in plan mode`() {
        val chatId = chatWithAgent(serve())
        graphQlTester.document("mutation { setChatMode(id: $chatId, mode: PLAN) { mode } }").execute()
        graphQlTester.document("""mutation { sendChatMessage(id: $chatId, text: "Remind me to deploy") { millis } }""")
            .execute().path("sendChatMessage.millis").hasValue()
        val requestId = requests.findAll().single().id

        graphQlTester.document("""mutation { answerRequest(id: $requestId, option: "${ChatModes.REJECT}") { outcome } }""")
            .execute().path("answerRequest.outcome").entity(String::class.java).isEqualTo("Rejected")
        assertThat(chats.findById(chatId).orElseThrow().mode).isEqualTo(ChatMode.PLAN)
        assertThat(inbox.findAll().map { it.body }).anyMatch { it.contains("rejected your plan") && it.contains("Do not carry any of it out") }
    }

    @Test
    fun `auto mode is said in the briefing and changes nothing about what is offered`() {
        val chatId = chatWithAgent(serve())
        graphQlTester.document("mutation { setChatMode(id: $chatId, mode: AUTO) { mode } }").execute()
        graphQlTester.document("""mutation { sendChatMessage(id: $chatId, text: "Just answer") { millis } }""")
            .execute().path("sendChatMessage.millis").hasValue()
        assertThat(briefed.first()).contains("auto mode")
        assertThat(offered.first()).contains(TimerTools.SET).doesNotContain(ChatModes.PRESENT)
    }

    private fun assistantSaid(conversation: String): List<String> =
        history.findByConversationId(conversation).filter { it.messageType == MessageType.ASSISTANT }.map { it.text.orEmpty() }

    /**
     * In plan mode: asks for a reminder (which is refused), then presents a
     * plan, then says it did. Handed the approval, it says it is done. Outside
     * plan mode, with nothing to carry out, it just answers.
     */
    private fun serve(): String {
        val stub = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        stub.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val request = mapper.readTree(body)
            bodies += body
            offered += request.path("tools").values().map { it.path("function").path("name").stringValue() }
            briefed += request.path("messages").values().firstOrNull { it.path("role").stringValue() == "system" }
                ?.path("content")?.stringValue().orEmpty()
            val reply = when {
                body.contains("approved your plan") -> said("Done: reminder set.")
                // What Qwen did in chat 996, asked again after presenting: the turn ends at the plan, so it never is.
                body.contains("buttons to approve it") -> said("Plan approved, starting.")
                body.contains("it was not run") -> called(ChatModes.PRESENT, mapOf(ChatModes.PLAN to PLAN_TEXT))
                body.contains("in plan mode") -> called(TimerTools.SET, mapOf("seconds" to 60, "note" to "deploy"))
                else -> said("Answered.")
            }.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
            exchange.close()
        }
        stub.start()
        server = stub
        return "http://${stub.address.hostString}:${stub.address.port}"
    }

    private fun said(content: String) =
        """{"choices":[{"message":{"role":"assistant","content":"$content"}}],"usage":{"prompt_tokens":9,"completion_tokens":4}}"""

    private fun called(tool: String, arguments: Map<String, Any?>): String {
        val call = mapOf(
            "id" to "call_${offered.size}",
            "type" to "function",
            "function" to mapOf("name" to tool, "arguments" to mapper.writeValueAsString(arguments)),
        )
        return mapper.writeValueAsString(
            mapOf(
                "choices" to listOf(mapOf("message" to mapOf("role" to "assistant", "content" to null, "tool_calls" to listOf(call)))),
                "usage" to mapOf("prompt_tokens" to 9, "completion_tokens" to 4),
            ),
        )
    }

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
            """mutation { startChat(input: { workspaceId: $workspaceId, title: "Deploy" }) { id } }""",
        ).execute().path("startChat.id").entity(Long::class.java).get()
        graphQlTester.document("""mutation { chooseChatAgent(id: $chatId, agentId: $agentId) { id } }""")
            .execute().path("chooseChatAgent.id").hasValue()
        return chatId
    }

    private companion object {
        const val PLAN_TEXT = "1. Set a reminder for the deploy."

        /** What the turn says once it has put the plan to the person: the chat's own words, not the model's. */
        const val PLAN_WAITING = "I've put the plan to you above. I'll carry it out once you approve it."
    }

}
