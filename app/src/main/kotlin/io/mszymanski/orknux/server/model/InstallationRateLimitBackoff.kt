package io.mszymanski.orknux.server.model

import io.mszymanski.orknux.connector.model.RateLimitBackoff
import io.mszymanski.orknux.server.attachment.InstallationSettings
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * The first wait for a rate limit that named none, answered from the
 * installation's settings. Issue #608.
 *
 * The connection module asks and the app answers, because the number lives
 * where every other number an administrator sets lives. Read on every call, so
 * a change takes effect on the next rate limit without a restart.
 */
@Component
class InstallationRateLimitBackoff(private val settings: InstallationSettings) : RateLimitBackoff {

    override fun unsaid(): Duration = Duration.ofSeconds(settings.rateLimitBackoffSeconds().toLong())
}
