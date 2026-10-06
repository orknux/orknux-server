package io.mszymanski.orknux.connector.model

import com.openai.client.OpenAIClient
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.connector.proxy.ProxyRuleSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * A provider holds one client, and the one it no longer needs is closed.
 * Issue #616.
 *
 * Clients were cached by what they were built for, so a provider whose secret
 * or address changed got a second client and kept the first - its connection
 * pool and dispatcher threads included - for as long as the server ran. And a
 * plain API key was cached as "bearer", so two providers at one address shared
 * the client built with whichever key came first.
 *
 * Closed is read off what a closed client does: its dispatcher takes no more
 * work, so an asynchronous call on it fails without reaching anything.
 */
class ModelClientsCacheTest {

    private lateinit var origin: HttpServer
    private val clients = ModelClients(ProxyRouter(ProxyRuleSource { emptyList() }))

    @BeforeEach
    fun start() {
        origin = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        origin.createContext("/") { exchange ->
            val bytes = (
                """{"id":"c","object":"chat.completion","created":1,"model":"m",""" +
                    """"choices":[{"index":0,"message":{"role":"assistant","content":"Hello."},"finish_reason":"stop"}]}"""
                ).toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        origin.start()
    }

    @AfterEach
    fun stop() {
        clients.close()
        origin.stop(0)
    }

    @Test
    fun `a provider given a new key replaces its client and closes the old one`() {
        val provider = provider(id = 1)
        val first = clients.clientFor(provider, ModelClients.apiKey("sk-one"))
        assertThat(clients.clientFor(provider, ModelClients.apiKey("sk-one"))).isSameAs(first)
        say(first)

        val second = clients.clientFor(provider, ModelClients.apiKey("sk-two"))

        assertThat(second).isNotSameAs(first)
        assertThat(clients.held()).describedAs("clients held for one provider").isEqualTo(1)
        assertThatThrownBy { say(first) }.describedAs("the replaced client").isInstanceOf(Exception::class.java)
        say(second)
    }

    @Test
    fun `two providers at one address with different keys do not share a client`() {
        val one = clients.clientFor(provider(id = 1), ModelClients.apiKey("sk-one"))
        val two = clients.clientFor(provider(id = 2), ModelClients.apiKey("sk-two"))

        assertThat(two).isNotSameAs(one)
    }

    @Test
    fun `reloading closes every client it forgets`() {
        val client = clients.clientFor(provider(id = 1), ModelClients.apiKey("sk-one"))

        clients.reload()

        assertThat(clients.held()).isZero()
        assertThatThrownBy { say(client) }.isInstanceOf(Exception::class.java)
    }

    private fun say(client: OpenAIClient) {
        client.async().chat().completions()
            .create(ChatCompletionCreateParams.builder().model("m").addUserMessage("Hi").build())
            .get(10, TimeUnit.SECONDS)
    }

    private fun provider(id: Long) = ModelProvider(
        id = id,
        workspaceId = 1,
        name = "Provider $id",
        type = ProviderType.OPENAI,
        endpoint = "http://${origin.address.hostString}:${origin.address.port}",
        secret = "sk-test",
    )
}
