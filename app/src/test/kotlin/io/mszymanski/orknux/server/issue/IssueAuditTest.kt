package io.mszymanski.orknux.server.issue

import io.mszymanski.orknux.server.mcp.IssueTools
import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.user.AppUser
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.UserType
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.context.support.WithMockUser

/**
 * The tracker has two doors, and the audit log has to see through both.
 *
 * An issue can be filed from the page and it can be filed by an agent calling
 * `orknux_open_issue`; the same is true of closing one. The audit log answers
 * "what happened here and who did it", and it answered it for one of those two
 * - so a workspace where the agents do most of the filing had an audit trail
 * with the agents' work missing from it, which is the half that most needed
 * writing down.
 *
 * The assertions are deliberately about the entries being *the same*, not about
 * an entry existing on each side. Two doors writing two phrasings is the bug
 * one step further along: the log is read as one list, and a search for
 * "closed" that finds the page's closures and not the agents' is the same
 * silence in a different place.
 *
 * News is not this. `IssueNewsDesk` decides who should hear about an issue and
 * is asked from both doors already; the audit log records what was done and by
 * whom, whether or not anybody was told. An issue nobody observes still gets an
 * audit entry, and that is the point of having both.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class IssueAuditTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val tools: IssueTools,
    @Autowired val issues: IssueRepository,
    @Autowired val observers: IssueObserverRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val users: AppUserRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var scope: OrknuxScope

    @BeforeEach
    fun reset() {
        observers.deleteAll()
        issues.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
        scope = OrknuxScope(workspaceId = workspaceId, mayWrite = true)
        if (users.findByUsername("bob") == null) {
            users.save(AppUser(username = "bob", displayName = "Bob", type = UserType.INTERNAL))
        }
    }

    private fun messages(): List<String> =
        audit.findAll().filter { it.workspaceId == workspaceId }.map { it.message }

    /** Filed through the page, the way a person does it. */
    private fun fileFromThePage(title: String): Long =
        graphQlTester.document(
            """mutation { createIssue(input: { workspaceId: $workspaceId, title: "$title" }) { id number } }""",
        ).execute().path("createIssue.id").entity(Long::class.java).get()

    @Test
    fun `an issue opened through either door is audited, in the same words`() {
        fileFromThePage("The reply is late")
        tools.open(scope, """{"title": "The webhook answers 500", "observers": "bob"}""")

        assertThat(messages()).contains(
            "Issue #1 \"The reply is late\" opened",
            "Issue #2 \"The webhook answers 500\" opened",
        )

        // Attributed, both of them, and to the caller rather than to the door.
        assertThat(audit.findAll().filter { it.message.endsWith("opened") }.map { it.userId })
            .containsOnly("alice")

        // And filed under the category every other issue entry already uses, so
        // the workspace audit page's filter shows them together.
        assertThat(audit.findAll().filter { it.message.endsWith("opened") }.map { it.category })
            .containsOnly(WorkspaceAuditCategory.WORKSPACE)
    }

    @Test
    fun `closing and reopening through either door is audited, in the same words`() {
        val fromThePage = fileFromThePage("The reply is late")
        tools.open(scope, """{"title": "The webhook answers 500"}""")

        graphQlTester.document("""mutation { updateIssue(id: $fromThePage, input: { status: "CLOSED" }) { status } }""")
            .execute().path("updateIssue.status").entity(String::class.java).isEqualTo("CLOSED")
        tools.setStatus(scope, """{"issue": 2, "status": "CLOSED"}""")

        assertThat(messages()).contains("Issue #1 closed", "Issue #2 closed")

        // Reopening is the other half of the same fact, and is said as such
        // rather than as another "status changed".
        graphQlTester.document("""mutation { updateIssue(id: $fromThePage, input: { status: "OPEN" }) { status } }""")
            .execute().path("updateIssue.status").entity(String::class.java).isEqualTo("OPEN")
        tools.setStatus(scope, """{"issue": 2, "status": "OPEN"}""")

        assertThat(messages()).contains("Issue #1 reopened", "Issue #2 reopened")
    }

    /**
     * The page sends the whole form back on every save, and the tools can be
     * told to set the status an issue already has. Neither is a change, and an
     * audit log that says so is one nobody reads.
     */
    @Test
    fun `a status that did not move writes nothing, through either door`() {
        val fromThePage = fileFromThePage("The reply is late")
        tools.open(scope, """{"title": "The webhook answers 500"}""")
        val before = messages().size

        graphQlTester.document("""mutation { updateIssue(id: $fromThePage, input: { status: "OPEN" }) { status } }""")
            .execute().path("updateIssue.status").entity(String::class.java).isEqualTo("OPEN")
        tools.setStatus(scope, """{"issue": 2, "status": "OPEN"}""")

        assertThat(messages()).hasSize(before)
    }

    /**
     * Who was put on an issue, whichever door put them there. The tools name
     * observers on the call that files the issue; the page adds them
     * afterwards. Both are the same question to somebody reading the log.
     */
    @Test
    fun `observers named on the tool call are audited the way the page audits them`() {
        tools.open(scope, """{"title": "The webhook answers 500", "observers": "bob"}""")

        assertThat(messages()).contains("Issue #1: bob is now an observer")
    }

    /**
     * The audit names whoever the issue names, not whichever door was used.
     */
    @Test
    @WithMockUser(username = "Claude", roles = ["USERS"])
    fun `an issue filed by an agent is audited under the agent`() {
        tools.open(scope, """{"title": "The webhook answers 500"}""")

        val made = requireNotNull(issues.findByWorkspaceIdAndNumber(workspaceId, 1))
        val entry = requireNotNull(audit.findAll().firstOrNull { it.message.endsWith("opened") })
        assertThat(entry.userId).isEqualTo("Claude").isEqualTo(made.reporter)
    }

    /**
     * A tool call with nobody signed in behind it, which is the ordinary case
     * on one of the three ways into these tools: an agent reached from a
     * workflow that a schedule started has no request and no principal.
     *
     * This is why the entry is written through `recordAutomated`.
     * `WorkspaceAuditRecorder.record` reads the security context itself and
     * throws when it finds nobody - so filing an issue on that path would have
     * come back to the model as a failure *after* the issue was saved, which is
     * a worse outcome than the missing audit entry this whole change is about.
     * The tools already have a name for this case and the issue's own
     * `reporter` carries it; the audit carries the same one.
     */
    @Test
    fun `an issue filed with nobody signed in is still audited, under the platform`() {
        SecurityContextHolder.clearContext()

        val answer = tools.open(scope, """{"title": "The schedule fired at the wrong hour"}""")
        assertThat(answer).doesNotContain("error")

        val made = requireNotNull(issues.findByWorkspaceIdAndNumber(workspaceId, 1))
        val entry = requireNotNull(audit.findAll().firstOrNull { it.message.endsWith("opened") })
        assertThat(entry.userId).isEqualTo("orknux").isEqualTo(made.reporter)

        // And closing it, from the same nowhere.
        tools.setStatus(scope, """{"issue": 1, "status": "CLOSED"}""")
        assertThat(messages()).contains("Issue #1 closed")
    }
}
