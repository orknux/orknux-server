package io.mszymanski.orknux.server.security

import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceNotFoundException
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service

/**
 * Decides which workspaces the caller may see.
 *
 * A workspace is opened by the roles assigned to it, and the caller's roles come from
 * whatever the identity provider said about them, translated once by [RoleResolver].
 * Nothing here knows what a directory group is, which is the point: the same check
 * works whichever provider signed somebody in.
 */
@Service
class WorkspaceAccess(
    private val resolver: RoleResolver,
    private val workspaces: WorkspaceRepository,
    /** Who this is here, for the roles an administrator gave them. See [heldRoles]. */
    private val users: io.mszymanski.orknux.server.user.AppUserRepository,
) {

    fun roles(): Set<String> {
        if (!signedIn()) return emptySet()
        val authentication = SecurityContextHolder.getContext().authentication ?: return emptySet()
        return authentication.authorities.mapNotNull(GrantedAuthority::getAuthority).toSet()
    }

    /** Somebody rather than nobody: authenticated, and not the anonymous token. */
    fun signedIn(): Boolean {
        val authentication = SecurityContextHolder.getContext().authentication
        return authentication != null &&
            authentication.isAuthenticated &&
            authentication !is AnonymousAuthenticationToken
    }

    fun isAdmin(): Boolean = resolver.administers(roles()) || assigned().any { it.administers }

    /**
     * The roles this caller holds here, whatever the provider called them.
     *
     * Two sources, and the second is not a duplicate of the first. What the
     * provider says is the directory's answer, and it is the whole answer for
     * everybody it covers. What an administrator assigned on the Users screen is
     * this installation's own answer, for the person the directory has no group
     * for - the first administrator of a new installation, a contractor nobody
     * will make a group for, somebody who needs one workspace for a fortnight.
     *
     * That control existed and did nothing. It saved, the screen drew the role
     * against the name, and every decision was still made from the provider's
     * groups alone - so an administrator could give somebody a role, see it
     * listed, and watch them sign in to nothing.
     */
    fun heldRoles(): Set<Role> = resolver.rolesFor(roles()) + assigned()

    /**
     * The roles this installation gave this person itself.
     *
     * Empty where nobody has been written down yet, which is the moment between
     * arriving at the door and the first sign-in being recorded.
     */
    private fun assigned(): Set<Role> {
        val username = SecurityContextHolder.getContext().authentication?.name ?: return emptySet()
        return users.findByUsername(username)?.roles?.toSet().orEmpty()
    }

    /**
     * Whether the caller may see this workspace.
     *
     * A workspace with no roles is administrators only. That is the same answer the
     * empty group gave before, and it is the safe direction to fail in: a workspace
     * whose audience nobody has decided is not one to show to everybody.
     */
    fun canSee(workspace: Workspace): Boolean {
        if (isAdmin()) return true
        if (workspace.roles.isEmpty() && workspace.adminRoles.isEmpty()) return false
        val held = heldRoles().mapNotNull { it.id }.toSet()
        // The administering set as well as the opening one. It is meant to be a
        // subset of it - the API refuses a save where it is not - so this reads as
        // belt and braces, and it is: a role that administers a workspace it cannot
        // see is a state no screen can produce and no check should have to survive.
        return (workspace.roles + workspace.adminRoles).any { it.id in held }
    }

    /**
     * Whether the caller may *administer* this workspace, not merely see it.
     *
     * The same question one level up, decided in the same class and read off the
     * same roles, which is the point: there is one notion of administering a
     * workspace and it lives here. A resolver that wants it asks; it does not grow
     * its own.
     *
     * What it grants is deliberately small - the workspace's own name and
     * description, putting somebody else on one of its issues as an observer, and
     * moving an issue in or out of it. It grants nothing installation-wide:
     * connections, proxy rules, shells, users, roles and the installation settings
     * are still [requireAdmin], and so are creating and deleting a workspace, since
     * neither is something a workspace can decide about itself.
     *
     * **It does not include the workspace's role list.** A workspace administrator
     * who could edit it would decide who else gets in and could take the role off
     * everybody else, including whoever gave it to them - contained to their own
     * workspace, but real, and not something to hand out by implication. Widening
     * this later is a line of code; narrowing it once somebody has arranged their
     * installation around it is taking something away. So the first version makes
     * the smaller promise, and the role list stays an installation administrator's.
     *
     * An installation administrator administers every workspace without being named
     * on any of them, exactly as they see every workspace without being named on any
     * of them.
     */
    fun canAdminister(workspace: Workspace): Boolean {
        if (isAdmin()) return true
        if (workspace.adminRoles.isEmpty()) return false
        val held = heldRoles().mapNotNull { it.id }.toSet()
        return workspace.adminRoles.any { it.id in held }
    }

    fun canAdminister(workspaceId: Long): Boolean =
        workspaces.findByIdOrNull(workspaceId)?.let { canAdminister(it) } == true

    /**
     * The same question asked about the workspace an entity says it belongs to.
     *
     * A workspace that is not there and one the caller may not see answer alike,
     * which is what lets a query holding an id return null for both rather than
     * confirming, by refusing, that the id was a real one.
     *
     * A mutation cannot answer null, so it asks this and then throws whatever it
     * already throws for an id that is not there - the same exception with the
     * same words, because a second message would be the difference all over
     * again. Read it as the one question every resolver taking an id asks
     * before it will admit the id exists.
     */
    fun canSee(workspaceId: Long): Boolean = workspaces.findByIdOrNull(workspaceId)?.let { canSee(it) } == true

    /**
     * The workspace behind an id the caller handed us, or the same refusal for one
     * that is not there and one that is not theirs.
     *
     * The distinction used to be visible: an id nothing was saved under answered
     * "No workspace with id 999" and one belonging to somebody else answered that
     * access was refused. Two answers to the same question is a directory - walk
     * the numbers and you learn how many workspaces this installation has and
     * roughly where they start, without seeing inside any of them.
     *
     * So both answer as not there. This is deliberately the *narrower* half of
     * what [canSee] does for an entity, and it costs something worth naming:
     * somebody who mistypes a workspace id is told it does not exist rather than
     * that it is not theirs. That is the honest answer for them anyway - they
     * cannot see it, so from where they stand it does not exist - and an
     * administrator, who can see every workspace, still gets the true not-found
     * for a genuinely missing id.
     */
    fun requireVisible(workspaceId: Long): Workspace {
        val workspace = workspaces.findByIdOrNull(workspaceId)
        if (workspace == null || !canSee(workspace)) throw WorkspaceNotFoundException(workspaceId)
        return workspace
    }

    fun requireVisible(workspace: Workspace) {
        if (!canSee(workspace)) throw WorkspaceForbiddenException()
    }

    fun requireAdmin() {
        if (!isAdmin()) throw AdminRequiredException()
    }

    /**
     * Somebody, rather than nobody. The weakest check on this platform, and the
     * only one that fits a list which belongs to the installation rather than to
     * a workspace.
     *
     * The component templates are the case it exists for: they are published
     * installation-wide precisely so that a workspace this caller has nothing to
     * do with can offer its work to one they do. There is no workspace to name in
     * the question, so [requireVisible] cannot be asked, and [requireAdmin] would
     * be the wrong answer - it would mean only administrators could see what is
     * on offer, which is the opposite of publishing.
     *
     * Written out rather than left to the filter chain because "every resolver
     * checks access first" is a rule that only holds if the resolvers that decide
     * *not* to narrow say so out loud. A resolver with no check at all reads as
     * one where somebody forgot.
     */
    fun requireSignedIn() {
        if (!signedIn()) throw SignInRequiredException()
    }

    /**
     * The workspace behind an id, for a caller who has to administer it.
     *
     * Two refusals, and the difference between them is on purpose. A workspace the
     * caller cannot see answers as not there, the same as [requireVisible] does and
     * for the same reason - saying "you may not administer that" about an id would
     * confirm the id, and walking the numbers is a script. A workspace they *can*
     * see but do not administer says so plainly, because they already know it
     * exists and what they need is the name of the thing they are missing.
     */
    fun requireAdministers(workspaceId: Long): Workspace {
        val workspace = requireVisible(workspaceId)
        if (!canAdminister(workspace)) throw WorkspaceAdminRequiredException(workspace.name)
        return workspace
    }

    fun requireAdministers(workspace: Workspace) {
        requireVisible(workspace)
        if (!canAdminister(workspace)) throw WorkspaceAdminRequiredException(workspace.name)
    }

}

