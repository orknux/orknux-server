package io.mszymanski.orknux.server.security

import io.mszymanski.orknux.server.user.AppUser
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.UserType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.web.client.RestClient

/**
 * An installation with no directory, describing itself.
 *
 * The all-in-one image is one: it ships nothing to sign in against but the account
 * it generates on its first start. Under the LDAP default it said the opposite in
 * two places at once — the sign-in card offered single sign-on, and the monitoring
 * screen reported the whole server degraded for failing to reach the `localhost:389`
 * that `spring.ldap.urls` falls back to. It worked anyway, because an internal
 * account is checked before the directory, which is exactly what made it worth
 * fixing: it worked and it looked broken.
 *
 * The directory is up in this suite, which is what gives the assertions below their
 * teeth. Nothing here passes for want of an LDAP server to talk to: `alice` is real,
 * her password is right, and she is refused because this installation has no
 * directory to ask — not because asking failed.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["orknux.security.auth-method=INTERNAL"],
)
class InternalSignInTest(
    @LocalServerPort val port: Int,
    @Autowired val users: AppUserRepository,
    @Autowired val encoder: PasswordEncoder,
) {

    /** Status handling is disabled so error responses can be asserted on. */
    private val client = RestClient.builder()
        .baseUrl("http://localhost:$port")
        .defaultStatusHandler({ true }, { _, _ -> })
        .build()

    @BeforeEach
    fun seed() {
        /*
         * The suite shares one database between contexts, so `alice` may already be
         * a row here — recorded by whichever test signed her in against the
         * directory. That row is external and holds no password, which is what makes
         * her a directory user and nothing else. An internal one carrying a password
         * would let her in through the door below and quietly turn the assertion
         * about her into a tautology, so it goes.
         */
        users.findByUsername(DIRECTORY_USER)
            ?.takeIf { it.type == UserType.INTERNAL }
            ?.let(users::delete)

        users.findByUsername(INTERNAL_USER)?.let(users::delete)
        users.save(
            AppUser(
                username = INTERNAL_USER,
                displayName = "Issue 122",
                type = UserType.INTERNAL,
                passwordHash = encoder.encode(INTERNAL_PASSWORD),
            ),
        )
    }

    @AfterEach
    fun clear() {
        users.findByUsername(INTERNAL_USER)?.let(users::delete)
    }

    @Test
    fun `the sign-in card is not told this installation uses a directory`() {
        val answer = client.get().uri("/api/auth/method").retrieve().toEntity(AuthMethodView::class.java)

        assertThat(answer.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(answer.body?.method).isEqualTo("INTERNAL")
        // No provider to be sent to, so no button: the card draws a password box.
        assertThat(answer.body?.authorizeUrl).isNull()
        assertThat(answer.body?.displayName).isEqualTo(INTERNAL_DISPLAY_NAME)
        assertThat(answer.body?.displayName).doesNotContain("single sign-on")
    }

    @Test
    fun `an account this installation holds signs in`() {
        val answer = signIn(INTERNAL_USER, INTERNAL_PASSWORD)

        assertThat(answer.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(answer.body?.username).isEqualTo(INTERNAL_USER)
        // And is told Preferences may make access tokens for them. Issue #2.
        assertThat(answer.body?.tokensAllowed).isTrue()
    }

    @Test
    fun `a directory account does not, with the right password and a directory that is up`() {
        val answer = refusal(DIRECTORY_USER, DIRECTORY_PASSWORD)

        // A plain refusal, and the same sentence any wrong password gets. Not the
        // 409 an OIDC installation answers with, which is a different claim: there
        // the password is somewhere else, here there is no such account.
        assertThat(answer.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(answer.body).doesNotContain("single sign-on")
    }

    @Test
    fun `a wrong password is refused rather than carried to a directory`() {
        assertThat(refusal(INTERNAL_USER, "not-the-password").statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    private fun signIn(username: String, password: String) = post(username, password, SessionUser::class.java)

    /** The same call, read as text: what comes back refused is not a session. */
    private fun refusal(username: String, password: String) = post(username, password, String::class.java)

    private fun <T : Any> post(username: String, password: String, into: Class<T>) = client.post()
        .uri("/api/session")
        .contentType(MediaType.APPLICATION_JSON)
        .body("""{"username":"$username","password":"$password"}""")
        .retrieve()
        .toEntity(into)

    private companion object {
        const val INTERNAL_USER = "issue122"
        const val INTERNAL_PASSWORD = "a-long-enough-password"

        /** Real, in the directory this suite starts, with this password. */
        const val DIRECTORY_USER = "alice"
        const val DIRECTORY_PASSWORD = "password"
    }
}

/**
 * The two screens that report on the directory, asked about an installation that has
 * not got one. They have to agree, and what they have to agree on is that it is
 * absent rather than unreachable.
 */
@SpringBootTest(properties = ["orknux.security.auth-method=INTERNAL"])
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class InternalHealthTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
) {

    @Test
    fun `monitoring draws no card for a directory nobody configured`() {
        graphQlTester.document(
            """
            query {
              components {
                name status detail
                dependencies { name reachable detail }
              }
            }
            """,
        ).execute()
            .path("components[0].status").entity(String::class.java).isEqualTo("HEALTHY")
            .path("components[0].detail").entity(String::class.java).isEqualTo("Answering")
            .path("components[0].dependencies[*].name").entityList(String::class.java)
            .containsExactly("Database")
    }

    @Test
    fun `the doctor says there is no directory rather than saying nothing`() {
        val checks = graphQlTester.document("""query { doctor { name verdict detail } }""")
            .execute()
            .path("doctor").entityList(Map::class.java).get()

        val authentication = checks.single { it["name"] == "Authentication" }
        assertThat(authentication["verdict"]).isEqualTo("OK")
        assertThat(authentication["detail"] as String)
            .contains("this installation holds itself")
            .contains("No directory")
    }
}
