package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.connector.connection.McpAnswer
import io.mszymanski.orknux.connector.connection.McpClient
import io.mszymanski.orknux.connector.connection.McpListing
import io.mszymanski.orknux.connector.connection.McpServer
import io.mszymanski.orknux.connector.connection.McpServerRepository
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.workflow.script.SessionScratch
import io.mszymanski.orknux.workflow.script.StoredKind
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * The tools an agent's MCP servers offer.
 *
 * Names are prefixed with the server — `brave-search__web_search` — because two
 * servers offering `search` is the ordinary case rather than the exceptional
 * one, and a call whose destination depends on which server was listed first is
 * not something anybody could debug.
 *
 * Listing asks the server, every time. A cache here would be a cache of what a
 * remote system can do, invalidated by events this process never sees; asking is
 * a round trip and being right is worth one.
 *
 * A server that cannot be reached contributes nothing rather than failing the
 * conversation. An agent whose search server is down should still be able to
 * answer from what it knows, and it will be told the tool is missing if it tries.
 *
 * **A server's resources are two more tools under its name.** Issue #617. MCP
 * means a resource to be chosen by the application rather than the model, but
 * an agent's loop has no application in it to do the choosing - the model is the
 * only reader there is - so it is handed a list and a read, the way it is handed
 * `skill_list` and `skill_load`. Offered only where the handshake advertised
 * `resources`, because a model is only ever offered tools that will run.
 */
