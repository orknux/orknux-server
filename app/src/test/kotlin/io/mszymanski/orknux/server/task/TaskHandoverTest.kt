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
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.reset
import org.mockito.stubbing.Answer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * When a task is handed to a worker, and what that worker can read when it is.
 *
 * A task is written inside a transaction and worked on outside one: everything
 * that reaches [TaskEngine] does so from a `@Transactional` method of
 * [TaskService], and the worker that answers reads the task by its id on a
 * thread and a connection of its own. Handed over a moment too early it reads
 * the row that is not there yet, [TaskLoop.advance] returns [TaskTurn.Over], and
 * the task sits at QUEUED until the process restarts - which is what every task
 * started through the API did on an installation running the inline engine.
 *
 * **These are not races.** The transaction is held open by the test until the
 * worker has read, or until [WHILE_WRITING] says it never will - so the read a
 * too-early hand-over performs happens while the row is provably uncommitted,
 * rather than whenever the machine got round to it. The cost of that is the two
 * transactional tests below each waiting [WHILE_WRITING] out once the hand-over
 * is correctly deferred, which is the price of the failure being certain rather
 * than likely.
 *
 * [TaskLoop] is the one thing stood in for. It is where a worker reads the task,
 * so it is where what the worker could see is written down; everything above it
 * - the service, its transaction, the engine and its thread pool - is what is
 * deployed.
 */
