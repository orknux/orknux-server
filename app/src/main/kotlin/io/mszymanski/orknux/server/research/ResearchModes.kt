package io.mszymanski.orknux.server.research

import io.mszymanski.orknux.server.action.ActionSubtype
import io.mszymanski.orknux.server.action.ActionType
import io.mszymanski.orknux.server.action.FunctionScope
import io.mszymanski.orknux.server.action.WorkflowAction
import io.mszymanski.orknux.server.action.WorkflowActionRepository
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.embedded.EmbeddedCapabilities
import io.mszymanski.orknux.server.workflow.MappingMode
import io.mszymanski.orknux.server.workflow.NodeKind
import io.mszymanski.orknux.server.workflow.NodeMapping
import io.mszymanski.orknux.server.workflow.Workflow
import io.mszymanski.orknux.server.workflow.WorkflowNode
import io.mszymanski.orknux.server.workflow.WorkflowNodeRepository
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflow
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The three research modes every workspace starts with, and the workflows
 * behind them. Issue #659.
 *
 * **Real workflows, not a feature dressed as one.** Shallow, Medium and Deep
 * are each an ordinary workflow of the workspace holding one action node, which
 * calls `research_run` - see [ResearchCapability] - with the request read off
 * what started the run and the knobs written on the node: how many
 * sub-questions, how broad and how deep, how many results, sources and pages.
 * Those numbers are what make the three different, and somebody who wants a
 * deeper Medium opens the workflow and changes them; nothing here reads the
 * graph back. They are seeded hidden, because a list of what people built is
 * not the place to meet three nobody did, and "Show hidden" on the Workflows
 * list brings them out.
 *
 * **Seeded once.** [ensure] does nothing for a workspace that already has its
 * seeded modes, so a workflow somebody removed is not put back behind their
 * back - the mode says it has nothing to run instead. It is called where a
 * workspace is made and once for every workspace at start-up, which is how
 * the workspaces that predate this get theirs on both engines without a
 * migration having to spell out the rows.
 *
 * **Adopted by name.** A copied or imported workspace arrives with the
 * workflows and the action already in it, under the names written here, and a
 * second set beside them would be a name refused or a duplicate nobody wants.
 * So a name that is already there is used rather than made again.
 */
