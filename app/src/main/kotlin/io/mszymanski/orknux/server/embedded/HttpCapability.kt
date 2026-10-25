package io.mszymanski.orknux.server.embedded

import io.mszymanski.orknux.server.chat.AgentTools
import io.mszymanski.orknux.server.chat.BuiltInTools
import io.mszymanski.orknux.server.llm.StoredTypes
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.server.llm.LlmSessionStore
import io.mszymanski.orknux.workflow.script.ScriptResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64

/**
 * Calling an HTTP API. Issue #509.
 *
 * The one plugin here that named no service: it is about whichever API a
 * workspace points it at - an internal endpoint, a status page, a vendor with
 * no plugin of its own - and that is a thing the product should be able to do
 * rather than an integration with somebody else's system.
 *
 * ## No credential yet, and that is deliberate
 *
 * The plugin also carried a `token`, attached only to hosts it had been told
 * about. That half is **not** here: a credential has to be stored encrypted and
 * never read back into a page or a log, and `InstallationSettings` holds
 * plaintext. Improvising somewhere to keep a token is how a secret ends up in a
 * settings table, so this ships without one and a caller that needs
 * authentication sends its own `Authorization` header - which the plugin
 * honoured too, on the grounds that a caller saying it has a better credential
 * for this one call is not worth overruling.
 *
 * What that leaves working: every open endpoint, every internal API behind the
 * installation's own network, and anything whose key a caller already holds.
 * What it leaves missing is the convenience of not repeating a header.
 *
 * ## The fence
 *
 * The installation's proxy rules are the outer one and nothing here widens
 * them - the client is built by [ProxyRouter], so a request goes wherever the
 * installation says it may and nowhere else, with its trusted certificates.
 *
 * Inside that, an allow-list of hosts. Empty refuses nothing, which is a choice
 * worth making deliberately rather than discovering; named, anything else is
 * refused here before a request is made. It matters because an agent reads
 * pages for a living, and a model talked into fetching some other address by
 * something it read is the shape of the problem.
 */