/**
 * Says no without saying what was asked for.
 *
 * It used to name the workspace, which meant that every query taking an id -
 * an action, a condition, an agent, a skill, a tool, a run - had three answers
 * for an arbitrary number, and the middle one handed over the name of the
 * workspace the caller is not in. GraphQL reports errors with a 200, so trying
 * every number in turn is a script rather than an afternoon, and what comes
 * back is a directory of who else is on this installation and what their
 * workspaces are called.
 *
 * The MCP surface has always answered this way and says why: a run in another
 * workspace is answered exactly as one that does not exist, because "that is
 * not yours" confirms it is somebody's. What is left here still says whether
 * an id is real, which is the smaller half of the same leak - closing that one
 * means each of those queries answering null the way `workspace(id)` already
 * does, rather than refusing.
 */
class WorkspaceForbiddenException :
    RuntimeException("That does not exist, or you do not have access to it")

class AdminRequiredException : RuntimeException("This action requires the administrator role")

/** Nobody is signed in. Says nothing about what was asked for, because it did not look. */
class SignInRequiredException : RuntimeException("Sign in to see this")

/**
 * Says which workspace, unlike [WorkspaceForbiddenException], and can afford to.
 *
 * This is only ever thrown at somebody who can already see the workspace and read
 * its name off the top of the page, so naming it leaks nothing and saves them
 * guessing which of the two they are in. What they are told is the thing to go and
 * ask for: a role that administers *this* workspace, which is not the installation
 * administrator role and not a role that administers a different one.
 */
class WorkspaceAdminRequiredException(val name: String) : RuntimeException(
    "This action needs a role that administers $name. Being able to see a workspace is not the same " +
        "as leading it, and a role that administers another workspace does not administer this one.",
), Refusal {

    override val arguments get() = mapOf("name" to name)
}

