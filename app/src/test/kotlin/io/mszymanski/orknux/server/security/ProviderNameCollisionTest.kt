package io.mszymanski.orknux.server.security

import io.mszymanski.orknux.server.user.AppUser
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.UserType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.Instant

/**
 * An identity provider's sign-in never inherits an internal account's roles by
 * sharing its name. Issue #588.
 *
 * Rows are found by username, and an OIDC token's username is often a claim the
 * person can edit at the provider. Somebody calling themselves after the
 * installation's internal administrator would have been given that account's
 * administering role.
 */
@SpringBootTest
class ProviderNameCollisionTest(
    @Autowired val access: WorkspaceAccess,
    @Autowired val users: AppUserRepository,
    @Autowired val roles: RoleRepository,
) {

    private var role: Role? = null

    @BeforeEach
    fun seed() {
        users.findByUsername(NAME)?.let(users::delete)
        role = roles.save(Role(name = "collision-admins", scopes = mutableSetOf(RoleScope.ADMIN)))
        users.save(AppUser(username = NAME, displayName = NAME, type = UserType.INTERNAL, roles = mutableSetOf(role!!)))
    }

    @AfterEach
    fun clear() {
        SecurityContextHolder.clearContext()
        users.findByUsername(NAME)?.let(users::delete)
        role?.let(roles::delete)
    }

    @Test
    fun `the internal account itself administers`() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken.authenticated(NAME, null, emptyList())
        assertThat(access.isAdmin()).isTrue()
    }

    @Test
    fun `a provider's token carrying the same name does not`() {
        val jwt = Jwt.withTokenValue("t").header("alg", "none").claim("preferred_username", NAME)
            .subject("someone-else").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt, emptyList(), NAME)
        assertThat(access.isAdmin()).isFalse()
        assertThat(access.heldRoles()).isEmpty()
    }

    private companion object {
        const val NAME = "collision-admin"
    }
}