@SpringBootTest
@WithMockUser(username = "alice", roles = ["ADMINS"])
class TaskHandoverTest(
    @Autowired val service: TaskService,
    @Autowired val engine: InlineTaskEngine,
    @Autowired val loop: TaskLoop,
    @Autowired val transactionManager: PlatformTransactionManager,
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

    /** The status the stand-in worker found each time it was handed an id. */
    private val readings = CopyOnWriteArrayList<TaskStatus?>()

    /** Counted down the first time a worker has read, whatever it read. */
    private val read = CountDownLatch(1)

    @BeforeEach
    fun start() {
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
        agentId = worker()

        readings.clear()
        reset(loop)
        /*
         * A turn that reads the task, writes down what state it found it in and
         * finishes it. Finishing matters: the engine looks once more after
         * letting go and picks up anything still RUNNING, so a stand-in that
         * left the row alone would be handed the same task for ever.
         */
        val turn = Answer<TaskTurn> { call ->
            val task = tasks.findByIdOrNull(call.getArgument<Long>(0))
            readings += task?.status
            task?.let {
                it.status = TaskStatus.DONE
                tasks.save(it)
            }
            read.countDown()
            TaskTurn.Over
        }
        doAnswer(turn).`when`(loop).advance(anyLong())
    }

    /**
     * The bug, at the door somebody presses Start on.
     *
     * `TaskService.start` writes the row and asks the engine to begin as its
     * last act, both inside one transaction. Handed straight to a worker, that
     * worker reads a task that does not exist yet.
     */
    @Test
    fun `a task started inside a transaction is handed over only once its row can be read`() {
        val transactions = TransactionTemplate(transactionManager)
        var handedWhileWriting = false

        val id = requireNotNull(
            transactions.execute {
                val task = service.start(
                    NewTask(
                        workspaceId = workspaceId,
                        prompt = "Tidy the failed runs.",
                        agentId = agentId,
                        modelId = modelId,
                        createdBy = "alice",
                    ),
                )
                // Held open on purpose. Nothing else can make the question
                // "what would a worker see right now" have one answer.
                handedWhileWriting = read.await(WHILE_WRITING, TimeUnit.SECONDS)
                requireNotNull(task.id)
            },
        )

        assertThat(read.await(HANDED_OVER, TimeUnit.SECONDS)).isTrue()
        assertThat(readings).containsExactly(TaskStatus.QUEUED)
        assertThat(handedWhileWriting).isFalse()
        assertThat(tasks.findByIdOrNull(id)?.status).isEqualTo(TaskStatus.DONE)
    }

    /**
     * The same hazard on the way back in.
     *
     * `approve` sets the task running and nudges it in one transaction. A nudge
     * that arrives first is answered by a worker reading WAITING, which parks
     * the task again - so the approval takes effect when the task's patience
     * runs out a week later, or never.
     */
    @Test
    fun `an approved task is nudged only once the decision it carries can be read`() {
        val id = parked()
        val requestId = requireNotNull(requests.findAll().first { it.taskId == id }.id)

        val transactions = TransactionTemplate(transactionManager)
        var nudgedWhileWriting = false
        transactions.execute {
            service.approve(requestId, "alice")
            nudgedWhileWriting = read.await(WHILE_WRITING, TimeUnit.SECONDS)
        }

        assertThat(read.await(HANDED_OVER, TimeUnit.SECONDS)).isTrue()
        assertThat(readings).containsExactly(TaskStatus.RUNNING)
        assertThat(nudgedWhileWriting).isFalse()
    }

    /**
     * And nothing changes where there is no transaction to wait for.
     *
     * The revival on the way up is the only thing that picks a stranded task
     * back up, and it runs from a lifecycle callback with no transaction
     * anywhere near it. Waiting for a commit that is never coming would turn
     * the safety net into the fault it exists to catch.
     */
    @Test
    fun `the sweep on the way up picks up a queued task at once`() {
        val id = requireNotNull(
            tasks.save(
                Task(
                    workspaceId = workspaceId,
                    title = "Left over from last time",
                    prompt = "Carry on",
                    modelId = modelId,
                    createdBy = "alice",
                    turnsAllowed = 10,
                    secondsAllowed = 600,
                ),
            ).id,
        )

        engine.start()

        assertThat(read.await(HANDED_OVER, TimeUnit.SECONDS)).isTrue()
        assertThat(readings).containsExactly(TaskStatus.QUEUED)
        assertThat(tasks.findByIdOrNull(id)?.status).isEqualTo(TaskStatus.DONE)
    }

    /** A task stopped for permission, with the request still open. */
    private fun parked(): Long {
        val task = tasks.save(
            Task(
                workspaceId = workspaceId,
                title = "Build the report",
                prompt = "Do the thing",
                modelId = modelId,
                status = TaskStatus.WAITING,
                createdBy = "alice",
                turnsAllowed = 10,
                secondsAllowed = 600,
                waitingUntil = OffsetDateTime.now().plusDays(1),
            ),
        )
        val id = requireNotNull(task.id)
        task.sessionId = recorder.open(workspaceId, "task", id.toString())
        tasks.save(task)
        requests.save(
            TaskRequest(
                taskId = id,
                kind = TaskRequestKind.PERMISSION,
                capability = TaskCapability.ORKNUX,
                asks = "I need to run the build",
            ),
        )
        return id
    }

    /**
     * Somebody to do the work.
     *
     * A task is held with an agent since #295, so one exists here even though
     * nothing in this file reaches a model: what is being measured is when a
     * worker may read the row, not what it finds when it does.
     */
    private fun worker(): Long = requireNotNull(
        agents.save(
            Agent(workspaceId = workspaceId, name = "Worker", type = AgentType.LLM, modelId = modelId),
        ).id,
    )

    /** A provider that can never answer; nothing here reaches a model. */
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

    /**
     * Stands in for the turn, so what a worker could read is written down.
     *
     * Primary rather than the only one, so the real loop is still built by the
     * context it replaces - a bean that fails to construct is something this
     * suite should notice.
     */
    @TestConfiguration
    class RecordingLoop {

        @Bean
        @Primary
        fun recordingTaskLoop(): TaskLoop = mock(TaskLoop::class.java)
    }

    private companion object {

        /**
         * How long a transaction is held open waiting to see whether a worker
         * was handed the task too early.
         *
         * Long enough that a worker which was handed it has certainly read by
         * now, and paid in full on every run where the hand-over is correctly
         * deferred - there is nothing to count down, so this test waits it out.
         */
        const val WHILE_WRITING = 3L

        /** How long the hand-over itself is given, once the commit is done. */
        const val HANDED_OVER = 10L
    }
}
