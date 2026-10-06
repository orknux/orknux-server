package io.mszymanski.orknux.server.embedded

import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.workflow.script.ScriptResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Looking something up. Issue #510.
 *
 * A model asked about anything after its training cut-off has two honest moves:
 * say it does not know, or look. Looking is not an integration with somebody
 * else's system - it is a thing the product should be able to do - so it is
 * here rather than in a plugin.
 *
 * **Which index is the workspace's choice**, with tavily as the default,
 * because the point of the results is a model reading them and tavily returns
 * cleaned, quotable content where a search page returns its marketing fragment.
 * Brave is the independent index, cheaper per query, for a workspace that wants
 * it. See [SearchSettings].
 *
 * **The key never passes through the model.** The server makes the call and the
 * key is read from an encrypted column on the way out; nothing above this holds
 * it, nothing logs it, and the screen that sets it is told only that one is set.
 *
 * **A picture's address goes straight to whatever uploads it.** `searchImages`
 * answers the url rather than the bytes, so a picture reaches a channel without
 * a megabyte of base64 travelling through a model on the way.
 */
@Component
class SearchCapability(
    private val settings: SearchSettings,
    private val router: ProxyRouter,
    private val mapper: ObjectMapper,
) : EmbeddedCapability {

    private val log = LoggerFactory.getLogger(javaClass)

    override val key = "web"
    override val name = "Web search"

    override fun tools(): List<EmbeddedTool> = listOf(
        EmbeddedTool(
            name = SEARCH,
            summary = "Searches the web and answers readable results.",
            description = "Searches the web and answers title, url and a readable snippet per result. Use it " +
                "for anything that happened after you were trained, anything that changes - a price, a " +
                "version, who holds an office - and anything you would otherwise be guessing at. Saying you " +
                "do not know is the only honest alternative.",
            params = listOf(
                EmbeddedParam(QUERY, ValueType.STRING, "What to search for.", required = true),
                EmbeddedParam(LIMIT, ValueType.NUMBER, "How many results, up to $MOST_RESULTS."),
            ),
        ),
        EmbeddedTool(
            name = SEARCH_IMAGES,
            summary = "Searches for pictures and answers their addresses.",
            description = "Searches for pictures and answers the image url, what it is, and the page it came " +
                "from. The url goes straight to whatever uploads a picture from an address, so the bytes " +
                "never travel through you.",
            params = listOf(
                EmbeddedParam(QUERY, ValueType.STRING, "What to search for.", required = true),
                EmbeddedParam(LIMIT, ValueType.NUMBER, "How many pictures, up to $MOST_RESULTS."),
            ),
        ),
    )

    override fun functions(): List<EmbeddedFunction> = tools().map { tool ->
        EmbeddedFunction(tool.name, tool.description, ValueType.MAP, tool.params)
    }

    override fun run(name: String, arguments: String, workspaceId: Long, sessionId: Long?): String {
        val asked = runCatching { mapper.readTree(arguments) }.getOrNull()
            ?: return refusal("That is not valid JSON.")
        val query = asked.path(QUERY).takeIf { it.isTextual }?.stringValue()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return refusal("Give the $QUERY to search for.")
        val limit = asked.path(LIMIT).takeIf { it.isNumber }?.intValue()?.coerceIn(1, MOST_RESULTS) ?: SOME_RESULTS

        val engine = settings.engineOf(workspaceId)
        val key = settings.keyOf(workspaceId)
            ?: return refusal(
                "This workspace has no search key set. An administrator sets the engine and its key under " +
                    "the workspace's Search settings.",
            )

        val pictures = when (name) {
            SEARCH -> false
            SEARCH_IMAGES -> true
            else -> return refusal("There is no tool called web_$name.")
        }

        return try {
            when (engine) {
                SearchEngine.TAVILY -> tavily(query, limit, key, pictures, settings.of(workspaceId).composeAnswer)
                SearchEngine.BRAVE -> brave(query, limit, key, pictures)
            }
        } catch (failure: Exception) {
            log.warn("A search did not finish: {}", failure.message)
            refusal("that search did not finish: " + (failure.message ?: "the index could not be reached"))
        }
    }

    override fun call(name: String, arguments: List<String>, workspaceId: Long, sessionId: Long?): ScriptResult? {
        val declared = functions().firstOrNull { it.name == name } ?: return null
        val named = linkedMapOf<String, Any?>()
        declared.params.forEachIndexed { at, param ->
            val given = arguments.getOrNull(at)?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return@forEachIndexed
            val read = runCatching { mapper.readTree(given) }.getOrNull()
            named[param.name] = when {
                read != null && read.isNumber -> read.intValue()
                read != null && read.isTextual -> read.stringValue()
                else -> given
            }
        }
        val said = run(name, mapper.writeValueAsString(named), workspaceId, sessionId)
        runCatching { mapper.readTree(said) }.getOrNull()?.path("error")?.takeIf { it.isTextual }?.let {
            return ScriptResult.Failed(it.stringValue(), 0)
        }
        return ScriptResult.Returned(said, 0)
    }

    /* --------------------------------------------------------------- tavily */

    private fun tavily(query: String, limit: Int, key: String, pictures: Boolean, compose: Boolean): String {
        val asked = linkedMapOf<String, Any>(
            "query" to query,
            "max_results" to limit,
            "include_answer" to (compose && !pictures),
            "include_images" to pictures,
        )
        val answered = post("https://api.tavily.com/search", mapper.writeValueAsString(asked), mapOf(
            "authorization" to "Bearer $key",
            "content-type" to "application/json",
        ))
        val read = mapper.readTree(answered)

        if (pictures) {
            val images = read.path("images").take(limit).map { one ->
                linkedMapOf(
                    "url" to (one.takeIf { it.isTextual }?.stringValue() ?: one.path("url").takeIf { it.isTextual }?.stringValue()),
                    "title" to one.path("description").takeIf { it.isTextual }?.stringValue(),
                    "source" to null,
                )
            }
            return mapper.writeValueAsString(mapOf("images" to images))
        }

        val results = read.path("results").take(limit).map { one ->
            linkedMapOf(
                "title" to one.path("title").takeIf { it.isTextual }?.stringValue(),
                "url" to one.path("url").takeIf { it.isTextual }?.stringValue(),
                "snippet" to one.path("content").takeIf { it.isTextual }?.stringValue(),
            )
        }
        val answer = read.path("answer").takeIf { it.isTextual }?.stringValue()?.ifEmpty { null }
        return mapper.writeValueAsString(
            linkedMapOf("results" to results, "answer" to answer, "engine" to SearchEngine.TAVILY.asked),
        )
    }

    /* ---------------------------------------------------------------- brave */

    private fun brave(query: String, limit: Int, key: String, pictures: Boolean): String {
        val where = if (pictures) "images" else "web"
        val url = "https://api.search.brave.com/res/v1/$where/search?q=" +
            java.net.URLEncoder.encode(query, Charsets.UTF_8) + "&count=" + limit
        val answered = get(url, mapOf("x-subscription-token" to key, "accept" to "application/json"))
        val read = mapper.readTree(answered)

        if (pictures) {
            val images = read.path("results").take(limit).map { one ->
                linkedMapOf(
                    "url" to one.path("properties").path("url").stringValue(),
                    "title" to one.path("title").takeIf { it.isTextual }?.stringValue(),
                    "source" to one.path("url").takeIf { it.isTextual }?.stringValue(),
                    "thumbnail" to one.path("thumbnail").path("src").stringValue(),
                )
            }
            return mapper.writeValueAsString(mapOf("images" to images))
        }

        val results = read.path("web").path("results").take(limit).map { one ->
            linkedMapOf(
                "title" to one.path("title").takeIf { it.isTextual }?.stringValue(),
                "url" to one.path("url").takeIf { it.isTextual }?.stringValue(),
                "snippet" to one.path("description").takeIf { it.isTextual }?.stringValue(),
            )
        }
        return mapper.writeValueAsString(
            linkedMapOf("results" to results, "answer" to null, "engine" to SearchEngine.BRAVE.asked),
        )
    }

    /* ---------------------------------------------------------------- shared */

    private fun get(url: String, headers: Map<String, String>): String = sent(
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .GET()
            .build(),
    )

    private fun post(url: String, body: String, headers: Map<String, String>): String = sent(
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
    )

    /**
     * Through the installation's own router, so a search obeys the proxy rules
     * and trusted certificates like every other outbound call here.
     */
    private fun sent(request: HttpRequest): String {
        val answered = router.client()
            .send(router.authorized(request), HttpResponse.BodyHandlers.ofString())
        if (answered.statusCode() >= 400) {
            /*
             * The index's own complaint, not ours - a key that is wrong and a
             * quota that is spent say different things, and both are worth
             * passing on. The body is capped because a failure page is
             * sometimes an HTML document.
             */
            throw IllegalStateException(
                "the index answered " + answered.statusCode() + ": " + answered.body().orEmpty().take(MOST_REASON),
            )
        }
        return answered.body().orEmpty()
    }

    private fun JsonNode.stringValue(): String? = takeIf { it.isTextual }?.asText()

    private fun refusal(said: String): String = mapper.writeValueAsString(mapOf("error" to said))

    private companion object {
        const val SEARCH = "search"
        const val SEARCH_IMAGES = "searchImages"
        const val QUERY = "query"
        const val LIMIT = "limit"

        const val MOST_RESULTS = 20
        const val SOME_RESULTS = 5
        const val TIMEOUT_SECONDS = 20L
        const val MOST_REASON = 300
    }
}
