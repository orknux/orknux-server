package io.mszymanski.orknux.server.tin

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An orktin server as far as a test needs one: a few routes on a port of its
 * own, answering what orktin would, and a note of what it was asked. Nothing
 * leaves the machine.
 */
class StubOrktin(private val token: String = "change-me") : AutoCloseable {

    /** Every request: method, path with query, and the Authorization header. */
    val asked = CopyOnWriteArrayList<Triple<String, String, String?>>()

    var ready = true

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange -> handle(exchange) }
        start()
    }

    val address: String get() = "http://127.0.0.1:${server.address.port}"

    private fun handle(exchange: HttpExchange) {
        val path = exchange.requestURI.rawPath + (exchange.requestURI.rawQuery?.let { "?$it" } ?: "")
        val auth = exchange.requestHeaders.getFirst("Authorization")
        asked += Triple(exchange.requestMethod, path, auth)
        exchange.requestBody.readAllBytes()
        when {
            exchange.requestURI.path == "/readyz" ->
                if (ready) reply(exchange, 200, "ok", "text/plain") else reply(exchange, 503, "docker: daemon not reachable", "text/plain")

            auth != "Bearer $token" -> reply(
                exchange,
                401,
                """{"type":"about:blank","title":"Unauthorized","status":401,"code":"unauthorized","detail":"A valid bearer token is required"}""",
                "application/problem+json",
            )

            exchange.requestURI.path == "/v1/info" -> reply(exchange, 200, INFO, "application/json")

            exchange.requestURI.path == "/v1/sessions" && exchange.requestMethod == "GET" ->
                reply(exchange, 200, """{"items":[$SESSION,$SESSION]}""", "application/json")

            exchange.requestURI.path == "/v1/sessions/ses_1/logs" -> {
                val since = query(exchange, "since")?.toLongOrNull() ?: 0
                val items = LOG.filter { it.first > since }.joinToString(",") { it.second }
                reply(exchange, 200, """{"items":[$items],"nextSeq":${LOG.map { it.first }.filter { it > since }.maxOrNull() ?: since}}""", "application/json")
            }

            exchange.requestURI.path == "/v1/sessions/ses_1/executions/exe_1/output" -> {
                val offset = (query(exchange, "offset")?.toIntOrNull() ?: 0).coerceAtMost(OUTPUT.length)
                exchange.responseHeaders.add("X-Next-Offset", OUTPUT.length.toString())
                exchange.responseHeaders.add("X-Execution-Status", "succeeded")
                reply(exchange, 200, OUTPUT.substring(offset), "application/octet-stream")
            }

            exchange.requestURI.path.startsWith("/v1/sessions/ses_gone") -> reply(
                exchange,
                404,
                """{"type":"about:blank","title":"Session not found","status":404,"code":"session_not_found","detail":"No session ses_gone"}""",
                "application/problem+json",
            )

            else -> reply(exchange, 404, """{"code":"not_found","title":"Not found","status":404}""", "application/problem+json")
        }
    }

    private fun query(exchange: HttpExchange, name: String): String? =
        exchange.requestURI.rawQuery?.split("&")?.map { it.substringBefore("=") to it.substringAfter("=", "") }
            ?.firstOrNull { it.first == name }?.second

    private fun reply(exchange: HttpExchange, status: Int, body: String, type: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", type)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() = server.stop(0)

    companion object {
        /** ses_1's log: a session opened, and one command run in it. */
        val LOG = listOf(
            1L to """{"seq":1,"at":"2026-10-10T10:00:00Z","type":"session.created","data":{"runtime":"docker"}}""",
            2L to """{"seq":2,"at":"2026-10-10T10:00:01Z","type":"execution.finished","data":{"executionId":"exe_1","status":"succeeded","exitCode":0}}""",
        )

        /** What exe_1 in ses_1 printed. */
        const val OUTPUT = "hello\n"

        const val INFO = """{"name":"orktin","version":"9.9.9","defaultRuntime":"docker","runtimes":[
            {"name":"docker","available":true,"workdir":"/workspace","shell":["bash","-c"],"os":"linux",
             "images":["*"],"defaultImage":"orknux/orktin-toolchain:latest",
             "defaultResources":{"cpus":2.0,"memoryMb":4096},"maxResources":{"cpus":8.0,"memoryMb":16384},
             "diskLimitEnforced":false,"networkModes":["full","none"],"caches":["maven","npm"],"futureField":1}],
            "defaultTtlSeconds":7200,"maxTtlSeconds":86400,"defaultIdleTimeoutSeconds":900,"maxSessions":50}"""

        const val SESSION = """{"id":"ses_1","status":"ready","runtime":"docker","image":"img","workdir":"/workspace",
            "env":{},"resources":null,"network":"full","caches":[],"labels":{"orknux.agent":"7"},
            "createdAt":"2026-10-10T10:00:00Z","lastActivityAt":"2026-10-10T10:01:00Z","expiresAt":"2026-10-10T12:00:00Z",
            "idleTimeoutSeconds":900,"stoppedAt":null,"stopReason":null,"failure":null,
            "executions":{"total":3,"running":1},"usage":null}"""
    }
}
