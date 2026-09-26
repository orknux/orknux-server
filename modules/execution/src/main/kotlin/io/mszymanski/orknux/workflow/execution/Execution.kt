package io.mszymanski.orknux.workflow.execution

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

enum class ExecutionStatus {
    RUNNING,
    COMPLETED,
    FAILED,

    /** Ended because somebody asked it to stop, rather than because it finished or failed. Issue #395. */
    STOPPED,
}

enum class ExecutionTrigger {
    WEBHOOK,
    MANUAL,
    SCHEDULE,
    API,
}

/** Where one node of the graph got to in a run. */
enum class StepStatus {
    /** Never reached, because the run stopped or has not got there yet. */
    PENDING,
    RUNNING,

    /**
     * Parked: the node was asked, is not ready, and said when to come back.
     *
     * Nothing is held while it waits — the delay belongs to whatever is
     * carrying the run, which is a Temporal timer, or the inline engine's own
     * sleep. The step is open, so it has a start and no finish.
     */
    WAITING,
    COMPLETED,
    FAILED,
    SKIPPED,
}

enum class LogLevel {
    INFO,
    SUCCESS,
    ERROR,
}

/**
 * One run of a workflow.
 *
 * The workflow itself lives in orknux-server, so this holds its id and the name
 * it had when the run started rather than a foreign key: the definition can be
 * renamed, or removed, without rewriting what happened.
 */
@Entity
@Table(name = "workflow_execution")
class WorkflowExecution(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Column(name = "workflow_id", nullable = false)
    val workflowId: Long,

    @Column(name = "workflow_name", nullable = false)
    val workflowName: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: ExecutionStatus,

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 16)
    val trigger: ExecutionTrigger,

    @Column(name = "started_at", nullable = false)
    val startedAt: OffsetDateTime,

    /** Null while the run is still going. */
    @Column(name = "finished_at")
    var finishedAt: OffsetDateTime? = null,

    /** What the run was handed by whatever asked for it. */
    @Column(columnDefinition = "text")
    val input: String? = null,

    /**
     * The run this one was started from, when somebody re-ran an earlier one.
     *
     * Null for an ordinary run. Written down rather than worked out afterwards
     * because nothing else on the row says it: a re-run is recorded as manual
     * and carries the earlier run's input, which describes every hand-started
     * run equally well and names no particular one.
     */
    @Column(name = "started_from")
    val startedFrom: Long? = null,

    /**
     * The trigger definition this run came from, where one fired.
     *
     * [trigger] says what kind of thing started the run; this says which one,
     * and a workflow drawn with two triggers needs the difference — the branch
     * belonging to the other one has no business running.
     *
     * Kept on the row for the sake of re-running. A re-run is recorded as
     * manual, because a person pressed it, so nothing else on the row would say
     * which trigger the run it repeats belonged to; without this a repeat would
     * run both halves of a graph the original ran one half of, which is the one
     * thing repeating something must not do.
     *
     * Null for every run nothing triggered, and for every run recorded before
     * this was kept. Both mean the same to the engine: nothing says which
     * trigger fired, so none of them is silenced.
     */
    @Column(name = "fired_trigger_id")
    val firedTriggerId: Long? = null,

    /**
     * What the run is carrying now: everything produced so far, under the names
     * the nodes gave it.
     *
     * Kept here rather than passed from step to step because one of the two
     * engines is Temporal, where an activity's arguments and results are written
     * into event history and kept for the life of the run. Handing a growing
     * payload back and forth would record it again at every step — fine for a
     * Slack message, ruinous for anything that moves real data, and bounded by a
     * payload limit that has nothing to do with what a workflow ought to carry.
     * So the payload lives in the database and Temporal carries an id.
     */
    @Column(columnDefinition = "text")
    var carried: String? = null,

    /** Why the run stopped, when it stopped badly. */
    @Column(length = 1000)
    var error: String? = null,

    /**
     * The node that ended the run early, and what it said.
     *
     * A condition that does not hold ends a run without failing it, which would
     * otherwise be indistinguishable from a run that did everything — the steps
     * after it simply never started.
     */
    @Column(name = "stopped_at_node_key", length = 64)
    var stoppedAtNodeKey: String? = null,

    @Column(name = "stopped_reason", length = 500)
    var stoppedReason: String? = null,

    /**
     * Whether somebody has asked this run to stop. Issue #395.
     *
     * The signal, not the state: the engine reads it before each step and ends
     * the run where it stands rather than starting the next. The state that
     * results is [ExecutionStatus.STOPPED], which is terminal - a stopped run
     * does not resume.
     */
    @Column(name = "stop_requested", nullable = false)
    var stopRequested: Boolean = false,
)

