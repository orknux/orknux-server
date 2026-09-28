package io.mszymanski.orknux.server.issue

import io.mszymanski.orknux.server.attachment.AttachmentProperties
import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.mcp.OrknuxTools
import io.mszymanski.orknux.server.security.Role
import io.mszymanski.orknux.server.security.RoleRepository
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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.WithMockUser
import java.nio.file.Files
import java.nio.file.Path

/**
 * Taking a comment off an issue - issue #276.
 *
 * The schema could add a comment and change one and had nothing that removed
 * one, so a comment posted by mistake, or one carrying something that should
 * not be there, was permanent.
 *
 * What is worth pinning down here is the word *removed*. This deletes rather
 * than tombstones, and a delete that leaves copies of the text lying about in
 * other tables is a tombstone wearing a delete's name - so the assertions that
 * matter most in this file are the ones that go looking for the words
 * afterwards, in every column the tracker ever wrote them to. The secret is a
 * string nothing else in the suite says, and it is searched for by `like` over
 * the whole table rather than by id, because the question is not "did the row
 * this test knows about go" but "is it anywhere".
 *
 * The other half is that a deleted comment must not be a silent hole. The
 * history says one was removed, by whom and whose it was, and never a word of
 * what it said - which is the whole design in one row.
 *
 * The location is pointed at the build directory so a run does not leave
 * uploads in the working tree, the way [IssueAttachmentTest] does.
 */
