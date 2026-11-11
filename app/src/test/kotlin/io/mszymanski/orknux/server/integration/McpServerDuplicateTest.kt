package io.mszymanski.orknux.server.integration

import io.mszymanski.orknux.connector.connection.McpServerRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * Copying an MCP server, from its row on Integrations.
 *
 * What it is for is the same server in another environment - prod beside
 * test - so the copy carries everything but the name: the address, the
 * headers, and the credential, whether the server keeps its own or reads a
 * workspace secret. A copy that arrived without its token would be the work
 * the button exists to skip.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class McpServerDuplicateTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val servers: McpServerRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        servers.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "mcp-copied-${System.nanoTime()}")).id)
    }

    private fun id(document: String, path: String): Long =
        graphQlTester.document(document).execute().errors().verify().path(path).entity(Long::class.java).get()

    private fun duplicate(id: Long): Long =
        id("""mutation { duplicateMcpServer(id: $id) { id } }""", "duplicateMcpServer.id")

    @Test
    fun `a copy is named beside it and carries the address, the headers and its own token`() {
        val original = id(
            """mutation { createMcpServer(input: { workspaceId: $workspaceId, name: "order mcp", description: "orders, read only",
                 address: "http://orders.example.invalid/api/mcp", authType: BEARER_TOKEN, secret: "tok-own",
                 headers: [{ name: "X-Env", value: "prod" }] }) { id } }""",
            "createMcpServer.id",
        )

        val first = servers.findById(duplicate(original)).orElseThrow()
        val second = servers.findById(duplicate(original)).orElseThrow()

        assertThat(first.name).isEqualTo("order mcp (copy)")
        assertThat(second.name).describedAs("past the names taken").isEqualTo("order mcp (copy 2)")
        assertThat(first.workspaceId).isEqualTo(workspaceId)
        assertThat(first.address).isEqualTo("http://orders.example.invalid/api/mcp")
        assertThat(first.description).isEqualTo("orders, read only")
        assertThat(first.authType.name).isEqualTo("BEARER_TOKEN")
        assertThat(first.headers.map { it.name to it.value }).containsExactly("X-Env" to "prod")
        assertThat(first.secretVariableId).isNull()

        graphQlTester.document("""mutation { revealMcpServerSecret(id: ${first.id}) }""")
            .execute().path("revealMcpServerSecret").entity(String::class.java).isEqualTo("tok-own")
        // Not checked yet: what the original's last check found is the original's.
        assertThat(first.lastCheckedAt).isNull()
        assertThat(audit.findAll().map { it.message }).contains("MCP Server order mcp duplicated as order mcp (copy)")
    }

    @Test
    fun `a server reading a workspace secret is copied reading the same one`() {
        val catalog = id("""mutation { createVariableCatalog(workspaceId: $workspaceId, name: "Keys") { id } }""", "createVariableCatalog.id")
        val token = id(
            """mutation { createVariable(input: { workspaceId: $workspaceId, catalogId: $catalog, name: "jira_token",
                 type: STRING, kind: SECRET, value: "s3cret" }) { id } }""",
            "createVariable.id",
        )
        val original = id(
            """mutation { createMcpServer(input: { workspaceId: $workspaceId, name: "jira",
                 address: "https://jira.example.invalid/mcp", authType: BEARER_TOKEN, secretVariableId: $token }) { id } }""",
            "createMcpServer.id",
        )

        val copy = servers.findById(duplicate(original)).orElseThrow()

        assertThat(copy.secretVariableId).isEqualTo(token)
        assertThat(copy.secret).isNull()
    }

    @Test
    fun `a server nobody can see is not copied`() {
        graphQlTester.document("""mutation { duplicateMcpServer(id: 987654) { id } }""")
            .execute().errors().satisfy { errors -> assertThat(errors).isNotEmpty() }
        assertThat(servers.findAll()).isEmpty()
    }
}
