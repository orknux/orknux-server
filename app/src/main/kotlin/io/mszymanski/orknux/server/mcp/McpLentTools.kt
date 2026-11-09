package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.connector.connection.McpServerRepository
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.agent.McpToolCaller
import io.mszymanski.orknux.server.agent.PluginToolCaller
import io.mszymanski.orknux.server.chat.AgentTools
import io.mszymanski.orknux.server.chat.ConnectionTools
import io.mszymanski.orknux.server.memory.MemoryCatalogRepository
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import io.mszymanski.orknux.server.chat.FormatValidator
import io.mszymanski.orknux.server.chat.LinkTools
import io.mszymanski.orknux.server.embedded.EmbeddedCapabilities
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/**
 * The agents' own tools that the MCP endpoint offers as well, beside the
 * orknux_* surface: the ones that need a workspace and nothing else - no
 * agent's grants, no conversation of its own.
 *
 * MCP offered only what [OrknuxTools] declares, so a client reached the
 * workspace's records and none of the work an agent does with them: no
 * chart drawn, no date reckoned in the working calendar, no format checked.
 * Each is read off the declaration an agent gets, so the two cannot describe
 * one tool differently.
 *
 * And the workspace's own reach, which an agent holds by grant: its memories,
 * the MCP servers it has added, its connections, its own tools and the
 * plugins' tools. Over MCP there is no agent to have been granted anything, so
 * the caller is a stand-in holding everything of the workspace's - and the
 * person behind the token may already reach all of it on the screen, which is
 * what access to the workspace means. Run through [AgentTools], the one place
 * that runs them for an agent, so a tool answers here exactly as it does there.
 * A token that may not write is offered only what reads: memory_search, and the
 * tools that say they only read.
 *
 * Left out on purpose: tools that only mean something inside an agent's loop
 * (finishing a round, its own to-dos, timers that wake it), tools that keep
 * files in a conversation's store, and fetching from the web or searching it,
 * which a client does itself and through which a token would otherwise reach
 * this server's network.
 */
@Component
class McpLentTools(
    private val embedded: ObjectProvider<EmbeddedCapabilities>,
    private val validator: FormatValidator,
    private val links: LinkTools,
    private val agentTools: ObjectProvider<AgentTools>,
    private val memoryCatalogs: MemoryCatalogRepository,
    private val mcpServers: McpServerRepository,
    private val connections: WorkspaceConnectionRepository,
    private val workspaceTools: AgentToolRepository,
    private val pluginTools: PluginToolCaller,
    private val mcpTools: McpToolCaller,
) {

    /**
     * Who calls the workspace's tools over MCP: an agent nobody saved, holding
     * every memory catalog, MCP server, connection and tool the workspace has,
     * under the name of the person signed in - which is who a saved memory is
     * then by.
     */
    private fun standIn(workspaceId: Long, who: String, servers: Boolean = true): Agent =
        Agent(workspaceId = workspaceId, name = who, type = AgentType.LLM).apply {
            memoryCatalogs = this@McpLentTools.memoryCatalogs.findByWorkspaceIdOrderByNameAsc(workspaceId).map { it.name }.toMutableList()
            if (servers) mcpServers = this@McpLentTools.mcpServers.findByWorkspaceId(workspaceId, Sort.by("name")).map { it.name }.toMutableList()
            connections = this@McpLentTools.connections.findByWorkspaceId(workspaceId, Sort.by("name")).mapNotNull { it.id }.toMutableList()
            tools = (
                workspaceTools.findByWorkspaceId(workspaceId, Pageable.unpaged()).content.map { it.name } +
                    pluginTools.all().map { it.name } +
                    ConnectionTools.FIND
                ).distinct().toMutableList()
        }

    /**
     * Whether [name] is one of the workspace's own: a memory tool, a connection
     * lookup, how its MCP servers are doing, one of its servers' or tools'.
     */
    private fun reach(agent: Agent, name: String): Boolean =
        name.startsWith("memory_") || name == ConnectionTools.FIND || name == io.mszymanski.orknux.server.chat.McpStatusTools.STATUS ||
            name in agent.tools || mcpTools.resolve(agent, name) != null

    /** The workspace's own tools, as the stand-in is offered them, held to reading where the caller may not write. */
    private fun reaching(workspaceId: Long, who: String, mayWrite: Boolean): Pair<Agent, List<ToolSpec>> {
        val agent = standIn(workspaceId, who)
        val specs = agentTools.getObject().specsFor(agent)
            .filter { reach(agent, it.name) }
            .filter { mayWrite || it.readOnly || it.name == "memory_search" || it.name == ConnectionTools.FIND }
        return agent to specs
    }

    /** Whether [name] is one of the embedded tools lent: pure reckoning and drawing, answered inline. */
    private fun lentEmbedded(name: String): Boolean =
        name.startsWith("date_") || name in setOf("markdown_toText", "charts_render", "diagram_render")

    /**
     * For choosing a Custom token's tools: the workspace's own reach as names,
     * without asking its MCP servers - which is a call to each, at the moment a
     * form opens - so each server stands as one entry, `<server>__*`, for every
     * tool it offers now or later.
     */
    fun catalog(workspaceId: Long): List<ToolSpec> {
        val agent = standIn(workspaceId, "", servers = false)
        val own = agentTools.getObject().specsFor(agent).filter { reach(agent, it.name) }
        val servers = mcpServers.findByWorkspaceId(workspaceId, Sort.by("name")).map { server ->
            ToolSpec(name = McpToolCaller.slug(server) + "__*", description = "Every tool the MCP server ${server.name} offers.", readOnly = false)
        }
        return own + servers
    }

    /** What needs a workspace and nothing else: the same for every workspace and every caller. */
    fun specs(): List<ToolSpec> =
        embedded.getObject().toolSpecs().filter { lentEmbedded(it.name) } +
            AgentTools.VALIDATE_SPEC +
            links.shed().specs()

    /** Everything lent for [workspaceId]: the above, and the workspace's own reach as [who] may use it. */
    fun specs(workspaceId: Long, who: String, mayWrite: Boolean): List<ToolSpec> {
        val plain = specs()
        val taken = plain.map { it.name }.toSet()
        return plain + reaching(workspaceId, who, mayWrite).second.filterNot { it.name in taken }
    }

    fun handles(name: String): Boolean = specs().any { it.name == name }

    /**
     * Runs one lent tool. [sessionId] is the caller's MCP store ([McpFiles]),
     * so a tool that takes a contentKey reads a file the caller uploaded; null
     * where they have uploaded nothing.
     */
    fun run(workspaceId: Long, name: String, arguments: String, who: String = "", mayWrite: Boolean = false, sessionId: Long? = null): String = when {
        name == AgentTools.VALIDATE -> validator.check(arguments)
        name == LinkTools.LINK -> links.shed().run(ToolCall("mcp", name, arguments))
        lentEmbedded(name) -> embedded.getObject().run(name, arguments, workspaceId, sessionId = null)
        else -> {
            val (agent, offered) = reaching(workspaceId, who, mayWrite)
            if (offered.none { it.name == name }) {
                """{"error":"There is no tool called $name"}"""
            } else {
                agentTools.getObject().run(agent, ToolCall("mcp", name, arguments), sessionId = sessionId)
            }
        }
    }
}
