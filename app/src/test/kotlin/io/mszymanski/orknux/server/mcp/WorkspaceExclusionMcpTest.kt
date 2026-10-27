package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.server.security.Role
import io.mszymanski.orknux.server.security.RoleRepository
import io.mszymanski.orknux.server.user.AppUser
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.user.InternalAuthentication
import io.mszymanski.orknux.server.user.UserType
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceExcludedUser
import io.mszymanski.orknux.server.workspace.WorkspaceExcludedUserId
import io.mszymanski.orknux.server.workspace.WorkspaceExcludedUserRepository
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
 * A token is no way back into a workspace its holder was removed from.
 *
 * Over HTTP with a real token, because the endpoint is one of the doors the
 * removal has to close: the workspace answers as not there, and the other
 * workspace the same role opens still offers its tools.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkspaceExclusionMcpTest(
    @LocalServerPort val port: Int,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val users: AppUserRepository,
    @Autowired val roles: RoleRepository,
    @Autowired val exclusions: WorkspaceExcludedUserRepository,
    @Autowired val internal: InternalAuthentication,
    @Autowired val mapper: ObjectMapper,
) {

    private val client = RestClient.builder()
        .baseUrl("http://localhost:$port")
        .defaultStatusHandler({ true }, { _, _ -> })
        .build()

    private var removedFrom = 0L
    private var keeps = 0L
    private lateinit var username: String
    private lateinit var token: String

    @BeforeEach
    fun arrive() {
        val n = System.nanoTime()
        val crew = roles.save(Role(name = "mcp-excluded-$n"))
        removedFrom = requireNotNull(workspaces.save(Workspace(name = "mcp-removed-$n", roles = mutableSetOf(crew))).id)
        keeps = requireNotNull(workspaces.save(Workspace(name = "mcp-kept-$n", roles = mutableSetOf(crew))).id)
        val person = users.save(AppUser(username = "mcp-dana-$n", displayName = "Dana", type = UserType.INTERNAL, roles = mutableSetOf(crew)))
        username = person.username
        token = internal.mint(person, "dana-$n").second
    }

    @Test
    fun `a removed member's token cannot reach the workspace, and still reaches the other`() {
        assertThat(tools(removedFrom).path("result").path("tools").size()).isPositive()

        exclusions.save(WorkspaceExcludedUser(id = WorkspaceExcludedUserId(removedFrom, username), excludedBy = "test"))

        val refused = tools(removedFrom)
        assertThat(refused.has("result")).isFalse()
        assertThat(refused.path("error").path("message").stringValue()).isEqualTo("There is no workspace $removedFrom that you can see")
        val called = rpc(removedFrom, "tools/call", """{"name":"orknux_workflows","arguments":{}}""")
        assertThat(called.has("result")).isFalse()

        assertThat(tools(keeps).path("result").path("tools").size()).isPositive()
    }

    private fun tools(workspaceId: Long) = rpc(workspaceId, "tools/list", "{}")

    private fun rpc(workspaceId: Long, method: String, params: String): JsonNode {
        val body = client.post().uri("/mcp/$workspaceId")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .body("""{"jsonrpc":"2.0","id":7,"method":"$method","params":$params}""")
            .retrieve()
            .body(String::class.java)
        return mapper.readTree(requireNotNull(body))
    }
}
