package io.mszymanski.orknux.server.plugin

import java.net.http.HttpHeaders
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * A service telling a plugin "not now", noticed on the way through the door
 * and remembered for whoever is running the plugin.
 *
 * GitHub's search allows thirty calls a minute. An agent comparing two
 * repositories ran into it, the plugin raised what GitHub said, and the agent
 * read the error as final - "the code could not be read, the GitHub limit
 * blocked it" - and asked a person for a file it would have had a minute later.
 * Waiting cannot happen inside the door: a plugin's whole call has a wall clock
 * (`orknux.plugin.timeout-millis`, thirty seconds) that counts a wait, and a
 * minute's sleep there kills the call instead of saving it. So the door only
 * notes when the limit lifts, and the caller - outside the sandbox, with no
 * clock on it - decides whether to wait for it and ask again.
 *
 * Kept per thread because that is the shape of a plugin call: the sandbox runs
 * on the thread that called it and may not make one of its own, so whatever the
 * door sees during a call was seen during that call and no other.
 */
object PluginRateLimits {

    /** What a call through the door was told, at the latest lifting it named. */
    data class Limited(val host: String, val until: Instant?)

    private val seen = ThreadLocal<Limited?>()

    /**
     * Runs [call] and says what limit, if any, it ran into. The newest call
     * starts clean, so one limit is never reported against the next call.
     */
    fun <T> watching(call: () -> T): Pair<T, Limited?> {
        val before = seen.get()
        seen.set(null)
        try {
            val result = call()
            return result to seen.get()
        } finally {
            seen.set(before)
        }
    }

    /**
     * Notes an answer if it was a rate limit: a 429, or a 403 that says its
     * allowance is spent, which is how GitHub answers both of its limits. Any
     * other 403 is a refusal of the request and is left alone.
     */
    fun noticed(host: String, status: Int, headers: HttpHeaders, now: Instant = Instant.now()) {
        val after = headers.firstValue("retry-after").orElse(null)
        val spent = headers.firstValue("x-ratelimit-remaining").orElse(null)?.trim() == "0"
        val limited = status == TOO_MANY || (status == FORBIDDEN && (after != null || spent))
        if (!limited) return

        val until = after?.let { untilAfter(it, now) }
            ?: headers.firstValue("x-ratelimit-reset").orElse(null)?.trim()?.toLongOrNull()?.let { Instant.ofEpochSecond(it) }
        val held = seen.get()
        // The latest lifting wins: waiting for an earlier one would ask again into the later.
        if (held == null || (until != null && (held.until == null || until.isAfter(held.until)))) {
            seen.set(Limited(host, until))
        }
    }

    /** A Retry-After, in seconds or as an HTTP date. */
    private fun untilAfter(raw: String, now: Instant): Instant? {
        raw.trim().toLongOrNull()?.let { return now.plusSeconds(it) }
        return runCatching { ZonedDateTime.parse(raw.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
    }

    /**
     * Runs [call], and runs it again after the limit lifts where it failed
     * having met one: up to [attempts] more times, each only if the service
     * named a time no further off than [longest]. Otherwise the last answer,
     * passed through [said] so the reader is told when the limit lifts.
     *
     * Only where the call [failed]: a plugin that met a limit on one request
     * and answered anyway has answered.
     */
    fun waitedOut(
        longest: Duration,
        attempts: Int,
        failed: (String) -> Boolean,
        said: (String, Limited, Duration?) -> String,
        waited: (Limited, Duration) -> Unit = { _, _ -> },
        sleep: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
        call: () -> String,
    ): String = waitedOutFor(longest, attempts, failed, said, waited, sleep, call)

    /** [waitedOut] for any answer: a tool's JSON, or a function's result as a workflow step gets it. */
    fun <T> waitedOutFor(
        longest: Duration,
        attempts: Int,
        failed: (T) -> Boolean,
        said: (T, Limited, Duration?) -> T,
        waited: (Limited, Duration) -> Unit = { _, _ -> },
        sleep: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
        call: () -> T,
    ): T {
        var attempt = 0
        while (true) {
            val (answer, limited) = watching(call)
            if (limited == null || !failed(answer)) return answer
            val left = limited.left()
            if (left == null || left > longest || attempt >= attempts) return said(answer, limited, left)
            waited(limited, left)
            try {
                // A second past the moment named: a reset in whole seconds has not always happened at its own second.
                sleep(left.plusSeconds(1))
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                return said(answer, limited, left)
            }
            attempt++
        }
    }

    /** How long from [now] until the limit lifts; zero when it has, null when the service did not say. */
    fun Limited.left(now: Instant = Instant.now()): Duration? =
        until?.let { Duration.between(now, it).let { left -> if (left.isNegative) Duration.ZERO else left } }

    private const val TOO_MANY = 429
    private const val FORBIDDEN = 403
}
