package io.mszymanski.orknux.server.attachment

import io.mszymanski.orknux.server.workflow.ExecutionRetentionProperties
import io.mszymanski.orknux.server.chat.ChatProperties
import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.monitoring.MetricsProperties
import io.mszymanski.orknux.server.revision.RevisionProperties
import io.mszymanski.orknux.server.task.TaskSweepProperties
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * One thing about this installation that somebody changed from the screen.
 *
 * A key and a value, because these are few and unrelated: a table per setting
 * would be a migration every time an administrator is given a switch, and a
 * column per setting on a one-row table is the same thing with extra steps.
 *
 * What is here overrides the configuration file, with one exception — a file
 * that says no is final. An operator who turned attachments off did it because
 * the disk is not theirs to fill, and a browser should not be able to overrule
 * that.
 */
@Entity
@Table(name = "installation_setting")
class InstallationSetting(
    @Id
    @Column(name = "name", nullable = false, length = 120)
    val name: String = "",

    @Column(nullable = false, length = 500)
    var value: String = "",

    @Column(name = "last_modified_at", nullable = false)
    var lastModifiedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_modified_by", nullable = false, length = 120)
    var lastModifiedBy: String = "",
)

interface InstallationSettingRepository : JpaRepository<InstallationSetting, String>

/** Where a name that is typed twice would be a bug. */
object SettingNames {
    const val ATTACHMENTS_ENABLED = "attachments.enabled"
    const val CHAT_ENABLED = "chat.enabled"
    const val METRICS_ANONYMOUS = "metrics.anonymous"
    const val REVISION_RETENTION_DAYS = "revision.retention.days"
    const val EXECUTION_RETENTION_DAYS = "execution.retention.days"
    const val TASK_SWEEP_MINUTES = "task.sweep.minutes"
    const val PLUGIN_MAX_SOURCE_KB = "plugin.max.source.kb"
    const val PLUGIN_TIMEOUT_SECONDS = "plugin.timeout.seconds"
    const val CHAT_MAX_ROUNDS = "chat.max.rounds"
}

/**
 * What this installation allows, as the configuration file and the screen agree
 * it.
 *
 * The file is the floor and the screen is the switch: everything defaults to
 * what was configured, and an administrator may turn something off — or back on
 * where the file permitted it in the first place.
 */
