package io.mszymanski.orknux.server.issue

import io.mszymanski.orknux.server.mcp.IssueTools
import io.mszymanski.orknux.server.mcp.OrknuxScope
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
import org.springframework.core.ParameterizedTypeReference
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.context.support.WithMockUser
import java.time.OffsetDateTime

/**
 * What happened to an issue, and who did it.
 *
 * The tracker recorded pieces of this in three places and none of them was a
 * history: the audit log is free text keyed by a workspace, the news is written
 * once per person told, and nothing at all wrote down a label changing or an
 * issue changing hands. What these pin down is that every kind of change now
 * lands in one ordered list with an actor on it, that both doors into the
 * tracker write to it, and - the one worth reading twice - that an issue older
 * than the record says so rather than showing a quiet week it never had.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class IssueHistoryTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val issues: IssueRepository,
    @Autowired val events: IssueEventRepository,
    @Autowired val observers: IssueObserverRepository,
    @Autowired val news: IssueNewsRepository,
    @Autowired val marks: IssueNewsReadRepository,
    @Autowired val issueTools: IssueTools,
    @Autowired val users: AppUserRepository,
    @Autowired val roles: RoleRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private var bobId: Long = 0

    @BeforeEach
    fun reset() {
        news.deleteAll()
        marks.deleteAll()
        events.deleteAll()
        observers.deleteAll()
        issues.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        users.deleteAll()
        roles.deleteAll(roles.findAll().filterNot { it.builtin })

        val support = roles.save(Role(name = "support"))
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support", roles = mutableSetOf(support))).id)

        val admin = roles.findAll().first { it.administers }
        users.save(
            AppUser(username = "alice", displayName = "Alice", type = UserType.INTERNAL, roles = mutableSetOf(admin)),
        )
        bobId = requireNotNull(
            users.save(AppUser(username = "bob", displayName = "Bob", type = UserType.INTERNAL)).id,
        )
    }

    private fun file(title: String = "The reply is late"): Long =
        graphQlTester.document(
            """mutation { createIssue(input: { workspaceId: $workspaceId, title: "$title" }) { id } }""",
        ).execute().path("createIssue.id").entity(Long::class.java).get()

    private fun history(number: Int = 1, limit: Int? = null): List<Map<String, Any?>> {
        val asked = if (limit == null) "" else ", limit: $limit"
        return graphQlTester.document(
            """{ issueHistory(workspaceId: $workspaceId, number: $number$asked) {
                   earlier
                   entries { id kind actor was became said edited commentId }
                 } }""",
        ).execute()
            .path("issueHistory.entries")
            .entity(object : ParameterizedTypeReference<List<Map<String, Any?>>>() {})
            .get()
    }

    private fun <T> asUser(name: String, block: () -> T): T {
        val held = SecurityContextHolder.getContext().authentication
        /*
         * The same authorities under a different name. What is being changed
         * here is who is doing it, not what they may do - a caller with no
         * authorities cannot see the workspace at all, and every one of these
         * would fail as "no such issue" long before reaching the history.
         */
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(name, "n/a", held?.authorities.orEmpty())
        try {
            return block()
        } finally {
            SecurityContextHolder.getContext().authentication = held
        }
    }

    /**
     * The whole list, in the order it happened, with a name against each line.
     *
     * One test rather than six, because the thing being checked is the order as
     * much as the entries: a history is read as a story, and each of these is
     * only meaningful in the place it lands.
     */
    @Test
    fun `every kind of change lands in the history, in order, with an actor`() {
        val id = file()

        graphQlTester.document(
            """mutation { updateIssue(id: $id, input: { title: "The reply is late", status: "IN_PROGRESS",
                 labels: ["slack"], assigneeKind: USER, assigneeId: "$bobId" }) { id } }""",
        ).execute().path("updateIssue.id").hasValue()

        asUser("bob") {
            graphQlTester.document(
                """mutation { commentOnIssue(id: $id, content: "Looking at it now") { id } }""",
            ).execute().path("commentOnIssue.id").hasValue()
        }

        graphQlTester.document(
            """mutation { observeIssue(id: $id, observerKind: USER, observerId: "$bobId") { id } }""",
        ).execute().path("observeIssue.id").hasValue()

        // Labels swapped and the issue put back down in one save, which is what
        // the page does: everything on the form is sent whether it moved or not.
        graphQlTester.document(
            """mutation { updateIssue(id: $id, input: { title: "The reply is late", status: "CLOSED",
                 labels: ["timing"], assigneeKind: USER, assigneeId: "" }) { id } }""",
        ).execute().path("updateIssue.id").hasValue()

        graphQlTester.document(
            """mutation { unobserveIssue(id: $id, observerKind: USER, observerId: "$bobId") { id } }""",
        ).execute().path("unobserveIssue.id").hasValue()

        val told = history()
        assertThat(told.map { "${it["kind"]} ${it["actor"]} ${it["was"]}>${it["became"]}" }).containsExactly(
            // Read off the issue itself, so it is there for every issue there is.
            "OPENED alice null>null",
            "STATUS alice OPEN>IN_PROGRESS",
            "LABEL alice null>slack",
            "ASSIGNEE alice null>Bob",
            "COMMENT bob null>null",
            "OBSERVER alice null>Bob",
            "STATUS alice IN_PROGRESS>CLOSED",
            "LABEL alice null>timing",
            "LABEL alice slack>null",
            "ASSIGNEE alice Bob>null",
            "OBSERVER alice Bob>null",
        )

        // The comment is there to be recognised rather than re-read: the thread
        // itself is one tab away, and a history that reproduces it is a second
        // copy of the same page.
        val said = told.first { it["kind"] == "COMMENT" }
        assertThat(said["said"]).isEqualTo("Looking at it now")
        assertThat(said["edited"]).isEqualTo(false)
        assertThat(said["commentId"]).isNotNull()

        // Ids are unique across the three sources, or a list keyed by them
        // would draw event 5 and comment 5 as one row.
        assertThat(told.map { it["id"] }).doesNotHaveDuplicates()
    }

    /**
     * Saving the page without changing anything writes nothing.
     *
     * The form posts every field on every save, so a history that recorded what
     * arrived rather than what changed would fill up with an issue being
     * assigned to the person who already had it.
     */
    @Test
    fun `a save that changed nothing is not a line in the history`() {
        val id = file()
        graphQlTester.document(
            """mutation { updateIssue(id: $id, input: { title: "The reply is late", status: "OPEN",
                 labels: [], assigneeKind: USER, assigneeId: "$bobId" }) { id } }""",
        ).execute().path("updateIssue.id").hasValue()

        val was = events.findAll().size
        repeat(2) {
            graphQlTester.document(
                """mutation { updateIssue(id: $id, input: { title: "The reply is late", status: "OPEN",
                     labels: [], assigneeKind: USER, assigneeId: "$bobId" }) { id } }""",
            ).execute().path("updateIssue.id").hasValue()
        }
        assertThat(events.findAll()).hasSize(was)
    }

    /**
     * The other door writes here too.
     *
     * An agent works the tracker through the MCP tools, which write no audit
     * line at all - so a history assembled from the audit log would have had
     * its holes exactly where the unattended work happened.
     */
    @Test
    fun `what an agent changes through the tools is in the history as well`() {
        val id = file()
        val scope = OrknuxScope(workspaceId = workspaceId, mayWrite = true)

        asUser("bob") {
            issueTools.setStatus(scope, """{"issue": 1, "status": "CLOSED"}""")
            issueTools.update(scope, """{"issue": 1, "add_labels": "p1"}""")
        }

        assertThat(history().map { "${it["kind"]} ${it["actor"]} ${it["became"]}" })
            .contains("STATUS bob CLOSED", "LABEL bob p1")
        assertThat(events.findAll().map { it.issueId }).allMatch { it == id }
    }

    /**
     * An issue older than the record says so.
     *
     * The migration writes one of these against every issue that was already
     * here. Everything above it is what genuinely survived - the issue being
     * opened, and what was said on it, because comments were always kept - and
     * everything below it was recorded as it happened. Without the line, an
     * issue opened last week would show an empty history and appear to have had
     * a quiet week.
     */
    @Test
    fun `an issue that predates the record shows where the recording began`() {
        val id = file()

        // What survived from before: the issue being opened, and what was said
        // on it, because comments were always kept properly.
        asUser("bob") {
            graphQlTester.document(
                """mutation { commentOnIssue(id: $id, content: "Still happening") { id } }""",
            ).execute().path("commentOnIssue.id").hasValue()
        }

        // And the line the migration writes against every issue that was here
        // before this table was.
        events.save(IssueEvent(issueId = id, kind = IssueEventKind.RECORDING, actor = "system"))

        graphQlTester.document("""mutation { updateIssue(id: $id, input: { status: "CLOSED" }) { id } }""")
            .execute().path("updateIssue.id").hasValue()

        assertThat(history().map { it["kind"] }).containsExactly("OPENED", "COMMENT", "RECORDING", "STATUS")
    }

    /**
     * A long-lived issue says how much it is not showing.
     *
     * A list that simply stops is a list implying the issue was quiet before
     * that, which is the one thing a history must never do.
     */
    @Test
    fun `an issue with more history than was asked for says how much was left out`() {
        val id = file()
        repeat(10) {
            events.save(
                IssueEvent(
                    issueId = id,
                    kind = IssueEventKind.LABEL,
                    actor = "alice",
                    became = "p$it",
                    at = OffsetDateTime.now().plusSeconds(it.toLong()),
                ),
            )
        }

        graphQlTester.document(
            """{ issueHistory(workspaceId: $workspaceId, number: 1, limit: 3) {
                   earlier entries { became }
                 } }""",
        ).execute()
            // The newest three, because what is asked of a busy issue is what
            // happened lately.
            .path("issueHistory.entries[*].became").entityList(String::class.java)
            .containsExactly("p7", "p8", "p9")
            // The opening line and the seven before them, counted rather than
            // dropped in silence.
            .path("issueHistory.earlier").entity(Int::class.java).isEqualTo(8)
    }

    /** Deleted with the issue, because the rows hang off its id. */
    @Test
    fun `an issue taking its history with it when it goes`() {
        val id = file()
        graphQlTester.document("""mutation { updateIssue(id: $id, input: { status: "CLOSED" }) { id } }""")
            .execute().path("updateIssue.id").hasValue()
        assertThat(events.findAll()).isNotEmpty()

        graphQlTester.document("""mutation { deleteIssue(id: $id) }""").execute().path("deleteIssue").hasValue()
        assertThat(events.findAll()).isEmpty()
    }

    /** Nobody who cannot see the workspace can read what happened in it. */
    @Test
    @WithMockUser(username = "bob", roles = ["NOBODY"])
    fun `somebody who cannot see the workspace is told nothing`() {
        issues.save(Issue(workspaceId = workspaceId, number = 1, title = "The reply is late", reporter = "alice"))

        graphQlTester.document("""{ issueHistory(workspaceId: $workspaceId, number: 1) { earlier } }""")
            .execute().path("issueHistory").valueIsNull()
    }
}
