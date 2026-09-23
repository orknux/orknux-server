package io.mszymanski.orknux.server.security

import io.mszymanski.orknux.server.user.AppUser
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.UserType
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder

/**
 * A role an administrator gave somebody, rather than one their groups gave them.
 *
 * The Users screen has always offered this and it has always done nothing. The
 * roles saved against the person, the screen drew them, and every decision was
 * made from the provider's groups alone - so an administrator could grant a
 * role, see it listed under the name, and watch that person sign in to an empty
 * product. Nothing said why, because from where they were sitting it had worked.
 *
 * It is what a directory cannot answer for: the first administrator of a new
 * installation, a contractor nobody will make a group for, somebody who needs
 * one workspace for a fortnight. Asking the directory team for a group is the
 * right answer for a team and an absurd one for a person.
 *
 * Makes a user, a role and a workspace, and removes them.
 */
@SpringBootTest
class AssignedRoleTest(
    @Autowired val access: WorkspaceAccess,
    @Autowired val roles: RoleRepository,
    @Autowired val users: AppUserRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private val username = "zzassigned"

    @AfterEach
    fun sweep() {
        SecurityContextHolder.clearContext()
        users.findByUsername(username)?.let { users.delete(it) }
        workspaces.findByName("zzAssignedSpace")?.let { workspaces.delete(it) }
        roles.findByName("zzGiven")?.let { roles.delete(it) }
        roles.findByName("zzGivenAdmin")?.let { roles.delete(it) }
    }

    /** Signed in with the authorities a directory would hand over, and no more. */
    private fun arrive(vararg authorities: String) {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            username,
            "",
            authorities.map(::SimpleGrantedAuthority),
        )
    }

    private fun give(role: Role) {
        val held = users.findByUsername(username)
            ?: users.save(AppUser(username = username, displayName = username, type = UserType.EXTERNAL))
        held.roles = mutableSetOf(role)
        users.save(held)
    }

    /* -------------------------------------------------- seeing a workspace - */

    @Test
    fun `a role an administrator gave opens the workspace it is on`() {
        val role = roles.save(Role(name = "zzGiven"))
        val space = workspaces.save(Workspace(name = "zzAssignedSpace").apply { this.roles = mutableSetOf(role) })
        give(role)

        // Arrives holding nothing at all, which is the whole point: the
        // directory has no group for this person.
        arrive("ROLE_ZZNOTHING_USEFUL")

        assertThat(access.canSee(space)).isTrue()
    }

    @Test
    fun `and somebody who was given nothing still cannot see it`() {
        val role = roles.save(Role(name = "zzGiven"))
        val space = workspaces.save(Workspace(name = "zzAssignedSpace").apply { this.roles = mutableSetOf(role) })

        arrive("ROLE_ZZNOTHING_USEFUL")

        assertThat(access.canSee(space)).isFalse()
    }

    /* --------------------------------------------------------- administering */

    /**
     * The one that matters on a new installation: somebody has to be able to
     * administer it before anybody has made a group for administering it.
     */
    @Test
    fun `a role that administers, given here, administers`() {
        give(roles.save(Role(name = "zzGivenAdmin", scopes = mutableSetOf(RoleScope.ADMIN))))

        arrive("ROLE_ZZNOTHING_USEFUL")

        assertThat(access.isAdmin()).isTrue()
    }

    @Test
    fun `and an ordinary one does not`() {
        give(roles.save(Role(name = "zzGiven")))

        arrive("ROLE_ZZNOTHING_USEFUL")

        assertThat(access.isAdmin()).isFalse()
    }

    /* ------------------------------------------------ what still decides ---- */

    @Test
    fun `the provider's own groups go on deciding as they did`() {
        val role = roles.save(Role(name = "zzGiven"))
        val space = workspaces.save(Workspace(name = "zzAssignedSpace").apply { this.roles = mutableSetOf(role) })

        // Nothing assigned here; the group's name is the whole of it.
        arrive("ROLE_ZZGIVEN")

        assertThat(access.canSee(space)).isTrue()
    }

    @Test
    fun `somebody with no row here at all is unaffected`() {
        val role = roles.save(Role(name = "zzGiven"))
        val space = workspaces.save(Workspace(name = "zzAssignedSpace").apply { this.roles = mutableSetOf(role) })

        arrive("ROLE_ZZNOTHING_USEFUL")

        assertThat(users.findByUsername(username)).isNull()
        assertThat(access.canSee(space)).isFalse()
    }
}
