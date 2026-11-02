package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.connector.proxy.OutboundTrust
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.util.concurrent.atomic.AtomicReference
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * One HTTP request, made by the server because a plugin was granted it.
 *
 * **What this is, and what it is not.** It is not a network for the sandbox:
 * nothing in `PluginRunner`'s context changed, there is still no socket and no
 * `fetch`, and a package calling `require('net')` is as uninstallable as it was.
 * It is a function the server implements, called under a capability an
 * administrator accepted, whose argument and answer are both text.
 *
 * That distinction is the whole design. A plugin holding a socket could keep it
 * open, listen on it, or hand it to something else; a plugin calling this gets
 * a status, some headers and a string, and the connection is closed before it
 * sees any of them.
 *
 * ## Where it can get to
 *
 * Wherever [ProxyRouter] lets it, which is the same answer an MCP call and a
 * Slack call get. The client below is built from [ProxyRouter.builder], so an
 * installation's proxy rules are the boundary — and the trusted-certificate
 * list is honoured for the same reason, since both hang off that builder. A
 * second HTTP client here would be #176's bug in a new place: rules that cover
 * most of the product and quietly miss one door.
 *
 * **This is the widest capability there is and it is worth being plain about
 * what it costs.** A plugin granted it can reach anything the server can, which
 * on most installations includes hosts the person who accepted it cannot reach
 * themselves. What is here to make that survivable is a narrow shape — one
 * request, one answer, no redirect to a scheme it refuses, a bounded body — and
 * the proxy rules. What is *not* here is a URL allowlist, because a plugin
 * knows one outside service and the address it needs is its own business; an
 * installation that wants a list has proxy rules, which are where such a list
 * belongs and where the rest of the product already reads one.
 */
