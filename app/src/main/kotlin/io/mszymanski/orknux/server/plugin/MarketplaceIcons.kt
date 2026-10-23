package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The text if it is a drawing, and null if it is not.
 *
 * Several places ask this and they used to ask it differently — one wanted
 * `<svg` or `<?xml`, one wanted only `<svg`, and the zip path did not ask at
 * all, which is how an icon whose file opens with a licence comment ended up
 * stored and then printed as its own source on the screen. So it is one
 * function, here, rather than a rule each door remembers.
 *
 * What is skipped is everything a real SVG file is allowed to open with before
 * its root element: whitespace, an XML declaration, a doctype, and comments.
 * Whatever is left has to be the `<svg` tag itself.
 *
 * This is a sanity check rather than a safety one. What makes somebody else's
 * markup safe to draw is that it is put in an `<img>` as a data URI, where a
 * browser renders it as a picture and runs nothing in it — the screen's job,
 * not this one's.
 */
fun drawingOrNull(text: String?, mostChars: Int): String? {
    val held = text?.takeIf { it.length <= mostChars } ?: return null
    var at = 0
    while (at < held.length) {
        when {
            held[at].isWhitespace() -> at++
            held.startsWith("<?", at) -> at = held.indexOf("?>", at).takeIf { it >= 0 }?.plus(2) ?: return null
            held.startsWith("<!--", at) -> at = held.indexOf("-->", at).takeIf { it >= 0 }?.plus(3) ?: return null
            held.startsWith("<!", at) -> at = held.indexOf('>', at).takeIf { it >= 0 }?.plus(1) ?: return null
            else -> return held.takeIf { held.startsWith("<svg", at) }
        }
    }
    return null
}

/**
 * The faces on the catalog screen, fetched here rather than by the browser.
 *
 * A listing's icon arrives as a URL on the marketplace, and until this landed
 * the screen simply put that URL in an `<img>` — so the one outbound call the
 * catalog made that did not go through [ProxyRouter] was the only one this
 * server was not making at all. On an installation whose egress is a proxy, or
 * one that reaches the marketplace through a rule and nothing else directly,
 * every icon on the page was a broken square: the rules were right, the
 * catalog loaded, and the pictures were fetched by a browser those rules have
 * no say over.
 *
 * So the bytes come across here, on the same client every other outbound call
 * uses, and the listing carries the drawing itself. Which is also what an
 * install already did with the icon it keeps — this is the same move, made a
 * step earlier.
 *
 * ## What it will fetch
 *
 * The marketplace's own host and nowhere else, which [MarketplaceInstallKey.own]
 * is the existing answer to. A catalog is somebody else's JSON: a listing whose
 * icon pointed at `http://169.254.169.254/` would otherwise be this server
 * fetching it and handing the answer to a screen. An icon hosted anywhere else
 * is left as the URL it was, which is what the screen did with every icon
 * before — no worse than yesterday, and not a door.
 *
 * ## Why it is cached
 *
 * The catalog is read afresh on every visit to the screen and an icon is not:
 * it is a small file that changes when a plugin is re-published. Without a
 * cache, opening the screen would fetch a dozen files that were fetched a
 * minute ago, and the wait would be on the catalog rather than on them.
 * Failures are remembered too, briefly — an air-gapped installation should ask
 * once and draw its placeholders, not ask a dozen times per visit forever.
 */
