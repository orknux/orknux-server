package io.mszymanski.orknux.server.integration

import io.mszymanski.orknux.connector.connection.ConnectionInput
import io.mszymanski.orknux.connector.connection.ConnectionPage
import io.mszymanski.orknux.connector.connection.ConnectionService
import io.mszymanski.orknux.connector.connection.ConnectionView
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

/**
 * The admin default connections. The connection module holds them and
 * knows nothing about who is asking; this decides that, and records what was
 * done — which only the part of the platform with users can do.
 */
@Controller
class ConnectionAPI(
    private val connections: ConnectionService,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
) {

    @QueryMapping
    fun connections(
        @Argument page: Int?,
        @Argument size: Int?,
        @Argument order: String?,
        @Argument ascending: Boolean?,
    ): ConnectionPage {
        access.requireAdmin()
        return connections.connections(page, size, order, ascending)
    }

    @QueryMapping
    fun connection(@Argument id: Long): ConnectionView? {
        access.requireAdmin()
        return connections.connection(id)
    }

    @MutationMapping
    fun createConnection(@Argument input: ConnectionInput): ConnectionView {
        access.requireAdmin()
        val created = connections.createConnection(input)

        val name = created.connection.name
        auditRecorder.record(null, WorkspaceAuditCategory.INTEGRATION, "Default connection $name created")
        if (created.addedToWorkspaces > 0) {
            val workspaces = if (created.addedToWorkspaces == 1) "workspace" else "workspaces"
            auditRecorder.record(
                null,
                WorkspaceAuditCategory.INTEGRATION,
                "Default connection $name added to ${created.addedToWorkspaces} existing $workspaces",
            )
        }
        return created.connection
    }

    /** Name, type and URL are shared, so the change also reaches the workspaces holding a copy. */
    @MutationMapping
    fun updateConnection(@Argument id: Long, @Argument input: ConnectionInput): ConnectionView {
        access.requireAdmin()
        val previousName = connections.connection(id)?.name
        val updated = connections.updateConnection(id, input)

        val message = if (previousName == null || previousName == updated.name) {
            "Default connection ${updated.name} updated"
        } else {
            "Default connection $previousName renamed to ${updated.name}"
        }
        auditRecorder.record(null, WorkspaceAuditCategory.INTEGRATION, message)
        return updated
    }

    /**
     * Removes the default. The copies workspaces already hold stay where they are,
     * credentials included, and become the workspaces' own.
     */
    @MutationMapping
    fun deleteConnection(@Argument id: Long): Boolean {
        access.requireAdmin()
        val name = connections.connection(id)?.name ?: return false
        if (!connections.deleteConnection(id)) return false

        auditRecorder.record(null, WorkspaceAuditCategory.INTEGRATION, "Default connection $name deleted")
        return true
    }
}
