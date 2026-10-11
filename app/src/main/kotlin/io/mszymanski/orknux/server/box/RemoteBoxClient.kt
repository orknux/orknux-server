package io.mszymanski.orknux.server.box

import io.mszymanski.orknux.orkbox.model.CreateSessionRequest
import io.mszymanski.orknux.orkbox.model.ForwardRequest
import io.mszymanski.orknux.orkbox.model.PublishedPort
import io.mszymanski.orknux.orkbox.model.DirectoryListing
import io.mszymanski.orknux.orkbox.model.EditRequest
import io.mszymanski.orknux.orkbox.model.EditResult
import io.mszymanski.orknux.orkbox.model.ExecuteRequest
import io.mszymanski.orknux.orkbox.model.ExecutionDto
import io.mszymanski.orknux.orkbox.model.FileEntry
import io.mszymanski.orknux.orkbox.model.Info
import io.mszymanski.orknux.orkbox.model.MoveRequest
import io.mszymanski.orknux.orkbox.model.Page
import io.mszymanski.orknux.orkbox.model.SearchRequest
import io.mszymanski.orknux.orkbox.model.SearchResult
import io.mszymanski.orknux.orkbox.model.SessionDto
import io.mszymanski.orknux.orkbox.model.SignalRequest
import io.mszymanski.orknux.orkbox.model.UpdateSessionRequest
import io.mszymanski.orknux.orkbox.model.WriteResult
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.serializer
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * A remote box: an orkbox server spoken to over its HTTP Session API.
 *
 * **Hand-written, because there is nothing to use instead.** orkbox publishes
 * no client library - only the server and `orkbox-core`, the library the local
 * box embeds. What this does reuse is that library's model classes and their
 * kotlinx.serialization serializers, so every request and response shape is
 * orkbox's own and a field it adds is one this carries without being told. The
 * protocol left to write is a URL per operation and a bearer token.
 *
 * **Under the Networking rules, as every outbound call is.** [client] is
 * `ProxyRouter.client()`, so a proxy rule matching a box's address carries its
 * requests, with the rule's credentials, and the trusted certificates apply.
 * And every request is first put past [vet] - the address guard
 * (`ConnectionProbe.vet`) the model, MCP and HTTP clients ask - so a box at an
 * address this installation refuses to call is refused, with the guard's
 * reason, before anything is sent: the proxy selector can route but cannot say
 * no, which is why the guard is asked separately.
 *
 * Every request carries [timeout]; a call that waits for an execution carries
 * that wait on top of it, since orkbox holds the response open for exactly that
 * long. A failure orkbox reports comes back as a [BoxCallException] with its
 * problem+json `code`; one that never reached it, with `unreachable`.
 */
