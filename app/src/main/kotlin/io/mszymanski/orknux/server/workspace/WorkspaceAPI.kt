package io.mszymanski.orknux.server.workspace

import org.slf4j.LoggerFactory
import io.mszymanski.orknux.server.attachment.MAX_SUBAGENTS
import io.mszymanski.orknux.server.attachment.MIN_SUBAGENTS
import io.mszymanski.orknux.server.attachment.MAX_CALLS_AT_ONCE
import io.mszymanski.orknux.server.attachment.MIN_CALLS_AT_ONCE
import io.mszymanski.orknux.server.attachment.MAX_COMPACT_AFTER
import io.mszymanski.orknux.server.attachment.MIN_COMPACT_AFTER
import io.mszymanski.orknux.server.attachment.MAX_COMPACTIONS
import io.mszymanski.orknux.server.attachment.MIN_COMPACTIONS
import io.mszymanski.orknux.server.attachment.MAX_KEEP_TURNS
import io.mszymanski.orknux.server.attachment.MIN_KEEP_TURNS
import io.mszymanski.orknux.server.attachment.MAX_SUMMARY_TOKENS
import io.mszymanski.orknux.server.attachment.MIN_SUMMARY_TOKENS
import io.mszymanski.orknux.server.workflow.ExecutionSweeper
import io.mszymanski.orknux.connector.connection.WorkspaceLifecycleService
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.server.issue.IssueType
import io.mszymanski.orknux.server.issue.IssueTypeAPI
import io.mszymanski.orknux.server.memory.FIRST_MEMORY_CATALOG
import io.mszymanski.orknux.server.memory.MemoryCatalog
import io.mszymanski.orknux.server.issue.IssueTypeRepository
import io.mszymanski.orknux.server.issue.IssueStatusCatalogue
import io.mszymanski.orknux.server.workflow.ImageSizePresetCatalogue
import io.mszymanski.orknux.server.task.TaskProperties
import io.mszymanski.orknux.workflow.script.ScriptProperties
import io.mszymanski.orknux.server.llm.SessionMemoryBudgets
import io.mszymanski.orknux.server.security.Role
import io.mszymanski.orknux.server.security.RoleNotFoundException
import io.mszymanski.orknux.server.security.RoleRepository
import io.mszymanski.orknux.server.security.WorkspaceAccess
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller
import io.mszymanski.orknux.connector.model.ModelKind
import io.mszymanski.orknux.server.graphql.Refusal
import org.springframework.transaction.annotation.Transactional

