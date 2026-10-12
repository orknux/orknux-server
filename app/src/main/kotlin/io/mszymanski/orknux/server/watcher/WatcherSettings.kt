package io.mszymanski.orknux.server.watcher

import io.mszymanski.orknux.server.attachment.InstallationSetting
import io.mszymanski.orknux.server.attachment.InstallationSettingRepository
import io.mszymanski.orknux.server.graphql.Refusal
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import kotlin.math.ceil

/**
 * Where a fresh installation's watcher limits come from: `orknux.watchers.*`,
 * each a named ORKNUX_ variable in application.yml. Admin -> Settings ->
 * Watchers overrides all three. Issue #606.
 */
@ConfigurationProperties(prefix = "orknux.watchers")
data class WatcherProperties(
    /** The longest a watcher may run, in seconds - a week. */
    val maxSeconds: Int = DEFAULT_WATCHER_MAX_SECONDS,
    /** The shortest interval between two checks, in seconds. */
    val minIntervalSeconds: Int = DEFAULT_WATCHER_MIN_INTERVAL_SECONDS,
    /** How many watchers one agent may have running at once. */
    val maxPerAgent: Int = DEFAULT_WATCHER_MAX_PER_AGENT,
    /** The shortest an agent may ask to be shown a watcher's result between checks. #618. */
    val minAgentCheckSeconds: Int = DEFAULT_WATCHER_MIN_AGENT_CHECK_SECONDS,
    /** The delay before the agent's first look of its own; see [LookBackoff]. */
    val lookInitialSeconds: Int = DEFAULT_LOOK_BACKOFF.initialSeconds,
    /** What each delay is multiplied by for the next. */
    val lookMultiplier: Double = DEFAULT_LOOK_BACKOFF.multiplier,
    /** The longest a delay grows to. */
    val lookMaxSeconds: Int = DEFAULT_LOOK_BACKOFF.maxSeconds,
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WatcherProperties::class)
class WatcherPropertiesConfig

/**
 * The three numbers that bound what an agent may ask a watcher to do. Issue #606.
 *
 * Each is read now rather than when a watcher was set, except the length: a
 * watcher's timeout is fixed when it is set, because an administrator who
 * shortens the ceiling means it for the watchers asked for next, not as a way
 * of ending the ones an agent is already counting on. The count and the
 * interval only ever bite at `watcher_set`.
 *
 * Kept in the installation's key-value table beside every other setting, so a
 * new one is a row and not a migration.
 */
