package io.mszymanski.orknux.server.agent

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.llm.SessionEventKind
import io.mszymanski.orknux.server.llm.SessionEventRepository
import io.mszymanski.orknux.server.workflow.WorkflowEdgeRepository
import io.mszymanski.orknux.server.workflow.WorkflowNodeRepository
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.ExecutionLogRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.StepStatus
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Two runs of one agent in one session: the second joins the first's turn.
 *
 * Two Slack mentions in one thread a minute apart were two turns at once, each
 * blind to the other, and each made the task it was asked for - two tasks for
 * one request. Here the first turn is held at its first request until the
 * second run has arrived, which is exactly that overlap.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class SessionTurnsTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val agents: AgentRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val logs: ExecutionLogRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val sessionEvents: LlmSessionEventRepository,
    @Autowired val inbox: SessionEventRepository,
    @Autowired val turns: SessionTurns,
    @Autowired val recorder: io.mszymanski.orknux.server.llm.LlmSessionRecorder,
) {

    private var workspaceId: Long = 0
    private var workflowId: Long = 0
    private lateinit var server: HttpServer
    private val received = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun reset() {
        logs.deleteAll()
        steps.deleteAll()
        executions.deleteAll()
        nodes.deleteAll()
        edges.deleteAll()
        assignments.deleteAll()
        workflows.deleteAll()
        agents.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        inbox.deleteAll()
        sessionEvents.deleteAll()
        sessions.deleteAll()
        workspaces.deleteAll()
        received.clear()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Slack desk" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
    }

    @AfterEach
    fun stop() { if (::server.isInitialized) server.stop(0) }

    /**
     * Read in time: the second message reaches the model in the first turn's
     * next round, the model is asked twice in all, and the second run hands
     * nothing on of its own.
     */
    @Test
    fun `a second run in a session whose turn is going joins that turn`() {
        val arrived = CompletableFuture<Unit>()
        graph(agent(model(serve(firstWaitsFor = arrived, firstCallsATool = true))))

        val first = startAside("""{"summary":"make a task of this"}""")
        awaitRequests(1)
        val second = startAside("""{"summary":"in OKO (Jira)"}""")
        awaitArrived()
        arrived.complete(Unit)
        first.get(30, TimeUnit.SECONDS)
        second.get(30, TimeUnit.SECONDS)

        assertThat(received).describedAs("one turn, two rounds: never a second turn beside it").hasSize(2)
        assertThat(received[1]).contains("in OKO (Jira)").contains("Another message arrived in this conversation")
        val joined = steps.findAll().single { it.executionId == second.get() }
        assertThat(joined.status).isEqualTo(StepStatus.COMPLETED)
        assertThat(joined.output.orEmpty()).isEmpty()
        assertThat(logs.findAll().filter { it.executionId == second.get() }.map { it.message })
            .anyMatch { it.contains("was still answering in this session") }
    }

    /**
     * Arrived after the model's last answer: the turn reads its inbox before it
     * ends, so it goes another round for it rather than leaving it unanswered -
     * still one turn, and still nothing handed on by the second run.
     */
    @Test
    fun `a message arriving as the going turn answers is still answered in that turn`() {
        val arrived = CompletableFuture<Unit>()
        graph(agent(model(serve(firstWaitsFor = arrived, firstCallsATool = false))))

        val first = startAside("""{"summary":"make a task of this"}""")
        awaitRequests(1)
        val second = startAside("""{"summary":"in OKO (Jira)"}""")
        awaitArrived()
        arrived.complete(Unit)
        first.get(30, TimeUnit.SECONDS)
        second.get(30, TimeUnit.SECONDS)

        assertThat(received).hasSize(2)
        assertThat(received[1]).contains("in OKO (Jira)").contains("Created OKO-1.")
        assertThat(steps.findAll().single { it.executionId == second.get() }.output.orEmpty()).isEmpty()
    }

    /**
     * Never read at all - the going turn ended without looking - and the run
     * that left it takes it back and answers it itself, after that turn and
     * never beside it. Driven directly, because a real turn always looks.
     */
    @Test
    fun `a message the going turn never read is taken back and answered after it`() {
        val session = recorder.open(workspaceId, "slack", "C42-1793018325.828889")
        val going = CompletableFuture<Unit>()
        val order = CopyOnWriteArrayList<String>()

        val first = CompletableFuture.supplyAsync {
            turns.oneAtATime(session, 1L, { "make a task of this" }) {
                order += "first starts"
                going.get(30, TimeUnit.SECONDS)
                order += "first ends"
                "first"
            }
        }
        while (order.isEmpty()) Thread.sleep(10)
        val second = CompletableFuture.supplyAsync {
            turns.oneAtATime(session, 2L, { "in OKO (Jira)" }) {
                order += "second runs"
                "second"
            }
        }
        awaitArrived()
        going.complete(Unit)

        assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(SessionTurns.Outcome.Ran("first"))
        assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(SessionTurns.Outcome.Ran("second"))
        assertThat(order).containsExactly("first starts", "first ends", "second runs")
        assertThat(inbox.findAll()).describedAs("taken back, so nothing is left to be read twice").isEmpty()
    }

    /** Started on a thread of its own, as a second Slack event is, with the test's user on it. */
    private fun startAside(input: String): CompletableFuture<Long> {
        val context = SecurityContextHolder.getContext()
        return CompletableFuture.supplyAsync {
            SecurityContextHolder.setContext(context)
            try {
                graphQlTester.document(
                    """
                    mutation(${'$'}input: String) {
                      startExecution(workspaceId: $workspaceId, workflowId: $workflowId, input: ${'$'}input) { id }
                    }
                    """,
                ).variable("input", input).execute().path("startExecution.id").entity(Long::class.java).get()
            } finally {
                SecurityContextHolder.clearContext()
            }
        }
    }

    private fun awaitRequests(count: Int) {
        val until = System.currentTimeMillis() + 20_000
        while (received.size < count && System.currentTimeMillis() < until) Thread.sleep(20)
        assertThat(received).hasSizeGreaterThanOrEqualTo(count)
    }

    /** Until the second run has left its message for the first turn. */
    private fun awaitArrived() {
        val until = System.currentTimeMillis() + 20_000
        while (inbox.findAll().none { it.kind == SessionEventKind.PERSON } && System.currentTimeMillis() < until) Thread.sleep(20)
        assertThat(inbox.findAll().map { it.kind }).contains(SessionEventKind.PERSON)
    }

    /**
     * A model whose first answer waits for [firstWaitsFor]: a call to the clock
     * where [firstCallsATool], so the turn has a next round to read the inbox in,
     * or a final answer where not. Every later request is answered "Created OKO-1."
     */
    private fun serve(firstWaitsFor: CompletableFuture<Unit>, firstCallsATool: Boolean): String {
        val calls = AtomicInteger()
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.createContext("/chat/completions") { exchange ->
            received += exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val body = if (calls.incrementAndGet() == 1) {
                firstWaitsFor.get(30, TimeUnit.SECONDS)
                if (firstCallsATool) {
                    """
                    {"choices":[{"message":{"role":"assistant","content":null,
                      "tool_calls":[{"id":"call_1","type":"function","function":{"name":"current_time","arguments":"{}"}}]}}],
                     "usage":{"prompt_tokens":9,"completion_tokens":4}}
                    """.trimIndent()
                } else {
                    """{"choices":[{"message":{"role":"assistant","content":"Created OKO-1."}}]}"""
                }
            } else {
                """{"choices":[{"message":{"role":"assistant","content":"Created OKO-1."}}]}"""
            }.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}"
    }

    /** A session node keyed the same for every run, as a Slack thread's is, and the agent after it. */
    private fun graph(agentId: Long) {
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "talk", kind: SESSION, name: "the thread", x: 0, y: 0,
                    mappings: [
                      { name: "sessionKeyPrefix", expression: "slack", mode: VALUE },
                      { name: "sessionKey", expression: "C42-1793018325.828889", mode: VALUE }
                    ] },
                  { key: "think", kind: AGENT, name: "OKO Agent", agentId: $agentId, x: 200, y: 0 }
                ],
                edges: [{ source: "talk", target: "think" }]
              }) { nodes { key } problems { message } }
            }
            """,
        ).execute()
    }

    private fun agent(modelId: Long): Long {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "OKO Agent", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "OKO Agent", modelId: $modelId, requiredSkills: [] }) { id } }""",
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
