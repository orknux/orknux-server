package io.mszymanski.orknux.server.recyclebin

import io.mszymanski.orknux.server.action.WorkflowAction
import io.mszymanski.orknux.server.action.WorkflowFunction
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentSkill
import io.mszymanski.orknux.server.agent.AgentTool
import io.mszymanski.orknux.server.agent.SkillCatalog
import io.mszymanski.orknux.server.condition.WorkflowCondition
import io.mszymanski.orknux.server.design.DesignDocument
import io.mszymanski.orknux.server.design.Sheet
import io.mszymanski.orknux.server.design.Wireframe
import io.mszymanski.orknux.server.diagram.Diagram
import io.mszymanski.orknux.server.issue.Issue
import io.mszymanski.orknux.server.memory.Memory
import io.mszymanski.orknux.server.memory.MemoryCatalog
import io.mszymanski.orknux.server.obj.WorkflowObject
import io.mszymanski.orknux.server.revision.ComponentSnapshot
import io.mszymanski.orknux.server.security.ActingIn
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.trigger.WorkflowTrigger
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.variable.WorkspaceVariable
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflow
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.time.OffsetDateTime

/**
 * Where a deleted object is put down whole, and where the clock takes it away.
 *
 * Called from the one place each kind is deleted - the `delete…` mutation - just
 * before the row leaves its table, with the same `ComponentSnapshot` a version
 * is kept in. The object is removed exactly as before, so nothing else in the
 * application has to change: a binned object is simply gone, the way a deleted
 * one has always been, and every list, unique-name constraint and dependants
 * check reads the same. What is new is only the row left behind here, which
 * [RecycleBinAPI] lists and restores and this sweeps.
 *
 * The snapshot is built here, per kind, rather than at the six call sites, so
 * the format lives in one place as `ComponentRevisionRecorder`'s does - except a
 * diagram's, whose drawing only its own door can read, so the delete hands it in.
 */