@SpringBootTest(properties = ["orknux.attachments.location=target/test-attachments"])
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class IssueCommentRemovalTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val issues: IssueRepository,
    @Autowired val events: IssueEventRepository,
    @Autowired val news: IssueNewsRepository,
    @Autowired val marks: IssueNewsReadRepository,
    @Autowired val desk: IssueNewsDesk,
    @Autowired val uploads: IssueAttachmentAPI,
    @Autowired val attachments: IssueAttachmentRepository,
    @Autowired val observers: IssueObserverRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val users: AppUserRepository,
    @Autowired val roles: RoleRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val properties: AttachmentProperties,
    @Autowired val settings: InstallationSettingRepository,
    @Autowired val tools: OrknuxTools,
    @Autowired val jdbc: JdbcTemplate,
) {

    /** Where bob can see and cannot administer. */
    private var supportId: Long = 0

    /** Where bob administers. Two workspaces because the rule differs in them. */
    private var billingId: Long = 0

    @BeforeEach
    fun reset() {
        news.deleteAll()
        marks.deleteAll()
        events.deleteAll()
        observers.deleteAll()
        attachments.deleteAll()
        /*
         * The attachments switch is a row, so a class that turned it off left it
         * off for whatever ran next - which is how the file half of this failed
         * only when the whole suite was run. `IssueAttachmentTest` clears it for
         * the same reason.
         */
        settings.deleteAll()
        issues.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        users.deleteAll()
        roles.deleteAll(roles.findAll().filterNot { it.builtin })

        val support = roles.save(Role(name = "support"))
        supportId = requireNotNull(workspaces.save(Workspace(name = "support", roles = mutableSetOf(support))).id)
        // The same role opens this one *and* administers it, which is what makes
        // bob a workspace administrator here and an ordinary member next door.
        billingId = requireNotNull(
            workspaces.save(
                Workspace(name = "billing", roles = mutableSetOf(support), adminRoles = mutableSetOf(support)),
            ).id,
        )

        val admin = roles.findAll().first { it.administers }
        users.save(
            AppUser(username = "alice", displayName = "Alice", type = UserType.INTERNAL, roles = mutableSetOf(admin)),
        )
        users.save(AppUser(username = "bob", displayName = "Bob", type = UserType.INTERNAL))
    }

    private fun file(title: String = "The reply is late", workspace: Long = supportId): Long =
        graphQlTester.document(
            """mutation { createIssue(input: { workspaceId: $workspace, title: "$title" }) { id } }""",
        ).execute().path("createIssue.id").entity(Long::class.java).get()

    /**
     * The comments on an issue, as the API hands them over.
     *
     * Read through GraphQL rather than off the entity because the collection is
     * lazy and this test holds no session; and because what a reader is shown is
     * half of what "removed" has to mean.
     */
    private fun commentsOn(workspace: Long, number: Int): List<Map<String, Any?>> =
        graphQlTester.document(
            """{ workspaceIssue(workspaceId: $workspace, number: $number) {
                   comments { id author content mine mayRemove attachments { filename } }
                   lastCommentAt
                 } }""",
        ).execute()
            .path("workspaceIssue.comments")
            .entity(object : org.springframework.core.ParameterizedTypeReference<List<Map<String, Any?>>>() {})
            .get()

    /*
     * Read as the issue rather than as the field, because the answer this is
     * most interested in is null and `entity(String)` cannot carry one - the
     * mapper is handed no type at all and throws before the assertion.
     */
    private fun lastCommentAt(workspace: Long, number: Int): String? =
        graphQlTester.document("""{ workspaceIssue(workspaceId: $workspace, number: $number) { lastCommentAt } }""")
            .execute()
            .path("workspaceIssue")
            .entity(object : org.springframework.core.ParameterizedTypeReference<Map<String, Any?>>() {})
            .get()["lastCommentAt"] as String?

    private fun history(workspace: Long, number: Int): List<Map<String, Any?>> =
        graphQlTester.document(
            """{ issueHistory(workspaceId: $workspace, number: $number) {
                   entries { kind actor was became said commentId }
                 } }""",
        ).execute()
            .path("issueHistory.entries")
            .entity(object : org.springframework.core.ParameterizedTypeReference<List<Map<String, Any?>>>() {})
            .get()

    /** How many rows anywhere in the tracker still hold these words. */
    private fun copiesOf(said: String): Int {
        val inComments = jdbc.queryForObject(
            "select count(*) from workspace_issue_comment where content like ?",
            Int::class.java,
            "%$said%",
        ) ?: 0
        val inNews = jdbc.queryForObject(
            "select count(*) from issue_news where says like ?",
            Int::class.java,
            "%$said%",
        ) ?: 0
        val inHistory = jdbc.queryForObject(
            "select count(*) from workspace_issue_event where was like ? or became like ?",
            Int::class.java,
            "%$said%",
            "%$said%",
        ) ?: 0
        return inComments + inNews + inHistory
    }

    private fun remove(comment: Long) =
        graphQlTester.document("""mutation { removeIssueComment(id: $comment) { id comments { id } } }""").execute()

    private fun onDisk(location: String): Path =
        Path.of(properties.location).toAbsolutePath().normalize().resolve(location)

    /**
     * The whole of it in one issue: the comment goes, the words go with it, and
     * the thread does not pretend nothing happened.
     */
    @Test
    fun `a comment is removed, the words are gone from the database, and the history says so`() {
        val secret = "orkx-test-token-a41f9"
        val id = file()
        val comment = graphQlTester.document(
            """mutation { commentOnIssue(id: $id, content: "Here is the key: $secret") { comments { id } } }""",
        ).execute().path("commentOnIssue.comments[0].id").entity(Long::class.java).get()

        // Somebody was told, and the telling carries the words.
        assertThat(copiesOf(secret)).isGreaterThanOrEqualTo(1)

        remove(comment).path("removeIssueComment.comments").entityList(Any::class.java).hasSize(0)

        // Off the page, and out of every column the tracker writes text to.
        assertThat(commentsOn(supportId, 1)).isEmpty()
        assertThat(copiesOf(secret)).isZero()

        // And the thread is not silently one message short.
        val told = history(supportId, 1)
        assertThat(told).anySatisfy { line ->
            assertThat(line["kind"]).isEqualTo("COMMENT_REMOVED")
            assertThat(line["actor"]).isEqualTo("alice")
            // Whose it was, so the line can be argued with later.
            assertThat(line["was"]).isEqualTo("alice")
            assertThat(line["became"]).isNull()
            // Never what it said. That is the point of removing it.
            assertThat(line["said"]).isNull()
        }
        // The comment's own line has gone with the comment; only the removal is left.
        assertThat(told.map { it["kind"] }).doesNotContain("COMMENT")

        assertThat(audit.findAll().map { it.message })
            .anySatisfy { assertThat(it).contains("removed a comment by alice") }
    }

    /**
     * The bell's copy is the one that would have been missed.
     *
     * `issue_news.says` holds the comment in full and one row is written per
     * person told, so an issue with watchers keeps a copy of every comment for
     * every one of them. A removal that stopped at the comments table would be a
     * removal in the interface only.
     */
    @Test
    fun `what the bell was told about a comment goes when the comment does`() {
        val secret = "orkx-test-token-b73c2"
        val id = file()
        // Bob hears about this issue, so there is somebody for the news to be
        // addressed to - the desk never tells anybody about their own doing.
        graphQlTester.document(
            """mutation { observeIssue(id: $id, observerKind: USER, observerId: "${users.findByUsername("bob")?.id}") { id } }""",
        ).execute().path("observeIssue.id").hasValue()

        val comment = graphQlTester.document(
            """mutation { commentOnIssue(id: $id, content: "The password is $secret") { comments { id } } }""",
        ).execute().path("commentOnIssue.comments[0].id").entity(Long::class.java).get()

        val written = news.findByCommentId(comment)
        assertThat(written).isNotEmpty()
        assertThat(written).allSatisfy { assertThat(it.says).contains(secret) }

        remove(comment).path("removeIssueComment.id").hasValue()

        assertThat(news.findByCommentId(comment)).isEmpty()
        assertThat(copiesOf(secret)).isZero()
    }

    /**
     * The files go with it, rows and bytes.
     *
     * A comment's attachment is claimed by that comment and by nothing else, so
     * there is nothing else pointing at it to break - and a file whose comment is
     * gone is one no page can draw and nobody can remove. Often it is the
     * screenshot with the credential in it.
     */
    @Test
    fun `removing a comment takes its files, and leaves the issue's own alone`() {
        val id = file()

        uploads.upload(supportId, listOf(MockMultipartFile("files", "log.txt", "text/plain", "a log".toByteArray())))
        val withComment = attachments.findAll().first { it.filename == "log.txt" }
        uploads.upload(supportId, listOf(MockMultipartFile("files", "shot.png", "image/png", "a shot".toByteArray())))
        val onIssue = attachments.findAll().first { it.filename == "shot.png" }

        graphQlTester.document("""mutation { attachToIssue(id: $id, attachmentIds: [${onIssue.id}]) { id } }""")
            .execute().path("attachToIssue.id").hasValue()
        val comment = graphQlTester.document(
            """mutation { commentOnIssue(id: $id, content: "Here it is", attachmentIds: [${withComment.id}]) {
                 comments { id attachments { filename } }
               } }""",
        ).execute()
            .path("commentOnIssue.comments[0].attachments[0].filename").entity(String::class.java).isEqualTo("log.txt")
            .path("commentOnIssue.comments[0].id").entity(Long::class.java).get()

        assertThat(Files.exists(onDisk(withComment.location))).isTrue()

        remove(comment).path("removeIssueComment.id").hasValue()

        // The comment's file: row and bytes both.
        assertThat(attachments.findAll().map { it.filename }).doesNotContain("log.txt")
        assertThat(Files.exists(onDisk(withComment.location))).isFalse()
        // The issue's own file was never part of this.
        assertThat(attachments.findAll().map { it.filename }).contains("shot.png")
        assertThat(Files.exists(onDisk(onIssue.location))).isTrue()
    }

    /**
     * `lastCommentAt` is what the LAST_COMMENT sort reads, so a removal that
     * left it pointing at a comment nobody can find is a list ordered by a lie.
     */
    @Test
    fun `removing the newest comment moves the last-comment mark back to the one before it`() {
        val id = file()
        val first = graphQlTester.document(
            """mutation { commentOnIssue(id: $id, content: "Looking") { comments { id } } }""",
        ).execute().path("commentOnIssue.comments[0].id").entity(Long::class.java).get()
        val second = graphQlTester.document(
            """mutation { commentOnIssue(id: $id, content: "Still looking") { comments { id } } }""",
        ).execute().path("commentOnIssue.comments[1].id").entity(Long::class.java).get()

        val afterBoth = lastCommentAt(supportId, 1)
        assertThat(afterBoth).isNotNull()

        remove(second).path("removeIssueComment.id").hasValue()
        val afterOne = lastCommentAt(supportId, 1)
        // Back to the first one's moment, which is earlier - not cleared, and not
        // left where it was.
        assertThat(afterOne).isNotNull()
        assertThat(afterOne).isNotEqualTo(afterBoth)

        remove(first).path("removeIssueComment.id").hasValue()
        // Nobody has said anything here now, which is exactly what null means.
        assertThat(lastCommentAt(supportId, 1)).isNull()
    }

    /**
     * Somebody who can see the workspace and does not administer it may not
     * remove what somebody else wrote - and is told which two things would have
     * let them.
     */
    @Test
    @WithMockUser(username = "bob", roles = ["SUPPORT"])
    fun `somebody else's comment is not yours to remove without administering the workspace`() {
        val held = issues.save(
            Issue(
                workspaceId = supportId,
                number = 1,
                title = "The reply is late",
                reporter = "alice",
                comments = mutableListOf(IssueComment(author = "alice", content = "Hers, not his")),
            ),
        )
        val comment = requireNotNull(held.comments.first().id)

        remove(comment)
            .errors().expect { it.message?.contains("whoever wrote it, or by an administrator") == true }
            .verify()

        // Still there, and the page is not offering him the button either.
        val left = commentsOn(supportId, 1)
        assertThat(left).hasSize(1)
        assertThat(left.first()["mine"]).isEqualTo(false)
        assertThat(left.first()["mayRemove"]).isEqualTo(false)
    }

    /**
     * The same person, the same comment, in the workspace he administers.
     *
     * This is the case the whole rule exists for: whoever notices a credential in
     * a thread is very often not whoever pasted it, and the only other way out
     * already in the product is deleting the issue, which takes the whole thread.
     */
    @Test
    @WithMockUser(username = "bob", roles = ["SUPPORT"])
    fun `an administrator of the workspace may remove somebody else's comment`() {
        val held = issues.save(
            Issue(
                workspaceId = billingId,
                number = 1,
                title = "The invoice is late",
                reporter = "alice",
                comments = mutableListOf(IssueComment(author = "alice", content = "Hers, not his")),
            ),
        )
        val comment = requireNotNull(held.comments.first().id)

        assertThat(commentsOn(billingId, 1).first()["mayRemove"]).isEqualTo(true)

        remove(comment).path("removeIssueComment.comments").entityList(Any::class.java).hasSize(0)
        assertThat(commentsOn(billingId, 1)).isEmpty()

        // Named as his doing, and whose comment it was.
        val removals = events.findAll().filter { it.kind == IssueEventKind.COMMENT_REMOVED }
        assertThat(removals).hasSize(1)
        assertThat(removals.first().actor).isEqualTo("bob")
        assertThat(removals.first().was).isEqualTo("alice")
    }

    /** Editing is still the narrower rule: not even an administrator's to do. */
    @Test
    @WithMockUser(username = "bob", roles = ["SUPPORT"])
    fun `administering the workspace does not make somebody else's comment yours to edit`() {
        val held = issues.save(
            Issue(
                workspaceId = billingId,
                number = 1,
                title = "The invoice is late",
                reporter = "alice",
                comments = mutableListOf(IssueComment(author = "alice", content = "Hers, not his")),
            ),
        )
        val comment = requireNotNull(held.comments.first().id)

        graphQlTester.document("""mutation { editIssueComment(id: $comment, content: "Put words here") { id } }""")
            .execute()
            .errors().expect { it.message?.contains("whoever wrote it") == true }
            .verify()
    }

    /**
     * No agent removes what a person said.
     *
     * `orknux_comment_on_issue` adds to a thread; nothing offered over MCP takes
     * anything out of one, and there is no `orknux_delete_issue` either. A tool
     * that could erase what people said is a different product, and the surface
     * is the place that decides - so this asks the surface rather than the
     * documentation.
     */
    @Test
    fun `no tool an agent can be given removes a comment`() {
        val scope = OrknuxScope(workspaceId = supportId, mayWrite = true)
        val offered = tools.specs(scope).map { it.name }

        assertThat(offered).contains("orknux_comment_on_issue")
        // Deleting a whole issue is a tool since #478 - an agent tidying up a
        // duplicate it filed - and takes its comments with it. Taking one comment
        // off an issue is still a person's to do.
        assertThat(offered.filter { (it.contains("remove") || it.contains("delete")) && it != "orknux_delete_issue" })
            .isEmpty()
        assertThat(offered).doesNotContain("orknux_remove_issue_comment")

        // And a name invented by a model is refused rather than dispatched.
        assertThat(tools.run(scope, "orknux_remove_issue_comment", "{}")).contains("orknux_remove_issue_comment")
    }
}
