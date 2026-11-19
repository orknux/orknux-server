package io.mszymanski.orknux.server.diagram

import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceNotFoundException
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional

/**
 * The door onto [DiagramSources]: what a diagram's code and Jira shapes last
 * read, one shape read again, and the workspace's choice of Jira connection.
 *
 * A read is not audited - a diagram opened asks for every stale shape in view,
 * and a line per shape per visit would bury the log. Choosing the connection
 * is, as the workspace's other choices are.
 */
@Controller
class DiagramSourcesAPI(
    private val sources: DiagramSources,
    private val diagrams: DiagramAPI,
    private val nodes: DiagramNodeRepository,
    private val workspaces: WorkspaceRepository,
    private val connections: WorkspaceConnectionRepository,
    private val access: WorkspaceAccess,
    private val audit: WorkspaceAuditRecorder,
) {

    @QueryMapping
    @Transactional(readOnly = true)
    fun diagramSources(@Argument diagramId: Long): List<DiagramSourceView> {
        val diagram = diagrams.find(diagramId)
        val workspace = workspaces.findByIdOrNull(diagram.workspaceId) ?: throw WorkspaceNotFoundException(diagram.workspaceId)
        val shapes = nodes.findByDiagramIdOrderByPositionAsc(diagramId).mapNotNull { node ->
            val kind = DiagramNodeKind.byKey(node.kind)?.let(DiagramSourceKind::of) ?: return@mapNotNull null
            node.source?.let { kind to it }
        }
        return sources.cached(workspace, shapes)
    }

    @MutationMapping
    fun fetchDiagramSource(
        @Argument diagramId: Long,
        @Argument kind: DiagramSourceKind,
        @Argument source: String,
        @Argument force: Boolean?,
    ): DiagramSourceView {
        val diagram = diagrams.find(diagramId)
        val workspace = workspaces.findByIdOrNull(diagram.workspaceId) ?: throw WorkspaceNotFoundException(diagram.workspaceId)
        val said = source.trim()
        val readable = when (kind) {
            DiagramSourceKind.CODE -> DiagramCodeSource.of(said) != null
            DiagramSourceKind.JIRA -> DiagramJiraSource.of(said) != null
        }
        if (!readable) throw DiagramSourceInvalidException(said)
        return sources.fetch(workspace, kind, said, force == true)
    }

    @MutationMapping
    @Transactional
    fun setWorkspaceDiagramSources(
        @Argument workspaceId: Long,
        @Argument jiraConnectionId: Long?,
        @Argument fetchMinutes: Int?,
    ): Workspace {
        val workspace = workspaces.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireAdministers(workspace)
        // One of this workspace's Jira connections - of the Jira plugin's own kind, which is the only kind it can read through.
        val jira = jiraConnectionId?.let { id ->
            connections.findByIdOrNull(id)?.takeIf { it.workspaceId == workspaceId && it.pluginType == DiagramSourceKind.JIRA_CONNECTION }
                ?: throw DiagramJiraConnectionUnfitException(id.toString())
        }
        if (fetchMinutes != null && (fetchMinutes < 0 || fetchMinutes > MOST_MINUTES)) throw DiagramFetchMinutesInvalidException(fetchMinutes)
        // What was read through another Jira is not what this one says.
        if (workspace.diagramJiraConnectionId != jira?.id) sources.forget(workspace, DiagramSourceKind.JIRA)
        workspace.diagramJiraConnectionId = jira?.id
        workspace.diagramFetchMinutes = fetchMinutes
        audit.record(
            workspaceId,
            WorkspaceAuditCategory.WORKSPACE,
            "Diagram Jira shapes read through ${jira?.name ?: "the Jira plugin's connection"}, " +
                "copies kept ${fetchMinutes ?: DiagramSources.DEFAULT_MINUTES} minutes",
        )
        return workspace
    }

    private companion object {
        /** A week; a format - past it a copy is a snapshot, which is not what these shapes are. */
        const val MOST_MINUTES = 7 * 24 * 60
    }
}

class DiagramSourceInvalidException(val source: String) :
    RuntimeException("$source is neither a GitHub file's address nor a Jira issue key"), Refusal {
    override val arguments get() = mapOf("source" to source)
}

class DiagramJiraConnectionUnfitException(val name: String) :
    RuntimeException("$name is not one of this workspace's Jira connections, which is what a diagram's Jira shapes read through"),
    Refusal {
    override val arguments get() = mapOf("name" to name)
}

class DiagramFetchMinutesInvalidException(val minutes: Int) :
    RuntimeException("A copy is kept from 0 to 10080 minutes, not $minutes"), Refusal {
    override val arguments get() = mapOf("minutes" to minutes)
}
