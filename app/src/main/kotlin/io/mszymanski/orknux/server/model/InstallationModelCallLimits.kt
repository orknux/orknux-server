package io.mszymanski.orknux.server.model

import io.mszymanski.orknux.connector.model.ModelCallLimits
import io.mszymanski.orknux.server.attachment.InstallationSettings
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * How long one call to a model may take, answered from the installation's
 * settings.
 *
 * The connection module asks and the app answers, because the number lives
 * where every other number an administrator sets lives. Read on every call, so
 * a change takes effect on the next one without a restart.
 */
@Component
class InstallationModelCallLimits(private val settings: InstallationSettings) : ModelCallLimits {

    override fun answer(): Duration = Duration.ofMinutes(settings.modelAnswerLimitMinutes().toLong())
}
