package io.mszymanski.orknux.server.update

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.update.TestReleaseJars.Shape
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.sql.DataSource

/**
 * A server jar from a URL an administrator gives - a company's Artifactory, in
 * the case this was built for. Issue #589.
 *
 * The repository is a loopback HTTP server, and the jars are real ones signed
 * with the test key (see [TestReleaseJars]), so what is exercised is the
 * download, its bounds and its credential, followed by the very verification
 * an upload gets.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
@ExtendWith(OutputCaptureExtension::class)
class ServerReleaseUrlTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val releases: ServerReleaseRepository,
    @Autowired val settings: InstallationSettings,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val dataSource: DataSource,
) {

    private val jdbc = JdbcTemplate(dataSource)

    @BeforeEach
    fun reset() {
        jdbc.update("DELETE FROM server_release_part")
        jdbc.update("DELETE FROM server_release")
        jdbc.update("DELETE FROM installation_setting WHERE name LIKE 'release%'")
        audit.deleteAll()
        served = TestReleaseJars.signed(Shape(version = "9.8.1"))
        authorizationAtTheOtherHost = null
        requests.clear()
    }

    private fun fetch(url: String, credential: String? = null) = graphQlTester.document(
        """
        mutation(${'$'}url: String!, ${'$'}credential: String) {
          installServerReleaseFromUrl(url: ${'$'}url, credential: ${'$'}credential) {
            version source sourceUrl state activatable
          }
        }
        """,
    ).variable("url", url).variable("credential", credential).execute()

    private fun refusedWith(url: String, code: String, credential: String? = null) =
        fetch(url, credential).errors().satisfy { errors -> assertThat(errors.single().extensions["code"]).isEqualTo(code) }

    @Test
    fun `a signed jar at a URL is fetched, verified and stored, not started, and audited by its host`() {
        fetch("http://${where()}/public/orknux-server.jar?token=not-kept#also-not")
            .path("installServerReleaseFromUrl.version").entity(String::class.java).isEqualTo("9.8.1")
            .path("installServerReleaseFromUrl.source").entity(String::class.java).isEqualTo("URL")
            .path("installServerReleaseFromUrl.sourceUrl").entity(String::class.java)
            .isEqualTo("http://${where()}/public/orknux-server.jar")
            .path("installServerReleaseFromUrl.state").entity(String::class.java).isEqualTo("STORED")
            .path("installServerReleaseFromUrl.activatable").entity(Boolean::class.java).isEqualTo(true)

        val row = releases.findAll().single()
        assertThat(row.sha256).isEqualTo(ReleaseJarVerifier.sha256(served))
        assertThat(row.source).isEqualTo(ServerReleaseSource.URL)
        assertThat(audit.findAll().map { it.message }).containsExactly("Server release 9.8.1 fetched from 127.0.0.1")

        // Stored is stored: the same jar again is refused like a second upload.
        refusedWith("http://${where()}/public/orknux-server.jar", "ServerReleaseAlreadyStored")
    }

    @Test
    fun `an unsigned jar is refused exactly as an upload would be, and nothing is kept`() {
        served = TestReleaseJars.unsigned(Shape(version = "9.8.2"))

        fetch("http://${where()}/public/orknux-server.jar").errors().satisfy { errors ->
            assertThat(errors.single().extensions["code"]).isEqualTo("ReleaseJarRefused")
            assertThat(errors.single().message).isEqualTo("This jar cannot be used: it is not signed.")
        }
        assertThat(releases.findAll()).isEmpty()
    }

    @Test
    fun `a release older than in-place updates is refused from a URL too`() {
        served = TestReleaseJars.signed(Shape(version = "0.9.9.7"))

        fetch("http://${where()}/public/orknux-server.jar").errors().satisfy { errors ->
            assertThat(errors.single().message).contains("it predates in-place updates (0.9.9.8)")
        }
        assertThat(releases.findAll()).isEmpty()
    }

    @Test
    fun `a repository that wants a credential is answered with it, and it is kept nowhere`(output: CapturedOutput) {
        refusedWith("http://${where()}/private/orknux-server.jar", "ServerReleaseFetchFailed")
        refusedWith("http://${where()}/private/orknux-server.jar", "ServerReleaseFetchFailed", credential = "deploy:wrong")

        fetch("http://${where()}/private/orknux-server.jar", credential = "$USER:$PASSWORD")
            .path("installServerReleaseFromUrl.version").entity(String::class.java).isEqualTo("9.8.1")

        // And as a token, the other shape a repository takes.
        served = TestReleaseJars.signed(Shape(version = "9.8.3"))
        fetch("http://${where()}/token/orknux-server.jar", credential = TOKEN)
            .path("installServerReleaseFromUrl.version").entity(String::class.java).isEqualTo("9.8.3")

        val secrets = listOf(PASSWORD, TOKEN, Base64.getEncoder().encodeToString("$USER:$PASSWORD".toByteArray()))
        val rows = jdbc.queryForList("SELECT * FROM server_release").joinToString { it.toString() }
        val audited = audit.findAll().joinToString { "${it.message} ${it.toString()}" }
        for (secret in secrets) {
            assertThat(rows).doesNotContain(secret)
            assertThat(audited).doesNotContain(secret)
            assertThat(output.all).doesNotContain(secret)
        }
        assertThat(audit.findAll().map { it.message }).contains(
            "Server release 9.8.1 fetched from 127.0.0.1",
            "Server release 9.8.3 fetched from 127.0.0.1",
        )
    }

    @Test
    fun `the credential goes to the host it was given for and is dropped at a redirect elsewhere`() {
        fetch("http://${where()}/elsewhere/orknux-server.jar", credential = TOKEN)
            .path("installServerReleaseFromUrl.version").entity(String::class.java).isEqualTo("9.8.1")

        assertThat(authorizationAtTheOtherHost).isEqualTo("none")
    }

    @Test
    fun `only http and https are fetched, at the start and at every redirect`() {
        refusedWith("file:///etc/passwd", "ServerReleaseUrlRefused")
        refusedWith("jar:file:/app/app.jar!/BOOT-INF/classes/application.yml", "ServerReleaseUrlRefused")
        refusedWith("ftp://${where()}/orknux-server.jar", "ServerReleaseUrlRefused")
        refusedWith("http://deploy:secret@${where()}/public/orknux-server.jar", "ServerReleaseUrlRefused")

        fetch("http://${where()}/to-file/orknux-server.jar").errors().satisfy { errors ->
            assertThat(errors.single().extensions["code"]).isEqualTo("ServerReleaseUrlRefused")
            assertThat(errors.single().message).contains("file:")
        }
        assertThat(releases.findAll()).isEmpty()
    }

    @Test
    fun `a body larger than this installation takes is cut off and refused`() {
        settings.setReleaseMaxMb(64, "alice")

        refusedWith("http://${where()}/huge/orknux-server.jar", "ServerReleaseTooLarge")
        assertThat(releases.findAll()).isEmpty()
    }

    @Test
    fun `a repository directory lists what is newer than this server, its jars resolved against it`() {
        graphQlTester.document(
            """{ serverReleasesAtUrl(url: "http://${where()}/repo/") { version jarUrl stored } }""",
        ).execute()
            .path("serverReleasesAtUrl[*].version").entityList(String::class.java).containsExactly("99.0.2", "99.0.1")
            .path("serverReleasesAtUrl[1].jarUrl").entity(String::class.java)
            .isEqualTo("http://${where()}/repo/jars/orknux-server-99.0.1.jar")

        graphQlTester.document("""{ serverReleasesAtUrl(url: "http://${where()}/repo/releases.json") { version } }""")
            .execute().errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("ServerReleaseUrlRefused") }
    }

    @Test
    @WithMockUser(username = "bob", roles = ["USERS"])
    fun `somebody who is not an administrator fetches nothing`() {
        fetch("http://${where()}/public/orknux-server.jar").errors().satisfy { assertThat(it).isNotEmpty() }
        assertThat(releases.findAll()).isEmpty()
        assertThat(requests).isEmpty()
    }

    @Test
    fun `how long a download may take is an Admin Setting, refused out of range`() {
        graphQlTester.document("mutation { setReleaseDownloadSeconds(seconds: 120) { releaseDownloadSeconds releaseDownloadSecondsConfigured } }")
            .execute()
            .path("setReleaseDownloadSeconds.releaseDownloadSeconds").entity(Int::class.java).isEqualTo(120)
            .path("setReleaseDownloadSeconds.releaseDownloadSecondsConfigured").entity(Int::class.java).isEqualTo(600)
        graphQlTester.document("mutation { setReleaseDownloadSeconds(seconds: 5) { releaseDownloadSeconds } }").execute()
            .errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("ReleaseDownloadOutOfRange") }
        assertThat(settings.releaseDownloadSeconds()).isEqualTo(120)
    }

    @Test
    fun `the configured source fills the page`() {
        graphQlTester.document("{ serverUpdates { sourceUrl } }").execute()
            .path("serverUpdates.sourceUrl").entity(String::class.java).isEqualTo("http://${where()}/repo/")
    }

    companion object {
        private const val USER = "deploy"
        private const val PASSWORD = "s3cret-artifactory-pass"
        private const val TOKEN = "cmVmdGtuOjAxOjE3OTk5OTk5OTk6c2VjcmV0"

        /** The jar the repository hands out; a test sets it. */
        @Volatile
        lateinit var served: Path

        /** What arrived as Authorization at the host a redirect went to; "none" for nothing. */
        @Volatile
        var authorizationAtTheOtherHost: String? = null

        /** Every path asked for, so a test can say a request was never made. */
        val requests: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

        private val stub: HttpServer =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                fun send(exchange: HttpExchange, status: Int, body: ByteArray = ByteArray(0)) {
                    exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
                    if (body.isNotEmpty()) exchange.responseBody.use { it.write(body) }
                    exchange.close()
                }
                fun redirect(exchange: HttpExchange, to: String) {
                    exchange.responseHeaders.add("Location", to)
                    send(exchange, 302)
                }
                createContext("/") { exchange ->
                    val path = exchange.requestURI.path
                    requests += path
                    val authorization = exchange.requestHeaders.getFirst("Authorization")
                    when {
                        path.startsWith("/public/") -> send(exchange, 200, Files.readAllBytes(served))
                        path.startsWith("/private/") ->
                            if (authorization == "Basic " + Base64.getEncoder().encodeToString("$USER:$PASSWORD".toByteArray())) {
                                send(exchange, 200, Files.readAllBytes(served))
                            } else {
                                send(exchange, 401)
                            }
                        path.startsWith("/token/") ->
                            if (authorization == "Bearer $TOKEN") send(exchange, 200, Files.readAllBytes(served)) else send(exchange, 401)
                        // 127.0.0.1 and localhost are the same server and different hosts.
                        path.startsWith("/elsewhere/") ->
                            if (exchange.requestHeaders.getFirst("Host").orEmpty().startsWith("localhost")) {
                                authorizationAtTheOtherHost = authorization ?: "none"
                                send(exchange, 200, Files.readAllBytes(served))
                            } else {
                                redirect(exchange, "http://localhost:${address.port}/elsewhere/orknux-server.jar")
                            }
                        path.startsWith("/to-file/") -> redirect(exchange, "file:///etc/passwd")
                        path.startsWith("/huge/") -> {
                            // Chunked, so no length says it in advance: counted as it arrives.
                            exchange.sendResponseHeaders(200, 0)
                            try {
                                exchange.responseBody.use { out ->
                                    val chunk = ByteArray(1024 * 1024)
                                    repeat(80) { out.write(chunk) }
                                }
                            } catch (_: java.io.IOException) {
                                // The server stopped reading, which is the point.
                            }
                            exchange.close()
                        }
                        path == "/repo/releases.json" -> send(
                            exchange,
                            200,
                            """
                            [
                              {"version": "0.9.9.7", "jarUrl": "jars/orknux-server-0.9.9.7.jar"},
                              {"version": "99.0.1", "jarUrl": "jars/orknux-server-99.0.1.jar"},
                              {"version": "99.0.2", "jarUrl": "http://${address.hostString}:${address.port}/other/99.0.2.jar"},
                              {"version": "not a version", "jarUrl": "x.jar"},
                              {"version": "99.0.3", "jarUrl": "file:///etc/passwd"}
                            ]
                            """.trimIndent().toByteArray(),
                        )
                        else -> send(exchange, 404)
                    }
                }
                start()
            }

        fun where() = "${stub.address.hostString}:${stub.address.port}"

        @JvmStatic
        @AfterAll
        fun stop() = stub.stop(0)

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("orknux.update.certificate") { TestReleaseJars.trusted.pem.toUri().toString() }
            registry.add("orknux.update.source-url") { "http://${where()}/repo/" }
        }
    }
}