@Service
class ResearchModes(
    private val modes: ResearchModeRepository,
    private val actions: WorkflowActionRepository,
    private val functions: WorkflowFunctionRepository,
    private val workflows: WorkflowRepository,
    private val assignments: WorkspaceWorkflowRepository,
    private val nodes: WorkflowNodeRepository,
    /** Only to write `research_run`'s row where start-up has not yet; see [functionId]. */
    private val embedded: ObjectProvider<EmbeddedCapabilities>,
) {

    /** Seeds the workspace's three modes, unless it has them already. */
    @Transactional
    fun ensure(workspaceId: Long) {
        if (modes.existsByWorkspaceIdAndKindNot(workspaceId, ResearchModeKind.CUSTOM)) return

        val actionId = requireNotNull(action(workspaceId).id)
        SEEDED.forEachIndexed { at, depth ->
            val workflowId = assignments.findByWorkspaceIdAndWorkflowName(workspaceId, depth.workflowName)
                ?.workflow?.id
                ?: made(workspaceId, actionId, depth)
            val name = depth.modeName.takeUnless { modes.existsByWorkspaceIdAndName(workspaceId, it) }
                ?: "${depth.modeName} (${depth.kind.name.lowercase()})"
            modes.save(ResearchMode(workspaceId = workspaceId, kind = depth.kind, name = name, workflowId = workflowId, position = at))
        }
    }

    /** The workspace's Research action, found by name or made. */
    private fun action(workspaceId: Long): WorkflowAction {
        val functionId = functionId()
        actions.findByWorkspaceIdAndName(workspaceId, ACTION_NAME)
            ?.takeIf { it.subtype == ActionSubtype.FUNCTION && it.functionId == functionId }
            ?.let { return it }
        val name = ACTION_NAME.takeIf { actions.findByWorkspaceIdAndName(workspaceId, it) == null } ?: "$ACTION_NAME (Answer)"
        return actions.findByWorkspaceIdAndName(workspaceId, name) ?: actions.save(
            WorkflowAction(
                workspaceId = workspaceId,
                name = name,
                type = ActionType.EXECUTE,
                subtype = ActionSubtype.FUNCTION,
                functionId = functionId,
            ),
        )
    }

    /**
     * `research_run`'s row. Written at start-up by [EmbeddedCapabilities], which
     * hears that the application is ready at the same moment the seeder does -
     * so where the seeder is first, the row is written here, the same way.
     */
    private fun functionId(): Long {
        functions.findByScopeAndName(FunctionScope.EMBEDDED, ResearchCapability.FUNCTION_NAME)?.id?.let { return it }
        embedded.getObject().register()
        return requireNotNull(functions.findByScopeAndName(FunctionScope.EMBEDDED, ResearchCapability.FUNCTION_NAME)?.id) {
            "${ResearchCapability.FUNCTION_NAME} is not registered"
        }
    }

    private fun made(workspaceId: Long, actionId: Long, depth: Depth): Long {
        val workflow = workflows.save(Workflow(name = depth.workflowName, description = depth.description, hidden = true))
        val workflowId = requireNotNull(workflow.id)
        assignments.save(WorkspaceWorkflow(workspaceId = workspaceId, workflow = workflow, enabled = true))
        nodes.save(
            WorkflowNode(
                workflowId = workflowId,
                nodeKey = "research",
                kind = NodeKind.ACTION,
                name = "Research",
                actionId = actionId,
                // What the research reads back: the run carries the report under this name.
                outputName = OUTPUT,
                positionX = 0.0,
                positionY = 0.0,
                mappings = (
                    // The request, as the research started it.
                    ResearchCapability.REQUEST.map { NodeMapping(name = it, expression = it, mode = MappingMode.REFERENCE) } +
                        // How far to dig: what makes this mode this mode.
                        depth.knobs.map { (name, value) -> NodeMapping(name = name, expression = value.toString(), mode = MappingMode.VALUE) }
                    ).toMutableList(),
            ),
        )
        return workflowId
    }

    /** One of the seeded depths, and the numbers on its node. */
    private data class Depth(
        val kind: ResearchModeKind,
        val modeName: String,
        val workflowName: String,
        val description: String,
        val knobs: List<Pair<String, Int>>,
    )

    companion object {

        /** What the run carries the research's answer under; [ResearchRuns] reads the report out of it. */
        const val OUTPUT = "research"
        const val REPORT = "report"

        /** What the payload carries the brief under, for a custom workflow that would rather hand it to an agent. */
        const val BRIEF = "brief"

        const val ACTION_NAME = "Research"

        private fun knobs(sub: Int, breadth: Int, depth: Int, results: Int, sources: Int, pages: Int) = listOf(
            ResearchCapability.SUB_QUESTIONS to sub,
            ResearchCapability.BREADTH to breadth,
            ResearchCapability.DEPTH to depth,
            ResearchCapability.RESULTS_PER_QUERY to results,
            ResearchCapability.MAX_SOURCES to sources,
            ResearchCapability.MAX_PAGES_PER_SOURCE to pages,
        )

        /*
         * Shallow is a quick look: a few queries, no going deeper. Medium goes
         * one level below the plan. Deep is gpt-researcher's deep mode in
         * proportion: a wider plan, follow-ups three levels down, and room for
         * fifty sources.
         */
        private val SEEDED = listOf(
            Depth(
                ResearchModeKind.SHALLOW, "Shallow", "Research: Shallow",
                "Answer > Research, shallow: a few searches and a short report.",
                knobs(sub = 3, breadth = 0, depth = 1, results = 3, sources = 8, pages = 1),
            ),
            Depth(
                ResearchModeKind.MEDIUM, "Medium", "Research: Medium",
                "Answer > Research, medium: a broader plan and one level of follow-up questions.",
                knobs(sub = 5, breadth = 2, depth = 2, results = 4, sources = 20, pages = 2),
            ),
            Depth(
                ResearchModeKind.DEEP, "Deep", "Research: Deep",
                "Answer > Research, deep: follow-up questions three levels down, sources checked against each other.",
                knobs(sub = 6, breadth = 4, depth = 3, results = 5, sources = 50, pages = 3),
            ),
        )
    }
}
