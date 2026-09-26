package io.mszymanski.orknux.server.task

import io.mszymanski.orknux.server.workflow.ExecutionRetentionProperties
import io.mszymanski.orknux.connector.model.LlmModel
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProvider
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.attachment.AttachmentProperties
import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.attachment.SettingNames
import io.mszymanski.orknux.server.chat.ChatProperties
import io.mszymanski.orknux.server.monitoring.MetricsProperties
import io.mszymanski.orknux.server.revision.RevisionProperties
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.reset
import org.mockito.stubbing.Answer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The net under the hand-over, and the setting that says how long it waits.
 *
 * #296 fixed the cause of a task stranding at QUEUED - a hand-over that ran
 * before the row it announced had committed. What was missing afterwards is
 * anything that notices when a hand-over is lost anyway: a process killed
 * between the commit and the callback, a pool that refuses the work, or a
 * Temporal workflow that starts and cannot run. The inline engine sweeps on the
 * way up and so recovers on a restart; Temporal had no net at all, restart
 * included.
 *
 * Four things are held here, and the third is the one that matters. A task
 * queued longer than the interval is handed over. One queued for less is not. A
 * task **already in hand is not handed over a second time** - which is the whole
 * of the risk, because handing over a task a worker is already turning would
 * take the same turn twice. And the interval is a real setting: it round-trips
 * through the API, it changes what the next pass considers stranded, and a value
 * outside what the screen offers is refused rather than quietly clamped to the
 * nearest one that fits.
 *
 * [TaskLoop] is the one thing stood in for, as in [TaskHandoverTest] and for the
 * same reason: it is where a worker reads the task, so it is where what a worker
 * did is written down. The engine, its pool, the sweeper and its query are what
 * is deployed.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class TaskSweepTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val sweeper: TaskSweeper,
    @Autowired val engine: InlineTaskEngine,
    @Autowired val loop: TaskLoop,
    @Autowired val settings: InstallationSettings,
    @Autowired val storedSettings: InstallationSettingRepository,
    @Autowired val tasks: TaskRepository,
    @Autowired val requests: TaskRequestRepository,
    @Autowired val grants: TaskGrantRepository,
    @Autowired val messages: TaskMessageRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private var modelId: Long = 0

    /** Every id a worker was handed, in the order they arrived. */
    private val worked = CopyOnWriteArrayList<Long>()

    /** Counted down the first time a worker has read. */
    private val read = CountDownLatch(1)

    @BeforeEach
    fun start() {
        storedSettings.findById(SettingNames.TASK_SWEEP_MINUTES).ifPresent(storedSettings::delete)
        grants.deleteAll()
        messages.deleteAll()
        requests.deleteAll()
        tasks.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "sweep")).id)
        modelId = model()

        worked.clear()
        reset(loop)
        /*
         * A turn that writes down which task it was handed and finishes it.
         * Finishing matters: the engine looks once more after letting go and
         * picks up anything still RUNNING, so a stand-in that left the row
         * alone would be handed the same task for ever.
         */
        val turn = Answer<TaskTurn> { call ->
            val id = call.getArgument<Long>(0)
            worked += id
            tasks.findByIdOrNull(id)?.let {
                it.status = TaskStatus.DONE
                tasks.save(it)
            }
            // Last, so that a test woken by this has the write to read.
            read.countDown()
            TaskTurn.Over
        }
        doAnswer(turn).`when`(loop).advance(anyLong())
    }

    @AfterEach
    fun release() {
        storedSettings.findById(SettingNames.TASK_SWEEP_MINUTES).ifPresent(storedSettings::delete)
    }

    /**
     * The bug the sweep exists for: a task whose hand-over never happened.
     *
     * Written straight into the table, which is exactly what a process killed
     * between the commit and its `afterCommit` callback leaves behind - the row
     * is there and nothing was ever told about it.
     */
    @Test
    fun `a task queued longer than the interval is handed over`() {
        val id = queued(since = OffsetDateTime.now().minusMinutes(30))

        assertThat(sweeper.sweep()).isEqualTo(1)

        assertThat(read.await(HANDED_OVER, TimeUnit.SECONDS)).isTrue()
        assertThat(worked).containsExactly(id)
        assertThat(tasks.findByIdOrNull(id)?.status).isEqualTo(TaskStatus.DONE)
    }

    /**
     * And a task that is merely starting is left alone.
     *
     * Not because picking it up would be unsafe - the engine would refuse it -
     * but because a net that fires on every task the moment it is written is
     * not a net, it is the hand-over happening twice as a matter of course.
     */
    @Test
    fun `a task queued for less than the interval is left where it is`() {
        val id = queued(since = OffsetDateTime.now())

        assertThat(sweeper.sweep()).isEqualTo(0)
        assertThat(worked).isEmpty()
        assertThat(tasks.findByIdOrNull(id)?.status).isEqualTo(TaskStatus.QUEUED)
    }

    /**
     * The one that would be a real fault: the same turn taken twice.
     *
     * A task can sit at QUEUED for a long time and be perfectly well looked
     * after - the inline engine runs four workers, so a fifth task waits behind
     * them, and it is at QUEUED for as long as that takes. Nothing about the
     * age of the row can tell that apart from a task nobody holds. What can is
     * the engine's own record of what it has, which is what
     * [TaskEngine.recover] answers on.
     *
     * So the task here is genuinely in hand and genuinely old: a worker is
     * inside its turn and stays there until this test lets it out. The sweep
     * finds the row, asks, and is told no.
     */
    @Test
    fun `a task already in hand is not handed over twice`() {
        val id = queued(since = OffsetDateTime.now().minusMinutes(30))

        /*
         * A turn that says it has started and then waits. `held` is how the
         * test knows a worker is inside it; `stillGoing` is what keeps it
         * there, so the task is in hand for as long as this test needs it to
         * be rather than for however long a machine happens to take.
         */
        val held = CountDownLatch(1)
        val stillGoing = CountDownLatch(1)
        doAnswer(
            Answer<TaskTurn> { call ->
                worked += call.getArgument<Long>(0)
                held.countDown()
                stillGoing.await(HELD, TimeUnit.SECONDS)
                tasks.findByIdOrNull(id)?.let {
                    it.status = TaskStatus.DONE
                    tasks.save(it)
                }
                TaskTurn.Over
            },
        ).`when`(loop).advance(anyLong())

        assertThat(engine.recover(id)).describedAs("nothing had it yet").isTrue()
        assertThat(held.await(HANDED_OVER, TimeUnit.SECONDS)).describedAs("a worker is in its turn").isTrue()

        // The row still says QUEUED - the stand-in has not written anything yet
        // - so the query finds it, and only the engine can say it is spoken for.
        assertThat(tasks.findByIdOrNull(id)?.status).isEqualTo(TaskStatus.QUEUED)
        assertThat(sweeper.sweep()).isEqualTo(0)
        assertThat(worked).describedAs("the turn was taken once, not twice").containsExactly(id)

        // Let the turn out and wait for it, so the tidy-up below is not deleting
        // the row from under a thread that is still writing to it.
        stillGoing.countDown()
        val finished = Instant.now().plusSeconds(HANDED_OVER)
        while (tasks.findByIdOrNull(id)?.status != TaskStatus.DONE && Instant.now() < finished) {
            Thread.sleep(FINISHING)
        }
        assertThat(tasks.findByIdOrNull(id)?.status).isEqualTo(TaskStatus.DONE)
    }

    /** Five minutes, until somebody says otherwise. */
    @Test
    fun `the default is five minutes`() {
        assertThat(settings.taskSweepMinutes()).isEqualTo(5)
        graphQlTester.document("""query { installationSettings { taskSweepMinutes } }""").execute()
            .path("installationSettings.taskSweepMinutes").entity(Int::class.java).isEqualTo(5)
    }

    /** Changed on the screen, honoured on the next pass and not on a restart. */
    @Test
    fun `an administrator can change how long a task may sit queued`() {
        val id = queued(since = OffsetDateTime.now().minusMinutes(30))

        graphQlTester.document("""mutation { setTaskSweepMinutes(minutes: 120) { taskSweepMinutes } }""")
            .execute()
            .path("setTaskSweepMinutes.taskSweepMinutes").entity(Int::class.java).isEqualTo(120)

        // Half an hour is no longer long enough to be stuck.
        assertThat(sweeper.sweep()).isEqualTo(0)
        assertThat(tasks.findByIdOrNull(id)?.status).isEqualTo(TaskStatus.QUEUED)

        graphQlTester.document("""query { installationSettings { taskSweepMinutes } }""").execute()
            .path("installationSettings.taskSweepMinutes").entity(Int::class.java).isEqualTo(120)
    }

    /**
     * Refused, and not rounded up to the nearest number that fits.
     *
     * A value silently changed on its way in is a screen that lies about what
     * is in force, and this one governs how long a stranded task is left.
     */
    @Test
    fun `an interval outside what the screen would offer is refused`() {
        graphQlTester.document("""mutation { setTaskSweepMinutes(minutes: 0) { taskSweepMinutes } }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors).singleElement()
                    .satisfies({ assertThat(it.message).contains("not a number of minutes") })
            }
        graphQlTester.document("""mutation { setTaskSweepMinutes(minutes: 100000) { taskSweepMinutes } }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors).singleElement()
                    .satisfies({ assertThat(it.message).contains("not a number of minutes") })
            }

        assertThat(settings.taskSweepMinutes()).describedAs("nothing was stored").isEqualTo(5)
    }

    /**
     * Which engine is running, as the screen is told it.
     *
     * The suite runs inline - `orknux.temporal.enabled=false` in the build - so
     * the field is offered here. The other answer is asserted against the same
     * class built the other way round rather than against a second application
     * context: what decides it is one property, and standing up a Temporal
     * client to read a boolean would be a test of Temporal.
     */
    @Test
    fun `the field is offered on the inline engine and not on Temporal`() {
        assertThat(settings.taskSweepConfigurable()).isTrue()
        graphQlTester.document("""query { installationSettings { taskSweepConfigurable } }""").execute()
            .path("installationSettings.taskSweepConfigurable").entity(Boolean::class.java).isEqualTo(true)

        assertThat(onTemporal().taskSweepConfigurable()).isFalse()
    }

    /** And what the screen will not offer, the API will not store either. */
    @Test
    fun `setting the interval is refused where there is no field for it`() {
        val temporal = onTemporal()
        assertThat(
            runCatching { temporal.setTaskSweepMinutes(30, "alice") }.exceptionOrNull(),
        ).isNotNull()
        assertThat(temporal.taskSweepMinutes()).isEqualTo(5)
    }

    /**
     * The same settings, read as an installation running Temporal would read
     * them. Only the one property differs; everything else is its default.
     */
    private fun onTemporal() = InstallationSettings(
        storedSettings,
        AttachmentProperties(),
        ChatProperties(),
        MetricsProperties(),
        RevisionProperties(),
        TaskSweepProperties(),
        // Run retention, which this test says nothing about - the defaults are
        // what a fresh installation has.
        ExecutionRetentionProperties(),
        // How long a plugin may take to load, which this test says nothing
        // about either.
        io.mszymanski.orknux.workflow.script.PluginProperties(),
        // How long a session counts as active, which this test says nothing
        // about either.
        io.mszymanski.orknux.server.llm.SessionProperties(),
        temporalEnabled = true,
    )

    /** A task written straight into the table, as a lost hand-over leaves one. */
    private fun queued(since: OffsetDateTime): Long = requireNotNull(
        tasks.save(
            Task(
                workspaceId = workspaceId,
                title = "Nobody picked this up",
                prompt = "Carry on",
                modelId = modelId,
                createdBy = "alice",
                createdAt = since,
                turnsAllowed = 10,
                secondsAllowed = 600,
            ),
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
     * Stands in for the turn, so what a worker did is written down.
     *
     * Primary rather than the only one, so the real loop is still built by the
     * context it replaces - a bean that fails to construct is something this
     * suite should notice.
     */
    @TestConfiguration
    class RecordingLoop {

        @Bean
        @Primary
        fun sweepingTaskLoop(): TaskLoop = mock(TaskLoop::class.java)
    }

    private companion object {

        /** How long the hand-over is given once the sweep has asked for it. */
        const val HANDED_OVER = 10L

        /**
         * The ceiling on a stand-in turn that is being held open.
         *
         * A bound and not a wait: every test that shuts the latch opens it
         * again itself, and this only stops a worker thread outliving the run
         * if one does not.
         */
        const val HELD = 30L

        /** How often the last test looks to see whether the turn it let out is over. */
        const val FINISHING = 50L
    }
}
