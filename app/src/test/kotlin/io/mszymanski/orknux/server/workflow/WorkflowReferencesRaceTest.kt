package io.mszymanski.orknux.server.workflow

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import tools.jackson.databind.json.JsonMapper

/**
 * Deleting a condition failed with "Workflow 424 is not assigned to workspace 9".
 *
 * The caller was deleting a **condition** and had never named workflow 424. The
 * runs either side of it succeeded on the same code, so it was intermittent
 * rather than a plain bug in the delete path. Issue #194.
 *
 * The shape, once found: the delete asks what still uses the condition, which
 * listed the workspace's assignments and then read each workflow's published
 * graph - and reading the graph checked the assignment *again*, raising when it
 * had gone. Between the list and the read, another transaction removing a
 * workflow left a row in hand whose assignment no longer existed. A browser
 * check tidying up does exactly that, which is how it was noticed.
 *
 * Since #616 the graph is not read through that door at all: the drafts and the
 * snapshots are each one statement that asks the assignments for itself. The
 * window is still there - between the list and those statements - and what has
 * to stay true is the same: a workflow that has stopped being this workspace's
 * is not a workflow using this condition, and saying so raises nothing.
 *
 * Fakes rather than a database, because the window is between two statements
 * and cannot be opened on demand: what is reproduced here is the state that
 * window produces - a listed workflow the later statements no longer find.
 */
class WorkflowReferencesRaceTest {

    private val assignments = mock(WorkspaceWorkflowRepository::class.java)
    private val nodes = mock(WorkflowNodeRepository::class.java)
    private val publications = mock(WorkflowPublicationRepository::class.java)

    private val references = WorkflowReferences(assignments, nodes, publications, JsonMapper.builder().build())

    private val workflow = AssignedWorkflow(424, "Answer a question asked in Slack", WorkflowStatus.PUBLISHED)

    @Test
    fun `a workflow unassigned between the list and the read is not a dependant, and does not raise`() {
        // Listed: the read that happened a moment ago still has it.
        `when`(assignments.assignedTo(9)).thenReturn(listOf(workflow))
        // Gone: the statements after it no longer find it, which is the whole race.
        `when`(nodes.draftsNamingCondition(9, 77)).thenReturn(emptyList())
        `when`(publications.currentInWorkspace(9)).thenReturn(emptyList())

        assertThatCode { references.toCondition(9, 77) }.doesNotThrowAnyException()
        assertThat(references.toCondition(9, 77)).isEmpty()
    }

    /**
     * The ordinary case still reads the published copy.
     *
     * A guard that answered "not assigned" for everything would make every
     * delete succeed and every published workflow invisible to it, which is a
     * worse bug than the one being fixed.
     */
    @Test
    fun `a workflow this workspace still has is read as it always was`() {
        `when`(assignments.assignedTo(9)).thenReturn(listOf(workflow))
        `when`(nodes.draftsNamingCondition(9, 77)).thenReturn(emptyList())
        `when`(publications.currentInWorkspace(9)).thenReturn(
            listOf(CurrentGraph(424, """{"nodes":[{"key":"c","kind":"CONDITION","conditionId":77,"x":770}]}""")),
        )

        assertThat(references.toCondition(9, 77).map { it.phrase })
            .containsExactly("the published workflow Answer a question asked in Slack")
    }

    /**
     * The digits are only where to look, never the answer: a snapshot holding
     * 77 as a coordinate, or as some other kind of id, does not name condition 77.
     */
    @Test
    fun `a snapshot that holds the number elsewhere does not name the condition`() {
        `when`(assignments.assignedTo(9)).thenReturn(listOf(workflow))
        `when`(nodes.draftsNamingCondition(9, 77)).thenReturn(listOf(424L))
        `when`(publications.currentInWorkspace(9)).thenReturn(
            listOf(CurrentGraph(424, """{"nodes":[{"key":"a","kind":"ACTION","actionId":77,"x":77}]}""")),
        )

        // Drawn on the canvas and not in what runs: the draft's answer.
        assertThat(references.toCondition(9, 77).map { it.published }).containsExactly(false)
    }

    /**
     * Published before snapshots existed: no publication, a status saying
     * published, and what runs is the draft - so the draft naming it is the
     * published copy naming it, as `AppWorkflowGraphSource.graph` would resolve.
     */
    @Test
    fun `a workflow published before snapshots answers from its draft as the published copy`() {
        `when`(assignments.assignedTo(9)).thenReturn(listOf(workflow))
        `when`(nodes.draftsNamingCondition(9, 77)).thenReturn(listOf(424L))
        `when`(publications.currentInWorkspace(9)).thenReturn(emptyList())

        assertThat(references.toCondition(9, 77).map { it.published }).containsExactly(true)

        `when`(assignments.assignedTo(9)).thenReturn(listOf(workflow.copy(status = WorkflowStatus.DRAFT)))
        assertThat(references.toCondition(9, 77).map { it.published }).containsExactly(false)
    }
}
