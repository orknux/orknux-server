package io.mszymanski.orknux.server.recyclebin

import io.mszymanski.orknux.server.action.WorkflowFunction
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentSkill
import io.mszymanski.orknux.server.agent.AgentTool
import io.mszymanski.orknux.server.design.DesignDocument
import io.mszymanski.orknux.server.diagram.Diagram
import io.mszymanski.orknux.server.revision.ComponentSnapshot
import io.mszymanski.orknux.server.security.ActingIn
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
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

    /** A workspace's bin, newest first. */
    fun entries(workspaceId: Long, pageable: Pageable): Page<RecycleBinEntry> =
        entries.findByWorkspaceIdOrderByDeletedAtDescIdDesc(workspaceId, pageable)

    fun entry(id: Long): RecycleBinEntry? = entries.findByIdOrNull(id)

    /** Removes one entry, whether it was restored or purged by hand. */
    fun remove(entry: RecycleBinEntry) = entries.delete(entry)

    /** Empties one workspace's bin. Answers how many went. */
    @Transactional
    fun empty(workspaceId: Long): Int {
        val page = entries.findByWorkspaceIdOrderByDeletedAtDescIdDesc(workspaceId, Pageable.unpaged())
        if (!page.isEmpty) entries.deleteAllInBatch(page.content)
        return page.numberOfElements
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
        if (stale.isNotEmpty()) entries.deleteAllByIdInBatch(stale)
        if (stale.isNotEmpty()) log.info("Purged {} recycle bin entries deleted before {}", stale.size, cutoff)
        return stale.size
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
