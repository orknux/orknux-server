package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.server.llm.LlmSessionEventKind
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.security.Role
import io.mszymanski.orknux.server.security.RoleRepository
import io.mszymanski.orknux.server.security.RoleScope
import io.mszymanski.orknux.server.user.AppUser
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.InternalAuthentication
import io.mszymanski.orknux.server.user.UserType
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * A chat turn that breaks says so, in the session and on the page.
 *
 * A StackOverflowError in the answer's thread - a regular expression run over a long tool call - was not an
 * `Exception`, so nothing caught it: the thread died, the turn was dropped, the page was left holding the question,
 * and the only trace was a stack in the server's log. The agent's round is made to throw one here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ChatStreamFailureTest(
    @LocalServerPort val port: Int,
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val chats: ChatSessionRepository,
    @Autowired val events: LlmSessionEventRepository,
    @Autowired val users: AppUserRepository,
    @Autowired val roles: RoleRepository,
    @Autowired val internal: InternalAuthentication,
) {

    @MockitoSpyBean
    lateinit var client: ModelChatClient

    @Test
    fun `a turn that dies on an Error tells the page and leaves a note in its session`() {
        val workspaceId = requireNotNull(workspaces.save(Workspace(name = "chat-failure-${System.nanoTime()}")).id)
        val admins = roles.findByName(ADMINS)
            ?: roles.save(Role(name = ADMINS, scopes = mutableSetOf(RoleScope.ADMIN, RoleScope.USER)))
        val reader = users.findByUsername(READER)
            ?: users.save(AppUser(username = READER, displayName = "The Reader", type = UserType.INTERNAL, roles = mutableSetOf(admins)))
        val token = internal.mint(reader, "test-${System.nanoTime()}").second
        val modelId = model(workspaceId)
        val agentId = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Breaker", type: LLM }) { id } }""",
        ).execute().errors().verify().path("createAgent.id").entity(Long::class.java).get()
        graphQlTester.document("""mutation { updateAgent(id: $agentId, input: { name: "Breaker", modelId: $modelId }) { id } }""")
            .execute().errors().verify()
        val chatId = requireNotNull(
            chats.save(
                ChatSession(
                    workspaceId = workspaceId,
                    conversationId = UUID.randomUUID().toString(),
                    title = "Breaks",
                    userId = READER,
                    modelId = modelId,
                    agentId = agentId,
                ),
            ).id,
        )
        // Asked once while the turn is set up and again by the round, after the session is open: the second throws.
        Mockito.doCallRealMethod().doThrow(StackOverflowError()).`when`(client).toolLimit(modelId)

        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/api/chats/$chatId/stream"))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString("""{"text":"continue","attachmentIds":[],"voice":false}"""))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        assertThat(response.body()).contains("event: error").contains("StackOverflowError")
        val session = requireNotNull(chats.findById(chatId).get().llmSessionId) { "the chat opened no session" }
        // The turn is put back, as any failed one is: the question stays to be sent again.
        assertThat(events.findAll().filter { it.sessionId == session && it.kind == LlmSessionEventKind.SYSTEM }.map { it.content })
            .anySatisfy { assertThat(it).contains("StackOverflowError") }
    }

    private fun model(workspaceId: Long): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub", type: OPENAI, endpoint: "http://stub.invalid", secret: "sk-test"
               }) { id } }""",
        ).execute().errors().verify().path("createModelProvider.id").entity(Long::class.java).get()
        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT })
               { id } }""",
        ).execute().errors().verify().path("createModel.id").entity(Long::class.java).get()
    }

    private companion object {
        const val ADMINS = "Admins"
        const val READER = "failure-reader"
    }
}
