package io.mszymanski.orknux.server.mcp

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.server.chat.ChatTurnSweeper
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.http.HttpStatus
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger

/**
 * The quick chat's questions are kept and answered on the server.
 *
 * The panel used to hold its conversation in the browser and wait on one
 * request for the answer, so a reload, a closed tab or a restart lost both.
 * Asserted: a question is answered with nobody holding a request open, the
 * conversation is there to draw again, New conversation puts it away, Stop
 * keeps no answer, and a question cut off by a restart is asked again.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class QuickChatAsksTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val api: QuickChatAPI,
    @Autowired val asks: QuickChatAskRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val sweeper: ChatTurnSweeper,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer
    private val asked = AtomicInteger()

    @BeforeEach
    fun reset() {
        asks.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "quick-${System.nanoTime()}")).id)
        asked.set(0)
    }

    @AfterEach
    fun stop() {
        if (::server.isInitialized) server.stop(0)
        asks.deleteAll()
    }

    @Test
    fun `a question is answered on the server and kept as the conversation`() {
        model(serveSaying("It ran at six."))

        val id = idOf(api.begin(workspaceId, request("Did the sync run?")).body)
        val answered = body(api.asked(workspaceId, id, 10).body)

        assertThat(answered["status"]).isEqualTo("ANSWERED")
        assertThat(answered["answer"]).isEqualTo("It ran at six.")

        val thread = body(api.thread(workspaceId).body)
        assertThat((thread["messages"] as List<*>).map { (it as Map<*, *>)["content"] })
            .containsExactly("Did the sync run?", "It ran at six.")
        assertThat(thread["pending"]).isNull()

        api.clear(workspaceId)
        assertThat(body(api.thread(workspaceId).body)["messages"] as List<*>).isEmpty()
    }

    @Test
    fun `a question stopped keeps no answer`() {
        model(serveSaying("Too late.", delayMillis = 1_500))

        val id = idOf(api.begin(workspaceId, request("Slow one?")).body)
        assertThat(body(api.thread(workspaceId).body)["pending"]).isEqualTo(id.toString())
        api.stop(workspaceId, id)
        Thread.sleep(2_500)

        val after = body(api.asked(workspaceId, id, 0).body)
        assertThat(after["status"]).isEqualTo("STOPPED")
        assertThat(after["answer"]).isNull()
    }

    @Test
    fun `a question cut off by a restart is asked again`() {
        model(serveSaying("Answered by the next server."))
        val cut = asks.save(
            QuickChatAsk(
                workspaceId = workspaceId,
                userId = "alice",
                messages = """[{"role":"user","content":"Still there?"}]""",
                beatAt = OffsetDateTime.now().minusMinutes(10),
            ),
        )

        assertThat(sweeper.sweep()).isEqualTo(1)

        val answered = body(api.asked(workspaceId, requireNotNull(cut.id), 10).body)
        assertThat(answered["status"]).isEqualTo("ANSWERED")
        assertThat(answered["answer"]).isEqualTo("Answered by the next server.")
        assertThat(asked.get()).isEqualTo(1)
    }

    /** A person's Claude Code is never asked the same thing twice by the server: it may have done it already. */
    @Test
    fun `a question to My Claude Code cut off by a restart is not asked again`() {
        model(serveSaying("Not this."))
        val cut = asks.save(
            QuickChatAsk(
                workspaceId = workspaceId,
                userId = "alice",
                messages = """[{"role":"user","content":"Rename the workflow"}]""",
                answeredBy = io.mszymanski.orknux.server.chat.ANSWERED_BY_CLAUDE_CODE,
                beatAt = OffsetDateTime.now().minusMinutes(10),
            ),
        )

        assertThat(sweeper.sweep()).isEqualTo(0)

        val after = body(api.asked(workspaceId, requireNotNull(cut.id), 0).body)
        assertThat(after["status"]).isEqualTo("FAILED")
        assertThat(after["error"] as String).contains("Claude Code", "not asked again")
        assertThat(asked.get()).isEqualTo(0)
    }

    @Test
    fun `a workspace with no quick chat model is refused before anything is kept`() {
        val refused = api.begin(workspaceId, request("Anyone?"))

        assertThat(refused.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(asks.findAll()).isEmpty()
    }

    @Test
    fun `nobody reads another person's question`() {
        model(serveSaying("Mine."))
        val theirs = asks.save(QuickChatAsk(workspaceId = workspaceId, userId = "bob", messages = "[]"))

        assertThat(api.asked(workspaceId, requireNotNull(theirs.id), 0).statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    private fun request(question: String) =
        QuickChatRequest(messages = listOf(QuickChatMessage("user", question)), page = PageContext("Executions", "/workspace/$workspaceId/executions"))

    @Suppress("UNCHECKED_CAST")
    private fun body(said: Any?): Map<String, Any?> = said as Map<String, Any?>

    private fun idOf(said: Any?): Long = (body(said)["id"] as String).toLong()

    private fun model(endpoint: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub", endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().errors().verify().path("createModelProvider.id").entity(Long::class.java).get()

        return graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT
               }) { id } }""",
        ).execute().errors().verify().path("createModel.id").entity(Long::class.java).get()
    }

    private fun serveSaying(said: String, delayMillis: Long = 0): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.createContext("/chat/completions") { exchange ->
            exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            asked.incrementAndGet()
            if (delayMillis > 0) Thread.sleep(delayMillis)
            val bytes = """{"choices":[{"message":{"role":"assistant","content":"$said"}}]}""".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}"
    }
}
