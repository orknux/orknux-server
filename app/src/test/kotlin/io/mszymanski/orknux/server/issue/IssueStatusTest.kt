package io.mszymanski.orknux.server.issue

import io.mszymanski.orknux.server.mcp.IssueTools
import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.mcp.OrknuxTools
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * Issue statuses as a workspace's own list - issue #428.
 *
 * What used to be an enum is a table, and the three things worth pinning are
 * the three ways that could go quietly wrong: a workspace arriving without its
 * four, an issue holding a key nothing is called, and a rule about the list -
 * where new issues start, what counts as done - being enforced by the page
 * alone. Each is asked through the door it would actually come through: the
 * browser's mutations and the assistant's tool.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class IssueStatusTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val issues: IssueRepository,
    @Autowired val statuses: IssueStatusRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val tools: IssueTools,
    @Autowired val surface: OrknuxTools,
) {

    private var workspaceId: Long = 0
    private lateinit var scope: OrknuxScope

    @BeforeEach
    fun reset() {
        issues.deleteAll()
        statuses.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
        scope = OrknuxScope(workspaceId = workspaceId, mayWrite = true)
    }

    private fun file(title: String): Long =
        graphQlTester.document(
            """mutation { createIssue(input: { workspaceId: $workspaceId, title: "$title" }) { id status } }""",
        ).execute()
            .path("createIssue.status").entity(String::class.java).isEqualTo("OPEN")
            .path("createIssue.id").entity(Long::class.java).get()

    private fun keysOf(workspace: Long): List<String> =
        graphQlTester.document("""{ issueStatuses(workspaceId: $workspace) { key } }""")
            .execute().path("issueStatuses[*].key").entityList(String::class.java).get()

    private fun add(key: String, label: String, color: String? = null): Long =
        graphQlTester.document(
            """mutation { addIssueStatus(workspaceId: $workspaceId, key: "$key", label: "$label"
               ${color?.let { ", color: \"$it\"" } ?: ""}) { id key } }""",
        ).execute().path("addIssueStatus.id").entity(Long::class.java).get()

    /**
     * A workspace made through the door arrives with its four, in order, and
     * the first is where a new issue lands.
     */
    @Test
    fun `a new workspace is given the four statuses in order`() {
        val made = graphQlTester.document("""mutation { createWorkspace(input: { name: "billing" }) { id } }""")
            .execute().path("createWorkspace.id").entity(Long::class.java).get()

        graphQlTester.document("""{ issueStatuses(workspaceId: $made) { key label position initial closed inUse } }""")
            .execute()
            .path("issueStatuses[*].key").entityList(String::class.java)
            .containsExactly("OPEN", "IN_PROGRESS", "REVIEW", "CLOSED")
            .path("issueStatuses[0].initial").entity(Boolean::class.java).isEqualTo(true)
            .path("issueStatuses[0].label").entity(String::class.java).isEqualTo("Open")
            .path("issueStatuses[3].closed").entity(Boolean::class.java).isEqualTo(true)
            .path("issueStatuses[*].inUse").entityList(Int::class.java).containsExactly(0, 0, 0, 0)
    }

    /**
     * A workspace written straight into the table reads as having the four as
     * well, and asking for them writes them down. The tests around this one
     * make workspaces that way, and so did every workspace before the
     * migration ran.
     */
    @Test
    fun `a workspace nobody seeded reads as the defaults and is seeded on first use`() {
        assertThat(statuses.findByWorkspaceIdOrderByPositionAscIdAsc(workspaceId)).isEmpty()
        assertThat(keysOf(workspaceId)).containsExactly("OPEN", "IN_PROGRESS", "REVIEW", "CLOSED")
        assertThat(statuses.findByWorkspaceIdOrderByPositionAscIdAsc(workspaceId)).hasSize(4)
    }

    /**
     * A status the workspace adds is one an issue can be moved into - from the
     * page and from the tool alike - and the key is the workspace's spelling,
     * whatever case the caller typed.
     */
    @Test
    fun `a custom status can be added and an issue moved into it through both doors`() {
        val id = file("The reply is late")
        add("wontfix", "Won't fix", "#999999")
        assertThat(keysOf(workspaceId)).containsExactly("OPEN", "IN_PROGRESS", "REVIEW", "CLOSED", "WONTFIX")

        graphQlTester.document("""mutation { updateIssue(id: $id, input: { status: "wontfix" }) { status } }""")
            .execute().path("updateIssue.status").entity(String::class.java).isEqualTo("WONTFIX")
        // A status nothing special-cases is audited by its label.
        assertThat(audit.findAll().map { it.message }).contains("Issue #1 moved to Won't fix")

        assertThat(tools.setStatus(scope, """{"issue": 1, "status": "in_progress"}""")).contains("IN_PROGRESS")
        assertThat(tools.setStatus(scope, """{"issue": 1, "status": "WONTFIX"}""")).contains("WONTFIX")
        assertThat(issues.findByWorkspaceIdAndNumber(workspaceId, 1)?.status).isEqualTo("WONTFIX")

        // The list the tool describes is the workspace's, so the new one is in it.
        val status = surface.specs(scope).single { it.name == "orknux_set_issue_status" }
        assertThat(status.parameters.single { it.name == "status" }.description)
            .contains("WONTFIX").contains("IN_PROGRESS").contains("workspace")

        // The filter reads it, and the settings page counts it.
        graphQlTester.document("""{ workspaceIssues(workspaceId: $workspaceId, status: "wontfix") { totalElements } }""")
            .execute().path("workspaceIssues.totalElements").entity(Int::class.java).isEqualTo(1)
        graphQlTester.document("""{ issueStatuses(workspaceId: $workspaceId) { key inUse } }""")
            .execute().path("issueStatuses[4].inUse").entity(Int::class.java).isEqualTo(1)
    }

    /** What counts as done is the flag, not the name: a closed custom status audits as closed. */
    @Test
    fun `a custom status that counts as closed is audited as closing, and leaving it as reopening`() {
        val id = file("The reply is late")
        val wontfix = add("WONTFIX", "Won't fix")
        graphQlTester.document("""mutation { updateIssueStatus(id: $wontfix, closed: true) { closed } }""")
            .execute().path("updateIssueStatus.closed").entity(Boolean::class.java).isEqualTo(true)

        graphQlTester.document("""mutation { updateIssue(id: $id, input: { status: "WONTFIX" }) { status } }""").execute()
        graphQlTester.document("""mutation { updateIssue(id: $id, input: { status: "OPEN" }) { status } }""").execute()

        assertThat(audit.findAll().map { it.message }).contains("Issue #1 closed", "Issue #1 reopened")
    }

    /** A status issues hold stays until they are moved, and the refusal says how many. */
    @Test
    fun `a status in use cannot be removed`() {
        val id = file("The reply is late")
        val wontfix = add("WONTFIX", "Won't fix")
        graphQlTester.document("""mutation { updateIssue(id: $id, input: { status: "WONTFIX" }) { status } }""").execute()

        graphQlTester.document("""mutation { removeIssueStatus(id: $wontfix) }""")
            .execute().errors().expect { it.message?.contains("Won't fix is on 1 issue") == true }.verify()
        assertThat(keysOf(workspaceId)).contains("WONTFIX")

        graphQlTester.document("""mutation { updateIssue(id: $id, input: { status: "OPEN" }) { status } }""").execute()
        graphQlTester.document("""mutation { removeIssueStatus(id: $wontfix) }""")
            .execute().path("removeIssueStatus").entity(Boolean::class.java).isEqualTo(true)
        assertThat(keysOf(workspaceId)).doesNotContain("WONTFIX")
    }

    /** Where new issues start is neither done nor removable, and the last done status stays. */
    @Test
    fun `the initial status cannot be closed or removed, and the last closed one stays`() {
        val held = statuses.findByWorkspaceIdOrderByPositionAscIdAsc(workspaceId).ifEmpty {
            keysOf(workspaceId)
            statuses.findByWorkspaceIdOrderByPositionAscIdAsc(workspaceId)
        }
        val open = requireNotNull(held.first { it.initial }.id)
        val closed = requireNotNull(held.single { it.closed }.id)

        graphQlTester.document("""mutation { updateIssueStatus(id: $open, closed: true) { closed } }""")
            .execute().errors().expect { it.message?.contains("cannot count as closed") == true }.verify()
        graphQlTester.document("""mutation { removeIssueStatus(id: $open) }""")
            .execute().errors().expect { it.message?.contains("where a new issue starts") == true }.verify()
        graphQlTester.document("""mutation { removeIssueStatus(id: $closed) }""")
            .execute().errors().expect { it.message?.contains("only status that counts as closed") == true }.verify()
        graphQlTester.document("""mutation { updateIssueStatus(id: $closed, closed: false) { closed } }""")
            .execute().errors().expect { it.message?.contains("only status that counts as closed") == true }.verify()

        assertThat(keysOf(workspaceId)).containsExactly("OPEN", "IN_PROGRESS", "REVIEW", "CLOSED")
    }

    /** A key the workspace does not have is refused naming the ones it does - through both doors. */
    @Test
    fun `a key the workspace does not have is refused with its list`() {
        val id = file("The reply is late")
        val expected = "There is no issue status called DONE in this workspace; it has: OPEN, IN_PROGRESS, REVIEW, CLOSED"

        assertThat(tools.setStatus(scope, """{"issue": 1, "status": "DONE"}""")).contains(expected)
        graphQlTester.document("""mutation { updateIssue(id: $id, input: { status: "DONE" }) { status } }""")
            .execute().errors().expect { it.message == expected }.verify()
        graphQlTester.document("""{ workspaceIssues(workspaceId: $workspaceId, status: "DONE") { totalElements } }""")
            .execute().errors().expect { it.message == expected }.verify()
        assertThat(issues.findByWorkspaceIdAndNumber(workspaceId, 1)?.status).isEqualTo("OPEN")

        // And the list tool refuses the same way, rather than answering with nothing.
        assertThat(tools.list(scope, """{"status": "DONE"}""")).contains(expected)
    }

    /** The order is the workspace's, and a rename carries to every reader; the key does not move. */
    @Test
    fun `statuses can be reordered and relabelled, and a bad key or a taken key is refused`() {
        val held = run {
            keysOf(workspaceId)
            statuses.findByWorkspaceIdOrderByPositionAscIdAsc(workspaceId)
        }
        val ids = held.map { requireNotNull(it.id) }
        val swapped = listOf(ids[0], ids[2], ids[1], ids[3])

        graphQlTester.document("""mutation { reorderIssueStatuses(workspaceId: $workspaceId, ids: [${swapped.joinToString()}]) { key } }""")
            .execute().path("reorderIssueStatuses[*].key").entityList(String::class.java)
            .containsExactly("OPEN", "REVIEW", "IN_PROGRESS", "CLOSED")
        assertThat(keysOf(workspaceId)).containsExactly("OPEN", "REVIEW", "IN_PROGRESS", "CLOSED")

        graphQlTester.document("""mutation { reorderIssueStatuses(workspaceId: $workspaceId, ids: [${ids[0]}, ${ids[1]}]) { key } }""")
            .execute().errors().expect { it.message?.contains("once each") == true }.verify()

        graphQlTester.document("""mutation { updateIssueStatus(id: ${ids[2]}, label: "QA") { key label } }""")
            .execute()
            .path("updateIssueStatus.key").entity(String::class.java).isEqualTo("REVIEW")
            .path("updateIssueStatus.label").entity(String::class.java).isEqualTo("QA")
        assertThat(audit.findAll().map { it.message }).contains("Issue status Review renamed to QA")

        graphQlTester.document("""mutation { addIssueStatus(workspaceId: $workspaceId, key: "review", label: "Again") { id } }""")
            .execute().errors().expect { it.message?.contains("already has an issue status REVIEW") == true }.verify()
        graphQlTester.document("""mutation { addIssueStatus(workspaceId: $workspaceId, key: "won't fix", label: "Won't fix") { id } }""")
            .execute().errors().expect { it.message?.contains("capital letters, digits and underscores") == true }.verify()
    }

    /** Only somebody who administers the workspace changes the list; anybody who sees it reads it. */
    @Test
    @WithMockUser(username = "mallory", roles = ["USERS"])
    fun `somebody who does not administer the workspace cannot change its statuses`() {
        graphQlTester.document("""mutation { addIssueStatus(workspaceId: $workspaceId, key: "WONTFIX", label: "Won't fix") { id } }""")
            .execute().errors().expect { true }.verify()
        assertThat(statuses.findAll().none { it.key == "WONTFIX" }).isTrue()
    }
}
