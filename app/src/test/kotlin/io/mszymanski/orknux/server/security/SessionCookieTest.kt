package io.mszymanski.orknux.server.security

import io.mszymanski.orknux.server.user.AppUser
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.UserType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.client.RestClient

/**
 * The session cookie is what the configuration file says it is. Issue #588.
 *
 * Spring Session writes its own cookie and read none of the cookie settings, so
 * a SameSite or HttpOnly an operator chose was documented and ignored, and
 * Secure followed the request - which behind a proxy that ends TLS is never
 * secure. Here the file asks for Strict and Secure over plain HTTP, and the
 * cookie has to say both.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "orknux.security.auth-method=INTERNAL",
        "server.servlet.session.cookie.same-site=strict",
        "orknux.web.session-cookie-secure=true",
    ],
)
class SessionCookieTest(
    @LocalServerPort val port: Int,
    @Autowired val users: AppUserRepository,
    @Autowired val encoder: PasswordEncoder,
) {

    @AfterEach
    fun clear() {
        users.findByUsername(USER)?.let(users::delete)
    }

    @Test
    fun `the cookie carries the SameSite and Secure the file asked for`() {
        users.findByUsername(USER)?.let(users::delete)
        users.save(AppUser(username = USER, displayName = USER, type = UserType.INTERNAL, passwordHash = encoder.encode(PASSWORD)))

        val answer = RestClient.builder().baseUrl("http://localhost:$port").defaultStatusHandler({ true }, { _, _ -> }).build()
            .post().uri("/api/session")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"username":"$USER","password":"$PASSWORD"}""")
            .retrieve().toBodilessEntity()

        assertThat(answer.statusCode).isEqualTo(HttpStatus.OK)
        val cookie = answer.headers[HttpHeaders.SET_COOKIE]!!.first()
        assertThat(cookie).contains("SameSite=Strict").contains("Secure").contains("HttpOnly")
    }

    private companion object {
        const val USER = "cookie-check"
        const val PASSWORD = "a-long-enough-password"
    }
}
