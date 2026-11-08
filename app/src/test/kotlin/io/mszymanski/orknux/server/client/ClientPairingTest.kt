package io.mszymanski.orknux.server.client

import io.mszymanski.orknux.server.user.AppUser
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.UserType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/**
 * Connecting a client with a pairing code: issued to whoever is signed in,
 * traded once over the real port for a credential, which opens the client doors
 * and nothing else, and stops when disconnected - for a directory account too,
 * which may not have an access token at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClientPairingTest(
    @LocalServerPort val port: Int,
    @Autowired val api: ClientPairingAPI,
    @Autowired val pairings: ClientPairings,
    @Autowired val tokenFilter: io.mszymanski.orknux.server.user.TokenAuthenticationFilter,
    @Autowired val codes: ClientPairingCodeRepository,
    @Autowired val credentials: ClientCredentialRepository,
    @Autowired val users: AppUserRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private val client = RestClient.builder().baseUrl("http://localhost:$port").defaultStatusHandler({ true }, { _, _ -> }).build()

    @AfterEach
    fun signOut() = SecurityContextHolder.clearContext()

    private fun signedInAs(name: String) {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(name, null, emptyList())
    }

    private fun pair(code: String) = client.post().uri("/api/clients/pair").contentType(MediaType.APPLICATION_JSON)
        .body("""{"code":"$code","client":"Chrome","name":"Chrome on test"}""").retrieve().toEntity(String::class.java)

    private fun hello(credential: String) = client.get().uri("/api/browser/hello")
        .header(HttpHeaders.AUTHORIZATION, "Bearer $credential").retrieve().toEntity(String::class.java)

    private fun credentialFrom(code: String): String {
        val paired = pair(code)
        assertThat(paired.statusCode).isEqualTo(HttpStatus.OK)
        return mapper.readTree(paired.body).path("credential").asString("")
    }

    @Test
    fun `a code is traded once for a credential that opens the client doors`() {
        val person = "pairer-" + System.nanoTime()
        signedInAs(person)
        val code = api.startClientPairing().code
        SecurityContextHolder.clearContext()

        assertThat(code).matches("[A-HJ-NP-Z2-9]{3}-[A-HJ-NP-Z2-9]{3}")
        val credential = credentialFrom(code.lowercase())
        assertThat(credential).startsWith("orkxc_")
        assertThat(pair(code).statusCode).describedAs("a code works once").isEqualTo(HttpStatus.BAD_REQUEST)

        val greeted = hello(credential)
        assertThat(greeted.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(mapper.readTree(greeted.body).path("user").asString("")).isEqualTo(person)
    }

    @Test
    fun `the Chrome extension trades a code with its own origin`() {
        signedInAs("extension-" + System.nanoTime())
        val code = api.startClientPairing().code
        SecurityContextHolder.clearContext()

        val paired = client.post().uri("/api/clients/pair").contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.ORIGIN, "chrome-extension://abcdefghijklmnopabcdefghijklmnop")
            .body("""{"code":"$code","client":"Chrome"}""").retrieve().toEntity(String::class.java)
        assertThat(paired.statusCode).describedAs(paired.body).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `the credential is refused everywhere but the client doors, in words`() {
        signedInAs("narrow-" + System.nanoTime())
        val credential = credentialFrom(api.startClientPairing().code)
        SecurityContextHolder.clearContext()

        val graphql = client.post().uri("/graphql").header(HttpHeaders.AUTHORIZATION, "Bearer $credential")
            .contentType(MediaType.APPLICATION_JSON).body("""{"query":"{ myClients { id } }"}""").retrieve().toEntity(String::class.java)
        assertThat(graphql.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(graphql.body).contains("client credential")
    }

    @Test
    fun `a code that ran out is refused, and so is one never issued`() {
        val person = "late-" + System.nanoTime()
        signedInAs(person)
        val code = api.startClientPairing().code
        val held = codes.findAll().single { it.username == person }
        codes.delete(held)
        codes.save(ClientPairingCode(username = person, codeHash = held.codeHash, expiresAt = OffsetDateTime.now().minusMinutes(1)))

        assertThat(pair(code).statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(pair("ZZZ-ZZZ").statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `disconnecting stops it, and somebody else's client is not found`() {
        val person = "revoker-" + System.nanoTime()
        signedInAs(person)
        val credential = credentialFrom(api.startClientPairing().code)
        signedInAs(person)
        val mine = api.myClients().single()
        assertThat(mine.name).isEqualTo("Chrome on test")

        signedInAs("somebody-else-" + System.nanoTime())
        assertThatThrownBy { api.disconnectClient(mine.id) }.isInstanceOf(ClientNotFoundException::class.java)

        signedInAs(person)
        assertThat(api.disconnectClient(mine.id)).isTrue()
        assertThat(hello(credential).statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `a directory account can pair, and its client stops after going unused too long`() {
        val person = "directory-" + System.nanoTime()
        users.save(AppUser(username = person, displayName = "From the directory", type = UserType.EXTERNAL))
        signedInAs(person)
        val credential = credentialFrom(api.startClientPairing().code)
        assertThat(hello(credential).statusCode).isEqualTo(HttpStatus.OK)

        val held = credentials.findByUsernameIgnoreCaseOrderByCreatedAtDesc(person).single()
        held.lastUsedAt = OffsetDateTime.now().minusDays(31)
        credentials.save(held)
        assertThat(hello(credential).statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(credentials.findByUsernameIgnoreCaseOrderByCreatedAtDesc(person)).isEmpty()
    }

    @Test
    fun `a directory account's client holds the groups it signed in with, which nothing else keeps`() {
        val person = "grouped-" + System.nanoTime()
        users.save(AppUser(username = person, displayName = "In a group", type = UserType.EXTERNAL))
        // What a directory sign-in hands over: its groups as authorities, stored on no row.
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
            person, null, listOf(org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMINS")),
        )
        val credential = credentialFrom(api.startClientPairing().code)
        SecurityContextHolder.clearContext()

        val held = requireNotNull(pairings.authenticate(credential)).authorities.map { it.authority }
        assertThat(held).contains("ROLE_ADMINS", ClientPairings.CLIENT_AUTHORITY)
    }

    @Test
    fun `the credential decides who is asking, even when a browser session comes with it`() {
        val owner = "paired-" + System.nanoTime()
        signedInAs(owner)
        val credential = credentialFrom(api.startClientPairing().code)

        // A browser extension's request carries the browser's Orknux session too: somebody else is signed in there.
        signedInAs("signed-in-elsewhere")
        val request = org.springframework.mock.web.MockHttpServletRequest("GET", "/api/browser/hello").apply {
            addHeader(HttpHeaders.AUTHORIZATION, "Bearer $credential")
        }
        var asking: String? = null
        tokenFilter.doFilter(request, org.springframework.mock.web.MockHttpServletResponse()) { _, _ ->
            asking = SecurityContextHolder.getContext().authentication?.name
        }
        assertThat(asking).isEqualTo(owner)

        // And a credential that is not valid is not carried by the session.
        val forged = org.springframework.mock.web.MockHttpServletRequest("GET", "/api/browser/hello").apply {
            addHeader(HttpHeaders.AUTHORIZATION, "Bearer orkxc_not-a-real-one")
        }
        signedInAs("signed-in-elsewhere")
        tokenFilter.doFilter(forged, org.springframework.mock.web.MockHttpServletResponse()) { _, _ ->
            asking = SecurityContextHolder.getContext().authentication?.name
        }
        assertThat(asking).isNull()
    }
}
