package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.issue.Issue
import io.mszymanski.orknux.server.issue.IssueRepository
import io.mszymanski.orknux.server.issue.IssueStatuses
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
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.context.support.WithMockUser

/**
 * Picking work up, and putting a name on it.
 *
 * Both are things the tracker's screens have always done and the tools could
 * not say. One of them turned out to be a lie rather than a gap - the status
 * tool accepted all three values and described two - and that is the worse
 * shape of the two: a capability nobody is told about is never used, and never
 * being used is indistinguishable from not being there.
 *
 * What ties them together is a backlog worked by more than one agent at a time.
 * Neither of them changes what an issue *is*; both are how everybody else finds
 * out that somebody has started.
 */
@SpringBootTest
@WithMockUser(username = "alice", roles = ["ADMINS"])
class IssueAssignmentToolsTest(
    @Autowired val tools: IssueTools,
    @Autowired val surface: OrknuxTools,
    @Autowired val issues: IssueRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val users: AppUserRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var scope: OrknuxScope

    @BeforeEach
    fun reset() {
        issues.deleteAll()
        agents.deleteAll()
        users.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
        scope = OrknuxScope(workspaceId = workspaceId, mayWrite = true)

        users.save(AppUser(username = "michal", displayName = "Michal", type = UserType.INTERNAL))
        agents.save(Agent(workspaceId = workspaceId, name = "Claude", type = AgentType.LLM))
        issues.save(Issue(workspaceId = workspaceId, number = 1, title = "The reply is late", reporter = "alice"))
    }

    private fun held(): Issue = issues.findByWorkspaceIdAndNumber(workspaceId, 1)!!

    private fun auditLines(): List<String> = audit.findAll().map { it.message }

    /**
     * The status that says somebody has started, which is what an agent needed
     * and was told did not exist.
     *
     * It always worked. The tool's own description said "OPEN or CLOSED", so a
     * model with three values available sent one of two - which is why this
     * asserts on the description as well as on the behaviour. A capability
     * nobody is told about is not a capability.
     */
    @Test
    fun `an issue can be picked up, and the tool says so`() {
        assertThat(tools.setStatus(scope, """{"issue": 1, "status": "IN_PROGRESS"}""")).contains("IN_PROGRESS")
        assertThat(held().status).isEqualTo(IssueStatuses.IN_PROGRESS)

        // The half that was actually broken: what a model is told it may send.
        val status = surface.specs(scope).single { it.name == "orknux_set_issue_status" }
        assertThat(status.parameters.single { it.name == "status" }.description).contains("IN_PROGRESS")
    }

    /**
     * And the audit stops calling it something it is not.
     *
     * The line was `if (wanted == CLOSED) "closed" else "reopened"` - two
     * answers to a question with three - so every issue anybody picked up was
     * recorded as having been reopened. An audit trail that says the wrong
     * thing confidently is worse than one with a gap in it, because nobody goes
     * looking behind it.
     */
    @Test
    fun `picking one up is audited as picked up rather than as reopened`() {
        tools.setStatus(scope, """{"issue": 1, "status": "IN_PROGRESS"}""")

        assertThat(auditLines()).containsExactly("Issue #1 picked up")
        assertThat(auditLines()).noneMatch { it.contains("reopened") }
    }

    /**
     * Reopening needs to know where it came from.
     *
     * Only a closed issue can be reopened. One put back to open from in
     * progress was put down, which is a different thing to have happened, and
     * the word for it is not the word for the other.
     */
    @Test
    fun `only a closed issue is reopened`() {
        tools.setStatus(scope, """{"issue": 1, "status": "IN_PROGRESS"}""")
        tools.setStatus(scope, """{"issue": 1, "status": "OPEN"}""")
        tools.setStatus(scope, """{"issue": 1, "status": "CLOSED"}""")
        tools.setStatus(scope, """{"issue": 1, "status": "OPEN"}""")

        assertThat(auditLines()).containsExactly(
            "Issue #1 picked up",
            "Issue #1 put back to open",
            "Issue #1 closed",
            "Issue #1 reopened",
        )
    }

    /**
     * A name is what an assistant has, so a name is what the tool takes.
     *
     * The browser sends a kind and an id because it has just drawn the list to
     * pick from. Nothing calling a tool has that pair, and demanding it would
     * be a parameter no caller can fill.
     */
    @Test
    fun `an issue is assigned by name, to a person or to an agent`() {
        assertThat(tools.update(scope, """{"issue": 1, "assignee": "Claude"}""")).contains("Claude")
        assertThat(held().assignee?.kind?.name).isEqualTo("AGENT")

        assertThat(tools.update(scope, """{"issue": 1, "assignee": "michal"}""")).contains("Michal")
        assertThat(held().assignee?.kind?.name).isEqualTo("USER")

        assertThat(auditLines()).contains("Issue #1 assigned to Claude", "Issue #1 assigned to Michal")
    }

    /** Handing it back is half of handing it over, so there is a word for it. */
    @Test
    fun `nobody hands it back`() {
        tools.update(scope, """{"issue": 1, "assignee": "Claude"}""")
        tools.update(scope, """{"issue": 1, "assignee": "nobody"}""")

        assertThat(held().assignee).isNull()
        assertThat(auditLines()).contains("Issue #1 unassigned")
    }

    /**
     * Left out is left alone, which every other field here already means.
     *
     * If absence cleared it, every update made for a label would quietly hand
     * the issue back from whoever was working on it.
     */
    @Test
    fun `an update that does not mention the assignee leaves it alone`() {
        tools.update(scope, """{"issue": 1, "assignee": "Claude"}""")
        tools.update(scope, """{"issue": 1, "add_labels": "p1"}""")

        assertThat(held().assignee?.kind?.name).isEqualTo("AGENT")
        assertThat(held().labels).containsExactly("p1")
    }

    /**
     * A name that matches nobody is refused by name.
     *
     * Quietly ignoring it is the failure nobody notices: the call answers as
     * though it worked, and the issue sits with the wrong person on it - or
     * with nobody - while whoever asked believes it is being looked at.
     */
    @Test
    fun `a name nobody here answers to is refused, and changes nothing`() {
        tools.update(scope, """{"issue": 1, "assignee": "Claude"}""")

        val refused = tools.update(scope, """{"issue": 1, "assignee": "Nobody By That Name"}""")

        assertThat(refused).contains("Nobody By That Name")
        assertThat(held().assignee?.kind?.name).isEqualTo("AGENT")
    }

    /** A conversation that may only read cannot hand work out. */
    @Test
    fun `a reading conversation cannot assign`() {
        val reading = OrknuxScope(workspaceId = workspaceId, mayWrite = false)

        assertThat(tools.update(reading, """{"issue": 1, "assignee": "Claude"}""")).contains("not change them")
        assertThat(held().assignee).isNull()
    }
}
