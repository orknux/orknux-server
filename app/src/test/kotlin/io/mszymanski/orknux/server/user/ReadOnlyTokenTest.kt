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
    fun `a read and write token still changes things, but makes no tokens`() {
        val writing = internal.mint(owner, "deploy").second

        assertThat(mcpTools(writing)).contains("orknux_workflows", "orknux_open_issue")
        // Making a token is a person's act in a session, whatever the token may otherwise do. #591.
        val made = graphql(writing, """mutation { createUserToken(name: "made by a token", readOnly: true) { token { readOnly } } }""")
        assertThat(made.path("errors").get(0).path("extensions").path("code").asText()).isEqualTo("TokenMintedByToken")
    }

    @Test
    fun `over MCP a read-only token is offered only the tools that read`() {
        val tools = mcpTools(internal.mint(owner, "assistant", readOnly = true).second)

        assertThat(tools).contains("orknux_workflows", "orknux_issues", "orknux_workflow")
        assertThat(tools).doesNotContain("orknux_open_issue", "orknux_run_workflow", "orknux_create_workflow")
    }

    private fun mcpCall(token: String, tool: String, arguments: String): JsonNode = mapper.readTree(
        client.post().uri("/mcp/$workspaceId")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .body("""{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"$tool","arguments":$arguments}}""")
            .retrieve()
            .body(String::class.java),
    ).path("result")

    /**
     * Custom: the tools named and no others, a writing one among them if it is
     * named, while outside MCP the token is held to reading. Read and write
     * were the only answers, so a client wanted for one job was kept from
     * writing at all or handed every tool that writes.
     */
    @Test
    fun `a Custom token is offered the tools it names and no others, and reads only outside MCP`() {
        val (stored, secret) = internal.mint(owner, "filing", tools = """["orknux_issues","orknux_open_issue"]""")
        assertThat(stored.readOnly).isFalse()

        assertThat(mcpTools(secret)).containsExactlyInAnyOrder("orknux_issues", "orknux_open_issue")
        val opened = mcpCall(secret, "orknux_open_issue", """{"title":"Filed by a Custom token"}""")
        assertThat(opened.path("isError").asBoolean()).describedAs(opened.toString()).isFalse()
        val refused = mcpCall(secret, "orknux_create_workflow", """{"name":"nope"}""")
        assertThat(refused.path("isError").asBoolean()).isTrue()
        assertThat(refused.toString()).contains("There is no tool called orknux_create_workflow")

        val mutation = graphql(secret, """mutation { createUserToken(name: "sneaky") { secret } }""")
        assertThat(mutation.path("errors").get(0).path("extensions").path("code").asText()).isEqualTo("TokenReadOnly")
        val listed = graphql(secret, "{ myTokens { name tools } }").path("data").path("myTokens")
        val mine = (0 until listed.size()).map { listed.get(it) }.first { it.path("name").asText() == "filing" }
        // The form's list says which tools read and which change something, as the endpoint decides it.
        val catalog = graphql(secret, "{ mcpTools { name readOnly } }").path("data").path("mcpTools")
        val reads = (0 until catalog.size()).associate { catalog.get(it).path("name").asText() to catalog.get(it).path("readOnly").asBoolean() }
        assertThat(reads["orknux_issues"]).isTrue()
        assertThat(reads["orknux_open_issue"]).isFalse()
        assertThat(reads.keys).doesNotContain("orknux_suggest_function_code")
        val named = mine.path("tools")
        assertThat((0 until named.size()).map { named.get(it).asText() }).containsExactly("orknux_issues", "orknux_open_issue")
    }

    /**
     * The agents' tools a workspace alone can run are offered over MCP too:
     * reckoning dates, drawing a chart, checking a format, building a link.
     * They change nothing, so a read-only token is offered them as well.
     */
    @Test
    fun `MCP offers the agents' tools that need only a workspace, and runs them`() {
        val reading = internal.mint(owner, "lent", readOnly = true).second
        assertThat(mcpTools(reading)).contains("date_today", "markdown_toText", "charts_render", "diagram_render", "validate_format", "app_link")
            .doesNotContain("scratchpad_write", "finish_answer", "http_get", "web_search")

        val today = mcpCall(reading, "date_today", "{}")
        assertThat(today.path("isError").asBoolean()).describedAs(today.toString()).isFalse()
        val chart = mcpCall(reading, "charts_render", """{"kind":"pie","title":"Spend","values":{"Rent":45,"Food":30}}""")
        assertThat(chart.path("isError").asBoolean()).describedAs(chart.toString()).isFalse()
        // Drawn, it arrives as a picture a client can show.
        assertThat(chart.path("content").get(0).path("type").asText()).isEqualTo("image")
        assertThat(chart.path("content").get(0).path("mimeType").asText()).startsWith("image/")
        val checked = mcpCall(reading, "validate_format", """{"format":"json","text":"{\"a\":1}"}""")
        assertThat(checked.path("isError").asBoolean()).describedAs(checked.toString()).isFalse()
    }

    /**
     * The workspace's own reach over MCP: its memories, its connections and its
     * own tools, which an agent holds by grant and a client signed in to the
     * workspace holds by being there. Only the reading half to a read-only token.
     */
    @Test
    fun `MCP offers the workspace's memories, connections and own tools, writing ones only to a writer`() {
        val writing = internal.mint(owner, "reach").second
        val made = graphql(writing, """mutation { createMemoryCatalog(workspaceId: $workspaceId, name: "Team notes") { id } }""")
        assertThat(made.path("errors").isMissingNode).describedAs(made.toString()).isTrue()
        val tool = graphql(writing, """mutation { createTool(input: { workspaceId: $workspaceId, name: "shout", description: "Says it louder." }) { id } }""")
        assertThat(tool.path("errors").isMissingNode).describedAs(tool.toString()).isTrue()

        assertThat(mcpTools(writing)).contains("memory_search", "memory_save", "memory_update", "memory_delete", "find_connections", "shout")
        val saved = mcpCall(writing, "memory_save", """{"catalog":"Team notes","title":"Deploys","content":"Deploys go out on Thursdays."}""")
        assertThat(saved.path("isError").asBoolean()).describedAs(saved.toString()).isFalse()
        val found = mcpCall(writing, "memory_search", """{"query":"Thursdays"}""")
        assertThat(found.toString()).contains("Deploys go out on Thursdays")

        val reading = internal.mint(owner, "reach-reader", readOnly = true).second
        assertThat(mcpTools(reading)).contains("memory_search", "find_connections").doesNotContain("memory_save", "memory_delete")
        assertThat(mcpCall(reading, "memory_save", """{"catalog":"Team notes","title":"x","content":"y"}""").path("isError").asBoolean()).isTrue()
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
