package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.connection.AuthType
import io.mszymanski.orknux.connector.connection.CreateMcpServerInput
import io.mszymanski.orknux.connector.connection.HttpHeaderInput
import io.mszymanski.orknux.connector.connection.McpServerRepository
import io.mszymanski.orknux.connector.connection.McpServerService
import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.Sort
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * `mcp_status`: how the workspace's MCP servers are doing, told to any agent
 * whatever MCP servers it holds.
 *
 * Pinned: it is offered to an agent granted no MCP server at all, and lists
 * every server with whether this agent holds it; it is a row on the Tools list,
 * at Offer, and refused where hidden; it says no credential, header or anything
 * of the address past its host; and asked to check, it checks - against a
 * `.invalid` host, so the answer is "unreachable" and no test reaches the
 * network.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class McpStatusToolsTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val tools: AgentTools,
    @Autowired val servers: McpServerService,
    @Autowired val serverRows: McpServerRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun make() {
        workspaceId = requireNotNull(
            (workspaces.findByName("mcp status") ?: workspaces.save(Workspace(name = "mcp status"))).id,
        )
        // Made afresh each time, so what is listed is exactly these two.
        serverRows.findByWorkspaceId(workspaceId, Sort.by("name")).forEach { serverRows.delete(it) }
        servers.createMcpServer(
            CreateMcpServerInput(
                workspaceId = workspaceId,
                name = "Wiki",
                address = "https://wiki.invalid/mcp?api_key=query-secret",
                description = "The team's wiki, read only",
                authType = AuthType.BEARER_TOKEN,
                secret = "bearer-secret",
                headers = listOf(HttpHeaderInput("X-Tenant", "header-secret")),
            ),
        )
        servers.createMcpServer(CreateMcpServerInput(workspaceId = workspaceId, name = "Search", address = "https://search.invalid/mcp"))
    }

    private fun agent(mcpServers: List<String> = emptyList(), hidden: List<String> = emptyList()) = Agent(
        workspaceId = workspaceId,
        name = "Status reader",
        type = AgentType.LLM,
        mcpServers = mcpServers.toMutableList(),
        hiddenTools = hidden.toMutableList(),
    )

    private fun ask(agent: Agent, arguments: String = "{}"): String =
        tools.run(agent, ToolCall("call-1", McpStatusTools.STATUS, arguments))

    private fun lines(answer: String): Map<String, JsonNode> =
        mapper.readTree(answer).path("servers").values().associateBy { it.path("name").asString() }

    @Test
    fun `an agent granted no MCP server is offered it, and told every server and which it holds`() {
        val none = agent()
        assertThat(tools.specsFor(none).map { it.name }).contains(McpStatusTools.STATUS)

        val told = lines(ask(agent(mcpServers = listOf("Wiki"))))
        assertThat(told.keys).containsExactlyInAnyOrder("Wiki", "Search")
        assertThat(told.getValue("Wiki").path("youHoldIt").asBoolean()).isTrue()
        assertThat(told.getValue("Search").path("youHoldIt").asBoolean()).isFalse()
        assertThat(told.getValue("Wiki").path("state").asString()).isEqualTo("not checked yet")
        assertThat(told.getValue("Wiki").path("host").asString()).isEqualTo("wiki.invalid")
        assertThat(told.getValue("Wiki").path("credential").asString()).isEqualTo("set")
        assertThat(told.getValue("Search").path("credential").asString()).isEqualTo("none")
        assertThat(told.getValue("Wiki").path("description").asString()).isEqualTo("The team's wiki, read only")
    }

    @Test
    fun `it says no credential, header or query`() {
        val answer = ask(agent(mcpServers = listOf("Wiki", "Search")))
        assertThat(answer).doesNotContain("bearer-secret", "header-secret", "query-secret", "api_key", "X-Tenant")
    }

    @Test
    fun `a name narrows it, and a name nothing matches is said with what there is`() {
        assertThat(lines(ask(agent(), """{"name":"wik"}""")).keys).containsExactly("Wiki")
        val nothing = mapper.readTree(ask(agent(), """{"name":"jira"}"""))
        assertThat(nothing.path("servers").isEmpty()).isTrue()
        assertThat(nothing.path("note").asString()).contains("Wiki").contains("Search")
    }

    @Test
    fun `asked to check, it checks, and the page sees the same answer`() {
        val checked = lines(ask(agent(), """{"name":"Search","check":true}""")).getValue("Search")
        assertThat(checked.path("state").asString()).isEqualTo("unreachable")
        assertThat(checked.path("lastCheckedAt").isNull()).isFalse()
        // Recorded, as the Check button records it: read without checking, it is still so.
        assertThat(lines(ask(agent(), """{"name":"Search"}""")).getValue("Search").path("state").asString())
            .isEqualTo("unreachable")
    }

    /**
     * The tool is offered as reading - in plan mode, to a read-only token - so
     * one word of a model's must not be a call to every server the workspace
     * has, each with a timeout of its own.
     */
    @Test
    fun `a check is of one named server, never of every server at once`() {
        servers.createMcpServer(CreateMcpServerInput(workspaceId = workspaceId, name = "Wiki archive", address = "https://old.invalid/mcp"))
        val unnamed = mapper.readTree(ask(agent(), """{"check":true}"""))
        assertThat(unnamed.path("note").asString()).contains("Nothing was checked")
        // Part of a name that two servers share picks out neither.
        val both = mapper.readTree(ask(agent(), """{"name":"wik","check":true}"""))
        assertThat(both.path("servers").size()).isEqualTo(2)
        assertThat(both.path("note").asString()).contains("Nothing was checked")
        assertThat(lines(ask(agent())).values.map { it.path("state").asString() }).containsOnly("not checked yet")

        // The exact name is one server even where it is part of another's.
        val exact = mapper.readTree(ask(agent(), """{"name":"wiki","check":true}"""))
        assertThat(exact.has("note")).isFalse()
        val after = lines(ask(agent()))
        assertThat(after.getValue("Wiki").path("state").asString()).isEqualTo("unreachable")
        assertThat(after.getValue("Wiki archive").path("state").asString()).isEqualTo("not checked yet")
    }

    @Test
    fun `it is a row on the Tools list, at Offer, and refused where hidden`() {
        assertThat(BuiltInTools.GRANTED).contains(McpStatusTools.STATUS)
        assertThat(BuiltInTools.STARTS_OFFERED).contains(McpStatusTools.STATUS)
        assertThat(BuiltInTools.startingAlways()).doesNotContain(McpStatusTools.STATUS)
        graphQlTester.document("{ builtInTools { name } }").execute()
            .path("builtInTools[*].name").entityList(String::class.java).contains(McpStatusTools.STATUS)

        val hiding = agent(hidden = listOf(McpStatusTools.STATUS))
        assertThat(tools.specsFor(hiding).map { it.name }).doesNotContain(McpStatusTools.STATUS)
        assertThat(ask(hiding)).contains("There is no tool called ${McpStatusTools.STATUS}")
    }
}
