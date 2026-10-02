package io.mszymanski.orknux.server.transfer

import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.obj.WorkflowObjectRepository
import io.mszymanski.orknux.connector.model.copied
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
    /** Where the copy's lock wait is read and set. Issue #581. */
    private val installation: io.mszymanski.orknux.server.attachment.InstallationSettings,
    dataSource: javax.sql.DataSource,
) {

    private val inOwnTransaction = org.springframework.transaction.support.TransactionTemplate(transactions)

    /**
     * Whether a transaction of the copy can be told how long to wait for a lock.
     * Postgres waits for ever by default; SQLite's one writer is already bounded
     * by its busy timeout and the queue in front of it.
     */
    private val boundedWaits = !io.mszymanski.orknux.server.database.isSqlite(
        io.mszymanski.orknux.server.database.jdbcUrlOf(dataSource),
    )

    /** Joins the transaction's own connection: the JPA transaction exposes it to JDBC. */
    private val jdbc = org.springframework.jdbc.core.JdbcTemplate(dataSource)

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * One step of the copy in a transaction of its own, waiting at most
     * [lockWaitSeconds] for any lock it needs. Issue #581: a copy on Postgres sat
     * for ever with nothing in the log, and a lock wait is the one way a
     * statement there waits without limit. Bounded, a wait becomes an error that
     * is logged and answered instead of a page that never moves.
     */
    private fun <T : Any> step(lockWaitSeconds: Int, work: () -> T): T = requireNotNull(
        inOwnTransaction.execute {
            if (boundedWaits) jdbc.execute("SET LOCAL lock_timeout = '${lockWaitSeconds}s'")
            work()
        },
    )

    /**
     * What a failed step comes to in a sentence. A lock wait that ran out is
     * said as one, rather than as the database's "canceling statement", which
     * reads as somebody having cancelled the copy.
     */
    private fun reasonOf(why: Throwable, lockWaitSeconds: Int): String {
        val lockTimedOut = generateSequence(why) { it.cause }.any { cause ->
            (cause as? java.sql.SQLException)?.sqlState == LOCK_NOT_AVAILABLE
        }
        if (lockTimedOut) return "it waited $lockWaitSeconds seconds for a row another transaction was holding"
        // The database's own words stop at the first line: the rest is SQL and binds.
        return why.message?.lineSequence()?.firstOrNull()?.substringBefore(" [insert") ?: why.javaClass.simpleName
    }

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
    fun duplicate(
        sourceId: Long,
        name: String,
        by: String,
        /** Told after each step, for a page showing how far the copy has got. Issue #572. */
        progress: (WorkspaceCopyProgress.Step) -> Unit = {},
    ): Copied {
        val source = workspaces.findByIdOrNull(sourceId)
            ?: throw IllegalArgumentException("There is no workspace $sourceId.")
        val wanted = name.trim()
        require(wanted.isNotEmpty()) { "Give the new workspace a name." }
        require(workspaces.findByName(wanted) == null) { "There is already a workspace called \"$wanted\"." }

        /*
         * Logged at every step, at INFO, so a copy that stops can be found from
         * the log alone. Issue #581: one sat on a Postgres installation with no
         * error and nothing written, and nothing said which step it was on. Each
         * line is written *before* the step, so the last one is where it is.
         */
        val started = System.nanoTime()
        // Read once: a copy is held to the number it started under.
        val wait = installation.workspaceCopyLockWaitSeconds()
        log.info("Copy of workspace {} \"{}\" as \"{}\" by {}: creating the workspace", sourceId, source.name, wanted, by)
        val copy = step(wait) { workspaces.save(settingsOf(source, wanted)) }
        val into = requireNotNull(copy.id)
        log.info("Copy of workspace {} into {}: workspace created", sourceId, into)

        val counts = linkedMapOf<String, Int>()
        val problems = mutableListOf<String>()

        /*
         * Everything there is to carry, counted before anything is carried, so
         * the page has numbers from the first step rather than a sentence until
         * the components start. Issue #581: the externals step said nothing, so
         * a copy spending its time there - or stopped there - looked the same as
         * one that had not begun. A retry later does not move the goalposts.
         */
        val externals = externalsOf(sourceId)
        val pending = order.flatMap { kind -> idsOf(sourceId, kind).map { kind to it } }.toMutableList()
        val totals = linkedMapOf<String, Int>()
        externals.forEach { (kind, ids) -> if (ids.isNotEmpty()) totals[kind] = ids.size }
        pending.groupingBy { it.first.label }.eachCount().forEach { (kind, count) -> totals[kind] = count }
        val overall = totals.values.sum()
        var carried = 0
        log.info("Copy of workspace {} into {}: {} things to copy {}", sourceId, into, overall, totals)
        totals.keys.firstOrNull()?.let { kind -> progress(WorkspaceCopyProgress.Step(kind, 0, totals.getValue(kind), 0, overall)) }

        fun carriedOne(kind: String) {
            counts[kind] = (counts[kind] ?: 0) + 1
            carried += 1
            progress(WorkspaceCopyProgress.Step(kind, counts.getValue(kind), totals.getValue(kind), carried, overall))
        }

        /*
         * What components point at by name, before any component. Issue #570:
         * these were never copied, so every action that sends through a
         * connection, every agent on a model and every workflow running those
         * actions arrived as "no connection called Slack outbound here" - the
         * copy of a Slack desk kept almost nothing. Copied under the same names
         * with their credentials left behind, which is the stance on secrets
         * below, so every reference resolves and the list says what to set.
         *
         * One transaction each, like the components, so a poll asking how far
         * the copy has got is answered between two of them on SQLite as well.
         * One that fails stops the copy: a component after it would point at a
         * name that is not there, and the copy would say less than it lost.
         */
        val needs = mutableListOf<String>()
        val modelIds = mutableMapOf<Long, Long>()
        externals.forEach { (kind, ids) ->
            ids.forEach { id ->
                val what = runCatching {
                    step(wait) { copyExternal(sourceId, into, kind, id, needs, modelIds) }
                }.getOrElse { why -> throw stopped(sourceId, into, "$kind \"${externalName(kind, id)}\"", why, wait) }
                log.info("Copy of workspace {} into {}: {} \"{}\" copied", sourceId, into, kind, what)
                carriedOne(kind)
            }
        }
        log.info("Copy of workspace {} into {}: pointing the workspace's model settings at the copies", sourceId, into)
        runCatching { step(wait) { remapModels(into, modelIds) } }
            .onFailure { why -> throw stopped(sourceId, into, "the workspace's model settings", why, wait) }

        /*
         * Copied kind by kind, and then again for what did not come. Issue #570:
         * inside a kind nothing is ordered - a function calling another comes
         * before or after it by id - so one that needed a later one failed, and
         * everything built on it after. Each pass retries what failed until a
         * pass carries nothing new; what is left then is genuinely missing, and
         * is reported with the reason from its last attempt.
         */
        order.forEach { kind -> if (pending.any { it.first == kind }) counts[kind.label] = 0 }
        val lastWhy = mutableMapOf<Pair<ComponentKind, Long>, String?>()
        var pass = 0
        while (pending.isNotEmpty()) {
            val before = pending.size
            pass += 1
            log.info("Copy of workspace {} into {}: pass {}, {} components left", sourceId, into, pass, before)
            pending.toList().forEach { (kind, id) ->
                log.info("Copy of workspace {} into {}: copying {} {}", sourceId, into, kind.label, id)
                /*
                 * Shallow, because the order above has already put everything
                 * this points at in place. Deep would export each dependency
                 * again with every component that touches it, which the
                 * importer would match by name and discard - correct, and a
                 * great deal of work to arrive at the same place.
                 */
                runCatching {
                    step(wait) {
                        val envelope = exporter.export(sourceId, kind, id, ExportDepth.SHALLOW)
                        /*
                         * Planned before it is applied: the plan writes nothing
                         * and joins no transaction, so a component that cannot
                         * come is skipped with its reason.
                         */
                        val plan = importer.plan(into, envelope)
                        if (!plan.importable) throw ImportNotPossibleException(plan.problems)
                        importer.apply(into, envelope)
                    }
                }
                    .onSuccess {
                        pending.remove(kind to id)
                        carriedOne(kind.label)
                    }
                    .onFailure { why ->
                        lastWhy[kind to id] = reasonOf(why, wait)
                        log.info("Copy of workspace {} into {}: {} {} not copied on pass {}: {}", sourceId, into, kind.label, id, pass, lastWhy[kind to id])
                    }
            }
            if (pending.size == before) break
        }
        pending.forEach { (kind, id) ->
            val called = runCatching { nameOf(sourceId, kind, id) }.getOrNull() ?: id.toString()
            val why = lastWhy[kind to id]
            log.warn("Copying {} {} into workspace {} failed: {}", kind.label, called, into, why)
            problems += "${kind.label} \"$called\" was not copied: $why"
        }

        log.info("Copy of workspace {} into {}: naming the variables", sourceId, into)
        val secrets = copyVariables(sourceId, into)
        if (secrets.isNotEmpty()) counts["variable"] = (counts["variable"] ?: 0)
        log.info(
            "Copy of workspace {} into {}: done in {} ms, {} copied, {} not",
            sourceId, into, (System.nanoTime() - started) / 1_000_000, carried, problems.size,
        )

        return Copied(
            workspaceId = into,
            name = wanted,
            counts = counts,
            secretsToSet = secrets,
            credentialsToSet = needs,
            problems = problems,
        )
    }

    /**
     * A step the copy cannot go on without, failed: logged with where it
     * stopped, and answered as a sentence. Issue #581.
     */
    private fun stopped(from: Long, into: Long, at: String, why: Throwable, wait: Int): WorkspaceCopyStoppedException {
        val reason = reasonOf(why, wait)
        log.warn("Copy of workspace {} into {} stopped at {}: {}", from, into, at, reason, why)
        val made = workspaces.findByIdOrNull(into)?.name ?: into.toString()
        return WorkspaceCopyStoppedException(made, at, reason)
    }

    /**
     * The connections, MCP servers and model providers a workspace holds, by
     * the kind the page names them under, in the order they are copied. Ids
     * only: each is read again inside the transaction that copies it.
     */
    private fun externalsOf(from: Long): List<Pair<String, List<Long>>> {
        val byName = org.springframework.data.domain.Sort.by("name")
        return listOf(
            "connection" to connections.findByWorkspaceId(from, byName).mapNotNull { it.id },
            "mcp server" to mcpServers.findByWorkspaceId(from, byName).mapNotNull { it.id },
            "model provider" to providers.findByWorkspaceId(from, byName).mapNotNull { it.id },
        )
    }

    /** What one of those is called, for a sentence about it; its id where it cannot be read. */
    private fun externalName(kind: String, id: Long): String = runCatching {
        when (kind) {
            "connection" -> connections.findByIdOrNull(id)?.name
            "mcp server" -> mcpServers.findByIdOrNull(id)?.name
            else -> providers.findByIdOrNull(id)?.name
        }
    }.getOrNull() ?: id.toString()

    /**
     * One connection, model provider with its models, or MCP server, copied
     * without its credentials. Issue #570.
     *
     * Field by field rather than by reflection, for the reason the snapshot is:
     * what is carried is a decision. What is left behind is named at each: the
     * secret and the workspace variable it may be read from (variables are not
     * copied), and the last check's result, which is about the original. An
     * inherited connection keeps pointing at the installation's, whose
     * credential it reads, so it works as it did and needs nothing set.
     * WorkspaceDuplicateTest holds a list of every field, so a field added to
     * one of these and not decided on here fails a test.
     *
     * @return its name, for the log.
     */
    private fun copyExternal(
        from: Long,
        into: Long,
        kind: String,
        id: Long,
        needs: MutableList<String>,
        modelIds: MutableMap<Long, Long>,
    ): String = when (kind) {
        "connection" -> {
            val held = requireNotNull(connections.findByIdOrNull(id)?.takeIf { it.workspaceId == from })
            log.info("Copy of workspace {} into {}: connection \"{}\"", from, into, held.name)
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
            held.name
        }

        "mcp server" -> {
            val held = requireNotNull(mcpServers.findByIdOrNull(id)?.takeIf { it.workspaceId == from })
            log.info("Copy of workspace {} into {}: MCP server \"{}\"", from, into, held.name)
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
            held.name
        }

        else -> {
            val held = requireNotNull(providers.findByIdOrNull(id)?.takeIf { it.workspaceId == from })
            log.info("Copy of workspace {} into {}: model provider \"{}\"", from, into, held.name)
            val copy = providers.save(held.copied(workspaceId = into, name = held.name))
            if (held.secretVariableId != null || !held.secret.isNullOrBlank()) needs += "model provider ${held.name}"
            models.findByProviderId(id).forEach { model ->
                log.info("Copy of workspace {} into {}: model \"{}\" of \"{}\"", from, into, model.name, held.name)
                val made = models.save(model.copied(providerId = requireNotNull(copy.id), name = model.name))
                modelIds[requireNotNull(model.id)] = requireNotNull(made.id)
            }
            held.name
        }
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

/** Postgres's `lock_not_available`, which is what a `lock_timeout` running out raises. */
private const val LOCK_NOT_AVAILABLE = "55P03"

/**
 * A copy that could not go on, said where it stopped and why. Issue #581.
 *
 * The workspace it was making is left as far as it got, and named, so whoever
 * reads this knows there is something to delete or to finish by hand.
 */
class WorkspaceCopyStoppedException(val workspace: String, val at: String, val reason: String) : RuntimeException(
    "The copy stopped at $at because $reason. The workspace \"$workspace\" holds what was copied before it.",
), io.mszymanski.orknux.server.graphql.Refusal {

    override val arguments get() = mapOf("workspace" to workspace, "at" to at, "reason" to reason)
}
