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
 * The other thing written is the opposite of membership: a workspace may
 * remove one of its people without touching the roles that let them in -
 * see [excludeWorkspaceMember].
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
    private val exclusions: WorkspaceExcludedUserRepository,
) {

    /**
     * Everybody a role lets in, and everybody this workspace has removed - the
     * second listed even where no role of theirs opens it any more, so a row
     * left behind can still be seen and allowed rather than lingering unseen.
     */
    @QueryMapping
    @Transactional(readOnly = true)
    fun workspaceMembers(@Argument workspaceId: Long): List<WorkspaceMemberView> {
        val workspace = access.requireAdministers(workspaceId)
        val opening = openingRoles(workspace)
        val named = adminUsers.findAllByIdWorkspaceId(workspaceId).map { it.id.username }.toSet()
        val excluded = excludedFrom(workspaceId)
        val known = roles.findAll().associateBy { it.id }
        return users.findAll()
            .filter { user -> user.username in excluded || user.resolvedAdmin || rolesOf(user, known).any { it.id in opening || it.administers } }
            .map { user -> view(user, workspace, named, excluded, known) }
            .sortedWith(compareBy({ !it.fixed }, { it.displayName.lowercase() }))
    }

    /**
     * Removes a member from this workspace and nothing else.
     *
     * Their roles stay, so every other workspace those roles open stays open,
     * and it works for somebody a directory signs in, whose groups are not
     * this installation's to change. [WorkspaceAccess] reads the row on every
     * check, which is what makes the workspace go from everywhere at once.
     *
     * Somebody named on the workspace as administering it loses that naming
     * here rather than the removal being refused. The administrator removing
     * them has already decided they should not be here at all; refusing would
     * only make them demote first and remove second. And a naming left behind
     * would come back on Allow - an administrator restored by a button that
     * says it lets somebody back in, a grant nobody made again.
     */
    @MutationMapping
    @Transactional
    fun excludeWorkspaceMember(@Argument workspaceId: Long, @Argument username: String): WorkspaceMemberView {
        val workspace = access.requireAdministers(workspaceId)
        val user = users.findByUsername(username) ?: throw WorkspaceMemberUnknownException(username)
        val known = roles.findAll().associateBy { it.id }
        val held = rolesOf(user, known)
        // An installation administrator sees every workspace by definition; a row here could not change that.
        if (user.resolvedAdmin || held.any { it.administers }) throw WorkspaceMemberNotRemovableException(username)
        val opening = openingRoles(workspace)
        if (held.none { it.id in opening }) throw WorkspaceMemberUnknownException(username)

        val id = WorkspaceExcludedUserId(workspaceId, username)
        if (!exclusions.existsById(id)) {
            val by = SecurityContextHolder.getContext().authentication?.name ?: "system"
            if (username == by && !othersAdminister(workspace, username, known)) throw WorkspaceLastAdministratorException(username)
            val adminId = WorkspaceAdminUserId(workspaceId, username)
            val wasNamed = adminUsers.existsById(adminId)
            if (wasNamed) adminUsers.deleteById(adminId)
            exclusions.save(WorkspaceExcludedUser(id = id, excludedBy = by))
            val name = user.displayName.ifBlank { user.username }
            audit.record(
                workspaceId,
                WorkspaceAuditCategory.WORKSPACE,
                "$name removed from ${workspace.name}" + if (wasNamed) ", and no longer a workspace admin" else "",
            )
        }
        return view(user, workspace, namedIn(workspaceId), excludedFrom(workspaceId), known)
    }

    /** Lets a removed member back in, as an ordinary member: whatever their roles open, this opens again. */
    @MutationMapping
    @Transactional
    fun allowWorkspaceMember(@Argument workspaceId: Long, @Argument username: String): WorkspaceMemberView {
        val workspace = access.requireAdministers(workspaceId)
        val user = users.findByUsername(username) ?: throw WorkspaceMemberUnknownException(username)
        val id = WorkspaceExcludedUserId(workspaceId, username)
        if (exclusions.existsById(id)) {
            exclusions.deleteById(id)
            val name = user.displayName.ifBlank { user.username }
            audit.record(workspaceId, WorkspaceAuditCategory.WORKSPACE, "$name allowed back into ${workspace.name}")
        }
        val known = roles.findAll().associateBy { it.id }
        return view(user, workspace, namedIn(workspaceId), excludedFrom(workspaceId), known)
    }

    /**
     * Whether somebody other than [username] administers this workspace at its
     * own level - named on it, or through a role that administers it - and is
     * not removed from it. Installation administrators are not counted: they
     * administer everywhere, and a workspace whose last administrator of its
     * own walked out would be one nobody inside it can run.
     */
    private fun othersAdminister(workspace: Workspace, username: String, known: Map<Long?, Role>): Boolean {
        val id = requireNotNull(workspace.id)
        val excluded = excludedFrom(id)
        val named = namedIn(id)
        val administering = workspace.adminRoles.mapNotNull { it.id }.toSet()
        return users.findAll().any { other ->
            other.username != username &&
                other.username !in excluded &&
                !other.resolvedAdmin &&
                rolesOf(other, known).none { it.administers } &&
                (other.username in named || rolesOf(other, known).any { it.id in administering })
        }
    }

    private fun namedIn(workspaceId: Long): Set<String> =
        adminUsers.findAllByIdWorkspaceId(workspaceId).map { it.id.username }.toSet()

    private fun excludedFrom(workspaceId: Long): Set<String> =
        exclusions.findAllByIdWorkspaceId(workspaceId).map { it.id.username }.toSet()

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
        // Naming somebody removed would be a grant that does nothing until Allow brings it back unasked.
        if (exclusions.existsById(WorkspaceExcludedUserId(workspaceId, username))) throw WorkspaceMemberExcludedException(username)

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
        return view(user, workspace, namedIn(workspaceId), excludedFrom(workspaceId), known)
    }

    /** Both halves of what a person holds here: given on the Users page, and resolved from their groups at sign-in. */
    private fun rolesOf(user: AppUser, known: Map<Long?, Role>): Set<Role> =
        user.roles + user.resolvedRoleIds().mapNotNull { known[it] }

    private fun openingRoles(workspace: Workspace): Set<Long> =
        (workspace.roles + workspace.adminRoles).mapNotNull { it.id }.toSet()

    private fun view(
        user: AppUser,
        workspace: Workspace,
        named: Set<String>,
        excluded: Set<String>,
        known: Map<Long?, Role>,
    ): WorkspaceMemberView {
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
            excluded = !fixed && user.username in excluded,
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
    /** Removed from this workspace although a role of theirs opens it. */
    val excluded: Boolean = false,
)

