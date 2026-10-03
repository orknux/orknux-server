package io.mszymanski.orknux.server.security

import io.mszymanski.orknux.server.user.AppUser
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.UserType
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.client.RestClient

/**
 * A write a browser says came from another site is refused. Issue #588.
 *
 * Two halves. Over HTTP, with a real session cookie: a page on another origin
 * cannot make a signed-in browser create a workspace, while the interface's own
 * origin and a caller that sends no Origin at all - the CLI, curl - carry on.
 * And the guard on its own, because over HTTP the CORS filter answers some of
 * the same requests first, and what is under test is that this answer does not
 * depend on it.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["orknux.security.auth-method=INTERNAL"],
)
class CrossSiteRequestTest(
    @LocalServerPort val port: Int,
    @Autowired val users: AppUserRepository,
    @Autowired val encoder: PasswordEncoder,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private val client = RestClient.builder()
        .baseUrl("http://localhost:$port")
        .defaultStatusHandler({ true }, { _, _ -> })
        .build()

    @BeforeEach
    fun seed() {
        users.findByUsername(USER)?.let(users::delete)
        users.save(
            AppUser(
                username = USER,
                displayName = "Cross-site",
                type = UserType.INTERNAL,
                passwordHash = encoder.encode(PASSWORD),
            ),
        )
    }

    @AfterEach
    fun clear() {
        users.findByUsername(USER)?.let(users::delete)
        workspaces.findAll().filter { it.name.startsWith("csrf-") }.forEach(workspaces::delete)
    }

    private fun session(): String {
        val answer = client.post().uri("/api/session")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"username":"$USER","password":"$PASSWORD"}""")
            .retrieve().toBodilessEntity()
        assertThat(answer.statusCode).isEqualTo(HttpStatus.OK)
        return answer.headers[HttpHeaders.SET_COOKIE]!!.first().substringBefore(';')
    }

    private fun me(cookie: String, headers: Map<String, String>) = client.post().uri("/graphql")
        .contentType(MediaType.APPLICATION_JSON)
        .header(HttpHeaders.COOKIE, cookie)
        .headers { h -> headers.forEach { (name, value) -> h.set(name, value) } }
        .body("""{"query":"mutation { setChatEnabled(enabled: true) { chatEnabled } }"}""")
        .retrieve().toEntity(String::class.java)

    @Test
    fun `a signed-in browser posting from another origin is refused`() {
        val cookie = session()
        val answer = me(cookie, mapOf(HttpHeaders.ORIGIN to "https://evil.example"))
        assertThat(answer.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `the same browser on this server's own origin, and a caller with no origin, are not`() {
        val cookie = session()
        val own = me(cookie, mapOf(HttpHeaders.ORIGIN to "http://localhost:$port", "Sec-Fetch-Site" to "same-origin"))
        assertThat(own.statusCode).isEqualTo(HttpStatus.OK)

        val bare = me(cookie, emptyMap())
        assertThat(bare.statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `a form from another site with no Origin is refused on what the browser says about it`() {
        val cookie = session()
        assertThat(me(cookie, mapOf("Sec-Fetch-Site" to "cross-site")).statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(me(cookie, mapOf(HttpHeaders.REFERER to "https://evil.example/page")).statusCode)
            .isEqualTo(HttpStatus.FORBIDDEN)
    }

    /** A mutation over GET would be a write a plain link could make. */
    @Test
    fun `a GraphQL mutation cannot be sent as a GET`() {
        val cookie = session()
        val answer = client.get()
            .uri("/graphql?query={q}", "mutation { setChatEnabled(enabled: false) { chatEnabled } }")
            .header(HttpHeaders.COOKIE, cookie)
            .retrieve().toEntity(String::class.java)
        assertThat(answer.statusCode.is2xxSuccessful && (answer.body ?: "").contains("\"chatEnabled\":false")).isFalse()
    }

    @Test
    fun `the interface is served with a policy, and nothing may frame it`() {
        val answer = client.get().uri("/api/auth/method").retrieve().toBodilessEntity()
        val csp = answer.headers.getFirst("Content-Security-Policy")
        assertThat(csp).contains("default-src 'self'").contains("frame-ancestors 'none'").contains("script-src 'self';")
        assertThat(answer.headers.getFirst("X-Frame-Options")).isEqualTo("DENY")
        assertThat(answer.headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff")
        assertThat(answer.headers.getFirst("Referrer-Policy")).isEqualTo("same-origin")
        assertThat(answer.headers.getFirst("Permissions-Policy")).contains("camera=()")
    }

    /* ---- the guard on its own, without CORS in front of it ---- */

    private val guard = CrossSiteRequestGuard(
        WebProperties(allowedOrigins = listOf("http://localhost:5173"), baseUrl = "https://orknux.example"),
    )

    private fun post(vararg headers: Pair<String, String>, path: String = "/graphql") =
        MockHttpServletRequest("POST", path).apply {
            serverName = "orknux.internal"
            serverPort = 8080
            scheme = "http"
            headers.forEach { (name, value) -> addHeader(name, value) }
        }

    @Test
    fun `the guard refuses a foreign origin, a null one, and a foreign referrer`() {
        assertThat(guard.refusal(post(HttpHeaders.ORIGIN to "https://evil.example"))).isNotNull()
        assertThat(guard.refusal(post(HttpHeaders.ORIGIN to "null"))).isNotNull()
        assertThat(guard.refusal(post("Sec-Fetch-Site" to "same-site"))).isNotNull()
        assertThat(guard.refusal(post(HttpHeaders.REFERER to "https://evil.example/x"))).isNotNull()
    }

    @Test
    fun `the guard lets through its own origins, a token, a webhook and a read`() {
        assertThat(guard.refusal(post(HttpHeaders.ORIGIN to "http://orknux.internal:8080"))).isNull()
        assertThat(guard.refusal(post(HttpHeaders.ORIGIN to "http://localhost:5173"))).isNull()
        assertThat(guard.refusal(post(HttpHeaders.ORIGIN to "https://orknux.example"))).isNull()
        assertThat(guard.refusal(post(HttpHeaders.REFERER to "https://orknux.example/workspace/1"))).isNull()
        assertThat(guard.refusal(post("Sec-Fetch-Site" to "same-origin"))).isNull()
        assertThat(guard.refusal(post())).isNull()
        assertThat(
            guard.refusal(post(HttpHeaders.ORIGIN to "https://evil.example", HttpHeaders.AUTHORIZATION to "Bearer orkx_x")),
        ).isNull()
        assertThat(guard.refusal(post(HttpHeaders.ORIGIN to "https://evil.example", path = "/api/webhooks/abc"))).isNull()
        assertThat(guard.refusal(MockHttpServletRequest("GET", "/graphql").apply { addHeader(HttpHeaders.ORIGIN, "https://evil.example") }))
            .isNull()
    }

    private companion object {
        const val USER = "csrf-check"
        const val PASSWORD = "a-long-enough-password"
    }
}
