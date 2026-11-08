package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.connector.proxy.ProxyRouter
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * One version of a plugin, as the marketplace remembers it.
 *
 * A listing answers with its whole history, not only what is current - which
 * is what lets a details pane say "this is the fourth release since March"
 * rather than showing one number and leaving somebody to wonder.
 *
 * [available] is the half worth reading before anything else. The marketplace
 * keeps the bytes of the ten newest releases and the record of every one, so a
 * version further back than that is real, nameable, and cannot be installed.
 * Saying so on the row is the difference between a disabled line and a failure
 * at the moment somebody pressed Install.
 */
data class MarketplaceRelease(
    val version: String,
    /** When this version first appeared, ISO-8601. It never moves again. */
    val published: String,
    /** When its bytes last changed; the same as [published] for almost all of them. */
    val replaced: String,
    /**
     * The SHA-256 of the plugin's main file at this version, lowercase hex.
     *
     * What makes a download checkable. Without it an install trusts whatever
     * arrived over the wire; with it the bytes can be held against what the
     * marketplace says it published.
     */
    val digest: String,
    /** How many files it shipped with, the plugin and its libraries together. */
    val files: Int,
    /** False for a release whose bytes are no longer held; see the note above. */
    val available: Boolean,

    /**
     * What changed in this version, as the author wrote it when publishing.
     *
     * On the release rather than in a list of its own. It was a `changelog`
     * map keyed by version for a few days, which is the wrong shape twice
     * over: an entry is *about* a release, and a map has to be matched back to
     * one by a string neither side parses. Empty for a release published
     * without any - most of them, before the publisher started sending them.
     */
    val notes: String = "",
)

/**
 * One plugin the marketplace offers, exactly as it answered.
 *
 * Whether it is installed here is not on it: that is this server's own fact,
 * folded in where the two are put together. See [MarketplaceListingView].
 */
data class MarketplaceOffering(
    val key: String,
    val name: String,
    val author: String,
    val summary: String,
    val description: String,
    val version: String,
    val url: String,
    /** The glyph for a light ground, as an address. */
    val icon: String?,
    /**
     * The same glyph in white, for a dark ground, or null where the plugin
     * ships one icon for both.
     *
     * Two files rather than one that adapts, and the reason is worth keeping:
     * an SVG loaded through `<img>` is its own document, inherits no colour
     * from the page around it, and resolves `currentColor` to black - which on
     * a dark screen is a square of nothing. So each file carries real colours.
     */
    val iconDark: String?,
    val downloads: Int,
    val rating: Double?,
    val reviews: Int,
    val published: String,
    /**
     * What the plugin is for, in its author's own words - `chat`, `files`,
     * `search`. Empty for one that said nothing, never null.
     *
     * A list rather than one word, because a plugin is usually more than one
     * thing: the Slack plugin is chat, and files, and search, and filing it
     * under whichever of those somebody picked hid it from the readers looking
     * for the other two. Free text and lowercase on the marketplace's side, so
     * these are shown and filtered by and never matched against an installed
     * row.
     */
    val tags: List<String> = emptyList(),
    /**
     * Every release, newest first, or empty from a marketplace that does not
     * answer with them.
     *
     * Empty rather than absent on purpose: an older marketplace simply has no
     * such field, and a screen that reads this should draw a listing without a
     * history rather than refuse to draw one at all.
     */
    val versions: List<MarketplaceRelease> = emptyList(),
) {
    /**
     * The release this listing's `version` names, where the history holds it.
     *
     * What a caller actually wants when it asks about the current version -
     * its digest, and whether its bytes are still there - without having to
     * know that the two facts arrive in different shapes.
     */
    val current: MarketplaceRelease? get() = versions.firstOrNull { it.version == version }
}