@Service
class InstallationSettings(
    private val settings: InstallationSettingRepository,
    private val properties: AttachmentProperties,
    private val chat: ChatProperties,
    private val metrics: MetricsProperties,
    private val revisions: RevisionProperties,
    private val tasks: TaskSweepProperties,
    private val runs: ExecutionRetentionProperties,
    /**
     * What the file says a plugin may take to load, which is where a fresh
     * installation starts before anybody touches the screen.
     */
    private val plugins: io.mszymanski.orknux.workflow.script.PluginProperties,
    /**
     * Which engine is carrying tasks, read as the container reads it.
     *
     * The property and not the bean. `TemporalProperties` only exists where
     * Temporal is on - its configuration class carries the same condition - so
     * asking for it here would leave an inline installation unable to build
     * this class at all; and injecting `TaskEngine` would put the whole of the
     * task machinery behind a setting the chat and the attachment store both
     * need. This is the string `@ConditionalOnProperty` on `InlineTaskEngine`
     * and `TemporalTaskEngine` is keyed on, with the same default, so it cannot
     * come to disagree with which bean was built.
     */
    @Value("\${orknux.temporal.enabled:true}") private val temporalEnabled: Boolean,
) {

    /**
     * Whether this installation has a chat at all.
     *
     * Off is a real answer: an installation that exists to run workflows has no
     * use for a chat window, and one whose models are not cleared for
     * conversation should not be offering one. The same floor as attachments —
     * false in the file cannot be pressed back on.
     */
    fun chatEnabled(): Boolean {
        if (!chat.enabled) return false
        val held = settings.findByIdOrNull(SettingNames.CHAT_ENABLED) ?: return true
        return held.value.toBooleanStrictOrNull() ?: true
    }

    /** Whether the screen may offer the switch at all. */
    fun chatConfigurable(): Boolean = chat.enabled

    @Transactional
    fun setChatEnabled(enabled: Boolean, by: String) = hold(SettingNames.CHAT_ENABLED, enabled, by)

    /**
     * Whether a chat may carry files.
     *
     * False in the file means false here, whatever was last pressed: the
     * operator's answer is the one that holds when the two disagree, because
     * only one of them owns the disk.
     */
    fun attachmentsEnabled(): Boolean {
        if (!properties.enabled) return false
        val held = settings.findByIdOrNull(SettingNames.ATTACHMENTS_ENABLED) ?: return true
        return held.value.toBooleanStrictOrNull() ?: true
    }

    /** Whether the screen may offer the switch at all. */
    fun attachmentsConfigurable(): Boolean = properties.enabled

    fun storage(): AttachmentStorage = properties.storage

    fun location(): String = properties.location

    fun maxFileSizeMb(): Long = properties.maxFileSizeMb

    @Transactional
    fun setAttachmentsEnabled(enabled: Boolean, by: String) = hold(SettingNames.ATTACHMENTS_ENABLED, enabled, by)

    /**
     * Whether `/actuator/prometheus` answers somebody who has not signed in.
     *
     * The one setting here where the file is not the floor, because for this one
     * the file's default *is* the closed answer. Attachments and the chat are on
     * unless an operator says otherwise, so "false in the file is final" costs an
     * administrator nothing; this is off unless somebody says otherwise, and the
     * same rule would mean the switch could never be pressed on a default
     * installation - a switch that is only ever a way of saying no twice.
     *
     * So the file is the value a fresh installation starts at, and what an
     * administrator stored is the answer from then on. Neither is fighting the
     * other: ORKNUX_METRICS_ANONYMOUS decides what happens before anybody has an
     * opinion, and after that the opinion is what happened.
     *
     * Read per request rather than once at startup - see SecurityConfig - which
     * is what lets the switch take effect without a restart.
     */
    fun metricsAnonymous(): Boolean {
        val held = settings.findByIdOrNull(SettingNames.METRICS_ANONYMOUS) ?: return metrics.anonymous
        return held.value.toBooleanStrictOrNull() ?: metrics.anonymous
    }

    /** What a fresh installation would answer, for a screen that wants to say so. */
    fun metricsAnonymousConfigured(): Boolean = metrics.anonymous

    @Transactional
    fun setMetricsAnonymous(enabled: Boolean, by: String) = hold(SettingNames.METRICS_ANONYMOUS, enabled, by)

    /**
     * How many days of a component's history are kept.
     *
     * The file is the value a fresh installation starts at and the screen is
     * the answer from then on - the same bargain the metrics switch is under,
     * and for the same reason: there is no closed answer here for a floor to
     * protect. Fourteen days is the default the owner chose.
     *
     * A stored value that is not a number, or is outside what the screen would
     * let anybody choose, reads as the configured one. It cannot be zero: a
     * retention of none is a feature switched off by a number, and the switch
     * for that would be a different setting with a different name.
     */
    fun revisionRetentionDays(): Int {
        val held = settings.findByIdOrNull(SettingNames.REVISION_RETENTION_DAYS) ?: return revisions.retentionDays
        return held.value.toIntOrNull()?.takeIf { it in MIN_RETENTION_DAYS..MAX_RETENTION_DAYS }
            ?: revisions.retentionDays
    }

    /** What a fresh installation would keep - ORKNUX_REVISION_RETENTION_DAYS. */
    fun revisionRetentionDaysConfigured(): Int = revisions.retentionDays

    /**
     * How many days of finished runs are kept.
     *
     * The same bargain as the setting above - the file is where a fresh
     * installation starts, the screen is the answer from then on - and the same
     * bounds, so one retention screen governs both. What differs is the
     * default: a revision is a copy of source nobody reads twice, a run is the
     * record of something that happened, and the questions asked of it are
     * asked weeks later. Issue #167.
     */
    fun executionRetentionDays(): Int {
        val held = settings.findByIdOrNull(SettingNames.EXECUTION_RETENTION_DAYS) ?: return runs.retentionDays
        return held.value.toIntOrNull()?.takeIf { it in MIN_RETENTION_DAYS..MAX_RETENTION_DAYS }
            ?: runs.retentionDays
    }

    /** What a fresh installation would keep - ORKNUX_EXECUTION_RETENTION_DAYS. */
    fun executionRetentionDaysConfigured(): Int = runs.retentionDays

    /**
     * How large one of a plugin's source files may be, in KB.
     *
     * One number for the plugin itself, each library it ships, and each file a
     * URL load fetches - it is the same question asked of each of them. In KB
     * on the screen because that is how the refusal has always said it; the
     * byte form below is for the code that measures.
     */
    fun pluginMaxSourceKb(): Int {
        val held = settings.findByIdOrNull(SettingNames.PLUGIN_MAX_SOURCE_KB)
            ?: return DEFAULT_PLUGIN_SOURCE_KB
        return held.value.toIntOrNull()?.takeIf { it in MIN_PLUGIN_SOURCE_KB..MAX_PLUGIN_SOURCE_KB }
            ?: DEFAULT_PLUGIN_SOURCE_KB
    }

    /** What a fresh installation allows: the built-in default. */
    fun pluginMaxSourceKbConfigured(): Int = DEFAULT_PLUGIN_SOURCE_KB

    /**
     * How long a plugin may take to load, in seconds.
     *
     * A screen rather than a restart, for the same reason the source cap is
     * one: which plugins an installation runs is not a decision made once at
     * deployment, and a bundle that needs twelve seconds on a small machine is
     * found out by somebody watching it fail - not by whoever wrote the
     * environment file.
     *
     * This is the *loading* bound. What one of its functions or tools may then
     * take is the workspace's business and is set there: a plugin that is slow
     * to parse and a tool that is slow to answer are different problems with
     * different people to talk to.
     */
    fun pluginTimeoutSeconds(): Int {
        val held = settings.findByIdOrNull(SettingNames.PLUGIN_TIMEOUT_SECONDS)
            ?: return pluginTimeoutSecondsConfigured()
        return held.value.toIntOrNull()?.takeIf { it in MIN_PLUGIN_TIMEOUT_SECONDS..MAX_PLUGIN_TIMEOUT_SECONDS }
            ?: pluginTimeoutSecondsConfigured()
    }

    /** What a fresh installation waits - ORKNUX_PLUGIN_TIMEOUT_MILLIS. */
    fun pluginTimeoutSecondsConfigured(): Int =
        (plugins.timeoutMillis / 1000).toInt().coerceIn(MIN_PLUGIN_TIMEOUT_SECONDS, MAX_PLUGIN_TIMEOUT_SECONDS)

    /** The same number where it is used, which is in milliseconds. */
    fun pluginTimeoutMillis(): Long = pluginTimeoutSeconds() * 1000L

    @Transactional
    fun setPluginTimeoutSeconds(seconds: Int, by: String) {
        if (seconds !in MIN_PLUGIN_TIMEOUT_SECONDS..MAX_PLUGIN_TIMEOUT_SECONDS) {
            throw PluginTimeoutOutOfRangeException(seconds)
        }
        val held = settings.findByIdOrNull(SettingNames.PLUGIN_TIMEOUT_SECONDS)
            ?: InstallationSetting(name = SettingNames.PLUGIN_TIMEOUT_SECONDS)
        held.value = seconds.toString()
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = by
        settings.save(held)
    }

    /**
     * How many rounds of tool calls an agent gets before it must answer.
     *
     * A round is one call to the model: it either answers or asks for tools, and
     * what it asks for is run and handed back. The ceiling exists so a model
     * talking to itself is stopped rather than billed for - what it is not for
     * is stopping honest work, which is what eight did to an agent with a
     * catalogue of tools in front of it.
     */
    fun chatMaxRounds(): Int {
        val held = settings.findByIdOrNull(SettingNames.CHAT_MAX_ROUNDS) ?: return chatMaxRoundsConfigured()
        return held.value.toIntOrNull()?.takeIf { it in MIN_CHAT_ROUNDS..MAX_CHAT_ROUNDS } ?: chatMaxRoundsConfigured()
    }

    /** What a fresh installation allows - ORKNUX_CHAT_MAX_ROUNDS. */
    fun chatMaxRoundsConfigured(): Int = chat.maxRounds.coerceIn(MIN_CHAT_ROUNDS, MAX_CHAT_ROUNDS)

    @Transactional
    fun setChatMaxRounds(rounds: Int, by: String) {
        if (rounds !in MIN_CHAT_ROUNDS..MAX_CHAT_ROUNDS) throw ChatRoundsOutOfRangeException(rounds)
        val held = settings.findByIdOrNull(SettingNames.CHAT_MAX_ROUNDS)
            ?: InstallationSetting(name = SettingNames.CHAT_MAX_ROUNDS)
        held.value = rounds.toString()
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = by
        settings.save(held)
    }

    fun pluginMaxSourceBytes(): Long = pluginMaxSourceKb() * 1024L

    @Transactional
    fun setPluginMaxSourceKb(kb: Int, by: String) {
        if (kb !in MIN_PLUGIN_SOURCE_KB..MAX_PLUGIN_SOURCE_KB) throw PluginSourceLimitOutOfRangeException(kb)
        val held = settings.findByIdOrNull(SettingNames.PLUGIN_MAX_SOURCE_KB)
            ?: InstallationSetting(name = SettingNames.PLUGIN_MAX_SOURCE_KB)
        held.value = kb.toString()
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = by
        settings.save(held)
    }

    @Transactional
    fun setExecutionRetentionDays(days: Int, by: String) {
        if (days !in MIN_RETENTION_DAYS..MAX_RETENTION_DAYS) throw RetentionOutOfRangeException(days)
        val held = settings.findByIdOrNull(SettingNames.EXECUTION_RETENTION_DAYS)
            ?: InstallationSetting(name = SettingNames.EXECUTION_RETENTION_DAYS)
        held.value = days.toString()
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = by
        settings.save(held)
    }

    @Transactional
    fun setRevisionRetentionDays(days: Int, by: String) {
        if (days !in MIN_RETENTION_DAYS..MAX_RETENTION_DAYS) throw RetentionOutOfRangeException(days)
        val held = settings.findByIdOrNull(SettingNames.REVISION_RETENTION_DAYS)
            ?: InstallationSetting(name = SettingNames.REVISION_RETENTION_DAYS)
        held.value = days.toString()
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = by
        settings.save(held)
    }

    /**
     * How many minutes a task may sit at QUEUED before something hands it over
     * again.
     *
     * The same bargain the retention has: the file is where a fresh
     * installation starts, and what an administrator stored is the answer from
     * then on. A stored value that is not a number, or is outside what the
     * screen would offer, reads as the configured one - so a row edited by hand
     * cannot switch the net off by being nonsense.
     *
     * Read on every pass, on both engines. It is honoured wherever it is
     * stored, including on an installation that has since moved to Temporal and
     * no longer draws the field; the sweep needs an interval either way, and a
     * number somebody chose is a better one than a number nobody did.
     */
    fun taskSweepMinutes(): Int {
        val held = settings.findByIdOrNull(SettingNames.TASK_SWEEP_MINUTES) ?: return tasks.minutes
        return held.value.toIntOrNull()?.takeIf { it in MIN_SWEEP_MINUTES..MAX_SWEEP_MINUTES } ?: tasks.minutes
    }

    /** What a fresh installation would wait - ORKNUX_TASK_SWEEP_MINUTES. */
    fun taskSweepMinutesConfigured(): Int = tasks.minutes

    /**
     * Whether the screen may offer the field at all.
     *
     * Which engine is carrying tasks, asked of the one thing that decides it.
     * `orknux.temporal.enabled` is what the `@ConditionalOnProperty` on
     * `InlineTaskEngine` and `TemporalTaskEngine` is keyed on, so reading it
     * here is reading the same fact the container read - not a second mechanism
     * that could come to disagree with the first.
     *
     * Off on Temporal because the interval is not an administrator's decision
     * there: what a Temporal installation is deciding about a stuck task is a
     * Temporal question, and the sweep still runs with whatever the file says.
     * The field would be a control whose effect nobody could see the shape of.
     */
    fun taskSweepConfigurable(): Boolean = !temporalEnabled

    /**
     * Both refusals are here rather than at the door.
     *
     * The chat and the attachment switches gate themselves in the resolver, and
     * this one does not, because what it is gating on is not a policy the
     * resolver could restate: `taskSweepConfigurable` is two lines up, and a
     * copy of it in the controller would be a second place to remember when the
     * engines change. What the screen will not offer, this will not hold.
     */
    @Transactional
    fun setTaskSweepMinutes(minutes: Int, by: String) {
        if (!taskSweepConfigurable()) throw TaskSweepNotConfigurableException()
        if (minutes !in MIN_SWEEP_MINUTES..MAX_SWEEP_MINUTES) throw TaskSweepIntervalOutOfRangeException(minutes)
        val held = settings.findByIdOrNull(SettingNames.TASK_SWEEP_MINUTES)
            ?: InstallationSetting(name = SettingNames.TASK_SWEEP_MINUTES)
        held.value = minutes.toString()
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = by
        settings.save(held)
    }

    private fun hold(name: String, enabled: Boolean, by: String) {
        val held = settings.findByIdOrNull(name) ?: InstallationSetting(name = name)
        held.value = enabled.toString()
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = by
        settings.save(held)
    }
}

