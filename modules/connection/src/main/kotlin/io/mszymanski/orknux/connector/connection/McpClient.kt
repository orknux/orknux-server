package io.mszymanski.orknux.connector.connection

import org.slf4j.LoggerFactory
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.connector.proxy.OutboundTrust
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.net.URI
import java.net.http.HttpClient
import java.util.concurrent.atomic.AtomicReference
import java.security.cert.CertificateException
import java.security.cert.CertPathBuilderException
import javax.net.ssl.SSLException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** One tool an MCP server offers, as it describes itself. */
data class McpTool(
    val name: String,
    val description: String,
    /** Parameter names from the tool's own input schema, required ones first. */
    val parameters: List<McpParameter>,
)

data class McpParameter(val name: String, val description: String, val required: Boolean)

/** What a server said it can do, or why it could not be asked. */
sealed interface McpListing {
    data class Tools(val tools: List<McpTool>) : McpListing
    data class Failed(val reason: String) : McpListing
}

/**
 * How the handshake went, and what the server said about itself while it went.
 *
 * A boolean, or a session id and null, was what this used to be, and every way
 * a handshake can fail arrived as the same sentence: the server did not
 * complete the MCP handshake. That is true of a wrong address, an expired
 * token, a server speaking a protocol version this does not, and a proxy
 * answering on its behalf — four different things to go and fix, told apart by
 * nothing.
 */
sealed interface McpHandshake {

    /**
     * @param session the server's session id, or empty where it started none.
     *   Optional in the protocol, so an empty string is a working session and
     *   not a failure.
     * @param server what the server called itself, where it said.
     * @param protocol the version it answered with, which need not be the one
     *   it was asked for.
     */
    data class Open(
        val session: String,
        val server: String?,
        val protocol: String?,
        /**
         * Whether the server advertised, in `capabilities.experimental` under
         * the orknux-shell marker, that it jails each session to its own root.
         * When it does, a shell may hold one session open and reuse it across
         * runs rather than carrying a directory of its own. False for any server
         * that does not say so, which reads as "no jail here". See issue #337.
         */
        val sessionIsolation: Boolean,
    ) : McpHandshake

    /** Why it did not open, in a sentence somebody can act on. */
    data class Refused(val reason: String) : McpHandshake
}

@ConfigurationProperties(prefix = "orknux.mcp")
data class McpProperties(
    /** How long a server has to answer before the call is given up on. */
    val timeout: Duration = Duration.ofSeconds(30),
)

/**
 * Talks to an MCP server over Streamable HTTP.
 *
 * Here rather than in the app for the reason everything outbound is: it needs
 * the credential, and credentials are resolved in one place — [McpServer.target]
 * builds the headers, the same as for a connection check.
 *
 * Every call opens its own session: initialize, then the request. That is one
 * extra round trip per call, and it is deliberate — a pooled session would have
 * to survive a server restart, a token expiring, and two agents using one server
 * at once, and none of that is worth carrying until somebody is running enough
 * calls to notice. The cost is a request; the alternative is a cache with three
 * invalidation rules.
 *
 * Nothing here trusts the server. A tool with no name is dropped, a description
 * that is missing becomes an empty one, and a result that is not what the
 * protocol describes is reported as a failure rather than passed to a model as
 * though it were an answer.
 */