@Service
class McpToolCaller(
    private val servers: McpServerRepository,
    private val client: McpClient,
    private val scratch: SessionScratch,
    private val mapper: ObjectMapper,
) {

    /** The servers this agent was granted, as the workspace names them. */
    fun granted(agent: Agent): List<McpServer> {
        if (agent.mcpServers.isEmpty()) return emptyList()
        val held = agent.mcpServers.toSet()
        return servers.findByWorkspaceId(agent.workspaceId, Sort.by("name")).filter { it.name in held }
    }

    fun specsFor(agent: Agent): List<ToolSpec> = granted(agent).flatMap { server ->
        when (val listing = client.tools(server)) {
            is McpListing.Failed -> {
                log.warn("MCP server {} could not be listed: {}", server.name, listing.reason)
                emptyList()
            }

            is McpListing.Tools -> {
                val own = listing.tools.map { tool ->
                    ToolSpec(
                        name = qualified(server, tool.name),
                        description = tool.description.ifBlank { "A tool offered by ${server.name}." },
                        parameters = tool.parameters.map {
                            ToolParameterSpec(it.name, it.description, it.required, it.schema)
                        },
                    )
                }
                // The server's own tool wins a name it already uses; the reading
                // pair is ours to give up, not its tool.
                val taken = listing.tools.map { it.name }.toSet()
                val reading = if (listing.resources) resourceSpecs(server).filterNot { it.first in taken } else emptyList()
                own + reading.map { it.second }
            }
        }
    }

    /** Whether this name belongs to one of the agent's servers, and which. */
    fun resolve(agent: Agent, name: String): Pair<McpServer, String>? = granted(agent)
        .firstOrNull { name.startsWith(prefix(it)) }
        ?.let { server -> server to name.removePrefix(prefix(server)) }

    /**
     * Calls one, whether the server's own or one of the two reading its
     * resources. A server tool that happens to share a reading tool's name was
     * offered under it instead (see [specsFor]), so it is asked about first.
     */
    fun call(server: McpServer, tool: String, arguments: String, sessionId: Long? = null): String = when (tool) {
        RESOURCES, READ_RESOURCE -> when (val listing = client.tools(server)) {
            is McpListing.Tools ->
                if (listing.tools.any { it.name == tool }) {
                    client.call(server, tool, arguments)
                } else if (tool == RESOURCES) {
                    listResources(server)
                } else {
                    readResource(server, arguments, sessionId)
                }
            is McpListing.Failed -> failure("${server.name} could not be asked: ${listing.reason}")
        }
        else -> client.call(server, tool, typed(server, tool, arguments))
    }

    /**
     * The arguments, with a value sent as the text of an array or an object
     * put back as one where the tool declares it so. Issue #619. Asks the
     * server for its tools only where some value is text that looks like
     * JSON, which is the only case there is anything to put back.
     */
    private fun typed(server: McpServer, tool: String, arguments: String): String {
        val held = runCatching { mapper.readTree(arguments) }.getOrNull() ?: return arguments
        val suspect = held.properties().any { (_, value) ->
            value.isTextual && value.stringValue().trimStart().let { it.startsWith("[") || it.startsWith("{") }
        }
        if (!suspect) return arguments
        val declared = (client.tools(server) as? McpListing.Tools)?.tools?.firstOrNull { it.name == tool } ?: return arguments
        return client.coerced(arguments, declared)
    }

    private fun listResources(server: McpServer): String = when (val listed = client.resources(server)) {
        is McpAnswer.Failed -> failure(listed.reason)
        is McpAnswer.Got -> mapper.writeValueAsString(
            mapOf(
                "resources" to listed.value.map { resource ->
                    buildMap {
                        put("uri", resource.uri)
                        put("name", resource.name)
                        if (resource.description.isNotBlank()) put("description", resource.description)
                        resource.mimeType?.let { put("mimeType", it) }
                    }
                },
            ),
        )
    }

    /**
     * One resource, read. Text is handed back as text; bytes go into the
     * session's store and the key comes back instead, because what makes bytes
     * answers a key - a model handed base64 reasons about it as though it were
     * the file, and every tool that uploads takes a key.
     */
    private fun readResource(server: McpServer, arguments: String, sessionId: Long?): String {
        val uri = runCatching { mapper.readTree(arguments).path("uri").stringValue() }.getOrNull()?.trim()
        if (uri.isNullOrEmpty()) return failure("Say which resource to read: pass its uri from ${prefix(server)}$RESOURCES.")

        return when (val read = client.readResource(server, uri)) {
            is McpAnswer.Failed -> failure(read.reason)
            is McpAnswer.Got -> mapper.writeValueAsString(
                mapOf(
                    "contents" to read.value.map { content ->
                        buildMap {
                            put("uri", content.uri)
                            content.mimeType?.let { put("mimeType", it) }
                            when {
                                content.text != null -> put("text", content.text)
                                content.blob != null && sessionId != null -> {
                                    val key = "resource." + java.lang.Long.toString(System.nanoTime(), 36)
                                    val refused = scratch.put(
                                        sessionId,
                                        key,
                                        mapper.writeValueAsString(content.blob),
                                        StoredKind(content.mimeType ?: "application/octet-stream", true),
                                    )
                                    if (refused == null) {
                                        put("key", key)
                                    } else {
                                        put("note", "The bytes could not be kept: $refused")
                                    }
                                }
                                content.blob != null ->
                                    put("note", "Bytes, which cannot be kept here because there is no session to keep them in.")
                                else -> put("note", "The server sent neither text nor bytes.")
                            }
                        }
                    },
                ),
            )
        }
    }

    private fun resourceSpecs(server: McpServer): List<Pair<String, ToolSpec>> = listOf(
        RESOURCES to ToolSpec(
            name = qualified(server, RESOURCES),
            description = "List the documents ${server.name} offers to be read: each one's uri, name and " +
                "what it is. Read one with ${qualified(server, READ_RESOURCE)}.",
            parameters = emptyList(),
        ),
        READ_RESOURCE to ToolSpec(
            name = qualified(server, READ_RESOURCE),
            description = "Read one document ${server.name} offers, by the uri " +
                "${qualified(server, RESOURCES)} gave it. Text comes back as text; anything else comes back as " +
                "a key to hand to a tool that takes one.",
            parameters = listOf(ToolParameterSpec("uri", "The resource's uri, copied as it was listed.", true)),
        ),
    )

    private fun failure(reason: String): String = mapper.writeValueAsString(mapOf("error" to reason))

    /**
     * `server__tool`, with anything a model cannot reliably send stripped out of
     * the server's name — both request shapes want a plain identifier.
     */
    private fun qualified(server: McpServer, tool: String): String = prefix(server) + tool

    private fun prefix(server: McpServer): String = slug(server) + "__"

    companion object {
        /** The two tools a server's resources are read through. Issue #617. */
        const val RESOURCES = "list_resources"
        const val READ_RESOURCE = "read_resource"

        private val UNSAFE = Regex("[^a-z0-9_-]+")
        private val log = LoggerFactory.getLogger(McpToolCaller::class.java)

        /** A server's name as a plain identifier: what its tools and its prompts' catalog are named by. */
        fun slug(server: McpServer): String = server.name.lowercase().replace(UNSAFE, "_").trim('_')
    }
}