/**
 * The marketplace, read through.
 *
 * This server holds no catalog: it asks the marketplace what is on offer and
 * answers with what came back. A marketplace that cannot be reached is a
 * refusal rather than an empty list — "nothing is offered" is not something a
 * network failure may claim, and a catalog screen that silently emptied would
 * be the worst way to say the site is down.
 *
 * The call goes through [ProxyRouter.builder], like every other outbound call
 * on this server, so an installation's proxy rules and trusted certificates
 * govern where it may reach. An installation with no marketplace configured
 * says so in a sentence rather than failing to start.
 */
@Component
class Marketplace(
    private val mapper: ObjectMapper,
    /** What this installation says to be answered at all; both doors want it. */
    private val installKey: MarketplaceInstallKey,
    proxies: ProxyRouter,
    /** Where the marketplace's GraphQL lives; empty means this installation has none. */
    @Value("\${orknux.marketplace.url:https://orknux.ai/graphql}") private val endpoint: String,
) {

    private val http: HttpClient = proxies.builder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    /** Whether this installation has a marketplace at all, for a screen to ask. */
    val configured: Boolean get() = endpoint.isNotBlank()

    /**
     * What the marketplace offers, asked afresh every time.
     *
     * Nothing is cached on either side of this call, so there is nothing for a
     * refresh flag to bypass — and one was sent for a while, which the
     * marketplace rightly refused as an argument it does not have. Asking
     * again is the whole of refreshing.
     */
    fun offerings(): List<MarketplaceOffering> {
        val answer = askedForFields { "query Catalog { marketplacePlugins { $it } }" to emptyMap<String, Any?>() }
        return answer.path("data").path("marketplacePlugins").values().map(::read)
    }

    /** One offering by key, or null where the catalog has none by that name. */
    fun offering(key: String): MarketplaceOffering? {
        val answer = askedForFields { fields ->
            """
            query Offering(${'$'}key: String!) {
              marketplacePlugin(key: ${'$'}key) { $fields }
            }
            """.trimIndent() to mapOf("key" to key)
        }
        val found = answer.path("data").path("marketplacePlugin")
        return if (found.isNull || found.isMissingNode) null else read(found)
    }

    /**
     * The server releases orknux.ai offers after [after], newest first. Issue #584.
     *
     * Null where the far end has never heard of `serverReleases` - a
     * marketplace older than the feature, or one an installation points at
     * that only lists plugins. That is "nothing offered yet", not a failure,
     * and the Updates screen says it in those words; anything else that goes
     * wrong is the usual [MarketplaceUnreachableException].
     */
    fun serverReleases(after: String?): List<OfferedServerRelease>? {
        for (fields in SERVER_RELEASE_LADDER) {
            try {
                val answer = asked(
                    """
                    query ServerReleases(${'$'}after: String) {
                      serverReleases(after: ${'$'}after) { $fields }
                    }
                    """.trimIndent(),
                    mapOf("after" to after),
                )
                return answer.path("data").path("serverReleases").values().map { node ->
                    OfferedServerRelease(
                        version = node.path("version").asString(""),
                        publishedAt = node.path("publishedAt").asString(""),
                        changelog = node.path("changelog").asString(""),
                        jarUrl = node.path("jarUrl").asString(""),
                        sha256 = node.path("sha256").asString("").lowercase(),
                        size = node.path("size").asLong(0),
                    )
                }.filter { it.version.isNotBlank() }.toList()
            } catch (refused: MarketplaceRefusedQueryException) {
                log.info("the marketplace refused the server release fields: {}", refused.message)
            }
        }
        return null
    }

    /**
     * The integrations orknux.ai recommends for a workspace, one page of them,
     * ordered by its own judgement of [context]. See
     * `docs/orknux-ai/integrations.graphql` for the contract.
     *
     * Null where the far end has never heard of `integrations` - a marketplace
     * older than the feature, or one an installation points at that lists only
     * plugins - which a screen says as "nothing recommended yet". Anything else
     * that goes wrong is the usual [MarketplaceUnreachableException].
     */
    /**
     * One page of the catalog, in [order] - orknux.ai's own when null - and,
     * for an order that has one, the direction [ascending] asks for.
     *
     * The direction is an argument orknux.ai added later, so it climbs down
     * like the fields do: asked for first, and where refused the page is asked
     * without it and turned round here. Turning round a page is exact while the
     * catalog fits on one, which it does; past that the far end has to know.
     */
    fun integrations(
        context: Map<String, Any?>,
        search: String?,
        tag: String?,
        after: String?,
        order: String? = null,
        ascending: Boolean? = null,
    ): OfferedIntegrationPage? {
        val filter = linkedMapOf<String, Any?>().apply {
            search?.takeIf { it.isNotBlank() }?.let { put("search", it) }
            tag?.takeIf { it.isNotBlank() }?.let { put("tag", it) }
        }.takeIf { it.isNotEmpty() }
        val directions = if (order != null && ascending != null) listOf(true, false) else listOf(false)
        for (sendDirection in directions) {
            for (fields in INTEGRATION_LADDER) {
                try {
                    val declared = buildString {
                        append("${'$'}context: RecommendationContext!, ${'$'}filter: IntegrationFilter, ${'$'}after: String")
                        if (order != null) append(", ${'$'}order: IntegrationOrder")
                        if (sendDirection) append(", ${'$'}ascending: Boolean")
                    }
                    val passed = buildString {
                        append("context: ${'$'}context, filter: ${'$'}filter, after: ${'$'}after")
                        if (order != null) append(", order: ${'$'}order")
                        if (sendDirection) append(", ascending: ${'$'}ascending")
                    }
                    val answer = asked(
                        """
                        query Integrations($declared) {
                          integrations($passed) {
                            total next items { reason installed integration { $fields } }
                          }
                        }
                        """.trimIndent(),
                        linkedMapOf<String, Any?>("context" to context, "filter" to filter, "after" to after).apply {
                            if (order != null) put("order", order)
                            if (sendDirection) put("ascending", ascending)
                        },
                    )
                    val page = answer.path("data").path("integrations")
                    val read = OfferedIntegrationPage(
                        items = page.path("items").values().mapNotNull { item ->
                            val integration = item.path("integration")
                            readIntegration(integration)?.let {
                                OfferedRecommendation(it, item.path("reason").stringValue(null), item.path("installed").asBoolean(false))
                            }
                        }.toList(),
                        next = page.path("next").stringValue(null),
                        total = page.path("total").asInt(0),
                    )
                    // Asked without the direction: each order's own one, turned round where the other was wanted.
                    val natural = order == "NAME"
                    if (sendDirection || ascending == null || ascending == natural) return read
                    // Cards with no count stay last when POPULAR is turned round, as orknux.ai itself orders them.
                    val (counted, uncounted) = read.items.partition { order != "POPULAR" || it.integration.popularity != null }
                    return read.copy(items = counted.reversed() + uncounted)
                } catch (refused: MarketplaceRefusedQueryException) {
                    log.info("the marketplace refused the integration query: {}", refused.message)
                }
            }
        }
        return null
    }

    /**
     * Where each integration's icons are, as the last listing said: what [icon]
     * may fetch. Only an address the marketplace itself named can be fetched,
     * so the door the page asks through cannot be pointed anywhere else.
     */
    private val icons = java.util.concurrent.ConcurrentHashMap<String, Pair<String?, String?>>()

    /** Icons already fetched, by key and shade: one picture per card is asked of orknux.ai once, not on every page. */
    private val iconCache = java.util.concurrent.ConcurrentHashMap<String, IntegrationIcon>()

    /**
     * The icon of integration [key], for a dark ground when [dark] and one is
     * offered, fetched by the server rather than the browser - which never talks
     * to orknux.ai, so an installation without it shows letters and a browser
     * sends orknux.ai nothing. Null where there is none, or what came back is
     * not a small picture.
     */
    fun icon(key: String, dark: Boolean): IntegrationIcon? {
        val (light, shaded) = icons[key] ?: return null
        val address = (if (dark) shaded ?: light else light ?: shaded) ?: return null
        iconCache[address]?.let { return it }
        val request = HttpRequest.newBuilder(URI.create(address)).timeout(Duration.ofSeconds(10)).GET().build()
        val answered = try {
            http.send(request, HttpResponse.BodyHandlers.ofByteArray())
        } catch (failed: Exception) {
            log.info("the icon of {} could not be fetched from {}: {}", key, address, failed.message)
            return null
        }
        val type = answered.headers().firstValue("Content-Type").orElse("").substringBefore(';').trim().lowercase()
        val bytes = answered.body()
        if (answered.statusCode() != 200 || type !in ICON_TYPES || bytes.isEmpty() || bytes.size > ICON_BYTES) {
            log.info("the icon of {} at {} was not used: {} {} bytes, status {}", key, address, type, bytes.size, answered.statusCode())
            return null
        }
        if (iconCache.size > ICON_CACHE) iconCache.clear()
        return IntegrationIcon(bytes, type).also { iconCache[address] = it }
    }

    /** An icon address worth fetching: https anywhere, or whatever scheme the marketplace's own host is reached by. */
    private fun iconAddress(address: String): Boolean {
        val asked = runCatching { URI.create(address) }.getOrNull() ?: return false
        if (asked.scheme == "https") return true
        val home = runCatching { URI.create(endpoint) }.getOrNull() ?: return false
        return asked.scheme == home.scheme && asked.host == home.host && asked.port == home.port
    }

    /** The tags orknux.ai files integrations under, or null from a marketplace without them. */
    fun integrationTags(): List<OfferedIntegrationTag>? = try {
        asked("query IntegrationTags { integrationTags { tag label count } }", emptyMap())
            .path("data").path("integrationTags").values().map {
                OfferedIntegrationTag(it.path("tag").asString(""), it.path("label").asString(""), it.path("count").asInt(0))
            }.filter { it.tag.isNotBlank() }.toList()
    } catch (refused: MarketplaceRefusedQueryException) {
        log.info("the marketplace refused integration tags: {}", refused.message)
        null
    }

    private fun readIntegration(node: tools.jackson.databind.JsonNode): OfferedIntegration? {
        val key = node.path("key").asString("").takeIf { it.isNotBlank() } ?: return null
        val setup = node.path("setup")
        fun text(field: String) = node.path(field).stringValue(null)?.takeIf { it.isNotBlank() }
        val icon = text("icon")?.takeIf(::iconAddress)
        val iconDark = text("iconDark")?.takeIf(::iconAddress)
        icons[key] = icon to iconDark
        return OfferedIntegration(
            icon = icon,
            iconDark = iconDark,
            key = key,
            name = node.path("name").asString(key),
            vendor = text("vendor"),
            summary = node.path("summary").asString(""),
            description = node.path("description").asString(""),
            tags = node.path("tags").values().map { it.asString("") }.filter { it.isNotBlank() }.toList(),
            homepage = text("homepage"),
            repository = text("repository"),
            license = text("license"),
            maintainer = text("maintainer"),
            popularity = node.path("popularity").takeIf { it.isNumber }?.asInt(),
            github = node.path("github").takeIf { it.isObject }?.let { repo ->
                val url = repo.path("url").stringValue(null)?.takeIf { it.startsWith("https://github.com/") }
                val name = repo.path("fullName").stringValue(null)?.takeIf { it.isNotBlank() }
                if (url == null || name == null) null else OfferedGitHubRepository(
                    fullName = name,
                    url = url,
                    stars = repo.path("stars").asInt(0),
                    archived = repo.path("archived").asBoolean(false),
                    checkedAt = repo.path("checkedAt").stringValue(null),
                )
            },
            kind = text("kind") ?: "MCP_SERVER",
            transport = text("transport"),
            runsOn = text("runsOn") ?: "ANYWHERE",
            sampleTools = node.path("sampleTools").values().map { it.asString("") }.filter { it.isNotBlank() }.toList(),
            minimumServer = text("minimumServer"),
            effort = setup.path("effort").asString("HOUR"),
            minutes = setup.path("minutes").asInt(0),
            requirements = setup.path("requirements").values().map { it.asString("") }.filter { it.isNotBlank() }.toList(),
            needsAdmin = setup.path("needsAdmin").asBoolean(false),
            steps = setup.path("steps").asString(""),
            claudePrompt = setup.path("claudePrompt").asString(""),
        )
    }

    /**
     * What fetching a server jar the catalog listed asks of the download: the
     * install key, sent to the marketplace's own host and to nothing a redirect
     * leads to - the jar used to follow redirects with the key attached - and
     * the marketplace's own sentences for a refusal. Issue #602: the download
     * itself runs in the background and resumes, see `ResumableDownload`.
     */
    fun serverJarRequest(
        url: String,
        maxBytes: Long,
        tooLarge: () -> RuntimeException,
    ): io.mszymanski.orknux.server.update.DownloadRequest {
        if (!configured) throw MarketplaceUnreachableException("this installation has no marketplace configured")
        if (installKey.today() == null) throw MarketplaceUnreachableException(MarketplaceInstallKey.MISSING)
        val start = runCatching { URI.create(url) }.getOrNull()
            ?.takeIf { it.scheme?.lowercase() in setOf("http", "https") && !it.host.isNullOrBlank() }
            ?: throw MarketplaceUnreachableException("it listed a jar at an address that is not one")
        return io.mszymanski.orknux.server.update.DownloadRequest(
            start = start,
            // Computed per request: the key is the day's, and a download can outlive a day.
            headersFor = { at -> if (installKey.own(at)) installKey.today()?.let { mapOf(MarketplaceInstallKey.HEADER to it) }.orEmpty() else emptyMap() },
            limit = maxBytes,
            tooLarge = tooLarge,
            refused = { status, at ->
                when {
                    status == 401 && installKey.own(at) -> MarketplaceUnreachableException(MarketplaceInstallKey.REFUSED)
                    status == 401 -> MarketplaceUnreachableException(installKey.elsewhere(at))
                    status == 404 -> MarketplaceUnreachableException("it does not have that jar any more (404)")
                    else -> MarketplaceUnreachableException("it answered $status for the jar")
                }
            },
        )
    }

    private fun read(node: tools.jackson.databind.JsonNode) = MarketplaceOffering(
        key = node.path("key").asString(""),
        name = node.path("name").asString(""),
        author = node.path("author").asString(""),
        summary = node.path("summary").asString(""),
        description = node.path("description").asString(""),
        version = node.path("version").asString(""),
        url = node.path("url").asString(""),
        icon = node.path("icon").asString("").ifEmpty { null },
        iconDark = node.path("iconDark").asString("").ifEmpty { null },
        downloads = node.path("downloads").asInt(0),
        rating = node.path("rating").takeIf { it.isNumber }?.asDouble(),
        reviews = node.path("reviews").asInt(0),
        published = node.path("published").asString(""),
        /*
         * Never null on a marketplace that has them, and absent on one that
         * does not - both read as an empty list.
         *
         * A marketplace still on the old field answers one `category` instead,
         * and it is read as a tag, because that is what it was: one word for
         * what a plugin is for. Transitional, and only reachable through the
         * rung below that asks for it - when every marketplace answers tags
         * this and [WITH_CATEGORY] go together.
         */
        tags = node.path("tags").values().map { it.asString("") }.filter { it.isNotBlank() }.toList()
            .ifEmpty { listOfNotNull(node.path("category").asString("").ifEmpty { null }) },
        versions = node.path("versions").values().map(::release).toList(),
    )

    /**
     * One release, read defensively.
     *
     * A marketplace older than this field answers nothing here and the list is
     * empty, which is a listing without a history rather than a failure - see
     * the note on [MarketplaceOffering.versions].
     */
    private fun release(node: tools.jackson.databind.JsonNode) = MarketplaceRelease(
        version = node.path("version").asString(""),
        published = node.path("published").asString(""),
        replaced = node.path("replaced").asString(""),
        digest = node.path("digest").asString(""),
        files = node.path("files").asInt(0),
        // Absent reads as available: a marketplace that does not say cannot
        // have its silence taken as "these bytes are gone".
        available = node.path("available").asBoolean(true),
        // Absent on a rung that did not ask for them, and on a release
        // published before the publisher sent any.
        notes = node.path("notes").asString(""),
    )

    /**
     * A listing asked for whole, and asked again for less where whole failed.
     *
     * The fields a listing carries grew, and the marketplaces this server
     * talks to did not grow at the same moment - one is deployed here, the
     * other is wherever an installation points. A query naming a field the far
     * end does not have fails entirely: not that field missing from the
     * answer, but no answer, and a Catalog screen saying the marketplace
     * cannot be read while it is up and perfectly well.
     *
     * The same is true of a field that is *declared* and answers null under a
     * non-null type, which is what the marketplace this was written against
     * does with `versions` today - GraphQL is required to fail the whole
     * listing over it, so a client asking for it optimistically has to be able
     * to stop asking.
     *
     * So: ask for everything, and where the query itself was refused, ask once
     * more for the fields that have always been there. Only a refusal of the
     * query - not a timeout, a 401 or an outage, which retrying would only
     * make slower, and which the second answer would report no better than the
     * first.
     */
    private fun askedForFields(query: (String) -> Pair<String, Map<String, Any?>>): tools.jackson.databind.JsonNode {
        var refusal: MarketplaceRefusedQueryException? = null
        for (fields in LADDER) {
            val (asking, variables) = query(fields)
            try {
                return asked(asking, variables)
            } catch (refused: MarketplaceRefusedQueryException) {
                refusal = refused
                log.info("the marketplace refused a listing's fields, asking for fewer: {}", refused.message)
            }
        }
        throw refusal ?: MarketplaceUnreachableException("it refused every shape of the query")
    }

    /**
     * One query, and the marketplace's own words when it refuses.
     *
     * A GraphQL error is carried out as the sentence it is: the caller is a
     * screen, and "the marketplace said X" is worth more than a stack trace
     * about a JSON node that was not there.
     */
    private fun asked(query: String, variables: Map<String, Any?>): tools.jackson.databind.JsonNode {
        if (!configured) throw MarketplaceUnreachableException("this installation has no marketplace configured")
        /*
         * The catalog is keyed too, and refused with a bare 401 before the
         * query is looked at. Asked for here rather than at the far end so
         * the answer is what is missing, not what the status code was.
         */
        val key = installKey.today() ?: throw MarketplaceUnreachableException(MarketplaceInstallKey.MISSING)

        val body = mapper.writeValueAsString(mapOf("query" to query, "variables" to variables))
        val request = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header(MarketplaceInstallKey.HEADER, key)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val answer = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (failure: java.io.IOException) {
            throw MarketplaceUnreachableException(failure.message ?: "it could not be reached")
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw MarketplaceUnreachableException("the request was interrupted")
        }
        // The two statuses worth naming. Everything else is the number, which
        // is all anybody could act on anyway.
        if (answer.statusCode() == 401) throw MarketplaceUnreachableException(MarketplaceInstallKey.REFUSED)
        if (answer.statusCode() == 429) {
            val after = answer.headers().firstValue("Retry-After").orElse(null)
            throw MarketplaceUnreachableException(
                if (after == null) {
                    "it is rate-limiting this installation; try again shortly"
                } else {
                    "it is rate-limiting this installation; try again in ${after}s"
                },
            )
        }
        if (answer.statusCode() != 200) {
            throw MarketplaceUnreachableException("it answered ${answer.statusCode()}")
        }

        val read = runCatching { mapper.readTree(answer.body()) }.getOrNull()
            ?: throw MarketplaceUnreachableException("it answered something that is not JSON")
        val errors = read.path("errors")
        if (errors.isArray && !errors.isEmpty) {
            // Its own kind, because this is the one failure asking differently
            // could fix - see [askedForFields].
            throw MarketplaceRefusedQueryException(errors.first().path("message").asString("it refused the query"))
        }
        return read
    }

    private companion object {
        /**
         * The fields every marketplace has ever answered with.
         *
         * What a listing falls back to when the whole query is refused. Kept
         * as its own constant rather than spelled out twice, so a field added
         * to the newer half can never quietly join the half that is meant to
         * work everywhere.
         */
        const val CORE_FIELDS =
            "key name author summary description version url icon iconDark downloads rating reviews published"

        /**
         * What a listing is asked for.
         *
         * Narrower than what the marketplace offers, and that is allowed to
         * stay true: GraphQL breaks on asking for what is not there, never on
         * leaving something out, so this server reads what it uses and a field
         * added on the other side costs nothing until somebody wants it.
         */
        /**
         * Everything but the release notes, which are the newest thing to be
         * asked for.
         */
        val WITH_VERSIONS =
            "$WITH_TAGS versions { version published replaced digest files available }"

        val FIELDS =
            "$WITH_TAGS versions { version published replaced digest files available notes }"

        /** Everything but the history, which is the field marketplaces stumble on first. */
        const val WITH_TAGS = "$CORE_FIELDS tags "

        /**
         * The rung for a marketplace that has not switched to tags yet.
         *
         * `category` was one word for what a plugin is for and `tags` are
         * several, so a catalog still answering the old field has its word
         * read as a tag of one - which keeps the shelf's filter working on
         * every marketplace that is deployed today rather than only on the
         * ones that have caught up.
         *
         * Transitional, and meant to be deleted: when nothing answers
         * `category` any more this rung only costs a refused query.
         */
        const val WITH_CATEGORY = "$CORE_FIELDS category "

        /**
         * What a listing is asked for, in the order it is asked.
         *
         * A rung at a time rather than all-or-nothing, because the fields did
         * not arrive together and neither did the marketplaces: the one this
         * was written against declares `versions` and answers null for it
         * under a non-null type, which fails the whole query - and asking for
         * nothing new over that would hide `tags`, which it answers perfectly
         * well. So each step drops the newest thing and keeps the rest, and an
         * installation gets as much as its marketplace can say.
         */
        val LADDER = listOf(FIELDS, WITH_VERSIONS, WITH_TAGS, WITH_CATEGORY, CORE_FIELDS)

        /**
         * What a server release listing is asked for, newest shape first. One
         * rung today; a field added later is a new rung above it, and a
         * marketplace that refuses every rung offers no server releases.
         */
        val SERVER_RELEASE_LADDER = listOf("version publishedAt changelog jarUrl sha256 size")

        /**
         * What an integration is asked for, newest shape first: the contract in
         * `docs/orknux-ai/integrations.graphql`. One rung today; a field added
         * there is a new rung above this one. Icons are left out on purpose:
         * the browser would fetch them from orknux.ai directly, past this
         * installation's proxy, which is the call [MarketplaceIcons] exists to
         * keep on the server.
         */
        private const val INTEGRATION_FIELDS =
            "key name vendor summary description tags homepage repository license maintainer popularity " +
                "kind transport runsOn sampleTools minimumServer " +
                "setup { effort minutes requirements needsAdmin steps claudePrompt }"

        /** Newest first: the icons, then the shape before them - a marketplace that never had them still answers. */
        val INTEGRATION_LADDER = listOf(
            "$INTEGRATION_FIELDS icon iconDark github { fullName url stars archived checkedAt }",
            "$INTEGRATION_FIELDS icon iconDark",
            INTEGRATION_FIELDS,
        )

        /** How many icons are kept fetched; a page of cards is a few dozen, and past this the cache starts again. */
        const val ICON_CACHE = 400

        /** What an integration's icon may be: the picture formats a card draws, nothing that runs. */
        val ICON_TYPES = setOf("image/svg+xml", "image/png", "image/webp", "image/jpeg")

        /** The most an icon may weigh; a picture the size of a card's corner has no need of more. */
        const val ICON_BYTES = 256 * 1024
    }
}

