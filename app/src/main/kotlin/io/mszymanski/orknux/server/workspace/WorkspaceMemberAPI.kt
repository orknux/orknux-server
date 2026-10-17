package io.mszymanski.orknux.server.workspace

import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.security.Role
import io.mszymanski.orknux.server.security.RoleRepository
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.user.AppUser
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.resolvedRoleIds
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional

/**
 * Who is in a workspace, and which of them administer it.
 *
 * Membership is read, not written: a person is here because a role an
 * installation administrator gave them opens the workspace. What is written is
 * the one thing a workspace decides about its own people - which of them may
 * change its settings - and it is decided by somebody who already may, so the
 * first workspace admin is always made by an installation administrator.
 *
 * A person's roles here are the ones an administrator gave them on the Users
 * page and the ones their directory groups resolved to the last time they
 * signed in - so somebody let in by a group alone is listed once they have
 * been here, and not before.
 */
@Controller
class WorkspaceMemberAPI(
    private val access: WorkspaceAccess,
    private val users: AppUserRepository,
    private val roles: RoleRepository,
    private val adminUsers: WorkspaceAdminUserRepository,
    private val audit: WorkspaceAuditRecorder,
) {

    @QueryMapping
    @Transactional(readOnly = true)
    fun workspaceMembers(@Argument workspaceId: Long): List<WorkspaceMemberView> {
        val workspace = access.requireAdministers(workspaceId)
        val opening = openingRoles(workspace)
        val named = adminUsers.findAllByIdWorkspaceId(workspaceId).map { it.id.username }.toSet()
        val known = roles.findAll().associateBy { it.id }
        return users.findAll()
            .filter { user -> user.resolvedAdmin || rolesOf(user, known).any { it.id in opening || it.administers } }
            .map { user -> view(user, workspace, named, known) }
            .sortedWith(compareBy({ !it.fixed }, { it.displayName.lowercase() }))
    }

    @MutationMapping
    @Transactional
    fun setWorkspaceMemberRole(
        @Argument workspaceId: Long,
        @Argument username: String,
        @Argument role: WorkspaceRole,
    ): WorkspaceMemberView {
        val workspace = access.requireAdministers(workspaceId)
        val user = users.findByUsername(username) ?: throw WorkspaceMemberUnknownException(username)
        val known = roles.findAll().associateBy { it.id }
        val held = rolesOf(user, known)
        if (user.resolvedAdmin || held.any { it.administers }) throw WorkspaceMemberFixedException(username)
        val opening = openingRoles(workspace)
        if (held.none { it.id in opening }) throw WorkspaceMemberUnknownException(username)

        val id = WorkspaceAdminUserId(workspaceId, username)
        val by = SecurityContextHolder.getContext().authentication?.name ?: "system"
        when (role) {
            WorkspaceRole.ADMIN -> if (!adminUsers.existsById(id)) {
                adminUsers.save(WorkspaceAdminUser(id = id, grantedBy = by))
                audit.record(workspaceId, WorkspaceAuditCategory.WORKSPACE, "User $username made a workspace admin")
            }
            WorkspaceRole.USER -> if (adminUsers.existsById(id)) {
                adminUsers.deleteById(id)
                audit.record(workspaceId, WorkspaceAuditCategory.WORKSPACE, "User $username made an ordinary member")
            }
        }
        val named = adminUsers.findAllByIdWorkspaceId(workspaceId).map { it.id.username }.toSet()
        return view(user, workspace, named, known)
    }

    /** Both halves of what a person holds here: given on the Users page, and resolved from their groups at sign-in. */
    private fun rolesOf(user: AppUser, known: Map<Long?, Role>): Set<Role> =
        user.roles + user.resolvedRoleIds().mapNotNull { known[it] }

    private fun openingRoles(workspace: Workspace): Set<Long> =
        (workspace.roles + workspace.adminRoles).mapNotNull { it.id }.toSet()

    private fun view(user: AppUser, workspace: Workspace, named: Set<String>, known: Map<Long?, Role>): WorkspaceMemberView {
        val held = rolesOf(user, known)
        val fixed = user.resolvedAdmin || held.any { it.administers }
        val opening = openingRoles(workspace)
        val administering = workspace.adminRoles.mapNotNull { it.id }.toSet()
        val admin = fixed || user.username in named || held.any { it.id in administering }
        return WorkspaceMemberView(
            username = user.username,
            displayName = user.displayName.ifBlank { user.username },
            userType = user.type.name,
            through = held.filter { it.id in opening || it.administers }.map { it.name }.distinct().sorted(),
            role = if (admin) WorkspaceRole.ADMIN else WorkspaceRole.USER,
            fixed = fixed,
        )
    }
}

enum class WorkspaceRole { USER, ADMIN }

data class WorkspaceMemberView(
    val username: String,
    val displayName: String,
    val userType: String,
    val through: List<String>,
    val role: WorkspaceRole,
    val fixed: Boolean,
)

/** Not a member: no role of theirs opens this workspace, or there is no such user. */
class WorkspaceMemberUnknownException(val username: String) : RuntimeException(
    "$username is not a member of this workspace. Membership comes from roles, in Admin > Roles.",
), Refusal {
    override val arguments: Map<String, Any?> get() = mapOf("username" to username)
}

/** An installation administrator administers every workspace; that is not changed here. */
class WorkspaceMemberFixedException(val username: String) : RuntimeException(
    "$username is an installation administrator, and administers every workspace.",
), Refusal {
    override val arguments: Map<String, Any?> get() = mapOf("username" to username)
}
