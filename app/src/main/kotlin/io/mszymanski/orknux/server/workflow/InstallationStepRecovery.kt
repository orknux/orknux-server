package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.workflow.execution.StepRecovery
import org.springframework.stereotype.Component

/**
 * How a step a dead server was in the middle of is recovered, answered from the
 * installation's settings. Issue #601.
 *
 * The execution module asks and the app answers, as [InstallationStepConcurrency]
 * does: both numbers are on Admin -> Settings, and a module holds no settings of
 * its own. Asked every time, so a change applies to the next step.
 */
@Component
class InstallationStepRecovery(private val settings: InstallationSettings) : StepRecovery {

    override fun stepHeartbeatSeconds(): Long = settings.workflowStepHeartbeatSeconds()

    override fun restartAttempts(): Int = settings.workflowRestartAttempts()
}