/**
 * A day and ten years.
 *
 * The floor is a day rather than nothing, because "keep no history" is the
 * feature turned off and a number is the wrong way to say that. The ceiling is
 * there so a typed zero too many cannot quietly mean forever - the rows are
 * whole copies of source and prompts, and forever is the state this setting
 * exists to prevent.
 */
const val MIN_RETENTION_DAYS = 1
const val MAX_RETENTION_DAYS = 3650

/**
 * A minute and a day.
 *
 * The floor is a minute rather than nothing, because "sweep continuously" is
 * not a thing anybody wants: a task leaves QUEUED in the time it takes to read
 * its row, so a number below a minute buys no recovery and costs a query. The
 * ceiling is a day, which is already longer than anybody should wait to find
 * out a task never started - past that the net is not a net.
 *
 * Neither end is what makes the sweep safe. Handing the same task over twice is
 * refused by the engine, not by the interval; these bound how long a stranded
 * task is left, and nothing else.
 */
const val MIN_SWEEP_MINUTES = 1
const val MAX_SWEEP_MINUTES = 1440

/**
 * 64 KB and 20 MB, around a 5 MB default.
 *
 * The floor keeps a typo from making plugins unloadable; the ceiling keeps a
 * typed zero too many from letting a bundle fill the table - a plugin's source
 * is read whole on every call, so this number is also a statement about memory.
 */
