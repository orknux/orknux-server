package io.mszymanski.orknux.server.security

import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.UserType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Configuration
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.web.client.RestClient

/**
 * The half of this switch that has to hold before anything else is worth testing:
 * that off cannot be arrived at, only chosen.
 *
 * These bind the properties on their own rather than starting the application,
 * because what is being asserted is the binding itself — an application that
 * refuses to start cannot be asserted on from inside a `@SpringBootTest` that
 * needs it to have started.
 */
class AuthMethodBindingTest {

    private val runner = ApplicationContextRunner().withUserConfiguration(BoundSecurity::class.java)

    @Test
    fun `an unset variable is LDAP, not off`() {
        runner.run { context ->
            assertThat(context.getBean(SecurityProperties::class.java).authMethod).isEqualTo(AuthMethod.LDAP)
        }
    }

    /**
     * `ORKNUX_AUTH_METHOD=` with nothing after it reaches the placeholder as an
     * empty string rather than as an absence, so it is worth pinning separately:
     * the one way an operator half-sets a variable must also land on the closed
     * position.
     */
    @Test
    fun `an empty variable is LDAP, not off`() {
        runner.withPropertyValues("orknux.security.auth-method=").run { context ->
            assertThat(context.getBean(SecurityProperties::class.java).authMethod).isEqualTo(AuthMethod.LDAP)
        }
    }

    /**
     * The one that matters most. A misspelling has to be a stopped application, not
     * a quiet fall back to a default and not a quiet fall through to open.
     */
    @Test
    fun `a name that is none of the four refuses to start`() {
        runner.withPropertyValues("orknux.security.auth-method=OFF").run { context ->
            assertThat(context).hasFailed()
            assertThat(context.startupFailure)
                .hasMessageContaining("SecurityProperties")
                .hasStackTraceContaining("No enum constant")
                .hasStackTraceContaining("AuthMethod.OFF")
        }
    }

    @Test
    fun `NONE is reached by writing it, in either case`() {
        runner.withPropertyValues("orknux.security.auth-method=NONE").run { context ->
            assertThat(context.getBean(SecurityProperties::class.java).authMethod).isEqualTo(AuthMethod.NONE)
        }
        runner.withPropertyValues("orknux.security.auth-method=none").run { context ->
            assertThat(context.getBean(SecurityProperties::class.java).authMethod).isEqualTo(AuthMethod.NONE)
        }
    }

    /** What the interface has room for: one line, and a short one. */
    @Test
    fun `the notice is one short line`() {
        assertThat(AUTHENTICATION_OFF).doesNotContain("\n")
        assertThat(AUTHENTICATION_OFF.length).isLessThanOrEqualTo(LONGEST_NOTICE)
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SecurityProperties::class)
    class BoundSecurity

    private companion object {
        /** A strip across the top of a page, not a paragraph under a field. */
        const val LONGEST_NOTICE = 80
    }
}

