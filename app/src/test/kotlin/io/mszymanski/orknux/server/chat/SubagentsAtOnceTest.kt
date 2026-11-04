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
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * An agent's own number of asked agents working at once.
 *
 * From a Slack thread on an Azure deployment with a capacity of 200: the bot
 * started five audits together, every one of them failed on the token rate
 * limit, and the people in the thread had to tell it to work one at a time. The
 * installation's number held three of the five back - but each ask answered
 * "working", so the agent believed five were running, and an hour later it ran
 * two at once by mistake while its notes said one.
 *
 * So the number can be the agent's, and an ask held back says it is waiting -
 * in its own answer and in `agent_asks`. The stub model answers only once the
 * test lets it, so the first ask holds its permit while the second is made.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class SubagentsAtOnceTest(
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
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer
    private val release = CountDownLatch(1)

    @BeforeEach
    fun reset() {
        events.deleteAll()
        sessions.deleteAll()
        agents.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        settings.setAgentMaxSubagentsAtOnce(settings.agentMaxSubagentsAtOnceConfigured(), "alice")
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "at-once")).id)
    }

    @AfterEach
    fun stop() {
        release.countDown()
        settings.setAgentMaxSubagentsAtOnce(settings.agentMaxSubagentsAtOnceConfigured(), "alice")
        if (::server.isInitialized) server.stop(0)
    }

    /* ------------------------------------------------------------ fixture */

    /** A model that answers once [release] is let go, so an ask holds its permit until then. */
    private fun serve(): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 4)
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.createContext("/chat/completions") { exchange ->
            exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            release.await(10, TimeUnit.SECONDS)
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

    private fun asker(atOnce: Int?): Agent {
        val modelId = model()
        val searcher = agents.save(Agent(workspaceId = workspaceId, name = "Searcher", type = AgentType.LLM, modelId = modelId))
        return agents.save(
            Agent(
                workspaceId = workspaceId, name = "Bot", type = AgentType.LLM, modelId = modelId,
                agents = mutableListOf(requireNotNull(searcher.id)),
            ).apply { maxSubagentsAtOnce = atOnce },
        )
    }

    private fun ask(agent: Agent, parent: Long, question: String) =
        mapper.readTree(asking.run(agent, """{"agent":"Searcher","question":"$question","fresh":true}""", parent = parent))

    /** What agent_asks says, once the held-back ask has reached its wait. */
    private fun asksOnceWaiting(agent: Agent, parent: Long, waiting: Int) =
        generateSequence { mapper.readTree(asking.asked(agent, parent)) }
            .take(40)
            .onEach { if (it.path("waiting").asInt() != waiting) Thread.sleep(50) }
            .firstOrNull { it.path("waiting").asInt() == waiting }

    /* ---------------------------------------------------------- the gate */

    @Test
    fun `an agent allowed one at a time is told its second ask is waiting, and agent_asks says so`() {
        val bot = asker(atOnce = 1)
        val thread = recorder.open(workspaceId, "slack_thread", "t-${System.nanoTime()}")

        val first = ask(bot, thread, "Audit order-service")
        val second = ask(bot, thread, "Audit order-processing-service")

        assertThat(first.path("waiting").asBoolean()).isFalse()
        assertThat(second.path("waiting").asBoolean()).isTrue()
        assertThat(second.path("note").asString()).contains("waiting its turn").contains("1 asked agent working")

        val listed = asksOnceWaiting(bot, thread, waiting = 1)
        assertThat(listed).describedAs("agent_asks reports one ask waiting for a slot").isNotNull()
        assertThat(listed!!.path("working").asInt()).isEqualTo(2)
        assertThat(listed.path("atOnce").asInt()).isEqualTo(1)

        release.countDown()
        assertThat(asksOnceWaiting(bot, thread, waiting = 0)).isNotNull()
    }

    @Test
    fun `an agent with no number of its own follows the installation's`() {
        settings.setAgentMaxSubagentsAtOnce(3, "alice")
        val bot = asker(atOnce = null)
        val thread = recorder.open(workspaceId, "slack_thread", "t-${System.nanoTime()}")

        assertThat(asking.atOnceFor(bot)).isEqualTo(3)
        assertThat(ask(bot, thread, "One").path("waiting").asBoolean()).isFalse()
        assertThat(ask(bot, thread, "Two").path("waiting").asBoolean()).isFalse()
        assertThat(ask(bot, thread, "Three").path("waiting").asBoolean()).isFalse()
        assertThat(ask(bot, thread, "Four").path("waiting").asBoolean()).isTrue()
    }

    /** What it is told before it asks: in the tool's own description. */
    @Test
    fun `the ask tool says how many work at once`() {
        assertThat(asking.specFor(asker(atOnce = 1)).description).contains("Only one of them works at a time")
    }

    /* ----------------------------------------------------------- the API */

    @Test
    fun `the number is set on the agent, audited, cleared, and refused outside one to twenty`() {
        val bot = asker(atOnce = null)
        val id = requireNotNull(bot.id)
        fun save(value: String) = graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "Bot", maxSubagentsAtOnce: $value }) { maxSubagentsAtOnce } }""",
        ).execute()

        save("1").path("updateAgent.maxSubagentsAtOnce").entity(Int::class.java).isEqualTo(1)
        assertThat(audit.findAll().map { it.message }).contains("Agent Bot may have 1 asked agents working at once")

        save("null").path("updateAgent.maxSubagentsAtOnce").valueIsNull()
        assertThat(audit.findAll().map { it.message }).contains("Agent Bot asked agents at once reset to the installation's")

        save("0").errors().satisfy { errors ->
            assertThat(errors).hasSize(1)
            assertThat(errors.first().extensions["code"]).isEqualTo("SubagentsAtOnceOutOfRange")
        }
    }
}
