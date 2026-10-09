package io.mszymanski.orknux.workflow.execution

import org.springframework.data.jpa.domain.Specification
import java.time.OffsetDateTime
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface WorkflowExecutionRepository :
    JpaRepository<WorkflowExecution, Long>,
    JpaSpecificationExecutor<WorkflowExecution> {

    /**
     * The run, held against every other writer until the transaction ends.
     * Issue #285.
     *
     * What two steps finishing at the same moment on two paths of one run take
     * before adding what they produced to what the run carries: read, merged
     * and written by one of them at a time, or the second write carries a copy
     * that never saw the first and loses it.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from WorkflowExecution e where e.id = :id")
    fun lockById(@org.springframework.data.repository.query.Param("id") id: Long): WorkflowExecution?

    /** The most recent run of one workflow, for the list that shows where it got to. */
    fun findFirstByWorkspaceIdAndWorkflowIdOrderByStartedAtDesc(
        workspaceId: Long,
        workflowId: Long,
    ): WorkflowExecution?

    /**
     * Every workflow this workspace has a run of, and when each was last run.
     *
     * Grouped by the name as well as the id because a run keeps the name the
     * workflow had when it started, so one that has been renamed comes back
     * once per name it has worn - which is why the last run is selected too:
     * it is what lets the caller pick the newest of them.
     *
     * Nothing is joined. This module knows which workflow a run named; whether
     * the workspace still lists that workflow is not its question.
     */
    @Query(
        """
        select e.workflowId as workflowId, e.workflowName as workflowName, max(e.startedAt) as lastRunAt
        from WorkflowExecution e
        where e.workspaceId = :workspaceId
        group by e.workflowId, e.workflowName
        """,
    )
    fun workflowsRun(workspaceId: Long): List<RanWorkflow>
    /**
     * The runs a retention sweep may take.
     *
     * Ids rather than entities: a sweep deletes by id in batches, and loading a
     * run to throw it away would read its whole payload first.
     *
     * **A run still going is never a candidate.** `finishedAt` is null while it
     * is running, so measuring from it excludes them by construction - and the
     * status is asked for as well, because a run left with no finish by a crash
     * would otherwise be immortal.
     */
    @Query(
        """
        select e.id from WorkflowExecution e
        where e.finishedAt < :before
          and e.status <> io.mszymanski.orknux.workflow.execution.ExecutionStatus.RUNNING
        """,
    )
    fun idsFinishedBefore(before: OffsetDateTime): List<Long>

    /**
     * Every run of a workspace, for when the workspace itself is deleted.
     *
     * `workflow_execution` carries no foreign key on the workspace - the table
     * belongs to another module - so nothing cascades, and without this the
     * rows stay for ever, unreachable by any query the product can make.
     */
    @Query("select e.id from WorkflowExecution e where e.workspaceId = :workspaceId")
    fun idsOfWorkspace(workspaceId: Long): List<Long>

    /**
     * Runs still RUNNING whose record has not moved since [cutoff] and which
     * are not parked on a wake, for the sweeper that carries them on. Issue
     * #448.
     *
     * The other way a restart strands a run. [ExecutionStepRepository.parkedPast]
     * finds a run whose open step is WAITING past its wake; this finds the run
     * the restart caught anywhere else - between two steps, with one COMPLETED
     * and the next PENDING and nothing to dispatch it, or in the middle of a
     * step, which is left RUNNING with no thread underneath it. Neither state
     * has a wake to read back, so what says the run is dead is that nothing on
     * it has been touched since the cutoff: no step started or finished after
     * it, and the run itself began before it. A live run moves from one step
     * to the next in milliseconds and stamps each as it goes, so a record that
     * has sat still for the whole grace is one nobody is writing.
     *
     * A run with a WAITING step is left to the wake query, whatever its
     * timestamps say: a wait is meant to sit still, and one parked for a retry
     * carries no wake at all and is being slept out on a live thread.
     */
    @Query(
        """
        select e.id from WorkflowExecution e
        where e.status = io.mszymanski.orknux.workflow.execution.ExecutionStatus.RUNNING
          and e.startedAt < :cutoff
          and not exists (
            select 1 from ExecutionStep w
            where w.executionId = e.id
              and w.status = io.mszymanski.orknux.workflow.execution.StepStatus.WAITING
          )
          and not exists (
            select 1 from ExecutionStep s
            where s.executionId = e.id
              and (s.startedAt >= :cutoff or s.finishedAt >= :cutoff)
          )
        """,
    )
    fun stalledBefore(cutoff: OffsetDateTime): List<Long>
}

/** One row of [WorkflowExecutionRepository.workflowsRun]. */
interface RanWorkflow {
    val workflowId: Long
    val workflowName: String
    val lastRunAt: OffsetDateTime

}

interface ExecutionStepRepository : JpaRepository<ExecutionStep, Long> {
    fun findByExecutionIdOrderByOrderAsc(executionId: Long): List<ExecutionStep>
    fun findByExecutionIdAndNodeKey(executionId: Long, nodeKey: String): ExecutionStep?

    /** Whether a step writing into this session is in one of these states: mid-turn, or parked on it. */
    fun existsBySessionIdAndStatusIn(sessionId: Long, statuses: Collection<StepStatus>): Boolean

    /** The step that last wrote into this session. */
    fun findFirstBySessionIdOrderByIdDesc(sessionId: Long): ExecutionStep?

