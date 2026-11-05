package io.mszymanski.orknux.server.user

import io.mszymanski.orknux.server.security.Role
import io.mszymanski.orknux.server.security.RoleRepository
import io.mszymanski.orknux.server.security.RoleScope
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
 * A token held to some workspaces reaches those and no others, through every
 * door: a workspace it does not name answers as not there over GraphQL and over
 * MCP, and it administers no installation even when its owner does, since an
 * installation administrator reaches every workspace. A token held to none
 * reaches what its owner reaches, as every token made before this did.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TokenWorkspacesTest(
    @LocalServerPort val port: Int,
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
    private var inside: Long = 0
    private var outside: Long = 0

    @BeforeEach
    fun arrive() {
        val admins = roles.findByName(ADMINS)
            ?: roles.save(Role(name = ADMINS, scopes = mutableSetOf(RoleScope.ADMIN, RoleScope.USER)))
        owner = users.findByUsername(OWNER)
            ?: users.save(AppUser(username = OWNER, displayName = "Held Owner", type = UserType.INTERNAL, roles = mutableSetOf(admins)))
        inside = requireNotNull(workspaces.save(Workspace(name = "held-in-${System.nanoTime()}")).id)
        outside = requireNotNull(workspaces.save(Workspace(name = "held-out-${System.nanoTime()}")).id)
    }

    private fun graphql(token: String, document: String): JsonNode = mapper.readTree(
        client.post().uri("/graphql")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .body(mapper.writeValueAsString(mapOf("query" to document)))
            .retrieve()
            .body(String::class.java),
    )

    private fun mcpTools(token: String, workspace: Long): List<String> {
        val answer = mapper.readTree(
            client.post().uri("/mcp/$workspace")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .body("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
                .retrieve()
                .body(String::class.java)
                .orEmpty()
                .ifBlank { "{}" },
        )
        val tools = answer.path("result").path("tools")
        return (0 until tools.size()).map { tools.get(it).path("name").asText() }
    }

    private fun held(vararg ids: Long) = mapper.writeValueAsString(ids.toList())

    @Test
    fun `a token held to one workspace sees that one and not the other`() {
        val token = internal.mint(owner, "one place", workspaces = held(inside)).second

        val here = graphql(token, "{ workspace(id: $inside) { name } }")
        assertThat(here.path("data").path("workspace").path("name").asText()).describedAs(here.toString()).startsWith("held-in-")

        val there = graphql(token, "{ workspace(id: $outside) { name } }")
        assertThat(there.path("data").path("workspace").isNull || there.path("errors").size() > 0)
            .describedAs(there.toString()).isTrue()

        val listed = graphql(token, "{ workspaces(size: 500) { content { id } } }").path("data").path("workspaces").path("content")
        val ids = (0 until listed.size()).map { listed.get(it).path("id").asLong() }
        assertThat(ids).contains(inside).doesNotContain(outside)
    }

    @Test
    fun `over MCP it is offered tools in its workspace and nothing in another`() {
        val token = internal.mint(owner, "mcp in one", workspaces = held(inside)).second
        assertThat(mcpTools(token, inside)).isNotEmpty()
        assertThat(mcpTools(token, outside)).isEmpty()
    }

    @Test
    fun `held to workspaces it administers no installation, though its owner does`() {
        val token = internal.mint(owner, "no admin", workspaces = held(inside)).second
        val refused = graphql(token, "{ users { username } }")
        assertThat(refused.path("errors").size()).describedAs(refused.toString()).isGreaterThan(0)
    }

    @Test
    fun `a token held to no workspaces reaches what its owner does`() {
        val token = internal.mint(owner, "everywhere").second
        val there = graphql(token, "{ workspace(id: $outside) { name } }")
        assertThat(there.path("data").path("workspace").path("name").asText()).describedAs(there.toString()).startsWith("held-out-")
        val people = graphql(token, "{ users { username } }")
        assertThat(people.path("errors").isMissingNode).describedAs(people.toString()).isTrue()
    }

    private companion object {
        const val OWNER = "held-token-owner"
        const val ADMINS = "Held Token Admins"
    }
}