@Service
@EnableConfigurationProperties(McpProperties::class)
class McpClient(
    private val mapper: ObjectMapper,
    private val properties: McpProperties,
    private val probe: ConnectionProbe,
    /** The one place a stored credential is read; see [ConnectionCredentials]. */
    private val credentials: ConnectionCredentials,
    private val proxies: ProxyRouter,
    /** What this installation trusts on the way out; see [OutboundTrust]. */
    private val trusted: OutboundTrust,
) {

    /**
     * The client, made once, trusting whatever the installation trusts.
     *
     * Made lazily rather than in the constructor because the trusted list is
     * read from the database, and a bean that queried during construction would
     * have to be ordered against the schema being there. Made once after that: a
     * client carries a connection pool, and one per request would open a fresh
     * connection every call.
     *
     * Rebuilt when the list changes, which is what the generation is for. An
     * administrator who adds an authority and then presses Check expects the
     * check to use it, and a client cached for the life of the process would
     * make them restart the server to find that out.
     */
    private val held = AtomicReference<Pair<Int, HttpClient>?>(null)

    private fun client(): HttpClient {
        val wanted = trusted.context()
        val generation = System.identityHashCode(wanted)
        held.get()?.takeIf { it.first == generation }?.let { return it.second }

        val made = proxies.builder()
            .connectTimeout(CONNECT_TIMEOUT)
            // Not followed, for the reason the probe does not follow one either: a
            // redirect can leave the host somebody configured and take the stored
            // credential with it, which is how a request meant for an MCP server
            // ends up delivering a bearer token to whoever answered. The first
            // response is the answer.
            .followRedirects(HttpClient.Redirect.NEVER)
            .also { builder -> wanted?.let { builder.sslContext(it) } }
            .build()
        held.set(generation to made)
        return made
    }

    /** What this server offers, or why it could not say. */
    fun tools(server: McpServer): McpListing {
        refusal(server)?.let { return McpListing.Failed(it) }

        val opened = when (val handshake = open(server)) {
            is McpHandshake.Refused -> return McpListing.Failed(handshake.reason)
            is McpHandshake.Open -> handshake
        }

        val answer = send(server, opened.session, "tools/list", mapper.createObjectNode(), id = 2)
            ?: return McpListing.Failed("The server did not answer tools/list")

        answer.path("error").takeIf { !it.isMissingNode }?.let { error ->
            return McpListing.Failed(error.path("message").stringValue() ?: "The server refused tools/list")
        }

        val listed = answer.path("result").path("tools") as? ArrayNode
            ?: return McpListing.Failed("The server answered tools/list without any tools")
        return McpListing.Tools(listed.mapNotNull(::toolOf))
    }

    /**
     * Calls one, and hands back what it said as JSON text.
     *
     * A failure comes back as a result rather than an exception, the way a
     * workspace tool's does: the model can be told the lookup failed and carry
     * on, which beats the conversation dying because a server was down.
     */
    fun call(server: McpServer, tool: String, arguments: String): String {
        refusal(server)?.let { return failure(it) }

        val opened = when (val handshake = open(server)) {
            is McpHandshake.Refused -> return failure("${server.name} could not be asked: ${handshake.reason}")
            is McpHandshake.Open -> handshake
        }

        // The protocol returns content blocks; the text ones are what a model
        // can read. Anything else is described rather than dropped silently -
        // all of which is callWith's, shared with the session-holding path.
        return callWith(server, opened.session, tool, arguments)
    }

    /**
     * Why this server's address must not be called, or null when it may be.
     *
     * An address is whatever a workspace member typed, and every request that
     * goes out carries the stored credential, so it is asked about before the
     * handshake rather than at the socket - the point of asking is to refuse
     * while there is still a sentence to hand back. What may be called at all
     * is [ConnectionProbe]'s decision, the same one a connection check makes,
     * so there is one rule here and one place to change it.
     *
     * The server is named in the answer because that is what the person who
     * configured it will recognise: this reason travels back to the model as
     * the tool's result, and from there into the conversation, which is the
     * only place somebody is watching.
     */
    private fun refusal(server: McpServer): String? =
        probe.vet(server.address)?.let { "${server.name} cannot be called: $it" }

    /**
     * Initializes a session and returns its id, or null when the server would
     * not start one. A server that returns no session id is still usable —
     * the header is optional — so an empty string stands for "no session".
     */
    private fun open(server: McpServer): McpHandshake {
        val params = mapper.createObjectNode()
        params.put("protocolVersion", PROTOCOL_VERSION)
        params.putObject("capabilities")
        params.putObject("clientInfo").put("name", CLIENT_NAME).put("version", CLIENT_VERSION)

        log.debug("MCP handshake with {} at {}, asking for protocol {}", server.name, server.address, PROTOCOL_VERSION)

        val response = post(server, session = null, body = request("initialize", params, id = 1))
            ?: return McpHandshake.Refused(takeRefusal() ?: "${server.address} could not be reached")

        if (response.statusCode() !in 200..299) {
            /*
             * The body, not only the status. What a server says when it refuses
             * is where the reason lives - an expired token, a path that is not
             * the MCP endpoint, a proxy explaining it will not forward this -
             * and throwing it away left a number and nothing to do about it.
             */
            val said = detail(response.body())
            log.warn("MCP server {} answered {} to initialize: {}", server.name, response.statusCode(), said)
            return McpHandshake.Refused("The server answered ${response.statusCode()} to initialize: $said")
        }

        val body = parse(response.body())
            ?: return McpHandshake.Refused("The server answered initialize with something that is not JSON-RPC")

        /*
         * A refusal can arrive with a 200 on it. JSON-RPC carries the error in
         * the body, so a server that will not talk to this client answers
         * successfully and says no inside - which used to read here as a
         * handshake that worked, followed by a tools/list that mysteriously did
         * not.
         */
        body.path("error").takeIf { !it.isMissingNode }?.let { error ->
            val said = error.path("message").stringValue() ?: error.toString()
            log.warn("MCP server {} refused initialize: {}", server.name, said)
            return McpHandshake.Refused("The server refused the handshake: $said")
        }

        val result = body.path("result")
        val named = result.path("serverInfo").path("name").stringValue()
        val spoke = result.path("protocolVersion").stringValue()
        val session = response.headers().firstValue(SESSION_HEADER).orElse("")
        // The marker orknux-shell puts under capabilities.experimental. A name in
        // serverInfo is a hint anyone could copy; a namespaced experimental key
        // carrying sessionIsolation is the handshake proof this server jails each
        // session, and is what lets a shell reuse one session across runs. #337
        val isolated = result.path("capabilities").path("experimental")
            .path(SHELL_MARKER).path("sessionIsolation").asBoolean(false)

        log.debug(
            "MCP server {} completed the handshake as {} speaking {}, session {}",
            server.name,
            named ?: "a server that did not name itself",
            spoke ?: "no stated version",
            session.takeIf { it.isNotEmpty() } ?: "none",
        )

        // The notification that the handshake is done. It has no reply, and a
        // server that ignores it is not a server that is broken - but which of
        // them ignored it is worth being able to find out.
        runCatching {
            post(server, session, request("notifications/initialized", mapper.createObjectNode(), id = null))
                ?: takeRefusal()
        }.onFailure { log.debug("MCP server {} did not take notifications/initialized", server.name, it) }

        return McpHandshake.Open(session, named, spoke, isolated)
    }

    /**
     * Opens a session and hands it back, for a caller that will make several
     * calls on it rather than one.
     *
     * The stateless [call] and [tools] each open their own session and let it
     * go, which is right for a lookup. A shell is not a lookup: it opens once,
     * runs many commands that have to land in the same place, and closes. When
     * the server jails each session ([McpHandshake.Open.sessionIsolation]), that
     * place is the session's own root, so the session id is what has to be kept
     * and threaded through - see [callOn] and [closeSession]. Issue #337.
     */
    fun openSession(server: McpServer): McpHandshake {
        refusal(server)?.let { return McpHandshake.Refused(it) }
        return open(server)
    }

    /**
     * Calls a tool on a session already open, rather than opening one.
     *
     * The half of [call] after the handshake, so a shell that has opened a
     * session keeps landing in the same jailed root instead of a fresh one per
     * command. A failure is a result, not an exception, exactly as [call]'s is.
     */
    fun callOn(server: McpServer, session: String, tool: String, arguments: String): String {
        refusal(server)?.let { return failure(it) }
        return callWith(server, session, tool, arguments)
    }

    /**
     * Ends a session, so the server can destroy the root it jailed for it.
     *
     * An HTTP DELETE carrying the session id, which is how the protocol says a
     * client is done with a session. Best-effort: a server that has already
     * forgotten the session, or an idle-sweep that beat us to it, is not a
     * failure worth raising - the session is gone either way, which is the
     * outcome asked for.
     */
    fun closeSession(server: McpServer, session: String) {
        if (session.isEmpty()) return
        runCatching {
            val target = credentials.target(server)
            val builder = HttpRequest.newBuilder(URI(server.address))
                .timeout(properties.timeout)
                .header(SESSION_HEADER, session)
            target.requestHeaders().forEach { (name, value) -> builder.header(name, value) }
            client().send(builder.DELETE().build(), HttpResponse.BodyHandlers.ofString())
        }.onFailure { log.debug("MCP server {} did not take a session close", server.name, it) }
    }

    /** The tool call itself, on whatever session it is handed. */
    private fun callWith(server: McpServer, session: String, tool: String, arguments: String): String {
        val params = mapper.createObjectNode()
        params.put("name", tool)
        params.set("arguments", argumentsOf(arguments))

        val answer = send(server, session, "tools/call", params, id = 3)
            ?: return failure("${server.name} did not answer")

        answer.path("error").takeIf { !it.isMissingNode }?.let { error ->
            return failure(error.path("message").stringValue() ?: "${server.name} refused the call")
        }

        val blocks = answer.path("result").path("content") as? ArrayNode
            ?: return mapper.writeValueAsString(mapOf("result" to answer.path("result")))
        val text = blocks.joinToString("\n") { block ->
            when (block.path("type").stringValue()) {
                "text" -> block.path("text").stringValue().orEmpty()
                else -> "[${block.path("type").stringValue() ?: "unknown"} content, which cannot be read as text]"
            }
        }
        return mapper.writeValueAsString(mapOf("result" to text))
    }

    /**
     * As much of a body as belongs in a log line or a sentence on a screen.
     *
     * Servers answer failures with anything from a word to an HTML page, and
     * neither a stack of markup in the log nor a paragraph in a dialog helps
     * anybody. Blank bodies are common enough to be worth saying so explicitly,
     * since "answered 401: " reads like the line was cut off.
     */
    private fun detail(body: String?): String {
        val said = body?.trim().orEmpty().replace(WHITESPACE, " ")
        if (said.isEmpty()) return "no body"
        return if (said.length <= DETAIL_LENGTH) said else said.take(DETAIL_LENGTH) + "…"
    }

    private fun send(server: McpServer, session: String, method: String, params: ObjectNode, id: Int): JsonNode? {
        val response = post(server, session, request(method, params, id)) ?: return null.also { takeRefusal() }
        if (response.statusCode() !in 200..299) {
            log.warn(
                "MCP server {} answered {} to {}: {}",
                server.name,
                response.statusCode(),
                method,
                detail(response.body()),
            )
            return null
        }
        return parse(response.body())
    }

    private fun post(server: McpServer, session: String?, body: String): HttpResponse<String>? = try {
        val target = credentials.target(server)
        val builder = HttpRequest.newBuilder(URI(server.address))
            .timeout(properties.timeout)
            .header("Content-Type", "application/json")
            // Either shape is acceptable; a server may answer with a stream.
            .header("Accept", "application/json, text/event-stream")
        target.requestHeaders().forEach { (name, value) -> builder.header(name, value) }
        session?.takeIf { it.isNotEmpty() }?.let { builder.header(SESSION_HEADER, it) }

        client()
            .send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
    } catch (failure: Exception) {
        // The address as well as the name. A name is what somebody called it;
        // the address is the thing that did not answer, and the two disagree
        // exactly when this is worth reading.
        log.warn("Could not reach MCP server {} at {}", server.name, server.address, failure)
        lastRefusal.set(unreachable(server, failure))
        null
    }

    /**
     * Why the last request from this thread did not go out.
     *
     * A thread local rather than a returned value because [post] already answers
     * with a response or nothing, and three callers read that; threading a
     * second answer through all of them to carry a sentence only one of them
     * prints would be a worse shape than this. Set on the way out of the catch
     * and read immediately by the caller that is about to refuse, on the same
     * thread, inside the same call.
     *
     * And taken, not only read - see [takeRefusal]. Issue #616.
     */
    private val lastRefusal = ThreadLocal<String?>()

    /**
     * The refusal, cleared as it is read. Every caller [post] answered with
     * nothing takes it, the ones that do not print it as well: left set, the
     * sentence stayed on the pooled thread that made the call for as long as
     * that thread lived.
     */
    private fun takeRefusal(): String? = lastRefusal.get().also { lastRefusal.remove() }

    /**
     * What to say about a request that never got an answer.
     *
     * The TLS case is called out by name because it is the one somebody can
     * actually fix and the one the JVM describes worst. *Unable to find
     * certification path to requested target* is what it says when a certificate
     * is self-signed or signed by an internal CA - which is the ordinary case
     * for an MCP server inside somebody's own network - and those eight words
     * name no server, no certificate and no remedy. Issue #322.
     */
    private fun unreachable(server: McpServer, failure: Exception): String {
        val tls = generateSequence(failure as Throwable?) { it.cause }
            .any { it is SSLException || it is CertificateException || it is CertPathBuilderException }
        if (tls) {
            return "${server.address} presented a certificate this installation does not trust. " +
                "If it is signed by an internal authority, paste that authority's certificate into " +
                "the server's own settings."
        }
        return "${server.address} could not be reached"
    }

    /**
     * The body, whether it arrived as JSON or as one server-sent event.
     *
     * Streamable HTTP allows either, and which one a server picks is not
     * something the caller should have to care about.
     */
    private fun parse(body: String): JsonNode? = runCatching {
        val payload = if (body.trimStart().startsWith("{")) {
            body
        } else {
            body.lineSequence()
                .filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").trim() }
                .lastOrNull { it.isNotEmpty() }
                ?: return null
        }
        mapper.readTree(payload)
    }.getOrNull()

    private fun request(method: String, params: ObjectNode, id: Int?): String {
        val root = mapper.createObjectNode()
        root.put("jsonrpc", "2.0")
        id?.let { root.put("id", it) }
        root.put("method", method)
        root.set("params", params)
        return mapper.writeValueAsString(root)
    }

    /** A tool the server described well enough to offer; null when it did not. */
    private fun toolOf(node: JsonNode): McpTool? {
        val name = node.path("name").stringValue()?.takeIf { it.isNotBlank() } ?: return null
        val schema = node.path("inputSchema")
        val required = (schema.path("required") as? ArrayNode)
            ?.mapNotNull { it.stringValue() }
            .orEmpty()
            .toSet()

        val properties = schema.path("properties")
        val parameters = properties.propertyNames().map { parameter ->
            McpParameter(
                name = parameter,
                description = properties.path(parameter).path("description").stringValue().orEmpty(),
                required = parameter in required,
            )
        }
        return McpTool(
            name = name,
            description = node.path("description").stringValue().orEmpty(),
            parameters = parameters.sortedByDescending { it.required },
        )
    }

    private fun argumentsOf(arguments: String): ObjectNode =
        runCatching { mapper.readTree(arguments) as? ObjectNode }.getOrNull() ?: mapper.createObjectNode()

    private fun failure(reason: String): String = mapper.writeValueAsString(mapOf("error" to reason))

    private companion object {
        /** The revision of the protocol this speaks. */
        const val PROTOCOL_VERSION = "2025-06-18"
        const val SESSION_HEADER = "Mcp-Session-Id"

        /**
         * The reverse-DNS key orknux-shell advertises itself under, in the
         * handshake's `capabilities.experimental`. Matched exactly, so no
         * ordinary server produces it by accident. Issue #337.
         */
        const val SHELL_MARKER = "io.mszymanski.orknux-shell"
        const val CLIENT_NAME = "ordilumen"
        const val CLIENT_VERSION = "1.0"
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)

        /** How much of a server's own words is worth repeating. */
        const val DETAIL_LENGTH = 300
        val WHITESPACE = Regex("\\s+")

        val log = LoggerFactory.getLogger(McpClient::class.java)
    }
}