/**
 * Two rounds and a hundred, around whatever the file says.
 *
 * The floor is two because one round is an agent that cannot use the tools it
 * was given - it would call them and never get to speak about what came back.
 * The ceiling is a hundred because every round is a paid call to a model, and an
 * agent that has not finished after a hundred of them is not close: it is
 * looping, which is the thing this bound exists to stop.
 */
const val MIN_CHAT_ROUNDS = 2
const val MAX_CHAT_ROUNDS = 100

class ChatRoundsOutOfRangeException(val rounds: Int) : RuntimeException(
    "$rounds is not a number of tool rounds an agent can be given. " +
        "Choose between $MIN_CHAT_ROUNDS and $MAX_CHAT_ROUNDS.",
), Refusal {

    override val arguments get() = mapOf("rounds" to rounds)
}

const val MIN_PLUGIN_SOURCE_KB = 64
const val MAX_PLUGIN_SOURCE_KB = 20 * 1024
const val DEFAULT_PLUGIN_SOURCE_KB = 5 * 1024

/**
 * One second and five minutes, around the file's own number.
 *
 * The floor is there because zero would make every plugin unloadable, and the
 * ceiling because this bound holds a thread: a plugin that cannot be parsed in
 * five minutes is not slow, it is wrong.
 */
const val MIN_PLUGIN_TIMEOUT_SECONDS = 1
const val MAX_PLUGIN_TIMEOUT_SECONDS = 300

