package io.mszymanski.orknux.server.security

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * A role named after a directory group whose name has punctuation in it.
 *
 * The directory and this application disagreed about punctuation, and for years
 * the disagreement was invisible because every group anybody named was one word.
 *
 * Spring's populator writes `ROLE_` and the group's common name uppercased and
 * otherwise untouched, so `dev.TL` arrives as `ROLE_DEV.TL`. This end replaced
 * anything that is not a letter or a digit with an underscore, giving
 * `ROLE_DEV_TL`. A role named for such a group matched nothing, and no screen
 * could say why: the group was found, the authority was held, and the name
 * looked right to anybody reading it.
 *
 * Found on a real directory, where the group somebody needed was called
 * `dev.TL` and the ones that already worked were called `CPL_USER`.
 *
 * Makes roles and removes them.
 */
@SpringBootTest
class RoleNameMatchTest(
    @Autowired val resolver: RoleResolver,
    @Autowired val roles: RoleRepository,
) {

    private val made = mutableListOf<Long>()

    private fun role(name: String, administers: Boolean = false): Role =
        roles.save(
            Role(
                name = name,
                scopes = mutableSetOf(if (administers) RoleScope.ADMIN else RoleScope.USER),
            ),
        ).also { made += requireNotNull(it.id) }

    /**
     * By name rather than by instance: a role read back is a different object
     * from the one that was saved, and [Role] is a JPA entity with no equality
     * of its own - so comparing the objects would only ever be comparing
     * identity, and would fail even where the matching worked.
     */
    private fun matchedFor(vararg authorities: String): List<String> =
        resolver.rolesFor(authorities.toSet()).map { it.name }

    @AfterEach
    fun sweep() {
        made.forEach { roles.deleteById(it) }
        made.clear()
    }

    @Test
    fun `a one-word group still matches, which is every installation that has one`() {
        val held = role("zzCplUser")

        assertThat(matchedFor("ROLE_ZZCPLUSER")).contains(held.name)
    }

    /**
     * The sanitised spelling, which is what the migration into roles produced
     * from the groups workspaces used to name. Nobody's workspace may stop
     * opening because the other spelling was added.
     */
    @Test
    fun `and so does one an underscore was put into on the way in`() {
        val held = role("zz Team Lead")

        assertThat(matchedFor("ROLE_ZZ_TEAM_LEAD")).contains(held.name)
    }

    @Test
    fun `a group with a dot in its name matches the name it actually arrives as`() {
        val held = role("zzdev.TL")

        assertThat(matchedFor("ROLE_ZZDEV.TL")).contains(held.name)
    }

    @Test
    fun `and so does one with a space`() {
        val held = role("zzBoarCMS Group")

        assertThat(matchedFor("ROLE_ZZBOARCMS GROUP")).contains(held.name)
    }

    @Test
    fun `holding something else entirely still matches nothing`() {
        role("zzdev.TL")

        assertThat(matchedFor("ROLE_ZZSOMETHING_ELSE")).isEmpty()
    }

    /**
     * The whole point of the role having a scope, reached through the spelling
     * that was not matching: an administrator group with a dot in its name
     * administered nothing.
     */
    @Test
    fun `and one that administers administers`() {
        role("zzops.admins", administers = true)

        assertThat(resolver.administers(setOf("ROLE_ZZOPS.ADMINS"))).isTrue()
    }
}
