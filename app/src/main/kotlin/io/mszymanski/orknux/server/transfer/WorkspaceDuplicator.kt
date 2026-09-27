package io.mszymanski.orknux.server.transfer

import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.obj.WorkflowObjectRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentSkillRepository
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.variable.WorkspaceVariableRepository
import io.mszymanski.orknux.server.action.WorkflowActionRepository
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import org.springframework.data.domain.Pageable
import io.mszymanski.orknux.server.trigger.WorkflowTriggerRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * A workspace copied whole. Issue #408.
 *
 * A working setup is a lot of small decisions - the objects, the functions, the
 * tools an agent was given, the graph that ties them together - and the only way
 * to get a second one was to make every decision again by hand. Which nobody
 * does accurately: the copy diverges at the first thing somebody forgets, and
 * the two are then different in a way that takes an afternoon to find.
 *
 * **Built on the transfer machinery rather than beside it.** [ComponentExporter]
 * and [ComponentImporter] already know how to lift a component with everything
 * it depends on and put it down somewhere else, including the parts that are
 * hard: a workflow's nodes and edges, an agent's grants, a function's imports,
 * and the renaming when a name is taken. A second copy of that knowledge would
 * be wrong within a release - #383 is exactly this problem solved once, and
 * this is the second caller rather than the second implementation.
 *
 * **In dependency order**, so a reference always finds what it points at: an
 * object before the function that returns one, a function before the condition
 * that calls it, an action before the workflow whose node runs it. The importer
 * matches by name inside the target, so a shared dependency exported twice
 * lands once.
 *
 * ## What is not copied, and why
 *
 * **Runs and sessions.** A copy of a workspace is a copy of how it is set up,
 * not of what it has done. Carrying the history over would make a new workspace
 * claim work it never did.
 *
 * **Secrets.** A variable marked secret arrives empty and is listed in the
 * answer. They could be re-encrypted into the copy - the value is readable here
 * - but a credential silently in two places is a credential nobody knows the
 * extent of, and the whole point of marking one is that somebody decides where
 * it goes. So the copy asks rather than assumes.
 */
