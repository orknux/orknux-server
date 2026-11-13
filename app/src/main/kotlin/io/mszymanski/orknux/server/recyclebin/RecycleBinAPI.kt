package io.mszymanski.orknux.server.recyclebin

import io.mszymanski.orknux.connector.connection.WorkspaceConnectionService
import io.mszymanski.orknux.server.action.ActionNameTakenException
import io.mszymanski.orknux.server.action.ActionSubtype
import io.mszymanski.orknux.server.action.ActionType
import io.mszymanski.orknux.server.action.FunctionNameTakenException
import io.mszymanski.orknux.server.action.FunctionScope
import io.mszymanski.orknux.server.action.WorkflowAction
import io.mszymanski.orknux.server.action.WorkflowActionRepository
import io.mszymanski.orknux.server.action.WorkflowFunction
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentNameTakenException
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentTool
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.agent.SkillAPI
import io.mszymanski.orknux.server.agent.ToolNameTakenException
import io.mszymanski.orknux.server.condition.ConditionAPI
import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
import io.mszymanski.orknux.server.design.DocumentAPI
import io.mszymanski.orknux.server.design.SheetAPI
import io.mszymanski.orknux.server.design.WireframeAPI
import io.mszymanski.orknux.server.diagram.DiagramAPI
import io.mszymanski.orknux.server.issue.IssueBin
import io.mszymanski.orknux.server.memory.Memory
import io.mszymanski.orknux.server.memory.MemoryCatalog
import io.mszymanski.orknux.server.memory.MemoryCatalogNameTakenException
import io.mszymanski.orknux.server.memory.MemoryCatalogRepository
import io.mszymanski.orknux.server.memory.MemoryRepository
import io.mszymanski.orknux.server.obj.ObjectAPI
import io.mszymanski.orknux.server.revision.ComponentSnapshot
import io.mszymanski.orknux.server.security.ActingIn
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.trigger.TriggerAPI
import io.mszymanski.orknux.server.variable.VariableAPI
import io.mszymanski.orknux.server.workflow.PictureBin
import io.mszymanski.orknux.server.workflow.WorkflowNameTakenException
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflow
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.pageRequest
import org.springframework.context.annotation.Lazy
import org.springframework.data.domain.Page
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * A workspace's recycle bin: what has been deleted, and putting one of them back.
 *
 * The dual of `ComponentRevisionAPI`, and shaped like it. A restore re-creates
 * the object from the snapshot the delete wrote: the three code kinds inline
 * here, with the same `ComponentSnapshot.restore` a version restore uses, onto a
 * fresh row rather than the live one; a skill, a document and a diagram through
 * their own doors, because each knows what it alone holds - a skill its id, a
 * document its pictures, a diagram its drawing, and a wireframe, a sheet, a
 * picture and an issue the same way. A memory catalog or a skill catalog comes
 * back as the folder it was - its name, and everything it held, in it - rather
 * than as its contents one at a time. The restored object gets a new id, which
 * is safe because nothing referenced the old one: a delete is refused while
 * anything does.
 *
 * A workflow is the one kind that is not re-created. Its definition was never
 * removed - only taken off the workspace - so restoring it assigns it again,
 * with its graph, its publications, its triggers and its runs as they were.
 *
 * Whoever may see the workspace may restore and purge, which is the same gate
 * the delete itself had - the bin does not widen who may undo a deletion, only
 * how long there is to.
 */
