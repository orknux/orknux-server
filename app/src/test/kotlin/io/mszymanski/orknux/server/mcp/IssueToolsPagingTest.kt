package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.server.issue.Assignee
import io.mszymanski.orknux.server.issue.AssigneeKind
import io.mszymanski.orknux.server.issue.Issue
import io.mszymanski.orknux.server.issue.IssueRepository
import io.mszymanski.orknux.server.issue.IssueStatuses
import io.mszymanski.orknux.server.user.AppUser
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.UserType
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper

/**
 * What the tools say about a tracker bigger than one page of it.
 *
 * Both halves of this are invisible below [IssueTools.MANY] and wrong above it,
 * so the fixture fills a workspace past that number - read from the constant
 * rather than written as 200, because a test carrying its own copy of the page
 * size stops testing anything the day somebody changes it.
 *
 * The two things it pins:
 *
 * - `orknux_issue_labels` counted the labels on one page of issues read back,
 *   which is a count of the page. With 205 issues and a page of 200 it reported
 *   200, and which five it had dropped was the database's choice because the
 *   page was not even sorted.
 * - `orknux_issues` fetched the newest page and filtered *that*, so `labels` and
 *   `assignee` could only ever match something in the newest 200 - and the short
 *   answer came back looking like the whole answer.
 */
