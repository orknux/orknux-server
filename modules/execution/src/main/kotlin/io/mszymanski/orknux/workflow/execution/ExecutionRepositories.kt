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