@Controller
class RecycleBinAPI(
    private val recycleBin: RecycleBin,
    private val agents: AgentRepository,
    private val tools: AgentToolRepository,
    private val functions: WorkflowFunctionRepository,
    private val actions: WorkflowActionRepository,
    private val mapper: ObjectMapper,
    /** The three kinds restored through their own door, which is where what they alone hold is written. */
    @param:Lazy private val skillApi: SkillAPI,
    @param:Lazy private val conditionApi: ConditionAPI,
    @param:Lazy private val triggerApi: TriggerAPI,
    @param:Lazy private val objectApi: ObjectAPI,
    @param:Lazy private val variableApi: VariableAPI,
    private val conditions: WorkflowConditionRepository,
    private val connections: WorkspaceConnectionService,
    @param:Lazy private val documentApi: DocumentAPI,
    @param:Lazy private val diagramApi: DiagramAPI,
    @param:Lazy private val sheetApi: SheetAPI,
    @param:Lazy private val wireframeApi: WireframeAPI,
    @param:Lazy private val pictureBin: PictureBin,
    @param:Lazy private val issueBin: IssueBin,
    private val memoryCatalogs: MemoryCatalogRepository,
    private val memories: MemoryRepository,
    private val workflows: WorkflowRepository,
    private val assignments: WorkspaceWorkflowRepository,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    @QueryMapping
    @Transactional(readOnly = true)
    fun recycleBin(
        @Argument workspaceId: Long,
        @Argument page: Int?,
        @Argument size: Int?,
    ): RecycleBinPage {
        access.requireVisible(workspaceId)
        // The order is baked into the repository method; the sort here only pages.
        val pageable = pageRequest(page, size, Sort.unsorted())
        return RecycleBinPage(recycleBin.entries(workspaceId, pageable))
    }

    @MutationMapping
    @Transactional
    fun restoreRecycleBinEntry(@Argument id: Long): Boolean {
        val entry = recycleBin.entry(id) ?: throw RecycleBinEntryNotFoundException(id)
        access.requireVisible(entry.workspaceId)
        val workspaceId = entry.workspaceId
        val name = ComponentSnapshot.nameIn(entry.snapshot, mapper).ifBlank { entry.name }

        when (entry.kind) {
            RecycleBinKind.AGENT -> {
                if (agents.findByWorkspaceIdAndName(workspaceId, name) != null) throw AgentNameTakenException(name)
                val agent = Agent(workspaceId = workspaceId, name = name, type = AgentType.LLM)
                ComponentSnapshot.restore(agent, entry.snapshot, mapper)
                agent.lastModifiedAt = OffsetDateTime.now()
                agent.lastModifiedBy = currentUser()
                agents.save(agent)
            }

            RecycleBinKind.TOOL -> {
                if (tools.findByWorkspaceIdAndName(workspaceId, name) != null) throw ToolNameTakenException(name)
                val tool = AgentTool(workspaceId = workspaceId, name = name, source = "", typescript = "")
                ComponentSnapshot.restore(tool, entry.snapshot, mapper)
                tool.lastModifiedAt = OffsetDateTime.now()
                tool.lastModifiedBy = currentUser()
                tools.save(tool)
            }

            RecycleBinKind.FUNCTION -> {
                if (functions.findByWorkspaceIdAndName(workspaceId, name) != null) throw FunctionNameTakenException(name)
                // A workspace function (the only kind binned): scope defaults to WORKSPACE, so editable.
                val function = WorkflowFunction(workspaceId = workspaceId, name = name, source = "")
                ComponentSnapshot.restore(function, entry.snapshot, mapper)
                function.lastModifiedAt = OffsetDateTime.now()
                function.lastModifiedBy = currentUser()
                functions.save(function)
            }

            RecycleBinKind.SKILL -> skillApi.recreateFromBin(workspaceId, entry.originalCatalogId, entry.snapshot)
            RecycleBinKind.DOCUMENT -> documentApi.recreate(workspaceId, entry.originalCatalogId, entry.snapshot)
            RecycleBinKind.DIAGRAM -> diagramApi.recreate(workspaceId, entry.originalCatalogId, entry.snapshot)
            RecycleBinKind.SHEET -> sheetApi.recreate(workspaceId, entry.originalCatalogId, entry.snapshot)

            RecycleBinKind.ACTION -> {
                if (actions.findByWorkspaceIdAndName(workspaceId, name) != null) throw ActionNameTakenException(name)
                // The placeholders are overwritten at once from the snapshot; a restored action re-joins the catalogue.
                val action = WorkflowAction(workspaceId = workspaceId, name = name, type = ActionType.entries.first(), subtype = ActionSubtype.entries.first())
                ComponentSnapshot.restore(action, entry.snapshot, mapper)
                /*
                 * What it points at is looked for again rather than trusted.
                 * Nothing holds a function in place for an action that is in
                 * the bin, so it may have been deleted since - and put back,
                 * under another id. The two columns are foreign keys, so an id
                 * nothing answers to is not an action with a gap in it but a
                 * restore that fails as an internal error.
                 */
                val held = mapper.readTree(entry.snapshot)
                action.functionId = BinReference.at(held, "function").resolve(
                    "action", name, "the function",
                    byId = { id -> functions.findByIdOrNull(id)?.takeIf { it.scope != FunctionScope.WORKSPACE || it.workspaceId == workspaceId } },
                    byName = { functions.findByWorkspaceIdAndName(workspaceId, it) },
                )?.id
                action.conditionId = BinReference.at(held, "condition").resolve(
                    "action", name, "the condition",
                    byId = { id -> conditions.findByIdOrNull(id)?.takeIf { it.workspaceId == workspaceId } },
                    byName = { conditions.findByWorkspaceIdAndWorkflowIdIsNullAndName(workspaceId, it) },
                )?.id
                action.connectionId = BinReference.at(held, "connection").resolve(
                    "action", name, "the connection",
                    byId = { id -> connections.workspaceConnection(id)?.takeIf { it.workspaceId == workspaceId } },
                    byName = { said -> connections.workspaceConnections(workspaceId).firstOrNull { it.name == said } },
                )?.id
                actions.save(action)
            }

            RecycleBinKind.CONDITION -> conditionApi.recreate(workspaceId, mapper.readTree(entry.snapshot))
            RecycleBinKind.TRIGGER -> triggerApi.recreate(workspaceId, mapper.readTree(entry.snapshot))
            RecycleBinKind.OBJECT -> objectApi.recreate(workspaceId, mapper.readTree(entry.snapshot))
            RecycleBinKind.VARIABLE -> variableApi.recreate(workspaceId, entry.originalCatalogId, mapper.readTree(entry.snapshot))

            RecycleBinKind.WIREFRAME -> wireframeApi.recreate(workspaceId, entry.originalCatalogId, entry.snapshot)
            RecycleBinKind.IMAGE -> pictureBin.recreate(workspaceId, entry.originalCatalogId, entry.snapshot)
            RecycleBinKind.ISSUE -> issueBin.recreate(workspaceId, entry.snapshot)

            RecycleBinKind.MEMORY -> {
                val tree = mapper.readTree(entry.snapshot)
                val written = timeIn(tree.path("createdAt"))
                memories.save(
                    Memory(
                        catalogId = requireNotNull(memoryCatalogFor(workspaceId, entry.originalCatalogId).id),
                        title = name,
                        content = tree.path("content").asString(),
                        createdAt = written ?: OffsetDateTime.now(),
                        createdBy = tree.path("createdBy").takeIf { it.isString }?.asString() ?: currentUser(),
                        lastModifiedAt = OffsetDateTime.now(),
                        lastModifiedBy = currentUser(),
                    ),
                )
            }

            RecycleBinKind.MEMORY_CATALOG -> {
                // A folder's name is how an agent is granted it, so two of one name cannot be told apart.
                if (memoryCatalogs.findByWorkspaceIdAndName(workspaceId, name) != null) throw MemoryCatalogNameTakenException(name)
                val tree = mapper.readTree(entry.snapshot)
                val catalog = requireNotNull(
                    memoryCatalogs.save(
                        MemoryCatalog(
                            workspaceId = workspaceId,
                            name = name,
                            createdAt = timeIn(tree.path("createdAt")) ?: OffsetDateTime.now(),
                            createdBy = textIn(tree.path("createdBy")) ?: currentUser(),
                        ),
                    ).id,
                )
                tree.path("memories").values().forEach { held ->
                    memories.save(
                        Memory(
                            catalogId = catalog,
                            title = held.path("name").asString(),
                            content = held.path("content").asString(),
                            createdAt = timeIn(held.path("createdAt")) ?: OffsetDateTime.now(),
                            createdBy = textIn(held.path("createdBy")) ?: currentUser(),
                            lastModifiedAt = timeIn(held.path("lastModifiedAt")) ?: OffsetDateTime.now(),
                            lastModifiedBy = textIn(held.path("lastModifiedBy")) ?: currentUser(),
                        ),
                    )
                }
                /*
                 * And back to the agents that had it, those of them still here:
                 * the delete took the grant from them by name, and a folder put
                 * back that nobody could read would be back only on this screen.
                 */
                val had = tree.path("grantedTo").values().filter { it.isNumber }.map { it.asLong() }
                agents.findAllById(had)
                    .filter { it.workspaceId == workspaceId && name !in it.memoryCatalogs }
                    .forEach {
                        it.memoryCatalogs.add(name)
                        agents.save(it)
                    }
            }

            RecycleBinKind.SKILL_CATALOG -> skillApi.recreateCatalogFromBin(workspaceId, entry.snapshot)

            RecycleBinKind.WORKFLOW -> {
                val workflow = workflows.findByIdOrNull(entry.originalId) ?: throw RecycleBinWorkflowGoneException(name)
                // Names are unique among what a workspace has, and something may have taken this one since.
                if (assignments.existsByWorkspaceIdAndWorkflowName(workspaceId, workflow.name)) {
                    throw WorkflowNameTakenException(workflow.name)
                }
                val enabled = mapper.readTree(entry.snapshot).path("enabled").asBoolean(true)
                assignments.save(WorkspaceWorkflow(workspaceId = workspaceId, workflow = workflow, enabled = enabled))
            }
        }

        recycleBin.remove(entry)
        auditRecorder.record(workspaceId, entry.kind.auditCategory, "${entry.kind.label} $name restored from the recycle bin")
        return true
    }

    @MutationMapping
    @Transactional
    fun purgeRecycleBinEntry(@Argument id: Long): Boolean {
        val entry = recycleBin.entry(id) ?: throw RecycleBinEntryNotFoundException(id)
        access.requireVisible(entry.workspaceId)
        recycleBin.purge(entry)
        auditRecorder.record(
            entry.workspaceId,
            entry.kind.auditCategory,
            "${entry.kind.label} ${entry.name} deleted from the recycle bin",
        )
        return true
    }

    @MutationMapping
    @Transactional
    fun emptyRecycleBin(@Argument workspaceId: Long): Int {
        access.requireVisible(workspaceId)
        val removed = recycleBin.empty(workspaceId)
        if (removed > 0) auditRecorder.record(workspaceId, WorkspaceAuditCategory.WORKSPACE, "Recycle bin emptied")
        return removed
    }

    /**
     * Where a restored memory is filed: the catalog it was in while that is
     * still this workspace's, and otherwise the one the workspace always has -
     * a catalog deleted with its memories does not come back with them.
     */
    private fun memoryCatalogFor(workspaceId: Long, catalogId: Long?): MemoryCatalog {
        catalogId?.let(memoryCatalogs::findByIdOrNull)?.takeIf { it.workspaceId == workspaceId }?.let { return it }
        val held = memoryCatalogs.findByWorkspaceIdOrderByNameAsc(workspaceId)
        return held.firstOrNull { it.isDefault } ?: held.firstOrNull()
            ?: memoryCatalogs.save(MemoryCatalog(workspaceId = workspaceId, name = "General", createdBy = currentUser(), isDefault = true))
    }

    /** A moment a snapshot wrote down, or null where it wrote none that can be read. */
    private fun timeIn(node: JsonNode): OffsetDateTime? =
        node.takeIf { it.isString }?.let { runCatching { OffsetDateTime.parse(it.asString()) }.getOrNull() }

    private fun textIn(node: JsonNode): String? = node.takeIf { it.isString }?.asString()

    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name
            ?: ActingIn.actor()
            ?: "system"
}

/** One line of a workspace's recycle bin. */
data class RecycleBinEntryView(
    val id: Long,
    val kind: RecycleBinKind,
    val name: String,
    val deletedAt: String,
    val deletedBy: String,
) {
    constructor(entry: RecycleBinLine) : this(
        id = entry.id,
        kind = entry.kind,
        name = entry.name,
        deletedAt = entry.deletedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        deletedBy = entry.deletedBy,
    )
}

data class RecycleBinPage(
    val content: List<RecycleBinEntryView>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    constructor(page: Page<RecycleBinLine>) : this(
        content = page.content.map(::RecycleBinEntryView),
        page = page.number,
        size = page.size,
        totalElements = page.totalElements.toInt(),
        totalPages = page.totalPages,
    )
}
