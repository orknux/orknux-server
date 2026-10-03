package io.mszymanski.orknux.server.security

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.server.embedded.HttpCapability
import io.mszymanski.orknux.server.plugin.NetworkPluginHost
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * The two doors a workspace's code and an agent go out through ask of every
 * address whether it may be reached, and read no further than they may. #588.
 *
 * Cloud instance metadata is the address that matters: a function that can
 * read `169.254.169.254` can read the machine's cloud credentials. Nothing here
 * reaches it - the address is refused before anything is sent - and a stub on
 * loopback stands in for an outside server that redirects there.
 */
@SpringBootTest
class GuardedFetchTest(
    @Autowired val network: NetworkPluginHost,
    @Autowired val http: HttpCapability,
) {

    private lateinit var server: HttpServer
    private val base get() = "http://127.0.0.1:${server.address.port}"

    @BeforeEach
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/to-metadata") { exchange ->
            exchange.responseHeaders.add("Location", "http://169.254.169.254/latest/meta-data/")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/to-ok") { exchange ->
            exchange.responseHeaders.add("Location", "/ok")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/ok") { exchange ->
            val body = "fine".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/endless") { exchange ->
            // Twelve megabytes, which is past every limit these doors have.
            exchange.sendResponseHeaders(200, 0)
            val chunk = "x".repeat(64 * 1024).toByteArray(StandardCharsets.UTF_8)
            runCatching { exchange.responseBody.use { out -> repeat(192) { out.write(chunk) } } }
            exchange.close()
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `a function cannot read cloud metadata, directly or by a redirect`() {
        assertThat(network.request("""["http://169.254.169.254/latest/meta-data/", "GET"]"""))
            .contains("may not be reached")
        assertThat(network.request("""["http://[fd00:ec2::254]/latest/", "GET"]"""))
            .contains("may not be reached")
        assertThat(network.request("""["$base/to-metadata", "GET"]"""))
            .contains("may not be reached")
    }

    @Test
    fun `a function still follows a redirect that stays where it may go`() {
        val answer = network.request("""["$base/to-ok", "GET"]""")
        assertThat(answer).contains("\"status\":200").contains("\"body\":\"fine\"")
    }

    @Test
    fun `a function's answer is read no further than its limit`() {
        assertThat(network.request("""["$base/endless", "GET"]""")).contains("larger than")
    }

    @Test
    fun `an agent's http tool cannot read cloud metadata either`() {
        assertThat(http.run("get", """{"url":"http://169.254.169.254/latest/meta-data/"}""", 1, null))
            .contains("may not be reached")
    }

    @Test
    fun `an agent's http tool cuts a long page rather than holding it`() {
        val answer = http.run("get", """{"url":"$base/endless"}""", 1, null)
        assertThat(answer).contains("\"status\":200")
        assertThat(answer.length).isLessThan(1_000_000)
    }
}
