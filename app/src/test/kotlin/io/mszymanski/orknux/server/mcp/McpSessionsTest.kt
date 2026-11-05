package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.server.security.Role
import io.mszymanski.orknux.server.security.RoleRepository
import io.mszymanski.orknux.server.security.RoleScope
import io.mszymanski.orknux.server.user.AppUser
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.InternalAuthentication
import io.mszymanski.orknux.server.user.UserType
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper

/**
 * What Admin -> MCP and a workspace's MCP page read: each client's session,
 * from which address, as which client and token, and every call it made -
 * every workspace's for an administrator, one workspace's when asked about one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class McpSessionsTest(
    @LocalServerPort val port: Int,
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val users: AppUserRepository,
    @Autowired val roles: RoleRepository,
    @Autowired val internal: InternalAuthentication,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private val client = RestClient.builder()
        .baseUrl("http://localhost:$port")
        .defaultStatusHandler({ true }, { _, _ -> })
        .build()

    private lateinit var owner: AppUser
    private var first: Long = 0
    private var second: Long = 0

    @BeforeEach
    fun arrive() {
        val admins = roles.findByName(ADMINS)
            ?: roles.save(Role(name = ADMINS, scopes = mutableSetOf(RoleScope.ADMIN, RoleScope.USER)))
        owner = users.findByUsername(OWNER)
            ?: users.save(AppUser(username = OWNER, displayName = "Sessions Owner", type = UserType.INTERNAL, roles = mutableSetOf(admins)))
        first = requireNotNull(workspaces.save(Workspace(name = "mcp-sessions-a-${System.nanoTime()}")).id)
        second = requireNotNull(workspaces.save(Workspace(name = "mcp-sessions-b-${System.nanoTime()}")).id)
    }

    /** One JSON-RPC request; answers the session id header and the body. */
    private fun rpc(token: String, workspace: Long, body: String, session: String? = null, forwardedFor: String? = null): Pair<String?, String> {
        val answer = client.post().uri("/mcp/$workspace")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .header(HttpHeaders.USER_AGENT, "claude-code/9.9 (test)")
            .headers { headers ->
                session?.let { headers.set(McpSessions.HEADER, it) }
                forwardedFor?.let { headers.set("X-Forwarded-For", it) }
            }
            .body(body)
            .retrieve()
            .toEntity(String::class.java)
        return answer.headers.getFirst(McpSessions.HEADER) to answer.body.orEmpty()
    }

    private fun connect(token: String, workspace: Long, forwardedFor: String? = null): String {
        val (session, _) = rpc(
            token, workspace,
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"clientInfo":{"name":"claude-code","version":"9.9"}}}""",
            forwardedFor = forwardedFor,
        )
        return requireNotNull(session) { "initialize hands the client an Mcp-Session-Id" }
    }

    private fun callTool(token: String, workspace: Long, session: String, tool: String, arguments: String = "{}", forwardedFor: String? = null) =
        rpc(token, workspace, """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}""", session, forwardedFor)

    @Test
    fun `a client's session says who, as what, from where, and its calls are listed under it`() {
        val token = internal.mint(owner, "laptop").second
        val session = connect(token, first, forwardedFor = "203.0.113.7, 10.0.0.1")
        callTool(token, first, session, "orknux_workflows", forwardedFor = "203.0.113.7")
        callTool(token, first, session, "orknux_no_such_tool", """{"text":"${"x".repeat(500)}"}""", forwardedFor = "203.0.113.7")

        val sessions = graphQlTester.document(
            """{ mcpSessions(workspaceId: $first) { connectedCount content { id username tokenName clientName clientVersion userAgent ipAddress peerAddress connected calls endedAt } } }""",
        ).execute().path("mcpSessions").entity(Map::class.java).get()
        @Suppress("UNCHECKED_CAST")
        val rows = sessions["content"] as List<Map<String, Any?>>
        assertThat(rows).hasSize(1)
        val row = rows.single()
        assertThat(row["username"]).isEqualTo(OWNER)
        assertThat(row["tokenName"]).isEqualTo("laptop")
        assertThat(row["clientName"]).isEqualTo("claude-code")
        assertThat(row["clientVersion"]).isEqualTo("9.9")
        assertThat(row["userAgent"]).isEqualTo("claude-code/9.9 (test)")
        assertThat(row["ipAddress"]).isEqualTo("203.0.113.7")
        assertThat(row["peerAddress"]).isNotNull()
        assertThat(row["connected"]).isEqualTo(true)
        assertThat(row["calls"]).isEqualTo(2)
        assertThat(sessions["connectedCount"]).isEqualTo(1)

        val calls = graphQlTester.document(
            """{ mcpCalls(sessionId: ${row["id"]}) { totalElements content { tool failed error arguments username ipAddress clientName } } }""",
        ).execute().path("mcpCalls").entity(Map::class.java).get()
        @Suppress("UNCHECKED_CAST")
        val history = calls["content"] as List<Map<String, Any?>>
        assertThat(history.map { it["tool"] }).containsExactly("orknux_no_such_tool", "orknux_workflows")
        assertThat(history.first()["failed"]).isEqualTo(true)
        assertThat(history.first()["error"].toString()).contains("no tool called")
        // A long text is cut to a line, with its length said.
        assertThat(history.first()["arguments"].toString()).contains("(500 characters)").hasSizeLessThan(300)
        assertThat(history.last()["failed"]).isEqualTo(false)
        assertThat(history.last()["ipAddress"]).isEqualTo("203.0.113.7")
        assertThat(history.last()["clientName"]).isEqualTo("claude-code")

        assertThat(
            graphQlTester.document("""{ mcpCalls(workspaceId: $first, failedOnly: true) { totalElements } }""")
                .execute().path("mcpCalls.totalElements").entity(Int::class.java).get(),
        ).isEqualTo(1)
    }

    @Test
    fun `a workspace's page holds its own sessions, and the administrator's every workspace's`() {
        val token = internal.mint(owner, "both").second
        callTool(token, first, connect(token, first), "orknux_workflows")
        callTool(token, second, connect(token, second), "orknux_workflows")

        val mine = graphQlTester.document("""{ mcpSessions(workspaceId: $first) { content { workspaceId } } }""")
            .execute().path("mcpSessions.content[*].workspaceId").entityList(String::class.java).get()
        assertThat(mine).containsOnly(first.toString())

        val everywhere = graphQlTester.document("""{ mcpSessions(size: 200) { content { workspaceId workspaceName } } }""")
            .execute().path("mcpSessions.content[*].workspaceId").entityList(String::class.java).get()
        assertThat(everywhere).contains(first.toString(), second.toString())

        val history = graphQlTester.document("""{ mcpCalls(workspaceId: $second) { content { workspaceId } } }""")
            .execute().path("mcpCalls.content[*].workspaceId").entityList(String::class.java).get()
        assertThat(history).containsOnly(second.toString())
    }

    @Test
    fun `a client that never sends its session id is counted under the one it looks like, and a delete ends it`() {
        val token = internal.mint(owner, "forgetful").second
        val session = connect(token, first)
        rpc(token, first, """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"orknux_workflows","arguments":{}}}""")
        rpc(token, first, """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"orknux_workflows","arguments":{}}}""")

        val counted = graphQlTester.document("""{ mcpSessions(workspaceId: $first) { content { calls } } }""")
            .execute().path("mcpSessions.content[*].calls").entityList(Int::class.java).get()
        assertThat(counted).containsExactly(2)

        val closed = client.delete().uri("/mcp/$first")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .header(McpSessions.HEADER, session)
            .retrieve().toBodilessEntity()
        assertThat(closed.statusCode.value()).isEqualTo(204)
        val after = graphQlTester.document("""{ mcpSessions(workspaceId: $first) { connectedCount content { connected endedAt } } }""")
            .execute().path("mcpSessions").entity(Map::class.java).get()
        assertThat(after["connectedCount"]).isEqualTo(0)
    }

    @Test
    fun `a host is named by reverse lookup of a literal address, and a name is never looked up`() {
        assertThat(McpSessions.hostOf("127.0.0.1")).isNotNull()
        // Whatever a client writes in X-Forwarded-For: a name there is refused rather than resolved.
        assertThat(runCatching { McpSessions.hostOf("example.invalid") }.isFailure).isTrue()
        assertThat(McpSessions.hostOf("")).isNull()
    }

    @Test
    @WithMockUser(username = "bob", roles = ["USERS"])
    fun `somebody who is not an administrator reads no workspace's clients but their own`() {
        graphQlTester.document("""{ mcpSessions { totalElements } }""").execute().errors().satisfy { errors ->
            assertThat(errors).isNotEmpty()
        }
        graphQlTester.document("""{ mcpCalls(workspaceId: $first) { totalElements } }""").execute().errors().satisfy { errors ->
            assertThat(errors).isNotEmpty()
        }
    }

    private companion object {
        const val OWNER = "mcp-sessions-owner"
        const val ADMINS = "MCP Sessions Admins"
    }
}
