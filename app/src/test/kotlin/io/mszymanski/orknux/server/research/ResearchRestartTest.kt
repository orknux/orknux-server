package io.mszymanski.orknux.server.research

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.server.action.ActionNodeRunner
import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.mcp.OrknuxTools
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tunables
import io.mszymanski.orknux.server.workflow.WorkflowNodeRepository
import io.mszymanski.orknux.server.workspace.WorkspaceAPI
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.StepStatus
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
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A research restarted from one of its steps, followed onto a rerun of its run,
 * shown while it runs, and timed from the ones before it.
 *
 * Research #27 on the development server failed at Gather, "interrupted by a
 * restart", having planned three sub-questions and read three sources. Starting
 * it again meant a new research and the plan asked for again; re-running its
 * run from Gather on the Runs page did the work while the research's page went
 * on showing the old failure. So a research restarts as itself, from the step
 * it stopped at by default, with what the steps before it found kept - and a
 * rerun of its run from any door moves it onto the new run.
 *
 * The model is a stub that answers by the line each question opens with, and
 * writes the report as frames it can be told to stop half-way through; the web
 * is a stub that can be told to break, or to hold a page until let go.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ResearchRestartTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val researches: ResearchRepository,
    @Autowired val agents: io.mszymanski.orknux.server.agent.AgentRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val runSteps: ExecutionStepRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val workspaceApi: WorkspaceAPI,
    @Autowired val web: HeldWeb,
    @Autowired val orknux: OrknuxTools,
    @Autowired val actionRunner: ActionNodeRunner,
    @Autowired val tunables: Tunables,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer
    private val received = CopyOnWriteArrayList<String>()
    private val stamp = System.nanoTime().toString().takeLast(9)

    /** Set, the second source's notes are not answered until it is let go. */
    @Volatile private var notesHeld: CountDownLatch? = null
    private val notesAsked = AtomicInteger()

    /** Set, the writer's frames stop after the first few until it is let go. */
    @Volatile private var writingHeld: CountDownLatch? = null

    @BeforeEach
    fun workspace() {
        received.clear()
        web.read.clear()
        web.breaking = null
        notesHeld = null
        writingHeld = null
        notesAsked.set(0)
        workspaceId = requireNotNull(workspaceApi.create("Research restarts $stamp", null).id)
        // A failure is a failure here, not a wait for another go.
        nodes.saveAll(nodes.findByWorkflowId(modeWorkflow("SHALLOW")).onEach { it.retryAttempts = null; it.retryBackoffSeconds = null })
    }

    @AfterEach
    fun stop() {
        notesHeld?.countDown()
        writingHeld?.countDown()
        if (::server.isInitialized) server.stop(0)
    }

    /* ----------------------------------------------------------- restart --- */

    @Test
    fun `a research that failed at Gather restarts from Gather, the plan carried over and not asked again, and completes`() {
        val modelId = model(serve())
        web.breaking = "the connection was reset"
        val id = start(modelId)
        val failed = settled(id)
        assertThat(failed.status).isEqualTo(ResearchStatus.FAILED)
        val firstRun = requireNotNull(failed.executionId)
        assertThat(asked(ResearchPipeline.PLAN)).hasSize(1)
        assertThat(failed.progress).contains("tide causes")

        // A step the run never reached cannot be started from, and the run's own reason is quoted.
        graphQlTester.document("""mutation { restartResearch(id: $id, fromStage: SAVE) { id } }""").execute().errors()
            .expect { it.extensions?.get("code") == "ResearchRestartRefused" && it.message!!.contains("save never ran") }
            .verify()

        web.breaking = null
        val restarted = graphQlTester.document(
            """mutation { restartResearch(id: $id) { id status error executionId earlierExecutionIds progress { stage subQuestions } } }""",
        ).execute()
        restarted.path("restartResearch.status").entity(String::class.java).isEqualTo("RUNNING")
        restarted.path("restartResearch.earlierExecutionIds").entityList(Long::class.java).containsExactly(firstRun)
        restarted.path("restartResearch.progress.subQuestions").entityList(String::class.java)
            .containsExactly("tide causes", "tide timing", "tide records")

        val done = settled(id)
        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)
        assertThat(done.error).isNull()
        assertThat(done.executionId).isNotEqualTo(firstRun)
        assertThat(done.result).startsWith("# Tides").contains("## Sources")
        // Planned once, on the first run; the second began at Gather with that plan.
        assertThat(asked(ResearchPipeline.PLAN)).hasSize(1)
        val steps = runSteps.findByExecutionIdOrderByOrderAsc(requireNotNull(done.executionId)).associateBy { it.nodeKey }
        assertThat(steps.getValue("plan").carriedOver).isTrue()
        assertThat(steps.getValue("gather").carriedOver).isFalse()
        assertThat(steps.getValue("gather").status).isEqualTo(StepStatus.COMPLETED)
        assertThat(web.read).isNotEmpty()
        assertThat(audit.findAll().map { it.message }).anyMatch { it.startsWith("Research restarted from Gather sources: Why are there tides?") }
    }

    @Test
    fun `a research restarted from Write keeps the notes and sources it gathered and reads nothing again`() {
        val modelId = model(serve())
        val first = settled(start(modelId))
        assertThat(first.status).describedAs(first.error).isEqualTo(ResearchStatus.COMPLETED)
        val read = web.read.toList()
        val notes = asked(ResearchPipeline.NOTES).size
        val sources = sourcesOf(requireNotNull(first.id))
        assertThat(sources).isNotEmpty()

        graphQlTester.document("""mutation { restartResearch(id: ${first.id}, fromStage: WRITE) { status sources { url } } }""").execute()
            .path("restartResearch.status").entity(String::class.java).isEqualTo("RUNNING")
            // Kept from the start: what Follow up handed on is what the new run carries.
            .path("restartResearch.sources[*].url").entityList(String::class.java).containsExactly(*sources.toTypedArray())

        val done = settled(requireNotNull(first.id))
        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)
        assertThat(web.read).isEqualTo(read)
        assertThat(asked(ResearchPipeline.NOTES)).hasSize(notes)
        assertThat(asked(ResearchPipeline.WRITE)).hasSize(2)
        assertThat(sourcesOf(requireNotNull(first.id))).isEqualTo(sources)
        assertThat(done.result).startsWith("# Tides")
        val steps = runSteps.findByExecutionIdOrderByOrderAsc(requireNotNull(done.executionId)).associateBy { it.nodeKey }
        assertThat(listOf("plan", "gather", "followUp").map { steps.getValue(it).carriedOver }).containsOnly(true)
        assertThat(steps.getValue("write").carriedOver).isFalse()
    }

    @Test
    fun `a research still running is not restarted`() {
        val modelId = model(serve())
        val held = CountDownLatch(1).also { notesHeld = it }
        val id = start(modelId)
        awaitNotes(2)

        graphQlTester.document("""mutation { restartResearch(id: $id) { id } }""").execute().errors()
            .expect { it.extensions?.get("code") == "ResearchStillRunning" }
            .verify()

        held.countDown()
        assertThat(settled(id).status).isEqualTo(ResearchStatus.COMPLETED)
    }

    @Test
    fun `a rerun of the run from its failed step, on the Runs page, carries the research with it to the end`() {
        val modelId = model(serve())
        web.breaking = "the connection was reset"
        val id = start(modelId)
        val failed = settled(id)
        assertThat(failed.status).isEqualTo(ResearchStatus.FAILED)
        web.breaking = null

        // The run page's own door, which knows nothing of research.
        val rerun = graphQlTester.document("""mutation { rerunExecutionStep(id: ${failed.executionId}, nodeKey: "gather") { id } }""")
            .execute().path("rerunExecutionStep.id").entity(Long::class.java).get()

        val done = settled(id)
        assertThat(done.executionId).isEqualTo(rerun)
        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)
        assertThat(done.error).isNull()
        assertThat(done.result).startsWith("# Tides")
        assertThat(asked(ResearchPipeline.PLAN)).hasSize(1)
        graphQlTester.document("""query { research(id: $id) { earlierExecutionIds } }""").execute()
            .path("research.earlierExecutionIds").entityList(Long::class.java).containsExactly(requireNotNull(failed.executionId))
    }

    @Test
    fun `an agent restarts a research through orknux_restart_research`() {
        val modelId = model(serve())
        val scope = OrknuxScope(workspaceId = workspaceId, mayWrite = true)
        assertThat(orknux.specs(scope).map { it.name }).contains("orknux_restart_research")
        assertThat(orknux.specs(scope.copy(mayWrite = false)).map { it.name }).doesNotContain("orknux_restart_research")

        web.breaking = "the connection was reset"
        val id = start(modelId)
        assertThat(settled(id).status).isEqualTo(ResearchStatus.FAILED)
        web.breaking = null

        assertThat(orknux.run(scope.copy(mayWrite = false), "orknux_restart_research", """{"id":$id}""")).contains("may read")
        assertThat(orknux.run(scope, "orknux_restart_research", """{"id":$id,"fromStep":"galaxy"}""")).contains("A step is one of")

        val answered = mapper.readTree(orknux.run(scope, "orknux_restart_research", """{"id":$id}"""))
        assertThat(answered.path("error").isMissingNode).describedAs(answered.toString()).isTrue()
        assertThat(answered.path("restarted").asLong()).isEqualTo(id)
        assertThat(answered.path("url").stringValue()).endsWith("/workspace/$workspaceId/explore/research/$id")

        assertThat(settled(id).status).isEqualTo(ResearchStatus.COMPLETED)
        val read = mapper.readTree(orknux.run(scope, "orknux_research", """{"id":$id}"""))
        assertThat(read.path("status").stringValue()).isEqualTo("COMPLETED")
        assertThat(read.path("earlierRuns").size()).isEqualTo(1)
        // And refused, in the page's words, while it runs.
        val held = CountDownLatch(1).also { notesHeld = it }
        notesAsked.set(0)
        val again = start(modelId)
        awaitNotes(2)
        assertThat(orknux.run(scope, "orknux_restart_research", """{"id":$again}""")).contains("is still running")
        held.countDown()
        settled(again)
    }

    /* ------------------------------------------------------- resume ------ */

    @Test
    fun `a research's own step cut short by a restart is run again, Save and somebody's function are not`() {
        val modelId = model(serve())
        val done = settled(start(modelId))
        val steps = runSteps.findByExecutionIdOrderByOrderAsc(requireNotNull(done.executionId)).associateBy { it.nodeKey }
        // As the sweep finds them: the dead attempt counted.
        assertThat(actionRunner.asksAgainAfterRestart(steps.getValue("gather"))).isTrue()
        assertThat(actionRunner.asksAgainAfterRestart(steps.getValue("followUp"))).isTrue()
        assertThat(actionRunner.asksAgainAfterRestart(steps.getValue("save"))).isFalse()

        tunables.set(Tunable.RESEARCH_RESUME_AFTER_RESTART, 0, "alice")
        try {
            assertThat(actionRunner.asksAgainAfterRestart(steps.getValue("gather"))).isFalse()
        } finally {
            tunables.set(Tunable.RESEARCH_RESUME_AFTER_RESTART, null, "alice")
        }
    }

    /* --------------------------------------------------------- live ------ */

    @Test
    fun `each source is on the research as it is read, and the one being read before its notes are written`() {
        val modelId = model(serve())
        val held = CountDownLatch(1).also { notesHeld = it }
        val id = start(modelId)
        awaitNotes(2)

        // Gather is part-way: one source noted, the second read and waiting on its notes.
        val view = graphQlTester.document(
            """query { research(id: $id) { status sources { number title notes } progress { stage sourcesRead reading { title url kind } } } }""",
        ).execute()
        view.path("research.status").entity(String::class.java).isEqualTo("RUNNING")
        view.path("research.progress.stage").entity(String::class.java).isEqualTo("GATHER")
        view.path("research.sources[*].number").entityList(Int::class.java).containsExactly(1)
        view.path("research.sources[0].notes").entity(String::class.java).isEqualTo("The moon's gravity raises tides.")
        view.path("research.progress.sourcesRead").entity(Int::class.java).isEqualTo(1)
        view.path("research.progress.reading.url").entity(String::class.java).isEqualTo(web.read[1])
        view.path("research.progress.reading.kind").entity(String::class.java).isEqualTo("WEB")

        held.countDown()
        val done = settled(id)
        assertThat(done.status).isEqualTo(ResearchStatus.COMPLETED)
        graphQlTester.document("""query { research(id: $id) { progress { reading { url } } } }""").execute()
            .path("research.progress.reading").valueIsNull()
    }

    @Test
    fun `the report is on the research while it is being written, and gives way to the finished one`() {
        val modelId = model(serve())
        val held = CountDownLatch(1).also { writingHeld = it }
        val id = start(modelId)

        // Until the draft reaches where the writer is held: it arrives in pieces, and the first read can land
        // between them - CI's SQLite run read "# Tides" before "The moon" (v1.2.1's build).
        var draft: String? = null
        val until = System.currentTimeMillis() + 30_000
        val held0 = "# Tides\n\nThe moon"
        while (draft != held0 && System.currentTimeMillis() < until) {
            draft = graphQlTester.document("""query { research(id: $id) { draft } }""").execute()
                .path("research").entity(Map::class.java).get()["draft"] as String?
            if (draft != held0) Thread.sleep(50)
        }
        assertThat(draft).isEqualTo("# Tides\n\nThe moon")
        graphQlTester.document("""query { research(id: $id) { status result progress { stage } } }""").execute()
            .path("research.status").entity(String::class.java).isEqualTo("RUNNING")
            .path("research.result").valueIsNull()
            .path("research.progress.stage").entity(String::class.java).isEqualTo("WRITE")

        held.countDown()
        val done = settled(id)
        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)
        assertThat(done.result).startsWith("# Tides\n\nThe moon pulls the sea [1].")
        graphQlTester.document("""query { research(id: $id) { draft } }""").execute().path("research.draft").valueIsNull()
    }

    /* ----------------------------------------------------- estimate ------ */

    @Test
    fun `a running research says how long it has left from the ones before it, and nothing with too few`() {
        val modelId = model(serve())
        val finished = (1..2).map { settled(start(modelId)) }
        assertThat(finished).allMatch { it.status == ResearchStatus.COMPLETED }

        // Two finished in this mode: not enough to measure from.
        var held = CountDownLatch(1).also { notesHeld = it }
        notesAsked.set(0)
        val early = start(modelId)
        awaitNotes(2)
        graphQlTester.document("""query { research(id: $early) { estimatedSecondsLeft } }""").execute()
            .path("research.estimatedSecondsLeft").valueIsNull()
        held.countDown()
        assertThat(settled(early).status).isEqualTo(ResearchStatus.COMPLETED)

        // Three now, each made to have taken ten seconds a step: Gather, then three more steps, are about forty seconds.
        listOf(finished[0], finished[1], researches.findByIdOrNull(early)!!).forEach { research ->
            val base = OffsetDateTime.now().minusHours(1)
            runSteps.saveAll(
                runSteps.findByExecutionIdOrderByOrderAsc(requireNotNull(research.executionId)).onEachIndexed { at, step ->
                    step.startedAt = base.plusSeconds(10L * at)
                    step.finishedAt = base.plusSeconds(10L * at + 10)
                },
            )
        }
        held = CountDownLatch(1).also { notesHeld = it }
        notesAsked.set(0)
        val running = start(modelId)
        awaitNotes(2)
        val left = graphQlTester.document("""query { research(id: $running) { estimatedSecondsLeft } }""").execute()
            .path("research.estimatedSecondsLeft").entity(Int::class.java).get()
        assertThat(left).isBetween(30, 40)
        // Only the one being looked at: the list does not work it out for every row.
        val listed = graphQlTester.document("""query { researches(workspaceId: $workspaceId) { content { estimatedSecondsLeft } } }""")
            .execute().path("researches.content").entityList(Map::class.java).get()
        assertThat(listed).hasSize(4).allMatch { it["estimatedSecondsLeft"] == null }
        held.countDown()
        val done = settled(running)
        graphQlTester.document("""query { research(id: ${done.id}) { estimatedSecondsLeft } }""").execute()
            .path("research.estimatedSecondsLeft").valueIsNull()
    }

    /* ------------------------------------------------------- fixture ----- */

    private fun asked(instruction: String): List<String> = received.filter { it.contains(instruction.substringBefore('.')) }

    @Suppress("UNCHECKED_CAST")
    private fun modesOf(): List<Map<String, Any?>> =
        graphQlTester.document("""query { researchModes(workspaceId: $workspaceId) { id kind workflowId } }""")
            .execute().path("researchModes").entity(List::class.java).get() as List<Map<String, Any?>>

    private fun modeId(kind: String): Long = (modesOf().first { it["kind"] == kind }["id"] as String).toLong()

    private fun modeWorkflow(kind: String): Long = (modesOf().first { it["kind"] == kind }["workflowId"] as String).toLong()

    private fun start(modelId: Long): Long =
        graphQlTester.document(
            """mutation { startResearch(input: { workspaceId: $workspaceId, question: "Why are there tides?", modeId: ${modeId("SHALLOW")},
                 language: FORMAL, outputFormat: DOCUMENT }) { id } }""",
        ).execute().path("startResearch.id").entity(Long::class.java).get()

    private fun sourcesOf(id: Long): List<String> =
        graphQlTester.document("""query { research(id: $id) { sources { url } } }""").execute()
            .path("research.sources[*].url").entityList(String::class.java).get()

    /** The research once its run has ended; the run goes on on a thread of its own. */
    private fun settled(id: Long): Research {
        val until = System.currentTimeMillis() + 30_000
        while (true) {
            val held = requireNotNull(researches.findByIdOrNull(id))
            if (held.status != ResearchStatus.RUNNING || System.currentTimeMillis() > until) return held
            Thread.sleep(50)
        }
    }

    /** Until the model has been asked for the notes of [count] sources. */
    private fun awaitNotes(count: Int) {
        val until = System.currentTimeMillis() + 30_000
        while (notesAsked.get() < count && System.currentTimeMillis() < until) Thread.sleep(20)
        assertThat(notesAsked.get()).isGreaterThanOrEqualTo(count)
    }

    private fun model(endpoint: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: { workspaceId: $workspaceId, name: "Stub $stamp", endpoint: "$endpoint", secret: "sk-test" }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Researcher model", modelId: "research-$stamp", kind: CHAT }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get().also { agents.researchWith(workspaceId, it) }
    }

    /**
     * A model answering each of the research's questions as a model would - and
     * the report, where it is asked for as a stream, as frames: the first few,
     * then, while [writingHeld] is set, nothing until it is let go.
     */
    private fun serve(): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            received += body
            val streaming = body.replace(" ", "").contains("\"stream\":true")
            when {
                body.contains(ResearchPipeline.PLAN.substringBefore('.')) -> answer(exchange, streaming, """["tide causes", "tide timing", "tide records"]""")
                body.contains(ResearchPipeline.FOLLOW_UP.substringBefore('.')) -> answer(exchange, streaming, """["moon distance and tides"]""")
                body.contains(ResearchPipeline.NOTES.substringBefore('.')) -> {
                    if (notesAsked.incrementAndGet() == 2) notesHeld?.await(30, TimeUnit.SECONDS)
                    answer(exchange, streaming, "The moon's gravity raises tides.")
                }
                else -> answer(exchange, streaming, REPORT, holdAfter = 2)
            }
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}"
    }

    /** Says [text], as frames where the request streams: one per piece, and with [holdAfter] pieces sent, it waits. */
    private fun answer(exchange: HttpExchange, streaming: Boolean, text: String, holdAfter: Int? = null) {
        fun escaped(said: String) = said.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        if (!streaming) {
            val sent = """{"choices":[{"message":{"role":"assistant","content":"${escaped(text)}"}}],
                "usage":{"prompt_tokens":11,"completion_tokens":5}}""".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, sent.size.toLong())
            exchange.responseBody.use { it.write(sent) }
            exchange.close()
            return
        }
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, 0)
        exchange.responseBody.use { out ->
            val pieces = PIECES.takeIf { text == REPORT } ?: listOf(text)
            pieces.forEachIndexed { at, piece ->
                if (holdAfter != null && at == holdAfter) writingHeld?.await(30, TimeUnit.SECONDS)
                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"${escaped(piece)}\"}}]}\n\n".toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }
            out.write("data: {\"choices\":[{\"delta\":{}}],\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":4}}\n\n".toByteArray(StandardCharsets.UTF_8))
            out.write("data: [DONE]\n\n".toByteArray(StandardCharsets.UTF_8))
            out.flush()
        }
        exchange.close()
    }

    /**
     * The web, as a stub: three results for every query, each on a website of
     * its own; told to break, every page it is asked for throws.
     */
    class HeldWeb : ResearchWeb {
        override fun searchReady(workspaceId: Long) = true

        val read = CopyOnWriteArrayList<String>()

        @Volatile var breaking: String? = null

        override fun search(workspaceId: Long, query: String, limit: Int): Result<List<SearchHit>> {
            val slug = query.replace(' ', '-')
            return Result.success((1..3).map { at -> SearchHit("$query, part $at", "https://$slug-$at.example.org/$slug", "What $slug says.") })
        }

        override fun read(workspaceId: Long, url: String): PageRead {
            breaking?.let { throw IllegalStateException(it) }
            read += url
            return PageRead.Read(null, "The moon pulls the sea at $url.", emptyList())
        }

        override fun searchImages(workspaceId: Long, query: String, limit: Int): Result<List<ImageHit>> = Result.success(emptyList())

        override fun image(workspaceId: Long, url: String): ImageFetched = ImageFetched.Unfetched("not here")
    }

    @TestConfiguration
    class Web {
        @Bean
        @Primary
        fun heldWeb(): HeldWeb = HeldWeb()
    }

    private companion object {
        const val REPORT = "# Tides\n\nThe moon pulls the sea [1].\n\n## Conclusion\n\nThe moon."
        val PIECES = listOf("# Tides\n\n", "The moon", " pulls the sea [1].", "\n\n## Conclusion\n\nThe moon.")
    }
}
