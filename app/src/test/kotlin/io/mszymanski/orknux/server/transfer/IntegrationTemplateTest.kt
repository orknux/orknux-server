package io.mszymanski.orknux.server.transfer

import io.mszymanski.orknux.connector.connection.McpServerRepository
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * Connections and MCP servers as templates. Issue #646.
 *
 * Saved from a row with the settings and without the credentials, offered on
 * the Integrations page and nowhere a component is, and used into another
 * workspace - naming what has to be given a credential again, and leaving a
 * name already there alone.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class IntegrationTemplateTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val connections: WorkspaceConnectionRepository,
    @Autowired val servers: McpServerRepository,
    @Autowired val templates: ComponentTemplateRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    @Test
    fun `a connection saved as a template carries its settings, leaves its secret, and is used once`() {
        val stamp = System.nanoTime()
        val from = requireNotNull(workspaces.save(Workspace(name = "tpl conn from $stamp")).id)
        val into = requireNotNull(workspaces.save(Workspace(name = "tpl conn into $stamp")).id)
        val connection = graphQlTester.document(
            """mutation { createWorkspaceConnection(input: {
                 workspaceId: $from, name: "Billing", type: HTTP, url: "http://billing.invalid/api",
                 description: "Invoices", authType: BEARER_TOKEN, secret: "tok-secret",
                 headers: [{ name: "X-Team", value: "finance" }]
               }) { id } }""",
        ).execute().path("createWorkspaceConnection.id").entity(Long::class.java).get()

        val name = "Billing API $stamp"
        val saved = graphQlTester.document(
            """mutation { saveConnectionAsTemplate(workspaceId: $from, id: $connection, name: "$name", description: "Our billing") {
                 id ofIntegrations ofModels connections mcpServers componentCount usable } }""",
        ).execute()
        saved.path("saveConnectionAsTemplate.ofIntegrations").entity(Boolean::class.java).isEqualTo(true)
            .path("saveConnectionAsTemplate.ofModels").entity(Boolean::class.java).isEqualTo(false)
            .path("saveConnectionAsTemplate.connections").entityList(String::class.java).containsExactly("Billing")
            .path("saveConnectionAsTemplate.mcpServers").entityList(String::class.java).hasSize(0)
            .path("saveConnectionAsTemplate.usable").entity(Boolean::class.java).isEqualTo(true)
        val id = saved.path("saveConnectionAsTemplate.id").entity(Long::class.java).get()

        // No credential is stored in the template, as a copy or otherwise.
        val stored = requireNotNull(templates.findByNameIgnoreCase(name)).envelope
        assertThat(stored).contains("orknux-integrations", "http://billing.invalid/api", "X-Team", "Invoices")
            .doesNotContain("tok-secret")

        graphQlTester.document("""query { integrationTemplates { name } }""").execute()
            .path("integrationTemplates[*].name").entityList(String::class.java).contains(name)
        graphQlTester.document("""query { modelTemplates { name } }""").execute()
            .path("modelTemplates[*].name").entityList(String::class.java).doesNotContain(name)
        graphQlTester.document("""query { componentTemplates(holding: FUNCTION) { name } }""").execute()
            .path("componentTemplates[*].name").entityList(String::class.java).doesNotContain(name)

        fun use() = graphQlTester.document(
            """mutation { useIntegrationTemplate(workspaceId: $into, id: $id) {
                 connections mcpServers skipped needsCredential { kind id name } } }""",
        ).execute()
        val used = use()
        used.path("useIntegrationTemplate.connections").entityList(String::class.java).containsExactly("Billing")
            .path("useIntegrationTemplate.needsCredential[*].name").entityList(String::class.java).containsExactly("Billing")
            .path("useIntegrationTemplate.needsCredential[0].kind").entity(String::class.java).isEqualTo("CONNECTION")
        val arrived = requireNotNull(connections.findByWorkspaceIdAndName(into, "Billing"))
        used.path("useIntegrationTemplate.needsCredential[0].id").entity(Long::class.java).isEqualTo(requireNotNull(arrived.id))
        assertThat(arrived.secret).isNull()
        assertThat(arrived.secretVariableId).isNull()
        assertThat(arrived.url).isEqualTo("http://billing.invalid/api")
        assertThat(arrived.description).isEqualTo("Invoices")
        assertThat(arrived.authType.name).isEqualTo("BEARER_TOKEN")
        assertThat(arrived.headers.map { it.name to it.value }).containsExactly("X-Team" to "finance")
        assertThat(audit.findByWorkspaceId(into, PageRequest.of(0, 50)).content.map { it.message })
            .contains("Connection Billing added from template $name")

        // Used again: the name is taken here, so nothing changes.
        use().path("useIntegrationTemplate.connections").entityList(String::class.java).hasSize(0)
            .path("useIntegrationTemplate.skipped").entityList(String::class.java).containsExactly("Billing")
        assertThat(connections.findByWorkspaceId(into, org.springframework.data.domain.Sort.unsorted())).hasSize(1)
    }

    @Test
    fun `an MCP server saved as a template arrives without its credential and asks for one`() {
        val stamp = System.nanoTime()
        val from = requireNotNull(workspaces.save(Workspace(name = "tpl mcp from $stamp")).id)
        val into = requireNotNull(workspaces.save(Workspace(name = "tpl mcp into $stamp")).id)
        val server = graphQlTester.document(
            """mutation { createMcpServer(input: {
                 workspaceId: $from, name: "Search", address: "http://search.invalid/mcp", authType: API_KEY, secret: "key-secret"
               }) { id } }""",
        ).execute().path("createMcpServer.id").entity(Long::class.java).get()
        val open = graphQlTester.document(
            """mutation { createMcpServer(input: { workspaceId: $from, name: "Open", address: "http://open.invalid/mcp" }) { id } }""",
        ).execute().path("createMcpServer.id").entity(Long::class.java).get()

        val withKey = graphQlTester.document(
            """mutation { saveMcpServerAsTemplate(workspaceId: $from, id: $server, name: "Search $stamp") { id mcpServers } }""",
        ).execute()
        withKey.path("saveMcpServerAsTemplate.mcpServers").entityList(String::class.java).containsExactly("Search")
        val keyed = withKey.path("saveMcpServerAsTemplate.id").entity(Long::class.java).get()
        val plain = graphQlTester.document(
            """mutation { saveMcpServerAsTemplate(workspaceId: $from, id: $open, name: "Open $stamp") { id } }""",
        ).execute().path("saveMcpServerAsTemplate.id").entity(Long::class.java).get()
        assertThat(requireNotNull(templates.findByNameIgnoreCase("Search $stamp")).envelope).doesNotContain("key-secret")

        graphQlTester.document(
            """mutation { useIntegrationTemplate(workspaceId: $into, id: $keyed) { mcpServers needsCredential { kind name } } }""",
        ).execute()
            .path("useIntegrationTemplate.mcpServers").entityList(String::class.java).containsExactly("Search")
            .path("useIntegrationTemplate.needsCredential[0].kind").entity(String::class.java).isEqualTo("MCP_SERVER")
        val arrived = requireNotNull(servers.findByWorkspaceIdAndName(into, "Search"))
        assertThat(arrived.secret).isNull()
        assertThat(arrived.address).isEqualTo("http://search.invalid/mcp")
        assertThat(arrived.authType.name).isEqualTo("API_KEY")

        // One that never had a credential asks for nothing.
        graphQlTester.document(
            """mutation { useIntegrationTemplate(workspaceId: $into, id: $plain) { mcpServers needsCredential { name } } }""",
        ).execute()
            .path("useIntegrationTemplate.mcpServers").entityList(String::class.java).containsExactly("Open")
            .path("useIntegrationTemplate.needsCredential").entityList(Any::class.java).hasSize(0)
    }

    @Test
    fun `a row of another workspace is refused, and a template of something else is not one of integrations`() {
        val stamp = System.nanoTime()
        val mine = requireNotNull(workspaces.save(Workspace(name = "tpl mine $stamp")).id)
        val theirs = requireNotNull(workspaces.save(Workspace(name = "tpl theirs $stamp")).id)
        val server = graphQlTester.document(
            """mutation { createMcpServer(input: { workspaceId: $theirs, name: "Theirs", address: "http://theirs.invalid/mcp" }) { id } }""",
        ).execute().path("createMcpServer.id").entity(Long::class.java).get()

        graphQlTester.document("""mutation { saveMcpServerAsTemplate(workspaceId: $mine, id: $server, name: "Stolen $stamp") { id } }""")
            .execute().errors().satisfy {
                assertThat(it.single().message).isEqualTo("There is no MCP server $server in this workspace.")
                assertThat(it.single().extensions["code"]).isEqualTo("IntegrationFileRefused")
            }
        assertThat(templates.findByNameIgnoreCase("Stolen $stamp")).isNull()

        val provider = graphQlTester.document(
            """mutation { createModelProvider(input: { workspaceId: $mine, name: "Lab", type: OPENAI, endpoint: "http://lab.invalid/v1" }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        val model = graphQlTester.document(
            """mutation { createModel(input: { providerId: $provider, name: "Talker", modelId: "talker", kind: CHAT }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
        val models = graphQlTester.document(
            """mutation { publishModelTemplate(workspaceId: $mine, modelIds: [$model], name: "Models $stamp") { id ofIntegrations } }""",
        ).execute()
        models.path("publishModelTemplate.ofIntegrations").entity(Boolean::class.java).isEqualTo(false)
        val modelsId = models.path("publishModelTemplate.id").entity(Long::class.java).get()
        graphQlTester.document("""mutation { useIntegrationTemplate(workspaceId: $mine, id: $modelsId) { connections } }""")
            .execute().errors().satisfy {
                assertThat(it.single().message).isEqualTo("There is no template of integrations $modelsId.")
            }
    }

    @Test
    @WithMockUser(username = "bob", roles = ["USERS"])
    fun `saving one is an administrator's`() {
        val stamp = System.nanoTime()
        val from = requireNotNull(workspaces.save(Workspace(name = "tpl denied $stamp")).id)
        graphQlTester.document("""mutation { saveConnectionAsTemplate(workspaceId: $from, id: 1, name: "Denied $stamp") { id } }""")
            .execute().errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("AdminRequired") }
        assertThat(templates.findByNameIgnoreCase("Denied $stamp")).isNull()
    }
}
