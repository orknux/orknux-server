package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.llm.LlmSessionEventKind
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A round the provider could not answer is asked again, not reported.
 *
 * The model answer time limit became minutes rather than an hour, so a single
 * dropped or timed-out call is likelier - and a turn that gave up on the first
 * one would end work a second attempt finishes. A provider that could not be
 * reached is the same remedy as one that answered with nothing: the same round
 * goes back unchanged, up to the empty-answer retry bound. The stub here drops
 * the first call (the connection closes with nothing said) and answers the
 * retry, and the turn ends answered. Issue #644.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class TimeoutRetryTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val conversation: AgentConversation,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val events: LlmSessionEventRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer

    /** How many times the turn's own round reached the stub, and whether it has dropped one already. */
    private val turnCalls = AtomicInteger(0)
    private val dropped = AtomicBoolean(false)

    @BeforeEach
    fun reset() {
        agents.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "timeout")).id)
        turnCalls.set(0)
        dropped.set(false)
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `a round the provider could not answer is asked again, and the turn ends on the retry's answer`() {
        val agentId = agent("Builder", model(serve()))
        val agent = requireNotNull(agents.findByIdOrNull(agentId))
        val session = recorder.open(workspaceId, "test", "timeout-${System.nanoTime()}")

        val said = conversation.answer(
            requireNotNull(agent.modelId), agent,
            listOf(ChatTurn("system", "Work."), ChatTurn("user", "Build it.")), session,
        )

        // The turn asked again past the first, dropped call and finished on the second's answer.
        assertThat(said).isInstanceOf(ChatCompletion.Answered::class.java)
        assertThat((said as ChatCompletion.Answered).content).isEqualTo("Done.")
        assertThat(turnCalls.get()).isGreaterThanOrEqualTo(2)

        val notes = events.findAll()
            .filter { it.sessionId == session && it.kind == LlmSessionEventKind.SYSTEM }
            .mapNotNull { it.content }
        assertThat(notes).anyMatch { it.startsWith("The provider could not be reached") && it.contains("asked again") }
    }

    private fun answered(text: String) =
        """{"choices":[{"message":{"role":"assistant","content":"$text"}}],
           "usage":{"prompt_tokens":9,"completion_tokens":1}}"""

    private fun serve(): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            // Only the turn's own round, not any model check that reaches the stub
            // first. The first time the turn asks, the response headers promise a
            // body that never comes and the connection closes - what a provider
            // that fell over mid-answer looks like to the client - so the call
            // fails as unreachable; the retry is answered at once.
            if (body.contains("Build it.")) {
                turnCalls.incrementAndGet()
                if (dropped.compareAndSet(false, true)) {
                    // Headers and a few bytes go out, then the body stops short of
                    // what was promised and the connection closes: the client has
                    // already begun reading a response, so it does not retry under
                    // us - it surfaces the broken read, which is the unreachable
                    // failure the turn then asks again past.
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, 500) // promises 500 bytes
                    exchange.responseBody.write("""{"choices""".toByteArray(StandardCharsets.UTF_8))
                    exchange.responseBody.flush()
                    exchange.responseBody.close() // far short of 500, then drops
                    return@createContext
                }
            }
            val bytes = answered("Done.").toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
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
