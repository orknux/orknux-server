package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.llm.LlmSessionAPI
import io.mszymanski.orknux.server.llm.LlmSessionEventKind
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * An agent asked by another gets a session of its own, under the one that
 * asked. Issue #379.
 *
 * The asked agent used to answer in a one-shot conversation written down
 * nowhere: what it looked up, which tools it called, what it was actually
 * asked - gone the moment the answer came back. Now it gets a session keyed
 * under the parent's, titled with what the asking agent called the task, and
 * the session page can list a session's family and switch between them.
 *
 * The model is a stub that answers whatever it is asked, because what is
 * measured is the record and its shape, not the answer.
 *
 * Makes a workspace, a provider, a model, two agents and their sessions, and
 * removes them.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class SubagentSessionTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val asking: AgentRunTools,
    @Autowired val tools: AgentTools,
    @Autowired val api: LlmSessionAPI,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val events: LlmSessionEventRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val scratch: io.mszymanski.orknux.server.llm.LlmSessionStore,
    @Autowired val mapper: tools.jackson.databind.ObjectMapper,
    @Autowired val runs: io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository,
    @Autowired val steps: io.mszymanski.orknux.workflow.execution.ExecutionStepRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer

    /** Every request the stub model was sent, in order. */
    private val sent = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** How long the stub model takes to answer, for an ask that is still working. */
    @Volatile
    private var answerAfterMillis = 0L

    @BeforeEach
    fun reset() {
        events.deleteAll()
        sessions.deleteAll()
        agents.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "asking")).id)
    }

    @AfterEach
    fun stop() {
        if (::server.isInitialized) server.stop(0)
    }

    /* ------------------------------------------------------------ fixture */

    private fun serve(): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            sent.add(exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() })
            if (answerAfterMillis > 0) Thread.sleep(answerAfterMillis)
            val bytes = """{"choices":[{"message":{"role":"assistant","content":"Forty-two."}}],
               "usage":{"prompt_tokens":3,"completion_tokens":1}}""".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}"
    }

    private fun model(): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub", endpoint: "${serve()}", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT })
               { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }

    private fun pair(): Pair<Agent, Agent> {
        val modelId = model()
        val librarian = agents.save(Agent(workspaceId = workspaceId, name = "Librarian", type = AgentType.LLM, modelId = modelId))
        val asker = agents.save(
            Agent(
                workspaceId = workspaceId, name = "Planner", type = AgentType.LLM, modelId = modelId,
                agents = mutableListOf(requireNotNull(librarian.id)),
            ),
        )
        return asker to librarian
    }

    /* ---------------------------------------------------- what is kept ---- */

    @Test
    fun `the asked agent's conversation is written under the session that asked`() {
        val (asker, _) = pair()
        val main = recorder.open(workspaceId, "chat", "planning")

        asking.run(asker, """{"agent":"Librarian","question":"What is the answer?","title":"Find the answer"}""", parent = main)

        val children = sessions.findByParentSessionIdOrderByCreatedAtAscIdAsc(main)
        assertThat(children).hasSize(1)
        val child = children.single()
        assertThat(child.title).isEqualTo("Find the answer")
        assertThat(child.keyPrefix).isEqualTo("chat:planning")
        assertThat(child.workspaceId).isEqualTo(workspaceId)
    }

    @Test
    fun `and holds the question under the asker's name, and the answer`() {
        val (asker, _) = pair()
        val main = recorder.open(workspaceId, "chat", "planning")

        asking.run(asker, """{"agent":"Librarian","question":"What is the answer?"}""", parent = main)
        // It works in the background now; the answer is written when it lands.
        asking.waited(asker, main, """{"seconds":10}""")

        val child = sessions.findByParentSessionIdOrderByCreatedAtAscIdAsc(main).single()
        val lines = events.search(requireNotNull(child.id), "", io.mszymanski.orknux.server.flag.ConcealedTools.hiddenFrom(true), listOf(-1L), org.springframework.data.domain.PageRequest.of(0, 20)).content
        assertThat(lines.map { it.kind to it.actor }).contains(
            LlmSessionEventKind.USER to "Planner",
            LlmSessionEventKind.AGENT to "Librarian",
        )
    }

    /** No title given: the first line of the question stands in. */
    @Test
    fun `left untitled, the task is called by the first line of the question`() {
        val (asker, _) = pair()
        val main = recorder.open(workspaceId, "chat", "planning")

        asking.run(asker, """{"agent":"Librarian","question":"Which shelf is it on?\nIt is a red book."}""", parent = main)

        assertThat(sessions.findByParentSessionIdOrderByCreatedAtAscIdAsc(main).single().title)
            .isEqualTo("Which shelf is it on?")
    }

    /**
     * Asked again, the same agent carries on the conversation it had. A
     * follow-up - "refine what you found" - used to open a session that had
     * never seen the first answer, so the agent searched again from nothing.
     */
    @Test
    fun `a follow-up to the same agent continues its conversation, with what it answered before`() {
        val (asker, _) = pair()
        val main = recorder.open(workspaceId, "chat", "planning")

        asking.run(asker, """{"agent":"Librarian","question":"First?"}""", parent = main)
        asking.waited(asker, main, """{"seconds":10}""")
        val again = mapper.readTree(asking.run(asker, """{"agent":"Librarian","question":"Second?"}""", parent = main))
        asking.waited(asker, main, """{"seconds":10}""")

        assertThat(again.path("continued").asBoolean()).isTrue()
        assertThat(sessions.findByParentSessionIdOrderByCreatedAtAscIdAsc(main)).hasSize(1)
        // The second request carries the first question and its answer, then the follow-up once.
        val second = sent.last()
        assertThat(second).contains("First?").contains("Forty-two.")
        assertThat(second.split("Second?").size - 1).isEqualTo(1)
    }

    /**
     * A streaming step's agent is not held by agent_wait, and is not offered
     * it. What it writes is handed on as it goes, so a wait held back "I'm
     * checking this" until the answer came; the answer wakes it later instead.
     */
    @Test
    fun `agent_wait in a streaming step's conversation does not wait, and is not offered`() {
        val (asker, _) = pair()
        val main = stepConversation(streamOutput = true)
        answerAfterMillis = 3_000

        val asked = mapper.readTree(asking.run(asker, """{"agent":"Librarian","question":"What is the answer?"}""", parent = main))
        val started = System.nanoTime()
        val said = mapper.readTree(asking.waited(asker, main, """{"seconds":30}"""))

        assertThat((System.nanoTime() - started) / 1_000_000).describedAs("milliseconds spent").isLessThan(1_500)
        assertThat(said.path("waited").asInt()).isEqualTo(0)
        assertThat(said.path("note").asString()).contains("End your turn now")
        // Told to end its turn, not to call a tool it does not have.
        assertThat(asked.path("note").asString()).contains("end your turn").doesNotContain(AgentRunTools.WAIT)
        val offered = tools.offeringFor(asker, null, main).let { it.core + it.searchable }.map { it.name }
        assertThat(offered).contains(AgentRunTools.ASK).doesNotContain(AgentRunTools.WAIT)
        assertThat(tools.offeringFor(asker, null, main).core.single { it.name == AgentRunTools.ASK }.description)
            .doesNotContain(AgentRunTools.WAIT)
        answerAfterMillis = 0
    }

    /** A step that does not stream sends only its last answer, so there nothing is held back by waiting. */
    @Test
    fun `agent_wait in a step that does not stream waits, and is offered`() {
        val (asker, _) = pair()
        val main = stepConversation(streamOutput = false)
        answerAfterMillis = 1_000

        asking.run(asker, """{"agent":"Librarian","question":"What is the answer?"}""", parent = main)
        val said = mapper.readTree(asking.waited(asker, main, """{"seconds":30}"""))

        assertThat(said.path("waited").asInt()).isGreaterThan(0)
        assertThat(tools.offeringFor(asker, null, main).let { it.core + it.searchable }.map { it.name })
            .contains(AgentRunTools.WAIT)
        answerAfterMillis = 0
    }

    /** A conversation an agent node's step writes into. */
    private fun stepConversation(streamOutput: Boolean): Long {
        val main = recorder.open(workspaceId, "chat", "planning")
        val run = runs.save(
            io.mszymanski.orknux.workflow.execution.WorkflowExecution(
                workspaceId = workspaceId, workflowId = 1, workflowName = "Reply",
                status = io.mszymanski.orknux.workflow.execution.ExecutionStatus.RUNNING,
                trigger = io.mszymanski.orknux.workflow.execution.ExecutionTrigger.MANUAL,
                startedAt = java.time.OffsetDateTime.now(),
            ),
        )
        steps.save(
            io.mszymanski.orknux.workflow.execution.ExecutionStep(
                executionId = requireNotNull(run.id), nodeKey = "think",
                kind = io.mszymanski.orknux.workflow.execution.NodeKind.AGENT, name = "Think", order = 0, x = 0.0, y = 0.0,
                streamOutput = streamOutput,
            ).also { it.sessionId = main },
        )
        return main
    }

    /** Asked with `fresh`, it starts a conversation of its own, for an unrelated question. */
    @Test
    fun `a fresh ask opens a conversation of its own`() {
        val (asker, _) = pair()
        val main = recorder.open(workspaceId, "chat", "planning")

        asking.run(asker, """{"agent":"Librarian","question":"First?"}""", parent = main)
        asking.waited(asker, main, """{"seconds":10}""")
        asking.run(asker, """{"agent":"Librarian","question":"Unrelated?","fresh":true}""", parent = main)

        assertThat(sessions.findByParentSessionIdOrderByCreatedAtAscIdAsc(main)).hasSize(2)
    }

    /** Asked from nowhere - a round with no session - nothing is opened, as before. */
    @Test
    fun `an asker in no session leaves no session behind`() {
        val (asker, _) = pair()

        asking.run(asker, """{"agent":"Librarian","question":"Anything?"}""")

        assertThat(sessions.findAll()).isEmpty()
    }

    /* ------------------------------------------------- handed on by key --- */

    /**
     * The answer is kept in the asker's session under a key the answer names,
     * so an upload can take it from the server rather than from the asker
     * typing it back - which is what cut a ten-thousand-character page off at
     * the model's output cap. Issue #393.
     */
    @Test
    fun `the answer is kept in the asker's session under the key the answer names`() {
        val (asker, _) = pair()
        val main = recorder.open(workspaceId, "chat", "planning")

        /*
         * Asked, waited for, and read back - the way an agent does it since
         * asks stopped blocking (#462). Issue #536: the answer used to be lost
         * at the last step, agent_asks listing who was asked and never what
         * they said.
         */
        val started = mapper.readTree(
            asking.run(asker, """{"agent":"Librarian","question":"What is the answer?"}""", parent = main),
        )
        assertThat(started.path("working").asBoolean()).isTrue()
        asking.waited(asker, main, """{"seconds":10}""")

        val answered = mapper.readTree(asking.asked(asker, main)).path("asks").single()
        assertThat(answered.path("working").asBoolean()).isFalse()
        assertThat(answered.path("answer").stringValue()).isEqualTo("Forty-two.")
        val key = answered.path("contentKey").stringValue()
        assertThat(key).startsWith("answer.")
        assertThat(scratch.get(main, key)).isEqualTo("\"Forty-two.\"")
    }

    /**
     * A finished ask is let go of. Issue #616: its future, holding the whole
     * answer, and its conversation's permits stayed in memory for as long as
     * the server ran. What `agent_asks` says afterwards is read from the
     * transcript and the store, so it must not change.
     */
    @Test
    fun `a finished ask holds nothing in memory, and is still read back whole`() {
        val (asker, _) = pair()
        val main = recorder.open(workspaceId, "chat", "planning")

        asking.run(asker, """{"agent":"Librarian","question":"What is the answer?"}""", parent = main)
        val child = requireNotNull(sessions.findByParentSessionIdOrderByCreatedAtAscIdAsc(main).single().id)
        asking.waited(asker, main, """{"seconds":10}""")

        // The future is let go just after it is marked done, on the ask's own thread.
        val until = System.nanoTime() + 5_000_000_000L
        while ((asking.holdsAsk(child) || asking.holdsGate(main)) && System.nanoTime() < until) Thread.sleep(20)
        assertThat(asking.holdsAsk(child)).describedAs("the ask's future").isFalse()
        assertThat(asking.holdsGate(main)).describedAs("the conversation's permits").isFalse()

        val answered = mapper.readTree(asking.asked(asker, main)).path("asks").single()
        assertThat(answered.path("working").asBoolean()).isFalse()
        assertThat(answered.path("answer").stringValue()).isEqualTo("Forty-two.")
        assertThat(answered.path("contentKey").stringValue()).isEqualTo("answer.$child")
    }

    /**
     * What the asked agent's own tools kept comes up with the answer: a key
     * its answer names has to work in the conversation that asked. The
     * asker's own keys win, and the ceiling is the ceiling.
     */
    @Test
    fun `what a subagent's tools kept is copied into the asker's session, the asker's own keys kept`() {
        val main = recorder.open(workspaceId, "chat", "planning")
        val child = recorder.openUnder(main, "Draw it")
        scratch.put(child, "picture.7", "\"iVBOR\"")
        scratch.put(child, "shared", "\"the child's\"")
        scratch.put(main, "shared", "\"the asker's\"")

        val copied = scratch.copy(from = child, into = main)

        assertThat(copied).isEqualTo(1)
        assertThat(scratch.get(main, "picture.7")).isEqualTo("\"iVBOR\"")
        assertThat(scratch.get(main, "shared")).isEqualTo("\"the asker's\"")
        assertThat(scratch.get(child, "picture.7")).describedAs("left where it was").isEqualTo("\"iVBOR\"")
    }

    @Test
    fun `an asker in no session gets the answer and no key, because there is nowhere to keep it`() {
        val (asker, _) = pair()

        val said = asking.run(asker, """{"agent":"Librarian","question":"Anything?"}""")

        assertThat(mapper.readTree(said).has("contentKey")).isFalse()
    }

    /* ------------------------------------------------------- the family --- */

    @Test
    fun `a family is listed from the top, whichever member is asked`() {
        val (asker, _) = pair()
        val main = recorder.open(workspaceId, "chat", "planning")
        asking.run(asker, """{"agent":"Librarian","question":"One?","title":"One"}""", parent = main)
        asking.run(asker, """{"agent":"Librarian","question":"Two?","title":"Two","fresh":true}""", parent = main)
        val second = sessions.findByParentSessionIdOrderByCreatedAtAscIdAsc(main).last()

        val fromTop = api.llmSessionFamily(main)
        val fromChild = api.llmSessionFamily(requireNotNull(second.id))

        assertThat(fromTop.map { it.title }).containsExactly("Main session", "One", "Two")
        assertThat(fromTop.first().main).isTrue()
        assertThat(fromTop.first().id).isEqualTo(main)
        assertThat(fromChild.map { it.id }).isEqualTo(fromTop.map { it.id })
    }

    /**
     * Green while somebody is there: a line within the last minute, or a tool
     * called and not yet answered. Orange once it has gone quiet.
     */
    @Test
    fun `a member is active while an agent is at work in it, and not afterwards`() {
        val main = recorder.open(workspaceId, "chat", "planning")
        val child = recorder.openUnder(main, "Quiet one")
        val busy = recorder.openUnder(main, "Busy one")
        recorder.toolCalled(busy, "find_tools", "{}")

        val listed = api.llmSessionFamily(main).associateBy { it.title }

        assertThat(listed.getValue("Busy one").active).describedAs("a tool called and not answered").isTrue()
        assertThat(listed.getValue("Quiet one").active).describedAs("nothing said in it").isFalse()

        val old = sessions.findByIdOrNull(child)!!
        assertThat(old.lastEventAt).isNull()
    }

    @Test
    fun `a session opened under another says so`() {
        val main = recorder.open(workspaceId, "chat", "planning")
        val child = recorder.openUnder(main, "Find the answer")

        graphQlTester.document("""query { llmSession(id: $child) { title parentId } }""").execute()
            .path("llmSession.title").entity(String::class.java).isEqualTo("Find the answer")
            .path("llmSession.parentId").entity(Long::class.java).isEqualTo(main)
    }
}
