package io.mszymanski.orknux.server.revision

import io.mszymanski.orknux.server.action.FunctionExternallyManagedException
import io.mszymanski.orknux.server.action.FunctionNameTakenException
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.agent.AgentNameTakenException
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentSkillRepository
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.agent.SkillCatalogRepository
import io.mszymanski.orknux.server.agent.SkillNameTakenException
import io.mszymanski.orknux.server.agent.ToolNameTakenException
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.data.repository.findByIdOrNull
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
 * A component's history: what it has been, and putting one of those back.
 *
 * A read side of its own rather than fields hung off each component's view,
 * for the reason the issue history has one. A history is opened, not shown —
 * nobody looking at a function wants the last fifty copies of its source
 * fetched alongside it — so the list is asked for when a tab is opened, and the
 * snapshot of one revision only when a row in it is.
 *
 * Workflows are not here. Their versions are their publications, which the
 * workflow's own controller owns because it is what publishes them and what
 * runs them; see `WorkflowGraphAPI.workflowPublications`.
 */
@Controller
class ComponentRevisionAPI(
    private val revisions: ComponentRevisionRepository,
    private val recorder: ComponentRevisionRecorder,
    private val functions: WorkflowFunctionRepository,
    private val tools: AgentToolRepository,
    private val skills: AgentSkillRepository,
    private val catalogs: SkillCatalogRepository,
    private val agents: AgentRepository,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val mapper: ObjectMapper,
) {

    /**
     * One component's history, newest first.
     *
     * Without the snapshots: a tool edited fifty times in an afternoon is fifty
     * copies of its source, and a list is read for its dates and its names.
     */
    @QueryMapping
    @Transactional(readOnly = true)
    fun componentRevisions(
        @Argument kind: ComponentRevisionKind,
        @Argument componentId: Long,
        @Argument limit: Int?,
    ): List<ComponentRevisionView> {
        // Which workspace it is in is the live component's to say, which is
        // also what makes a revision of a deleted component unreachable rather
        // than readable by whoever knows an id.
        access.requireVisible(workspaceOf(kind, componentId))
        return recorder.history(kind, componentId, limit ?: DEFAULT_REVISIONS)
            .map(::ComponentRevisionView)
    }

    /** One revision, with what it held. */
    @QueryMapping
    @Transactional(readOnly = true)
    fun componentRevision(@Argument id: Long): ComponentRevisionDetailView {
        val held = revision(id)
        return ComponentRevisionDetailView.of(held, mapper)
    }

    /**
     * Makes an older state current again.
     *
     * The state it displaces is recorded first, so a restore is undoable by the
     * same button that made it — restoring the wrong revision is exactly the
     * mistake this feature exists to be able to take back, and a restore that
     * ate the state it replaced would be the one edit with no way home.
     *
     * The revision itself is left where it is. Nothing is deleted and nothing
     * is rewritten: the history is what happened, and what happened is that
     * somebody went back to this.
     *
     * Answers whether it was done rather than the component it restored. The
     * four kinds are four different views and a union of them would be a type
     * nobody could write a fragment against for a screen that is about to
     * refetch the component anyway.
     */
    @MutationMapping
    @Transactional
    fun restoreComponentRevision(@Argument id: Long): Boolean {
        val held = revision(id)
        access.requireVisible(held.workspaceId)

        val restoredName = ComponentSnapshot.nameIn(held.snapshot, mapper)
        when (held.kind) {
            ComponentRevisionKind.FUNCTION -> {
                val function = functions.findByIdOrNull(held.componentId)
                    ?: throw RevisionComponentGoneException(held.kind, held.componentId)
                // A plugin's function is the plugin's; loading it again is how
                // it changes, and a snapshot put back here would be overwritten
                // by the next reload anyway.
                if (!function.editable) throw FunctionExternallyManagedException(function.name)
                if (restoredName != function.name &&
                    functions.findByWorkspaceIdAndName(held.workspaceId, restoredName) != null
                ) {
                    throw FunctionNameTakenException(restoredName)
                }
                recorder.saved(function)
                ComponentSnapshot.restore(function, held.snapshot, mapper)
                function.lastModifiedAt = OffsetDateTime.now()
                function.lastModifiedBy = currentUser()
            }

            ComponentRevisionKind.TOOL -> {
                val tool = tools.findByIdOrNull(held.componentId)
                    ?: throw RevisionComponentGoneException(held.kind, held.componentId)
                if (restoredName != tool.name &&
                    tools.findByWorkspaceIdAndName(held.workspaceId, restoredName) != null
                ) {
                    throw ToolNameTakenException(restoredName)
                }
                recorder.saved(tool)
                ComponentSnapshot.restore(tool, held.snapshot, mapper)
                tool.lastModifiedAt = OffsetDateTime.now()
                tool.lastModifiedBy = currentUser()
            }

            ComponentRevisionKind.SKILL -> {
                val skill = skills.findByIdOrNull(held.componentId)
                    ?: throw RevisionComponentGoneException(held.kind, held.componentId)
                if (restoredName != skill.name &&
                    skills.findByWorkspaceIdAndName(held.workspaceId, restoredName) != null
                ) {
                    throw SkillNameTakenException(restoredName)
                }
                recorder.saved(skill)
                val catalog = skill.catalogId
                ComponentSnapshot.restore(skill, held.snapshot, mapper)
                /*
                 * The folder it was in, if it is still there.
                 *
                 * A catalog deleted since takes its skills with it, so this is
                 * only reachable when the skill was moved and the old folder
                 * removed afterwards. Left where it is now rather than filed
                 * into nothing: a skill in no catalog is a skill no agent can
                 * be granted, which is worse than one in the wrong folder.
                 */
                if (catalogs.findByIdOrNull(skill.catalogId)?.workspaceId != held.workspaceId) {
                    skill.catalogId = catalog
                }
                skill.lastModifiedAt = OffsetDateTime.now()
                skill.lastModifiedBy = currentUser()
            }

            ComponentRevisionKind.AGENT -> {
                val agent = agents.findByIdOrNull(held.componentId)
                    ?: throw RevisionComponentGoneException(held.kind, held.componentId)
                if (restoredName != agent.name &&
                    agents.findByWorkspaceIdAndName(held.workspaceId, restoredName) != null
                ) {
                    throw AgentNameTakenException(restoredName)
                }
                recorder.saved(agent)
                ComponentSnapshot.restore(agent, held.snapshot, mapper)
                agent.lastModifiedAt = OffsetDateTime.now()
                agent.lastModifiedBy = currentUser()
            }

            // Restored through the workflow's own door, which is where the
            // publication that is a workflow's version is written and read.
            ComponentRevisionKind.WORKFLOW -> throw RevisionNotRestorableException(restoredName)
        }

        auditRecorder.record(
            held.workspaceId,
            categoryOf(held.kind),
            "${held.kind.name.lowercase().replaceFirstChar { it.uppercase() }} $restoredName restored to the " +
                "version saved on ${DateTimeFormatter.ISO_INSTANT.format(held.savedAt.toInstant())}",
        )
        return true
    }

    private fun revision(id: Long): ComponentRevision {
        val held = revisions.findByIdOrNull(id) ?: throw ComponentRevisionNotFoundException(id)
        access.requireVisible(held.workspaceId)
        return held
    }

    /**
     * The workspace a component is in, asked of the component and not of its
     * revisions.
     *
     * A revision carries the workspace it was written in, and that is what the
     * retention sweep and the cascade use — but a caller listing a history has
     * only an id and a kind, and answering from the rows would let somebody who
     * knows an id read a history for a component they cannot see.
     */
    private fun workspaceOf(kind: ComponentRevisionKind, componentId: Long): Long = when (kind) {
        ComponentRevisionKind.FUNCTION -> functions.findByIdOrNull(componentId)?.workspaceId
        ComponentRevisionKind.TOOL -> tools.findByIdOrNull(componentId)?.workspaceId
        ComponentRevisionKind.SKILL -> skills.findByIdOrNull(componentId)?.workspaceId
        ComponentRevisionKind.AGENT -> agents.workspaceIdOf(componentId)
        ComponentRevisionKind.WORKFLOW -> null
    } ?: throw RevisionComponentGoneException(kind, componentId)

    /** Where the audit line goes, by whose screen the component lives on. */
    private fun categoryOf(kind: ComponentRevisionKind): WorkspaceAuditCategory = when (kind) {
        ComponentRevisionKind.FUNCTION, ComponentRevisionKind.WORKFLOW -> WorkspaceAuditCategory.WORKFLOW
        ComponentRevisionKind.TOOL, ComponentRevisionKind.SKILL, ComponentRevisionKind.AGENT ->
            WorkspaceAuditCategory.AGENT
    }

    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"

    private companion object {
        /** What a history tab asks for without saying. */
        const val DEFAULT_REVISIONS = 25
    }
}

