package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.server.agent.AgentSkill
import io.mszymanski.orknux.server.agent.AgentSkillRepository
import io.mszymanski.orknux.server.agent.SkillCatalog
import io.mszymanski.orknux.server.agent.SkillCatalogRepository
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

/**
 * The workspace's skills, offered over its MCP endpoint as prompts and as
 * resources. Issue #617.
 *
 * Driven over HTTP with a real token, because what is being tested is what an
 * outside client sees: the capability in the handshake, the listing, and the
 * page it gets back - and that a skill switched off is none of those.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpSkillsEndpointTest(
    @LocalServerPort val port: Int,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val users: AppUserRepository,
    @Autowired val roles: RoleRepository,
    @Autowired val internal: InternalAuthentication,
    @Autowired val catalogs: SkillCatalogRepository,
    @Autowired val skills: AgentSkillRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private val client = RestClient.builder()
        .baseUrl("http://localhost:$port")
        .defaultStatusHandler({ true }, { _, _ -> })
        .build()

    private var workspaceId: Long = 0
    private lateinit var token: String

    /** A workspace of its own, and nothing deleted: this class shares the database. */
    @BeforeEach
    fun arrive() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "mcp-skills-${System.nanoTime()}")).id)
        val catalog = catalogs.save(SkillCatalog(workspaceId = workspaceId, name = "Team Ways"))
        skills.save(
            AgentSkill(
                workspaceId = workspaceId,
                catalogId = requireNotNull(catalog.id),
                name = "Reviewing a change",
                key = "reviewing-a-change",
                description = "How this team reviews",
                content = PAGE,
            ),
        )
        skills.save(
            AgentSkill(
                workspaceId = workspaceId,
                catalogId = requireNotNull(catalog.id),
                name = "Retired",
                key = "retired",
                content = "---\nname: Retired\n---\nGone.",
                enabled = false,
            ),
        )

        val admins = roles.findByName(ADMINS)
            ?: roles.save(Role(name = ADMINS, scopes = mutableSetOf(RoleScope.ADMIN, RoleScope.USER)))
        val reader = users.findByUsername(READER)
            ?: users.save(
                AppUser(username = READER, displayName = "Skill Reader", type = UserType.INTERNAL, roles = mutableSetOf(admins)),
            )
        token = internal.mint(reader, "test-${System.nanoTime()}").second
    }

    @Test
    fun `the handshake says prompts and resources are offered`() {
        val capabilities = rpc("initialize", "{}").path("result").path("capabilities")
        assertThat(capabilities.has("prompts")).isTrue()
        assertThat(capabilities.has("resources")).isTrue()
    }

    @Test
    fun `a skill is a prompt, and getting it hands back its page`() {
        val prompts = rpc("prompts/list", "{}").path("result").path("prompts")
        val names = prompts.toList().map { it.path("name").stringValue() }
        assertThat(names).contains("reviewing-a-change").doesNotContain("retired")
        // Not the server's own catalog: those pages are for its own agents.
        assertThat(names).doesNotContain("caveman")

        val got = rpc("prompts/get", """{"name":"reviewing-a-change"}""").path("result")
        val message = got.path("messages").toList().single()
        assertThat(message.path("role").stringValue()).isEqualTo("user")
        assertThat(message.path("content").path("text").stringValue()).isEqualTo(PAGE)

        assertThat(rpc("prompts/get", """{"name":"retired"}""").path("error").path("code").asInt()).isEqualTo(-32602)
    }

    @Test
    fun `a skill is a resource at an address of its own, and reading it hands back its page`() {
        val listed = rpc("resources/list", "{}").path("result").path("resources").toList()
            .single { it.path("name").stringValue() == "reviewing-a-change" }
        val uri = listed.path("uri").stringValue()
        assertThat(uri).isEqualTo("orknux://skills/Team%20Ways/reviewing-a-change")
        assertThat(listed.path("mimeType").stringValue()).isEqualTo("text/markdown")

        val read = rpc("resources/read", """{"uri":"$uri"}""").path("result").path("contents").toList().single()
        assertThat(read.path("text").stringValue()).isEqualTo(PAGE)

        val missing = rpc("resources/read", """{"uri":"orknux://skills/Team%20Ways/retired"}""")
        assertThat(missing.path("error").path("code").asInt()).isEqualTo(-32002)
    }

    private fun rpc(method: String, params: String): JsonNode {
        val body = client.post().uri("/mcp/$workspaceId")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .body("""{"jsonrpc":"2.0","id":7,"method":"$method","params":$params}""")
            .retrieve()
            .body(String::class.java)
        return mapper.readTree(requireNotNull(body))
    }

    private companion object {
        const val ADMINS = "Admins"
        const val READER = "skill-reader"
        const val PAGE = "---\nname: Reviewing a change\ndescription: How this team reviews\n---\nRead the diff first."
    }
}