@Controller
class WorkspaceAPI(
    private val repository: WorkspaceRepository,
    private val roles: RoleRepository,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val access: WorkspaceAccess,
    private val connections: WorkspaceLifecycleService,
    private val executions: ExecutionSweeper,
    private val models: ModelService,
    private val budgets: SessionMemoryBudgets,
    private val issueTypes: IssueTypeRepository,
    /** Where a new workspace's four issue statuses are written; see `createWorkspace`. */
    private val issueStatuses: IssueStatusCatalogue,
    /** Where a new workspace's seven image size presets are written; see `createWorkspace`. */
    private val imageSizePresets: ImageSizePresetCatalogue,
    /** Where a new workspace's one memory catalog is made; see `createWorkspace`. */
    private val memoryCatalogs: io.mszymanski.orknux.server.memory.MemoryCatalogRepository,
    /** Only to say what a task gets where the workspace has not said. */
    private val taskProperties: TaskProperties,
    /** Only to say how long a script may run where the workspace has not said. */
    private val scriptProperties: ScriptProperties,
    /** Copying a whole workspace; see [duplicateWorkspace]. Issue #408. */
    private val duplicator: io.mszymanski.orknux.server.transfer.WorkspaceDuplicator,
    /** How far a copy has got, for the page waiting on it. Issue #572. */
    private val copyProgress: io.mszymanski.orknux.server.transfer.WorkspaceCopyProgress,
    /** Only to say how many agents one may ask where the workspace has not said. Issue #380. */
    private val installation: io.mszymanski.orknux.server.attachment.InstallationSettings,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Non-admins only see workspaces whose directory group they belong to. The filter
     * runs in memory because membership lives on the authentication rather than in
     * the database, and an workspace count stays small.
     */
    /** The two columns the admin list draws, and what they order by. Issue #358. */
    private val WORKSPACE_ORDERS = mapOf(
        "NAME" to listOf("name"),
        "DESCRIPTION" to listOf("description", "name"),
    )

    @QueryMapping
    fun workspaces(
        @Argument page: Int?,
        @Argument size: Int?,
        @Argument order: String?,
        @Argument ascending: Boolean?,
    ): WorkspacePage {
        val sort = sortBy(order, ascending, WORKSPACE_ORDERS, "NAME")
        val pageable = pageRequest(page, size, sort)
        if (access.isAdmin()) return WorkspacePage(repository.findAll(pageable))

        /*
         * The same order for somebody who sees only some of them. The page is cut
         * here rather than by the database, so the sort has to be applied to the
         * whole list before the cutting - handing it to the page request alone
         * would order the slice and not the list.
         */
        val visible = repository.findAll(sort).filter(access::canSee)
        return WorkspacePage(PageImpl(visible.page(pageable), pageable, visible.size.toLong()))
    }

    @QueryMapping
    fun workspace(@Argument id: Long): Workspace? = repository.findByIdOrNull(id)?.takeIf(access::canSee)

    /**
     * A whole workspace copied into a new one. Issue #408.
     *
     * An administrator's, like creating one: it makes a workspace, and reads
     * every component of the source to do it.
     *
     * What comes back says what happened rather than only that it did - how
     * many of each kind were carried, which variables have to be set by hand,
     * and anything a component could not bring. A copy that quietly lost three
     * agents would be worse than one that refused.
     */
    @MutationMapping
    fun duplicateWorkspace(@Argument id: Long, @Argument name: String, @Argument progressKey: String?): WorkspaceCopyView {
        access.requireAdmin()
        val by = org.springframework.security.core.context.SecurityContextHolder
            .getContext().authentication?.name ?: "system"
        val key = progressKey?.trim()?.takeIf { it.isNotEmpty() }
        val copied = try {
            duplicator.duplicate(id, name, by) { step -> key?.let { copyProgress.report(it, step) } }
        } finally {
            key?.let { copyProgress.forget(it) }
        }
        auditRecorder.record(
            workspaceId = copied.workspaceId,
            operationType = WorkspaceOperationType.ADD,
            newWorkspaceName = copied.name,
        )
        return WorkspaceCopyView(
            workspace = repository.findByIdOrNull(copied.workspaceId)
                ?: throw WorkspaceNotFoundException(copied.workspaceId),
            carried = copied.counts.map { (kind, count) -> CopiedKind(kind, count) },
            variablesToSet = copied.secretsToSet,
            credentialsToSet = copied.credentialsToSet,
            problems = copied.problems,
        )
    }

    /**
     * How far a copy started with this key has got, or null where none is
     * running under it - not started yet, or finished. Issue #572.
     */
    @QueryMapping
    fun workspaceCopyProgress(@Argument key: String): io.mszymanski.orknux.server.transfer.WorkspaceCopyProgress.Step? {
        access.requireAdmin()
        return copyProgress.read(key)
    }

    @MutationMapping
    @Transactional
    fun createWorkspace(@Argument input: CreateWorkspaceInput): Workspace {
        access.requireAdmin()
        val name = input.name.trim()
        if (name.isEmpty()) throw WorkspaceNameInvalidException()
        if (repository.findByName(name) != null) throw WorkspaceNameTakenException(name)

        val workspace = repository.save(Workspace(name = name, description = input.description?.trim()?.ifEmpty { null }))
        auditRecorder.record(
            workspaceId = requireNotNull(workspace.id),
            operationType = WorkspaceOperationType.ADD,
            newWorkspaceName = workspace.name,
        )
        // The admin default connections come with the workspace.
        provision(requireNotNull(workspace.id), workspace.name)
        /*
         * And so do the two kinds of thing every tracker files. Here rather
         * than only in the migration that added them, because a workspace made
         * tomorrow never replays the Postgres history and a workspace made on
         * SQLite never had one - so the migration seeds what already existed
         * and this seeds everything since, and the two engines agree.
         *
         * No audit line for these: they arrive with the workspace, like the
         * default connections, and a log that announced them would be
         * announcing the shape of a new workspace rather than a decision
         * anybody made.
         */
        IssueTypeAPI.TO_BEGIN_WITH.forEach {
            issueTypes.save(IssueType(workspaceId = requireNotNull(workspace.id), name = it))
        }
        /*
         * And the four statuses an issue moves through, for the same reason and
         * with the same silence: they arrive with the workspace. Issue #428.
         */
        issueStatuses.ensure(requireNotNull(workspace.id))
        /*
         * And the sizes an image node offers in its Preset menu, the same way:
         * a menu with nothing in it is a workspace setting nobody was told to
         * set. Issue #431.
         */
        imageSizePresets.ensure(requireNotNull(workspace.id))
        /*
         * And somewhere to remember things, for the same reason and in the same
         * way. A catalog is what an agent is granted and what a memory is filed
         * into, so a workspace with none has nowhere for either - `memory_save`
         * refuses because there is nothing to write to, and the two memory tools
         * are not offered at all. Making one was therefore setup nobody is told
         * about, before an agent could remember anything.
         *
         * Named rather than left blank, and renameable: the name it starts with
         * is a starting point, and the flag rather than the name is what makes
         * it the one that stays.
         */
        memoryCatalogs.save(
            MemoryCatalog(
                workspaceId = requireNotNull(workspace.id),
                name = FIRST_MEMORY_CATALOG,
                createdBy = "system",
                isDefault = true,
            ),
        )
        return workspace
    }

    private fun provision(workspaceId: Long, name: String) {
        val provisioned = connections.provisionWorkspaceConnections(workspaceId)
        if (provisioned.isEmpty()) return

        val what = if (provisioned.size == 1) "connection" else "connections"
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.INTEGRATION,
            "${provisioned.size} default $what provisioned for $name",
        )
    }

    /**
     * Backs the workspace settings form: name, description and the role lists.
     *
     * Two callers with two different rights, and the split is the point of the
     * workspace administrator role. Whoever administers *this* workspace may change
     * its name and its description - that is what the role is for, and it is what
     * was asked for. Only an installation administrator may change either role
     * list, and a workspace administrator who sends one is refused unless it is the
     * list that is already there.
     *
     * Refused rather than quietly ignored, and unchanged rather than absent, for
     * the same reason: the settings form loads the lists and posts back what it
     * loaded, so a workspace administrator who touched only the name is sending the
     * current lists and means nothing by it. Somebody who has actually changed one
     * is doing the thing the role does not cover, and being told so beats saving
     * everything except the part they came for.
     *
     * Why they may not: whoever edits the list decides who else gets into the
     * workspace, and can take the role off everybody else - the person who gave it
     * to them included. That is contained to one workspace and may be exactly what
     * an installation wants, but it is a bigger promise than "changing settings",
     * and it is the one that cannot be walked back. Widening this later is a line
     * of code; narrowing it after somebody has arranged their installation around
     * it is taking something away.
     */
    @MutationMapping
    @Transactional
    fun updateWorkspace(@Argument id: Long, @Argument input: UpdateWorkspaceInput): Workspace {
        val newName = input.name.trim()
        if (newName.isEmpty()) throw WorkspaceNameInvalidException()

        val workspace = repository.findByIdOrNull(id) ?: throw WorkspaceNotFoundException(id)
        access.requireAdministers(workspace)
        val previousName = workspace.name
        if (newName != previousName && repository.findByName(newName) != null) {
            throw WorkspaceNameTakenException(newName)
        }

        val previousDescription = workspace.description
        val previousRoles = workspace.roles.mapNotNull { it.id }.toSet()
        val previousAdminRoles = workspace.adminRoles.mapNotNull { it.id }.toSet()

        workspace.name = newName
        workspace.description = input.description?.trim()?.ifEmpty { null }
        /*
         * Null leaves the assignment alone; a list replaces it, empty included —
         * taking every role off a workspace is a decision somebody may make, and it
         * means administrators only.
         */
        val wantedRoles = input.roleIds?.let(::resolve)
        val wantedAdminRoles = input.adminRoleIds?.let(::resolve)

        // The installation administrator's half, checked before anything is
        // assigned: sending the list that is already there is not an edit, so a
        // workspace administrator saving the form they were shown goes through.
        val changesRoles = wantedRoles != null && wantedRoles.mapNotNull { it.id }.toSet() != previousRoles
        val changesAdminRoles =
            wantedAdminRoles != null && wantedAdminRoles.mapNotNull { it.id }.toSet() != previousAdminRoles
        if (changesRoles || changesAdminRoles) access.requireAdmin()

        wantedRoles?.let { workspace.roles = it.toMutableSet() }
        wantedAdminRoles?.let { workspace.adminRoles = it.toMutableSet() }

        /*
         * A role that administers a workspace it cannot open is nothing, so the two
         * lists are checked against each other after both have been applied rather
         * than as each arrives — a save that adds a role and marks it administering
         * in one go is the ordinary case, and checking them one at a time would
         * refuse it depending on the order the fields happened to be read in.
         */
        val opens = workspace.roles.mapNotNull { it.id }.toSet()
        val stranded = workspace.adminRoles.filter { it.id !in opens }
        if (stranded.isNotEmpty()) throw WorkspaceAdminRoleNotAssignedException(stranded.map { it.name }.sorted())

        if (newName != previousName) {
            auditRecorder.record(
                workspaceId = id,
                operationType = WorkspaceOperationType.RENAME,
                oldWorkspaceName = previousName,
                newWorkspaceName = newName,
            )
        }
        val nowRoles = workspace.roles.mapNotNull { it.id }.toSet()
        if (nowRoles != previousRoles) {
            // Named, not counted: who can see a workspace is worth being able to
            // read out of the log a year later.
            val named = workspace.roles.map { it.name }.sorted()
            auditRecorder.record(
                id,
                WorkspaceAuditCategory.WORKSPACE,
                if (named.isEmpty()) {
                    "Workspace roles cleared: administrators only"
                } else {
                    "Workspace roles set to ${named.joinToString(", ")}"
                },
            )
        }
        val nowAdminRoles = workspace.adminRoles.mapNotNull { it.id }.toSet()
        if (nowAdminRoles != previousAdminRoles) {
            // Named for the same reason the list above is, and more so: who may
            // administer a workspace is the line somebody will want to read out of
            // the log a year later, when the question is how it came to be theirs.
            val named = workspace.adminRoles.map { it.name }.sorted()
            auditRecorder.record(
                id,
                WorkspaceAuditCategory.WORKSPACE,
                if (named.isEmpty()) {
                    "Workspace administrators cleared: installation administrators only"
                } else {
                    "Workspace administered by ${named.joinToString(", ")}"
                },
            )
        }
        if (workspace.description != previousDescription) {
            auditRecorder.record(id, WorkspaceAuditCategory.WORKSPACE, "Workspace description updated")
        }
        return workspace
    }

    /** Ids to roles, refusing the whole save on one that names nothing. */
    private fun resolve(ids: List<Long>): List<Role> = ids.distinct()
        .map { roleId -> roles.findByIdOrNull(roleId) ?: throw RoleNotFoundException(roleId) }

    /**
     * Whether the caller administers this workspace, for the interface to paint with.
     *
     * A field on the workspace rather than a flag on the session, because that is
     * what the answer depends on: somebody can lead one workspace and merely work in
     * another, and a single boolean about the person could not say so. It is how the
     * settings page knows whether to offer the name and description at all, and it
     * is true for an installation administrator everywhere.
     */
    @SchemaMapping(typeName = "Workspace")
    fun administered(workspace: Workspace): Boolean = access.canAdminister(workspace)

    /**
     * What a task here gets when the workspace has said nothing.
     *
     * Sent so the field can *show* the number rather than describe where it
     * comes from. "The installation's own" is a true sentence and a useless
     * placeholder - it tells somebody looking at an empty box that a value
     * exists somewhere and not what it is, which is the only thing they wanted
     * to know. The box says 40, and typing over it is how you disagree.
     */
    @SchemaMapping(typeName = "Workspace")
    fun taskMaxTurnsDefault(workspace: Workspace): Int = taskProperties.maxTurns

    /** What an agent here may ask when the workspace has said nothing: Admin -> Settings. Issue #380. */
    @SchemaMapping(typeName = "Workspace")
    fun agentMaxSubagentsDefault(workspace: Workspace): Int = installation.agentMaxSubagents()

    /** How many calls one message may ask for when the workspace has said nothing. Issue #518. */
    @SchemaMapping(typeName = "Workspace")
    fun maxToolCallsAtOnceDefault(workspace: Workspace): Int = installation.maxToolCallsAtOnce()

    /** How long a session here may grow before it is compacted. Issue #523. */
    @SchemaMapping(typeName = "Workspace")
    fun sessionCompactAfterTokensDefault(workspace: Workspace): Int =
        installation.sessionCompactAfterTokens()

    /** What a compacted turn keeps here when the workspace has said nothing. Issue #522. */
    @SchemaMapping(typeName = "Workspace")
    fun sessionCompactionKeepTurnsDefault(workspace: Workspace): Int =
        installation.sessionCompactionKeepTurns()

    /** And how long its summary may run. Issue #522. */
    @SchemaMapping(typeName = "Workspace")
    fun sessionCompactionSummaryTokensDefault(workspace: Workspace): Int =
        installation.sessionCompactionSummaryTokens()

    /** And how many times one turn may be compacted. Issue #522. */
    @SchemaMapping(typeName = "Workspace")
    fun sessionCompactionAttemptsDefault(workspace: Workspace): Int =
        installation.sessionCompactionAttempts()

    /**
     * The installation's script timeout, in seconds, for the same box: the
     * default the workspace inherits while it has decided nothing.
     *
     * One number behind both boxes, because the installation bounds a script
     * rather than an occasion: what the operator set is how long this server
     * will let any sandbox hold a thread. The two workspace settings are about
     * what the work is worth waiting for, which is a judgement the workspace
     * makes and the operator does not.
     */
    @SchemaMapping(typeName = "Workspace")
    fun functionTimeoutSecondsDefault(workspace: Workspace): Int =
        (scriptProperties.timeoutMillis / 1000).toInt().coerceAtLeast(1)

    /** The same number, for the tool box. See above. */
    @SchemaMapping(typeName = "Workspace")
    fun toolTimeoutSecondsDefault(workspace: Workspace): Int =
        (scriptProperties.timeoutMillis / 1000).toInt().coerceAtLeast(1)

    /**
     * Chooses the model the workspace uses for its own small jobs.
     *
     * Anyone who can see the workspace may set it: it is a workspace setting,
     * not an administrative one, and the person whose chats get named is the
     * one who cares what names them. Null clears it, which switches those jobs
     * off rather than falling back to something unasked for.
     */
    @MutationMapping
    @Transactional
    fun setWorkspaceCompanionModel(@Argument workspaceId: Long, @Argument modelId: Long?): Workspace {
        val workspace = repository.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)

        val chosen = modelId?.let { models.model(it) ?: throw ModelNotFoundForWorkspaceException(it) }
        if (chosen != null && chosen.workspaceId != workspaceId) throw ModelNotFoundForWorkspaceException(modelId)

        workspace.companionModelId = chosen?.id
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.MODEL,
            if (chosen == null) "Companion model cleared" else "Companion model set to ${chosen.name}",
        )
        return workspace
    }

    /**
     * Chooses the model the workspace hears with.
     *
     * The same rule as the companion model: whoever can see the workspace may
     * set it, and null switches the microphone off rather than guessing at a
     * substitute. Only a transcription model will do — a chat model handed
     * audio answers something, and what it answers is not a transcript.
     */
    @MutationMapping
    @Transactional
    fun setWorkspaceTranscriptionModel(@Argument workspaceId: Long, @Argument modelId: Long?): Workspace {
        val workspace = repository.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)

        val chosen = modelId?.let { models.model(it) ?: throw ModelNotFoundForWorkspaceException(it) }
        if (chosen != null && chosen.workspaceId != workspaceId) throw ModelNotFoundForWorkspaceException(modelId)
        if (chosen != null && chosen.kind != ModelKind.TRANSCRIPTION) {
            throw ModelNotTranscriptionException(chosen.name)
        }

        workspace.transcriptionModelId = chosen?.id
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.MODEL,
            if (chosen == null) "Transcription model cleared" else "Transcription model set to ${chosen.name}",
        )
        return workspace
    }

    /**
     * Chooses the model the workspace speaks with.
     *
     * The mirror of the one above, and refused the same way: only a speech model
     * will do, since a chat model handed an answer would talk *about* it rather
     * than read it. Null takes the speaker away.
     */
    @MutationMapping
    @Transactional
    fun setWorkspaceSpeechModel(@Argument workspaceId: Long, @Argument modelId: Long?): Workspace {
        val workspace = repository.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)

        val chosen = modelId?.let { models.model(it) ?: throw ModelNotFoundForWorkspaceException(it) }
        if (chosen != null && chosen.workspaceId != workspaceId) throw ModelNotFoundForWorkspaceException(modelId)
        if (chosen != null && chosen.kind != ModelKind.SPEECH) {
            throw ModelNotSpeechException(chosen.name)
        }

        workspace.speechModelId = chosen?.id
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.MODEL,
            if (chosen == null) "Speech model cleared" else "Speech model set to ${chosen.name}",
        )
        return workspace
    }

    /**
     * When a chat is summarised, how short the summary has to be, and what
     * writes it.
     *
     * One mutation for the three because they are one decision: a threshold
     * without a summariser is a setting that does nothing, and a summariser
     * without a threshold is a model nobody calls. Null for `afterTokens` turns
     * it off, which is where every workspace starts.
     *
     * The model is refused unless it is a chat model, for the reason the three
     * above are refused: an image model asked to summarise a conversation draws
     * something, and finding that out is a compaction that has already thrown
     * the older half away. Issue #286.
     */
    @MutationMapping
    @Transactional
    fun setWorkspaceCompaction(
        @Argument workspaceId: Long,
        @Argument afterTokens: Int?,
        @Argument summaryTokens: Int?,
        @Argument modelId: Long?,
    ): Workspace {
        val workspace = repository.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)

        if (afterTokens != null && afterTokens <= 0) throw CompactionThresholdInvalidException()
        if (summaryTokens != null && summaryTokens <= 0) throw CompactionSummaryInvalidException()
        /*
         * A summary as long as the conversation is not a summary. Refused rather
         * than clamped, because the two numbers together are the setting and
         * silently changing one of them is a screen that does not say what it
         * did.
         */
        if (afterTokens != null && summaryTokens != null && summaryTokens >= afterTokens) {
            throw CompactionSummaryTooLongException()
        }

        val chosen = modelId?.let { models.model(it) ?: throw ModelNotFoundForWorkspaceException(it) }
        if (chosen != null && chosen.workspaceId != workspaceId) throw ModelNotFoundForWorkspaceException(modelId)
        if (chosen != null && chosen.kind != ModelKind.CHAT) throw ModelNotChatException(chosen.name)

        workspace.compactAfterTokens = afterTokens
        workspace.compactionSummaryTokens = summaryTokens
        workspace.compactionModelId = chosen?.id
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.MODEL,
            if (afterTokens == null) {
                "Chat compaction turned off"
            } else {
                "Chat compaction set to $afterTokens tokens" +
                    (chosen?.let { ", summarised by ${it.name}" } ?: "")
            },
        )
        return workspace
    }

    /**
     * Chooses the model the workspace draws with.
     *
     * The third of the trio, refused the same way: only an image model will do,
     * since a chat model handed a prompt writes about the picture instead of
     * drawing it and there is no endpoint on it that would. Null takes the
     * picture button out of the composer.
     */
    @MutationMapping
    @Transactional
    fun setWorkspaceImageModel(@Argument workspaceId: Long, @Argument modelId: Long?): Workspace {
        val workspace = repository.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)

        val chosen = modelId?.let { models.model(it) ?: throw ModelNotFoundForWorkspaceException(it) }
        if (chosen != null && chosen.workspaceId != workspaceId) throw ModelNotFoundForWorkspaceException(modelId)
        if (chosen != null && chosen.kind != ModelKind.IMAGE) {
            throw ModelNotImageException(chosen.name)
        }

        workspace.imageModelId = chosen?.id
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.MODEL,
            if (chosen == null) "Image model cleared" else "Image model set to ${chosen.name}",
        )
        return workspace
    }

    /**
     * Chooses the model behind the quick chat.
     *
     * A chat model, unlike the two above: this one is asked questions and calls
     * orknux's own tools to answer them, so a model that only listens or only
     * reads aloud would have nothing to do here. Null takes the button away.
     */
    @MutationMapping
    @Transactional
    fun setWorkspaceQuickChatModel(@Argument workspaceId: Long, @Argument modelId: Long?): Workspace {
        val workspace = repository.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)

        val chosen = modelId?.let { models.model(it) ?: throw ModelNotFoundForWorkspaceException(it) }
        if (chosen != null && chosen.workspaceId != workspaceId) throw ModelNotFoundForWorkspaceException(modelId)
        if (chosen != null && chosen.kind != ModelKind.CHAT) throw ModelNotChatException(chosen.name)

        workspace.quickChatModelId = chosen?.id
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.MODEL,
            if (chosen == null) "Quick chat model cleared" else "Quick chat model set to ${chosen.name}",
        )
        return workspace
    }

    /**
     * Whether the quick chat may change things, or only look at them.
     *
     * Recorded either way: this is the setting that decides whether a panel
     * somebody opened to ask a question can act on the workspace, and "who
     * turned that on" is a question worth being able to answer afterwards.
     */
    @MutationMapping
    @Transactional
    fun setWorkspaceQuickChatWrites(@Argument workspaceId: Long, @Argument allowed: Boolean): Workspace {
        val workspace = repository.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)

        workspace.quickChatMayWrite = allowed
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.MODEL,
            if (allowed) "Quick chat allowed to make changes" else "Quick chat limited to reading",
        )
        return workspace
    }

    /**
     * Whether this workspace's chats show when each message was sent.
     *
     * A display choice rather than a capability, so it is not audited the way
     * the write switch is - but it is per workspace rather than per person,
     * because a chat read by two people should read the same. Issue #323.
     */
    @MutationMapping
    @Transactional
    fun setWorkspaceChatTimestamps(@Argument workspaceId: Long, @Argument shown: Boolean): Workspace {
        val workspace = repository.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)

        workspace.chatShowTimestamps = shown
        return workspace
    }

    /**
     * What agents in this workspace are given when they ask for nothing.
     *
     * The middle step of three - the agent's own share, then this, then the
     * built-in allowance - and it exists because the per-agent setting is the
     * right place to make an exception and the wrong place to state a policy.
     * An installation that has decided its agents should remember more than the
     * built-in allowance was saying so once per agent, again on every agent
     * made afterwards, and could only read the decision back by looking at
     * every row.
     *
     * Null clears it, which puts every agent that sets nothing back on the
     * built-in allowance rather than leaving them on the last value; that is
     * the same rule the agent's own share follows, and it is what lets a form
     * offer "the default" as a thing to choose.
     *
     * Only the bounds are checked, and the sentence is the one the agent form
     * raises because it comes from the same calculation. What is *not* checked
     * is everything that needs a model - a window too small to carry an
     * exchange, a model reserving most of its window for its answer - because
     * this default is not tied to a model. A workspace runs several at once
     * whose windows differ by an order of magnitude, so refusing a default
     * because the smallest of them could not give it would refuse a setting
     * that is right for every other model in the workspace. Those refusals
     * still happen, in `SessionMemoryBudgets`, against the model an agent
     * actually uses, at the point its budget is worked out.
     *
     * Whoever can see the workspace may set it, like the model settings above
     * and unlike the roles: it is a workspace setting rather than an
     * administrative one, and it is recorded either way.
     */
    @MutationMapping
    @Transactional
    fun setWorkspaceDefaultMemoryShare(@Argument workspaceId: Long, @Argument share: Int?): Workspace {
        val workspace = repository.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)

        budgets.resolveDefault(share).refusal?.let { throw WorkspaceMemoryShareUnusableException(it) }

        workspace.defaultMemoryShare = share
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.MODEL,
            share?.let { "Agents default to $it% of their model's context window" }
                ?: "Agent memory default cleared",
        )
        return workspace
    }

    /**
     * How many times a task in this workspace may ask its model.
     *
     * Null clears it, which puts the workspace back on the installation's own
     * number - the same shape the memory share above has, and for the same
     * reason: "this workspace has no opinion" is a real answer and is not the
     * same as any particular count.
     *
     * Read when a task is created and copied onto its row, so this decides what
     * the next task is given and leaves the ones already running alone.
     */
    @MutationMapping
    @Transactional
    fun setWorkspaceTaskMaxTurns(@Argument workspaceId: Long, @Argument turns: Int?): Workspace {
        val workspace = repository.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)

        if (turns != null && turns !in MIN_TASK_TURNS..MAX_TASK_TURNS) throw TaskTurnsOutOfRangeException(turns)

        workspace.taskMaxTurns = turns
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.WORKSPACE,
            turns?.let { "A task may take $it turns" } ?: "The turns a task may take are the installation's again",
        )
        return workspace
    }

    /**
     * What marks a command in a message that starts a run here.
     *
     * A workspace's own rather than the installation's, because it is a
     * convention of the people typing in that workspace's Slack. Refused
     * rather than trimmed to fit, and refused for a letter or a digit,
     * because a marker like that turns ordinary words into commands. Issue
     * #381.
     */
    @MutationMapping
    @Transactional
    fun setWorkspaceCommandMarker(@Argument workspaceId: Long, @Argument marker: String?): Workspace {
        val workspace = repository.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)

        val wanted = marker?.trim()?.ifEmpty { null }
        if (wanted == null) {
            workspace.commandMarker = null
            auditRecorder.record(workspaceId, WorkspaceAuditCategory.WORKSPACE, "Commands are marked as the installation says again")
            return workspace
        }
        if (!io.mszymanski.orknux.server.trigger.Commands.usableMarker(wanted)) throw CommandMarkerInvalidException(wanted)
        workspace.commandMarker = wanted
        auditRecorder.record(workspaceId, WorkspaceAuditCategory.WORKSPACE, "Commands in a message are marked with $wanted")
        return workspace
    }

    /** What marks a command here when the workspace has said nothing: the installation's. Issue #402. */
    @SchemaMapping(typeName = "Workspace")
    fun commandMarkerDefault(workspace: Workspace): String = installation.commandMarker()

    /**
     * How many other agents one agent in this workspace may ask in one
     * conversation.
     *
     * Null clears it and puts the workspace back on Admin -> Settings; zero is
     * a real answer and takes the tool off the table here. Read at the moment
     * an agent asks, so it applies to the next ask and not to conversations
     * already had. Issue #380.
     */
    /**
     * Whether this workspace's agents may have a built-in tool hidden. Issue #482.
     *
     * Off, and the screen says why: the tools the server brings are what the
     * product is built on, and an agent missing one behaves in ways nothing
     * here can stand behind. What this opens is the ability to switch them,
     * never the switching itself - the rows stay where they are, and become
     * pressable.
     */
    @MutationMapping
    @Transactional
    fun setWorkspaceUnsafeBuiltInTools(@Argument workspaceId: Long, @Argument allowed: Boolean): Workspace {
        val workspace = repository.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)

        workspace.unsafeBuiltInTools = allowed
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.WORKSPACE,
            if (allowed) {
                "Built-in tools may be hidden from this workspace's agents"
            } else {
                "Built-in tools are offered to every agent in this workspace again"
            },
        )
        return workspace
    }

    @MutationMapping
    @Transactional
    fun setWorkspaceAgentMaxSubagents(@Argument workspaceId: Long, @Argument count: Int?): Workspace {
        val workspace = repository.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)

        if (count != null && count !in MIN_SUBAGENTS..MAX_SUBAGENTS) {
            throw io.mszymanski.orknux.server.attachment.SubagentsOutOfRangeException(count)
        }

        workspace.agentMaxSubagents = count
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.WORKSPACE,
            count?.let { "An agent may ask $it other agents in a conversation" }
                ?: "How many agents one may ask is the installation's again",
        )
        return workspace
    }

    /**
     * How many tool calls one message here may ask for at once. Issue #518.
     *
     * Null clears it, back onto the installation's number - the same shape as
     * the asks above. Read per round, so this decides the next message and
     * leaves one already in flight alone.
     */
    @MutationMapping
    @Transactional
    fun setWorkspaceMaxToolCallsAtOnce(@Argument workspaceId: Long, @Argument count: Int?): Workspace {
        val workspace = repository.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)

        if (count != null && count !in MIN_CALLS_AT_ONCE..MAX_CALLS_AT_ONCE) {
            throw io.mszymanski.orknux.server.attachment.ToolCallsAtOnceOutOfRangeException(count)
        }

        workspace.maxToolCallsAtOnce = count
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.WORKSPACE,
            count?.let { "One message here may ask for $it tool calls" }
                ?: "How many calls one message may ask for is the installation's again",
        )
        return workspace
    }

    /**
     * Compacting a turn here that has outgrown its model. Issue #522.
     *
     * Four numbers rather than one switch, because a compaction is a trade and
     * the terms are the workspace's: how much of the recent end is worth keeping
     * word for word, how long a summary of the rest may be, how many times to
     * try before giving up, and which model writes it. Null on any of them takes
     * the installation's - and null on the model means the turn's own.
     */
    @MutationMapping
    @Transactional
    fun setWorkspaceSessionCompaction(
        @Argument workspaceId: Long,
        @Argument afterTokens: Int?,
        @Argument keepTurns: Int?,
        @Argument summaryTokens: Int?,
        @Argument attempts: Int?,
        @Argument modelId: Long?,
    ): Workspace {
        val workspace = repository.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)

        if (keepTurns != null && keepTurns !in MIN_KEEP_TURNS..MAX_KEEP_TURNS) {
            throw io.mszymanski.orknux.server.attachment.CompactionKeepOutOfRangeException(keepTurns)
        }
        if (summaryTokens != null && summaryTokens !in MIN_SUMMARY_TOKENS..MAX_SUMMARY_TOKENS) {
            throw io.mszymanski.orknux.server.attachment.CompactionSummaryOutOfRangeException(summaryTokens)
        }
        if (attempts != null && attempts !in MIN_COMPACTIONS..MAX_COMPACTIONS) {
            throw io.mszymanski.orknux.server.attachment.CompactionAttemptsOutOfRangeException(attempts)
        }

        if (afterTokens != null && afterTokens != 0 &&
            afterTokens !in MIN_COMPACT_AFTER..MAX_COMPACT_AFTER
        ) {
            throw io.mszymanski.orknux.server.attachment.CompactAfterOutOfRangeException(afterTokens)
        }

        workspace.sessionCompactAfterTokens = afterTokens
        workspace.sessionCompactionKeepTurns = keepTurns
        workspace.sessionCompactionSummaryTokens = summaryTokens
        workspace.sessionCompactionAttempts = attempts
        workspace.sessionCompactionModelId = modelId
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.WORKSPACE,
            "How a turn here is compacted when it outgrows its model was changed",
        )
        return workspace
    }

    /**
     * How long this workspace's tools and functions may run, where they have no
     * timeout of their own.
     *
     * Null clears it, which puts the workspace back on the installation's own
     * bound - the same shape the task turns above have, and for the same
     * reason. Read per call, so this decides the next run and leaves the ones
     * already going alone.
     */
    @MutationMapping
    @Transactional
    fun setWorkspaceFunctionTimeout(@Argument workspaceId: Long, @Argument seconds: Int?): Workspace {
        val workspace = repository.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)

        if (seconds != null && seconds !in MIN_SCRIPT_TIMEOUT_SECONDS..MAX_SCRIPT_TIMEOUT_SECONDS) {
            throw ScriptTimeoutOutOfRangeException(seconds)
        }

        workspace.functionTimeoutSeconds = seconds
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.WORKSPACE,
            seconds?.let { "A function may run for $it seconds" }
                ?: "The time a function may run is the installation's again",
        )
        return workspace
    }

    /**
     * And how long a tool an agent called may run for.
     *
     * Its own setting rather than the one above, because the wait belongs to
     * somebody: a model is stopped mid-turn until the tool answers and, in a
     * chat, a person is watching that happen. Twenty seconds is patience in a
     * workflow and a failure in a conversation.
     *
     * The same bounds and the same null. Read per call, so this decides the
     * next tool call and leaves the ones already going alone.
     */
    @MutationMapping
    @Transactional
    fun setWorkspaceToolTimeout(@Argument workspaceId: Long, @Argument seconds: Int?): Workspace {
        val workspace = repository.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)

        if (seconds != null && seconds !in MIN_SCRIPT_TIMEOUT_SECONDS..MAX_SCRIPT_TIMEOUT_SECONDS) {
            throw ScriptTimeoutOutOfRangeException(seconds)
        }

        workspace.toolTimeoutSeconds = seconds
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.WORKSPACE,
            seconds?.let { "A tool an agent calls may run for $it seconds" }
                ?: "The time a tool an agent calls may run is the installation's again",
        )
        return workspace
    }

    /**
     * How voice mode decides somebody has finished talking, for this workspace.
     *
     * Three settings and one call, because they are one decision. The pause is
     * what ends a turn, the ratio decides who counts as talking while it is
     * running, and the fuse is what happens when no pause ever comes; changing
     * one without seeing the other two is how a workspace ends up with a
     * generous pause that a ratio never lets it reach.
     *
     * All three are stated on every call and null clears one, which puts it
     * back on voice mode's own value rather than leaving it on whatever was set
     * last - the same rule `setWorkspaceDefaultMemoryShare` follows, and what
     * lets a form offer "the default" as a thing to choose. Nothing here states
     * what those values are: they belong to the interface, and a copy on this
     * side would be a second source of truth that drifts the first time either
     * moves. Null travels to the client as null and the client supplies its
     * own.
     *
     * Only the bounds are checked, and each refusal names what is allowed
     * rather than saying no - see [VoiceTurnTaking] for why each bound is where
     * it is. Whoever can see the workspace may set it, like the model settings
     * above and unlike the roles: it is a workspace setting rather than an
     * administrative one, and it is recorded either way.
     */
    @MutationMapping
    @Transactional
    fun setWorkspaceVoiceTurnTaking(
        @Argument workspaceId: Long,
        @Argument pauseEndsTurnMs: Int?,
        @Argument speechOverRoomPercent: Int?,
        @Argument unattendedMicrophoneMs: Int?,
        @Argument bargeInMs: Int?,
    ): Workspace {
        val workspace = repository.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)

        VoiceTurnTaking.refusalFor(pauseEndsTurnMs, speechOverRoomPercent, unattendedMicrophoneMs, bargeInMs)
            ?.let { throw WorkspaceVoiceTurnTakingUnusableException(it) }

        workspace.voicePauseEndsTurnMs = pauseEndsTurnMs
        workspace.voiceSpeechOverRoomPercent = speechOverRoomPercent
        workspace.voiceUnattendedMicrophoneMs = unattendedMicrophoneMs
        workspace.voiceBargeInMs = bargeInMs

        auditRecorder.record(
            workspaceId,
            // What happens inside a chat: the microphone is a chat's, not a model's.
            WorkspaceAuditCategory.CHAT,
            VoiceTurnTaking.audit(pauseEndsTurnMs, speechOverRoomPercent, unattendedMicrophoneMs, bargeInMs),
        )
        return workspace
    }

    /**
     * Where an answer is cut for the speech provider, for this workspace.
     *
     * Its own call rather than a fourth argument on the one above, although the
     * form draws both on one card and saves them with one press. Turn-taking is
     * three numbers that are one decision - a pause a sensitivity never lets the
     * microphone reach is not a setting anybody meant to make - and this is not
     * part of that decision: it is about the half of a turn the model is
     * talking, and nothing about it can contradict any of those three.
     *
     * Nothing to check and nothing to refuse. The three values are the whole of
     * what may be asked for, and the schema is what says so - an enum arriving
     * off the wire has already been one of them or the request never reached
     * here. Whoever can see the workspace may set it, like the settings above.
     */
    @MutationMapping
    @Transactional
    fun setWorkspaceVoiceSpeechChunking(
        @Argument workspaceId: Long,
        @Argument chunking: SpeechChunking,
    ): Workspace {
        val workspace = repository.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)

        workspace.voiceSpeechChunking = chunking

        auditRecorder.record(
            workspaceId,
            // The same category as turn-taking: what a chat does, not what a
            // model is.
            WorkspaceAuditCategory.CHAT,
            when (chunking) {
                SpeechChunking.NONE -> "Answers are read aloud in one piece"
                SpeechChunking.SENTENCE -> "Answers are read aloud a sentence at a time"
                SpeechChunking.PARAGRAPH -> "Answers are read aloud a paragraph at a time"
            },
        )
        return workspace
    }

    @MutationMapping
    @Transactional
    fun deleteWorkspace(@Argument id: Long): Boolean {
        access.requireAdmin()
        val workspace = repository.findByIdOrNull(id) ?: return false
        repository.delete(workspace)
        // workspace_connection has no foreign key to workspace — the module owns its own
        // tables — so what was held for this workspace is dropped explicitly.
        connections.forgetWorkspace(id)
        // And its run history, for the same reason and with the same shape:
        // workflow_execution carries no foreign key on the workspace either, so
        // without this the rows stay for ever, reachable by nothing. Issue #167.
        val runs = executions.forgetWorkspace(id)
        if (runs > 0) log.info("Forgot {} runs belonging to workspace {}", runs, id)
        auditRecorder.record(
            workspaceId = id,
            operationType = WorkspaceOperationType.REMOVE,
            oldWorkspaceName = workspace.name,
        )
        return true
    }
}

