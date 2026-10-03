package io.mszymanski.orknux.server.user

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder

/**
 * An access token cannot make another one. Issue #588.
 *
 * A token that could, once leaked, would mint itself a replacement that
 * outlives revoking it. Making one is a person's act in a session.
 */
@SpringBootTest
class TokenMintTest(
    @Autowired val users: AppUserRepository,
    @Autowired val tokens: AppUserTokenRepository,
    @Autowired val internal: InternalAuthentication,
    @Autowired val api: UserAPI,
    @Autowired val encoder: PasswordEncoder,
) {

    private lateinit var user: AppUser

    @BeforeEach
    fun seed() {
        users.findByUsername(NAME)?.let { held -> tokens.findByUserId(held.id!!).forEach(tokens::delete); users.delete(held) }
        user = users.save(AppUser(username = NAME, displayName = NAME, type = UserType.INTERNAL, passwordHash = encoder.encode("a-long-enough-password")))
    }

    @AfterEach
    fun clear() {
        SecurityContextHolder.clearContext()
        tokens.findByUserId(user.id!!).forEach(tokens::delete)
        users.delete(user)
    }

    @Test
    fun `a caller holding a token is refused a new one, and one in a session is not`() {
        val (_, secret) = internal.mint(user, "first")
        SecurityContextHolder.getContext().authentication = internal.authenticateToken(secret)
        assertThat(calledByToken()).isTrue()
        assertThatThrownBy { api.createUserToken(null, "second", null) }.isInstanceOf(TokenMintedByTokenException::class.java)
        assertThat(tokens.findByUserId(user.id!!)).hasSize(1)

        SecurityContextHolder.getContext().authentication =
            org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(NAME, null, emptyList())
        assertThat(api.createUserToken(null, "second", null).secret).startsWith("orkx_")
    }

    private companion object {
        const val NAME = "token-mint-check"
    }
}
