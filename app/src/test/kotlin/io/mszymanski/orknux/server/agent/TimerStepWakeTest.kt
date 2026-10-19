package io.mszymanski.orknux.server.agent

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.chat.BuiltInTools
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.llm.SessionEvent
import io.mszymanski.orknux.server.llm.SessionEventDue
import io.mszymanski.orknux.server.llm.SessionEventKind
import io.mszymanski.orknux.server.llm.SessionEventRepository
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.ExecutionLogRepository
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
import org.springframework.context.ApplicationEventPublisher
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A reminder an agent sets in a workflow step does not hold the step's answer.
 *
 * Seen on a Slack workflow: the agent answered, set `timer_set` to wake itself
 * the next morning, and the step parked for fifteen hours on the timer - so the
 * reply node after it, the one that posts to the thread, sent nothing until
 * then. A timer is a reminder for later work, not something this turn is owed:
 * the step completes with what the agent said, and when the reminder comes up
 * the agent is woken in the same session for a turn of its own, whose answer is
 * carried on to the reply node the way a watcher's is.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class TimerStepWakeTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val agents: AgentRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val runLog: ExecutionLogRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val lines: LlmSessionEventRepository,
    @Autowired val inbox: SessionEventRepository,
    @Autowired val settings: InstallationSettings,
    @Autowired val published: ApplicationEventPublisher,
) {

    private var workspaceId: Long = 0
    private var workflowId: Long = 0
    private lateinit var server: HttpServer

    /** What the model was sent, one body per call, in order. */
    private val asked = CopyOnWriteArrayList<String>()

    /** Whether the agent waits with finish_answer instead of setting a reminder. */
    private var sleeping = false

    @BeforeEach
    fun reset() {
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
        sleeping = false
        // Longer than the reminder, so the step could have parked on it.
        settings.setAgentSleepSeconds(60, "alice")
        settings.setAgentSleepTimes(3, "alice")

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Slack mention" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
        settings.setAgentSleepSeconds(settings.agentSleepSecondsConfigured(), "alice")
        settings.setAgentSleepTimes(settings.agentSleepTimesConfigured(), "alice")
    }

    @Test
    fun `a reminder the agent set does not hold its answer, and wakes it later for a turn of its own`() {
        val began = System.nanoTime()
        val first = start()
        val took = Duration.ofNanos(System.nanoTime() - began)

        // The run went straight through: the reply node had the answer at once.
        assertThat(executions.findById(first).orElseThrow().status).isEqualTo(ExecutionStatus.COMPLETED)
        val think = steps.findAll().single { it.executionId == first && it.nodeKey == "think" }
        assertThat(think.agentSleeps).isZero()
        assertThat(steps.findAll().single { it.executionId == first && it.nodeKey == "reply" }.input)
            .contains("Wake-up set for tomorrow.")
            .doesNotContain("Good morning")
        assertThat(took).isLessThan(Duration.ofSeconds(20))
        assertThat(runLog.findByExecutionIdOrderBySequenceAsc(first).map { it.message })
            .noneMatch { "timer set" in it }

        // The reminder is still owed to the session, and comes up later.
        val session = requireNotNull(think.sessionId)
        val reminder = inbox.findAll().single { it.sessionId == session && it.kind == SessionEventKind.TIMER }
        assertThat(reminder.deliveredAt).isNull()
        inbox.delete(reminder)
        inbox.save(
            SessionEvent(
                sessionId = session, kind = SessionEventKind.TIMER, body = reminder.body,
                dueAt = OffsetDateTime.now().minusSeconds(1),
            ),
        )
        // What the sweep announces when a reminder comes due.
        published.publishEvent(SessionEventDue(session))

        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            val carried = executions.findAll().singleOrNull { it.id != first }
            assertThat(carried).isNotNull
            assertThat(carried!!.status).isEqualTo(ExecutionStatus.COMPLETED)
            assertThat(carried.startedFrom).isEqualTo(first)
            val run = steps.findAll().filter { it.executionId == carried.id }
            assertThat(run.single { it.nodeKey == "reply" }.input).contains("Good morning, the build is green.")
            assertThat(run.single { it.nodeKey == "think" }.carriedOver).isTrue()
        }
        assertThat(asked.last()).contains("is up: look at the build").contains("started by a reminder you set")
        assertThat(lines.findAll().any { "was woken by a reminder it set" in (it.content ?: "") }).isTrue()
    }

    /**
     * A wait the agent asks for is said once in the run's log. The runner wrote
     * the line and so did the engine, on the first park, so every park read
     * twice.
     */
    @Test
    fun `a wait the agent asks for is logged once`() {
        sleeping = true
        val first = start()

        assertThat(executions.findById(first).orElseThrow().status).isEqualTo(ExecutionStatus.COMPLETED)
        assertThat(steps.findAll().single { it.executionId == first && it.nodeKey == "think" }.agentSleeps).isEqualTo(1)
        assertThat(runLog.findByExecutionIdOrderBySequenceAsc(first).map { it.message }.filter { "is waiting 1s" in it })
            .hasSize(1)
    }

    private fun start(): Long {
        val modelId = model(serve())
        val agent = agents.save(
            Agent(
                workspaceId = workspaceId, name = "OKO Bot", type = AgentType.LLM, modelId = modelId,
                systemPrompt = "You are the bot.", tools = BuiltInTools.GRANTED.toMutableList(),
            ),
        )
        graph(requireNotNull(agent.id), replyAction())
        return graphQlTester.document(
            """mutation { startExecution(workspaceId: $workspaceId, workflowId: $workflowId, input: "{}") { id } }""",
        ).execute().path("startExecution.id").entity(Long::class.java).get()
    }

    /** An action that records what it was handed, standing in for the Slack reply after the agent. */
    private fun replyAction(): Long {
        val source = "export default function deliver() { return { sent: true }; }"
        val functionId = graphQlTester.document(
            """
            mutation {
              createFunction(input: {
                workspaceId: $workspaceId, name: "deliver", returnType: MAP,
                source: ${'"'}${'"'}${'"'}$source${'"'}${'"'}${'"'}, typescript: ${'"'}${'"'}${'"'}$source${'"'}${'"'}${'"'}, params: []
              }) { id }
            }
            """,
        ).execute().path("createFunction.id").entity(Long::class.java).get()
        return graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Send Slack message", type: EXECUTE, subtype: FUNCTION, functionId: $functionId
              }) { id }
            }
            """,
        ).execute().path("createAction.id").entity(Long::class.java).get()
    }

    private fun graph(agentId: Long, replyActionId: Long) {
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
                  { key: "think", kind: AGENT, name: "OKO Bot", agentId: $agentId, x: 200, y: 0 },
                  { key: "reply", kind: ACTION, name: "Send Slack message", actionId: $replyActionId, x: 400, y: 0 }
                ],
                edges: [{ source: "talk", target: "think" }, { source: "think", target: "reply" }]
              }) { nodes { key } problems { message } }
            }
            """,
        ).execute()
    }

    /**
     * Sets a reminder and answers; woken by it, says what it found. Or, for the
     * log test, waits a second with finish_answer and then answers.
     */
    private fun serve(): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            asked += body
            val reply = if (sleeping) {
                // Woken, it is handed the note it slept with.
                if (body.contains("look again")) {
                    said("Looked again.")
                } else {
                    called("call_s", "finish_answer", """{\"answer\":\"look again\",\"wake_after_ms\":1000}""")
                }
            } else {
                when {
                    body.contains("is up: look at the build") -> said("Good morning, the build is green.")
                    body.contains("call_1") -> said("Wake-up set for tomorrow.")
                    else -> called("call_1", "timer_set", """{\"seconds\":30,\"note\":\"look at the build\"}""")
                }
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
