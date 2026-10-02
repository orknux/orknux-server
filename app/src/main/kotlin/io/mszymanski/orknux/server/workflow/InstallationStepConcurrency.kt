package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.workflow.execution.StepConcurrency
import org.springframework.stereotype.Component

/**
 * How wide a workflow run may go, answered from the installation's settings.
 * Issue #285.
 *
 * The execution module asks and the app answers, because the setting lives
 * where every other number an administrator sets lives, and a module holds no
 * settings of its own.
 */
@Component
class InstallationStepConcurrency(private val settings: InstallationSettings) : StepConcurrency {

    override fun stepsAtOnce(): Int = settings.workflowStepsAtOnce()
}
