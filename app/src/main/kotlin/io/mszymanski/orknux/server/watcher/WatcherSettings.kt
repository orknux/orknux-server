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
    /** How long a watcher is that gets three looks; see [WatcherSettings.looksFor]. */
    val lookScaleSeconds: Int = DEFAULT_WATCHER_LOOK_SCALE_SECONDS,
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
     * The length of watcher that gets three looks of the agent's own; the rest
     * of the schedule is measured against it - see [looksFor].
     */
    fun lookScaleSeconds(): Int = held(LOOK_SCALE, WATCHER_LOOK_SCALE_RANGE) ?: lookScaleSecondsConfigured()

    fun lookScaleSecondsConfigured(): Int = properties.lookScaleSeconds.coerceIn(WATCHER_LOOK_SCALE_RANGE)

    /**
     * Seconds between the agent's own looks for a watcher of [timeout] checked
     * every [interval], where nobody chose: [looksFor] looks, evenly spaced, so
     * the last falls before the timeout rather than on it. Fewer where that
     * spacing would be shorter than [minAgentCheckSeconds] or [interval];
     * null where not even one fits.
     */
    fun agentCheckFor(timeout: Int, interval: Int): Int? {
        val floor = maxOf(minAgentCheckSeconds(), interval)
        for (looks in looksFor(timeout, lookScaleSeconds()) downTo 1) {
            val every = timeout / (looks + 1)
            if (every >= floor) return every
        }
        return null
    }

    @Transactional
    fun setLookScaleSeconds(seconds: Int, by: String) {
        if (seconds !in WATCHER_LOOK_SCALE_RANGE) throw WatcherLookScaleOutOfRangeException(seconds)
        write(LOOK_SCALE, seconds, by)
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

    private fun write(name: String, value: Int, by: String) {
        val held = settings.findByIdOrNull(name) ?: InstallationSetting(name = name)
        held.value = value.toString()
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = by
        settings.save(held)
    }

    companion object {
        const val MAX_SECONDS = "watcher.max.seconds"
        const val MIN_INTERVAL = "watcher.min.interval.seconds"
        const val MAX_PER_AGENT = "watcher.max.per.agent"
        const val MIN_AGENT_CHECK = "watcher.min.agent.check.seconds"
        const val LOOK_SCALE = "watcher.look.scale.seconds"

        /**
         * How many looks of its own an agent gets on a watcher of [timeout]
         * seconds, against [scale] (an hour by default): none below a twelfth of
         * it, one below a third, two below the whole, three at it, and one more
         * each time the timeout doubles - 3 + floor(log2(timeout / scale)).
         * With an hour: 5-20 min one, 20-60 two, 1-2 h three, 2-4 h four.
         */
        fun looksFor(timeout: Int, scale: Int): Int {
            val t = timeout.toLong()
            val s = scale.toLong()
            if (t * 12 < s) return 0
            if (t * 3 < s) return 1
            if (t < s) return 2
            var looks = 3
            var edge = s * 2
            while (t >= edge) {
                looks++
                edge *= 2
            }
            return looks
        }
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

/** An hour: five minutes to twenty get one look, twenty to an hour two, and each doubling past it one more. */
const val DEFAULT_WATCHER_LOOK_SCALE_SECONDS = 60 * 60

/** A minute to a week. */
val WATCHER_LOOK_SCALE_RANGE = 60..7 * 24 * 60 * 60

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

class WatcherLookScaleOutOfRangeException(val seconds: Int) : RuntimeException(
    "$seconds is not a length the agent's looks can be measured against. " +
        "Choose between ${WATCHER_LOOK_SCALE_RANGE.first} and ${WATCHER_LOOK_SCALE_RANGE.last}.",
), Refusal {
    override val arguments get() = mapOf("seconds" to seconds)
}

class WatcherMinAgentCheckOutOfRangeException(val seconds: Int) : RuntimeException(
    "$seconds is not an interval a watcher's agent check can be held to. " +
        "Choose between ${WATCHER_MIN_AGENT_CHECK_RANGE.first} and ${WATCHER_MIN_AGENT_CHECK_RANGE.last}.",
), Refusal {
    override val arguments get() = mapOf("seconds" to seconds)
}
