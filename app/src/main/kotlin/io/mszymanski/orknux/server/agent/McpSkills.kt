package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.connector.connection.McpAnswer
import io.mszymanski.orknux.connector.connection.McpClient
import io.mszymanski.orknux.connector.connection.McpServerRepository
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service

/**
 * The prompts an agent's MCP servers offer, as skills. Issue #617.
 *
 * An MCP prompt is instructions a server hands out by name for a client to
 * start from - which is what a skill is here, a page an agent reads before
 * doing something. So a granted server's prompts are a catalog of their own,
 * `<server>_mcp`, and reach the agent through `skill_list` and `skill_load`
 * like a plugin's: nothing downstream learns a fourth kind of skill exists.
 *
 * **Granted with the server**, rather than as a catalog on its own line. The
 * server grant already says this agent may talk to it, and a second switch for
 * the same server would be a way to grant half of it by accident. Hiding one
 * prompt, or making it Always, works by its id like any other skill.
 *
 * **Read when loaded, not when listed.** A prompt's page is produced by the
 * server, and may take arguments; fetching every page to draw a list of names
 * would be a round trip per prompt per turn for text nobody asked to see.
 */
@Service
class McpSkills(
    private val tools: McpToolCaller,
    private val client: McpClient,
    private val servers: McpServerRepository,
) {

    /** The prompts of every server this agent was granted, one catalog per server. */
    fun granted(agent: Agent): List<GrantedSkill> = tools.granted(agent).flatMap { server ->
        when (val listed = client.prompts(server)) {
            is McpAnswer.Failed -> {
                log.warn("MCP server {} could not list its prompts: {}", server.name, listed.reason)
                emptyList()
            }

            is McpAnswer.Got -> listed.value.map { prompt ->
                GrantedSkill(
                    name = prompt.title ?: prompt.name,
                    key = prompt.name,
                    description = described(prompt.description, prompt.arguments),
                    catalog = catalogOf(server),
                    content = "",
                    prompt = McpPromptSource(
                        serverId = requireNotNull(server.id),
                        server = server.name,
                        prompt = prompt.name,
                        arguments = prompt.arguments,
                    ),
                )
            }.sortedBy { it.name }
        }
    }

    /**
     * The page, from the server, filled in with [arguments].
     *
     * Throws with a sentence for the model when it cannot be had: a required
     * argument left out is named, so the next call can pass it.
     */
    fun read(source: McpPromptSource, arguments: Map<String, String>): String {
        val missing = source.arguments.filter { it.required && arguments[it.name].isNullOrBlank() }.map { it.name }
        require(missing.isEmpty()) {
            "${source.prompt} needs ${missing.joinToString(", ")}. Pass them in arguments, as a JSON object."
        }
        val server = servers.findByIdOrNull(source.serverId)
            ?: throw IllegalStateException("${source.server} is no longer one of this workspace's MCP servers")
        return when (val got = client.prompt(server, source.prompt, arguments)) {
            is McpAnswer.Got -> got.value
            is McpAnswer.Failed -> throw IllegalStateException(got.reason)
        }
    }

    /** The server's description, and what the prompt takes, where it takes anything. */
    private fun described(said: String, arguments: List<io.mszymanski.orknux.connector.connection.McpParameter>): String? {
        val takes = arguments.joinToString(", ") { if (it.required) it.name else "${it.name} (optional)" }
        return listOf(said.trim(), if (takes.isEmpty()) "" else "Takes arguments: $takes.")
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifEmpty { null }
    }

    companion object {
        private val log = LoggerFactory.getLogger(McpSkills::class.java)

        /**
         * `brave_search_mcp` - suffixed like a plugin's catalog, and for the same
         * reason: a bare server name could be a folder the workspace already has.
         */
        fun catalogOf(server: io.mszymanski.orknux.connector.connection.McpServer): String =
            McpToolCaller.slug(server) + "_mcp"
    }
}
