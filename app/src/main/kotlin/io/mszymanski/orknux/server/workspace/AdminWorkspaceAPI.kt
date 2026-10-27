package io.mszymanski.orknux.server.workspace

import io.mszymanski.orknux.server.security.Role
import io.mszymanski.orknux.server.security.RoleRepository
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.resolvedRoleIds
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Which workspaces are admin workspaces, and what each may read. Admin >
 * Workspaces, an installation administrator's and nobody else's: a workspace
 * cannot make itself one, and its own administrators cannot widen what it
 * reaches.
 *
 * The rule an admin workspace lives by is that nobody but an installation
 * administrator can open it - its agents read other workspaces, so whoever can
 * open it can read them. Switching one on is therefore refused while a role
 * that is not the administrator's opens it, or somebody who is not one is
 * named administering it; and while it is one, [WorkspaceGuards] refuses
 * putting such a role on it or taking the Admin scope off one that opens it.
 * [WorkspaceAccess.canSee] holds the same line on every read whatever the rows
 * say.
 */
@Controller
class AdminWorkspaceAPI(
    private val workspaces: WorkspaceRepository,
    private val settings: AdminWorkspaceRepository,
    private val adminUsers: WorkspaceAdminUserRepository,
    private val users: AppUserRepository,
    private val roles: RoleRepository,
    private val access: WorkspaceAccess,
    private val audit: WorkspaceAuditRecorder,
) {

    @QueryMapping
    @Transactional(readOnly = true)
    fun adminWorkspace(@Argument workspaceId: Long): AdminWorkspaceView {
        access.requireAdmin()
        val workspace = workspaces.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        return view(workspace, settings.findByIdOrNull(workspaceId) ?: AdminWorkspace(workspaceId = workspaceId))
    }

    /**
     * Writes the whole card at once: on or off, the reach, and every switch.
     * One audit line per thing that changed, worded as the card shows it.
     */
    @MutationMapping
    @Transactional
    fun setAdminWorkspace(@Argument workspaceId: Long, @Argument input: AdminWorkspaceInput): AdminWorkspaceView {
        access.requireAdmin()
        val workspace = workspaces.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        if (input.enabled) requireOnlyAdministrators(workspace)

        val reach = input.reach.distinct().map { id ->
            workspaces.findByIdOrNull(id) ?: throw AdminWorkspaceReachUnknownException(id)
        }
        val held = settings.findByIdOrNull(workspaceId)
        val row = held ?: AdminWorkspace(workspaceId = workspaceId)
        val wasOn = workspace.adminWorkspace
        val before = Snapshot(row.reachAll, row.reach.toSet(), row.scopes.toSet(), row.tools.toSet())

        workspace.adminWorkspace = input.enabled
        row.reachAll = input.reachAll
        row.reach = reach.mapNotNull { it.id }.toMutableSet()
        row.scopes = input.scopes.toMutableSet()
        row.tools = input.tools.toMutableSet()
        row.updatedAt = OffsetDateTime.now()
        row.updatedBy = currentUser()
        settings.save(row)
        workspaces.save(workspace)

        if (wasOn != input.enabled) {
            audit.record(workspaceId, WorkspaceAuditCategory.WORKSPACE, if (input.enabled) "Admin workspace switched on" else "Admin workspace switched off")
        }
        // Against what the card showed before, which for a workspace never switched on is the defaults it was drawn with.
        if (before.reachAll != row.reachAll || (!row.reachAll && before.reach != row.reach)) {
            audit.record(
                workspaceId,
                WorkspaceAuditCategory.WORKSPACE,
                if (row.reachAll) {
                    "Admin workspace reaches every workspace"
                } else if (reach.isEmpty()) {
                    "Admin workspace reaches no workspace"
                } else {
                    "Admin workspace reaches ${reach.map { it.name }.sorted().joinToString(", ")}"
                },
            )
        }
        AdminWorkspaceScope.entries.forEach { scope ->
            val on = scope in row.scopes
            if (on != (scope in before.scopes)) {
                audit.record(
                    workspaceId,
                    WorkspaceAuditCategory.WORKSPACE,
                    "Admin workspace ${if (on) "may read" else "may no longer read"} ${scope.kind} in other workspaces",
                )
            }
        }
        AdminWorkspaceTool.entries.forEach { tool ->
            val on = tool in row.tools
            if (on != (tool in before.tools)) {
                audit.record(
                    workspaceId,
                    WorkspaceAuditCategory.WORKSPACE,
                    "Admin workspace tool ${tool.toolName} switched ${if (on) "on" else "off"}",
                )
            }
        }
        return view(workspace, row)
    }

    private data class Snapshot(
        val reachAll: Boolean,
        val reach: Set<Long>,
        val scopes: Set<AdminWorkspaceScope>,
        val tools: Set<AdminWorkspaceTool>,
    )

    /**
     * Refuses, naming them, while anybody who is not an installation
     * administrator can open this workspace: a role on it without the Admin
     * scope, or a person named administering it who is not one.
     */
    private fun requireOnlyAdministrators(workspace: Workspace) {
        val opening = (workspace.roles + workspace.adminRoles).filterNot { it.administers }.map { it.name }.distinct().sorted()
        val known = roles.findAll().associateBy { it.id }
        val people = adminUsers.findAllByIdWorkspaceId(requireNotNull(workspace.id)).map { it.id.username }
            .filterNot { username ->
                val user = users.findByUsername(username) ?: return@filterNot false
                user.resolvedAdmin || user.roles.any(Role::administers) || user.resolvedRoleIds().any { known[it]?.administers == true }
            }
            .sorted()
        if (opening.isNotEmpty() || people.isNotEmpty()) throw AdminWorkspaceOpenToOthersException(workspace.name, opening, people)
    }

    private fun view(workspace: Workspace, row: AdminWorkspace) = AdminWorkspaceView(
        workspaceId = requireNotNull(workspace.id),
        enabled = workspace.adminWorkspace,
        reachAll = row.reachAll,
        reach = row.reach.sorted(),
        scopes = AdminWorkspaceScope.entries.filter { it in row.scopes },
        tools = AdminWorkspaceTool.entries.filter { it in row.tools },
    )

    private fun currentUser(): String = SecurityContextHolder.getContext().authentication?.name ?: "system"
}

