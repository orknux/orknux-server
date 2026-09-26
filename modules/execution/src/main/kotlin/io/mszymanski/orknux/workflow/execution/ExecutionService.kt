package io.mszymanski.orknux.workflow.execution

import org.springframework.data.domain.Page
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Starting runs and reading what they did.
 *
 * There is no per-workspace access check here, unlike orknux-server: the caller is a
 * service that has already decided a person may do this. The service token is
 * the whole of the authorization.
 */
@Service
class ExecutionService(
    private val engine: ExecutionEngine,
    private val executions: WorkflowExecutionRepository,
    private val steps: ExecutionStepRepository,
    private val logs: ExecutionLogRepository,
    /** The steps in flight in this process, so a stop can cut one short. Issue #440. */
    private val interrupts: StepInterrupts,
) {

    /**
     * The columns the runs list can be put in the order of. Issue #358.
     *
     * Duration is not one: it is the distance between two of these columns, and
     * a run that has not finished has no second one - so the list would have to
     * order by a value half its rows do not have. It stays a heading.
     *
     * The id breaks every tie, because runs of one workflow started in the same
     * second are common and a page that shuffles between reads is a page nobody
     * trusts.
     */
    private val orders = mapOf(
        "STARTED" to listOf("startedAt", "id"),
        "WORKFLOW" to listOf("workflowName", "startedAt"),
        "STATUS" to listOf("status", "startedAt"),
        "TRIGGER" to listOf("trigger", "startedAt"),
        "RUN" to listOf("id"),
    )

    fun executions(
        workspaceId: Long?,
        workflowId: Long?,
        status: ExecutionStatus?,
        days: Int?,
        search: String?,
        page: Int?,
        size: Int?,
        order: String? = null,
        ascending: Boolean? = null,
    ): ExecutionPage {
        val asked = order?.trim()?.uppercase()?.takeIf { it in orders }
        val fields = orders[asked] ?: orders.getValue("STARTED")
        // Newest first where nobody has said otherwise, which is what a list of
        // runs has always been read as.
        val up = ascending ?: false
        val pageable = pageRequest(
            page,
            size,
            Sort.by(if (up) Sort.Direction.ASC else Sort.Direction.DESC, *fields.toTypedArray()),
        )
        val since = days?.takeIf { it > 0 }?.let { OffsetDateTime.now().minusDays(it.toLong()) }
        val filter = executionFilter(workspaceId, workflowId, status, since, search?.trim()?.ifEmpty { null })
        return ExecutionPage(executions.findAll(filter, pageable))
    }

    /**
     * Every workflow this workspace has a run of, by name.
     *
     * Read off the runs rather than off any list of workflows, which is the
     * whole point of it: a run outlives the workflow's place in a workspace, so
     * the workflows named here are not the workflows a workspace currently
     * lists. Whoever asks is the one who can tell the difference.
     *
     * A workflow that has been renamed is one entry under the name its most
     * recent run recorded, not one entry per name it has worn.
     */
    fun workflowsRun(workspaceId: Long): List<RunWorkflowView> =
        executions.workflowsRun(workspaceId)
            .groupBy { it.workflowId }
            .map { (workflowId, named) ->
                RunWorkflowView(workflowId, named.maxBy { it.lastRunAt }.workflowName)
            }
            .sortedBy { it.workflowName.lowercase() }

    /** Where a workflow last got to, or null if it has never run for this workspace. */
    fun lastExecution(workspaceId: Long, workflowId: Long): ExecutionView? =
        executions.findFirstByWorkspaceIdAndWorkflowIdOrderByStartedAtDesc(workspaceId, workflowId)?.let(::ExecutionView)

    fun execution(id: Long): ExecutionDetailView? {
        val execution = executions.findByIdOrNull(id) ?: return null
        return detailOf(execution)
    }

    /**
     * The runs that wrote into one session, newest first. Issue #420.
     *
     * A session's page reads this the other way round from how it is written: a
     * step records which session its agent talked into, and this finds the runs
     * whose steps name it. Usually one; several where more than one run computed
     * the same session key. Empty for a session nothing wrote into, such as a
     * chat - so the page can draw nothing rather than an empty control.
     */
    fun executionsForSession(sessionId: Long): List<SessionExecutionLink> {
        val ids = steps.executionIdsForSession(sessionId)
        if (ids.isEmpty()) return emptyList()
        return executions.findAllById(ids)
            .sortedByDescending { it.startedAt }
            .map(::SessionExecutionLink)
    }

    /**
     * Asks a running execution to stop. Issue #395.
     *
     * The flag, not the ending: the engine reads it before its next step and
     * ends the run itself, so this returns the run as it stands - RUNNING still,
     * until the engine notices - and a caller that polls sees it become STOPPED.
     * A run that has already ended is left as it is; there is nothing to stop.
     *
     * A step in the middle of its work is cut short as well (#440): the flag
     * alone is read only between steps and during a wait, and a step blocked
     * on a model for two minutes would otherwise go on for the two minutes.
     * Asked whether or not the flag was already set, so a second press reaches
     * a step the first one did not find running yet.
     */
    @org.springframework.transaction.annotation.Transactional
    fun requestStop(id: Long): ExecutionDetailView? {
        val execution = executions.findByIdOrNull(id) ?: return null
        if (execution.status == ExecutionStatus.RUNNING) {
            if (!execution.stopRequested) {
                execution.stopRequested = true
                executions.save(execution)
            }
            interrupts.stop(id)
        }
        return detailOf(execution)
    }

    /**
     * Starts the workflow and answers with the run as it stands.
     *
     * What that means depends on the engine: the inline one has finished by the
     * time this returns, while Temporal answers as soon as the run is accepted,
     * with every step still pending. Either way the id is real and `execution`
     * follows it from there, so a caller that polls works against both.
     */
    fun startExecution(input: StartExecutionInput): ExecutionDetailView =
        detailOf(
            engine.start(
                workspaceId = input.workspaceId,
                workflowId = input.workflowId,
                trigger = input.trigger,
                input = input.payload,
                version = input.version,
                resumeFrom = input.resumeFrom,
                startedFrom = input.startedFrom,
                firedTriggerId = input.firedTriggerId,
            ),
        )

    private fun detailOf(execution: WorkflowExecution): ExecutionDetailView {
        val id = requireNotNull(execution.id)
        return ExecutionDetailView(
            execution = execution,
            steps = steps.findByExecutionIdOrderByOrderAsc(id),
            logs = logs.findByExecutionIdOrderBySequenceAsc(id),
        )
    }
}

