package io.mszymanski.orknux.server.research

import io.mszymanski.orknux.server.action.ActionSubtype
import io.mszymanski.orknux.server.action.ActionType
import io.mszymanski.orknux.server.action.FunctionScope
import io.mszymanski.orknux.server.action.WorkflowAction
import io.mszymanski.orknux.server.action.WorkflowActionRepository
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.embedded.EmbeddedCapabilities
import io.mszymanski.orknux.server.workflow.MappingMode
import io.mszymanski.orknux.server.workflow.NodeExpressions
import io.mszymanski.orknux.server.workflow.NodeKind
import io.mszymanski.orknux.server.workflow.NodeMapping
import io.mszymanski.orknux.server.workflow.Workflow
import io.mszymanski.orknux.server.workflow.WorkflowEdge
import io.mszymanski.orknux.server.workflow.WorkflowEdgeRepository
import io.mszymanski.orknux.server.workflow.WorkflowNode
import io.mszymanski.orknux.server.workflow.WorkflowNodeRepository
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflow
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * The three research modes every workspace starts with, and the workflows
 * behind them. Issue #659.
 *
 * **Real workflows, not a feature dressed as one.** Shallow, Medium and Deep
 * are each an ordinary workflow of the workspace, a chain of five action nodes
 * - Plan, Gather, Follow up, Write, Save - each calling one step of
 * [ResearchCapability]. Each node reads the request off what started the run
 * and what the step before it found off the run, and holds its own knobs:
 * how many sub-questions on Plan; how many results, sources and pages on
 * Gather; how broad and how deep on Follow up. Those numbers are what make the
 * three different, and somebody who wants a deeper Medium opens the workflow
 * and changes them on the step they belong to; nothing here reads the graph
 * back. They are seeded hidden, because a list of what people built is not
 * the place to meet three nobody did, and "Show hidden" on the Workflows list
 * brings them out.
 *
 * **Seeded once.** [ensure] does not seed a workspace that already has its
 * seeded modes again, so a workflow somebody removed is not put back behind
 * their back - the mode says it has nothing to run instead. It is called where
 * a workspace is made and once for every workspace at start-up, which is how
 * the workspaces that predate this get theirs on both engines without a
 * migration having to spell out the rows.
 *
 * **Upgraded only where untouched.** A mode's workflow was once one node
 * calling `research_run` and nothing else. Where a seeded mode's workflow is
 * still exactly that node, as it was seeded, [ensure] draws the five steps in
 * its place with the same numbers. A workflow somebody edited - a knob, a
 * name, a node or a line added - is theirs and is left as it is, still
 * running `research_run`.
 *
 * **Adopted by name.** A copied or imported workspace arrives with the
 * workflows and the actions already in it, under the names written here, and
 * a second set beside them would be a name refused or a duplicate nobody
 * wants. So a name that is already there is used rather than made again.
 */