data class AdminWorkspaceInput(
    val enabled: Boolean,
    val reachAll: Boolean,
    val reach: List<Long> = emptyList(),
    val scopes: List<AdminWorkspaceScope> = emptyList(),
    val tools: List<AdminWorkspaceTool> = emptyList(),
)

data class AdminWorkspaceView(
    val workspaceId: Long,
    val enabled: Boolean,
    val reachAll: Boolean,
    val reach: List<Long>,
    val scopes: List<AdminWorkspaceScope>,
    val tools: List<AdminWorkspaceTool>,
)

/**
 * The two doors through which somebody who is not an administrator could come to
 * open an admin workspace: a role put on it, and a role on it losing the Admin
 * scope. Asked by [WorkspaceAPI.updateWorkspace] and the role save.
 */
object WorkspaceGuards {

    /** Refuses a save that leaves an admin workspace opened by a role that is not the administrator's. */
    fun requireAdminRolesOnly(workspace: Workspace) {
        if (!workspace.adminWorkspace) return
        val others = (workspace.roles + workspace.adminRoles).filterNot { it.administers }.map { it.name }.distinct().sorted()
        if (others.isNotEmpty()) throw AdminWorkspaceMemberRefusedException(listOf(workspace.name), others)
    }

    /** Refuses taking the Admin scope off [role] while it opens an admin workspace. */
    fun requireStillAdministers(role: Role, administersAfter: Boolean, all: List<Workspace>) {
        if (administersAfter) return
        val opened = all.filter { it.adminWorkspace && (it.roles + it.adminRoles).any { held -> held.id == role.id } }
        if (opened.isNotEmpty()) throw AdminWorkspaceMemberRefusedException(opened.map { it.name }.sorted(), listOf(role.name))
    }
}