@Component
class HttpCapability(
    private val router: ProxyRouter,
    private val scratch: LlmSessionStore,
    private val settings: io.mszymanski.orknux.server.attachment.InstallationSettings,
    private val mapper: ObjectMapper,
    /** Admin Settings -> HTTP tools, which fences the tools and not the functions. Issue #602. */
    private val policy: HttpToolPolicy,
    /** Asks of the address whether it may be reached, and reads no further than the limit. Issue #588. */
    private val fetch: io.mszymanski.orknux.server.security.GuardedFetch,
    /** The numbers this reads from Admin -> Settings. Issue #453. */
    private val tuning: Tuning = Tuning.DEFAULTS,
) : EmbeddedCapability {

    private val log = LoggerFactory.getLogger(javaClass)

    /*
     * Said on every HTTP tool, because a model asked where it may send requests
     * answered from the links it had happened to see - tool pages on this
     * server - rather than asking: nothing it was offered said that the
     * destinations are this installation's decision, or which tool knows them.
     */
    private val WHERE = "Where requests may go is decided by this installation: ${BuiltInTools.HTTP_ALLOW_LIST} answers it, " +
        "and a refused request says why."

    override val key = "http"
    override val name = "HTTP"

    override fun tools(): List<EmbeddedTool> = listOf(
        EmbeddedTool(
            name = GET,
            readOnly = true,
            summary = "Fetches a URL and reads what comes back.",
            description = "Fetches a URL and answers status, ok, headers, body as text and json where the " +
                "body was a JSON object. A non-2xx is an answer rather than an error, so you can say what " +
                "the other end complained about. Send an $AUTH header yourself where the API needs one. " +
                WHERE,
            params = listOf(
                EmbeddedParam(URL, ValueType.STRING, "The full URL.", required = true),
                EmbeddedParam(HEADERS, ValueType.MAP, "Headers to send, as a name and a value each."),
            ),
        ),
        EmbeddedTool(
            name = REQUEST,
            summary = "Any HTTP method, with a body.",
            description = "The same as $GET with the method named - POST, PUT, PATCH, DELETE, HEAD. A map " +
                "$BODY goes as JSON with the content type set; a string $BODY goes exactly as written, " +
                "which is how form-encoded and plain text are sent. " + WHERE,
            params = listOf(
                EmbeddedParam(URL, ValueType.STRING, "The full URL.", required = true),
                EmbeddedParam(METHOD, ValueType.STRING, "GET, POST, PUT, PATCH, DELETE or HEAD."),
                EmbeddedParam(BODY, ValueType.STRING, "A JSON object, or a string sent as written."),
                EmbeddedParam(HEADERS, ValueType.MAP, "Headers to send."),
            ),
        ),
        EmbeddedTool(
            name = DOWNLOAD,
            readOnly = true,
            summary = "Fetches bytes and answers a key for them.",
            description = "Fetches a URL as bytes and keeps them here, answering a $KEY rather than base64 - " +
                "so a file goes from an API into a channel without a megabyte passing through you, which is " +
                "the one thing that does not survive the trip to the next call. The contentType is what the " +
                "bytes are, where they say. " + WHERE,
            params = listOf(
                EmbeddedParam(URL, ValueType.STRING, "The full URL.", required = true),
                EmbeddedParam(HEADERS, ValueType.MAP, "Headers to send."),
            ),
        ),
        EmbeddedTool(
            name = ALLOW_LIST,
            readOnly = true,
            summary = "Where you may send HTTP requests - ask it when asked, or before guessing an address.",
            description = "Which URLs and methods the HTTP tools may request on this installation: either any URL, " +
                "or a list of rules, each a regular expression for the whole URL and the methods it allows. " +
                "Call it when asked where you can send requests - it is the only answer to that, not the " +
                "addresses you have seen - before guessing an address, and after a request was refused.",
        ),
    )

    override fun functions(): List<EmbeddedFunction> = listOf(
        EmbeddedFunction(
            name = GET,
            description = "Fetches a URL and answers status, headers, body and json.",
            params = listOf(
                EmbeddedParam(URL, ValueType.STRING, "The full URL.", required = true),
                EmbeddedParam(HEADERS, ValueType.MAP, "Headers to send."),
            ),
        ),
        EmbeddedFunction(
            name = REQUEST,
            description = "Any HTTP method, with a body.",
            params = listOf(
                EmbeddedParam(URL, ValueType.STRING, "The full URL.", required = true),
                EmbeddedParam(METHOD, ValueType.STRING, "The method."),
                EmbeddedParam(BODY, ValueType.STRING, "The body."),
                EmbeddedParam(HEADERS, ValueType.MAP, "Headers to send."),
            ),
        ),
    )

    /**
     * One tool call, which is an agent's: fenced by Admin Settings -> HTTP
     * tools before anything else is looked at. Issue #602.
     */
    override fun run(name: String, arguments: String, workspaceId: Long, sessionId: Long?): String {
        if (!policy.enabled()) return refusal(HttpToolPolicy.SWITCHED_OFF_SENTENCE)
        if (name == ALLOW_LIST) return mapper.writeValueAsString(policy.describe())
        return perform(name, arguments, sessionId, fenced = true)
    }

    /**
     * The request itself, from either door.
     *
     * [fenced] is the agents' allow list, and only the tool door sets it: a
     * workflow's `http_get` function was written by somebody who chose its
     * address, which is the case `orknux.http` is and the case the policy is
     * not about. The proxy rules and the hosts below apply either way.
     */
    private fun perform(name: String, arguments: String, sessionId: Long?, fenced: Boolean): String {
        val asked = runCatching { mapper.readTree(arguments) }.getOrNull()
            ?: return refusal("That is not valid JSON.")
        val url = text(asked, URL)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return refusal("Give the $URL to call.")

        val refused = refusalFor(url)
        if (refused != null) return refusal(refused)

        val method = when (name) {
            GET -> "GET"
            DOWNLOAD -> "GET"
            REQUEST -> text(asked, METHOD)?.trim()?.uppercase()?.ifEmpty { null } ?: "GET"
            else -> return refusal("There is no tool called http_$name.")
        }
        if (method !in METHODS) return refusal("\"$method\" is not a method this sends; use ${METHODS.joinToString(", ")}.")

        /*
         * Checked here, before a byte is sent, and only here: redirects are not
         * followed - the client is left at its default, which never follows -
         * so a 3xx comes back as the answer with its location, and fetching
         * that location is a new call that meets this same check. A redirect
         * therefore cannot carry a request somewhere the list does not allow.
         */
        if (fenced) {
            val decided = policy.decide(url, method)
            if (!decided.allowed) return refusal(decided.message)
        }

        return try {
            if (name == DOWNLOAD) download(url, asked, sessionId) else send(url, method, asked)
        } catch (failure: Exception) {
            log.warn("An http call to {} did not finish: {}", url, failure.message)
            refusal("that call did not finish: " + (failure.message ?: "the host could not be reached"))
        }
    }

    override fun call(name: String, arguments: List<String>, workspaceId: Long, sessionId: Long?): ScriptResult? {
        val declared = functions().firstOrNull { it.name == name } ?: return null
        val named = linkedMapOf<String, Any?>()
        declared.params.forEachIndexed { at, param ->
            unquoted(arguments.getOrNull(at))?.let { named[param.name] = it }
        }
        val said = perform(name, mapper.writeValueAsString(named), sessionId, fenced = false)
        runCatching { mapper.readTree(said) }.getOrNull()?.path("error")?.takeIf { it.isTextual }?.let {
            return ScriptResult.Failed(it.stringValue(), 0)
        }
        return ScriptResult.Returned(said, 0)
    }

    /* ---------------------------------------------------------------- the call */

    private fun send(url: String, method: String, asked: JsonNode): String {
        val body = asked.path(BODY)
        val written = when {
            body.isMissingNode || body.isNull -> null
            body.isTextual -> body.stringValue()
            else -> body.toString()
        }
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(tuning[Tunable.HTTP_TIMEOUT_SECONDS].toLong()))
            .apply {
                headersIn(asked).forEach { (name, value) -> header(name, value) }
                // A map body is JSON and says so; a string is sent as written,
                // which is what form-encoded and plain text need.
                if (written != null && !body.isTextual) header("content-type", "application/json")
                when (method) {
                    "GET" -> GET()
                    "HEAD" -> method("HEAD", HttpRequest.BodyPublishers.noBody())
                    else -> method(
                        method,
                        written?.let { HttpRequest.BodyPublishers.ofString(it) }
                            ?: HttpRequest.BodyPublishers.noBody(),
                    )
                }
            }
            .build()

        /*
         * Read up to four bytes for every character handed on, the longest a
         * UTF-8 character is, and cut there - the rest of a long page is never
         * held. Not followed past a redirect, as before: the answer is the
         * redirect, and the model can ask for where it points.
         */
        val mostChars = tuning[Tunable.HTTP_BODY_CHARACTERS]
        val answered = when (
            val fetched = fetch.fetch(router.client(), request, mostChars * 4L, followRedirects = false, router::authorized, cutLong = true)
        ) {
            is io.mszymanski.orknux.server.security.GuardedFetch.Fetched.Refused ->
                return refusal("that address may not be reached: ${fetched.why}.")
            is io.mszymanski.orknux.server.security.GuardedFetch.Fetched.TooLarge ->
                return refusal("the answer is longer than ${fetched.most} bytes, which is more than is read.")
            is io.mszymanski.orknux.server.security.GuardedFetch.Fetched.Answered -> fetched
        }

        val text = answered.text()
        val json = runCatching { mapper.readTree(text) }.getOrNull()?.takeIf { it.isObject }

        return mapper.writeValueAsString(
            linkedMapOf(
                "status" to answered.status,
                "ok" to (answered.status < 400),
                "headers" to answered.headers.map().mapValues { it.value.firstOrNull() },
                "body" to text.take(mostChars),
                /*
                 * An object only. A JSON array leaves this null and stays in the
                 * body, because a map cannot hold a list and whoever asked for
                 * an array knows they did.
                 */
                "json" to json,
            ),
        )
    }

    private fun download(url: String, asked: JsonNode, sessionId: Long?): String {
        val mostMb = tuning[Tunable.HTTP_DOWNLOAD_MB]
        // The policy was asked in perform, for this tool's door; here the address and the size.
        val fetched = when (val got = bytes(url, mostMb * 1024L * 1024L, headersIn(asked), followRedirects = false)) {
            is Bytes.Refused -> return refusal(got.why)
            is Bytes.Fetched -> got
        }

        val answer = linkedMapOf<String, Any?>(
            "status" to fetched.status,
            "ok" to (fetched.status < 400),
            "size" to fetched.bytes.size,
            "contentType" to fetched.contentType,
        )
        if (sessionId == null) {
            answer["base64"] = Base64.getEncoder().encodeToString(fetched.bytes)
        } else {
            val named = "http." + java.lang.Long.toString(System.nanoTime(), 36)
            scratch.put(
                sessionId, named, mapper.writeValueAsString(Base64.getEncoder().encodeToString(fetched.bytes)),
                io.mszymanski.orknux.workflow.script.StoredKind(fetched.contentType, true),
            )?.let { return refusal("what was fetched could not be kept: $it.") }
            answer[KEY] = named
            answer["note"] = "Pass $KEY to whatever sends, uploads or saves a file" +
                if (fetched.contentType in StoredTypes.SHOWN_PICTURES || fetched.contentType == SVG) {
                    ", or to ${AgentTools.VIEW_TOOL.name} to look at it."
                } else {
                    "."
                }
        }
        return mapper.writeValueAsString(answer)
    }

    /** What [bytes] answers: the body and what it is, or the sentence a caller is told. */
    sealed interface Bytes {
        /** [contentType] is what the bytes say they are, else what the answer said, else null. */
        class Fetched(val status: Int, val bytes: ByteArray, val contentType: String?) : Bytes

        class Refused(val why: String) : Bytes
    }

    /**
     * An address's body as bytes, through the installation's router and the
     * address guard, read no further than [most]. The one fetch of bytes on a
     * model's behalf - `http_download`, and `picture_view` given an address -
     * so the two cannot differ about what is reachable or how large is too
     * large.
     *
     * Asks nothing of Admin Settings -> HTTP tools: whether this caller may
     * make the request at all is the caller's question ([mayFetch] answers it
     * for an agent's own choice of address). What it does ask is everything
     * else - http or https only, the installation's host list, the address
     * guard at every hop. [followRedirects] only where every hop may go
     * anywhere, since a policy rule is held to the first address alone.
     *
     * The type is the bytes', not the header's: a picture host answering
     * `application/octet-stream` for a JPEG is still a JPEG to a reader.
     */
    fun bytes(url: String, most: Long, headers: Map<String, String> = emptyMap(), followRedirects: Boolean): Bytes {
        refusalFor(url)?.let { return Bytes.Refused(it) }
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(tuning[Tunable.HTTP_TIMEOUT_SECONDS].toLong()))
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .GET()
            .build()
        val answered = try {
            when (val fetched = fetch.fetch(router.client(), request, most, followRedirects, router::authorized)) {
                is io.mszymanski.orknux.server.security.GuardedFetch.Fetched.Refused ->
                    return Bytes.Refused("that address may not be reached: ${fetched.why}.")
                is io.mszymanski.orknux.server.security.GuardedFetch.Fetched.TooLarge ->
                    return Bytes.Refused("that is more than ${megabytes(most)} MB, which is the most that is fetched.")
                is io.mszymanski.orknux.server.security.GuardedFetch.Fetched.Answered -> fetched
            }
        } catch (failure: Exception) {
            log.warn("A fetch of {} did not finish: {}", url, failure.message)
            return Bytes.Refused("that call did not finish: " + (failure.message ?: "the host could not be reached"))
        }
        val said = answered.headers.firstValue("content-type").orElse(null)
            ?.substringBefore(';')?.trim()?.lowercase()?.ifEmpty { null }
        return Bytes.Fetched(answered.status, answered.bytes, StoredTypes.magic(answered.bytes) ?: said)
    }

    /**
     * Whether an agent's own choice of address may be fetched with GET, as the
     * HTTP tools would fetch it: null where it may, the sentence otherwise.
     * Admin Settings -> HTTP tools switched on, and its policy allowing the URL.
     */
    fun mayFetch(url: String): String? {
        if (!policy.enabled()) return HttpToolPolicy.SWITCHED_OFF_SENTENCE
        val decided = policy.decide(url, "GET")
        return if (decided.allowed) null else decided.message
    }

    /** Whether a redirect may be followed for an agent's own address: only where the policy allows any URL. */
    fun followsForAgents(): Boolean = policy.current().kind == HttpToolPolicyKind.ANY

    private fun megabytes(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb == Math.floor(mb)) mb.toLong().toString() else String.format(java.util.Locale.ROOT, "%.1f", mb)
    }

    /* --------------------------------------------------------------- the fence */

    /**
     * Why this call may not be made, or null where it may.
     *
     * Checked before anything is sent, because a refusal after the request has
     * gone is not a fence.
     */
    private fun refusalFor(url: String): String? {
        val uri = runCatching { URI.create(url) }.getOrNull()
            ?: return "\"$url\" is not a URL."
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return "only http and https are called, not \"$scheme\"."
        val host = uri.host ?: return "\"$url\" names no host."

        val allowed = settings.httpHosts().split(',', ';', ' ', 10.toChar())
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
        if (allowed.isEmpty()) return null

        val named = host.lowercase()
        val permitted = allowed.any { one -> named == one || named.endsWith(".$one") }
        return if (permitted) null else {
            "$named is not a host this may reach: it is configured for ${allowed.joinToString(", ")}."
        }
    }

    private fun headersIn(asked: JsonNode): Map<String, String> {
        val given = asked.path(HEADERS).takeIf { it.isObject } ?: return emptyMap()
        return given.properties()
            .filter { (name, _) -> name.lowercase() !in REFUSED_HEADERS }
            .mapNotNull { (name, value) -> value.stringValue()?.let { name to it } }
            .toMap()
    }

    private fun text(node: JsonNode, name: String): String? =
        node.path(name).takeIf { it.isTextual }?.stringValue()

    private fun unquoted(argument: String?): String? {
        val given = argument?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        return runCatching { mapper.readTree(given) }.getOrNull()?.takeIf { it.isTextual }?.stringValue() ?: given
    }

    private fun refusal(said: String): String = mapper.writeValueAsString(mapOf("error" to said))

    private companion object {
        const val GET = "get"
        const val REQUEST = "request"
        const val DOWNLOAD = "download"
        const val ALLOW_LIST = "allowList"

        const val URL = "url"
        const val METHOD = "method"
        const val BODY = "body"
        const val HEADERS = "headers"
        const val KEY = "contentKey"
        const val AUTH = "authorization"
        const val SVG = "image/svg+xml"

        val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")

        /**
         * Headers a caller may not set, because the client owns them and a
         * value here would either be ignored or would break the request.
         */
        val REFUSED_HEADERS = setOf("host", "content-length", "connection", "upgrade", "transfer-encoding")

        // How long a request may take, how much of an answer is read (enough to
        // read one; past it is a download) and the largest download are
        // Tunable.HTTP_*. Issue #453.
    }
}
