package io.mszymanski.orknux.server.mcp

import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

/** One tool the MCP endpoint can offer, as the Custom token form lists it. */
data class McpToolView(
    val name: String,
    /** Its phrase: the summary where one was written, its description's first sentence otherwise. */
    val summary: String,
    /** Whether it only reads; a Custom token chooses its reading and writing tools apart. */
    val readOnly: Boolean,
)

/**
 * Every tool the MCP endpoint can offer, for choosing a Custom token's.
 *
 * Read off the same declarations the endpoint answers tools/list with, under a
 * scope that may write, so a tool added to the surface is on the form the same
 * day. Any signed-in person may ask: they are names and phrases, the same for
 * everybody, and what a token may then do is still only what its owner may.
 */
@Controller
class McpToolCatalogAPI(
    private val tools: OrknuxTools,
    private val lent: McpLentTools,
    private val workspaces: io.mszymanski.orknux.server.workspace.WorkspaceRepository,
    private val access: io.mszymanski.orknux.server.security.WorkspaceAccess,
) {

    @QueryMapping
    fun mcpTools(): List<McpToolView> {
        // What reads is what a caller that may not write is offered: the surface decides by scope, not by a flag on each declaration.
        val reading = tools.specs(OrknuxScope(workspaceId = 0, mayWrite = false)).map { it.name }.toSet()
        // The lent tools reckon and draw and change nothing, so they read.
        val alsoReading = lent.specs().map { it.name }.toSet() + setOf("memory_search", "find_connections")
        // And each workspace's own reach that this person can see: its memories, connections, tools and servers.
        val reach = workspaces.findAll().filter { access.canSee(it) }.flatMap { workspace -> runCatching { lent.catalog(requireNotNull(workspace.id)) }.getOrDefault(emptyList()) }
        return (tools.specs(OrknuxScope(workspaceId = 0, mayWrite = true)) + lent.specs() + reach)
            .distinctBy { it.name }
            .map { spec -> McpToolView(spec.name, spec.summary ?: firstSentence(spec.description), spec.name in reading || spec.name in alsoReading || spec.readOnly) }
            .sortedWith(compareBy({ !it.readOnly }, { it.name }))
    }

    private fun firstSentence(said: String): String =
        said.trim().let { whole -> Regex("""^(.+?[.!?])(\s|$)""").find(whole)?.groupValues?.get(1) ?: whole }.take(200)
}
