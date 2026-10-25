package io.mszymanski.orknux.server.revision

import io.mszymanski.orknux.server.action.WorkflowFunction
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentSkill
import io.mszymanski.orknux.server.agent.AgentTool
import io.mszymanski.orknux.server.design.DesignDocument
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.workflow.WorkflowPublication
import io.mszymanski.orknux.server.workflow.WorkflowPublicationRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/**
 * One component, as one call has to describe it to be written down.
 *
 * Assembled by the caller rather than read off the entity here, because the
 * four kinds keep the same four facts in four different classes and a recorder
 * that reflected over them would be a recorder that silently misses a field
 * somebody adds.
 */
data class RevisionSubject(
    val workspaceId: Long,
    val componentId: Long,
    val name: String,
    /** When *this* state was saved — the component's own stamp. */
    val savedAt: OffsetDateTime,
    val savedBy: String,
    val snapshot: String,
    /** Which door [savedAt]'s save came through, where the kind says; see [ComponentRevision.door]. */
    val door: String? = null,
)

/**
 * Writes a component's history, wherever the change came from.
 *
 * Called from every door, exactly as `IssueHistoryRecorder` is and for the
 * lesson that one learnt: the tracker's history had a hole in it precisely
 * where the MCP tools wrote, because only the browser's door was covered. An
 * agent switching an agent off through `orknux_set_agent_enabled` is the same
 * thing happening to that agent as a person pressing the toggle, and a history
 * that only knew about the toggle would be missing the half nobody watches.
 *
 * **The rule about what counts as a version lives here**, in
 * [ComponentRevisionKind.release], and not at the call sites. Every door says
 * what happened — this was saved, this was published — and this decides whether
 * that made a version. So the day a function or a tool grows a draft, its kind
 * changes its answer and every call site is already right.
 */