class PluginTimeoutOutOfRangeException(val seconds: Int) : RuntimeException(
    "$seconds is not a number of seconds a plugin can be given to load. " +
        "Choose between $MIN_PLUGIN_TIMEOUT_SECONDS and $MAX_PLUGIN_TIMEOUT_SECONDS.",
), Refusal {

    override val arguments get() = mapOf("seconds" to seconds)
}

class PluginSourceLimitOutOfRangeException(val kb: Int) : RuntimeException(
    "$kb is not a number of KB a plugin source can be capped at. " +
        "Choose between $MIN_PLUGIN_SOURCE_KB and $MAX_PLUGIN_SOURCE_KB.",
), Refusal {

    override val arguments get() = mapOf("kb" to kb)
}

class TaskSweepIntervalOutOfRangeException(val minutes: Int) : RuntimeException(
    "$minutes is not a number of minutes a task can be left queued for. " +
        "Choose between $MIN_SWEEP_MINUTES and $MAX_SWEEP_MINUTES.",
), Refusal {

    override val arguments get() = mapOf("minutes" to minutes)
}

/**
 * The field, on an installation that does not draw it.
 *
 * Refused rather than stored quietly. A Temporal installation offers no control
 * for this, so a value that arrived anyway came from somewhere that should be
 * told - and storing one would leave a number in force that nobody can see.
 */
class TaskSweepNotConfigurableException : RuntimeException(
    "This installation runs its tasks on Temporal, where how long a queued task waits is not set here",
)

class RetentionOutOfRangeException(val days: Int) : RuntimeException(
    "$days is not a number of days history can be kept for. " +
        "Choose between $MIN_RETENTION_DAYS and $MAX_RETENTION_DAYS.",
), Refusal {

    override val arguments get() = mapOf("days" to days)
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AttachmentProperties::class, ChatProperties::class, MetricsProperties::class)
class AttachmentConfig