data class CreateWorkspaceInput(
    val name: String,
    val description: String? = null,
)

data class UpdateWorkspaceInput(
    val name: String,
    val description: String? = null,
    /** The roles that open this workspace. Null leaves them alone; empty means administrators only. */
    val roleIds: List<Long>? = null,
    /**
     * The roles that also administer it, which has to be a subset of [roleIds].
     *
     * Null leaves them alone; empty means installation administrators only, which is
     * what every workspace has until somebody decides otherwise. Only an installation
     * administrator may change this - a workspace administrator sending back the list
     * they were shown is not changing it and is not refused.
     */
    val adminRoleIds: List<Long>? = null,
)

data class WorkspacePage(
    val content: List<Workspace>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    constructor(page: Page<Workspace>) : this(
        content = page.content,
        page = page.number,
        size = page.size,
        totalElements = page.totalElements.toInt(),
        totalPages = page.totalPages,
    )
}

/** What a duplicate came to, as a screen reads it. Issue #408. */
data class WorkspaceCopyView(
    val workspace: Workspace,
    /** Connections, providers and MCP servers that need their credentials set. Issue #570. */
    val credentialsToSet: List<String> = emptyList(),
    /** How many of each kind were carried, in the order they were carried. */
    val carried: List<CopiedKind>,
    /**
     * Variables the copy could not bring, by name.
     *
     * Named rather than counted: each is something somebody has to go and set,
     * and a number would leave them hunting for which.
     */
    val variablesToSet: List<String>,
    /** Anything a component could not bring, said as the importer said it. */
    val problems: List<String>,
)