@SpringBootTest
@WithMockUser(username = "alice", roles = ["ADMINS"])
class IssueToolsPagingTest(
    @Autowired val tools: IssueTools,
    @Autowired val issues: IssueRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val users: AppUserRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0
    private lateinit var scope: OrknuxScope

    /** One more than fits in an answer, and then some. */
    private val filed = IssueTools.MANY + 5

    /**
     * The oldest few, which is what a page sorted newest-first drops.
     *
     * Deliberately at the far end from where the page looks: an issue the old
     * code could still see would prove nothing.
     */
    private val oldest = 1..8

    @BeforeEach
    fun reset() {
        issues.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
        scope = OrknuxScope(workspaceId = workspaceId, mayWrite = true)

        val ada = users.findByUsername("ada")
            ?: users.save(AppUser(username = "ada", displayName = "Ada Lovelace", type = UserType.INTERNAL))

        issues.saveAll(
            (1..filed).map { number ->
                Issue(
                    workspaceId = workspaceId,
                    number = number,
                    title = "Issue number $number",
                    reporter = "alice",
                    // Every one of them, so the label's true count is the whole
                    // tracker and a tally of one page cannot reach it.
                    labels = if (number in oldest) {
                        mutableSetOf("backlog", "p1")
                    } else {
                        mutableSetOf("backlog")
                    },
                    // The three oldest have somebody on them, which is the one
                    // place a page looking at the newest will never see.
                    assignee = if (number <= 3) {
                        Assignee(kind = AssigneeKind.USER, id = requireNotNull(ada.id).toString())
                    } else {
                        null
                    },
                )
            },
        )
    }

    private fun numbersIn(answer: String): List<Int> =
        mapper.readTree(answer).path("issues").values().map { it.path("number").asLong().toInt() }

    private fun countOf(answer: String, label: String): Long =
        mapper.readTree(answer).path("labels").values()
            .first { it.path("label").stringValue() == label }
            .path("issues").asLong()

    /**
     * #223: the count is the tracker's, not the page's.
     *
     * `backlog` is on every issue, so its count is the only number in the
     * answer that cannot be got right by luck: a tally of a page of
     * [IssueTools.MANY] can say at most that, and the truth here is five more.
     */
    @Test
    fun `a label is counted across the whole tracker, not one page of it`() {
        val answer = tools.labels(scope)

        assertThat(countOf(answer, "backlog")).isEqualTo(filed.toLong())
        assertThat(countOf(answer, "backlog")).isGreaterThan(IssueTools.MANY.toLong())
        assertThat(countOf(answer, "p1")).isEqualTo(oldest.count().toLong())
    }

    /**
     * #224: a label filter reaches the issues a page would have dropped.
     *
     * All eight of them, including #1, which is as far from the newest end as
     * this workspace goes.
     */
    @Test
    fun `a label filter finds issues older than the page`() {
        val found = numbersIn(tools.list(scope, """{"labels": "p1"}"""))

        assertThat(found).hasSize(oldest.count())
        assertThat(found).contains(1, 2, 3)
        assertThat(found).containsExactlyInAnyOrderElementsOf(oldest.toList())
    }

    /**
     * Issue #610: `labels` matches the labels, exactly and in any case - not
     * an issue that mentions the label in its title or description, which is
     * what `search` is for.
     */
    @Test
    fun `the labels argument matches labels only, and search still reads the text`() {
        issues.save(
            Issue(
                workspaceId = workspaceId,
                number = filed + 1,
                title = "Mentions P1 in its title",
                description = "Nothing labelled here, only p1 in the words",
                reporter = "alice",
            ),
        )

        val labelled = numbersIn(tools.list(scope, """{"labels": "P1"}"""))
        assertThat(labelled).containsExactlyInAnyOrderElementsOf(oldest.toList())

        val searched = numbersIn(tools.list(scope, """{"search": "only p1 in the words"}"""))
        assertThat(searched).containsExactly(filed + 1)
    }

    /** Two labels still mean "carries both", now that the query does the work. */
    @Test
    fun `two labels still mean every one of them`() {
        assertThat(numbersIn(tools.list(scope, """{"labels": "backlog, p1"}""")))
            .containsExactlyInAnyOrderElementsOf(oldest.toList())

        // Nothing carries this one, so nothing comes back - rather than
        // everything, which is what an ignored filter would give.
        assertThat(numbersIn(tools.list(scope, """{"labels": "p1, nobody-uses-this"}"""))).isEmpty()
    }

    /** #224 again, through the other filter: assignee is resolved, then queried. */
    @Test
    fun `an assignee filter finds issues older than the page`() {
        val found = numbersIn(tools.list(scope, """{"assignee": "ada"}"""))

        assertThat(found).containsExactlyInAnyOrder(1, 2, 3)
    }

    /** A name nobody has matches no issue, rather than falling through to all of them. */
    @Test
    fun `an assignee nobody is called matches nothing`() {
        assertThat(numbersIn(tools.list(scope, """{"assignee": "nobody at all"}"""))).isEmpty()
    }

    /**
     * What will not fit is said, rather than left to look like the whole answer.
     *
     * The complaint in #224 was not only that the filter was wrong: a list
     * showing 200 of 205 with nothing to say so is a list a reader will size a
     * backlog from.
     */
    @Test
    fun `a list too long to send says how many there are`() {
        val answer = tools.list(scope, "{}")
        val read = mapper.readTree(answer)

        assertThat(read.path("issues").size()).isEqualTo(IssueTools.MANY)
        assertThat(read.path("matching").asLong()).isEqualTo(filed.toLong())
        assertThat(read.path("note").stringValue()).contains("$filed")

        // Newest first, so the page that is sent is a decided one rather than
        // whatever the database handed back.
        assertThat(numbersIn(answer).first()).isEqualTo(filed)
    }

    /** Status still filters, and still filters in the query beside the rest. */
    @Test
    fun `status and labels filter together past the page`() {
        val one = requireNotNull(issues.findByWorkspaceIdAndNumber(workspaceId, 2))
        one.status = IssueStatuses.CLOSED
        issues.save(one)

        val open = numbersIn(tools.list(scope, """{"labels": "p1", "status": "OPEN"}"""))
        assertThat(open).doesNotContain(2)
        assertThat(open).hasSize(oldest.count() - 1)

        assertThat(numbersIn(tools.list(scope, """{"labels": "p1", "status": "CLOSED"}""")))
            .containsExactly(2)
    }

    /** The search still reads title, description and labels, and still pages last. */
    @Test
    fun `a search reaches past the page too`() {
        assertThat(numbersIn(tools.list(scope, """{"search": "Issue number 1"}"""))).contains(1)
    }
}
