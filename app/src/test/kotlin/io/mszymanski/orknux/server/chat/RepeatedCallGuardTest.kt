package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The repetition guard counts each call on its own. It used to count only a call
 * repeating the one just before it, so two calls taking turns - read, edit,
 * read, edit - never tripped it; a fix that counted every call alike would stop
 * a model doing several different things quickly and tell it it was repeating
 * itself.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class RepeatedCallGuardTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val conversation: AgentConversation,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val agents: AgentRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer
    private val script = ConcurrentLinkedQueue<String>()
    /** Every request the stub was sent, so what the model was told can be read back. */
    private val sent = ConcurrentLinkedQueue<String>()

    @BeforeEach
    fun reset() {
        agents.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "loops", maxRepeatedToolCalls = 3)).id)
        script.clear()
        sent.clear()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `two calls taking turns are caught`() {
        repeat(4) { round ->
            script += called("a$round", "skill_list", "{}")
            script += called("b$round", "skill_search", "{\\\"query\\\":\\\"deploy\\\"}")
        }
        run()
        assertThat(sent).anyMatch { it.contains("identical calls within") }
    }

    @Test
    fun `different calls in quick succession are not taken for a loop`() {
        repeat(8) { round -> script += called("c$round", "skill_search", "{\\\"query\\\":\\\"topic $round\\\"}") }
        run()
        assertThat(sent).noneMatch { it.contains("identical calls within") }
    }

    private fun run() {
        script += answered("Done.")
        val agentId = agent("Looper", model(serve()))
        val agent = requireNotNull(agents.findByIdOrNull(agentId))
        val session = recorder.open(workspaceId, "test", "loops-${System.nanoTime()}")
        conversation.answer(
            requireNotNull(agent.modelId), agent,
            listOf(ChatTurn("system", "Work."), ChatTurn("user", "Go.")), session,
        )
    }

    private fun called(id: String, name: String, arguments: String) =
        """{"choices":[{"message":{"role":"assistant","content":null,
           "tool_calls":[{"id":"$id","type":"function","function":{"name":"$name","arguments":"$arguments"}}]}}],
           "usage":{"prompt_tokens":7,"completion_tokens":2}}"""

    private fun answered(text: String) =
        """{"choices":[{"message":{"role":"assistant","content":"$text"}}],
           "usage":{"prompt_tokens":9,"completion_tokens":1}}"""

    private fun serve(): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            sent += exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val status = 200
            val body = script.poll() ?: answered("Out of script.")
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}"
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

    private fun model(endpoint: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub", endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()

        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT })
               { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }
}
