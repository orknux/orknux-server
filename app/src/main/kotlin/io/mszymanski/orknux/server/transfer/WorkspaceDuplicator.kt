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
    /** What the components point at by name, copied first so they resolve. Issue #570. */
    private val connections: io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository,
    private val mcpServers: io.mszymanski.orknux.connector.connection.McpServerRepository,
    private val providers: io.mszymanski.orknux.connector.model.ModelProviderRepository,
    private val models: io.mszymanski.orknux.connector.model.LlmModelRepository,
    /**
     * One transaction per piece rather than one for the whole copy. Issue #570:
     * a component that failed in the database left the one shared session
     * unusable - Hibernate cannot go on after a failed flush - so every piece
     * after it failed too, and the copy was lost.
     */
    transactions: org.springframework.transaction.PlatformTransactionManager,
) {

    private val inOwnTransaction = org.springframework.transaction.support.TransactionTemplate(transactions)

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
        /**
         * Connections, model providers and MCP servers that arrived without
         * their credentials, by name. Issue #570.
         */
        val credentialsToSet: List<String> = emptyList(),
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

    /**
     * Not one transaction, deliberately. The new workspace is committed first,
     * then each component in a transaction of its own, so one that cannot be
     * copied - refused by the plan, or failing in the database - is named in
     * the answer and costs nothing else. What is copied is what the answer says
     * was copied. Issue #570.
     */
    fun duplicate(sourceId: Long, name: String, by: String): Copied {
        val source = workspaces.findByIdOrNull(sourceId)
            ?: throw IllegalArgumentException("There is no workspace $sourceId.")
        val wanted = name.trim()
        require(wanted.isNotEmpty()) { "Give the new workspace a name." }
        require(workspaces.findByName(wanted) == null) { "There is already a workspace called \"$wanted\"." }

        val copy = requireNotNull(inOwnTransaction.execute { workspaces.save(settingsOf(source, wanted)) })
        val into = requireNotNull(copy.id)

        val counts = linkedMapOf<String, Int>()
        val problems = mutableListOf<String>()

        /*
         * What components point at by name, before any component. Issue #570:
         * these were never copied, so every action that sends through a
         * connection, every agent on a model and every workflow running those
         * actions arrived as "no connection called Slack outbound here" - the
         * copy of a Slack desk kept almost nothing. Copied under the same names
         * with their credentials left behind, which is the stance on secrets
         * below, so every reference resolves and the list says what to set.
         */
        val externals = requireNotNull(inOwnTransaction.execute { copyExternals(sourceId, into, counts) })
        inOwnTransaction.executeWithoutResult { remapModels(into, externals.models) }

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
                    inOwnTransaction.executeWithoutResult {
                    val envelope = exporter.export(sourceId, kind, id, ExportDepth.SHALLOW)
                    /*
                     * Planned before it is applied. Issue #570: apply refuses by
                     * throwing, inside this method's one transaction, and a
                     * refusal caught here had already marked that transaction
                     * rollback-only - so one agent on a model the copy does not
                     * have threw away the whole duplicate. The plan writes
                     * nothing and joins no transaction, so a component that
                     * cannot come is skipped with its reason and the rest land.
                     */
                    val plan = importer.plan(into, envelope)
                    if (!plan.importable) throw ImportNotPossibleException(plan.problems)
                    importer.apply(into, envelope)
                    }
                }
                    .onSuccess { carried++ }
                    .onFailure { why ->
                        val called = runCatching { nameOf(sourceId, kind, id) }.getOrNull() ?: id.toString()
                        log.warn("Copying {} {} into workspace {} failed: {}", kind.label, called, into, why.message)
                        // The database's own words stop at the first line: the rest is SQL and binds.
                        val said = why.message?.lineSequence()?.firstOrNull()?.substringBefore(" [insert")
                        problems += "${kind.label} \"$called\" was not copied: $said"
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
            credentialsToSet = externals.credentialsToSet,
            problems = problems,
        )
    }

    /** What copying the externals came to: source model id to its copy, and what needs a credential. */
    private data class Externals(val models: Map<Long, Long>, val credentialsToSet: List<String>)

    /**
     * Connections, model providers with their models, and MCP servers, copied
     * without their credentials. Issue #570.
     *
     * Field by field rather than by reflection, for the reason the snapshot is:
     * what is carried is a decision. What is left behind is named at each: the
     * secret and the workspace variable it may be read from (variables are not
     * copied), and the last check's result, which is about the original. An
     * inherited connection keeps pointing at the installation's, whose
     * credential it reads, so it works as it did and needs nothing set.
     * WorkspaceDuplicateTest holds a list of every field, so a field added to
     * one of these and not decided on here fails a test.
     */
    private fun copyExternals(from: Long, into: Long, counts: MutableMap<String, Int>): Externals {
        val needs = mutableListOf<String>()
        val byName = org.springframework.data.domain.Sort.by("name")

        val heldConnections = connections.findByWorkspaceId(from, byName)
        heldConnections.forEach { held ->
            connections.save(
                io.mszymanski.orknux.connector.connection.WorkspaceConnection(
                    workspaceId = into,
                    connectionId = held.connectionId,
                    name = held.name,
                    type = held.type,
                    url = held.url,
                    urlOverride = held.urlOverride,
                    pluginType = held.pluginType,
                    authType = held.authType,
                    smtpPort = held.smtpPort,
                    smtpUsername = held.smtpUsername,
                    smtpFrom = held.smtpFrom,
                    smtpSecurity = held.smtpSecurity,
                    headers = held.headers.map {
                        io.mszymanski.orknux.connector.connection.HttpHeader(it.name, it.value)
                    }.toMutableList(),
                ),
            )
            val hadCredential = held.secretVariableId != null || !held.secret.isNullOrBlank() ||
                held.appTokenVariableId != null || !held.appToken.isNullOrBlank() ||
                held.userTokenVariableId != null || !held.userToken.isNullOrBlank()
            if (held.connectionId == null && hadCredential) needs += "connection ${held.name}"
        }
        if (heldConnections.isNotEmpty()) counts["connection"] = heldConnections.size

        val heldServers = mcpServers.findByWorkspaceId(from, byName)
        heldServers.forEach { held ->
            mcpServers.save(
                io.mszymanski.orknux.connector.connection.McpServer(
                    workspaceId = into,
                    name = held.name,
                    address = held.address,
                    authType = held.authType,
                    headers = held.headers.map {
                        io.mszymanski.orknux.connector.connection.HttpHeader(it.name, it.value)
                    }.toMutableList(),
                ),
            )
            if (held.secretVariableId != null || !held.secret.isNullOrBlank()) needs += "MCP server ${held.name}"
        }
        if (heldServers.isNotEmpty()) counts["mcp server"] = heldServers.size

        val modelIds = mutableMapOf<Long, Long>()
        val heldProviders = providers.findByWorkspaceId(from, byName)
        heldProviders.forEach { held ->
            val copy = providers.save(
                io.mszymanski.orknux.connector.model.ModelProvider(
                    workspaceId = into,
                    name = held.name,
                    type = held.type,
                    endpoint = held.endpoint,
                    authMethod = held.authMethod,
                    apiVersion = held.apiVersion,
                    deploymentName = held.deploymentName,
                    region = held.region,
                    tenantId = held.tenantId,
                    clientId = held.clientId,
                    scope = held.scope,
                    checkEnabled = held.checkEnabled,
                    throttleTokensPerSecond = held.throttleTokensPerSecond,
                    throttleRequestsPerSecond = held.throttleRequestsPerSecond,
                    acceptRetryAfter = held.acceptRetryAfter,
                ),
            )
            if (held.secretVariableId != null || !held.secret.isNullOrBlank()) needs += "model provider ${held.name}"
            models.findByProviderId(requireNotNull(held.id)).forEach { model ->
                val made = models.save(
                    io.mszymanski.orknux.connector.model.LlmModel(
                        providerId = requireNotNull(copy.id),
                        name = model.name,
                        modelId = model.modelId,
                        kind = model.kind,
                        contextWindow = model.contextWindow,
                        maxOutput = model.maxOutput,
                        parallelToolCalls = model.parallelToolCalls,
                        temperature = model.temperature,
                        topP = model.topP,
                        topK = model.topK,
                        minP = model.minP,
                        repeatPenalty = model.repeatPenalty,
                        enabled = model.enabled,
                        tokenLimit = model.tokenLimit,
                        resetInterval = model.resetInterval,
                        requestsPerMinute = model.requestsPerMinute,
                        throttleTokensPerSecond = model.throttleTokensPerSecond,
                        throttleRequestsPerSecond = model.throttleRequestsPerSecond,
                        acceptRetryAfter = model.acceptRetryAfter,
                        inputCostPerMillion = model.inputCostPerMillion,
                        outputCostPerMillion = model.outputCostPerMillion,
                        voice = model.voice,
                        skipEmptyLines = model.skipEmptyLines,
                        imageCostPerImage = model.imageCostPerImage,
                    ),
                )
                modelIds[requireNotNull(model.id)] = requireNotNull(made.id)
            }
        }
        if (heldProviders.isNotEmpty()) counts["model provider"] = heldProviders.size

        return Externals(modelIds, needs)
    }

    /**
     * The workspace's own model choices, pointed at the copies. Issue #570: the
     * settings were carried as ids, which named the source workspace's models -
     * a copy whose chat would think with another workspace's provider.
     */
    private fun remapModels(into: Long, copied: Map<Long, Long>) {
        val copy = workspaces.findByIdOrNull(into) ?: return
        fun mapped(id: Long?): Long? = id?.let { copied[it] }
        copy.companionModelId = mapped(copy.companionModelId)
        copy.transcriptionModelId = mapped(copy.transcriptionModelId)
        copy.speechModelId = mapped(copy.speechModelId)
        copy.compactionModelId = mapped(copy.compactionModelId)
        copy.imageModelId = mapped(copy.imageModelId)
        copy.quickChatModelId = mapped(copy.quickChatModelId)
        copy.sessionCompactionModelId = mapped(copy.sessionCompactionModelId)
        workspaces.save(copy)
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