/** A server release orknux.ai offers, as it answered. Issue #584. */
data class OfferedServerRelease(
    val version: String,
    val publishedAt: String,
    /** What changed, in markdown: that version's section of CHANGELOG.md. */
    val changelog: String,
    val jarUrl: String,
    /** Lowercase hex; held against the downloaded bytes before anything else looks at them. */
    val sha256: String,
    val size: Long,
)

/**
 * The marketplace answered, and refused the query.
 *
 * Told apart from every other failure because it is the only one asking
 * differently could fix: a field the far end does not have, or one it declares
 * and cannot fill. See [Marketplace.askedForFields].
 */
class MarketplaceRefusedQueryException(said: String) : MarketplaceUnreachableException(said)

/** The marketplace could not be asked, in the words it or the network used. */
open class MarketplaceUnreachableException(why: String) : RuntimeException("The marketplace could not be read: $why.")

/** An integration's repository on GitHub, as orknux.ai last read it. */
data class OfferedGitHubRepository(
    val fullName: String,
    val url: String,
    val stars: Int,
    val archived: Boolean,
    /** ISO-8601, when orknux.ai read it; null from one that did not say. */
    val checkedAt: String?,
)

/** A picture fetched for an integration's card, and what kind of picture it is. */
class IntegrationIcon(val bytes: ByteArray, val type: String)

