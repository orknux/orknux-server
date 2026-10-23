package io.mszymanski.orknux.server.workflow

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.repository.query.Param
import org.springframework.data.jpa.repository.Query

interface WorkflowRepository : JpaRepository<Workflow, Long> {

    fun findByName(name: String): Workflow?

    /**
     * Moves the draft's revision on from [from]; the number of rows it moved,
     * so 0 is a draft somebody else saved since it was read.
     *
     * One statement rather than a read and a write, so two saves at once
     * cannot both pass: the second waits on the row the first has locked and
     * then finds the revision already moved. Clears what the context holds,
     * since a workflow read before this would otherwise be written back over
     * it with the old number. Issue #645.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Workflow w set w.graphRevision = w.graphRevision + 1 where w.id = :id and w.graphRevision = :from")
    fun advanceGraphFrom(@Param("id") id: Long, @Param("from") from: Long): Int

    /** The same for a save that read no revision - a tool's, which sends the whole graph it means. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Workflow w set w.graphRevision = w.graphRevision + 1 where w.id = :id")
    fun advanceGraph(@Param("id") id: Long): Int
}

interface WorkspaceWorkflowRepository : JpaRepository<WorkspaceWorkflow, Long> {

    fun findByWorkspaceId(workspaceId: Long, pageable: Pageable): Page<WorkspaceWorkflow>

    /**
     * The same, narrowed to what a word appears in.
     *
     * The workflow's name and description, reached through the assignment -
     * which is the row this list is of, and the workflow is what somebody is
     * actually looking for. Asked of the database rather than sieved in the
     * browser because the list is paged.
     *
     * The sort still comes from the pageable, so a search keeps whatever order
     * the column headers are set to rather than silently reverting to one of
     * this query\'s choosing.
     */
    @Query(
        """
        SELECT a FROM WorkspaceWorkflow a
        WHERE a.workspaceId = :workspaceId
          AND (LOWER(a.workflow.name) LIKE LOWER(CONCAT('%', :looking, '%'))
            OR LOWER(COALESCE(a.workflow.description, '')) LIKE LOWER(CONCAT('%', :looking, '%')))
        """,
    )
    fun searching(
        @Param("workspaceId") workspaceId: Long,
        @Param("looking") looking: String,
        pageable: Pageable,
    ): Page<WorkspaceWorkflow>

    /**
     * All of them, for a caller asking about the workspace rather than showing
     * it a page - which in practice means "does anything here still name this
     * definition", asked once when somebody tries to delete one.
     */
    fun findByWorkspaceId(workspaceId: Long): List<WorkspaceWorkflow>

    /**
     * The same list, as the three columns a "where is this used" answer reads.
     *
     * One statement. Read as entities, every assignment fetched its workflow
     * with a select of its own and its last run with a subquery. Issue #616.
     */
    @Query(
        """
        SELECT new io.mszymanski.orknux.server.workflow.AssignedWorkflow(w.id, w.name, w.status)
        FROM WorkspaceWorkflow a JOIN a.workflow w
        WHERE a.workspaceId = :workspaceId
        ORDER BY a.id
        """,
    )
    fun assignedTo(@Param("workspaceId") workspaceId: Long): List<AssignedWorkflow>

    fun existsByWorkspaceIdAndWorkflowId(workspaceId: Long, workflowId: Long): Boolean

    /** Every workspace a workflow is assigned to; one workflow may be in several. */
    fun findByWorkflowId(workflowId: Long): List<WorkspaceWorkflow>

    /**
     * Whether this workspace already has a workflow by this name - the name
     * check, scoped to the workspace rather than the installation, because a
     * name is only taken where it is used. Resolves across the assignment's
     * `workflow` to its `name`. See issue #341.
     */
    fun existsByWorkspaceIdAndWorkflowName(workspaceId: Long, workflowName: String): Boolean

    /** The assignment a name belongs to in this workspace, for the importer's reuse decision. */
    fun findByWorkspaceIdAndWorkflowName(workspaceId: Long, workflowName: String): WorkspaceWorkflow?

    /**
     * The assignment itself, for a caller that needs more than whether it is
     * there - which in practice means whether the workspace has it switched on.
     */
    fun findByWorkspaceIdAndWorkflowId(workspaceId: Long, workflowId: Long): WorkspaceWorkflow?
}

/** A workflow a workspace has, as much of it as [WorkflowReferences] needs. */
data class AssignedWorkflow(val id: Long, val name: String, val status: WorkflowStatus)
