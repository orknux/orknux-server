package io.mszymanski.orknux.server.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.cors.CorsUtils
import org.springframework.web.filter.OncePerRequestFilter
import java.net.URI

/**
 * Refuses a request that changes something when a browser says it was sent by
 * another site. Issue #588.
 *
 * ---------------------------------------------------------------------------
 * Why this and not a CSRF token
 *
 * The API is signed in with a session cookie, and a cookie rides along with
 * whatever a browser sends to this host - including a form another site's page
 * submits. Spring Security's answer is a token the page reads and echoes on
 * every write; this application's answer is the one OWASP lists beside it,
 * verifying where the request came from with the headers a browser writes and a
 * page cannot: `Origin`, which every current browser sends on every cross-origin
 * request that is not a plain navigation, `Sec-Fetch-Site`, and `Referer` where
 * neither is there. No state to keep, nothing for every `fetch` in the
 * interface to remember to carry, and nothing a non-browser client - the CLI,
 * an assistant on `/mcp`, a build server - has to do differently, because none
 * of them sends either header.
 *
 * Two things already stood in the way before this, and neither was meant as
 * the lock: the session cookie is `SameSite=Lax`, which an operator may set to
 * `none`; and the CORS filter refuses a cross-origin request from an origin it
 * does not know, which is a fact about how Spring answers CORS rather than a
 * decision anybody here made. This makes the decision explicit, and tested.
 *
 * ---------------------------------------------------------------------------
 * What is let through
 *
 * - Reads: GET, HEAD, OPTIONS. Nothing here changes state on a read, and a
 *   GraphQL mutation is refused over GET by the GraphQL handler itself.
 * - A request carrying `Authorization`. A bearer token is not ambient - no
 *   browser attaches one to somebody else's form - so there is nothing to forge.
 * - Webhooks, which are open by design and take no session.
 * - A request with no `Origin`, no `Sec-Fetch-Site` and no `Referer`: that is
 *   not a browser, or is one old enough to send none, and `SameSite` covers it.
 * - An origin that is this server's own (by the same reckoning CORS uses), one
 *   of `ORKNUX_ALLOWED_ORIGINS`, or `ORKNUX_BASE_URL`'s.
 *
 * Everything else that writes is answered 403 before it reaches a controller.
 *
 * Not a bean: built by [SecurityConfig] and placed in the security chain, so
 * it runs once, behind the CORS filter, and never as a filter of its own.
 */
class CrossSiteRequestGuard(private val web: WebProperties) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val why = refusal(request)
        if (why == null) {
            chain.doFilter(request, response)
            return
        }
        log.warn("Refused a cross-site {} to {}: {}", request.method, request.requestURI, why)
        response.status = HttpStatus.FORBIDDEN.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.write("""{"error":"Refused: this request came from another site.","code":"CrossSiteRequest"}""")
    }

    /** Why [request] is refused, or null when it is let through. */
    internal fun refusal(request: HttpServletRequest): String? {
        if (request.method.uppercase() in SAFE) return null
        if (!request.getHeader(HttpHeaders.AUTHORIZATION).isNullOrBlank()) return null
        if (request.requestURI.removePrefix(request.contextPath).startsWith("$WEBHOOK_PATH/")) return null

        val origin = request.getHeader(HttpHeaders.ORIGIN)?.trim()?.takeIf { it.isNotEmpty() }
        if (origin != null) {
            if (origin == "null") return "the page that sent it has no origin"
            if (trusted(origin, request)) return null
            return "origin $origin is not this server's"
        }

        // No Origin. A current browser names the relation instead.
        when (request.getHeader(FETCH_SITE)?.trim()?.lowercase()) {
            "cross-site", "same-site" -> return "a browser on another site sent it"
            "same-origin", "none" -> return null
        }

        val referer = request.getHeader(HttpHeaders.REFERER)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val refererOrigin = originOf(referer) ?: return "its referrer cannot be read"
        return if (trusted(refererOrigin, request)) null else "referrer $refererOrigin is not this server's"
    }

    private fun trusted(origin: String, request: HttpServletRequest): Boolean {
        val wanted = normalised(origin) ?: return false
        if (allowed().contains(wanted)) return true
        // The same reckoning the CORS filter makes: scheme, host and port of the
        // request as this server received it.
        return !CorsUtils.isCorsRequest(SingleOrigin(request, origin))
    }

    private fun allowed(): Set<String> =
        (web.allowedOrigins + listOf(web.baseUrl)).mapNotNull { normalised(it) }.toSet()

    /** A request whose only Origin is [origin], for asking CORS whether it is cross-origin. */
    private class SingleOrigin(request: HttpServletRequest, private val origin: String) :
        jakarta.servlet.http.HttpServletRequestWrapper(request) {
        override fun getHeader(name: String): String? =
            if (name.equals(HttpHeaders.ORIGIN, ignoreCase = true)) origin else super.getHeader(name)
    }

    companion object {
        private val log = LoggerFactory.getLogger(CrossSiteRequestGuard::class.java)

        private val SAFE = setOf("GET", "HEAD", "OPTIONS", "TRACE")

        const val FETCH_SITE = "Sec-Fetch-Site"

        /** `scheme://host[:port]`, lower case, with a default port left off; null for anything else. */
        fun normalised(value: String): String? {
            val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase() ?: return null
            val port = when {
                uri.port == -1 -> ""
                scheme == "http" && uri.port == 80 -> ""
                scheme == "https" && uri.port == 443 -> ""
                else -> ":${uri.port}"
            }
            return "$scheme://$host$port"
        }

        fun originOf(url: String): String? = normalised(url)
    }
}