/** What to run, and what to hand the first node. */
data class StartExecutionInput(
    val workspaceId: Long,
    val workflowId: Long,
    val trigger: ExecutionTrigger = ExecutionTrigger.API,
    /** Named `payload` in Kotlin because `input` is the argument holding it. */
    val payload: String? = null,
    /**
     * Which copy of the workflow to run, where what started it does not decide.
     *
     * Re-running is the case. A rerun is recorded as manual - a person pressed
     * it, and the run list should not claim Slack sent the message twice - but
     * that would then run the draft, so re-running a webhook's run would run a
     * graph that webhook never touched. Null keeps the rule: manual means the
     * draft, everything else means what was published.
     */
    val version: GraphVersion? = null,
    /**
     * Where to pick up an earlier run rather than start at the beginning.
     *
     * A run that failed at the last node of six should not have to redo the
     * five that worked - for anything that sends or charges, redoing them is
     * not a repeat but a second occurrence. Null for an ordinary run, which is
     * every run that is not somebody asking for one step again.
     */
    val resumeFrom: ResumePoint? = null,
    /**
     * The run this one is a re-run of, so the new run can point back at it.
     *
     * It belongs here rather than being read off [resumeFrom], because only one
     * of the two ways of running something again has a resume point: repeating
     * a whole run starts at the beginning and still came from somewhere. A
     * resume point does imply it, so giving both is allowed and giving only the
     * resume point still records the link.
     */
    val startedFrom: Long? = null,
    /**
     * Which trigger definition fired, where one did.
     *
     * A workflow may be drawn with two triggers, and the run belongs to one of
     * them: without this the engine cannot tell which half of the graph it is,
     * so it runs both. Null where nothing fired — a person pressed Run, an API
     * asked for the workflow itself, or a run is being repeated, which repeats
     * a graph rather than an event — and a run with none behaves as every run
     * did before triggers were told apart: every trigger node is a beginning.
     */
    val firedTriggerId: Long? = null,
)

data class ExecutionView(
    val id: Long,
    val workspaceId: Long,
    val workflowId: Long,
    val workflowName: String,
    val status: ExecutionStatus,
    val trigger: ExecutionTrigger,
    val startedAt: String,
    val finishedAt: String?,
    /** Null while the run is still going. */
    val durationSeconds: Int?,
    val error: String?,
    /** Set when a condition ended the run early; null for an ordinary finish. */
    val stoppedReason: String? = null,
) {
    constructor(execution: WorkflowExecution) : this(
        id = requireNotNull(execution.id),
        workspaceId = execution.workspaceId,
        workflowId = execution.workflowId,
        workflowName = execution.workflowName,
        status = execution.status,
        trigger = execution.trigger,
        startedAt = execution.startedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        finishedAt = execution.finishedAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        durationSeconds = execution.finishedAt?.let { seconds(execution.startedAt, it) },
        error = execution.error,
        stoppedReason = execution.stoppedReason,
    )
}

/**
 * A run that wrote into a session, as the session's page links to it. Issue #420.
 *
 * Small on purpose: enough to tell one run from another where several wrote
 * into the same session - the workflow's name, when it started, and where it
 * got to - and the id to open it. Not the whole [ExecutionView], which carries
 * more than a link needs.
 */
