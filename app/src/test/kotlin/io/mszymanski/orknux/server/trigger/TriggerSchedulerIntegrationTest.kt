package io.mszymanski.orknux.server.trigger

import com.github.kagkarlsson.scheduler.Scheduler
import com.github.kagkarlsson.scheduler.task.TaskInstanceId
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkflowEdgeRepository
import io.mszymanski.orknux.server.workflow.WorkflowNodeRepository
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.workflow.execution.ExecutionLogRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.time.Duration
import java.time.Instant

/**
 * That the clock is actually wired.
 *
 * `TriggerSchedulerTest` calls the tick itself, which says nothing about whether
 * anything ever calls it — and for a while nothing did: db-scheduler's Spring
 * Boot starter never applied on Boot 4, the table stayed empty, and scheduled
 * triggers silently never fired. So this one starts the real scheduler, asks it
 * to run the recurring task now, and waits for a workflow to have run.
 *
 * The rest of the suite runs with `db-scheduler.enabled=false`; this class turns
 * it on for its own context.
 */
@SpringBootTest(properties = ["db-scheduler.enabled=true", "db-scheduler.polling-interval=1s"])
/*
 * Closed with the class. Left cached, this context's scheduler went on polling
 * every second for the rest of the suite and ticked watchers that belonged to
 * later tests: WatcherTest saw its tool called twice where it called it once.
 */
@DirtiesContext
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class TriggerSchedulerIntegrationTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val scheduler: Scheduler,
    @Autowired val triggers: WorkflowTriggerRepository,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val logs: ExecutionLogRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private var workflowId: Long = 0

    @BeforeEach
    fun reset() {
        triggers.deleteAll()
        logs.deleteAll()
        steps.deleteAll()
        executions.deleteAll()
        nodes.deleteAll()
        edges.deleteAll()
        assignments.deleteAll()
        workflows.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "test" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
    }

    @Test
    fun `the recurring task is registered with the scheduler`() {
        assertThat(scheduler.schedulerState.isStarted).isTrue()
        assertThat(scheduler.scheduledExecutions.map { it.taskInstance.taskName })
            .contains(TASK_NAME)
    }

    @Test
    fun `the clock fires a due trigger, without anyone calling the tick`() {
        val triggerId = graphQlTester.document(
            """
            mutation {
              createTrigger(input: {
                workspaceId: $workspaceId, name: "every minute", type: SCHEDULED, cron: "* * * * *", timezone: "UTC"
              }) { id }
            }
            """,
        ).execute().path("createTrigger.id").entity(Long::class.java).get()

        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [{ key: "start", kind: TRIGGER, name: "Every minute", triggerId: $triggerId, x: 0, y: 0 }],
                edges: []
              }) { nodes { key } }
            }
            """,
        ).execute()

        // Published, because a trigger runs the published copy: a graph that
        // was only ever saved is one somebody is still drawing.
        graphQlTester.document(
            """mutation { publishWorkflow(workspaceId: $workspaceId, workflowId: $workflowId) { status } }""",
        ).execute()

        // Rather than waiting for the top of the minute, the task is asked to run
        // now; everything after that is the scheduler's own doing.
        scheduler.reschedule(TaskInstanceId.of(TASK_NAME, INSTANCE), Instant.now())

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted {
            assertThat(executions.findAll().map { it.workflowName }).contains("test")
        }

        assertThat(executions.findAll().single().trigger).isEqualTo(ExecutionTrigger.SCHEDULE)
        assertThat(triggers.findAll().single().lastFiredAt).isNotNull()
    }

    private companion object {
        const val TASK_NAME = "scheduled-triggers"

        /** What db-scheduler calls the single instance of a recurring task. */
        const val INSTANCE = "recurring"
    }
}
