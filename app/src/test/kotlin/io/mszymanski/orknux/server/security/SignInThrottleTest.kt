package io.mszymanski.orknux.server.security

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * The rule itself, without a server around it.
 *
 * What these hold to is the shape of the promise rather than the numbers: a
 * mistyped password costs nothing, a list of guesses costs more each time, and
 * every wait ends. The last of those is the one worth guarding - a throttle that
 * could be made permanent by somebody else's knocking is a way of taking a
 * colleague off the system, which is a worse hole than the one it closes.
 *
 * The waits here are milliseconds rather than the configured seconds, so that
 * "it ends" is something a test can sit through.
 */
class SignInThrottleTest {

    /**
     * Three free tries and then a wait measured in milliseconds - the same rule
     * the defaults describe, at a speed a test can watch.
     */
    private fun throttle(
        perUsername: Int = 3,
        perAddress: Int = 100,
        firstWait: Duration = Duration.ofMillis(200),
        forgetAfter: Duration = Duration.ofMinutes(15),
    ) = SignInThrottle(
        SignInThrottleProperties(
            perUsername = perUsername,
            perAddress = perAddress,
            firstWait = firstWait,
            longestWait = Duration.ofSeconds(1),
            forgetAfter = forgetAfter,
        ),
    )

    /**
     * Spraying names used to wipe every record, the one pausing a guessed
     * account with them. Issue #588.
     */
    @Test
    fun `spraying ten thousand names does not lift the pause on the one being guessed`() {
        val throttle = throttle(perAddress = Int.MAX_VALUE, firstWait = Duration.ofSeconds(1))
        repeat(3) { throttle.failed("alice", "10.0.0.1") }
        repeat(10_050) { throttle.failed("name-$it", "10.0.$it.1") }

        assertThatThrownBy { throttle.check("alice", "10.9.9.9") }
            .isInstanceOf(TooManySignInAttempts::class.java)
    }

    @Test
    fun `getting it wrong once or twice costs nothing at all`() {
        val throttle = throttle()

        repeat(2) {
            assertThatCode { throttle.check("alice", "10.0.0.1") }.doesNotThrowAnyException()
            throttle.failed("alice", "10.0.0.1")
        }


        assertThatCode { throttle.check("alice", "10.0.0.1") }.doesNotThrowAnyException()
    }

    @Test
    fun `one wrong password past the allowance and the next attempt is refused`() {
        val throttle = throttle()
        repeat(3) { throttle.failed("alice", "10.0.0.1") }

        assertThatThrownBy { throttle.check("alice", "10.0.0.1") }
            .isInstanceOf(TooManySignInAttempts::class.java)
    }

    /** The whole point of a wait rather than a lock: it ends by itself. */
    @Test
    fun `the wait ends, so nobody is shut out for good`() {
        val throttle = throttle(firstWait = Duration.ofMillis(200))
        repeat(3) { throttle.failed("alice", "10.0.0.1") }
        assertThatThrownBy { throttle.check("alice", "10.0.0.1") }
            .isInstanceOf(TooManySignInAttempts::class.java)

        Thread.sleep(300)

        assertThatCode { throttle.check("alice", "10.0.0.1") }.doesNotThrowAnyException()
    }

    /**
     * Knocking while refused must not push the wait out, or somebody could hold
     * a colleague's username at the ceiling for as long as they cared to keep
     * knocking - which is the lockout this was built to avoid, reached by a
     * longer road.
     */
    @Test
    fun `an attempt the throttle refused does not lengthen the wait`() {
        val throttle = throttle(firstWait = Duration.ofMillis(300))
        repeat(3) { throttle.failed("alice", "10.0.0.1") }

        val hammering = System.currentTimeMillis() + 250
        while (System.currentTimeMillis() < hammering) {
            assertThatThrownBy { throttle.check("alice", "10.0.0.1") }
                .isInstanceOf(TooManySignInAttempts::class.java)
        }
        Thread.sleep(150)

        assertThatCode { throttle.check("alice", "10.0.0.1") }.doesNotThrowAnyException()
    }

    @Test
    fun `signing in clears what went before`() {
        val throttle = throttle()
        repeat(3) { throttle.failed("alice", "10.0.0.1") }

        throttle.succeeded("alice", "10.0.0.1")

        assertThatCode { throttle.check("alice", "10.0.0.1") }.doesNotThrowAnyException()
    }

    /**
     * The half a per-username rule cannot do on its own: one machine working
     * through a list of names never fails the same name twice.
     */
    @Test
    fun `one machine is slowed even when the username keeps changing`() {
        val throttle = throttle(perUsername = 100, perAddress = 3)

        listOf("alice", "bob", "carol", "dave").forEach { throttle.failed(it, "10.0.0.1") }

        assertThatThrownBy { throttle.check("erin", "10.0.0.1") }
            .isInstanceOf(TooManySignInAttempts::class.java)
    }

    /**
     * And the half a per-address rule cannot do: a botnet is a new address every
     * time, and the name it is guessing at is the only thing the attempts have
     * in common.
     */
    @Test
    fun `one username is slowed even when the address keeps changing`() {
        val throttle = throttle(perUsername = 3, perAddress = 100)

        listOf("10.0.0.1", "10.0.0.2", "10.0.0.3", "10.0.0.4").forEach { throttle.failed("alice", it) }

        assertThatThrownBy { throttle.check("alice", "10.0.0.5") }
            .isInstanceOf(TooManySignInAttempts::class.java)
    }

    /** Going quiet is forgiven, so a bad afternoon does not follow anybody about. */
    @Test
    fun `a username that has gone quiet is forgotten`() {
        val throttle = throttle(firstWait = Duration.ofSeconds(30), forgetAfter = Duration.ofMillis(200))
        repeat(3) { throttle.failed("alice", "10.0.0.1") }

        Thread.sleep(300)

        assertThatCode { throttle.check("alice", "10.0.0.1") }.doesNotThrowAnyException()
    }

    /** Somebody made to wait is told how long, rather than left to guess. */
    @Test
    fun `the refusal says how long to leave it`() {
        val throttle = throttle(firstWait = Duration.ofSeconds(2))
        repeat(3) { throttle.failed("alice", "10.0.0.1") }

        val refused = runCatching { throttle.check("alice", "10.0.0.1") }.exceptionOrNull()

        assertThat(refused).isInstanceOf(TooManySignInAttempts::class.java)
        assertThat((refused as TooManySignInAttempts).headers.getFirst("Retry-After")).isNotNull()
    }
}
