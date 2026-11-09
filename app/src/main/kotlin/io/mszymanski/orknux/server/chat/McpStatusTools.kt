package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.connection.McpServerService
import io.mszymanski.orknux.connector.connection.McpServerView
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.agent.Agent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * How the workspace's MCP servers are doing: the line the MCP servers page
 * draws for each, read by an agent.
 *
 * ### Whatever the agent was granted
 *
 * An agent whose MCP tool stopped answering, or whose server's tools never
 * arrived, could only say it did not have them - it could not tell a server
 * that is down from one it was never given, and the person asking "is the wiki
 * server up?" got a guess. So this lists every server of the workspace, and
 * says beside each whether this agent holds it. Seeing a server's state is not
 * using it: the tools themselves stay behind the grant, and an agent learning
 * that a server it does not hold is up has learnt only what to ask for.
 *
 * Still a row on the Tools list, like every built-in - on for every agent, and
 * hidden there by whoever does not want it said. "Regardless of grant" is about
 * the MCP servers it reports on, not about the switch.
 *
 * ### What it never says
 *
 * No credential, no header, and of the address only the host: an MCP address
 * may carry a key in its query, and a header is where a token usually goes.
 * Whether the server has a credential at all is said, because "it has none" is
 * the commonest reason for a 401 and is not itself a secret.
 *
 * ### Checking now
 *
 * The state is the last check's, kept by the page and the sweep. Asked to,
 * it checks again - the same handshake and `tools/list` the page's Check
 * button makes, which records its answer for the page too - so a stale
 * "reachable" from yesterday is not the only thing an agent can go on.
 *
 * Bounded, because the tool reads and is offered as reading - in plan mode,
 * and to a read-only MCP token: a check is made of one server, the one the
 * name picks out. Unbounded it was a call to every server in the workspace,
 * one after another, each with a timeout of its own, from one word of a
 * model's. What a check writes is the
 * status line the sweep writes on its timer - nothing anybody configured - which
 * is why checking is still reading.
 */
@Service
class McpStatusTools(
    private val servers: McpServerService,
    private val mapper: ObjectMapper,
) {

    val spec: ToolSpec = ToolSpec(
        name = STATUS,
        readOnly = true,
        description = "Says the status of this workspace's MCP servers - every one of them, not only those you " +
            "were given: whether each was reachable at its last check, when that was, what the check said, and " +
            "how many tools it offered - and whether you hold it. Use it when an MCP tool fails or seems to be " +
            "missing, or when asked whether a server is up. A server you do not hold is one you cannot call; " +
            "say so, or ask for it, rather than calling it. Name a server and pass check to check it again now " +
            "instead of reading the last result.",
        parameters = listOf(
            ToolParameterSpec(
                name = NAME,
                description = "One server's name, or part of it. Left out, every server is listed.",
                required = false,
            ),
            ToolParameterSpec(
                name = CHECK,
                description = "True to check the named server again now - a call to it - rather than report " +
                    "the last check. Needs a name that picks out one server; otherwise the last results are reported.",
                required = false,
                schema = mapOf("type" to "boolean"),
            ),
        ),
    )

    fun handles(name: String): Boolean = name == STATUS

    fun run(agent: Agent, arguments: String): String {
        val args = runCatching { mapper.readTree(arguments.ifBlank { "{}" }) }.getOrNull()
        val asked = args?.path(NAME)?.takeIf { it.isString }?.stringValue()?.trim().orEmpty()
        // A model writes "true" as a string often enough to take it.
        val checkNow = args?.path(CHECK)?.let { (it.isBoolean && it.booleanValue()) || (it.isString && it.stringValue().trim() == "true") } ?: false

        val all = servers.mcpServers(agent.workspaceId)
        if (all.isEmpty()) {
            return mapper.writeValueAsString(
                mapOf(
                    "servers" to emptyList<Any>(),
                    "note" to "This workspace has no MCP servers. They are added under Integrations > MCP servers.",
                ),
            )
        }
        val matching = if (asked.isEmpty()) all else all.filter { it.name.contains(asked, ignoreCase = true) }
        if (matching.isEmpty()) {
            return mapper.writeValueAsString(
                mapOf(
                    "servers" to emptyList<Any>(),
                    "note" to "No MCP server here is called \"$asked\". The workspace has: ${all.joinToString(", ") { it.name }}.",
                ),
            )
        }
        // One server, the one the name picks out: see the class's note on checking.
        // "Wiki" beside "Wiki archive" is still one server named: the exact name wins over the part.
        val named = matching.singleOrNull { it.name.equals(asked, ignoreCase = true) } ?: matching.singleOrNull()
        val checked = named?.takeIf { checkNow && asked.isNotEmpty() }?.id
        val now = matching.map { server ->
            if (server.id != checked) return@map server
            // A check that throws is said on the server's line, not as the tool failing.
            runCatching { servers.checkMcpServer(server.id) }.onFailure { why ->
                log.info("Checking MCP server {} for agent {} failed: {}", server.name, agent.name, why.message)
            }
            servers.mcpServer(server.id) ?: server
        }
        val answer = linkedMapOf<String, Any?>("servers" to now.map { line(agent, it) })
        if (checkNow && checked == null) {
            answer["note"] = "Nothing was checked: these are the last results. A check is of one server - " +
                "pass a name that picks out exactly one."
        }
        return mapper.writeValueAsString(answer)
    }

    private fun line(agent: Agent, server: McpServerView): Map<String, Any?> = linkedMapOf(
        "name" to server.name,
        "description" to server.description,
        "host" to host(server.address),
        "youHoldIt" to (server.name in agent.mcpServers),
        "state" to when (server.reachable) {
            true -> "reachable"
            false -> "unreachable"
            null -> "not checked yet"
        },
        "lastCheckedAt" to server.lastCheckedAt,
        "checkSaid" to server.checkDetail,
        "toolCount" to server.toolCount,
        "credential" to when {
            server.secretVariableMissing -> "the workspace secret it reads is gone"
            server.secretSet || server.secretVariableId != null -> "set"
            else -> "none"
        },
    )

    /** The host out of an address, never its path or query, where a key may be. */
    private fun host(address: String): String? = runCatching { java.net.URI(address).host }.getOrNull()

    companion object {
        const val STATUS = "mcp_status"
        const val NAME = "name"
        const val CHECK = "check"

        private val log = LoggerFactory.getLogger(McpStatusTools::class.java)
    }
}
