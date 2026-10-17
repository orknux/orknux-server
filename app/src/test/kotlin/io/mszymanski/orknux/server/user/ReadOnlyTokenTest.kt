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
 * A read-only access token reads and changes nothing, through every door a
 * token reaches the server by: GraphQL refuses its mutations, the MCP endpoint
 * offers it only the tools that read, and any other request that is not a read
 * is refused. A read and write token - every token made before this - does all
 * of it, as it did.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReadOnlyTokenTest(
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
    private var workspaceId: Long = 0

    @BeforeEach
    fun arrive() {
        val admins = roles.findByName(ADMINS)
            ?: roles.save(Role(name = ADMINS, scopes = mutableSetOf(RoleScope.ADMIN, RoleScope.USER)))
        owner = users.findByUsername(OWNER)
            ?: users.save(AppUser(username = OWNER, displayName = "Token Owner", type = UserType.INTERNAL, roles = mutableSetOf(admins)))
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "read-only-${System.nanoTime()}")).id)
    }

    private fun graphql(token: String, document: String): JsonNode = mapper.readTree(
        client.post().uri("/graphql")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .body(mapper.writeValueAsString(mapOf("query" to document)))
            .retrieve()
            .body(String::class.java),
    )

    private fun mcpTools(token: String): List<String> {
        val answer = mapper.readTree(
            client.post().uri("/mcp/$workspaceId")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .body("""{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}""")
                .retrieve()
                .body(String::class.java),
        )
        val tools = answer.path("result").path("tools")
        return (0 until tools.size()).map { tools.get(it).path("name").asText() }
    }

    @Test
    fun `a read-only token reads, and every mutation is refused`() {
        val reading = internal.mint(owner, "dashboard", readOnly = true).second

        val listed = graphql(reading, "{ myTokens { name readOnly } }")
        assertThat(listed.path("errors").isMissingNode).describedAs(listed.toString()).isTrue()
        val mine = listed.path("data").path("myTokens")
        assertThat((0 until mine.size()).map { mine.get(it).path("readOnly").asBoolean() }).contains(true)

        val refused = graphql(reading, """mutation { createUserToken(name: "sneaky") { secret } }""")
        assertThat(refused.path("errors").get(0).path("extensions").path("code").asText()).isEqualTo("TokenReadOnly")
        assertThat(refused.path("data").path("createUserToken").isMissingNode || refused.path("data").isNull).isTrue()
    }

    @Test
    fun `a read and write token still changes things`() {
        val writing = internal.mint(owner, "deploy").second

        val made = graphql(writing, """mutation { createUserToken(name: "made by a token", readOnly: true) { token { readOnly } } }""")
        assertThat(made.path("data").path("createUserToken").path("token").path("readOnly").asBoolean()).isTrue()
        assertThat(mcpTools(writing)).contains("orknux_workflows", "orknux_open_issue")
    }

    @Test
    fun `over MCP a read-only token is offered only the tools that read`() {
        val tools = mcpTools(internal.mint(owner, "assistant", readOnly = true).second)

        assertThat(tools).contains("orknux_workflows", "orknux_issues", "orknux_workflow")
        assertThat(tools).doesNotContain("orknux_open_issue", "orknux_run_workflow", "orknux_create_workflow")
    }

    @Test
    fun `any other request that is not a read is refused`() {
        val reading = internal.mint(owner, "reader", readOnly = true).second

        val posted = client.post().uri("/api/plugins")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $reading")
            .contentType(MediaType.APPLICATION_JSON)
            .body("{}")
            .retrieve()
            .toEntity(String::class.java)
        assertThat(posted.statusCode.value()).isEqualTo(403)
        assertThat(posted.body).contains("may only read")
    }

    private companion object {
        const val ADMINS = "Admins"
        const val OWNER = "token-owner"
    }
}