class RemoteBoxClient(
    address: String,
    private val token: String?,
    private val client: HttpClient,
    private val timeout: Duration,
    /** Why a URL may not be called, or null where it may; see the class comment. */
    private val vet: (String) -> String? = { null },
) : BoxClient {

    private val base = address.trimEnd('/')

    /** Whether the box says it can create sessions: `/readyz`, which needs no token. */
    fun health(): Pair<Boolean, String?> = try {
        vet("$base/readyz")?.let { return false to "$base cannot be called: $it" }
        val response = client.send(
            HttpRequest.newBuilder(URI.create("$base/readyz")).timeout(timeout).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        val body = response.body().trim().take(MOST_DETAIL)
        if (response.statusCode() == 200) {
            true to null
        } else {
            false to body.ifEmpty { "HTTP ${response.statusCode()}" }
        }
    } catch (failed: IOException) {
        false to describe(failed)
    } catch (failed: IllegalArgumentException) {
        false to (failed.message ?: "not a usable address")
    }

    override fun info(): Info = get("/v1/info", serializer())

    override fun sessions(status: String?): List<SessionDto> {
        val all = mutableListOf<SessionDto>()
        var cursor: String? = null
        repeat(MOST_PAGES) {
            val page: Page<SessionDto> = get(
                "/v1/sessions" + query("status" to status, "limit" to PAGE_SIZE.toString(), "cursor" to cursor),
                serializer(),
            )
            all += page.items
            cursor = page.nextCursor ?: return all
        }
        return all
    }

    override fun create(request: CreateSessionRequest): SessionDto =
        send("POST", "/v1/sessions", encode(request), serializer())

    override fun session(id: String): SessionDto = get(sessionPath(id), serializer())

    override fun forward(id: String, port: Int): PublishedPort =
        send("POST", sessionPath(id) + "/ports", encode(ForwardRequest(port)), serializer())

    override fun unforward(id: String, port: Int) {
        try {
            exchange("DELETE", sessionPath(id) + "/ports/$port", null)
        } catch (gone: BoxCallException) {
            if (gone.code != "port_not_forwarded") throw gone
        }
    }

    override fun update(id: String, request: UpdateSessionRequest): SessionDto =
        send("PATCH", sessionPath(id), encode(request), serializer())

    override fun stop(id: String): SessionDto = send("DELETE", sessionPath(id), null, serializer())

    override fun execute(id: String, request: ExecuteRequest): ExecutionDto {
        val waiting = if (request.wait) (request.waitSeconds ?: EmbeddedBoxClient.DEFAULT_WAIT_SECONDS) else 0
        return send("POST", sessionPath(id) + "/executions", encode(request), serializer(), Duration.ofSeconds(waiting))
    }

    override fun execution(id: String, executionId: String, waitSeconds: Long?, maxOutputBytes: Int): ExecutionDto =
        send(
            "GET",
            executionPath(id, executionId) + query(
                "wait" to waitSeconds?.let { "true" },
                "waitSeconds" to waitSeconds?.toString(),
                "maxOutputBytes" to maxOutputBytes.toString(),
            ),
            null,
            serializer(),
            Duration.ofSeconds(waitSeconds ?: 0),
        )

    override fun output(id: String, executionId: String, stream: String, offset: Long, limit: Int): BoxOutput {
        val response = exchange(
            "GET",
            executionPath(id, executionId) + "/output" +
                query("stream" to stream, "offset" to offset.toString(), "limit" to limit.toString()),
            null,
        )
        val text = String(response.body(), Charsets.UTF_8)
        val next = response.headers().firstValue("X-Next-Offset").orElse(null)?.toLongOrNull()
            ?: (offset + response.body().size)
        return BoxOutput(next - response.body().size, next, text, response.headers().firstValue("X-Execution-Status").orElse(null))
    }

    override fun stdin(id: String, executionId: String, input: String, close: Boolean) {
        exchange("POST", executionPath(id, executionId) + "/stdin" + query("close" to close.toString()), input.toByteArray(), "application/octet-stream")
    }

    override fun signal(id: String, executionId: String, signal: String): ExecutionDto =
        send("POST", executionPath(id, executionId) + "/signal", encode(SignalRequest(signal)), serializer())

    override fun files(
        id: String,
        path: String?,
        depth: Int?,
        glob: String?,
        includeHidden: Boolean,
        limit: Int,
    ): DirectoryListing = get(
        sessionPath(id) + "/files" + query(
            "path" to path,
            "depth" to depth?.toString(),
            "glob" to glob,
            "includeHidden" to includeHidden.toString(),
            "limit" to limit.toString(),
        ),
        serializer(),
    )

    override fun read(id: String, path: String, startLine: Int?, endLine: Int?): BoxFile {
        val response = exchange(
            "GET",
            sessionPath(id) + "/files/content" +
                query("path" to path, "startLine" to startLine?.toString(), "endLine" to endLine?.toString()),
            null,
        )
        val headers = response.headers()
        val text = headers.firstValue("Content-Type").orElse("").startsWith("text/")
        return BoxFile(
            bytes = response.body(),
            text = text,
            size = headers.firstValue("X-File-Size").orElse(null)?.toLongOrNull() ?: response.body().size.toLong(),
            lineCount = headers.firstValue("X-Line-Count").orElse(null)?.toIntOrNull(),
            etag = headers.firstValue("ETag").orElse(null),
        )
    }

    override fun write(id: String, path: String, content: ByteArray, mode: String?): WriteResult =
        decode(
            exchange(
                "PUT",
                sessionPath(id) + "/files/content" + query("path" to path, "mode" to mode),
                content,
                "application/octet-stream",
            ),
            serializer(),
        )

    override fun edit(id: String, request: EditRequest): EditResult =
        send("POST", sessionPath(id) + "/files/edit", encode(request), serializer())

    override fun stat(id: String, path: String): FileEntry =
        get(sessionPath(id) + "/files/stat" + query("path" to path), serializer())

    override fun delete(id: String, path: String, recursive: Boolean) {
        exchange("DELETE", sessionPath(id) + "/files" + query("path" to path, "recursive" to recursive.toString()), null)
    }

    override fun move(id: String, request: MoveRequest) {
        exchange("POST", sessionPath(id) + "/files/move", encode(request).toByteArray(), JSON_TYPE)
    }

    override fun search(id: String, request: SearchRequest): SearchResult =
        send("POST", sessionPath(id) + "/files/search", encode(request), serializer())

    override fun logs(id: String, since: Long, limit: Int, types: Set<String>?): BoxLogPage {
        val response = exchange(
            "GET",
            sessionPath(id) + "/logs" +
                query("since" to since.toString(), "limit" to limit.toString(), "types" to types?.joinToString(",")),
            null,
        )
        val page = JSON.parseToJsonElement(String(response.body(), Charsets.UTF_8)).jsonObject
        val events = page["items"]?.jsonArray.orEmpty().map { item ->
            val event = item.jsonObject
            BoxLogEvent(
                seq = event["seq"]?.jsonPrimitive?.longOrNull ?: 0,
                at = event["at"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                type = event["type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                data = (event["data"] as? JsonObject ?: JsonObject(emptyMap())).toString(),
            )
        }
        return BoxLogPage(events, page["nextSeq"]?.jsonPrimitive?.longOrNull ?: since)
    }

    override fun exportArchive(id: String, path: String?): ByteArray =
        exchange("GET", sessionPath(id) + "/archive" + query("path" to path), null).body()

    override fun importArchive(id: String, path: String?, archive: ByteArray) {
        exchange("PUT", sessionPath(id) + "/archive" + query("path" to path), archive, "application/gzip")
    }

    // ---------------------------------------------------------------- plumbing

    private fun <T> get(path: String, as_: KSerializer<T>): T = send("GET", path, null, as_)

    private fun <T> send(method: String, path: String, json: String?, as_: KSerializer<T>, waiting: Duration = Duration.ZERO): T =
        decode(exchange(method, path, json?.toByteArray(), JSON_TYPE, waiting), as_)

    private fun <T> decode(response: HttpResponse<ByteArray>, as_: KSerializer<T>): T = try {
        JSON.decodeFromString(as_, String(response.body(), Charsets.UTF_8))
    } catch (unreadable: IllegalArgumentException) {
        throw BoxCallException(response.statusCode(), "invalid_response", "The box answered with something that is not orkbox's: ${unreadable.message}")
    }

    private inline fun <reified T> encode(value: T): String = JSON.encodeToString(serializer<T>(), value)

    /**
     * One request; a 2xx answer comes back, anything else is a [BoxCallException]
     * with the code orkbox put in its problem+json.
     */
    private fun exchange(
        method: String,
        path: String,
        body: ByteArray?,
        contentType: String = JSON_TYPE,
        waiting: Duration = Duration.ZERO,
    ): HttpResponse<ByteArray> {
        vet(base + path)?.let { throw BoxCallException(null, BoxCallException.REFUSED, "$base cannot be called: $it") }
        val request = try {
            HttpRequest.newBuilder(URI.create(base + path))
                .timeout(timeout.plus(waiting))
                .header("Accept", "application/json")
                .apply { token?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }
                .apply { if (body != null) header("Content-Type", contentType) }
                .method(method, body?.let { HttpRequest.BodyPublishers.ofByteArray(it) } ?: HttpRequest.BodyPublishers.noBody())
                .build()
        } catch (invalid: IllegalArgumentException) {
            throw BoxCallException(null, BoxCallException.UNREACHABLE, "$base is not a usable address: ${invalid.message}")
        }
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        } catch (failed: IOException) {
            throw BoxCallException(null, BoxCallException.UNREACHABLE, describe(failed))
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw BoxCallException(null, BoxCallException.UNREACHABLE, "interrupted while waiting for the box")
        }
        if (response.statusCode() in 200..299) return response
        throw refusal(response)
    }

    private fun refusal(response: HttpResponse<ByteArray>): BoxCallException {
        val text = String(response.body(), Charsets.UTF_8)
        val problem = runCatching { JSON.parseToJsonElement(text).jsonObject }.getOrNull()
        val code = problem?.get("code")?.jsonPrimitive?.contentOrNull
            ?: if (response.statusCode() == 401) "unauthorized" else "http_${response.statusCode()}"
        val said = problem?.get("detail")?.jsonPrimitive?.contentOrNull
            ?: problem?.get("title")?.jsonPrimitive?.contentOrNull
            ?: text.trim().take(MOST_DETAIL).ifEmpty { "HTTP ${response.statusCode()}" }
        return BoxCallException(response.statusCode(), code, said)
    }

    private fun describe(failed: IOException): String = when (failed) {
        is java.net.http.HttpTimeoutException -> "it did not answer within ${timeout.seconds} seconds"
        is java.net.ConnectException -> "nothing is listening at $base"
        else -> failed.message?.takeIf { it.isNotBlank() } ?: failed.javaClass.simpleName
    }

    private fun sessionPath(id: String) = "/v1/sessions/" + encodePath(id)

    private fun executionPath(id: String, executionId: String) =
        sessionPath(id) + "/executions/" + encodePath(executionId)

    private fun encodePath(part: String) = URLEncoder.encode(part, Charsets.UTF_8).replace("+", "%20")

    private fun query(vararg parts: Pair<String, String?>): String =
        parts.mapNotNull { (name, value) -> value?.let { name to it } }
            .joinToString("&", prefix = "?") { (name, value) -> name + "=" + URLEncoder.encode(value, Charsets.UTF_8) }
            .let { if (it == "?") "" else it }

    private companion object {
        const val JSON_TYPE = "application/json"
        const val PAGE_SIZE = 1000
        const val MOST_PAGES = 20
        const val MOST_DETAIL = 1000

        /** Lenient on the way in, so a field a newer orkbox adds is not a failure here. */
        val JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }
    }
}
