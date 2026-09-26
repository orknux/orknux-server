package io.mszymanski.orknux.server.attachment

import io.mszymanski.orknux.server.chat.ChatDisabledException
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller

/**
 * The switches that belong to the installation rather than to a workspace.
 *
 * Read by anyone signed in — the chat has to know whether to offer a paperclip —
 * and changed by administrators only. What the configuration file forbids is not
 * offered as a switch at all, which is why [InstallationSettingsView] says
 * whether the screen may ask.
 */
@Controller
class InstallationSettingsAPI(
    private val settings: InstallationSettings,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    @QueryMapping
    fun installationSettings(): InstallationSettingsView = InstallationSettingsView(
        attachmentsEnabled = settings.attachmentsEnabled(),
        attachmentsConfigurable = settings.attachmentsConfigurable(),
        attachmentStorage = settings.storage().name,
        // The operator's, and read-only here: a filesystem path is not
        // something to hand a browser the ability to change.
        attachmentLocation = settings.location(),
        attachmentMaxFileSizeMb = settings.maxFileSizeMb().toInt(),
        chatEnabled = settings.chatEnabled(),
        chatConfigurable = settings.chatConfigurable(),
        metricsAnonymous = settings.metricsAnonymous(),
        metricsAnonymousConfigured = settings.metricsAnonymousConfigured(),
        revisionRetentionDays = settings.revisionRetentionDays(),
        revisionRetentionDaysConfigured = settings.revisionRetentionDaysConfigured(),
        executionRetentionDays = settings.executionRetentionDays(),
        executionRetentionDaysConfigured = settings.executionRetentionDaysConfigured(),
        taskSweepMinutes = settings.taskSweepMinutes(),
        taskSweepMinutesConfigured = settings.taskSweepMinutesConfigured(),
        taskSweepConfigurable = settings.taskSweepConfigurable(),
        pluginMaxSourceKb = settings.pluginMaxSourceKb(),
        pluginMaxSourceKbConfigured = settings.pluginMaxSourceKbConfigured(),
        pluginTimeoutSeconds = settings.pluginTimeoutSeconds(),
        pluginTimeoutSecondsConfigured = settings.pluginTimeoutSecondsConfigured(),
        chatMaxRounds = settings.chatMaxRounds(),
        chatMaxRoundsConfigured = settings.chatMaxRoundsConfigured(),
        agentSleepSeconds = settings.agentSleepSeconds(),
        agentSleepSecondsConfigured = settings.agentSleepSecondsConfigured(),
        agentSleepTimes = settings.agentSleepTimes(),
        agentSleepTimesConfigured = settings.agentSleepTimesConfigured(),
        agentMaxSubagents = settings.agentMaxSubagents(),
        agentMaxSubagentsConfigured = settings.agentMaxSubagentsConfigured(),
        scratchpadBudgetBytes = settings.scratchpadBudgetBytes(),
        scratchpadBudgetBytesConfigured = settings.scratchpadBudgetBytesConfigured(),
        commandMarker = settings.commandMarker(),
        commandMarkerConfigured = settings.commandMarkerConfigured(),
        toolsNamedInSearch = settings.toolsNamedInSearch(),
        toolsNamedInSearchConfigured = settings.toolsNamedInSearchConfigured(),
        scratchpadFileBudgetBytes = settings.scratchpadFileBudgetBytes(),
        scratchpadKeepDays = settings.scratchpadKeepDays(),
        scratchpadKeepDaysConfigured = settings.scratchpadKeepDaysConfigured(),
        scratchpadFileBudgetBytesConfigured = settings.scratchpadFileBudgetBytesConfigured(),
        toolSummariesFullUpTo = settings.toolSummariesFullUpTo(),
        toolSummariesFullUpToConfigured = settings.toolSummariesFullUpToConfigured(),
        toolSummaryTrimPercent = settings.toolSummaryTrimPercent(),
        toolSummaryTrimPercentConfigured = settings.toolSummaryTrimPercentConfigured(),
        sessionsRemovable = settings.sessionsRemovable(),
        sessionsActiveWindowSeconds = settings.sessionsActiveWindowSeconds(),
        sessionsActiveWindowSecondsConfigured = settings.sessionsActiveWindowSecondsConfigured(),
    )

    /** How long a scratchpad nobody touches is kept; zero keeps them for ever. Issue #492. */
    @MutationMapping
    fun setScratchpadKeepDays(@Argument days: Int): InstallationSettingsView {
        access.requireAdmin()
        settings.setScratchpadKeepDays(days, currentUser())
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            if (days == 0) "Scratchpads are kept for ever" else "Scratchpads are kept for $days days",
        )
        return installationSettings()
    }

    /** What the files in one session's scratchpads may come to. Issue #491. */
    @MutationMapping
    fun setScratchpadFileBudgetBytes(@Argument bytes: Long): InstallationSettingsView {
        access.requireAdmin()
        settings.setScratchpadFileBudgetBytes(bytes, currentUser())
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            "A session's scratchpad files may come to ${bytes / (1024 * 1024)} MB",
        )
        return installationSettings()
    }

    /**
     * How many tools fit in a briefing before their lines are cut, and by how
     * much each further block of that many cuts them. Issue #481.
     */
    @MutationMapping
    fun setToolSummariesFullUpTo(@Argument count: Int): InstallationSettingsView {
        access.requireAdmin()
        settings.setToolSummariesFullUpTo(count, currentUser())
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            "A tool's line in a briefing is kept whole up to $count tools",
        )
        return installationSettings()
    }

    @MutationMapping
    fun setToolSummaryTrimPercent(@Argument percent: Int): InstallationSettingsView {
        access.requireAdmin()
        settings.setToolSummaryTrimPercent(percent, currentUser())
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            "Each further block of tools takes $percent% off a tool's line",
        )
        return installationSettings()
    }

    /**
     * Up to how many findable tools `find_tools` names outright. Issue #442.
     */
    @MutationMapping
    fun setToolsNamedInSearch(@Argument count: Int): InstallationSettingsView {
        access.requireAdmin()

        settings.setToolsNamedInSearch(count, currentUser())
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            "find_tools names up to $count findable tools outright",
        )
        return installationSettings()
    }

    /**
     * How long a session counts as active after its last line. Issue #448.
     *
     * The recency half of the sessions list's dot, which was a minute in the
     * source. An installation whose models think for longer than that between
     * two lines watched its sessions blink off mid-turn; this is where it says
     * how long its agents go quiet for.
     */
    @MutationMapping
    fun setSessionsActiveWindowSeconds(@Argument seconds: Int): InstallationSettingsView {
        access.requireAdmin()

        settings.setSessionsActiveWindowSeconds(seconds, currentUser())
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            "A session counts as active for $seconds seconds after its last line",
        )
        return installationSettings()
    }

    @MutationMapping
    fun setChatEnabled(@Argument enabled: Boolean): InstallationSettingsView {
        access.requireAdmin()
        if (!settings.chatConfigurable()) throw ChatDisabledException()

        settings.setChatEnabled(enabled, currentUser())
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            if (enabled) "Chat turned on" else "Chat turned off",
        )
        return installationSettings()
    }

    @MutationMapping
    fun setAttachmentsEnabled(@Argument enabled: Boolean): InstallationSettingsView {
        access.requireAdmin()
        if (!settings.attachmentsConfigurable()) throw AttachmentsDisabledException()

        settings.setAttachmentsEnabled(enabled, currentUser())
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            if (enabled) "Attachments turned on" else "Attachments turned off",
        )
        return installationSettings()
    }

    /**
     * Opens the metrics to anybody who can reach the port, or closes them again.
     *
     * No `configurable` gate, unlike the two above: the file's default for this
     * one is already the closed answer, so a gate would be a way of saying no
     * twice and the switch would never be pressable. What stands in its place is
     * the audit entry — turning this on publishes counters about this
     * installation to whoever can reach it, and that is worth a line with a name
     * against it.
     */
    @MutationMapping
    fun setMetricsAnonymous(@Argument enabled: Boolean): InstallationSettingsView {
        access.requireAdmin()

        settings.setMetricsAnonymous(enabled, currentUser())
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            if (enabled) {
                "Metrics opened to callers who have not signed in"
            } else {
                "Metrics closed to callers who have not signed in"
            },
        )
        return installationSettings()
    }

    /**
     * How long a component's history is kept before the sweep takes it.
     *
     * An administrator's, because it is a decision about the disk: the rows are
     * whole copies of function source, tool source and agent prompts, so this
     * number is what decides how large that table gets.
     */
    @MutationMapping
    fun setRevisionRetentionDays(@Argument days: Int): InstallationSettingsView {
        access.requireAdmin()

        settings.setRevisionRetentionDays(days, currentUser())
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            "Component history kept for $days days",
        )
        return installationSettings()
    }

    /**
     * How long a finished run is kept before a sweep takes it.
     *
     * An administrator's for the same reason the setting above is: it decides
     * how large `workflow_execution` and its steps get, and nothing deleted a
     * run before this existed at all. Issue #167.
     *
     * A run still going is never swept, whatever this says, so the number is
     * about history rather than about anything in flight.
     */
    @MutationMapping
    fun setExecutionRetentionDays(@Argument days: Int): InstallationSettingsView {
        access.requireAdmin()

        settings.setExecutionRetentionDays(days, currentUser())
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            "Run history kept for $days days",
        )
        return installationSettings()
    }

    /**
     * How long a task may sit queued before something hands it over again.
     *
     * Gated like the chat and attachment switches — what the screen does not
     * offer, this does not store — but the gate itself is in
     * [InstallationSettings.setTaskSweepMinutes] beside the answer it asks,
     * rather than restated here where it would be a second place to remember.
     * A Temporal installation has no field for this: how a stuck task is
     * recovered there is Temporal's business and the interval comes from the
     * file, so a value arriving anyway is refused rather than kept where nobody
     * could see it.
     */
    @MutationMapping
    fun setTaskSweepMinutes(@Argument minutes: Int): InstallationSettingsView {
        access.requireAdmin()

        settings.setTaskSweepMinutes(minutes, currentUser())
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            "Queued tasks picked up again after $minutes minutes",
        )
        return installationSettings()
    }

    /**
     * How large one of a plugin's source files may be.
     *
     * An administrator's for the same reason the retentions are: each file is
     * stored whole and read whole on every call, so the number is a statement
     * about the disk and the heap at once.
     */
    /**
     * How long a plugin may take to load.
     *
     * An administrator's, like the source cap beside it: it holds a thread
     * while it runs, so the number is a statement about this server rather
     * than about one workspace's work.
     */
    @MutationMapping
    fun setPluginTimeoutSeconds(@Argument seconds: Int): InstallationSettingsView {
        access.requireAdmin()

        settings.setPluginTimeoutSeconds(seconds, currentUser())
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            "Plugins given $seconds seconds to load",
        )
        return installationSettings()
    }

    /**
     * How many rounds of tool calls an agent gets before it has to answer.
     *
     * The installation's number, which every agent follows unless it carries one
     * of its own. Here rather than only on the agent because the common case is
     * an installation whose agents all hold more tools than eight rounds allow -
     * and because somebody debugging "kept looking things up without reaching an
     * answer" should be able to raise it once rather than agent by agent.
     */
    @MutationMapping
    fun setChatMaxRounds(@Argument rounds: Int): InstallationSettingsView {
        access.requireAdmin()

        settings.setChatMaxRounds(rounds, currentUser())
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            "Agents given $rounds rounds of tool calls",
        )
        return installationSettings()
    }

    /**
     * The longest an agent may put itself to sleep for.
     *
     * An agent ending its turn with a wake-up parks the step and the run comes
     * back to it later, so this number is a statement about how long this
     * installation will hold a run open - which is an operator's decision and
     * nobody else's. A model asking for longer is given this instead and told.
     */
    @MutationMapping
    fun setAgentSleepSeconds(@Argument seconds: Int): InstallationSettingsView {
        access.requireAdmin()

        settings.setAgentSleepSeconds(seconds, currentUser())
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            "Agents allowed to wait $seconds seconds at a time",
        )
        return installationSettings()
    }

    /**
     * How many times in a row an agent may do that on one step.
     *
     * The bound that actually stops a run going round for a week: waiting is a
     * decision the model takes again every time it wakes. Zero takes the
     * wake-up off the tool altogether.
     */
    @MutationMapping
    fun setAgentSleepTimes(@Argument times: Int): InstallationSettingsView {
        access.requireAdmin()

        settings.setAgentSleepTimes(times, currentUser())
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            "Agents allowed to wait $times times in a row",
        )
        return installationSettings()
    }

    /**
     * How many other agents one agent may ask in one conversation.
     *
     * The bound on fan-out: each ask is a conversation of its own, with its own
     * model calls, started on the asking model's say-so. Zero takes the tool
     * off the table; a workspace's own number wins over this one. Issue #380.
     */
    @MutationMapping
    fun setAgentMaxSubagents(@Argument count: Int): InstallationSettingsView {
        access.requireAdmin()

        settings.setAgentMaxSubagents(count, currentUser())
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            "Agents allowed to ask $count other agents in a conversation",
        )
        return installationSettings()
    }

    @MutationMapping
    fun setScratchpadBudgetBytes(@Argument bytes: Int): InstallationSettingsView {
        access.requireAdmin()

        settings.setScratchpadBudgetBytes(bytes, currentUser())
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            "A session's scratchpads may hold $bytes bytes",
        )
        return installationSettings()
    }

    /**
     * What marks a command in a message that starts a run, installation-wide.
     * A workspace's own wins over this. Issue #402.
     */
    @MutationMapping
    fun setCommandMarker(@Argument marker: String): InstallationSettingsView {
        access.requireAdmin()

        settings.setCommandMarker(marker, currentUser())
        auditRecorder.record(null, WorkspaceAuditCategory.WORKSPACE, "Commands in a message are marked with ${marker.trim()}")
        return installationSettings()
    }

    /**
     * Whether a conversation may be thrown away.
     *
     * A session is the record of what an agent was asked and what it answered,
     * and on some installations that is the only account of a decision anybody
     * has. Removing one is a person tidying up - which is what it is for - but
     * on an installation that has to be able to say what happened it is a hole
     * somebody can put in the record with one press and no way back.
     */
    @MutationMapping
    fun setSessionsRemovable(@Argument removable: Boolean): InstallationSettingsView {
        access.requireAdmin()

        settings.setSessionsRemovable(removable, currentUser())
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            if (removable) "Conversations may be removed" else "Conversations may no longer be removed",
        )
        return installationSettings()
    }

    @MutationMapping
    fun setPluginMaxSourceKb(@Argument kb: Int): InstallationSettingsView {
        access.requireAdmin()

        settings.setPluginMaxSourceKb(kb, currentUser())
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            "Plugin source files capped at $kb KB",
        )
        return installationSettings()
    }

    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"
}

