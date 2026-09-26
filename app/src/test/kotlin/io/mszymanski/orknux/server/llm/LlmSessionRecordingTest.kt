package io.mszymanski.orknux.server.llm

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentSkillRepository
import io.mszymanski.orknux.server.agent.SkillCatalogRepository
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
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An agent node writing its turn into a session, without being asked to.
 *
 * The owner's decision, and the one worth a test: recording belongs to the
 * runtime rather than to whoever draws the graph. A transcript a workflow author
 * has to remember to write is a transcript with holes exactly where somebody was
 * busy, and those holes are invisible - a session that recorded half a
 * conversation looks exactly like a session that had half a conversation.
 *
 * The other half is what it costs when nobody asked for it. A node that names no
 * session must touch nothing, because every agent node drawn before this existed
 * is one of those.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class LlmSessionRecordingTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val events: LlmSessionEventRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val catalogs: SkillCatalogRepository,
    @Autowired val skills: AgentSkillRepository,
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
) {

    private var workspaceId: Long = 0
    private var workflowId: Long = 0
    private lateinit var server: HttpServer
    private val received = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun reset() {
        events.deleteAll()
        sessions.deleteAll()
        logs.deleteAll()
        steps.deleteAll()
        executions.deleteAll()
        nodes.deleteAll()
        edges.deleteAll()
        assignments.deleteAll()
        workflows.deleteAll()
        agents.deleteAll()
        skills.deleteAll()
        catalogs.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        received.clear()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Incident Response" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
    }

    @AfterEach
    fun stop() = server.stop(0)

    /**
     * The conversation's own lines, oldest first, without the setup line.
     *
     * A session opens with an AGENT_DETAILS line since #441 - the setup the
     * agent was about to answer under, written before anything is said - and
     * again wherever that setup changes. What this class is about is what the
     * runtime records *of the conversation*, so the setup is left out here
     * rather than written into every expectation; `AgentNodeRunnerTest` is where
     * the line itself, and when a second one is due, are pinned.
     */
    private fun said(): List<LlmSessionEvent> = events.findAll()
        .filter { it.kind != LlmSessionEventKind.AGENT_DETAILS }
        .sortedBy { it.id }

    /**
     * The question, the tool, and the answer - none of which the graph asked to
     * have written down.
     *
     * The node names a session and that is the whole of the configuration. What
     * lands in it is the round as it happened, including the lookup the agent
     * did on the way, which is precisely what the run's own log does not keep.
     */
    @Test
    fun `an agent node records its round into the session it names`() {
        val catalogId = catalog("Reviews")
        skill("codeReview", catalogId, "Read the diff twice before commenting.")
        val agentId = agent("Reviewer", model(serveToolThenAnswer()), granted = "Reviews")
        graph(agentId, prefix = "issue", key = "42")

        start()

        val session = sessions.findAll().single()
        assertThat(session.sessionKey).isEqualTo("issue:42")
        assertThat(session.keyPrefix).isEqualTo("issue")
        assertThat(session.workspaceId).isEqualTo(workspaceId)

        val lines = said()
        assertThat(lines.map { it.kind }).containsExactly(
            LlmSessionEventKind.USER,
            LlmSessionEventKind.TOOL,
            LlmSessionEventKind.AGENT,
        )
        // The question came from the run, under the node's name - which is the
        // only thing in a workflow that can be said to have asked.
        assertThat(lines[0].actor).isEqualTo("Ask reviewer")
        assertThat(lines[0].content).contains("the database fell over")
        // The tool is named by the tool, with what the model passed it.
        assertThat(lines[1].actor).isEqualTo("skill_load")
        assertThat(lines[1].content).contains("codeReview")
        // The answer is the agent's, under the agent's own name.
        assertThat(lines[2].actor).isEqualTo("Reviewer")
        assertThat(lines[2].content).isEqualTo("Read the diff twice.")

        // All of it in the one session, and nothing else in there: the three
        // lines above plus the setup line the log opens with (#441).
        assertThat(session.id?.let { events.countBySessionId(it) }).isEqualTo(4)
    }

    /**
     * A round that said something *and* asked for a tool keeps both.
     *
     * Providers are entitled to answer with a message and tool calls in one
     * reply, and several do it habitually. That text was kept only in the round
     * itself: the model read its own remark until the round ended and then it
     * was gone - off the page, out of what the session can hand back, and out
     * of any account of how the answer was arrived at.
     *
     * Before the call and not after, because that is the order it happened in.
     * The model wrote the sentence and asked for the tool in the same message,
     * so a transcript that puts the sentence under the lookup has the agent
     * speaking about something it had not yet looked up.
     */
    @Test
    fun `what an agent said before it called a tool is written down, before the call`() {
        val catalogId = catalog("Reviews")
        skill("codeReview", catalogId, "Read the diff twice before commenting.")
        val agentId = agent("Reviewer", model(serveSaying(REMARK)), granted = "Reviews")
        graph(agentId, prefix = "issue", key = "42")

        start()

        val lines = said()
        assertThat(lines.map { it.kind }).containsExactly(
            LlmSessionEventKind.USER,
            LlmSessionEventKind.AGENT,
            LlmSessionEventKind.TOOL,
            LlmSessionEventKind.AGENT,
        )
        // Under the agent's own name, and word for word: what is being recorded
        // is what the model said, so nothing here trims or reflows it.
        assertThat(lines[1].actor).isEqualTo("Reviewer")
        assertThat(lines[1].content).isEqualTo(REMARK)
        assertThat(lines[3].content).isEqualTo("Read the diff twice.")
    }

    /**
     * And the ordinary round writes no line of its own, which is the part that
     * had to be got right.
     *
     * Nearly every tool-calling round carries no text at all. A line for each
     * of them would put an empty speech bubble under every lookup on every page
     * in the product, on every round, for ever.
     */
    @Test
    fun `a round that called a tool and said nothing writes no line for it`() {
        val catalogId = catalog("Reviews")
        skill("codeReview", catalogId, "Read the diff twice before commenting.")
        val agentId = agent("Reviewer", model(serveSaying(null)), granted = "Reviews")
        graph(agentId, prefix = "issue", key = "42")

        start()

        assertThat(said().map { it.kind }).containsExactly(
            LlmSessionEventKind.USER,
            LlmSessionEventKind.TOOL,
            LlmSessionEventKind.AGENT,
        )
    }

    /**
     * Blank counts as nothing, and it has to: a closing `</think>` routinely
     * leaves a newline behind it, so a round whose whole text is that newline
     * is a round that said nothing. The same test the client makes when it
     * decides whether a stream carried an answer at all.
     */
    @Test
    fun `a round whose only text is what a reasoning tag left behind writes no line`() {
        val catalogId = catalog("Reviews")
        skill("codeReview", catalogId, "Read the diff twice before commenting.")
        val agentId = agent("Reviewer", model(serveSaying("<think>the skill first</think>\\n  ")), granted = "Reviews")
        graph(agentId, prefix = "issue", key = "42")

        start()

        assertThat(said().map { it.kind }).containsExactly(
            LlmSessionEventKind.USER,
            LlmSessionEventKind.TOOL,
            LlmSessionEventKind.AGENT,
        )
    }

    /**
     * And the next run hears it, which is the consequence that mattered most.
     *
     * [LlmSessionRecorder.remembered] reads what was *said*, so a remark that
     * was never written down was gone from the agent's own memory the moment
     * the round ended. It is what the agent actually said and belongs in its
     * history for the same reason its answer does; a task whose progress was
     * reported in one of those remarks had the only copy of it thrown away.
     *
     * Asserted on the first request of the second run, before any tool has run
     * in it: what is in that body was remembered rather than said again.
     */
    @Test
    fun `what an agent said before a call is in front of it on the next run`() {
        val catalogId = catalog("Reviews")
        skill("codeReview", catalogId, "Read the diff twice before commenting.")
        val agentId = agent("Reviewer", model(serveSaying(REMARK)), granted = "Reviews")
        graph(agentId, prefix = "issue", key = "42")

        start()
        val first = received.size
        start()

        assertThat(received[first]).contains(REMARK)
    }

    /**
     * The feature, stated: a second run joins the first one's conversation.
     *
     * Two runs are two executions with two logs and nothing in common. The
     * session is the thing that survives them, and it survives because it is
     * keyed by what the node computed rather than by anything about a run.
     */
    @Test
    fun `a second run writes into the same session rather than a new one`() {
        val agentId = agent("Reviewer", model(serveAnswer()))
        graph(agentId, prefix = "issue", key = "42")

        start()
        start()

        assertThat(sessions.findAll()).hasSize(1)
        // Two rounds of two lines each, in the order the two runs happened.
        assertThat(said().map { it.kind })
            .containsExactly(
                LlmSessionEventKind.USER,
                LlmSessionEventKind.AGENT,
                LlmSessionEventKind.USER,
                LlmSessionEventKind.AGENT,
            )
    }

    /**
     * And the second run *hears* the first, which is the whole point.
     *
     * Recording without reading back would be a transcript: an agent that
     * writes everything down and remembers none of it, asking each question of
     * a model that has never heard the last one. What proves the difference is
     * not the rows in the session but what went to the model - so this asserts
     * on the request body the second run sent.
     */
    @Test
    fun `the second run is asked with the first run's exchange in front of it`() {
        val agentId = agent("Reviewer", model(serveAnswer()))
        graph(agentId, prefix = "issue", key = "42")

        start()
        val first = received.size
        start()

        // Everything the second run sent; the first run's requests are behind it.
        val asked = received.drop(first).joinToString(" ")
        assertThat(asked).contains("The database was the cause.")
        // And it is offered as something already said, not as a fresh question.
        assertThat(asked).contains("assistant")
    }

    /**
     * And the next run is asked with the data, not with what was said about it.
     *
     * This is the one the transcript could not do before. The result was
     * threaded into the round that fetched it and the round was thrown away;
     * what survived was the sentence the model wrote out of it. So a second run
     * asking about the same thing worked from the model's summary, and a
     * summary cannot be checked against anything - which is how a labelled
     * issue came to be reported as unlabelled twice over.
     *
     * Asserted on the first request of the second run, before any tool has run
     * in it: what is in that body was remembered rather than fetched.
     */
    @Test
    fun `what a tool returned is recorded, and the next run is asked with it`() {
        val catalogId = catalog("Reviews")
        skill("codeReview", catalogId, "Read the diff twice before commenting.")
        val agentId = agent("Reviewer", model(serveToolThenAnswer()), granted = "Reviews")
        graph(agentId, prefix = "issue", key = "42")

        start()

        val call = events.findAll().single { it.kind == LlmSessionEventKind.TOOL }
        assertThat(call.content).contains("codeReview")
        assertThat(call.result).contains("Read the diff twice before commenting.")

        val first = received.size
        start()

        assertThat(received[first]).contains("Read the diff twice before commenting.")
    }

    /**
     * A node that names no session costs nothing.
     *
     * Every agent node drawn before this existed is this node, so "nothing" has
     * to mean no row and no lookup - not an empty session sitting in the list
     * for somebody to wonder about.
     */
    @Test
    fun `a node that names no session records nothing at all`() {
        val agentId = agent("Reviewer", model(serveAnswer()))
        graph(agentId, prefix = null, key = null)

        start()

        assertThat(steps.findAll().single().status).isEqualTo(StepStatus.COMPLETED)
        assertThat(sessions.findAll()).isEmpty()
        assertThat(events.findAll()).isEmpty()
    }

    /**
     * The key is read off what the run is carrying, which is what makes two
     * workflows agree on one.
     *
     * A key typed into every node would only ever join runs of the same
     * workflow. A key read from the event - a ticket, a thread, a customer - is
     * what lets a second workflow that saw the same thing land in the same
     * conversation.
     */
    @Test
    fun `the key can be read from what the run was handed`() {
        val agentId = agent("Reviewer", model(serveAnswer()))
        graph(agentId, prefix = "issue", key = "ticket", keyIsReference = true)

        start()

        assertThat(sessions.findAll().single().sessionKey).isEqualTo("issue:99")
    }

    /**
     * An agent that could not answer leaves a note rather than silence.
     *
     * A transcript that simply stops leaves whoever reads it looking for words
     * that were never spoken; the note is what says the conversation was
     * interrupted rather than finished.
     */
    @Test
    fun `an agent that could not answer leaves a system note`() {
        val catalogId = catalog("Reviews")
        skill("codeReview", catalogId, "Read the diff twice.")
        val agentId = agent("Looper", model(serveAlwaysCallingTools()), granted = "Reviews")
        graph(agentId, prefix = "issue", key = "42")

        start(expectFailure = true)

        val lines = said()
        assertThat(lines.first().kind).isEqualTo(LlmSessionEventKind.USER)
        assertThat(lines.last().kind).isEqualTo(LlmSessionEventKind.SYSTEM)
        assertThat(lines.last().actor).isEqualTo("system")
        assertThat(lines.last().content).contains("could not answer")
        // And the lookups it did on the way are all there, which is what makes
        // the note legible: an agent that went round in circles looks like one.
        assertThat(lines.count { it.kind == LlmSessionEventKind.TOOL }).isGreaterThan(1)
    }

    /**
     * The shape the editor draws now: a session node, and an edge to the agent.
     *
     * The two halves live on the session rather than on the agent, and the edge
     * is what says this agent talks into it. What lands in the session is the
     * same round as before - the point being that moving where the key is
     * written down changed nothing about what is recorded.
     */
    @Test
    fun `an agent records into the session node wired to it`() {
        val agentId = agent("Reviewer", model(serveAnswer()))
        wiredGraph(agentId, prefix = "issue", key = "42")

        start()

        val session = sessions.findAll().single()
        assertThat(session.sessionKey).isEqualTo("issue:42")
        assertThat(session.keyPrefix).isEqualTo("issue")
        assertThat(said().map { it.kind })
            .containsExactly(LlmSessionEventKind.USER, LlmSessionEventKind.AGENT)
    }

    /**
     * The reason the session is a node at all: two agents, one conversation.
     *
     * Under the old shape this meant typing the same key into both nodes and
     * nothing on the canvas saying they were sharing anything. Here it is two
     * edges from one node, and the second agent is asked with the first one's
     * exchange in front of it.
     */
    @Test
    fun `two agents wired to one session node share the conversation`() {
        val agentId = agent("Reviewer", model(serveAnswer()))
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "chat", kind: SESSION, name: "The incident", x: 0, y: 0, mappings: [
                    { name: "sessionKeyPrefix", expression: "issue", mode: VALUE },
                    { name: "sessionKey", expression: "42", mode: VALUE }
                  ] },
                  { key: "first", kind: AGENT, name: "Ask reviewer", agentId: $agentId, x: 200, y: 0 },
                  { key: "second", kind: AGENT, name: "Ask again", agentId: $agentId, x: 400, y: 0 }
                ],
                edges: [
                  { source: "chat", target: "first" },
                  { source: "chat", target: "second" },
                  { source: "first", target: "second" }
                ]
              }) { nodes { key } }
            }
            """,
        ).execute()

        start()

        // One conversation, with both nodes' rounds in it, in the order they ran.
        assertThat(sessions.findAll()).hasSize(1)
        val lines = said()
        assertThat(lines.map { it.actor })
            .containsExactly("Ask reviewer", "Reviewer", "Ask again", "Reviewer")
        // And the second node heard the first: what it sent carries the answer
        // the first node had already put in the session.
        assertThat(received.last()).contains("The database was the cause.")
    }

    /**
     * A key read off the run still reads the run, though it is written on a
     * node the run never reaches.
     *
     * It is resolved in the agent, against what that step was handed - which is
     * what keeps a session keyed by a ticket, a thread or a customer working
     * now that the key lives somewhere else.
     */
    @Test
    fun `a session node's key can be read from what the run was handed`() {
        val agentId = agent("Reviewer", model(serveAnswer()))
        wiredGraph(agentId, prefix = "issue", key = "ticket", keyIsReference = true)

        start()

        assertThat(sessions.findAll().single().sessionKey).isEqualTo("issue:99")
    }

    /**
     * A session wired to the node beats the key the node itself still carries.
     *
     * The old shape has to keep running, and it does - but the moment somebody
     * draws a session node and joins it up, that is the answer. Otherwise the
     * canvas would say one thing and the run would do another.
     */
    @Test
    fun `a wired session overrides a key the agent node still carries`() {
        val agentId = agent("Reviewer", model(serveAnswer()))
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "chat", kind: SESSION, name: "The incident", x: 0, y: 0, mappings: [
                    { name: "sessionKeyPrefix", expression: "issue", mode: VALUE },
                    { name: "sessionKey", expression: "42", mode: VALUE }
                  ] },
                  { key: "think", kind: AGENT, name: "Ask reviewer", agentId: $agentId, x: 200, y: 0, mappings: [
                    { name: "sessionKeyPrefix", expression: "old", mode: VALUE },
                    { name: "sessionKey", expression: "7", mode: VALUE }
                  ] }
                ],
                edges: [{ source: "chat", target: "think" }]
              }) { nodes { key } }
            }
            """,
        ).execute()

        start()

        assertThat(sessions.findAll().single().sessionKey).isEqualTo("issue:42")
    }

    /**
     * The session node and its edges are a declaration, not a step.
     *
     * Nothing runs it, so no step is recorded for it - and the agent it leads
     * to is not counted as having been reached by it either.
     */
    @Test
    fun `a session node is not recorded as a step of the run`() {
        val agentId = agent("Reviewer", model(serveAnswer()))
        wiredGraph(agentId, prefix = "issue", key = "42")

        val executionId = start()

        assertThat(steps.findAll().filter { it.executionId == executionId }.map { it.nodeKey })
            .containsExactly("think")
    }

    /** Answers in one round, with no tools involved. */
    private fun serveAnswer(): String = serve {
        """
        {"choices":[{"message":{"role":"assistant","content":"The database was the cause."}}],
         "usage":{"prompt_tokens":11,"completion_tokens":6}}
        """.trimIndent()
    }

    /** Asks for a skill first, then answers with what it read. */
    private fun serveToolThenAnswer(): String = serve { body ->
        if (body.contains("tool_call_id")) {
            """{"choices":[{"message":{"role":"assistant","content":"Read the diff twice."}}],
               "usage":{"prompt_tokens":9,"completion_tokens":4}}"""
        } else {
            """
            {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
              {"id":"call_1","type":"function",
               "function":{"name":"skill_load","arguments":"{\"name\":\"codeReview\"}"}}
            ]}}],"usage":{"prompt_tokens":7,"completion_tokens":2}}
            """.trimIndent()
        }
    }

    /**
     * The same round, with whatever the model chose to say alongside the call.
     *
     * @param said the message content of the tool-calling reply, already
     *   escaped for JSON, or null for the `"content":null` every other stub
     *   here sends. The point of the parameter is that the two are one shape:
     *   a provider decides per reply whether to put words beside the calls.
     */
    private fun serveSaying(said: String?): String = serve { body ->
        if (body.contains("tool_call_id")) {
            """{"choices":[{"message":{"role":"assistant","content":"Read the diff twice."}}],
               "usage":{"prompt_tokens":9,"completion_tokens":4}}"""
        } else {
            """
            {"choices":[{"message":{"role":"assistant","content":${said?.let { "\"$it\"" } ?: "null"},
             "tool_calls":[
              {"id":"call_1","type":"function",
               "function":{"name":"skill_load","arguments":"{\"name\":\"codeReview\"}"}}
            ]}}],"usage":{"prompt_tokens":7,"completion_tokens":2}}
            """.trimIndent()
        }
    }

    /** Never answers, only ever asks for another lookup - which is stopped. */
    private fun serveAlwaysCallingTools(): String = serve {
        """
        {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
          {"id":"call_n","type":"function","function":{"name":"skill_list","arguments":"{}"}}
        ]}}],"usage":{"prompt_tokens":1,"completion_tokens":1}}
        """.trimIndent()
    }

    private fun serve(answer: (String) -> String): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            received += body
            val bytes = answer(body).toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}"
    }

    /**
     * One agent node, which is the whole workflow.
     *
     * The session is two node parameters and nothing else: there is no column
     * for it on the node, because a session is named the same way a prompt is -
     * with something written, or with something read off what arrived.
     */
    private fun graph(agentId: Long, prefix: String?, key: String?, keyIsReference: Boolean = false) {
        val mapped = buildList {
            if (prefix != null) add("""{ name: "sessionKeyPrefix", expression: "$prefix", mode: VALUE }""")
            if (key != null) {
                val mode = if (keyIsReference) "REFERENCE" else "VALUE"
                add("""{ name: "sessionKey", expression: "$key", mode: $mode }""")
            }
        }.joinToString(", ")

        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [{
                  key: "think", kind: AGENT, name: "Ask reviewer", agentId: $agentId,
                  mappings: [$mapped], x: 0, y: 0
                }],
                edges: []
              }) { nodes { key agentId } }
            }
            """,
        ).execute()
    }

    /**
     * The same one agent, with the session written where it belongs now: on a
     * node of its own, with an edge saying which agent talks into it.
     */
    private fun wiredGraph(agentId: Long, prefix: String?, key: String, keyIsReference: Boolean = false) {
        val mapped = buildList {
            if (prefix != null) add("""{ name: "sessionKeyPrefix", expression: "$prefix", mode: VALUE }""")
            add("""{ name: "sessionKey", expression: "$key", mode: ${if (keyIsReference) "REFERENCE" else "VALUE"} }""")
        }.joinToString(", ")

        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "chat", kind: SESSION, name: "The incident", x: 0, y: 0, mappings: [$mapped] },
                  { key: "think", kind: AGENT, name: "Ask reviewer", agentId: $agentId, x: 200, y: 0 }
                ],
                edges: [{ source: "chat", target: "think" }]
              }) { nodes { key } }
            }
            """,
        ).execute()
    }

    private fun start(expectFailure: Boolean = false): Long {
        val id = graphQlTester.document(
            """
            mutation(${'$'}input: String) {
              startExecution(workspaceId: $workspaceId, workflowId: $workflowId, input: ${'$'}input) { id status }
            }
            """,
        ).variable("input", """{"summary":"the database fell over","ticket":"99"}""")
            .execute().path("startExecution.id").entity(Long::class.java).get()

        if (!expectFailure) {
            assertThat(steps.findAll().filter { it.executionId == id }.map { it.status })
                .containsOnly(StepStatus.COMPLETED)
        }
        return id
    }

    private fun agent(name: String, modelId: Long, granted: String? = null): Long {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "$name", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()

        val grant = if (granted == null) "" else """, skillCatalogs: ["$granted"]"""
        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "$name", modelId: $modelId$grant }) { id } }""",
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

    private fun catalog(name: String): Long = graphQlTester.document(
        """mutation { createSkillCatalog(workspaceId: $workspaceId, name: "$name") { id } }""",
    ).execute().path("createSkillCatalog.id").entity(Long::class.java).get()

    private fun skill(name: String, catalogId: Long, content: String): Long = graphQlTester.document(
        """mutation { createSkill(input: {
             workspaceId: $workspaceId, name: "$name", catalogId: $catalogId,
             content: ${'"'}${'"'}${'"'}---
name: $name
description: How to review
---

$content
${'"'}${'"'}${'"'}
           }) { id } }""",
    ).execute().path("createSkill.id").entity(Long::class.java).get()

    private companion object {
        /** What a model says alongside the call, in the reply that makes it. */
        const val REMARK = "Let me read the review skill first."
    }
}
