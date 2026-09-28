package io.mszymanski.orknux.server.task

import io.mszymanski.orknux.server.graphql.Refusal
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.query.Param
import org.springframework.data.jpa.repository.Query
import java.time.OffsetDateTime

/**
 * Where a task has got to.
 *
 * Six, and the interesting one is [WAITING]. A workflow run has three states
 * because a graph either walks or it does not; a task has a fourth thing it can
 * be doing, which is standing still because it asked somebody a question. That
 * is not a failure and it is not progress, and calling it either would lose the
 * one fact the person looking at the list needs.
 */
enum class TaskStatus {
    /** Recorded and not yet picked up. What [TaskEngine.begin] is handed. */
    QUEUED,

    /** An agent is working at it. */
    RUNNING,

    /**
     * Stopped, and waiting for a person.
     *
     * There is exactly one unanswered [TaskRequest] whenever a task is in this
     * state, and answering it is what moves the task back to [RUNNING].
     */
    WAITING,

    /** The agent said it had finished, and what it said is the outcome. */
    DONE,

    /** It could not be finished: out of turns, out of time, or the model refused. */
    FAILED,

    /** Somebody stopped it. */
    STOPPED,
    ;

    /** Whether there is anything left to do, which is what a sweep and a page both ask. */
    val over: Boolean get() = this == DONE || this == FAILED || this == STOPPED
}

/**
 * Something an agent may be given that it was not granted.
 *
 * The same six things an agent's own page grants, named the same way, because
 * approving one here is the same decision taken for one task instead of for
 * ever. There is deliberately no seventh meaning "everything": a blanket
 * approval is not a decision anybody can be held to, and the whole point of
 * parking a task is that somebody looks at what was asked for.
 */
enum class TaskCapability {
    /** Asking orknux about orknux, scoped to the task's own workspace. */
    ORKNUX,

    /** Opening a shell on one of the installation's machines and running commands. */
    SHELLS,

    /** One of the workspace's own tools, by name. */
    TOOL,

    /** One registered MCP server, by name. */
    MCP_SERVER,

    /** One skill catalog, by name. */
    SKILL_CATALOG,

    /** One memory catalog, by name. */
    MEMORY_CATALOG,
    ;

    /** Whether this capability names one thing, or is the whole of itself. */
    val named: Boolean get() = this != ORKNUX && this != SHELLS
}

/** What the agent stopped for. */
enum class TaskRequestKind {
    /** It wants something it was not granted. */
    PERMISSION,

    /**
     * It wants to be told something.
     *
     * The issue asks for this where the delivery of what a task produces is not
     * clear from the prompt, and it is the same mechanism as a permission for
     * that reason: park, tell somebody, resume with exactly what they said.
     */
    QUESTION,
}

/** What a person decided about a request. */
enum class TaskDecision {
    GRANTED,
    REFUSED,
    ANSWERED,
}

/**
 * A problem given to an agent, and the agent working at it until it is done.
 *
 * The row is small because the record is elsewhere: [sessionId] points at an LLM
 * session, and every tool the agent called, everything it was told and
 * everything it said is written there by the recorder every other agent in this
 * application already writes through. What is here is what the session cannot
 * answer - who asked, what it may spend, and whether it is still going.
 *
 * Nothing here is a workflow. A workflow is a graph somebody drew; this is a
 * loop, and what happens in it is the agent's decision at every turn.
 */
