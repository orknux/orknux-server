package io.mszymanski.orknux.server.workflow

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.Formula
import java.time.OffsetDateTime

/** A workflow definition, independent of the workspaces that use it. */
@Entity
@Table(name = "workflow")
class Workflow(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    // Not unique in the database: a name is unique within a workspace, and the
    // workspace a definition belongs to is on its assignment, not here. The
    // check is made against the assignments in WorkflowAPI, the way every other
    // per-workspace name is. See issue #341.
    @Column(nullable = false)
    var name: String,

    @Column(length = 500)
    var description: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: WorkflowStatus = WorkflowStatus.DRAFT,

    /**
     * How many times the draft graph has been saved. An editor saves against
     * the revision it last read, so a save over somebody else's newer one is
     * refused and merged rather than written over it. Moved only by
     * [WorkflowRepository.advanceGraph]. Issue #645.
     */
    @Column(name = "graph_revision", nullable = false)
    val graphRevision: Long = 0,

    /**
     * Kept off the Workflows list unless somebody ticks "Show hidden". The
     * research workflows are seeded this way: they are the workspace's to
     * edit, and a list of what people built is not where to meet three nobody
     * did. Hidden is not disabled - it runs exactly as it would shown. Issue #659.
     */
    @Column(nullable = false)
    var hidden: Boolean = false,
)

/** A workflow made available to a workspace, which the workspace can enable or disable. */
@Entity
@Table(name = "workspace_workflow")
class WorkspaceWorkflow(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val workspaceId: Long,

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "workflow_id", nullable = false)
    val workflow: Workflow,

    @Column(nullable = false)
    var enabled: Boolean = true,

    /**
     * When this workflow last started a run in this workspace, or null when it
     * never has.
     *
     * A read-only expression rather than a column, because nothing writes it:
     * the answer already exists in `workflow_execution`, and a copy kept beside
     * the assignment would be a second truth to keep up to date on every run,
     * every re-run and every deletion.
     *
     * It is here at all so that the list can be *ordered* by it. The row's last
     * run is already fetched one workflow at a time for display, but a page is
     * ten rows of however many the workspace has, and ordering ten of them
     * orders the page rather than the list. Ordering has to happen in the
     * query, and a query can only order by something the database can name.
     *
     * `(workflow_id, started_at DESC)` is indexed, which is exactly the shape
     * this asks for.
     */
    @Formula(
        "(select max(run.started_at) from workflow_execution run " +
            "where run.workflow_id = workflow_id and run.workspace_id = workspace_id)",
    )
    val lastRunAt: OffsetDateTime? = null,
)
