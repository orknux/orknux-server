package io.mszymanski.orknux.server.llm

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Where a fresh installation starts on what it says about sessions.
 *
 * The file is the floor a fresh installation starts from and Admin -> Settings
 * is the answer from then on, the bargain every number on that screen is
 * under; see `InstallationSettings`.
 */
@ConfigurationProperties(prefix = "orknux.sessions")
data class SessionProperties(
    /**
     * How long a session counts as active after its last line, in seconds.
     *
     * The sessions list draws a dot for a session an agent is at work in, and
     * half of that rule is recency: a line written this recently means somebody
     * is still there, even between two lines. A minute was written into the
     * source (#404) and a minute is right for a chatty agent and wrong for one
     * whose model thinks for three between two lines, so it is a number an
     * administrator can move. Issue #448.
     */
    val activeWindowSeconds: Int = 60,
    /**
     * How often something due at a session is looked for and delivered, where no
     * turn is running to read it - a reminder that comes due after the agent
     * finished. Also how late one may be, at worst.
     */
    val dueSweep: java.time.Duration = java.time.Duration.ofSeconds(1),
    /** Off only in the test suite, where no clock may fire; a test calls the sweep itself. */
    val dueSweepEnabled: Boolean = true,
)
