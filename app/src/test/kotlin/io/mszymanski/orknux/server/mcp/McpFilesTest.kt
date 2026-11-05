package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.server.agent.AgentTool
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.llm.TemporaryFiles
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tunables
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
import org.junit.jupiter.api.AfterEach
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
    @Autowired val tunables: Tunables,
) {

    private val client = RestClient.builder()
        .baseUrl("http://localhost:$port")
        .defaultStatusHandler({ true }, { _, _ -> })
        .build()

    private lateinit var owner: AppUser
    private var workspaceId: Long = 0

    /** Bytes no text encoding would leave alone. */
    private val bytes = ByteArray(300) { (it * 7 % 256).toByte() }

    /* Its workspace goes with it; see McpSessionsTest.leave for what one left behind did. */
    @AfterEach
    fun leave() {
        workspaces.deleteById(workspaceId)
    }

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
        seconds: Int? = null,
        type: MediaType = MediaType.APPLICATION_OCTET_STREAM,
        body: ByteArray = bytes,
    ): Pair<Int, JsonNode> {
        val answer = client.post().uri("/mcp/$workspace/files" + (filename?.let { "?filename=$it" } ?: "") + (seconds?.let { "&seconds=$it" } ?: ""))
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
        assertThat(key).startsWith(TemporaryFiles.KEY_PREFIX)
        assertThat(answer.path("secondsLeft").asLong()).isBetween(1L, 300L)
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

    /** What a tool called over MCP answered, read as the JSON it is. */
    private fun said(read: JsonNode): JsonNode = mapper.readTree(read.path("result").path("content").get(0).path("text").asText())

    private fun heldUnder(token: String, key: String): String? =
        said(call(token, "heldUnder", mapOf("key" to key))).path("held").stringValue(null)

    @Test
    fun `text kept by the tool is read by another tool under its key, for as long as was asked`() {
        val token = internal.mint(owner, "keeper").second
        val kept = said(call(token, FileTools.KEEP, mapOf("filename" to "notes.txt", "text" to "zażółć", "seconds" to 60)))
        val key = kept.path("contentKey").asText()
        assertThat(key).describedAs(kept.toString()).startsWith(TemporaryFiles.KEY_PREFIX)
        assertThat(kept.path("secondsLeft").asLong()).isBetween(55L, 60L)
        assertThat(heldUnder(token, key)).isEqualTo("zażółć")

        val bytesKept = said(call(token, FileTools.KEEP, mapOf("filename" to "flow.png", "base64" to Base64.getEncoder().encodeToString(bytes))))
        assertThat(Base64.getDecoder().decode(heldUnder(token, bytesKept.path("contentKey").asText()))).isEqualTo(bytes)
        // Asked for nothing, it is kept for the longest allowed.
        assertThat(bytesKept.path("secondsLeft").asLong()).isBetween(295L, 300L)

        val listed = said(call(token, FileTools.KEPT, emptyMap()))
        assertThat(listed.path("files").toString()).contains("\"$key\"", "\"${bytesKept.path("contentKey").asText()}\"")
        assertThat(listed.path("mostAtOnce").asInt()).isEqualTo(3)
    }

    @Test
    fun `a file is kept no longer than allowed, and is nothing once its time is up`() {
        val token = internal.mint(owner, "short").second
        val long = said(call(token, FileTools.KEEP, mapOf("filename" to "a.txt", "text" to "a", "seconds" to 100_000)))
        assertThat(long.path("secondsLeft").asLong()).describedAs(long.toString()).isLessThanOrEqualTo(300L)
        assertThat(long.path("note").asText()).contains("the longest allowed")

        val (_, brief) = upload(token, seconds = 1)
        val key = brief.path("contentKey").asText()
        assertThat(heldUnder(token, key)).isNotNull()
        Thread.sleep(1_200)
        assertThat(heldUnder(token, key)).isNull()
        assertThat(said(call(token, FileTools.KEPT, emptyMap())).path("files").toString()).doesNotContain("\"$key\"")
    }

    @Test
    fun `one session holds three at once, and forgetting one makes room`() {
        val token = internal.mint(owner, "many").second
        val keys = (1..3).map { said(call(token, FileTools.KEEP, mapOf("filename" to "$it.txt", "text" to "$it", "seconds" to 30))).path("contentKey").asText() }
        val fourth = said(call(token, FileTools.KEEP, mapOf("filename" to "4.txt", "text" to "4", "seconds" to 30)))
        assertThat(fourth.path("error").asText()).contains("already holds 3").contains(keys.first())
        assertThat(upload(token).first).isEqualTo(409)

        assertThat(said(call(token, FileTools.FORGET, mapOf("key" to keys.first()))).path("forgotten").asText()).isEqualTo(keys.first())
        assertThat(heldUnder(token, keys.first())).isNull()
        assertThat(said(call(token, FileTools.KEEP, mapOf("filename" to "4.txt", "text" to "4", "seconds" to 30))).has("contentKey")).isTrue()
    }

    @Test
    fun `the installation's bound and the largest file are an administrator's to set`() {
        val token = internal.mint(owner, "bounded").second
        try {
            tunables.set(Tunable.TEMPORARY_FILE_MB, 1, "test")
            val (status, answer) = upload(token, body = ByteArray(1024 * 1024 + 1))
            assertThat(status).describedAs(answer.toString()).isEqualTo(413)
            assertThat(answer.path("error").asText()).contains("at most 1 MB")

            tunables.set(Tunable.TEMPORARY_FILES_MOST, 1, "test")
            val first = upload(token, seconds = 5)
            val second = upload(internal.mint(owner, "another").second, workspace = workspaceId, seconds = 5)
            // Whatever else the suite keeps, two at once is past one.
            assertThat(listOf(first.first, second.first)).contains(409)
        } finally {
            tunables.set(Tunable.TEMPORARY_FILE_MB, null, "test")
            tunables.set(Tunable.TEMPORARY_FILES_MOST, null, "test")
        }
    }

    @Test
    fun `a read-only token is not offered keeping a file`() {
        val token = internal.mint(owner, "looker", readOnly = true).second
        val answer = said(call(token, FileTools.KEEP, mapOf("filename" to "a.txt", "text" to "a")))
        assertThat(answer.path("error").asText()).isNotEmpty()
        assertThat(said(call(token, FileTools.KEPT, emptyMap())).has("files")).isTrue()
    }

    private companion object {
        const val OWNER = "mcp-files-owner"
        const val ADMINS = "MCP Files Admins"
    }
}
