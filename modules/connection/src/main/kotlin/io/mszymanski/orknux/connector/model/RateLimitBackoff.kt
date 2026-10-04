package io.mszymanski.orknux.connector.model

import java.time.Duration

/**
 * The first wait for a rate limit that named none, doubled per attempt.
 * Issue #608.
 *
 * Azure rate limits a streaming call inside a 200 and does not always say how
 * long to stay away, so the client backs off on its own; how long is a number
 * an administrator may need to change for a provider that recovers slower or
 * faster, so it is read at the call. Declared here and answered by the app,
 * which keeps the number among the installation's settings: this module holds
 * no settings of its own.
 */
fun interface RateLimitBackoff {

    fun unsaid(): Duration

    companion object {
        /** What a fresh installation waits first: five seconds, as 0.9.9.11 hard-coded. */
        val DEFAULT: Duration = Duration.ofSeconds(5)
    }
}
