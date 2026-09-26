package io.mszymanski.orknux.connector.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Holding a model's calls under the rate it is allowed. Issue #426.
 *
 * The arithmetic is what breaks a rate limiter - a request spaced wrong, a token
 * window that does not drain, a Retry-After one run obeys and the next does not -
 * and none of it needs a provider on the other end. So this drives
 * [ModelThrottle.reserveMillis] against a clock it can move by hand and reads
 * the instant a call is cleared to fire, which is what the sleep is built on.
 */
class ModelThrottleTest {

    private val clock = MutableClock(Instant.parse("2026-09-26T00:00:00Z"))
    private val throttle = ModelThrottle(clock)

    private fun limits(tokens: Long? = null, requests: Double? = null, retryAfter: Boolean = true) =
        ModelThrottle.Limits(tokens, requests, retryAfter)

    private fun waitFor(modelId: Long, limits: ModelThrottle.Limits): Long =
        throttle.reserveMillis(modelId, limits) - clock.millis()

    /* ------------------------------------------------- nothing set --------- */

    @Test
    fun `a model with no limits waits not at all`() {
        assertThat(waitFor(1, limits())).isZero()
        assertThat(waitFor(1, limits())).isZero()
    }

    /* ------------------------------------------------- requests ------------ */

    @Test
    fun `requests are spaced by one over the rate`() {
        // Two a second: the first fires now, the next half a second later.
        assertThat(waitFor(1, limits(requests = 2.0))).isZero()
        assertThat(waitFor(1, limits(requests = 2.0))).isEqualTo(500)
    }

    @Test
    fun `a rate below one a second spaces past a second`() {
        // Half a second: one every two seconds.
        assertThat(waitFor(1, limits(requests = 0.5))).isZero()
        assertThat(waitFor(1, limits(requests = 0.5))).isEqualTo(2000)
    }

    @Test
    fun `once the spacing has passed the next request is clear again`() {
        waitFor(1, limits(requests = 2.0))
        clock.advance(Duration.ofMillis(500))
        assertThat(waitFor(1, limits(requests = 2.0))).isZero()
    }

    /* ------------------------------------------------- tokens -------------- */

    @Test
    fun `a token window below half the limit earns no delay`() {
        throttle.recordUsage(1, 400) // 40% of 1000
        assertThat(waitFor(1, limits(tokens = 1000))).isZero()
    }

    @Test
    fun `a token window at the limit earns a full second, and more beyond it`() {
        throttle.recordUsage(1, 1000) // at the limit
        assertThat(waitFor(1, limits(tokens = 1000))).isEqualTo(1000)

        throttle.recordUsage(1, 1000) // now twice the limit in the window
        assertThat(waitFor(1, limits(tokens = 1000))).isEqualTo(3000)
    }

    @Test
    fun `a token window drains as its second passes`() {
        throttle.recordUsage(1, 1000)
        clock.advance(Duration.ofMillis(1001)) // the whole window has aged out
        assertThat(waitFor(1, limits(tokens = 1000))).isZero()
    }

    @Test
    fun `whichever dimension is nearer its limit drives the wait`() {
        // Requests would space the next by 500ms; the token window at twice the
        // limit wants 3000ms. The larger wins.
        waitFor(1, ModelThrottle.Limits(1000, 2.0, true))
        throttle.recordUsage(1, 2000)
        assertThat(waitFor(1, ModelThrottle.Limits(1000, 2.0, true))).isEqualTo(3000)
    }

    /* ------------------------------------------------- retry-after --------- */

    @Test
    fun `a Retry-After holds the model even with no limits, and for everyone`() {
        throttle.blockFor(7, Duration.ofSeconds(5))
        // A second run of the same model, with no limits of its own, still waits.
        assertThat(waitFor(7, limits())).isEqualTo(5000)
    }

    @Test
    fun `a Retry-After lifts when its time has passed`() {
        throttle.blockFor(7, Duration.ofSeconds(5))
        clock.advance(Duration.ofSeconds(5))
        assertThat(waitFor(7, limits())).isZero()
    }

    @Test
    fun `the longer of two Retry-Afters wins`() {
        throttle.blockFor(7, Duration.ofSeconds(2))
        throttle.blockFor(7, Duration.ofSeconds(10))
        assertThat(waitFor(7, limits())).isEqualTo(10_000)
    }

    /* ------------------------------------------------- effective limits ---- */

    @Test
    fun `a model inherits the provider's defaults where it sets none`() {
        val provider = provider().apply {
            throttleTokensPerSecond = 5000
            throttleRequestsPerSecond = 3.0
            acceptRetryAfter = false
        }
        val model = model()

        val limits = ModelThrottle.Limits.of(model, provider)
        assertThat(limits.tokensPerSecond).isEqualTo(5000)
        assertThat(limits.requestsPerSecond).isEqualTo(3.0)
        assertThat(limits.acceptRetryAfter).isFalse()
    }

    @Test
    fun `a model overrides the provider, and a zero turns a dimension off`() {
        val provider = provider().apply {
            throttleTokensPerSecond = 5000
            throttleRequestsPerSecond = 3.0
        }
        val model = model().apply {
            throttleTokensPerSecond = 0 // off, though the provider sets one
            throttleRequestsPerSecond = 10.0 // its own, higher
        }

        val limits = ModelThrottle.Limits.of(model, provider)
        assertThat(limits.tokensPerSecond).isEqualTo(0)
        assertThat(limits.throttled).isTrue() // requests still throttle it
        assertThat(limits.requestsPerSecond).isEqualTo(10.0)
    }

    private fun provider() = ModelProvider(workspaceId = 1, name = "p", endpoint = "https://example.test")
    private fun model() = LlmModel(providerId = 1, name = "m", modelId = "gpt-x")

    private class MutableClock(private var now: Instant, private val zone: ZoneId = ZoneOffset.UTC) : Clock() {
        override fun getZone(): ZoneId = zone
        override fun withZone(z: ZoneId): Clock = MutableClock(now, z)
        override fun instant(): Instant = now
        fun advance(by: Duration) {
            now = now.plus(by)
        }
    }
}
