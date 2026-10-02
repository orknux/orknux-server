package io.mszymanski.orknux.server.update

import io.mszymanski.orknux.server.update.TestReleaseJars.Shape
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

/**
 * The upload through a real Tomcat, which MockMvc is not. Issue #589.
 *
 * A release jar is a third of a gigabyte, and the installation-wide upload caps
 * (ORKNUX_UPLOAD_MAX_FILE_SIZE and _REQUEST_SIZE) are sized for a recording. A
 * test that posts a small jar through MockMvc passes whatever those say, which
 * is how a real jar came to be answered 413 while the suite was green - so the
 * jars here are past those caps, sent the way the page and curl send them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServerReleaseUploadHttpTest(
    @LocalServerPort val port: Int,
    @Autowired val releases: ServerReleaseRepository,
    @Autowired val dataSource: DataSource,
    @Autowired val settings: io.mszymanski.orknux.server.attachment.InstallationSettings,
) {

    private val jdbc = JdbcTemplate(dataSource)
    private val http: HttpClient = HttpClient.newHttpClient()

    @BeforeEach
    fun reset() {
        jdbc.update("DELETE FROM server_release_part")
        jdbc.update("DELETE FROM server_release")
        jdbc.update("DELETE FROM installation_setting WHERE name LIKE 'release%'")
    }

    private fun session(): String {
        val answer = http.send(
            HttpRequest.newBuilder(URI("http://localhost:$port/api/session"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"username":"alice","password":"password"}"""))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertThat(answer.statusCode()).isEqualTo(200)
        return answer.headers().allValues("Set-Cookie").first { it.startsWith("SESSION=") }.substringBefore(';')
    }

    private fun upload(
        jar: Path,
        contentType: String,
        cookie: String = session(),
        expectContinue: Boolean = false,
    ): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI("http://localhost:$port/api/server-releases"))
            .expectContinue(expectContinue)
            .header("Cookie", cookie)
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofFile(jar))
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    @Test
    fun `a jar past the attachment caps is uploaded as the body, whichever type it is sent as`() {
        val jar = TestReleaseJars.signed(Shape(version = "9.7.1", padding = 40 * 1024 * 1024))
        assertThat(Files.size(jar)).isGreaterThan(26L * 1024 * 1024)

        val answer = upload(jar, "application/java-archive")

        assertThat(answer.statusCode()).withFailMessage { "${answer.statusCode()}: ${answer.body()}" }.isEqualTo(201)
        assertThat(answer.body()).contains("\"version\":\"9.7.1\"")

        // As curl sends it with --data-binary and nothing else said.
        val second = TestReleaseJars.signed(Shape(version = "9.7.2", padding = 30 * 1024 * 1024))
        val form = upload(second, "application/x-www-form-urlencoded")
        assertThat(form.statusCode()).withFailMessage { "${form.statusCode()}: ${form.body()}" }.isEqualTo(201)

        assertThat(releases.findAll().map { it.version }).containsExactlyInAnyOrder("9.7.1", "9.7.2")
    }

    /**
     * A jar of more than a hundred megabytes goes into the database a piece at
     * a time and comes back out the same way - neither the store nor the
     * launcher's read holds it whole, which is what lets a 350 MB release fit
     * a server's heap. Byte for byte, on whichever engine the suite runs.
     */
    @Test
    fun `a jar past a hundred megabytes is stored and read back in pieces`() {
        val jar = TestReleaseJars.signed(Shape(version = "9.7.3", padding = 110 * 1024 * 1024))

        assertThat(upload(jar, "application/octet-stream").statusCode()).isEqualTo(201)

        val id = releases.findAll().single().id!!
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM server_release_part WHERE release_id = ?", Int::class.java, id))
            .isGreaterThan(10)
        val back = Files.createTempDirectory("orknux-back-").resolve("back.jar")
        JdbcReleaseStore { dataSource.connection }.writeJar(id, back)
        assertThat(ReleaseJarVerifier.sha256(back)).isEqualTo(ReleaseJarVerifier.sha256(jar))
        Files.deleteIfExists(back)
    }

    @Test
    fun `a jar past this installation's limit is refused in one line before it is read`() {
        val cookie = session()
        settings.setReleaseMaxMb(64, "alice")
        val jar = TestReleaseJars.signed(Shape(version = "9.7.4", padding = 70 * 1024 * 1024))

        // As curl sends a large body: it waits for the server's go-ahead, and a
        // refusal by the declared length arrives before a byte of the jar is sent.
        val answer = upload(jar, "application/octet-stream", cookie, expectContinue = true)

        assertThat(answer.statusCode()).isEqualTo(400)
        assertThat(answer.body()).contains("That jar is larger than the 64 MB this installation takes.")
        assertThat(answer.body()).contains("\"code\":\"ServerReleaseTooLarge\"")
        assertThat(releases.findAll()).isEmpty()
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("orknux.update.certificate") { TestReleaseJars.trusted.pem.toUri().toString() }
        }
    }
}
