package io.mszymanski.orknux.server.diagram

import io.mszymanski.orknux.connector.proxy.ProxyRouter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * A picture a diagram shows from an address on the web, fetched so that a
 * picture of the diagram drawn on the server - an export, the picture an
 * agent is shown - has it too. The editor needs none of this: the browser
 * fetches it there.
 *
 * Whoever can draw on a diagram names the address, so the fetch is held in.
 * A link-local address never - a cloud's metadata service answers there, and
 * reading it is what a fetch an editor can aim is turned to. This machine and
 * its network only where Admin -> Settings allows - `ORKNUX_DIAGRAM_PICTURES_INTERNAL`
 * until somebody changes it there - which it does unless an administrator says otherwise: a diagram of a company's
 * systems points at its own servers. And only the four picture types a browser and the renderer both
 * draw, at most [MOST_BYTES], within [WAIT]. Through [ProxyRouter.builder], as
 * every call out of this application is. What was fetched, or failed, is held
 * for [KEEP_MILLIS], so a diagram exported twice asks once.
 */
@Component
class DiagramWebPictures(
    proxies: ProxyRouter,
    /** Whether an address on this machine or its network is fetched too: Admin -> Settings, or `ORKNUX_DIAGRAM_PICTURES_INTERNAL`. */
    private val settings: io.mszymanski.orknux.server.attachment.InstallationSettings,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val http: HttpClient = proxies.builder()
        .connectTimeout(WAIT)
        // Not followed: a redirect is a second address, and it would not be checked.
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    private class Held(val data: String?, val at: Long)

    private val held = ConcurrentHashMap<String, Held>()

    /**
     * The picture at [address] as a data address, or null where it may not or could not be fetched.
     *
     * Held under the address and the setting it was fetched under: held under
     * the address alone, a picture fetched while internal addresses were
     * allowed was still drawn for ten minutes after an administrator stopped
     * them, and one refused before they were allowed stayed refused as long.
     */
    fun data(address: String): String? {
        val internal = settings.diagramPicturesInternal()
        val key = "$internal $address"
        held[key]?.takeIf { System.currentTimeMillis() - it.at < KEEP_MILLIS }?.let { return it.data }
        val data = runCatching { fetch(address, internal) }.onFailure { log.debug("Diagram picture {} not fetched: {}", address, it.message) }.getOrNull()
        if (held.size > MOST_HELD) held.clear()
        held[key] = Held(data, System.currentTimeMillis())
        return data
    }

    private fun fetch(address: String, internal: Boolean): String? {
        val uri = URI(address)
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
        if (!public(uri.host, internal)) return null
        val request = HttpRequest.newBuilder(uri).timeout(WAIT).header("Accept", TYPES.joinToString(", ")).GET().build()
        val answer = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        answer.body().use { body ->
            if (answer.statusCode() != 200) return null
            val type = answer.headers().firstValue("Content-Type").orElse("").substringBefore(';').trim().lowercase()
            if (type !in TYPES) return null
            val bytes = body.readNBytes(MOST_BYTES + 1)
            if (bytes.size > MOST_BYTES) return null
            return DiagramPictureData.of(type, bytes)
        }
    }

    /** Whether every address [host] names may be fetched: never link-local, and here or on this network only where allowed. */
    private fun public(host: String, internal: Boolean): Boolean {
        val all = runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
        return all.isNotEmpty() && all.none { address ->
            address.isLinkLocalAddress || address.isAnyLocalAddress || address.isMulticastAddress ||
                (!internal && (address.isLoopbackAddress || address.isSiteLocalAddress || unique(address)))
        }
    }

    /** IPv6's own private range, fc00::/7, which the JDK does not call site-local. */
    private fun unique(address: InetAddress): Boolean = address.address.size == 16 && (address.address[0].toInt() and 0xfe) == 0xfc

    private companion object {
        val WAIT: Duration = Duration.ofSeconds(5)
        const val MOST_BYTES = 5 * 1024 * 1024
        const val KEEP_MILLIS = 10 * 60 * 1000L
        const val MOST_HELD = 200
        val TYPES = listOf("image/png", "image/jpeg", "image/gif", "image/webp")
    }
}
