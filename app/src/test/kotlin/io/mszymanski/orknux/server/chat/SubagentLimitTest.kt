package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
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

/**
 * How many other agents one agent may ask in a conversation is a number an
 * administrator sets, and a workspace may set its own. Issue #380.
 *
 * Each ask is a conversation of its own with its own model calls, started on
 * the asking model's say-so - so this is the number that bounds what one
 * question can fan out into. Counted against the sessions under the asker's
 * own; an agent that has spent them is told so and answers with what it has;
 * zero takes the tool off the table.
 *
 * The model is a stub that answers whatever it is asked, because what is
 * measured is the count and the refusal, not the answer.
 *
 * Makes a workspace, a provider, a model, two agents and their sessions, and
 * removes them. Puts the installation's number back to the file's own.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class SubagentLimitTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val asking: AgentRunTools,
    @Autowired val settings: InstallationSettings,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val events: LlmSessionEventRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer

    @BeforeEach
    fun reset() {
        events.deleteAll()
        sessions.deleteAll()
        agents.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        settings.setAgentMaxSubagents(settings.agentMaxSubagentsConfigured(), "alice")
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "asking")).id)
    }

    @AfterEach
    fun stop() {
        settings.setAgentMaxSubagents(settings.agentMaxSubagentsConfigured(), "alice")
        if (::server.isInitialized) server.stop(0)
    }

    /* ------------------------------------------------------------ fixture */

    private fun serve(): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val bytes = """{"choices":[{"message":{"role":"assistant","content":"Forty-two."}}],
               "usage":{"prompt_tokens":3,"completion_tokens":1}}""".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}"
    }

    private fun model(): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub", endpoint: "${serve()}", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT })
               { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }

    private fun asker(): Agent {
        val modelId = model()
        val librarian = agents.save(Agent(workspaceId = workspaceId, name = "Librarian", type = AgentType.LLM, modelId = modelId))
        return agents.save(
            Agent(
                workspaceId = workspaceId, name = "Planner", type = AgentType.LLM, modelId = modelId,
                agents = mutableListOf(requireNotNull(librarian.id)),
            ),
        )
    }

    private fun ask(agent: Agent, parent: Long, question: String) =
        asking.run(agent, """{"agent":"Librarian","question":"$question"}""", parent = parent)

    private fun workspaceAllows(count: Int?) {
        graphQlTester.document(
            """mutation { setWorkspaceAgentMaxSubagents(workspaceId: $workspaceId, count: ${count ?: "null"})
               { agentMaxSubagents agentMaxSubagentsDefault } }""",
        ).execute().errors().verify()
    }

    /* ------------------------------------------------------- the count --- */

    @Test
    fun `an agent that has asked as many as the installation allows is refused the next`() {
        settings.setAgentMaxSubagents(2, "alice")
        val agent = asker()
        val main = recorder.open(workspaceId, "chat", "planning")

        assertThat(ask(agent, main, "One?")).contains("Forty-two")
        assertThat(ask(agent, main, "Two?")).contains("Forty-two")
        val third = ask(agent, main, "Three?")

        assertThat(third).contains("asked 2 other agents").contains("Answer with what you have")
        assertThat(sessions.findByParentSessionIdOrderByCreatedAtAscIdAsc(main)).describedAs("no session for a refused ask").hasSize(2)
    }

    @Test
    fun `the workspace's own number wins over the installation's`() {
        settings.setAgentMaxSubagents(5, "alice")
        workspaceAllows(1)
        val agent = asker()
        val main = recorder.open(workspaceId, "chat", "planning")

        assertThat(ask(agent, main, "One?")).contains("Forty-two")
        assertThat(ask(agent, main, "Two?")).contains("asked 1 other agent")
    }

    @Test
    fun `and cleared, the workspace is on the installation's number again`() {
        settings.setAgentMaxSubagents(1, "alice")
        workspaceAllows(3)
        workspaceAllows(null)

        val agent = asker()
        assertThat(asking.limitFor(agent)).isEqualTo(1)
        assertThat(workspaces.findByIdOrNull(workspaceId)!!.agentMaxSubagents).isNull()
    }

    /** Counted per conversation: another conversation starts from nothing. */
    @Test
    fun `each conversation has the whole allowance to itself`() {
        settings.setAgentMaxSubagents(1, "alice")
        val agent = asker()
        val first = recorder.open(workspaceId, "chat", "one")
        val second = recorder.open(workspaceId, "chat", "two")

        assertThat(ask(agent, first, "One?")).contains("Forty-two")
        assertThat(ask(agent, first, "Again?")).contains("all this workspace allows")
        assertThat(ask(agent, second, "One?")).contains("Forty-two")
    }

    /* ---------------------------------------------------------- zero ----- */

    @Test
    fun `zero takes the tool off the table`() {
        val agent = asker()
        assertThat(asking.offered(agent)).describedAs("with the file's number").isTrue()

        workspaceAllows(0)
        assertThat(asking.offered(agent)).describedAs("the workspace at zero").isFalse()

        workspaceAllows(null)
        settings.setAgentMaxSubagents(0, "alice")
        assertThat(asking.offered(agent)).describedAs("the installation at zero").isFalse()
    }

    /* ------------------------------------------------------- the doors --- */

    @Test
    fun `an administrator sets the installation's number from the screen`() {
        graphQlTester.document("""mutation { setAgentMaxSubagents(count: 4) { agentMaxSubagents agentMaxSubagentsConfigured } }""")
            .execute()
            .path("setAgentMaxSubagents.agentMaxSubagents").entity(Int::class.java).isEqualTo(4)
            .path("setAgentMaxSubagents.agentMaxSubagentsConfigured").entity(Int::class.java)
            .isEqualTo(settings.agentMaxSubagentsConfigured())

        assertThat(settings.agentMaxSubagents()).isEqualTo(4)
    }

    @Test
    fun `a workspace shows the installation's number until it has its own`() {
        settings.setAgentMaxSubagents(7, "alice")

        graphQlTester.document("""query { workspace(id: $workspaceId) { agentMaxSubagents agentMaxSubagentsDefault } }""")
            .execute()
            .path("workspace.agentMaxSubagents").valueIsNull()
            .path("workspace.agentMaxSubagentsDefault").entity(Int::class.java).isEqualTo(7)

        workspaceAllows(2)
        graphQlTester.document("""query { workspace(id: $workspaceId) { agentMaxSubagents } }""")
            .execute()
            .path("workspace.agentMaxSubagents").entity(Int::class.java).isEqualTo(2)
    }

    @Test
    fun `a number outside none to a hundred is refused at both doors`() {
        graphQlTester.document("""mutation { setAgentMaxSubagents(count: 101) { agentMaxSubagents } }""")
            .execute().errors().expect { it.message!!.contains("between 0 and 100") }.verify()
        graphQlTester.document("""mutation { setWorkspaceAgentMaxSubagents(workspaceId: $workspaceId, count: -1) { id } }""")
            .execute().errors().expect { it.message!!.contains("between 0 and 100") }.verify()

        assertThat(settings.agentMaxSubagents()).isEqualTo(settings.agentMaxSubagentsConfigured())
        assertThat(workspaces.findByIdOrNull(workspaceId)!!.agentMaxSubagents).isNull()
    }
}