data class InstallationSettingsView(
    /** Whether a chat may carry files, as the file and the screen agree it. */
    val attachmentsEnabled: Boolean,
    /** False when the configuration file has said no, and the switch is not offered. */
    val attachmentsConfigurable: Boolean,
    val attachmentStorage: String,
    /** Where they are written; the operator's setting, shown so it can be checked. */
    val attachmentLocation: String,
    val attachmentMaxFileSizeMb: Int,
    /** Whether this installation has a chat, as the file and the screen agree it. */
    val chatEnabled: Boolean,
    /** False when the configuration file has said no, and the switch is not offered. */
    val chatConfigurable: Boolean,
    /**
     * Whether `/actuator/prometheus` answers a caller who has not signed in.
     *
     * Always switchable, and always off until somebody switches it: this is the
     * one setting here the file does not put a floor under, because the file's
     * default is already the closed answer.
     */
    val metricsAnonymous: Boolean,
    /**
     * What a fresh installation would have answered - ORKNUX_METRICS_ANONYMOUS.
     *
     * Shown so the screen can say when the stored answer differs from the
     * configured one, rather than leaving an operator to wonder why the file
     * they edited appears to be ignored.
     */
    val metricsAnonymousConfigured: Boolean,
    /**
     * How many days of component history are kept.
     *
     * A component's versions are what it was before each save - the code and
     * the prompts, in full - so this is the setting that decides how much of
     * the disk they take. Fourteen days unless somebody has said otherwise.
     */
    val revisionRetentionDays: Int,
    /** What a fresh installation would keep - ORKNUX_REVISION_RETENTION_DAYS. */
    val revisionRetentionDaysConfigured: Int,
    /** How long a finished run is kept, and what a fresh installation would keep. */
    val executionRetentionDays: Int,
    val executionRetentionDaysConfigured: Int,
    /**
     * How many minutes a task may sit queued before something hands it over
     * again.
     *
     * The net under the hand-over: a task whose start was lost - to a process
     * killed at the wrong moment, or a workflow that could not run - would
     * otherwise say QUEUED for ever. Five minutes unless an administrator has
     * said otherwise.
     */
    val taskSweepMinutes: Int,
    /** What a fresh installation would wait - ORKNUX_TASK_SWEEP_MINUTES. */
    val taskSweepMinutesConfigured: Int,
    /**
     * How large one of a plugin's source files may be, in KB - the plugin
     * itself, each library it ships, and each file a URL load fetches.
     */
    val pluginMaxSourceKb: Int,
    /** How long a plugin may take to load, in seconds. */
    val pluginTimeoutSeconds: Int,
    /** What a fresh installation waits, before anybody changed it. */
    val pluginTimeoutSecondsConfigured: Int,
    /**
     * How many rounds of tool calls an agent gets before it must answer.
     *
     * One call to the model is a round: it answers, or it asks for tools and
     * what it asks for is run and handed back. An agent may carry its own
     * number; this is what the rest of them follow.
     */
    val chatMaxRounds: Int,
    /** What a fresh installation allows - ORKNUX_CHAT_MAX_ROUNDS. */
    val chatMaxRoundsConfigured: Int,
    /**
     * The longest one of an agent's own waits may be, in seconds.
     *
     * An agent may end its turn with a wake-up instead of an answer: the step
     * parks and the run comes back to that node when the time is up. A model
     * asking for longer than this is given this instead.
     */
    val agentSleepSeconds: Int,
    /** What a fresh installation allows - ORKNUX_CHAT_SLEEP_SECONDS. */
    val agentSleepSecondsConfigured: Int,
    /** How many times in a row one step's agent may wait; zero is never. */
    val agentSleepTimes: Int,
    /** What a fresh installation allows - ORKNUX_CHAT_SLEEP_TIMES. */
    val agentSleepTimesConfigured: Int,
    /** How many other agents one agent may ask in one conversation; zero is none. Issue #380. */
    val agentMaxSubagents: Int,
    /** What a fresh installation allows - ORKNUX_CHAT_MAX_SUBAGENTS. */
    val agentMaxSubagentsConfigured: Int,
    /** How many bytes one session's scratchpads may hold in all. Issue #411. */
    val scratchpadBudgetBytes: Int,
    /** What a fresh installation allows before anybody sets it. */
    val scratchpadBudgetBytesConfigured: Int,
    /** What marks a command in a message here; a workspace may carry its own. Issue #402. */
    val commandMarker: String,
    /** What a fresh installation starts on - ORKNUX_COMMAND_MARKER. */
    val commandMarkerConfigured: String,
    /** Up to how many findable tools find_tools names outright; zero never does. Issue #442. */
    val toolsNamedInSearch: Int,
    /** How many tools fit before their lines in a briefing are cut. Issue #481. */
    /** What the files in one session's scratchpads may come to, in bytes. Issue #491. */
    val scratchpadFileBudgetBytes: Long,
    /** How long a scratchpad nobody touches is kept, in days; zero is for ever. Issue #492. */
    val scratchpadKeepDays: Int,
    val scratchpadKeepDaysConfigured: Int,
    val scratchpadFileBudgetBytesConfigured: Long,
    val toolSummariesFullUpTo: Int,
    val toolSummariesFullUpToConfigured: Int,
    /** And what each further block of that many costs, in percent. Issue #481. */
    val toolSummaryTrimPercent: Int,
    val toolSummaryTrimPercentConfigured: Int,
    /** What a fresh installation names - ORKNUX_CHAT_TOOLS_NAMED_IN_SEARCH. */
    val toolsNamedInSearchConfigured: Int,
    /**
     * Whether a conversation may be thrown away.
     *
     * True on every installation until somebody turns it off, which is how this
     * has always worked - a switch that silently took an ability away on upgrade
     * would be worse than the hole it closes.
     */
    val sessionsRemovable: Boolean,
    /**
     * How long a session counts as active after its last line, in seconds.
     *
     * The recency half of the sessions list's dot; the other half, an open
     * line, counts only while a run or a task that writes into the session is
     * still going. Issue #448.
     */
    val sessionsActiveWindowSeconds: Int,
    /** What a fresh installation counts - ORKNUX_SESSIONS_ACTIVE_WINDOW_SECONDS. */
    val sessionsActiveWindowSecondsConfigured: Int,
    /** What a fresh installation allows: the built-in default. */
    val pluginMaxSourceKbConfigured: Int,
    /**
     * False on an installation running Temporal, and the field is not offered.
     *
     * A `configurable` flag like the chat's and the attachments', and the fact
     * behind it is which engine is carrying tasks. The sweep runs either way;
     * what is not an administrator's decision on Temporal is how long it waits.
     */
    val taskSweepConfigurable: Boolean,
)
