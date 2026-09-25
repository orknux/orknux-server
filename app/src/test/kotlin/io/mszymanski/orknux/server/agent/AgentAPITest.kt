package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class AgentAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val agents: AgentRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private var otherWorkspaceId: Long = 0

    @BeforeEach
    fun reset() {
        agents.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        otherWorkspaceId = requireNotNull(workspaces.save(Workspace(name = "frontend")).id)
    }

    @Test
    fun `creates an agent, enabled, with its type`() {
        graphQlTester.document(
            """
            mutation {
              createAgent(input: {
                workspaceId: $workspaceId,
                name: "Research Agent",
                type: LLM,
                description: "Conducts web search and synthesizes market data findings"
              }) { name type description enabled mcpServers icon }
            }
            """,
        ).execute()
            .path("createAgent.name").entity(String::class.java).isEqualTo("Research Agent")
            .path("createAgent.type").entity(String::class.java).isEqualTo("LLM")
            .path("createAgent.enabled").entity(Boolean::class.java).isEqualTo(true)
            .path("createAgent.mcpServers").entityList(String::class.java).hasSize(0)
            // A fresh agent draws as a bot unless one names another icon. Issue #415.
            .path("createAgent.icon").entity(String::class.java).isEqualTo("bot")

        assertThat(agents.findAll().single().workspaceId).isEqualTo(workspaceId)
    }

    @Test
    fun `an agent created with its own icon keeps it, not the bot default`() {
        graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Owl", type: LLM, icon: "owl" }) { icon } }""",
        ).execute().path("createAgent.icon").entity(String::class.java).isEqualTo("owl")
    }

    @Test
    fun `lists only the agents of the requested workspace, ordered by name`() {
        create("Writer Agent", "LLM")
        create("Code Review Agent", "LLM")
        create("Frontend Helper", "LLM", workspace = otherWorkspaceId)

        graphQlTester.document("""query { workspaceAgents(workspaceId: $workspaceId) { content { name } totalElements } }""")
            .execute()
            .path("workspaceAgents.content[*].name").entityList(String::class.java)
            .containsExactly("Code Review Agent", "Writer Agent")
            .path("workspaceAgents.totalElements").entity(Int::class.java).isEqualTo(2)
    }

    @Test
    fun `saves the settings form, including system prompt and mcp servers`() {
        val id = create("Research Agent", "LLM")

        graphQlTester.document(
            """
            mutation {
              updateAgent(id: $id, input: {
                name: "Research Agent",
                description: "Conducts web search.",
                systemPrompt: "You are a research agent.",
                mcpServers: ["brave-search", "postgres-db"]
              }) { description systemPrompt mcpServers }
            }
            """,
        ).execute()
            .path("updateAgent.systemPrompt").entity(String::class.java).isEqualTo("You are a research agent.")
            .path("updateAgent.mcpServers").entityList(String::class.java)
            .containsExactly("brave-search", "postgres-db")

        assertThat(agents.findAll().single().mcpServers).containsExactly("brave-search", "postgres-db")
    }

    @Test
    fun `drops blank and repeated mcp servers, keeping order`() {
        val id = create("Research Agent", "LLM")

        graphQlTester.document(
            """
            mutation {
              updateAgent(id: $id, input: {
                name: "Research Agent",
                mcpServers: ["  brave-search ", "postgres-db", "brave-search", "   "]
              }) { mcpServers }
            }
            """,
        ).execute()
            .path("updateAgent.mcpServers").entityList(String::class.java)
            .containsExactly("brave-search", "postgres-db")
    }

    @Test
    fun `clears the mcp servers with an empty list`() {
        val id = create("Research Agent", "LLM")
        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "Research Agent", mcpServers: ["brave-search"] }) { mcpServers } }""",
        ).execute()

        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "Research Agent", mcpServers: [] }) { mcpServers } }""",
        ).execute().path("updateAgent.mcpServers").entityList(String::class.java).hasSize(0)

        assertThat(agents.findAll().single().mcpServers).isEmpty()
    }

    @Test
    fun `rejects a duplicate agent name within the workspace`() {
        create("Research Agent", "LLM")

        graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Research Agent", type: LLM }) { id } }""",
        ).execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors).singleElement()
                    .extracting { it.message }
                    .isEqualTo("""An agent named "Research Agent" already exists in this workspace""")
            }

        assertThat(agents.findAll()).hasSize(1)
    }

    @Test
    fun `allows the same agent name in another workspace`() {
        create("Research Agent", "LLM")
        create("Research Agent", "LLM", workspace = otherWorkspaceId)

        assertThat(agents.findAll()).hasSize(2)
    }

    @Test
    fun `rejects a blank agent name`() {
        graphQlTester.document("""mutation { createAgent(input: { workspaceId: $workspaceId, name: " ", type: LLM }) { id } }""")
            .execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors).singleElement()
                    .extracting { it.message }
                    .isEqualTo("An agent name is required")
            }
    }

    @Test
    fun `disables and re-enables an agent`() {
        val id = create("Research Agent", "LLM")

        graphQlTester.document("""mutation { setAgentEnabled(id: $id, enabled: false) { enabled } }""")
            .execute().path("setAgentEnabled.enabled").entity(Boolean::class.java).isEqualTo(false)

        assertThat(agents.findAll().single().enabled).isFalse()

        graphQlTester.document("""mutation { setAgentEnabled(id: $id, enabled: true) { enabled } }""")
            .execute().path("setAgentEnabled.enabled").entity(Boolean::class.java).isEqualTo(true)
    }

    @Test
    fun `deletes an agent and reports whether it existed`() {
        val id = create("Research Agent", "LLM")

        graphQlTester.document("""mutation { deleteAgent(id: $id) }""")
            .execute().path("deleteAgent").entity(Boolean::class.java).isEqualTo(true)

        assertThat(agents.findAll()).isEmpty()

        graphQlTester.document("""mutation { deleteAgent(id: $id) }""")
            .execute().path("deleteAgent").entity(Boolean::class.java).isEqualTo(false)
    }

    @Test
    fun `deleting a workspace takes its agents with it`() {
        create("Research Agent", "LLM")

        workspaces.deleteById(workspaceId)

        assertThat(agents.findAll()).isEmpty()
    }

    private fun create(name: String, type: String, workspace: Long = workspaceId): String =
        graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspace, name: "$name", type: $type }) { id } }""",
        ).execute().path("createAgent.id").entity(String::class.java).get()
}
