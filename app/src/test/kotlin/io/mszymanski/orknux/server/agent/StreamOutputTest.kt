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
 * An agent node hands on what its agent says while it is still working.
 *
 * What an agent wrote beside its tool calls reached the reply node after it
 * only when the step finished, so "I'm checking this" arrived with the answer,
 * minutes later. Streaming - on unless the node says otherwise - hands each
 * such message on as it is written, in a run carried on from the step's, and
 * the step's own output is then only its last answer. Off, the agent finishes
 * first and everything it said goes on together, as before.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class StreamOutputTest(
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
) {

    private var workspaceId: Long = 0
    private var workflowId: Long = 0
    private lateinit var server: HttpServer

    /** What the model was sent, one body per call, in order. */
    private val asked = CopyOnWriteArrayList<String>()

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
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Slack mention" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun replyInputs(run: Long) =
        steps.findAll().filter { it.executionId == run && it.nodeKey == "reply" }.mapNotNull { it.input }

    @Test
    fun `what the agent says while it works is handed on as it is written, and the step answers with the rest`() {
        val first = start()

        assertThat(executions.findById(first).orElseThrow().status).isEqualTo(ExecutionStatus.COMPLETED)
        // The reply after the step had only the last answer.
        assertThat(replyInputs(first).single()).contains("The build is green.").doesNotContain("Checking the build.")
        // And the message written beside the tool call went on in a run of its own, while the step ran.
        val streamed = executions.findAll().single { it.id != first }
        assertThat(streamed.startedFrom).isEqualTo(first)
        assertThat(replyInputs(requireNotNull(streamed.id)).single()).contains("Checking the build.")
            .doesNotContain("The build is green.")
    }

    @Test
    fun `off, the agent finishes before anything after it runs, and everything it said goes on together`() {
        val first = start(stream = false)

        assertThat(executions.findAll().map { it.id }).containsExactly(first)
        assertThat(replyInputs(first).single()).contains("Checking the build.").contains("The build is green.")
    }

    private fun start(stream: Boolean? = null): Long {
        val modelId = model(serve())
        val agent = agents.save(
            Agent(
                workspaceId = workspaceId, name = "OKO Bot", type = AgentType.LLM, modelId = modelId,
                systemPrompt = "You are the bot.", tools = BuiltInTools.GRANTED.toMutableList(),
            ),
        )
        graph(requireNotNull(agent.id), replyAction(), stream)
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

    private fun graph(agentId: Long, replyActionId: Long, stream: Boolean?) {
        val streaming = stream?.let { ", streamOutput: $it" }.orEmpty()
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
                  { key: "think", kind: AGENT, name: "OKO Bot", agentId: $agentId, x: 200, y: 0$streaming },
                  { key: "reply", kind: ACTION, name: "Send Slack message", actionId: $replyActionId, x: 400, y: 0 }
                ],
                edges: [{ source: "talk", target: "think" }, { source: "think", target: "reply" }]
              }) { nodes { key } problems { message } }
            }
            """,
        ).execute()
    }

    /** Says it is checking beside a tool call, then answers. */
    private fun serve(): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            asked += body
            val reply = if (body.contains("call_1")) {
                said("The build is green.")
            } else {
                """{"choices":[{"message":{"role":"assistant","content":"Checking the build.",
                  "tool_calls":[{"id":"call_1","type":"function","function":{"name":"current_time","arguments":"{}"}}]}}],
                 "usage":{"prompt_tokens":9,"completion_tokens":4}}"""
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
