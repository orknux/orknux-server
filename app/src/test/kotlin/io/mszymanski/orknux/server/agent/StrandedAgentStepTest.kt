package io.mszymanski.orknux.server.agent

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.server.llm.LlmSessionEventKind
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.ExecutionLogRepository
import io.mszymanski.orknux.workflow.execution.ExecutionPlanner
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.ParkedRunSweeper
import io.mszymanski.orknux.workflow.execution.StepStatus
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
import org.springframework.test.context.TestPropertySource
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An agent step a restart cut short is asked again, and the message is
 * answered. Issue #601.
 *
 * The inline engine's half. A server that dies while an agent is waiting on its
 * model leaves the step RUNNING with nothing underneath it; the sweeper finds
 * the run, and until this failed the step as interrupted - correct for a
 * function that may have charged a card, and for an agent answering a Slack
 * message the end of it: nobody was ever answered, and Rerun repeated the whole
 * run. An agent step is now asked again, with its session, and the run finishes
 * with the answer - the same run, not a second one.
 *
 * The record is the one a killed process leaves, built the way
 * `ParkedRunResumeTest` builds its own: the run planned, the agent step begun
 * an hour ago with its first attempt spent, and the question already in the
 * session, because the dead attempt wrote it there before asking the model.
 * Everything after that is real - the sweep, the engine, the agent runner and a
 * model over HTTP.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
@TestPropertySource(
    properties = [
        "orknux.execution.inline.sweep.enabled=false",
        // No grace: the run is planned now rather than an hour ago, and what is
        // under test is what the sweep does with it, not how long it waits.
        "orknux.execution.inline.sweep.grace=0s",
    ],
)
class StrandedAgentStepTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val planner: ExecutionPlanner,
    @Autowired val sweeper: ParkedRunSweeper,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val logs: ExecutionLogRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val sessionEvents: LlmSessionEventRepository,
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
        sessionEvents.deleteAll()
        sessions.deleteAll()
        received.clear()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "stranded-agent-${System.nanoTime()}")).id)
        workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Answer the thread" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()

        val agentId = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Answerer", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        graphQlTester.document(
            """mutation { updateAgent(id: $agentId, input: { name: "Answerer", modelId: ${model(serve())} }) { id } }""",
        ).execute()

        // One agent node keeping a session, as a Slack thread's does, and a
        // node after it, so the run has somewhere to go once it is answered.
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "answer", kind: AGENT, name: "Answer", agentId: $agentId, x: 0, y: 0,
                    mappings: [
                      { name: "prompt", expression: "$QUESTION", mode: VALUE },
                      { name: "sessionKey", expression: "thread-601", mode: VALUE }
                    ] },
                  { key: "after", kind: OBJECT, name: "Pass it on", outputName: "note", x: 200, y: 0,
                    mappings: [{ name: "said", expression: "answered", mode: VALUE }] }
                ],
                edges: [{ source: "answer", target: "after" }]
              }) { nodes { key } }
            }
            """,
        ).execute()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `an agent step a restart cut short is asked again, and the run finishes with the message answered`() {
        val executionId = strandedMidCall(attemptsSpent = 1)

        assertThat(sweeper.sweep()).isEqualTo(1)

        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            assertThat(executions.findById(executionId).orElseThrow().status).isEqualTo(ExecutionStatus.COMPLETED)
        }
        val after = steps.findByExecutionIdOrderByOrderAsc(executionId).associateBy { it.nodeKey }
        assertThat(after.getValue("answer").status).isEqualTo(StepStatus.COMPLETED)
        assertThat(after.getValue("answer").output).isEqualTo(ANSWER)
        // The attempt that died counts, so a step that keeps killing its
        // process runs out of goes.
        assertThat(after.getValue("answer").attempts).isEqualTo(2)
        assertThat(after.getValue("after").status).isEqualTo(StepStatus.COMPLETED)
        // The same run, carried on: nobody had to press Rerun.
        assertThat(executions.findAll().filter { it.workspaceId == workspaceId }).hasSize(1)
        assertThat(logs.findByExecutionIdOrderBySequenceAsc(executionId).map { it.message })
            .anyMatch { "interrupted by a restart" in it && "asking it again" in it }

        // The model was asked once more, and shown the question once - not the
        // copy the dead attempt left in the session as well as this one's.
        assertThat(received).hasSize(1)
        assertThat(received.single().split(QUESTION)).hasSize(2)
        // And the conversation reads as one question and one answer.
        val said = sessionEvents.findAll().filter { it.sessionId == after.getValue("answer").sessionId }
        assertThat(said.filter { it.kind == LlmSessionEventKind.USER }.map { it.content }).containsExactly(QUESTION)
        assertThat(said.filter { it.kind == LlmSessionEventKind.AGENT }.map { it.content }).containsExactly(ANSWER)
    }

    @Test
    fun `an agent step that restarts keep cutting short stops being asked, and fails as interrupted`() {
        // Three goes spent, the default for the inline engine as for Temporal:
        // the step may be what kills the process, so it is not asked for ever.
        val executionId = strandedMidCall(attemptsSpent = 3)

        assertThat(sweeper.sweep()).isEqualTo(1)

        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            assertThat(executions.findById(executionId).orElseThrow().status).isEqualTo(ExecutionStatus.FAILED)
        }
        val answer = steps.findByExecutionIdOrderByOrderAsc(executionId).single { it.nodeKey == "answer" }
        assertThat(answer.status).isEqualTo(StepStatus.FAILED)
        assertThat(answer.error).contains("interrupted by a restart")
        assertThat(received).isEmpty()
    }

    /**
     * What a server killed in the middle of the agent's model call leaves: the
     * run RUNNING, the agent step RUNNING with its attempts
     * spent and its session on it, the question in the session, and no thread
     * anywhere carrying any of it.
     */
    private fun strandedMidCall(attemptsSpent: Int): Long {
        val planned = planner.plan(workspaceId, workflowId, ExecutionTrigger.MANUAL, "{}")
        val executionId = requireNotNull(planned.execution.id)
        val anHourAgo = OffsetDateTime.now().minusHours(1)

        val session = recorder.open(workspaceId, null, "thread-601")
        recorder.userSaid(session, "Answer", QUESTION)
        val step = requireNotNull(steps.findByExecutionIdAndNodeKey(executionId, "answer"))
        step.status = StepStatus.RUNNING
        step.attempts = attemptsSpent
        step.startedAt = anHourAgo
        step.sessionId = session
        steps.save(step)
        return executionId
    }

    /** A model that answers at once, streamed, as a node with a session asks it to. */
    private fun serve(): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            received += exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val frames = listOf(
                """{"choices":[{"delta":{"content":"$ANSWER"}}]}""",
                """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":11,"completion_tokens":6}}""",
            )
            val body = (frames.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n")
                .toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}"
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

    private companion object {
        const val QUESTION = "What is the capital of France?"
        const val ANSWER = "Paris."
    }
}