@Entity
@Table(name = "task")
class Task(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Column(nullable = false, length = TITLE_LENGTH)
    var title: String,

    @Column(nullable = false, columnDefinition = "text")
    val prompt: String,

    /**
     * The agent doing the work, or null for a task given to a bare model.
     *
     * A bare model has no grants at all, which is a coherent thing to want: it
     * has the three tools every task has, it can ask for anything else, and
     * whoever approves is approving for something that started with nothing.
     */
    @Column(name = "agent_id")
    var agentId: Long? = null,

    /** What it thinks with. Falls back to the agent's own model where null. */
    @Column(name = "model_id")
    var modelId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: TaskStatus = TaskStatus.QUEUED,

    /** The event log. See the migration for why there is not a second one. */
    @Column(name = "session_id")
    var sessionId: Long? = null,

    /** The issue this was started from, and the reason its observers hear about it. */
    @Column(name = "issue_id")
    val issueId: Long? = null,

    @Column(name = "created_by", nullable = false, length = 120)
    val createdBy: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "started_at")
    var startedAt: OffsetDateTime? = null,

    @Column(name = "finished_at")
    var finishedAt: OffsetDateTime? = null,

    @Column(name = "turns_spent", nullable = false)
    var turnsSpent: Int = 0,

    /**
     * Seconds actually worked, and not wall clock.
     *
     * Time spent parked belongs to whoever has not answered yet, and charging it
     * to the task would make "why did this fail" answerable with "you went
     * home".
     */
    @Column(name = "worked_seconds", nullable = false)
    var workedSeconds: Long = 0,

    /**
     * Copied from the installation's settings when the task was made, so a
     * later change to them does not move the goalposts under a running task.
     *
     * Settable, and only ever at one moment: [TaskService.carryOn], where a
     * finished task is asked to carry on and both allowances are taken again
     * from the settings as they stand. Nothing else writes them, and nothing
     * writes them while the task is working - which is what the paragraph above
     * is about.
     */
    @Column(name = "turns_allowed", nullable = false)
    var turnsAllowed: Int,

    @Column(name = "seconds_allowed", nullable = false)
    var secondsAllowed: Long,

    /** When a parked task gives up on being answered. Null unless waiting. */
    @Column(name = "waiting_until")
    var waitingUntil: OffsetDateTime? = null,

    @Column(columnDefinition = "text")
    var outcome: String? = null,

    @Column(name = "ended_because", length = 200)
    var endedBecause: String? = null,
) {
    companion object {
        /** Matches the column, and the issue title beside it in the news. */
        const val TITLE_LENGTH = 200
    }
}

/**
 * Something the agent stopped to ask for.
 *
 * One row per stop. A task has at most one of these unanswered at a time, which
 * is what makes "the task is waiting" and "this is what it is waiting for" the
 * same fact rather than two that could disagree.
 */
@Entity
@Table(name = "task_request")
class TaskRequest(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "task_id", nullable = false)
    val taskId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val kind: TaskRequestKind,

    /** Set on a permission and null on a question. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    val capability: TaskCapability? = null,

    /** Which one, where the capability names one. */
    @Column(length = 200)
    val subject: String? = null,

    /** The question, or why the agent says it needs the thing. Never null: it
     * is what the person reads before deciding. */
    @Column(nullable = false, columnDefinition = "text")
    val asks: String,

    @Column(name = "asked_at", nullable = false)
    val askedAt: OffsetDateTime = OffsetDateTime.now(),

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    var decision: TaskDecision? = null,

    /** What the person said, on a question. */
    @Column(columnDefinition = "text")
    var answer: String? = null,

    @Column(name = "decided_by", length = 120)
    var decidedBy: String? = null,

    @Column(name = "decided_at")
    var decidedAt: OffsetDateTime? = null,
) {
    /** Whether this is the one the task is standing still for. */
    val open: Boolean get() = decision == null
}

/**
 * Something a person let one task do that its agent may not.
 *
 * On the task rather than on the agent, and that is the safety argument in one
 * sentence: approving a shell for an afternoon's work must not arm the agent in
 * every chat for ever. The row is read while this task runs and is thrown away
 * with it.
 */
@Entity
@Table(name = "task_grant")
class TaskGrant(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "task_id", nullable = false)
    val taskId: Long,

    @Column(name = "request_id")
    val requestId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val capability: TaskCapability,

    @Column(length = 200)
    val subject: String? = null,

    @Column(name = "granted_by", nullable = false, length = 120)
    val grantedBy: String,

    @Column(name = "granted_at", nullable = false)
    val grantedAt: OffsetDateTime = OffsetDateTime.now(),
)

