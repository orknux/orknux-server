package io.mszymanski.orknux.server.research

import io.mszymanski.orknux.server.action.ActionSubtype
import io.mszymanski.orknux.server.action.ActionType
import io.mszymanski.orknux.server.action.FunctionScope
import io.mszymanski.orknux.server.action.WorkflowAction
import io.mszymanski.orknux.server.action.WorkflowActionRepository
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentNodeRunner
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.agent.Chattiness
import io.mszymanski.orknux.server.agent.GrantExemptions
import io.mszymanski.orknux.server.chat.BuiltInTools
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
 * are each an ordinary workflow of the workspace, a chain of five nodes - Plan,
 * Gather, Follow up, Write, Save. **Where a model does the thinking, the node
 * is an agent**: Plan is the workspace's Research planner and Write its
 * Research writer, two ordinary agents on the Agents page whose instructions
 * are what the planning and the writing are told, each node naming the model
 * the research was started with through its `model` parameter. Gather, Follow
 * up and Save are action nodes calling [ResearchCapability]'s steps, because
 * searching, reading pages and saving a file are work, not thinking - though
 * Gather and Follow up still ask the same model for notes on each source, and
 * their nodes read the model off the run under the same name. Each node reads
 * the request off what started the run and what the step before it found off
 * the run, and holds its own knobs: how many sub-questions in Plan's prompt;
 * how many results, sources and pages on Gather; how broad and how deep on
 * Follow up. Those numbers are what make the three different, and somebody
 * who wants a deeper Medium opens the workflow and changes them on the step
 * they belong to; nothing here reads the graph back. They are seeded hidden,
 * because a list of what people built is not the place to meet three nobody
 * did, and "Show hidden" on the Workflows list brings them out.
 *
 * **Seeded once.** [ensure] does not seed a workspace that already has its
 * seeded modes again, so a workflow somebody removed is not put back behind
 * their back - the mode says it has nothing to run instead. It is called where
 * a workspace is made and once for every workspace at start-up, which is how
 * the workspaces that predate this get theirs on both engines without a
 * migration having to spell out the rows.
 *
 * **Upgraded only where untouched.** A mode's workflow was once one node
 * calling `research_run`, and then five action nodes with the model's work
 * hidden inside Plan and Write. Where a seeded mode's workflow is still
 * exactly either of those, as it was seeded, [ensure] draws the current shape
 * in its place with the same numbers. A workflow somebody edited - a knob, a
 * name, a node or a line added - is theirs and is left as it is, still running
 * the functions it calls, which all still work.
 *
 * **Adopted by name.** A copied or imported workspace arrives with the
 * workflows, the actions and the agents already in it, under the names written
 * here, and a second set beside them would be a name refused or a duplicate
 * nobody wants. So a name that is already there is used rather than made again.
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
    private val agents: AgentRepository,
    /** Only to write the research functions' rows where start-up has not yet; see [functionId]. */
    private val embedded: ObjectProvider<EmbeddedCapabilities>,
) : GrantExemptions {

    /**
     * The agents the seeded modes' Plan and Write nodes think with. A memory
     * catalog or a plugin's skills granted to every agent by default would
     * hand them tools they were made without; ticked on their page, it is
     * somebody's decision and stays.
     */
    override fun exempt(workspaceId: Long): Set<Long> =
        modes.findByWorkspaceIdOrderByPositionAscIdAsc(workspaceId)
            .filter { it.kind != ResearchModeKind.CUSTOM }
            .mapNotNull { it.workflowId }
            .flatMap { nodes.findByWorkflowId(it) }
            .filter { it.kind == NodeKind.AGENT && (it.nodeKey == PLAN || it.nodeKey == WRITE) }
            .mapNotNull { it.agentId }
            .toSet()

    private val log = LoggerFactory.getLogger(javaClass)

    /** Seeds the workspace's three modes, unless it has them already - and upgrades their workflows where untouched. */
    @Transactional
    fun ensure(workspaceId: Long) {
        if (modes.existsByWorkspaceIdAndKindNot(workspaceId, ResearchModeKind.CUSTOM)) {
            upgrade(workspaceId)
            return
        }

        val parts = parts(workspaceId)
        SEEDED.forEachIndexed { at, depth ->
            val workflowId = assignments.findByWorkspaceIdAndWorkflowName(workspaceId, depth.workflowName)
                ?.workflow?.id
                ?: made(workspaceId, parts, depth)
            val name = depth.modeName.takeUnless { modes.existsByWorkspaceIdAndName(workspaceId, it) }
                ?: "${depth.modeName} (${depth.kind.name.lowercase()})"
            modes.save(ResearchMode(workspaceId = workspaceId, kind = depth.kind, name = name, workflowId = workflowId, position = at))
        }
    }

    /** Whether a research's workflow plans with an agent node, which writes nothing on the research itself. */
    fun plansWithAnAgent(workflowId: Long): Boolean =
        nodes.findByWorkflowId(workflowId).any { it.nodeKey == PLAN && it.kind == NodeKind.AGENT && it.enabled }

    /**
     * Redraws each seeded mode's workflow that is still a shape it was once
     * seeded as - the one `research_run` node, or the five actions - as the
     * current shape with the same numbers. Anything else is somebody's work
     * and is not looked at twice.
     */
    private fun upgrade(workspaceId: Long) {
        val legacy = functions.findByScopeAndName(FunctionScope.EMBEDDED, ResearchCapability.FUNCTION_NAME)?.id ?: return
        val stale = modes.findByWorkspaceIdOrderByPositionAscIdAsc(workspaceId)
            .filter { it.kind != ResearchModeKind.CUSTOM }
            .mapNotNull { mode ->
                val depth = SEEDED.firstOrNull { it.kind == mode.kind } ?: return@mapNotNull null
                val workflowId = mode.workflowId ?: return@mapNotNull null
                if (!assignments.existsByWorkspaceIdAndWorkflowId(workspaceId, workflowId)) return@mapNotNull null
                (workflowId to depth).takeIf {
                    untouchedSingle(workspaceId, workflowId, depth, legacy) || untouchedActions(workspaceId, workflowId, depth)
                }
            }
        if (stale.isEmpty()) return

        val parts = parts(workspaceId)
        stale.forEach { (workflowId, depth) ->
            edges.deleteAll(edges.findByWorkflowId(workflowId))
            nodes.deleteAll(nodes.findByWorkflowId(workflowId))
            edges.flush()
            nodes.flush()
            lay(workflowId, parts, depth)
            log.info("Research mode {} of workspace {} redrawn with its planner and writer as agent nodes", depth.modeName, workspaceId)
        }
    }

    /** Whether a workflow is still exactly the one node a mode was first seeded with. */
    private fun untouchedSingle(workspaceId: Long, workflowId: Long, depth: Depth, legacyFunctionId: Long): Boolean {
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

    /**
     * Whether a workflow is still exactly the five action nodes a mode was
     * seeded with before Plan and Write were agents: the same nodes, names,
     * descriptions, functions, outputs and parameters, and the same four lines.
     * Where they were drawn on the canvas is not looked at; moving a node
     * changes nothing it does.
     */
    private fun untouchedActions(workspaceId: Long, workflowId: Long, depth: Depth): Boolean {
        val drawn = edges.findByWorkflowId(workflowId)
        val wantedLines = ACTION_STEPS.zipWithNext().map { (from, to) -> from.key to to.key }.toSet()
        if (drawn.size != wantedLines.size || drawn.any { it.branch != null } || drawn.map { it.sourceKey to it.targetKey }.toSet() != wantedLines) {
            return false
        }
        val held = nodes.findByWorkflowId(workflowId).associateBy { it.nodeKey }
        if (held.keys != ACTION_STEPS.map { it.key }.toSet()) return false
        return ACTION_STEPS.all { step ->
            val node = held.getValue(step.key)
            val action = node.actionId?.let { actions.findByIdOrNull(it) } ?: return@all false
            val function = functions.findByScopeAndName(FunctionScope.EMBEDDED, ResearchCapability.functionName(requireNotNull(step.function)))
                ?: return@all false
            node.kind == NodeKind.ACTION &&
                node.name == step.nodeName &&
                node.description == step.description &&
                node.enabled &&
                !node.fallbackEnabled &&
                node.outputName == step.outputName &&
                action.workspaceId == workspaceId &&
                action.subtype == ActionSubtype.FUNCTION &&
                action.functionId == function.id &&
                signature(node.mappings) == signature(mappingsOf(step, ACTION_STEPS, depth.knobs))
        }
    }

    private fun signature(mappings: List<NodeMapping>) =
        mappings.map { listOf(it.name, it.expression, it.mode.name, it.sourceNodeKey) }.sortedBy { it.joinToString("\u0000") }

    /** What the workspace's modes are drawn with: the step actions and the two agents, found by name or made. */
    private fun parts(workspaceId: Long): Parts = Parts(
        actions = STEPS.mapNotNull { step ->
            step.function?.let { step.key to requireNotNull(action(workspaceId, "$ACTION_NAME: ${step.nodeName}", functionId(it)).id) }
        }.toMap(),
        agents = STEPS.mapNotNull { step -> step.agent?.let { step.key to requireNotNull(agent(workspaceId, it).id) } }.toMap(),
    )

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
     * One of the research agents, found by name or made.
     *
     * Made as bare as an agent can be: no model of its own, because each node
     * names the research's; every built-in hidden, no skills, memory,
     * connections or orknux access, and no asking for more - planning and
     * writing are answered from what the prompt holds, and a tool that runs
     * something is a tool these two have no use for. Silent while it works,
     * because what it says beside its answer would be handed on with it. All
     * of which is on its page to change, like any agent's.
     */
    private fun agent(workspaceId: Long, seeded: SeededAgent): Agent {
        agents.findByWorkspaceIdAndName(workspaceId, seeded.name)?.let { return it }
        return agents.save(
            Agent(
                workspaceId = workspaceId,
                name = seeded.name,
                type = AgentType.LLM,
                description = seeded.description,
                systemPrompt = seeded.instructions,
                // The bot icon a hand-made agent starts with; see AgentAPI.
                icon = "bot",
                chattiness = Chattiness.NONE,
                hiddenTools = BuiltInTools.hiddenBy(emptyList()),
                orknuxAccess = false,
                mayAskForTools = false,
                lastModifiedBy = "system",
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

    private fun made(workspaceId: Long, parts: Parts, depth: Depth): Long {
        val workflow = workflows.save(Workflow(name = depth.workflowName, description = depth.description, hidden = true))
        val workflowId = requireNotNull(workflow.id)
        assignments.save(WorkspaceWorkflow(workspaceId = workspaceId, workflow = workflow, enabled = true))
        lay(workflowId, parts, depth)
        return workflowId
    }

    /** The five steps, left to right, each wired to the next. */
    private fun lay(workflowId: Long, parts: Parts, depth: Depth) {
        STEPS.forEachIndexed { at, step ->
            nodes.save(
                WorkflowNode(
                    workflowId = workflowId,
                    nodeKey = step.key,
                    kind = if (step.agent != null) NodeKind.AGENT else NodeKind.ACTION,
                    name = step.nodeName,
                    description = step.description,
                    actionId = parts.actions[step.key],
                    agentId = parts.agents[step.key],
                    outputName = step.outputName,
                    positionX = at * SPACING,
                    positionY = 0.0,
                    mappings = mappingsOf(step, STEPS, depth.knobs).toMutableList(),
                ).also {
                    // Its answer goes on whole, when it is done: a draft handed on in pieces is a report saved in pieces.
                    if (step.agent != null) it.streamOutput = false
                },
            )
        }
        STEPS.zipWithNext().forEach { (from, to) ->
            edges.save(WorkflowEdge(workflowId = workflowId, sourceKey = from.key, targetKey = to.key))
        }
    }

    /** What a step's node passes: the request, what the step before found, its prompt, and its knobs. */
    private fun mappingsOf(step: Step, among: List<Step>, knobs: Knobs): List<NodeMapping> =
        // The request, as the research started it: read off the run's start, so a step's own
        // output can never stand in for it.
        step.request.map {
            NodeMapping(name = it, expression = "${NodeExpressions.TRIGGER}.$it", mode = MappingMode.REFERENCE)
        } +
            // What the step before found.
            step.reads.map { (name, field) ->
                NodeMapping(
                    name = name,
                    expression = field,
                    mode = MappingMode.REFERENCE,
                    sourceNodeKey = among.first { it.outputName == field.substringBefore('.') }.key,
                )
            } +
            // What an agent node asks, with its knobs written into it.
            listOfNotNull(
                step.prompt?.let { NodeMapping(name = AgentNodeRunner.PROMPT, expression = it(knobs), mode = MappingMode.VALUE) },
            ) +
            // How far to dig, on the step it is about: what makes this mode this mode.
            step.knobs(knobs).map { (name, value) ->
                NodeMapping(name = name, expression = value.toString(), mode = MappingMode.VALUE)
            }

    /** The actions and agents a mode's nodes point at, by step key. */
    private class Parts(val actions: Map<String, Long>, val agents: Map<String, Long>)

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

    /** One of the agents the modes' workflows think with. */
    class SeededAgent(val name: String, val description: String, val instructions: String)

    /**
     * One step of a mode's workflow: its node, the function or the agent it
     * runs, what it reads off the request and off the step before, what an
     * agent node is asked, and which of the knobs are its own.
     */
    private class Step(
        val key: String,
        val nodeName: String,
        val description: String,
        val outputName: String,
        val request: List<String>,
        val function: String? = null,
        val agent: SeededAgent? = null,
        val reads: List<Pair<String, String>> = emptyList(),
        val prompt: ((Knobs) -> String)? = null,
        val knobs: (Knobs) -> List<Pair<String, Int>> = { emptyList() },
    )

    companion object {

        /** What the run carries the report under - Save's output; [ResearchRuns] reads the report out of it. */
        const val OUTPUT = "research"
        const val REPORT = ResearchCapability.REPORT

        /** What the payload carries the brief under, for a custom workflow that would rather hand it to an agent. */
        const val BRIEF = "brief"

        /** The documents not to use, by name, as the payload carries them for the writer's prompt. */
        const val DOCUMENTS_EXCLUDE_NAMES = "documentsExcludeNames"

        /** What the one action of a mode's workflow was called before the steps were nodes, and what they are named after. */
        const val ACTION_NAME = "Research"

        /** The step nodes' keys, left to right. */
        const val PLAN = "plan"
        const val GATHER = "gather"
        const val FOLLOW_UP = "followUp"
        const val WRITE = "write"
        const val SAVE = "save"

        /** What the writer agent's answer is carried under, for Save to put the sources after. */
        const val DRAFT = "draft"

        /** The one node a mode's workflow was, and what it read off the run, before the steps were nodes. */
        private const val LEGACY_NODE_KEY = "research"
        private val LEGACY_REQUEST = listOf(
            ResearchCapability.RESEARCH, ResearchCapability.QUESTION, ResearchCapability.LANGUAGE, ResearchCapability.MODEL,
            ResearchCapability.WEBSITES_INCLUDE, ResearchCapability.WEBSITES_EXCLUDE,
            ResearchCapability.DOCUMENTS_INCLUDE, ResearchCapability.DOCUMENTS_EXCLUDE,
        )

        /*
         * How far apart the steps are drawn, left edge to left edge. A card is
         * as wide as its longest line - its description, which the canvas
         * clamps to three lines but measures as one - so the descriptions here
         * are kept under fifty characters, about 300px at the canvas's 11px,
         * and a card 320px wide is the most one of these comes to. Between two
         * cards sits the line's label, the fields it carries in 11px mono -
         * `deepened -> gathered` at the longest, about 150px - and the gap
         * leaves room for it with some 80px clear on either side. Drawn at
         * 320 apart the five overlapped: each card was wider than the step.
         */
        private const val CARD = 320.0
        private const val GAP = 320.0
        private const val SPACING = CARD + GAP

        val PLANNER = SeededAgent(
            name = "Research planner",
            description = "Plans a research: breaks its question into search queries. Thinks with the model each research is started with.",
            instructions = ResearchPipeline.PLAN,
        )

        val WRITER = SeededAgent(
            name = "Research writer",
            description = "Writes a research's report from the notes taken on its sources. Thinks with the model each research is started with.",
            instructions = ResearchPipeline.WRITE + "\n\n" +
                "Write in the register the request names. ACADEMIC: " + ResearchPipeline.register(ResearchLanguage.ACADEMIC) +
                " COLLOQUIAL: " + ResearchPipeline.register(ResearchLanguage.COLLOQUIAL) +
                " FORMAL: " + ResearchPipeline.register(ResearchLanguage.FORMAL) +
                "\n\nNever use or cite the documents the request names as not to be used.",
        )

        /** The model the research was started with, as an agent node names its own. */
        private val MODEL_OF_THE_RESEARCH = ResearchCapability.MODEL

        private val GATHER_STEP = Step(
            GATHER, "Gather", "Searches each query and notes what it reads",
            "gathered",
            function = ResearchCapability.GATHER,
            request = listOf(
                ResearchCapability.RESEARCH, ResearchCapability.QUESTION, ResearchCapability.MODEL,
                ResearchCapability.WEBSITES_INCLUDE, ResearchCapability.WEBSITES_EXCLUDE, ResearchCapability.DOCUMENTS_INCLUDE,
                ResearchCapability.WEB_SEARCH,
            ),
            // The planner's answer, as it wrote it: a JSON array of queries, which Gather reads.
            reads = listOf(ResearchCapability.QUERIES to PLAN),
            knobs = {
                listOf(
                    ResearchCapability.RESULTS_PER_QUERY to it.results,
                    ResearchCapability.MAX_SOURCES to it.sources,
                    ResearchCapability.MAX_PAGES_PER_SOURCE to it.pages,
                )
            },
        )

        private val FOLLOW_UP_STEP = Step(
            FOLLOW_UP, "Follow up", "Searches follow-up questions, to the depth set",
            "deepened",
            function = ResearchCapability.FOLLOW_UP,
            request = listOf(
                ResearchCapability.RESEARCH, ResearchCapability.QUESTION, ResearchCapability.MODEL,
                ResearchCapability.WEBSITES_INCLUDE, ResearchCapability.WEBSITES_EXCLUDE, ResearchCapability.WEB_SEARCH,
            ),
            reads = listOf(ResearchCapability.GATHERED to "gathered"),
            knobs = { listOf(ResearchCapability.BREADTH to it.breadth, ResearchCapability.DEPTH to it.depth) },
        )

        /*
         * The shape a mode is drawn as. The planner and the writer are agent
         * nodes, each told what the pipeline tells the model - the planner the
         * question and how many queries, the writer the question, the register,
         * the documents not to use and every source's notes - and each naming
         * the research's model. Save puts the numbered sources after the
         * writer's draft and is where the report is carried from.
         */
        private val STEPS = listOf(
            Step(
                PLAN, "Plan", "Research planner writes the search queries",
                PLAN,
                agent = PLANNER,
                request = listOf(MODEL_OF_THE_RESEARCH),
                prompt = { "Question: {{${NodeExpressions.TRIGGER}.${ResearchCapability.QUESTION}}}\nWrite exactly ${it.sub} search queries." },
            ),
            GATHER_STEP,
            FOLLOW_UP_STEP,
            Step(
                WRITE, "Write", "Research writer writes the cited report",
                DRAFT,
                agent = WRITER,
                request = listOf(MODEL_OF_THE_RESEARCH),
                prompt = {
                    val trigger = NodeExpressions.TRIGGER
                    "Question: {{$trigger.${ResearchCapability.QUESTION}}}\n\n" +
                        "Register: {{$trigger.${ResearchCapability.LANGUAGE}}}\n" +
                        "Documents not to use: {{$trigger.$DOCUMENTS_EXCLUDE_NAMES}}\n\n" +
                        "Sources, by number:\n\n{{deepened.${ResearchCapability.NOTES}}}"
                },
            ),
            Step(
                SAVE, "Save", "Lists the sources and saves the report",
                OUTPUT,
                function = ResearchCapability.SAVE,
                request = listOf(ResearchCapability.RESEARCH, ResearchCapability.QUESTION, ResearchCapability.OUTPUT_FORMAT),
                reads = listOf(ResearchCapability.REPORT to DRAFT, ResearchCapability.GATHERED to "deepened"),
            ),
        )

        /** The five actions a mode was drawn as before Plan and Write were agents: what [ensure] recognises to redraw. */
        private val ACTION_STEPS = listOf(
            Step(
                PLAN, "Plan", "Breaks the question into search queries.", "plan",
                function = ResearchCapability.PLAN,
                request = listOf(ResearchCapability.RESEARCH, ResearchCapability.QUESTION, ResearchCapability.MODEL),
                knobs = { listOf(ResearchCapability.SUB_QUESTIONS to it.sub) },
            ),
            Step(
                GATHER, GATHER_STEP.nodeName, "Reads the documents, then searches every query and notes what it reads.", GATHER_STEP.outputName,
                function = ResearchCapability.GATHER,
                request = GATHER_STEP.request,
                reads = listOf(ResearchCapability.QUERIES to "plan.${ResearchCapability.QUERIES}"),
                knobs = GATHER_STEP.knobs,
            ),
            Step(
                FOLLOW_UP, FOLLOW_UP_STEP.nodeName, "Asks follow-up questions from what was learnt and searches those, to the depth set.",
                FOLLOW_UP_STEP.outputName,
                function = ResearchCapability.FOLLOW_UP,
                request = FOLLOW_UP_STEP.request,
                reads = FOLLOW_UP_STEP.reads,
                knobs = FOLLOW_UP_STEP.knobs,
            ),
            Step(
                WRITE, "Write", "Writes the report from the notes, citing numbered sources, in the register asked for.", OUTPUT,
                function = ResearchCapability.WRITE,
                request = listOf(
                    ResearchCapability.RESEARCH, ResearchCapability.QUESTION, ResearchCapability.MODEL,
                    ResearchCapability.LANGUAGE, ResearchCapability.DOCUMENTS_EXCLUDE,
                ),
                reads = listOf(ResearchCapability.GATHERED to "deepened"),
            ),
            Step(
                SAVE, "Save", "Saves the report on the Artifacts page as the PDF or document asked for.", "saved",
                function = ResearchCapability.SAVE,
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
