package io.mszymanski.orknux.server.workflow

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime

/**
 * A workflow as it was when somebody published it.
 *
 * Until this existed, "published" was a word on a screen: a trigger fired every
 * workflow with a node instancing it and the runner read the rows as they were
 * at that moment, so an event arriving mid-edit ran the half-drawn graph. The
 * badge said Draft and the graph ran anyway.
 *
 * Now publishing takes a copy and the copy is what runs. What is left in the
 * tables is the draft, which is what makes saving safe - a graph can be left
 * half-finished overnight without answering the next event with it.
 *
 * **These are a workflow's versions, and they are kept.** There was one row per
 * workflow, replaced on every publish, so what a workflow ran last month was
 * gone the next time anybody pressed Publish. A draft is not a version — it is
 * what somebody is in the middle of — and a publication is, which makes keeping
 * them the whole of a workflow's history. The newest is what runs; see
 * [WorkflowPublicationRepository.current].
 *
 * The graph is kept as the execution module reads it, serialised whole. A
 * second set of tables shadowing the first would be a second schema to migrate
 * every time a node gains a field, in exchange for queries nobody makes: this
 * is read entire, by one caller, at the moment a run begins.
 */
@Entity
@Table(name = "workflow_publication")
class WorkflowPublication(
    /**
     * The publication's own id.
     *
     * It used to be the workflow's, which is what made the table one row per
     * workflow. A history needs a key of its own, and this one also orders it:
     * ids only increase, so the greatest is the newest, whatever a clock says.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workflow_id", nullable = false)
    val workflowId: Long,

    @Column(name = "published_at", nullable = false)
    var publishedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "published_by", nullable = false, length = 120)
    var publishedBy: String = "system",

    /** The runnable graph as JSON: nodes, their bindings, edges and branches. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    var graph: String = "{}",

    /**
     * The publication this one was copied from, when it was made by restoring.
     *
     * Restoring publishes again rather than reviving an old row: the newest
     * publication is always the one that runs, and the history only grows —
     * the shape of reverting a commit rather than deleting one. Null for a
     * publication somebody made from the draft, which is most of them.
     */
    @Column(name = "restored_from")
    val restoredFrom: Long? = null,
)

interface WorkflowPublicationRepository : JpaRepository<WorkflowPublication, Long> {

    /**
     * What this workflow runs: its newest publication, or null if it has none.
     *
     * By id rather than by date. Two publications a second apart must not be
     * able to swap places between two reads, and a clock that steps backwards
     * would otherwise put an older graph back into service without anybody
     * publishing anything.
     */
    fun findFirstByWorkflowIdOrderByIdDesc(workflowId: Long): WorkflowPublication?

    /** Whether it has ever been published, asked where an exception was costly. */
    fun existsByWorkflowId(workflowId: Long): Boolean

    /** One workflow's history, newest first, no more of it than was asked for. */
    fun findByWorkflowIdOrderByIdDesc(workflowId: Long, pageable: Pageable): List<WorkflowPublication>

    fun deleteByWorkflowId(workflowId: Long)

    /**
     * The publication each workflow is running, which is what retention keeps
     * whatever its age.
     *
     * A workflow published two years ago and left alone is still running that
     * graph; sweeping it away because it is old would stop the workflow.
     */
    @Query("select max(p.id) from WorkflowPublication p group by p.workflowId")
    fun currentIds(): List<Long>

    @Query("select p.id from WorkflowPublication p where p.publishedAt < :before")
    fun idsPublishedBefore(before: OffsetDateTime): List<Long>

    /**
     * What each of a workspace's workflows runs: the newest publication of
     * every one it has assigned, in one statement, and only the graph of it.
     * Issue #616 - this used to be seven statements and a parse per workflow.
     */
    @Query(
        """
        SELECT new io.mszymanski.orknux.server.workflow.CurrentGraph(p.workflowId, p.graph)
        FROM WorkflowPublication p
        WHERE p.id IN (
          SELECT MAX(q.id) FROM WorkflowPublication q
          WHERE q.workflowId IN (SELECT a.workflow.id FROM WorkspaceWorkflow a WHERE a.workspaceId = :workspaceId)
          GROUP BY q.workflowId
        )
        """,
    )
    fun currentInWorkspace(@Param("workspaceId") workspaceId: Long): List<CurrentGraph>
}

/** The snapshot a workflow runs, without the rest of its publication row. */
data class CurrentGraph(val workflowId: Long, val graph: String)

/** What a workflow runs now, said in one place so nothing has to spell it. */
fun WorkflowPublicationRepository.current(workflowId: Long): WorkflowPublication? =
    findFirstByWorkflowIdOrderByIdDesc(workflowId)
