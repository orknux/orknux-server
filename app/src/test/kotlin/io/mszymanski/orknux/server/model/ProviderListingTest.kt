package io.mszymanski.orknux.server.model

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.connector.model.ModelProvider
import io.mszymanski.orknux.connector.model.ProviderStatus
import io.mszymanski.orknux.connector.model.ProviderType
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Where a provider is asked what it can run.
 *
 * The listing is the call behind "Test Connection", and it was the last one
 * still assembling its own URL. For an Azure endpoint written through to
 * `/openai/v1` that produced `/openai/v1/openai/models?api-version=...` - the
 * doubled path - so a provider that served every chat perfectly reported that
 * it could not be reached. A red card on a working provider is worse than no
 * card: it sends whoever is reading it to check the endpoint, which was right.
 *
 * **What this test does and does not prove.** It pins the address asked for.
 * It does not exercise the SDK's Azure branch: `AzureUrlCategory` decides from
 * the hostname, and a stub on `127.0.0.1` is categorised as an ordinary OpenAI
 * host, not as Azure. The two roads meet at the same URL for a `/openai/v1`
 * endpoint, which is why this is worth asserting - but a claim that the
 * AUTO-to-unified branch was driven here would be false, and the earlier
 * version of this file made exactly that claim.
 */
@SpringBootTest
class ProviderListingTest(
    @Autowired val service: ModelService,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer

    /** Every path the stub was asked for, with its query. */
    private val asked = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun start() {
        providers.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        asked.clear()

        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            val query = exchange.requestURI.query
            asked += exchange.requestURI.path + if (query == null) "" else "?$query"
            if (exchange.requestURI.path.endsWith("/models")) {
                answer(exchange, 200, """{"object":"list","data":[{"id":"gpt-5.6-terra"}]}""")
            } else {
                answer(exchange, 404, """{"error":{"message":"Resource not found"}}""")
            }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `an Azure endpoint written to the v1 surface is asked there, not one path deeper`() {
        val provider = provider("${root()}/openai/v1")

        val checked = service.testProvider(provider)

        assertThat(checked.status).isEqualTo(ProviderStatus.CONNECTED)
        assertThat(checked.lastCheckMessage).isEqualTo("Connected; 1 model listed")
        // Not `/openai/v1/openai/models?api-version=...`, which is what this
        // built for itself and what Azure answers 404 to.
        assertThat(asked).containsExactly("/openai/v1/models")
    }

    /**
     * And the check no longer contradicts the chat. A provider that answers a
     * message has to be a provider whose card says so, or the screen is telling
     * whoever configured it to go and fix something that works.
     */
    @Test
    fun `a v1 provider that answers is reported as connected rather than unreachable`() {
        val provider = provider("${root()}/openai/v1")

        val checked = service.testProvider(provider)

        assertThat(checked.status).isNotEqualTo(ProviderStatus.FAILED)
        assertThat(checked.lastCheckMessage).doesNotContain("No model list")
    }

    /**
     * A provider with nothing listening says where it was looked for and what
     * stopped it - not the SDK's "Request failed", and not "No model list",
     * which the fallback used to say about a server that was not there at all
     * and which sends whoever reads it to check a path that was right.
     */
    @Test
    fun `a provider nothing is listening for is reported with the address and the cause`() {
        val endpoint = "http://127.0.0.1:${java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }}/v1"
        val provider = provider(endpoint, ProviderType.OPENAI)

        val checked = service.testProvider(provider)

        assertThat(checked.status).isEqualTo(ProviderStatus.FAILED)
        assertThat(checked.lastCheckMessage).contains("Could not reach $endpoint: connection refused")
        assertThat(checked.lastCheckMessage).doesNotContain("Request failed").doesNotContain("No model list")
    }

    private fun provider(endpoint: String, type: ProviderType = ProviderType.AZURE_OPENAI): Long = requireNotNull(
        providers.save(
            ModelProvider(
                workspaceId = workspaceId,
                name = "Azure OpenAI",
                type = type,
                endpoint = endpoint,
                secret = "sk-test",
            ),
        ).id,
    )

    private fun root(): String = "http://${server.address.hostString}:${server.address.port}"

    private fun answer(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }
}
