package io.mszymanski.orknux.server.plugin

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.workflow.script.PluginCapability
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * The widest capability there is, and the things that hold it in.
 *
 * A plugin an administrator has agreed to can ask the server to make a request.
 * That is a real grant with a real cost — it reaches anything the server can —
 * so what is pinned here is less "it works" than the shape of it:
 *
 *   it works      a granted plugin gets a status, headers and a body back
 *   as data       and gets them as JSON, so there is no socket, no stream and
 *                 nothing to keep open on either side
 *   schemes       `file:` is refused. It would read the disk the sandbox is
 *                 kept off, which is the one thing this must never become
 *   methods       an invented one is refused rather than sent
 *   headers       a plugin cannot set `Host` or the forwarding ones, so it
 *                 cannot make this server's request look like somebody else's
 *   refusals      a host that is not there comes back as data, because a plugin
 *                 has to be able to say something about it
 *
 * Not covered here, and deliberately: where a request may *get to*. That is the
 * proxy rules, they are `ProxyRouter`'s, and a copy of them here would be a
 * second opinion about the thing that governs every other outbound call.
 */
@SpringBootTest
class NetworkCapabilityTest(@Autowired val host: SlackPluginHost, @Autowired val mapper: ObjectMapper) {

    private fun ask(vararg given: Any?): Map<*, *> {
        val argument = mapper.writeValueAsString(given.toList())
        return mapper.readValue(host.ask(PluginCapability.NETWORK_REQUEST, argument, null), Map::class.java)
    }

    @Test
    fun `a granted plugin gets the answer, as data`() {
        val answered = ask("http://${where()}/hello", "GET", emptyMap<String, String>(), null)

        assertThat(answered["status"]).isEqualTo(200)
        assertThat(answered["body"]).isEqualTo("""{"said":"hello"}""")
        @Suppress("UNCHECKED_CAST")
        val headers = answered["headers"] as Map<String, String>
        assertThat(headers.keys.map { it.lowercase() }).contains("content-type")
    }

    /**
     * A rate limit is handed back as it came, and noticed on the way: GitHub's
     * answer when its search allowance is spent, a 403 saying none remain and
     * when they come back. The plugin still sees the 403 - the door does not
     * decide for it - and whoever ran the plugin learns when to ask again.
     */
    @Test
    fun `a rate limit is handed back and noticed, with when it lifts`() {
        val (answered, limited) = PluginRateLimits.watching { ask("http://${where()}/limited", "GET", emptyMap<String, String>(), null) }

        assertThat(answered["status"]).isEqualTo(403)
        assertThat(limited).isNotNull()
        assertThat(limited!!.host).isEqualTo(server.address.hostString)
        assertThat(limited.until).isEqualTo(java.time.Instant.ofEpochSecond(RESET))

        // And an ordinary answer is not a limit.
        val (_, none) = PluginRateLimits.watching { ask("http://${where()}/hello", "GET", emptyMap<String, String>(), null) }
        assertThat(none).isNull()
    }

    /**
     * The one refusal that matters most.
     *
     * `file:` would read the filesystem this sandbox is built to be kept off,
     * and the whole design rests on a capability being a function rather than a
     * way back to the machine.
     */
    @Test
    fun `a scheme that is not http is refused`() {
        assertThat(ask("file:///etc/passwd", "GET")["error"] as String)
            .contains("only http and https")

        assertThat(ask("jar:file:///tmp/x.jar!/y", "GET")["error"]).isNotNull()
    }

    @Test
    fun `a method it does not make is refused rather than sent`() {
        assertThat(ask("http://${where()}/hello", "TRACE")["error"] as String).contains("TRACE")
    }

    /**
     * A plugin cannot make this server's request look like somebody else's.
     *
     * Dropped rather than refused: setting `Host` is far more often somebody
     * copying an example than somebody up to something, and failing the whole
     * call over a header nobody reads teaches nothing.
     */
    @Test
    fun `the headers that would disguise the request are not sent`() {
        ask(
            "http://${where()}/echo",
            "GET",
            mapOf("Host" to "elsewhere.example", "X-Forwarded-For" to "10.0.0.1", "X-Asked" to "yes"),
            null,
        )

        assertThat(seen).containsKey("x-asked")
        assertThat(seen).describedAs("a plugin cannot forge where a request came from").doesNotContainKey("x-forwarded-for")
        assertThat(seen["host"]).isEqualTo(where())
    }

