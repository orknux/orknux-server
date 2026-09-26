package io.mszymanski.orknux.server.task

import io.mszymanski.orknux.connector.model.LlmModel
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProvider
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.issue.Assignee
import io.mszymanski.orknux.server.issue.AssigneeKind
import io.mszymanski.orknux.server.issue.Issue
import io.mszymanski.orknux.server.issue.IssueComment
import io.mszymanski.orknux.server.issue.IssueEventKind
import io.mszymanski.orknux.server.issue.IssueEventRepository
import io.mszymanski.orknux.server.issue.IssueNewsRepository
import io.mszymanski.orknux.server.issue.IssueRepository
import io.mszymanski.orknux.server.issue.IssueStatuses
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.LlmSessionRepository
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
import org.springframework.security.test.context.support.WithMockUser
import java.time.OffsetDateTime

/**
 * "Start by AI": issue #230, from the button through to the task and back.
 *
 * The model here can never answer - the provider is pointed at a host that
 * cannot resolve, as every probe test in this suite is - so a task started
 * through the API fails on its first turn, on a thread of its own. Nothing here
 * asserts on a task's *status* for that reason: what is pinned is what the press
 * wrote down, which is all of it written inside the mutation.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class IssueTaskTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val issues: IssueRepository,
    @Autowired val tasks: TaskRepository,
    @Autowired val requests: TaskRequestRepository,
    @Autowired val grants: TaskGrantRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val events: LlmSessionEventRepository,
    @Autowired val history: IssueEventRepository,
    @Autowired val news: IssueNewsRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private var agentId: Long = 0

    @BeforeEach
    fun reset() {
        grants.deleteAll()
        requests.deleteAll()
        tasks.deleteAll()
        history.deleteAll()
        news.deleteAll()
        issues.deleteAll()
        events.deleteAll()
        sessions.deleteAll()
        agents.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
        agentId = requireNotNull(
            agents.save(
                Agent(workspaceId = workspaceId, name = "Responder", type = AgentType.LLM, modelId = model()),
            ).id,
        )
    }

    /**
     * The whole of what one press does, in one test, because it is one press.
     *
     * Splitting it would let three of the five things it writes be right and the
     * fourth quietly stop happening - which is exactly the shape of the bug this
     * feature can have, since nobody looks at an audit log until they need it.
     */
    @Test
    fun `starting an agent-assigned issue by AI hands over the issue and picks it up`() {
        val issue = filed(
            title = "Replies arrive twice",
            description = "Every Slack mention is answered twice.",
            labels = mutableSetOf("slack", "p1"),
            assignee = Assignee(AssigneeKind.AGENT, agentId.toString()),
            comments = mutableListOf(
                IssueComment(author = "bob", content = "It started on Tuesday."),
                IssueComment(author = "alice", content = "The listener is registered twice."),
            ),
        )

        val taskId = graphQlTester.document(
            """mutation { startIssueTask(issueId: ${issue.id}) { id title prompt agentId issueId createdBy } }""",
        ).execute()
            .path("startIssueTask.title").entity(String::class.java).isEqualTo("#1 Replies arrive twice")
            .path("startIssueTask.agentId").entity(Long::class.java).isEqualTo(agentId)
            .path("startIssueTask.issueId").entity(Long::class.java).isEqualTo(requireNotNull(issue.id))
            .path("startIssueTask.createdBy").entity(String::class.java).isEqualTo("alice")
            .path("startIssueTask.id").entity(Long::class.java).get()

        // What the agent was handed: the issue, not a summary of it. The thread
        // is in it, and so is who said what - see IssueTaskPrompt for why.
        val prompt = requireNotNull(tasks.findById(taskId).orElseThrow().prompt)
        assertThat(prompt)
            .contains("Issue #1: Replies arrive twice")
            .contains("Labels: p1, slack")
            .contains("Every Slack mention is answered twice.")
            .contains("bob: It started on Tuesday.")
            .contains("alice: The listener is registered twice.")

        // The issue was picked up, and both records say so exactly once.
        assertThat(issues.findById(requireNotNull(issue.id)).orElseThrow().status)
            .isEqualTo(IssueStatuses.IN_PROGRESS)
        val moves = history.findAll().filter { it.kind == IssueEventKind.STATUS }
        assertThat(moves).hasSize(1)
        assertThat(moves.first().became).isEqualTo("IN_PROGRESS")

        // And the agent says so in the thread, signed with its own name - the
        // same name its later comments carry, so the two read as one voice.
        // Without it the first thing an agent says arrives with nothing before
        // it, and nothing anywhere says the work was handed to one (#230).
        graphQlTester.document(
            """query { workspaceIssue(workspaceId: $workspaceId, number: 1) { comments { author content } } }""",
        ).execute()
            .path("workspaceIssue.comments[2].author").entity(String::class.java).isEqualTo("Responder")
            .path("workspaceIssue.comments[2].content").entity(String::class.java)
            // The task is linked, because this comment is the only place the
            // issue mentions it and the work is happening on that page, live.
            .isEqualTo("Started by AI. alice set me to work on this — [follow it](/workspace/$workspaceId/tasks/$taskId).")

        val lines = audit.findAll()
        assertThat(lines.filter { it.category == WorkspaceAuditCategory.TASK }.map { it.message })
            .containsExactly("Task #1 Replies arrive twice started")
        assertThat(lines.map { it.message }).contains("Issue #1 picked up")
    }

    /** The link, both ways: the task carries the issue and the issue offers the task. */
    @Test
    fun `the issue and the task point at each other`() {
        val issue = filed(assignee = Assignee(AssigneeKind.AGENT, agentId.toString()))

        val taskId = graphQlTester.document(
            """mutation { startIssueTask(issueId: ${issue.id}) { id } }""",
        ).execute().path("startIssueTask.id").entity(Long::class.java).get()

        graphQlTester.document("""query { issueTasks(issueId: ${issue.id}) { id issueId } }""")
            .execute()
            .path("issueTasks[*].id").entityList(Long::class.java).containsExactly(taskId)
            .path("issueTasks[0].issueId").entity(Long::class.java).isEqualTo(requireNotNull(issue.id))
    }

    /**
     * A person, a model and nobody are all refused.
     *
     * The button is not drawn for any of the three, so every one of these is a
     * second window or somebody calling the API - which is the case a check
     * exists for.
     */
    @Test
    fun `an issue not assigned to an agent cannot be started by AI`() {
        val toNobody = filed(assignee = null)
        val toAPerson = filed(assignee = Assignee(AssigneeKind.USER, "7"))
        val toAModel = filed(assignee = Assignee(AssigneeKind.MODEL, "7"))

        for (issue in listOf(toNobody, toAPerson, toAModel)) {
            graphQlTester.document("""mutation { startIssueTask(issueId: ${issue.id}) { id } }""")
                .execute().errors().expect { it.message?.contains("not assigned to an agent") == true }.verify()
        }
        assertThat(tasks.findAll()).isEmpty()
    }

    /**
     * Pressing it twice does not set two agents on one issue.
     *
     * The task in the way is written straight into the database rather than
     * started, so what is being tested is the refusal and not a race with a
     * thread pool. WAITING is the honest state to put it in: a task parked for
     * permission is precisely the one somebody looking at a stalled issue is
     * tempted to press the button again on.
     */
    @Test
    fun `an issue with a task already working on it refuses a second`() {
        val issue = filed(assignee = Assignee(AssigneeKind.AGENT, agentId.toString()))
        val running = live(requireNotNull(issue.id))

        graphQlTester.document("""mutation { startIssueTask(issueId: ${issue.id}) { id } }""")
            .execute().errors().expect { it.message?.contains("already has a task") == true }.verify()

        assertThat(tasks.findAll().map { it.id }).containsExactly(running)
    }

    /**
     * A task that ended leaves the issue where it was put, and the button comes
     * back.
     *
     * Both halves of the conservative decision in one test. Nothing moves an
     * issue back to open: whoever pressed the button made a statement about
     * their own tracker and a machine reverting it hours later would be editing
     * somebody's board while they were not looking. What the ending does do is
     * stop the issue being locked out of a second attempt.
     */
    @Test
    fun `an ended task neither reopens the issue nor blocks another attempt`() {
        val issue = filed(assignee = Assignee(AssigneeKind.AGENT, agentId.toString()), status = IssueStatuses.IN_PROGRESS)
        val first = live(requireNotNull(issue.id))
        tasks.findById(first).orElseThrow().let {
            it.status = TaskStatus.FAILED
            it.endedBecause = "out of turns"
            tasks.save(it)
        }

        graphQlTester.document("""mutation { startIssueTask(issueId: ${issue.id}) { id } }""")
            .execute().path("startIssueTask.id").entity(Long::class.java).matches { it != first }

        assertThat(issues.findById(requireNotNull(issue.id)).orElseThrow().status)
            .isEqualTo(IssueStatuses.IN_PROGRESS)
        // It was already in progress, so nothing was picked up a second time.
        assertThat(history.findAll().filter { it.kind == IssueEventKind.STATUS }).isEmpty()
    }

    /** A closed issue is not quietly reopened by a button press. */
    @Test
    fun `a closed issue is not started by AI`() {
        val issue = filed(assignee = Assignee(AssigneeKind.AGENT, agentId.toString()), status = IssueStatuses.CLOSED)

        graphQlTester.document("""mutation { startIssueTask(issueId: ${issue.id}) { id } }""")
            .execute().errors().expect { it.message?.contains("is closed") == true }.verify()

        assertThat(issues.findById(requireNotNull(issue.id)).orElseThrow().status).isEqualTo(IssueStatuses.CLOSED)
    }

    /** Somebody who cannot see the workspace cannot start anything in it. */
    @Test
    @WithMockUser(username = "mallory", roles = ["USERS"])
    fun `an issue in a workspace you cannot see offers nothing`() {
        val issue = filed(assignee = Assignee(AssigneeKind.AGENT, agentId.toString()))

        graphQlTester.document("""mutation { startIssueTask(issueId: ${issue.id}) { id } }""")
            .execute().errors().expect { true }.verify()
        graphQlTester.document("""query { issueTasks(issueId: ${issue.id}) { id } }""")
            .execute().path("issueTasks").entityList(Any::class.java).hasSize(0)

        assertThat(tasks.findAll()).isEmpty()
    }

    /**
     * A thread longer than the budget loses its beginning, and says so.
     *
     * The direction is the whole point and is the one thing about the prompt
     * that could be silently backwards: a decision is reached at the end of an
     * argument, so an agent handed only the first half of one would act on a
     * position that was overturned.
     */
    @Test
    fun `a long thread is cut from the front and the prompt admits it`() {
        // Three of these come to nine eighths of the budget, so exactly one goes.
        val long = "x".repeat(IssueTaskPrompt.THREAD_BUDGET * 3 / 8)
        val issue = Issue(
            workspaceId = workspaceId,
            number = 1,
            title = "An argument",
            reporter = "alice",
            comments = mutableListOf(
                IssueComment(author = "first", content = long),
                IssueComment(author = "second", content = long),
                IssueComment(author = "third", content = long),
            ),
        )

        val prompt = IssueTaskPrompt.of(issue)
        assertThat(prompt).contains("third:").doesNotContain("first:")
        assertThat(prompt).contains("the earliest 1 of 3 are left out")
    }

    /** An issue in the database, in whatever state the test needs it. */
    private fun filed(
        title: String = "Something to look at",
        description: String? = "It does the wrong thing.",
        labels: MutableSet<String> = mutableSetOf(),
        assignee: Assignee? = null,
        status: String = IssueStatuses.OPEN,
        comments: MutableList<IssueComment> = mutableListOf(),
    ): Issue = issues.save(
        Issue(
            workspaceId = workspaceId,
            number = issues.lastNumber(workspaceId) + 1,
            title = title,
            description = description,
            status = status,
            reporter = "alice",
            assignee = assignee,
            labels = labels,
            comments = comments,
        ),
    )

    /**
     * A task on this issue that has not finished, written rather than started.
     *
     * Started tasks run on a thread of their own and would race every assertion
     * about whether one is still going; what these tests are about is the
     * refusal, which reads the row.
     */
    private fun live(issueId: Long): Long {
        val task = tasks.save(
            Task(
                workspaceId = workspaceId,
                title = "Already going",
                prompt = "Do the thing",
                agentId = agentId,
                modelId = agents.findById(agentId).orElseThrow().modelId,
                status = TaskStatus.WAITING,
                issueId = issueId,
                createdBy = "alice",
                turnsAllowed = 10,
                secondsAllowed = 600,
                waitingUntil = OffsetDateTime.now().plusDays(1),
            ),
        )
        val id = requireNotNull(task.id)
        task.sessionId = recorder.open(workspaceId, "task", id.toString())
        tasks.save(task)
        return id
    }

    /**
     * A provider that can never answer.
     *
     * `.invalid` cannot resolve, by definition, so nothing here reaches the
     * network - the rule every probe test in this suite follows.
     */
    private fun model(): Long {
        val provider = providers.save(
            ModelProvider(workspaceId = workspaceId, name = "Nowhere", endpoint = "http://nowhere.invalid"),
        )
        return requireNotNull(
            models.save(LlmModel(providerId = requireNotNull(provider.id), name = "Stub", modelId = "stub")).id,
        )
    }
}
