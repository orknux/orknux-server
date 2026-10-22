package io.mszymanski.orknux.server.integration

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.connection.McpServerMonitor
import io.mszymanski.orknux.connector.connection.McpServerRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
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
import java.nio.charset.StandardCharsets

/**
 * What pressing Check on an MCP server says.
 *
 * The point of the button is the sentence, not the colour. Every way a
 * handshake can fail used to arrive as "the server did not complete the MCP
 * handshake" — a wrong address, an expired token and a server refusing this
 * client were one message — and the only way to find out which had happened
 * was to grant the server to an agent and watch a conversation quietly lose a
 * capability.
 *
 * The stub speaks the protocol rather than pretending to, as in
 * `McpToolCallTest`: the handshake is the thing being tested, so mocking it
 * away would leave nothing worth asserting.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class McpServerCheckTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val servers: McpServerRepository,
    @Autowired val monitor: McpServerMonitor,
) {

    private var workspaceId: Long = 0
    private var server: HttpServer? = null

    @BeforeEach
    fun reset() {
        servers.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    @AfterEach
    fun stop() {
        server?.stop(0)
        server = null
    }

    @Test
    fun `a server that answers says so, and how much it offers`() {
        val id = mcpServer("Brave Search", serve { _, _ -> null })

        val check = check(id)

        assertThat(check.path("checkMcpServer.reachable").entity(Boolean::class.java).get()).isTrue()
        assertThat(check.path("checkMcpServer.tools").entity(Int::class.javaObjectType).get()).isEqualTo(1)
        assertThat(detailOf(check)).contains("one tool")
    }

    @Test
    fun `a server not yet saved can be checked from the add form, and nothing is saved`() {
        val address = serve { _, _ -> null }
        val draft = graphQlTester.document(
            """mutation { checkMcpServerDraft(input: { workspaceId: $workspaceId, name: "Brave Search", address: "$address" })
               { reachable detail tools } }""",
        ).execute()

        assertThat(draft.path("checkMcpServerDraft.reachable").entity(Boolean::class.java).get()).isTrue()
        assertThat(draft.path("checkMcpServerDraft.tools").entity(Int::class.javaObjectType).get()).isEqualTo(1)
        assertThat(servers.findAll()).isEmpty()

        val nowhere = graphQlTester.document(
            """mutation { checkMcpServerDraft(input: { workspaceId: $workspaceId, name: "x", address: "http://nowhere.invalid/rpc" })
               { reachable } }""",
        ).execute()
        assertThat(nowhere.path("checkMcpServerDraft.reachable").entity(Boolean::class.java).get()).isFalse()
    }

    /**
     * The commonest failure worth telling apart, and the one that used to be
     * indistinguishable from a wrong address: the address is right, and the
     * credential is not.
     */
    @Test
    fun `a refused handshake carries the status and what the server said`() {
        val id = mcpServer("Brave Search", serve { method, _ ->
            if (method == "initialize") 401 to """{"error":"token expired"}""" else null
        })

        val check = check(id)

        assertThat(check.path("checkMcpServer.reachable").entity(Boolean::class.java).get()).isFalse()
        check.path("checkMcpServer.tools").valueIsNull()
        assertThat(detailOf(check)).contains("401")
        assertThat(detailOf(check)).contains("token expired")
    }

    /**
     * A refusal can arrive with a 200 on it. JSON-RPC carries the error in the
     * body, so a server that will not talk to this client answers successfully
     * and says no inside — which read here as a handshake that had worked,
     * followed by a tools/list that mysteriously had not.
     */
    @Test
    fun `a handshake refused inside a 200 is a refused handshake`() {
        val id = mcpServer("Brave Search", serve { method, _ ->
            if (method == "initialize") {
                200 to """{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"Unsupported protocol version"}}"""
            } else {
                null
            }
        })

        val check = check(id)

        assertThat(check.path("checkMcpServer.reachable").entity(Boolean::class.java).get()).isFalse()
        assertThat(detailOf(check)).contains("Unsupported protocol version")
    }

    /**
     * An address that cannot be called is refused before the handshake, by the
     * same guard a connection check uses — so the sentence is about the address
     * rather than about a server that failed to answer, which is the truth and
     * is also the thing to go and fix.
     */
    @Test
    fun `an address that cannot be resolved is refused before anything is asked`() {
        // .invalid never resolves, by RFC.
        val id = mcpServer("Broken", "https://mcp.example.invalid/rpc")

        val check = check(id)

        assertThat(check.path("checkMcpServer.reachable").entity(Boolean::class.java).get()).isFalse()
        assertThat(detailOf(check)).contains("could not be resolved")
    }

    /** Resolves, and nothing is listening: the handshake is the thing that fails. */
    @Test
    fun `a port nothing listens on says the address could not be reached`() {
        val id = mcpServer("Broken", "http://${InetAddress.getLoopbackAddress().hostAddress}:$closedPort/rpc")

        val check = check(id)

        assertThat(check.path("checkMcpServer.reachable").entity(Boolean::class.java).get()).isFalse()
        assertThat(detailOf(check)).contains("could not be reached")
    }

    /**
     * A port that was free a moment ago, which is the closest a test can get to
     * one nothing is listening on without picking a number and hoping.
     */
    private val closedPort: Int
        get() {
            val held = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            val port = held.address.port
            held.stop(0)
            return port
        }

    /**
     * The check is written onto the row, so the list shows reachability without
     * anybody pressing anything. Issue #329.
     */
    @Test
    fun `a check is written onto the server`() {
        val id = mcpServer("Brave Search", serve { _, _ -> null })

        check(id)

        val stored = servers.findById(id).orElseThrow()
        assertThat(stored.reachable).isTrue()
        assertThat(stored.toolCount).isEqualTo(1)
        assertThat(stored.lastCheckedAt).isNotNull()
        assertThat(stored.checkDetail).contains("one tool")
    }

    /** And the monitor's sweep records every server, with nobody pressing Check. */
    @Test
    fun `the monitor sweep records a server on its own`() {
        val id = mcpServer("Brave Search", serve { _, _ -> null })

        monitor.sweep()

        val stored = servers.findById(id).orElseThrow()
        assertThat(stored.reachable).isTrue()
        assertThat(stored.lastCheckedAt).isNotNull()
    }

    private fun detailOf(answer: org.springframework.graphql.test.tester.GraphQlTester.Response): String =
        answer.path("checkMcpServer.detail").entity(String::class.java).get()

    private fun check(id: Long) = graphQlTester
        .document("mutation { checkMcpServer(id: $id) { reachable detail tools } }")
        .execute()

    /**
     * A stub speaking the protocol, with one hole in it.
     *
     * [refuse] is asked about every method first, and a status and body from it
     * replace the ordinary answer — which is how each of these describes the one
     * server it is about without a second stub per test.
     */
    private fun serve(refuse: (String, String) -> Pair<Int, String>?): String {
        val started = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        started.createContext("/rpc") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val method = Regex("\"method\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1).orEmpty()

            val refusal = refuse(method, body)
            val status = refusal?.first ?: 200
            val answer = refusal?.second ?: when (method) {
                "initialize" ->
                    """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{},
                       "serverInfo":{"name":"stub","version":"1"}}}"""

                "tools/list" ->
                    """{"jsonrpc":"2.0","id":2,"result":{"tools":[
                       {"name":"web_search","description":"Search the web",
                        "inputSchema":{"type":"object","properties":{
                          "query":{"type":"string","description":"What to search for"}},"required":["query"]}}]}}"""

                // The initialized notification expects no reply.
                else -> ""
            }

            val bytes = answer.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.responseHeaders.add("Mcp-Session-Id", "stub-session")
            if (bytes.isEmpty()) {
                exchange.sendResponseHeaders(202, -1)
            } else {
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        started.start()
        server = started
        return "http://${started.address.hostString}:${started.address.port}/rpc"
    }

    private fun mcpServer(name: String, address: String): Long = graphQlTester
        .document(
            """mutation { createMcpServer(input: { workspaceId: $workspaceId, name: "$name", address: "$address" })
               { id } }""",
        )
        .execute()
        .path("createMcpServer.id")
        .entity(Long::class.java)
        .get()
}