    @Test
    fun `a host that is not there comes back as a sentence`() {
        val answered = ask("http://127.0.0.1:1/nothing", "GET")

        assertThat(answered["error"]).isNotNull()
        assertThat(answered["status"]).isNull()
    }

    @Test
    fun `what is not a url at all is refused before anything is sent`() {
        assertThat(ask("not a url", "GET")["error"]).isNotNull()
        assertThat(ask(null, "GET")["error"] as String).contains("has to be a url")
    }

    /**
     * Binary crosses as base64, in both directions.
     *
     * What is pinned is that the bytes on the wire are the *decoded* ones - a
     * server receiving a file must receive the file, not sixty-six characters
     * of alphabet - and that bytes coming back are not read as a string, which
     * a PNG does not survive.
     */
    @Test
    fun `an upload marked base64 sends the decoded bytes`() {
        val answered = ask(
            "http://${where()}/echo",
            "POST",
            mapOf("Content-Type" to "application/octet-stream"),
            java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 0, -1)),
            mapOf("sendBase64" to true),
        )

        assertThat(answered["status"]).isEqualTo(200)
        assertThat(seenBody).containsExactly(1, 2, 3, 0, -1)
    }

    @Test
    fun `a body that claims to be base64 and is not is refused in words`() {
        assertThat(ask("http://${where()}/echo", "POST", null, "not base64!!!", mapOf("sendBase64" to true))["error"] as String)
            .contains("base64")
    }

    @Test
    fun `a download answers base64 beside what the bytes claim to be`() {
        val answered = ask("http://${where()}/bytes", "GET", null, null, mapOf("wantBytes" to true))

        assertThat(answered["status"]).isEqualTo(200)
        assertThat(answered["contentType"]).isEqualTo("image/png")
        assertThat(answered["size"]).isEqualTo(4)
        val held = java.util.Base64.getDecoder().decode(answered["base64"] as String)
        assertThat(held).containsExactly(-119, 80, 78, 71)
        assertThat(answered["body"]).describedAs("bytes never come back pretending to be text").isNull()
    }

    companion object {

        /** When the stub's rate limit says it lifts, as epoch seconds. */
        const val RESET = 1_893_456_000L

        /** What the last request carried, so the headers can be looked at. */
        val seen = mutableMapOf<String, String>()

        /** And its body as the bytes that actually arrived, for the upload test. */
        var seenBody: ByteArray = ByteArray(0)

        private val server: HttpServer =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                createContext("/") { exchange ->
                    seen.clear()
                    exchange.requestHeaders.forEach { (name, values) ->
                        seen[name.lowercase()] = values.joinToString(", ")
                    }
                    seenBody = exchange.requestBody.use { it.readBytes() }
                    val body = """{"said":"hello"}""".toByteArray(StandardCharsets.UTF_8)
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                    exchange.close()
                }
                // GitHub's spent search allowance, as it answers it.
                createContext("/limited") { exchange ->
                    val body = """{"message":"API rate limit exceeded"}""".toByteArray(StandardCharsets.UTF_8)
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.responseHeaders.add("x-ratelimit-remaining", "0")
                    exchange.responseHeaders.add("x-ratelimit-reset", RESET.toString())
                    exchange.sendResponseHeaders(403, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                    exchange.close()
                }
                // Four bytes a string cannot hold: a PNG signature's first half.
                createContext("/bytes") { exchange ->
                    val body = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
                    exchange.responseHeaders.add("Content-Type", "image/png")
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                    exchange.close()
                }
                start()
            }

        private fun where() = "${server.address.hostString}:${server.address.port}"

        @JvmStatic
        @AfterAll
        fun stop() = server.stop(0)
    }
}
