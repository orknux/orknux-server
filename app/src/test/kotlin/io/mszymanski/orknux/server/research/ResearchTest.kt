package io.mszymanski.orknux.server.research

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.server.action.WorkflowActionRepository
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.action.ActionSubtype
import io.mszymanski.orknux.server.action.ActionType
import io.mszymanski.orknux.server.action.FunctionScope
import io.mszymanski.orknux.server.action.WorkflowAction
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.workflow.EdgeBranch
import io.mszymanski.orknux.server.workflow.GraphValidator
import io.mszymanski.orknux.server.workflow.MappingMode
import io.mszymanski.orknux.server.workflow.NodeMapping
import io.mszymanski.orknux.server.workflow.WorkflowEdgeRepository
import io.mszymanski.orknux.server.workflow.WorkflowNode
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.server.workflow.NodeKind
import io.mszymanski.orknux.server.workflow.SavedArtifactRepository
import io.mszymanski.orknux.server.workflow.SavedArtifacts
import io.mszymanski.orknux.server.workflow.WorkflowNodeRepository
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.server.workspace.WorkspaceAPI
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.context.event.EventListener
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.workflow.execution.StepRetrying
import io.mszymanski.orknux.workflow.execution.StepStatus
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Analysis > Research. Issue #659.
 *
 * A workspace arrives with three research modes, each a hidden workflow of
 * five steps - Plan research, Gather sources, Deepen findings, Write report,
 * Save report; Plan, Gather, Follow up, Write and Save here - whose nodes hold the
 * knobs that make the mode. Plan and Write are agent nodes, the workspace's
 * Research planner and Research writer, each naming the model the research
 * was started with; the other three are actions. One still drawn as the single
 * `research_run` node, or as the five actions, it once was is redrawn, and one
 * somebody edited is left alone. A research
 * plans sub-questions, searches each through the workspace's search with the
 * websites to keep to and avoid applied, reads what it found and notes it,
 * goes deeper with follow-up questions to the mode's depth, and writes a report
 * citing numbered sources - kept as a document or a PDF, with its progress and
 * its sources on the research.
 *
 * The model is a stub that answers each of the four things it is asked by the
 * line the question opens with, and the web is a stub that records what was
 * searched and read, because what is measured is the pipeline: what reached
 * the model and the search, and what came back out.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ResearchTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val researches: ResearchRepository,
    @Autowired val modes: ResearchModeRepository,
    @Autowired val actions: WorkflowActionRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val saved: SavedArtifactRepository,
    @Autowired val savedArtifacts: SavedArtifacts,
    @Autowired val store: AttachmentStore,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val workspaceApi: WorkspaceAPI,
    @Autowired val web: StubWeb,
    @Autowired val uploads: ResearchUploadAPI,
    @Autowired val orknux: io.mszymanski.orknux.server.mcp.OrknuxTools,
    @Autowired val mapper: tools.jackson.databind.ObjectMapper,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val validator: GraphValidator,
    @Autowired val researchModes: ResearchModes,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val runSteps: ExecutionStepRepository,
    @Autowired val agents: io.mszymanski.orknux.server.agent.AgentRepository,
    @Autowired val runLogs: io.mszymanski.orknux.workflow.execution.ExecutionLogRepository,
    @Autowired val tunables: io.mszymanski.orknux.server.tuning.Tunables,
    @Autowired val retries: RetryWatch,
    @Autowired val realWeb: WorkspaceResearchWeb,
    @Autowired val llmEvents: io.mszymanski.orknux.server.llm.LlmSessionEventRepository,
    @Autowired val searchSettings: io.mszymanski.orknux.server.embedded.SearchSettings,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer
    private val received = CopyOnWriteArrayList<String>()
    private val stamp = System.nanoTime().toString().takeLast(9)

    /** While set, the stub answers the writer with nothing, until the run has parked Write for another attempt. */
    @Volatile private var writerSilent = false

    @BeforeEach
    fun workspace() {
        received.clear()
        web.searched.clear()
        web.read.clear()
        web.refusing = null
        web.finding = true
        web.ready = true
        web.searchedImages.clear()
        web.fetchedImages.clear()
        retries.seen.clear()
        writerSilent = false
        // What createWorkspace does after its access check, so a test signed in as somebody else has one too.
        workspaceId = requireNotNull(workspaceApi.create("Research $stamp", null).id)
    }

    @AfterEach
    fun stop() {
        if (::server.isInitialized) server.stop(0)
    }

    /* ------------------------------------------------------------- seeded --- */

    @Test
    fun `a new workspace has the three modes, each a hidden workflow of five steps whose nodes hold the mode's knobs`() {
        val seeded = modesOf()
        assertThat(seeded.map { it["kind"] }).containsExactly("SHALLOW", "MEDIUM", "DEEP")
        assertThat(seeded.map { it["workflowName"] }).containsExactly("Research: Shallow", "Research: Medium", "Research: Deep")

        val ids = seeded.map { (it["workflowId"] as String).toLong() }
        assertThat(workflows.findAllById(ids)).allMatch { it.hidden }

        val knobs = ids.map { id ->
            val drawn = nodes.findByWorkflowId(id).sortedBy { it.positionX }
            assertThat(drawn.map { it.name }).containsExactly("Plan research", "Gather sources", "Deepen findings", "Write report", "Review asked for?", "Review report", "Save report")
            assertCurrentShape(drawn)
            // A chain, left to right, with the question whether to review between Write and Save.
            assertThat(edges.findByWorkflowId(id).map { it.sourceKey to it.targetKey }).containsExactlyInAnyOrder(
                "plan" to "gather", "gather" to "followUp", "followUp" to "write", "write" to "reviewing",
                "reviewing" to "review", "reviewing" to "save", "review" to "save",
            )
            assertThat(edges.findByWorkflowId(id).filter { it.sourceKey == "reviewing" }.associate { it.targetKey to it.branch })
                .isEqualTo(mapOf("review" to EdgeBranch.YES, "save" to EdgeBranch.NO))
            // And it is a graph that holds together, with nothing to warn about.
            assertThat(validator.problems(drawn, edges.findByWorkflowId(id), workspaceId = workspaceId)).isEmpty()
            knobsOf(drawn)
        }
        assertThat(knobs.map { it.mapValues { (_, values) -> values.keys } }).allMatch {
            it == mapOf(
                "Plan research" to setOf(ResearchCapability.SUB_QUESTIONS),
                "Gather sources" to setOf(ResearchCapability.RESULTS_PER_QUERY, ResearchCapability.MAX_SOURCES, ResearchCapability.MAX_PAGES_PER_SOURCE),
                "Deepen findings" to setOf(ResearchCapability.BREADTH, ResearchCapability.DEPTH),
                "Write report" to emptySet(),
                "Review asked for?" to emptySet(),
                "Review report" to emptySet(),
                "Save report" to emptySet(),
            )
        }
        // The modes' numbers: sub-questions, breadth, depth, results per query, sources, pages per source.
        val numbers = knobs.map { steps ->
            val all = steps.values.fold(emptyMap<String, Int>()) { one, other -> one + other }
            listOf(
                ResearchCapability.SUB_QUESTIONS, ResearchCapability.BREADTH, ResearchCapability.DEPTH,
                ResearchCapability.RESULTS_PER_QUERY, ResearchCapability.MAX_SOURCES, ResearchCapability.MAX_PAGES_PER_SOURCE,
            ).map { all.getValue(it) }
        }
        assertThat(numbers).containsExactly(listOf(3, 0, 1, 3, 8, 1), listOf(5, 2, 2, 4, 20, 2), listOf(6, 4, 3, 5, 50, 3))

        // And no agent: a workspace has none of its own for research, so nothing is chosen for anybody.
        assertThat(agents.findAll().filter { it.workspaceId == workspaceId }.map { it.name })
            .doesNotContain(ResearchModes.PLANNER.name, ResearchModes.WRITER.name, ResearchModes.REVIEWER.name)
    }

    /* ------------------------------------------------------------ upgrade --- */

    @Test
    fun `a mode workflow still as it was first seeded, one node, is redrawn as the five steps with the same numbers`() {
        val medium = modeWorkflow("MEDIUM")
        legacy(medium, mapOf(SUB to 5, BREADTH to 2, DEPTH to 2, RESULTS to 4, SOURCES to 20, PAGES to 2))

        researchModes.ensure(workspaceId)

        val drawn = nodes.findByWorkflowId(medium).sortedBy { it.positionX }
        assertThat(drawn.map { it.name }).containsExactly("Plan research", "Gather sources", "Deepen findings", "Write report", "Review asked for?", "Review report", "Save report")
        assertCurrentShape(drawn)
        assertThat(knobsOf(drawn).values.fold(emptyMap<String, Int>()) { one, other -> one + other })
            .containsExactlyInAnyOrderEntriesOf(mapOf(SUB to 5, BREADTH to 2, DEPTH to 2, RESULTS to 4, SOURCES to 20, PAGES to 2))
        assertThat(edges.findByWorkflowId(medium)).hasSize(7)

        // And it runs as the steps.
        val modelId = model(serve())
        val done = settled(
            start("""{ workspaceId: $workspaceId, question: "Why are there tides?", modeId: ${modeId("MEDIUM")}, language: FORMAL, outputFormat: DOCUMENT }"""),
        )
        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)
        assertThat(stepsOf(done)).containsExactly("plan", "gather", "followUp", "write", "reviewing", "review", "save")
    }

    @Test
    fun `a mode workflow somebody edited is left as it is, and still runs in one step`() {
        val shallow = modeWorkflow("SHALLOW")
        // Shallow's numbers, but one source rather than eight: somebody's own.
        legacy(shallow, mapOf(SUB to 3, BREADTH to 0, DEPTH to 1, RESULTS to 3, SOURCES to 1, PAGES to 1))

        researchModes.ensure(workspaceId)

        val kept = nodes.findByWorkflowId(shallow).single()
        assertThat(kept.nodeKey).isEqualTo("research")
        assertThat(kept.mappings.single { it.name == SOURCES }.expression).isEqualTo("1")

        val modelId = model(serve())
        val done = settled(
            start("""{ workspaceId: $workspaceId, question: "Why are there tides?", modeId: ${modeId("SHALLOW")}, language: FORMAL, outputFormat: DOCUMENT }"""),
        )
        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)
        assertThat(web.read).hasSize(1)
        assertThat(stepsOf(done)).containsExactly("research")
        // Saved when the run ended, as it always was.
        assertThat(saved.findByIdOrNull(requireNotNull(done.artifactId))!!.name).isEqualTo("tides.md")
        assertThat(researches.findByIdOrNull(requireNotNull(done.id))!!.progress).contains("\"step\":\"Finished\"")
    }

    @Test
    fun `a mode workflow whose node was renamed is not redrawn either`() {
        val deep = modeWorkflow("DEEP")
        legacy(deep, mapOf(SUB to 6, BREADTH to 4, DEPTH to 3, RESULTS to 5, SOURCES to 50, PAGES to 3))
        val node = nodes.findByWorkflowId(deep).single()
        node.name = "Dig"
        nodes.save(node)

        researchModes.ensure(workspaceId)
        assertThat(nodes.findByWorkflowId(deep).map { it.name }).containsExactly("Dig")
    }

    @Test
    fun `a mode workflow still the five actions it was seeded as is redrawn with agent nodes, and runs through them`() {
        val deep = modeWorkflow("DEEP")
        actionShape(deep, mapOf(SUB to 6, BREADTH to 4, DEPTH to 3, RESULTS to 5, SOURCES to 50, PAGES to 3))
        // Moved on the canvas, which changes nothing it does.
        nodes.findByWorkflowId(deep).forEach { it.positionY = 40.0; nodes.save(it) }

        researchModes.ensure(workspaceId)

        val drawn = nodes.findByWorkflowId(deep).sortedBy { it.positionX }
        assertCurrentShape(drawn)
        assertThat(knobsOf(drawn).values.fold(emptyMap<String, Int>()) { one, other -> one + other })
            .containsExactlyInAnyOrderEntriesOf(mapOf(SUB to 6, BREADTH to 4, DEPTH to 3, RESULTS to 5, SOURCES to 50, PAGES to 3))
        assertThat(edges.findByWorkflowId(deep).map { it.sourceKey to it.targetKey }).containsExactlyInAnyOrder(
            "plan" to "gather", "gather" to "followUp", "followUp" to "write", "write" to "reviewing",
                "reviewing" to "review", "reviewing" to "save", "review" to "save",
        )
        assertThat(validator.problems(drawn, edges.findByWorkflowId(deep), workspaceId = workspaceId)).isEmpty()

        val modelId = model(serve())
        val done = settled(
            start("""{ workspaceId: $workspaceId, question: "Why are there tides?", modeId: ${modeId("DEEP")}, language: FORMAL, outputFormat: DOCUMENT }"""),
        )
        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)
        assertThat(stepsOf(done)).containsExactly("plan", "gather", "followUp", "write", "reviewing", "review", "save")
        assertThat(asked(PLAN).single()).contains("exactly 6 search queries")
    }

    @Test
    fun `a mode drawn in today's shape is laid out again, Save a row lower, and an edited one keeps where its cards are`() {
        val shallow = modeWorkflow("SHALLOW")
        val medium = modeWorkflow("MEDIUM")
        fun at(workflowId: Long) = nodes.findByWorkflowId(workflowId).associate { it.nodeKey to (it.positionX to it.positionY) }
        // Save drawn under the row, so Follow up's line to it passes under Write; the others in one row, left to right.
        val laid = at(shallow)
        assertThat(laid.getValue("save").second).isGreaterThan(laid.getValue("write").second + 200)
        assertThat(listOf("plan", "gather", "followUp", "write", "save").map { laid.getValue(it).first }).isSorted()
        assertThat(listOf("plan", "gather", "followUp", "write").map { laid.getValue(it).second }.toSet()).hasSize(1)

        // Cards dragged into a heap: put back.
        nodes.saveAll(nodes.findByWorkflowId(shallow).onEach { it.positionX = 5.0; it.positionY = 5.0 })
        // Somebody's own prompt on Medium's planner, and its cards moved: left exactly as it is.
        nodes.saveAll(nodes.findByWorkflowId(medium).onEach { node ->
            node.positionX = 7.0
            node.positionY = 7.0
            if (node.nodeKey == "plan") node.mappings.first { it.name == "prompt" }.expression = "My own planning prompt"
        })

        researchModes.ensure(workspaceId)

        assertThat(at(shallow)).isEqualTo(laid)
        assertThat(at(medium).values).allMatch { it == 7.0 to 7.0 }
    }

    @Test
    fun `a five-action mode workflow with numbers of its own is drawn again with them, and runs through the steps`() {
        val shallow = modeWorkflow("SHALLOW")
        // Shallow's numbers, but one source rather than eight and four queries: somebody's own, and the mode's.
        actionShape(shallow, mapOf(SUB to 4, BREADTH to 0, DEPTH to 1, RESULTS to 3, SOURCES to 1, PAGES to 1))

        researchModes.ensure(workspaceId)

        val drawn = nodes.findByWorkflowId(shallow).sortedBy { it.positionX }
        assertCurrentShape(drawn)
        assertThat(knobsOf(drawn).values.fold(emptyMap<String, Int>()) { one, other -> one + other })
            .containsExactlyInAnyOrderEntriesOf(mapOf(SUB to 4, BREADTH to 0, DEPTH to 1, RESULTS to 3, SOURCES to 1, PAGES to 1))

        val modelId = model(serve())
        val done = settled(
            start("""{ workspaceId: $workspaceId, question: "Why are there tides?", modeId: ${modeId("SHALLOW")}, language: COLLOQUIAL, outputFormat: DOCUMENT }"""),
        )
        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)
        assertThat(web.read).hasSize(1)
        assertThat(stepsOf(done)).containsExactly("plan", "gather", "followUp", "write", "reviewing", "review", "save")
        assertThat(asked(PLAN).single()).contains("exactly 4 search queries")
        assertThat(done.result).contains("## Sources")
        assertThat(saved.findByIdOrNull(requireNotNull(done.artifactId))!!.name).isEqualTo("tides.md")
    }

    /**
     * The steps were Plan, Gather, Follow up, Write and Save, and their
     * actions Research: Gather, Research: Follow up and Research: Save. A mode
     * still exactly as it was seeded under those names takes the names the
     * steps have now, its actions too - the one made as "(Analysis)" because
     * somebody's own action had the plain name included - and goes on running.
     */
    @Test
    fun `a mode still drawn under the steps' former names takes the current ones, and so do its actions`() {
        val medium = modeWorkflow("MEDIUM")
        formerNames(medium)
        // Somebody's own action took the plain name once, so Save's was made with the fallback after it.
        val save = actions.findByIdOrNull(requireNotNull(nodes.findByWorkflowId(medium).single { it.nodeKey == "save" }.actionId))!!
        save.name = "Research: Save (Analysis)"
        actions.save(save)
        val someoneElses = functions.findByScopeAndName(FunctionScope.EMBEDDED, ResearchCapability.functionName(ResearchCapability.PLAN))?.id
        actions.save(
            WorkflowAction(
                workspaceId = workspaceId, name = "Research: Save", type = ActionType.EXECUTE,
                subtype = ActionSubtype.FUNCTION, functionId = someoneElses,
            ),
        )

        researchModes.ensure(workspaceId)

        val drawn = nodes.findByWorkflowId(medium).sortedBy { it.positionX }
        // Renamed, and then given its review, as the five steps it was become the seven.
        assertThat(drawn.map { it.name })
            .containsExactly("Plan research", "Gather sources", "Deepen findings", "Write report", "Review asked for?", "Review report", "Save report")
        assertCurrentShape(drawn)
        assertThat(actions.findByWorkspaceId(workspaceId).map { it.name })
            .contains("Research: Gather sources", "Research: Deepen findings", "Research: Save report", "Research: Save")
            .doesNotContain("Research: Gather", "Research: Follow up", "Research: Save (Analysis)")
        // Shallow points at the same actions, so it calls them by the current names too.
        val shallowGather = nodes.findByWorkflowId(modeWorkflow("SHALLOW")).single { it.nodeKey == "gather" }
        assertThat(actions.findByIdOrNull(requireNotNull(shallowGather.actionId))!!.name).isEqualTo("Research: Gather sources")

        val modelId = model(serve())
        val done = settled(
            start("""{ workspaceId: $workspaceId, question: "Why are there tides?", modeId: ${modeId("MEDIUM")}, language: FORMAL, outputFormat: DOCUMENT }"""),
        )
        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)
    }

    @Test
    fun `a mode under the former names with a step somebody renamed is drawn again, and keeps the name they gave it`() {
        val deep = modeWorkflow("DEEP")
        formerNames(deep)
        nodes.saveAll(nodes.findByWorkflowId(deep).onEach { if (it.nodeKey == "gather") { it.name = "Dig"; it.description = "Digs" } })

        researchModes.ensure(workspaceId)

        val drawn = nodes.findByWorkflowId(deep).sortedBy { it.positionX }
        assertThat(drawn.map { it.name })
            .containsExactly("Plan research", "Dig", "Deepen findings", "Write report", "Review asked for?", "Review report", "Save report")
        assertThat(drawn.single { it.nodeKey == "gather" }.description).isEqualTo("Digs")
        assertThat(drawn.single { it.nodeKey == "plan" }.description).isEqualTo("Plans the search queries")
    }

    @Test
    fun `the workflows list leaves the hidden ones out until it is asked for them`() {
        graphQlTester.document("""mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Mine" }) { id } }""")
            .execute().errors().verify()

        assertThat(listed(showHidden = false)).containsExactly("Mine")
        assertThat(listed(showHidden = true))
            .containsExactlyInAnyOrder("Mine", "Research: Shallow", "Research: Medium", "Research: Deep")
        assertThat(listed(showHidden = false, search = "Research")).isEmpty()
        assertThat(listed(showHidden = true, search = "Deep")).containsExactly("Research: Deep")
    }

    @Test
    fun `a hidden workflow can be shown, and says so in the audit`() {
        val deep = graphQlTester.document(
            """query { workspaceWorkflows(workspaceId: $workspaceId, showHidden: true, search: "Deep") { content { id hidden } } }""",
        ).execute().path("workspaceWorkflows.content[0].id").entity(String::class.java).get()

        graphQlTester.document("""mutation { setWorkflowHidden(id: $deep, hidden: false) { hidden } }""")
            .execute().path("setWorkflowHidden.hidden").entity(Boolean::class.java).isEqualTo(false)

        assertThat(listed(showHidden = false)).containsExactly("Research: Deep")
        assertThat(audit.findAll().map { it.message }).contains("Workflow Research: Deep shown")
    }

    @Test
    fun `seeding twice changes nothing, and workflows already there are adopted`() {
        val before = modes.findByWorkspaceIdOrderByPositionAscIdAsc(workspaceId).map { it.workflowId }
        modes.deleteAll(modes.findByWorkspaceIdOrderByPositionAscIdAsc(workspaceId))

        // As a copied workspace arrives: the workflows and the action there, no modes.
        assertThat(modesOf().map { it["workflowId"]?.toString()?.toLong() }).isEqualTo(before)
        assertThat(actions.findByWorkspaceId(workspaceId).filter { it.name.startsWith(ResearchModes.ACTION_NAME) }.map { it.name })
            .containsExactlyInAnyOrder("Research: Gather sources", "Research: Deepen findings", "Research: Review report", "Research: Save report")
        // And no agent made for it: a workspace has none of its own for research.
        assertThat(agents.findByWorkspaceId(workspaceId, org.springframework.data.domain.Sort.unsorted()).map { it.name }.filter { it.startsWith("Research") })
            .isEmpty()
        assertThat(modesOf()).hasSize(3)
    }

    /* ------------------------------------------------------------ retries --- */

    @Test
    fun `every step of a seeded mode is drawn with the retry policy the tunables say`() {
        // The installation's numbers, on the workspace made in setUp.
        val attempts = tunables[Tunable.RESEARCH_STEP_RETRIES] + 1
        val wait = tunables[Tunable.RESEARCH_STEP_RETRY_SECONDS]
        modesOf().forEach { mode ->
            assertThat(nodes.findByWorkflowId((mode["workflowId"] as String).toLong()))
                .hasSize(7)
                .allMatch { it.retryAttempts == attempts && it.retryBackoffSeconds == wait }
        }

        // Changed, a workspace made afterwards is drawn with the new numbers.
        try {
            tunables.set(Tunable.RESEARCH_STEP_RETRIES, 4, "alice")
            tunables.set(Tunable.RESEARCH_STEP_RETRY_SECONDS, 7, "alice")
            workspaceId = requireNotNull(workspaceApi.create("Research retried $stamp", null).id)
            modesOf().forEach { mode ->
                assertThat(nodes.findByWorkflowId((mode["workflowId"] as String).toLong()))
                    .hasSize(7)
                    .allMatch { it.retryAttempts == 5 && it.retryBackoffSeconds == 7 && it.retryMultiplier == null }
            }
        } finally {
            tunables.set(Tunable.RESEARCH_STEP_RETRIES, null, "alice")
            tunables.set(Tunable.RESEARCH_STEP_RETRY_SECONDS, null, "alice")
        }
    }

    @Test
    fun `a seeded mode made before there were retries is given them, and one somebody edited is not`() {
        val shallow = modeWorkflow("SHALLOW")
        val medium = modeWorkflow("MEDIUM")
        val deep = modeWorkflow("DEEP")
        fun noRetries(workflowId: Long) = nodes.saveAll(
            nodes.findByWorkflowId(workflowId).onEach { it.retryAttempts = null; it.retryBackoffSeconds = null },
        )
        // As every workflow was seeded until now.
        listOf(shallow, medium, deep).forEach(::noRetries)
        // Medium's planner asks something of somebody's own.
        nodes.saveAll(nodes.findByWorkflowId(medium).onEach { node ->
            if (node.nodeKey == "plan") node.mappings.first { it.name == "prompt" }.expression = "My own planning prompt"
        })
        // Deep's writer has a policy of somebody's own, and the rest none: theirs to have decided.
        nodes.saveAll(nodes.findByWorkflowId(deep).onEach { node ->
            if (node.nodeKey == "write") {
                node.retryAttempts = 5
                node.retryBackoffSeconds = 60
            }
        })

        researchModes.ensure(workspaceId)

        val attempts = tunables[Tunable.RESEARCH_STEP_RETRIES] + 1
        val wait = tunables[Tunable.RESEARCH_STEP_RETRY_SECONDS]
        assertThat(nodes.findByWorkflowId(shallow)).allMatch { it.retryAttempts == attempts && it.retryBackoffSeconds == wait }
        assertThat(nodes.findByWorkflowId(medium)).allMatch { it.retryAttempts == null && it.retryBackoffSeconds == null }
        assertThat(nodes.findByWorkflowId(deep).associate { it.nodeKey to it.retryAttempts })
            .isEqualTo(mapOf("plan" to null, "gather" to null, "followUp" to null, "write" to 5, "reviewing" to null, "review" to null, "save" to null))

        // And a retry policy is no edit: Shallow, retried now, is still laid out again when its cards are heaped.
        nodes.saveAll(nodes.findByWorkflowId(shallow).onEach { it.positionX = 5.0; it.positionY = 5.0 })
        researchModes.ensure(workspaceId)
        assertThat(nodes.findByWorkflowId(shallow).map { it.positionX }.distinct()).hasSize(7)
    }

    /**
     * Research 3632 on the development server: the writer's model, llama.cpp
     * serving Qwen, stopped after its thinking and said nothing, and the
     * research failed at Write - while the same request by hand, and the same
     * research run again straight after, wrote the report.
     */
    @Test
    fun `a writer that answers with nothing is tried again, and the research completes`() {
        val modelId = model(serve())
        writerSilent = true
        // Somebody's shorter wait, so the test does not sit out the installation's.
        nodes.saveAll(nodes.findByWorkflowId(modeWorkflow("SHALLOW")).onEach { it.retryBackoffSeconds = 1 })

        val id = start(
            """{ workspaceId: $workspaceId, question: "Why are there tides?", modeId: ${modeId("SHALLOW")}, language: FORMAL, outputFormat: DOCUMENT }""",
        )
        // What the page says while Write waits for its second go.
        var said: String? = null
        val until = System.currentTimeMillis() + 30_000
        while (said == null && System.currentTimeMillis() < until) {
            val held = requireNotNull(researches.findByIdOrNull(id))
            said = held.progress?.takeIf { it.contains("trying again") }
            if (held.status != ResearchStatus.RUNNING) break
            Thread.sleep(20)
        }
        val done = settled(id)

        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)
        assertThat(done.result).startsWith("# Tides").contains("## Sources")
        assertThat(said).contains("Write report failed on attempt 1 of 3; trying again in 1s").contains("\"stage\":\"WRITE\"")

        val steps = runSteps.findByExecutionIdOrderByOrderAsc(requireNotNull(done.executionId)).associateBy { it.nodeKey }
        assertThat(steps.getValue("write").attempts).isEqualTo(2)
        assertThat(steps.getValue("write").status).isEqualTo(StepStatus.COMPLETED)
        // Review was not asked for, so it never ran.
        assertThat(steps.filterKeys { it != "write" && it != "review" }.values).allMatch { it.attempts == 1 }
        assertThat(retries.seen.map { it.nodeKey }).containsExactly("write")
        // On the run, under the step, as any retry is.
        assertThat(runLogs.findByExecutionIdOrderBySequenceAsc(requireNotNull(done.executionId)).filter { it.nodeKey == "write" }.map { it.message })
            .anyMatch {
                it.startsWith("Write report failed on attempt 1 of 3: Research writer could not answer: The provider answered with no message") &&
                    it.contains("Trying again")
            }
    }

    /* ----------------------------------------------- plan, gather, write --- */

    @Test
    fun `a shallow research plans, searches each part, reads and notes what it found, and writes a cited report`() {
        val endpoint = serve()
        // Two chat models, and the agents think with the second: what reaches the model is the agents' own.
        model(endpoint)
        val modelId = model(endpoint, "Chosen")

        val id = start(
            """{ workspaceId: $workspaceId, question: "Why are there tides?", modeId: ${modeId("SHALLOW")}, 
                 language: FORMAL, outputFormat: DOCUMENT, websitesExclude: ["tabloid.example"] }""",
        )
        val done = settled(id)
        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)
        // Started with the seeded agents, the Writer's model recorded as the one it ran with.
        assertThat(listOf(done.plannerAgentName, done.writerAgentName)).containsExactly("Research planner", "Research writer")
        assertThat(done.modelName).isEqualTo("Chosen")

        // Plan: the planner agent asked, with its own model, for three queries - which are what was searched, and only those, at depth 1.
        assertThat(asked(PLAN)).hasSize(1)
        assertThat(asked(PLAN).single()).contains("Why are there tides?").contains("exactly 3 search queries")
        assertThat(modelOf(asked(PLAN).single())).isEqualTo("research-$stamp-Chosen")
        // Lent web search because the research searches the web (#678).
        assertThat(toolsOf(asked(PLAN).single())).contains("web_search")
        assertThat(web.searched).containsExactly("tide causes", "tide timing", "tide records")
        assertThat(asked(FOLLOW_UP)).isEmpty()

        // Gather: every page read is a page that was found, never one on a website it was told to avoid.
        assertThat(web.read).isNotEmpty().noneMatch { it.contains("tabloid.example") }
        assertThat(asked(NOTES)).hasSize(web.read.size)
        assertThat(asked(NOTES).first()).contains("Query: tide causes").contains("The moon pulls the sea at")

        // Write: the writer agent, with its own model, in the register asked for, from the numbered notes.
        val write = asked(WRITE).single()
        assertThat(write).contains("Register: FORMAL").contains("Documents not to use: none").contains("[1] ").contains("The moon's gravity raises tides.")
        assertThat(modelOf(write)).isEqualTo("research-$stamp-Chosen")
        assertThat(toolsOf(write)).contains("web_search")
        // Nor told of any: a model told of skill_list it was not given wrote the call out as the report.
        assertThat(asked(PLAN) + asked(WRITE)).allSatisfy { assertThat(it).doesNotContain("skill_list").doesNotContain("skill_load") }
        assertThat(asked(NOTES).map(::modelOf)).isNotEmpty().allMatch { it == "research-$stamp-Chosen" }

        val view = graphQlTester.document(
            """query { research(id: $id) { result progress { step subQuestions searched level sourcesRead }
                 sources { number title url kind notes } fileName artifactId } }""",
        ).execute()
        // The report's own artifact, for the page's link to it on the Artifacts page.
        view.path("research.artifactId").entity(Long::class.java).isEqualTo(requireNotNull(done.artifactId))
        view.path("research.progress.subQuestions").entityList(String::class.java).containsExactly("tide causes", "tide timing", "tide records")
        view.path("research.progress.step").entity(String::class.java).isEqualTo("Finished")
        // Five steps ran, one node each, and the page was told which one it was in.
        assertThat(stepsOf(done)).containsExactly("plan", "gather", "followUp", "write", "reviewing", "review", "save")
        graphQlTester.document("""query { research(id: $id) { progress { stage } } }""").execute()
            .path("research.progress.stage").entity(String::class.java).isEqualTo("SAVE")
        val urls = view.path("research.sources[*].url").entityList(String::class.java).get()
        assertThat(urls).isNotEmpty().allMatch { it.startsWith("https://") && !it.contains("tabloid") }
        // At most three results a query and eight sources, as Shallow's node says.
        assertThat(urls.size).isLessThanOrEqualTo(8)
        view.path("research.sources[*].number").entityList(Int::class.java).containsExactly(*(1..urls.size).toList().toTypedArray())

        val report = requireNotNull(done.result)
        assertThat(report).startsWith("# Tides").contains("[1]").contains("## Sources")
        assertThat(report.substringAfter("## Sources")).contains("1. [").contains(urls.first())
        assertThat(saved.findByIdOrNull(requireNotNull(done.artifactId))!!.name).isEqualTo("tides.md")

        // Plan and Write each talked in a session of their own, which the page links: what each was asked and answered (#668).
        val sessions = graphQlTester.document("""query { research(id: $id) { sessions { stage sessionId url } } }""").execute()
        sessions.path("research.sessions[*].stage").entityList(String::class.java).containsExactly("PLAN", "WRITE")
        val planSession = sessions.path("research.sessions[0].sessionId").entity(Long::class.java).get()
        sessions.path("research.sessions[0].url").entity(String::class.java).isEqualTo("/workspace/$workspaceId/sessions/$planSession")
        val said = llmEvents.findAll().filter { it.sessionId == planSession }
        assertThat(said.map { it.kind.name }).contains("USER", "AGENT")
        assertThat(said.first { it.kind.name == "USER" }.content).contains("Why are there tides?")
    }

    @Test
    fun `a deeper mode asks follow-up questions from what it learnt and searches those too`() {
        val modelId = model(serve())

        val done = settled(
            start("""{ workspaceId: $workspaceId, question: "Why are there tides?", modeId: ${modeId("MEDIUM")}, language: ACADEMIC, outputFormat: DOCUMENT }"""),
        )
        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)

        // Medium: five queries planned, then follow-ups one level down.
        assertThat(asked(PLAN).single()).contains("exactly 5 search queries")
        assertThat(asked(FOLLOW_UP)).isNotEmpty()
        assertThat(asked(FOLLOW_UP).first()).contains("What was learnt:").contains("exactly 2 follow-up search queries")
        assertThat(web.searched).contains("moon distance and tides")
        assertThat(researches.findByIdOrNull(requireNotNull(done.id))!!.progress).contains("\"level\":2")
        assertThat(asked(WRITE).single()).contains("Register: ACADEMIC")
    }

    @Test
    fun `websites to keep to scope the search and what is read`() {
        val modelId = model(serve())
        val done = settled(
            start(
                """{ workspaceId: $workspaceId, question: "Why are there tides?", modeId: ${modeId("SHALLOW")}, 
                     language: FORMAL, outputFormat: DOCUMENT, websitesInclude: ["https://www.one.example.org/x"] }""",
            ),
        )
        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)
        assertThat(web.searched).isNotEmpty().allMatch { it.endsWith(" site:one.example.org") }
        assertThat(web.read).isNotEmpty().allMatch { ResearchPipeline.hostOf(it)!!.endsWith("one.example.org") }
    }

    @Test
    fun `a custom mode is a copy of another with numbers of its own`() {
        val modelId = model(serve())
        val copied = graphQlTester.document(
            """mutation { createResearchMode(input: { workspaceId: $workspaceId, name: "One source", copyOfModeId: ${modeId("SHALLOW")} }) { id workflowId } }""",
        ).execute().path("createResearchMode.workflowId").entity(Long::class.java).get()
        assertThat(workflows.findByIdOrNull(copied)!!.hidden).isTrue()

        // Written on the copy's Gather node, the way somebody would in the editor.
        val node = nodes.findByWorkflowId(copied).single { it.nodeKey == ResearchModes.GATHER }
        node.mappings.single { it.name == ResearchCapability.MAX_SOURCES }.expression = "1"
        nodes.save(node)

        val custom = modesOf().single { it["name"] == "One source" }["id"]
        val done = settled(start("""{ workspaceId: $workspaceId, question: "Tides", modeId: $custom, language: FORMAL, outputFormat: DOCUMENT }"""))
        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)
        assertThat(web.read).hasSize(1)
        assertThat(done.modeName).isEqualTo("One source")
    }

    @Test
    fun `documents included are read as sources, and documents excluded are named as off limits`() {
        val notes = document("notes.md", "The harbour master logs a spring tide every fortnight.")
        val rumours = document("rumours.txt", "Tides are caused by whales.")
        val modelId = model(serve())

        val done = settled(
            start(
                """{ workspaceId: $workspaceId, question: "How often are spring tides?", modeId: ${modeId("SHALLOW")},
                     language: COLLOQUIAL, outputFormat: DOCUMENT,
                     documentsInclude: [$notes], documentsExclude: [$rumours] }""",
            ),
        )
        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)
        assertThat(asked(NOTES).first()).contains("Source: notes.md").contains("every fortnight")
        assertThat(asked(WRITE).single()).contains("Documents not to use: rumours.txt").contains("Register: COLLOQUIAL").contains("/api/artifacts/$notes")
        assertThat(asked(NOTES) + asked(WRITE)).noneMatch { it.contains("caused by whales") }
        assertThat(researches.findByIdOrNull(requireNotNull(done.id))!!.foundSources).contains("\"kind\":\"DOCUMENT\"")
    }

    /** A picker asks for a few, by what is typed; a workspace may hold thousands. */
    @Test
    fun `documents are found by a word in the name or the description, newest first and no more than asked for`() {
        document("tides-2024.md", "Spring tides.")
        document("budget.txt", "Numbers.")
        document("tides-2025.md", "Neap tides.")

        graphQlTester.document("""query { researchDocuments(workspaceId: $workspaceId, search: "TIDES") { name } }""").execute()
            .path("researchDocuments[*].name").entityList(String::class.java).containsExactly("tides-2025.md", "tides-2024.md")
        graphQlTester.document("""query { researchDocuments(workspaceId: $workspaceId, limit: 1) { name } }""").execute()
            .path("researchDocuments[*].name").entityList(String::class.java).containsExactly("tides-2025.md")
        // "A note" is every test document's description.
        graphQlTester.document("""query { researchDocuments(workspaceId: $workspaceId, search: "a note") { name } }""").execute()
            .path("researchDocuments[*].name").entityList(String::class.java).hasSize(3)
    }

    /** Uploaded from the dialog, a document is the workspace's like any other, and can be picked at once. */
    @Test
    fun `an uploaded document lands on the Artifacts page and can be picked, and one a research cannot read is refused`() {
        val answer = uploads.upload(
            workspaceId,
            listOf(MockMultipartFile("files", "harbour.md", "text/markdown", "A spring tide every fortnight.".toByteArray())),
        )
        assertThat(answer.statusCode.value()).isEqualTo(200)
        val held = saved.findByWorkspaceId(workspaceId).single { it.name == "harbour.md" }
        assertThat(held.savedBy).isEqualTo("alice")
        assertThat(store.open(held.location).use { String(it.readBytes()) }).isEqualTo("A spring tide every fortnight.")
        graphQlTester.document("""query { researchDocuments(workspaceId: $workspaceId, search: "harbour") { id } }""").execute()
            .path("researchDocuments[0].id").entity(Long::class.java).isEqualTo(held.id!!)
        assertThat(audit.findAll().map { it.message }).contains("Uploaded harbour.md for research")

        val refused = uploads.upload(
            workspaceId,
            listOf(MockMultipartFile("files", "photo.png", "image/png", byteArrayOf(1, 2, 3))),
        )
        assertThat(refused.statusCode.value()).isEqualTo(400)
        assertThat(refused.body.toString()).contains("photo.png is not a document a research can read")
        assertThat(saved.findByWorkspaceId(workspaceId).map { it.name }).doesNotContain("photo.png")
    }

    /** An agent - or anything over MCP - starts a research and follows it, through the page's own doors. */
    @Test
    fun `orknux tools start a research by mode name and read where it got to`() {
        val modelId = model(serve())
        val scope = io.mszymanski.orknux.server.mcp.OrknuxScope(workspaceId = workspaceId, mayWrite = true)
        assertThat(orknux.specs(scope).map { it.name }).contains("orknux_start_research", "orknux_research")

        val started = mapper.readTree(
            orknux.run(
                scope, "orknux_start_research",
                """{"question":"Why are there tides?","mode":"medium","planner":"research planner","writer":"research writer",
                   "sitesExclude":["tabloid.example"],"register":"academic"}""",
            ),
        )
        assertThat(started.path("error").isMissingNode).describedAs(started.toString()).isTrue()
        val id = started.path("started").asLong()
        assertThat(started.path("mode").stringValue()).isEqualTo("Medium")
        assertThat(started.path("url").stringValue()).endsWith("/workspace/$workspaceId/explore/research/$id")

        val done = settled(id)
        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)
        assertThat(done.modelId).isEqualTo(modelId)
        assertThat(done.language).isEqualTo(ResearchLanguage.ACADEMIC)
        assertThat(done.outputFormat).isEqualTo(ResearchOutputFormat.PDF)
        assertThat(web.read).noneMatch { it.contains("tabloid") }

        val read = mapper.readTree(orknux.run(scope, "orknux_research", """{"id":$id}"""))
        assertThat(read.path("status").stringValue()).isEqualTo("COMPLETED")
        assertThat(read.path("report").stringValue()).startsWith("# Tides")
        assertThat(read.path("sources").size()).isGreaterThan(0)
        assertThat(read.path("file").stringValue()).contains("/api/artifacts/")

        // Read-only conversations may follow one but not start one; and modes are named, not guessed.
        val reading = scope.copy(mayWrite = false)
        assertThat(orknux.run(reading, "orknux_start_research", """{"question":"Tides"}""")).contains("may read")
        assertThat(orknux.run(scope, "orknux_start_research", """{"question":"Tides","mode":"Galactic"}"""))
            .contains("There is no research mode called Galactic")
        assertThat(orknux.run(scope, "orknux_start_research", """{"question":"Tides","agent":"research writer","webSearch":false}"""))
            .contains("Without web search, pick documents or websites to read")
        assertThat(orknux.run(scope, "orknux_start_research", """{"question":"Tides","writer":"Nobody"}"""))
            .contains("There is no agent called Nobody")
        assertThat(read.path("planner").stringValue()).isEqualTo("Research planner")
        assertThat(read.path("writer").stringValue()).isEqualTo("Research writer")

        // Left out, no agent stands in: the start says which to choose.
        assertThat(orknux.run(scope, "orknux_start_research", """{"question":"Tides","sitesInclude":["one.example.org"],"webSearch":false}"""))
            .contains("Choose the agent that plans the research")
        // Nor both ways at once.
        assertThat(orknux.run(scope, "orknux_start_research", """{"question":"Tides","agent":"research writer","planner":"research planner"}"""))
            .contains("not both")
        // One agent for all of it, thinking with its own model.
        val unnamed = mapper.readTree(
            orknux.run(scope, "orknux_start_research", """{"question":"Tides","agent":"research writer","sitesInclude":["one.example.org"],"webSearch":false}"""),
        )
        // And so is the mode: Shallow, which is what an agent is told to leave it at unless the person asked for more. Issue #669.
        assertThat(unnamed.path("mode").stringValue()).isEqualTo("Shallow")
        val second = settled(unnamed.path("started").asLong())
        assertThat(second.modelId).isEqualTo(modelId)
        assertThat(second.useWebSearch).isFalse()
        assertThat(second.agentMode).isEqualTo(ResearchAgentMode.SINGLE)
        assertThat(listOf(second.plannerAgentName, second.writerAgentName)).containsOnly("Research writer")
        assertThat(mapper.readTree(orknux.run(scope, "orknux_research", """{"id":${second.id}}""")).path("agents").stringValue()).isEqualTo("SINGLE")
    }

    /**
     * What an agent is told about the mode: Shallow unless the person asked
     * for a deeper one, as a rule and not as a menu. Listed as a choice, a
     * model picked Deep for any question that sounded big. Issue #669.
     */
    @Test
    fun `an agent is told to start a Shallow research unless the person asked for a deeper one`() {
        val spec = orknux.specs(io.mszymanski.orknux.server.mcp.OrknuxScope(workspaceId = workspaceId, mayWrite = true))
            .single { it.name == "orknux_start_research" }
        val mode = spec.parameters.single { it.name == "mode" }

        assertThat(spec.description).contains("Shallow one unless the person asked for a deeper one")
        assertThat(mode.description).startsWith("Leave it out unless the person asked for a deeper research")
        assertThat(mode.description).contains("left out, it is Shallow")
    }

    @Test
    fun `a research asked for as a PDF is laid out as one`() {
        val modelId = model(serve())
        val done = settled(
            start("""{ workspaceId: $workspaceId, question: "Tides", modeId: ${modeId("SHALLOW")}, language: FORMAL, outputFormat: PDF }"""),
        )
        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)
        val file = saved.findByIdOrNull(requireNotNull(done.artifactId))!!
        assertThat(file.name).isEqualTo("tides.pdf")
        assertThat(store.open(file.location).use { it.readNBytes(4) }).isEqualTo("%PDF".toByteArray())
    }

    /**
     * One agent for every step, or one chosen for each - and nothing in place
     * of one nobody chose, because a workspace has no research agents of its
     * own: a start that leaves one out is refused, saying which.
     */
    @Test
    fun `a research runs with one agent for every step or one for each, and an agent left out is refused`() {
        model(serve())
        val writer = requireNotNull(agents.findByWorkspaceIdAndName(workspaceId, ResearchModes.WRITER.name))
        val one = settled(
            start(
                """{ workspaceId: $workspaceId, question: "Why are there tides?", modeId: ${modeId("SHALLOW")}, agentMode: SINGLE,
                     agentId: ${writer.id}, review: true, language: FORMAL, outputFormat: DOCUMENT }""",
            ),
        )
        assertThat(one.status).describedAs(one.error).isEqualTo(ResearchStatus.COMPLETED)
        assertThat(one.agentMode).isEqualTo(ResearchAgentMode.SINGLE)
        assertThat(listOf(one.plannerAgentId, one.writerAgentId, one.reviewerAgentId)).containsOnly(writer.id)
        graphQlTester.document("""query { research(id: ${one.id}) { agentMode plannerAgentName reviewerAgentName } }""").execute()
            .path("research.agentMode").entity(String::class.java).isEqualTo("SINGLE")
            .path("research.reviewerAgentName").entity(String::class.java).isEqualTo(writer.name)

        val each = settled(start("""{ workspaceId: $workspaceId, question: "Tides", modeId: ${modeId("SHALLOW")}, language: FORMAL, outputFormat: DOCUMENT }"""))
        assertThat(each.status).describedAs(each.error).isEqualTo(ResearchStatus.COMPLETED)
        assertThat(each.agentMode).isEqualTo(ResearchAgentMode.PER_STEP)
        assertThat(each.plannerAgentName).isEqualTo(ResearchModes.PLANNER.name)

        fun refusedAs(input: String, code: String) = graphQlTester.document("""mutation { startResearch(input: { workspaceId: $workspaceId, question: "Tides", modeId: ${modeId("SHALLOW")}, language: FORMAL, outputFormat: DOCUMENT, $input }) { id } }""")
            .execute().errors().expect { it.extensions?.get("code") == code }.verify()
        refusedAs("agentMode: SINGLE", "ResearchAgentNotChosen")
        refusedAs("agentMode: PER_STEP, writerAgentId: ${writer.id}", "ResearchPlannerNotChosen")
        refusedAs("agentMode: PER_STEP, plannerAgentId: ${writer.id}", "ResearchWriterNotChosen")
        refusedAs("agentMode: PER_STEP, plannerAgentId: ${writer.id}, writerAgentId: ${writer.id}, review: true", "ResearchReviewerNotChosen")
        // Nothing given at all is an agent for each step, none of them chosen.
        refusedAs("useWebSearch: true", "ResearchPlannerNotChosen")
    }

    /** An agent with no model is refused before the research starts, rather than run to fail. */
    @Test
    fun `a research is not started with an agent that has no model`() {
        val bare = agents.save(
            io.mszymanski.orknux.server.agent.Agent(workspaceId = workspaceId, name = "No model $stamp", type = io.mszymanski.orknux.server.agent.AgentType.LLM),
        )
        refused("""{ workspaceId: $workspaceId, question: "Tides", modeId: ${modeId("SHALLOW")}, agentMode: SINGLE, agentId: ${bare.id}, language: FORMAL, outputFormat: DOCUMENT }""", "ResearchAgentModelMissing")
        assertThat(researches.findAll().filter { it.workspaceId == workspaceId }).isEmpty()
    }

    /* --------------------------------------------------------- web search --- */

    @Test
    fun `without web search a research reads only its documents and the websites it keeps to, and searches nothing`() {
        val notes = document("notes.md", "The harbour master logs a spring tide every fortnight.")
        val modelId = model(serve())

        val id = start(
            """{ workspaceId: $workspaceId, question: "How often are spring tides?", modeId: ${modeId("MEDIUM")}, 
                 language: FORMAL, outputFormat: DOCUMENT, useWebSearch: false, documentsInclude: [$notes],
                 websitesInclude: ["harbour.example.org/tides", "https://tabloid.example/gossip"], websitesExclude: ["tabloid.example"] }""",
        )
        val done = settled(id)
        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)
        assertThat(done.useWebSearch).isFalse()

        // No search at all, not even a follow-up one at Medium's depth; the website kept to is read where it was typed.
        assertThat(web.searched).isEmpty()
        // Nor is web search lent to the agents it runs with.
        assertThat(toolsOf(asked(PLAN).single())).doesNotContain("web_search")
        assertThat(toolsOf(asked(WRITE).single())).doesNotContain("web_search")
        assertThat(asked(FOLLOW_UP)).isEmpty()
        assertThat(web.read).containsExactly("https://harbour.example.org/tides")
        assertThat(asked(WRITE).single()).contains("/api/artifacts/$notes").contains("https://harbour.example.org/tides")
        graphQlTester.document("""query { research(id: $id) { useWebSearch sources { url } } }""").execute()
            .path("research.useWebSearch").entity(Boolean::class.java).isEqualTo(false)
            .path("research.sources[*].url").entityList(String::class.java)
            .containsExactly("/api/artifacts/$notes", "https://harbour.example.org/tides")
    }

    @Test
    fun `without web search, documents or websites, there is nothing to read and the start is refused`() {
        val modelId = model(serve())
        refused(
            """{ workspaceId: $workspaceId, question: "Tides", modeId: ${modeId("SHALLOW")}, language: FORMAL,
                 outputFormat: PDF, useWebSearch: false, websitesExclude: ["tabloid.example"] }""",
            "ResearchNothingToRead",
        )
        assertThat(researches.findAll().filter { it.workspaceId == workspaceId }).isEmpty()
    }

    /**
     * Web search with no key it can read is refused at the start, with a
     * code, and the form is told the same by asking. Without web search the
     * key does not matter. Issue #666.
     */
    @Test
    fun `a research that searches the web is refused before it starts where there is no search key`() {
        model(serve())
        web.ready = false
        graphQlTester.document("""{ researchSearchReady(workspaceId: $workspaceId) }""")
            .execute().path("researchSearchReady").entity(Boolean::class.java).isEqualTo(false)
        refused(
            """{ workspaceId: $workspaceId, question: "Tides", modeId: ${modeId("SHALLOW")}, language: FORMAL, outputFormat: PDF }""",
            "ResearchSearchKeyMissing",
        )
        assertThat(orknux.run(io.mszymanski.orknux.server.mcp.OrknuxScope(workspaceId = workspaceId, mayWrite = true), "orknux_start_research", """{"question":"Tides","agent":"research writer"}"""))
            .contains("search key")
        assertThat(researches.findAll().filter { it.workspaceId == workspaceId }).isEmpty()
        assertThat(web.searched).isEmpty()

        val reading = start(
            """{ workspaceId: $workspaceId, question: "Tides", modeId: ${modeId("SHALLOW")}, language: FORMAL,
                 outputFormat: DOCUMENT, useWebSearch: false, websitesInclude: ["one.example.org"] }""",
        )
        assertThat(settled(reading).status).isEqualTo(ResearchStatus.COMPLETED)

        web.ready = true
        graphQlTester.document("""{ researchSearchReady(workspaceId: $workspaceId) }""")
            .execute().path("researchSearchReady").entity(Boolean::class.java).isEqualTo(true)
    }

    /** And the server's own answer is the workspace's key: none, then one. Issue #666. */
    @Test
    fun `the workspace's search is ready once it has a key`() {
        assertThat(realWeb.searchReady(workspaceId)).isFalse()
        searchSettings.set(workspaceId, null, "tvly-test-key", null, null, "alice")
        assertThat(realWeb.searchReady(workspaceId)).isTrue()
    }

    /* ------------------------------------------------------------- images --- */

    @Test
    fun `a research told not to look for images looks for none, fetches none and tells the writer of none`() {
        val modelId = model(serve())
        val id = start(
            """{ workspaceId: $workspaceId, question: "Why are there tides?", modeId: ${modeId("SHALLOW")}, 
                 language: FORMAL, outputFormat: PDF, includeImages: false }""",
        )
        val done = settled(id)
        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)
        assertThat(done.includeImages).isFalse()
        assertThat(web.searchedImages).isEmpty()
        assertThat(web.fetchedImages).isEmpty()
        assertThat(done.foundImages).isNull()
        assertThat(asked(WRITE).single()).doesNotContain("Images you may place")
        assertThat(saved.findByWorkspaceId(workspaceId).map { it.contentType }).noneMatch { it.startsWith("image/") }
        graphQlTester.document("""query { research(id: $id) { includeImages images { number } } }""").execute()
            .path("research.includeImages").entity(Boolean::class.java).isEqualTo(false)
            .path("research.images").entityList(Any::class.java).hasSize(0)
    }

    @Test
    fun `a research asked for images keeps those within the caps as artifacts, says why it passed each other one over, and lays out the one placed in its PDF`() {
        tunables.set(Tunable.RESEARCH_IMAGES_MOST, 3, "alice")
        tunables.set(Tunable.RESEARCH_IMAGE_MB, 1, "alice")
        try {
            val modelId = model(serve())
            val id = start(
                """{ workspaceId: $workspaceId, question: "Why are there tides?", modeId: ${modeId("SHALLOW")}, 
                     language: FORMAL, outputFormat: PDF, websitesExclude: ["tabloid.example"], includeImages: true }""",
            )
            val done = settled(id)
            assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)
            assertThat(done.includeImages).isTrue()

            // The picture search asked once, for the question; then the pictures on the pages read.
            assertThat(web.searchedImages).containsExactly("Why are there tides?")
            // Three kept, the most this installation keeps: the fourth worth keeping is never fetched, nor one on a website to avoid.
            assertThat(web.fetchedImages).doesNotContain("$PICTURES/extra.png", "https://tabloid.example/whale.png")
            val view = graphQlTester.document(
                """query { research(id: $id) { includeImages images { number caption url page contentType width height artifactId fileUrl } result } }""",
            ).execute()
            view.path("research.includeImages").entity(Boolean::class.java).isEqualTo(true)
            view.path("research.images[*].number").entityList(Int::class.java).containsExactly(1, 2, 3)
            view.path("research.images[*].caption").entityList(String::class.java)
                .containsExactly("The moon over the bay", "A diver at low tide", "A tide chart")
            view.path("research.images[*].contentType").entityList(String::class.java).containsExactly("image/png", "image/jpeg", "image/gif")
            view.path("research.images[0].page").entity(String::class.java).isEqualTo("https://bay.example.org/moon")
            val artifactIds = view.path("research.images[*].artifactId").entityList(Long::class.java).get()
            // Each kept as an artifact of its own, the picture's own bytes, which the page shows it from.
            artifactIds.zip(listOf("image/png", "image/jpeg", "image/gif")).forEach { (artifactId, type) ->
                val file = saved.findByIdOrNull(artifactId)!!
                assertThat(file.workspaceId).isEqualTo(workspaceId)
                assertThat(file.contentType).isEqualTo(type)
                assertThat(ResearchPipeline.typeOf(store.open(file.location).use { it.readBytes() })).isEqualTo(type)
            }
            view.path("research.images[0].fileUrl").entity(String::class.java).isEqualTo("/api/artifacts/${artifactIds[0]}")

            // Every picture tried is in Gather's log, kept or passed over, and by which rule.
            val said = runLogs.findByExecutionIdOrderBySequenceAsc(requireNotNull(done.executionId)).groupBy({ it.nodeKey }, { it.message })
            assertThat(said.getValue("gather"))
                .contains("Searched images for \"Why are there tides?\": 8 results")
                .anyMatch { it.startsWith("Kept image 1 ($PICTURES/moon.png) as ") && it.endsWith("200 x 150 pixels, 1 KB, \"The moon over the bay\"") }
                .contains("Skipped image $PICTURES/page.html: it was served as text/html, not an image")
                .contains("Skipped image $PICTURES/fake.png: it was served as image/png, and its bytes are not a PNG, JPEG or GIF")
                .contains("Skipped image $PICTURES/huge.jpg: it is 1.5 MB, larger than the 1 MB an image may be")
                .contains("Skipped image $PICTURES/icon.png: it is 16 x 16 pixels, smaller than 120 on its shorter side")
                .contains("Skipped image https://tabloid.example/whale.png: tabloid.example is among the websites to avoid")
                .contains("Warning: Could not fetch image $PICTURES/gone.png: the address answered 404")
                .anyMatch { it.startsWith("Kept image 3 ($PICTURES/chart.gif) as ") }
                .contains("Stopped looking for images at 3, the most a research keeps")
            assertThat(said.getValue("save")).contains("1 of 3 kept images are placed in the report")

            // The writer is told of the three by number, and of nothing else.
            val write = asked(WRITE).single()
            assertThat(write).contains("[image 1] The moon over the bay - from https://bay.example.org/moon")
                .contains("[image 3] A tide chart").contains("(image:1)").doesNotContain("[image 4]")

            // The PDF lays out the one picture placed - from the bytes kept, not from an address - and the made-up and the remote ones as words.
            val pdf = store.open(saved.findByIdOrNull(requireNotNull(done.artifactId))!!.location).use { it.readBytes() }
            assertThat(picturesIn(pdf)).isEqualTo(1)
            val text = org.apache.pdfbox.Loader.loadPDF(pdf).use { org.apache.pdfbox.text.PDFTextStripper().getText(it) }
            assertThat(text).contains("Image 1: The moon over the bay").contains("Made up").contains("Remote")

            // The report the page reads links the picture placed to the one kept, and nothing else.
            val report = requireNotNull(done.result)
            assertThat(report).contains("![The moon over the bay](/api/artifacts/${artifactIds[0]})")
                .doesNotContain("image:9").doesNotContain("evil.example")
        } finally {
            tunables.set(Tunable.RESEARCH_IMAGES_MOST, null, "alice")
            tunables.set(Tunable.RESEARCH_IMAGE_MB, null, "alice")
        }
    }

    @Test
    fun `without web search a research asked for images takes them only from the pages and documents it read, and a document places them as links`() {
        val atlas = pdfDocument("atlas.pdf", "The harbour floods twice a day.")
        val modelId = model(serve())
        val id = start(
            """{ workspaceId: $workspaceId, question: "How often does the harbour flood?", modeId: ${modeId("SHALLOW")}, 
                 language: FORMAL, outputFormat: DOCUMENT, useWebSearch: false, includeImages: true, documentsInclude: [$atlas],
                 websitesInclude: ["harbour.example.org/tides"] }""",
        )
        val done = settled(id)
        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)

        // No picture search; the picture inside the document, then those on the page read.
        assertThat(web.searchedImages).isEmpty()
        val images = graphQlTester.document("""query { research(id: $id) { images { caption url artifactId } } }""").execute()
        images.path("research.images[*].caption").entityList(String::class.java)
            .containsExactly("atlas.pdf, page 1", "A tide chart", "Another picture")
        images.path("research.images[0].url").entity(String::class.java).isEqualTo("/api/artifacts/$atlas#page=1")

        // The document places the picture as a link to the one kept, with its caption and where it came from.
        val first = images.path("research.images[0].artifactId").entity(Long::class.java).get()
        val file = saved.findByIdOrNull(requireNotNull(done.artifactId))!!
        val markdown = store.open(file.location).use { String(it.readBytes(), Charsets.UTF_8) }
        assertThat(markdown).contains("![atlas\\.pdf, page 1](/api/artifacts/$first)\\\n*Image 1: atlas\\.pdf, page 1*")
            .doesNotContain("image:9").doesNotContain("evil.example")
    }

    @Test
    fun `include images round-trips through the orknux tools`() {
        val modelId = model(serve())
        val scope = io.mszymanski.orknux.server.mcp.OrknuxScope(workspaceId = workspaceId, mayWrite = true)
        val started = mapper.readTree(
            orknux.run(
                scope, "orknux_start_research",
                """{"question":"Tides","agent":"research writer","sitesInclude":["harbour.example.org/tides"],"webSearch":false,"includeImages":true}""",
            ),
        )
        assertThat(started.path("error").isMissingNode).describedAs(started.toString()).isTrue()
        val done = settled(started.path("started").asLong())
        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)
        assertThat(done.includeImages).isTrue()

        val read = mapper.readTree(orknux.run(scope, "orknux_research", """{"id":${done.id}}"""))
        assertThat(read.path("includeImages").booleanValue()).isTrue()
        assertThat(read.path("images").values().map { it.path("caption").stringValue() }).containsExactly("A tide chart", "Another picture")
        assertThat(read.path("images").first().path("file").stringValue()).startsWith("http").contains("/api/artifacts/")

        // Left out, it is on; said, it is off.
        val plain = mapper.readTree(
            orknux.run(scope, "orknux_start_research", """{"question":"Tides","agent":"research writer","sitesInclude":["harbour.example.org/tides"],"webSearch":false}"""),
        )
        assertThat(settled(plain.path("started").asLong()).includeImages).isTrue()
        val without = mapper.readTree(
            orknux.run(
                scope, "orknux_start_research",
                """{"question":"Tides","agent":"research writer","sitesInclude":["harbour.example.org/tides"],"webSearch":false,"includeImages":false}""",
            ),
        )
        assertThat(settled(without.path("started").asLong()).includeImages).isFalse()
    }

    @Test
    fun `a seeded mode drawn before Gather read includeImages is given it, and still counts as seeded`() {
        val shallow = modeWorkflow("SHALLOW")
        unreviewed(shallow)
        val gather = nodes.findByWorkflowId(shallow).single { it.nodeKey == "gather" }
        gather.mappings.removeIf { it.name == ResearchCapability.INCLUDE_IMAGES }
        nodes.save(gather)
        // And no retry policy, which is given back only to a mode that still counts as seeded.
        nodes.saveAll(nodes.findByWorkflowId(shallow).onEach { it.retryAttempts = null; it.retryBackoffSeconds = null })

        researchModes.ensure(workspaceId)

        val drawn = nodes.findByWorkflowId(shallow).sortedBy { it.positionX }
        val given = drawn.single { it.nodeKey == "gather" }.mappings.single { it.name == ResearchCapability.INCLUDE_IMAGES }
        assertThat(given.mode).isEqualTo(MappingMode.REFERENCE)
        assertThat(given.expression).isEqualTo("trigger.includeImages")
        assertCurrentShape(drawn)
        assertThat(drawn.map { it.retryAttempts }).allMatch { it != null }
    }

    /** A mode drawn before Plan and Write kept sessions is given them, and still counts as seeded. Issue #668. */
    @Test
    fun `a seeded mode drawn before its agents kept sessions is given them`() {
        val shallow = modeWorkflow("SHALLOW")
        nodes.findByWorkflowId(shallow).filter { it.nodeKey == "plan" || it.nodeKey == "write" }.forEach { node ->
            node.mappings.removeIf { it.name == "sessionKey" || it.name == "sessionKeyPrefix" }
            nodes.save(node)
        }

        researchModes.ensure(workspaceId)

        val drawn = nodes.findByWorkflowId(shallow).sortedBy { it.positionX }
        assertCurrentShape(drawn)
        // And one whose session somebody changed is theirs, and left as it is.
        val plan = drawn.single { it.nodeKey == "plan" }
        plan.mappings.single { it.name == "sessionKey" }.expression = "mine"
        nodes.save(plan)
        researchModes.ensure(workspaceId)
        assertThat(nodes.findByWorkflowId(shallow).single { it.nodeKey == "plan" }.mappings.single { it.name == "sessionKey" }.expression)
            .isEqualTo("mine")
    }

    @Test
    fun `the list is newest first and a research can be taken off it`() {
        val modelId = model(serve())
        val first = settled(start("""{ workspaceId: $workspaceId, question: "First", modeId: ${modeId("SHALLOW")}, language: FORMAL, outputFormat: DOCUMENT }"""))
        val second = settled(start("""{ workspaceId: $workspaceId, question: "Second", modeId: ${modeId("SHALLOW")}, language: FORMAL, outputFormat: DOCUMENT }"""))

        graphQlTester.document("""query { researches(workspaceId: $workspaceId) { content { question status } totalElements } }""").execute()
            .path("researches.content[*].question").entityList(String::class.java).containsExactly("Second", "First")
            .path("researches.totalElements").entity(Int::class.java).isEqualTo(2)

        graphQlTester.document("""mutation { deleteResearch(id: ${first.id}) }""").execute()
            .path("deleteResearch").entity(Boolean::class.java).isEqualTo(true)
        assertThat(researches.findByIdOrNull(requireNotNull(first.id))).isNull()
        assertThat(researches.findByIdOrNull(requireNotNull(second.id))).isNotNull()
        assertThat(audit.findAll().map { it.message }).anyMatch { it.startsWith("Research started in Shallow mode: First") }
    }

    /* --------------------------------------------------------------- logs --- */

    /**
     * Every step says in the run's log what it did, under its own node: the
     * run page's log for a research step was empty. Gather and Follow up name
     * each search and what it gave, each page read and each one passed over
     * with the rule that passed it over; Save names the file; the agent steps
     * say they are waiting on the model.
     */
    @Test
    fun `every step of a research writes what it did to the run's log under its own node`() {
        val modelId = model(serve())
        val done = settled(
            start(
                """{ workspaceId: $workspaceId, question: "Why are there tides?", modeId: ${modeId("MEDIUM")}, 
                     language: FORMAL, outputFormat: DOCUMENT, websitesExclude: ["tabloid.example"] }""",
            ),
        )
        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)
        val said = runLogs.findByExecutionIdOrderBySequenceAsc(requireNotNull(done.executionId))
            .groupBy({ it.nodeKey }, { it.message })

        assertThat(said.getValue("plan")).anyMatch { it.contains("Research planner is thinking") }
        assertThat(said.getValue("gather"))
            .contains("Searched \"tide causes\": 4 results")
            .contains("Skipped https://tabloid.example/tide-causes: tabloid.example is among the websites to avoid")
            .anyMatch { it.startsWith("Read tide causes, part 1 (https://tide-causes-1.example.org/tide-causes/1) as source 1") }
        assertThat(said.getValue("followUp"))
            .contains("Follow-up question at level 2: moon distance and tides")
            .contains("Searched \"moon distance and tides\": 4 results")
        assertThat(said.getValue("write")).anyMatch { it.contains("Research writer is thinking") }
        assertThat(said.getValue("save")).anyMatch { it.startsWith("Saved tides.md on the Artifacts page as artifact ${done.artifactId}") }
    }

    /* ------------------------------------------------------- nothing read --- */

    /**
     * Research 4 on the development server: a workspace with no search key,
     * three searches each refused, nothing read - and a PDF of the model
     * saying it had no sources, filed as the research's report.
     */
    @Test
    fun `a research whose every search is refused fails at Gather with what the search said, and writes nothing`() {
        web.refusing = "This workspace has no search key set."
        val modelId = model(serve())

        val done = settled(
            start("""{ workspaceId: $workspaceId, question: "GPU prices in 2027", modeId: ${modeId("SHALLOW")}, language: FORMAL, outputFormat: PDF }"""),
        )
        assertThat(done.status).isEqualTo(ResearchStatus.FAILED)
        assertThat(done.error)
            .contains("research_gather read no source")
            .contains("the search answered \"This workspace has no search key set.\" to all 3 queries")
        assertThat(web.searched).hasSize(3)
        assertThat(web.read).isEmpty()
        assertThat(asked(WRITE)).isEmpty()
        assertThat(done.result).isNull()
        assertThat(done.artifactId).isNull()
        val gather = runSteps.findByExecutionIdOrderByOrderAsc(requireNotNull(done.executionId)).single { it.nodeKey == "gather" }
        assertThat(gather.status).isEqualTo(StepStatus.FAILED)
        // Settled: the same searches would read the same nothing, so the step's retry policy spends no attempt on it.
        assertThat(gather.retryAttempts).isGreaterThan(1)
        assertThat(gather.attempts).isEqualTo(1)
        assertThat(retries.seen).isEmpty()
        assertThat(web.searched).hasSize(3)
        // Each refusal in the step's log, quoted as the search said it, though the step failed.
        assertThat(runLogs.findByExecutionIdOrderBySequenceAsc(requireNotNull(done.executionId)).filter { it.nodeKey == "gather" }.map { it.message })
            .contains("Warning: Searched \"tide causes\": the search answered \"This workspace has no search key set.\"")
    }

    @Test
    fun `a research whose searches find nothing to read writes no report and says how many it searched`() {
        web.finding = false
        val modelId = model(serve())

        val done = settled(
            start("""{ workspaceId: $workspaceId, question: "GPU prices in 2027", modeId: ${modeId("SHALLOW")}, language: FORMAL, outputFormat: PDF }"""),
        )
        assertThat(done.status).isEqualTo(ResearchStatus.FAILED)
        // Stopped before the writer, which would otherwise write a report from what it already believed.
        assertThat(done.error).contains("research_followUp has no source to write a report from: nothing was read in 3 searches.")
        assertThat(asked(WRITE)).isEmpty()
        assertThat(done.artifactId).isNull()
    }

    /* ------------------------------------------------- embedded functions --- */

    /**
     * The step actions call functions the release brings. Their scope was not
     * in the schema and they were not in the workspace's list, so an action
     * opened on an empty picker and the function's own page failed outright.
     */
    @Test
    fun `a step's function is listed and opened as built in, and refused an edit`() {
        val gather = requireNotNull(functions.findByScopeAndName(FunctionScope.EMBEDDED, ResearchCapability.functionName(ResearchCapability.GATHER)))
        val action = requireNotNull(actions.findByWorkspaceIdAndName(workspaceId, "Research: Gather sources"))
        assertThat(action.functionId).isEqualTo(gather.id)

        graphQlTester.document("""query { function(id: ${gather.id}) { name scope editable params { name } } }""").execute()
            .path("function.name").entity(String::class.java).isEqualTo("research_gather")
            .path("function.scope").entity(String::class.java).isEqualTo("EMBEDDED")
            .path("function.editable").entity(Boolean::class.java).isEqualTo(false)

        // The list the action's picker reads its choice from holds it.
        graphQlTester.document("""query { workspaceFunctions(workspaceId: $workspaceId, size: 50, search: "research_gather") { content { id scope } } }""")
            .execute().path("workspaceFunctions.content[*].id").entityList(String::class.java).contains(gather.id.toString())
        graphQlTester.document("""query { workspaceFunctions(workspaceId: $workspaceId, size: 50) { totalElements } }""")
            .execute().path("workspaceFunctions.totalElements").entity(Int::class.java).satisfies({ assertThat(it).isGreaterThan(0) })
        val builtIn = graphQlTester.document("""query { workspaceFunctions(workspaceId: $workspaceId, size: 100, scope: EMBEDDED) { content { name scope } } }""")
            .execute().path("workspaceFunctions.content[*].scope").entityList(String::class.java).get()
        assertThat(builtIn).isNotEmpty().allMatch { it == "EMBEDDED" }

        graphQlTester.document("""mutation { updateFunction(id: ${gather.id}, input: { description: "Mine" }) { id } }""")
            .execute().errors().expect { it.extensions?.get("code") == "FunctionBuiltIn" }.verify()
        graphQlTester.document("""mutation { setFunctionTimeout(id: ${gather.id}, seconds: 30) { id } }""")
            .execute().errors().expect { it.extensions?.get("code") == "FunctionBuiltIn" }.verify()
        assertThat(functions.findByIdOrNull(requireNotNull(gather.id))!!.editedAt).isNull()
    }

    /* ----------------------------------------------------------- refusals --- */

    @Test
    fun `what cannot be researched is refused with a code`() {
        val shallow = modeId("SHALLOW")
        refused("""{ workspaceId: $workspaceId, question: "  ", modeId: $shallow, language: FORMAL, outputFormat: PDF }""", "ResearchQuestionMissing")
        refused("""{ workspaceId: $workspaceId, question: "Tides", modeId: 999999999, language: FORMAL, outputFormat: PDF }""", "ResearchModeNotFound")
        refused("""{ workspaceId: $workspaceId, question: "Tides", modeId: $shallow, plannerAgentId: 999999999, language: FORMAL, outputFormat: PDF }""", "ResearchAgentNotFound")
        refused("""{ workspaceId: $workspaceId, question: "Tides", modeId: $shallow, language: FORMAL, outputFormat: PDF, documentsInclude: [999999999] }""", "ResearchDocumentNotFound")
        assertThat(researches.findAll().filter { it.workspaceId == workspaceId }).isEmpty()
    }

    @Test
    @WithMockUser(username = "bob", roles = ["USERS"])
    fun `somebody who cannot see the workspace can neither list nor start research there`() {
        listOf(
            """query { researches(workspaceId: $workspaceId) { totalElements } }""",
            """query { researchModes(workspaceId: $workspaceId) { id } }""",
            """query { researchDocuments(workspaceId: $workspaceId) { id } }""",
            """mutation { startResearch(input: { workspaceId: $workspaceId, question: "Tides", modeId: 1, language: FORMAL, outputFormat: PDF }) { id } }""",
        ).forEach { document ->
            graphQlTester.document(document).execute().errors().expect { it.extensions?.get("code") == "WorkspaceNotFound" }.verify()
        }
        assertThat(researches.findAll().filter { it.workspaceId == workspaceId }).isEmpty()
    }

    /* -------------------------------------------------------------- modes --- */

    @Test
    fun `a custom mode is a name and a workflow, picked or copied, and the seeded ones stay`() {
        val mine = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "My research" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()

        graphQlTester.document(
            """mutation { createResearchMode(input: { workspaceId: $workspaceId, name: "Patents", workflowId: $mine }) { kind name workflowName } }""",
        ).execute()
            .path("createResearchMode.kind").entity(String::class.java).isEqualTo("CUSTOM")
            .path("createResearchMode.workflowName").entity(String::class.java).isEqualTo("My research")

        graphQlTester.document(
            """mutation { createResearchMode(input: { workspaceId: $workspaceId, name: "Legal", copyOfModeId: ${modeId("DEEP")} }) { id workflowName } }""",
        ).execute().path("createResearchMode.workflowName").entity(String::class.java).isEqualTo("Research: Legal")

        assertThat(modesOf().map { it["name"] }).containsExactly("Shallow", "Medium", "Deep", "Patents", "Legal")

        graphQlTester.document("""mutation { createResearchMode(input: { workspaceId: $workspaceId, name: "Legal", workflowId: $mine }) { id } }""")
            .execute().errors().expect { it.extensions?.get("code") == "ResearchModeNameTaken" }.verify()
        graphQlTester.document("""mutation { deleteResearchMode(id: ${modeId("DEEP")}) }""")
            .execute().errors().expect { it.extensions?.get("code") == "ResearchModeSeeded" }.verify()

        val patents = modesOf().first { it["name"] == "Patents" }["id"]
        graphQlTester.document("""mutation { deleteResearchMode(id: $patents) }""").execute()
            .path("deleteResearchMode").entity(Boolean::class.java).isEqualTo(true)
        assertThat(audit.findAll().map { it.message }).contains("Research mode Patents added", "Research mode Patents removed")
    }

    /* ------------------------------------------------------------ fixture --- */

    /** What the model was asked under one of the pipeline's four instructions, told apart by its opening sentence. */
    private fun asked(instruction: String): List<String> = received.filter { it.contains(opening(instruction)) }

    private fun opening(instruction: String) = instruction.substringBefore('.')

    @Suppress("UNCHECKED_CAST")
    private fun modesOf(): List<Map<String, Any?>> =
        graphQlTester.document("""query { researchModes(workspaceId: $workspaceId) { id kind name workflowId workflowName reviewable } }""")
            .execute().path("researchModes").entity(List::class.java).get() as List<Map<String, Any?>>

    private fun modeId(kind: String): Long = (modesOf().first { it["kind"] == kind }["id"] as String).toLong()

    private fun modeWorkflow(kind: String): Long = (modesOf().first { it["kind"] == kind }["workflowId"] as String).toLong()

    /** The model a request to the stub named. */
    private fun modelOf(body: String): String = mapper.readTree(body).path("model").stringValue()

    /** The tools a request to the stub offered, by name. */
    private fun toolsOf(body: String): List<String> =
        mapper.readTree(body).path("tools").values().map { it.path("function").path("name").stringValue() }

    /**
     * The current shape: Plan and Write agent nodes naming no agent, each
     * answered by the agent the research was started with for it and asking
     * the step's instructions with its prompt, lent web search where it
     * searches the web, and asking its own prompt; Gather, Follow up and Save
     * actions, reading the request off the run and the step before off the run.
     */
    private fun assertCurrentShape(drawn: List<WorkflowNode>) {
        assertThat(drawn.map { it.kind })
            .containsExactly(NodeKind.AGENT, NodeKind.ACTION, NodeKind.ACTION, NodeKind.AGENT, NodeKind.CONDITION, NodeKind.ACTION, NodeKind.ACTION)
        val byKey = drawn.associateBy { it.nodeKey }
        listOf("plan" to ResearchModes.PLANNER, "write" to ResearchModes.WRITER).forEach { (key, part) ->
            val node = byKey.getValue(key)
            // Naming no agent: the research hands it the one it was started with, and nothing was made for it.
            assertThat(node.agentId).isNull()
            // The step's instructions are in what it asks, after whatever the agent was told on its page.
            assertThat(node.mappings.single { it.name == "prompt" }.expression).startsWith(part.instructions)
            // No model of the node's own: the agent's is the one it thinks with (#678).
            assertThat(node.mappings.map { it.name }).doesNotContain("model")
            val agent = node.mappings.single { it.name == "agent" }
            assertThat(agent.mode).isEqualTo(MappingMode.REFERENCE)
            assertThat(agent.expression).isEqualTo(if (key == "plan") "trigger.plannerAgent" else "trigger.writerAgent")
            assertThat(node.mappings.single { it.name == "webSearch" }.expression).isEqualTo("trigger.webSearch")
            assertThat(node.mappings.single { it.name == "prompt" }.expression).contains("{{trigger.question}}")
            assertThat(node.streamOutput).isFalse()
            // Each talks in a session of its own, the research's and the step's, so what it did can be read (#668).
            assertThat(node.mappings.single { it.name == "sessionKeyPrefix" }.expression).isEqualTo("research")
            assertThat(node.mappings.single { it.name == "sessionKey" }.let { it.mode to it.expression })
                .isEqualTo(MappingMode.VALUE to "{{trigger.research}}-$key")
        }
        assertThat(byKey.getValue("write").mappings.single { it.name == "prompt" }.expression)
            .contains("{{trigger.language}}", "{{trigger.documentsExcludeNames}}", "{{deepened.notes}}")
        listOf("gather", "followUp", "review", "save").forEach { key ->
            val node = byKey.getValue(key)
            assertThat(actions.findByIdOrNull(requireNotNull(node.actionId))!!.name).isEqualTo("Research: ${node.name}")
            node.mappings.filter { it.name in ResearchCapability.REQUEST }.forEach {
                assertThat(it.mode).isEqualTo(MappingMode.REFERENCE)
                assertThat(it.expression).isEqualTo("trigger.${it.name}")
            }
        }
        // The planner's answer is Gather's queries; the writer's draft and what was gathered are Save's.
        assertThat(byKey.getValue("gather").mappings.single { it.name == "queries" }.expression).isEqualTo("plan")
        assertThat(byKey.getValue("save").mappings.single { it.name == "report" }.expression).isEqualTo("draft")
        assertThat(byKey.getValue("save").mappings.single { it.name == "gathered" }.expression).isEqualTo("deepened")
        assertThat(byKey.getValue("save").outputName).isEqualTo("research")
        // The review reads the plan, what was gathered and the draft, with the model chosen for reviewing; Save prefers what it left.
        assertThat(byKey.getValue("review").mappings.associate { it.name to it.expression })
            .containsEntry("queries", "plan").containsEntry("gathered", "deepened").containsEntry("report", "draft")
            .containsEntry("reviewerModel", "trigger.reviewerModel")
            .containsEntry("writerAgent", "trigger.writerAgent").containsEntry("reviewerAgent", "trigger.reviewerAgent")
        assertThat(byKey.getValue("save").mappings.single { it.name == "reviewed" }.expression).isEqualTo("reviewed.report")
        assertThat(byKey.getValue("reviewing").mappings.single().expression).isEqualTo("{{trigger.review}}")
        // Laid out so no two cards overlap: a description short enough to keep a card near its least
        // width, and room between cards for the line's label.
        assertThat(drawn.map { it.description.orEmpty().length }).allMatch { it in 1..50 }
        assertThat(drawn.zipWithNext().map { (one, next) -> next.positionX - one.positionX }).allMatch { it >= 640.0 }
        // One row, with Save a row lower so Follow up's line to it passes under Write rather than through it.
        assertThat(drawn.filter { it.nodeKey != "save" }.map { it.positionY }.distinct()).hasSize(1)
        assertThat(byKey.getValue("save").positionY).isGreaterThan(byKey.getValue("write").positionY + 200)
    }

    /** Each step's knobs by name: its written numbers, and on Plan the number its prompt asks for. */
    private fun knobsOf(drawn: List<WorkflowNode>): Map<String, Map<String, Int>> = drawn.associate { node ->
        val numbers = node.mappings.filter { it.mode == MappingMode.VALUE && it.name != "prompt" }
            .mapNotNull { mapping -> mapping.expression.toIntOrNull()?.let { mapping.name to it } }.toMap()
        val asked = node.mappings.singleOrNull { it.name == "prompt" && node.nodeKey == "plan" }
            ?.let { Regex("exactly (\\d+) search queries").find(it.expression)!!.groupValues[1].toInt() }
        node.name to (numbers + listOfNotNull(asked?.let { SUB to it }))
    }

    /** A mode's workflow as it was seeded before Plan and Write were agents: five actions, each calling its step's function. */
    private fun actionShape(workflowId: Long, knobs: Map<String, Int>) {
        edges.deleteAll(edges.findByWorkflowId(workflowId))
        nodes.deleteAll(nodes.findByWorkflowId(workflowId))
        fun action(step: String, name: String): Long {
            val function = requireNotNull(functions.findByScopeAndName(FunctionScope.EMBEDDED, ResearchCapability.functionName(step)))
            return requireNotNull(
                (actions.findByWorkspaceIdAndName(workspaceId, "Research: $name") ?: actions.save(
                    WorkflowAction(
                        workspaceId = workspaceId, name = "Research: $name", type = ActionType.EXECUTE,
                        subtype = ActionSubtype.FUNCTION, functionId = function.id,
                    ),
                )).id,
            )
        }
        fun trigger(vararg names: String) = names.map { NodeMapping(name = it, expression = "trigger.$it", mode = MappingMode.REFERENCE) }
        fun read(name: String, field: String, from: String) = NodeMapping(name = name, expression = field, mode = MappingMode.REFERENCE, sourceNodeKey = from)
        fun knob(vararg names: String) = names.map { NodeMapping(name = it, expression = knobs.getValue(it).toString(), mode = MappingMode.VALUE) }
        val steps = listOf(
            WorkflowNode(
                workflowId = workflowId, nodeKey = "plan", kind = NodeKind.ACTION, name = "Plan",
                description = "Breaks the question into search queries.", actionId = action("plan", "Plan"), outputName = "plan",
                positionX = 0.0, positionY = 0.0,
                mappings = (trigger("research", "question", "model") + knob(SUB)).toMutableList(),
            ),
            WorkflowNode(
                workflowId = workflowId, nodeKey = "gather", kind = NodeKind.ACTION, name = "Gather",
                description = "Reads the documents, then searches every query and notes what it reads.", actionId = action("gather", "Gather"),
                outputName = "gathered", positionX = 320.0, positionY = 0.0,
                mappings = (
                    trigger("research", "question", "model", "websitesInclude", "websitesExclude", "documentsInclude", "webSearch") +
                        read("queries", "plan.queries", "plan") + knob(RESULTS, SOURCES, PAGES)
                    ).toMutableList(),
            ),
            WorkflowNode(
                workflowId = workflowId, nodeKey = "followUp", kind = NodeKind.ACTION, name = "Follow up",
                description = "Asks follow-up questions from what was learnt and searches those, to the depth set.",
                actionId = action("followUp", "Follow up"), outputName = "deepened", positionX = 640.0, positionY = 0.0,
                mappings = (
                    trigger("research", "question", "model", "websitesInclude", "websitesExclude", "webSearch") +
                        read("gathered", "gathered", "gather") + knob(BREADTH, DEPTH)
                    ).toMutableList(),
            ),
            WorkflowNode(
                workflowId = workflowId, nodeKey = "write", kind = NodeKind.ACTION, name = "Write",
                description = "Writes the report from the notes, citing numbered sources, in the register asked for.",
                actionId = action("write", "Write"), outputName = "research", positionX = 960.0, positionY = 0.0,
                mappings = (trigger("research", "question", "model", "language", "documentsExclude") + read("gathered", "deepened", "followUp")).toMutableList(),
            ),
            WorkflowNode(
                workflowId = workflowId, nodeKey = "save", kind = NodeKind.ACTION, name = "Save",
                description = "Saves the report on the Artifacts page as the PDF or document asked for.",
                actionId = action("save", "Save"), outputName = "saved", positionX = 1280.0, positionY = 0.0,
                mappings = (trigger("research", "question", "outputFormat") + read("report", "research.report", "write")).toMutableList(),
            ),
        )
        steps.forEach { nodes.save(it) }
        steps.zipWithNext().forEach { (from, to) ->
            edges.save(io.mszymanski.orknux.server.workflow.WorkflowEdge(workflowId = workflowId, sourceKey = from.nodeKey, targetKey = to.nodeKey))
        }
    }

    /** A mode's workflow in the current shape as it was seeded under the steps' former names, and its actions under theirs. */
    private fun formerNames(workflowId: Long) {
        unreviewed(workflowId)
        val former = mapOf(
            "plan" to ("Plan" to "Research planner writes the search queries"),
            "gather" to ("Gather" to "Searches each query and notes what it reads"),
            "followUp" to ("Follow up" to "Searches follow-up questions, to the depth set"),
            "write" to ("Write" to "Research writer writes the cited report"),
            "save" to ("Save" to "Lists the sources and saves the report"),
        )
        nodes.saveAll(
            nodes.findByWorkflowId(workflowId).onEach { node ->
                val (name, description) = former.getValue(node.nodeKey)
                node.name = name
                node.description = description
                node.actionId?.let { actions.findByIdOrNull(it) }?.let { action ->
                    action.name = "Research: $name"
                    actions.save(action)
                }
            },
        )
    }

    /**
     * A mode's workflow as it was seeded before there was a review: the five
     * steps in a line, Write straight to Save, and Save reading the draft alone.
     */
    private fun unreviewed(workflowId: Long) {
        beforeAgents(workflowId)
        edges.deleteAll(edges.findByWorkflowId(workflowId).filter { it.sourceKey in setOf("write", "reviewing", "review") })
        nodes.deleteAll(nodes.findByWorkflowId(workflowId).filter { it.nodeKey == "reviewing" || it.nodeKey == "review" })
        edges.save(io.mszymanski.orknux.server.workflow.WorkflowEdge(workflowId = workflowId, sourceKey = "write", targetKey = "save"))
        nodes.saveAll(nodes.findByWorkflowId(workflowId).onEach { node -> if (node.nodeKey == "save") node.mappings.removeIf { it.name == "reviewed" } })
    }

    /**
     * A mode's workflow as it was drawn while Plan and Write named the agents
     * this product seeded - the Research planner and writer, made here as they
     * were then - and asked only the question, the agent's page carrying the
     * step's instructions. Every shape before today's named them.
     */
    private fun namingAgents(workflowId: Long) {
        nodes.saveAll(
            nodes.findByWorkflowId(workflowId).onEach { node ->
                val part = when (node.nodeKey) {
                    "plan" -> ResearchModes.PLANNER
                    "write" -> ResearchModes.WRITER
                    else -> return@onEach
                }
                val agent = agents.findByWorkspaceIdAndName(workspaceId, part.name)
                    ?: agents.save(
                        io.mszymanski.orknux.server.agent.Agent(
                            workspaceId = workspaceId, name = part.name,
                            type = io.mszymanski.orknux.server.agent.AgentType.LLM, systemPrompt = part.instructions,
                        ),
                    )
                node.agentId = agent.id
                node.mappings.single { it.name == "prompt" }.let { it.expression = it.expression.removePrefix(part.instructions + "\n\n") }
            },
        )
    }

    /**
     * A mode's workflow as it was seeded before research picked its agents
     * (#678): Plan and Write naming the research's model, no agent and no web
     * search read off the run, and Review reading no agents.
     */
    private fun beforeAgents(workflowId: Long) {
        namingAgents(workflowId)
        nodes.saveAll(
            nodes.findByWorkflowId(workflowId).onEach { node ->
                when (node.nodeKey) {
                    "plan", "write" -> {
                        // And so before they kept sessions (#668), which came later still.
                        node.mappings.removeIf { it.name == "agent" || it.name == "webSearch" || it.name == "sessionKey" || it.name == "sessionKeyPrefix" }
                        node.mappings.add(NodeMapping(name = "model", expression = "trigger.model", mode = MappingMode.REFERENCE))
                    }
                    "review" -> node.mappings.removeIf { it.name == "writerAgent" || it.name == "reviewerAgent" }
                }
            },
        )
    }

    @Test
    fun `a mode as it was before research picked its agents is drawn with them, runs with them, and an edited one is left`() {
        val medium = modeWorkflow("MEDIUM")
        val deep = modeWorkflow("DEEP")
        beforeAgents(medium)
        beforeAgents(deep)
        // Deep's planner told something of somebody's own: theirs now, and left as it is.
        nodes.saveAll(nodes.findByWorkflowId(deep).onEach { node ->
            if (node.nodeKey == "plan") node.mappings.single { it.name == "prompt" }.expression = "Ask about the moon only."
        })
        val retried = nodes.findByWorkflowId(medium).associate { it.nodeKey to it.retryAttempts }

        researchModes.ensure(workspaceId)

        val drawn = nodes.findByWorkflowId(medium).sortedBy { it.positionX }
        assertCurrentShape(drawn)
        assertThat(drawn.associate { it.nodeKey to it.retryAttempts }).isEqualTo(retried)
        assertThat(nodes.findByWorkflowId(deep).single { it.nodeKey == "plan" }.mappings.map { it.name }).contains("model").doesNotContain("agent")

        // Opened in the editor, Plan is given an empty model box, which leaves the agent its own: still the seeded shape, laid out again.
        nodes.saveAll(nodes.findByWorkflowId(medium).onEach { node ->
            if (node.nodeKey == "plan") {
                node.mappings.add(NodeMapping(name = "model", expression = "", mode = MappingMode.VALUE))
                node.positionX += 37.0
            }
        })
        researchModes.ensure(workspaceId)
        assertThat(nodes.findByWorkflowId(medium).single { it.nodeKey == "plan" }.positionX).isEqualTo(drawn.single { it.nodeKey == "plan" }.positionX)

        // And it runs with the agents a research picks: somebody's own planner, with a model of its own.
        val endpoint = serve()
        val first = model(endpoint)
        val own = model(endpoint, "Own")
        // The seeded agents keep the first model; only the planner picked below thinks with "Own".
        agents.researchWith(workspaceId, first)
        val picked = agents.save(
            io.mszymanski.orknux.server.agent.Agent(
                workspaceId = workspaceId, name = "Tide planner $stamp", type = io.mszymanski.orknux.server.agent.AgentType.LLM,
                systemPrompt = PLAN, modelId = own,
            ),
        )
        val done = settled(
            start("""{ workspaceId: $workspaceId, question: "Why are there tides?", modeId: ${modeId("MEDIUM")}, plannerAgentId: ${picked.id}, language: FORMAL, outputFormat: DOCUMENT }"""),
        )
        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)
        assertThat(done.plannerAgentName).isEqualTo("Tide planner $stamp")
        assertThat(modelOf(asked(PLAN).single())).isEqualTo("research-$stamp-Own")
        assertThat(modelOf(asked(WRITE).single())).isNotEqualTo("research-$stamp-Own")
        graphQlTester.document("""query { research(id: ${done.id}) { plannerAgentName writerAgentName } }""").execute()
            .path("research.plannerAgentName").entity(String::class.java).isEqualTo("Tide planner $stamp")
            .path("research.writerAgentName").entity(String::class.java).isEqualTo("Research writer")
    }

    @Test
    fun `a research agent this product once seeded is left as it is, and given nothing`() {
        val endpoint = serve()
        val first = model(endpoint)
        // As a research started before #678 left it: the model named on the research, none on the agents.
        researches.save(
            Research(
                workspaceId = workspaceId, question = "Old", modeName = "Shallow", workflowId = modeWorkflow("SHALLOW"), workflowName = "Research: Shallow",
                modelId = first, modelName = "Researcher model", language = ResearchLanguage.FORMAL, outputFormat = ResearchOutputFormat.DOCUMENT,
                sources = "{}", startedBy = "alice", status = ResearchStatus.COMPLETED,
            ),
        )
        // As an earlier release seeded it, with no model: somebody's agent now, kept, and given none behind their back.
        val seeded = agents.findByWorkspaceIdAndName(workspaceId, ResearchModes.PLANNER.name)!!.also { it.modelId = null }
        agents.save(seeded)
        val before = agents.findAll().count { it.workspaceId == workspaceId }

        researchModes.ensure(workspaceId)

        assertThat(agents.findByIdOrNull(requireNotNull(seeded.id))!!.modelId).isNull()
        assertThat(agents.findAll().count { it.workspaceId == workspaceId }).isEqualTo(before)
    }

    @Test
    fun `a mode still the five steps it was before there was a review is given the review, and runs through it`() {
        val medium = modeWorkflow("MEDIUM")
        unreviewed(medium)

        researchModes.ensure(workspaceId)

        val drawn = nodes.findByWorkflowId(medium).sortedBy { it.positionX }
        assertThat(drawn.map { it.name }).containsExactly("Plan research", "Gather sources", "Deepen findings", "Write report", "Review asked for?", "Review report", "Save report")
        assertCurrentShape(drawn)
        assertThat(edges.findByWorkflowId(medium)).hasSize(7)
        // Still counted as seeded: laid out and retried like any other.
        assertThat(drawn.map { it.retryAttempts }).allMatch { it != null }

        val modelId = model(serve())
        val done = settled(
            start("""{ workspaceId: $workspaceId, question: "Why are there tides?", modeId: ${modeId("MEDIUM")}, language: FORMAL, outputFormat: DOCUMENT }"""),
        )
        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)
        assertThat(stepsOf(done)).containsExactly("plan", "gather", "followUp", "write", "reviewing", "review", "save")
    }

    /**
     * Shallow on the development server's workspace 9: the agent shape under
     * the former names, seeded before Gather read includeImages and before
     * there were retries or a review, with the boxes the editor writes into an
     * agent node when it is opened - and missed by every start-up upgrade,
     * because each looked for the shape the one before it left. Read for what
     * it does, it is drawn as today's shape with its own numbers, and a
     * research reviewed in it is started rather than refused.
     */
    @Test
    fun `a mode that missed every upgrade on the way is drawn as today's shape with its own numbers, and may be reviewed`() {
        val shallow = modeWorkflow("SHALLOW")
        missedEveryUpgrade(shallow)
        assertThat(researchModes.reviews(shallow)).isFalse()

        researchModes.ensure(workspaceId)

        val drawn = nodes.findByWorkflowId(shallow).sortedBy { it.positionX }
        assertThat(drawn.map { it.name })
            .containsExactly("Plan research", "Gather sources", "Deepen findings", "Write report", "Review asked for?", "Review report", "Save report")
        assertCurrentShape(drawn)
        assertThat(drawn.single { it.nodeKey == "gather" }.mappings.map { it.name }).contains(ResearchCapability.INCLUDE_IMAGES)
        assertThat(drawn.flatMap { node -> node.mappings.map { it.name } }).doesNotContain("systemPrompt", "skillIds")
        assertThat(knobsOf(drawn).values.fold(emptyMap<String, Int>()) { one, other -> one + other })
            .containsExactlyInAnyOrderEntriesOf(mapOf(SUB to 4, BREADTH to 0, DEPTH to 1, RESULTS to 3, SOURCES to 5, PAGES to 1))
        val attempts = tunables[Tunable.RESEARCH_STEP_RETRIES] + 1
        assertThat(drawn).allMatch { it.retryAttempts == attempts }
        assertThat(modesOf().single { it["kind"] == "SHALLOW" }["reviewable"]).isEqualTo(true)

        // Drawn once: the next start-up finds today's shape and leaves it be.
        val ids = drawn.map { it.id }
        researchModes.ensure(workspaceId)
        assertThat(nodes.findByWorkflowId(shallow).map { it.id }).containsExactlyInAnyOrderElementsOf(ids)

        val endpoint = serve()
        val modelId = model(endpoint)
        val reviewer = model(endpoint, "reviewer")
        val done = settled(
            start(
                """{ workspaceId: $workspaceId, question: "Why are there tides?", modeId: ${modeId("SHALLOW")}, 
                     language: FORMAL, outputFormat: DOCUMENT, review: true }""",
            ),
        )
        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)
        assertThat(asked(PLAN).single()).contains("exactly 4 search queries")
        assertThat(received).anyMatch { it.contains(opening(REVIEW)) }
        assertThat(runSteps.findByExecutionIdOrderByOrderAsc(requireNotNull(done.executionId)).single { it.nodeKey == "review" }.status)
            .isEqualTo(StepStatus.COMPLETED)
    }

    @Test
    fun `a mode somebody really changed is left as it is, and says it cannot be reviewed`() {
        val shallow = modeWorkflow("SHALLOW")
        val medium = modeWorkflow("MEDIUM")
        val deep = modeWorkflow("DEEP")
        listOf(shallow, medium, deep).forEach(::missedEveryUpgrade)
        // A step added after Save.
        nodes.save(
            WorkflowNode(
                workflowId = shallow, nodeKey = "announce", kind = NodeKind.AGENT, name = "Announce",
                agentId = agents.findByWorkspaceIdAndName(workspaceId, "Research writer")!!.id, positionX = 0.0, positionY = 600.0,
            ),
        )
        edges.save(io.mszymanski.orknux.server.workflow.WorkflowEdge(workflowId = shallow, sourceKey = "save", targetKey = "announce"))
        // Write fed straight from Gather, Follow up left hanging.
        edges.deleteAll(edges.findByWorkflowId(medium).filter { it.sourceKey == "followUp" })
        edges.save(io.mszymanski.orknux.server.workflow.WorkflowEdge(workflowId = medium, sourceKey = "gather", targetKey = "write"))
        // How many sources read off the run rather than written.
        nodes.saveAll(nodes.findByWorkflowId(deep).onEach { node ->
            if (node.nodeKey == "gather") node.mappings.single { it.name == SOURCES }.let { it.expression = "trigger.maxSources"; it.mode = MappingMode.REFERENCE }
        })
        fun shapeOf(workflowId: Long) = nodes.findByWorkflowId(workflowId).map { Triple(it.id, it.name, it.mappings.size) }.toSet() to
            edges.findByWorkflowId(workflowId).map { it.sourceKey to it.targetKey }.toSet()
        val before = listOf(shallow, medium, deep).associateWith(::shapeOf)

        researchModes.ensure(workspaceId)

        assertThat(listOf(shallow, medium, deep).associateWith(::shapeOf)).isEqualTo(before)
        assertThat(modesOf().map { it["reviewable"] }).containsOnly(false)
        val modelId = model(serve())
        refused(
            """{ workspaceId: $workspaceId, question: "Tides", modeId: ${modeId("MEDIUM")}, 
                 language: FORMAL, outputFormat: DOCUMENT, review: true }""",
            "ResearchModeNotReviewed",
        )
    }

    /**
     * A mode's workflow as workspace 9 had it: the agent shape under the
     * former names with no review, Gather not reading includeImages, no retry
     * policy, the editor's boxes on the two agent nodes, and numbers of the
     * mode's own - four queries and five sources.
     */
    private fun missedEveryUpgrade(workflowId: Long) {
        formerNames(workflowId)
        nodes.saveAll(
            nodes.findByWorkflowId(workflowId).onEach { node ->
                node.retryAttempts = null
                node.retryBackoffSeconds = null
                when (node.nodeKey) {
                    "gather" -> {
                        node.mappings.removeIf { it.name == ResearchCapability.INCLUDE_IMAGES }
                        node.mappings.single { it.name == SOURCES }.expression = "5"
                    }
                    "plan", "write" -> {
                        if (node.nodeKey == "plan") {
                            node.mappings.single { it.name == "prompt" }.let { it.expression = Regex("exactly \\d+ search").replace(it.expression, "exactly 4 search") }
                        }
                        val briefing = agents.findByIdOrNull(requireNotNull(node.agentId))!!.systemPrompt.orEmpty()
                        node.mappings.add(NodeMapping(name = "systemPrompt", expression = briefing, mode = MappingMode.VALUE))
                        node.mappings.add(NodeMapping(name = "skillIds", expression = "", mode = MappingMode.VALUE))
                    }
                }
            },
        )
    }

    /** The nodes a research's run went through, in order. */
    private fun stepsOf(research: Research): List<String> =
        runSteps.findByExecutionIdOrderByOrderAsc(requireNotNull(research.executionId)).map { it.nodeKey }

    /**
     * A mode's workflow as it was seeded before the steps were nodes: one node
     * calling `research_run` through an action called Research, with the
     * request read off the run by name and the numbers given.
     */
    private fun legacy(workflowId: Long, knobs: Map<String, Int>) {
        edges.deleteAll(edges.findByWorkflowId(workflowId))
        nodes.deleteAll(nodes.findByWorkflowId(workflowId))
        val function = requireNotNull(functions.findByScopeAndName(FunctionScope.EMBEDDED, ResearchCapability.FUNCTION_NAME))
        val action = actions.findByWorkspaceIdAndName(workspaceId, ResearchModes.ACTION_NAME) ?: actions.save(
            WorkflowAction(
                workspaceId = workspaceId, name = ResearchModes.ACTION_NAME, type = ActionType.EXECUTE,
                subtype = ActionSubtype.FUNCTION, functionId = function.id,
            ),
        )
        val request = listOf("research", "question", "language", "model", "websitesInclude", "websitesExclude", "documentsInclude", "documentsExclude")
        nodes.save(
            WorkflowNode(
                workflowId = workflowId, nodeKey = "research", kind = NodeKind.ACTION, name = "Research",
                actionId = action.id, outputName = "research", positionX = 0.0, positionY = 0.0,
                mappings = (
                    request.map { NodeMapping(name = it, expression = it, mode = MappingMode.REFERENCE) } +
                        knobs.map { (name, value) -> NodeMapping(name = name, expression = value.toString(), mode = MappingMode.VALUE) }
                    ).toMutableList(),
            ),
        )
    }

    private fun listed(showHidden: Boolean, search: String? = null): List<String> {
        val looking = search?.let { ", search: \"$it\"" }.orEmpty()
        return graphQlTester.document(
            """query { workspaceWorkflows(workspaceId: $workspaceId, size: 50, showHidden: $showHidden$looking) { content { name } } }""",
        ).execute().path("workspaceWorkflows.content[*].name").entityList(String::class.java).get()
    }

    private fun start(input: String): Long =
        graphQlTester.document("""mutation { startResearch(input: ${agents.withResearchAgents(workspaceId, input)}) { id status } }""")
            .execute().path("startResearch.id").entity(Long::class.java).get()

    private fun refused(input: String, code: String) {
        graphQlTester.document("""mutation { startResearch(input: ${agents.withResearchAgents(workspaceId, input)}) { id } }""")
            .execute().errors().expect { it.extensions?.get("code") == code }.verify()
    }

    /** The research once its run has ended; the run goes on on a thread of its own. */
    private fun settled(id: Long): Research {
        val until = System.currentTimeMillis() + 30_000
        while (true) {
            val held = requireNotNull(researches.findByIdOrNull(id))
            if (held.status != ResearchStatus.RUNNING || System.currentTimeMillis() > until) return held
            Thread.sleep(50)
        }
    }

    private fun document(name: String, text: String): Long {
        val saving = savedArtifacts.save(workspaceId, "Tester", name, "A note", text, base64 = false)
        return requireNotNull((saving as SavedArtifacts.Saving.Saved).artifact.id)
    }

    /** A PDF on the Artifacts page with a line of text and a picture of 200 by 160 on its one page. */
    private fun pdfDocument(name: String, text: String): Long {
        val bytes = org.apache.pdfbox.pdmodel.PDDocument().use { pdf ->
            val page = org.apache.pdfbox.pdmodel.PDPage()
            pdf.addPage(page)
            val image = org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(
                pdf, javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(picture("png", 200, 160))),
            )
            org.apache.pdfbox.pdmodel.PDPageContentStream(pdf, page).use { drawn ->
                drawn.beginText()
                drawn.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12f)
                drawn.newLineAtOffset(72f, 720f)
                drawn.showText(text)
                drawn.endText()
                drawn.drawImage(image, 72f, 480f)
            }
            java.io.ByteArrayOutputStream().also { pdf.save(it) }.toByteArray()
        }
        val saving = savedArtifacts.save(workspaceId, "Tester", name, "An atlas", java.util.Base64.getEncoder().encodeToString(bytes), base64 = true)
        return requireNotNull((saving as SavedArtifacts.Saving.Saved).artifact.id)
    }

    private fun model(endpoint: String, named: String? = null): Long {
        val suffix = named?.let { "-$it" }.orEmpty()
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub $stamp$suffix", endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "${named ?: "Researcher model"}", modelId: "research-$stamp$suffix", kind: CHAT })
               { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get().also { made ->
            // The research thinks with its agents' own models (#678): the newest model made is theirs, and a reviewer's is the reviewer's.
            if (named == "reviewer") {
                val reviewer = requireNotNull(agents.findByWorkspaceIdAndName(workspaceId, ResearchModes.REVIEWER.name))
                reviewer.modelId = made
                agents.save(reviewer)
            } else {
                agents.researchWith(workspaceId, made)
            }
        }
    }

    /** A model that answers each of the pipeline's four questions as a model would. */
    private fun serve(): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            received += body
            val answer = when {
                // Nothing said, as llama.cpp's Qwen answered research 3632's writer: a stop with no message.
                writerSilent && body.contains(opening(WRITE)) && retries.seen.none { it.nodeKey == "write" } -> ""
                body.contains(opening(REVIEW)) -> """{"verdict": "ACCEPT", "summary": "Sound.", "issues": []}"""
                body.contains(opening(PLAN)) -> """["tide causes", "tide timing", "tide records"]"""
                body.contains(opening(FOLLOW_UP)) -> """["moon distance and tides"]"""
                body.contains(opening(NOTES)) -> "The moon's gravity raises tides."
                // Told of pictures, a writer places the first - and one it made up, and one from the web, which must not be laid out.
                body.contains("(image:1)") ->
                    "# Tides\n\nThe moon pulls the sea [1].\n\n![The moon](image:1)\n\n![Made up](image:9)\n\n" +
                        "![Remote](https://evil.example/x.png)\n\n## Conclusion\n\nThe moon."
                else -> "# Tides\n\nThe moon pulls the sea [1].\n\n## Conclusion\n\nThe moon."
            }
            val content = answer.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
            val sent = """{"choices":[{"message":{"role":"assistant","content":"$content"}}],
                "usage":{"prompt_tokens":11,"completion_tokens":5}}""".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, sent.size.toLong())
            exchange.responseBody.use { it.write(sent) }
            exchange.close()
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}"
    }

    /**
     * The web, as a stub: three results for every query, on websites of the
     * query's own - and one on a website a research may be told to avoid.
     */
    class StubWeb : ResearchWeb {
        val searched = CopyOnWriteArrayList<String>()
        val read = CopyOnWriteArrayList<String>()

        /** What every search answers instead of results, as the real one does without a key. */
        @Volatile var refusing: String? = null

        /** Off, every search answers no results at all. */
        @Volatile var finding = true

        /** Off, as a workspace with no search key it can read. Issue #666. */
        @Volatile var ready = true

        override fun searchReady(workspaceId: Long) = ready

        override fun search(workspaceId: Long, query: String, limit: Int): Result<List<SearchHit>> {
            searched += query
            refusing?.let { return Result.failure(IllegalStateException(it)) }
            if (!finding) return Result.success(emptyList())
            val slug = query.substringBefore(" site:").replace(' ', '-')
            val kept = query.substringAfter(" site:", "").takeIf { it.isNotEmpty() }
            val hits = (1..3).map { at ->
                val host = kept ?: "$slug-$at.example.org"
                SearchHit("$query, part $at", "https://$host/$slug/$at", "What $slug $at says.")
            } + SearchHit("Gossip", "https://tabloid.example/$slug", "Whales.")
            return Result.success(listOf(hits.last()) + hits.dropLast(1))
        }

        override fun read(workspaceId: Long, url: String): PageRead {
            read += url
            // Every page shows a tide chart and one more picture, as a page's figures do.
            return PageRead.Read(
                null, "The moon pulls the sea at $url.",
                listOf(ImageHit("$PICTURES/chart.gif", "A tide chart", url), ImageHit("$PICTURES/extra.png", "Another picture", url)),
            )
        }

        val searchedImages = CopyOnWriteArrayList<String>()
        val fetchedImages = CopyOnWriteArrayList<String>()

        /**
         * The picture search: one picture worth keeping, then one of each
         * kind a research must pass over, then another worth keeping.
         */
        override fun searchImages(workspaceId: Long, query: String, limit: Int): Result<List<ImageHit>> {
            searchedImages += query
            return Result.success(
                listOf(
                    ImageHit("$PICTURES/moon.png", "The moon over the bay", "https://bay.example.org/moon"),
                    ImageHit("$PICTURES/page.html", "A page, not a picture", null),
                    ImageHit("$PICTURES/fake.png", "Text calling itself a PNG", null),
                    ImageHit("$PICTURES/huge.jpg", "A very large photograph", null),
                    ImageHit("$PICTURES/icon.png", "An icon", null),
                    ImageHit("https://tabloid.example/whale.png", "A whale", "https://tabloid.example/whales"),
                    ImageHit("$PICTURES/gone.png", "Not there any more", null),
                    ImageHit("$PICTURES/diver.jpg", "A diver at low tide", null),
                ),
            )
        }

        override fun image(workspaceId: Long, url: String): ImageFetched {
            fetchedImages += url
            return when (url.substringAfterLast('/')) {
                "moon.png" -> ImageFetched.Fetched(picture("png", 200, 150), "image/png")
                "diver.jpg" -> ImageFetched.Fetched(picture("jpg", 400, 300), "image/jpeg; charset=binary")
                "chart.gif" -> ImageFetched.Fetched(picture("gif", 300, 200), "image/gif")
                "extra.png" -> ImageFetched.Fetched(picture("png", 300, 300), "image/png")
                "page.html" -> ImageFetched.Fetched("<html>hello</html>".toByteArray(), "text/html")
                "fake.png" -> ImageFetched.Fetched("<html>not a picture</html>".toByteArray(), "image/png")
                // A JPEG's first bytes, and a megabyte and a half after them.
                "huge.jpg" -> ImageFetched.Fetched(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(1_572_861), "image/jpeg")
                "icon.png" -> ImageFetched.Fetched(picture("png", 16, 16), "image/png")
                else -> ImageFetched.Unfetched("the address answered 404")
            }
        }
    }

    /** Every step a run parked for another attempt, as the engine announced it. */
    class RetryWatch {
        val seen = CopyOnWriteArrayList<StepRetrying>()

        @EventListener
        fun on(event: StepRetrying) {
            seen += event
        }
    }

    @TestConfiguration
    class Web {
        @Bean
        @Primary
        fun stubWeb(): StubWeb = StubWeb()

        @Bean
        fun retryWatch(): RetryWatch = RetryWatch()
    }

    private companion object {
        const val SUB = ResearchCapability.SUB_QUESTIONS
        const val BREADTH = ResearchCapability.BREADTH
        const val DEPTH = ResearchCapability.DEPTH
        const val RESULTS = ResearchCapability.RESULTS_PER_QUERY
        const val SOURCES = ResearchCapability.MAX_SOURCES
        const val PAGES = ResearchCapability.MAX_PAGES_PER_SOURCE
        const val PLAN = ResearchPipeline.PLAN
        const val NOTES = ResearchPipeline.NOTES
        const val FOLLOW_UP = ResearchPipeline.FOLLOW_UP
        const val WRITE = ResearchPipeline.WRITE
        const val REVIEW = ResearchPipeline.REVIEW
        const val PICTURES = "https://pictures.example.org"

        /** A picture of [width] by [height] in one colour, as [format] writes it. */
        fun picture(format: String, width: Int, height: Int): ByteArray {
            val drawn = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
            drawn.createGraphics().apply { color = java.awt.Color(30, 90, 160); fillRect(0, 0, width, height); dispose() }
            return java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(drawn, format, it) }.toByteArray()
        }

        /** The pictures a PDF lays out, counted wherever on its pages they sit. */
        fun picturesIn(pdf: ByteArray): Int = org.apache.pdfbox.Loader.loadPDF(pdf).use { document ->
            fun count(resources: org.apache.pdfbox.pdmodel.PDResources?): Int = resources?.xObjectNames?.sumOf { name ->
                when (val drawn = resources.getXObject(name)) {
                    is org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject -> 1
                    is org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject -> count(drawn.resources)
                    else -> 0
                }
            } ?: 0
            document.pages.sumOf { count(it.resources) }
        }
    }
}
