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
 * A workspace's issue tracker: filed, filtered, assigned, commented on.
 *
 * The number is the part worth pinning down. It is per workspace, because "#3"
 * is what people say and what they mean is the third issue here - so two
 * workspaces both have a #1, and the ids underneath differ.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class IssueAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val issues: IssueRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val users: AppUserRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private var elsewhereId: Long = 0
    private var bobId: Long = 0

    @BeforeEach
    fun reset() {
        issues.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
        elsewhereId = requireNotNull(workspaces.save(Workspace(name = "billing")).id)
        /*
         * Somebody to hand an issue to, and this test decides what he is called.
         *
         * Reused rather than recreated - the user table is not emptied between
         * classes - but the display name is written every time rather than
         * inherited. It used to take whatever row was already there, and a row
         * is left behind by any test that signs in as `bob` through
         * `@WithMockUser`, which provisions the account with the username as its
         * display name. So this passed or failed on which class Surefire happened
         * to run first: `expected:<Bob> but was:<bob>`, in CI, on a tree where
         * nothing about issues had changed. A fixture that reads another test's
         * leavings is a fixture, not a test.
         */
        bobId = requireNotNull(
            users.save(
                (users.findByUsername("bob") ?: AppUser(username = "bob", displayName = "Bob", type = UserType.INTERNAL))
                    .apply { displayName = "Bob" },
            ).id,
        )
    }

    private fun file(title: String, labels: String = "[]", workspace: Long = workspaceId): Long =
        graphQlTester.document(
            """mutation { createIssue(input: {
                 workspaceId: $workspace, title: "$title", description: "Something to look at", labels: $labels
               }) { id number status reporter } }""",
        ).execute()
            .path("createIssue.status").entity(String::class.java).isEqualTo("OPEN")
            .path("createIssue.reporter").entity(String::class.java).isEqualTo("alice")
            .path("createIssue.id").entity(Long::class.java).get()

    @Test
    fun `issues are numbered from one within each workspace`() {
        file("The reply is late")
        file("The trigger fires twice")
        file("Billing is wrong", workspace = elsewhereId)

        graphQlTester.document("""{ workspaceIssues(workspaceId: $workspaceId) { totalElements content { number title } } }""")
            .execute()
            .path("workspaceIssues.totalElements").entity(Int::class.java).isEqualTo(2)
            // Newest first: a tracker is read from the top.
            .path("workspaceIssues.content[0].number").entity(Int::class.java).isEqualTo(2)
            .path("workspaceIssues.content[1].number").entity(Int::class.java).isEqualTo(1)

        graphQlTester.document("""{ workspaceIssues(workspaceId: $elsewhereId) { content { number title } } }""")
            .execute()
            // Its own #1, not #3.
            .path("workspaceIssues.content[0].number").entity(Int::class.java).isEqualTo(1)
    }

    @Test
    fun `the search reads the title, the description and the labels`() {
        file("The reply is late", labels = """["slack", "urgent"]""")
        file("Something else entirely")

        graphQlTester.document("""{ workspaceIssues(workspaceId: $workspaceId, search: "slack") { content { title } } }""")
            .execute()
            .path("workspaceIssues.content").entityList(Any::class.java).hasSize(1)
            .path("workspaceIssues.content[0].title").entity(String::class.java).isEqualTo("The reply is late")

        graphQlTester.document("""{ workspaceIssues(workspaceId: $workspaceId, search: "reply") { content { title } } }""")
            .execute()
            .path("workspaceIssues.content").entityList(Any::class.java).hasSize(1)

        // The description too, which both of these share.
        graphQlTester.document("""{ workspaceIssues(workspaceId: $workspaceId, search: "look at") { content { title } } }""")
            .execute()
            .path("workspaceIssues.content").entityList(Any::class.java).hasSize(2)
    }

    @Test
    fun `closing one takes it out of the open list and says so in the audit`() {
        val id = file("The reply is late")

        graphQlTester.document("""mutation { updateIssue(id: $id, input: { status: "CLOSED" }) { status } }""")
            .execute()
            .path("updateIssue.status").entity(String::class.java).isEqualTo("CLOSED")

        graphQlTester.document("""{ workspaceIssues(workspaceId: $workspaceId, status: "OPEN") { totalElements } }""")
            .execute()
            .path("workspaceIssues.totalElements").entity(Int::class.java).isEqualTo(0)

        assertThat(audit.findAll().map { it.message }).anySatisfy { assertThat(it).contains("closed") }
    }

    @Test
    fun `a comment lands on the issue, in order, and an empty one is refused`() {
        val id = file("The reply is late")

        graphQlTester.document("""mutation { commentOnIssue(id: $id, content: "Looking at it now") { comments { author content } } }""")
            .execute()
            .path("commentOnIssue.comments[0].author").entity(String::class.java).isEqualTo("alice")

        graphQlTester.document("""mutation { commentOnIssue(id: $id, content: "Fixed in the trigger") { comments { content } } }""")
            .execute()
            .path("commentOnIssue.comments").entityList(Any::class.java).hasSize(2)
            .path("commentOnIssue.comments[1].content").entity(String::class.java).isEqualTo("Fixed in the trigger")

        graphQlTester.document("""mutation { commentOnIssue(id: $id, content: "   ") { id } }""")
            .execute()
            .errors().expect { it.message?.contains("needs something in it") == true }
            .verify()
    }

    /**
     * Sorting by where the talking is, which is not sorting by last change.
     *
     * Closing an issue, relabelling it and assigning it all move the change
     * time, so a tracker sorted that way puts the housekeeping at the top.
     */
    @Test
    fun `sorting by last comment puts the newest conversation first, and silence last`() {
        val quiet = file("Nobody has replied to this one")
        val first = file("Said something a while ago")
        val second = file("Said something just now")

        graphQlTester.document("""mutation { commentOnIssue(id: $first, content: "Looking at it") { id } }""")
            .execute().path("commentOnIssue.id").hasValue()
        graphQlTester.document("""mutation { commentOnIssue(id: $second, content: "Me too") { id } }""")
            .execute().path("commentOnIssue.id").hasValue()

        // Newest conversation first; the issue nobody has replied to is last
        // rather than first, which is where Postgres puts a null descending.
        val newest = graphQlTester
            .document("""{ workspaceIssues(workspaceId: $workspaceId, order: LAST_COMMENT) { content { id lastCommentAt } } }""")
            .execute()
        newest.path("workspaceIssues.content[0].id").entity(Long::class.java).isEqualTo(second)
        newest.path("workspaceIssues.content[1].id").entity(Long::class.java).isEqualTo(first)
        newest.path("workspaceIssues.content[2].id").entity(Long::class.java).isEqualTo(quiet)
        newest.path("workspaceIssues.content[2].lastCommentAt").valueIsNull()

        // And the other way round the silent one is still last: an absent
        // answer is not the oldest one either.
        val oldest = graphQlTester
            .document(
                """{ workspaceIssues(workspaceId: $workspaceId, order: LAST_COMMENT, ascending: true) { content { id } } }""",
            )
            .execute()
        oldest.path("workspaceIssues.content[0].id").entity(Long::class.java).isEqualTo(first)
        oldest.path("workspaceIssues.content[2].id").entity(Long::class.java).isEqualTo(quiet)
    }

    @Test
    fun `a comment can be changed by whoever wrote it, and says that it was`() {
        val id = file("The reply is late")
        val comment = graphQlTester.document(
            """mutation { commentOnIssue(id: $id, content: "Looking") { comments { id editedAt mine } } }""",
        ).execute()
            .path("commentOnIssue.comments[0].mine").entity(Boolean::class.java).isEqualTo(true)
            .path("commentOnIssue.comments[0].editedAt").valueIsNull()
            .path("commentOnIssue.comments[0].id").entity(Long::class.java).get()

        graphQlTester.document(
            """mutation { editIssueComment(id: $comment, content: "Looking, and fixed") { comments { content editedAt } } }""",
        ).execute()
            .path("editIssueComment.comments[0].content").entity(String::class.java).isEqualTo("Looking, and fixed")
            // Marked, so a reader is told it changed.
            .path("editIssueComment.comments[0].editedAt").hasValue()
    }

    @Test
    fun `somebody else's comment is not yours to change`() {
        // Bob's from the start, which is what a second person is - written
        // through the repository because the API only ever writes as whoever
        // is signed in, and that is the whole point of this test.
        val held = issues.save(
            Issue(
                workspaceId = workspaceId,
                number = 1,
                title = "The reply is late",
                reporter = "bob",
                comments = mutableListOf(IssueComment(author = "bob", content = "Mine, not yours")),
            ),
        )
        val comment = requireNotNull(held.comments.first().id)

        graphQlTester.document(
            """mutation { editIssueComment(id: $comment, content: "Not mine") { id } }""",
        ).execute()
            .errors().expect { it.message?.contains("whoever wrote it") == true }
            .verify()
    }

    @Test
    fun `an assignee that is not in this workspace is refused`() {
        val id = file("The reply is late")

        graphQlTester.document(
            """mutation { updateIssue(id: $id, input: { assigneeKind: AGENT, assigneeId: "9999" }) { id } }""",
        ).execute()
            .errors().expect { it.message?.contains("not something in this workspace") == true }
            .verify()
    }

    /**
     * "No one" has to survive the round trip.
     *
     * The page posts its whole form on every save, so an absent assignee is how
     * a caller says it did not touch the box - which left the picker's empty
     * choice with no way to say anything at all, and clearing an assignee
     * silently did nothing. An empty id is the clear; an absent pair is still
     * hands off.
     */
    @Test
    fun `an empty assignee id clears the assignee, and an absent one leaves it alone`() {
        val id = file("The reply is late")

        graphQlTester.document(
            """mutation { updateIssue(id: $id, input: { assigneeKind: USER, assigneeId: "$bobId" }) { assignee { name } } }""",
        ).execute().path("updateIssue.assignee.name").entity(String::class.java).isEqualTo("Bob")

        // A save that does not mention the assignee leaves Bob where he is.
        graphQlTester.document(
            """mutation { updateIssue(id: $id, input: { title: "The reply is still late" }) { assignee { name } } }""",
        ).execute().path("updateIssue.assignee.name").entity(String::class.java).isEqualTo("Bob")

        // A kind by itself names nobody, and is not read as a clear either.
        graphQlTester.document(
            """mutation { updateIssue(id: $id, input: { assigneeKind: AGENT }) { assignee { name } } }""",
        ).execute().path("updateIssue.assignee.name").entity(String::class.java).isEqualTo("Bob")

        // What the picker sends for "No one".
        graphQlTester.document(
            """mutation { updateIssue(id: $id, input: { assigneeId: "" }) { assignee { name } } }""",
        ).execute().path("updateIssue.assignee").valueIsNull()

        assertThat(issues.findById(id).orElseThrow().assignee).isNull()
    }

    @Test
    fun `an assignee id without a kind is refused rather than guessed at`() {
        val id = file("The reply is late")

        graphQlTester.document(
            """mutation { updateIssue(id: $id, input: { assigneeId: "$bobId" }) { id } }""",
        ).execute()
            .errors().expect { it.message?.contains("without a kind") == true }
            .verify()
    }

    @Test
    fun `a title is required, and blank does not count`() {
        graphQlTester.document(
            """mutation { createIssue(input: { workspaceId: $workspaceId, title: "   " }) { id } }""",
        ).execute()
            .errors().expect { it.message?.contains("needs a title") == true }
            .verify()
    }

    /**
     * What a workspace has reached for lately is offered first.
     *
     * The box under the label field offers six of them, so the order is the
     * whole of whether the label somebody wants is on the screen. A tracker
     * settles into a handful it is using and a long tail it used once, and the
     * alphabet files both together.
     *
     * The tail is what the history has never seen - labels on issues filed
     * before any of this was recorded - and those are read by name.
     */
    @Test
    fun `labels are offered most recently used first, and the rest by name`() {
        /*
         * Filed straight into the table, the way an issue that predates the
         * history is: no event was ever written for this label.
         */
        issues.save(
            Issue(
                workspaceId = workspaceId,
                number = 900,
                title = "Filed before any of this was recorded",
                reporter = "alice",
                labels = mutableSetOf("ancient"),
            ),
        )

        file("The trigger fires twice", labels = """["timing"]""")
        val late = file("The reply is late", labels = """["slack"]""")
        graphQlTester.document(
            """mutation { updateIssue(id: $late, input: { labels: ["slack", "urgent"] }) { id } }""",
        ).execute().path("updateIssue.id").hasValue()

        graphQlTester.document("""{ workspaceIssueLabels(workspaceId: $workspaceId) }""")
            .execute()
            .path("workspaceIssueLabels").entityList(String::class.java)
            .containsExactly("urgent", "slack", "timing", "ancient")
    }

    /**
     * A label taken off everything stops being offered.
     *
     * The history keeps saying it was used, because it was; the list is of
     * labels in use, and reading the history alone would offer back every
     * label the workspace ever abandoned.
     */
    @Test
    fun `a label nothing carries any more is not offered`() {
        val id = file("The reply is late", labels = """["slack"]""")
        graphQlTester.document(
            """mutation { updateIssue(id: $id, input: { labels: ["timing"] }) { id } }""",
        ).execute().path("updateIssue.id").hasValue()

        graphQlTester.document("""{ workspaceIssueLabels(workspaceId: $workspaceId) }""")
            .execute()
            .path("workspaceIssueLabels").entityList(String::class.java).containsExactly("timing")
    }
}
