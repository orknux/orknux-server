package io.mszymanski.orknux.server.watcher

import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.task.TaskInstanceId
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentTool
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.database.SqliteJdbcCustomization
import io.mszymanski.orknux.server.database.isSqlite
import io.mszymanski.orknux.server.database.jdbcUrlOf
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.SessionEventRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.test.annotation.DirtiesContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import javax.sql.DataSource

/**
 * That watchers are checked by the real clock, and that the clock needs
 * nothing but the table - so a watcher set before a restart still fires after
 * one. Issue #606.
 *
 * `WatcherTest` calls the tick by hand, which says nothing about whether
 * anything ever calls it; this class turns db-scheduler on for its own context
 * the way `TriggerSchedulerIntegrationTest` does.
 *
 * The restart is the scheduler's. The context's scheduler is stopped, as a
 * shutdown stops it, and a new one is built over the same database with
 * nothing but the task definition - which is all a fresh process has. The
 * watcher it then fires was set before the first one stopped; nothing about
 * it lived anywhere but its row.
 */
@SpringBootTest(properties = ["db-scheduler.enabled=true", "db-scheduler.polling-interval=1s"])
// The restart stops this context's scheduler for good, so the context goes with the class.
@DirtiesContext
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class WatcherSchedulerIntegrationTest(
    @Autowired val scheduler: Scheduler,
    @Autowired val dataSource: DataSource,
    @Autowired val tools: WatcherTools,
    @Autowired val watchers: WatcherRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val agentTools: AgentToolRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val lines: LlmSessionEventRepository,
    @Autowired val events: SessionEventRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val watcherTask: RecurringTask<Void>,
) {

    private var session: Long = 0
    private lateinit var agent: Agent

    @BeforeEach
    fun reset() {
        clean()
        val workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        session = recorder.open(workspaceId, "node", "restart")
        val done = "export default async function buildStatus() { return { status: 'done' }; }"
        agentTools.save(AgentTool(workspaceId = workspaceId, name = "buildStatus", source = done, typescript = done))
        agent = agents.save(
            Agent(workspaceId = workspaceId, name = "Builder", type = AgentType.LLM, tools = mutableListOf("buildStatus")),
        )
    }

    @AfterEach
    fun clean() {
        watchers.deleteAll()
        events.deleteAll()
        lines.deleteAll()
        agents.deleteAll()
        agentTools.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
    }

    private fun setWatcher(): Long {
        val answer = requireNotNull(tools.shed(agent, session)).run(
            ToolCall(
                "1", WatcherTools.SET,
                """{"tool":"buildStatus","condition_type":"jsonpath","condition":"$[?(@.status == 'done')]",
                   "interval_seconds":15,"timeout_seconds":600}""",
            ),
        )
        val id = Regex("\"watcher\":(\\d+)").find(answer)!!.groupValues[1].toLong()
        // Due now rather than in fifteen seconds; the clock does the rest.
        val watcher = watchers.findById(id).orElseThrow()
        watcher.nextCheckAt = OffsetDateTime.now().minusSeconds(1)
        watchers.save(watcher)
        return id
    }

    @Test
    @Order(1)
    fun `the watchers task is registered with the scheduler`() {
        assertThat(scheduler.schedulerState.isStarted).isTrue()
        assertThat(scheduler.scheduledExecutions.map { it.taskInstance.taskName })
            .contains(WatcherSchedulerConfig.TASK_NAME)
    }

    @Test
    @Order(2)
    fun `a watcher set before a restart is fired by the scheduler that comes up after it`() {
        val id = setWatcher()

        // The server goes down before the watcher is looked at.
        scheduler.stop()
        assertThat(watchers.findById(id).orElseThrow().status).isEqualTo(WatcherStatus.ACTIVE)

        // And comes up: a new scheduler over the same table, knowing only the task.
        val restarted = Scheduler.create(dataSource)
            .startTasks(listOf(watcherTask))
            .threads(1)
            .pollingInterval(Duration.ofSeconds(1))
            .also { if (isSqlite(jdbcUrlOf(dataSource))) it.jdbcCustomization(SqliteJdbcCustomization()) }
            .build()
        try {
            restarted.start()
            restarted.reschedule(TaskInstanceId.of(WatcherSchedulerConfig.TASK_NAME, "recurring"), Instant.now())

            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted {
                assertThat(watchers.findById(id).orElseThrow().status).isEqualTo(WatcherStatus.FIRED)
            }
            assertThat(events.findAll().single { it.sessionId == session }.body).startsWith("Watcher #$id fired.")
        } finally {
            restarted.stop()
        }
    }
}