@Service
class WatcherSettings(
    private val settings: InstallationSettingRepository,
    private val properties: WatcherProperties,
) {

    /** The longest a watcher may run, in seconds. */
    fun maxSeconds(): Int = held(MAX_SECONDS, WATCHER_MAX_SECONDS_RANGE) ?: maxSecondsConfigured()

    /** What a fresh installation allows - ORKNUX_WATCHER_MAX_SECONDS. */
    fun maxSecondsConfigured(): Int = properties.maxSeconds.coerceIn(WATCHER_MAX_SECONDS_RANGE)

    /** The shortest interval between two checks, in seconds. */
    fun minIntervalSeconds(): Int = held(MIN_INTERVAL, WATCHER_MIN_INTERVAL_RANGE) ?: minIntervalSecondsConfigured()

    /** What a fresh installation allows - ORKNUX_WATCHER_MIN_INTERVAL_SECONDS. */
    fun minIntervalSecondsConfigured(): Int = properties.minIntervalSeconds.coerceIn(WATCHER_MIN_INTERVAL_RANGE)

    /** How many watchers one agent may have running at once; zero switches watchers off. */
    fun maxPerAgent(): Int = held(MAX_PER_AGENT, WATCHER_MAX_PER_AGENT_RANGE) ?: maxPerAgentConfigured()

    /** What a fresh installation allows - ORKNUX_WATCHER_MAX_PER_AGENT. */
    fun maxPerAgentConfigured(): Int = properties.maxPerAgent.coerceIn(WATCHER_MAX_PER_AGENT_RANGE)

    /**
     * How often, at the most, a watcher may wake its agent to look at the result
     * itself. Each look is a model turn, so this is what an agent asking to be
     * shown every fifteen seconds for a week is held to. #618.
     */
    fun minAgentCheckSeconds(): Int =
        held(MIN_AGENT_CHECK, WATCHER_MIN_AGENT_CHECK_RANGE) ?: minAgentCheckSecondsConfigured()

    fun minAgentCheckSecondsConfigured(): Int =
        properties.minAgentCheckSeconds.coerceIn(WATCHER_MIN_AGENT_CHECK_RANGE)

    /**
     * How the agent's own looks are spaced where it gave no agent check - see
     * [LookBackoff]. A stored backoff that no longer holds together falls back
     * to the configured one whole, never part of it.
     */
    fun lookBackoff(): LookBackoff {
        val stored = LookBackoff(
            initialSeconds = held(LOOK_INITIAL, WATCHER_LOOK_INITIAL_RANGE) ?: return lookBackoffConfigured(),
            multiplier = settings.findByIdOrNull(LOOK_MULTIPLIER)?.value?.toDoubleOrNull() ?: return lookBackoffConfigured(),
            maxSeconds = held(LOOK_MAX, WATCHER_LOOK_MAX_RANGE) ?: return lookBackoffConfigured(),
        )
        return if (stored.problem() == null) stored else lookBackoffConfigured()
    }

    /** What a fresh installation uses - the ORKNUX_WATCHER_LOOK_* variables. */
    fun lookBackoffConfigured(): LookBackoff {
        val configured = LookBackoff(properties.lookInitialSeconds, properties.lookMultiplier, properties.lookMaxSeconds)
        return if (configured.problem() == null) configured else DEFAULT_LOOK_BACKOFF
    }

    /**
     * The delay before the first look where nobody chose one: the backoff's
     * initial delay, but no sooner than [minAgentCheckSeconds] allows or than
     * the tool is next called. Null where it would fall at or after the
     * watcher's end, since it would never come.
     */
    fun firstLookFor(timeout: Int, interval: Int): Int? =
        maxOf(lookBackoff().initialSeconds, minAgentCheckSeconds(), interval).takeIf { it < timeout }

    /** The delay after a look that came [delay] seconds after the one before. */
    fun nextLookAfter(delay: Int): Int = lookBackoff().after(delay)

    /** Sets the whole backoff at once, since the ceiling is only valid against the initial delay. */
    @Transactional
    fun setLookBackoff(backoff: LookBackoff, by: String) {
        backoff.problem()?.let { throw WatcherLookBackoffInvalidException(backoff, it) }
        write(LOOK_INITIAL, backoff.initialSeconds, by)
        write(LOOK_MULTIPLIER, backoff.multiplier.toString(), by)
        write(LOOK_MAX, backoff.maxSeconds, by)
    }

    @Transactional
    fun setMaxSeconds(seconds: Int, by: String) {
        if (seconds !in WATCHER_MAX_SECONDS_RANGE) throw WatcherMaxSecondsOutOfRangeException(seconds)
        write(MAX_SECONDS, seconds, by)
    }

    @Transactional
    fun setMinIntervalSeconds(seconds: Int, by: String) {
        if (seconds !in WATCHER_MIN_INTERVAL_RANGE) throw WatcherMinIntervalOutOfRangeException(seconds)
        write(MIN_INTERVAL, seconds, by)
    }

    @Transactional
    fun setMinAgentCheckSeconds(seconds: Int, by: String) {
        if (seconds !in WATCHER_MIN_AGENT_CHECK_RANGE) throw WatcherMinAgentCheckOutOfRangeException(seconds)
        write(MIN_AGENT_CHECK, seconds, by)
    }

    @Transactional
    fun setMaxPerAgent(count: Int, by: String) {
        if (count !in WATCHER_MAX_PER_AGENT_RANGE) throw WatcherMaxPerAgentOutOfRangeException(count)
        write(MAX_PER_AGENT, count, by)
    }

    /** A stored value that is still in range, or null for the configured one. */
    private fun held(name: String, range: IntRange): Int? =
        settings.findByIdOrNull(name)?.value?.toIntOrNull()?.takeIf { it in range }

    private fun write(name: String, value: Int, by: String) = write(name, value.toString(), by)

    private fun write(name: String, value: String, by: String) {
        val held = settings.findByIdOrNull(name) ?: InstallationSetting(name = name)
        held.value = value
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = by
        settings.save(held)
    }

    companion object {
        const val MAX_SECONDS = "watcher.max.seconds"
        const val MIN_INTERVAL = "watcher.min.interval.seconds"
        const val MAX_PER_AGENT = "watcher.max.per.agent"
        const val MIN_AGENT_CHECK = "watcher.min.agent.check.seconds"
        const val LOOK_INITIAL = "watcher.look.initial.seconds"
        const val LOOK_MULTIPLIER = "watcher.look.multiplier"
        const val LOOK_MAX = "watcher.look.max.seconds"
    }
}

