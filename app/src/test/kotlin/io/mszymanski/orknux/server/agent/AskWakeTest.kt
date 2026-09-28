package io.mszymanski.orknux.server.agent

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.chat.BuiltInTools
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
 * An agent that asked, and ended its turn before the answer came.
 *
 * Seen in production: an agent delegated with ask_agent, said it would check
 * back later, and finished - and nothing brought it back, so the answer landed
 * with nobody to read it. The briefing now tells a model there is no later; this
 * is the backup. The step parks rather than completing, the answer arriving
 * wakes it, and the woken turn reads the answer first.
 *
 * The asked agent takes a moment on purpose, so the asker's turn has ended by
 * the time it answers - and the step's wait is a minute, so a run that finishes
 * in seconds is one the answer woke rather than the clock.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class AskWakeTest(
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
) {

    private var workspaceId: Long = 0
    private var workflowId: Long = 0
    private lateinit var server: HttpServer

    /** What the asker was sent, one body per call, in order. */
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
        settings.setAgentSleepSeconds(60, "alice")
        settings.setAgentSleepTimes(3, "alice")

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Delegation" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
        settings.setAgentSleepSeconds(settings.agentSleepSecondsConfigured(), "alice")
        settings.setAgentSleepTimes(settings.agentSleepTimesConfigured(), "alice")
    }

    @Test
    fun `an answer that lands after the asker finished wakes it, and it reads the answer`() {
        val modelId = model(serve())
        val librarian = agents.save(
            Agent(
                workspaceId = workspaceId, name = "Librarian", type = AgentType.LLM, modelId = modelId,
                systemPrompt = "You are the librarian.", tools = BuiltInTools.GRANTED.toMutableList(),
            ),
        )
        val asker = agents.save(
            Agent(
                workspaceId = workspaceId, name = "Responder", type = AgentType.LLM, modelId = modelId,
                systemPrompt = "You are the responder.", agents = mutableListOf(requireNotNull(librarian.id)),
                tools = BuiltInTools.GRANTED.toMutableList(),
            ),
        )
        graph(requireNotNull(asker.id))

        val began = System.nanoTime()
        val id = graphQlTester.document(
            """mutation { startExecution(workspaceId: $workspaceId, workflowId: $workflowId, input: "{}") { id } }""",
        ).execute().path("startExecution.id").entity(Long::class.java).get()
        val took = (System.nanoTime() - began) / 1_000_000_000

        val run = executions.findById(id).orElseThrow()
        assertThat(run.status).isEqualTo(ExecutionStatus.COMPLETED)
        val step = steps.findAll().single { it.nodeKey == "think" }
        assertThat(step.output).contains("It is 42.")
        // Parked at most once, on the ask - and woken by it, not by the minute
        // running out. None is right too: where the answer lands before the turn
        // has ended, the turn reads it itself and there is nothing to wait for.
        // Which happens is timing; SQLite's single writer makes the second likelier.
        assertThat(step.agentSleeps).isIn(0, 1)
        assertThat(took).describedAs("seconds the run took").isLessThan(30)
        // The woken turn was handed the answer, as said by orknux.
        assertThat(asked.last()).contains("has answered what you asked").contains("The answer is 42.")
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

    /**
     * One provider for both agents, told apart by their system prompts.
     *
     * The librarian waits a moment and answers. The responder asks it, then says
     * it will check back later - the production mistake - and, once it is handed
     * the answer, says what it was.
     */
    private fun serve(): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val reply = if (body.contains("You are the librarian.")) {
                Thread.sleep(1500)
                said("The answer is 42.")
            } else {
                asked += body
                when {
                    body.contains("has answered what you asked") -> said("It is 42.")
                    body.contains("\"tool_call_id\"") -> said("I have asked the librarian and will check back later.")
                    else -> """
                        {"choices":[{"message":{"role":"assistant","content":null,
                          "tool_calls":[{"id":"call_1","type":"function","function":{"name":"ask_agent",
                            "arguments":"{\"agent\":\"Librarian\",\"question\":\"What is the answer?\"}"}}]}}],
                         "usage":{"prompt_tokens":9,"completion_tokens":4}}
                    """.trimIndent()
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