data class CopiedKind(val kind: String, val count: Int)

class WorkspaceNotFoundException(val id: Long) : RuntimeException("No workspace with id $id"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

class WorkspaceNameTakenException(val name: String) :
    RuntimeException("A workspace named \"$name\" already exists"), Refusal {

    override val arguments get() = mapOf("name" to name)
}

class WorkspaceNameInvalidException : RuntimeException("A workspace name is required")

/**
 * A role was told to administer a workspace it is not assigned to.
 *
 * Refused rather than assigned quietly, because what it would produce is a role
 * that administers a workspace nobody holding it can open - a permission that
 * looks granted on the settings page and does nothing at all. The sentence names
 * the roles, since the fix is to add them above and save again.
 */
class WorkspaceAdminRoleNotAssignedException(val names: List<String>) : RuntimeException(
    "${names.joinToString(", ")} cannot administer this workspace without being assigned to it. " +
        "Add them to the roles that open it, then mark them as administering.",
), Refusal {

    override val arguments get() = mapOf("names" to names)
}

/** A model chosen for a workspace has to be one of that workspace's own. */
class ModelNotTranscriptionException(name: String) : RuntimeException(
    "$name is not a transcription model. A microphone needs one that turns speech into text; " +
        "add one under Models with the transcription kind.",
)

class ModelNotChatException(name: String) : RuntimeException(
    "$name is not a chat model. The quick chat asks questions and calls tools to answer them, " +
        "which is something only a chat model does.",
)

class ModelNotSpeechException(name: String) : RuntimeException(
    "$name is not a speech model. Reading an answer aloud needs one that turns text into speech; " +
        "add one under Models with the speech kind.",
)

class ModelNotImageException(name: String) : RuntimeException(
    "$name is not an image model. Drawing a picture needs one that turns text into an image; " +
        "add one under Models with the image kind.",
)

class ModelNotFoundForWorkspaceException(id: Long) :
    RuntimeException("No model with id $id in this workspace")

/**
 * A default share of a context window that could not work, refused where it was set.
 *
 * Carries the sentence `SessionMemoryBudgets` wrote, which is the same sentence
 * the agent form is refused with, from the same check - two surfaces set the
 * same setting and must not come to disagree about which numbers are allowed.
 */
class WorkspaceMemoryShareUnusableException(message: String) : RuntimeException(message)

/**
 * One turn, and two hundred.
 *
 * The floor is one because a task that may ask its model once is a real thing
 * to want: it is how somebody tries a prompt without paying for a loop. The
 * ceiling is there because this is the count that bounds the bill, and a number
 * typed with one digit too many is the mistake it exists to catch - the working
 * time stops a runaway task either way, but hours later and after the model
 * calls have been made.
 */
const val MIN_TASK_TURNS = 1
const val MAX_TASK_TURNS = 200

class CommandMarkerInvalidException(val marker: String) : RuntimeException(
    "\"$marker\" cannot mark a command: one to three characters, none of them a letter, a digit or a space",
), io.mszymanski.orknux.server.graphql.Refusal {

    override val arguments get() = mapOf("marker" to marker)
}

class TaskTurnsOutOfRangeException(val turns: Int) : RuntimeException(
    "$turns is not a number of turns a task can be given. " +
        "Choose between $MIN_TASK_TURNS and $MAX_TASK_TURNS.",
)

/**
 * The bounds a script timeout is held to, wherever one is set — the workspace's
 * default and a tool's or function's own number are the same judgement at two
 * scopes, so they answer to the same limits.
 *
 * The ceiling is high enough for a genuinely long call and low enough that a
 * timeout typed in milliseconds by mistake is caught here rather than holding a
 * sandbox thread for a fortnight.
 */
const val MIN_SCRIPT_TIMEOUT_SECONDS = 1
const val MAX_SCRIPT_TIMEOUT_SECONDS = 600

class ScriptTimeoutOutOfRangeException(val seconds: Int) : RuntimeException(
    "$seconds is not a number of seconds a script can be given. " +
        "Choose between $MIN_SCRIPT_TIMEOUT_SECONDS and $MAX_SCRIPT_TIMEOUT_SECONDS.",
)

/**
 * The bounds voice mode's turn-taking settings are held to, and the reasons.
 *
 * Bounds only, and nothing that resembles a default: what a workspace that has
 * decided nothing gets belongs to the interface, which is the half that can
 * judge it, and every number named here is a limit rather than a value anybody
 * is given. The units are the interface's own too, so nothing converts at the
 * boundary - the other place a second source of truth hides.
 */
object VoiceTurnTaking {

    /**
     * A pause between one and a half and ten seconds.
     *
     * The floor sits strictly above 1.2 seconds, which is the value that was
     * demonstrated to cut people off at clause breaks: people stop to think in
     * the middle of a sentence and every one of those stops was read as their
     * turn ending. Putting the floor above it is what stops the reported bug
     * from being reproducible through configuration.
     *
     * Ten seconds is the ceiling because past it nothing happening no longer
     * reads as the application being patient; it reads as the application being
     * broken, and somebody starts talking again to see whether it is alive.
     */
    val pauseEndsTurnMs = 1_500..10_000

    /**
     * A voice between 1.2 and 6 times the room, as a percentage of it.
     *
     * Below about 1.2 the ratio cannot separate a voice from the room it is
     * spoken in, and the failure inverts: a breath or a keystroke clears the
     * line, holds the turn open, and the turn never ends at all. Above 6 you
     * have to raise your voice to be heard, which is the complaint this setting
     * exists to answer in the first place.
     */
    val speechOverRoomPercent = 120..600

    /**
     * An unattended microphone between five minutes and an hour.
     *
     * Thirty seconds and two minutes both looked like defensible limits on a
     * turn and both cut the same person off mid-sentence, so the floor has to
     * be well clear of a long spoken thought rather than merely above whatever
     * failed last. It is not a limit on how much anybody may say: it fires only
     * where no pause occurred in the whole span, which is extraordinary for a
     * person and ordinary for a microphone left open in an empty room.
     *
     * An hour is the ceiling because a fuse that never blows is not a fuse.
     */
    val unattendedMicrophoneMs = 300_000..3_600_000

    /**
     * Talking over the answer for between a quarter of a second and three.
     *
     * The floor is where a noise stops being one: a cough, a door and this
     * application's own voice getting past the echo cancellation are all short,
     * and somebody interrupting keeps talking. Below a quarter of a second the
     * answer stops on any of them, which is worse than not stopping at all -
     * the failure is loud, constant and in the middle of every reply.
     *
     * Three seconds is the ceiling because by then the sentence somebody
     * interrupted with is finished and they are talking to something that is
     * still talking back. Past that it is not an interruption, it is two people
     * speaking at once.
     *
     * Zero is outside the range and means it: off, for a room or a microphone
     * where the answer keeps stopping on nothing. See [refusalFor].
     */
    val bargeInMs = 250..3_000

    /**
     * The first of the three that is out of bounds, said as a sentence.
     *
     * Each names what is allowed rather than reporting that something was
     * refused, because "out of range" tells whoever typed it nothing about what
     * to type instead.
     */
    fun refusalFor(pauseMs: Int?, speechPercent: Int?, unattendedMs: Int?, bargeMs: Int? = null): String? = when {
        pauseMs != null && pauseMs !in pauseEndsTurnMs ->
            "A pause that ends a turn has to be between 1.5 and 10 seconds " +
                "(${pauseEndsTurnMs.first} to ${pauseEndsTurnMs.last} ms). " +
                "Below that it lands in the middle of a sentence; above it the microphone looks broken."

        speechPercent != null && speechPercent !in speechOverRoomPercent ->
            "A voice has to stand between ${speechOverRoomPercent.first}% and ${speechOverRoomPercent.last}% " +
                "of the room to be heard as one. Below that a breath holds the turn open and it never ends; " +
                "above it you have to raise your voice."

        unattendedMs != null && unattendedMs !in unattendedMicrophoneMs ->
            "An unattended microphone has to give up between 5 minutes and an hour " +
                "(${unattendedMicrophoneMs.first} to ${unattendedMicrophoneMs.last} ms). " +
                "It is a fuse for a microphone nobody is at, not a limit on how long anybody may talk."

        /*
         * Zero is allowed and is not in the range: it is the one value that
         * means "do not do this at all", which a room with bad echo needs and
         * which no number inside the range can say.
         */
        bargeMs != null && bargeMs != 0 && bargeMs !in bargeInMs ->
            "Talking over the answer has to hold for between ${bargeInMs.first} and ${bargeInMs.last} ms " +
                "before it stops, or be 0 to leave the answer alone. Below that a cough stops it; " +
                "above it the interruption is over before anything happens."

        else -> null
    }

    /** One line for the log, whichever were set and whichever were cleared. */
    fun audit(pauseMs: Int?, speechPercent: Int?, unattendedMs: Int?, bargeMs: Int? = null): String =
        if (pauseMs == null && speechPercent == null && unattendedMs == null && bargeMs == null) {
            "Voice turn-taking back to the default"
        } else {
            "Voice turn-taking set to a pause of ${pauseMs?.let { "$it ms" } ?: "the default"}, " +
                "a voice at ${speechPercent?.let { "$it%" } ?: "the default"} of the room, " +
                "an unattended microphone of ${unattendedMs?.let { "$it ms" } ?: "the default"} and " +
                "talking over the answer " +
                when (bargeMs) {
                    null -> "for the default"
                    0 -> "turned off"
                    else -> "after $bargeMs ms"
                }
        }
}

/**
 * A voice turn-taking setting outside its bounds, refused where it was set.
 *
 * Refused rather than clamped, because what a clamp produces is a form that
 * saves a number nobody typed and a microphone that behaves in a way the
 * settings page does not describe. The sentence names what is allowed instead.
 */
class WorkspaceVoiceTurnTakingUnusableException(message: String) : RuntimeException(message)


/**
 * The three refusals compaction has of its own.
 *
 * Said in numbers somebody can act on rather than as "invalid": what is wrong
 * with a threshold of zero is that it would compact every turn, and a screen
 * that only says the value was rejected leaves that to be guessed.
 */
class CompactionThresholdInvalidException : RuntimeException(
    "Compaction fires above a number of tokens, so that number has to be more than zero. " +
        "Leave it empty to turn compaction off.",
)

class CompactionSummaryInvalidException : RuntimeException(
    "A summary has to be allowed some length, so its budget has to be more than zero.",
)

class CompactionSummaryTooLongException : RuntimeException(
    "The summary is allowed to be as long as the conversation that triggers compaction, " +
        "which would compact nothing. Give it a smaller budget than the threshold.",
)
