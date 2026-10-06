package io.mszymanski.orknux.server.integration

import com.slack.api.socket_mode.SocketModeClient
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.connection.ConnectionType
import io.mszymanski.orknux.connector.connection.Delivery
import io.mszymanski.orknux.connector.connection.OutgoingMessages
import io.mszymanski.orknux.connector.connection.SlackBotUsers
import io.mszymanski.orknux.connector.connection.SlackClients
import io.mszymanski.orknux.connector.connection.SlackDirectory
import io.mszymanski.orknux.connector.connection.SlackListener
import io.mszymanski.orknux.connector.connection.SlackProperties
import io.mszymanski.orknux.connector.connection.WorkspaceConnection
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.connector.proxy.ProxyRule
import io.mszymanski.orknux.connector.proxy.ProxyRuleSource
import io.mszymanski.orknux.server.security.plainCredentials
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.context.ApplicationEventPublisher
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Whether Slack goes through the proxy rules - the Web API and the websocket
 * both.
 *
 * It used to go round them entirely, and the reason that was worth a defect
 * rather than a feature request is that nothing said so: an administrator wrote
 * a rule for `slack.com`, the rules page showed it as the rule that answers, and
 * Slack still could not reach anything. So the assertions here are all of the
 * same kind - a listener on the loopback address that only a proxied call could
 * possibly have reached, and proof that Slack's call reached it.
 *
 * Two listeners, because Slack needs two kinds of proof. A plain HTTP request
 * routed through a proxy arrives with the whole URL on the request line rather
 * than just the path, so a recorded absolute URL is the Web API's evidence. A
 * websocket has to be tunnelled, so a recorded `CONNECT` line naming a host this
 * machine cannot resolve is the socket's: nothing but a proxy could have carried
 * it.
 */
class SlackProxyRoutingTest {

    private lateinit var proxy: HttpServer
    private lateinit var slack: HttpServer
    private lateinit var tunnels: ServerSocket

    /** The absolute URLs the proxy was asked to fetch. Empty means it was never used. */
    private val proxied = CopyOnWriteArrayList<String>()

    /** What the proxy was told to authenticate with, if anything. */
    private val proxyCredentials = CopyOnWriteArrayList<String>()

    /** The paths that reached Slack's stand-in without a proxy in between. */
    private val direct = CopyOnWriteArrayList<String>()

    /** The request lines the tunnelling proxy saw, which are `CONNECT host:port`. */
    private val tunnelled = CopyOnWriteArrayList<String>()

    /** Set to make the proxy demand credentials before it will do anything. */
    private var demandsCredentials = false

