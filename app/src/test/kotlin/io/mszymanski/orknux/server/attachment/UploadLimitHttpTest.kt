package io.mszymanski.orknux.server.attachment

import io.mszymanski.orknux.server.workflow.SavedArtifactRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The upload limit through a real Tomcat, which MockMvc is not.
 *
 * Spring's multipart caps used to be the limit: past 25 MB Tomcat refused the
 * request before any code ran, the browser saw the connection go and the chat
 * said "Failed to fetch". Now they sit above the largest the limit can be, so a
 * file past Admin Settings' Largest file is answered 413 with a body that says
 * the limit and where to raise it, and a file the old caps would have dropped
 * is kept when the limit allows it. Sent as a browser's FormData sends it.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["orknux.attachments.location=target/test-upload-limit-http"],
)
class UploadLimitHttpTest(
    @LocalServerPort val port: Int,
    @Autowired val settings: InstallationSettings,
    @Autowired val held: InstallationSettingRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val attachments: ChatAttachmentRepository,
    @Autowired val saved: SavedArtifactRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private val http: HttpClient = HttpClient.newHttpClient()
    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        forget()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "upload-limit-http-${System.nanoTime()}")).id)
    }

    @AfterEach
    fun forget() {
        held.findAll().filter { it.name == SettingNames.UPLOAD_MAX_MB }.forEach(held::delete)
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

    /** One file in a multipart form, under [field], as FormData sends it. */
    private fun post(path: String, field: String, filename: String, bytes: ByteArray, cookie: String): HttpResponse<String> {
        val boundary = "----orknux${System.nanoTime()}"
        val body = ByteArrayOutputStream().apply {
            write("--$boundary\r\nContent-Disposition: form-data; name=\"$field\"; filename=\"$filename\"\r\n".toByteArray())
            write("Content-Type: application/octet-stream\r\n\r\n".toByteArray())
            write(bytes)
            write("\r\n--$boundary--\r\n".toByteArray())
        }.toByteArray()
        return http.send(
            HttpRequest.newBuilder(URI("http://localhost:$port$path"))
                .header("Cookie", cookie)
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    }

    @Test
    fun `a chat file past the old 25 MB cap is kept when the limit allows it, and refused in words when it does not`() {
        val cookie = session()
        val file = ByteArray(30 * 1024 * 1024)

        // At the default 25 MB: answered, not reset - a body the page can show.
        val refused = post("/api/workspaces/$workspaceId/attachments", "files", "recording.wav", file, cookie)
        assertThat(refused.statusCode()).withFailMessage { "${refused.statusCode()}: ${refused.body()}" }.isEqualTo(413)
        val said = mapper.readTree(refused.body())
        assertThat(said.path("message").asString()).isEqualTo(
            "recording.wav is 30 MB; uploads are limited to 25 MB - an administrator can raise it in Admin Settings → Attachments",
        )
        assertThat(said.path("code").asString()).isEqualTo("AttachmentTooLarge")
        assertThat(said.path("arguments").path("limitMb").asInt()).isEqualTo(25)
        assertThat(attachments.findAll().filter { it.workspaceId == workspaceId }).isEmpty()

        settings.setMaxFileSizeMb(40, "alice")
        val kept = post("/api/workspaces/$workspaceId/attachments", "files", "recording.wav", file, cookie)
        assertThat(kept.statusCode()).withFailMessage { "${kept.statusCode()}: ${kept.body()}" }.isEqualTo(200)
        assertThat(attachments.findAll().filter { it.workspaceId == workspaceId }.map { it.sizeBytes }).containsExactly(30L * 1024 * 1024)
    }

    @Test
    fun `the Artifacts page's upload is held to the upload limit, not to what an agent may save`() {
        val cookie = session()
        settings.setMaxFileSizeMb(7, "alice")

        // Past the 5 MB a save_artifact may be, under the limit.
        val kept = post("/api/workspaces/$workspaceId/artifacts", "file", "dataset.csv", ByteArray(6 * 1024 * 1024), cookie)
        assertThat(kept.statusCode()).withFailMessage { "${kept.statusCode()}: ${kept.body()}" }.isEqualTo(200)
        assertThat(saved.findAll().filter { it.workspaceId == workspaceId }.map { it.name }).containsExactly("dataset.csv")

        val refused = post("/api/workspaces/$workspaceId/artifacts", "file", "bigger.csv", ByteArray(8 * 1024 * 1024), cookie)
        assertThat(refused.statusCode()).withFailMessage { "${refused.statusCode()}: ${refused.body()}" }.isEqualTo(413)
        assertThat(mapper.readTree(refused.body()).path("error").asString()).isEqualTo(
            "bigger.csv is 8 MB; uploads are limited to 7 MB - an administrator can raise it in Admin Settings → Attachments",
        )
        assertThat(saved.findAll().filter { it.workspaceId == workspaceId }.map { it.name }).containsExactly("dataset.csv")
    }
}