/**
 * Something a person said to a task while it was working.
 *
 * The other direction from [TaskRequest]. A request is the agent stopping to ask
 * and somebody answering; this is nobody having asked - the work is going,
 * somebody watching it wants it shaped differently, and they say so without
 * stopping the task and starting it again.
 *
 * **This is a doorstep and not a second transcript.** The row holds the words
 * only until the loop picks them up: at the top of its next turn [TaskLoop]
 * writes them into the task's LLM session as a turn from whoever typed them -
 * the same thing [TaskService] already does with the answer to a question - and
 * stamps [deliveredAt] here. From that moment the session is the account of what
 * was said and this row is the receipt.
 *
 * Held rather than written straight into the session because of ordering. A turn
 * is minutes long and the agent's answer is written when it ends, so a message
 * dropped in mid-turn lands *before* an answer composed without it, and the next
 * turn reads a conversation in which the agent appears to have already replied
 * to something it never saw.
 */
@Entity
@Table(name = "task_message")
class TaskMessage(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "task_id", nullable = false)
    val taskId: Long,

    /** Whoever typed it, by username. The transcript carries their name. */
    @Column(name = "said_by", nullable = false, length = 120)
    val saidBy: String,

    @Column(nullable = false, columnDefinition = "text")
    val body: String,

    @Column(name = "sent_at", nullable = false)
    val sentAt: OffsetDateTime = OffsetDateTime.now(),

    /**
     * When the loop put it in front of the agent, and null until it did.
     *
     * What lets the page be honest. A message typed while the model is mid-turn
     * has not been read yet, and a message typed at a task that finishes before
     * its next turn is never read at all - which somebody should be told rather
     * than left to assume.
     */
    @Column(name = "delivered_at")
    var deliveredAt: OffsetDateTime? = null,
) {
    /** Whether the agent has seen it, which is what the page draws. */
    val read: Boolean get() = deliveredAt != null
}

interface TaskRepository : JpaRepository<Task, Long> {

    /** The task whose conversation this is, for something arriving at it; see [TaskService.wake]. */
    fun findFirstBySessionId(sessionId: Long): Task?

    /**
     * The workspace's tasks, newest first, optionally narrowed to a status.
     *
     * Two methods rather than a nullable status, the way the tracker's filter
     * is two: "no filter" is the caller's decision and not a value to pass
     * down.
     */
    /*
     * Ordered by the page request rather than by a name or an ORDER BY. A fixed
     * order wins over the sort on the `Pageable`, so a list to be read in
     * whichever order somebody pressed a heading for cannot be fetched through
     * one. Issue #358. Newest first is what the caller asks for when nobody has
     * asked for anything else.
     */
    fun findByWorkspaceId(workspaceId: Long, pageable: Pageable): Page<Task>

    /**
     * The same, narrowed to what a word appears in the title.
     *
     * The title is what a row shows and what somebody is remembering. Asked of
     * the database rather than sieved in the browser because the list is paged
     * - narrowing what arrived on page one would hide matches on page four.
     *
     * The status still applies where one is set, for the reason the two
     * unsearched queries are two queries: "no filter" is a decision here and
     * not a value.
     */
    @Query(
        """
        SELECT t FROM Task t
        WHERE t.workspaceId = :workspaceId
          AND LOWER(t.title) LIKE LOWER(CONCAT('%', :looking, '%'))
        """,
    )
    fun searching(
        @Param("workspaceId") workspaceId: Long,
        @Param("looking") looking: String,
        pageable: Pageable,
    ): Page<Task>

    @Query(
        """
        SELECT t FROM Task t
        WHERE t.workspaceId = :workspaceId AND t.status = :status
          AND LOWER(t.title) LIKE LOWER(CONCAT('%', :looking, '%'))
        """,
    )
    fun searchingWithStatus(
        @Param("workspaceId") workspaceId: Long,
        @Param("status") status: TaskStatus,
        @Param("looking") looking: String,
        pageable: Pageable,
    ): Page<Task>

