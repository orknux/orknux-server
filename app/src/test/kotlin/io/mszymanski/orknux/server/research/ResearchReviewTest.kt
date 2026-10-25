package io.mszymanski.orknux.server.research

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.mcp.OrknuxTools
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tunables
import io.mszymanski.orknux.server.workspace.WorkspaceAPI
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A research reviewed before it is saved.
 *
 * Asked for, Review report runs between Write and Save: the workspace's
 * Research reviewer reads the draft against the plan and the notes, with the
 * model chosen for reviewing, and where it asks for changes the Research writer
 * is handed its own conversation with the findings after it and revises - as
 * many rounds as the workspace's number, or the installation's, allows. Not
 * asked for, the question before it says no and it is skipped.
 *
 * The models are one stub told apart by what each request opens with, the
 * reviewer answering the verdicts it is given in turn; the web is a stub too.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ResearchReviewTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val researches: ResearchRepository,
    @Autowired val runSteps: ExecutionStepRepository,
    @Autowired val workspaceApi: WorkspaceAPI,
    @Autowired val orknux: OrknuxTools,
    @Autowired val tunables: Tunables,
    @Autowired val rounds: ResearchReviewRounds,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer
    private val received = CopyOnWriteArrayList<String>()
    private val stamp = System.nanoTime().toString().takeLast(9)

    /** What the reviewer answers, in turn; the last one again once they run out. */
    private val verdicts = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun workspace() {
        received.clear()
        verdicts.clear()
        verdicts += ACCEPT
        workspaceId = requireNotNull(workspaceApi.create("Research reviews $stamp", null).id)
    }

    @AfterEach
    fun stop() {
        if (::server.isInitialized) server.stop(0)
        tunables.set(Tunable.RESEARCH_REVIEW_ROUNDS, null, "alice")
    }

    @Test
    fun `not asked for, the review is skipped, the reviewer never asked, and the draft is saved`() {
        val (writer, _) = models()
        val done = settled(start(writer, review = false))

        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)
        assertThat(reviewerAsked()).isEmpty()
        val steps = stepsOf(done)
        assertThat(steps.getValue("reviewing").status).isEqualTo(StepStatus.COMPLETED)
        assertThat(steps.getValue("review").status).isEqualTo(StepStatus.SKIPPED)
        assertThat(steps.getValue("save").status).isEqualTo(StepStatus.COMPLETED)
        // Save was handed what the run held, not the question's "did not hold".
        assertThat(done.result).startsWith(DRAFT).contains("## Sources")
        assertThat(done.review).isFalse()
        graphQlTester.document("""query { research(id: ${done.id}) { review reviewRounds { round } } }""").execute()
            .path("research.review").entity(Boolean::class.java).isEqualTo(false)
            .path("research.reviewRounds").entityList(Any::class.java).hasSize(0)
    }

    @Test
    fun `asked for and accepted, the reviewer is asked once with everything, and the report is the draft`() {
        val (writer, reviewer) = models()
        val done = settled(start(writer, review = true, reviewer = reviewer))

        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)
        val asked = reviewerAsked()
        assertThat(asked).hasSize(1)
        assertThat(modelOf(asked.single())).isEqualTo("reviewer-$stamp")
        assertThat(asked.single())
            .contains("Question: Why are there tides?")
            .contains("- tide causes")
            .contains("The moon's gravity raises tides.")
            .contains("The sun does it too.")
        assertThat(revisionsAsked()).isEmpty()
        assertThat(done.result).startsWith(DRAFT)
        assertThat(stepsOf(done).getValue("review").status).isEqualTo(StepStatus.COMPLETED)

        val view = graphQlTester.document(
            """query { research(id: ${done.id}) { review reviewerModelName reviewRounds { round verdict revised issues { kind } } } }""",
        ).execute()
        view.path("research.review").entity(Boolean::class.java).isEqualTo(true)
        view.path("research.reviewerModelName").entity(String::class.java).isEqualTo("Reviewer model")
        view.path("research.reviewRounds[*].verdict").entityList(String::class.java).containsExactly("ACCEPT")
        view.path("research.reviewRounds[0].revised").entity(Boolean::class.java).isEqualTo(false)
    }

    @Test
    fun `asked to revise, the writer is handed the findings in its own conversation, and the revision is saved`() {
        val (writer, reviewer) = models()
        verdicts.clear()
        verdicts += REVISE

        val done = settled(start(writer, review = true, reviewer = reviewer))

        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)
        assertThat(reviewerAsked()).hasSize(1)
        val revision = revisionsAsked().single()
        // The writer's own model, its own instructions and request, its draft as its answer, and the findings after it.
        assertThat(modelOf(revision)).isEqualTo("writer-$stamp")
        val turns = mapper.readTree(revision).path("messages").values().map { it.path("role").stringValue() to it.path("content").toString() }
        assertThat(turns.map { it.first }).containsExactly("system", "user", "assistant", "user")
        assertThat(turns[0].second).contains(ResearchPipeline.WRITE.substringBefore('.'))
        assertThat(turns[1].second).contains("Register: FORMAL")
        assertThat(turns[2].second).contains("The moon pulls the sea")
        assertThat(turns[3].second).contains("[unsupported] The claim about the sun is in no note.").contains("Fix: Cite [1] or cut it.")
        // Saved: the revision, with the sources after it, not the draft.
        assertThat(done.result).startsWith(REVISED).contains("## Sources").doesNotContain("The sun does it too")

        val view = graphQlTester.document("""query { research(id: ${done.id}) { reviewRounds { verdict revised charactersAfter issues { kind issue fix } } } }""")
            .execute()
        view.path("research.reviewRounds[0].verdict").entity(String::class.java).isEqualTo("REVISE")
        view.path("research.reviewRounds[0].revised").entity(Boolean::class.java).isEqualTo(true)
        view.path("research.reviewRounds[0].issues[0].kind").entity(String::class.java).isEqualTo("UNSUPPORTED")
        view.path("research.reviewRounds[0].charactersAfter").entity(Int::class.java).isEqualTo(REVISED.length)

        // And an agent reads it the same way.
        val read = mapper.readTree(orknux.run(OrknuxScope(workspaceId = workspaceId, mayWrite = true), "orknux_research", """{"id":${done.id}}"""))
        assertThat(read.path("reviewed").booleanValue()).isTrue()
        assertThat(read.path("review").path(0).path("verdict").stringValue()).isEqualTo("REVISE")
    }

    @Test
    fun `the rounds are the workspace's number where it has one, and the installation's where it has none`() {
        val (writer, reviewer) = models()
        verdicts.clear()
        verdicts += REVISE

        tunables.set(Tunable.RESEARCH_REVIEW_ROUNDS, 2, "alice")
        assertThat(rounds.of(workspaceId)).isEqualTo(2)
        settled(start(writer, review = true, reviewer = reviewer)).also {
            assertThat(it.status).describedAs(it.error).isEqualTo(ResearchStatus.COMPLETED)
        }
        // Two rounds, each asking for changes: reviewed twice, revised twice, then saved.
        assertThat(reviewerAsked()).hasSize(2)
        assertThat(revisionsAsked()).hasSize(2)

        received.clear()
        graphQlTester.document("""mutation { setWorkspaceResearchReviewRounds(workspaceId: $workspaceId, count: 3) { researchReviewRounds researchReviewRoundsDefault } }""")
            .execute()
            .path("setWorkspaceResearchReviewRounds.researchReviewRounds").entity(Int::class.java).isEqualTo(3)
            .path("setWorkspaceResearchReviewRounds.researchReviewRoundsDefault").entity(Int::class.java).isEqualTo(2)
        assertThat(rounds.of(workspaceId)).isEqualTo(3)
        settled(start(writer, review = true, reviewer = reviewer))
        assertThat(reviewerAsked()).hasSize(3)

        graphQlTester.document("""mutation { setWorkspaceResearchReviewRounds(workspaceId: $workspaceId, count: null) { researchReviewRounds } }""")
            .execute().path("setWorkspaceResearchReviewRounds.researchReviewRounds").valueIsNull()
        assertThat(rounds.of(workspaceId)).isEqualTo(2)
        graphQlTester.document("""mutation { setWorkspaceResearchReviewRounds(workspaceId: $workspaceId, count: 99) { id } }""")
            .execute().errors().expect { it.extensions?.get("code") == "ResearchReviewRoundsOutOfRange" }.verify()
    }

    @Test
    fun `a review without a reviewer is refused`() {
        val (writer, _) = models()
        graphQlTester.document(
            """mutation { startResearch(input: { workspaceId: $workspaceId, question: "Tides", modeId: ${modeId()}, modelId: $writer,
                 language: FORMAL, outputFormat: DOCUMENT, review: true }) { id } }""",
        ).execute().errors().expect { it.extensions?.get("code") == "ResearchReviewerMissing" }.verify()
        val scope = OrknuxScope(workspaceId = workspaceId, mayWrite = true)
        assertThat(orknux.run(scope, "orknux_start_research", """{"question":"Tides","review":true}""")).contains("needs a reviewerModel")

        // Nor is a review asked of a mode whose workflow has no Review step: it would say it was reviewed and not be.
        val mine = graphQlTester.document("""mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "My research" }) { workflowId } }""")
            .execute().path("createWorkflow.workflowId").entity(String::class.java).get()
        val patents = graphQlTester.document("""mutation { createResearchMode(input: { workspaceId: $workspaceId, name: "Patents", workflowId: $mine }) { id } }""")
            .execute().path("createResearchMode.id").entity(String::class.java).get()
        graphQlTester.document(
            """mutation { startResearch(input: { workspaceId: $workspaceId, question: "Tides", modeId: $patents, modelId: $writer,
                 language: FORMAL, outputFormat: DOCUMENT, review: true, reviewerModelId: $writer }) { id } }""",
        ).execute().errors().expect { it.extensions?.get("code") == "ResearchModeNotReviewed" }.verify()
    }

    @Test
    fun `a reviewed research restarts from Review, the steps before it carried over`() {
        val (writer, reviewer) = models()
        val first = settled(start(writer, review = true, reviewer = reviewer))
        assertThat(first.status).describedAs(first.error).isEqualTo(ResearchStatus.COMPLETED)
        val planned = received.count { it.contains(ResearchPipeline.PLAN.substringBefore('.')) }
        verdicts.clear()
        verdicts += REVISE

        graphQlTester.document("""mutation { restartResearch(id: ${first.id}, fromStage: REVIEW) { status reviewRounds { round } } }""").execute()
            .path("restartResearch.status").entity(String::class.java).isEqualTo("RUNNING")
            .path("restartResearch.reviewRounds").entityList(Any::class.java).hasSize(0)

        val done = settled(requireNotNull(first.id))
        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)
        assertThat(reviewerAsked()).hasSize(2)
        assertThat(received.count { it.contains(ResearchPipeline.PLAN.substringBefore('.')) }).isEqualTo(planned)
        assertThat(done.result).startsWith(REVISED)
        val steps = stepsOf(done)
        assertThat(listOf("plan", "gather", "followUp", "write").map { steps.getValue(it).carriedOver }).containsOnly(true)
        assertThat(steps.getValue("reviewing").carriedOver).isFalse()
    }

    @Test
    fun `an agent asks for a review by the reviewer's name`() {
        val (_, _) = models()
        val scope = OrknuxScope(workspaceId = workspaceId, mayWrite = true)
        val started = mapper.readTree(
            orknux.run(scope, "orknux_start_research", """{"question":"Why are there tides?","model":"Writer model","review":true,"reviewerModel":"Reviewer model"}"""),
        )
        assertThat(started.path("error").isMissingNode).describedAs(started.toString()).isTrue()
        val done = settled(started.path("started").asLong())
        assertThat(done.review).isTrue()
        assertThat(done.reviewerModelName).isEqualTo("Reviewer model")
        assertThat(reviewerAsked()).hasSize(1)
    }

    /* ------------------------------------------------------- fixture ----- */

    private fun reviewerAsked() = received.filter { it.contains(ResearchPipeline.REVIEW.substringBefore('.')) }

    private fun revisionsAsked() = received.filter { it.contains(ResearchPipeline.REVISE.substringBefore('.')) }

    private fun modelOf(body: String): String = mapper.readTree(body).path("model").stringValue()

    private fun stepsOf(research: Research) =
        runSteps.findByExecutionIdOrderByOrderAsc(requireNotNull(research.executionId)).associateBy { it.nodeKey }

    @Suppress("UNCHECKED_CAST")
    private fun modeId(): Long =
        ((graphQlTester.document("""query { researchModes(workspaceId: $workspaceId) { id kind } }""")
            .execute().path("researchModes").entity(List::class.java).get() as List<Map<String, Any?>>)
            .first { it["kind"] == "SHALLOW" }["id"] as String).toLong()

    private fun start(writer: Long, review: Boolean, reviewer: Long? = null): Long {
        val reviewing = if (review) ", review: true, reviewerModelId: $reviewer" else ""
        return graphQlTester.document(
            """mutation { startResearch(input: { workspaceId: $workspaceId, question: "Why are there tides?", modeId: ${modeId()},
                 modelId: $writer, language: FORMAL, outputFormat: DOCUMENT$reviewing }) { id } }""",
        ).execute().path("startResearch.id").entity(Long::class.java).get()
    }

    private fun settled(id: Long): Research {
        val until = System.currentTimeMillis() + 30_000
        while (true) {
            val held = requireNotNull(researches.findByIdOrNull(id))
            if (held.status != ResearchStatus.RUNNING || System.currentTimeMillis() > until) return held
            Thread.sleep(50)
        }
    }

    /** The writer's model and the reviewer's, both answered by the stub. */
    private fun models(): Pair<Long, Long> {
        val endpoint = serve()
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: { workspaceId: $workspaceId, name: "Stub $stamp", endpoint: "$endpoint", secret: "sk-test" }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        fun model(name: String, id: String) = graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "$name", modelId: "$id", kind: CHAT }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
        return model("Writer model", "writer-$stamp") to model("Reviewer model", "reviewer-$stamp")
    }

    private fun serve(): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            received += body
            val streaming = body.replace(" ", "").contains("\"stream\":true")
            val answer = when {
                body.contains(ResearchPipeline.REVIEW.substringBefore('.')) -> {
                    val at = reviewerAsked().size - 1
                    verdicts.getOrElse(at) { verdicts.last() }
                }
                body.contains(ResearchPipeline.REVISE.substringBefore('.')) -> REVISED
                body.contains(ResearchPipeline.PLAN.substringBefore('.')) -> """["tide causes", "tide timing", "tide records"]"""
                body.contains(ResearchPipeline.FOLLOW_UP.substringBefore('.')) -> """["moon distance and tides"]"""
                body.contains(ResearchPipeline.NOTES.substringBefore('.')) -> "The moon's gravity raises tides."
                else -> DRAFT
            }
            reply(exchange, streaming, answer)
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}"
    }

    private fun reply(exchange: HttpExchange, streaming: Boolean, text: String) {
        val content = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val sent = if (streaming) {
            "data: {\"choices\":[{\"delta\":{\"content\":\"$content\"}}]}\n\ndata: [DONE]\n\n"
        } else {
            """{"choices":[{"message":{"role":"assistant","content":"$content"}}],"usage":{"prompt_tokens":11,"completion_tokens":5}}"""
        }.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", if (streaming) "text/event-stream" else "application/json")
        exchange.sendResponseHeaders(200, sent.size.toLong())
        exchange.responseBody.use { it.write(sent) }
        exchange.close()
    }

    /** Three results for every query, each on a website of its own. */
    class PlainWeb : ResearchWeb {
        override fun search(workspaceId: Long, query: String, limit: Int): Result<List<SearchHit>> {
            val slug = query.replace(' ', '-')
            return Result.success((1..3).map { at -> SearchHit("$query, part $at", "https://$slug-$at.example.org/$slug", "What $slug says.") })
        }

        override fun read(workspaceId: Long, url: String): PageRead = PageRead.Read(null, "The moon pulls the sea at $url.", emptyList())
    }

    @TestConfiguration
    class Web {
        @Bean
        @Primary
        fun plainWeb(): PlainWeb = PlainWeb()
    }

    private companion object {
        const val DRAFT = "# Tides\n\nThe moon pulls the sea [1]. The sun does it too.\n\n## Conclusion\n\nThe moon."
        const val REVISED = "# Tides\n\nThe moon pulls the sea [1].\n\n## Conclusion\n\nThe moon, as the notes say."
        const val ACCEPT = """{"verdict": "accept", "summary": "Every claim is cited and supported.", "issues": []}"""
        const val REVISE = """Here is my review: {"verdict": "revise", "summary": "One claim has no source.",
            "issues": [{"kind": "unsupported", "issue": "The claim about the sun is in no note.", "fix": "Cite [1] or cut it."}]}"""
    }
}
