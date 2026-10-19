package io.mszymanski.orknux.server.schedule

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
 * Where a fresh installation's schedule limits come from: `orknux.schedules.*`,
 * each a named ORKNUX_ variable in application.yml. Admin -> Settings ->
 * Schedules overrides both.
 */
@ConfigurationProperties(prefix = "orknux.schedules")
data class ScheduleProperties(
    /** The shortest a schedule may come round, in seconds. */
    val minIntervalSeconds: Int = DEFAULT_SCHEDULE_MIN_INTERVAL_SECONDS,
    /** How many schedules one agent may have running at once. */
    val maxPerAgent: Int = DEFAULT_SCHEDULE_MAX_PER_AGENT,
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ScheduleProperties::class)
class SchedulePropertiesConfig

/**
 * The two numbers that bound what an agent may ask a schedule to do.
 *
 * Both bite only at `schedule_set`: an administrator who raises the floor
 * means it for the schedules asked for next, not as a way of ending the ones an
 * agent is already counting on. Every run of a schedule is a turn of a model,
 * which is why the floor is minutes where a watcher's - a tool call, no model -
 * is seconds.
 *
 * Kept in the installation's key-value table beside every other setting, so a
 * new one is a row and not a migration.
 */
@Service
class ScheduleSettings(
    private val settings: InstallationSettingRepository,
    private val properties: ScheduleProperties,
) {

    /** The shortest a schedule may come round, in seconds - by interval or by its cron. */
    fun minIntervalSeconds(): Int = held(MIN_INTERVAL, SCHEDULE_MIN_INTERVAL_RANGE) ?: minIntervalSecondsConfigured()

    /** What a fresh installation allows - ORKNUX_SCHEDULE_MIN_INTERVAL_SECONDS. */
    fun minIntervalSecondsConfigured(): Int = properties.minIntervalSeconds.coerceIn(SCHEDULE_MIN_INTERVAL_RANGE)

    /** How many schedules one agent may have running at once; zero switches schedules off. */
    fun maxPerAgent(): Int = held(MAX_PER_AGENT, SCHEDULE_MAX_PER_AGENT_RANGE) ?: maxPerAgentConfigured()

    /** What a fresh installation allows - ORKNUX_SCHEDULE_MAX_PER_AGENT. */
    fun maxPerAgentConfigured(): Int = properties.maxPerAgent.coerceIn(SCHEDULE_MAX_PER_AGENT_RANGE)

    @Transactional
    fun setMinIntervalSeconds(seconds: Int, by: String) {
        if (seconds !in SCHEDULE_MIN_INTERVAL_RANGE) throw ScheduleMinIntervalOutOfRangeException(seconds)
        write(MIN_INTERVAL, seconds, by)
    }

    @Transactional
    fun setMaxPerAgent(count: Int, by: String) {
        if (count !in SCHEDULE_MAX_PER_AGENT_RANGE) throw ScheduleMaxPerAgentOutOfRangeException(count)
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
        const val MIN_INTERVAL = "schedule.min.interval.seconds"
        const val MAX_PER_AGENT = "schedule.max.per.agent"
    }
}

/** Five minutes: every run is a model turn, and a schedule tighter than that is a loop. */
const val DEFAULT_SCHEDULE_MIN_INTERVAL_SECONDS = 300

const val DEFAULT_SCHEDULE_MAX_PER_AGENT = 10

/**
 * Ten seconds to a week. Ten is the scheduler's own tick by default
 * (ORKNUX_SCHEDULER_TICK_INTERVAL), the finest a schedule is really kept to; a
 * floor of a week is the most an administrator could mean by "rarely".
 */
val SCHEDULE_MIN_INTERVAL_RANGE = 10..7 * 24 * 60 * 60

/** None, which takes the schedule tools off the table, to a thousand. */
val SCHEDULE_MAX_PER_AGENT_RANGE = 0..1000

class ScheduleMinIntervalOutOfRangeException(val seconds: Int) : RuntimeException(
    "$seconds is not an interval a schedule can be held to. " +
        "Choose between ${SCHEDULE_MIN_INTERVAL_RANGE.first} and ${SCHEDULE_MIN_INTERVAL_RANGE.last}.",
), Refusal {
    override val arguments get() = mapOf("seconds" to seconds)
}

class ScheduleMaxPerAgentOutOfRangeException(val count: Int) : RuntimeException(
    "$count is not a number of schedules an agent can be allowed. " +
        "Choose between ${SCHEDULE_MAX_PER_AGENT_RANGE.first} and ${SCHEDULE_MAX_PER_AGENT_RANGE.last}.",
), Refusal {
    override val arguments get() = mapOf("count" to count)
}