@Component
class MarketplaceIcons(
    proxies: ProxyRouter,
    /** Whose host an icon may be on, and what a request for one carries. */
    private val installKey: MarketplaceInstallKey,
    /** The numbers this reads from Admin -> Settings. Issue #453. */
    private val tuning: Tuning = Tuning.DEFAULTS,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val http: HttpClient = proxies.builder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** Where the fetching happens; see [warm]. Virtual, so waiting costs nothing. */
    private val waiting = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()

    /** What was fetched, and when — including the fetches that came to nothing. */
    private val held = ConcurrentHashMap<String, Held>()

    private class Held(val drawing: String?, val at: Long)

    /**
     * Fetch every icon in this catalog that is not already held, at once.
     *
     * Called before the listings are built so the fetches overlap rather than
     * queue: a catalog of a dozen plugins is two dozen small files, and doing
     * them one after another is the difference between a screen that opens and
     * one that takes a visible moment to.
     */
    fun warm(icons: Collection<String?>) {
        val wanted = icons.filterNotNull()
            .map { it.trim() }
            .filter { fetchable(it) && fresh(held[it]) == null }
            .distinct()
        if (wanted.isEmpty()) return

        /*
         * On virtual threads, not the common pool.
         *
         * Each of these blocks on a socket for as long as the marketplace
         * takes, and `commonPool` is sized for work that computes rather than
         * waits - a dozen icons on a slow morning would be a dozen of its
         * threads parked, and everything else in the process that uses it
         * queued behind them.
         */
        val asked = wanted.map { address ->
            CompletableFuture.runAsync({ held[address] = Held(fetched(address), now()) }, waiting)
        }
        runCatching {
            CompletableFuture.allOf(*asked.toTypedArray()).get(ALL_WITHIN_SECONDS, TimeUnit.SECONDS)
        }.onFailure {
            // A slow marketplace delays a screen, it does not empty it: whatever
            // did arrive is held, and whatever did not is asked for next time.
            log.info("not every marketplace icon arrived in time ({})", it.javaClass.simpleName)
        }
    }

    /**
     * What a listing should carry for this icon.
     *
     * An emoji, or markup, comes back as it stands: it is already the picture.
     * A URL on the marketplace comes back as what was fetched from it, and as
     * itself where that could not be fetched — a URL the browser may still be
     * able to reach is a better answer than nothing.
     */
    fun drawn(icon: String?): String? {
        val named = icon?.trim()?.ifEmpty { null } ?: return null
        if (!fetchable(named)) return named
        /*
         * The held answer including a held failure, which is the half an
         * elvis chain here got wrong: "no drawing" and "not asked yet" are the
         * same value and not the same state, so reading through the first fell
         * back to fetching and the short memory of an outage bought nothing.
         */
        val known = fresh(held[named]) ?: Held(fetched(named), now()).also { held[named] = it }
        return known.drawing ?: named
    }

    /** Whether this is a URL, and one on the marketplace's own host. */
    private fun fetchable(icon: String): Boolean {
        if (!icon.startsWith("http://") && !icon.startsWith("https://")) return false
        return runCatching { installKey.own(URI.create(icon)) }.getOrDefault(false)
    }

    /** A held answer while it is still worth believing, or null. */
    private fun fresh(was: Held?): Held? {
        val had = was ?: return null
        val forMillis = if (had.drawing == null) MISSING_FOR_MILLIS else tuning[Tunable.MARKETPLACE_ICON_HOURS] * 3_600_000L
        return had.takeIf { now() - it.at < forMillis }
    }

    private fun fetched(address: String): String? = runCatching {
        val building = HttpRequest.newBuilder(URI.create(address))
            .timeout(Duration.ofSeconds(10))
            .GET()
        /*
         * Keyed like every other request to the marketplace. The icons are the
         * one door it leaves open today - a picture on a page somebody is
         * reading - and sending the day's value anyway costs nothing and means
         * this goes on working the day that changes.
         */
        installKey.today()?.let { building.header(MarketplaceInstallKey.HEADER, it) }

        val answer = http.send(building.build(), HttpResponse.BodyHandlers.ofString())
        if (answer.statusCode() != 200) {
            log.info("the marketplace answered {} for the icon at {}", answer.statusCode(), address)
            return@runCatching null
        }
        /*
         * An SVG and nothing else. A marketplace pointing an icon at something
         * that is not a picture is a marketplace putting somebody else's bytes
         * on this installation's screens, and the cap is what keeps a row a
         * row.
         */
        drawingOrNull(answer.body(), MOST_ICON_CHARS)
    }.onFailure { log.info("the icon at {} could not be fetched: {}", address, it.message) }.getOrNull()

    private fun now() = System.currentTimeMillis()

    private companion object {
        /**
         * A face is a small drawing. Large enough for a real icon with a
         * gradient in it, small enough that a row stays a row.
         */
        const val MOST_ICON_CHARS = 64 * 1024

        // How long a fetched drawing stands is Tunable.MARKETPLACE_ICON_HOURS. #453.

        /** And how long a failure does, so an outage is asked about again soon. */
        const val MISSING_FOR_MILLIS = 2L * 60 * 1000

        /** How long the whole catalog's icons get, together, before the screen goes on without them. */
        const val ALL_WITHIN_SECONDS = 12L
    }
}
