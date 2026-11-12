package io.mszymanski.orknux.server.recyclebin

import io.mszymanski.orknux.server.action.FunctionNameTakenException
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
import io.mszymanski.orknux.server.design.DocumentAPI
import io.mszymanski.orknux.server.diagram.DiagramAPI
import io.mszymanski.orknux.server.revision.ComponentSnapshot
import io.mszymanski.orknux.server.security.ActingIn
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.pageRequest
import org.springframework.context.annotation.Lazy
import org.springframework.data.domain.Page
import org.springframework.data.domain.Sort
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
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
 * document its pictures, a diagram its drawing. The restored object gets a new
 * id, which is safe because nothing referenced the old one: a delete is refused
 * while anything does.
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
    private val mapper: ObjectMapper,
    /** The three kinds restored through their own door, which is where what they alone hold is written. */
    @param:Lazy private val skillApi: SkillAPI,
    @param:Lazy private val documentApi: DocumentAPI,
    @param:Lazy private val diagramApi: DiagramAPI,
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
        recycleBin.remove(entry)
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
    constructor(entry: RecycleBinEntry) : this(
        id = requireNotNull(entry.id),
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
    constructor(page: Page<RecycleBinEntry>) : this(
        content = page.content.map(::RecycleBinEntryView),
        page = page.number,
        size = page.size,
        totalElements = page.totalElements.toInt(),
        totalPages = page.totalPages,
    )
}
