package io.mszymanski.orknux.server.plugin

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.connection.SlackClients
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.script.PluginCapability
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `SLACK_UPLOAD_FILE`, end to end: the door a plugin or a function knocks on,
 * the connection's own token, and Slack's three-step upload as the SDK makes it.
 *
 * What it replaced was the Slack plugin holding a second copy of the bot token
 * and calling Slack over the request door - so the assertions that matter are
 * that the token Slack was handed is the connection's, that each step got what
 * the plugin used to send it, and that a connection belonging to another
 * workspace is not one this door can be pointed at.
 *
 * Slack is a stand-in on the loopback address, upload url included; nothing
 * reaches the network.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class SlackUploadDoorTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val slackClients: SlackClients,
    @Autowired val host: SlackPluginHost,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private lateinit var api: HttpServer
    private lateinit var realEndpoint: String

    /** Each Web API method called, with its form arguments and the token it carried. */
    private val called = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
    private val tokens = CopyOnWriteArrayList<String>()

    /** What arrived at the upload url. */
    private val uploaded = CopyOnWriteArrayList<ByteArray>()

    private val answers = ConcurrentHashMap<String, String>()

    private var workspaceId: Long = 0
    private var connectionId: Long = 0

    @BeforeEach
    fun start() {
        api = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val base = "http://${api.address.hostString}:${api.address.port}"
        answers["files.getUploadURLExternal"] = """{"ok":true,"upload_url":"$base/upload/F1","file_id":"F1"}"""
        answers["files.completeUploadExternal"] =
            """{"ok":true,"files":[{"id":"F1","title":"report.csv","permalink":"https://acme.slack.com/files/U1/F1/report.csv"}]}"""
        answers["files.remote.add"] =
            """{"ok":true,"file":{"id":"F2","title":"The report","permalink":"https://acme.slack.com/files/U1/F2/report"}}"""

        api.createContext("/upload/") { exchange ->
            uploaded += exchange.requestBody.readBytes()
            respond(exchange, "OK - ${uploaded.last().size}")
        }
        api.createContext("/api/") { exchange ->
            val method = exchange.requestURI.path.substringAfterLast('/')
            val form = formOf(exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8))
            called += method to form
            (exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ") ?: form["token"])
                ?.let { tokens += it }
            respond(exchange, answers[method] ?: """{"ok":true}""")
        }
        api.start()

        realEndpoint = slackClients.webApi.config.methodsEndpointUrlPrefix
        slackClients.webApi.config.methodsEndpointUrlPrefix = "$base/api/"

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "uploads-${System.nanoTime()}")).id)
        connectionId = graphQlTester.document(
            """
            mutation {
              createWorkspaceConnection(input: {
                workspaceId: $workspaceId, name: "Upload Slack", type: SLACK, secret: "xoxb-the-connections-own"
              }) { id }
            }
            """,
        ).execute().path("createWorkspaceConnection.id").entity(Long::class.java).get()
        called.clear()
        tokens.clear()
    }

    @AfterEach
    fun stop() {
        slackClients.webApi.config.methodsEndpointUrlPrefix = realEndpoint
        api.stop(0)
    }

    @Test
    fun `text is uploaded with the connection's token and shared where it was asked`() {
        val answer = host.ask(
            PluginCapability.SLACK_UPLOAD_FILE,
            """[$connectionId,{"op":"upload","filename":"report.csv","content":"a,b\nzażółć","channel":"C1","comment":"Here it is","threadTs":"1.0"}]""",
            workspaceId,
        )

        assertThat(answer).contains("\"id\":\"F1\"").contains("\"permalink\":\"https://acme.slack.com/files/U1/F1/report.csv\"")
        assertThat(called.map { it.first }).containsExactly("files.getUploadURLExternal", "files.completeUploadExternal")
        assertThat(tokens).describedAs("only the connection's token reached Slack").containsOnly("xoxb-the-connections-own")

        // The length Slack is told is the UTF-8 byte count, not the character count.
        val text = "a,b\nzażółć".toByteArray(Charsets.UTF_8)
        assertThat(called[0].second).containsEntry("filename", "report.csv").containsEntry("length", text.size.toString())
        assertThat(uploaded).singleElement().isEqualTo(text)

        val completed = called[1].second
        assertThat(completed).containsEntry("channel_id", "C1")
            .containsEntry("initial_comment", "Here it is")
            .containsEntry("thread_ts", "1.0")
        assertThat(completed["files"]).contains("\"id\":\"F1\"").contains("\"title\":\"report.csv\"")
    }

    @Test
    fun `bytes arrive as base64 and are uploaded as the bytes, and without a channel nothing is shared`() {
        val pdf = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x00, 0xFF.toByte())
        val answer = host.ask(
            PluginCapability.SLACK_UPLOAD_FILE,
            """[$connectionId,{"op":"upload","filename":"a.pdf","base64":"${Base64.getEncoder().encodeToString(pdf)}"}]""",
            workspaceId,
        )

        assertThat(answer).contains("\"id\":\"F1\"")
        assertThat(uploaded).singleElement().isEqualTo(pdf)
        assertThat(called[1].second).doesNotContainKey("channel_id").doesNotContainKey("initial_comment")
    }

    @Test
    fun `a url is registered as a remote file and shared`() {
        val answer = host.ask(
            PluginCapability.SLACK_UPLOAD_FILE,
            """[$connectionId,{"op":"remote","url":"https://example.invalid/r.pdf","title":"The report","filetype":"pdf","channel":"C1"}]""",
            workspaceId,
        )

        assertThat(answer).contains("\"id\":\"F2\"").contains("files/U1/F2/report")
        assertThat(called.map { it.first }).containsExactly("files.remote.add", "files.remote.share")
        assertThat(called[0].second).containsEntry("external_id", "https://example.invalid/r.pdf")
            .containsEntry("external_url", "https://example.invalid/r.pdf")
            .containsEntry("title", "The report")
            .containsEntry("filetype", "pdf")
        assertThat(called[1].second).containsEntry("external_id", "https://example.invalid/r.pdf").containsEntry("channels", "C1")
        assertThat(tokens).containsOnly("xoxb-the-connections-own")
    }

    @Test
    fun `Slack's own refusal comes back in its own words`() {
        answers["files.getUploadURLExternal"] = """{"ok":false,"error":"missing_scope"}"""

        val answer = host.ask(
            PluginCapability.SLACK_UPLOAD_FILE,
            """[$connectionId,{"op":"upload","filename":"a.txt","content":"hi"}]""",
            workspaceId,
        )

        assertThat(answer).isEqualTo("""{"error":"missing_scope"}""")
        assertThat(uploaded).isEmpty()
    }

    /** The boundary: another workspace's connection is, to this caller, not there. */
    @Test
    fun `a connection of another workspace is not one this door reaches`() {
        val other = requireNotNull(workspaces.save(Workspace(name = "elsewhere-${System.nanoTime()}")).id)

        val answer = host.ask(
            PluginCapability.SLACK_UPLOAD_FILE,
            """[$connectionId,{"op":"upload","filename":"a.txt","content":"hi"}]""",
            other,
        )

        assertThat(answer).contains("has been deleted")
        assertThat(called).describedAs("Slack was never asked").isEmpty()
    }

    @Test
    fun `what cannot be uploaded is refused before Slack is asked`() {
        fun ask(file: String) = host.ask(PluginCapability.SLACK_UPLOAD_FILE, "[$connectionId,$file]", workspaceId)

        assertThat(ask("""{"op":"upload","content":"hi"}""")).contains("needs a filename")
        assertThat(ask("""{"op":"upload","filename":"a.txt"}""")).contains("nothing to upload")
        assertThat(ask("""{"op":"upload","filename":"a.pdf","base64":"not base64!"}""")).contains("not valid base64")
        assertThat(ask("""{"op":"remote","url":"ftp://x"}""")).contains("http(s) url")
        assertThat(ask("""{"filename":"a.txt","content":"hi"}""")).contains("upload or a remote file")
        assertThat(called).isEmpty()
    }

    /**
     * Form arguments, from either encoding the SDK uses: `files.remote.add`
     * goes as multipart because it may carry a preview image, the rest as a
     * url-encoded form.
     */
    private fun formOf(body: String): Map<String, String> =
        if (body.startsWith("--")) {
            MULTIPART_FIELD.findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
        } else {
            urlEncoded(body)
        }

    private fun urlEncoded(body: String): Map<String, String> = body.split('&')
        .map { it.split('=', limit = 2) }
        .filter { it.size == 2 }
        .associate { (name, value) ->
            URLDecoder.decode(name, StandardCharsets.UTF_8) to URLDecoder.decode(value, StandardCharsets.UTF_8)
        }

    private fun respond(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private companion object {
        val MULTIPART_FIELD = Regex("""name="([^"]+)"\r?\n(?:[^\r\n]+\r?\n)*\r?\n(.*?)\r?\n--""", RegexOption.DOT_MATCHES_ALL)
    }
}
