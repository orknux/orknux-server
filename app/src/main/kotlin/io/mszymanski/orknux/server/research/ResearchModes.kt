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
import io.mszymanski.orknux.server.condition.ConditionCheck
import io.mszymanski.orknux.server.condition.ConditionType
import io.mszymanski.orknux.server.condition.VALUE_SUBJECT
import io.mszymanski.orknux.server.condition.WorkflowCondition
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.embedded.EmbeddedCapabilities
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.workflow.EdgeBranch
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
 * are each an ordinary workflow of the workspace, a chain of five nodes - Plan
 * research, Gather sources, Deepen findings, Write report, Save report, called
 * Plan, Gather, Follow up, Write and Save below and by their keys. **Where a model does the thinking, the node
 * is an agent**: Plan is the workspace's Research planner and Write its
 * Research writer, two ordinary agents on the Agents page whose instructions
 * are what the planning and the writing are told. Each node is answered by the
 * agent the research was started with for it through its `agent` parameter -
 * those two unless somebody picked others - thinking with that agent's own
 * model, and lends it web search through its `webSearch` where the research
 * searches the web (#678). Gather, Follow
 * up and Save are action nodes calling [ResearchCapability]'s steps, because
 * searching, reading pages and saving a file are work, not thinking - though
 * Gather and Follow up still ask a model for notes on each source - the
 * Writer's, which the run carries as `model`. Each node reads
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
 * calling `research_run`, then five action nodes with the model's work hidden
 * inside Plan and Write, then the five with Plan and Write as agents, and now
 * those with a review. A seeded mode's workflow that is still any of those -
 * read for what it does, so names, descriptions, retries, card positions and
 * a parameter a later release gave a step do not count - is drawn again in
 * the current shape at start-up with its own numbers, whichever release it
 * stopped at. A workflow somebody really changed - a node or a line added or
 * removed, another action or agent, a prompt of their own, a knob read off
 * something rather than written - is theirs and is left as it is, still
 * running the functions it calls, which all still work.
 *
 * **Tried again by default.** Every step is drawn with a retry policy - as
 * many tries and as long a wait as [Tunable.RESEARCH_STEP_RETRIES] and
 * [Tunable.RESEARCH_STEP_RETRY_SECONDS] say when it is drawn - because a model
 * that answers with nothing once answers the same request in full a moment
 * later, and a research of many minutes should not be lost to it. The policy
 * spends attempts only on a failure that says it could come out differently:
 * a model that did not answer, not a research that read nothing. It is the
 * node's, like any knob, and is not part of what makes a workflow look edited;
 * a workflow still as it was drawn but with no retry setting on any step - one
 * made before there were any - is given it.
 *
 * **Reviewed where asked.** Between Write and Save sits a condition, Review
 * asked for?, reading the `review` the research was started with: yes leads
 * to Review report, which has the Research reviewer read the draft with the
 * model chosen for it and the Research writer revise it where asked, and on to
 * Save; no leads straight to Save, and Review report is skipped. Save saves
 * what the review left where there was a review and Write's draft where there
 * was not.
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
    /** The question whether to review, which each mode's workflow owns. */
    private val conditions: WorkflowConditionRepository,
    /** Only to write the research functions' rows where start-up has not yet; see [functionId]. */
    private val embedded: ObjectProvider<EmbeddedCapabilities>,
    private val tuning: Tuning,
    /** What the workspace's research last ran with, for the seeded agents' models; see [givenModels]. */
    private val researches: ResearchRepository,
    private val chatModels: io.mszymanski.orknux.server.agent.WorkspaceChatModels,
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
            .toSet() +
            // And the reviewer, whom no node names: the Review step reads its instructions.
            setOfNotNull(agents.findByWorkspaceIdAndName(workspaceId, REVIEWER.name)?.id)

    private val log = LoggerFactory.getLogger(javaClass)

    /** Seeds the workspace's three modes, unless it has them already - and upgrades their workflows where untouched. */
    @Transactional
    fun ensure(workspaceId: Long) {
        if (modes.existsByWorkspaceIdAndKindNot(workspaceId, ResearchModeKind.CUSTOM)) {
            upgrade(workspaceId)
            givenModels(workspaceId)
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

    /** Whether a research's workflow has a Review step to review with: a mode somebody drew their own way may not. */
    fun reviews(workflowId: Long): Boolean =
        nodes.findByWorkflowId(workflowId).any { it.nodeKey == REVIEW && it.kind == NodeKind.ACTION && it.enabled }

    /** Whether a research's workflow plans with an agent node, which writes nothing on the research itself. */
    fun plansWithAnAgent(workflowId: Long): Boolean =
        nodes.findByWorkflowId(workflowId).any { it.nodeKey == PLAN && it.kind == NodeKind.AGENT && it.enabled }

    /**
     * Brings each seeded mode's workflow that is still a shape it was once
     * seeded as up to the current one, and leaves anything else alone.
     *
     * One upgrade, not one per release: [recognise] reads a workflow as what
     * it does, against every shape this product has drawn, so a mode that
     * missed one step of the way - Shallow in a workspace whose start-up never
     * ran the rename, the pictures, the retries or the review - is not left
     * behind by every step after it. A workflow that is a seeded shape but not
     * the current one is drawn again as the current one with its own numbers;
     * one that already is the current shape is laid out again and given its
     * retries where it has none. The one node a mode was first seeded as is
     * recognised the old way, because its numbers are all it ever had.
     */
    private fun upgrade(workspaceId: Long) {
        val legacy = functions.findByScopeAndName(FunctionScope.EMBEDDED, ResearchCapability.FUNCTION_NAME)?.id
        val seeded = modes.findByWorkspaceIdOrderByPositionAscIdAsc(workspaceId)
            .filter { it.kind != ResearchModeKind.CUSTOM }
            .mapNotNull { mode ->
                val depth = SEEDED.firstOrNull { it.kind == mode.kind } ?: return@mapNotNull null
                val workflowId = mode.workflowId ?: return@mapNotNull null
                if (!assignments.existsByWorkspaceIdAndWorkflowId(workspaceId, workflowId)) return@mapNotNull null
                workflowId to depth
            }
        if (seeded.isEmpty()) return

        var parts: Parts? = null
        seeded.forEach { (workflowId, depth) ->
            val read = recognise(workspaceId, workflowId)
            val was = when {
                read != null && !read.current -> read
                read == null && legacy != null && untouchedSingle(workspaceId, workflowId, depth, legacy) ->
                    Seeded(depth.knobs, emptyMap(), current = false)
                else -> null
            }
            if (was != null) {
                redraw(workspaceId, workflowId, parts ?: parts(workspaceId).also { parts = it }, was)
                log.info("Research mode {} of workspace {} drawn again in the current shape, with its own numbers", depth.modeName, workspaceId)
            }
            if (relaid(workspaceId, workflowId, depth)) {
                log.info("Research mode {} of workspace {} laid out again", depth.modeName, workspaceId)
            }
            if (retried(workspaceId, workflowId, depth)) {
                log.info("Research mode {} of workspace {} given its steps' retry policy", depth.modeName, workspaceId)
            }
        }
    }

    /**
     * Gives the Research planner, writer and reviewer the model this
     * workspace's research last ran with, where they have none. Issue #678.
     *
     * Until then they had no model of their own: each research named one, and
     * its Plan and Write nodes thought with that. A research now runs with its
     * agents' own models, so agents seeded bare would leave the form's default
     * choice unable to start - and the model somebody last picked is the one
     * they would have picked again. Only while the newest research is one
     * started before the agents were picked: after that, an agent with no
     * model is somebody's decision, and the form says so rather than this
     * choosing for them. A model this workspace can no longer use is not given.
     */
    private fun givenModels(workspaceId: Long) {
        val latest = researches.findFirstByWorkspaceIdOrderByStartedAtDescIdDesc(workspaceId) ?: return
        if (latest.writerAgentId != null || latest.plannerAgentId != null) return
        val reviewing = researches.findFirstByWorkspaceIdAndReviewerModelIdIsNotNullOrderByStartedAtDescIdDesc(workspaceId)
        listOf(PLANNER to latest.modelId, WRITER to latest.modelId, REVIEWER to (reviewing?.reviewerModelId ?: latest.modelId))
            .forEach { (seeded, modelId) ->
                val agent = agents.findByWorkspaceIdAndName(workspaceId, seeded.name) ?: return@forEach
                if (agent.modelId != null || modelId == null || chatModels.usable(workspaceId, modelId) == null) return@forEach
                agent.modelId = modelId
                agent.lastModifiedBy = "system"
                agents.save(agent)
                log.info("{} of workspace {} given model {}, which its research last ran with", seeded.name, workspaceId, modelId)
            }
    }

    /**
     * Where a step is drawn: left to right, with Save a row lower. Save also
     * reads Follow up's sources, and that second line ran straight through the
     * Write card with its label on top of it; dropped a row, the line passes
     * under Write and its label sits clear of every card.
     */
    private fun xOf(key: String) = LAYOUT.indexOf(key) * SPACING
    private fun yOf(key: String) = if (key == SAVE) DROP else 0.0

    /**
     * Draws a seeded mode's workflow again in the current shape, with the
     * numbers it was read with.
     *
     * What the nodes were is kept where it is somebody's and changes nothing
     * the work is: a name or a description that is not one this product gave
     * the step, and each step's retry settings where any step had any - where
     * none did, every step takes the policy the tunables say, as [lay] draws
     * it. The question whether to review keeps the condition it asked with.
     */
    private fun redraw(workspaceId: Long, workflowId: Long, parts: Parts, was: Seeded) {
        val retried = was.held.values.any(::hasRetrySettings)
        edges.deleteAll(edges.findByWorkflowId(workflowId))
        nodes.deleteAll(nodes.findByWorkflowId(workflowId))
        edges.flush()
        nodes.flush()
        lay(workspaceId, workflowId, parts, was.knobs, was.held[REVIEWING]?.conditionId) { node ->
            val old = was.held[node.nodeKey] ?: return@lay
            if (old.name !in OUR_NAMES[node.nodeKey].orEmpty()) node.name = old.name
            if (old.description !in OUR_DESCRIPTIONS[node.nodeKey].orEmpty()) node.description = old.description
            if (retried) {
                node.retryAttempts = old.retryAttempts
                node.retryBackoffSeconds = old.retryBackoffSeconds
                node.retryMultiplier = old.retryMultiplier
                node.retryMaxWaitSeconds = old.retryMaxWaitSeconds
                node.retryJitter = old.retryJitter
                node.retryBudgetSeconds = old.retryBudgetSeconds
            }
        }
    }

    /**
     * The question whether to review, as a node: a condition this workflow
     * owns, asking whether the run's `review` is true. Read as a written value
     * with the field in it rather than as a reference, so a run that carries no
     * `review` - one started before there was a review - answers no rather
     * than failing to be answered. [conditionId] is the one it already asked
     * with, where it is drawn again.
     */
    private fun gateNode(workspaceId: Long, workflowId: Long, conditionId: Long? = null): WorkflowNode {
        val condition = conditionId ?: conditions.save(
            WorkflowCondition(
                workspaceId = workspaceId,
                workflowId = workflowId,
                name = GATE_CONDITION,
                type = ConditionType.VALUE,
                check = ConditionCheck.IN_LIST,
                values = mutableListOf("true"),
            ),
        ).id
        return WorkflowNode(
            workflowId = workflowId,
            nodeKey = REVIEWING,
            kind = NodeKind.CONDITION,
            name = GATE_NAME,
            description = GATE_DESCRIPTION,
            conditionId = condition,
            positionX = xOf(REVIEWING),
            positionY = yOf(REVIEWING),
            mappings = GATE_MAPPINGS().toMutableList(),
        )
    }

    /** Whether the question whether to review still asks what it was drawn to ask, whatever it is called. */
    private fun untouchedGate(workspaceId: Long, node: WorkflowNode): Boolean {
        val condition = node.conditionId?.let { conditions.findByIdOrNull(it) } ?: return false
        return node.kind == NodeKind.CONDITION &&
            node.enabled &&
            condition.workspaceId == workspaceId &&
            condition.type == ConditionType.VALUE &&
            condition.check == ConditionCheck.IN_LIST &&
            !condition.negate &&
            condition.values.toList() == listOf("true") &&
            signature(node.mappings) == signature(GATE_MAPPINGS())
    }

    /** Puts a mode drawn in the current shape back where [lay] would draw it, moving nothing else. */
    private fun relaid(workspaceId: Long, workflowId: Long, depth: Depth): Boolean {
        if (!untouchedCurrent(workspaceId, workflowId, depth)) return false
        val held = nodes.findByWorkflowId(workflowId).associateBy { it.nodeKey }
        var moved = false
        LAYOUT.forEach { key ->
            val node = held.getValue(key)
            if (node.positionX != xOf(key) || node.positionY != yOf(key)) {
                node.positionX = xOf(key)
                node.positionY = yOf(key)
                nodes.save(node)
                moved = true
            }
        }
        return moved
    }

    /**
     * Gives a mode drawn in the current shape, and with no retry setting on any
     * of its steps, the policy [lay] draws them with now. One whose steps carry
     * any retry setting at all - somebody's own numbers, or a policy cut back
     * to one attempt with its wait left behind - is not touched.
     */
    private fun retried(workspaceId: Long, workflowId: Long, depth: Depth): Boolean {
        if (tuning[Tunable.RESEARCH_STEP_RETRIES] < 1) return false
        if (!untouchedCurrent(workspaceId, workflowId, depth)) return false
        val held = nodes.findByWorkflowId(workflowId)
        if (held.any(::hasRetrySettings)) return false
        held.forEach { node ->
            retrying(node)
            nodes.save(node)
        }
        return true
    }

    /** Writes the installation's research retry policy on a step's node; none where it says no retries. */
    private fun retrying(node: WorkflowNode) {
        val retries = tuning[Tunable.RESEARCH_STEP_RETRIES]
        if (retries < 1) return
        // Attempts in all, as the node counts them: the first go and the retries after it.
        node.retryAttempts = retries + 1
        node.retryBackoffSeconds = tuning[Tunable.RESEARCH_STEP_RETRY_SECONDS]
    }

    private fun hasRetrySettings(node: WorkflowNode): Boolean =
        listOf(
            node.retryAttempts, node.retryBackoffSeconds, node.retryMultiplier,
            node.retryMaxWaitSeconds, node.retryJitter, node.retryBudgetSeconds,
        ).any { it != null }

    /**
     * Whether a workflow is the current shape exactly as [lay] draws it for
     * [depth] - its steps under their current names, with the mode's own
     * numbers - wherever its cards sit and whatever its retry settings say:
     * what may be laid out again and given its retries without anybody's work
     * being moved.
     */
    private fun untouchedCurrent(workspaceId: Long, workflowId: Long, depth: Depth): Boolean {
        val read = recognise(workspaceId, workflowId) ?: return false
        if (!read.current || read.knobs != depth.knobs) return false
        return CURRENT.all { step -> read.held.getValue(step.key).let { it.name == step.nodeName && it.description == step.description } } &&
            read.held.getValue(REVIEWING).let { it.name == GATE_NAME && it.description == GATE_DESCRIPTION }
    }

    /**
     * A workflow read as one of the shapes a mode has been seeded as, or null
     * where it is somebody's own.
     *
     * Read for what it does, not for how it looks: the same nodes under the
     * same keys, each of the kind it was drawn as and calling the step's
     * function or thinking with the research agent it was drawn with, the same
     * lines, and on every node the parameters that shape drew - its prompt
     * word for word but for the number in it. Not looked at: names and
     * descriptions, where the cards sit, retry settings, a parameter a later
     * version gave a step ([LATER]), and the empty boxes and the agent's own
     * briefing the editor writes into an agent node when it is opened. Every
     * knob has to be a written whole number: one read off something else is a
     * decision somebody made, and a node added or removed, a line drawn
     * elsewhere, another action or agent, a step switched off or a prompt of
     * their own makes the workflow theirs.
     */
    private fun recognise(workspaceId: Long, workflowId: Long): Seeded? {
        val all = nodes.findByWorkflowId(workflowId)
        val held = all.associateBy { it.nodeKey }
        if (held.size != all.size) return null
        val drawn = edges.findByWorkflowId(workflowId).map { Triple(it.sourceKey, it.targetKey, it.branch) }
        val lines = drawn.toSet().takeIf { it.size == drawn.size } ?: return null
        // The five actions and the five with agents share their keys and lines: told apart by their nodes.
        return SHAPES.filter { it.keys == held.keys && it.lines == lines }.firstNotNullOfOrNull { shape -> asShape(workspaceId, held, shape) }
    }

    /** The nodes read as [shape]'s steps, their knobs gathered into the mode's numbers; null where one is not. */
    private fun asShape(workspaceId: Long, held: Map<String, WorkflowNode>, shape: Shape): Seeded? {
        if (shape.gated && !untouchedGate(workspaceId, held.getValue(REVIEWING))) return null
        val knobs = mutableMapOf<String, Int>()
        var behind = false
        shape.steps.forEach { step ->
            val read = read(workspaceId, held.getValue(step.key), step, shape.steps) ?: return null
            read.knobs.forEach { (name, value) -> if (knobs.put(name, value) != null) return null }
            behind = behind || read.behind
        }
        val numbers = Knobs.of(knobs) ?: return null
        return Seeded(numbers, held, current = shape === SHAPES.last() && !behind)
    }

    /** One node read as [step] of a seeded shape: the knobs it holds, and whether it lacks a parameter a later version gave it. */
    private fun read(workspaceId: Long, node: WorkflowNode, step: Step, among: List<Step>): StepRead? {
        if (!node.enabled || node.fallbackEnabled || node.outputName != step.outputName) return null
        val agent = if (step.agent != null) {
            if (node.kind != NodeKind.AGENT) return null
            node.agentId?.let { agents.findByIdOrNull(it) }?.takeIf { it.workspaceId == workspaceId && it.name == step.agent.name }
                ?: return null
        } else {
            if (node.kind != NodeKind.ACTION) return null
            val action = node.actionId?.let { actions.findByIdOrNull(it) } ?: return null
            val function = functions.findByScopeAndName(FunctionScope.EMBEDDED, ResearchCapability.functionName(requireNotNull(step.function)))
                ?: return null
            if (action.workspaceId != workspaceId || action.subtype != ActionSubtype.FUNCTION || action.functionId != function.id) return null
            null
        }

        // The knobs written on the node, each a whole number.
        val knobNames = step.knobs(MARKS).map { it.first }.toSet()
        val found = mutableMapOf<String, Int>()
        node.mappings.filter { it.name in knobNames }.forEach { mapping ->
            val value = mapping.expression.trim().toIntOrNull()?.takeIf { mapping.mode == MappingMode.VALUE } ?: return null
            if (found.put(mapping.name, value) != null) return null
        }
        if (found.keys != knobNames) return null

        val rest = node.mappings.filter { it.name !in knobNames && !(agent != null && editorsOwn(it, agent.systemPrompt, step)) }
        // An agent step's prompt, word for word but for the knob written into it.
        val prompted = step.prompt?.let { prompt ->
            val asked = rest.singleOrNull { it.name == AgentNodeRunner.PROMPT }?.takeIf { it.mode == MappingMode.VALUE } ?: return null
            val inPrompt = knobsIn(prompt, asked.expression) ?: return null
            inPrompt.forEach { (name, value) -> if (found.put(name, value) != null) return null }
            true
        } == true
        val compared = if (prompted) rest.filter { it.name != AgentNodeRunner.PROMPT } else rest
        val expected = mappingsOf(step, among, MARKS).filter { it.name !in knobNames && !(prompted && it.name == AgentNodeRunner.PROMPT) }
        val behind = when (signature(compared)) {
            signature(expected) -> false
            signature(expected.filter { it.name !in LATER[step.key].orEmpty() }) -> true
            else -> return null
        }
        return StepRead(found, behind)
    }

    /**
     * What the workflow editor writes into an agent node when it is opened,
     * which changes nothing it does: an empty skills box, the agent's own
     * briefing - or an empty box - for its system prompt, and an empty model
     * box, which leaves the agent its own model - the one Plan and Write have
     * thought with since #678, when they stopped naming the research's.
     */
    private fun editorsOwn(mapping: NodeMapping, briefing: String?, step: Step): Boolean {
        if (mapping.mode != MappingMode.VALUE) return false
        val written = mapping.expression.trim()
        return when (mapping.name) {
            AgentNodeRunner.SKILL_IDS, AgentNodeRunner.MODEL -> written.isEmpty()
            AgentNodeRunner.SYSTEM_PROMPT -> written.isEmpty() || written == briefing?.trim() || written == step.agent?.instructions?.trim()
            else -> false
        }
    }

    /**
     * The knobs a prompt was drawn with, read back: the prompt [prompt] draws
     * with every knob a mark of its own, each mark taken for a whole number.
     * Null where the prompt says anything else, or one knob twice over differently.
     */
    private fun knobsIn(prompt: (Knobs) -> String, written: String): Map<String, Int>? {
        val drawn = prompt(MARKS)
        val marks = MARKS.all().associate { (name, mark) -> mark.toString() to name }
        val named = mutableListOf<String>()
        val pattern = StringBuilder()
        var at = 0
        Regex(marks.keys.joinToString("|")).findAll(drawn).forEach { mark ->
            pattern.append(Regex.escape(drawn.substring(at, mark.range.first))).append("(\\d+)")
            named += marks.getValue(mark.value)
            at = mark.range.last + 1
        }
        pattern.append(Regex.escape(drawn.substring(at)))
        val found = Regex(pattern.toString()).matchEntire(written) ?: return null
        val knobs = mutableMapOf<String, Int>()
        named.forEachIndexed { index, name ->
            val value = found.groupValues[index + 1].toIntOrNull() ?: return null
            if (knobs.put(name, value)?.let { it != value } == true) return null
        }
        return knobs
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

    private fun signature(mappings: List<NodeMapping>) =
        mappings.map { listOf(it.name, it.expression, it.mode.name, it.sourceNodeKey) }.sortedBy { it.joinToString("\u0000") }

    /** What the workspace's modes are drawn with: the step actions and the two agents, found by name or made. */
    private fun parts(workspaceId: Long): Parts = Parts(
        actions = CURRENT.mapNotNull { step ->
            step.function?.let {
                step.key to requireNotNull(
                    action(workspaceId, requireNotNull(step.actionName), FORMER[step.key]?.actionName, functionId(it)).id,
                )
            }
        }.toMap(),
        agents = CURRENT.mapNotNull { step -> step.agent?.let { step.key to requireNotNull(agent(workspaceId, it).id) } }.toMap(),
    ).also {
        // The reviewer no node names, whose instructions the Review step reads.
        agent(workspaceId, REVIEWER)
    }

    /**
     * An action calling one research function, found by name or made.
     *
     * Found under its name, or under that name with [FALLBACK] after it - the
     * name it was made with where somebody's own action had the plain one.
     * Then under the name it had before the steps were renamed, with or
     * without [FALLBACK], and given its current name where that is free: a
     * workspace seeded before then has its actions under the former names, and
     * missing them would make a second set beside the first.
     */
    private fun action(workspaceId: Long, wanted: String, former: String?, functionId: Long): WorkflowAction {
        fun calling(name: String) = actions.findByWorkspaceIdAndName(workspaceId, name)
            ?.takeIf { it.subtype == ActionSubtype.FUNCTION && it.functionId == functionId }
        calling(wanted)?.let { return it }
        calling("$wanted$FALLBACK")?.let { return it }
        former?.let { (calling(it) ?: calling("$it$FALLBACK"))?.let { held -> return adopted(workspaceId, held, wanted) } }
        val name = wanted.takeIf { actions.findByWorkspaceIdAndName(workspaceId, it) == null } ?: "$wanted$FALLBACK"
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

    /** A step's action under a former name, given [wanted] - or [wanted] with [FALLBACK] - where nothing else has it. */
    private fun adopted(workspaceId: Long, action: WorkflowAction, wanted: String): WorkflowAction {
        val name = listOf(wanted, "$wanted$FALLBACK").firstOrNull { actions.findByWorkspaceIdAndName(workspaceId, it) == null }
            ?: return action
        log.info("Research action {} of workspace {} renamed {}", action.name, workspaceId, name)
        action.name = name
        return actions.save(action)
    }

    /**
     * One of the research agents, found by name or made.
     *
     * Made as bare as an agent can be: no model until somebody chooses one on
     * its page, or [givenModels] gives it the one research last ran with; every built-in hidden, no skills, memory,
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
        lay(workspaceId, workflowId, parts, depth.knobs)
        return workflowId
    }

    /**
     * The steps, left to right: the five in a line, with the question whether
     * to review and Review between Write and Save. [finish] has the last word
     * on each node before it is saved.
     */
    private fun lay(
        workspaceId: Long,
        workflowId: Long,
        parts: Parts,
        knobs: Knobs,
        gateConditionId: Long? = null,
        finish: (WorkflowNode) -> Unit = {},
    ) {
        CURRENT.forEach { step -> nodes.save(stepNode(workflowId, step, parts, knobs).also(finish)) }
        nodes.save(gateNode(workspaceId, workflowId, gateConditionId).also { retrying(it) }.also(finish))
        REVIEW_LINES.forEach { (from, to, branch) ->
            edges.save(WorkflowEdge(workflowId = workflowId, sourceKey = from, targetKey = to, branch = branch))
        }
    }

    private fun stepNode(workflowId: Long, step: Step, parts: Parts, knobs: Knobs) = WorkflowNode(
        workflowId = workflowId,
        nodeKey = step.key,
        kind = if (step.agent != null) NodeKind.AGENT else NodeKind.ACTION,
        name = step.nodeName,
        description = step.description,
        actionId = parts.actions[step.key],
        agentId = parts.agents[step.key],
        outputName = step.outputName,
        positionX = xOf(step.key),
        positionY = yOf(step.key),
        mappings = mappingsOf(step, CURRENT, knobs).toMutableList(),
    ).also {
        // Its answer goes on whole, when it is done: a draft handed on in pieces is a report saved in pieces.
        if (step.agent != null) it.streamOutput = false
        retrying(it)
    }

    /** What a step's node passes: the request, what the step before found, its prompt, and its knobs. */
    private fun mappingsOf(step: Step, among: List<Step>, knobs: Knobs): List<NodeMapping> =
        // The request, as the research started it: read off the run's start, so a step's own
        // output can never stand in for it.
        step.request.map {
            NodeMapping(name = it, expression = "${NodeExpressions.TRIGGER}.$it", mode = MappingMode.REFERENCE)
        } +
            step.fromRun.map { (name, field) ->
                NodeMapping(name = name, expression = "${NodeExpressions.TRIGGER}.$field", mode = MappingMode.REFERENCE)
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

        companion object {
            /** The numbers by name, where every one of them is there. */
            fun of(numbers: Map<String, Int>): Knobs? {
                if (numbers.keys != MARKS.all().map { it.first }.toSet()) return null
                return Knobs(
                    sub = numbers.getValue(ResearchCapability.SUB_QUESTIONS),
                    breadth = numbers.getValue(ResearchCapability.BREADTH),
                    depth = numbers.getValue(ResearchCapability.DEPTH),
                    results = numbers.getValue(ResearchCapability.RESULTS_PER_QUERY),
                    sources = numbers.getValue(ResearchCapability.MAX_SOURCES),
                    pages = numbers.getValue(ResearchCapability.MAX_PAGES_PER_SOURCE),
                )
            }
        }
    }

    /** A shape a mode's workflow was once seeded as: its steps, and whether the question whether to review sits between Write and Save. */
    private class Shape(val steps: List<Step>, val gated: Boolean) {
        val keys: Set<String> = steps.map { it.key }.toSet() + listOfNotNull(REVIEWING.takeIf { gated })
        val lines: Set<Triple<String, String, EdgeBranch?>> =
            if (gated) REVIEW_LINES.toSet() else steps.zipWithNext().map { (from, to) -> Triple(from.key, to.key, null) }.toSet()
    }

    /** A workflow [recognise]d as seeded: its numbers, its nodes by key, and whether it is the current shape already. */
    private class Seeded(val knobs: Knobs, val held: Map<String, WorkflowNode>, val current: Boolean)

    /** One node as [read] found it: the knobs it holds, and whether it lacks a parameter a later version gave its step. */
    private class StepRead(val knobs: Map<String, Int>, val behind: Boolean)

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
        /** The catalogue action a function step calls, by name. */
        val actionName: String? = null,
        val agent: SeededAgent? = null,
        val reads: List<Pair<String, String>> = emptyList(),
        val prompt: ((Knobs) -> String)? = null,
        val knobs: (Knobs) -> List<Pair<String, Int>> = { emptyList() },
        /** Parameters read off the run's start under another name: the node's parameter, and the run's field. */
        val fromRun: List<Pair<String, String>> = emptyList(),
    )

    /** What a step's node is called and says it does, and what its action is called. */
    private data class Named(val nodeName: String, val description: String, val actionName: String? = null)

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

        /** What goes after an action's name where somebody's own action already has it. */
        private const val FALLBACK = " (Analysis)"

        /** The step nodes' keys, left to right. */
        const val PLAN = "plan"
        const val GATHER = "gather"
        const val FOLLOW_UP = "followUp"
        const val WRITE = "write"
        /** The question whether to review, and the review. */
        const val REVIEWING = "reviewing"
        const val REVIEW = "review"
        const val SAVE = "save"

        /** Left to right, as [lay] draws them. */
        private val LAYOUT = listOf(PLAN, GATHER, FOLLOW_UP, WRITE, REVIEWING, REVIEW, SAVE)

        private const val GATE_NAME = "Review asked for?"
        private const val GATE_DESCRIPTION = "Goes to Review where it was asked for"
        private const val GATE_CONDITION = "Research: review asked for"

        /** What the question reads: the run's `review`, as text, so one that carries none answers no. */
        private val GATE_MAPPINGS = {
            listOf(
                NodeMapping(
                    name = VALUE_SUBJECT,
                    expression = "{{${NodeExpressions.TRIGGER}.${ResearchCapability.REVIEW_ASKED}}}",
                    mode = MappingMode.VALUE,
                ),
            )
        }

        /** The lines of the current shape: the five in a line, with Review asked for? between Write and Save. */
        private val REVIEW_LINES: List<Triple<String, String, EdgeBranch?>> = listOf(
            Triple(PLAN, GATHER, null),
            Triple(GATHER, FOLLOW_UP, null),
            Triple(FOLLOW_UP, WRITE, null),
            Triple(WRITE, REVIEWING, null),
            Triple(REVIEWING, REVIEW, EdgeBranch.YES),
            Triple(REVIEWING, SAVE, EdgeBranch.NO),
            Triple(REVIEW, SAVE, null),
        )

        /** What the writer agent's answer is carried under, for Save to put the sources after. */
        const val DRAFT = "draft"

        /** What Review report's answer is carried under, for Save to save in place of the draft. */
        const val REVIEWED_OUTPUT = "reviewed"

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
        /** How far below the row Save is drawn: a card's height and a label's clear of it, so Follow up's line to it passes under Write. */
        private const val DROP = 260.0

        val PLANNER = SeededAgent(
            name = "Research planner",
            description = "Plans a research: breaks its question into search queries. Thinks with the model chosen here.",
            instructions = ResearchPipeline.PLAN,
        )

        val REVIEWER = SeededAgent(
            name = "Research reviewer",
            description = "Reviews a research's report against its plan and the notes on its sources before it is saved. " +
                "Thinks with the model chosen here.",
            instructions = ResearchPipeline.REVIEW,
        )

        val WRITER = SeededAgent(
            name = "Research writer",
            description = "Writes a research's report from the notes taken on its sources. Thinks with the model chosen here.",
            instructions = ResearchPipeline.WRITE + "\n\n" +
                "Write in the register the request names. ACADEMIC: " + ResearchPipeline.register(ResearchLanguage.ACADEMIC) +
                " COLLOQUIAL: " + ResearchPipeline.register(ResearchLanguage.COLLOQUIAL) +
                " FORMAL: " + ResearchPipeline.register(ResearchLanguage.FORMAL) +
                "\n\nNever use or cite the documents the request names as not to be used.",
        )

        /** The model the research was started with, as an agent node named its own before the agents were picked. */
        private val MODEL_OF_THE_RESEARCH = ResearchCapability.MODEL

        /**
         * What an agent step reads off the run now: the agent the research
         * was started with for it, and whether the research searches the web,
         * which lends that agent web search for the step. Issue #678.
         */
        private fun agentFromRun(field: String) = listOf(
            AgentNodeRunner.AGENT to field,
            AgentNodeRunner.WEB_SEARCH to ResearchCapability.WEB_SEARCH,
        )

        private val GATHER_STEP = Step(
            GATHER, "Gather sources", "Searches each query and takes notes on sources",
            "gathered",
            function = ResearchCapability.GATHER,
            actionName = "$ACTION_NAME: Gather sources",
            request = listOf(
                ResearchCapability.RESEARCH, ResearchCapability.QUESTION, ResearchCapability.MODEL,
                ResearchCapability.WEBSITES_INCLUDE, ResearchCapability.WEBSITES_EXCLUDE, ResearchCapability.DOCUMENTS_INCLUDE,
                ResearchCapability.WEB_SEARCH, ResearchCapability.INCLUDE_IMAGES,
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
            FOLLOW_UP, "Deepen findings", "Searches follow-up questions to the set depth",
            "deepened",
            function = ResearchCapability.FOLLOW_UP,
            actionName = "$ACTION_NAME: Deepen findings",
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
        private val PLAN_PROMPT: (Knobs) -> String =
            { "Question: {{${NodeExpressions.TRIGGER}.${ResearchCapability.QUESTION}}}\nWrite exactly ${it.sub} search queries." }

        private val WRITE_PROMPT: (Knobs) -> String = {
            val trigger = NodeExpressions.TRIGGER
            "Question: {{$trigger.${ResearchCapability.QUESTION}}}\n\n" +
                "Register: {{$trigger.${ResearchCapability.LANGUAGE}}}\n" +
                "Documents not to use: {{$trigger.$DOCUMENTS_EXCLUDE_NAMES}}\n\n" +
                "Sources, by number:\n\n{{deepened.${ResearchCapability.NOTES}}}"
        }

        /** Plan and Write as they were drawn until #678: naming the research's model, with the agent the node points at. */
        private val MODEL_PLAN_STEP =
            Step(
                PLAN, "Plan research", "Research planner drafts the search queries",
                PLAN,
                agent = PLANNER,
                request = listOf(MODEL_OF_THE_RESEARCH),
                prompt = PLAN_PROMPT,
            )

        private val MODEL_WRITE_STEP =
            Step(
                WRITE, "Write report", "Research writer drafts the cited report",
                DRAFT,
                agent = WRITER,
                request = listOf(MODEL_OF_THE_RESEARCH),
                prompt = WRITE_PROMPT,
            )

        /*
         * And now: the agent the research was started with for the step, which
         * thinks with its own model, and web search lent where the research
         * searches the web. The node still points at the Research planner and
         * writer, which is who answers a run that names no agent - one started
         * before there were agents to pick, restarted.
         */
        private val PLAN_STEP =
            Step(
                PLAN, MODEL_PLAN_STEP.nodeName, MODEL_PLAN_STEP.description,
                PLAN,
                agent = PLANNER,
                request = emptyList(),
                prompt = PLAN_PROMPT,
                fromRun = agentFromRun(ResearchCapability.PLANNER_AGENT),
            )

        private val WRITE_STEP =
            Step(
                WRITE, MODEL_WRITE_STEP.nodeName, MODEL_WRITE_STEP.description,
                DRAFT,
                agent = WRITER,
                request = emptyList(),
                prompt = WRITE_PROMPT,
                fromRun = agentFromRun(ResearchCapability.WRITER_AGENT),
            )

        /** Save as it was before there was a review: the draft and the sources. */
        private val UNREVIEWED_SAVE_STEP = Step(
            SAVE, "Save report", "Appends the sources and saves the report",
            OUTPUT,
            function = ResearchCapability.SAVE,
            actionName = "$ACTION_NAME: Save report",
            request = listOf(ResearchCapability.RESEARCH, ResearchCapability.QUESTION, ResearchCapability.OUTPUT_FORMAT),
            reads = listOf(ResearchCapability.REPORT to DRAFT, ResearchCapability.GATHERED to "deepened"),
        )

        /** And now: what the review left as well, which it saves in place of the draft where there was a review. */
        private val SAVE_STEP = Step(
            SAVE, UNREVIEWED_SAVE_STEP.nodeName, UNREVIEWED_SAVE_STEP.description,
            OUTPUT,
            function = ResearchCapability.SAVE,
            actionName = UNREVIEWED_SAVE_STEP.actionName,
            request = UNREVIEWED_SAVE_STEP.request,
            reads = UNREVIEWED_SAVE_STEP.reads + (ResearchCapability.REVIEWED to "$REVIEWED_OUTPUT.${ResearchCapability.REPORT}"),
        )

        /**
         * Review report: the Research reviewer reads the draft against the
         * plan and the notes, with the model chosen for reviewing, and the
         * Research writer revises it where asked - a function rather than two
         * agent nodes, because the rounds are a loop and a graph has none.
         */
        private val MODEL_REVIEW_STEP = Step(
            REVIEW, "Review report", "Reviewer checks the draft; writer revises",
            REVIEWED_OUTPUT,
            function = ResearchCapability.REVIEW,
            actionName = "$ACTION_NAME: Review report",
            request = listOf(
                ResearchCapability.RESEARCH, ResearchCapability.QUESTION, ResearchCapability.MODEL,
                ResearchCapability.REVIEWER_MODEL, ResearchCapability.LANGUAGE, ResearchCapability.EXCLUDED_NAMES,
            ),
            reads = listOf(
                ResearchCapability.QUERIES to PLAN,
                ResearchCapability.GATHERED to "deepened",
                ResearchCapability.REPORT to DRAFT,
            ),
        )

        /** And now with the Writer and the Reviewer the research was started with, whose instructions it reads. Issue #678. */
        private val REVIEW_STEP = Step(
            REVIEW, MODEL_REVIEW_STEP.nodeName, MODEL_REVIEW_STEP.description,
            REVIEWED_OUTPUT,
            function = ResearchCapability.REVIEW,
            actionName = MODEL_REVIEW_STEP.actionName,
            request = MODEL_REVIEW_STEP.request + listOf(ResearchCapability.WRITER_AGENT, ResearchCapability.REVIEWER_AGENT),
            reads = MODEL_REVIEW_STEP.reads,
        )

        /** The five as they were drawn before there was a review. */
        private val STEPS = listOf(MODEL_PLAN_STEP, GATHER_STEP, FOLLOW_UP_STEP, MODEL_WRITE_STEP, UNREVIEWED_SAVE_STEP)

        /** Those with the review, as drawn until the research picked its agents (#678). */
        private val REVIEWED_STEPS = listOf(MODEL_PLAN_STEP, GATHER_STEP, FOLLOW_UP_STEP, MODEL_WRITE_STEP, MODEL_REVIEW_STEP, SAVE_STEP)

        /** The shape a mode is drawn as now, less the question whether to review, which is a condition. */
        private val CURRENT = listOf(PLAN_STEP, GATHER_STEP, FOLLOW_UP_STEP, WRITE_STEP, REVIEW_STEP, SAVE_STEP)

        /**
         * What the current shape's steps were called before they had the names
         * above, with their actions' names then: what [ensure] recognises to
         * rename. Drawn under these, a mode is still as it was seeded.
         */
        private val FORMER = mapOf(
            PLAN to Named("Plan", "Research planner writes the search queries"),
            GATHER to Named("Gather", "Searches each query and notes what it reads", "$ACTION_NAME: Gather"),
            FOLLOW_UP to Named("Follow up", "Searches follow-up questions, to the depth set", "$ACTION_NAME: Follow up"),
            WRITE to Named("Write", "Research writer writes the cited report"),
            SAVE to Named("Save", "Lists the sources and saves the report", "$ACTION_NAME: Save"),
        )

        /** The five actions a mode was drawn as before Plan and Write were agents. */
        private val ACTION_STEPS = listOf(
            Step(
                PLAN, "Plan", "Breaks the question into search queries.", "plan",
                function = ResearchCapability.PLAN,
                request = listOf(ResearchCapability.RESEARCH, ResearchCapability.QUESTION, ResearchCapability.MODEL),
                knobs = { listOf(ResearchCapability.SUB_QUESTIONS to it.sub) },
            ),
            Step(
                GATHER, "Gather", "Reads the documents, then searches every query and notes what it reads.", GATHER_STEP.outputName,
                function = ResearchCapability.GATHER,
                // As it was then: Gather was not asked about pictures before the steps were agents.
                request = GATHER_STEP.request - ResearchCapability.INCLUDE_IMAGES,
                reads = listOf(ResearchCapability.QUERIES to "plan.${ResearchCapability.QUERIES}"),
                knobs = GATHER_STEP.knobs,
            ),
            Step(
                FOLLOW_UP, "Follow up", "Asks follow-up questions from what was learnt and searches those, to the depth set.",
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

        /**
         * Every shape a mode has been seeded as, oldest first and the current
         * one last: the five actions, the five with Plan and Write as agents,
         * those with the review, and those with the agents the research was
         * started with. Between releases a shape kept its nodes
         * and lines and changed in what [recognise] does not look at - names,
         * descriptions, retries, Gather's `includeImages` - so these are all
         * there were.
         */
        private val SHAPES = listOf(
            Shape(ACTION_STEPS, gated = false),
            Shape(STEPS, gated = false),
            Shape(REVIEWED_STEPS, gated = true),
            Shape(CURRENT, gated = true),
        )

        /** What a step was given after it was first drawn in a shape, which a mode seeded before then lacks. */
        private val LATER = mapOf(GATHER to setOf(ResearchCapability.INCLUDE_IMAGES))

        /** Every name, and every description, a step's node has been given by this product: any other is somebody's. */
        private val OUR_NAMES: Map<String, Set<String>> =
            ((ACTION_STEPS + STEPS + REVIEWED_STEPS + CURRENT).map { it.key to it.nodeName } + FORMER.map { (key, named) -> key to named.nodeName } +
                (REVIEWING to GATE_NAME))
                .groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }
        private val OUR_DESCRIPTIONS: Map<String, Set<String?>> =
            ((ACTION_STEPS + STEPS + REVIEWED_STEPS + CURRENT).map { it.key to it.description } + FORMER.map { (key, named) -> key to named.description } +
                (REVIEWING to GATE_DESCRIPTION))
                .groupBy({ it.first }, { it.second }).mapValues { it.value.toSet<String?>() }

        /** Knobs that are marks rather than numbers, nine digits each, for finding where a prompt writes them. */
        private val MARKS = Knobs(sub = 900_000_001, breadth = 900_000_002, depth = 900_000_003, results = 900_000_004, sources = 900_000_005, pages = 900_000_006)

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