    /** The step this agent last answered in, in this session: what a watcher it set there woke it from. */
    fun findFirstBySessionIdAndAgentIdOrderByIdDesc(sessionId: Long, agentId: Long): ExecutionStep?

    /**
     * The runs that wrote into one session: the distinct executions whose steps
     * name it. Issue #420.
     *
     * The reverse of [ExecutionStep.sessionId] - a step records which session
     * its agent talked into, and this reads it the other way, so a session's
     * page can find the run or runs that produced it. Distinct because a run
     * that touched one session in more than one step is still one run.
     */
    @Query(
        """
        select distinct s.executionId from ExecutionStep s
        where s.sessionId = :sessionId
        """,
    )
    fun executionIdsForSession(sessionId: Long): List<Long>

    /**
     * The runs parked on this session right now: a step writing into it that is
     * WAITING. What something arriving at the session may wake - a run still
     * mid-turn reads the session's inbox itself, and waking it as well left a
     * wake pending that cut its next wait short.
     */
    @Query(
        """
        select distinct s.executionId from ExecutionStep s
        where s.sessionId = :sessionId and s.status = io.mszymanski.orknux.workflow.execution.StepStatus.WAITING
        """,
    )
    fun executionIdsWaitingOnSession(sessionId: Long): List<Long>

    /**
     * Which of these sessions a run is still writing into: the sessions named
     * by a step of a run that is RUNNING. Issue #448.
     *
     * For the sessions list's status dot. A tool called with no result, or a
     * thought with no end, used to mean an agent was at work in the session -
     * and it does, for as long as the run that opened the line is going. A run
     * the process died under leaves the line open for ever, and the session
     * read as active for ever with it. So the open line counts only while
     * something that writes into the session is still running, and this is the
     * runs' half of that answer. One query for a page, not one per row.
     */
    @Query(
        """
        select distinct s.sessionId from ExecutionStep s
        where s.sessionId in :sessionIds
          and exists (
            select 1 from WorkflowExecution e
            where e.id = s.executionId
              and e.status = io.mszymanski.orknux.workflow.execution.ExecutionStatus.RUNNING
          )
        """,
    )
    fun sessionsWithRunningExecutions(sessionIds: Collection<Long>): List<Long>

    /**
     * Runs left parked past their wake, for the sweeper that carries them on.
     * Issue #406.
     *
     * A step waits on the inline engine by sleeping the thread that carries the
     * run. A restart kills that thread, so the wake never fires and the run sits
     * WAITING for ever. This finds those: a run still RUNNING whose open step
     * was due to wake before [cutoff]. The cutoff is set back far enough that a
     * wait a live thread is about to answer on its own is not swept out from
     * under it - only one no thread is watching any more.
     */
    @Query(
        """
        select distinct s.executionId from ExecutionStep s
        where s.status = io.mszymanski.orknux.workflow.execution.StepStatus.WAITING
          and s.waitUntil is not null
          and s.waitUntil < :cutoff
          and exists (
            select 1 from WorkflowExecution e
            where e.id = s.executionId
              and e.status = io.mszymanski.orknux.workflow.execution.ExecutionStatus.RUNNING
          )
        """,
    )
    fun parkedPast(cutoff: OffsetDateTime): List<Long>

    /**
     * Goes with the runs it belongs to; nothing here cascades on its own.
     *
     * One statement rather than Spring Data's derived delete, which loads every
     * row and deletes them one at a time - and then argues with the persistence
     * context about rows it has already removed.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ExecutionStep s where s.executionId in :executionIds")
    fun deleteByExecutionIdIn(executionIds: Collection<Long>): Int
}

interface ExecutionLogRepository : JpaRepository<ExecutionLog, Long> {
    fun findByExecutionIdOrderBySequenceAsc(executionId: Long): List<ExecutionLog>

    /** The same: a log line outlives its run only if somebody forgets it. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from ExecutionLog l where l.executionId in :executionIds")
    fun deleteByExecutionIdIn(executionIds: Collection<Long>): Int

    /** The next line's sequence number; see [RunLogger] for why it is read, not counted. */
    fun countByExecutionId(executionId: Long): Int
}

/**
 * Filters for the runs listing; the optional ones are simply left out. A
 * `Specification` rather than JPQL, because `:enum IS NULL OR …` fails in
 * Hibernate 6.
 */
fun executionFilter(
    workspaceId: Long?,
    workflowId: Long?,
    status: ExecutionStatus?,
    since: OffsetDateTime? = null,
    search: String? = null,
): Specification<WorkflowExecution> = Specification { root, _, builder ->
    val predicates = mutableListOf<jakarta.persistence.criteria.Predicate>()

    workspaceId?.let { predicates += builder.equal(root.get<Long>("workspaceId"), it) }
    workflowId?.let { predicates += builder.equal(root.get<Long>("workflowId"), it) }
    status?.let { predicates += builder.equal(root.get<ExecutionStatus>("status"), it) }
    since?.let { predicates += builder.greaterThanOrEqualTo(root.get("startedAt"), it) }
    // The name the workflow had when the run started is the only text a run
    // carries, and it is what the executions view searches.
    search?.let {
        predicates += builder.like(builder.lower(root.get("workflowName")), "%${it.lowercase()}%")
    }

    builder.and(*predicates.toTypedArray())
}
