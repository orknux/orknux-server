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
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `SLACK_WEB_API_READ`, end to end: the door, the connection's own bot or user
 * token, and Slack's answer handed back as Slack wrote it. Issue #647.
 *
 * What it replaces is the Slack plugin holding copies of both tokens and
 * calling Slack over the request door - so what matters is which token Slack
 * was handed, that only reads are made, that another workspace's connection is
 * not reachable, and that a file comes back as text or bytes within the
 * request door's own ceiling.
 *
 * Slack is a stand-in on the loopback address, its file host included; nothing
 * reaches the network.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class SlackWebApiDoorTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val slackClients: SlackClients,
    @Autowired val host: SlackPluginHost,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private lateinit var api: HttpServer
    private lateinit var realEndpoint: String
    private lateinit var base: String

    /** Each Web API method called, with its form arguments. */
    private val called = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()

    /** Every bearer token that reached the stand-in, API and file host alike. */
    private val tokens = CopyOnWriteArrayList<String>()

    /** Which files were fetched. */
    private val fetched = CopyOnWriteArrayList<String>()

    private val answers = ConcurrentHashMap<String, String>()
    private val files = ConcurrentHashMap<String, Pair<String, ByteArray>>()

    private var workspaceId: Long = 0
    private var connectionId: Long = 0

    @BeforeEach
    fun start() {
        api = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        base = "http://${api.address.hostString}:${api.address.port}"

        api.createContext("/files/") { exchange ->
            exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.let { tokens += it }
            val name = exchange.requestURI.path.substringAfterLast('/')
            fetched += name
            val (type, bytes) = files[name] ?: ("text/plain" to ByteArray(0))
            exchange.responseHeaders.add("Content-Type", type)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        api.createContext("/api/") { exchange ->
            val method = exchange.requestURI.path.substringAfterLast('/')
            val form = urlEncoded(exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8))
            called += method to form
            exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.let { tokens += it }
            respond(exchange, answers[method] ?: """{"ok":true}""")
        }
        api.start()

        realEndpoint = slackClients.webApi.config.methodsEndpointUrlPrefix
        slackClients.webApi.config.methodsEndpointUrlPrefix = "$base/api/"

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "web-api-${System.nanoTime()}")).id)
        connectionId = graphQlTester.document(
            """
            mutation {
              createWorkspaceConnection(input: {
                workspaceId: $workspaceId, name: "Reading Slack", type: SLACK,
                secret: "xoxb-the-connections-own", userToken: "xoxp-the-connections-user"
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

    private fun ask(asked: String, on: Long = workspaceId): String =
        host.ask(PluginCapability.SLACK_WEB_API_READ, "[$connectionId,$asked]", on)

    @Test
    fun `an allowed method is answered with Slack's own JSON, made with the bot token`() {
        answers["conversations.history"] =
            """{"ok":true,"messages":[{"ts":"1.1","text":"hello"}],"has_more":false,"response_metadata":{"next_cursor":""}}"""

        val answer = mapper.readTree(
            ask("""{"op":"api","method":"conversations.history","args":{"channel":"C1","limit":"5","token":"xoxb-not-this"},"as":"bot"}"""),
        )

        assertThat(answer.path("error").isMissingNode).isTrue()
        assertThat(answer.path("json").path("messages").get(0).path("text").asString()).isEqualTo("hello")
        assertThat(answer.path("json").path("has_more").isBoolean).describedAs("Slack's own field names").isTrue()
        assertThat(called).singleElement().satisfies({ (method, form) ->
            assertThat(method).isEqualTo("conversations.history")
            assertThat(form).containsEntry("channel", "C1").containsEntry("limit", "5").doesNotContainKey("token")
        })
        assertThat(tokens).describedAs("only the connection's bot token reached Slack").containsOnly("xoxb-the-connections-own")
    }

    @Test
    fun `as user, the connection's user token is the one used`() {
        answers["auth.test"] = """{"ok":true,"user_id":"U1","team":"Acme"}"""

        val answer = ask("""{"op":"api","method":"auth.test","args":{},"as":"user"}""")

        assertThat(answer).contains("\"user_id\":\"U1\"")
        assertThat(tokens).containsOnly("xoxp-the-connections-user")
    }

    @Test
    fun `Slack's refusal comes back in its own words, with its whole answer`() {
        answers["users.list"] = """{"ok":false,"error":"missing_scope","needed":"users:read"}"""

        val answer = mapper.readTree(ask("""{"op":"api","method":"users.list"}"""))

        assertThat(answer.path("error").asString()).isEqualTo("missing_scope")
        assertThat(answer.path("json").path("needed").asString()).isEqualTo("users:read")
    }

    @Test
    fun `a method that is not a read is refused, naming the ones that are, and Slack is never asked`() {
        val answer = mapper.readTree(ask("""{"op":"api","method":"chat.delete","args":{"channel":"C1","ts":"1.1"}}"""))

        assertThat(answer.path("error").asString())
            .contains("chat.delete is not a Slack method this reads")
            .contains("auth.test, conversations.list, users.conversations, conversations.history, conversations.replies, users.list, users.lookupByEmail, files.info")
        assertThat(called).isEmpty()
    }

    /** The boundary: another workspace's connection is, to this caller, not there. */
    @Test
    fun `a connection of another workspace is not one this door reaches`() {
        val other = requireNotNull(workspaces.save(Workspace(name = "elsewhere-${System.nanoTime()}")).id)

        assertThat(ask("""{"op":"api","method":"auth.test"}""", on = other)).contains("has been deleted")
        assertThat(ask("""{"op":"download","file":"F1"}""", on = other)).contains("has been deleted")
        assertThat(called).describedAs("Slack was never asked").isEmpty()
        assertThat(fetched).isEmpty()
    }

    @Test
    fun `a text file comes back as its text, fetched with the bot token`() {
        val csv = "a,b\nzażółć".toByteArray(Charsets.UTF_8)
        files["report.csv"] = "text/csv" to csv
        answers["files.info"] = fileInfo("F1", "report.csv", "text/csv", csv.size)

        val answer = mapper.readTree(ask("""{"op":"download","file":"F1"}"""))

        assertThat(answer.path("text").asString()).isEqualTo("a,b\nzażółć")
        assertThat(answer.path("base64").isMissingNode).isTrue()
        assertThat(answer.path("size").asInt()).isEqualTo(csv.size)
        assertThat(answer.path("contentType").asString()).isEqualTo("text/csv")
        assertThat(answer.path("file").path("name").asString()).isEqualTo("report.csv")
        assertThat(called.map { it.first }).containsExactly("files.info")
        assertThat(called[0].second).containsEntry("file", "F1")
        assertThat(fetched).containsExactly("report.csv")
        assertThat(tokens).containsOnly("xoxb-the-connections-own")
    }

    @Test
    fun `a binary file comes back as base64 of exactly its bytes`() {
        val pdf = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D, 0x00, 0xFF.toByte())
        files["a.pdf"] = "application/pdf" to pdf
        answers["files.info"] = fileInfo("F2", "a.pdf", "application/pdf", pdf.size)

        val answer = mapper.readTree(ask("""{"op":"download","file":"F2"}"""))

        assertThat(Base64.getDecoder().decode(answer.path("base64").asString())).isEqualTo(pdf)
        assertThat(answer.path("text").isMissingNode).isTrue()
        assertThat(answer.path("size").asInt()).isEqualTo(pdf.size)
        assertThat(answer.path("contentType").asString()).isEqualTo("application/pdf")
    }

    @Test
    fun `a file over the download ceiling is refused - by its size before fetching, and by its bytes while reading`() {
        answers["files.info"] = fileInfo("F3", "big.bin", "application/octet-stream", MOST_DOWNLOADED_BYTES + 1)
        assertThat(ask("""{"op":"download","file":"F3"}""")).contains("at most 5 MB")
        assertThat(fetched).describedAs("refused on what Slack said, before the bytes").isEmpty()

        // Slack said less than there was.
        files["big.bin"] = "application/octet-stream" to ByteArray(MOST_DOWNLOADED_BYTES + 1)
        answers["files.info"] = fileInfo("F3", "big.bin", "application/octet-stream", 10)
        assertThat(ask("""{"op":"download","file":"F3"}""")).contains("larger than 5 MB")
        assertThat(fetched).containsExactly("big.bin")
    }

    /** The bot token goes to Slack's hosts and to where this server's Slack API is, nowhere else. */
    @Test
    fun `a file whose address is not Slack's is not fetched`() {
        answers["files.info"] =
            """{"ok":true,"file":{"id":"F4","name":"x","mimetype":"text/plain","size":1,"url_private_download":"https://elsewhere.invalid/x"}}"""

        assertThat(ask("""{"op":"download","file":"F4"}""")).contains("only fetched from Slack's own host")
        assertThat(fetched).isEmpty()
    }

    private fun fileInfo(id: String, name: String, mimetype: String, size: Int): String =
        """{"ok":true,"file":{"id":"$id","name":"$name","mimetype":"$mimetype","size":$size,""" +
            """"url_private":"$base/files/private/$name","url_private_download":"$base/files/download/$name"}}"""

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
}