/**
 * Exponential backoff for the agent's own looks on a watcher:
 *
 *     delay(1) = initial
 *     delay(n) = min(delay(n - 1) * multiplier, max)
 *
 * The first look comes [initialSeconds] after the watcher is set, and each
 * wait after that is the last one times [multiplier], until it reaches
 * [maxSeconds]. A short watcher gets a look or two while what it watches is
 * still fresh, and a week-long one is not looked at every hour of it. A
 * multiplier of 1 is a fixed interval.
 */
data class LookBackoff(
    val initialSeconds: Int,
    val multiplier: Double,
    val maxSeconds: Int,
) {

    /** The wait after one of [delay] seconds: longer by the multiplier, up to the ceiling, never shorter. */
    fun after(delay: Int): Int = maxOf(delay, minOf(ceil(delay * multiplier), maxSeconds.toDouble()).toInt())

    /**
     * When, in seconds after it was set, the looks fall on a watcher of
     * [timeout] seconds, the first no sooner than [floor]. What the Admin page
     * simulates from, and what the tests read.
     */
    fun looksWithin(timeout: Int, floor: Int = 0): List<Long> {
        val at = mutableListOf<Long>()
        var delay = maxOf(initialSeconds, floor)
        var t = delay.toLong()
        while (t < timeout && at.size < MOST_LOOKS) {
            at += t
            delay = after(delay)
            t += delay
        }
        return at
    }

    /** Why this is not a backoff, or null when it is. */
    fun problem(): String? = when {
        initialSeconds !in WATCHER_LOOK_INITIAL_RANGE ->
            "the initial delay must be between ${WATCHER_LOOK_INITIAL_RANGE.first} and ${WATCHER_LOOK_INITIAL_RANGE.last} seconds"
        multiplier.isNaN() || multiplier < WATCHER_LOOK_MULTIPLIER_RANGE.start || multiplier > WATCHER_LOOK_MULTIPLIER_RANGE.endInclusive ->
            "the multiplier must be between ${WATCHER_LOOK_MULTIPLIER_RANGE.start} and ${WATCHER_LOOK_MULTIPLIER_RANGE.endInclusive}"
        maxSeconds !in WATCHER_LOOK_MAX_RANGE ->
            "the maximum delay must be between ${WATCHER_LOOK_MAX_RANGE.first} and ${WATCHER_LOOK_MAX_RANGE.last} seconds"
        maxSeconds < initialSeconds -> "the maximum delay must be at least the initial delay"
        else -> null
    }

    private companion object {
        /** Past this many looks a list is a bug, not a schedule. */
        const val MOST_LOOKS = 10_000
    }
}

