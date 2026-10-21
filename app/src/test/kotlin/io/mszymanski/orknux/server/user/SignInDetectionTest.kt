package io.mszymanski.orknux.server.user

import io.mszymanski.orknux.server.security.LoginRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.client.RestClient

/**
 * Somebody signing in is somebody this installation now knows about.
 *
 * The point of this file is that it authenticates for real. [UserDetection] is
 * a listener, and a test that hands it an `AuthenticationSuccessEvent` is a test
 * of the listener — which was never the broken half. It passed for as long as
 * the bug lasted, because what was missing was the publisher on the manager the
 * directory door is built from: every bind succeeded, nothing was announced, and
 * the Users page listed only the accounts this installation had made itself.
 *
 * So the credentials here go to the real directory the suite starts — `alice`
 * and `bob` from `docker/ldap/bootstrap.ldif` — and what is asserted is the row
 * afterwards. Nothing between the password and the row is stood in for.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SignInDetectionTest(
    @LocalServerPort val port: Int,
    @Autowired val users: AppUserRepository,
    /** The directory's own manager: the bean `LdapAuthenticationConfig` builds. */
    @Autowired val authenticationManager: AuthenticationManager,
    @Autowired val internal: InternalAuthentication,
    @Autowired val encoder: PasswordEncoder,
) {

    private val client = RestClient.builder()
        .baseUrl("http://localhost:$port")
        .defaultStatusHandler({ true }, { _, _ -> })
        .build()

    /**
     * The suite shares one database between contexts and these two are real
     * directory users, so an earlier test may well have signed them in already.
     * A row that was there before this test started would make every assertion
     * below true without anybody having authenticated.
     */
    @BeforeEach
    fun forgetThem() {
        for (name in listOf(FIRST, SECOND)) users.findByUsername(name)?.let(users::delete)
    }

    @Test
    fun `signing in at the front door writes the directory user down`() {
        val response = client.post().uri("/api/session")
            .contentType(MediaType.APPLICATION_JSON)
            .body(LoginRequest(FIRST, PASSWORD))
            .retrieve()
            .toBodilessEntity()

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)

        val held = users.findByUsername(FIRST)
        assertThat(held).isNotNull
        assertThat(held?.type).isEqualTo(UserType.EXTERNAL)
        // The mail attribute off the inetOrgPerson principal, which is the whole
        // reason the context mapper is configured the way it is.
        assertThat(held?.email).isEqualTo("alice@orknux.io")
        // Nobody typed it, so the directory is still free to refresh it.
        assertThat(held?.emailChosen).isFalse()
    }

    /**
     * The same thing one layer down, and the one that names the bug.
     *
     * The manager is asked directly here, with no HTTP and no controller in the
     * way, so a row appearing can only mean that the manager published the
     * arrival. Before the publisher was put on it this authenticated `bob`
     * perfectly and left the table empty.
     */
    @Test
    fun `the directory manager announces the arrival it authenticated`() {
        val authentication = authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken(SECOND, PASSWORD),
        )

        assertThat(authentication.isAuthenticated).isTrue()
        assertThat(users.findByUsername(SECOND)?.type).isEqualTo(UserType.EXTERNAL)
    }

    /** A second arrival is the same person, not a second one. */
    @Test
    fun `signing in twice writes one row`() {
        repeat(2) { authenticationManager.authenticate(UsernamePasswordAuthenticationToken(SECOND, PASSWORD)) }

        assertThat(users.findAll().count { it.username == SECOND }).isEqualTo(1)
    }

    /** What the Users list shows as Last Signed In, moved by every arrival. */
    @Test
    fun `each sign-in through the directory moves when they last signed in`() {
        authenticationManager.authenticate(UsernamePasswordAuthenticationToken(SECOND, PASSWORD))
        val first = requireNotNull(users.findByUsername(SECOND)?.lastSignedInAt)

        authenticationManager.authenticate(UsernamePasswordAuthenticationToken(SECOND, PASSWORD))

        assertThat(users.findByUsername(SECOND)?.lastSignedInAt).isAfter(first)
    }

    /** The door that publishes nothing stamps it too; a wrong password does not. */
    @Test
    fun `a password this installation holds stamps when they last signed in`() {
        users.findByUsername(INTERNAL_NAME)?.let(users::delete)
        users.save(
            AppUser(
                username = INTERNAL_NAME,
                displayName = INTERNAL_NAME,
                type = UserType.INTERNAL,
                passwordHash = encoder.encode(INTERNAL_PASSWORD),
            ),
        )

        assertThat(internal.authenticate(INTERNAL_NAME, "not the password")).isNull()
        assertThat(users.findByUsername(INTERNAL_NAME)?.lastSignedInAt).isNull()

        assertThat(internal.authenticate(INTERNAL_NAME, INTERNAL_PASSWORD)).isNotNull
        assertThat(users.findByUsername(INTERNAL_NAME)?.lastSignedInAt).isNotNull
    }

    private companion object {
        /** Real, in the directory this suite starts, with this password. */
        const val FIRST = "alice"
        const val SECOND = "bob"
        const val PASSWORD = "password"
        const val INTERNAL_NAME = "signin-stamp"
        const val INTERNAL_PASSWORD = "a long enough password"
    }
}
