package io.mszymanski.orknux.server.llm

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
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
import org.springframework.data.domain.PageRequest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A session that has grown too long is summarised rather than truncated in
 * silence. Issue #523.
 *
 * What this pins:
 *
 *   off               a workspace set to zero is untouched however long it grows
 *   under the line    and one that has not reached the threshold is untouched
 *   over the line     a SUMMARY event is written into the session, the older
 *                     turns are marked superseded and kept, and the recent ones
 *                     are carried word for word
 *   in order          the summary is stamped where the turns it replaces began,
 *                     so the session still reads front to back
 *   carried           what a turn is given back is the summary and the recent
 *                     end - not the superseded turns, and marked as a record
 *   twice             a second compaction folds the first summary into the new
 *                     one rather than stacking them
 *   a failure         a summariser that will not answer leaves the session alone
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class SessionCompactionTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val compaction: SessionCompaction,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val events: LlmSessionEventRepository,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private var modelId: Long = 0
    private var session: Long = 0
    private lateinit var server: HttpServer
    private val asked = CopyOnWriteArrayList<String>()
    private var answering = true

    @BeforeEach
    fun start() {
        events.deleteAll()
        sessions.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        asked.clear()
        answering = true

        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            asked += exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val body = if (answering) {
                """{"choices":[{"message":{"role":"assistant","content":"They settled on the sync fix on Monday."}}]}"""
            } else {
                """{"error":{"message":"no"}}"""
            }
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(if (answering) 200 else 500, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
        modelId = chatModel("http://${server.address.hostString}:${server.address.port}")
        session = recorder.open(workspaceId, "slack_", "compaction-test")

        // Twenty turns, alternating, each long enough that the estimate is well
        // over any threshold used below.
        (1..20).forEach { turn ->
            if (turn % 2 == 1) {
                recorder.userSaid(session, "alice", "Turn $turn. ${"word ".repeat(60)}")
            } else {
                recorder.agentSaid(session, "Support responder", "Turn $turn. ${"word ".repeat(60)}")
            }
        }
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `a workspace set to zero is left alone`() {
        val workspace = settle(afterTokens = 0, keep = 6)

        assertThat(compaction.compactIfNeeded(session, workspace, modelId)).isNull()
        assertThat(summaries()).isEmpty()
        assertThat(asked).isEmpty()
    }

    @Test
    fun `one under the threshold is left alone`() {
        val workspace = settle(afterTokens = 1_000_000, keep = 6)

        assertThat(compaction.compactIfNeeded(session, workspace, modelId)).isNull()
        assertThat(summaries()).isEmpty()
    }

    /**
     * The shape of the thing: one SUMMARY event, the older turns kept but no
     * longer carried, and the recent ones untouched.
     */
    @Test
    fun `over the threshold a summary event is written and the older turns are superseded`() {
        val workspace = settle(afterTokens = 1_000, keep = 6)

        val done = compaction.compactIfNeeded(session, workspace, modelId)
        assertThat(done).isNotNull()
        assertThat(done?.replaced).isEqualTo(14)
        assertThat(done?.kept).isEqualTo(6)

        assertThat(summaries()).singleElement().satisfies({ summary ->
            assertThat(summary.content).isEqualTo("They settled on the sync fix on Monday.")
        })

        val all = events.findAll().filter { it.sessionId == session }
        // Nothing deleted: every turn is still in the table for a person to read.
        assertThat(all.count { it.kind != LlmSessionEventKind.SUMMARY }).isEqualTo(20)
        assertThat(all.filter { it.superseded }.map { it.content.orEmpty().substringBefore(".") })
            .containsExactlyInAnyOrderElementsOf((1..14).map { "Turn $it" })
        assertThat(all.filter { !it.superseded && it.kind != LlmSessionEventKind.SUMMARY })
            .hasSize(6)
    }

    /** Stamped where the replaced stretch began, so the log still reads in order. */
    @Test
    fun `the summary stands where the turns it replaced began`() {
        val workspace = settle(afterTokens = 1_000, keep = 6)
        val first = events.findAll().filter { it.sessionId == session }.minByOrNull { it.at }!!

        compaction.compactIfNeeded(session, workspace, modelId)

        assertThat(summaries().single().at).isEqualTo(first.at)
    }

    /**
     * What the next turn is given: the summary first, marked as a record, then
     * the recent end - and none of the superseded turns.
     */
    @Test
    fun `a turn is given back the summary and the recent end`() {
        val workspace = settle(afterTokens = 1_000, keep = 6)
        compaction.compactIfNeeded(session, workspace, modelId)

        val carried = recorder.remembered(session)

        assertThat(carried.first().content)
            .startsWith("[Earlier in this conversation, summarised")
            .contains("They settled on the sync fix on Monday.")
        assertThat(carried.drop(1).map { it.content.substringBefore(".") })
            .containsExactly("Turn 15", "Turn 16", "Turn 17", "Turn 18", "Turn 19", "Turn 20")
        assertThat(carried.joinToString(" ") { it.content }).doesNotContain("Turn 3.")
    }

    /** The second compaction reads the first summary and replaces it. */
    @Test
    fun `a second compaction folds the first summary in rather than stacking`() {
        val workspace = settle(afterTokens = 1_000, keep = 6)
        compaction.compactIfNeeded(session, workspace, modelId)

        (21..34).forEach { turn ->
            recorder.userSaid(session, "alice", "Turn $turn. ${"word ".repeat(60)}")
        }
        asked.clear()
        assertThat(compaction.compactIfNeeded(session, workspace, modelId)).isNotNull()

        // The summariser was shown the earlier summary as part of what it replaces.
        assertThat(asked).singleElement().satisfies({ sent ->
            assertThat(sent).contains("They settled on the sync fix on Monday.")
        })
        // And only the newest summary is still carried.
        assertThat(summaries().filter { !it.superseded }).hasSize(1)
        assertThat(recorder.remembered(session).count { it.content.startsWith("[Earlier") }).isEqualTo(1)
    }

    /** Losing the older turns because a model was unreachable is worse than a long session. */
    @Test
    fun `a summariser that will not answer leaves the session alone`() {
        val workspace = settle(afterTokens = 1_000, keep = 6)
        answering = false

        assertThat(compaction.compactIfNeeded(session, workspace, modelId)).isNull()
        assertThat(summaries()).isEmpty()
        assertThat(events.findAll().filter { it.sessionId == session && it.superseded }).isEmpty()
    }

    private fun summaries() =
        events.findAll().filter { it.sessionId == session && it.kind == LlmSessionEventKind.SUMMARY }

    /** Through the mutation rather than the entity, so the GraphQL surface is exercised too. */
    private fun settle(afterTokens: Int, keep: Int): Workspace {
        graphQlTester.document(
            """
            mutation {
              setWorkspaceSessionCompaction(
                workspaceId: $workspaceId, afterTokens: $afterTokens, keepTurns: $keep,
                summaryTokens: 200, attempts: 2, modelId: $modelId
              ) { sessionCompactAfterTokens sessionCompactionKeepTurns }
            }
            """,
        ).execute().path("setWorkspaceSessionCompaction.sessionCompactAfterTokens")
            .entity(Int::class.java).isEqualTo(afterTokens)
        return workspaces.findById(workspaceId).orElseThrow()
    }

    private fun chatModel(endpoint: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Local", endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()

        return graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $providerId, name: "Summariser", modelId: "small", kind: CHAT
               }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }
}
