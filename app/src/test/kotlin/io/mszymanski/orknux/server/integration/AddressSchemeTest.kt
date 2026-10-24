package io.mszymanski.orknux.server.integration

import io.mszymanski.orknux.connector.connection.ConnectionRepository
import io.mszymanski.orknux.connector.connection.McpServerRepository
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.action.WorkflowActionRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * An http(s) address typed without a scheme is stored as http://, through every
 * door that saves one: an MCP server, a model provider, a default and a workspace
 * connection, an HTTP action. One typed with https:// is stored as typed, a mail
 * server's host is left a host, and nothing typed is still refused. The box and
 * the installation's own address are in [io.mszymanski.orknux.server.box.BoxAPITest]
 * and [io.mszymanski.orknux.server.security.InstallationAddressAPITest].
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class AddressSchemeTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val connections: ConnectionRepository,
    @Autowired val workspaceConnections: WorkspaceConnectionRepository,
    @Autowired val mcpServers: McpServerRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val actions: WorkflowActionRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        clean()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "addresses")).id)
    }

    @AfterEach
    fun clean() {
        actions.deleteAll()
        workspaceConnections.deleteAll()
        mcpServers.deleteAll()
        providers.deleteAll()
        connections.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
    }

    private fun id(document: String, field: String): Long =
        graphQlTester.document(document).execute().errors().verify().path("$field.id").entity(Long::class.java).get()

    private fun refused(document: String, code: String) {
        graphQlTester.document(document).execute().errors().satisfy { errors ->
            assertThat(errors.map { it.extensions["code"] }).containsExactly(code)
        }
    }

    @Test
    fun `an MCP server address`() {
        val made = id(
            """mutation { createMcpServer(input: { workspaceId: $workspaceId, name: "tools", address: " tools.invalid:8931/mcp " }) { id } }""",
            "createMcpServer",
        )
        assertThat(mcpServers.findById(made).get().address).isEqualTo("http://tools.invalid:8931/mcp")

        id("""mutation { updateMcpServer(id: $made, input: { name: "tools", address: "https://tools.invalid/mcp" }) { id } }""", "updateMcpServer")
        assertThat(mcpServers.findById(made).get().address).isEqualTo("https://tools.invalid/mcp")

        id("""mutation { updateMcpServer(id: $made, input: { name: "tools", address: "10.0.0.7:8931" }) { id } }""", "updateMcpServer")
        assertThat(mcpServers.findById(made).get().address).isEqualTo("http://10.0.0.7:8931")

        refused(
            """mutation { updateMcpServer(id: $made, input: { name: "tools", address: "  " }) { id } }""",
            "McpServerAddressInvalid",
        )
    }

    @Test
    fun `a model provider endpoint`() {
        val made = id(
            """mutation { createModelProvider(input: { workspaceId: $workspaceId, name: "local", endpoint: "192.168.0.199:8005/v1" }) { id } }""",
            "createModelProvider",
        )
        assertThat(providers.findById(made).get().endpoint).isEqualTo("http://192.168.0.199:8005/v1")

        id("""mutation { updateModelProvider(id: $made, input: { name: "local", endpoint: "https://models.invalid/v1" }) { id } }""", "updateModelProvider")
        assertThat(providers.findById(made).get().endpoint).isEqualTo("https://models.invalid/v1")

        refused(
            """mutation { createModelProvider(input: { workspaceId: $workspaceId, name: "empty", endpoint: "" }) { id } }""",
            "ModelProviderEndpointInvalid",
        )
    }

    @Test
    fun `a default connection's address, and a mail server's host left a host`() {
        val http = id(
            """mutation { createConnection(input: { name: "Tracker", type: HTTP, url: "jira.invalid" }) { id } }""",
            "createConnection",
        )
        assertThat(connections.findById(http).get().url).isEqualTo("http://jira.invalid")

        id("""mutation { updateConnection(id: $http, input: { name: "Tracker", type: HTTP, url: "https://jira.invalid" }) { id } }""", "updateConnection")
        assertThat(connections.findById(http).get().url).isEqualTo("https://jira.invalid")

        val mail = id(
            """mutation { createConnection(input: { name: "Mail", type: SMTP, url: "smtp.invalid" }) { id } }""",
            "createConnection",
        )
        assertThat(connections.findById(mail).get().url).isEqualTo("smtp.invalid")

        refused(
            """mutation { createConnection(input: { name: "Nowhere", type: HTTP, url: " " }) { id } }""",
            "ConnectionUrlInvalid",
        )
    }

    @Test
    fun `a workspace connection's address and its override`() {
        val made = id(
            """mutation { createWorkspaceConnection(input: { workspaceId: $workspaceId, name: "Hook", type: HTTP, url: "hooks.invalid/in" }) { id } }""",
            "createWorkspaceConnection",
        )
        assertThat(workspaceConnections.findById(made).get().url).isEqualTo("http://hooks.invalid/in")

        id(
            """mutation { updateWorkspaceConnection(id: $made, input: { url: "https://hooks.invalid/in", urlOverride: "10.1.1.1:9000" }) { id } }""",
            "updateWorkspaceConnection",
        )
        workspaceConnections.findById(made).get().let {
            assertThat(it.url).isEqualTo("https://hooks.invalid/in")
            assertThat(it.urlOverride).isEqualTo("http://10.1.1.1:9000")
        }

        val mail = id(
            """mutation { createWorkspaceConnection(input: { workspaceId: $workspaceId, name: "Mail", type: SMTP, url: "smtp.invalid" }) { id } }""",
            "createWorkspaceConnection",
        )
        assertThat(workspaceConnections.findById(mail).get().url).isEqualTo("smtp.invalid")

        refused(
            """mutation { updateWorkspaceConnection(id: $made, input: { url: "" }) { id } }""",
            "ConnectionUrlInvalid",
        )
    }

    @Test
    fun `an HTTP action's URL`() {
        val made = id(
            """mutation { createAction(input: {
                 workspaceId: $workspaceId, name: "Fetch", type: EXECUTE, subtype: HTTP_REQUEST, url: "api.invalid/orders"
               }) { id } }""",
            "createAction",
        )
        assertThat(actions.findById(made).get().url).isEqualTo("http://api.invalid/orders")

        id("""mutation { updateAction(id: $made, input: { url: "https://api.invalid/orders" }) { id } }""", "updateAction")
        assertThat(actions.findById(made).get().url).isEqualTo("https://api.invalid/orders")

        refused(
            """mutation { createAction(input: {
                 workspaceId: $workspaceId, name: "Nowhere", type: EXECUTE, subtype: HTTP_REQUEST, url: " "
               }) { id } }""",
            "ActionSettingMissing",
        )
    }
}
