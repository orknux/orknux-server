package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.ObjectMapper

/**
 * What an agent or an MCP client can read of a workspace's conversations.
 * Issue #524.
 *
 * A run's steps were readable through orknux_execution and the conversation an
 * agent had inside one was not, which is the half that says why it did what it
 * did. What is pinned here: a session can be found and read, a long one is read
 * in pages, an agent with no id reads its own, a line a compaction superseded
 * says so, and the workspace is a boundary.
 */
@SpringBootTest
class OrknuxSessionToolsTest(
    @Autowired val tools: OrknuxTools,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val events: LlmSessionEventRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0
    private var elsewhereId: Long = 0

    @BeforeEach
    fun reset() {
        events.deleteAll()
        sessions.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
        elsewhereId = requireNotNull(workspaces.save(Workspace(name = "billing")).id)
    }

    private fun scope(session: Long? = null) = OrknuxScope(workspaceId = workspaceId, session = session)

    private fun conversation(workspace: Long, key: String): Long {
        val id = recorder.open(workspace, "slack_", key)
        recorder.userSaid(id, "alice", "Is the sync broken?")
        recorder.toolReturned(recorder.toolCalled(id, "orknux_executions", "{}"), """{"runs":[]}""")
        recorder.agentSaid(id, "Support responder", "It was fixed on Monday.")
        return id
    }

    @Test
    fun `the list finds this workspace's conversations and no others`() {
        conversation(workspaceId, "1790000000.000001")
        conversation(elsewhereId, "1790000000.999999")

        val answer = mapper.readTree(tools.run(scope(), "orknux_sessions", "{}"))

        val keys = answer.path("sessions").toList().map { it.path("key").stringValue() }
        assertThat(keys).containsExactly("slack_:1790000000.000001")
        assertThat(answer.path("sessions").first().path("url").stringValue()).contains("/sessions/")
    }

    @Test
    fun `a session reads back in order, calls with what they returned`() {
        val id = conversation(workspaceId, "1790000000.000001")

        val answer = mapper.readTree(tools.run(scope(), "orknux_session", """{"id":$id}"""))
        val lines = answer.path("lines").toList()

        assertThat(lines.map { it.path("kind").stringValue() }).containsExactly("USER", "TOOL", "AGENT")
        assertThat(lines[0].path("said").stringValue()).isEqualTo("Is the sync broken?")
        assertThat(lines[1].path("who").stringValue()).isEqualTo("orknux_executions")
        assertThat(lines[1].path("returned").stringValue()).isEqualTo("""{"runs":[]}""")
        assertThat(answer.path("more").asBoolean()).isFalse()
    }

    /** Where it is in one, "this conversation" is the agent's own, without it knowing the id. */
    @Test
    fun `with no id an agent reads the conversation it is in`() {
        val mine = conversation(workspaceId, "mine")
        conversation(workspaceId, "someone-else")

        val answer = mapper.readTree(tools.run(scope(session = mine), "orknux_session", "{}"))

        assertThat(answer.path("key").stringValue()).isEqualTo("slack_:mine")
    }

    @Test
    fun `with no id and no conversation of its own the caller is asked which`() {
        assertThat(tools.run(scope(), "orknux_session", "{}")).contains("Which conversation")
    }

    /** A long log in pieces, each page saying where the next begins. */
    @Test
    fun `a long session is read in pages`() {
        val id = recorder.open(workspaceId, "slack_", "long")
        (1..7).forEach { recorder.userSaid(id, "alice", "Line $it") }

        val first = mapper.readTree(tools.run(scope(), "orknux_session", """{"id":$id,"limit":3}"""))
        assertThat(first.path("lines").toList().map { it.path("said").stringValue() }).containsExactly("Line 1", "Line 2", "Line 3")
        assertThat(first.path("more").asBoolean()).isTrue()

        val next = first.path("next").asLong()
        val second = mapper.readTree(tools.run(scope(), "orknux_session", """{"id":$id,"limit":3,"after":$next}"""))
        assertThat(second.path("lines").toList().map { it.path("said").stringValue() }).containsExactly("Line 4", "Line 5", "Line 6")

        val last = mapper.readTree(
            tools.run(scope(), "orknux_session", """{"id":$id,"limit":3,"after":${second.path("next").asLong()}}"""),
        )
        assertThat(last.path("lines").toList().map { it.path("said").stringValue() }).containsExactly("Line 7")
        assertThat(last.path("more").asBoolean()).isFalse()
    }

    /** What a compaction let go of is still readable, and says it was let go of. */
    @Test
    fun `a superseded line is shown and marked`() {
        val id = conversation(workspaceId, "compacted")
        events.findAll().filter { it.sessionId == id }.first().let {
            it.superseded = true
            events.save(it)
        }

        val lines = mapper.readTree(tools.run(scope(), "orknux_session", """{"id":$id}""")).path("lines").toList()

        assertThat(lines).hasSize(3)
        assertThat(lines.count { it.path("superseded").asBoolean() }).isEqualTo(1)
    }

    /** Another workspace's conversation is answered as one that does not exist. */
    @Test
    fun `another workspace's session is not there`() {
        val theirs = conversation(elsewhereId, "theirs")

        val answer = tools.run(scope(), "orknux_session", """{"id":$theirs}""")

        assertThat(answer).contains("There is no conversation $theirs here").doesNotContain("It was fixed")
    }
}
