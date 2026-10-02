package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.connector.connection.ConnectionType
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.trigger.TriggerAction
import io.mszymanski.orknux.server.trigger.WorkflowTriggerRepository
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.server.monitoring.TemporalLinks
import io.mszymanski.orknux.workflow.execution.ExecutionDetailView
import io.mszymanski.orknux.workflow.execution.ExecutionLogLineView
import io.mszymanski.orknux.workflow.execution.ExecutionPage
import io.mszymanski.orknux.workflow.execution.ExecutionService
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import io.mszymanski.orknux.workflow.execution.ExecutionStepView
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.ExecutionView
import io.mszymanski.orknux.workflow.execution.GraphVersion
import io.mszymanski.orknux.workflow.execution.ResumePoint
import io.mszymanski.orknux.workflow.execution.StartExecutionInput
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

/**
 * Runs of a workspace's workflows. The execution module carries them out and keeps
 * what each one did; this decides who may look, and joins a run to the workflow
 * definition it came from, which is this module's own.
 */
@Controller
class WorkflowExecutionAPI(
    private val runs: ExecutionService,
    private val edges: WorkflowEdgeRepository,
    private val assignments: WorkspaceWorkflowRepository,
    private val workspaces: WorkspaceRepository,
    private val access: WorkspaceAccess,
    private val temporal: TemporalLinks,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val pictures: ExecutionPictureRepository,
    /** What a run said out loud, filed against the steps that said it. */
    private val speeches: ExecutionSpeechRepository,
    /** Which trigger fired a connection run, and so which event on which kind of connection. */
    private val triggers: WorkflowTriggerRepository,
    private val connections: WorkspaceConnectionRepository,
) {

    /**
     * What started each of these connection runs, by the trigger that fired it.
     *
     * Read off the trigger rather than written onto the run: the run already
     * keeps which trigger fired it, and the trigger is what knows its connection
     * and its event. A trigger that has since been removed answers nothing, and
     * the run reads as a plain connection run.
     */
    private fun sourcesOf(fired: Collection<Long>): Map<Long, ExecutionSourceView> {
        if (fired.isEmpty()) return emptyMap()
        val found = triggers.findAllById(fired.toSet())
        val types = connections.findAllById(found.mapNotNull { it.connectionId }.toSet())
            .associate { requireNotNull(it.id) to it.type }
        return found.mapNotNull { trigger ->
            val type = trigger.connectionId?.let(types::get) ?: return@mapNotNull null
            val action = trigger.action ?: return@mapNotNull null
            requireNotNull(trigger.id) to ExecutionSourceView(type, action)
        }.toMap()
    }

    /**
     * The fired trigger worth asking about. Only a run recorded as WEBHOOK, which
     * is how a connection event is stored - a value of its own would be one an
     * older jar cannot read after a rollback. Not a re-run, which is MANUAL and
     * keeps the trigger it repeats without having been started by it.
     */
    private fun connectionFired(trigger: ExecutionTrigger, firedTriggerId: Long?): Long? =
        firedTriggerId.takeIf { trigger == ExecutionTrigger.WEBHOOK }

    /** The pictures one run's image nodes drew, oldest first, as the graph shows them. */
    private fun picturesOf(executionId: Long): List<ExecutionPictureView> =
        pictures.findByExecutionIdOrderByDrawnAtAscIdAsc(executionId).map(::ExecutionPictureView)

    /** What one run said out loud, oldest first, as the graph shows them. */
    private fun speechesOf(executionId: Long): List<ExecutionSpeechView> =
        speeches.findByExecutionIdOrderBySpokenAtAscIdAsc(executionId).map(::ExecutionSpeechView)

    @QueryMapping
    fun workspaceExecutions(
        @Argument workspaceId: Long,
        @Argument page: Int?,
        @Argument size: Int?,
        @Argument status: ExecutionStatus?,
        @Argument workflowId: Long?,
        @Argument days: Int?,
        @Argument search: String?,
        @Argument order: String?,
        @Argument ascending: Boolean?,
    ): RunPage {
        requireWorkspaceAccess(workspaceId)
        val found = runs.executions(
            workspaceId = workspaceId,
            workflowId = workflowId,
            status = status,
            days = days,
            search = search?.trim()?.ifEmpty { null },
            page = page,
            size = size,
            order = order,
            ascending = ascending,
        )
        val sources = sourcesOf(found.content.mapNotNull { connectionFired(it.trigger, it.firedTriggerId) })
        return RunPage(found, assignedIn(workspaceId), sources)
    }

    /**
     * Every workflow this workspace has runs of, and whether it still lists it.
     *
     * The executions screen filters by workflow, and it used to offer only the
     * workflows the workspace assigns - so a run of a workflow that has since
     * been removed could be scrolled past but never singled out, because the
     * only control that would have singled it out did not know it existed.
     * This is read off the runs, so what the filter offers is what the list
     * actually holds.
     *
     * [ExecutionWorkflowView.assigned] is the part the screen has to say out
     * loud. Without it a removed workflow would sit in the list looking exactly
     * like a live one, which trades a filter that cannot see for a filter that
     * misleads.
     */
    @QueryMapping
    fun executionWorkflows(@Argument workspaceId: Long): List<ExecutionWorkflowView> {
        requireWorkspaceAccess(workspaceId)
        val assigned = assignedIn(workspaceId)
        return runs.workflowsRun(workspaceId).map {
            ExecutionWorkflowView(
                workflowId = it.workflowId,
                name = it.workflowName,
                assigned = it.workflowId in assigned,
            )
        }
    }

    /** What one run did: the graph as it ran, per-node outcome, and its log. */
    @QueryMapping
    fun execution(@Argument id: Long): RunDetailView? {
        val run = runs.execution(id)?.takeIf { access.canSee(it.workspaceId) } ?: return null
        return RunDetailView(
            run,
            edgesOf(run.workflowId),
            temporal.forExecution(run.id),
            assignments.existsByWorkspaceIdAndWorkflowId(run.workspaceId, run.workflowId),
            picturesOf(run.id),
            speechesOf(run.id),
        ).copy(
            source = connectionFired(run.trigger, run.firedTriggerId)?.let { sourcesOf(listOf(it))[it] },
        )
    }

    /**
     * Runs a workflow the workspace has assigned, from the workflows screen.
     *
     * [input] is what the first node is handed, as JSON. A trigger supplies it
     * from whatever fired; a person running a workflow by hand can type it, and
     * leaving it out hands the run nothing.
     */
    @MutationMapping
    fun startExecution(
        @Argument workspaceId: Long,
        @Argument workflowId: Long,
        @Argument input: String?,
    ): RunDetailView {
        requireWorkspaceAccess(workspaceId)
        val started = runs.startExecution(
            StartExecutionInput(
                workspaceId = workspaceId,
                workflowId = workflowId,
                trigger = ExecutionTrigger.MANUAL,
                payload = input?.takeIf { it.isNotBlank() },
            ),
        )
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.WORKFLOW,
            "Workflow ${started.workflowName} run started",
        )
        return startedView(started)
    }

    /**
     * Runs the workflow again on the same event, with the graph as it stands now.
     *
     * The payload is carried over deliberately. Without it a re-run started from
     * nothing: every reference to the event read blank, so a workflow that answers
     * whoever asked had nobody to answer, and the run looked broken in a way the
     * original was not. Re-running is for trying a changed graph against the
     * thing that happened, which needs the thing that happened.
     *
     * Recorded as [ExecutionTrigger.MANUAL] all the same: a person pressed this,
     * and the run list should not claim Slack sent the message twice.
     */
    @MutationMapping
    fun rerunExecution(@Argument id: Long): RunDetailView {
        val previous = runs.execution(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw ExecutionNotFoundException(id)

        val started = runs.startExecution(
            StartExecutionInput(
                workspaceId = previous.workspaceId,
                workflowId = previous.workflowId,
                trigger = ExecutionTrigger.MANUAL,
                payload = previous.input,
                /*
                 * The graph the original ran, not whatever is being edited now.
                 *
                 * The rerun is recorded as manual because a person pressed it,
                 * and manual means the draft - so without this, re-running what
                 * a webhook did would run a graph that webhook never touched.
                 */
                version = if (previous.trigger == ExecutionTrigger.MANUAL) {
                    GraphVersion.DRAFT
                } else {
                    GraphVersion.PUBLISHED
                },
                // So the new run's page can point back at the one somebody
                // pressed re-run on. Nothing else on the row would say: a
                // re-run is manual and carries the earlier input, which
                // describes every hand-started run alike.
                startedFrom = id,
            ),
        )
        return startedView(started)
    }

    /**
     * Asks a running execution to stop. Issue #395.
     *
     * The engine ends it before its next step, or during a wait; a stopped run
     * is terminal and does not resume. Answers with the run as it stands - still
     * running until the engine notices - so a page that polls sees it become
     * stopped. A run that has already ended is left as it is.
     */
    @MutationMapping
    fun stopExecution(@Argument id: Long): RunDetailView {
        val running = runs.execution(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw ExecutionNotFoundException(id)
        val stopped = runs.requestStop(id) ?: throw ExecutionNotFoundException(id)
        auditRecorder.record(running.workspaceId, WorkspaceAuditCategory.WORKFLOW, "Run #$id was asked to stop")
        return startedView(stopped)
    }

    /**
     * Runs the workflow again from one of its steps, carrying what the earlier
     * run had produced by the time it got there.
     *
     * Re-running everything is often not an option. A run that failed at the
     * last node of six had to redo the five that worked, and for a node that
     * sends a message, files a ticket or takes a payment, redoing it is not a
     * repeat but a second occurrence - so the safe thing to do with a fixed
     * node was nothing. This starts at the node and goes on to the end; the
     * steps ahead of it appear as what they were, marked as carried over.
     *
     * The refusals are the execution module's, and they are refusals rather
     * than best guesses on purpose: a step that never ran, a graph redrawn
     * since so the node is gone, an answer the earlier run did not record.
     * Starting a run that quietly reads blank where the earlier one read a
     * channel is worse than being told it cannot be done.
     */
    @MutationMapping
    fun rerunExecutionStep(@Argument id: Long, @Argument nodeKey: String): RunDetailView {
        val previous = runs.execution(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw ExecutionNotFoundException(id)

        val started = runs.startExecution(
            StartExecutionInput(
                workspaceId = previous.workspaceId,
                workflowId = previous.workflowId,
                trigger = ExecutionTrigger.MANUAL,
                payload = previous.input,
                // The graph the original ran, for the reason rerunExecution
                // gives: a person pressed this, so it is recorded as manual,
                // and manual would otherwise mean the draft.
                version = if (previous.trigger == ExecutionTrigger.MANUAL) {
                    GraphVersion.DRAFT
                } else {
                    GraphVersion.PUBLISHED
                },
                resumeFrom = ResumePoint(executionId = id, nodeKey = nodeKey),
                startedFrom = id,
            ),
        )
        return startedView(started)
    }

    /**
     * A run that has just been started, as the detail screen wants it.
     *
     * Whether the workspace lists the workflow is asked rather than assumed:
     * re-running is offered on any run this workspace can see, and one of those
     * is a run of a workflow the workspace has since removed.
     */
    private fun startedView(started: ExecutionDetailView): RunDetailView =
        RunDetailView(
            started,
            edgesOf(started.workflowId),
            temporal.forExecution(started.id),
            assignments.existsByWorkspaceIdAndWorkflowId(started.workspaceId, started.workflowId),
            picturesOf(started.id),
            speechesOf(started.id),
        )

    /**
     * A run keeps its own copy of the nodes it ran, but not of the edges between
     * them, so the shape of the graph is read from the definition here.
     */
    private fun edgesOf(workflowId: Long): List<WorkflowEdgeView> =
        edges.findByWorkflowId(workflowId).map(::WorkflowEdgeView)

    /** The workflow definitions this workspace lists, as one set for a page of rows. */
    private fun assignedIn(workspaceId: Long): Set<Long> =
        assignments.findByWorkspaceId(workspaceId).mapNotNull { it.workflow.id }.toSet()

    private fun requireWorkspaceAccess(workspaceId: Long) {
        access.requireVisible(workspaceId)
    }
}

/**
 * One picture an image node drew, as the run graph shows it.
 *
 * The bytes are served by [ExecutionPictureAPI]; this carries the link and what
 * is needed to draw and name it. [nodeKey] is which step drew it, so the graph
 * shows each picture under the node that made it. Issue #333.
 */
data class ExecutionPictureView(
    val id: Long,
    val nodeKey: String,
    val url: String,
    /** What it was drawn from, its alt text. */
    val prompt: String,
    val filename: String,
    val contentType: String,
) {
    constructor(picture: ExecutionPicture) : this(
        id = requireNotNull(picture.id),
        nodeKey = picture.nodeKey,
        url = "/api/execution-pictures/${requireNotNull(picture.id)}",
        prompt = picture.prompt,
        filename = picture.filename,
        contentType = picture.contentType,
    )
}

/**
 * One row of the executions list: what the module recorded, plus whether this
 * workspace still lists the workflow the run names.
 *
 * The flag is here rather than left to the screen because the screen cannot
 * work it out. A row carries the workflow's id and the name it had when the run
 * started, and nothing about the id says whether the workspace assigns it.
 */
data class RunView(
    val id: Long,
    val workflowId: Long,
    val workflowName: String,
    val status: ExecutionStatus,
    val trigger: ExecutionTrigger,
    /** For a run an event on a connection started, which connection and event; null otherwise. */
    val source: ExecutionSourceView?,
    val startedAt: String,
    val finishedAt: String?,
    val durationSeconds: Int?,
    val stoppedReason: String?,
    /**
     * False for a run of a workflow this workspace has removed. Such a run is
     * kept and still opens; what it cannot do is lead anywhere, which is why
     * the row has to say so rather than offer a link into nothing.
     */
    val workflowAssigned: Boolean,
) {
    constructor(run: ExecutionView, assigned: Set<Long>, sources: Map<Long, ExecutionSourceView> = emptyMap()) : this(
        id = run.id,
        workflowId = run.workflowId,
        workflowName = run.workflowName,
        status = run.status,
        trigger = run.trigger,
        source = run.firedTriggerId?.takeIf { run.trigger == ExecutionTrigger.WEBHOOK }?.let(sources::get),
        startedAt = run.startedAt,
        finishedAt = run.finishedAt,
        durationSeconds = run.durationSeconds,
        stoppedReason = run.stoppedReason,
        workflowAssigned = run.workflowId in assigned,
    )
}

/** A page of [RunView], with the module's paging carried straight through. */
data class RunPage(
    val content: List<RunView>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    constructor(page: ExecutionPage, assigned: Set<Long>, sources: Map<Long, ExecutionSourceView> = emptyMap()) : this(
        content = page.content.map { RunView(it, assigned, sources) },
        page = page.page,
        size = page.size,
        totalElements = page.totalElements,
        totalPages = page.totalPages,
    )
}

/**
 * A workflow the executions list can be filtered by: one this workspace has
 * runs of, whether or not it still lists it.
 */
data class ExecutionWorkflowView(
    val workflowId: Long,
    /** The name its most recent run recorded, which for a removed one is all there is. */
    val name: String,
    /** False for one the workspace has removed; the filter says so rather than pretending. */
    val assigned: Boolean,
)

/** A run as the detail screen wants it: what the module recorded, plus the edges. */
data class RunDetailView(
    val id: Long,
    val workspaceId: Long,
    val workflowId: Long,
    val workflowName: String,
    val status: ExecutionStatus,
    val trigger: ExecutionTrigger,
    val startedAt: String,
    val finishedAt: String?,
    val durationSeconds: Int?,
    /** Why the run stopped, when it stopped badly. */
    val error: String?,
    /**
     * The node that ended the run early and what it said, when a condition
     * decided there was nothing further to do. Null for a run that went all the
     * way through — which is the difference this makes visible.
     */
    val stoppedAtNodeKey: String?,
    val stoppedReason: String?,
    /**
     * The run this one was started from, when somebody re-ran an earlier one -
     * the whole of it, or from one of its steps. Null for a run nobody re-ran,
     * which is what keeps the field worth showing when it is there.
     */
    val startedFrom: Long?,
    val steps: List<ExecutionStepView>,
    val edges: List<WorkflowEdgeView>,
    val logs: List<ExecutionLogLineView>,
    /**
     * Where Temporal's own screen for this run is, when there is one.
     *
     * What is here is what each node did; what is there is every attempt behind
     * it. Null when Temporal is off, or running without an interface to send
     * anybody to.
     */
    val temporalUrl: String? = null,
    /**
     * Whether this workspace still lists the workflow this run named. False for
     * a run whose workflow has been removed - the run is untouched, but there
     * is no editor to send anybody to, and the page says that instead.
     */
    val workflowAssigned: Boolean = true,
    /**
     * The pictures the run's image nodes drew, keyed to their steps by
     * [ExecutionPictureView.nodeKey] so the graph shows each under the node that
     * drew it. Empty for a run with no image node, and for one still under way.
     * Issue #333.
     */
    val pictures: List<ExecutionPictureView> = emptyList(),
    /**
     * What the run said out loud, keyed to their steps by
     * [ExecutionSpeechView.nodeKey] so the graph plays each under the node that
     * said it. Empty for a run that spoke nothing. Issue #264.
     */
    val speeches: List<ExecutionSpeechView> = emptyList(),
    /** For a run an event on a connection started, which connection and event; null otherwise. */
    val source: ExecutionSourceView? = null,
) {
    constructor(
        run: ExecutionDetailView,
        edges: List<WorkflowEdgeView>,
        temporalUrl: String?,
        workflowAssigned: Boolean,
        pictures: List<ExecutionPictureView>,
        speeches: List<ExecutionSpeechView>,
    ) : this(
        id = run.id,
        workspaceId = run.workspaceId,
        workflowId = run.workflowId,
        workflowName = run.workflowName,
        status = run.status,
        trigger = run.trigger,
        startedAt = run.startedAt,
        finishedAt = run.finishedAt,
        durationSeconds = run.durationSeconds,
        error = run.error,
        stoppedAtNodeKey = run.stoppedAtNodeKey,
        stoppedReason = run.stoppedReason,
        startedFrom = run.startedFrom,
        steps = run.steps,
        edges = edges,
        logs = run.logs,
        temporalUrl = temporalUrl,
        workflowAssigned = workflowAssigned,
        pictures = pictures,
        speeches = speeches,
    )
}

/**
 * What started a run an event on a connection started: the kind of connection
 * and the event, so the run says "Slack mention" rather than only "Connection".
 */
data class ExecutionSourceView(
    val connectionType: ConnectionType,
    val action: TriggerAction,
)

/** One thing a run said out loud, as the run graph plays it. */
data class ExecutionSpeechView(
    val id: Long,
    /** Which step said it, so the graph plays it under that node. */
    val nodeKey: String,
    /** Where the bytes are served: an `<audio src>` and the download. */
    val url: String,
    /** The words it read, because audio cannot be read at a glance. */
    val said: String,
    val filename: String,
    val contentType: String,
) {
    constructor(speech: ExecutionSpeech) : this(
        id = requireNotNull(speech.id),
        nodeKey = speech.nodeKey,
        url = "/api/executions/speech/${requireNotNull(speech.id)}",
        said = speech.said,
        filename = speech.filename,
        contentType = speech.contentType,
    )
}

class ExecutionNotFoundException(val id: Long) : RuntimeException("No execution with id $id"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

