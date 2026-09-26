package io.mszymanski.orknux.server.issue

import io.mszymanski.orknux.server.user.AppUser
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.UserType
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
 * The bell beside somebody's name.
 *
 * It reads the same feed an assistant reads over MCP, on purpose: two records
 * of what happened on an issue would eventually disagree, and the one nobody is
 * looking at would be the one that was right. What these pin down is that the
 * number means something - it does not clear itself by being asked for, it
 * counts across workspaces, and a name written in a comment reaches the person
 * whose name it is even when the issue is nothing to do with them.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class NotificationAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val issues: IssueRepository,
    @Autowired val news: IssueNewsRepository,
    @Autowired val marks: IssueNewsReadRepository,
    @Autowired val desk: IssueNewsDesk,
    @Autowired val users: AppUserRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var supportId: Long = 0
    private var billingId: Long = 0

    @BeforeEach
    fun reset() {
        news.deleteAll()
        marks.deleteAll()
        issues.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        users.deleteAll()

        supportId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
        billingId = requireNotNull(workspaces.save(Workspace(name = "billing")).id)
        users.save(AppUser(username = "alice", displayName = "Alice Brown", type = UserType.INTERNAL))
    }

    /** An issue alice filed, so anything that happens to it is her news. */
    private fun file(workspaceId: Long, title: String): Long =
        graphQlTester.document(
            """mutation { createIssue(input: { workspaceId: $workspaceId, title: "$title" }) { id } }""",
        ).execute().path("createIssue.id").entity(Long::class.java).get()

    private fun count(): Int =
        graphQlTester.document("{ myNotificationCount }").execute().path("myNotificationCount").entity(Int::class.java).get()

    @Test
    fun `what happens on an issue you filed reaches you, wherever it is`() {
        val here = file(supportId, "The reply is late")
        val elsewhere = file(billingId, "The invoice is wrong")

        // Somebody else acts on both; the reporter hears about both.
        desk.commented(issues.findById(here).get(), "bob", "Looking now")
        desk.statusChanged(issues.findById(elsewhere).get().apply { status = IssueStatuses.CLOSED }, "bob")

        assertThat(count()).isEqualTo(2)
        val kinds = graphQlTester.document("{ myNotifications { issueTitle kind actor } }")
            .execute()
            .path("myNotifications[*].kind")
            .entityList(String::class.java)
            .get()
        assertThat(kinds).containsExactlyInAnyOrder("STATUS", "COMMENT")
    }

    @Test
    fun `asking does not clear it, and saying so does`() {
        val id = file(supportId, "The reply is late")
        desk.commented(issues.findById(id).get(), "bob", "Looking now")

        // Asked twice, still there: a number that clears itself the moment it
        // is read is a number nobody ever sees.
        assertThat(count()).isEqualTo(1)
        assertThat(count()).isEqualTo(1)

        graphQlTester.document("mutation { readMyNotifications }")
            .execute()
            .path("readMyNotifications")
            .entity(Int::class.java)
            .isEqualTo(1)
        assertThat(count()).isZero()
    }

    @Test
    fun `nobody is told about their own doing`() {
        val id = file(supportId, "The reply is late")
        // alice filed it and alice commented on it.
        graphQlTester.document("""mutation { commentOnIssue(id: $id, content: "Mine") { id } }""").execute()

        assertThat(count()).isZero()
    }

    /**
     * The one a watcher list would never catch: an issue alice has nothing to
     * do with, in which somebody writes her name.
     */
    @Test
    fun `being named in a comment reaches you even on somebody else's issue`() {
        val id = issues.save(
            Issue(workspaceId = supportId, number = 99, title = "Nothing to do with alice", reporter = "bob"),
        ).id!!

        desk.commented(issues.findById(id).get(), "bob", "@Alice Brown could you look at this?")

        assertThat(count()).isEqualTo(1)
        graphQlTester.document("{ myNotifications { kind says } }")
            .execute()
            .path("myNotifications[0].kind")
            .entity(String::class.java)
            .isEqualTo("MENTIONED")
    }
}
