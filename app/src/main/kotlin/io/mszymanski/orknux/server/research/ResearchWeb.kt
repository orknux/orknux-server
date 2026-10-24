package io.mszymanski.orknux.server.research

import io.mszymanski.orknux.server.embedded.HttpCapability
import io.mszymanski.orknux.server.embedded.SearchCapability
import org.jsoup.Jsoup
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/** One thing a search found. */
data class SearchHit(val title: String, val url: String, val snippet: String)

/** What reading one address came to: its text, or why there is none. */
sealed interface PageRead {
    data class Read(val title: String?, val text: String) : PageRead
    data class Unread(val reason: String) : PageRead
}

/**
 * The web as a research reaches it. Issue #659.
 *
 * An interface for one reason: what is worth testing about a research is the
 * pipeline - what it plans, searches, reads, notes and writes - and a test of
 * that must not depend on a search engine's key or the network. The one the
 * server runs is [WorkspaceResearchWeb], which is the same two doors an agent
 * has: the workspace's own search, and the HTTP tools, with the installation's
 * switch and proxy rules in front of them.
 */
interface ResearchWeb {

    /** The workspace's search; empty, with the reason, where it has none. */
    fun search(workspaceId: Long, query: String, limit: Int): Result<List<SearchHit>>

    /** One page as text. */
    fun read(workspaceId: Long, url: String): PageRead
}

@Component
class WorkspaceResearchWeb(
    private val search: SearchCapability,
    private val http: HttpCapability,
    private val mapper: ObjectMapper,
) : ResearchWeb {

    override fun search(workspaceId: Long, query: String, limit: Int): Result<List<SearchHit>> {
        val said = search.run(SEARCH, mapper.writeValueAsString(mapOf("query" to query, "limit" to limit)), workspaceId, null)
        val read = runCatching { mapper.readTree(said) }.getOrNull()
            ?: return Result.failure(IllegalStateException("the search answered something that is not JSON"))
        read.path("error").takeIf { it.isTextual }?.let { return Result.failure(IllegalStateException(it.stringValue())) }
        return Result.success(
            read.path("results").mapNotNull { one ->
                val url = one.path("url").takeIf { it.isTextual }?.stringValue() ?: return@mapNotNull null
                SearchHit(
                    title = one.path("title").takeIf { it.isTextual }?.stringValue() ?: url,
                    url = url,
                    snippet = one.path("snippet").takeIf { it.isTextual }?.stringValue().orEmpty(),
                )
            },
        )
    }

    override fun read(workspaceId: Long, url: String): PageRead {
        val said = http.run(GET, mapper.writeValueAsString(mapOf("url" to url)), workspaceId, null)
        val read = runCatching { mapper.readTree(said) }.getOrNull() ?: return PageRead.Unread("the answer was not JSON")
        read.path("error").takeIf { it.isTextual }?.let { return PageRead.Unread(it.stringValue()) }
        val body = read.path("body").takeIf { it.isTextual }?.stringValue() ?: return PageRead.Unread("the page had no body")
        val page = Jsoup.parse(body)
        return PageRead.Read(page.title().ifBlank { null }, page.body()?.text() ?: page.text())
    }

    private companion object {
        /** The tools' own names, without the capability's key - what `run` takes. */
        const val SEARCH = "search"
        const val GET = "get"
    }
}
