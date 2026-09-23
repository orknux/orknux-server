package io.mszymanski.orknux.server.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.context.support.WithMockUser

/**
 * Naming the directory groups that grant a role, on the role.
 *
 * A role is matched to a group by name already - `Backend` is granted to whoever
 * holds `ROLE_BACKEND`, which is what the directory sends - and that carries
 * most installations until the first group called `dev.TL` or `BoarCMS Group`.
 * For those, the only way to grant the role was `orknux.security.role-mapping`
 * in the configuration file: something an administrator cannot see, cannot
 * reach, and cannot change without somebody redeploying the installation for
 * them.
 *
 * Found on a real installation, where the administrator getting people into
 * their own workspaces had no way to write the mapping the product needed.
 *
 * Makes roles and removes them.
 */
@SpringBootTest
class RoleDirectoryNameTest(
    @Autowired val api: RoleAPI,
    @Autowired val resolver: RoleResolver,
    @Autowired val roles: RoleRepository,
) {

    private val made = mutableListOf<Long>()

    @AfterEach
    fun sweep() {
        made.forEach { roles.deleteById(it) }
        made.clear()
    }

    private fun make(name: String, matches: List<String>? = null): RoleView =
        api.createRole(RoleInput(name = name, matches = matches)).also { made += it.id }

    private fun matchedFor(vararg authorities: String): List<String> =
        resolver.rolesFor(authorities.toSet()).map { it.name }

    /* ------------------------------------------------ what it grants ------- */

    @Test
    @WithMockUser(roles = ["ADMINS"])
    fun `a group this role could not be named after still grants it`() {
        make("zzDevelopers", matches = listOf("ROLE_ZZDEV.TL"))

        assertThat(matchedFor("ROLE_ZZDEV.TL")).contains("zzDevelopers")
    }

    /**
     * The full DN works as well as the name, the same as the configured mapping
     * has always allowed - somebody copying a DN out of their directory browser
     * should not have to know to cut it down first.
     */
    @Test
    @WithMockUser(roles = ["ADMINS"])
    fun `written as the whole DN it grants it too`() {
        make("zzEditors", matches = listOf("CN=zzBoarCMS Group,OU=Grupy,DC=example,DC=invalid"))

        assertThat(matchedFor("ROLE_ZZBOARCMS GROUP")).contains("zzEditors")
    }

    @Test
    @WithMockUser(roles = ["ADMINS"])
    fun `and a role keeps being granted by its own name as well`() {
        make("zzBackend", matches = listOf("ROLE_ZZSOMETHING.ELSE"))

        assertThat(matchedFor("ROLE_ZZBACKEND")).contains("zzBackend")
    }

    @Test
    @WithMockUser(roles = ["ADMINS"])
    fun `a name nobody holds grants nothing`() {
        make("zzDevelopers", matches = listOf("ROLE_ZZDEV.TL"))

        assertThat(matchedFor("ROLE_ZZUNRELATED")).isEmpty()
    }

    /**
     * The whole point of a role having a scope, reached through a name the role
     * could not be called.
     */
    @Test
    @WithMockUser(roles = ["ADMINS"])
    fun `and one that administers administers`() {
        api.createRole(
            RoleInput(
                name = "zzOps",
                scopes = listOf(RoleScope.ADMIN),
                matches = listOf("ROLE_ZZOPS.ADMINS"),
            ),
        ).also { made += it.id }

        assertThat(resolver.administers(setOf("ROLE_ZZOPS.ADMINS"))).isTrue()
    }

    /* ------------------------------------------------ what is stored ------- */

    @Test
    @WithMockUser(roles = ["ADMINS"])
    fun `blank rows are dropped rather than kept as rules that grant nothing`() {
        val held = make("zzDevelopers", matches = listOf(" ROLE_ZZDEV.TL ", "   ", ""))

        assertThat(held.matches).containsExactly("ROLE_ZZDEV.TL")
    }

    /**
     * They are compared without regard to case, so two rows differing only in
     * capitals would be one rule shown twice - and whoever removed the wrong one
     * would find it still working.
     */
    @Test
    @WithMockUser(roles = ["ADMINS"])
    fun `and the same name twice is one rule`() {
        val held = make("zzDevelopers", matches = listOf("ROLE_ZZDEV.TL", "role_zzdev.tl"))

        assertThat(held.matches).hasSize(1)
    }

    @Test
    @WithMockUser(roles = ["ADMINS"])
    fun `a role edited to drop a name stops being granted by it`() {
        val held = make("zzDevelopers", matches = listOf("ROLE_ZZDEV.TL"))
        assertThat(matchedFor("ROLE_ZZDEV.TL")).contains("zzDevelopers")

        api.updateRole(held.id, RoleInput(name = "zzDevelopers", matches = emptyList()))

        assertThat(matchedFor("ROLE_ZZDEV.TL")).isEmpty()
    }

    @Test
    @WithMockUser(roles = ["ADMINS"])
    fun `and a role made without any carries none`() {
        assertThat(make("zzBackend").matches).isEmpty()
    }
}
