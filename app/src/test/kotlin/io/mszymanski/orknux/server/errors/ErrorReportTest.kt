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

        graphQlTester.document("""query { errorReport(filter: { workspaceId: $workspace, search: "timed out" }) { total } }""")
            .execute().path("errorReport.total").entity(Int::class.java).isEqualTo(1)
    }

    @Test
    @WithMockUser(username = "bob")
    fun `only an administrator reads it`() {
        graphQlTester.document("query { errorReport { total } }").execute().errors().satisfy {
            assertThat(it).isNotEmpty
        }
    }
}
