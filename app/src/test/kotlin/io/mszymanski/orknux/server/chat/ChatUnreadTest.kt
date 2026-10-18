package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
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
import java.util.UUID

/**
 * How many answers a chat holds that its owner has not seen, beside its name.
 *
 * Answers only - what the person wrote is never news to them - counted from
 * the thread against what was there when they last had the chat open.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ChatUnreadTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val sessions: ChatSessionRepository,
    @Autowired val history: ChatMemoryRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun workspace() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "unread-${System.nanoTime()}")).id)
    }

    private fun chat(): ChatSession =
        sessions.save(ChatSession(workspaceId = workspaceId, conversationId = UUID.randomUUID().toString(), title = "Unread", userId = "alice"))

    private fun unread(id: Long): Int = graphQlTester.document("{ chatSessions(workspaceId: $workspaceId) { id unreadCount } }")
        .execute().path("chatSessions").entityList(Map::class.java).get()
        .single { it["id"].toString() == id.toString() }["unreadCount"] as Int

    @Test
    fun `answers that arrive are counted until the chat is read, and the person's own words never are`() {
        val chat = chat()
        val id = requireNotNull(chat.id)
        history.saveAll(chat.conversationId, listOf(UserMessage("hi"), AssistantMessage("hello"), AssistantMessage("and more")))

        graphQlTester.document("{ chatSession(id: $id) { unreadCount } }").execute()
            .path("chatSession.unreadCount").entity(Int::class.java).isEqualTo(2)
        org.assertj.core.api.Assertions.assertThat(unread(id)).isEqualTo(2)

        graphQlTester.document("mutation { markChatRead(id: $id) { unreadCount } }").execute()
            .path("markChatRead.unreadCount").entity(Int::class.java).isEqualTo(0)
        org.assertj.core.api.Assertions.assertThat(unread(id)).isEqualTo(0)

        // The person speaks: nothing new for them. The answer that follows is.
        history.saveAll(
            chat.conversationId,
            listOf(UserMessage("hi"), AssistantMessage("hello"), AssistantMessage("and more"), UserMessage("thanks"), AssistantMessage("welcome")),
        )
        org.assertj.core.api.Assertions.assertThat(unread(id)).isEqualTo(1)

        // A summary that made the thread shorter does not leave the count stuck below the old mark.
        graphQlTester.document("mutation { markChatRead(id: $id) { unreadCount } }").execute()
        history.saveAll(chat.conversationId, listOf(AssistantMessage("summary"), UserMessage("next")))
        org.assertj.core.api.Assertions.assertThat(unread(id)).isEqualTo(0)
        graphQlTester.document("mutation { markChatRead(id: $id) { unreadCount } }").execute()
        history.saveAll(chat.conversationId, listOf(AssistantMessage("summary"), UserMessage("next"), AssistantMessage("answer")))
        org.assertj.core.api.Assertions.assertThat(unread(id)).isEqualTo(1)
    }

    @Test
    @WithMockUser(username = "bob", roles = ["ADMINS"])
    fun `only the chat's owner may mark it read`() {
        val id = requireNotNull(chat().id)
        graphQlTester.document("mutation { markChatRead(id: $id) { unreadCount } }").execute()
            .errors().satisfy { org.assertj.core.api.Assertions.assertThat(it).isNotEmpty() }
    }
}