@Service
class ResearchModes(
    private val modes: ResearchModeRepository,
    private val actions: WorkflowActionRepository,
    private val functions: WorkflowFunctionRepository,
    private val workflows: WorkflowRepository,
    private val assignments: WorkspaceWorkflowRepository,
    private val nodes: WorkflowNodeRepository,
    private val edges: WorkflowEdgeRepository,
    /** Only to write the research functions' rows where start-up has not yet; see [functionId]. */
    private val embedded: ObjectProvider<EmbeddedCapabilities>,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Seeds the workspace's three modes, unless it has them already - and upgrades their workflows where untouched. */
    @Transactional
    fun ensure(workspaceId: Long) {
        if (modes.existsByWorkspaceIdAndKindNot(workspaceId, ResearchModeKind.CUSTOM)) {
            upgrade(workspaceId)
            return
        }

        val stepActions = stepActions(workspaceId)
        SEEDED.forEachIndexed { at, depth ->
            val workflowId = assignments.findByWorkspaceIdAndWorkflowName(workspaceId, depth.workflowName)
                ?.workflow?.id
                ?: made(workspaceId, stepActions, depth)
            val name = depth.modeName.takeUnless { modes.existsByWorkspaceIdAndName(workspaceId, it) }
                ?: "${depth.modeName} (${depth.kind.name.lowercase()})"
            modes.save(ResearchMode(workspaceId = workspaceId, kind = depth.kind, name = name, workflowId = workflowId, position = at))
        }
    }

    /**
     * Redraws each seeded mode's workflow that is still the one node it was
     * first seeded as, as the five steps with the same numbers. Anything else
     * is somebody's work and is not looked at twice.
     */
    private fun upgrade(workspaceId: Long) {
        val legacy = functions.findByScopeAndName(FunctionScope.EMBEDDED, ResearchCapability.FUNCTION_NAME)?.id ?: return
        val stale = modes.findByWorkspaceIdOrderByPositionAscIdAsc(workspaceId)
            .filter { it.kind != ResearchModeKind.CUSTOM }
            .mapNotNull { mode ->
                val depth = SEEDED.firstOrNull { it.kind == mode.kind } ?: return@mapNotNull null
                val workflowId = mode.workflowId ?: return@mapNotNull null
                if (!assignments.existsByWorkspaceIdAndWorkflowId(workspaceId, workflowId)) return@mapNotNull null
                (workflowId to depth).takeIf { untouched(workspaceId, workflowId, depth, legacy) }
            }
        if (stale.isEmpty()) return

        val stepActions = stepActions(workspaceId)
        stale.forEach { (workflowId, depth) ->
            nodes.deleteAll(nodes.findByWorkflowId(workflowId))
            nodes.flush()
            lay(workflowId, stepActions, depth)
            log.info("Research mode {} of workspace {} redrawn as its five steps", depth.modeName, workspaceId)
        }
    }

    /** Whether a workflow is still exactly the one node a mode was first seeded with. */
    private fun untouched(workspaceId: Long, workflowId: Long, depth: Depth, legacyFunctionId: Long): Boolean {
        if (edges.findByWorkflowId(workflowId).isNotEmpty()) return false
        val node = nodes.findByWorkflowId(workflowId).singleOrNull() ?: return false
        val action = node.actionId?.let { actions.findByIdOrNull(it) } ?: return false
        val seeded = LEGACY_REQUEST.map { Triple(it, it, MappingMode.REFERENCE) } +
            depth.knobs.all().map { (name, value) -> Triple(name, value.toString(), MappingMode.VALUE) }
        return node.nodeKey == LEGACY_NODE_KEY &&
            node.kind == NodeKind.ACTION &&
            node.name == ACTION_NAME &&
            node.description == null &&
            node.enabled &&
            node.outputName == OUTPUT &&
            action.workspaceId == workspaceId &&
            action.subtype == ActionSubtype.FUNCTION &&
            action.functionId == legacyFunctionId &&
            node.mappings.map { Triple(it.name, it.expression, it.mode) }.sortedBy { it.first } == seeded.sortedBy { it.first }
    }

    /** The workspace's five step actions, by step, found by name or made. */
    private fun stepActions(workspaceId: Long): Map<String, Long> =
        STEPS.associate { step -> step.key to requireNotNull(action(workspaceId, step.actionName, functionId(step.function)).id) }

    /** An action calling one research function, found by name or made. */
    private fun action(workspaceId: Long, wanted: String, functionId: Long): WorkflowAction {
        actions.findByWorkspaceIdAndName(workspaceId, wanted)
            ?.takeIf { it.subtype == ActionSubtype.FUNCTION && it.functionId == functionId }
            ?.let { return it }
        val name = wanted.takeIf { actions.findByWorkspaceIdAndName(workspaceId, it) == null } ?: "$wanted (Analysis)"
        actions.findByWorkspaceIdAndName(workspaceId, name)
            ?.takeIf { it.subtype == ActionSubtype.FUNCTION && it.functionId == functionId }
            ?.let { return it }
        return actions.save(
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
     * A research function's row. Written at start-up by [EmbeddedCapabilities],
     * which hears that the application is ready at the same moment the seeder
     * does - so where the seeder is first, the rows are written here, the same way.
     */
    private fun functionId(step: String): Long {
        val name = ResearchCapability.functionName(step)
        functions.findByScopeAndName(FunctionScope.EMBEDDED, name)?.id?.let { return it }
        embedded.getObject().register()
        return requireNotNull(functions.findByScopeAndName(FunctionScope.EMBEDDED, name)?.id) { "$name is not registered" }
    }

    private fun made(workspaceId: Long, stepActions: Map<String, Long>, depth: Depth): Long {
        val workflow = workflows.save(Workflow(name = depth.workflowName, description = depth.description, hidden = true))
        val workflowId = requireNotNull(workflow.id)
        assignments.save(WorkspaceWorkflow(workspaceId = workspaceId, workflow = workflow, enabled = true))
        lay(workflowId, stepActions, depth)
        return workflowId
    }

    /** The five steps, left to right, each wired to the next. */
    private fun lay(workflowId: Long, stepActions: Map<String, Long>, depth: Depth) {
        STEPS.forEachIndexed { at, step ->
            nodes.save(
                WorkflowNode(
                    workflowId = workflowId,
                    nodeKey = step.key,
                    kind = NodeKind.ACTION,
                    name = step.nodeName,
                    description = step.description,
                    actionId = stepActions.getValue(step.key),
                    outputName = step.outputName,
                    positionX = at * SPACING,
                    positionY = 0.0,
                    mappings = (
                        // The request, as the research started it: read off the run's start, so a step's own
                        // output - Write's is called research - can never stand in for it.
                        step.request.map {
                            NodeMapping(name = it, expression = "${NodeExpressions.TRIGGER}.$it", mode = MappingMode.REFERENCE)
                        } +
                            // What the step before found.
                            step.reads.map { (name, field) ->
                                NodeMapping(
                                    name = name,
                                    expression = field,
                                    mode = MappingMode.REFERENCE,
                                    sourceNodeKey = STEPS.first { it.outputName == field.substringBefore('.') }.key,
                                )
                            } +
                            // How far to dig, on the step it is about: what makes this mode this mode.
                            step.knobs(depth.knobs).map { (name, value) ->
                                NodeMapping(name = name, expression = value.toString(), mode = MappingMode.VALUE)
                            }
                        ).toMutableList(),
                ),
            )
        }
        STEPS.zipWithNext().forEach { (from, to) ->
            edges.save(WorkflowEdge(workflowId = workflowId, sourceKey = from.key, targetKey = to.key))
        }
    }

    /** The numbers a seeded mode is, as [ResearchKnobs] names them. */
    private data class Knobs(val sub: Int, val breadth: Int, val depth: Int, val results: Int, val sources: Int, val pages: Int) {
        fun all() = listOf(
            ResearchCapability.SUB_QUESTIONS to sub,
            ResearchCapability.BREADTH to breadth,
            ResearchCapability.DEPTH to depth,
            ResearchCapability.RESULTS_PER_QUERY to results,
            ResearchCapability.MAX_SOURCES to sources,
            ResearchCapability.MAX_PAGES_PER_SOURCE to pages,
        )
    }

    /** One of the seeded depths, and its numbers. */
    private data class Depth(
        val kind: ResearchModeKind,
        val modeName: String,
        val workflowName: String,
        val description: String,
        val knobs: Knobs,
    )

    /**
     * One step of a mode's workflow: its node, the action it calls, what it
     * reads off the request and off the step before, and which of the knobs
     * are its own.
     */
    private class Step(
        val key: String,
        val nodeName: String,
        val description: String,
        val function: String,
        val outputName: String,
        val request: List<String>,
        val reads: List<Pair<String, String>> = emptyList(),
        val knobs: (Knobs) -> List<Pair<String, Int>> = { emptyList() },
    ) {
        val actionName get() = "$ACTION_NAME: $nodeName"
    }

    companion object {

        /** What the run carries the report under - Write's output; [ResearchRuns] reads the report out of it. */
        const val OUTPUT = "research"
        const val REPORT = ResearchCapability.REPORT

        /** What the payload carries the brief under, for a custom workflow that would rather hand it to an agent. */
        const val BRIEF = "brief"

        /** What the one action of a mode's workflow was called before the steps were nodes, and what they are named after. */
        const val ACTION_NAME = "Research"

        /** The step nodes' keys, left to right. */
        const val PLAN = "plan"
        const val GATHER = "gather"
        const val FOLLOW_UP = "followUp"
        const val WRITE = "write"
        const val SAVE = "save"

        /** The one node a mode's workflow was, and what it read off the run, before the steps were nodes. */
        private const val LEGACY_NODE_KEY = "research"
        private val LEGACY_REQUEST = listOf(
            ResearchCapability.RESEARCH, ResearchCapability.QUESTION, ResearchCapability.LANGUAGE, ResearchCapability.MODEL,
            ResearchCapability.WEBSITES_INCLUDE, ResearchCapability.WEBSITES_EXCLUDE,
            ResearchCapability.DOCUMENTS_INCLUDE, ResearchCapability.DOCUMENTS_EXCLUDE,
        )

        /** How far apart the steps are drawn: a node's width and room for the line between. */
        private const val SPACING = 320.0

        private val STEPS = listOf(
            Step(
                PLAN, "Plan", "Breaks the question into search queries.",
                ResearchCapability.PLAN, "plan",
                request = listOf(ResearchCapability.RESEARCH, ResearchCapability.QUESTION, ResearchCapability.MODEL),
                knobs = { listOf(ResearchCapability.SUB_QUESTIONS to it.sub) },
            ),
            Step(
                GATHER, "Gather", "Reads the documents, then searches every query and notes what it reads.",
                ResearchCapability.GATHER, "gathered",
                request = listOf(
                    ResearchCapability.RESEARCH, ResearchCapability.QUESTION, ResearchCapability.MODEL,
                    ResearchCapability.WEBSITES_INCLUDE, ResearchCapability.WEBSITES_EXCLUDE, ResearchCapability.DOCUMENTS_INCLUDE,
                    ResearchCapability.WEB_SEARCH,
                ),
                reads = listOf(ResearchCapability.QUERIES to "plan.${ResearchCapability.QUERIES}"),
                knobs = {
                    listOf(
                        ResearchCapability.RESULTS_PER_QUERY to it.results,
                        ResearchCapability.MAX_SOURCES to it.sources,
                        ResearchCapability.MAX_PAGES_PER_SOURCE to it.pages,
                    )
                },
            ),
            Step(
                FOLLOW_UP, "Follow up", "Asks follow-up questions from what was learnt and searches those, to the depth set.",
                ResearchCapability.FOLLOW_UP, "deepened",
                request = listOf(
                    ResearchCapability.RESEARCH, ResearchCapability.QUESTION, ResearchCapability.MODEL,
                    ResearchCapability.WEBSITES_INCLUDE, ResearchCapability.WEBSITES_EXCLUDE, ResearchCapability.WEB_SEARCH,
                ),
                reads = listOf(ResearchCapability.GATHERED to "gathered"),
                knobs = { listOf(ResearchCapability.BREADTH to it.breadth, ResearchCapability.DEPTH to it.depth) },
            ),
            Step(
                WRITE, "Write", "Writes the report from the notes, citing numbered sources, in the register asked for.",
                ResearchCapability.WRITE, OUTPUT,
                request = listOf(
                    ResearchCapability.RESEARCH, ResearchCapability.QUESTION, ResearchCapability.MODEL,
                    ResearchCapability.LANGUAGE, ResearchCapability.DOCUMENTS_EXCLUDE,
                ),
                reads = listOf(ResearchCapability.GATHERED to "deepened"),
            ),
            Step(
                SAVE, "Save", "Saves the report on the Artifacts page as the PDF or document asked for.",
                ResearchCapability.SAVE, "saved",
                request = listOf(ResearchCapability.RESEARCH, ResearchCapability.QUESTION, ResearchCapability.OUTPUT_FORMAT),
                reads = listOf(ResearchCapability.REPORT to "$OUTPUT.${ResearchCapability.REPORT}"),
            ),
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
                "Explore > Research, shallow: a few searches and a short report.",
                Knobs(sub = 3, breadth = 0, depth = 1, results = 3, sources = 8, pages = 1),
            ),
            Depth(
                ResearchModeKind.MEDIUM, "Medium", "Research: Medium",
                "Explore > Research, medium: a broader plan and one level of follow-up questions.",
                Knobs(sub = 5, breadth = 2, depth = 2, results = 4, sources = 20, pages = 2),
            ),
            Depth(
                ResearchModeKind.DEEP, "Deep", "Research: Deep",
                "Explore > Research, deep: follow-up questions three levels down, sources checked against each other.",
                Knobs(sub = 6, breadth = 4, depth = 3, results = 5, sources = 50, pages = 3),
            ),
        )
    }
}
