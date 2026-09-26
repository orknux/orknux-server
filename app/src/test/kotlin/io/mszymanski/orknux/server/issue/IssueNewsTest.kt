package io.mszymanski.orknux.server.issue

import io.mszymanski.orknux.server.mcp.NewsTools
import io.mszymanski.orknux.server.mcp.OrknuxScope
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Finding out without being told.
 *
 * The tracker is where work is discussed, and that only works if the people in
 * it hear about a comment. A person has a page to look at; an assistant has
 * this - so what these pin down is who hears what, that nobody hears their own
 * doing, and that a reader who waits is woken rather than left to time out.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class IssueNewsTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val issues: IssueRepository,
    @Autowired val news: IssueNewsRepository,
    @Autowired val marks: IssueNewsReadRepository,
    @Autowired val desk: IssueNewsDesk,
    @Autowired val tools: NewsTools,
    @Autowired val users: AppUserRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0
    private var claudeId: Long = 0

    @BeforeEach
    fun reset() {
        news.deleteAll()
        marks.deleteAll()
        issues.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        users.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
        claudeId = requireNotNull(
            users.save(AppUser(username = "claude", displayName = "Claude", type = UserType.INTERNAL)).id,
        )
    }

    /** Filed by alice and handed to claude, which is the shape all of these want. */
    private fun fileForClaude(title: String = "The reply is late"): Long =
        graphQlTester.document(
            """mutation { createIssue(input: {
                 workspaceId: $workspaceId, title: "$title",
                 assigneeKind: USER, assigneeId: "$claudeId"
               }) { id } }""",
        ).execute().path("createIssue.id").entity(Long::class.java).get()

    /**
     * The tools read whoever is asking, so who that is has to be settable per
     * call rather than per test: half of what these check is that one person's
     * doing is another person's news.
     */
    private fun <T> asUser(name: String, block: () -> T): T {
        val held = SecurityContextHolder.getContext().authentication
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(name, "n/a", emptyList())
        try {
            return block()
        } finally {
            SecurityContextHolder.getContext().authentication = held
        }
    }

    private fun readAs(name: String, wait: Int = 0): List<Map<String, Any?>> {
        val answer = asUser(name) {
            tools.news(OrknuxScope(workspaceId = workspaceId, mayWrite = true), """{"wait": $wait}""").join()
        }
        val parsed = mapper.readValue(answer, Map::class.java)
        assertThat(parsed["reading"]).isEqualTo(name)
        @Suppress("UNCHECKED_CAST")
        return parsed["news"] as List<Map<String, Any?>>
    }

    @Test
    fun `being given an issue is news to whoever was given it`() {
        // Filed by alice, so the news is claude's.
        fileForClaude()

        val told = readAs("claude")
        assertThat(told).hasSize(1)
        assertThat(told.first()["what"]).isEqualTo("assigned to you")
        assertThat(told.first()["by"]).isEqualTo("alice")
        assertThat(told.first()["issue"]).isEqualTo(1)
    }

    @Test
    fun `nobody is told about their own doing`() {
        val id = fileForClaude()
        graphQlTester.document("""mutation { commentOnIssue(id: $id, content: "Looking now") { id } }""").execute()

        // alice filed it, alice commented; alice's own inbox stays empty.
        assertThat(readAs("alice")).isEmpty()
        // And claude, who has it, hears about the comment and the assignment.
        assertThat(news.since(workspaceId, AssigneeKind.USER, "claude", 0)).hasSize(2)
    }

    @Test
    fun `closing an issue reaches the person who filed it`() {
        val id = fileForClaude()
        // Claude closes it; alice filed it, so alice is the one who hears.
        desk.statusChanged(issues.findById(id).get().apply { status = IssueStatuses.CLOSED }, "claude")

        val told = news.since(workspaceId, AssigneeKind.USER, "alice", 0)
        assertThat(told).hasSize(1)
        assertThat(told.first().kind).isEqualTo(IssueNewsKind.STATUS)
        assertThat(told.first().says).isEqualTo("CLOSED")
    }

    @Test
    fun `reading marks it read, so it is only heard once`() {
        fileForClaude()
        assertThat(readAs("claude")).hasSize(1)
        assertThat(readAs("claude")).isEmpty()

        // Something new after the mark is still news.
        fileForClaude("The trigger fires twice")
        assertThat(readAs("claude")).hasSize(1)
    }

    /**
     * The point of the whole thing: a call that waits is woken by the event
     * rather than sitting out its timeout.
     *
     * The comment is written half a second in and the wait is allowed five, so
     * a wait that was never woken takes the full five and fails the four-second
     * ceiling - while a slow machine still has three seconds of slack.
     */
    @Test
    fun `a waiting reader is woken by what arrives`() {
        val id = fileForClaude()
        // Read it away, so only the comment is left to wait for.
        news.deleteAll()

        val writing = Executors.newSingleThreadScheduledExecutor()
        try {
            writing.schedule({
                desk.commented(issues.findById(id).get(), "claude", "Any progress?")
            }, 500, TimeUnit.MILLISECONDS)

            val began = System.currentTimeMillis()
            val told = asUser("alice") {
                tools.news(OrknuxScope(workspaceId = workspaceId, mayWrite = true), """{"wait": 5}""").join()
            }
            val took = System.currentTimeMillis() - began

            assertThat(told).contains("Any progress?")
            assertThat(took).isLessThan(4_000)
        } finally {
            writing.shutdownNow()
        }
    }
}