/**
 * One node of the graph as this run saw it. The name, kind and position are
 * copied when the run starts, so editing the workflow afterwards does not
 * rewrite history.
 */
@Entity
@Table(name = "execution_step")
class ExecutionStep(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "execution_id", nullable = false)
    val executionId: Long,

    @Column(name = "node_key", nullable = false, length = 64)
    val nodeKey: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val kind: NodeKind,

    @Column(nullable = false)
    val name: String,

    @Column(length = 500)
    val description: String? = null,

    /**
     * Which action this step runs, copied when the run started.
     *
     * The id and not the definition, which is the difference that matters: the
     * step is pinned to the same catalogue entry the graph named however the
     * graph is redrawn afterwards, and the runner reads that entry's row when
     * it gets here. So a run whose action was edited while it was waiting runs
     * the edit, and a published workflow calls whatever its function says now.
     * `PublishedDefinitionsTest` in the app module is where that is written
     * down. The step's own copies below - the mappings, the retry policy - are
     * the fields that genuinely are frozen.
     */
    @Column(name = "action_id")
    val actionId: Long? = null,

    /** Which condition this step asks; the id, like the action above it. */
    @Column(name = "condition_id")
    val conditionId: Long? = null,

    /** The agent this step runs, when the node is one; again by id. */
    @Column(name = "agent_id")
    val agentId: Long? = null,

    /** The image model this step draws with, when the node is one; by id. */
    @Column(name = "image_model_id")
    val imageModelId: Long? = null,

    /**
     * What this step asks of the drawing beyond the prompt, copied when the run
     * started: the size in pixels, the quality and the style, each as the word
     * the image endpoint takes. Null is the model's default. The run's own
     * copy, like the mappings: a node re-sized while this waits draws what it
     * was started with. Issue #423.
     */
    @Column(name = "image_size", length = 16)
    val imageSize: String? = null,

    @Column(name = "image_quality", length = 16)
    val imageQuality: String? = null,

    @Column(name = "image_style", length = 16)
    val imageStyle: String? = null,

    /** The shape an agent step's answer is held to, copied when the run started. */
    @Column(name = "output_object_id")
    val outputObjectId: Long? = null,

    /**
     * What the node called what it produces, copied when the run started.
     *
     * With a name, the step's output is an object holding it, so a later step
     * can read `{{input.<name>}}` instead of being handed prose it cannot
     * address. Without one, the output is passed on unchanged.
     */
    @Column(name = "output_name", length = 60)
    val outputName: String? = null,

    /**
     * What this step was told to pass, as JSON of name to expression.
     *
     * The run's own copy: a workflow edited while this is waiting, or replayed
     * later, resolves against what it started with.
     */
    @Column(name = "mappings", columnDefinition = "text")
    val mappings: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: StepStatus = StepStatus.PENDING,

    /**
     * How many times in all this step may be attempted, copied when the run
     * started; null is once, which is what every step was until now.
     *
     * The run's own copy for the same reason the mappings are: a policy edited
     * while this step is between attempts must not change how many it gets.
     */
    @Column(name = "retry_attempts")
    val retryAttempts: Int? = null,

    /** The wait before this step's second attempt, in seconds; null is none. */
    @Column(name = "retry_backoff_seconds")
    val retryBackoffSeconds: Int? = null,

    /**
     * What that wait is multiplied by after each attempt; null is one.
     *
     * Copied like the two above it: a node whose curve is steepened while this
     * step sits between attempts must not change the clock this run is on.
     */
    @Column(name = "retry_multiplier")
    val retryMultiplier: Double? = null,

    /** The most one of this step's waits may come to; null is the engine's own ceiling. */
    @Column(name = "retry_max_wait_seconds")
    val retryMaxWaitSeconds: Int? = null,

    /** The fraction of a wait that may be taken off it at random; null is none. */
    @Column(name = "retry_jitter")
    val retryJitter: Double? = null,

    /** The longest this step may go on being attempted for; null is no limit. */
    @Column(name = "retry_budget_seconds")
    val retryBudgetSeconds: Int? = null,

    /**
     * When this step's budget runs out, stamped the first time it is attempted.
     *
     * Written down rather than worked out, because a budget is wall clock and
     * nothing else on the row can answer when the first attempt began:
     * [startedAt] is rewritten by every attempt, and the attempt after next may
     * be carried by a different worker in a different process. Null on a step
     * with no budget, and on every step written before there were budgets.
     */
    @Column(name = "retry_deadline")
    var retryDeadline: OffsetDateTime? = null,

    /**
     * How many attempts this step has spent.
     *
     * On the row rather than counted by whatever is driving, because an attempt
     * and the one after it can be carried by different workers in different
     * processes: Temporal hands the step back as a fresh activity call, which
     * knows nothing of what the last one tried.
     */
    @Column(nullable = false)
    var attempts: Int = 0,

    /**
     * Which way out of a condition this step sent the run.
     *
     * Null for every kind that answers nothing, and for a condition drawn
     * without branches. Kept because a run started from a node halfway down the
     * graph has to know which edges the earlier run took: without it, a branch
     * the earlier run refused is indistinguishable from one it never reached,
     * and the new run would revive it.
     *
     * [EdgeBranch.FAILURE] on a failed step is the same fact about a step that
     * could not do its work: the run went on down its failure edge rather than
     * stopping there.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    var branch: EdgeBranch? = null,

    /**
     * Copied from an earlier run rather than performed by this one.
     *
     * A run that starts partway down shows the steps ahead of it as they were,
     * so the page reads end to end. That would otherwise claim work this run
     * never did, which is why it is written down rather than inferred from the
     * times.
     */
    @Column(name = "carried_over", nullable = false)
    var carriedOver: Boolean = false,

    @Column(name = "position_x", nullable = false)
    val x: Double,

    @Column(name = "position_y", nullable = false)
    val y: Double,

    /** The position in the run order, which is what the detail view reads by. */
    @Column(name = "step_order", nullable = false)
    val order: Int,

    @Column(name = "started_at")
    var startedAt: OffsetDateTime? = null,

    @Column(name = "finished_at")
    var finishedAt: OffsetDateTime? = null,

    @Column(columnDefinition = "text")
    var input: String? = null,

    @Column(columnDefinition = "text")
    var output: String? = null,

    @Column(length = 1000)
    var error: String? = null,

    /**
     * When a waiting step gives up, written the first time it parks.
     *
     * A wait outlives the worker that started it, so the deadline has to be
     * somewhere both of them can read: a step resumed an hour later counts from
     * when it first parked, not from when it was picked up again.
     */
    @Column(name = "wait_until")
    var waitUntil: OffsetDateTime? = null,

    /**
     * How many times in a row an agent on this step has stopped to wait.
     *
     * An agent may end its turn with a wake-up rather than an answer - it is
     * watching a build, or waiting on somebody - and the step parks and is asked
     * again when the time is up. Counted here rather than by whatever is driving
     * the run, for the reason [attempts] is: the wake-up may be carried by a
     * different worker in a different process, which knows nothing of what this
     * one did. The installation's ceiling on consecutive sleeps is counted
     * against this, so a model that keeps deciding to wait a little longer is
     * stopped rather than left going round for a week.
     *
     * Not an attempt: a sleep is the same attempt, deliberately paused.
     */
    @Column(name = "agent_sleeps", nullable = false)
    var agentSleeps: Int = 0,

    /**
     * What the agent left itself before it slept.
     *
     * A woken node is handed the same input it had the first time and nothing
     * else, so without this it wakes with no idea why it slept - the session it
     * may or may not keep is the only other memory in the picture, and most
     * nodes keep none. The note is put to it when it is asked again.
     */
    @Column(name = "agent_sleep_note", columnDefinition = "text")
    var agentSleepNote: String? = null,

    /**
     * The LLM session an agent step talked into, when it kept one.
     *
     * Written by the runner the first time it opens the session, so the run's
     * page can link a step to the conversation it produced. Null for every step
     * that keeps no session - which is every non-agent step, and every agent
     * node with none wired to it. Issue #387.
     */
    @Column(name = "session_id")
    var sessionId: Long? = null,
)

/** One line of what a run reported. */
@Entity
@Table(name = "execution_log")
class ExecutionLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "execution_id", nullable = false)
    val executionId: Long,

    /** Null for lines about the run itself rather than one step. */
    @Column(name = "node_key", length = 64)
    val nodeKey: String? = null,

    @Column(name = "logged_at", nullable = false)
    val loggedAt: OffsetDateTime,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val level: LogLevel = LogLevel.INFO,

    @Column(nullable = false, length = 2000)
    val message: String,

    /** Keeps lines logged in the same instant in the order they happened. */
    @Column(name = "sequence_no", nullable = false)
    val sequence: Int,
)
