package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.security.Role
import io.mszymanski.orknux.server.security.RoleRepository
import io.mszymanski.orknux.server.user.AppUser
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.InternalAuthentication
import io.mszymanski.orknux.server.user.UserType
import io.mszymanski.orknux.server.workspace.AdminWorkspace
import io.mszymanski.orknux.server.workspace.AdminWorkspaceRepository
import io.mszymanski.orknux.server.workspace.AdminWorkspaceTools
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * The MCP endpoint is not a way into an admin workspace, nor a way out of an
 * ordinary one into others.
 *
 * Driven over HTTP with real tokens, because what is tested is what an outside
 * client holding one sees: somebody who is not an administrator is told an
 * admin workspace is not there whatever they ask of it; their own workspace's
 * endpoint offers none of the admin workspace tools and refuses them by name,
 * and will not read a conversation of the admin workspace by its id; and even
 * an administrator's token is offered the orknux_* surface alone, since the
 * cross-workspace readers belong to an admin workspace's agents and nothing
 * else.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminWorkspaceMcpTest(
    @LocalServerPort val port: Int,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val settings: AdminWorkspaceRepository,
    @Autowired val users: AppUserRepository,
    @Autowired val roles: RoleRepository,
    @Autowired val internal: InternalAuthentication,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val mapper: ObjectMapper,
) {

    private val client = RestClient.builder()
        .baseUrl("http://localhost:$port")
        .defaultStatusHandler({ true }, { _, _ -> })
        .build()

    private var opsId = 0L
    private var teamId = 0L
    private var opsSession = 0L
    private lateinit var memberToken: String
    private lateinit var adminToken: String

    /** Workspaces of its own, and nothing deleted: this class shares the database. */
    @BeforeEach
    fun arrive() {
        val n = System.nanoTime()
        opsId = requireNotNull(workspaces.save(Workspace(name = "mcp-ops-$n", adminWorkspace = true)).id)
        settings.save(AdminWorkspace(workspaceId = opsId))
        val member = roles.save(Role(name = "mcp-member-$n"))
        teamId = requireNotNull(workspaces.save(Workspace(name = "mcp-team-$n", roles = mutableSetOf(member))).id)
        opsSession = recorder.open(opsId, "slack_", "ops-$n").also { recorder.userSaid(it, "alice", "Only administrators read this") }

        val person = users.save(AppUser(username = "mcp-member-$n", displayName = "Member", type = UserType.INTERNAL, roles = mutableSetOf(member)))
        memberToken = internal.mint(person, "member-$n").second
        val administrators = roles.findByBuiltinTrue().first()
        val admin = users.save(AppUser(username = "mcp-admin-$n", displayName = "Admin", type = UserType.INTERNAL, roles = mutableSetOf(administrators)))
        adminToken = internal.mint(admin, "admin-$n").second
    }

    @Test
    fun `somebody who is not an administrator is told an admin workspace is not there, whatever they ask`() {
        val listed = rpc(memberToken, opsId, "tools/list", "{}")
        assertThat(listed.has("result")).isFalse()
        assertThat(listed.path("error").path("message").stringValue()).isEqualTo("There is no workspace $opsId that you can see")

        val called = rpc(memberToken, opsId, "tools/call", """{"name":"orknux_sessions","arguments":{}}""")
        assertThat(called.has("result")).isFalse()
        assertThat(called.toString()).doesNotContain("Only administrators read this")
    }

    @Test
    fun `their own workspace's endpoint offers none of the admin workspace tools and refuses them by name`() {
        val names = rpc(memberToken, teamId, "tools/list", "{}").path("result").path("tools").toList().map { it.path("name").stringValue() }
        assertThat(names).isNotEmpty().noneMatch { it in AdminWorkspaceTools.NAMES }

        AdminWorkspaceTools.NAMES.forEach { name ->
            val answer = rpc(memberToken, teamId, "tools/call", """{"name":"$name","arguments":{"workspace":"$opsId","kind":"sessions"}}""")
            assertThat(answer.toString()).contains("There is no tool called $name")
        }
    }

    @Test
    fun `their own workspace's endpoint does not read an admin workspace's conversation by its id`() {
        val answer = rpc(memberToken, teamId, "tools/call", """{"name":"orknux_session","arguments":{"id":$opsSession}}""")
        assertThat(answer.toString()).contains("There is no conversation $opsSession here").doesNotContain("Only administrators read this")
    }

    @Test
    fun `even an administrator's token is offered the orknux surface alone`() {
        val names = rpc(adminToken, opsId, "tools/list", "{}").path("result").path("tools").toList().map { it.path("name").stringValue() }
        assertThat(names).isNotEmpty().noneMatch { it in AdminWorkspaceTools.NAMES }
    }

    private fun rpc(token: String, workspaceId: Long, method: String, params: String): JsonNode {
        val body = client.post().uri("/mcp/$workspaceId")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .body("""{"jsonrpc":"2.0","id":7,"method":"$method","params":$params}""")
            .retrieve()
            .body(String::class.java)
        return mapper.readTree(requireNotNull(body))
    }
}
