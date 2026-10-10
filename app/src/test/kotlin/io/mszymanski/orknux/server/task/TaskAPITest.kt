package io.mszymanski.orknux.server.task

import io.mszymanski.orknux.connector.model.LlmModel
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProvider
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.issue.IssueNewsRepository
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

/**
 * The door onto tasks: starting one, reading it, and deciding what a parked one
 * asked for.
 *
 * The model here never answers - the provider is pointed at a host that cannot
 * resolve, the way every probe test in this suite is - so a task started through
 * the API fails on its first turn and does so on a thread of its own. That is
 * deliberate: what this pins is the surface, and the turn is pinned a turn at a
 * time in [TaskLoopTest] where nothing races.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class TaskAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val tasks: TaskRepository,
    @Autowired val requests: TaskRequestRepository,
    @Autowired val grants: TaskGrantRepository,
    @Autowired val messages: TaskMessageRepository,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val events: LlmSessionEventRepository,
    @Autowired val news: IssueNewsRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private var modelId: Long = 0
    private var agentId: Long = 0

    @BeforeEach
    fun reset() {
        grants.deleteAll()
        messages.deleteAll()
        requests.deleteAll()
        tasks.deleteAll()
        news.deleteAll()
        events.deleteAll()
        sessions.deleteAll()
        agents.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        modelId = model()
        agentId = agent()
    }

    @Test
    fun `starting a task records what was asked and opens its log`() {
        val id = graphQlTester.document(
            """mutation { startTask(input: {
                 workspaceId: $workspaceId, agentId: $agentId,
                 prompt: "Tidy the failed runs.\nThen write it up."
               }) { id title prompt createdBy sessionId turnsAllowed requests { id } } }""",
        ).execute()
            .path("startTask.title").entity(String::class.java).isEqualTo("Tidy the failed runs.")
            .path("startTask.createdBy").entity(String::class.java).isEqualTo("alice")
            .path("startTask.turnsAllowed").entity(Int::class.java).matches { it > 0 }
            .path("startTask.requests").entityList(Any::class.java).hasSize(0)
            .path("startTask.sessionId").entity(Long::class.java).matches { it > 0 }
            .path("startTask.id").entity(Long::class.java).get()

        // The prompt is in the log before a single turn has been taken. A page
        // that draws an empty transcript for the first minute is one nobody can
        // tell from a broken one.
        val sessionId = requireNotNull(tasks.findById(id).orElseThrow().sessionId)
        assertThat(events.findAll().filter { it.sessionId == sessionId }.map { it.content })
            .contains("Tidy the failed runs.\nThen write it up.")

        assertThat(audit.findAll().map { it.category }).contains(WorkspaceAuditCategory.TASK)
    }

    /**
     * A prompt with nothing in it, and no agent at all.
     *
     * The second used to mean a bare model - an agent with no tools, no skills,
     * no grants and no instructions, set to a job that is defined by doing - and
     * the refusal it now gets is the schema's rather than the service's, because
     * `agentId` is `ID!` since issue #295. That is where the removal is: a
     * refusal thrown at runtime would still be a task somebody could ask for.
     */
    @Test
    fun `a task needs something to work on and an agent to work on it`() {
        graphQlTester.document(
            """mutation { startTask(input: { workspaceId: $workspaceId, agentId: $agentId, prompt: "   " })
               { id } }""",
        ).execute().errors().expect { it.message?.contains("something to work on") == true }.verify()

        graphQlTester.document(
            """mutation { startTask(input: { workspaceId: $workspaceId, prompt: "Do it" }) { id } }""",
        ).execute().errors().expect { it.message?.contains("agentId") == true }.verify()
    }

    /** The list, and the filter on it. */
    @Test
    fun `tasks are listed newest first and can be narrowed to one state`() {
        val first = parked("One")
        val second = parked("Two")

        graphQlTester.document(
            """query { workspaceTasks(workspaceId: $workspaceId) { totalElements content { id } } }""",
        ).execute()
            .path("workspaceTasks.totalElements").entity(Int::class.java).isEqualTo(2)
            .path("workspaceTasks.content[*].id").entityList(Long::class.java).containsExactly(second, first)

        graphQlTester.document(
            """query { workspaceTasks(workspaceId: $workspaceId, status: DONE) { totalElements } }""",
        ).execute().path("workspaceTasks.totalElements").entity(Int::class.java).isEqualTo(0)
    }

    /**
     * Approving grants the one thing that was asked for, and says who said yes.
     *
     * The absence of an "approve everything" is the point of the feature, so
     * what is checked is that one grant appeared naming exactly what the request
     * named.
     */
    @Test
    fun `approving grants exactly what was asked and records who granted it`() {
        val taskId = parked("Needs access")
        val requestId = requireNotNull(requests.findFirstByTaskIdAndDecisionIsNullOrderByAskedAtAscIdAsc(taskId)).id

        graphQlTester.document(
            """mutation { approveTaskRequest(id: $requestId) {
                 status requests { decision decidedBy } grants { capability subject grantedBy } } }""",
        ).execute()
            .path("approveTaskRequest.status").entity(TaskStatus::class.java).isEqualTo(TaskStatus.RUNNING)
            .path("approveTaskRequest.requests[0].decision").entity(String::class.java).isEqualTo("GRANTED")
            .path("approveTaskRequest.requests[0].decidedBy").entity(String::class.java).isEqualTo("alice")
            .path("approveTaskRequest.grants").entityList(Any::class.java).hasSize(1)
            .path("approveTaskRequest.grants[0].capability").entity(String::class.java).isEqualTo("ORKNUX")
            .path("approveTaskRequest.grants[0].grantedBy").entity(String::class.java).isEqualTo("alice")

        assertThat(audit.findAll().map { it.message }).anyMatch { it.contains("granted orknux") }
    }

    /**
     * A task that asked for shells before they were removed still reads.
     *
     * The capability is kept as a retired constant for exactly this: a stored
     * value with no constant behind it fails to load, and takes the task page
     * with it.
     */
    @Test
    fun `a request for shells recorded before their removal still reads`() {
        val taskId = parked("Asked for a shell", TaskCapability.SHELLS)

        graphQlTester.document("""query { task(id: $taskId) { requests { capability } } }""")
            .execute()
            .path("task.requests[0].capability").entity(String::class.java).isEqualTo("SHELLS")
    }

    /** Two people pressing the same button: the second is told it has moved on. */
    @Test
    fun `a request that has been decided cannot be decided again`() {
        val taskId = parked("Needs access")
        val requestId = requireNotNull(requests.findFirstByTaskIdAndDecisionIsNullOrderByAskedAtAscIdAsc(taskId)).id

        graphQlTester.document("""mutation { refuseTaskRequest(id: $requestId) { status } }""").execute()
        graphQlTester.document("""mutation { approveTaskRequest(id: $requestId) { status } }""")
            .execute().errors().expect { it.message?.contains("already been decided") == true }.verify()
    }

    /**
     * The door onto #280: saying something to a task nobody stopped.
     *
     * What the surface has to get right is that it is honest about *when* the
     * agent hears it. The message is read at the top of the next turn, so
     * `readAt` is null the moment it is sent - a page drawing it as delivered
     * would be telling somebody their correction had landed when it had not.
     */
    @Test
    fun `a working task can be told something and the page can see it has not been read`() {
        val taskId = parked("Needs access")

        graphQlTester.document(
            """mutation { sendTaskMessage(id: $taskId, said: "  Make it a table.  ")
               { status messages { saidBy body readAt } } }""",
        ).execute()
            .path("sendTaskMessage.status").entity(TaskStatus::class.java).isEqualTo(TaskStatus.WAITING)
            .path("sendTaskMessage.messages[0].saidBy").entity(String::class.java).isEqualTo("alice")
            // Trimmed, so a stray newline from a textarea is not a different message.
            .path("sendTaskMessage.messages[0].body").entity(String::class.java).isEqualTo("Make it a table.")
            .path("sendTaskMessage.messages[0].readAt").valueIsNull()

        // The audit line says a message was given, and not what was in it: the
        // words are in the task's transcript, where somebody reading the account
        // of the work will look for them.
        assertThat(audit.findAll().map { it.message })
            .anyMatch { it.contains("Needs access") && it.contains("given a message") }
    }

    /**
     * An empty message is refused whatever state the task is in, and a task that
     * has ended is set going again rather than refused.
     *
     * The second half used to be the opposite. There was no next turn to read a
     * message on, so writing the row would have been a promise nothing could
     * keep; #312 gave it one - the same task, on the same session, working again
     * - so the refusal went with the reason for it. The empty message did not
     * change: it is a mistyped Enter either way, and accepting it on a task that
     * has ended would reopen the work to say nothing.
     */
    @Test
    fun `an empty message is refused, and one to a task that has ended sets it going again`() {
        val taskId = parked("Needs access")

        graphQlTester.document("""mutation { sendTaskMessage(id: $taskId, said: "   ") { id } }""")
            .execute().errors().expect { it.message?.contains("needs something in it") == true }.verify()

        graphQlTester.document("""mutation { stopTask(id: $taskId) { status } }""")
            .execute().path("stopTask.status").entity(TaskStatus::class.java).isEqualTo(TaskStatus.STOPPED)

        graphQlTester.document("""mutation { sendTaskMessage(id: $taskId, said: "   ") { id } }""")
            .execute().errors().expect { it.message?.contains("needs something in it") == true }.verify()

        graphQlTester.document(
            """mutation { sendTaskMessage(id: $taskId, said: "One more thing.") {
                 status turnsSpent outcome messages { body readAt }
               } }""",
        ).execute()
            .path("sendTaskMessage.status").entity(TaskStatus::class.java).isEqualTo(TaskStatus.RUNNING)
            .path("sendTaskMessage.turnsSpent").entity(Int::class.java).isEqualTo(0)
            .path("sendTaskMessage.outcome").valueIsNull()
            .path("sendTaskMessage.messages[0].body").entity(String::class.java).isEqualTo("One more thing.")
            .path("sendTaskMessage.messages[0].readAt").valueIsNull()
    }

    /** A running task is stopped deliberately, not as a side effect of tidying up. */
    @Test
    fun `a task is stopped before it can be removed`() {
        val taskId = parked("Needs access")

        graphQlTester.document("""mutation { deleteTask(id: $taskId) }""")
            .execute().errors().expect { it.message?.contains("Stop the task") == true }.verify()

        graphQlTester.document("""mutation { stopTask(id: $taskId) { status endedBecause } }""").execute()
            .path("stopTask.status").entity(TaskStatus::class.java).isEqualTo(TaskStatus.STOPPED)
            .path("stopTask.endedBecause").entity(String::class.java).isEqualTo("stopped by alice")

        graphQlTester.document("""mutation { deleteTask(id: $taskId) }""").execute()
            .path("deleteTask").entity(Boolean::class.java).isEqualTo(true)

        // The log goes with it: it is the task's contents rather than something
        // in its own right.
        assertThat(sessions.findAll()).isEmpty()
        assertThat(events.findAll()).isEmpty()
    }

    /**
     * A task somebody cannot see reads as one that is not there.
     *
     * The same answer `workspace(id)` gives, and for the same reason: "that is
     * not yours" confirms it is somebody's.
     */
    @Test
    @WithMockUser(username = "mallory", roles = ["USERS"])
    fun `a task in a workspace you cannot see is not there`() {
        val taskId = parked("Hidden")
        graphQlTester.document("""query { task(id: $taskId) { id } }""")
            .execute().path("task").valueIsNull()
    }

    /**
     * A task sitting at WAITING with one open request, written straight into the
     * database.
     *
     * Not started through the API, because a started task runs on a thread of
     * its own and would be racing these assertions.
     */
    private fun parked(title: String, capability: TaskCapability = TaskCapability.ORKNUX): Long {
        val task = tasks.save(
            Task(
                workspaceId = workspaceId,
                title = title,
                prompt = "Do the thing",
                modelId = modelId,
                status = TaskStatus.WAITING,
                createdBy = "alice",
                turnsAllowed = 10,
                secondsAllowed = 600,
                waitingUntil = java.time.OffsetDateTime.now().plusDays(1),
            ),
        )
        val id = requireNotNull(task.id)
        task.sessionId = recorder.open(workspaceId, "task", id.toString())
        tasks.save(task)
        requests.save(
            TaskRequest(
                taskId = id,
                kind = TaskRequestKind.PERMISSION,
                capability = capability,
                asks = "I need to start the build",
            ),
        )
        return id
    }

    /**
     * A provider that can never answer.
     *
     * `.invalid` cannot resolve, by definition, so nothing here reaches the
     * network - the rule every probe test in this suite follows.
     *
     * Written through the repositories rather than the API because the fixture
     * is built under whichever account the test names, and one of them is a
     * person who may not see this workspace at all.
     */
    /**
     * An agent to do the work, since a task can no longer be started without one
     * (issue #295).
     *
     * Written through the repository rather than through `createAgent`, the same
     * way [model] is written. The fixture runs under whichever user the test
     * declares, and one of them is mallory, who cannot see this workspace - so a
     * fixture that went through an access-checked mutation would fail in
     * `reset` before the test it is for had begun.
     */
    private fun agent(): Long = requireNotNull(
        agents.save(
            Agent(workspaceId = workspaceId, name = "Worker", type = AgentType.LLM, modelId = modelId),
        ).id,
    )

    private fun model(): Long {
        val provider = providers.save(
            ModelProvider(workspaceId = workspaceId, name = "Nowhere", endpoint = "http://nowhere.invalid"),
        )
        return requireNotNull(
            models.save(
                LlmModel(providerId = requireNotNull(provider.id), name = "Stub", modelId = "stub"),
            ).id,
        )
    }
}
