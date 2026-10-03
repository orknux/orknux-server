package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.workflow.execution.StepRecovery
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * How a step a dead server was in the middle of is recovered is two numbers an
 * administrator sets on the Admin page, under Workflow runs. Issue #601.
 *
 * Read by the execution module through [StepRecovery] - the Temporal step
 * heartbeat as a run starts and with every step's report, an agent step's goes
 * after a restart when the sweep finds it - so what is asserted here is the
 * whole way from the mutation to the number the engine reads. The file's own
 * numbers, ORKNUX_TEMPORAL_STEP_HEARTBEAT_SECONDS and
 * ORKNUX_INLINE_RESTART_ATTEMPTS, are where a fresh installation starts. Puts
 * both back to the default.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class WorkflowStepRecoveryTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val settings: InstallationSettings,
    @Autowired val recovery: StepRecovery,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    @AfterEach
    fun restore() {
        settings.setWorkflowStepHeartbeatSeconds(settings.workflowStepHeartbeatSecondsConfigured(), "alice")
        settings.setWorkflowRestartAttempts(settings.workflowRestartAttemptsConfigured(), "alice")
    }

    @Test
    fun `the defaults are thirty seconds and three goes, and are what the engines read`() {
        graphQlTester.document(
            """{ installationSettings { workflowStepHeartbeatSeconds workflowStepHeartbeatSecondsConfigured
                 workflowRestartAttempts workflowRestartAttemptsConfigured } }""",
        )
            .execute()
            .path("installationSettings.workflowStepHeartbeatSeconds").entity(Int::class.java).isEqualTo(30)
            .path("installationSettings.workflowStepHeartbeatSecondsConfigured").entity(Int::class.java).isEqualTo(30)
            .path("installationSettings.workflowRestartAttempts").entity(Int::class.java).isEqualTo(3)
            .path("installationSettings.workflowRestartAttemptsConfigured").entity(Int::class.java).isEqualTo(3)
        assertThat(recovery.stepHeartbeatSeconds()).isEqualTo(30)
        assertThat(recovery.restartAttempts()).isEqualTo(3)
    }

    @Test
    fun `an administrator sets them, the engines read them, and the changes are audited`() {
        graphQlTester.document(
            """mutation { setWorkflowStepHeartbeatSeconds(seconds: 12) {
                 workflowStepHeartbeatSeconds workflowStepHeartbeatSecondsConfigured } }""",
        )
            .execute()
            .path("setWorkflowStepHeartbeatSeconds.workflowStepHeartbeatSeconds").entity(Int::class.java).isEqualTo(12)
            .path("setWorkflowStepHeartbeatSeconds.workflowStepHeartbeatSecondsConfigured").entity(Int::class.java)
            .isEqualTo(30)
        graphQlTester.document("""mutation { setWorkflowRestartAttempts(count: 5) { workflowRestartAttempts } }""")
            .execute()
            .path("setWorkflowRestartAttempts.workflowRestartAttempts").entity(Int::class.java).isEqualTo(5)

        assertThat(recovery.stepHeartbeatSeconds()).isEqualTo(12)
        assertThat(recovery.restartAttempts()).isEqualTo(5)
        assertThat(audit.findAll().map { it.message }).contains(
            "Workflow steps send a heartbeat, and are handed on after 12s without one",
            "Agent steps cut short by a restart allowed 5 goes",
        )
    }

    @Test
    fun `zero turns the heartbeat off`() {
        graphQlTester.document("""mutation { setWorkflowStepHeartbeatSeconds(seconds: 0) { workflowStepHeartbeatSeconds } }""")
            .execute()
            .path("setWorkflowStepHeartbeatSeconds.workflowStepHeartbeatSeconds").entity(Int::class.java).isEqualTo(0)

        assertThat(recovery.stepHeartbeatSeconds()).isEqualTo(0)
        assertThat(audit.findAll().map { it.message }).contains("Workflow steps no longer send a heartbeat")
    }

    @Test
    fun `a number outside the bounds is refused, in words and as a code`() {
        listOf(-1, 601).forEach { seconds ->
            graphQlTester.document("""mutation { setWorkflowStepHeartbeatSeconds(seconds: $seconds) { workflowStepHeartbeatSeconds } }""")
                .execute()
                .errors()
                .satisfy { errors ->
                    assertThat(errors).hasSize(1)
                    assertThat(errors.first().message).contains("Choose between 0 and 600 seconds")
                    assertThat(errors.first().extensions["code"]).isEqualTo("StepHeartbeatOutOfRange")
                }
        }
        listOf(0, 11).forEach { count ->
            graphQlTester.document("""mutation { setWorkflowRestartAttempts(count: $count) { workflowRestartAttempts } }""")
                .execute()
                .errors()
                .satisfy { errors ->
                    assertThat(errors).hasSize(1)
                    assertThat(errors.first().message).contains("Choose between 1 and 10")
                    assertThat(errors.first().extensions["code"]).isEqualTo("RestartAttemptsOutOfRange")
                }
        }
        assertThat(recovery.stepHeartbeatSeconds()).isEqualTo(30)
        assertThat(recovery.restartAttempts()).isEqualTo(3)
    }

    @Test
    @WithMockUser(username = "bob")
    fun `somebody who is not an administrator cannot set them`() {
        graphQlTester.document("""mutation { setWorkflowStepHeartbeatSeconds(seconds: 5) { workflowStepHeartbeatSeconds } }""")
            .execute()
            .errors()
            .satisfy { errors -> assertThat(errors).isNotEmpty() }
        graphQlTester.document("""mutation { setWorkflowRestartAttempts(count: 5) { workflowRestartAttempts } }""")
            .execute()
            .errors()
            .satisfy { errors -> assertThat(errors).isNotEmpty() }
        assertThat(recovery.stepHeartbeatSeconds()).isEqualTo(30)
        assertThat(recovery.restartAttempts()).isEqualTo(3)
    }
}
