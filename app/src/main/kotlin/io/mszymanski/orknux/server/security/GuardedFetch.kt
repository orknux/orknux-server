package io.mszymanski.orknux.server.security

import io.mszymanski.orknux.connector.connection.ConnectionProbe
import org.springframework.stereotype.Component
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * An HTTP call made on behalf of code or a model, checked at every address it
 * reaches and read no further than it may. Issue #588.
 *
 * The two doors a workspace's own JavaScript and an agent's HTTP tools go out
 * through - `orknux.http` in [io.mszymanski.orknux.server.plugin.NetworkPluginHost]
 * and the http tools in [io.mszymanski.orknux.server.embedded.HttpCapability] -
 * asked nobody before this. Every other outbound call a workspace configures (a
 * connection, an MCP server, a model provider) already asks
 * [ConnectionProbe.vet], which refuses link-local addresses: those reach cloud
 * instance metadata, and a function that can read `169.254.169.254` can read the
 * machine's cloud credentials. These two could, and returned the body.
 *
 * So the same question is asked here, of the first address and of every
 * address a redirect leads to - a redirect is followed by hand, because the
 * JDK's own following would carry the request to wherever the answer points
 * without asking. HTTPS is never followed down to HTTP, and the credentials a
 * caller set are not carried to another host.
 *
 * And the body is read through a bound rather than whole and then measured:
 * an answer that never ends would otherwise be held in memory until it did.
 *
 * Private and loopback addresses stay reachable, as they do for connections:
 * an internal API is what these tools are for. That is a decision recorded on
 * #588, not an oversight.
 */
@Component
class GuardedFetch(private val probe: ConnectionProbe) {

    sealed interface Fetched {
        data class Answered(
            val status: Int,
            val headers: HttpHeaders,
            val bytes: ByteArray,
            val uri: URI,
            /** Whether the body went on past what was read, when the caller asked for it cut rather than refused. */
            val cut: Boolean = false,
        ) : Fetched {
            /** The body as text, in the charset the answer named, else UTF-8. */
            fun text(): String = String(bytes, charsetOf(headers))
        }

        data class Refused(val why: String) : Fetched

        /** The body went past [most] bytes; nothing of it is handed back. */
        data class TooLarge(val most: Long) : Fetched
    }

    /**
     * Sends [request] with [client], which must not follow redirects itself.
     * [prepare] is applied to every request actually sent - the first and each
     * redirect's - for whatever the caller adds per hop, such as proxy
     * credentials. With [cutLong], a body past [most] is handed back cut at
     * [most] rather than refused - a page read for its text is still worth its
     * first part.
     */
    fun fetch(
        client: HttpClient,
        request: HttpRequest,
        most: Long,
        followRedirects: Boolean,
        prepare: (HttpRequest) -> HttpRequest = { it },
        cutLong: Boolean = false,
    ): Fetched {
        var current = request
        repeat(MOST_HOPS + 1) {
            probe.vet(current.uri().toString())?.let { return Fetched.Refused("${current.uri().host}: $it") }

            val answered = client.send(prepare(current), HttpResponse.BodyHandlers.ofInputStream())
            val status = answered.statusCode()
            val location = answered.headers().firstValue("location").orElse(null)
            if (followRedirects && status in REDIRECTS && location != null) {
                answered.body().close()
                val next = runCatching { current.uri().resolve(location.trim()) }.getOrNull()
                    ?: return Fetched.Refused("it redirected to \"$location\", which is not an address")
                if (current.uri().scheme.equals("https", true) && !next.scheme.equals("https", true)) {
                    return Fetched.Refused("it redirected from https to ${next.scheme}, which is not followed")
                }
                current = redirected(current, next, status)
                return@repeat
            }
            val (bytes, over) = answered.body().use { read(it, most) }
            if (over && !cutLong) return Fetched.TooLarge(most)
            return Fetched.Answered(status, answered.headers(), bytes, current.uri(), cut = over)
        }
        return Fetched.Refused("it redirected more than $MOST_HOPS times")
    }

    /** The next hop of a redirect, the way a browser would make it. */
    private fun redirected(previous: HttpRequest, next: URI, status: Int): HttpRequest {
        val sameHost = previous.uri().host.equals(next.host, ignoreCase = true) && previous.uri().port == next.port
        val builder = HttpRequest.newBuilder(previous) { name, _ ->
            sameHost || name.lowercase() !in CREDENTIALS
        }.uri(next)
        val becomesGet = status == SEE_OTHER || (status in setOf(MOVED, FOUND) && previous.method() !in setOf("GET", "HEAD"))
        if (becomesGet) builder.GET()
        return builder.build()
    }

    companion object {
        const val MOST_HOPS = 5
        private const val MOVED = 301
        private const val FOUND = 302
        private const val SEE_OTHER = 303
        private val REDIRECTS = setOf(MOVED, FOUND, SEE_OTHER, 307, 308)
        private val CREDENTIALS = setOf("authorization", "cookie", "proxy-authorization", "api-key", "x-api-key")

        /** Up to [most] bytes of [stream], and whether there was more; nothing past [most] is held. */
        fun read(stream: InputStream, most: Long): Pair<ByteArray, Boolean> {
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) return out.toByteArray() to false
                if (total + read > most) {
                    out.write(buffer, 0, (most - total).toInt())
                    return out.toByteArray() to true
                }
                total += read
                out.write(buffer, 0, read)
            }
        }

        fun charsetOf(headers: HttpHeaders): Charset {
            val named = headers.firstValue("content-type").orElse("")
                .split(';').map { it.trim() }
                .firstOrNull { it.startsWith("charset=", ignoreCase = true) }
                ?.substringAfter('=')?.trim('"', ' ')
            return named?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: StandardCharsets.UTF_8
        }
    }
}
