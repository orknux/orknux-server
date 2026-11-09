package io.mszymanski.orknux.server.transfer

import io.mszymanski.orknux.connector.connection.ConnectionType
import io.mszymanski.orknux.connector.connection.McpServer
import io.mszymanski.orknux.connector.connection.McpServerRepository
import io.mszymanski.orknux.connector.connection.WorkspaceConnection
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.agent.Chattiness
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * An agent's grants survive a copy: the Duplicate button on its page, and a
 * whole workspace copied.
 *
 * The connections it may name and the agents it may ask are held by id, and
 * the export wrote neither, so every copy arrived without them; its required
 * tools, its hidden and required skills and how it works (rounds, tool
 * ceiling, memory share, chattiness, effort) were not written either.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class AgentCopyGrantsTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val connections: WorkspaceConnectionRepository,
    @Autowired val mcpServers: McpServerRepository,
    @Autowired val duplicator: WorkspaceDuplicator,
) {

    /** A workspace with a connection, an MCP server, a tool, a colleague, and an agent granted all of them. */
    private fun granted(stamp: Long): Pair<Long, Long> {
        val ws = requireNotNull(workspaces.save(Workspace(name = "dup-grants-$stamp")).id)
        val slack = requireNotNull(
            connections.save(
                WorkspaceConnection(workspaceId = ws, name = "Slack outbound", type = ConnectionType.HTTP, url = "https://slack.invalid"),
            ).id,
        )
        mcpServers.save(McpServer(workspaceId = ws, name = "Jira MCP", address = "https://jira.invalid/mcp"))
        graphQlTester.document("""mutation { createTool(input: { workspaceId: $ws, name: "forecast" }) { id } }""")
            .execute().errors().verify()
        // Written before the agent that asks it, so a workspace copy has it in place.
        val helper = requireNotNull(agents.save(Agent(workspaceId = ws, name = "Helper", type = AgentType.LLM)).id)
        val agent = agents.save(
            Agent(
                workspaceId = ws, name = "Triage", type = AgentType.LLM,
                mcpServers = mutableListOf("Jira MCP"),
                connections = mutableListOf(slack),
                agents = mutableListOf(helper),
                tools = mutableListOf("forecast"),
                // An `orknux_*` tool and an MCP server's among them: neither is a component a copy carries.
                requiredTools = mutableListOf("forecast", "orknux_issues", "jira_search"),
                hiddenSkills = mutableListOf("escalating"),
                requiredSkills = mutableListOf("answering"),
                maxTools = 12, memoryShare = 30, maxRounds = 40, maxSubagentsAtOnce = 2,
                reasoningEffort = "high", chattiness = Chattiness.NONE,
            ),
        )
        return ws to requireNotNull(agent.id)
    }

    private fun assertGrantsCarried(copy: Agent, ws: Long) {
        assertThat(copy.mcpServers).containsExactly("Jira MCP")
        assertThat(copy.connections).containsExactly(connections.findByWorkspaceIdAndName(ws, "Slack outbound")?.id)
        assertThat(copy.agents).containsExactly(agents.findByWorkspaceIdAndName(ws, "Helper")?.id)
        assertThat(copy.tools).contains("forecast")
        assertThat(copy.requiredTools).containsExactly("forecast", "orknux_issues", "jira_search")
        assertThat(copy.hiddenSkills).containsExactly("escalating")
        assertThat(copy.requiredSkills).containsExactly("answering")
        assertThat(copy.maxTools).isEqualTo(12)
        assertThat(copy.memoryShare).isEqualTo(30)
        assertThat(copy.maxRounds).isEqualTo(40)
        assertThat(copy.maxSubagentsAtOnce).isEqualTo(2)
        assertThat(copy.reasoningEffort).isEqualTo("high")
        assertThat(copy.chattiness).isEqualTo(Chattiness.NONE)
    }

    @Test
    fun `a duplicated agent keeps its connection, MCP server and agent grants`() {
        val (ws, id) = granted(System.nanoTime())

        graphQlTester.document("""mutation { duplicateComponent(workspaceId: $ws, kind: AGENT, id: $id) { name } }""")
            .execute().path("duplicateComponent.name").entity(String::class.java).isEqualTo("Triage (2)")

        assertGrantsCarried(requireNotNull(agents.findByWorkspaceIdAndName(ws, "Triage (2)")), ws)
    }

    @Test
    fun `an agent in a copied workspace keeps its grants, pointed at the copy's rows`() {
        val stamp = System.nanoTime()
        val (source, _) = granted(stamp)

        val into = duplicator.duplicate(source, "dup-grants-copy-$stamp", "alice").workspaceId

        val copy = requireNotNull(agents.findByWorkspaceIdAndName(into, "Triage"))
        assertGrantsCarried(copy, into)
        // The copy's own rows, never the original's.
        assertThat(copy.connections).doesNotContain(connections.findByWorkspaceIdAndName(source, "Slack outbound")?.id)
        assertThat(copy.agents).doesNotContain(agents.findByWorkspaceIdAndName(source, "Helper")?.id)
    }

    /**
     * Every field of an agent is decided on: carried by the export and import,
     * or left on purpose. The copy is written field by field, so a field added
     * to [Agent] and not listed here fails until somebody decides.
     */
    @Test
    fun `every field of an agent is either carried or left on purpose`() {
        val fields = Agent::class.java.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) && !it.isSynthetic && !it.name.startsWith("$") }
            .map { it.name }.toSet()
        val carried = setOf(
            "name", "type", "description", "systemPrompt", "enabled", "modelId", "memoryShare", "maxRounds",
            "maxSubagentsAtOnce", "reasoningEffort", "chattiness", "maxTools", "orknuxAccess", "mayAskForTools",
            "boxLabelsText", "mcpServers", "memoryCatalogs", "skillCatalogs", "secretCatalogs", "variableCatalogs",
            "hiddenSkills", "requiredSkills", "tools", "requiredTools", "hiddenTools", "delegatedTools",
            "creatableModelIds", "connections", "agents", "icon",
        )
        // Who and when, and what made a temporary agent: about the original, not the copy.
        val left = setOf("id", "workspaceId", "temporary", "createdBySessionId", "createdByAgentId", "lastModifiedAt", "lastModifiedBy")
        assertThat(fields - carried - left).describedAs("fields of Agent the transfer has not decided on").isEmpty()
    }
}