    fun findByWorkspaceIdAndStatus(
        workspaceId: Long,
        status: TaskStatus,
        pageable: Pageable,
    ): Page<Task>

    /** Everything this issue started, for the link back the tracker draws. */
    fun findByIssueIdOrderByCreatedAtDescIdDesc(issueId: Long): List<Task>

    /**
     * What was going when the process stopped, for the revival on the way back up.
     *
     * Read across every workspace, because a restart is not a workspace's
     * business.
     */
    @Query("select t from Task t where t.status in :statuses")
    fun inState(statuses: Collection<TaskStatus>): List<Task>

    /**
     * Tasks that have been queued longer than they should be, ids only.
     *
     * What [TaskSweeper] reads, and the reason `task_queued_idx` exists: it is
     * asked on a timer for the life of the installation, across every workspace,
     * and on a healthy one it answers nothing. Ids rather than rows because
     * nothing about a task is wanted here except which one to hand over - the
     * engine reads the row it needs on its own thread.
     *
     * QUEUED is the only state with this shape. It is set once, when the row is
     * written, and never returned to, so `created_at` is when the task entered
     * it and no second column is needed to say so.
     */
    @Query("select t.id from Task t where t.status = :status and t.createdAt < :before")
    fun idsInStateSince(status: TaskStatus, before: OffsetDateTime): List<Long>

    /**
     * Which of these sessions a task in [status] is writing into. Issue #448.
     *
     * The tasks' half of the sessions list's status dot, beside the runs' half
     * in the execution module: a tool call left without a result lights a
     * session only while something that writes into it is still going, and a
     * task's log is a session. Asked with RUNNING - a task parked for a person
     * is not at work, and a queued one has said nothing yet.
     */
    @Query("select t.sessionId from Task t where t.sessionId in :sessionIds and t.status = :status")
    fun sessionsOfTasksIn(sessionIds: Collection<Long>, status: TaskStatus): List<Long>
}

interface TaskRequestRepository : JpaRepository<TaskRequest, Long> {

    fun findByTaskIdOrderByAskedAtAscIdAsc(taskId: Long): List<TaskRequest>

    /** The one a waiting task is standing still for; at most one exists. */
    fun findFirstByTaskIdAndDecisionIsNullOrderByAskedAtAscIdAsc(taskId: Long): TaskRequest?
}

interface TaskGrantRepository : JpaRepository<TaskGrant, Long> {

    fun findByTaskIdOrderByGrantedAtAscIdAsc(taskId: Long): List<TaskGrant>
}

interface TaskMessageRepository : JpaRepository<TaskMessage, Long> {

    fun findByTaskIdOrderBySentAtAscIdAsc(taskId: Long): List<TaskMessage>

    /**
     * What the agent has not been shown yet, oldest first.
     *
     * The read the loop makes at the top of every turn. Oldest first because two
     * messages typed in a row are one person changing their mind, and the second
     * only means what it means after the first.
     */
    fun findByTaskIdAndDeliveredAtIsNullOrderBySentAtAscIdAsc(taskId: Long): List<TaskMessage>
}

class TaskNotFoundException(val id: Long) : RuntimeException("No task with id $id"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

class TaskRequestNotFoundException(val id: Long) : RuntimeException("No task request with id $id"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

/**
 * A request somebody has already decided.
 *
 * Said plainly rather than accepted quietly: two people looking at the same
 * parked task will both press the button, and the second one needs to be told
 * the task is already moving rather than left thinking they approved it.
 */
class TaskRequestSettledException :
    RuntimeException("That has already been decided, and the task has moved on")

class TaskNotRunnableException(val what: String) : RuntimeException(what), Refusal {

    override val arguments get() = mapOf("what" to what)
}

class TaskPromptMissingException : RuntimeException("A task needs something to work on")

/**
 * An empty message.
 *
 * Its own refusal rather than a silent no-op, because the box it comes from is
 * beside a working agent: somebody who presses send and is told nothing assumes
 * the agent has been told something.
 */
class TaskMessageMissingException : RuntimeException("A message needs something in it")
