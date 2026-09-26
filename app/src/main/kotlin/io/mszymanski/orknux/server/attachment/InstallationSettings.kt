package io.mszymanski.orknux.server.attachment

import io.mszymanski.orknux.server.workflow.ExecutionRetentionProperties
import io.mszymanski.orknux.server.chat.ChatProperties
import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.llm.SessionProperties
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
    const val AGENT_SLEEP_SECONDS = "agent.sleep.seconds"
    const val AGENT_SLEEP_TIMES = "agent.sleep.times"
    const val AGENT_MAX_SUBAGENTS = "agent.max.subagents"
    const val COMMAND_MARKER = "command.marker"
    const val SESSIONS_REMOVABLE = "sessions.removable"
    const val SCRATCHPAD_BUDGET_BYTES = "scratchpad.budget.bytes"

    /** What the files in one session's scratchpads may come to. Issue #491. */
    const val SCRATCHPAD_FILE_BUDGET_BYTES = "scratchpad.file.budget.bytes"

    /** How long a scratchpad nobody touches is kept. Issue #492. */
    const val SCRATCHPAD_KEEP_DAYS = "scratchpad.keep.days"
    const val TOOLS_NAMED_IN_SEARCH = "tools.named.in.search"

    /** How many tools fit in the briefing before their lines start being cut. Issue #481. */
    const val TOOL_SUMMARIES_FULL_UP_TO = "tool.summaries.full.up.to"

    /** And how much of each line the next block of that many costs. Issue #481. */
    const val TOOL_SUMMARY_TRIM_PERCENT = "tool.summary.trim.percent"
    const val SESSIONS_ACTIVE_WINDOW_SECONDS = "sessions.active.window.seconds"
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
    /** Where a fresh installation starts on how long a session counts as active. Issue #448. */
    private val sessions: SessionProperties,
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

    /**
     * The longest an agent may put itself to sleep for, in seconds.
     *
     * An agent that ends its turn with a wake-up parks the step it is on, and
     * the run comes back to that node when the time is up. This is the ceiling
     * on one of those waits: a model asking for longer is given this instead
     * and told so, because the number is a statement about how long this
     * installation is willing to hold a run open, not about what the model
     * would prefer.
     */
    fun agentSleepSeconds(): Int {
        val held = settings.findByIdOrNull(SettingNames.AGENT_SLEEP_SECONDS) ?: return agentSleepSecondsConfigured()
        return held.value.toIntOrNull()?.takeIf { it in MIN_SLEEP_SECONDS..MAX_SLEEP_SECONDS }
            ?: agentSleepSecondsConfigured()
    }

    /** What a fresh installation allows - ORKNUX_CHAT_SLEEP_SECONDS. */
    fun agentSleepSecondsConfigured(): Int = chat.sleepSeconds.coerceIn(MIN_SLEEP_SECONDS, MAX_SLEEP_SECONDS)

    @Transactional
    fun setAgentSleepSeconds(seconds: Int, by: String) {
        if (seconds !in MIN_SLEEP_SECONDS..MAX_SLEEP_SECONDS) throw SleepSecondsOutOfRangeException(seconds)
        write(SettingNames.AGENT_SLEEP_SECONDS, seconds.toString(), by)
    }

    /**
     * How many times in a row an agent may sleep on one step; zero is never.
     *
     * A wait is a decision the model makes again every time it wakes, so the
     * one that matters is not the first but the twentieth. Zero takes the
     * wake-up off the tool altogether, which is the installation saying its
     * agents answer or finish and do neither by halves.
     */
    fun agentSleepTimes(): Int {
        val held = settings.findByIdOrNull(SettingNames.AGENT_SLEEP_TIMES) ?: return agentSleepTimesConfigured()
        return held.value.toIntOrNull()?.takeIf { it in MIN_SLEEP_TIMES..MAX_SLEEP_TIMES } ?: agentSleepTimesConfigured()
    }

    /** What a fresh installation allows - ORKNUX_CHAT_SLEEP_TIMES. */
    fun agentSleepTimesConfigured(): Int = chat.sleepTimes.coerceIn(MIN_SLEEP_TIMES, MAX_SLEEP_TIMES)

    @Transactional
    fun setAgentSleepTimes(times: Int, by: String) {
        if (times !in MIN_SLEEP_TIMES..MAX_SLEEP_TIMES) throw SleepTimesOutOfRangeException(times)
        write(SettingNames.AGENT_SLEEP_TIMES, times.toString(), by)
    }

    /**
     * How many other agents one agent may ask in one conversation; zero is none.
     *
     * Each ask starts a conversation of its own, with its own model calls and
     * its own tools, on the asking model's say-so - so this is the number that
     * bounds what one question can fan out into. Counted per conversation, and
     * an agent that has spent them is told so and answers with what it has.
     * Zero takes the tool off the table. A workspace may carry its own number,
     * which wins here. Issue #380.
     */
    fun agentMaxSubagents(): Int {
        val held = settings.findByIdOrNull(SettingNames.AGENT_MAX_SUBAGENTS) ?: return agentMaxSubagentsConfigured()
        return held.value.toIntOrNull()?.takeIf { it in MIN_SUBAGENTS..MAX_SUBAGENTS } ?: agentMaxSubagentsConfigured()
    }

    /** What a fresh installation allows - ORKNUX_CHAT_MAX_SUBAGENTS. */
    fun agentMaxSubagentsConfigured(): Int = chat.maxSubagents.coerceIn(MIN_SUBAGENTS, MAX_SUBAGENTS)

    @Transactional
    fun setAgentMaxSubagents(count: Int, by: String) {
        if (count !in MIN_SUBAGENTS..MAX_SUBAGENTS) throw SubagentsOutOfRangeException(count)
        write(SettingNames.AGENT_MAX_SUBAGENTS, count.toString(), by)
    }

    /**
     * How many bytes one session's scratchpads may occupy in all. Issue #411.
     *
     * A ceiling in bytes because a scratchpad is a document a model writes at
     * will - an agent told to draft something long could otherwise grow one
     * without bound. The count is the whole of a session's pads, so a session
     * cannot get round it by spreading text across many.
     */
    fun scratchpadBudgetBytes(): Int {
        val held = settings.findByIdOrNull(SettingNames.SCRATCHPAD_BUDGET_BYTES) ?: return DEFAULT_SCRATCHPAD_BYTES
        return held.value.toIntOrNull()?.takeIf { it in MIN_SCRATCHPAD_BYTES..MAX_SCRATCHPAD_BYTES } ?: DEFAULT_SCRATCHPAD_BYTES
    }

    /** What a fresh installation allows before anybody sets it. */
    fun scratchpadBudgetBytesConfigured(): Int = DEFAULT_SCRATCHPAD_BYTES

    @Transactional
    fun setScratchpadBudgetBytes(bytes: Int, by: String) {
        if (bytes !in MIN_SCRATCHPAD_BYTES..MAX_SCRATCHPAD_BYTES) throw ScratchpadBudgetOutOfRangeException(bytes)
        write(SettingNames.SCRATCHPAD_BUDGET_BYTES, bytes.toString(), by)
    }

    /**
     * What the files in one session's scratchpads may come to, in bytes.
     * Issue #491.
     *
     * Apart from the budget above, which is about text an agent writes and is
     * counted in characters it could have spent on the answer. A file is not
     * that: it is a picture somebody asked for, it is base64 in a text column,
     * and it stays as long as the session does whether or not anything reads it
     * again. An agent that drew twenty charts would otherwise leave twenty
     * megabytes behind.
     *
     * Past this, the oldest files are removed until the session is under it -
     * the newest being the one actually in use - and the tool's answer says
     * which went, because an agent that finds out later is an agent that packed
     * an archive around a file that is gone.
     */
    fun scratchpadFileBudgetBytes(): Long {
        val held = settings.findByIdOrNull(SettingNames.SCRATCHPAD_FILE_BUDGET_BYTES)
            ?: return DEFAULT_SCRATCHPAD_FILE_BYTES
        return held.value.toLongOrNull()?.takeIf { it in MIN_FILE_BYTES..MAX_FILE_BYTES }
            ?: DEFAULT_SCRATCHPAD_FILE_BYTES
    }

    /** What a fresh installation allows before anybody sets it: ten megabytes. */
    fun scratchpadFileBudgetBytesConfigured(): Long = DEFAULT_SCRATCHPAD_FILE_BYTES

    @Transactional
    fun setScratchpadFileBudgetBytes(bytes: Long, by: String) {
        if (bytes !in MIN_FILE_BYTES..MAX_FILE_BYTES) throw ScratchpadFileBudgetOutOfRangeException(bytes)
        write(SettingNames.SCRATCHPAD_FILE_BUDGET_BYTES, bytes.toString(), by)
    }

    /**
     * Up to how many findable tools `find_tools` names outright, in its own
     * description and in a miss, so the model asks for one by name instead of
     * guessing words for a search. Above the number the tool says only how
     * many there are; zero never names them. Issue #442.
     *
     * A setting and not a constant, because what is "few enough to list" is
     * a judgement about the models an installation runs and the tools its
     * plugins bring - forty names are nothing to a large model and a wall to a
     * small one - and the first cut of this had the number in the source.
     */
    fun toolsNamedInSearch(): Int {
        val held = settings.findByIdOrNull(SettingNames.TOOLS_NAMED_IN_SEARCH) ?: return toolsNamedInSearchConfigured()
        return held.value.toIntOrNull()?.takeIf { it in MIN_TOOLS_NAMED..MAX_TOOLS_NAMED } ?: toolsNamedInSearchConfigured()
    }

    /** What a fresh installation names - ORKNUX_CHAT_TOOLS_NAMED_IN_SEARCH. */
    fun toolsNamedInSearchConfigured(): Int = chat.toolsNamedInSearch.coerceIn(MIN_TOOLS_NAMED, MAX_TOOLS_NAMED)

    @Transactional
    fun setToolsNamedInSearch(count: Int, by: String) {
        if (count !in MIN_TOOLS_NAMED..MAX_TOOLS_NAMED) throw ToolsNamedOutOfRangeException(count)
        write(SettingNames.TOOLS_NAMED_IN_SEARCH, count.toString(), by)
    }

    /**
     * How many tools an agent can hold before their lines in the briefing are
     * cut, and by how much each further block of that many costs. Issue #481.
     *
     * Every tool an agent holds is named in its system prompt with a phrase
     * saying what it is for, so the model knows what it has rather than
     * guessing words for a search. That list is cheap at twenty tools and is
     * not at three hundred, so it is trimmed: full lines up to the first
     * number, and for each further block of that many, this percentage comes
     * off what is kept. The front of a phrase survives, which is why the editor
     * says the first words matter most.
     *
     * Both are settings and neither is a constant, because what an installation
     * can afford in its system prompt is a fact about its models and its
     * plugins, not about this product.
     */
    /**
     * How long a scratchpad nobody has touched is kept, in days. Issue #492.
     *
     * Zero is never, which is the answer for an installation keeping its pads
     * as part of the record - so this is a keep-for rather than a delete-after,
     * and the sweeper reads it on every pass, so a change this morning is obeyed
     * before the next restart rather than after it.
     *
     * From the last change and not from when the pad was made: a document still
     * being worked on survives, and one nobody has touched since last month is
     * the workings of a session that is over.
     */
    fun scratchpadKeepDays(): Int {
        val held = settings.findByIdOrNull(SettingNames.SCRATCHPAD_KEEP_DAYS) ?: return scratchpadKeepDaysConfigured()
        return held.value.toIntOrNull()?.takeIf { it in MIN_KEEP_DAYS..MAX_KEEP_DAYS } ?: scratchpadKeepDaysConfigured()
    }

    /** What a fresh installation keeps them for: thirty days. */
    fun scratchpadKeepDaysConfigured(): Int = DEFAULT_SCRATCHPAD_KEEP_DAYS

    @Transactional
    fun setScratchpadKeepDays(days: Int, by: String) {
        if (days !in MIN_KEEP_DAYS..MAX_KEEP_DAYS) throw ScratchpadKeepOutOfRangeException(days)
        write(SettingNames.SCRATCHPAD_KEEP_DAYS, days.toString(), by)
    }

    fun toolSummariesFullUpTo(): Int {
        val held = settings.findByIdOrNull(SettingNames.TOOL_SUMMARIES_FULL_UP_TO)
            ?: return toolSummariesFullUpToConfigured()
        return held.value.toIntOrNull()?.takeIf { it in MIN_SUMMARIES_FULL..MAX_SUMMARIES_FULL }
            ?: toolSummariesFullUpToConfigured()
    }

    /** What a fresh installation carries - ORKNUX_CHAT_TOOL_SUMMARIES_FULL_UP_TO. */
    fun toolSummariesFullUpToConfigured(): Int =
        chat.toolSummariesFullUpTo.coerceIn(MIN_SUMMARIES_FULL, MAX_SUMMARIES_FULL)

    @Transactional
    fun setToolSummariesFullUpTo(count: Int, by: String) {
        if (count !in MIN_SUMMARIES_FULL..MAX_SUMMARIES_FULL) throw SummariesFullOutOfRangeException(count)
        write(SettingNames.TOOL_SUMMARIES_FULL_UP_TO, count.toString(), by)
    }

    fun toolSummaryTrimPercent(): Int {
        val held = settings.findByIdOrNull(SettingNames.TOOL_SUMMARY_TRIM_PERCENT)
            ?: return toolSummaryTrimPercentConfigured()
        return held.value.toIntOrNull()?.takeIf { it in MIN_SUMMARY_TRIM..MAX_SUMMARY_TRIM }
            ?: toolSummaryTrimPercentConfigured()
    }

    /** What a fresh installation carries - ORKNUX_CHAT_TOOL_SUMMARY_TRIM_PERCENT. */
    fun toolSummaryTrimPercentConfigured(): Int =
        chat.toolSummaryTrimPercent.coerceIn(MIN_SUMMARY_TRIM, MAX_SUMMARY_TRIM)

    @Transactional
    fun setToolSummaryTrimPercent(percent: Int, by: String) {
        if (percent !in MIN_SUMMARY_TRIM..MAX_SUMMARY_TRIM) throw SummaryTrimOutOfRangeException(percent)
        write(SettingNames.TOOL_SUMMARY_TRIM_PERCENT, percent.toString(), by)
    }

    /**
     * How long a session counts as active after its last line, in seconds.
     * Issue #448.
     *
     * The recency half of the sessions list's dot: a line written within this
     * long means an agent is still there, even between two lines. The other
     * half - a tool call with no result, a thought with no end - counts only
     * while a run or a task that writes into the session is still going, and
     * needs no number. A minute was in the source (#404), and a minute is right
     * for a chatty agent and wrong for one whose model thinks for three between
     * two lines; which an installation has is a judgement about its models,
     * so it is a knob.
     */
    fun sessionsActiveWindowSeconds(): Int {
        val held = settings.findByIdOrNull(SettingNames.SESSIONS_ACTIVE_WINDOW_SECONDS)
            ?: return sessionsActiveWindowSecondsConfigured()
        return held.value.toIntOrNull()?.takeIf { it in MIN_ACTIVE_WINDOW_SECONDS..MAX_ACTIVE_WINDOW_SECONDS }
            ?: sessionsActiveWindowSecondsConfigured()
    }

    /** What a fresh installation counts - ORKNUX_SESSIONS_ACTIVE_WINDOW_SECONDS. */
    fun sessionsActiveWindowSecondsConfigured(): Int =
        sessions.activeWindowSeconds.coerceIn(MIN_ACTIVE_WINDOW_SECONDS, MAX_ACTIVE_WINDOW_SECONDS)

    @Transactional
    fun setSessionsActiveWindowSeconds(seconds: Int, by: String) {
        if (seconds !in MIN_ACTIVE_WINDOW_SECONDS..MAX_ACTIVE_WINDOW_SECONDS) throw ActiveWindowOutOfRangeException(seconds)
        write(SettingNames.SESSIONS_ACTIVE_WINDOW_SECONDS, seconds.toString(), by)
    }

    /**
     * What marks a command in a message that starts a run, for the whole
     * installation - `!review`. A workspace may carry its own, which wins.
     * Issue #402.
     */
    fun commandMarker(): String {
        val held = settings.findByIdOrNull(SettingNames.COMMAND_MARKER) ?: return commandMarkerConfigured()
        return held.value.takeIf { io.mszymanski.orknux.server.trigger.Commands.usableMarker(it) } ?: commandMarkerConfigured()
    }

    /** What a fresh installation starts on - ORKNUX_COMMAND_MARKER. */
    fun commandMarkerConfigured(): String =
        chat.commandMarker.takeIf { io.mszymanski.orknux.server.trigger.Commands.usableMarker(it) }
            ?: io.mszymanski.orknux.server.trigger.Commands.DEFAULT_MARKER

    @Transactional
    fun setCommandMarker(marker: String, by: String) {
        val wanted = marker.trim()
        if (!io.mszymanski.orknux.server.trigger.Commands.usableMarker(wanted)) {
            throw io.mszymanski.orknux.server.workspace.CommandMarkerInvalidException(wanted)
        }
        write(SettingNames.COMMAND_MARKER, wanted, by)
    }

    /** Stores one number under its name, made or found, stamped with who. */
    private fun write(name: String, value: String, by: String) {
        val held = settings.findByIdOrNull(name) ?: InstallationSetting(name = name)
        held.value = value
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = by
        settings.save(held)
    }

    /**
     * Whether a conversation may be thrown away.
     *
     * A session is the record of what an agent was asked and what it answered,
     * and on some installations that is the only account of a decision anybody
     * has. Removing one is a person tidying up after a mistyped key or a run
     * they were trying out - which is what it is for - but on an installation
     * that has to be able to say what happened, it is a hole somebody can put in
     * the record with one press and no way back.
     *
     * So an operator can close the door. On by default, because that is how
     * every installation has worked until now and a switch that silently took an
     * ability away on upgrade would be worse than the hole.
     */
    fun sessionsRemovable(): Boolean {
        val held = settings.findByIdOrNull(SettingNames.SESSIONS_REMOVABLE) ?: return true
        return held.value.toBooleanStrictOrNull() ?: true
    }

    @Transactional
    fun setSessionsRemovable(removable: Boolean, by: String) {
        write(SettingNames.SESSIONS_REMOVABLE, removable.toString(), by)
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

/**
 * A second and a day, for one of an agent's waits.
 *
 * The floor is a second because a wake-up shorter than that is not a wait, it
 * is the same round with a round trip in the middle. The ceiling is a day
 * because a run held open longer than that is not waiting on anything it can
 * still do something about - the thing to do then is finish and let a trigger
 * start it again.
 */
const val MIN_SLEEP_SECONDS = 1
const val MAX_SLEEP_SECONDS = 24 * 60 * 60

/** None and a hundred, for how many of those waits one step may take in a row. */
const val MIN_SLEEP_TIMES = 0
const val MAX_SLEEP_TIMES = 100

class SleepSecondsOutOfRangeException(val seconds: Int) : RuntimeException(
    "$seconds is not a length of time an agent can be allowed to wait for. " +
        "Choose between $MIN_SLEEP_SECONDS and $MAX_SLEEP_SECONDS.",
), Refusal {

    override val arguments get() = mapOf("seconds" to seconds)
}

class SleepTimesOutOfRangeException(val times: Int) : RuntimeException(
    "$times is not a number of times an agent can be allowed to wait in a row. " +
        "Choose between $MIN_SLEEP_TIMES and $MAX_SLEEP_TIMES.",
), Refusal {

    override val arguments get() = mapOf("times" to times)
}

/**
 * None and a hundred, for how many other agents one agent may ask in a
 * conversation. A hundred is a bill rather than a brief; the ceiling exists to
 * catch a digit too many.
 */
const val MIN_SUBAGENTS = 0
const val MAX_SUBAGENTS = 100

class SubagentsOutOfRangeException(val count: Int) : RuntimeException(
    "$count is not a number of agents an agent can be allowed to ask. " +
        "Choose between $MIN_SUBAGENTS and $MAX_SUBAGENTS.",
), Refusal {

    override val arguments get() = mapOf("count" to count)
}

/**
 * A kilobyte and sixty-four megabytes, for how much one session's scratchpads
 * may hold in all. The floor is a kilobyte because a budget of nothing is a
 * feature switched off; the ceiling catches a value that would let one session
 * fill a disk. A megabyte is the default - room for a long document, not for a
 * library. Issue #411.
 */
/**
 * A megabyte and half a gigabyte, for what one session's files may come to, and
 * ten megabytes to start. Issue #491.
 *
 * The floor is a megabyte because a budget under one picture is a budget that
 * removes what it just wrote; the ceiling is where a session's pads stop being
 * working files and start being somebody's disk.
 */
/**
 * Never, and five years, for how long a scratchpad nobody touches is kept, with
 * thirty days to start. Issue #492.
 *
 * Zero is never on purpose rather than by accident: an installation that keeps
 * its pads as part of the record needs a way to say so, and a switch beside a
 * number would be two controls for one decision.
 */
const val DEFAULT_SCRATCHPAD_KEEP_DAYS = 30
const val MIN_KEEP_DAYS = 0
const val MAX_KEEP_DAYS = 1825

class ScratchpadKeepOutOfRangeException(val days: Int) : RuntimeException(
    "$days is not a number of days a scratchpad can be kept for. " +
        "Choose between $MIN_KEEP_DAYS and $MAX_KEEP_DAYS, where 0 keeps them for ever.",
), Refusal {

    override val arguments get() = mapOf("days" to days)
}

const val DEFAULT_SCRATCHPAD_FILE_BYTES = 10L * 1024 * 1024
const val MIN_FILE_BYTES = 1L * 1024 * 1024
const val MAX_FILE_BYTES = 512L * 1024 * 1024

class ScratchpadFileBudgetOutOfRangeException(val bytes: Long) : RuntimeException(
    "$bytes is not a size a session's files can be held to. " +
        "Choose between ${MIN_FILE_BYTES / (1024 * 1024)} and ${MAX_FILE_BYTES / (1024 * 1024)} megabytes.",
), Refusal {

    override val arguments get() = mapOf("bytes" to bytes)
}

const val MIN_SCRATCHPAD_BYTES = 1024
const val MAX_SCRATCHPAD_BYTES = 64 * 1024 * 1024
const val DEFAULT_SCRATCHPAD_BYTES = 1024 * 1024

class ScratchpadBudgetOutOfRangeException(val bytes: Int) : RuntimeException(
    "$bytes is not a size a session's scratchpads can be held to. " +
        "Choose between $MIN_SCRATCHPAD_BYTES and $MAX_SCRATCHPAD_BYTES bytes.",
), Refusal {

    override val arguments get() = mapOf("bytes" to bytes)
}

/**
 * None and five hundred, for how many findable tools `find_tools` names outright.
 * Zero is the listing switched off; five hundred names is already a page of
 * text, past which the tool would be the cost it exists to avoid. Issue #442.
 */
const val MIN_TOOLS_NAMED = 0
const val MAX_TOOLS_NAMED = 500

class ToolsNamedOutOfRangeException(val count: Int) : RuntimeException(
    "$count is not a number of tools find_tools can be told to name. " +
        "Choose between $MIN_TOOLS_NAMED and $MAX_TOOLS_NAMED.",
), Refusal {

    override val arguments get() = mapOf("count" to count)
}

/**
 * Ten and a thousand, for how many tools fit before their lines are cut, and
 * none and half, for how much each further block costs. Issue #481.
 *
 * The floor on the block is ten because a briefing that trims at five tools is
 * one that never lists anything in full; the ceiling is a thousand because past
 * that the list is the cost it exists to avoid. Zero percent is the trimming
 * switched off, which an installation with a large model may well want, and
 * fifty is as much as can come off before the phrase stops being a phrase.
 */
const val MIN_SUMMARIES_FULL = 10
const val MAX_SUMMARIES_FULL = 1000
const val MIN_SUMMARY_TRIM = 0
const val MAX_SUMMARY_TRIM = 50

class SummariesFullOutOfRangeException(val count: Int) : RuntimeException(
    "$count is not a number of tools whose lines can be kept in full. " +
        "Choose between $MIN_SUMMARIES_FULL and $MAX_SUMMARIES_FULL.",
), Refusal {

    override val arguments get() = mapOf("count" to count)
}

class SummaryTrimOutOfRangeException(val percent: Int) : RuntimeException(
    "$percent is not a percentage a tool's line can be trimmed by. " +
        "Choose between $MIN_SUMMARY_TRIM and $MAX_SUMMARY_TRIM.",
), Refusal {

    override val arguments get() = mapOf("percent" to percent)
}

/**
 * A second and an hour, for how long a session counts as active after its
 * last line. The floor is a second because a window of nothing is the recency
 * half of the rule switched off, and the switch for that would be a different
 * setting; the ceiling is an hour because a session nobody has written into
 * for longer is not one anybody is at work in, whatever its model is doing.
 * Issue #448.
 */
const val MIN_ACTIVE_WINDOW_SECONDS = 1
const val MAX_ACTIVE_WINDOW_SECONDS = 3600

class ActiveWindowOutOfRangeException(val seconds: Int) : RuntimeException(
    "$seconds is not a number of seconds a session can be counted active for. " +
        "Choose between $MIN_ACTIVE_WINDOW_SECONDS and $MAX_ACTIVE_WINDOW_SECONDS.",
), Refusal {

    override val arguments get() = mapOf("seconds" to seconds)
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
@EnableConfigurationProperties(
    AttachmentProperties::class,
    ChatProperties::class,
    MetricsProperties::class,
    SessionProperties::class,
)
class AttachmentConfig
