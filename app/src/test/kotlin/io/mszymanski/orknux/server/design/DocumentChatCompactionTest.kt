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
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The document editor's conversation follows the workspace's compaction.
 *
 * The editor keeps its conversation in the browser and sends it up whole, so
 * it was never measured: a long one grew until the model refused it. Pinned:
 * past the workspace's line the older turns are summarised before the model is
 * asked, the model is sent the summary and the recent end only, and the answer
 * says so, for the editor to keep the shorter conversation; with compaction
 * off, everything is sent as it was.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class DocumentChatCompactionTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var server: HttpServer? = null
    private val received = CopyOnWriteArrayList<String>()

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

    @Test
    fun `past the workspace's line the older turns go to a summary, and the editor is told`() {
        val workspaceId = workspace(compactAfterTokens = 10)
        val (id, modelId) = documentAndModel(workspaceId)

        val compacted = graphQlTester.document(ask(id, modelId))
            .execute().errors().verify().path("askDocument.compacted").entity(Map::class.java).get()

        assertThat(compacted["summary"]).isEqualTo("They planned the launch note.")
        assertThat(compacted["replaced"]).isEqualTo(3)
        assertThat(compacted["kept"]).isEqualTo(6)

        val said = contents(received.first { mapper.readTree(it).has("tools") })
        assertThat(said).contains("They planned the launch note.", "Turn 9: now write it")
        assertThat(said.none { it.startsWith("Turn 1:") || it.startsWith("Turn 3:") }).isTrue()
    }

    @Test
    fun `with compaction off the whole conversation is sent`() {
        val workspaceId = workspace(compactAfterTokens = null)
        val (id, modelId) = documentAndModel(workspaceId)

        graphQlTester.document(ask(id, modelId))
            .execute().errors().verify().path("askDocument.compacted").valueIsNull()

        val said = contents(received.first { mapper.readTree(it).has("tools") })
        assertThat(said.any { it.startsWith("Turn 1:") }).isTrue()
        assertThat(received.none { it.contains("They planned") }).isTrue()
    }

    /** What each message of a request to the model said, as text. */
    private fun contents(body: String): List<String> {
        val out = mutableListOf<String>()
        for (message in mapper.readTree(body).path("messages")) out += message.path("content").stringValue() ?: ""
        return out
    }

    private fun ask(id: Long, modelId: Long): String {
        val turns = (1..9).joinToString(",\n") { n ->
            val role = if (n % 2 == 1) "user" else "assistant"
            val words = if (n == 9) "now write it" else "about the launch, " + "and more ".repeat(10)
            """{role: "$role", content: "Turn $n: $words"}"""
        }
        return """mutation { askDocument(id: $id, modelId: $modelId, messages: [$turns]) { answer compacted { summary replaced kept } } }"""
    }

    private fun workspace(compactAfterTokens: Int?): Long =
        requireNotNull(
            workspaces.save(Workspace(name = "document-compaction-${System.nanoTime()}").also { it.compactAfterTokens = compactAfterTokens }).id,
        )

    /** A document, and a model that summarises when it is offered no tools and otherwise answers at once. */
    private fun documentAndModel(workspaceId: Long): Pair<Long, Long> {
        val started = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        started.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            received += body
            val said = if (mapper.readTree(body).has("tools")) "Done." else "They planned the launch note."
            val bytes = """{"choices":[{"message":{"role":"assistant","content":"$said"}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}"""
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
        val id = graphQlTester.document("""mutation { createDocument(workspaceId: $workspaceId, name: "Launch") { id } }""")
            .execute().path("createDocument.id").entity(Long::class.java).get()
        return id to modelId
    }
}
