package io.mszymanski.orknux.server.watcher

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentTool
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.llm.SessionEventRepository
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A workflow agent that sets a watcher hands its answer on at once, and the
 * watcher wakes it later for a turn of its own. Issue #618.
 *
 * #606 parked the step for as long as the watcher ran, so the node after it
 * waited on something that could take a week. The step completes now, and when
 * the watcher ends, `WatcherFollowUp` wakes the agent in the same session -
 * told that nobody is waiting on the turn, so whatever it has to say it says
 * with its tools.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class WatcherStepWakeTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val agents: AgentRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val lines: LlmSessionEventRepository,
    @Autowired val inbox: SessionEventRepository,
    @Autowired val agentTools: AgentToolRepository,
    @Autowired val watchers: WatcherRepository,
    @Autowired val service: WatcherService,
    @Autowired val watcherSettings: WatcherSettings,
) {

    private var workspaceId: Long = 0
    private var workflowId: Long = 0
    private lateinit var server: HttpServer

    /** What the model was sent, one body per call, in order. */
    private val asked = CopyOnWriteArrayList<String>()

    /** What watcher_set is called with beyond the basics; the agent-check test adds its interval. */
    private var extra = ""

    /** How many times the woken turn is refused with a rate limit before it is answered. */
    private var rateLimited = 0

    /** What the agent node carries beyond its agent; the retry test adds a policy. */
    private var policy = ""

    @BeforeEach
    fun reset() {
        watchers.deleteAll()
        agentTools.deleteAll()
        steps.deleteAll()
        executions.deleteAll()
        workflows.deleteAll()
        agents.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        inbox.deleteAll()
        lines.deleteAll()
        sessions.deleteAll()
        workspaces.deleteAll()
        asked.clear()
        extra = ""
        rateLimited = 0
        policy = ""

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Watching" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
        watcherSettings.setMinAgentCheckSeconds(watcherSettings.minAgentCheckSecondsConfigured(), "alice")
    }

    @Test
    fun `the step hands its answer on at once, and the firing wakes the agent for a turn of its own`() {
        val executionId = start()

        // Completed with what the agent said, the watcher still running behind it.
        assertThat(executions.findById(executionId).orElseThrow().status).isEqualTo(ExecutionStatus.COMPLETED)
        val step = steps.findAll().single { it.nodeKey == "think" }
        assertThat(step.output).contains("Watching the build.")
        assertThat(step.agentSleeps).isZero()
        assertThat(watchers.findAll().single().status).isEqualTo(WatcherStatus.ACTIVE)

        buildIs("done")
        due()
        assertThat(service.tick()).isEqualTo(1)

        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            assertThat(asked.last()).contains("fired. You asked").contains("nobody is waiting on this turn")
        }
        // The step is history; the follow-up changed nothing on it.
        assertThat(steps.findAll().single { it.nodeKey == "think" }.output).contains("Watching the build.")
    }

    /**
     * Shown the result while the condition has not matched, the agent changes
     * the watcher - the condition it guessed at before it had seen a result.
     */
    @Test
    fun `a look the agent asked for shows it the result, and it can change the watcher`() {
        watcherSettings.setMinAgentCheckSeconds(15, "alice")
        extra = ""","agent_check_interval_seconds":15"""
        start()
        val watcher = watchers.findAll().single()
        assertThat(watcher.agentCheckIntervalSeconds).isEqualTo(15)

        watcher.nextAgentCheckAt = OffsetDateTime.now().minusSeconds(1)
        watchers.save(watcher)
        due()
        assertThat(service.tick()).isEqualTo(1)

        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            assertThat(asked.any { "the look you asked for" in it && "running" in it }).isTrue()
            assertThat(watchers.findAll().single().condition).isEqualTo("building")
        }
        assertThat(watchers.findAll().single().status).isEqualTo(WatcherStatus.ACTIVE)
    }

    /**
     * A woken turn that meets a rate limit is asked again, as the step's would
     * have been. It was answered once and dropped, so the agent never acted on
     * what its watcher found.
     */
    @Test
    fun `a woken turn refused by a rate limit is retried by the step's policy`() {
        policy = "retryAttempts: 3, retryBackoffSeconds: 1"
        rateLimited = 1
        start()

        buildIs("done")
        due()
        assertThat(service.tick()).isEqualTo(1)

        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            assertThat(lines.findAll().any { "Build 41 is done." in (it.content ?: "") }).isTrue()
        }
        assertThat(rateLimited).isZero()
    }

    /** Without a policy on the node there is nothing to retry by: one attempt, as the step would get. */
    @Test
    fun `a woken turn on a node without a policy is asked once`() {
        rateLimited = 1
        start()

        buildIs("done")
        due()
        assertThat(service.tick()).isEqualTo(1)

        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            assertThat(asked.count { "fired. You asked" in it }).isEqualTo(1)
        }
        Thread.sleep(1500)
        assertThat(asked.count { "fired. You asked" in it }).isEqualTo(1)
    }

    /** Below the installation's floor is refused, in words the model can act on. */
    @Test
    fun `an agent check more often than the installation allows is refused`() {
        watcherSettings.setMinAgentCheckSeconds(600, "alice")
        extra = ""","agent_check_interval_seconds":30"""
        start()
        assertThat(watchers.findAll()).isEmpty()
        assertThat(asked.any { "agent_check_interval_seconds must be at least 600" in it }).isTrue()
    }

    private fun start(): Long {
        val modelId = model(serve())
        buildIs("running")
        val agent = agents.save(
            Agent(
                workspaceId = workspaceId, name = "Responder", type = AgentType.LLM, modelId = modelId,
                systemPrompt = "You are the responder.", tools = mutableListOf("buildStatus"),
            ),
        )
        graph(requireNotNull(agent.id))
        return graphQlTester.document(
            """mutation { startExecution(workspaceId: $workspaceId, workflowId: $workflowId, input: "{}") { id } }""",
        ).execute().path("startExecution.id").entity(Long::class.java).get()
    }

    private fun buildIs(status: String) {
        val source = "export default async function buildStatus() { return { status: '$status', build: 41 }; }"
        val tool = agentTools.findAll().singleOrNull { it.name == "buildStatus" }
            ?: AgentTool(workspaceId = workspaceId, name = "buildStatus", source = source, typescript = source)
        tool.source = source
        tool.typescript = source
        agentTools.save(tool)
    }

    private fun due() {
        val watcher = watchers.findAll().single()
        watcher.nextCheckAt = OffsetDateTime.now().minusSeconds(1)
        watchers.save(watcher)
    }

    private fun graph(agentId: Long) {
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "talk", kind: SESSION, name: "the conversation", x: 0, y: 0,
                    mappings: [
                      { name: "sessionKeyPrefix", expression: "node", mode: VALUE },
                      { name: "sessionKey", expression: "one", mode: VALUE }
                    ] },
                  { key: "think", kind: AGENT, name: "Responder", agentId: $agentId, x: 200, y: 0${if (policy.isEmpty()) "" else ", $policy"} }
                ],
                edges: [{ source: "talk", target: "think" }]
              }) { nodes { key } problems { message } }
            }
            """,
        ).execute()
    }

    /**
     * Sets a watcher on the build and answers; woken by the firing, says what
     * the build did; shown a look, changes the condition.
     */
    private fun serve(): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            asked += body
            if (body.contains("fired. You asked") && !body.contains("call_2") && rateLimited > 0) {
                rateLimited--
                val refused = """{"error":{"message":"Rate limit reached","code":"429"}}""".toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(429, refused.size.toLong())
                exchange.responseBody.use { it.write(refused) }
                exchange.close()
                return@createContext
            }
            val reply = when {
                body.contains("call_2") -> said("Changed it.")
                body.contains("the look you asked for") -> called(
                    "call_2",
                    "watcher_update",
                    """{\"watcher\":${watchers.findAll().single().id},\"condition_type\":\"regex\",\"condition\":\"building\"}""",
                )
                body.contains("fired. You asked") -> said("Build 41 is done.")
                body.contains("call_1") -> said("Watching the build.")
                else -> called(
                    "call_1",
                    "watcher_set",
                    """{\"tool\":\"buildStatus\",\"tool_result_path\":\"$\",\"condition_type\":\"regex\",""" +
                        """\"condition\":\"done\",\"interval_seconds\":15,\"timeout_seconds\":600${extra.replace("\"", "\\\"")}}""",
                )
            }.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
            exchange.close()
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}"
    }

    private fun called(id: String, name: String, arguments: String) = """
        {"choices":[{"message":{"role":"assistant","content":null,
          "tool_calls":[{"id":"$id","type":"function","function":{"name":"$name","arguments":"$arguments"}}]}}],
         "usage":{"prompt_tokens":9,"completion_tokens":4}}
    """.trimIndent()

    private fun said(content: String) =
        """{"choices":[{"message":{"role":"assistant","content":"$content"}}],"usage":{"prompt_tokens":9,"completion_tokens":4}}"""

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
