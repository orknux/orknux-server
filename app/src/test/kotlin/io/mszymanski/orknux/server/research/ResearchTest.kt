package io.mszymanski.orknux.server.research

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.server.action.WorkflowActionRepository
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.action.ActionSubtype
import io.mszymanski.orknux.server.action.ActionType
import io.mszymanski.orknux.server.action.FunctionScope
import io.mszymanski.orknux.server.action.WorkflowAction
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
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
 * five steps - Plan, Gather, Follow up, Write, Save - whose nodes hold the
 * knobs that make the mode; one still drawn as the single `research_run` node
 * it once was is redrawn, and one somebody edited is left alone. A research
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
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer
    private val received = CopyOnWriteArrayList<String>()
    private val stamp = System.nanoTime().toString().takeLast(9)

    @BeforeEach
    fun workspace() {
        received.clear()
        web.searched.clear()
        web.read.clear()
        web.refusing = null
        web.finding = true
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
            assertThat(drawn.map { it.name }).containsExactly("Plan", "Gather", "Follow up", "Write", "Save")
            assertThat(drawn).allMatch { it.kind == NodeKind.ACTION }
            // Each step calls its own action, which calls its own function.
            drawn.forEach { node ->
                val action = actions.findByIdOrNull(requireNotNull(node.actionId))!!
                assertThat(action.name).isEqualTo("Research: ${node.name}")
            }
            // A chain, left to right.
            assertThat(edges.findByWorkflowId(id).map { it.sourceKey to it.targetKey }).containsExactlyInAnyOrder(
                "plan" to "gather", "gather" to "followUp", "followUp" to "write", "write" to "save",
            )
            // The request is read off what started the run; what a step before found, off the run.
            drawn.forEach { node ->
                node.mappings.filter { it.name in ResearchCapability.REQUEST }.forEach {
                    assertThat(it.mode).isEqualTo(MappingMode.REFERENCE)
                    assertThat(it.expression).isEqualTo("trigger.${it.name}")
                }
            }
            assertThat(drawn[1].mappings.single { it.name == "queries" }.expression).isEqualTo("plan.queries")
            assertThat(drawn[4].mappings.single { it.name == "report" }.expression).isEqualTo("research.report")
            // And it is a graph that holds together, with nothing to warn about.
            assertThat(validator.problems(drawn, edges.findByWorkflowId(id), workspaceId = workspaceId)).isEmpty()

            // Every knob on the step it is about, and nowhere else.
            drawn.associate { node ->
                node.name to node.mappings.filter { it.mode == MappingMode.VALUE }.associate { it.name to it.expression.toInt() }
            }
        }
        assertThat(knobs.map { it.mapValues { (_, values) -> values.keys } }).allMatch {
            it == mapOf(
                "Plan" to setOf(ResearchCapability.SUB_QUESTIONS),
                "Gather" to setOf(ResearchCapability.RESULTS_PER_QUERY, ResearchCapability.MAX_SOURCES, ResearchCapability.MAX_PAGES_PER_SOURCE),
                "Follow up" to setOf(ResearchCapability.BREADTH, ResearchCapability.DEPTH),
                "Write" to emptySet(),
                "Save" to emptySet(),
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
    }

    /* ------------------------------------------------------------ upgrade --- */

    @Test
    fun `a mode workflow still as it was first seeded, one node, is redrawn as the five steps with the same numbers`() {
        val medium = modeWorkflow("MEDIUM")
        legacy(medium, mapOf(SUB to 5, BREADTH to 2, DEPTH to 2, RESULTS to 4, SOURCES to 20, PAGES to 2))

        researchModes.ensure(workspaceId)

        val drawn = nodes.findByWorkflowId(medium).sortedBy { it.positionX }
        assertThat(drawn.map { it.name }).containsExactly("Plan", "Gather", "Follow up", "Write", "Save")
        assertThat(drawn.flatMap { it.mappings }.filter { it.mode == MappingMode.VALUE }.associate { it.name to it.expression })
            .containsExactlyInAnyOrderEntriesOf(
                mapOf(SUB to "5", BREADTH to "2", DEPTH to "2", RESULTS to "4", SOURCES to "20", PAGES to "2"),
            )
        assertThat(edges.findByWorkflowId(medium)).hasSize(4)

        // And it runs as the steps.
        val modelId = model(serve())
        val done = settled(
            start("""{ workspaceId: $workspaceId, question: "Why are there tides?", modeId: ${modeId("MEDIUM")}, modelId: $modelId, language: FORMAL, outputFormat: DOCUMENT }"""),
        )
        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)
        assertThat(stepsOf(done)).containsExactly("plan", "gather", "followUp", "write", "save")
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
            start("""{ workspaceId: $workspaceId, question: "Why are there tides?", modeId: ${modeId("SHALLOW")}, modelId: $modelId, language: FORMAL, outputFormat: DOCUMENT }"""),
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
            .containsExactlyInAnyOrder("Research: Plan", "Research: Gather", "Research: Follow up", "Research: Write", "Research: Save")
        assertThat(modesOf()).hasSize(3)
    }

    /* ----------------------------------------------- plan, gather, write --- */

    @Test
    fun `a shallow research plans, searches each part, reads and notes what it found, and writes a cited report`() {
        val modelId = model(serve())

        val id = start(
            """{ workspaceId: $workspaceId, question: "Why are there tides?", modeId: ${modeId("SHALLOW")}, modelId: $modelId,
                 language: FORMAL, outputFormat: DOCUMENT, websitesExclude: ["tabloid.example"] }""",
        )
        val done = settled(id)
        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)

        // Plan: the model's three queries, which are what was searched - and only those, at depth 1.
        assertThat(asked(PLAN)).hasSize(1)
        assertThat(asked(PLAN).single()).contains("Why are there tides?").contains("exactly 3 search queries")
        assertThat(web.searched).containsExactly("tide causes", "tide timing", "tide records")
        assertThat(asked(FOLLOW_UP)).isEmpty()

        // Gather: every page read is a page that was found, never one on a website it was told to avoid.
        assertThat(web.read).isNotEmpty().noneMatch { it.contains("tabloid.example") }
        assertThat(asked(NOTES)).hasSize(web.read.size)
        assertThat(asked(NOTES).first()).contains("Query: tide causes").contains("The moon pulls the sea at")

        // Write: in the register asked for, from the numbered notes.
        val write = asked(WRITE).single()
        assertThat(write).contains("formal register").contains("[1] ").contains("The moon's gravity raises tides.")

        val view = graphQlTester.document(
            """query { research(id: $id) { result progress { step subQuestions searched level sourcesRead }
                 sources { number title url kind notes } fileName } }""",
        ).execute()
        view.path("research.progress.subQuestions").entityList(String::class.java).containsExactly("tide causes", "tide timing", "tide records")
        view.path("research.progress.step").entity(String::class.java).isEqualTo("Finished")
        // Five steps ran, one node each, and the page was told which one it was in.
        assertThat(stepsOf(done)).containsExactly("plan", "gather", "followUp", "write", "save")
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
    }

    @Test
    fun `a deeper mode asks follow-up questions from what it learnt and searches those too`() {
        val modelId = model(serve())

        val done = settled(
            start("""{ workspaceId: $workspaceId, question: "Why are there tides?", modeId: ${modeId("MEDIUM")}, modelId: $modelId, language: ACADEMIC, outputFormat: DOCUMENT }"""),
        )
        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)

        // Medium: five queries planned, then follow-ups one level down.
        assertThat(asked(PLAN).single()).contains("exactly 5 search queries")
        assertThat(asked(FOLLOW_UP)).isNotEmpty()
        assertThat(asked(FOLLOW_UP).first()).contains("What was learnt:").contains("exactly 2 follow-up search queries")
        assertThat(web.searched).contains("moon distance and tides")
        assertThat(researches.findByIdOrNull(requireNotNull(done.id))!!.progress).contains("\"level\":2")
        assertThat(asked(WRITE).single()).contains("academic register")
    }

    @Test
    fun `websites to keep to scope the search and what is read`() {
        val modelId = model(serve())
        val done = settled(
            start(
                """{ workspaceId: $workspaceId, question: "Why are there tides?", modeId: ${modeId("SHALLOW")}, modelId: $modelId,
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
        val done = settled(start("""{ workspaceId: $workspaceId, question: "Tides", modeId: $custom, modelId: $modelId, language: FORMAL, outputFormat: DOCUMENT }"""))
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
                     modelId: $modelId, language: COLLOQUIAL, outputFormat: DOCUMENT,
                     documentsInclude: [$notes], documentsExclude: [$rumours] }""",
            ),
        )
        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)
        assertThat(asked(NOTES).first()).contains("Source: notes.md").contains("every fortnight")
        assertThat(asked(WRITE).single()).contains("Do not use: rumours.txt").contains("colloquial register").contains("/api/artifacts/$notes")
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
                """{"question":"Why are there tides?","mode":"medium","model":"Researcher model",
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
        assertThat(orknux.run(scope, "orknux_start_research", """{"question":"Tides","webSearch":false}"""))
            .contains("Without web search, pick documents or websites to read")

        // Left out, the model is the workspace's chat model: a research always has one.
        val unnamed = mapper.readTree(
            orknux.run(scope, "orknux_start_research", """{"question":"Tides","sitesInclude":["one.example.org"],"webSearch":false}"""),
        )
        val second = settled(unnamed.path("started").asLong())
        assertThat(second.modelId).isEqualTo(modelId)
        assertThat(second.useWebSearch).isFalse()
    }

    @Test
    fun `a research asked for as a PDF is laid out as one`() {
        val modelId = model(serve())
        val done = settled(
            start("""{ workspaceId: $workspaceId, question: "Tides", modeId: ${modeId("SHALLOW")}, modelId: $modelId, language: FORMAL, outputFormat: PDF }"""),
        )
        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)
        val file = saved.findByIdOrNull(requireNotNull(done.artifactId))!!
        assertThat(file.name).isEqualTo("tides.pdf")
        assertThat(store.open(file.location).use { it.readNBytes(4) }).isEqualTo("%PDF".toByteArray())
    }

    /** No step of a mode's workflow holds a model, so a research without one is refused rather than run to fail. */
    @Test
    fun `a research is not started without a model`() {
        refused("""{ workspaceId: $workspaceId, question: "Tides", modeId: ${modeId("SHALLOW")}, language: FORMAL, outputFormat: DOCUMENT }""", "ResearchModelMissing")
        assertThat(researches.findAll().filter { it.workspaceId == workspaceId }).isEmpty()
    }

    /* --------------------------------------------------------- web search --- */

    @Test
    fun `without web search a research reads only its documents and the websites it keeps to, and searches nothing`() {
        val notes = document("notes.md", "The harbour master logs a spring tide every fortnight.")
        val modelId = model(serve())

        val id = start(
            """{ workspaceId: $workspaceId, question: "How often are spring tides?", modeId: ${modeId("MEDIUM")}, modelId: $modelId,
                 language: FORMAL, outputFormat: DOCUMENT, useWebSearch: false, documentsInclude: [$notes],
                 websitesInclude: ["harbour.example.org/tides", "https://tabloid.example/gossip"], websitesExclude: ["tabloid.example"] }""",
        )
        val done = settled(id)
        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)
        assertThat(done.useWebSearch).isFalse()

        // No search at all, not even a follow-up one at Medium's depth; the website kept to is read where it was typed.
        assertThat(web.searched).isEmpty()
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
            """{ workspaceId: $workspaceId, question: "Tides", modeId: ${modeId("SHALLOW")}, modelId: $modelId, language: FORMAL,
                 outputFormat: PDF, useWebSearch: false, websitesExclude: ["tabloid.example"] }""",
            "ResearchNothingToRead",
        )
        assertThat(researches.findAll().filter { it.workspaceId == workspaceId }).isEmpty()
    }

    @Test
    fun `the list is newest first and a research can be taken off it`() {
        val modelId = model(serve())
        val first = settled(start("""{ workspaceId: $workspaceId, question: "First", modeId: ${modeId("SHALLOW")}, modelId: $modelId, language: FORMAL, outputFormat: DOCUMENT }"""))
        val second = settled(start("""{ workspaceId: $workspaceId, question: "Second", modeId: ${modeId("SHALLOW")}, modelId: $modelId, language: FORMAL, outputFormat: DOCUMENT }"""))

        graphQlTester.document("""query { researches(workspaceId: $workspaceId) { content { question status } totalElements } }""").execute()
            .path("researches.content[*].question").entityList(String::class.java).containsExactly("Second", "First")
            .path("researches.totalElements").entity(Int::class.java).isEqualTo(2)

        graphQlTester.document("""mutation { deleteResearch(id: ${first.id}) }""").execute()
            .path("deleteResearch").entity(Boolean::class.java).isEqualTo(true)
        assertThat(researches.findByIdOrNull(requireNotNull(first.id))).isNull()
        assertThat(researches.findByIdOrNull(requireNotNull(second.id))).isNotNull()
        assertThat(audit.findAll().map { it.message }).anyMatch { it.startsWith("Research started in Shallow mode: First") }
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
            start("""{ workspaceId: $workspaceId, question: "GPU prices in 2027", modeId: ${modeId("SHALLOW")}, modelId: $modelId, language: FORMAL, outputFormat: PDF }"""),
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
        assertThat(runSteps.findByExecutionIdOrderByOrderAsc(requireNotNull(done.executionId)).single { it.nodeKey == "gather" }.status)
            .isEqualTo(io.mszymanski.orknux.workflow.execution.StepStatus.FAILED)
    }

    @Test
    fun `a research whose searches find nothing to read writes no report and says how many it searched`() {
        web.finding = false
        val modelId = model(serve())

        val done = settled(
            start("""{ workspaceId: $workspaceId, question: "GPU prices in 2027", modeId: ${modeId("SHALLOW")}, modelId: $modelId, language: FORMAL, outputFormat: PDF }"""),
        )
        assertThat(done.status).isEqualTo(ResearchStatus.FAILED)
        assertThat(done.error).contains("research_write has no source to write a report from: nothing was read in 3 searches.")
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
        val action = requireNotNull(actions.findByWorkspaceIdAndName(workspaceId, "Research: Gather"))
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
        refused("""{ workspaceId: $workspaceId, question: "Tides", modeId: $shallow, modelId: 999999999, language: FORMAL, outputFormat: PDF }""", "ResearchModelUnusable")
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
        graphQlTester.document("""query { researchModes(workspaceId: $workspaceId) { id kind name workflowId workflowName } }""")
            .execute().path("researchModes").entity(List::class.java).get() as List<Map<String, Any?>>

    private fun modeId(kind: String): Long = (modesOf().first { it["kind"] == kind }["id"] as String).toLong()

    private fun modeWorkflow(kind: String): Long = (modesOf().first { it["kind"] == kind }["workflowId"] as String).toLong()

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
        graphQlTester.document("""mutation { startResearch(input: $input) { id status } }""")
            .execute().path("startResearch.id").entity(Long::class.java).get()

    private fun refused(input: String, code: String) {
        graphQlTester.document("""mutation { startResearch(input: $input) { id } }""")
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

    private fun model(endpoint: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub $stamp", endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Researcher model", modelId: "research-$stamp", kind: CHAT })
               { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }

    /** A model that answers each of the pipeline's four questions as a model would. */
    private fun serve(): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            received += body
            val answer = when {
                body.contains(opening(PLAN)) -> """["tide causes", "tide timing", "tide records"]"""
                body.contains(opening(FOLLOW_UP)) -> """["moon distance and tides"]"""
                body.contains(opening(NOTES)) -> "The moon's gravity raises tides."
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
            return PageRead.Read(null, "The moon pulls the sea at $url.")
        }
    }

    @TestConfiguration
    class Web {
        @Bean
        @Primary
        fun stubWeb(): StubWeb = StubWeb()
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
    }
}
