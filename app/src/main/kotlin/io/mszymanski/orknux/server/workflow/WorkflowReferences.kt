package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.dependency.Dependant
import io.mszymanski.orknux.server.dependency.DependencyKind
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Which of a workspace's workflows name a definition, and in which copy.
 *
 * A definition is deleted from a list that says nothing about workflows, so the
 * only thing between a delete and a workflow that stops working is a question
 * asked here. The question has two halves, and the second is the one that
 * matters:
 *
 * - The **draft** graph names it, and a draft can be redrawn. Somebody who wants
 *   the definition gone can open the canvas and take the node off.
 * - The **published** copy names it, and that copy cannot be edited at all. It
 *   was taken when somebody pressed Publish, nothing cascades into it, and the
 *   only ways it stops naming something are publishing over it or taking the
 *   workflow out of the workspace. Until then it is what a trigger runs.
 *
 * A guard that looked only at draft nodes would let exactly the bad case
 * through: the node taken off the canvas, never republished, and a published run
 * still holding an id that resolves to nothing.
 *
 * Only workflows this workspace has assigned are asked, and that is not
 * tidiness - it is the way out. A workflow removed from the workspace can no
 * longer be edited or published from it, so counting it would be a refusal with
 * nothing anybody could do about it.
 *
 * The published half reads what a run would resolve, which is not quite "what
 * is in the publication table": a workflow that was live before snapshots
 * existed has a status and no snapshot, and what runs for it is the draft. The
 * rule is [AppWorkflowGraphSource]'s - the newest publication, else the draft
 * for a workflow marked published - and is followed here without going through
 * it, because going through it was a workspace lookup, two assignment checks,
 * a workflow lookup, a publication lookup and a whole graph parsed, for every
 * workflow the workspace has, to look at one column. On an agent's settings
 * page that took 49 seconds. Now it is three statements however many workflows
 * there are: the assignments, the draft nodes naming the id, and the newest
 * snapshot of each - and a snapshot is only parsed when its text holds the id
 * at all. Issue #616.
 *
 * What the runnable graph carries is the whole of the published half: it keeps
 * `agentId`, `actionId` and `conditionId` and nothing else, so those three are
 * the only references a published run resolves. A trigger id is not among them,
 * which is why the trigger question below is a draft question and says so.
 *
 * The answer is a [Dependant] rather than a sentence, and that is not a detail.
 * A refusal joins [Dependant.phrase] and reads exactly as it always did; a screen
 * showing where a definition is used draws the same rows as links, which is what
 * #258 asked for. Two audiences, one set — see
 * [ComponentDependants][io.mszymanski.orknux.server.dependency.ComponentDependants],
 * which is what everything now asks.
 */
