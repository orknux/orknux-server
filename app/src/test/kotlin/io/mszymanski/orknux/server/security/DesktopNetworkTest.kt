package io.mszymanski.orknux.server.security

import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.nio.file.Path

/**
 * The desktop app's network switch, on a desktop server.
 *
 * It writes desktop.json for the next start and, going exposed, sets the
 * password the person at the machine will sign in with. The restart that
 * applies it is the desktop app's start loop, which a test has none of - so
 * the server here answers `restarts: false` and stays up.
 */
@SpringBootTest(properties = ["orknux.security.desktop=true", "orknux.security.auth-method=NONE", "server.address=127.0.0.1"])
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class DesktopNetworkTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val users: AppUserRepository,
    @Autowired val encoder: PasswordEncoder,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    companion object {
        private val data: Path = Files.createTempDirectory("orknux-desktop-network-")

        @JvmStatic
        @DynamicPropertySource
        fun dataDirectory(registry: DynamicPropertyRegistry) {
            registry.add("ORKNUX_DATA") { data.toString() }
        }
    }

    private val file get() = data.resolve("desktop.json")
    private val account = System.getProperty("user.name").lowercase().replace(Regex("[^a-z0-9._-]+"), "").ifEmpty { "admin" }

    @BeforeEach
    fun clean() {
        Files.deleteIfExists(file)
        Files.writeString(file, """{"window": {"width": 900}}""")
    }

    @Test
    fun `it says what runs now, what the next start will be, and who would sign in`() {
        graphQlTester.document("{ desktopNetwork { mode chosen restartRequired account restarts addresses } }").execute()
            .path("desktopNetwork.mode").entity(String::class.java).isEqualTo("LOCAL")
            .path("desktopNetwork.chosen").entity(String::class.java).isEqualTo("LOCAL")
            .path("desktopNetwork.restartRequired").entity(Boolean::class.java).isEqualTo(false)
            .path("desktopNetwork.account").entity(String::class.java).isEqualTo(account)
            .path("desktopNetwork.restarts").entity(Boolean::class.java).isEqualTo(false)
    }

    @Test
    fun `exposing it sets the person's password, writes the file for the next start, and says so`() {
        graphQlTester.document("""mutation { setDesktopNetwork(mode: EXPOSED, password: "correct horse battery") { chosen restartRequired } }""")
            .execute()
            .path("setDesktopNetwork.chosen").entity(String::class.java).isEqualTo("EXPOSED")
            .path("setDesktopNetwork.restartRequired").entity(Boolean::class.java).isEqualTo(true)

        val written = Files.readString(file)
        assertThat(written).contains("\"network\" : \"exposed\"", "\"account\" : \"$account\"")
        // What else the file held is kept.
        assertThat(written).contains("\"width\" : 900")

        val held = requireNotNull(users.findByUsername(account))
        assertThat(encoder.matches("correct horse battery", held.passwordHash)).isTrue()
        assertThat(held.roles.any { it.administers }).isTrue()
        assertThat(audit.findAll().map { it.message }).contains("The desktop app was opened to the network; $account signs in")

        // And back: this computer only, the file says so.
        graphQlTester.document("mutation { setDesktopNetwork(mode: LOCAL) { chosen restartRequired } }").execute()
            .path("setDesktopNetwork.chosen").entity(String::class.java).isEqualTo("LOCAL")
            .path("setDesktopNetwork.restartRequired").entity(Boolean::class.java).isEqualTo(false)
        assertThat(Files.readString(file)).contains("\"network\" : \"local\"")
        assertThat(audit.findAll().map { it.message }).contains("The desktop app was closed to the network; only this computer reaches it")
    }

    @Test
    fun `a password too short to sign in with is refused, and nothing is written`() {
        graphQlTester.document("""mutation { setDesktopNetwork(mode: EXPOSED, password: "short") { chosen } }""").execute()
            .errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("PasswordTooShort") }
        graphQlTester.document("mutation { setDesktopNetwork(mode: EXPOSED) { chosen } }").execute()
            .errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("PasswordTooShort") }
        assertThat(Files.readString(file)).doesNotContain("exposed")
    }

    @Test
    @WithMockUser(username = "bob", roles = ["USERS"])
    fun `only an administrator sees or sets it`() {
        graphQlTester.document("{ desktopNetwork { mode } }").execute()
            .errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("AdminRequired") }
        graphQlTester.document("mutation { setDesktopNetwork(mode: LOCAL) { mode } }").execute()
            .errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("AdminRequired") }
    }
}

/** A server that is not the desktop app's has no switch: nothing to draw, and setting it is refused. */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class DesktopNetworkOffDesktopTest(@Autowired val graphQlTester: ExecutionGraphQlServiceTester) {

    @Test
    fun `off the desktop there is no switch`() {
        graphQlTester.document("{ desktopNetwork { mode } }").execute()
            .path("desktopNetwork").valueIsNull()
        graphQlTester.document("mutation { setDesktopNetwork(mode: LOCAL) { mode } }").execute()
            .errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("DesktopNetworkUnavailable") }
    }
}
