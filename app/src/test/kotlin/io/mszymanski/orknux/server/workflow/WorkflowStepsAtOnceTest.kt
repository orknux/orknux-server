package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.workflow.execution.StepConcurrency
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * How many steps of one workflow run may be running at once is a number an
 * administrator sets on the Admin page. Issue #285.
 *
 * Read by the planner through [StepConcurrency], so what is asserted here is
 * the whole way from the mutation to the number a run is planned with. Puts
 * the installation's number back to the default.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class WorkflowStepsAtOnceTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val settings: InstallationSettings,
    @Autowired val concurrency: StepConcurrency,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    @AfterEach
    fun restore() {
        settings.setWorkflowStepsAtOnce(settings.workflowStepsAtOnceConfigured(), "alice")
    }

    @Test
    fun `the default is four, and is what a run is planned with`() {
        graphQlTester.document("""{ installationSettings { workflowStepsAtOnce workflowStepsAtOnceConfigured } }""")
            .execute()
            .path("installationSettings.workflowStepsAtOnce").entity(Int::class.java).isEqualTo(4)
            .path("installationSettings.workflowStepsAtOnceConfigured").entity(Int::class.java).isEqualTo(4)
        assertThat(concurrency.stepsAtOnce()).isEqualTo(4)
    }

    @Test
    fun `an administrator sets it, the planner reads it, and the change is audited`() {
        graphQlTester.document("""mutation { setWorkflowStepsAtOnce(count: 2) { workflowStepsAtOnce workflowStepsAtOnceConfigured } }""")
            .execute()
            .path("setWorkflowStepsAtOnce.workflowStepsAtOnce").entity(Int::class.java).isEqualTo(2)
            .path("setWorkflowStepsAtOnce.workflowStepsAtOnceConfigured").entity(Int::class.java).isEqualTo(4)

        assertThat(concurrency.stepsAtOnce()).isEqualTo(2)
        assertThat(audit.findAll().map { it.message }).contains("Workflow runs allowed 2 steps running at once")
    }

    @Test
    fun `a number outside one to thirty-two is refused, in words and as a code`() {
        listOf(0, 33).forEach { count ->
            graphQlTester.document("""mutation { setWorkflowStepsAtOnce(count: $count) { workflowStepsAtOnce } }""")
                .execute()
                .errors()
                .satisfy { errors ->
                    assertThat(errors).hasSize(1)
                    assertThat(errors.first().message).contains("Choose between 1 and 32")
                    assertThat(errors.first().extensions["code"]).isEqualTo("StepsAtOnceOutOfRange")
                }
        }
        assertThat(concurrency.stepsAtOnce()).isEqualTo(4)
    }

    @Test
    @WithMockUser(username = "bob")
    fun `somebody who is not an administrator cannot set it`() {
        graphQlTester.document("""mutation { setWorkflowStepsAtOnce(count: 2) { workflowStepsAtOnce } }""")
            .execute()
            .errors()
            .satisfy { errors -> assertThat(errors).isNotEmpty() }
        assertThat(concurrency.stepsAtOnce()).isEqualTo(4)
    }
}