@Service
class RecycleBin(
    private val entries: RecycleBinRepository,
    private val mapper: ObjectMapper,
    private val tuning: Tuning = Tuning.DEFAULTS,
    /** A binned workflow's definition, which stays where it is until its entry goes and is removed with it. */
    private val workflows: WorkflowRepository? = null,
    private val assignments: WorkspaceWorkflowRepository? = null,
) {

    fun bin(agent: Agent) =
        put(RecycleBinKind.AGENT, agent.workspaceId, agent.id, null, agent.name, ComponentSnapshot.of(agent, mapper))

    fun bin(tool: AgentTool) =
        put(RecycleBinKind.TOOL, tool.workspaceId, tool.id, null, tool.name, ComponentSnapshot.of(tool, mapper))

    /** An organisation-level function belongs to no workspace, so it has no bin to go to; it is simply deleted. */
    fun bin(function: WorkflowFunction) {
        val workspaceId = function.workspaceId ?: return
        put(RecycleBinKind.FUNCTION, workspaceId, function.id, null, function.name, ComponentSnapshot.of(function, mapper))
    }

    fun bin(skill: AgentSkill) =
        put(RecycleBinKind.SKILL, skill.workspaceId, skill.id, skill.catalogId, skill.name, ComponentSnapshot.of(skill, mapper))

    fun bin(document: DesignDocument) =
        put(RecycleBinKind.DOCUMENT, document.workspaceId, document.id, document.catalogId, document.name, ComponentSnapshot.of(document, mapper))

    /**
     * A diagram, with its drawing already written: the delete hands it in,
     * because the whole picture - its nodes, lines and layers - is rows only
     * `DiagramAPI` reads, and `DiagramAPI.snapshot` is what reads them.
     */
    fun bin(diagram: Diagram, drawing: String) =
        put(RecycleBinKind.DIAGRAM, diagram.workspaceId, diagram.id, diagram.catalogId, diagram.name, drawing)

    /** A sheet, with its workbook already written: the delete hands the snapshot in. */
    fun bin(sheet: Sheet, snapshot: String) =
        put(RecycleBinKind.SHEET, sheet.workspaceId, sheet.id, sheet.catalogId, sheet.name, snapshot)

    /**
     * An action: every setting and mapping, as [ComponentSnapshot] writes it. No
     * catalog - actions are a flat library.
     *
     * [names] is what it points at, by the field the id is kept under -
     * `function`, `condition`, `connection` - so a restore can find by name
     * what has itself been deleted and put back since; see [BinReference].
     */
    fun bin(action: WorkflowAction, names: Map<String, String?> = emptyMap()) {
        val snapshot = mapper.readTree(ComponentSnapshot.of(action, mapper)) as ObjectNode
        names.forEach { (field, name) -> name?.let { snapshot.put("${field}Name", it) } }
        put(RecycleBinKind.ACTION, action.workspaceId, action.id, null, action.name, mapper.writeValueAsString(snapshot))
    }

    /** A condition, as `ConditionAPI` wrote it down: what it asks, and what it names by id and by name. */
    fun bin(condition: WorkflowCondition, snapshot: Map<String, Any?>) =
        put(RecycleBinKind.CONDITION, condition.workspaceId, condition.id, null, condition.name, mapper.writeValueAsString(snapshot))

    /** A trigger, as `TriggerAPI` wrote it down. Its firing log is not kept: that is what it did, not what it is. */
    fun bin(trigger: WorkflowTrigger, snapshot: Map<String, Any?>) =
        put(RecycleBinKind.TRIGGER, trigger.workspaceId, trigger.id, null, trigger.name, mapper.writeValueAsString(snapshot))

    /** An object a workspace drew. A plugin's belongs to no workspace and is its plugin's to bring back. */
    fun bin(shape: WorkflowObject, snapshot: Map<String, Any?>) {
        val workspaceId = shape.workspaceId ?: return
        put(RecycleBinKind.OBJECT, workspaceId, shape.id, null, shape.name, mapper.writeValueAsString(snapshot))
    }

    /**
     * A variable, as `VariableAPI` wrote it down - its value still encrypted,
     * by the same cipher the column it came out of is kept in, so a secret is
     * no more readable in the bin than it was in its table.
     */
    fun bin(variable: WorkspaceVariable, snapshot: Map<String, Any?>) =
        put(RecycleBinKind.VARIABLE, variable.workspaceId, variable.id, variable.catalogId, variable.name, mapper.writeValueAsString(snapshot))

    /** A wireframe, with its drawing already written by its own door. */
    fun bin(wireframe: Wireframe, snapshot: String) =
        put(RecycleBinKind.WIREFRAME, wireframe.workspaceId, wireframe.id, wireframe.catalogId, wireframe.name, snapshot)

    /**
     * One memory. Its workspace is its catalog's, so the caller - who has just
     * read the catalog to decide whether this may be deleted at all - says it.
     * Who wrote it and when travel with it: a memory put back is the same
     * memory, not one somebody wrote today.
     */
    fun bin(memory: Memory, workspaceId: Long) = put(
        RecycleBinKind.MEMORY,
        workspaceId,
        memory.id,
        memory.catalogId,
        memory.title,
        mapper.writeValueAsString(
            mapOf(
                "name" to memory.title,
                "content" to memory.content,
                "createdBy" to memory.createdBy,
                "createdAt" to memory.createdAt.toString(),
            ),
        ),
    )

    /**
     * A memory catalog and everything in it, as one entry: the folder is what
     * was deleted, so the folder is what comes back - under its name, with its
     * memories, and granted to the agents that had it. [grantedTo] is those
     * agents by id, read before the delete takes the grant away from them: the
     * grant is a name, and a name alone would hand the folder to whichever
     * agents happen to exist on the day it is restored.
     */
    fun bin(catalog: MemoryCatalog, held: List<Memory>, grantedTo: List<Long>) = put(
        RecycleBinKind.MEMORY_CATALOG,
        catalog.workspaceId,
        catalog.id,
        null,
        catalog.name,
        mapper.writeValueAsString(
            mapOf(
                "name" to catalog.name,
                "createdBy" to catalog.createdBy,
                "createdAt" to catalog.createdAt.toString(),
                "grantedTo" to grantedTo,
                "memories" to held.map {
                    mapOf(
                        "name" to it.title,
                        "content" to it.content,
                        "createdBy" to it.createdBy,
                        "createdAt" to it.createdAt.toString(),
                        "lastModifiedBy" to it.lastModifiedBy,
                        "lastModifiedAt" to it.lastModifiedAt.toString(),
                    )
                },
            ),
        ),
    )

    /**
     * A skill catalog and the skills in it, as one entry. Each skill is the
     * snapshot a skill deleted by itself leaves, so putting the folder back is
     * putting each of them back into it. No grants: a skill catalog an agent
     * draws on is refused deletion, so one that reaches here has none.
     */
    fun bin(catalog: SkillCatalog, held: List<AgentSkill>) = put(
        RecycleBinKind.SKILL_CATALOG,
        catalog.workspaceId,
        catalog.id,
        null,
        catalog.name,
        mapper.writeValueAsString(
            mapOf(
                "name" to catalog.name,
                "createdBy" to catalog.createdBy,
                "createdAt" to catalog.createdAt.toString(),
                "skills" to held.map { ComponentSnapshot.of(it, mapper) },
            ),
        ),
    )

    /**
     * A picture, with its bytes: [snapshot] is what `PictureBin` wrote, the file
     * itself included, because the delete that follows removes the file.
     */
    fun binPicture(workspaceId: Long, id: Long?, catalogId: Long?, name: String, snapshot: String) =
        put(RecycleBinKind.IMAGE, workspaceId, id, catalogId, name, snapshot)

    /** An issue and everything on it - its comments, files, links and history - as `IssueBin` wrote it. */
    fun bin(issue: Issue, snapshot: String) =
        put(RecycleBinKind.ISSUE, issue.workspaceId, issue.id, null, "#${issue.number} ${issue.title}", snapshot)

    /**
     * A workflow taken off a workspace. Unlike every other kind nothing is
     * copied: the definition - its graph, its publications, the triggers and
     * actions it owns, its runs - is left exactly where it is, unreachable
     * because nothing runs or lists a workflow a workspace does not have, and
     * the entry holds its id. Putting it back is assigning it again; it is
     * deleted for good when the entry is, in [purge], [empty] and [sweep].
     */
    fun bin(assignment: WorkspaceWorkflow) = put(
        RecycleBinKind.WORKFLOW,
        assignment.workspaceId,
        assignment.workflow.id,
        null,
        assignment.workflow.name,
        mapper.writeValueAsString(mapOf("name" to assignment.workflow.name, "enabled" to assignment.enabled)),
    )

    private fun put(
        kind: RecycleBinKind,
        workspaceId: Long,
        originalId: Long?,
        catalogId: Long?,
        name: String,
        snapshot: String,
    ) {
        val id = originalId ?: return
        entries.save(
            RecycleBinEntry(
                workspaceId = workspaceId,
                kind = kind,
                originalId = id,
                originalCatalogId = catalogId,
                // The column's width: a document's name may be longer than the bin keeps.
                name = name.take(NAME_LENGTH),
                deletedBy = currentUser().take(NAME_LENGTH),
                snapshot = snapshot,
            ),
        )
    }

    /** A workspace's bin, newest first, a line each: what an entry held is not read until it is restored. */
    fun entries(workspaceId: Long, pageable: Pageable): Page<RecycleBinLine> = entries.lines(workspaceId, pageable)

    fun entry(id: Long): RecycleBinEntry? = entries.findByIdOrNull(id)

    /** Takes a restored entry out of the bin: what it held is back, so nothing else goes. */
    fun remove(entry: RecycleBinEntry) = entries.delete(entry)

    /** Removes one entry for good, by hand, and with it whatever only the entry was keeping. */
    @Transactional
    fun purge(entry: RecycleBinEntry) {
        entries.delete(entry)
        entries.flush()
        if (entry.kind == RecycleBinKind.WORKFLOW) dropDefinitions(listOf(entry.originalId))
    }

    /** Empties one workspace's bin. Answers how many went. */
    @Transactional
    fun empty(workspaceId: Long): Int {
        val definitions = entries.originalsIn(workspaceId, RecycleBinKind.WORKFLOW)
        // By one statement rather than by loading the rows: a row's snapshot may be a picture.
        val removed = entries.deleteByWorkspace(workspaceId)
        dropDefinitions(definitions)
        return removed
    }

    /**
     * One pass: purges everything deleted longer ago than the retention. Returns
     * how many rows went, which is what a test asserts on.
     *
     * The retention is read live, so a change on Admin -> Settings takes on the
     * next pass without a restart - the same as the revision sweep.
     */
    @Transactional
    fun sweep(): Int {
        val cutoff = OffsetDateTime.now().minusDays(tuning[Tunable.RECYCLE_BIN_RETENTION_DAYS].toLong())
        val stale = entries.idsDeletedBefore(cutoff)
        if (stale.isEmpty()) return 0
        val definitions = entries.originalsDeletedBefore(cutoff, RecycleBinKind.WORKFLOW)
        entries.deleteAllByIdInBatch(stale)
        dropDefinitions(definitions)
        log.info("Purged {} recycle bin entries deleted before {}", stale.size, cutoff)
        return stale.size
    }

    /**
     * Deletes the workflow definitions whose bin entries have just gone - the
     * real delete a binned workflow was waiting for. One still assigned to a
     * workspace, or still in another workspace's bin, is somebody else's and is
     * left. Everything a definition owns goes with it by the database's
     * cascades; its runs stay, which name the workflow themselves.
     */
    private fun dropDefinitions(ids: Collection<Long>) {
        val workflows = workflows ?: return
        val assignments = assignments ?: return
        ids.distinct().forEach { id ->
            if (assignments.findByWorkflowId(id).isNotEmpty()) return@forEach
            if (entries.existsByKindAndOriginalId(RecycleBinKind.WORKFLOW, id)) return@forEach
            workflows.findByIdOrNull(id)?.let(workflows::delete)
        }
    }

    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name
            ?: ActingIn.actor()
            ?: "system"

    private companion object {
        /** `recycle_bin.name` and `deleted_by`: a format, not a behaviour. */
        const val NAME_LENGTH = 120

        val log = LoggerFactory.getLogger(RecycleBin::class.java)
    }
}
