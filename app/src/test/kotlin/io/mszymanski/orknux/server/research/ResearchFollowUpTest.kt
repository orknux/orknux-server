package io.mszymanski.orknux.server.research

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.mcp.OrknuxTools
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tunables
import io.mszymanski.orknux.server.workflow.SavedArtifactRepository
import io.mszymanski.orknux.server.workspace.WorkspaceAPI
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
 * The questions a finished research suggests asking next, and a research
 * started on one of them.
 *
 * Asked for once the report is final, in a short call of their own, so they
 * are never in the report or its file; as many as the research was started
 * with - the start's number, or the workspace's, or the installation's - and
 * none, with no call, where that is nought. A follow-up is a research of its
 * own with everything its parent was started with and a link back to it.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ResearchFollowUpTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val researches: ResearchRepository,
    @Autowired val agents: io.mszymanski.orknux.server.agent.AgentRepository,
    @Autowired val workspaceApi: WorkspaceAPI,
    @Autowired val orknux: OrknuxTools,
    @Autowired val tunables: Tunables,
    @Autowired val saved: SavedArtifactRepository,
    @Autowired val store: AttachmentStore,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer
    private val received = CopyOnWriteArrayList<String>()
    private val stamp = System.nanoTime().toString().takeLast(9)

    @BeforeEach
    fun workspace() {
        received.clear()
        workspaceId = requireNotNull(workspaceApi.create("Research follow-ups $stamp", null).id)
    }

    @AfterEach
    fun stop() {
        if (::server.isInitialized) server.stop(0)
        tunables.set(Tunable.RESEARCH_FOLLOW_UPS, null, "alice")
    }

    @Test
    fun `a finished research suggests the installation's number of follow-up questions, never in its report or its file`() {
        val modelId = model()
        val done = settled(start(modelId, "outputFormat: PDF"))

        assertThat(done.status).describedAs(done.error).isEqualTo(ResearchStatus.COMPLETED)
        val asked = followUpsAsked().single()
        assertThat(asked).contains("Write exactly 5 follow-up questions").contains("- tide causes").contains("The moon pulls the sea")
        // Five of what was offered, less the one repeating a part already covered.
        assertThat(questionsOf(requireNotNull(done.id))).containsExactly(*OFFERED.drop(1).take(5).toTypedArray())
        assertThat(done.followUpCount).isEqualTo(5)

        // Kept apart: not in the report, and not in the PDF it was saved as.
        OFFERED.drop(1).forEach { assertThat(done.result).doesNotContain(it) }
        val pdf = store.open(saved.findByIdOrNull(requireNotNull(done.artifactId))!!.location).use { it.readBytes() }
        val text = org.apache.pdfbox.Loader.loadPDF(pdf).use { org.apache.pdfbox.text.PDFTextStripper().getText(it) }
        assertThat(text).contains("Tides")
        OFFERED.drop(1).forEach { assertThat(text).doesNotContain(it) }

        val read = mapper.readTree(orknux.run(OrknuxScope(workspaceId = workspaceId, mayWrite = true), "orknux_research", """{"id":${done.id}}"""))
        assertThat(read.path("followUps").values().map { it.stringValue() }).containsExactly(*OFFERED.drop(1).take(5).toTypedArray())
    }

    @Test
    fun `none asked for, none suggested and the model is not asked`() {
        val modelId = model()
        val off = settled(start(modelId, "followUps: 0"))
        assertThat(off.status).describedAs(off.error).isEqualTo(ResearchStatus.COMPLETED)
        assertThat(questionsOf(requireNotNull(off.id))).isEmpty()

        tunables.set(Tunable.RESEARCH_FOLLOW_UPS, 0, "alice")
        val unset = settled(start(modelId))
        assertThat(unset.followUpCount).isEqualTo(0)
        assertThat(questionsOf(requireNotNull(unset.id))).isEmpty()
        assertThat(followUpsAsked()).isEmpty()

        graphQlTester.document(
            """mutation { startResearch(input: { ${agents.researchAgents(workspaceId)}, workspaceId: $workspaceId, question: "Tides", modeId: ${modeId()}, 
                 language: FORMAL, outputFormat: DOCUMENT, followUps: -1 }) { id } }""",
        ).execute().errors().expect { it.extensions?.get("code") == "ResearchFollowUpsOutOfRange" }.verify()
    }

    @Test
    fun `the workspace's number wins over the installation's, and the start's over both`() {
        val modelId = model()
        graphQlTester.document("""mutation { setWorkspaceResearchFollowUps(workspaceId: $workspaceId, count: 2) { researchFollowUps researchFollowUpsDefault } }""")
            .execute()
            .path("setWorkspaceResearchFollowUps.researchFollowUps").entity(Int::class.java).isEqualTo(2)
            .path("setWorkspaceResearchFollowUps.researchFollowUpsDefault").entity(Int::class.java).isEqualTo(5)

        val workspaces = settled(start(modelId))
        assertThat(workspaces.followUpCount).isEqualTo(2)
        assertThat(questionsOf(requireNotNull(workspaces.id))).hasSize(2)

        val own = settled(start(modelId, "followUps: 1"))
        assertThat(questionsOf(requireNotNull(own.id))).hasSize(1)

        graphQlTester.document("""mutation { setWorkspaceResearchFollowUps(workspaceId: $workspaceId, count: null) { researchFollowUps } }""")
            .execute().path("setWorkspaceResearchFollowUps.researchFollowUps").valueIsNull()
        assertThat(settled(start(modelId)).followUpCount).isEqualTo(5)
    }

    @Test
    fun `a follow-up is started with everything its parent was, and the two are linked`() {
        val modelId = model()
        val parent = settled(
            start(modelId, """language: COLLOQUIAL, includeImages: false, websitesExclude: ["tabloid.example"], followUps: 3"""),
        )
        val question = questionsOf(requireNotNull(parent.id)).first()

        val child = graphQlTester.document(
            """mutation { startFollowUpResearch(parentId: ${parent.id}, question: "$question") { id question parentId } }""",
        ).execute()
        child.path("startFollowUpResearch.question").entity(String::class.java).isEqualTo(question)
        child.path("startFollowUpResearch.parentId").entity(Long::class.java).isEqualTo(requireNotNull(parent.id))
        val childId = child.path("startFollowUpResearch.id").entity(Long::class.java).get()

        val held = settled(childId)
        assertThat(held.parentResearchId).isEqualTo(parent.id)
        assertThat(listOf(held.modeId, held.modelId, held.language, held.outputFormat, held.includeImages, held.useWebSearch, held.review, held.followUpCount))
            .isEqualTo(listOf(parent.modeId, parent.modelId, parent.language, parent.outputFormat, parent.includeImages, parent.useWebSearch, parent.review, parent.followUpCount))
        assertThat(mapper.readTree(held.sources)).isEqualTo(mapper.readTree(parent.sources))

        graphQlTester.document("""query { research(id: ${parent.id}) { children { id question } } }""").execute()
            .path("research.children[*].id").entityList(Long::class.java).containsExactly(childId)
        graphQlTester.document("""query { research(id: $childId) { parentQuestion } }""").execute()
            .path("research.parentQuestion").entity(String::class.java).isEqualTo(parent.question)

        // And an agent the same way.
        val scope = OrknuxScope(workspaceId = workspaceId, mayWrite = true)
        val started = mapper.readTree(orknux.run(scope, "orknux_start_research", """{"question":"How high do tides get?","parentId":${parent.id}}"""))
        assertThat(started.path("parent").asLong()).describedAs(started.toString()).isEqualTo(requireNotNull(parent.id))
        val byAgent = settled(started.path("started").asLong())
        assertThat(byAgent.parentResearchId).isEqualTo(parent.id)
        assertThat(byAgent.language).isEqualTo(ResearchLanguage.COLLOQUIAL)
    }

    /* ------------------------------------------------------- fixture ----- */

    private fun followUpsAsked() = received.filter { it.contains(ResearchPipeline.FOLLOW_UPS.substringBefore('.')) }

    private fun questionsOf(id: Long): List<String> =
        graphQlTester.document("""query { research(id: $id) { followUps } }""").execute()
            .path("research.followUps").entityList(String::class.java).get()

    @Suppress("UNCHECKED_CAST")
    private fun modeId(): Long =
        ((graphQlTester.document("""query { researchModes(workspaceId: $workspaceId) { id kind } }""")
            .execute().path("researchModes").entity(List::class.java).get() as List<Map<String, Any?>>)
            .first { it["kind"] == "SHALLOW" }["id"] as String).toLong()

    private fun start(modelId: Long, more: String = ""): Long {
        val extra = if (more.isEmpty()) "" else ", $more"
        val format = if (more.contains("outputFormat")) "" else ", outputFormat: DOCUMENT"
        val language = if (more.contains("language")) "" else ", language: FORMAL"
        return graphQlTester.document(
            """mutation { startResearch(input: { ${agents.researchAgents(workspaceId)}, workspaceId: $workspaceId, question: "Why are there tides?", modeId: ${modeId()}$language$format$extra }) { id } }""",
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

    private fun model(): Long {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            received += body
            val streaming = body.replace(" ", "").contains("\"stream\":true")
            val answer = when {
                body.contains(ResearchPipeline.FOLLOW_UPS.substringBefore('.')) -> mapper.writeValueAsString(OFFERED)
                body.contains(ResearchPipeline.PLAN.substringBefore('.')) -> """["tide causes", "tide timing", "tide records"]"""
                body.contains(ResearchPipeline.NOTES.substringBefore('.')) -> "The moon's gravity raises tides."
                else -> REPORT
            }
            reply(exchange, streaming, answer)
        }
        server.start()
        val endpoint = "http://${server.address.hostString}:${server.address.port}"
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: { workspaceId: $workspaceId, name: "Stub $stamp", endpoint: "$endpoint", secret: "sk-test" }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Researcher model", modelId: "research-$stamp", kind: CHAT }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get().also { agents.researchWith(workspaceId, it) }
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

    class PlainWeb : ResearchWeb {
        override fun searchReady(workspaceId: Long) = true

        override fun search(workspaceId: Long, query: String, limit: Int): Result<List<SearchHit>> {
            val slug = query.replace(' ', '-')
            return Result.success((1..2).map { at -> SearchHit("$query, part $at", "https://$slug-$at.example.org/$slug", "What $slug says.") })
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
        const val REPORT = "# Tides\n\nThe moon pulls the sea [1].\n\n## Conclusion\n\nThe moon."
        val OFFERED = listOf(
            "tide causes",
            "How do spring and neap tides differ in height?",
            "Why do some coasts have one tide a day?",
            "How will rising seas change tidal ranges?",
            "How are tides predicted years ahead?",
            "What do tides do to the Earth's rotation?",
            "Do lakes have tides?",
        )
    }
}
