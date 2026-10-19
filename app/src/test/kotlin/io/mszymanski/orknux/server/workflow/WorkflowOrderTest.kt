package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.WorkflowExecution
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.time.OffsetDateTime

/**
 * The order a page of workflows comes back in, decided by the query.
 *
 * The point of every test here is that the order is applied *before* the page
 * is cut, not after. That is the whole difference between sorting a list and
 * sorting the rows that happen to be on screen, and it is invisible in a test
 * that asks for one page big enough to hold everything - so each of these asks
 * for a page of two out of four and says which two.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class WorkflowOrderTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val executions: WorkflowExecutionRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        executions.deleteAll()
        assignments.deleteAll()
        workflows.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    @Test
    fun `orders by name, ascending, when nothing is asked for`() {
        assign("Cleanup")
        assign("Alerting")
        assign("Deploy")
        assign("Backup")

        namesOn(page = 0, size = 2).containsExactly("Alerting", "Backup")
        namesOn(page = 1, size = 2).containsExactly("Cleanup", "Deploy")
    }

    /*
     * By the words, not by their case. Left to the database, "alerting" sorts
     * after "Zulu" on Postgres because every capital comes first, and a list of
     * names people typed is full of both.
     */
    @Test
    fun `orders by name without minding the case`() {
        assign("zulu")
        assign("Alpha")
        assign("beta")
        assign("Charlie")

        namesOn(page = 0, size = 4, order = "NAME").containsExactly("Alpha", "beta", "Charlie", "zulu")
    }

    @Test
    fun `orders by name descending when asked`() {
        assign("Alerting")
        assign("Backup")
        assign("Cleanup")
        assign("Deploy")

        namesOn(page = 0, size = 2, order = "NAME", ascending = false).containsExactly("Deploy", "Cleanup")
    }

    /**
     * The one that needs the whole list to be in the database's hands: the last
     * run is in another table, and the row that has to come first is the last
     * one alphabetically.
     */
    @Test
    fun `orders by last run, newest first, across the whole list`() {
        val alerting = assign("Alerting")
        val backup = assign("Backup")
        val cleanup = assign("Cleanup")
        assign("Deploy")

        ran(alerting, "2026-01-01T09:00:00Z")
        ran(backup, "2026-03-01T09:00:00Z")
        // Two runs, so the *latest* is what counts rather than the first.
        ran(cleanup, "2026-02-01T09:00:00Z")
        ran(cleanup, "2026-04-01T09:00:00Z")

        namesOn(page = 0, size = 2, order = "LAST_RUN", ascending = false)
            .containsExactly("Cleanup", "Backup")
    }

    /*
     * Never run is not the same as run a long time ago, and it is certainly not
     * the same as run most recently - which is where a database that sorts
     * nulls first would put it when the order is newest-first.
     */
    @Test
    fun `puts a workflow that has never run last, whichever way round`() {
        val alerting = assign("Alerting")
        assign("Backup")
        val cleanup = assign("Cleanup")

        ran(alerting, "2026-01-01T09:00:00Z")
        ran(cleanup, "2026-04-01T09:00:00Z")

        namesOn(page = 0, size = 3, order = "LAST_RUN", ascending = false)
            .containsExactly("Cleanup", "Alerting", "Backup")
        namesOn(page = 0, size = 3, order = "LAST_RUN", ascending = true)
            .containsExactly("Alerting", "Cleanup", "Backup")
    }

    @Test
    fun `orders by whether it is switched on, and by name inside each group`() {
        assign("Alerting", enabled = false)
        assign("Backup", enabled = true)
        assign("Cleanup", enabled = false)
        assign("Deploy", enabled = true)

        // Descending on a boolean is the switched-on ones first.
        namesOn(page = 0, size = 4, order = "ENABLED", ascending = false)
            .containsExactly("Backup", "Deploy", "Alerting", "Cleanup")
        namesOn(page = 0, size = 2, order = "ENABLED", ascending = true)
            .containsExactly("Alerting", "Cleanup")
    }

    /** By description, a pressable column like the others; one without goes last either way. */
    @Test
    fun `orders by description, with none last whichever way round`() {
        workflows.save(Workflow(name = "Zeta", description = "answers mail")).also { assignments.save(WorkspaceWorkflow(workspaceId = workspaceId, workflow = it)) }
        workflows.save(Workflow(name = "Alpha", description = "backs up")).also { assignments.save(WorkspaceWorkflow(workspaceId = workspaceId, workflow = it)) }
        assign("Middle")

        namesOn(page = 0, size = 3, order = "DESCRIPTION", ascending = true).containsExactly("Zeta", "Alpha", "Middle")
        namesOn(page = 0, size = 3, order = "DESCRIPTION", ascending = false).containsExactly("Alpha", "Zeta", "Middle")
    }

    /** The list is still only this workspace's, whatever it is ordered by. */
    @Test
    fun `orders within the workspace that was asked for`() {
        val other = requireNotNull(workspaces.save(Workspace(name = "frontend")).id)
        val mine = assign("Alerting")
        val theirs = assign("Backup", workspace = other)

        ran(mine, "2026-01-01T09:00:00Z")
        ran(theirs, "2026-05-01T09:00:00Z", workspace = other)

        namesOn(page = 0, size = 10, order = "LAST_RUN", ascending = false).containsExactly("Alerting")
    }

    // ------------------------------------------------------------------ fixture

    /** The workflow definition and this workspace's assignment to it. */
    private fun assign(name: String, enabled: Boolean = true, workspace: Long = workspaceId): Long {
        val workflow = workflows.save(Workflow(name = name))
        assignments.save(WorkspaceWorkflow(workspaceId = workspace, workflow = workflow, enabled = enabled))
        return requireNotNull(workflow.id)
    }

    /** A finished run of that workflow, at that moment. */
    private fun ran(workflowId: Long, at: String, workspace: Long = workspaceId) {
        val started = OffsetDateTime.parse(at)
        executions.save(
            WorkflowExecution(
                workspaceId = workspace,
                workflowId = workflowId,
                workflowName = "run",
                status = ExecutionStatus.COMPLETED,
                trigger = ExecutionTrigger.MANUAL,
                startedAt = started,
                finishedAt = started.plusSeconds(1),
            ),
        )
    }

    private fun namesOn(page: Int, size: Int, order: String? = null, ascending: Boolean? = null) =
        graphQlTester.document(
            """
            query {
              workspaceWorkflows(
                workspaceId: $workspaceId,
                page: $page,
                size: $size
                ${if (order == null) "" else ", order: $order"}
                ${if (ascending == null) "" else ", ascending: $ascending"}
              ) { content { name } }
            }
            """,
        ).execute()
            .path("workspaceWorkflows.content[*].name")
            .entityList(String::class.java)
}