/** Not a member: no role of theirs opens this workspace, or there is no such user. */
class WorkspaceMemberUnknownException(val username: String) : RuntimeException(
    "$username is not a member of this workspace. Membership comes from roles, in Admin > Roles.",
), Refusal {
    override val arguments: Map<String, Any?> get() = mapOf("username" to username)
}

/** An installation administrator sees every workspace by definition, so one cannot be removed from one. */
class WorkspaceMemberNotRemovableException(val username: String) : RuntimeException(
    "$username is an installation administrator and sees every workspace, so cannot be removed from one.",
), Refusal {
    override val arguments: Map<String, Any?> get() = mapOf("username" to username)
}

/** Removing yourself would leave nobody but installation administrators to run the workspace. */
class WorkspaceLastAdministratorException(val username: String) : RuntimeException(
    "$username is the last workspace admin here. Make somebody else a workspace admin before removing yourself.",
), Refusal {
    override val arguments: Map<String, Any?> get() = mapOf("username" to username)
}

/** A removed member's role here is not changed until they are allowed back. */
class WorkspaceMemberExcludedException(val username: String) : RuntimeException(
    "$username is removed from this workspace. Allow them back before changing their role.",
), Refusal {
    override val arguments: Map<String, Any?> get() = mapOf("username" to username)
}

/** An installation administrator administers every workspace; that is not changed here. */
class WorkspaceMemberFixedException(val username: String) : RuntimeException(
    "$username is an installation administrator, and administers every workspace.",
), Refusal {
    override val arguments: Map<String, Any?> get() = mapOf("username" to username)
}
