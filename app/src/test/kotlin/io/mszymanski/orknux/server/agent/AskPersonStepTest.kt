package io.mszymanski.orknux.server.agent

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.llm.SessionEventKind
import io.mszymanski.orknux.server.llm.SessionEventRepository
import io.mszymanski.orknux.server.llm.SessionInbox
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A workflow agent that asks the person something, as a Slack thread's does.
 *
 * Seen on prod: `ask_person` tells the model "their answer reaches you later;
 * end your turn now", the model did exactly that and wrote nothing, and the
 * empty reply was asked again five times and then failed the step with "could
 * not answer". And once the node's retries ran out with the person's answer
 * arriving during the last of them, nothing read it until the person typed "."
 * into the thread.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class AskPersonStepTest(
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
    @Autowired val events: SessionEventRepository,
    @Autowired val inbox: SessionInbox,
    @Autowired val agentTools: AgentToolRepository,
    @Autowired val chats: io.mszymanski.orknux.server.chat.ChatSessionRepository,
) {

    private var workspaceId: Long = 0
    private var workflowId: Long = 0
    private lateinit var server: HttpServer

    /** What the model was sent, one body per call, in order. */
    private val asked = CopyOnWriteArrayList<String>()

    /** How the model behaves once it has asked: says nothing, or is refused by a rate limit. */
    private var afterAsking = AfterAsking.SILENT

    /** Requests refused so far, in the rate-limited case. */
    private val refused = AtomicInteger()

    /** Which refusal the person answers during: the second is the last attempt's in the test that had only two. */
    private var answeredDuring = 2

    /** Whether the model only greets: the first run, which makes the session. */
    @Volatile private var greeting = false

    private enum class AfterAsking { SILENT, RATE_LIMITED }

    @BeforeEach
    fun reset() {
        agentTools.deleteAll()
        steps.deleteAll()
        executions.deleteAll()
        workflows.deleteAll()
        agents.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        chats.deleteAll()
        events.deleteAll()
        lines.deleteAll()
        sessions.deleteAll()
        workspaces.deleteAll()
        asked.clear()
        refused.set(0)
        afterAsking = AfterAsking.SILENT
        answeredDuring = 2

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "shop")).id)
        workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Thread" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    /**
     * Nothing after asking is the end of the turn: asked once, not five times,
     * the step completes rather than failing, and the person's answer wakes the
     * agent for a turn of its own.
     */
    @Test
    fun `an agent that says nothing after asking the person ends its turn, and the answer wakes it`() {
        val executionId = start(policy = "")

        assertThat(executions.findById(executionId).orElseThrow().status).isEqualTo(ExecutionStatus.COMPLETED)
        assertThat(asked.count { "call_1" in it }).describedAs("asked once after the question, not again").isEqualTo(1)
        assertThat(lines.findAll().none { "could not answer" in (it.content ?: "") || "answered with nothing" in (it.content ?: "") })
            .isTrue()

        val thread = requireNotNull(steps.findAll().single { it.nodeKey == "think" }.sessionId)
        inbox.post(thread, SessionEventKind.ANSWER, ANSWER)
        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            assertThat(lines.findAll().any { "Placing the order." in (it.content ?: "") }).isTrue()
        }
        // The turn that said nothing left no message behind: one with no words
        // and no calls is what some providers refuse the whole request over.
        assertThat(asked.flatMap(::wordless)).describedAs("assistant messages sent with nothing in them").isEmpty()
    }

    /**
     * The nothing is not handed on. The node after the agent would have been
     * given an empty text - a Slack reply, which throws on one, and the run
     * went red for an agent that did as it was told. The run ends at the agent
     * instead, and what it says once the person has answered goes on from
     * there in a run of its own.
     */
    @Test
    fun `nothing said after asking is not handed to the next node, and the answer's reply is`() {
        val executionId = start(policy = "", then = true)

        assertThat(executions.findById(executionId).orElseThrow().status).isEqualTo(ExecutionStatus.COMPLETED)
        assertThat(steps.findAll().filter { it.nodeKey == "say" }.map { it.status })
            .describedAs("the node after the agent, never reached")
            .containsOnly(StepStatus.PENDING)

        val thread = requireNotNull(steps.findAll().single { it.nodeKey == "think" }.sessionId)
        inbox.post(thread, SessionEventKind.ANSWER, ANSWER)
        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            assertThat(steps.findAll().filter { it.nodeKey == "say" && it.executionId != executionId }.map { it.status })
                .describedAs("the node after the agent, in the run the answer's reply went on in")
                .containsExactly(StepStatus.COMPLETED)
        }
    }

    /**
     * The answer arrives while the last attempt the node's policy allows is
     * failing. The step was running, so nothing woke it; then it failed and
     * told nobody, and the answer sat unread. Its failing now says it finished,
     * and what is waiting is read.
     */
    @Test
    fun `an answer that arrives while the last attempt fails is still read`() {
        afterAsking = AfterAsking.RATE_LIMITED
        val executionId = start(policy = "retryAttempts: 2, retryBackoffSeconds: 1")

        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            assertThat(executions.findById(executionId).orElseThrow().status).isEqualTo(ExecutionStatus.FAILED)
        }
        await().atMost(Duration.ofSeconds(20)).untilAsserted {
            assertThat(asked.any { ANSWER in it }).describedAs("the answer reached the model").isTrue()
            assertThat(lines.findAll().any { "Placing the order." in (it.content ?: "") }).isTrue()
        }
    }

    /**
     * And while an attempt that is *not* the last is failing: the step was
     * running, so the answer woke nobody, and the retry then sat out its whole
     * backoff with the answer unread. It is read as soon as the step is parked
     * for the retry - here, long before the half minute the policy asks for.
     */
    @Test
    fun `an answer that arrives while an attempt fails is read before the backoff is out`() {
        afterAsking = AfterAsking.RATE_LIMITED
        answeredDuring = 1
        val began = System.nanoTime()
        val executionId = start(policy = "retryAttempts: 3, retryBackoffSeconds: 30")

        await().atMost(Duration.ofSeconds(15)).untilAsserted {
            assertThat(executions.findById(executionId).orElseThrow().status).isEqualTo(ExecutionStatus.COMPLETED)
            assertThat(lines.findAll().any { "Placing the order." in (it.content ?: "") }).isTrue()
        }
        assertThat(Duration.ofNanos(System.nanoTime() - began)).describedAs("the backoff was not sat out").isLessThan(Duration.ofSeconds(25))
    }

    /**
     * A first run that only greets, so the thread's session exists; a chat
     * opened on it, as prod's Slack thread had - which is what lets a question
     * card be answered there; then the run that asks.
     */
    private fun start(policy: String, then: Boolean = false): Long {
        val modelId = model(serve())
        val agent = agents.save(
            Agent(workspaceId = workspaceId, name = "Shopper", type = AgentType.LLM, modelId = modelId, systemPrompt = "You shop."),
        )
        // A second agent for the node after, told apart by its prompt so the stub can answer it plainly.
        val teller = if (!then) null else agents.save(
            Agent(workspaceId = workspaceId, name = "Teller", type = AgentType.LLM, modelId = modelId, systemPrompt = TELLS),
        ).id
        graph(requireNotNull(agent.id), policy, teller)
        greeting = true
        run()
        val talk = requireNotNull(steps.findAll().single { it.nodeKey == "think" }.sessionId)
        graphQlTester.document(
            """mutation { startChat(input: { workspaceId: $workspaceId, title: "thread", llmSessionId: $talk }) { id } }""",
        ).execute().path("startChat.id").hasValue()
        greeting = false
        steps.deleteAll()
        executions.deleteAll()
        asked.clear()
        return run()
    }

    private fun run(): Long = graphQlTester.document(
        """mutation { startExecution(workspaceId: $workspaceId, workflowId: $workflowId, input: "{}") { id } }""",
    ).execute().path("startExecution.id").entity(Long::class.java).get()

    /** The assistant messages in a request [body] that carry neither words nor calls. */
    private fun wordless(body: String): List<String> =
        tools.jackson.databind.json.JsonMapper().readTree(body).path("messages").values()
            .filter { it.path("role").asString() == "assistant" }
            .filter { it.path("tool_calls").isEmpty && (it.path("content").isNull || it.path("content").isMissingNode || it.path("content").asString().isBlank()) }
            .map { it.toString() }

    /** [then] is a second agent, put on a node after the one that asks, to be handed what it says. */
    private fun graph(agentId: Long, policy: String, then: Long? = null) {
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "talk", kind: SESSION, name: "the thread", x: 0, y: 0,
                    mappings: [
                      { name: "sessionKeyPrefix", expression: "node", mode: VALUE },
                      { name: "sessionKey", expression: "one", mode: VALUE }
                    ] },
                  { key: "think", kind: AGENT, name: "Shopper", agentId: $agentId, x: 200, y: 0${if (policy.isEmpty()) "" else ", $policy"} }
                  ${if (then != null) """{ key: "say", kind: AGENT, name: "Teller", agentId: $then, x: 400, y: 0 }""" else ""}
                ],
                edges: [{ source: "talk", target: "think" }${if (then != null) """, { source: "think", target: "say" }""" else ""}]
              }) { nodes { key } problems { message } }
            }
            """,
        ).execute()
    }

    /** Asks the person; then says nothing, or is refused; and answers once told what the person said. */
    private fun serve(): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            asked += body
            val (status, reply) = when {
                greeting -> 200 to said("Hi.")
                TELLS in body -> 200 to said("Told.")
                ANSWER in body -> 200 to said("Placing the order.")
                "call_1" !in body -> 200 to called(
                    "call_1",
                    "ask_person",
                    """{\"question\":\"Which payment?\",\"options\":[{\"label\":\"On pickup\",\"description\":\"Pay at the shop\"},""" +
                        """{\"label\":\"Transfer\",\"description\":\"Pay now\"}]}""",
                )
                afterAsking == AfterAsking.SILENT -> 200 to said("")
                else -> {
                    // The person answers while an attempt is failing; by default the second, which is the last.
                    if (refused.incrementAndGet() == answeredDuring) {
                        val talk = steps.findAll().first { it.nodeKey == "think" }.sessionId
                        talk?.let { inbox.post(it, SessionEventKind.ANSWER, ANSWER) }
                    }
                    429 to """{"error":{"message":"Rate limit reached","code":"429"}}"""
                }
            }
            val bytes = reply.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
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

    private companion object {
        // No quotes: the stub looks for it in a JSON body, where they would be escaped.
        const val TELLS = "You tell the shop what was decided."
        const val ANSWER = "mszymanski answered your question about payment: on pickup."
    }
}