/** One integration orknux.ai recommends, as it answered; see `docs/orknux-ai/integrations.graphql`. */
data class OfferedIntegration(
    /** Absolute addresses of its icon for a light ground and a dark one; null where orknux.ai gives none. */
    val icon: String? = null,
    val iconDark: String? = null,
    val key: String,
    val name: String,
    val vendor: String?,
    val summary: String,
    val description: String,
    val tags: List<String>,
    val homepage: String?,
    val repository: String?,
    val license: String?,
    /** OFFICIAL or COMMUNITY, as the contract spells them. */
    val maintainer: String?,
    val popularity: Int?,
    /** Its repository as GitHub reports it, read by orknux.ai; null where it is not on GitHub or not read yet. */
    val github: OfferedGitHubRepository? = null,
    /** MCP_SERVER, CONNECTION, PLUGIN or EXTENSION. */
    val kind: String,
    /** STREAMABLE_HTTP, SSE or STDIO, for an MCP server. */
    val transport: String?,
    /** ANYWHERE, USER_MACHINE or SERVER. */
    val runsOn: String,
    val sampleTools: List<String>,
    val minimumServer: String?,
    /** MINUTES, QUARTER_HOUR, HOUR or AFTERNOON. */
    val effort: String,
    val minutes: Int,
    val requirements: List<String>,
    val needsAdmin: Boolean,
    /** Markdown. */
    val steps: String,
    /** With its {{placeholders}} still in; the app fills them. */
    val claudePrompt: String,
)

data class OfferedRecommendation(val integration: OfferedIntegration, val reason: String?, val installed: Boolean)

data class OfferedIntegrationPage(val items: List<OfferedRecommendation>, val next: String?, val total: Int)

data class OfferedIntegrationTag(val tag: String, val label: String, val count: Int)
