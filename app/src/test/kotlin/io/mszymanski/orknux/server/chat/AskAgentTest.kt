package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper

/**
 * One agent putting a question to another.
 *
 * Issue #350. An agent needing work done in a system it holds no tools for had
 * two ways out and both are bad: be granted those tools as well - forty
 * descriptions in its context, and a chain of lookups in its rounds before the
 * work it was asked about begins - or hand the job back to whoever asked.
 *
 * So an agent may be granted other agents and gets one tool. The specialist
 * answers in a conversation of its own, and what comes back is the answer rather
 * than the twelve rounds that produced it.
 *
 * What is pinned here is the grant and the bounds on it, rather than the model
 * call: which agents are offered, that an agent reached this way is granted none
 * of its own - the one rule that makes a ring of specialists impossible - and
 * that nothing outside the grant can be reached by naming it.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class AskAgentTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val asking: AgentRunTools,
    @Autowired val tools: AgentTools,
    @Autowired val agents: AgentRepository,
    @Autowired val workspaces: WorkspaceRepository,
    /** For the conversation an ask opens, and the setup written into it. Issue #456. */
    @Autowired val recorder: io.mszymanski.orknux.server.llm.LlmSessionRecorder,
    @Autowired val sessions: io.mszymanski.orknux.server.llm.LlmSessionRepository,
    @Autowired val events: io.mszymanski.orknux.server.llm.LlmSessionEventRepository,
    @Autowired val models: io.mszymanski.orknux.connector.model.LlmModelRepository,
    @Autowired val providers: io.mszymanski.orknux.connector.model.ModelProviderRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0
    private var otherWorkspaceId: Long = 0

    /** The provider the one test that lets an agent answer talks to; see [model]. */
    private var server: com.sun.net.httpserver.HttpServer? = null

    @BeforeEach
    fun make() {
        events.deleteAll()
        sessions.deleteAll()
        agents.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        /*
         * Found rather than made again: a workspace name is unique and the table
         * is not emptied between classes.
         */
        workspaceId = requireNotNull(
            (workspaces.findByName("asking") ?: workspaces.save(Workspace(name = "asking"))).id,
        )
        otherWorkspaceId = requireNotNull(
            (workspaces.findByName("asking elsewhere") ?: workspaces.save(Workspace(name = "asking elsewhere"))).id,
        )
    }

    @org.junit.jupiter.api.AfterEach
    fun stop() {
        server?.stop(0)
        server = null
    }

    /**
     * A provider that answers anything, and a model on it. Only the one test
     * that lets a specialist actually answer needs it - the rest are about what
     * is offered and what is refused, and neither reaches a model.
     */
    private fun model(thought: String? = null): Long {
        val started = com.sun.net.httpserver.HttpServer.create(
            java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0),
            0,
        )
        started.createContext("/chat/completions") { exchange ->
            exchange.requestBody.reader(java.nio.charset.StandardCharsets.UTF_8).use { it.readText() }
            // With a thought, streamed the way a reasoning model answers: the reasoning, then the answer.
            val bytes = if (thought != null) {
                listOf(
                    """{"choices":[{"delta":{"reasoning_content":"$thought"}}]}""",
                    """{"choices":[{"delta":{"content":"Twice a year."}}]}""",
                    """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":1}}""",
                ).joinToString("") { "data: $it\n\n" }.plus("data: [DONE]\n\n").toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            } else {
                """{"choices":[{"message":{"role":"assistant","content":"Twice a year."}}],
               "usage":{"prompt_tokens":3,"completion_tokens":1}}""".toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            }
            exchange.responseHeaders.add("Content-Type", if (thought != null) "text/event-stream" else "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        started.start()
        server = started
        val endpoint = "http://${started.address.hostString}:${started.address.port}"

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

    private fun agent(
        name: String,
        workspace: Long = workspaceId,
        asks: List<Long> = emptyList(),
        model: Long? = null,
    ): Agent =
        agents.save(
            Agent(
                workspaceId = workspace,
                name = name,
                type = AgentType.LLM,
                modelId = model,
                agents = asks.toMutableList(),
                // As a fresh agent is made: `ask_agent` is a name on the Tools
                // list since #444, and a row built without it has hidden the
                // tool this test is about.
                tools = BuiltInTools.GRANTED.toMutableList(),
            ),
        )

    private fun said(answer: String): String = mapper.readTree(answer).path("error").stringValue()

    /* ---------------------------------------------- whether it is offered */

    @Test
    fun `an agent granted nobody is offered nothing`() {
        val alone = agent("Alone")

        assertThat(asking.offered(alone)).isFalse()
        assertThat(tools.specsFor(alone).map { it.name }).doesNotContain(AgentRunTools.ASK)
    }

    @Test
    fun `an agent granted somebody is offered the tool, naming who it may ask`() {
        val specialist = agent("Librarian")
        val asker = agent("Support", asks = listOf(requireNotNull(specialist.id)))

        assertThat(asking.offered(asker)).isTrue()

        val spec = tools.specsFor(asker).single { it.name == AgentRunTools.ASK }
        assertThat(spec.description).contains("Librarian")
        assertThat(spec.parameters.map { it.name })
            .containsExactly(AgentRunTools.AGENT, AgentRunTools.QUESTION, AgentRunTools.TITLE)
        // Both needed: a question with nobody to ask, or somebody to ask with no
        // question, is a round spent being told so. The title is not - the
        // first line of the question stands in (issue #379).
        assertThat(spec.parameters.filter { it.required }.map { it.name })
            .containsExactly(AgentRunTools.AGENT, AgentRunTools.QUESTION)
    }

    /* ------------------------------------------------ what it will not do */

    @Test
    fun `an agent it was not granted cannot be reached by naming it`() {
        val specialist = agent("Librarian")
        val stranger = agent("Auditor")
        val asker = agent("Support", asks = listOf(requireNotNull(specialist.id)))

        val refused = asking.run(asker, """{"agent":"${stranger.name}","question":"anything"}""")
        assertThat(said(refused)).contains("not been given an agent called")
        // And it is told what it may ask, rather than only what it may not.
        assertThat(said(refused)).contains("Librarian")
    }

    @Test
    fun `an agent in another workspace is not among them however it is named`() {
        val elsewhere = agent("Librarian", workspace = otherWorkspaceId)
        val asker = agent("Support", asks = listOf(requireNotNull(elsewhere.id)))

        /*
         * Granted by id in the row and still unreachable: the grant is read
         * against the asking agent's own workspace, so a row that named one
         * elsewhere - a restore, a hand-edited database - offers nothing.
         */
        assertThat(asking.offered(asker)).isFalse()
        assertThat(said(asking.run(asker, """{"agent":"Librarian","question":"anything"}"""))).isNotEmpty()
    }

    @Test
    fun `an agent with no model is reported rather than called`() {
        val specialist = agent("Librarian")
        val asker = agent("Support", asks = listOf(requireNotNull(specialist.id)))

        assertThat(said(asking.run(asker, """{"agent":"Librarian","question":"what is the policy"}""")))
            .contains("has no model chosen")
    }

    @Test
    fun `a question with nothing in it is refused rather than asked`() {
        val specialist = agent("Librarian")
        val asker = agent("Support", asks = listOf(requireNotNull(specialist.id)))

        assertThat(said(asking.run(asker, """{"agent":"Librarian"}"""))).contains("are both needed")
        assertThat(said(asking.run(asker, """{"question":"what is the policy"}"""))).contains("are both needed")
    }

    /* ------------------------------------------------------- the one level */

    /**
     * The rule the whole feature rests on.
     *
     * A depth counter would be a number somebody has to choose, and every value
     * of it leaves a ring of specialists calling each other until it runs out.
     * Nothing to call cannot be got round.
     */
    @Test
    fun `an agent reached this way is granted no agents of its own`() {
        val deepest = agent("Archivist")
        val middle = agent("Librarian", asks = listOf(requireNotNull(deepest.id)))
        val asker = agent("Support", asks = listOf(requireNotNull(middle.id)))

        // The middle one holds a grant of its own on its row, and uses it when
        // it is the one being asked directly.
        assertThat(asking.offered(middle)).isTrue()
        assertThat(tools.specsFor(middle).map { it.name }).contains(AgentRunTools.ASK)

        // What the asker reaches is the same agent without it. Asserted through
        // the refusal, which names who the specialist could have asked: reached
        // this way, that list is empty and the tool is not there to be called.
        assertThat(asking.offered(asker)).isTrue()
        val spec = tools.specsFor(asker).single { it.name == AgentRunTools.ASK }
        assertThat(spec.description).contains("Librarian").doesNotContain("Archivist")
    }

    /* ------------------------------------------ who answered, written down */

    /**
     * A subagent's session opens with the setup the asked agent answered under.
     * Issue #456.
     *
     * An agent node and a task both write that line where an agent starts
     * responding, and the one conversation in a family whose agent nobody chose
     * did not: a subagent's session opened with a tool call, and nothing in it
     * ever said which agent had been asked, on which model, with what in front of
     * it or holding what. Which is the first question anybody reading one has.
     *
     * Three things are pinned here. That the line is there at all and comes
     * *before* the question, so the log opens with the setup its words were said
     * under. That it is the setup the round was actually given - the asked
     * agent's own model, its id, and the prompt including what its lent
     * scratchpads say about themselves, which for a subagent carries a paragraph
     * nothing else gets. And that the tools are the ones the model was handed:
     * reached this way an agent is granted nobody, so `ask_agent` is absent from
     * the record exactly as it was absent from the request.
     */
    @Test
    fun `a subagent's session opens with the agent's setup, before the question`() {
        val modelId = model()
        val specialist = agent("Librarian", model = modelId)
        val asker = agent("Support", asks = listOf(requireNotNull(specialist.id)), model = modelId)
        val main = recorder.open(workspaceId, "chat", "asking-${System.nanoTime()}")

        asking.run(asker, """{"agent":"Librarian","question":"How often is it reviewed?"}""", parent = main)

        val child = sessions.findByParentSessionIdOrderByCreatedAtAscIdAsc(main).single()
        val lines = events.findAll().filter { it.sessionId == child.id }.sortedBy { it.id }
        val details = lines.filter { it.kind == io.mszymanski.orknux.server.llm.LlmSessionEventKind.AGENT_DETAILS }

        assertThat(details).describedAs("one line, for the agent that was asked").hasSize(1)
        assertThat(details.single().actor).isEqualTo("Librarian")
        assertThat(lines.first().kind)
            .describedAs("first in the log, before the question the asker put")
            .isEqualTo(io.mszymanski.orknux.server.llm.LlmSessionEventKind.AGENT_DETAILS)

        val held = mapper.readTree(details.single().content)
        assertThat(held.path("agent").stringValue()).isEqualTo("Librarian")
        assertThat(held.path("agentId").asLong()).isEqualTo(specialist.id)
        assertThat(held.path("model").stringValue()).isEqualTo("Stub")
        // The prompt is what the round was given, which for an agent with no
        // prose of its own is what its lent tools say about themselves - and a
        // subagent is told something nothing else is. Issues #454, #458.
        assertThat(held.path("systemPrompt").stringValue())
            .contains("You have scratchpads")
            .contains("You are answering another agent")

        val handed = held.path("tools").mapNotNull { it.stringValue() }
        assertThat(handed).contains(ScratchpadTools.WRITE)
        assertThat(handed)
            .describedAs("reached this way it is granted nobody, so the record says so too")
            .doesNotContain(AgentRunTools.ASK)
    }

    /**
     * What the asked agent thought is in its session, as a task's and a node's is.
     *
     * Reported on session 569: a request, a lookup and an answer, with the
     * half-minute the model spent reasoning between them recorded nowhere.
     */
    @Test
    fun `a subagent's thinking is written into its session`() {
        val modelId = model(thought = "Two policies mention a review cycle.")
        val specialist = agent("Librarian", model = modelId)
        val asker = agent("Support", asks = listOf(requireNotNull(specialist.id)), model = modelId)
        val main = recorder.open(workspaceId, "chat", "thinking-${System.nanoTime()}")

        asking.run(asker, """{"agent":"Librarian","question":"How often is it reviewed?"}""", parent = main)

        val child = sessions.findByParentSessionIdOrderByCreatedAtAscIdAsc(main).single()
        // The ask runs on its own thread; its answer is the last line it writes.
        val deadline = System.currentTimeMillis() + 15_000
        fun lines() = events.findAll().filter { it.sessionId == child.id }
        while (lines().none { it.kind == io.mszymanski.orknux.server.llm.LlmSessionEventKind.AGENT } &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(100)
        }
        val thinking = lines().filter { it.kind == io.mszymanski.orknux.server.llm.LlmSessionEventKind.THINKING }
        assertThat(thinking).hasSize(1)
        assertThat(thinking.single().actor).isEqualTo("Librarian")
        assertThat(thinking.single().content).isEqualTo("Two policies mention a review cycle.")
        assertThat(thinking.single().millis).describedAs("settled, not left reading as still thinking").isNotNull()
    }

    /* --------------------------------------------------- setting the grant */

    @Test
    fun `the grant is what the screen sets and reads back`() {
        val specialist = agent("Librarian")
        val asker = agent("Support")

        graphQlTester.document(
            """mutation { updateAgent(id: ${asker.id}, input: {
                 name: "Support", agentIds: [${specialist.id}]
               }) { agentIds } }""",
        ).execute().path("updateAgent.agentIds").entityList(String::class.java)
            .containsExactly(specialist.id.toString())

        assertThat(agents.findById(requireNotNull(asker.id)).orElseThrow().agents)
            .containsExactly(specialist.id)
    }

    @Test
    fun `an agent cannot be given itself to ask`() {
        val asker = agent("Support")

        graphQlTester.document(
            """mutation { updateAgent(id: ${asker.id}, input: {
                 name: "Support", agentIds: [${asker.id}]
               }) { agentIds } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors).singleElement()
                .satisfies({ assertThat(it.message).contains("cannot be given itself") })
        }

        assertThat(agents.findById(requireNotNull(asker.id)).orElseThrow().agents).isEmpty()
    }

    /**
     * Refused rather than dropped, which is the rule the connection grant keeps:
     * a grant silently thrown away is a form that says it saved and a tool that
     * then cannot see what somebody ticked.
     */
    @Test
    fun `an agent from another workspace is refused rather than dropped`() {
        val elsewhere = agent("Librarian", workspace = otherWorkspaceId)
        val asker = agent("Support")

        graphQlTester.document(
            """mutation { updateAgent(id: ${asker.id}, input: {
                 name: "Support", agentIds: [${elsewhere.id}]
               }) { agentIds } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors).singleElement()
                .satisfies({ assertThat(it.message).contains("not one of this workspace's agents") })
        }

        assertThat(agents.findById(requireNotNull(asker.id)).orElseThrow().agents).isEmpty()
    }
}
