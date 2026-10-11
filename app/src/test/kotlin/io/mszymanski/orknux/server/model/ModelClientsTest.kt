package io.mszymanski.orknux.server.model

import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.ModelCallLimits
import io.mszymanski.orknux.connector.model.ModelClients
import io.mszymanski.orknux.connector.model.ModelProvider
import io.mszymanski.orknux.connector.model.ProviderType
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.connector.proxy.ProxyRule
import io.mszymanski.orknux.connector.proxy.ProxyRuleSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * The SDK client is built pointing where the provider says, and through the
 * proxy the rules name.
 *
 * Both halves are things this application gets wrong on its own. The URL layout
 * is what a hand-built client got wrong for Azure: a resource serving the
 * OpenAI-shaped surface under `/openai/v1` was called at
 * `/openai/v1/openai/deployments/...`, because the path was assembled here from
 * an assumption about which of Azure's two layouts was in play. It is the SDK's
 * decision now, and this pins that the address it is given is the address it
 * uses.
 *
 * The proxy is the other. [ProxyRouter]'s guarantee is that a client it did not
 * build is a client the rules do not reach, and the SDK brings its own OkHttp
 * stack - so a rule that covers the model endpoint has to be handed over
 * explicitly, and nothing but a test that watches a proxy receive the request
 * says it was.
 */
class ModelClientsTest {

    private lateinit var origin: HttpServer
    private lateinit var proxy: HttpServer

    /** Every path the origin was asked for. */
    private val asked = CopyOnWriteArrayList<String>()

    /** Every absolute URL the proxy was asked to forward. */
    private val proxied = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun start() {
        asked.clear()
        proxied.clear()

        origin = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        origin.createContext("/") { exchange ->
            asked += exchange.requestURI.path
            answer(exchange, completion())
        }
        origin.start()

        // A forward proxy for plain HTTP: the whole URL arrives on the request
        // line, which is how this can say the call went through it and not past.
        proxy = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        proxy.createContext("/") { exchange ->
            proxied += exchange.requestURI.toString()
            answer(exchange, completion())
        }
        proxy.start()
    }

    @AfterEach
    fun stop() {
        origin.stop(0)
        proxy.stop(0)
    }

    @Test
    fun `an Azure endpoint written to the v1 surface is called there`() {
        val clients = ModelClients(ProxyRouter(ProxyRuleSource { emptyList() }))
        val provider = provider(ProviderType.AZURE_OPENAI, "${url(origin)}/openai/v1")

        say(clients, provider, "gpt-5.6-terra")

        // Not `/openai/v1/openai/deployments/gpt-5.6-terra/chat/completions`,
        // which is what building this by hand produced and Azure 404s.
        assertThat(asked).containsExactly("/openai/v1/chat/completions")
    }

    @Test
    fun `a provider that speaks plain OpenAI is called at its own address`() {
        val clients = ModelClients(ProxyRouter(ProxyRuleSource { emptyList() }))
        val provider = provider(ProviderType.OPENAI, url(origin))

        say(clients, provider, "gpt-4o")

        assertThat(asked).containsExactly("/chat/completions")
    }

    @Test
    fun `a rule covering the model endpoint puts the call through the proxy`() {
        val rule = ProxyRule(
            id = 1,
            name = "everything",
            pattern = """127\.0\.0\.1|localhost""",
            proxyHost = proxy.address.hostString,
            proxyPort = proxy.address.port,
        )
        val clients = ModelClients(ProxyRouter(ProxyRuleSource { listOf(rule) }))
        val provider = provider(ProviderType.OPENAI, url(origin))

        say(clients, provider, "gpt-4o")

        // The proxy saw it and the origin did not: a client the SDK built is
        // still a client the rules reach.
        assertThat(proxied).singleElement().asString().contains("/chat/completions")
        assertThat(asked).isEmpty()
    }

    /**
     * A call is given up on at the installation's limit, not the SDK's.
     *
     * The SDK's own default is ten minutes, which cut a thinking model off at
     * exactly 600 seconds. A server that never answers is the honest stand-in:
     * held to a one-second limit the call fails in about a second, where the
     * default would have it wait ten minutes.
     */
    @Test
    fun `a call is given up on at the limit the installation sets`() {
        val silent = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        silent.createContext("/") { Thread.sleep(30_000) }
        silent.start()
        try {
            val clients = ModelClients(ProxyRouter(ProxyRuleSource { emptyList() })) { Duration.ofSeconds(1) }
            val started = System.nanoTime()
            assertThatThrownBy { say(clients, provider(ProviderType.OPENAI, url(silent)), "slow") }
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10))
        } finally {
            silent.stop(0)
        }
    }

    /** And a changed limit is a new client on the next call, without a restart. */
    @Test
    fun `a changed limit builds the client again`() {
        val limit = AtomicReference(Duration.ofMinutes(60))
        val clients = ModelClients(ProxyRouter(ProxyRuleSource { emptyList() }), ModelCallLimits { limit.get() })
        val provider = ModelProvider(
            id = 7,
            workspaceId = 1,
            name = "Provider",
            type = ProviderType.OPENAI,
            endpoint = url(origin),
            secret = "sk-test",
        )

        val first = clients.clientFor(provider, ModelClients.apiKey("sk-test"))
        assertThat(clients.clientFor(provider, ModelClients.apiKey("sk-test"))).isSameAs(first)
        limit.set(Duration.ofMinutes(5))
        assertThat(clients.clientFor(provider, ModelClients.apiKey("sk-test"))).isNotSameAs(first)
    }

    private fun say(clients: ModelClients, provider: ModelProvider, model: String) {
        val client = clients.clientFor(provider, ModelClients.apiKey("sk-test"))
        client.chat().completions().create(
            ChatCompletionCreateParams.builder().model(model).addUserMessage("Hi").build(),
        )
    }

    private fun provider(type: ProviderType, endpoint: String) = ModelProvider(
        workspaceId = 1,
        name = "Provider",
        type = type,
        endpoint = endpoint,
        secret = "sk-test",
    )

    private fun url(server: HttpServer): String = "http://${server.address.hostString}:${server.address.port}"

    private fun completion(): String =
        """{"id":"c","object":"chat.completion","created":1,"model":"m",""" +
            """"choices":[{"index":0,"message":{"role":"assistant","content":"Hello."},"finish_reason":"stop"}]}"""

    private fun answer(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }
}
