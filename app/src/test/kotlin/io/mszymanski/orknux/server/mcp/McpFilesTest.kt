package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.server.agent.AgentTool
import io.mszymanski.orknux.server.agent.AgentToolRepository
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
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.util.Base64

/**
 * An MCP client hands a file to a tool by key: it uploads the bytes as they
 * are, is answered a contentKey, and a tool called over MCP reads the file
 * under that key as it would in a chat. A workspace tool stands in for the
 * plugins' - Jira's attach, Slack's uploads - which read the same store.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpFilesTest(
    @LocalServerPort val port: Int,
    @Autowired val users: AppUserRepository,
    @Autowired val roles: RoleRepository,
    @Autowired val internal: InternalAuthentication,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val tools: AgentToolRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private val client = RestClient.builder()
        .baseUrl("http://localhost:$port")
        .defaultStatusHandler({ true }, { _, _ -> })
        .build()

    private lateinit var owner: AppUser
    private var workspaceId: Long = 0

    /** Bytes no text encoding would leave alone. */
    private val bytes = ByteArray(300) { (it * 7 % 256).toByte() }

    @BeforeEach
    fun arrive() {
        val admins = roles.findByName(ADMINS)
            ?: roles.save(Role(name = ADMINS, scopes = mutableSetOf(RoleScope.ADMIN, RoleScope.USER)))
        owner = users.findByUsername(OWNER)
            ?: users.save(AppUser(username = OWNER, displayName = "Files Owner", type = UserType.INTERNAL, roles = mutableSetOf(admins)))
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "mcp-files-${System.nanoTime()}")).id)
        // What a plugin's attach does first: read the bytes held under the key it was given.
        val source = "export default async function heldUnder(key) { " +
            "const held = orknux.session.store.get(key); " +
            "const kind = orknux.session.store.kind(key); " +
            "return { held: held, contentType: kind ? kind.contentType : null, binary: kind ? kind.binary : null }; }"
        tools.save(
            AgentTool(
                workspaceId = workspaceId, name = "heldUnder", source = source, typescript = source,
                params = mutableListOf(io.mszymanski.orknux.server.agent.AgentToolParam(name = "key", type = io.mszymanski.orknux.server.action.ValueType.STRING)),
            ),
        )
    }

    private fun upload(
        token: String,
        workspace: Long = workspaceId,
        filename: String? = "flow.png",
        type: MediaType = MediaType.APPLICATION_OCTET_STREAM,
        body: ByteArray = bytes,
    ): Pair<Int, JsonNode> {
        val answer = client.post().uri("/mcp/$workspace/files" + (filename?.let { "?filename=$it" } ?: ""))
            .contentType(type)
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .body(body)
            .retrieve()
            .toEntity(String::class.java)
        return answer.statusCode.value() to mapper.readTree(answer.body ?: "{}")
    }

    private fun call(token: String, tool: String, arguments: Map<String, Any>): JsonNode = mapper.readTree(
        client.post().uri("/mcp/$workspaceId")
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .body(
                mapper.writeValueAsString(
                    mapOf("jsonrpc" to "2.0", "id" to 1, "method" to "tools/call", "params" to mapOf("name" to tool, "arguments" to arguments)),
                ),
            )
            .retrieve()
            .body(String::class.java),
    )

    @Test
    fun `a file uploaded as bytes is read by a tool under the key it was answered`() {
        val token = internal.mint(owner, "uploader").second
        val (status, answer) = upload(token)
        assertThat(status).describedAs(answer.toString()).isEqualTo(200)
        val key = answer.path("contentKey").asText()
        assertThat(key).startsWith(McpFiles.KEY_PREFIX)
        assertThat(answer.path("contentType").asText()).isEqualTo("image/png")
        assertThat(answer.path("size").asLong()).isEqualTo(bytes.size.toLong())

        val read = call(token, "heldUnder", mapOf("key" to key))
        val text = read.path("result").path("content").get(0).path("text").asText()
        val held = mapper.readTree(text)
        assertThat(Base64.getDecoder().decode(held.path("held").asText())).describedAs(text).isEqualTo(bytes)
        assertThat(held.path("contentType").asText()).isEqualTo("image/png")
        assertThat(held.path("binary").asBoolean()).isTrue()
    }

    /** What `curl --data-binary @file` sends: the bytes, labelled as a form they are not. */
    @Test
    fun `bytes labelled as a form, as curl sends them, are kept as the file they are`() {
        val token = internal.mint(owner, "curl").second
        // A percent sign followed by nothing hexadecimal: what a form parser chokes on.
        val awkward = "%zz&=%".toByteArray() + bytes
        val (status, answer) = upload(token, filename = "report one.pdf", type = MediaType.APPLICATION_FORM_URLENCODED, body = awkward)
        assertThat(status).describedAs(answer.toString()).isEqualTo(200)
        assertThat(answer.path("filename").asText()).isEqualTo("report one.pdf")
        assertThat(answer.path("contentType").asText()).isEqualTo("application/pdf")
        assertThat(answer.path("size").asLong()).isEqualTo(awkward.size.toLong())
    }

    @Test
    fun `the endpoint says where a file goes, as an address a client can use`() {
        val token = internal.mint(owner, "reader of instructions").second
        val said = mapper.readTree(
            client.post().uri("/mcp/$workspaceId")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .body("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""")
                .retrieve()
                .body(String::class.java),
        ).path("result").path("instructions").asText()
        assertThat(said).contains("contentKey").contains("--data-binary").contains("/mcp/$workspaceId/files?filename=")
    }

    @Test
    fun `one person's key is nothing to another`() {
        val (_, answer) = upload(internal.mint(owner, "first").second)
        val other = users.save(
            AppUser(username = "mcp-files-other-${System.nanoTime()}", displayName = "Other", type = UserType.INTERNAL, roles = owner.roles.toMutableSet()),
        )
        val read = call(internal.mint(other, "second").second, "heldUnder", mapOf("key" to answer.path("contentKey").asText()))
        val text = read.path("result").path("content").get(0).path("text").asText()
        assertThat(text).doesNotContain(Base64.getEncoder().encodeToString(bytes))
    }

    @Test
    fun `a read-only token sends no file, a Custom one may, and a file needs a name and a workspace`() {
        assertThat(upload(internal.mint(owner, "reader", readOnly = true).second).first).isEqualTo(403)
        assertThat(upload(internal.mint(owner, "custom", tools = """["heldUnder"]""").second).first).isEqualTo(200)

        val token = internal.mint(owner, "careless").second
        assertThat(upload(token, filename = null).first).isEqualTo(400)
        assertThat(upload(token, workspace = 987_654_321).first).isEqualTo(404)
    }

    private companion object {
        const val OWNER = "mcp-files-owner"
        const val ADMINS = "MCP Files Admins"
    }
}
