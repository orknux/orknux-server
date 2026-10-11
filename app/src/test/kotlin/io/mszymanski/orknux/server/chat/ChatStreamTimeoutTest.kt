package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors

/**
 * A chat's answer is not cut off by the container's timeout for async requests.
 *
 * The stream is a `StreamingResponseBody`, which is an async request, and the
 * container ends every async request at `spring.mvc.async.request-timeout` -
 * 330 seconds, set for `orknux_news`'s wait. A model that thought for 637
 * seconds in one round had its stream closed under it at 330: no `done`, no
 * answer, and a page that went on showing the question until somebody reloaded
 * it. The turn itself finished and was kept, so only the reader was lost.
 *
 * Driven over a real socket because the timeout is the container's and nothing
 * short of a container has one: the timeout is set to two seconds here and the
 * stub model is silent for four before it answers. Before the fix the stream
 * ended at two seconds without its `done` frame.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["spring.mvc.async.request-timeout=2s"],
)
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ChatStreamTimeoutTest(
    @LocalServerPort val port: Int,
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val sessions: ChatSessionRepository,
    @Autowired val users: AppUserRepository,
    @Autowired val roles: RoleRepository,
    @Autowired val internal: InternalAuthentication,
) {

    private lateinit var server: HttpServer
    private lateinit var token: String
    private var workspaceId: Long = 0

    /** Nothing is deleted: a fixture that empties tables it does not own breaks its neighbours. */
    @BeforeEach
    fun arrive() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "chat-timeout-${System.nanoTime()}")).id)
        val admins = roles.findByName(ADMINS)
            ?: roles.save(Role(name = ADMINS, scopes = mutableSetOf(RoleScope.ADMIN, RoleScope.USER)))
        val reader = users.findByUsername(READER)
            ?: users.save(
                AppUser(username = READER, displayName = "The Reader", type = UserType.INTERNAL, roles = mutableSetOf(admins)),
            )
        token = internal.mint(reader, "test-${System.nanoTime()}").second
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `an answer slower than the async timeout still reaches the reader`() {
        val chatId = chat(model(slowModel()))

        val began = System.currentTimeMillis()
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/api/chats/$chatId/stream"))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString("""{"text":"Think hard","attachmentIds":[],"voice":false}"""))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val took = System.currentTimeMillis() - began

        assertThat(response.statusCode()).isEqualTo(200)
        // Before the fix: the stream ended at about three seconds - the timeout
        // and the relay's interrupt - holding only keep-alives.
        assertThat(response.body())
            .describedAs("the stream ended after ${took}ms without the answer")
            .contains("Finally.")
            .contains("event: done")
            // Kept alive while the model said nothing, so a proxy sees traffic.
            .contains(":\n\n")
        assertThat(took).describedAs("the stub must outlast the timeout, or this proves nothing").isGreaterThan(SILENT_MILLIS)
    }

    /** An OpenAI-shaped model that says nothing for [SILENT_MILLIS] and then answers. */
    private fun slowModel(): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/chat/completions") { exchange ->
            exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            Thread.sleep(SILENT_MILLIS)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out ->
                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"Finally.\"}}]}\n\ndata: [DONE]\n\n".toByteArray())
            }
            exchange.close()
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}"
    }

    /** A bare-model chat belonging to [READER], who is who the HTTP request signs in as. */
    private fun chat(modelId: Long): Long = requireNotNull(
        sessions.save(
            ChatSession(
                workspaceId = workspaceId,
                conversationId = UUID.randomUUID().toString(),
                title = "Slow",
                userId = READER,
                modelId = modelId,
                agentId = null,
            ),
        ).id,
    )

    private fun model(endpoint: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub", type: OPENAI, endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().errors().verify().path("createModelProvider.id").entity(Long::class.java).get()
        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT })
               { id } }""",
        ).execute().errors().verify().path("createModel.id").entity(Long::class.java).get()
    }

    private companion object {
        /** Twice the timeout set above. */
        const val SILENT_MILLIS = 4_000L
        const val ADMINS = "Admins"
        const val READER = "stream-reader"
    }
}
