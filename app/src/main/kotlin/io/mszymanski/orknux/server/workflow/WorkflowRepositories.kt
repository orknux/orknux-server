package io.mszymanski.orknux.server.workflow

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.query.Param
import org.springframework.data.jpa.repository.Query

interface WorkflowRepository : JpaRepository<Workflow, Long> {

    fun findByName(name: String): Workflow?
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
