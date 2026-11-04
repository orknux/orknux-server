package io.mszymanski.orknux.server.integration

import io.mszymanski.orknux.connector.connection.McpServerRepository
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.chat.AgentBriefing
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

/**
 * An MCP server says what it is for, and an agent holding it is told.
 *
 * A tool is named `wiki__search` and described by whoever wrote the server,
 * for every installation: neither says which wiki this one is, or when it is
 * the one to reach for. Two servers onto one kind of system are told apart by
 * what they are for, as two connections are.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class McpServerDescriptionTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val servers: McpServerRepository,
    @Autowired val briefing: AgentBriefing,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        servers.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "mcp-described-${System.nanoTime()}")).id)
    }

    private fun create(name: String, description: String?): String = graphQlTester.document(
        """mutation(${'$'}d: String) { createMcpServer(input: { workspaceId: $workspaceId, name: "$name",
             address: "http://wiki.example.invalid/mcp", description: ${'$'}d }) { id description } }""",
    ).variable("d", description).execute().path("createMcpServer.id").entity(String::class.java).get()

    private fun update(id: String, extra: String) = graphQlTester.document(
        """mutation { updateMcpServer(id: $id, input: { name: "Wiki", address: "http://wiki.example.invalid/mcp" $extra })
             { description } }""",
    ).execute().path("updateMcpServer.description")

    private fun read(id: String): String? = graphQlTester.document("{ mcpServer(id: $id) { description } }")
        .execute().path("mcpServer.description").entity(String::class.java).get()

    @Test
    fun `a description is kept, changed, left alone and cleared`() {
        val id = create("Wiki", "  the team's Confluence, read only  ")
        assertThat(read(id)).isEqualTo("the team's Confluence, read only")

        update(id, """, description: "the support space only"""").entity(String::class.java).isEqualTo("the support space only")
        update(id, "").entity(String::class.java).isEqualTo("the support space only")
        update(id, """, description: "   """").valueIsNull()
        assertThat(servers.findById(id.toLong()).orElseThrow().description).isNull()
    }

    @Test
    fun `an agent holding a described server is told what it is for, and nothing of one undescribed`() {
        create("Team Wiki", "the team's Confluence, read only")
        create("Search", null)
        val agent = Agent(
            workspaceId = workspaceId, name = "Helper", type = AgentType.LLM,
            mcpServers = mutableListOf("Team Wiki", "Search"),
        )

        val said = briefing.of(agent).orEmpty()
        assertThat(said).contains("- team_wiki__ (Team Wiki): the team's Confluence, read only")
        assertThat(said).doesNotContain("(Search)")
        assertThat(briefing.of(Agent(workspaceId = workspaceId, name = "Other", type = AgentType.LLM)).orEmpty())
            .doesNotContain("MCP servers you hold")
    }
}
