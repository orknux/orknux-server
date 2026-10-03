package io.mszymanski.orknux.server.user

import io.mszymanski.orknux.server.security.RoleRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.PlatformTransactionManager

/**
 * The way into an installation that has no directory and no OIDC provider.
 *
 * The first test is the whole point and is the only one that cannot be written
 * any other way: the properties are set on this context, so the account is made
 * by the application starting rather than by anything the test called, which is
 * what proves the variables are read and the seeding is wired at all.
 *
 * The rest drive [BootstrapAdmin] directly with configuration of their own,
 * because each of them is about a state the context cannot be started twice
 * into - properties unset, properties half set, an account already there.
 */
@SpringBootTest(
    properties = [
        "orknux.bootstrap-admin.username=firstadmin",
        "orknux.bootstrap-admin.password=let me in properly",
    ],
)
class BootstrapAdminTest(
    @Autowired val users: AppUserRepository,
    @Autowired val roles: RoleRepository,
    @Autowired val internal: InternalAuthentication,
    @Autowired val encoder: PasswordEncoder,
    @Autowired val transactions: PlatformTransactionManager,
) {

    @Test
    fun `an administrator is seeded at startup and can sign in`() {
        val seeded = users.findByUsername("firstadmin")

        assertThat(seeded).isNotNull()
        assertThat(seeded?.type).isEqualTo(UserType.INTERNAL)
        assertThat(seeded?.roles?.map { it.name }).contains("Administrators")
        assertThat(seeded?.roles?.any { it.administers }).isTrue()

        // The same door every internal user comes through, carrying the
        // authority the rest of the application checks for an administrator.
        val authenticated = internal.authenticate("firstadmin", "let me in properly")
        assertThat(authenticated).isNotNull()
        assertThat(authenticated?.authorities?.map { it.authority }).contains("ROLE_ADMINISTRATORS")
    }

    @Test
    fun `an account that already exists is left alone, password and all`() {
        val kept = users.save(
            AppUser(
                username = "keeper",
                displayName = "The Keeper",
                type = UserType.INTERNAL,
                passwordHash = encoder.encode("the one they chose"),
            ),
        )

        seeding(username = "keeper", password = "whatever was in the file").seedAdministrator()

        val after = users.findByUsername("keeper")
        assertThat(after?.id).isEqualTo(kept.id)
        // The password somebody has since changed is still theirs, and the
        // configured one opens nothing.
        assertThat(internal.authenticate("keeper", "the one they chose")).isNotNull()
        assertThat(internal.authenticate("keeper", "whatever was in the file")).isNull()
        // Nor is an account handed the administrator role it was not given.
        assertThat(after?.roles).isEmpty()

        users.delete(requireNotNull(after))
    }

    @Test
    fun `nothing is seeded when the properties are unset, or when only one of them is set`() {
        seeding(username = "", password = "").seedAdministrator()
        seeding(username = "halfway", password = "").seedAdministrator()
        seeding(username = "", password = "a perfectly long password").seedAdministrator()

        assertThat(users.findByUsername("halfway")).isNull()
    }

    @Test
    fun `a password below the minimum seeds nobody rather than an account that cannot be used`() {
        seeding(username = "tooeasy", password = "short").seedAdministrator()

        assertThat(users.findByUsername("tooeasy")).isNull()
    }

    /** The same component the context runs, told something else. */
    private fun seeding(username: String, password: String) =
        BootstrapAdmin(users, roles, encoder, BootstrapAdminProperties(username, password), transactions)
}
