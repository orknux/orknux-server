package io.mszymanski.orknux.server.integration

import io.mszymanski.orknux.connector.connection.CreateMcpServerInput
import io.mszymanski.orknux.connector.connection.McpServerCheck
import io.mszymanski.orknux.connector.connection.McpServerService
import io.mszymanski.orknux.connector.connection.McpServerView
import io.mszymanski.orknux.connector.connection.UpdateMcpServerInput
import io.mszymanski.orknux.server.agent.AgentGrants
import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.revision.ComponentRevisionRecorder
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/** The MCP servers a workspace's agents may connect to; the connection module holds them. */
@Controller
class McpServerAPI(
    private val servers: McpServerService,
    private val workspaces: WorkspaceRepository,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val grants: AgentGrants,
    private val revisions: ComponentRevisionRecorder,
) {

    @QueryMapping
    fun mcpServers(@Argument workspaceId: Long): List<McpServerView> {
        requireWorkspaceAccess(workspaceId)
        return servers.mcpServers(workspaceId)
    }

    @QueryMapping
    fun mcpServer(@Argument id: Long): McpServerView? =
        servers.mcpServer(id)?.takeIf { access.canSee(it.workspaceId) }

    /**
     * Asks the server whether it is there, on somebody's behalf.
     *
     * A mutation rather than a query because it is an outbound call somebody
     * pressed a button to make, not a fact about the workspace that can be
     * asked for again at will — and a client that re-fetched queries would
     * otherwise open handshakes nobody asked for.
     *
     * Not audited. It reads: the same handshake an agent makes every time it
     * calls a tool, and an entry every time somebody presses Check would drown
     * the entries that record a change.
     */
    @MutationMapping
    fun checkMcpServer(@Argument id: Long): McpServerCheck {
        val server = servers.mcpServer(id) ?: throw McpServerNotFoundException(id)
        requireWorkspaceAccess(server.workspaceId)
        return servers.checkMcpServer(id)
    }

    /** The add dialog's Test: the same check on what the form holds, saving nothing. */
    @MutationMapping
    fun checkMcpServerDraft(@Argument input: CreateMcpServerInput): McpServerCheck {
        requireWorkspaceAccess(input.workspaceId)
        return servers.checkMcpServerDraft(input)
    }

    @MutationMapping
    fun createMcpServer(@Argument input: CreateMcpServerInput): McpServerView {
        requireWorkspaceAccess(input.workspaceId)
        val created = servers.createMcpServer(input)
        auditRecorder.record(input.workspaceId, WorkspaceAuditCategory.INTEGRATION, "MCP Server ${created.name} added")
        return created
    }

    /**
     * Backs the MCP server settings form; a null secret keeps the stored one.
     *
     * A rename carries the grants with it. They are held by name, so a rename
     * that touched only the server would leave every agent holding a name that
     * matches nothing — and `McpToolCaller` drops a grant matching no server,
     * so the capability would go without a word while the agent's screen went
     * on listing it.
     *
     * That is the same defect [removeMcpServer] was given a guard for, reached
     * by a different door and arguably the worse of the two. Removing is a
     * deliberate destructive act done by somebody paying attention. Renaming is
     * tidying, and nobody expects tidying to disable anything.
     *
     * Followed rather than refused, and followed rather than reported: the
     * agent asked for *that server* and still means it, so moving the grant is
     * what keeps the intent. Nothing is handed back for the same reason —
     * nothing broke, so there is no warning to print. Which agents were touched
     * is in the audit, a line each, which is where somebody goes to ask what a
     * rename did.
     *
     * Transactional because it is a server and several agents: half a rename is
     * worse than none, since the half left behind is exactly the stale name.
     */
    @MutationMapping
    @Transactional
    fun updateMcpServer(@Argument id: Long, @Argument input: UpdateMcpServerInput): McpServerView {
        val server = servers.mcpServer(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw McpServerNotFoundException(id)

        val was = server.name
        val updated = servers.updateMcpServer(id, input)
        val message = if (was == updated.name) {
            "MCP Server ${updated.name} updated"
        } else {
            "MCP Server $was renamed to ${updated.name}"
        }
        auditRecorder.record(server.workspaceId, WorkspaceAuditCategory.INTEGRATION, message)
        if (was != updated.name) regrant(server.workspaceId, was, updated.name)
        return updated
    }

    /**
     * Moves a grant from the old name to the new one, on every agent holding it.
     *
     * Written where it stands rather than dropped and appended, because the
     * list is ordered and shown in that order: a rename is not a re-grant, and
     * a server that jumped to the bottom of an agent's list would be somebody
     * looking for what else had changed.
     *
     * Deduplicated afterwards for the case the ordering hides. An agent may
     * already hold the new name as a *stale* grant from an earlier rename —
     * the server's own name is unique in the workspace, but a name left on an
     * agent is not — and following the rename onto that agent would give it the
     * same server twice.
     */
    private fun regrant(workspaceId: Long, was: String, became: String) {
        grants.toMcpServer(workspaceId, was).forEach { agent ->
            // The state it is about to stop being, for the same reason a
            // revocation records one: a version with a hole exactly where the
            // automation worked is the failure this codebase has had once.
            revisions.saved(agent)

            val moved = agent.mcpServers.map { if (it == was) became else it }.distinct()
            agent.mcpServers.clear()
            agent.mcpServers.addAll(moved)
            agent.lastModifiedAt = OffsetDateTime.now()
            agent.lastModifiedBy = currentUser()

            auditRecorder.record(
                agent.workspaceId,
                WorkspaceAuditCategory.AGENT,
                "MCP Server $was renamed to $became for ${agent.name}",
            )
        }
    }

    /**
     * Removes the server, and takes the grant off every agent that held it.
     *
     * The fourth grant by name, and the only one whose delete is not refused.
     * [AgentGrants] carries the argument: what is named here is an address
     * somebody else runs, so "the server is gone, so I removed the entry" is
     * ordinary housekeeping and a refusal would get in its way. What is not
     * ordinary is leaving the name behind on the agents. `McpToolCaller` drops a
     * grant matching no server, so the capability goes quietly — and registering
     * a server under that name again hands every agent still holding it whatever
     * now answers there. Clearing the grants is what closes that, and it is why
     * this is a revocation rather than a warning printed over an unchanged
     * agent.
     *
     * The names of those agents come back so that somebody can see what just
     * lost a capability. A list of names rather than an object, deliberately:
     * the field used to be a `Boolean!` and the browser selects it without a
     * sub-selection, so a scalar list is the shape that can say more without
     * breaking the page that asks. Null is still "nothing was removed".
     *
     * Transactional because it is several agents and a server: a failure part
     * way through must not leave some agents cleaned, some not, and the server
     * gone from under all of them.
     */
    @MutationMapping
    @Transactional
    fun removeMcpServer(@Argument id: Long): List<String>? {
        val server = servers.mcpServer(id)?.takeIf { access.canSee(it.workspaceId) } ?: return null
        if (!servers.removeMcpServer(id)) return null

        auditRecorder.record(server.workspaceId, WorkspaceAuditCategory.INTEGRATION, "MCP Server ${server.name} removed")

        val held = grants.toMcpServer(server.workspaceId, server.name)
        held.forEach { agent ->
            // Losing a grant is a change to the agent, so it is a version of the
            // agent: the recorder is handed what the agent is about to stop
            // being, exactly as the settings form hands it. A history with a
            // hole where the automation worked is the failure the tracker's
            // already had once.
            revisions.saved(agent)
            agent.mcpServers.removeAll { it == server.name }
            agent.lastModifiedAt = OffsetDateTime.now()
            agent.lastModifiedBy = currentUser()
            // Worded as the settings form's own removal is, because it is the
            // same thing happening to the agent.
            auditRecorder.record(
                agent.workspaceId,
                WorkspaceAuditCategory.AGENT,
                "MCP Server ${server.name} removed from ${agent.name}",
            )
        }
        return held.map { it.name }
    }

    /** Hands the stored credentials to the settings form behind the "Reveal" action. */
    @MutationMapping
    fun revealMcpServerSecret(@Argument id: Long): String? {
        val server = servers.mcpServer(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw McpServerNotFoundException(id)

        auditRecorder.record(
            server.workspaceId,
            WorkspaceAuditCategory.INTEGRATION,
            "Credentials for ${server.name} revealed",
        )
        return servers.revealMcpServerSecret(id)
    }

    private fun requireWorkspaceAccess(workspaceId: Long) {
        access.requireVisible(workspaceId)
    }

    /** Whoever is asking, for the stamp a revision of this state will carry. */
    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"
}

class McpServerNotFoundException(val id: Long) : RuntimeException("No MCP server with id $id"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