@Component
class WorkflowReferences(
    private val assignments: WorkspaceWorkflowRepository,
    private val nodes: WorkflowNodeRepository,
    private val publications: WorkflowPublicationRepository,
    private val mapper: ObjectMapper,
) {

    /** Which of the workspace's workflows run this action. */
    fun toAction(workspaceId: Long, actionId: Long): List<Dependant> =
        using(workspaceId, nodes.draftsNamingAction(workspaceId, actionId), "actionId", actionId)

    /** Which of the workspace's workflows instance this agent. */
    fun toAgent(workspaceId: Long, agentId: Long): List<Dependant> =
        using(workspaceId, nodes.draftsNamingAgent(workspaceId, agentId), "agentId", agentId)

    /** Which of the workspace's workflows ask this condition. */
    fun toCondition(workspaceId: Long, conditionId: Long): List<Dependant> =
        using(workspaceId, nodes.draftsNamingCondition(workspaceId, conditionId), "conditionId", conditionId)

    /**
     * Which of the workspace's workflows start from this trigger.
     *
     * The draft alone, because that is where the answer is: publishing does not
     * copy a trigger id, and what an arriving event looks for is the trigger
     * *node* in the drawn graph. A published workflow whose trigger is deleted
     * does not fail in the middle of a run - it stops being reached at all,
     * which is the quieter half of the same bug.
     */
    fun toTrigger(workspaceId: Long, triggerId: Long): List<Dependant> =
        using(workspaceId, nodes.draftsNamingTrigger(workspaceId, triggerId), null, triggerId)

    /**
     * The workflows naming it, said the way a refusal has to say them.
     *
     * The published copy is reported in preference to the draft where one
     * workflow holds both, because it is the harder of the two to be rid of:
     * redrawing the canvas is not enough, and somebody told only "the workflow
     * Answer" would do exactly that and be refused again. That preference is what
     * [Dependant.published] carries, so a screen can mark the row the same way the
     * sentence does.
     *
     * [field] is the snapshot's name for the id, or null where a published copy
     * never carries one - a trigger.
     */
    private fun using(
        workspaceId: Long,
        inDraft: List<Long>,
        field: String?,
        id: Long,
    ): List<Dependant> {
        val drafted = inDraft.toSet()
        val held = if (field == null) emptyMap() else current(workspaceId)
        return assignments.assignedTo(workspaceId)
            .distinctBy { it.id }
            .mapNotNull { workflow ->
                val published = when {
                    field != null && publishedNames(workflow, held, drafted, field, id) -> true
                    workflow.id in drafted -> false
                    else -> return@mapNotNull null
                }
                Dependant(
                    kind = DependencyKind.WORKFLOW,
                    id = workflow.id,
                    name = workflow.name,
                    workspaceId = workspaceId,
                    workspaceName = null,
                    published = published,
                    phrase = if (published) {
                        "the published workflow ${workflow.name}"
                    } else {
                        "the workflow ${workflow.name}"
                    },
                )
            }
    }

    /**
     * Whether a run of the published copy would resolve [id].
     *
     * The newest publication where there is one. Where there is none and the
     * workflow is marked published, it predates snapshots and a run takes the
     * draft - so the draft's answer is the published answer. The copy a run
     * takes leaves out session and folded object nodes, and neither of those
     * ever holds an agent, action or condition, so the draft query answers for
     * it unchanged. A workflow with neither has nothing published.
     *
     * **Every statement asks the workspace's assignments for itself, and that is
     * the point.** The list read a moment ago and the snapshots read now can
     * disagree when another transaction removes a workflow in between - which
     * once made a *condition* delete raise "Workflow 424 is not assigned to
     * workspace 9", naming a workflow the caller had never mentioned. Issue
     * #194. A workflow that has stopped being this workspace's has no rows in
     * the later statements, so it is not a dependant and nothing is raised: a
     * workflow that is no longer this workspace's is not a workflow using this.
     */
    private fun publishedNames(
        workflow: AssignedWorkflow,
        held: Map<Long, String>,
        drafted: Set<Long>,
        field: String,
        id: Long,
    ): Boolean {
        val graph = held[workflow.id]
            ?: return workflow.status == WorkflowStatus.PUBLISHED && workflow.id in drafted
        return names(graph, field, id)
    }

    /** The snapshot each assigned workflow runs, by workflow. */
    private fun current(workspaceId: Long): Map<Long, String> =
        publications.currentInWorkspace(workspaceId).associate { it.workflowId to it.graph }

    /**
     * Whether a snapshot has a node whose [field] is [id].
     *
     * The text is looked at before it is parsed. A snapshot that does not hold
     * the digits anywhere cannot hold the id, and that is most of them; one
     * that does is read as a tree - not as a graph - and its nodes asked, since
     * the digits may as easily be a coordinate or a word in a prompt. The id is
     * read the way [WorkflowSnapshot.read] reads it: a number, or nothing.
     */
    private fun names(graph: String, field: String, id: Long): Boolean {
        if (!graph.contains(id.toString())) return false
        return mapper.readTree(graph).path("nodes").values().any { node ->
            node.path(field).let { it.isNumber && it.asLong() == id }
        }
    }
}