    @BeforeEach
    fun start() {
        proxied.clear()
        proxyCredentials.clear()
        direct.clear()
        tunnelled.clear()
        demandsCredentials = false

        proxy = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        proxy.createContext("/") { exchange ->
            val offered = exchange.requestHeaders.getFirst("Proxy-Authorization")
            if (demandsCredentials && offered == null) {
                // The challenge a proxy sends when it wants to know who is
                // asking. A client only sends credentials in answer to one.
                exchange.responseHeaders.add("Proxy-Authenticate", """Basic realm="orknux"""")
                exchange.sendResponseHeaders(407, -1)
                exchange.close()
                return@createContext
            }
            if (offered != null) proxyCredentials += offered
            proxied += exchange.requestURI.toString()
            respond(exchange, answerFor(exchange.requestURI.path))
        }
        proxy.start()

        slack = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        slack.createContext("/") { exchange ->
            direct += exchange.requestURI.path
            respond(exchange, answerFor(exchange.requestURI.path))
        }
        slack.start()

        tunnels = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
        Thread {
            while (!tunnels.isClosed) {
                try {
                    tunnels.accept().use { connection ->
                        val line = BufferedReader(InputStreamReader(connection.getInputStream())).readLine()
                        if (line != null) tunnelled += line
                        // Refused rather than tunnelled: the point was reaching
                        // here at all, and a real tunnel would need a websocket
                        // server behind it to prove nothing more.
                        connection.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                    }
                } catch (_: Exception) {
                    // The socket was closed to end the test, or a client hung up.
                }
            }
        }.apply { isDaemon = true }.start()
    }

    @AfterEach
    fun stop() {
        proxy.stop(0)
        slack.stop(0)
        tunnels.close()
    }

    @Test
    fun `a message posted to Slack goes through the rule matching Slack's address`() {
        val messages = outgoing(rule(name = "Slack", pattern = slackHost()))

        val delivery = messages.send(CONNECTION_ID, "#general", "through the proxy")

        assertThat(delivery).isEqualTo(Delivery.Sent("C0000000001", "1700000000.000100"))
        // The whole URL on the request line is what tells a proxy where to go,
        // and being asked to fetch it is the only thing that could have put it
        // there. Before this change the SDK's own client sent it straight out.
        // The SDK used to call `auth.test` first as well, to file the post under
        // a team in its call statistics; those are switched off (#616), so the
        // calls here are the ones this application makes.
        assertThat(proxied).contains(
            // Where the message was addressed is looked up before it is sent,
            // so this is a third call the test never asked for and a third one
            // that has to be routed.
            "${slackUrl()}/api/conversations.list",
            "${slackUrl()}/api/chat.postMessage",
        )
        assertThat(direct).isEmpty()
    }

    @Test
    fun `a message matching no rule goes straight out as it always did`() {
        val messages = outgoing(rule(name = "somewhere else", pattern = """entra\.example\.com"""))

        val delivery = messages.send(CONNECTION_ID, "#general", "no rule for this")

        assertThat(delivery).isInstanceOf(Delivery.Sent::class.java)
        assertThat(direct).contains("/api/conversations.list", "/api/chat.postMessage")
        assertThat(proxied).isEmpty()
    }

    @Test
    fun `a proxy that asks who is posting is given the credentials its rule holds`() {
        demandsCredentials = true
        val messages = outgoing(
            rule(name = "Slack", pattern = slackHost(), username = "sentry", password = "open-sesame"),
        )

        val delivery = messages.send(CONNECTION_ID, "#general", "with credentials")

        assertThat(delivery).isInstanceOf(Delivery.Sent::class.java)
        assertThat(proxied).contains("${slackUrl()}/api/chat.postMessage")
        // Basic, base64 of "sentry:open-sesame". Asserted as the header the
        // proxy actually saw rather than as anything this test built, and every
        // challenge it made was answered the same way.
        assertThat(proxyCredentials).isNotEmpty().containsOnly("Basic c2VudHJ5Om9wZW4tc2VzYW1l")
    }

    @Test
    fun `the Socket Mode websocket is tunnelled through the rule matching it`() {
        val clients = SlackClients(router(tunnelRule(name = "the socket", pattern = """\Q$SOCKET_HOST\E""")))
        val routed = clients.forSocketMode()
        routed.slack.config.methodsEndpointUrlPrefix = "${slackUrl()}/api/"

        // What SlackListener's SocketModeApp does for itself: ask Slack for a
        // websocket URL, then dial it. The stand-in above answers with a host
        // that does not resolve, so anything reaching it went through a proxy.
        val client = routed.slack.socketMode("xapp-1-test", SocketModeClient.Backend.Tyrus)
        try {
            routed.routeAgainst { client.wssUri?.toString() }
            client.connect()

            await().atMost(TEN_SECONDS).untilAsserted {
                assertThat(tunnelled).anyMatch { it.startsWith("CONNECT $SOCKET_HOST:443") }
            }
        } finally {
            runCatching { client.close() }
        }
    }

    @Test
    fun `the listener opens its socket through the rules without being told to`() {
        // No endpoint is redirected here: this is the whole path as it runs, from
        // the connection row to Slack's real address, and the only thing arranged
        // is a rule saying that address needs a proxy.
        val clients = SlackClients(router(tunnelRule(name = "Slack", pattern = """slack\.com""")))
        val listener = SlackListener(
            connections(),
            ApplicationEventPublisher { },
            SlackProperties(),
            plainCredentials(),
            clients,
            SlackBotUsers(connections(), plainCredentials(), clients),
        )

        try {
            listener.reconcile()

            // `apps.connections.open` is an HTTPS call, so a proxy sees it as a
            // tunnel to Slack's address and nothing else. That the tunnel was
            // asked for is the proof: the rule was consulted, and it answered.
            await().atMost(TEN_SECONDS).untilAsserted {
                assertThat(tunnelled).anyMatch { it.startsWith("CONNECT slack.com:443") }
            }
            // And the socket did not open, because the proxy in this test refuses
            // everything - which is the honest failure the rule asked for rather
            // than a call that quietly went somewhere else.
            assertThat(listener.listeningConnectionIds()).isEmpty()
        } finally {
            listener.stop()
        }
    }

    /** [OutgoingMessages] as the application builds it, pointed at the stand-in. */
    private fun outgoing(vararg rules: ProxyRule): OutgoingMessages {
        val clients = SlackClients(router(*rules))
        clients.webApi.config.methodsEndpointUrlPrefix = "${slackUrl()}/api/"
        val rows = connections()
        return OutgoingMessages(rows, SlackDirectory(rows, plainCredentials(), clients), plainCredentials(), clients)
    }

    private fun router(vararg rules: ProxyRule) = ProxyRouter(ProxyRuleSource { rules.toList() })

    /** The one Slack connection every test here sends or listens on. */
    private fun connections(): WorkspaceConnectionRepository {
        val connection = WorkspaceConnection(
            id = CONNECTION_ID,
            workspaceId = 1,
            name = "Slack",
            type = ConnectionType.SLACK,
            url = "https://slack.com",
            secret = "xoxb-test",
            appToken = "xapp-1-test",
        )
        val repository = mock(WorkspaceConnectionRepository::class.java)
        `when`(repository.findById(CONNECTION_ID)).thenReturn(Optional.of(connection))
        `when`(repository.findByType(ConnectionType.SLACK)).thenReturn(listOf(connection))
        return repository
    }

    /** A rule pointing at the proxy that answers ordinary requests. */
    private fun rule(
        name: String,
        pattern: String,
        username: String? = null,
        password: String? = null,
    ) = ProxyRule(
        id = 1,
        name = name,
        pattern = pattern,
        proxyHost = proxy.address.hostString,
        proxyPort = proxy.address.port,
        username = username,
        password = password,
    )

    /** A rule pointing at the proxy that only records what it was asked to tunnel. */
    private fun tunnelRule(name: String, pattern: String) = ProxyRule(
        id = 1,
        name = name,
        pattern = pattern,
        proxyHost = tunnels.inetAddress.hostAddress,
        proxyPort = tunnels.localPort,
    )

    private fun slackUrl() = "http://${slack.address.hostString}:${slack.address.port}"

    /** Slack's stand-in, quoted so the dots in an address are literal. */
    private fun slackHost() = """\Q${slack.address.hostString}:${slack.address.port}\E"""

    /** What Slack would have said, for the two methods these tests reach. */
    private fun answerFor(path: String) = when {
        path.endsWith("apps.connections.open") -> """{"ok":true,"url":"wss://$SOCKET_HOST/link?ticket=t"}"""
        else -> """{"ok":true,"channel":"C0000000001","ts":"1700000000.000100"}"""
    }

    private fun respond(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private companion object {
        const val CONNECTION_ID = 7L

        /**
         * Where the websocket is said to be. A `.invalid` name has no address
         * anywhere by definition, so a connection attempt that got as far as a
         * request line cannot have been made to it directly.
         */
        const val SOCKET_HOST = "wss-primary.slack.invalid"

        val TEN_SECONDS: Duration = Duration.ofSeconds(10)
    }
}
