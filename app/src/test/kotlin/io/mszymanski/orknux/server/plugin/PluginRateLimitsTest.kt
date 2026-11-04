package io.mszymanski.orknux.server.plugin

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.http.HttpHeaders
import java.time.Duration
import java.time.Instant

/**
 * A rate-limited plugin tool is run again once the limit lifts, instead of the
 * agent being handed the error as final.
 *
 * An agent comparing two repositories hit GitHub's thirty searches a minute,
 * read "rate limit exceeded" as the end of the road, and asked a person for a
 * file it would have had a minute later.
 */
class PluginRateLimitsTest {

    private fun headers(vararg pairs: Pair<String, String>) =
        HttpHeaders.of(pairs.groupBy({ it.first }, { it.second })) { _, _ -> true }

    private val now = Instant.parse("2026-11-04T15:00:00Z")

    private fun limitOf(status: Int, vararg pairs: Pair<String, String>): PluginRateLimits.Limited? =
        PluginRateLimits.watching { PluginRateLimits.noticed("api.github.com", status, headers(*pairs), now) }.second

    @Test
    fun `what counts as a rate limit, and when it lifts`() {
        // GitHub's primary limit: a 403 with nothing remaining, and the reset in epoch seconds.
        assertThat(limitOf(403, "x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "${now.epochSecond + 40}")?.until)
            .isEqualTo(now.plusSeconds(40))
        // Its secondary limit, and everybody's 429: a Retry-After in seconds or as a date.
        assertThat(limitOf(403, "retry-after" to "60")?.until).isEqualTo(now.plusSeconds(60))
        assertThat(limitOf(429, "retry-after" to "Wed, 04 Nov 2026 15:00:30 GMT")?.until).isEqualTo(now.plusSeconds(30))
        // A 429 that says nothing is still a limit, with no time to wait for.
        assertThat(limitOf(429)).isNotNull().extracting { it?.until }.isNull()
        // A 403 that is a refusal of the request is not one, and neither is a 200 counting down.
        assertThat(limitOf(403)).isNull()
        assertThat(limitOf(200, "x-ratelimit-remaining" to "0")).isNull()
    }

    private val failed = { answer: String -> answer.contains("error") }
    private val said = { answer: String, limited: PluginRateLimits.Limited, left: Duration? -> "$answer; lifts in ${left?.toSeconds()} at ${limited.host}" }

    /** A call that is rate limited [times] times and then answers. */
    private fun limitedFor(times: Int, inSeconds: Long): Pair<() -> String, () -> Int> {
        var calls = 0
        val call = {
            calls++
            if (calls <= times) {
                PluginRateLimits.noticed(
                    "api.github.com", 403,
                    headers("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "${Instant.now().epochSecond + inSeconds}"),
                )
                """{"error":"API rate limit exceeded"}"""
            } else {
                """{"result":"found"}"""
            }
        }
        return call to { calls }
    }

    @Test
    fun `a limit that lifts soon is waited out and the tool run again`() {
        val slept = mutableListOf<Duration>()
        val (call, calls) = limitedFor(times = 1, inSeconds = 20)

        val answer = PluginRateLimits.waitedOut(Duration.ofSeconds(90), 2, failed, said, sleep = { slept += it }, call = call)

        assertThat(answer).isEqualTo("""{"result":"found"}""")
        assertThat(calls()).isEqualTo(2)
        assertThat(slept.single()).isBetween(Duration.ofSeconds(19), Duration.ofSeconds(22))
    }

    @Test
    fun `a limit too far off is not waited for, and the answer says when it lifts`() {
        val slept = mutableListOf<Duration>()
        val (call, calls) = limitedFor(times = 1, inSeconds = 600)

        val answer = PluginRateLimits.waitedOut(Duration.ofSeconds(90), 2, failed, said, sleep = { slept += it }, call = call)

        assertThat(slept).isEmpty()
        assertThat(calls()).isEqualTo(1)
        assertThat(answer).contains("API rate limit exceeded").contains("lifts in 59").contains("api.github.com")
    }

    @Test
    fun `it gives up after its attempts, saying when`() {
        val (call, calls) = limitedFor(times = 5, inSeconds = 5)

        val answer = PluginRateLimits.waitedOut(Duration.ofSeconds(90), 2, failed, said, sleep = { }, call = call)

        assertThat(calls()).isEqualTo(3)
        assertThat(answer).contains("API rate limit exceeded").contains("lifts in")
    }

    @Test
    fun `a tool that met a limit and answered anyway has answered`() {
        var calls = 0
        val answer = PluginRateLimits.waitedOut(Duration.ofSeconds(90), 2, failed, said, sleep = { }) {
            calls++
            PluginRateLimits.noticed("api.github.com", 429, headers("retry-after" to "5"))
            """{"result":"partial"}"""
        }
        assertThat(answer).isEqualTo("""{"result":"partial"}""")
        assertThat(calls).isEqualTo(1)
    }

    /**
     * A workflow step calling a plugin function waits the same way: its
     * answer is a script result rather than a tool's JSON, and a limit that
     * outlasts the wait fails it unsettled, with when it lifts, so the node's
     * own retry policy can try again.
     */
    @Test
    fun `a workflow function's result is waited out as a tool's answer is`() {
        var calls = 0
        val call = {
            calls++
            if (calls == 1) {
                PluginRateLimits.noticed("api.github.com", 429, headers("retry-after" to "2"))
                io.mszymanski.orknux.workflow.script.ScriptResult.Failed("API rate limit exceeded", 5)
            } else {
                io.mszymanski.orknux.workflow.script.ScriptResult.Returned("""{"found":1}""", 5)
            }
        }
        val slept = mutableListOf<Duration>()
        val answer = PluginRateLimits.waitedOutFor<io.mszymanski.orknux.workflow.script.ScriptResult>(
            longest = Duration.ofSeconds(90),
            attempts = 2,
            failed = { it is io.mszymanski.orknux.workflow.script.ScriptResult.Failed },
            said = { it, _, _ -> it },
            sleep = { slept += it },
            call = call,
        )
        assertThat(answer).isInstanceOf(io.mszymanski.orknux.workflow.script.ScriptResult.Returned::class.java)
        assertThat(calls).isEqualTo(2)
        assertThat(slept).hasSize(1)
    }
}

