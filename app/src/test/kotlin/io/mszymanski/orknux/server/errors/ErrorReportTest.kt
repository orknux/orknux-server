package io.mszymanski.orknux.server.errors

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.RunEnded
import io.mszymanski.orknux.workflow.execution.WorkflowExecution
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.time.OffsetDateTime

/** Admin > Reports > Errors: what failed is recorded where it fails, and read back by source. */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ErrorReportTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val events: ApplicationEventPublisher,
    @Autowired val errors: ErrorRecorder,
) {

    @Test
    fun `a failed run is recorded and a finished one is not, and the report counts and narrows by source`() {
        val workspace = requireNotNull(workspaces.save(Workspace(name = "errors ${System.nanoTime()}")).id)
        fun run(status: ExecutionStatus, error: String?) = requireNotNull(
            executions.save(
                WorkflowExecution(
                    workspaceId = workspace,
                    workflowId = 1,
                    workflowName = "Nightly import",
                    status = status,
                    trigger = ExecutionTrigger.MANUAL,
                    startedAt = OffsetDateTime.now(),
                ).also { it.error = error },
            ).id,
        ).also { events.publishEvent(RunEnded(it)) }

        val failed = run(ExecutionStatus.FAILED, "The step Fetch answered 500.")
        run(ExecutionStatus.COMPLETED, null)
        errors.record(workspace, ErrorSource.AGENT, "Support", "The provider refused the key.", sessionId = 7)
        errors.record(workspace, ErrorSource.AGENT, "Support", "The provider timed out.")

        val report = """query { errorReport(filter: { workspaceId: $workspace }) {
              total counts { source count } often { source subject count } rows { source subject executionId message } } }"""
        graphQlTester.document(report).execute()
            .path("errorReport.total").entity(Int::class.java).isEqualTo(3)
            .path("errorReport.counts[?(@.source == 'RUN')].count").entityList(Int::class.java).containsExactly(1)
            .path("errorReport.counts[?(@.source == 'AGENT')].count").entityList(Int::class.java).containsExactly(2)
            .path("errorReport.often[0].subject").entity(String::class.java).isEqualTo("Support")
            .path("errorReport.often[0].count").entity(Int::class.java).isEqualTo(2)

        graphQlTester.document("""query { errorReport(filter: { workspaceId: $workspace }, source: RUN) { total rows { subject executionId message } } }""")
            .execute()
            .path("errorReport.total").entity(Int::class.java).isEqualTo(1)
            .path("errorReport.rows[0].subject").entity(String::class.java).isEqualTo("Nightly import")
            .path("errorReport.rows[0].executionId").entity(Long::class.java).isEqualTo(failed)
            .path("errorReport.rows[0].message").entity(String::class.java).isEqualTo("The step Fetch answered 500.")

        val today = java.time.LocalDate.now().toString()
        graphQlTester.document("""query { errorReport(filter: { workspaceId: $workspace }) { days { day runs agents models } } }""")
            .execute()
            .path("errorReport.days").entityList(Any::class.java).hasSize(30)
            .path("errorReport.days[29].day").entity(String::class.java).isEqualTo(today)
            .path("errorReport.days[29].runs").entity(Int::class.java).isEqualTo(1)
            .path("errorReport.days[29].agents").entity(Int::class.java).isEqualTo(2)
            .path("errorReport.days[0].runs").entity(Int::class.java).isEqualTo(0)

        graphQlTester.document("""query { errorReport(filter: { workspaceId: $workspace, search: "timed out" }) { total } }""")
            .execute().path("errorReport.total").entity(Int::class.java).isEqualTo(1)
    }

    @Test
    fun `the rows are put in the order of a column, and paged in that order`() {
        val workspace = requireNotNull(workspaces.save(Workspace(name = "errors order ${System.nanoTime()}")).id)
        errors.record(workspace, ErrorSource.AGENT, "beta", "Second message.")
        errors.record(workspace, ErrorSource.RUN, "Alpha", "Third message.")
        errors.record(workspace, ErrorSource.MODEL, "gamma", "First message.")

        fun subjects(order: String, ascending: Boolean, size: Int = 25) = graphQlTester.document(
            """query { errorReport(filter: { workspaceId: $workspace }, order: $order, ascending: $ascending, size: $size) {
                 total rows { subject } } }""",
        ).execute().path("errorReport.rows[*].subject").entityList(String::class.java).get()

        // Newest first when nothing is asked for, as before.
        graphQlTester.document("""query { errorReport(filter: { workspaceId: $workspace }) { rows { subject } } }""")
            .execute().path("errorReport.rows[*].subject").entityList(String::class.java).containsExactly("gamma", "Alpha", "beta")
        assertThat(subjects("SUBJECT", true)).containsExactly("Alpha", "beta", "gamma")
        assertThat(subjects("SUBJECT", false)).containsExactly("gamma", "beta", "Alpha")
        assertThat(subjects("MESSAGE", true)).containsExactly("gamma", "beta", "Alpha")
        assertThat(subjects("SOURCE", true)).containsExactly("beta", "gamma", "Alpha")
        assertThat(subjects("AT", true)).containsExactly("beta", "Alpha", "gamma")
        assertThat(subjects("WORKSPACE", true)).hasSize(3)
        // A page is cut from the ordered rows, not ordered after it is cut.
        assertThat(subjects("SUBJECT", true, size = 2)).containsExactly("Alpha", "beta")
    }

    @Test
    @WithMockUser(username = "bob")
    fun `only an administrator reads it`() {
        graphQlTester.document("query { errorReport { total } }").execute().errors().satisfy {
            assertThat(it).isNotEmpty
        }
    }
}
