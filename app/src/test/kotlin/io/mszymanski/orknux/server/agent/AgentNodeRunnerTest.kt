package io.mszymanski.orknux.server.agent

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.workflow.WorkflowEdgeRepository
import io.mszymanski.orknux.server.workflow.WorkflowNodeRepository
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.EdgeBranch
import io.mszymanski.orknux.workflow.execution.ExecutionLogRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
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
import io.mszymanski.orknux.connector.connection.SlackFile
import io.mszymanski.orknux.connector.connection.SlackFiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * An agent node in a workflow, run.
 *
 * The point of this one is that a workflow and a chat get the same agent: the
 * node asks through the same loop, with the same briefing and the same tools, so
 * an agent behaves the same whether somebody is talking to it or a run is. Two
 * behaviours under one name is a difference nobody sees until it matters.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class AgentNodeRunnerTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
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
    /** For reading what a node's turn wrote down while the model was thinking. */
    @Autowired val sessions: io.mszymanski.orknux.server.llm.LlmSessionRepository,
    @Autowired val sessionEvents: io.mszymanski.orknux.server.llm.LlmSessionEventRepository,
    @Autowired val runner: AgentNodeRunner,
    /** For reading the setup snapshot back as the page does. #446. */
    @Autowired val mapper: tools.jackson.databind.ObjectMapper,
) {

    /**
     * Slack's side of a picture, stood in for.
     *
     * What is under test is everything after the bytes arrive: the payload's
     * `files` read, the picture turned into a turn of its own, and the request
     * that reaches the model carrying it. Fetching is
     * [io.mszymanski.orknux.connector.connection.SlackFilesTest]'s business,
     * and a test that reached Slack would be a test that needs a token.
     */
    @MockitoBean
    private lateinit var slackFiles: SlackFiles

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
        skills.deleteAll()
        catalogs.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        sessionEvents.deleteAll()
        sessions.deleteAll()
        workspaces.deleteAll()
        received.clear()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Incident Response" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `an agent node answers, and what it said is what the next node is handed`() {
        val agentId = agent("Reviewer", model(serveAnswer()), prompt = "You summarise incidents.")
        graph(agentId)

        start()

        val step = steps.findAll().single { it.agentId == agentId }
        assertThat(step.status).isEqualTo(StepStatus.COMPLETED)
        assertThat(step.output).isEqualTo("The database was the cause.")
        assertThat(executions.findAll().single().status).isEqualTo(ExecutionStatus.COMPLETED)

        // The run's input became the question, and the agent's instructions came
        // with it — the same briefing a chat with this agent would open on.
        assertThat(received.single()).contains("You summarise incidents.")
        assertThat(received.single()).contains("the database fell over")
    }

    /**
     * A node pointing at no agent is unfinished, not broken.
     *
     * A graph is drawn before it is finished, so the run says what it found and
     * carries on rather than failing the workflow over a node nobody has
     * configured yet.
     */
    @Test
    fun `a node naming no agent is skipped, and says so`() {
        // Still needs a server: the fixture stops one after every test.
        serveAnswer()
        graph(agentId = null)

        start()

        val step = steps.findAll().single()
        assertThat(step.status).isEqualTo(StepStatus.SKIPPED)
        assertThat(step.output).contains("names no agent")
        assertThat(executions.findAll().single().status).isEqualTo(ExecutionStatus.COMPLETED)
    }

    @Test
    fun `an agent with no model fails the step, and says what is missing`() {
        serveAnswer()
        val agentId = agent("Reviewer", modelId = null)
        graph(agentId)

        start(expectFailure = true)

        val step = steps.findAll().single()
        assertThat(step.status).isEqualTo(StepStatus.FAILED)
        assertThat(step.error).contains("has no model chosen")
    }

    /**
     * The point of the whole thing: a provider saying "not now" is not an
     * answer about the request, so the node asks again and the run finishes.
     */
    @Test
    fun `a rate limited call is asked again, and the run finishes on a later attempt`() {
        val agentId = agent("Reviewer", model(serveAfter(refusals = 2, status = 429)))
        graph(agentId, attempts = 3)

        start()

        val step = steps.findAll().single { it.agentId == agentId }
        assertThat(step.status).isEqualTo(StepStatus.COMPLETED)
        assertThat(step.attempts).isEqualTo(3)
        assertThat(step.output).isEqualTo("The database was the cause.")
        assertThat(executions.findAll().single().status).isEqualTo(ExecutionStatus.COMPLETED)
        // Three calls really were made, rather than one answer being reused.
        assertThat(received).hasSize(3)
    }

    /**
     * The other half, and the one that costs money to get wrong. A 400 is the
     * provider having read the request and refused it; the same request will be
     * refused in the same words, so the policy is not spent proving it.
     */
    @Test
    fun `a request the provider refused is not asked again, whatever the policy says`() {
        val agentId = agent("Reviewer", model(serveAfter(refusals = 9, status = 400)))
        graph(agentId, attempts = 5)

        start(expectFailure = true)

        val step = steps.findAll().single { it.agentId == agentId }
        assertThat(step.status).isEqualTo(StepStatus.FAILED)
        assertThat(step.attempts).isEqualTo(1)
        assertThat(step.error).contains("could not answer")
        assertThat(received).hasSize(1)
    }

    /**
     * And where the graph has an answer for it, the run goes on down the edge
     * drawn for exactly this rather than ending at the agent.
     */
    @Test
    fun `an agent that could not answer leaves by its failure edge`() {
        val agentId = agent("Reviewer", model(serveAfter(refusals = 9, status = 400)))
        withFallback(agentId)

        start()

        val recorded = steps.findAll().associateBy { it.nodeKey }
        assertThat(recorded.getValue("think").status).isEqualTo(StepStatus.FAILED)
        assertThat(recorded.getValue("think").branch).isEqualTo(EdgeBranch.FAILURE)
        assertThat(recorded.getValue("rescue").status).isEqualTo(StepStatus.COMPLETED)
        assertThat(executions.findAll().single().status).isEqualTo(ExecutionStatus.COMPLETED)
    }

    private fun serveAnswer(): String = serveAfter(refusals = 0, status = 200)

    /**
     * Held to a shape, the answer comes through as the object - fields a later
     * node can address - and the model was told the shape in its instructions.
     */
    @Test
    fun `an agent held to a shape hands on the object its answer parsed into`() {
        val shape = verdictShape()
        val agentId = agent("Reviewer", model(serveAfter(0, 200, saying = """{ "cause": "the database", "urgent": true }""")))
        shapedGraph(agentId, shape)

        start()

        val step = steps.findAll().single { it.agentId == agentId }
        assertThat(step.status).isEqualTo(StepStatus.COMPLETED)
        assertThat(step.output).isEqualTo("""{"verdict":{"cause":"the database","urgent":true}}""")
        assertThat(received.single()).contains("single JSON object").contains("\\\"cause\\\"")
    }

    /**
     * An answer that does not comply fails the step unsettled, so the node's
     * own retry policy re-asks - the model is the flaky dependency here, and
     * the second ask is the enforcement.
     */
    @Test
    fun `an answer off the shape is re-asked under the node's policy, then fails`() {
        val shape = verdictShape()
        val agentId = agent("Reviewer", model(serveAnswer()))
        shapedGraph(agentId, shape, attempts = 2)

        start(expectFailure = true)

        val step = steps.findAll().single { it.agentId == agentId }
        assertThat(step.status).isEqualTo(StepStatus.FAILED)
        assertThat(step.error).contains("JSON object")
        assertThat(received).describedAs("the node's two attempts each asked the model").hasSize(2)
    }

    /**
     * The other way to shape an answer: the agent points at an object node on
     * the graph and answers with that node's voice - the shape derived from
     * the target, the answer emitted under the target's output name, before
     * the run moves to any next node. The object node is a declaration and
     * never runs; it needs no wiring, which is the point - the node stands
     * wherever it reads best, and everything after the *agent* can read it.
     */
    @Test
    fun `an agent saving into an object node answers with the node's voice`() {
        val shape = verdictShape()
        val agentId = agent("Reviewer", model(serveAfter(0, 200, saying = """{ "cause": "the database", "urgent": true }""")))

        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "think", kind: AGENT, name: "Reviewer", agentId: $agentId,
                    outputNodeKey: "keep", outputName: "llmResult", x: 0, y: 0 },
                  { key: "keep", kind: OBJECT, name: "Verdict", objectId: $shape,
                    outputName: "verdict", x: 200, y: 0 }
                ],
                edges: []
              }) { nodes { key outputObjectId outputNodeKey } }
            }
            """,
        ).execute()
            // The shape was never sent; it is derived from the target at the save.
            .path("saveWorkflowGraph.nodes[0].outputObjectId").entity(Long::class.java).isEqualTo(shape)

        start()

        val recorded = steps.findAll().associateBy { it.nodeKey }
        assertThat(recorded.getValue("think").status).isEqualTo(StepStatus.COMPLETED)
        // Under the object node's name, not the agent's own: the redirection
        // is the naming, and the write is done as part of the agent's step.
        assertThat(recorded.getValue("think").output)
            .isEqualTo("""{"verdict":{"cause":"the database","urgent":true}}""")
        // The object node is a declaration, not a step: nothing ran for it.
        assertThat(recorded).doesNotContainKey("keep")
        assertThat(executions.findAll().single().status).isEqualTo(ExecutionStatus.COMPLETED)
    }

    /** A reference that cannot be derived from is refused where it was typed. */
    @Test
    fun `saving into something that is not an object node is refused`() {
        serveAnswer()
        val agentId = agent("Reviewer", modelId = null)

        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "think", kind: AGENT, name: "Reviewer", agentId: $agentId,
                    outputNodeKey: "other", x: 0, y: 0 },
                  { key: "other", kind: AGENT, name: "Second opinion", x: 200, y: 0 }
                ],
                edges: []
              }) { nodes { key } }
            }
            """,
        ).execute().errors().expect { error ->
            error.message.orEmpty().contains("is not an object node")
        }.verify()
    }

    private fun verdictShape(): Long = graphQlTester.document(
        """
        mutation {
          createObject(input: {
            workspaceId: $workspaceId, name: "Verdict",
            properties: [{ name: "cause", kind: STRING }, { name: "urgent", kind: BOOLEAN }]
          }) { id }
        }
        """,
    ).execute().path("createObject.id").entity(Long::class.java).get()

    private fun shapedGraph(agentId: Long, outputObjectId: Long, attempts: Int? = null) {
        val retries = if (attempts == null) "" else ", retryAttempts: $attempts, retryBackoffSeconds: 0"
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [{ key: "think", kind: AGENT, name: "Reviewer", agentId: $agentId,
                          outputObjectId: $outputObjectId, outputName: "verdict"$retries, x: 0, y: 0 }],
                edges: []
              }) { nodes { key outputObjectId } }
            }
            """,
        ).execute().path("saveWorkflowGraph.nodes[0].outputObjectId").entity(Long::class.java).isEqualTo(outputObjectId)
    }

    /**
     * A provider that refuses the first [refusals] calls with [status] and
     * answers after that.
     *
     * Every call is counted in `received` whether it was answered or not, which
     * is what lets a test say how many times the model was actually asked - a
     * step that retried and a step that did not look identical from the count
     * of attempts alone if nothing watches the wire.
     */
    /**
     * What the model thought on its way to the answer, in the session, while it
     * was still thinking it.
     *
     * A node's session had a question, then nothing, then an answer - and for a
     * reasoning model the nothing is most of the turn. A task's turn has
     * written its reasoning down for a while (`SessionThinking`, which began
     * life in the task loop); a node's did not, so the same agent watched
     * through a task page and through a run showed two different amounts of
     * what it was doing.
     *
     * Handing the round a watcher is also what makes it stream, which is the
     * half that matters: written at the end it would be a block that appears
     * once the wait is over, which is the thing nobody was waiting for.
     *
     * The line is asserted whole - what was thought, and that it carries a
     * duration - because a line with no duration is what a page reads as *still
     * thinking*, and one left open after the turn is the bug that reads as the
     * live view having died.
     */
    @Test
    fun `a node's session keeps what the model was thinking, and closes the line when it stops`() {
        val agentId = agent("Reviewer", model(serveThinking()), prompt = "You summarise incidents.")
        withSession(agentId)

        start()

        val session = sessions.findAll().single()
        val lines = sessionEvents.findAll().filter { it.sessionId == session.id }
        val thinking = lines.filter { it.kind == io.mszymanski.orknux.server.llm.LlmSessionEventKind.THINKING }

        assertThat(thinking)
            .describedAs("one line for the round's reasoning, not one per frame")
            .hasSize(1)
        assertThat(thinking.single().content)
            .describedAs("all of it, including the frames inside the last flush window")
            .isEqualTo("Checking what failed. It was the database.")
        assertThat(thinking.single().millis)
            .describedAs("settled: a line with no duration is one a page reads as still being thought")
            .isNotNull()

        // And the turn is otherwise unchanged - the reasoning is not the answer,
        // and is never folded into it.
        assertThat(lines.filter { it.kind == io.mszymanski.orknux.server.llm.LlmSessionEventKind.AGENT }.map { it.content })
            .containsExactly("The database was the cause.")
        assertThat(steps.findAll().single { it.nodeKey == "think" }.status).isEqualTo(StepStatus.COMPLETED)
    }

    /**
     * A provider that sends no reasoning leaves no line at all.
     *
     * "Provided the provider supports thinking" is not a setting anywhere: it
     * is this. The watcher is fed what the model actually thought, blank
     * thinking opens nothing, and a transcript from a model that does not
     * reason reads exactly as it did before any of this.
     */
    @Test
    fun `a model that does not reason leaves no thinking line`() {
        val agentId = agent("Reviewer", model(serveThinking(thought = "")), prompt = "You summarise incidents.")
        withSession(agentId)

        start()

        val session = sessions.findAll().single()
        val kinds = sessionEvents.findAll().filter { it.sessionId == session.id }.map { it.kind }

        // The turn happened, which is what makes the absence below mean
        // something: a session with nothing in it has no thinking either.
        assertThat(kinds).contains(
            io.mszymanski.orknux.server.llm.LlmSessionEventKind.USER,
            io.mszymanski.orknux.server.llm.LlmSessionEventKind.AGENT,
        )
        assertThat(kinds).doesNotContain(io.mszymanski.orknux.server.llm.LlmSessionEventKind.THINKING)
    }

    /**
     * A node with a session writes the agent's setup into the log where the
     * agent starts responding, and again only where that setup changed. Issues
     * #391, #441.
     *
     * Three turns, and what each leaves. The first turn logs the setup, so the
     * log opens with the context its words were said in. The same agent taking
     * the thread again with the same setup logs nothing: a session one agent
     * talks in for a week carries one line, not one per turn. A different agent
     * pointed at the same session logs a second line at that point, signed with
     * its own name - which is the whole of #441: a Slack thread's session is
     * answered by whichever agent node a run points at it, and one account at
     * the top of the log was wrong about every turn but the first.
     */
    @Test
    fun `a node with a session logs the agent's setup where it changes`() {
        // One stub behind both agents: the fixture stops one server after the test.
        val modelId = model(serveAnswer())
        val reviewer = agent("Reviewer", modelId, prompt = "You summarise incidents.")
        // A skill catalog, so the setup has a tool that comes with a grant
        // rather than by name - the kind the block used to leave out. #446.
        graphQlTester.document(
            """mutation { createSkillCatalog(workspaceId: $workspaceId, name: "Reviews") { id } }""",
        ).execute()
        /*
         * And a skill in it, so the agent's grants have a sentence of their own.
         * Issue #454: the record kept `agent.systemPrompt` alone, so everything
         * the briefing appends after the agent's own prose - the skills it can
         * load and the commands people reach it by - was missing from an account
         * that read as the whole prompt.
         */
        val skill = "---\nname: Reviewing\ndescription: How this workspace reviews.\n---\n\nRead it twice.\n"
        graphQlTester.document(
            """mutation(${'$'}content: String) { createSkill(input: {
                 workspaceId: $workspaceId, name: "Reviewing", key: "review", content: ${'$'}content
               }) { id } }""",
        ).variable("content", skill).execute().path("createSkill.id").entity(Long::class.java).get()
        // The model and prompt sent again: the form sends every field on a
        // save, so the mutation reads a missing model as "none chosen".
        graphQlTester.document(
            """mutation { updateAgent(id: $reviewer, input: {
                 name: "Reviewer", modelId: $modelId, systemPrompt: "You summarise incidents.", skillCatalogs: ["Reviews"]
               }) { id } }""",
        ).execute()
        withSession(reviewer)

        start()

        val session = sessions.findAll().single()
        val logged = {
            sessionEvents.findAll()
                .filter { it.sessionId == session.id && it.kind == io.mszymanski.orknux.server.llm.LlmSessionEventKind.AGENT_DETAILS }
                .sortedBy { it.id }
        }
        val first = logged().single()
        // The line is signed by the agent and carries its name, model and
        // system prompt; the session keeps the same text as the latest.
        assertThat(first.actor).isEqualTo("Reviewer")
        assertThat(first.content).contains("Reviewer").contains("You summarise incidents.")
        assertThat(session.agentDetails).isEqualTo(first.content)
        /*
         * And the prompt is the text the model was sent rather than the agent's
         * own field. Issue #454: what was kept was `agent.systemPrompt`, so a
         * node that replaced the prompt, the grants briefing appended after it
         * and every paragraph a lent tool adds about itself were absent from a
         * record whose whole purpose is to say what the agent was working under.
         */
        val prompt = mapper.readTree(first.content).path("systemPrompt").stringValue()
        assertThat(prompt).describedAs("the agent's own prose first").startsWith("You summarise incidents.")
        assertThat(prompt).describedAs("then the grants briefing, which is what #454 found missing")
            // Counted rather than named since #521: an Offered skill is reachable
            // through skill_list, and only Always ones cost a line here.
            .containsPattern("""You have \d+ skills?""").contains("call skill_list only when a request needs one")
        // And where to ask for that list again, hours into the conversation,
        // which is what an agent asked for its commands was guessing at. #471.
        assertThat(prompt).describedAs("the briefing names the tool that lists the skills")
            .contains("When somebody asks what commands you take, call skill_list")
        assertThat(prompt).describedAs("and what the turn lent it says about itself (#445)")
            .contains("You have scratchpads")
        // And which agent that was, so the log can lead to its page. #454.
        assertThat(mapper.readTree(first.content).path("agentId").asLong()).isEqualTo(reviewer)
        /*
         * And every tool the model was handed, not the grant list alone. Issue
         * #446: the block printed `agent.tools`, so `finish_answer` - lent by
         * this runner - and `skill_load` - which comes with the catalog - were
         * absent from the account of an agent that could call both.
         */
        val held = mapper.readTree(first.content).path("tools").mapNotNull { it.stringValue() }
        assertThat(held).contains("finish_answer", "skill_load", "skill_list", "note_to_self", "current_time")
        assertThat(held).describedAs("sorted, so the same setup is the same text").isEqualTo(held.sorted())
        // No ceiling on this agent, so nothing is found rather than carried.
        assertThat(mapper.readTree(first.content).path("findable")).isEmpty()

        // The same agent, the same setup, another turn: nothing more is logged.
        // A wake is one such turn, and so is a second run reaching the session.
        val step = steps.findAll().single { it.nodeKey == "think" }
        step.agentSleeps = 1
        steps.save(step)
        runner.run(step, step.input, null)
        start()
        assertThat(logged()).describedAs("the same setup again adds no line").hasSize(1)

        // Another agent takes the thread: a second line, at that point, in its name.
        val triager = agent("Triager", modelId, prompt = "You decide what is urgent.")
        withSession(triager)
        start()

        val lines = logged()
        assertThat(lines).describedAs("one line per change of setup").hasSize(2)
        assertThat(lines.map { it.actor }).containsExactly("Reviewer", "Triager")
        assertThat(lines[1].content).contains("You decide what is urgent.")
        assertThat(sessions.findAll().single().agentDetails).describedAs("the latest is the second").isEqualTo(lines[1].content)
        // Still the one session: the second agent joined the conversation
        // rather than opening its own.
        assertThat(sessions.findAll()).hasSize(1)
    }

    /** The same one agent node, with a session node wired into it. */
    /**
     * A woken node does not write the question into the session again. Issue #396.
     *
     * A step that waits re-runs run() from the top when it wakes, and the user
     * message was recorded on every one of those passes - so a Slack thread
     * where the agent thought for a while, waited, and came back read as the
     * person having said the same thing two or three times. The question is
     * written on the first pass only; agentSleeps is the signal that a pass is
     * a resume.
     */
    @Test
    fun `a woken step does not record the question a second time`() {
        val agentId = agent("Reviewer", model(serveAnswer()), prompt = "You summarise incidents.")
        withSession(agentId)

        start()

        val step = steps.findAll().single { it.nodeKey == "think" }
        val session = sessions.findAll().single()
        val userLines = { sessionEvents.findAll().count { it.sessionId == session.id && it.kind == io.mszymanski.orknux.server.llm.LlmSessionEventKind.USER } }
        assertThat(userLines()).describedAs("the first pass records the question once").isEqualTo(1)

        // The step wakes and re-runs: agentSleeps is what a resume carries.
        step.agentSleeps = 1
        steps.save(step)
        runner.run(step, step.input, null)

        assertThat(userLines()).describedAs("the wake does not record it again").isEqualTo(1)
    }

    /**
     * A node with a session writes that session's id onto its step, so the
     * run's page can link the step to the conversation it produced. Issue #387.
     */
    @Test
    fun `a node with a session records it on the step`() {
        val agentId = agent("Reviewer", model(serveAnswer()), prompt = "You summarise incidents.")
        withSession(agentId)

        start()

        val step = steps.findAll().single { it.nodeKey == "think" }
        val session = sessions.findAll().single()
        assertThat(step.sessionId).isEqualTo(session.id)
    }

    /** A node with no session wired to it keeps none, and its step says so. */
    @Test
    fun `a node with no session leaves the step's session empty`() {
        val agentId = agent("Reviewer", model(serveAnswer()), prompt = "You summarise incidents.")
        graph(agentId)

        start()

        val step = steps.findAll().single { it.agentId == agentId }
        assertThat(step.sessionId).isNull()
    }

    private fun withSession(agentId: Long) {
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
                  { key: "think", kind: AGENT, name: "Reviewer", agentId: $agentId, x: 200, y: 0 }
                ],
                edges: [{ source: "talk", target: "think" }]
              }) { nodes { key } problems { message } }
            }
            """,
        ).execute()
    }

    /**
     * A provider that streams, because a node with a session now asks it to.
     *
     * Server-sent events in the OpenAI shape, with the reasoning on
     * `reasoning_content` - the field a provider that knows what it is holding
     * uses, and the one [io.mszymanski.orknux.connector.model.ModelChatClient]
     * reads. Two frames of it rather than one, so a line that recorded only
     * what the first frame carried would be a line short of half its sentence.
     */
    private fun serveThinking(thought: String = "Checking what failed. It was the database."): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            received += exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val halves = if (thought.isEmpty()) emptyList() else thought.chunked((thought.length + 1) / 2)
            val frames = buildList {
                halves.forEach { add("""{"choices":[{"delta":{"reasoning_content":"$it"}}]}""") }
                add("""{"choices":[{"delta":{"content":"The database was the cause."}}]}""")
                add("""{"choices":[{"delta":{},"finish_reason":"stop"}],""" +
                    """"usage":{"prompt_tokens":11,"completion_tokens":6}}""")
            }
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

    private fun serveAfter(
        refusals: Int,
        status: Int,
        saying: String = "The database was the cause.",
    ): String {
        val calls = AtomicInteger()
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            received += exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val refusing = calls.incrementAndGet() <= refusals
            val body = if (refusing) {
                """{"error":{"message":"the provider would not take it"}}"""
            } else {
                val content = saying.replace("\\", "\\\\").replace("\"", "\\\"")
                """
                {"choices":[{"message":{"role":"assistant","content":"$content"}}],
                 "usage":{"prompt_tokens":11,"completion_tokens":6}}
                """.trimIndent()
            }.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(if (refusing) status else 200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}"
    }

    /**
     * One agent node, which is the whole workflow.
     *
     * No wait between attempts: what these are about is how many calls are
     * made, not the clock, and the inline engine spends a backoff on the thread
     * running the test.
     */
    private fun graph(agentId: Long?, attempts: Int? = null) {
        val names = if (agentId == null) "" else ", agentId: $agentId"
        val retries = if (attempts == null) "" else ", retryAttempts: $attempts, retryBackoffSeconds: 0"
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [{ key: "think", kind: AGENT, name: "Reviewer"$names$retries, x: 0, y: 0 }],
                edges: []
              }) { nodes { key agentId } }
            }
            """,
        ).execute()
    }

    /** The same node, told to handle its own failure, with somewhere to go. */
    private fun withFallback(agentId: Long) {
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "think", kind: AGENT, name: "Reviewer", agentId: $agentId,
                    fallbackEnabled: true, x: 0, y: 0 },
                  { key: "rescue", kind: OBJECT, name: "Say so", outputName: "note", x: 200, y: 200,
                    mappings: [{ name: "why", expression: "the agent could not answer", mode: VALUE }] }
                ],
                edges: [{ source: "think", target: "rescue", branch: FAILURE }]
              }) { nodes { key } }
            }
            """,
        ).execute()
    }

    /**
     * What a Slack trigger hands on when somebody uploads a picture.
     *
     * `files` is the *text* of a JSON array rather than an array, which is how
     * the trigger's payload carries it: a payload is a flat map of strings, and
     * SlackListener writes the description into one of them.
     */
    private val SLACK_WITH_A_PICTURE = """
        {"action":"MENTION","text":"what is wrong with this","channel":"C42","connection":"1",
         "files":"[{\"id\":\"F1\",\"name\":\"shot.png\",\"mimetype\":\"image/png\",\"size\":120,\"url\":\"https://files.slack.com/shot.png\"}]"}
    """.trimIndent()

    private val SLACK_WITH_A_DOCUMENT = """
        {"action":"MENTION","text":"read this please","channel":"C42","connection":"1",
         "files":"[{\"id\":\"F2\",\"name\":\"report.pdf\",\"mimetype\":\"application/pdf\",\"size\":900,\"url\":\"https://files.slack.com/report.pdf\"}]"}
    """.trimIndent()

    private fun start(
        expectFailure: Boolean = false,
        input: String = """{"summary":"the database fell over"}""",
    ): Long {
        val id = graphQlTester.document(
            """
            mutation(${'$'}input: String) {
              startExecution(workspaceId: $workspaceId, workflowId: $workflowId, input: ${'$'}input) { id status }
            }
            """,
        ).variable("input", input)
            .execute().path("startExecution.id").entity(Long::class.java).get()

        val run = executions.findAll().single { it.id == id }
        if (!expectFailure) assertThat(run.status).isIn(ExecutionStatus.COMPLETED, ExecutionStatus.RUNNING)
        return id
    }

    /**
     * A picture somebody put in Slack reaches the model as a picture.
     *
     * The half that had no test at all. A file arrives on the trigger's payload
     * as a description - id, name, mimetype, url - and what a model can read is
     * bytes in a content part, so between the two there is a fetch, a data URL
     * and a turn of its own. Each of those was written and none of it was
     * pinned; what follows is the request that actually left for the provider.
     */
    @Test
    fun `a picture on a slack message reaches the model`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        org.mockito.Mockito.`when`(
            slackFiles.read(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
            ),
        ).thenReturn(SlackFile.Fetched(png, "image/png", "shot.png"))

        graph(agent("Support responder", model(serveAnswer())))

        start(input = SLACK_WITH_A_PICTURE)

        val asked = received.single()
        assertThat(asked).contains("image_url")
        assertThat(asked).contains("data:image/png;base64,")
        // The words as well as the picture: a message with a file on it still
        // said something, and an agent shown only the picture is being asked a
        // question nobody typed.
        assertThat(asked).contains("what is wrong with this")
    }

    /**
     * And a file that is not a picture is left where it is.
     *
     * A model's request takes pictures; a PDF in one is a request the provider
     * refuses, which is the whole turn lost for a file nobody could have shown
     * it anyway. The description still travels in the payload, so an agent with
     * the pdf tool can go and read it.
     */
    @Test
    fun `a document on a slack message is not sent as a picture`() {
        graph(agent("Support responder", model(serveAnswer())))

        start(input = SLACK_WITH_A_DOCUMENT)

        assertThat(received.single()).doesNotContain("image_url")
        org.mockito.Mockito.verify(slackFiles, org.mockito.Mockito.never())
            .read(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
            )
    }

    private fun agent(name: String, modelId: Long?, prompt: String? = null): Long {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "$name", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()

        val settings = buildString {
            if (modelId != null) append(", modelId: $modelId")
            if (prompt != null) append(""", systemPrompt: "$prompt"""")
        }
        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "$name"$settings }) { id } }""",
        ).execute()
        return id
    }

    /**
     * The agent says the work is done, and the round stops there.
     *
     * A round ends when the model writes prose instead of asking for another
     * tool, which assumes the answer is the prose. An agent that posted its own
     * reply - a Slack message, an uploaded file - has nothing left to write,
     * and being asked for an answer anyway made it either repeat the message or
     * answer with nothing, which reads as a failure and is retried, which posts
     * the whole thing twice. This is that ending said deliberately.
     */
    @Test
    fun `an agent that has already delivered its work can finish the turn itself`() {
        val agentId = agent("Responder", model(serveFinishing()))
        graph(agentId)

        start()

        val step = steps.findAll().single { it.agentId == agentId }
        assertThat(step.status).isEqualTo(StepStatus.COMPLETED)
        assertThat(step.output)
            .describedAs("nothing was passed, so the next node is handed nothing rather than invented prose")
            .isEmpty()
        assertThat(executions.findAll().single().status).isEqualTo(ExecutionStatus.COMPLETED)

        // One round. The model was not asked again for an answer it had just
        // said it did not have, which is the whole point.
        assertThat(received).hasSize(1)
        assertThat(received.single()).contains("finish_answer")
    }

    /** And what it passes, where it passes something, is what the step answers. */
    @Test
    fun `finishing with an answer hands that answer to the next node`() {
        val agentId = agent("Responder", model(serveFinishing(answer = "posted to the incidents channel")))
        graph(agentId)

        start()

        val step = steps.findAll().single { it.agentId == agentId }
        assertThat(step.status).isEqualTo(StepStatus.COMPLETED)
        assertThat(step.output).isEqualTo("posted to the incidents channel")
    }

    /**
     * Unticked, it is not there at all.
     *
     * A workflow whose next node needs an answer to work with is the case the
     * switch exists for, and an agent that cannot finish early answers the way
     * it always did.
     */
    @Test
    fun `an agent with finishing turned off is not offered the tool`() {
        val modelId = model(serveAnswer())
        val agentId = agent("Responder", modelId)
        // The model goes back in with it: an update reads a field nobody sent
        // as null, and null is what clears the model.
        graphQlTester.document(
            """mutation { updateAgent(id: $agentId, input: {
                 name: "Responder", modelId: $modelId, finishAccess: false
               }) { id } }""",
        ).execute()
        graph(agentId)

        start()

        assertThat(steps.findAll().single { it.agentId == agentId }.status).isEqualTo(StepStatus.COMPLETED)
        assertThat(received.single()).doesNotContain("finish_answer")
    }

    /**
     * A model that asks for `finish_answer` once, and answers prose if it is
     * ever asked again.
     *
     * The second answer is a tripwire: reaching it means the round did not end
     * where the tool said it did, and the test that counts the requests is what
     * says so.
     */
    private fun serveFinishing(answer: String? = null): String {
        val calls = AtomicInteger()
        val arguments = if (answer == null) "{}" else """{"answer":"$answer"}"""
        val escaped = arguments.replace("\"", "\\\"")
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            received += exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val body = if (calls.incrementAndGet() == 1) {
                """
                {"choices":[{"message":{"role":"assistant","content":null,
                  "tool_calls":[{"id":"call_1","type":"function",
                    "function":{"name":"finish_answer","arguments":"$escaped"}}]}}],
                 "usage":{"prompt_tokens":9,"completion_tokens":4}}
                """.trimIndent()
            } else {
                """{"choices":[{"message":{"role":"assistant","content":"asked again"}}]}"""
            }.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
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
}
