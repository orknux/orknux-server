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
        agentMaxSubagentsAtOnce = settings.agentMaxSubagentsAtOnce(),
        agentMaxSubagentsAtOnceConfigured = settings.agentMaxSubagentsAtOnceConfigured(),
        workflowStepsAtOnce = settings.workflowStepsAtOnce(),
        workflowStepsAtOnceConfigured = settings.workflowStepsAtOnceConfigured(),
        maxRepeatedToolCalls = settings.maxRepeatedToolCalls(),
        maxRepeatedToolCallsConfigured = settings.maxRepeatedToolCallsConfigured(),
        repeatedToolCallsWindowSeconds = settings.repeatedToolCallsWindowSeconds(),
        repeatedToolCallsWindowSecondsConfigured = settings.repeatedToolCallsWindowSecondsConfigured(),
        repeatedToolCallWarnings = settings.repeatedToolCallWarnings(),
        repeatedToolCallWarningsConfigured = settings.repeatedToolCallWarningsConfigured(),
        maxToolCallsAtOnce = settings.maxToolCallsAtOnce(),
        maxToolCallsAtOnceConfigured = settings.maxToolCallsAtOnceConfigured(),
        longestStoredValue = settings.longestStoredValue(),
        longestStoredValueConfigured = settings.longestStoredValueConfigured(),
        drawingScale = settings.drawingScale(),
        drawingScaleConfigured = settings.drawingScaleConfigured(),
        sessionCompactAfterTokens = settings.sessionCompactAfterTokens(),
        sessionCompactAfterTokensConfigured = settings.sessionCompactAfterTokensConfigured(),
        sessionCompactionKeepTurns = settings.sessionCompactionKeepTurns(),
        sessionCompactionKeepTurnsConfigured = settings.sessionCompactionKeepTurnsConfigured(),
        sessionCompactionSummaryTokens = settings.sessionCompactionSummaryTokens(),
        sessionCompactionSummaryTokensConfigured = settings.sessionCompactionSummaryTokensConfigured(),
        sessionCompactionAttempts = settings.sessionCompactionAttempts(),
        sessionCompactionAttemptsConfigured = settings.sessionCompactionAttemptsConfigured(),
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
        workspaceCopyLockWaitSeconds = settings.workspaceCopyLockWaitSeconds(),
        workspaceCopyLockWaitSecondsConfigured = settings.workspaceCopyLockWaitSecondsConfigured(),
        releasesKept = settings.releasesKept(),
        releasesKeptConfigured = settings.releasesKeptConfigured(),
        releaseBootAttempts = settings.releaseBootAttempts(),
        releaseBootAttemptsConfigured = settings.releaseBootAttemptsConfigured(),
        releaseFollowSeconds = settings.releaseFollowSeconds(),
        releaseFollowSecondsConfigured = settings.releaseFollowSecondsConfigured(),
        releaseMaxMb = settings.releaseMaxMb(),
        releaseMaxMbConfigured = settings.releaseMaxMbConfigured(),
        releaseRestartDelaySeconds = settings.releaseRestartDelaySeconds(),
        releaseRestartDelaySecondsConfigured = settings.releaseRestartDelaySecondsConfigured(),
        releaseDownloadSeconds = settings.releaseDownloadSeconds(),
        releaseDownloadSecondsConfigured = settings.releaseDownloadSecondsConfigured(),
    )

    /** How many server jars are kept for rolling back to. Issue #584. */
    @MutationMapping
    fun setReleasesKept(@Argument count: Int): InstallationSettingsView {
        access.requireAdmin()
        settings.setReleasesKept(count, currentUser())
        auditRecorder.record(null, WorkspaceAuditCategory.WORKSPACE, "The last $count server releases are kept")
        return installationSettings()
    }

    /** How many starts a newly activated server release gets before it is given up on. Issue #584. */
    @MutationMapping
    fun setReleaseBootAttempts(@Argument count: Int): InstallationSettingsView {
        access.requireAdmin()
        settings.setReleaseBootAttempts(count, currentUser())
        auditRecorder.record(null, WorkspaceAuditCategory.WORKSPACE, "A server release gets $count starts before it is given up on")
        return installationSettings()
    }

    /** How often every server checks it runs the chosen release. Issue #584. */
    @MutationMapping
    fun setReleaseFollowSeconds(@Argument seconds: Int): InstallationSettingsView {
        access.requireAdmin()
        settings.setReleaseFollowSeconds(seconds, currentUser())
        auditRecorder.record(null, WorkspaceAuditCategory.WORKSPACE, "Servers check for a new release every $seconds seconds")
        return installationSettings()
    }

    /** The largest server jar taken. Issue #584. */
    @MutationMapping
    fun setReleaseMaxMb(@Argument mb: Int): InstallationSettingsView {
        access.requireAdmin()
        settings.setReleaseMaxMb(mb, currentUser())
        auditRecorder.record(null, WorkspaceAuditCategory.WORKSPACE, "Server jars capped at $mb MB")
        return installationSettings()
    }

    /** How long a server waits after an update before restarting. Issue #584. */
    @MutationMapping
    fun setReleaseRestartDelaySeconds(@Argument seconds: Int): InstallationSettingsView {
        access.requireAdmin()
        settings.setReleaseRestartDelaySeconds(seconds, currentUser())
        auditRecorder.record(null, WorkspaceAuditCategory.WORKSPACE, "A server restarts $seconds seconds after an update")
        return installationSettings()
    }

    /** How long a server jar fetched from a URL may take. Issue #589. */
    @MutationMapping
    fun setReleaseDownloadSeconds(@Argument seconds: Int): InstallationSettingsView {
        access.requireAdmin()
        settings.setReleaseDownloadSeconds(seconds, currentUser())
        auditRecorder.record(null, WorkspaceAuditCategory.WORKSPACE, "A server jar download may take $seconds seconds")
        return installationSettings()
    }

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
            "tool_find names up to $count findable tools outright",
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

    /**
     * How long a step of a workspace copy may wait for a lock. Issue #581: a
     * copy on Postgres waited for ever, and an installation whose traffic holds
     * rows longer than a minute says so here.
     */
    @MutationMapping
    fun setWorkspaceCopyLockWaitSeconds(@Argument seconds: Int): InstallationSettingsView {
        access.requireAdmin()

        settings.setWorkspaceCopyLockWaitSeconds(seconds, currentUser())
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            "A workspace copy waits up to $seconds seconds for a lock",
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

    /**
     * How many steps of one workflow run may be running at once. Issue #285.
     *
     * Read when a run is planned, so a run already going keeps the number it
     * started with.
     */
    @MutationMapping
    fun setWorkflowStepsAtOnce(@Argument count: Int): InstallationSettingsView {
        access.requireAdmin()

        settings.setWorkflowStepsAtOnce(count, currentUser())
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            "Workflow runs allowed $count steps running at once",
        )
        return installationSettings()
    }

    /**
     * How many asks may be working at once. Issue #461.
     *
     * An ask past the ceiling waits its turn rather than being refused: a
     * refusal sends the model round again with the same ask in other words,
     * and the wait is invisible to it anyway now that asking does not block.
     */
    @MutationMapping
    fun setAgentMaxSubagentsAtOnce(@Argument count: Int): InstallationSettingsView {
        access.requireAdmin()

        settings.setAgentMaxSubagentsAtOnce(count, currentUser())
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            "Agents allowed $count asks working at once",
        )
        return installationSettings()
    }

    /**
     * The loop guard. Issue #516.
     *
     * Three numbers rather than one, because repetition on its own is not the
     * fault: an agent watching something calls the same tool with the same
     * arguments and is working. What makes it a loop is how close together the
     * calls are, and what makes it worth ending is the model going on after
     * being told.
     */
    @MutationMapping
    fun setMaxRepeatedToolCalls(@Argument count: Int): InstallationSettingsView {
        access.requireAdmin()
        settings.setMaxRepeatedToolCalls(count, currentUser())
        auditRecorder.record(null, WorkspaceAuditCategory.WORKSPACE, "Identical tool calls allowed: $count")
        return installationSettings()
    }

    @MutationMapping
    fun setSessionCompactAfterTokens(@Argument tokens: Int): InstallationSettingsView {
        access.requireAdmin()
        settings.setSessionCompactAfterTokens(tokens, currentUser())
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            if (tokens == 0) "Sessions are no longer compacted" else "A session is compacted past $tokens tokens",
        )
        return installationSettings()
    }

    @MutationMapping
    fun setSessionCompactionKeepTurns(@Argument turns: Int): InstallationSettingsView {
        access.requireAdmin()
        settings.setSessionCompactionKeepTurns(turns, currentUser())
        auditRecorder.record(null, WorkspaceAuditCategory.WORKSPACE, "A compacted turn keeps $turns steps")
        return installationSettings()
    }

    @MutationMapping
    fun setSessionCompactionSummaryTokens(@Argument tokens: Int): InstallationSettingsView {
        access.requireAdmin()
        settings.setSessionCompactionSummaryTokens(tokens, currentUser())
        auditRecorder.record(null, WorkspaceAuditCategory.WORKSPACE, "A turn's summary may run to $tokens tokens")
        return installationSettings()
    }

    @MutationMapping
    fun setSessionCompactionAttempts(@Argument times: Int): InstallationSettingsView {
        access.requireAdmin()
        settings.setSessionCompactionAttempts(times, currentUser())
        auditRecorder.record(null, WorkspaceAuditCategory.WORKSPACE, "A turn may be compacted $times times")
        return installationSettings()
    }

    @MutationMapping
    fun setDrawingScale(@Argument times: Int): InstallationSettingsView {
        access.requireAdmin()
        settings.setDrawingScale(times, currentUser())
        auditRecorder.record(null, WorkspaceAuditCategory.WORKSPACE, "Diagrams and charts are drawn at ${times}x")
        return installationSettings()
    }

    @MutationMapping
    fun setLongestStoredValue(@Argument characters: Int): InstallationSettingsView {
        access.requireAdmin()
        settings.setLongestStoredValue(characters, currentUser())
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            "A stored value is kept to $characters characters",
        )
        return installationSettings()
    }

    @MutationMapping
    fun setMaxToolCallsAtOnce(@Argument count: Int): InstallationSettingsView {
        access.requireAdmin()
        settings.setMaxToolCallsAtOnce(count, currentUser())
        auditRecorder.record(null, WorkspaceAuditCategory.WORKSPACE, "Tool calls allowed in one message: $count")
        return installationSettings()
    }

    @MutationMapping
    fun setRepeatedToolCallsWindowSeconds(@Argument seconds: Int): InstallationSettingsView {
        access.requireAdmin()
        settings.setRepeatedToolCallsWindowSeconds(seconds, currentUser())
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            "Identical tool calls counted within $seconds seconds",
        )
        return installationSettings()
    }

    @MutationMapping
    fun setRepeatedToolCallWarnings(@Argument count: Int): InstallationSettingsView {
        access.requireAdmin()
        settings.setRepeatedToolCallWarnings(count, currentUser())
        auditRecorder.record(
            null,
            WorkspaceAuditCategory.WORKSPACE,
            "A repeating turn is told $count times before it ends",
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
    /**
     * How many of those may be working at once. Issue #461.
     *
     * A different number from the one above, and one that only started meaning
     * anything when asks stopped blocking: before that they ran one at a time
     * whatever this said.
     */
    val agentMaxSubagentsAtOnce: Int,
    val agentMaxSubagentsAtOnceConfigured: Int,
    /** How many steps of one workflow run may be running at once. Issue #285. */
    val workflowStepsAtOnce: Int,
    val workflowStepsAtOnceConfigured: Int,
    /**
     * The loop guard. Issue #516: how many identical calls, how close together
     * they have to be to count, and how often a turn is told before it ends.
     */
    val maxRepeatedToolCalls: Int,
    val maxRepeatedToolCallsConfigured: Int,
    val repeatedToolCallsWindowSeconds: Int,
    val repeatedToolCallsWindowSecondsConfigured: Int,
    val repeatedToolCallWarnings: Int,
    val repeatedToolCallWarningsConfigured: Int,
    /** How many tool calls one message may ask for at once. Issue #518. */
    val maxToolCallsAtOnce: Int,
    val maxToolCallsAtOnceConfigured: Int,
    /** How long one stored value may be before the transcript cuts it. Issue #519. */
    val longestStoredValue: Int,
    val longestStoredValueConfigured: Int,
    /** How many times its own size a drawn picture is made. Issue #529. */
    val drawingScale: Int,
    val drawingScaleConfigured: Int,
    /** How long a session's log may grow before it is compacted. Issue #523. */
    val sessionCompactAfterTokens: Int,
    val sessionCompactAfterTokensConfigured: Int,
    /** Compacting a turn that has outgrown its model. Issue #522. */
    val sessionCompactionKeepTurns: Int,
    val sessionCompactionKeepTurnsConfigured: Int,
    val sessionCompactionSummaryTokens: Int,
    val sessionCompactionSummaryTokensConfigured: Int,
    val sessionCompactionAttempts: Int,
    val sessionCompactionAttemptsConfigured: Int,
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
    /** How long a step of a workspace copy may wait for a lock, in seconds. Issue #581. */
    val workspaceCopyLockWaitSeconds: Int,
    /** What a fresh installation waits: the built-in default. */
    val workspaceCopyLockWaitSecondsConfigured: Int,
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
    /** Server updates, #584: how many jars are kept for rolling back to. */
    val releasesKept: Int,
    val releasesKeptConfigured: Int,
    /** How many starts a newly activated release gets before it is given up on. */
    val releaseBootAttempts: Int,
    val releaseBootAttemptsConfigured: Int,
    /** How often every server checks it runs the chosen release, in seconds. */
    val releaseFollowSeconds: Int,
    val releaseFollowSecondsConfigured: Int,
    /** The largest server jar taken, uploaded or downloaded, in megabytes. */
    val releaseMaxMb: Int,
    val releaseMaxMbConfigured: Int,
    /** How long a server waits after an update before restarting, in seconds. */
    val releaseRestartDelaySeconds: Int,
    val releaseRestartDelaySecondsConfigured: Int,
    /** How long a server jar fetched from a URL may take, in seconds. */
    val releaseDownloadSeconds: Int,
    val releaseDownloadSecondsConfigured: Int,
)
