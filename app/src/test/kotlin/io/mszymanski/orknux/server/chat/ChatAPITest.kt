package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.memory.ChatMemoryRepository
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.MessageType
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * Chats, and the history behind them.
 *
 * The history is Spring AI's store, so these go through it rather than around
 * it: what the API returns has to be what `ChatMemoryRepository` holds, because
 * that is the same store a workflow run will share between its agents.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ChatAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val sessions: ChatSessionRepository,
    @Autowired val history: ChatMemoryRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        sessions.findAll().forEach { history.deleteByConversationId(it.conversationId) }
        sessions.deleteAll()
        agents.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        agent("Responder")
    }

    @Test
    fun `a new chat has a conversation of its own, and nothing in it`() {
        val id = startChat("Debug API auth flow")

        graphQlTester.document("""{ chatMessages(id: $id) { role content } }""")
            .execute().path("chatMessages").entityList(Any::class.java).hasSize(0)

        val session = sessions.findAll().single()
        // A UUID, because Spring AI's column is VARCHAR(36).
        assertThat(session.conversationId).hasSize(36)
        assertThat(session.pinned).isFalse()
        assertThat(session.lastMessageAt).isNull()
    }

    @Test
    fun `the history comes back from Spring AI's store, in the order it was said`() {
        val id = startChat("Explain codebase structure")
        val conversation = sessions.findAll().single().conversationId

        // Written the way the store is written, which is how a workflow run
        // will write it when several agents share one conversation.
        history.saveAll(
            conversation,
            listOf(
                UserMessage("What does the execution module do?"),
                AssistantMessage("It carries a workflow out, step by step."),
                UserMessage("And the connection one?"),
            ),
        )

        graphQlTester.document("""{ chatMessages(id: $id) { role content } }""")
            .execute()
            .path("chatMessages[*].role").entityList(String::class.java)
            .containsExactly("user", "assistant", "user")
            .path("chatMessages[0].content").entity(String::class.java)
            .isEqualTo("What does the execution module do?")
    }

    @Test
    fun `pinning, renaming and the order the sidebar draws in`() {
        val first = startChat("Write unit tests")
        val second = startChat("Setup CI pipeline")

        graphQlTester.document("""mutation { setChatPinned(id: $second, pinned: true) { pinned } }""")
            .execute().path("setChatPinned.pinned").entity(Boolean::class.java).isEqualTo(true)
        graphQlTester.document("""mutation { renameChat(id: $first, title: "Write the unit tests") { title } }""")
            .execute().path("renameChat.title").entity(String::class.java).isEqualTo("Write the unit tests")

        // Pinned first, whatever was said in the other one.
        graphQlTester.document("""{ chatSessions(workspaceId: $workspaceId) { title pinned } }""")
            .execute()
            .path("chatSessions[*].title").entityList(String::class.java)
            .containsExactly("Setup CI pipeline", "Write the unit tests")
    }

    /**
     * A chat nobody spoke in sorts by when it was made, not above everything:
     * Postgres puts a null first in a descending order, and that buried the chat
     * somebody was talking in under every empty one they had ever opened.
     */
    @Test
    fun `a chat spoken in a moment ago sorts above an older one nothing was said in`() {
        val silent = startChat("Opened and left")
        val spoken = startChat("Talked in")
        assertThat(silent).isLessThan(spoken)
        sessions.findById(spoken).get().also {
            it.lastMessageAt = java.time.OffsetDateTime.now().plusSeconds(1)
            sessions.save(it)
        }

        graphQlTester.document("""{ chatSessions(workspaceId: $workspaceId) { title } }""")
            .execute()
            .path("chatSessions[*].title").entityList(String::class.java)
            .containsExactly("Talked in", "Opened and left")
    }

    @Test
    fun `deleting a chat takes its history with it`() {
        val id = startChat("Refactor payment module")
        val conversation = sessions.findAll().single().conversationId
        history.saveAll(conversation, listOf(UserMessage("Where does it start?")))
        assertThat(history.findByConversationId(conversation)).hasSize(1)

        graphQlTester.document("""mutation { deleteChat(id: $id) }""")
            .execute().path("deleteChat").entity(Boolean::class.java).isEqualTo(true)

        // Nothing keyed by that conversation should survive it.
        assertThat(history.findByConversationId(conversation)).isEmpty()
        assertThat(sessions.findAll()).isEmpty()
    }

    @Test
    fun `a chat with no model chosen says so rather than sending`() {
        val id = startChat("Database migration help")
        // Taken away rather than never given, because no door opens a chat
        // without a model any more. This is how a real one gets there: the model
        // it named was removed, and `chat_session.model_id` is left null.
        sessions.save(sessions.findAll().single().also { it.modelId = null })

        graphQlTester.document("""mutation { sendChatMessage(id: $id, text: "Hello") { millis } }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors.single().message).contains("no model to answer with")
            }

        // And nothing was recorded, because nothing was said.
        assertThat(history.findByConversationId(sessions.findAll().single().conversationId)).isEmpty()
    }

    @Test
    fun `a chat is its own, however visible the workspace is`() {
        val id = startChat("Review security scanner")
        // The same workspace, a different person: this is somebody's chat.
        sessions.findAll().single().let { it.title = it.title }

        graphQlTester.mutate().build()
        graphQlTester.document("""{ chatSession(id: $id) { title } }""")
            .execute().path("chatSession.title").entity(String::class.java).isEqualTo("Review security scanner")

        // Recorded against the caller, which is what the sidebar filters on.
        assertThat(sessions.findAll().single().userId).isEqualTo("alice")
    }

    @Test
    fun `Spring AI keeps the roles it was given`() {
        val id = startChat("Draft API documentation")
        val conversation = sessions.findAll().single().conversationId
        history.saveAll(conversation, listOf(UserMessage("hi"), AssistantMessage("hello")))

        val stored = history.findByConversationId(conversation)
        assertThat(stored.map { it.messageType })
            .containsExactly(MessageType.USER, MessageType.ASSISTANT)
        assertThat(id).isPositive()
    }

    private fun startChat(title: String): Long = graphQlTester.document(
        """mutation { startChat(input: { workspaceId: $workspaceId, title: "$title" }) { id } }""",
    ).execute().path("startChat.id").entity(Long::class.java).get()

    /**
     * The one thing this workspace needs before a chat can be started in it.
     *
     * None of these tests calls a provider, and none of them used to need a
     * model either. A chat is opened on an agent now rather than on a bare model
     * (issue #295), and a workspace with no agent to hand is refused, so the
     * fixture puts one there — with the model behind it, because an agent
     * without one cannot answer and is skipped.
     */
    private fun agent(name: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub", endpoint: "http://models.invalid", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()

        val modelId = graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT
               }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()

        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "$name", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()

        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "$name", modelId: $modelId }) { id } }""",
        ).execute()
        return id
    }
}