@Service
class ComponentRevisionRecorder(
    private val revisions: ComponentRevisionRepository,
    private val publications: WorkflowPublicationRepository,
    private val mapper: ObjectMapper,
    private val tuning: Tuning = Tuning.DEFAULTS,
) {

    /**
     * A component of [kind] was saved, and [subject] is what it was *before*.
     *
     * The displaced state, not the new one: the live row is already the newest
     * version, so recording what it is about to stop being is what makes the
     * history complete without anything being written twice.
     *
     * [subject] is built only if it is going to be used. For a kind whose
     * versions come from publishing, this writes nothing and the snapshot is
     * never serialised — which is what makes it safe to call from a draft save
     * on every keystroke's worth of graph.
     */
    fun saved(kind: ComponentRevisionKind, subject: () -> RevisionSubject) {
        if (kind.release != ComponentRelease.SAVE) return
        val held = subject()
        revisions.save(
            ComponentRevision(
                workspaceId = held.workspaceId,
                kind = kind,
                componentId = held.componentId,
                // The columns' widths: a document's name and a signed-in name may be longer than a revision keeps.
                name = held.name.take(NAME_LENGTH),
                savedAt = held.savedAt,
                savedBy = held.savedBy.take(NAME_LENGTH),
                snapshot = held.snapshot,
                door = held.door,
            ),
        )
        if (kind == ComponentRevisionKind.DOCUMENT) prune(kind, held.componentId)
    }

    /** What a function was before this save. Nothing is written for a plugin's. */
    fun saved(function: WorkflowFunction) {
        val workspaceId = function.workspaceId ?: return
        val id = function.id ?: return
        saved(ComponentRevisionKind.FUNCTION) {
            RevisionSubject(
                workspaceId = workspaceId,
                componentId = id,
                name = function.name,
                savedAt = function.lastModifiedAt,
                savedBy = function.lastModifiedBy,
                snapshot = ComponentSnapshot.of(function, mapper),
            )
        }
    }

    fun saved(tool: AgentTool) {
        val id = tool.id ?: return
        saved(ComponentRevisionKind.TOOL) {
            RevisionSubject(
                workspaceId = tool.workspaceId,
                componentId = id,
                name = tool.name,
                savedAt = tool.lastModifiedAt,
                savedBy = tool.lastModifiedBy,
                snapshot = ComponentSnapshot.of(tool, mapper),
            )
        }
    }

    fun saved(skill: AgentSkill) {
        val id = skill.id ?: return
        saved(ComponentRevisionKind.SKILL) {
            RevisionSubject(
                workspaceId = skill.workspaceId,
                componentId = id,
                name = skill.name,
                savedAt = skill.lastModifiedAt,
                savedBy = skill.lastModifiedBy,
                snapshot = ComponentSnapshot.of(skill, mapper),
            )
        }
    }

    fun saved(agent: Agent) {
        val id = agent.id ?: return
        saved(ComponentRevisionKind.AGENT) {
            RevisionSubject(
                workspaceId = agent.workspaceId,
                componentId = id,
                name = agent.name,
                savedAt = agent.lastModifiedAt,
                savedBy = agent.lastModifiedBy,
                snapshot = ComponentSnapshot.of(agent, mapper),
            )
        }
    }

    /**
     * What a document was before this save, and the door that saved it.
     *
     * Every door calls this - the editor, an import, the document chat, the
     * orknux_* tools, a restore - through `DocumentAPI`, which is the one
     * place a document's content is written. Pruned to the count kept as it
     * is written, so a document saved a thousand times holds what was asked.
     */
    fun saved(document: DesignDocument) {
        val id = document.id ?: return
        saved(ComponentRevisionKind.DOCUMENT) {
            RevisionSubject(
                workspaceId = document.workspaceId,
                componentId = id,
                name = document.name,
                savedAt = document.updatedAt,
                savedBy = document.updatedBy,
                snapshot = ComponentSnapshot.of(document, mapper),
                door = document.updatedVia,
            )
        }
    }

    /**
     * Drops what is past the count a kind keeps per component, oldest first.
     * Zero keeps them all. Answers how many went.
     */
    fun prune(kind: ComponentRevisionKind, componentId: Long): Int {
        val kept = keptPer(kind) ?: return 0
        val beyond = revisions.idsNewestFirst(kind, componentId).drop(kept)
        if (beyond.isNotEmpty()) revisions.deleteAllByIdInBatch(beyond)
        return beyond.size
    }

    /** Every component of [kind] pruned to its count: what the sweep does after the count is lowered. */
    fun pruneAll(kind: ComponentRevisionKind): Int {
        val kept = keptPer(kind) ?: return 0
        return revisions.componentsHoldingMoreThan(kind, kept.toLong()).sumOf { prune(kind, it) }
    }

    /** How many revisions a kind keeps per component, or null where it is not kept by count. */
    private fun keptPer(kind: ComponentRevisionKind): Int? = when (kind) {
        ComponentRevisionKind.DOCUMENT -> tuning[Tunable.DOCUMENT_VERSIONS_KEPT].takeIf { it > 0 }
        else -> null
    }

    /**
     * A workflow was published, and the publication is kept.
     *
     * It used to be one row per workflow, overwritten every time, which is what
     * made "what did this run last month" unanswerable. Keeping them is the
     * whole of a workflow's history: for a kind whose release is
     * [ComponentRelease.PUBLISH], the publication *is* the version, and there
     * is nothing to write into [ComponentRevision]'s table that would not be a
     * second copy of a graph the runner already reads from here.
     *
     * The newest row is what runs, so restoring publishes again rather than
     * reviving an old row — [restoredFrom] says which one was copied.
     */
    fun published(
        workflowId: Long,
        graph: String,
        by: String,
        restoredFrom: Long? = null,
    ): WorkflowPublication = publications.save(
        WorkflowPublication(
            workflowId = workflowId,
            publishedAt = OffsetDateTime.now(),
            publishedBy = by,
            graph = graph,
            restoredFrom = restoredFrom,
        ),
    )

    /**
     * Everything kept about a component that has just been deleted.
     *
     * By hand because there is no foreign key to do it: the rows point into
     * whichever of four tables their kind names. A workspace being deleted is
     * the database's to cascade, and does.
     */
    fun forget(kind: ComponentRevisionKind, componentId: Long) {
        if (!kind.stored) return
        revisions.deleteByKindAndComponentId(kind, componentId)
    }

    /** One component's history, newest first, no more than [limit] of it. */
    fun history(kind: ComponentRevisionKind, componentId: Long, limit: Int): List<RevisionSummary> =
        if (!kind.stored) {
            emptyList()
        } else {
            revisions.summaries(
                kind,
                componentId,
                PageRequest.of(0, limit.coerceIn(1, MOST)),
            )
        }

    private companion object {
        /** More than a screen shows, and less than a table nobody wanted to read. */
        const val MOST = 200

        /** `component_revision.name` and `saved_by`: a format, not a behaviour. */
        const val NAME_LENGTH = 120
    }
}