/** A week. */
const val DEFAULT_WATCHER_MAX_SECONDS = 7 * 24 * 60 * 60

const val DEFAULT_WATCHER_MIN_INTERVAL_SECONDS = 15

const val DEFAULT_WATCHER_MAX_PER_AGENT = 10

/** Five minutes: a look by the agent is a model turn, and a watcher exists so the agent does not take one per check. */
const val DEFAULT_WATCHER_MIN_AGENT_CHECK_SECONDS = 300

/**
 * A minute to a year. Below a minute a watcher is a timer with extra steps;
 * above a year nobody is still waiting for the answer.
 */
val WATCHER_MAX_SECONDS_RANGE = 60..365 * 24 * 60 * 60

/**
 * A second to a day. A second is the floor because the clock cannot keep
 * anything finer - a watcher is looked at on the scheduler's tick, ten seconds
 * by default - and a day is where an interval stops being watching.
 */
val WATCHER_MIN_INTERVAL_RANGE = 1..24 * 60 * 60

/** None, which takes watcher_set off the table, to a thousand. */
val WATCHER_MAX_PER_AGENT_RANGE = 0..1000

val WATCHER_MIN_AGENT_CHECK_RANGE = 1..365 * 24 * 60 * 60

/** First look after 5 min, then 10, 20, 40 ... later, never more than a day apart. */
val DEFAULT_LOOK_BACKOFF = LookBackoff(initialSeconds = 5 * 60, multiplier = 2.0, maxSeconds = 24 * 60 * 60)

/** A minute to a week. */
val WATCHER_LOOK_INITIAL_RANGE = 60..7 * 24 * 60 * 60

/** 1, a fixed interval, to 10. */
val WATCHER_LOOK_MULTIPLIER_RANGE = 1.0..10.0

/** A minute to a year. */
val WATCHER_LOOK_MAX_RANGE = 60..365 * 24 * 60 * 60

class WatcherMaxSecondsOutOfRangeException(val seconds: Int) : RuntimeException(
    "$seconds is not a length of time a watcher can be allowed to run for. " +
        "Choose between ${WATCHER_MAX_SECONDS_RANGE.first} and ${WATCHER_MAX_SECONDS_RANGE.last}.",
), Refusal {
    override val arguments get() = mapOf("seconds" to seconds)
}

class WatcherMinIntervalOutOfRangeException(val seconds: Int) : RuntimeException(
    "$seconds is not an interval a watcher can be held to. " +
        "Choose between ${WATCHER_MIN_INTERVAL_RANGE.first} and ${WATCHER_MIN_INTERVAL_RANGE.last}.",
), Refusal {
    override val arguments get() = mapOf("seconds" to seconds)
}

class WatcherMaxPerAgentOutOfRangeException(val count: Int) : RuntimeException(
    "$count is not a number of watchers an agent can be allowed. " +
        "Choose between ${WATCHER_MAX_PER_AGENT_RANGE.first} and ${WATCHER_MAX_PER_AGENT_RANGE.last}.",
), Refusal {
    override val arguments get() = mapOf("count" to count)
}

class WatcherLookBackoffInvalidException(val backoff: LookBackoff, val reason: String) : RuntimeException(
    "That is not a backoff for the agent's looks: $reason.",
), Refusal {
    override val arguments get() = mapOf(
        "initialSeconds" to backoff.initialSeconds,
        "multiplier" to backoff.multiplier,
        "maxSeconds" to backoff.maxSeconds,
    )
}

class WatcherMinAgentCheckOutOfRangeException(val seconds: Int) : RuntimeException(
    "$seconds is not an interval a watcher's agent check can be held to. " +
        "Choose between ${WATCHER_MIN_AGENT_CHECK_RANGE.first} and ${WATCHER_MIN_AGENT_CHECK_RANGE.last}.",
), Refusal {
    override val arguments get() = mapOf("seconds" to seconds)
}