@Component
class NetworkPluginHost(
    private val mapper: ObjectMapper,
    private val proxies: ProxyRouter,
    /** What this installation trusts, watched so a new authority is picked up. */
    private val trusted: OutboundTrust,
    /**
     * Asks of every address a call reaches whether it may be reached, and reads
     * no further than the limit. Issue #588.
     */
    private val fetch: io.mszymanski.orknux.server.security.GuardedFetch,
    /** The numbers this reads from Admin -> Settings. Issue #453. */
    private val tuning: Tuning = Tuning.DEFAULTS,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val held = AtomicReference<Pair<Int, HttpClient>?>(null)

    /**
     * The client, made once and made again when the trusted list changes.
     *
     * Built here rather than in the constructor for the reason `McpClient`
     * builds its own lazily: the list is read from the database, and an
     * administrator who adds their authority and then asks a plugin to reach
     * something expects that call to use it. A client built at startup and kept
     * for the life of the process would make them restart the server to find
     * that out - which is exactly the shape of the bug this release is fixing,
     * one layer further in.
     *
     * The generation is the identity of the context the trust hands back: it
     * returns the same instance until something is added or removed, and a new
     * one after.
     */
    /**
     * The most bytes a download through a door carries - `orknux.http.download`,
     * and `orknux.slack.download`, which a plugin moved onto from the first and
     * should find the same files fit. Tunable.PLUGIN_DOWNLOAD_MB; issue #453.
     */
    fun mostDownloadedBytes(): Long = io.mszymanski.orknux.server.tuning.TunableArithmetic.bytesOf(tuning[Tunable.PLUGIN_DOWNLOAD_MB])

    /** The largest upload through either door, `orknux.http` or `orknux.slack.upload`; Tunable.PLUGIN_UPLOAD_MB. */
    fun mostUploadedBytes(): Long = io.mszymanski.orknux.server.tuning.TunableArithmetic.bytesOf(tuning[Tunable.PLUGIN_UPLOAD_MB])

    private fun client(): HttpClient {
        val generation = System.identityHashCode(trusted.context())
        held.get()?.takeIf { it.first == generation }?.let { return it.second }

        val made = proxies.builder()
            .connectTimeout(CONNECT_TIMEOUT)
            /*
             * Not followed by the client: [GuardedFetch] follows them, asking of
             * every address on the way whether it may be reached, and never from
             * HTTPS down to HTTP. The client's own following would take the call
             * wherever an answer pointed - cloud metadata included. Issue #588.
             */
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
        held.set(generation to made)
        return made
    }

    /**
     * `[url, method, headers, body, options]`, as the contract's helper sends it.
     *
     * Read defensively and refused in words. This is the boundary somebody
     * else's JavaScript writes to, so every shape of wrong has to come back as
     * something a plugin can say out loud.
     *
     * The fifth element is how binary crosses a boundary that only carries
     * text: `{ sendBase64: true }` says the body is base64 and the bytes it
     * decodes to are what is sent; `{ wantBytes: true }` says to bring the
     * answer's body back as base64 under `base64`, beside its `contentType`
     * and `size`, instead of as a string that a PDF or a PNG was never going
     * to survive being read as. Absent, everything is text, exactly as before.
     */
    fun request(argument: String): String {
        val given = runCatching { mapper.readTree(argument) }.getOrNull()
            ?: return refusal("the arguments were not JSON")
        /*
         * Two calls through one door, told apart by the shape of what arrives,
         * as the PDF door tells its two apart: a request has always been an
         * array, and a WebSocket exchange is an object saying so. One grant,
         * because the reach is one reach - an address this server can get to,
         * through the same rules.
         */
        if (given.isObject && given.path("op").takeIf { it.isTextual }?.asString() == "websocket") return socket(given)
        if (!given.isArray || given.size() < 2) return refusal("that call takes a url and a method")

        val address = given.get(0)?.takeIf { it.isTextual }?.asString()?.trim()
            ?: return refusal("the first argument has to be a url")
        val uri = runCatching { URI.create(address) }.getOrNull()
            ?: return refusal("\"$address\" is not a url")
        /*
         * http and https and nothing else. `file:` would read the disk the
         * sandbox is kept off, and `jar:` and the rest are the same thought in
         * other spellings - a scheme this does not name is refused rather than
         * handed to whatever handler happens to be registered.
         */
        if (uri.scheme?.lowercase() !in ALLOWED) {
            return refusal("only http and https can be asked for, and that is ${uri.scheme ?: "no scheme"}")
        }

        val method = given.get(1)?.takeIf { it.isTextual }?.asString()?.uppercase() ?: "GET"
        if (method !in METHODS) return refusal("$method is not a method this makes")

        val options = given.get(4)?.takeIf { it.isObject }
        val sendBase64 = options?.path("sendBase64")?.asBoolean(false) ?: false
        val wantBytes = options?.path("wantBytes")?.asBoolean(false) ?: false

        val body = given.get(3)?.takeIf { it.isTextual }?.asString()
        val publisher = when {
            body == null -> HttpRequest.BodyPublishers.noBody()
            sendBase64 -> {
                val bytes = runCatching { java.util.Base64.getDecoder().decode(body) }.getOrNull()
                    ?: return refusal("the body was said to be base64 and is not")
                val mostUpload = tuning[Tunable.PLUGIN_UPLOAD_MB]
                if (bytes.size > mostUpload * 1024L * 1024L) {
                    return refusal("the upload was larger than $mostUpload MB")
                }
                HttpRequest.BodyPublishers.ofByteArray(bytes)
            }
            else -> HttpRequest.BodyPublishers.ofString(body)
        }
        val request = HttpRequest.newBuilder(uri)
            .timeout(READ_TIMEOUT)
            .method(method, publisher)

        headers(given.get(2)).forEach { (name, value) -> request.header(name, value) }

        return try {
            val mostMb = if (wantBytes) tuning[Tunable.PLUGIN_DOWNLOAD_MB] else tuning[Tunable.PLUGIN_HTTP_BODY_MB]
            val answered = when (
                val fetched = fetch.fetch(client(), request.build(), mostMb * 1024L * 1024L, followRedirects = true)
            ) {
                is io.mszymanski.orknux.server.security.GuardedFetch.Fetched.Refused ->
                    return refusal("that address may not be reached: ${fetched.why}")
                is io.mszymanski.orknux.server.security.GuardedFetch.Fetched.TooLarge ->
                    return refusal("the answer was larger than $mostMb MB")
                is io.mszymanski.orknux.server.security.GuardedFetch.Fetched.Answered -> fetched
            }
            if (wantBytes) {
                val held = answered.bytes

                val answer = mapper.createObjectNode()
                answer.put("status", answered.status)
                val headers = answer.putObject("headers")
                answered.headers.map().forEach { (name, values) -> headers.put(name, values.joinToString(", ")) }
                answer.put("base64", java.util.Base64.getEncoder().encodeToString(held))
                answer.put("size", held.size)
                answer.put("contentType", answered.headers.firstValue("content-type").orElse(null))
                mapper.writeValueAsString(answer)
            } else {
                val held = answered.text()

                val answer = mapper.createObjectNode()
                answer.put("status", answered.status)
                val headers = answer.putObject("headers")
                answered.headers.map().forEach { (name, values) -> headers.put(name, values.joinToString(", ")) }
                answer.put("body", held)
                mapper.writeValueAsString(answer)
            }
        } catch (failure: IOException) {
            refusal(failure.message ?: "it could not be reached")
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            refusal("the request was interrupted")
        }
    }

    /**
     * `{ op: "websocket", url, messages, headers, timeoutMs }`: opens the
     * socket, sends each message and waits for one whole text reply to it,
     * then closes - so the socket lives inside this call and never reaches
     * the guest, which is what the request door promises too.
     *
     * Wanted for the desktop applications that are driven over a local
     * WebSocket rather than HTTP - MuseScore's plugin API serves one - and for
     * anything else that only speaks it.
     *
     * One reply per message is the protocol this assumes, and the one those
     * services speak: a command and its answer. A service that pushes frames
     * of its own would have them taken as answers; it is not what this is for.
     *
     * Asked about the way a request is: the address its handshake goes to is
     * vetted first, and the client is the request door's own, so the proxy
     * rules and the trusted authorities hold. A handshake is not redirected,
     * so there is only the one address to ask about. Each reply is bounded as
     * a text answer is, and a failure partway answers what came back before it.
     */
    private fun socket(given: JsonNode): String {
        val address = given.path("url").takeIf { it.isTextual }?.asString()?.trim()
            ?: return refusal("a websocket call takes a url")
        val asked = runCatching { URI.create(address) }.getOrNull()
            ?: return refusal("\"$address\" is not a url")
        // http and https are accepted and spoken as ws and wss: a connection's address is stored as one of those.
        val secure = when (asked.scheme?.lowercase()) {
            "ws", "http" -> false
            "wss", "https" -> true
            else -> return refusal("only ws, wss, http and https can be opened as a websocket, and that is ${asked.scheme ?: "no scheme"}")
        }
        val target = URI(if (secure) "wss" else "ws", null, asked.host, asked.port, asked.path, asked.query, null)
        val handshake = URI(if (secure) "https" else "http", null, asked.host, asked.port, asked.path, asked.query, null)
        fetch.refusalFor(handshake)?.let { return refusal("that address may not be reached: $it") }

        val sent = given.path("messages")
        val messages = when {
            sent.isTextual -> listOf(sent.asString())
            sent.isArray -> (0 until sent.size()).map {
                sent.get(it)?.takeIf { one -> one.isTextual }?.asString()
                    ?: return refusal("message ${it + 1} is not text")
            }
            else -> return refusal("a websocket call takes the messages to send")
        }
        if (messages.isEmpty()) return refusal("there is nothing to send")
        if (messages.size > MOST_MESSAGES) return refusal("one call sends at most $MOST_MESSAGES messages, and that was ${messages.size}")

        val wait = given.path("timeoutMs").takeIf { it.isNumber }?.asLong()
            ?.coerceIn(1, READ_TIMEOUT.toMillis())?.let(Duration::ofMillis) ?: READ_TIMEOUT
        val most = tuning[Tunable.PLUGIN_HTTP_BODY_MB] * 1024L * 1024L
        val replies = mutableListOf<String>()
        val listener = Replies(most)

        val socket = try {
            val builder = client().newWebSocketBuilder().connectTimeout(CONNECT_TIMEOUT)
            headers(given.path("headers"))
                .filterKeys { !it.lowercase().startsWith("sec-websocket-") }
                .forEach { (name, value) -> builder.header(name, value) }
            builder.buildAsync(target, listener).get(CONNECT_TIMEOUT.toMillis() + 1000, TimeUnit.MILLISECONDS)
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            return refusal("could not open $target: ${reason(failure)}")
        }

        return try {
            for ((index, message) in messages.withIndex()) {
                val reply = listener.expect()
                try {
                    socket.sendText(message, true).get(wait.toMillis(), TimeUnit.MILLISECONDS)
                    replies += reply.get(wait.toMillis(), TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    return refusal("no reply to message ${index + 1} came within ${wait.toMillis()} ms", replies)
                } catch (failure: ExecutionException) {
                    return refusal("message ${index + 1} got no reply: ${reason(failure)}", replies)
                }
            }
            val answer = mapper.createObjectNode()
            val held = answer.putArray("replies")
            replies.forEach { held.add(it) }
            mapper.writeValueAsString(answer)
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            refusal("the exchange was interrupted", replies)
        } finally {
            runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "").get(1, TimeUnit.SECONDS) }
            socket.abort()
        }
    }

    /**
     * Gathers a socket's text into one reply at a time.
     *
     * A reply may arrive in pieces; it is whole at `last`. Anything else the
     * far side does while a reply is awaited - a binary frame, a close, an
     * error, a reply past the bound - fails the one awaited, in words.
     */
    private class Replies(private val most: Long) : WebSocket.Listener {
        private val text = StringBuilder()
        private val awaited = AtomicReference(CompletableFuture<String>())

        /** The next reply, to be awaited after the message it answers is sent. */
        fun expect(): CompletableFuture<String> = CompletableFuture<String>().also { awaited.set(it) }

        override fun onText(socket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            text.append(data)
            when {
                text.length > most -> {
                    awaited.get().completeExceptionally(IOException("the reply was larger than ${most / (1024 * 1024)} MB"))
                    socket.abort()
                    return null
                }
                last -> {
                    awaited.get().complete(text.toString())
                    text.setLength(0)
                }
            }
            socket.request(1)
            return null
        }

        override fun onBinary(socket: WebSocket, data: java.nio.ByteBuffer, last: Boolean): CompletionStage<*>? {
            awaited.get().completeExceptionally(IOException("it answered with binary, and only text replies are read"))
            socket.request(1)
            return null
        }

        override fun onClose(socket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            awaited.get().completeExceptionally(IOException("it closed the socket ($statusCode${if (reason.isBlank()) "" else ", $reason"})"))
            return null
        }

        override fun onError(socket: WebSocket, error: Throwable) {
            awaited.get().completeExceptionally(error)
        }
    }

    /** The sentence under a failure, unwrapped from the futures it travelled through. */
    private fun reason(failure: Throwable): String {
        var cause: Throwable = failure
        while ((cause is ExecutionException || cause is CompletionException) && cause.cause != null) cause = cause.cause!!
        return cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName
    }

    private fun refusal(why: String, replies: List<String>): String {
        val answer = mapper.createObjectNode().put("error", why)
        val held = answer.putArray("replies")
        replies.forEach { held.add(it) }
        log.debug("A plugin's websocket exchange was refused: {}", why)
        return mapper.writeValueAsString(answer)
    }

    /**
     * The headers a plugin asked for, minus the ones it may not set.
     *
     * Dropped rather than refused: a plugin setting `Host` is much more likely
     * to be copying an example than to be up to something, and failing its whole
     * call over a header nobody reads teaches nothing. What it may not do is
     * make this server's own request look like it came from somewhere else.
     */
    private fun headers(given: JsonNode?): Map<String, String> {
        if (given == null || !given.isObject) return emptyMap()
        return given.propertyNames()
            .filter { it.lowercase() !in FORBIDDEN }
            .mapNotNull { name -> given.path(name).takeIf { it.isTextual }?.let { name to it.asString() } }
            .toMap()
    }

    private fun refusal(why: String): String =
        mapper.writeValueAsString(mapper.createObjectNode().put("error", why))
            .also { log.debug("A plugin's request was refused: {}", why) }

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        val READ_TIMEOUT: Duration = Duration.ofSeconds(30)

        /*
         * The ceilings on a text answer (large enough for an API's answer, small
         * enough that it stays a string), a binary answer and an upload are
         * Tunable.PLUGIN_*, issue #453. The binary two are on the bytes rather
         * than the base64: what crosses the sandbox is a third again larger, and
         * both ends of that trip live in memory while it is made.
         */

        val ALLOWED = setOf("http", "https")

        val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")

        /** Messages one websocket call sends: room for a batch of commands, not a stream. */
        const val MOST_MESSAGES = 50

        /**
         * Headers the caller does not get to set.
         *
         * `Host` and the forwarding ones because they are how a request is made
         * to look like somebody else's, and the rest because the client owns
         * them and a plugin setting one gets an answer it cannot read.
         */
        val FORBIDDEN = setOf(
            "host", "content-length", "connection", "upgrade", "transfer-encoding",
            "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "forwarded",
        )
    }
}
