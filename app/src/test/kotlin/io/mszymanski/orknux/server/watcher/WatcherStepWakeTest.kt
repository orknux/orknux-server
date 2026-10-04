package io.mszymanski.orknux.server.watcher

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentTool
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.attachment.InstallationSettings
import org.awaitility.Awaitility.await
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A workflow agent that set a watcher and finished is parked, and woken when
 * the watcher fires. Issue #606.
 *
 * A step that completed has nobody to wake: the firing would land in the
 * session's inbox and wait for whatever next runs there. So a step whose agent
 * ends its turn with a watcher still running parks, as one with an ask still
 * running does, and the firing - posted through the inbox - wakes it. The
 * step's wait is a minute, so a run that finishes in seconds is one the watcher
 * woke rather than the clock.
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
    @Autowired val settings: InstallationSettings,
    @Autowired val agentTools: AgentToolRepository,
    @Autowired val watchers: WatcherRepository,
    @Autowired val service: WatcherService,
) {

    private var workspaceId: Long = 0
    private var workflowId: Long = 0
    private lateinit var server: HttpServer

    /** What the asker was sent, one body per call, in order. */
    private val asked = CopyOnWriteArrayList<String>()

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
        settings.setAgentSleepSeconds(60, "alice")
        settings.setAgentSleepTimes(3, "alice")

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Watching" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
        settings.setAgentSleepSeconds(settings.agentSleepSecondsConfigured(), "alice")
        settings.setAgentSleepTimes(settings.agentSleepTimesConfigured(), "alice")
    }

    @Test
    fun `a step whose agent finished with a watcher running is woken when it fires`() {
        val modelId = model(serve())
        val status = "export default async function buildStatus() { return { status: 'done', build: 41 }; }"
        agentTools.save(AgentTool(workspaceId = workspaceId, name = "buildStatus", source = status, typescript = status))
        val watcherAgent = agents.save(
            Agent(
                workspaceId = workspaceId, name = "Responder", type = AgentType.LLM, modelId = modelId,
                systemPrompt = "You are the responder.", tools = mutableListOf("buildStatus"),
            ),
        )
        graph(requireNotNull(watcherAgent.id))

        val began = System.nanoTime()
        // On a thread of its own, since the inline engine waits a parked step out on the caller's - signed
        // in there too, which the mock user is not by itself.
        val signedIn = org.springframework.security.core.context.SecurityContextHolder.getContext()
        val running = CompletableFuture.supplyAsync {
            org.springframework.security.core.context.SecurityContextHolder.setContext(signedIn)
            graphQlTester.document(
                """mutation { startExecution(workspaceId: $workspaceId, workflowId: $workflowId, input: "{}") { id } }""",
            ).execute().path("startExecution.id").entity(Long::class.java).get()
        }

        // The agent has set its watcher and finished; the step is parked on it.
        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            assertThat(watchers.findAll()).hasSize(1)
            assertThat(steps.findAll().singleOrNull { it.nodeKey == "think" }?.agentSleeps).isEqualTo(1)
        }
        val watcher = watchers.findAll().single()
        watcher.nextCheckAt = OffsetDateTime.now().minusSeconds(1)
        watchers.save(watcher)
        assertThat(service.tick()).isEqualTo(1)

        val id = running.get(40, TimeUnit.SECONDS)
        val took = (System.nanoTime() - began) / 1_000_000_000
        assertThat(executions.findById(id).orElseThrow().status).isEqualTo(ExecutionStatus.COMPLETED)
        val step = steps.findAll().single { it.nodeKey == "think" }
        assertThat(step.output).contains("Build 41 is done.")
        assertThat(took).describedAs("seconds the run took").isLessThan(40)
        assertThat(asked.last()).contains("fired. You asked")
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
                  { key: "think", kind: AGENT, name: "Responder", agentId: $agentId, x: 200, y: 0 }
                ],
                edges: [{ source: "talk", target: "think" }]
              }) { nodes { key } problems { message } }
            }
            """,
        ).execute()
    }

    /** Sets a watcher on the build and finishes; handed the firing, says what the build did. */
    private fun serve(): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            asked += body
            val reply = when {
                body.contains("fired. You asked") -> said("Build 41 is done.")
                body.contains("\"tool_call_id\"") -> said("Watching the build.")
                else -> """
                    {"choices":[{"message":{"role":"assistant","content":null,
                      "tool_calls":[{"id":"call_1","type":"function","function":{"name":"watcher_set",
                        "arguments":"{\"tool\":\"buildStatus\",\"condition_type\":\"regex\",\"condition\":\"done\",\"interval_seconds\":15,\"timeout_seconds\":600}"}}]}}],
                     "usage":{"prompt_tokens":9,"completion_tokens":4}}
                """.trimIndent()
            }.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
            exchange.close()
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}"
    }

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
