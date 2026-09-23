package io.mszymanski.orknux.server.ldap

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.ldap.core.support.BaseLdapPathContextSource

/**
 * How far below its base the group search looks.
 *
 * Spring's populator looks one level and the user search looks at the whole
 * subtree, which is an asymmetry nobody here chose and which is a trap in a
 * directory that files its groups by department: the search runs, matches
 * nothing, and an installation where everybody signs in successfully shows every
 * one of them an empty product. Nothing fails, so nothing says why.
 *
 * So it is a setting, and both answers are held here rather than the one that
 * happens to be the default. The populator is built directly from the same
 * config bean the application uses - two of them, one each way - because the
 * thing being measured is what that bean does with the property, and standing up
 * two application contexts to ask would be slower and no more truthful.
 *
 * Reads the directory from compose.yaml. `cn=admins` sits directly in
 * `ou=groups`; `cn=platform` sits one further down in `ou=departments`, and
 * alice is in both.
 */
@SpringBootTest
class GroupSearchDepthTest(
    @Autowired val contextSource: BaseLdapPathContextSource,
) {

    private val alice = "uid=alice,ou=people,dc=orknux,dc=io"

    private fun authoritiesFor(subtree: Boolean): Set<String> =
        LdapAuthenticationConfig()
            .ldapAuthoritiesPopulator(
                contextSource,
                LdapProperties(groupSearchSubtree = subtree),
            )
            .let { it as org.springframework.security.ldap.userdetails.DefaultLdapAuthoritiesPopulator }
            .getGroupMembershipRoles(alice, "alice")
            .mapNotNull { it.authority }
            .toSet()

    @Test
    fun `by default a group directly in the base is found`() {
        assertThat(authoritiesFor(subtree = false)).contains("ROLE_ADMINS")
    }

    /**
     * The trap, pinned. Not a bug to fix quietly: an installation that has been
     * granting exactly these roles should go on granting exactly these roles
     * until somebody says otherwise, since the other direction can only ever
     * grant more.
     */
    @Test
    fun `and a group filed one level further down is not`() {
        assertThat(authoritiesFor(subtree = false)).doesNotContain("ROLE_PLATFORM")
    }

    @Test
    fun `told to look below its base, it finds both`() {
        val held = authoritiesFor(subtree = true)

        assertThat(held).contains("ROLE_ADMINS")
        assertThat(held).contains("ROLE_PLATFORM")
    }
}
