package io.mszymanski.orknux.server.design

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
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

/**
 * The document editor's chat is answered whoever is waiting.
 *
 * It used to wait for the answer on the request that asked, so a reload threw
 * the answer away and the page came back with only the question. Asserted: the
 * question is answered with nobody holding the asking request, a page opened
 * again finds the question still being answered and reads its answer, somebody
 * else cannot read it, and one cut off by a restart says so instead of being
 * waited on for ever.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class EditorAsksTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val api: EditorAskAPI,
    @Autowired val asks: EditorAskRepository,
) {

    private var server: HttpServer? = null

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

    @Test
    fun `a question is answered with nobody waiting, and a page opened again finds it`() {
        val (id, modelId) = documentAndModel(delayMillis = 1_500)

        val asked = body(api.askDocument(id, DocumentAskRequest(modelId, listOf(DocumentChatTurnInput("user", "Write a title")))).body)
        val askId = (asked["id"] as String).toLong()

        // The page reloads: it asks what is being answered on this document, and follows it.
        assertThat(body(api.pendingOnDocument(id).body)["id"]).isEqualTo(askId.toString())
        val answered = body(api.asked(askId, 10).body)

        assertThat(answered["status"]).isEqualTo("ANSWERED")
        assertThat(answered["answer"] as String).startsWith("Done.")
        assertThat(answered["changed"]).isEqualTo(false)
        assertThat(body(api.pendingOnDocument(id).body)["id"]).isNull()
    }

    /** The sheet's, wireframe's and diagram's chats keep their questions the same way. */
    @Test
    fun `a sheet's, a wireframe's and a diagram's question are answered with nobody waiting`() {
        val (_, modelId, workspaceId) = documentAndModelIn(delayMillis = 0)
        val made = { kind: String -> graphQlTester.document("""mutation { create$kind(workspaceId: $workspaceId, name: "Ask") { id } }""")
            .execute().path("create$kind.id").entity(Long::class.java).get() }
        val turns = listOf(DocumentChatTurnInput("user", "Add a row"))
        val asked = listOf(
            api.askSheet(made("Sheet"), EditorAskRequest(modelId, turns)),
            api.askWireframe(made("Wireframe"), EditorAskRequest(modelId, turns)),
            api.askDiagram(made("Diagram"), EditorAskRequest(modelId, turns)),
        )

        asked.forEach { response ->
            val askId = (body(response.body)["id"] as String).toLong()
            val answered = body(api.asked(askId, 10).body)
            assertThat(answered["status"]).isEqualTo("ANSWERED")
            assertThat(answered["answer"] as String).startsWith("Done.")
            assertThat(answered["revision"]).isNotNull()
        }
        assertThat(api.askSheet(Long.MAX_VALUE, EditorAskRequest(modelId, turns)).statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    @WithMockUser(username = "bob", roles = ["ADMINS"])
    fun `nobody reads another person's question`() {
        val theirs = asks.save(EditorAsk(userId = "alice", kind = "document", itemId = 1))
        assertThat(api.asked(requireNotNull(theirs.id), 0).statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `a question cut off by a restart says so rather than being waited on`() {
        val cut = asks.save(EditorAsk(userId = "alice", kind = "document", itemId = 1, beatAt = OffsetDateTime.now().minusMinutes(5)))

        val read = body(api.asked(requireNotNull(cut.id), 0).body)

        assertThat(read["status"]).isEqualTo("FAILED")
        assertThat(read["error"]).isEqualTo(EditorAsks.CUT_OFF)
    }

    @Suppress("UNCHECKED_CAST")
    private fun body(said: Any?): Map<String, Any?> = said as Map<String, Any?>

    /** A document, and a model that answers after [delayMillis] without calling a tool. */
    private fun documentAndModel(delayMillis: Long): Pair<Long, Long> = documentAndModelIn(delayMillis).let { it.first to it.second }

    /** The same, and the workspace they are in. */
    private fun documentAndModelIn(delayMillis: Long): Triple<Long, Long, Long> {
        val workspaceId = requireNotNull(workspaces.save(Workspace(name = "editor-asks-${System.nanoTime()}")).id)
        val started = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        started.executor = java.util.concurrent.Executors.newCachedThreadPool()
        started.createContext("/chat/completions") { exchange ->
            exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            Thread.sleep(delayMillis)
            val bytes = """{"choices":[{"message":{"role":"assistant","content":"Done."}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}"""
                .toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        started.start()
        server = started
        val endpoint = "http://${started.address.hostString}:${started.address.port}"
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub ${System.nanoTime()}", endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        val modelId = graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
        val id = graphQlTester.document("""mutation { createDocument(workspaceId: $workspaceId, name: "Ask") { id } }""")
            .execute().path("createDocument.id").entity(Long::class.java).get()
        return Triple(id, modelId, workspaceId)
    }
}