/**
 * An installation with authentication turned off, describing itself and letting
 * somebody in.
 *
 * The directory is up in this suite and `alice` is real in it, which is what gives
 * the assertions their teeth: nothing here passes for want of something to
 * authenticate against. Authentication is off because it was asked to be off.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["orknux.security.auth-method=NONE"],
)
class OpenAccessSignInTest(
    @LocalServerPort val port: Int,
    @Autowired val users: AppUserRepository,
) {

    /** Status handling is disabled so error responses can be asserted on. */
    private val client = RestClient.builder()
        .baseUrl("http://localhost:$port")
        .defaultStatusHandler({ true }, { _, _ -> })
        .build()

    @Test
    fun `a caller who has signed in to nothing is somebody`() {
        val answer = client.get().uri("/api/session").retrieve().toEntity(SessionUser::class.java)

        assertThat(answer.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(answer.body?.username).isEqualTo(OPEN_ACCESS_USERNAME)
        // The top bar is told the row's own name - which a desktop start before this one in the suite may have changed.
        assertThat(answer.body?.displayName).isEqualTo(users.findByUsername(OPEN_ACCESS_USERNAME)?.displayName)
    }

    /**
     * The decision this mode turns on, pinned so that narrowing it later is a
     * deliberate act rather than a refactor nobody noticed.
     */
    @Test
    fun `that somebody administers, because there is nobody to grant it later`() {
        val answer = client.get().uri("/api/session").retrieve().toEntity(SessionUser::class.java)

        assertThat(answer.body?.admin).isTrue()
    }

    @Test
    fun `the endpoint the interface asks says so, and says it in one line`() {
        val answer = client.get().uri("/api/auth/method").retrieve().toEntity(AuthMethodView::class.java)

        assertThat(answer.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(answer.body?.method).isEqualTo("NONE")
        assertThat(answer.body?.notice).isEqualTo(AUTHENTICATION_OFF)
        // No provider to be sent to, and no form worth drawing either.
        assertThat(answer.body?.authorizeUrl).isNull()
    }

    /**
     * The identity is a row, and a row that cannot be signed in as.
     *
     * It holds no password hash, so it is refused at the door under every method —
     * including the one an operator turns back on tomorrow, when this row is still
     * sitting in `app_user`. That is what keeps it from being a credential left
     * behind by having once run open.
     */
    @Test
    fun `the identity is an ordinary internal row with no password`() {
        val held = requireNotNull(users.findByUsername(OPEN_ACCESS_USERNAME))

        assertThat(held.type).isEqualTo(UserType.INTERNAL)
        assertThat(held.passwordHash).isNull()
        assertThat(held.roles.any { it.administers }).isTrue()
    }

    @Test
    fun `and cannot be signed in as, whatever is typed`() {
        val answer = client.post()
            .uri("/api/session")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"username":"$OPEN_ACCESS_USERNAME","password":"anything-at-all"}""")
            .retrieve()
            .toEntity(String::class.java)

        assertThat(answer.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }
}

/**
 * The chain is unchanged when nobody asked for it to change.
 *
 * The suite's own default is LDAP, so this is the same application every other test
 * in this repository runs against — and a request carrying nobody still gets 401.
 * Worth its own class rather than a line in the one above: the failure this guards
 * against is [OpenAccessFilter] being installed for everybody, and that failure
 * cannot be seen from a context that asked to be open.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthenticationOnByDefaultTest(@LocalServerPort val port: Int) {

    private val client = RestClient.builder()
        .baseUrl("http://localhost:$port")
        .defaultStatusHandler({ true }, { _, _ -> })
        .build()

    @Test
    fun `nobody is nobody unless the installation said otherwise`() {
        val answer = client.get().uri("/api/session").retrieve().toEntity(String::class.java)

        assertThat(answer.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `and the sign-in card is told there is a door`() {
        val answer = client.get().uri("/api/auth/method").retrieve().toEntity(AuthMethodView::class.java)

        assertThat(answer.body?.method).isEqualTo("LDAP")
        // Nothing to shout about, so nothing is drawn across the top of the page.
        assertThat(answer.body?.notice).isNull()
    }
}

/**
 * The two admin screens that report on how this installation is set up, asked about
 * one that is open. Neither may be quiet about it.
 */
@SpringBootTest(properties = ["orknux.security.auth-method=NONE"])
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class OpenAccessHealthTest(@Autowired val graphQlTester: ExecutionGraphQlServiceTester) {

    @Test
    fun `the doctor warns rather than reporting a clean bill`() {
        val checks = graphQlTester.document("""query { doctor { name verdict detail } }""")
            .execute()
            .path("doctor").entityList(Map::class.java).get()

        val authentication = checks.single { it["name"] == "Authentication" }
        assertThat(authentication["verdict"]).isEqualTo("WARN")
        assertThat(authentication["detail"] as String)
            .contains(AUTHENTICATION_OFF)
            .contains(OPEN_ACCESS_USERNAME)
    }

    @Test
    fun `monitoring draws no card for a directory nothing consults`() {
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
            .path("components[0].dependencies[*].name").entityList(String::class.java)
            .containsExactly("Database")
    }
}

/**
 * The desktop app's server: the one identity is the person at the machine, and
 * the session says so, which is what the top bar prints.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["orknux.security.auth-method=NONE", "orknux.security.desktop=true", "server.address=127.0.0.1"],
)
class OpenAccessDesktopTest(@LocalServerPort val port: Int) {

    @Test
    fun `the session names the person at the machine`() {
        val answer = RestClient.builder().baseUrl("http://127.0.0.1:$port").defaultStatusHandler({ true }, { _, _ -> }).build()
            .get().uri("/api/session").retrieve().toEntity(SessionUser::class.java)

        assertThat(answer.body?.username).isEqualTo(OPEN_ACCESS_USERNAME)
        assertThat(answer.body?.displayName).isEqualTo(System.getProperty("user.name"))
    }
}
