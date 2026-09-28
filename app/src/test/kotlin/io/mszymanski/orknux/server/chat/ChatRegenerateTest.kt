package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.memory.ChatMemoryRepository
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * Asking the last answer again (issue #245).
 *
 * The model itself is not in this: no test here calls a provider, and the part
 * worth pinning is what happens to the conversation either side of the call.
 * Asking again has to take the answer off the thread — a conversation holding
 * two answers to one question was never had, and the second would be answering
 * the first — and it has to leave the one it took off somewhere a reader can
 * still get at it, since somebody who presses the button twice is often after
 * the answer they started with.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ChatRegenerateTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val chats: ChatService,
    @Autowired val sessions: ChatSessionRepository,
    @Autowired val takes: ChatAnswerTakeRepository,
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
        takes.deleteAll()
        sessions.deleteAll()
        agents.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        // The model is behind an agent because a chat is opened on one now
        // rather than on a bare model (issue #295). Nothing here calls either:
        // what a chat is pointed at only has to be something, so that the chats
        // these tests regenerate can be started at all.
        agent("Responder", model("Gemma"))
    }

    @Test
    fun `asking again hands the model the question without its own answer under it`() {
        val id = startChat()
        said(id, "What does the execution module do?", "It runs things.")

        val again = chats.beginRegenerate(id)

        // The question, and nothing the chat said after it - under the
        // briefing every request opens with, which is not part of the thread.
        val said = again.turns.filterNot { it.role == "system" }
        assertThat(said.map { it.role }).containsExactly("user")
        assertThat(said.single().content).isEqualTo("What does the execution module do?")
        // And the thread it will be answered into ends on the question too.
        assertThat(history.findByConversationId(conversation(id))).hasSize(1)
    }

    @Test
    fun `what it said the first time is kept, and read back beside the answer that stands`() {
        val id = startChat()
        said(id, "Name three things", "Alpha, Beta, Gamma.")

        chats.beginRegenerate(id)
        chats.finishSend(id, "Alpha, Beta and Gamma.")

        graphQlTester.document("""{ chatMessages(id: $id) { role content takes } }""")
            .execute()
            .path("chatMessages[*].role").entityList(String::class.java).containsExactly("user", "assistant")
            // The one that stands is the newest.
            .path("chatMessages[1].content").entity(String::class.java).isEqualTo("Alpha, Beta and Gamma.")
            // And the one it displaced is still there to go back to.
            .path("chatMessages[1].takes").entityList(String::class.java)
            .containsExactly("Alpha, Beta, Gamma.")
    }

    @Test
    fun `every take is kept, oldest first, and only the newest one answers`() {
        val id = startChat()
        said(id, "Try again", "First.")

        chats.beginRegenerate(id)
        chats.finishSend(id, "Second.")
        chats.beginRegenerate(id)
        chats.finishSend(id, "Third.")

        graphQlTester.document("""{ chatMessages(id: $id) { content takes } }""")
            .execute()
            .path("chatMessages[1].content").entity(String::class.java).isEqualTo("Third.")
            .path("chatMessages[1].takes").entityList(String::class.java)
            .containsExactly("First.", "Second.")

        // One row per take and not one per answer: the answer that stands is in
        // the thread, which is the only place it belongs.
        assertThat(takes.findAll()).hasSize(2)
    }

    /**
     * Only the answer the chat ends on. Anything earlier has been answered on
     * top of, and a different answer there would rewrite what the turns after
     * it were replying to.
     */
    @Test
    fun `an answer with a question after it cannot be asked again`() {
        val id = startChat()
        said(id, "First question", "First answer")
        history.saveAll(conversation(id), history.findByConversationId(conversation(id)) + UserMessage("And also?"))

        assertThatThrownBy { chats.beginRegenerate(id) }
            .isInstanceOf(ChatNothingToRegenerateException::class.java)
            .hasMessageContaining("not an answer")

        // Nothing was taken off, and nothing was written down.
        assertThat(history.findByConversationId(conversation(id))).hasSize(3)
        assertThat(takes.findAll()).isEmpty()
    }

    @Test
    fun `a chat nobody has said anything in has nothing to ask again`() {
        val id = startChat()

        assertThatThrownBy { chats.beginRegenerate(id) }
            .isInstanceOf(ChatNothingToRegenerateException::class.java)
    }

    /**
     * The provider refused, so the answer goes back.
     *
     * A chat left ending on the question is worse than the answer somebody did
     * not like, and it is not what they asked for.
     */
    @Test
    fun `an answer that never arrived leaves the old one exactly where it was`() {
        val id = startChat()
        said(id, "Explain it", "Because of the cache.")

        chats.beginRegenerate(id)
        chats.abandonRegenerate(id)

        val thread = history.findByConversationId(conversation(id))
        assertThat(thread.map { it.text }).containsExactly("Explain it", "Because of the cache.")
        // Nothing was displaced in the end, so there is no earlier take to show.
        assertThat(takes.findAll()).isEmpty()
        graphQlTester.document("""{ chatMessages(id: $id) { takes } }""")
            .execute().path("chatMessages[1].takes").entityList(String::class.java).hasSize(0)
    }

    @Test
    fun `deleting the chat takes its earlier answers with it`() {
        val id = startChat()
        said(id, "Say something", "Something.")
        chats.beginRegenerate(id)
        chats.finishSend(id, "Something else.")
        assertThat(takes.findAll()).hasSize(1)

        graphQlTester.document("""mutation { deleteChat(id: $id) }""")
            .execute().path("deleteChat").entity(Boolean::class.java).isEqualTo(true)

        assertThat(takes.findAll()).isEmpty()
    }

    /** One exchange, written the way the store is written. */
    private fun said(id: Long, question: String, answer: String) {
        history.saveAll(conversation(id), listOf(UserMessage(question), AssistantMessage(answer)))
    }

    private fun conversation(id: Long): String = requireNotNull(chats.session(id)).conversationId

    private fun startChat(): Long = graphQlTester.document(
        """mutation { startChat(input: { workspaceId: $workspaceId, title: "Regenerate" }) { id } }""",
    ).execute().path("startChat.id").entity(Long::class.java).get()

    private fun model(name: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Local $name", endpoint: "http://localhost:9/v1", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()

        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "$name", modelId: "$name", kind: CHAT })
               { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }

    private fun agent(name: String, modelId: Long): Long {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "$name", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()

        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "$name", modelId: $modelId }) { id } }""",
        ).execute()
        return id
    }
}
