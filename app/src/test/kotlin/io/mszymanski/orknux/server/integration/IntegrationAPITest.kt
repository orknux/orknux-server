package io.mszymanski.orknux.server.integration

import io.mszymanski.orknux.connector.connection.ConnectionRepository
import io.mszymanski.orknux.connector.connection.ConnectionTargetService
import io.mszymanski.orknux.connector.connection.McpServerRepository
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
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
 * Integrations end to end: the app decides who may ask and records what they
 * did, the connection module holds the data. One process, so this exercises
 * both — no stand-in for either half.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class IntegrationAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val connections: ConnectionRepository,
    @Autowired val workspaceConnections: WorkspaceConnectionRepository,
    @Autowired val mcpServers: McpServerRepository,
    @Autowired val targets: ConnectionTargetService,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        workspaceConnections.deleteAll()
        mcpServers.deleteAll()
        connections.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    @Test
    fun `defines a default connection and records it`() {
        graphQlTester.document(
            """
            mutation {
              createConnection(input: { name: "Slack", type: SLACK, url: "https://hooks.slack.com" })
              { id name type url }
            }
            """,
        ).execute()
            .path("createConnection.name").entity(String::class.java).isEqualTo("Slack")
            .path("createConnection.type").entity(String::class.java).isEqualTo("SLACK")

        assertThat(connections.findAll()).singleElement().extracting<String> { it.name }.isEqualTo("Slack")
        assertThat(audit.findAll().map { it.message }).contains("Default connection Slack created")
        // An admin-level change belongs to no workspace.
        assertThat(audit.findAll().map { it.workspaceId }).containsOnly(null)
    }

    @Test
    fun `refuses a duplicate default name`() {
        createDefault("Slack")

        graphQlTester.document(
            """mutation { createConnection(input: { name: "Slack", type: SLACK, url: "https://x" }) { id } }""",
        ).execute().errors().expect { it.message?.contains("already exists") == true }.verify()
    }

    @Test
    fun `a new workspace is provisioned with every default, and the log says so`() {
        createDefault("Slack")
        createDefault("Pager", type = "HTTP")

        val newWorkspace = graphQlTester.document(
            """mutation { createWorkspace(input: { name: "platform" }) { id } }""",
        ).execute().path("createWorkspace.id").entity(Long::class.java).get()

        graphQlTester.document("""query { workspaceConnections(workspaceId: $newWorkspace) { name inherited status } }""")
            .execute()
            .path("workspaceConnections[*].name").entityList(String::class.java).containsExactly("Pager", "Slack")
            .path("workspaceConnections[0].inherited").entity(Boolean::class.java).isEqualTo(true)
            .path("workspaceConnections[0].status").entity(String::class.java).isEqualTo("NOT_CHECKED")

        assertThat(audit.findAll().map { it.message })
            .contains("2 default connections provisioned for platform")
    }

    @Test
    fun `deleting a workspace takes its connections with it`() {
        createDefault("Slack")
        val newWorkspace = graphQlTester.document(
            """mutation { createWorkspace(input: { name: "platform" }) { id } }""",
        ).execute().path("createWorkspace.id").entity(Long::class.java).get()
        assertThat(workspaceConnections.findAll()).isNotEmpty()

        graphQlTester.document("""mutation { deleteWorkspace(id: $newWorkspace) }""")
            .execute().path("deleteWorkspace").entity(Boolean::class.java).isEqualTo(true)

        // No foreign key does this: the module is told.
        assertThat(workspaceConnections.findAll()).isEmpty()
    }

    @Test
    fun `a backfill hands a new default to the workspaces that already exist`() {
        graphQlTester.document(
            """
            mutation {
              createConnection(input: {
                name: "Pager", type: HTTP, url: "https://pager.test", addToExistingWorkspaces: true
              }) { id }
            }
            """,
        ).execute().path("createConnection.id").hasValue()

        assertThat(workspaceConnections.findAll().map { it.workspaceId }).containsExactly(workspaceId)
        assertThat(audit.findAll().map { it.message })
            .contains("Default connection Pager added to 1 existing workspace")
    }

    /**
     * A connection says what it is for. A workspace's own one keeps what the
     * workspace wrote, and empties it on an empty one; an inherited copy
     * follows the admin default's, as its name does.
     */
    @Test
    fun `a connection carries a description, and a copy follows its default's`() {
        val made = graphQlTester.document(
            """
            mutation {
              createConnection(input: {
                name: "Desk", type: HTTP, url: "https://desk.test", description: "the support desk",
                addToExistingWorkspaces: true
              }) { id description }
            }
            """,
        ).execute()
        made.path("createConnection.description").entity(String::class.java).isEqualTo("the support desk")
        val defaultId = made.path("createConnection.id").entity(Long::class.java).get()
        assertThat(workspaceConnections.findAll().single { it.name == "Desk" }.description).isEqualTo("the support desk")
        graphQlTester.document(
            """mutation { updateConnection(id: $defaultId, input: { name: "Desk", type: HTTP, url: "https://desk.test", description: "  the support desk, EU  " }) { id } }""",
        ).execute().path("updateConnection.id").hasValue()
        assertThat(workspaceConnections.findAll().single { it.name == "Desk" }.description).isEqualTo("the support desk, EU")

        val own = createWorkspaceConnection("Tracker", type = "HTTP")
        graphQlTester.document(
            """mutation { updateWorkspaceConnection(id: $own, input: { description: "production Jira" }) { description } }""",
        ).execute().path("updateWorkspaceConnection.description").entity(String::class.java).isEqualTo("production Jira")
        graphQlTester.document(
            """mutation { updateWorkspaceConnection(id: $own, input: { name: "Tracker" }) { description } }""",
        ).execute().path("updateWorkspaceConnection.description").entity(String::class.java).isEqualTo("production Jira")
        graphQlTester.document(
            """mutation { updateWorkspaceConnection(id: $own, input: { description: "" }) { description } }""",
        ).execute().path("updateWorkspaceConnection.description").valueIsNull()
    }

    @Test
    fun `storing credentials connects a connection, and revealing them is recorded`() {
        val id = createWorkspaceConnection("Slack")

        graphQlTester.document(
            """
            mutation {
              updateWorkspaceConnection(id: $id, input: { authType: BEARER_TOKEN, secret: "xoxb-token" })
              { secretSet status }
            }
            """,
        ).execute()
            .path("updateWorkspaceConnection.secretSet").entity(Boolean::class.java).isEqualTo(true)
            .path("updateWorkspaceConnection.status").entity(String::class.java).isEqualTo("NOT_CHECKED")

        graphQlTester.document("""mutation { revealWorkspaceConnectionSecret(id: $id) }""")
            .execute()
            .path("revealWorkspaceConnectionSecret").entity(String::class.java).isEqualTo("xoxb-token")

        val entry = audit.findAll().single { it.message.startsWith("Credentials for") }
        assertThat(entry.message).isEqualTo("Credentials for Slack revealed")
        assertThat(entry.workspaceId).isEqualTo(workspaceId)
        assertThat(entry.userId).isEqualTo("alice")
    }

    @Test
    fun `the app-level token can be read back, and the entry says which credential it was`() {
        val id = createWorkspaceConnection("Slack")

        graphQlTester.document(
            """
            mutation {
              updateWorkspaceConnection(id: $id, input: { secret: "xoxb-token", appToken: "xapp-1-token" })
              { secretSet appTokenSet }
            }
            """,
        ).execute()
            .path("updateWorkspaceConnection.appTokenSet").entity(Boolean::class.java).isEqualTo(true)

        graphQlTester.document("""mutation { revealWorkspaceConnectionAppToken(id: $id) }""")
            .execute()
            .path("revealWorkspaceConnectionAppToken").entity(String::class.java).isEqualTo("xapp-1-token")

        // Named, not "Credentials": two credentials can be revealed now and a
        // line that does not say which answers nothing.
        val entry = audit.findAll().single { it.message.startsWith("App-level token for") }
        assertThat(entry.message).isEqualTo("App-level token for Slack revealed")
        assertThat(entry.workspaceId).isEqualTo(workspaceId)
        assertThat(entry.userId).isEqualTo("alice")
    }

    @Test
    fun `a Slack connection is given its endpoint and its authentication rather than asked for them`() {
        // The form has one answer to either question, so it stops asking: there
        // is one Slack Web API base, and a bot token is a bearer token.
        val id = graphQlTester.document(
            """
            mutation {
              createWorkspaceConnection(input: { workspaceId: $workspaceId, name: "Slack", type: SLACK })
              { id url authType }
            }
            """,
        ).execute()
            .path("createWorkspaceConnection.url").entity(String::class.java).isEqualTo("https://slack.com/api")
            .path("createWorkspaceConnection.authType").entity(String::class.java).isEqualTo("BEARER_TOKEN")
            .path("createWorkspaceConnection.id").entity(Long::class.java).get()

        // And an update cannot take them away again.
        graphQlTester.document(
            """
            mutation {
              updateWorkspaceConnection(id: $id, input: { url: "https://elsewhere.test", authType: NONE })
              { url authType }
            }
            """,
        ).execute()
            .path("updateWorkspaceConnection.url").entity(String::class.java).isEqualTo("https://slack.com/api")
            .path("updateWorkspaceConnection.authType").entity(String::class.java).isEqualTo("BEARER_TOKEN")
    }

    @Test
    fun `a Slack connection is configured by its bot token alone`() {
        val id = createWorkspaceConnection("Slack")
        graphQlTester.document("""query { workspaceConnection(id: $id) { status } }""")
            .execute()
            .path("workspaceConnection.status").entity(String::class.java).isEqualTo("NOT_CONFIGURED")

        // No app token: the connection sends and never listens, which is a whole
        // integration and must not report itself as half-finished.
        graphQlTester.document(
            """mutation { updateWorkspaceConnection(id: $id, input: { secret: "xoxb-token" }) { status } }""",
        ).execute()
            .path("updateWorkspaceConnection.status").entity(String::class.java).isEqualTo("NOT_CHECKED")
    }

    @Test
    fun `a check reports what the service answered`() {
        // Not a Slack connection: that type now always points at slack.com, and
        // a check on one would call Slack for real. An HTTP endpoint keeps the
        // URL it was given, which is what makes this test stay on the machine.
        val id = createWorkspaceConnection("Slack", url = "https://orknux-test.invalid/hook", type = "HTTP")
        graphQlTester.document(
            """mutation { updateWorkspaceConnection(id: $id, input: { secret: "x" }) { secretSet } }""",
        ).execute()

        // .invalid never resolves, so the probe fails without leaving the machine.
        graphQlTester.document("""mutation { testWorkspaceConnection(id: $id) { status lastCheckMessage } }""")
            .execute()
            .path("testWorkspaceConnection.status").entity(String::class.java).isEqualTo("FAILED")
            .path("testWorkspaceConnection.lastCheckMessage").hasValue()

        assertThat(audit.findAll().map { it.message }).contains("Connection Slack checked: failed")
    }

    @Test
    fun `disconnecting removes a connection the workspace added itself`() {
        val id = createWorkspaceConnection("Slack")

        graphQlTester.document("""mutation { disconnectWorkspaceConnection(id: $id) }""")
            .execute().path("disconnectWorkspaceConnection").entity(Boolean::class.java).isEqualTo(true)

        assertThat(workspaceConnections.findAll()).isEmpty()
        assertThat(audit.findAll().map { it.message }).contains("Connection Slack disconnected")
    }

    @Test
    fun `an mcp server is added, and its credentials are never listed`() {
        graphQlTester.document(
            """
            mutation {
              createMcpServer(input: {
                workspaceId: $workspaceId, name: "brave-search", address: "https://mcp.brave.com/v1", authType: API_KEY,
                secret: "sk-live", headers: [{ name: "X-Custom", value: "v" }, { name: " ", value: "dropped" }]
              }) { name authType secretSet headers { name } }
            }
            """,
        ).execute()
            .path("createMcpServer.authType").entity(String::class.java).isEqualTo("API_KEY")
            .path("createMcpServer.secretSet").entity(Boolean::class.java).isEqualTo(true)
            // A blank header name never reaches the database.
            .path("createMcpServer.headers").entityList(Map::class.java).hasSize(1)

        assertThat(mcpServers.findAll().single().secret).isEqualTo("sk-live")
        assertThat(audit.findAll().map { it.message }).contains("MCP Server brave-search added")
    }

    @Test
    fun `a resolved target carries the credential as a header`() {
        val id = createWorkspaceConnection("Slack")
        graphQlTester.document(
            """
            mutation {
              updateWorkspaceConnection(id: $id, input: { authType: BEARER_TOKEN, secret: "xoxb-token" }) { secretSet }
            }
            """,
        ).execute()

        // What a node runner asks for when it has something to send.
        val target = targets.connectionTarget(id)
        assertThat(target.headers.map { it.name }).contains("Authorization")
        assertThat(target.headers.single { it.name == "Authorization" }.value).isEqualTo("Bearer xoxb-token")
    }

    @Test
    fun `a workspace that does not exist is refused`() {
        graphQlTester.document("""query { mcpServers(workspaceId: 999999) { name } }""")
            .execute()
            .errors()
            .expect { it.message?.contains("No workspace") == true }
            .verify()
    }

    private fun createDefault(name: String, type: String = "SLACK"): Long = graphQlTester.document(
        """mutation { createConnection(input: { name: "$name", type: $type, url: "https://example.test" }) { id } }""",
    ).execute().path("createConnection.id").entity(Long::class.java).get()

    private fun createWorkspaceConnection(
        name: String,
        url: String = "https://example.test",
        type: String = "SLACK",
    ): Long =
        graphQlTester.document(
            """
            mutation {
              createWorkspaceConnection(input: { workspaceId: $workspaceId, name: "$name", type: $type, url: "$url" }) { id }
            }
            """,
        ).execute().path("createWorkspaceConnection.id").entity(Long::class.java).get()
}
