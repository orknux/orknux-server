package io.mszymanski.orknux.connector.connection

import org.springframework.stereotype.Service

/**
 * Gives a workspace its copy of an admin default connection, so the workspace only
 * has to add credentials. New workspaces get every default; existing workspaces are left
 * alone unless the administrator asks for a default to be handed to them too.
 *
 * The connector has no workspace table, so a backfill asks orknux-server which workspaces
 * exist. A new workspace is provisioned when the server says it was created — see
 * `provisionWorkspaceConnections` on [WorkspaceConnectionAPI].
 */
@Service
class ConnectionProvisioning(
    private val connections: ConnectionRepository,
    private val workspaceConnections: WorkspaceConnectionRepository,
    private val workspaces: WorkspaceDirectory,
) {

    fun provisionDefaults(workspaceId: Long): List<WorkspaceConnection> =
        connections.findAll().mapNotNull { default -> provision(workspaceId, default) }

    /** Returns how many workspaces took the connection; those already holding the name keep theirs. */
    fun provisionToExistingWorkspaces(default: Connection): Int = workspaces.workspaceIds()
        .mapNotNull { workspaceId -> provision(workspaceId, default) }
        .size

    private fun provision(workspaceId: Long, default: Connection): WorkspaceConnection? {
        // A workspace that already has a connection under this name keeps its own.
        if (workspaceConnections.findByWorkspaceIdAndName(workspaceId, default.name) != null) return null

        return workspaceConnections.save(
            WorkspaceConnection(
                workspaceId = workspaceId,
                connectionId = default.id,
                name = default.name,
                type = default.type,
                url = default.url,
                description = default.description,
                // The form no longer asks a Slack connection how it
                // authenticates, so a provisioned one has to arrive knowing:
                // left at NONE it would hold a bot token and send it to nobody.
                authType = if (default.type == ConnectionType.SLACK) AuthType.BEARER_TOKEN else AuthType.NONE,
            ),
        )
    }
}