/** One line of a component's history. */
data class ComponentRevisionView(
    val id: Long,
    val kind: ComponentRevisionKind,
    val componentId: Long,
    /** What it was called then, so a rename reads as one. */
    val name: String,
    val savedAt: String,
    val savedBy: String,
    /** When it stopped being current, which is what retention counts from. */
    val recordedAt: String,
) {
    constructor(revision: RevisionSummary) : this(
        id = revision.id,
        kind = revision.kind,
        componentId = revision.componentId,
        name = revision.name,
        savedAt = revision.savedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        savedBy = revision.savedBy,
        recordedAt = revision.recordedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
    )
}

/**
 * One revision with what it held.
 *
 * [content] is the part somebody reads — a function's or a tool's TypeScript, a
 * skill's markdown, an agent's system prompt — pulled out so a screen can show
 * it beside the current one without knowing this format. [snapshot] is the
 * whole thing, for anything that wants the rest.
 */
data class ComponentRevisionDetailView(
    val id: Long,
    val kind: ComponentRevisionKind,
    val componentId: Long,
    val name: String,
    val savedAt: String,
    val savedBy: String,
    val recordedAt: String,
    /** Null where this kind has no prose: an agent with no prompt at all. */
    val content: String?,
    /** `typescript`, `markdown`, or `json`. What to colour [content] as. */
    val contentLanguage: String,
    val snapshot: String,
) {
    companion object {

        /**
         * A factory rather than a second constructor, so the snapshot is read
         * once. A constructor delegating to `this(...)` can only call
         * [ComponentSnapshot.contentIn] separately for each of the two fields
         * it fills, which parses the whole revision twice.
         */
        fun of(revision: ComponentRevision, mapper: ObjectMapper): ComponentRevisionDetailView {
            val read = ComponentSnapshot.contentIn(revision.kind, revision.snapshot, mapper)
            return ComponentRevisionDetailView(
                id = requireNotNull(revision.id),
                kind = revision.kind,
                componentId = revision.componentId,
                name = revision.name,
                savedAt = revision.savedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                savedBy = revision.savedBy,
                recordedAt = revision.recordedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                content = read.text,
                contentLanguage = read.language,
                snapshot = revision.snapshot,
            )
        }
    }
}
