package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.server.security.WebProperties
import io.mszymanski.orknux.server.security.InstallationAddress
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI

/**
 * The item a tool call names, as a link the chat draws on the call's line.
 * Issue #660.
 *
 * Read off what the tool answered rather than off a list of tools: every
 * `orknux_*` answer that names something with a page carries that page's
 * address in a top-level `url` - a run started, a research, an issue filed, a
 * task - so the address is the item, and a tool added later is linked the day it
 * answers with one. Only the top level: a listing answers with many addresses
 * inside it and names none of them, and a line with forty links is a listing.
 *
 * Only this installation's own pages. The answer's address is absolute, because
 * it is handed to a model that may paste it anywhere, so it is kept when it is
 * on this installation's address (or is already a path) and comes back as the
 * path, which is what the browser follows without leaving the page. A web
 * search's `url` or a box port's is somebody else's page, and is left alone.
 *
 * An MCP tool answers `{"result":"<its JSON, as text>"}`, so a string `result`
 * that is JSON is read the same way.
 */
@Component
class ItemLinks(
    private val address: InstallationAddress,
    private val web: WebProperties,
) {

    /** The link, or null where the answer names no page of this installation. */
    fun of(result: String?): ItemLink? = of(result, base())

    private fun base(): String = address.base().ifEmpty { web.allowedOrigins.firstOrNull()?.trimEnd('/').orEmpty() }

    companion object {
        private val reader = ObjectMapper()

        /** A page of a workspace, which is what an item with a page has. */
        private val PAGE = Regex("^/workspace/\\d+/.+")

        /** The fields a name is read from, in the order an answer means them. */
        private val NAMES = listOf("title", "name", "workflow", "created", "updated", "agent")

        fun of(result: String?, base: String): ItemLink? {
            if (result.isNullOrBlank() || result.length > LONGEST_READ) return null
            val first = result.firstOrNull { !it.isWhitespace() }
            if (first != '{') return null
            val tree = runCatching { reader.readTree(result) }.getOrNull() ?: return null
            if (!tree.isObject) return null
            val url = tree.path("url")
            if (!url.isString) {
                // The MCP envelope: one string field holding the tool's own JSON.
                val inner = tree.path("result")
                return if (tree.size() == 1 && inner.isString) of(inner.stringValue(), base) else null
            }
            val path = pathOf(url.stringValue(), base) ?: return null
            return ItemLink(path, nameOf(tree))
        }

        /** The address as a path on this installation, or null where it is not one. */
        private fun pathOf(url: String, base: String): String? {
            val path = when {
                url.startsWith("/") -> url
                base.isNotEmpty() && (url == base || url.startsWith("$base/")) -> url.removePrefix(base)
                else -> return null
            }
            val bare = runCatching { URI(path).path }.getOrNull() ?: return null
            return path.takeIf { PAGE.matches(bare) }
        }

        private fun nameOf(tree: JsonNode): String? =
            NAMES.asSequence()
                .map { tree.path(it) }
                .firstOrNull { it.isString && it.stringValue().isNotBlank() }
                ?.stringValue()
                ?.trim()

        /**
         * Longer than any answer that names one thing. A format rather than a
         * behaviour: past it the answer is a listing or a document, and parsing
         * a megabyte to find no top-level address is the only thing it would buy.
         */
        private const val LONGEST_READ = 200_000
    }
}

/** A page a call named: its path on this installation, and what the answer called it. */
data class ItemLink(val url: String, val name: String?)
