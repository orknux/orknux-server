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
    }
}

/** A week. */
const val DEFAULT_WATCHER_MAX_SECONDS = 7 * 24 * 60 * 60

const val DEFAULT_WATCHER_MIN_INTERVAL_SECONDS = 15

const val DEFAULT_WATCHER_MAX_PER_AGENT = 10

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