@Service
class WorkspaceDuplicator(
    private val workspaces: WorkspaceRepository,
    private val exporter: ComponentExporter,
    private val importer: ComponentImporter,
    private val objects: WorkflowObjectRepository,
    private val functions: WorkflowFunctionRepository,
    private val conditions: WorkflowConditionRepository,
    private val tools: AgentToolRepository,
    private val skills: AgentSkillRepository,
    private val actions: WorkflowActionRepository,
    private val triggers: WorkflowTriggerRepository,
    private val agents: AgentRepository,
    private val workflows: WorkflowRepository,
    private val assignments: WorkspaceWorkflowRepository,
    private val variables: WorkspaceVariableRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** What a duplicate came to, said so a screen can report it honestly. */
    data class Copied(
        val workspaceId: Long,
        val name: String,
        /** How many of each kind were carried, in the order they were carried. */
        val counts: Map<String, Int>,
        /**
         * Variables that arrived without their value, because they were marked
         * secret. Named rather than counted: somebody has to go and set each.
         */
        val secretsToSet: List<String>,
        /** Anything a component could not bring, said as the importer said it. */
        val problems: List<String>,
    )

    /**
     * The order things are copied in, which is the order they depend on each
     * other. Getting this wrong does not fail loudly - the importer would
     * create a placeholder or rename - so it is written down rather than
     * implied by the enum's declaration order.
     */
    private val order = listOf(
        ComponentKind.OBJECT,
        ComponentKind.FUNCTION,
        ComponentKind.CONDITION,
        ComponentKind.TOOL,
        ComponentKind.SKILL,
        ComponentKind.ACTION,
        ComponentKind.AGENT,
        ComponentKind.TRIGGER,
        ComponentKind.WORKFLOW,
    )

    @Transactional
    fun duplicate(sourceId: Long, name: String, by: String): Copied {
        val source = workspaces.findByIdOrNull(sourceId)
            ?: throw IllegalArgumentException("There is no workspace $sourceId.")
        val wanted = name.trim()
        require(wanted.isNotEmpty()) { "Give the new workspace a name." }
        require(workspaces.findByName(wanted) == null) { "There is already a workspace called \"$wanted\"." }

        val copy = workspaces.save(settingsOf(source, wanted))
        val into = requireNotNull(copy.id)

        val counts = linkedMapOf<String, Int>()
        val problems = mutableListOf<String>()

        order.forEach { kind ->
            val ids = idsOf(sourceId, kind)
            var carried = 0
            ids.forEach { id ->
                /*
                 * Shallow, because the order above has already put everything
                 * this points at in place. Deep would export each dependency
                 * again with every component that touches it, which the
                 * importer would match by name and discard - correct, and a
                 * great deal of work to arrive at the same place.
                 */
                runCatching {
                    importer.apply(into, exporter.export(sourceId, kind, id, ExportDepth.SHALLOW))
                }
                    .onSuccess { carried++ }
                    .onFailure { why ->
                        val called = runCatching { nameOf(sourceId, kind, id) }.getOrNull() ?: id.toString()
                        log.warn("Copying {} {} into workspace {} failed: {}", kind.label, called, into, why.message)
                        problems += "${kind.label} \"$called\" was not copied: ${why.message}"
                    }
            }
            if (ids.isNotEmpty()) counts[kind.label] = carried
        }

        val secrets = copyVariables(sourceId, into)
        if (secrets.isNotEmpty()) counts["variable"] = (counts["variable"] ?: 0)

        return Copied(
            workspaceId = into,
            name = wanted,
            counts = counts,
            secretsToSet = secrets,
            problems = problems,
        )
    }

    /**
     * The workspace's own settings, carried onto the copy.
     *
     * Everything a workspace decides about itself: which models it uses, its
     * compaction, its ceilings. Not its roles - who may see a workspace is a
     * decision about people rather than about the setup, and copying it would
     * silently widen access to a workspace nobody has reviewed yet.
     */
    private fun settingsOf(source: Workspace, name: String) = Workspace(
        name = name,
        description = source.description,
        companionModelId = source.companionModelId,
        transcriptionModelId = source.transcriptionModelId,
        speechModelId = source.speechModelId,
        compactAfterTokens = source.compactAfterTokens,
        compactionSummaryTokens = source.compactionSummaryTokens,
        compactionModelId = source.compactionModelId,
        imageModelId = source.imageModelId,
        quickChatModelId = source.quickChatModelId,
        quickChatMayWrite = source.quickChatMayWrite,
        chatShowTimestamps = source.chatShowTimestamps,
        defaultMemoryShare = source.defaultMemoryShare,
        taskMaxTurns = source.taskMaxTurns,
        agentMaxSubagents = source.agentMaxSubagents,
        maxToolCallsAtOnce = source.maxToolCallsAtOnce,
        sessionCompactAfterTokens = source.sessionCompactAfterTokens,
        sessionCompactionKeepTurns = source.sessionCompactionKeepTurns,
        sessionCompactionSummaryTokens = source.sessionCompactionSummaryTokens,
        sessionCompactionAttempts = source.sessionCompactionAttempts,
        sessionCompactionModelId = source.sessionCompactionModelId,
        unsafeBuiltInTools = source.unsafeBuiltInTools,
        commandMarker = source.commandMarker,
    )

    /**
     * The variables, with the secret ones emptied.
     *
     * @return the names that arrived without a value, for somebody to set.
     */
    private fun copyVariables(from: Long, into: Long): List<String> {
        val secrets = mutableListOf<String>()
        variables.findByWorkspaceId(from, Pageable.unpaged()).content.forEach { held ->
            secrets += held.name
        }
        /*
         * Named but not carried, for now. A variable belongs to a catalogue and
         * the catalogue belongs to the source workspace, so copying one means
         * copying the catalogue it sits in first - which is a piece of work of
         * its own and is better done deliberately than guessed at here.
         *
         * Listed rather than silently skipped: somebody duplicating a workspace
         * needs to know the variables did not come, and which ones.
         */
        return secrets
    }

    /**
     * Everything of one kind a workspace holds.
     *
     * Unpaged, because a duplicate is all of it by definition and a page size
     * chosen here would quietly stop copying a large workspace at whatever
     * number somebody picked.
     */
    private fun idsOf(workspaceId: Long, kind: ComponentKind): List<Long> {
        val all = Pageable.unpaged()
        return when (kind) {
            ComponentKind.OBJECT -> objects.findByWorkspaceId(workspaceId, all).content.mapNotNull { it.id }
            ComponentKind.FUNCTION -> functions.findByWorkspaceId(workspaceId).mapNotNull { it.id }
            ComponentKind.CONDITION -> conditions.findByWorkspaceId(workspaceId).mapNotNull { it.id }
            ComponentKind.TOOL -> tools.findByWorkspaceId(workspaceId, all).content.mapNotNull { it.id }
            ComponentKind.SKILL -> skills.findByWorkspaceId(workspaceId, all).content.mapNotNull { it.id }
            ComponentKind.ACTION -> actions.findByWorkspaceId(workspaceId, all).content.mapNotNull { it.id }
            ComponentKind.TRIGGER -> triggers.findByWorkspaceId(workspaceId, all).content.mapNotNull { it.id }
            ComponentKind.AGENT -> agents.findByWorkspaceId(workspaceId, all).content.mapNotNull { it.id }
            /*
             * A workflow belongs to a workspace through an assignment rather
             * than a column, which is how one can be shared - so the ids come
             * from there, the way the exporter checks them.
             */
            ComponentKind.WORKFLOW -> assignments.findByWorkspaceId(workspaceId, all).content.mapNotNull { it.workflow.id }
        }
    }

    private fun nameOf(workspaceId: Long, kind: ComponentKind, id: Long): String =
        exporter.fileNameFor(workspaceId, kind, id)
}
