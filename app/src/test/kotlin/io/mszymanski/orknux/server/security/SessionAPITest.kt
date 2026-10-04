package io.mszymanski.orknux.server.security

import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceOperationType
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestClient

/**
 * End-to-end sign-in: credentials are checked against the LDAP container from
 * compose.yaml, and the resulting session is what attributes audit entries.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionAPITest(
    @LocalServerPort val port: Int,
    @Autowired val workspaceRepository: WorkspaceRepository,
    @Autowired val auditRepository: WorkspaceAuditRepository,
) {

    /** Status handling is disabled so error responses can be asserted on. */
    private val client = RestClient.builder()
        .baseUrl("http://localhost:$port")
        .defaultStatusHandler({ true }, { _, _ -> })
        .build()

    @BeforeEach
    fun clearWorkspaces() {
        auditRepository.deleteAll()
        workspaceRepository.deleteAll()
    }

    @Test
    fun `signs in a directory user and reports their roles`() {
        val response = signIn("alice", "password")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.username).isEqualTo("alice")
        assertThat(response.body?.roles).contains("ROLE_ADMINS", "ROLE_USERS")
        assertThat(response.body?.email).isEqualTo("alice@orknux.io")
        assertThat(sessionCookie(response)).isNotNull()
    }

    @Test
    fun `rejects a wrong password`() {
        assertThat(login("alice", "wrong").statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `rejects an unknown user`() {
        assertThat(login("nobody", "password").statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `refuses to describe the session when signed out`() {
        val response = client.get().uri("/api/session").retrieve().toEntity(String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `describes the signed-in user and forgets them on sign-out`() {
        val cookie = sessionCookie(login("bob", "password"))!!

        val current = client.get().uri("/api/session")
            .header(HttpHeaders.COOKIE, cookie)
            .retrieve()
            .toEntity(SessionUser::class.java)

        assertThat(current.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(current.body?.username).isEqualTo("bob")
        assertThat(current.body?.roles).containsExactlyInAnyOrder("ROLE_USERS", "ROLE_BACKEND")
        assertThat(current.body?.admin).isFalse()
        // A directory user's tokens would outlive what the directory says about them. Issue #2.
        assertThat(current.body?.tokensAllowed).isFalse()
        assertThat(current.body?.email).isEqualTo("bob@orknux.io")

        client.delete().uri("/api/session")
            .header(HttpHeaders.COOKIE, cookie)
            .retrieve()
            .toBodilessEntity()

        val afterLogout = client.get().uri("/api/session")
            .header(HttpHeaders.COOKIE, cookie)
            .retrieve()
            .toEntity(String::class.java)

        assertThat(afterLogout.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `rejects graphql calls without a session`() {
        val response = graphql("{ workspaces { totalElements } }", cookie = null)

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `attributes an audit entry to the signed-in ldap user`() {
        val cookie = sessionCookie(login("alice", "password"))!!

        val response = graphql("""mutation { createWorkspace(input: { name: "platform" }) { id name } }""", cookie)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("platform")

        val entry = auditRepository.findAll().single { it.operationType == WorkspaceOperationType.ADD }
        assertThat(entry.userId).isEqualTo("alice")
        assertThat(entry.operationType).isEqualTo(WorkspaceOperationType.ADD)
        assertThat(entry.newWorkspaceName).isEqualTo("platform")
    }

    /**
     * Sign-in, read as text.
     *
     * A refusal answers with an error object rather than a user, so asking for
     * one back means a rejected password fails while being deserialised instead
     * of being asserted on. [signIn] is for the calls that expect a user.
     */
    private fun login(username: String, password: String): ResponseEntity<String> =
        client.post().uri("/api/session")
            .contentType(MediaType.APPLICATION_JSON)
            .body(LoginRequest(username, password))
            .retrieve()
            .toEntity(String::class.java)

    /** Sign-in that is expected to succeed, with the user it answers with. */
    private fun signIn(username: String, password: String): ResponseEntity<SessionUser> =
        client.post().uri("/api/session")
            .contentType(MediaType.APPLICATION_JSON)
            .body(LoginRequest(username, password))
            .retrieve()
            .toEntity(SessionUser::class.java)

    private fun graphql(query: String, cookie: String?): ResponseEntity<String> =
        client.post().uri("/graphql")
            .contentType(MediaType.APPLICATION_JSON)
            .apply { if (cookie != null) header(HttpHeaders.COOKIE, cookie) }
            .body(mapOf("query" to query))
            .retrieve()
            .toEntity(String::class.java)

    /**
     * The session cookie, which is `SESSION` and not `JSESSIONID`.
     *
     * Sessions live in the database rather than in this process's heap, so the
     * one that comes back is Spring Session's, under its name. Looking for the
     * container's name found nothing and read as "sign-in issued no cookie".
     */
    private fun sessionCookie(response: ResponseEntity<*>): String? =
        response.headers[HttpHeaders.SET_COOKIE]
            ?.firstOrNull { it.startsWith("SESSION=") }
            ?.substringBefore(';')
}