data class SessionExecutionLink(
    val id: Long,
    val workflowName: String,
    val startedAt: String,
    val status: ExecutionStatus,
) {
    constructor(execution: WorkflowExecution) : this(
        id = requireNotNull(execution.id),
        workflowName = execution.workflowName,
        startedAt = execution.startedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        status = execution.status,
    )
}

/**
 * A workflow this workspace has run, under the name its latest run recorded.
 *
 * Deliberately only the two: this is what a run says about the workflow it ran,
 * and a run says nothing else about it.
 */
data class RunWorkflowView(
    val workflowId: Long,
    val workflowName: String,
)

data class ExecutionPage(
    val content: List<ExecutionView>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    constructor(page: Page<WorkflowExecution>) : this(
        content = page.content.map(::ExecutionView),
        page = page.number,
        size = page.size,
        totalElements = page.totalElements.toInt(),
        totalPages = page.totalPages,
    )
}

data class ExecutionStepView(
    val key: String,
    val kind: NodeKind,
    val name: String,
    val description: String?,
    val status: StepStatus,
    val startedAt: String?,
    val finishedAt: String?,
    val durationSeconds: Int?,
    val input: String?,
    val output: String?,
    val error: String?,
    /**
     * Which catalogue entry the step ran, so a run can link back to the action
     * or the condition it was built from.
     */
    val actionId: Long?,
    val conditionId: Long?,
    /** The agent an agent step ran, for the same link back. */
    val agentId: Long?,
    /** The LLM session this step talked into, so the run links to it; null for a step that kept none. Issue #387. */
    val sessionId: Long?,
    /** Which way out of a condition this step sent the run; null for the rest. */
    val branch: EdgeBranch?,
    /**
     * How many attempts the step spent. One for almost everything; more only
     * where the node was given a retry policy and needed it.
     */
    val attempts: Int,
    /**
     * Copied from an earlier run rather than performed by this one, which is
     * what every step ahead of the one a re-run started at looks like.
     */
    val carriedOver: Boolean,
    val x: Double,
    val y: Double,
) {
    constructor(step: ExecutionStep) : this(
        key = step.nodeKey,
        kind = step.kind,
        name = step.name,
        description = step.description,
        status = step.status,
        startedAt = step.startedAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        finishedAt = step.finishedAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        durationSeconds = step.startedAt?.let { started -> step.finishedAt?.let { seconds(started, it) } },
        input = step.input,
        output = step.output,
        error = step.error,
        actionId = step.actionId,
        conditionId = step.conditionId,
        agentId = step.agentId,
        sessionId = step.sessionId,
        branch = step.branch,
        attempts = step.attempts,
        carriedOver = step.carriedOver,
        x = step.x,
        y = step.y,
    )
}

data class ExecutionLogLineView(
    val id: Long,
    val nodeKey: String?,
    val at: String,
    val level: LogLevel,
    val message: String,
) {
    constructor(line: ExecutionLog) : this(
        id = requireNotNull(line.id),
        nodeKey = line.nodeKey,
        at = line.loggedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        level = line.level,
        message = line.message,
    )
}

data class ExecutionDetailView(
    val id: Long,
    val workspaceId: Long,
    val workflowId: Long,
    val workflowName: String,
    val status: ExecutionStatus,
    val trigger: ExecutionTrigger,
    val startedAt: String,
    val finishedAt: String?,
    val durationSeconds: Int?,
    val error: String?,
    /** What the run was started on, so it can be run again on the same thing. */
    val input: String? = null,
    /** The run this one was started from; null for a run nobody re-ran. */
    val startedFrom: Long? = null,
    /** The node that ended the run early, and what it said; null for a full run. */
    val stoppedAtNodeKey: String? = null,
    val stoppedReason: String? = null,
    val steps: List<ExecutionStepView>,
    val logs: List<ExecutionLogLineView>,
) {
    constructor(execution: WorkflowExecution, steps: List<ExecutionStep>, logs: List<ExecutionLog>) : this(
        id = requireNotNull(execution.id),
        workspaceId = execution.workspaceId,
        workflowId = execution.workflowId,
        workflowName = execution.workflowName,
        status = execution.status,
        trigger = execution.trigger,
        startedAt = execution.startedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        finishedAt = execution.finishedAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        durationSeconds = execution.finishedAt?.let { seconds(execution.startedAt, it) },
        error = execution.error,
        input = execution.input,
        startedFrom = execution.startedFrom,
        stoppedAtNodeKey = execution.stoppedAtNodeKey,
        stoppedReason = execution.stoppedReason,
        steps = steps.map(::ExecutionStepView),
        logs = logs.map(::ExecutionLogLineView),
    )
}

private fun seconds(from: OffsetDateTime, to: OffsetDateTime): Int =
    Duration.between(from, to).seconds.toInt()
