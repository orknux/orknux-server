package io.mszymanski.orknux.server.agent

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.chat.AgentTools
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An agent using a tool an MCP server offers.
 *
 * The stub speaks the protocol rather than pretending to: it answers
 * `initialize`, then `tools/list`, then `tools/call`, which is the sequence a
 * real server sees. Testing against a mocked client would only prove the code
 * agrees with itself, and the parts worth checking here — the handshake, the
 * namespacing, and what a granted name resolves to — all live in that exchange.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class McpToolCallTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val tools: AgentTools,
    @Autowired val agents: AgentRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer
    private val methods = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun reset() {
        agents.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        methods.clear()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `a granted server's tools are offered under its name, and calling one reaches it`() {
        val address = serve()
        mcpServer("Brave Search", address)
        val agent = agent("Researcher", granted = "Brave Search")

        // Namespaced, because two servers offering `search` is the ordinary case.
        val offered = tools.specsFor(agent).single { it.name == "brave_search__web_search" }
        assertThat(offered.description).isEqualTo("Search the web")
        assertThat(offered.parameters.map { it.name }).containsExactly("query")

        val answer = tools.run(
            agent,
            ToolCall(id = "call_1", name = "brave_search__web_search", arguments = """{"query":"ordilumen"}"""),
        )

        assertThat(answer).contains("Nothing found, which is the point")
        // The handshake happened before anything was asked of it.
        assertThat(methods).startsWith("initialize")
        assertThat(methods).contains("tools/list", "tools/call")
    }

    /**
     * What this agent was offered that came from a server.
     *
     * Not the whole list any more. Every agent is offered the built-ins without
     * a grant - saving a file, drawing, the scratchpads - so "granted nothing"
     * stopped meaning "offered nothing", and a test asserting the list is empty
     * was asserting a fact about a default rather than about servers. A
     * server's tool is the one named under its server, `server__tool`.
     */
    private fun fromServers(agent: io.mszymanski.orknux.server.agent.Agent) =
        tools.specsFor(agent).filter { "__" in it.name }

    /** A server it was not granted is not listed and cannot be reached. */
    @Test
    fun `an ungranted server contributes nothing`() {
        val address = serve()
        mcpServer("Brave Search", address)
        val agent = agent("Researcher", granted = null)

        assertThat(fromServers(agent)).isEmpty()

        val answer = tools.run(agent, ToolCall(id = "call_1", name = "brave_search__web_search", arguments = "{}"))
        assertThat(answer).contains("There is no tool called")
        // Nothing was even asked of the server.
        assertThat(methods).isEmpty()
    }

    /**
     * A server that cannot be reached contributes nothing rather than failing
     * the conversation: an agent whose search server is down should still be
     * able to answer from what it knows.
     */
    @Test
    fun `a server that is down leaves the agent with its other tools`() {
        serve()
        // Nothing listens on this port; .invalid never resolves.
        mcpServer("Broken", "https://mcp.example.invalid/rpc")
        val agent = agent("Researcher", granted = "Broken")

        assertThat(fromServers(agent)).isEmpty()
    }

    /** Answers initialize, tools/list and tools/call, as the protocol describes. */
    private fun serve(): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/rpc") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val method = Regex("\"method\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1).orEmpty()
            methods += method

            val answer = when (method) {
                "initialize" ->
                    """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{},
                       "serverInfo":{"name":"stub","version":"1"}}}"""

                "tools/list" ->
                    """{"jsonrpc":"2.0","id":2,"result":{"tools":[
                       {"name":"web_search","description":"Search the web",
                        "inputSchema":{"type":"object","properties":{
                          "query":{"type":"string","description":"What to search for"}},"required":["query"]}}]}}"""

                "tools/call" ->
                    """{"jsonrpc":"2.0","id":3,"result":{"content":[
                       {"type":"text","text":"Nothing found, which is the point"}]}}"""

                // The initialized notification expects no reply.
                else -> ""
            }

            val bytes = answer.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.responseHeaders.add("Mcp-Session-Id", "stub-session")
            if (bytes.isEmpty()) {
                exchange.sendResponseHeaders(202, -1)
            } else {
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}/rpc"
    }

    private fun mcpServer(name: String, address: String) {
        graphQlTester.document(
            """mutation { createMcpServer(input: { workspaceId: $workspaceId, name: "$name", address: "$address" })
               { id } }""",
        ).execute()
    }

    private fun agent(name: String, granted: String?): Agent {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "$name", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()

        val grant = if (granted == null) "" else """, mcpServers: ["$granted"]"""
        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "$name"$grant }) { mcpServers } }""",
        ).execute()
        return requireNotNull(agents.findByIdOrNull(id))
    }
}
