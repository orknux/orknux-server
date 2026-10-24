package io.mszymanski.orknux.server.research

import io.mszymanski.orknux.server.embedded.HttpCapability
import io.mszymanski.orknux.server.embedded.SearchCapability
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.Base64

/** One thing a search found. */
data class SearchHit(val title: String, val url: String, val snippet: String)

/** What reading one address came to: its text, or why there is none. */
sealed interface PageRead {
    /** @param images the pictures the page shows with words of its own: its `og:image`, and figures with a caption or alt text. */
    data class Read(val title: String?, val text: String, val images: List<ImageHit> = emptyList()) : PageRead
    data class Unread(val reason: String) : PageRead
}

/**
 * A picture somebody might place in a report: where it is, what it is said to
 * be, and the page it was found on or for.
 */
data class ImageHit(val url: String, val caption: String?, val page: String?)

/** What fetching one picture came to: its bytes and the type it was served as, or why there are none. */
sealed interface ImageFetched {
    class Fetched(val bytes: ByteArray, val contentType: String?) : ImageFetched
    data class Unfetched(val reason: String) : ImageFetched
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

    /** The workspace's picture search; empty, with the reason, where it has none. */
    fun searchImages(workspaceId: Long, query: String, limit: Int): Result<List<ImageHit>> = Result.success(emptyList())

    /** One picture's bytes, as served; whether they are a picture worth keeping is the pipeline's to decide. */
    fun image(workspaceId: Long, url: String): ImageFetched = ImageFetched.Unfetched("pictures are not fetched here")
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
        val page = Jsoup.parse(body, url)
        return PageRead.Read(page.title().ifBlank { null }, page.body()?.text() ?: page.text(), picturesOn(page, url))
    }

    override fun searchImages(workspaceId: Long, query: String, limit: Int): Result<List<ImageHit>> {
        val said = search.run(SEARCH_IMAGES, mapper.writeValueAsString(mapOf("query" to query, "limit" to limit)), workspaceId, null)
        val read = runCatching { mapper.readTree(said) }.getOrNull()
            ?: return Result.failure(IllegalStateException("the search answered something that is not JSON"))
        read.path("error").takeIf { it.isTextual }?.let { return Result.failure(IllegalStateException(it.stringValue())) }
        return Result.success(
            read.path("images").mapNotNull { one ->
                val url = one.path("url").takeIf { it.isTextual }?.stringValue()?.trim()?.ifEmpty { null } ?: return@mapNotNull null
                ImageHit(
                    url = url,
                    caption = one.path("title").takeIf { it.isTextual }?.stringValue(),
                    page = one.path("source").takeIf { it.isTextual }?.stringValue(),
                )
            },
        )
    }

    /**
     * Through the HTTP tools' download, so the installation's switch, its
     * hosts and its proxy rules stand in front of a picture as they do a page.
     */
    override fun image(workspaceId: Long, url: String): ImageFetched {
        val said = http.run(DOWNLOAD, mapper.writeValueAsString(mapOf("url" to url)), workspaceId, null)
        val read = runCatching { mapper.readTree(said) }.getOrNull() ?: return ImageFetched.Unfetched("the answer was not JSON")
        read.path("error").takeIf { it.isTextual }?.let { return ImageFetched.Unfetched(it.stringValue()) }
        val status = read.path("status").takeIf { it.isNumber }?.intValue() ?: 0
        if (status >= 400) return ImageFetched.Unfetched("the address answered $status")
        if (status in 300..399) return ImageFetched.Unfetched("the address answered $status, a redirect, which is not followed")
        val bytes = read.path("base64").takeIf { it.isTextual }
            ?.let { runCatching { Base64.getDecoder().decode(it.stringValue()) }.getOrNull() }
            ?: return ImageFetched.Unfetched("the answer had no body")
        return ImageFetched.Fetched(bytes, read.path("contentType").takeIf { it.isTextual }?.stringValue())
    }

    companion object {
        /** The tools' own names, without the capability's key - what `run` takes. */
        private const val SEARCH = "search"
        private const val SEARCH_IMAGES = "searchImages"
        private const val GET = "get"
        private const val DOWNLOAD = "download"

        /** The most a caption is kept to: words beside a picture, not a page of them. */
        const val MOST_CAPTION = 200

        /**
         * The pictures a page shows with words of its own, in the order they
         * are worth trying: the one the page names for itself (`og:image`),
         * then figures with a caption, then images with alt text. One with no
         * words is a picture nobody said anything about - a spacer, an icon, a
         * logo - and is left alone. Only http and https, made absolute against
         * the page's own address.
         */
        fun picturesOn(page: Document, url: String): List<ImageHit> {
            val found = linkedMapOf<String, ImageHit>()
            fun add(src: String?, caption: String?) {
                val at = src?.trim()?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) } ?: return
                val said = captionOf(caption) ?: return
                found.putIfAbsent(at, ImageHit(at, said, url))
            }
            page.select("meta[property=og:image], meta[name=og:image]").firstOrNull()?.let { meta ->
                val alt = page.select("meta[property=og:image:alt]").firstOrNull()?.attr("content")?.ifBlank { null }
                add(meta.absUrl("content").ifEmpty { meta.attr("content") }, alt ?: page.title())
            }
            page.select("figure img[src]").forEach { image ->
                val caption = image.closest("figure")?.selectFirst("figcaption")?.text()?.ifBlank { null }
                add(image.absUrl("src"), caption ?: image.attr("alt"))
            }
            page.select("img[src][alt]").forEach { image -> add(image.absUrl("src"), image.attr("alt")) }
            return found.values.toList()
        }

        /** Words said of a picture, on one line and no longer than a caption is; null where there are none. */
        fun captionOf(said: String?): String? =
            said?.replace(WHITESPACE, " ")?.trim()?.take(MOST_CAPTION)?.trim()?.ifEmpty { null }

        private val WHITESPACE = Regex("\\s+")
    }
}
