package io.mszymanski.orknux.connector.connection

import io.mszymanski.orknux.connector.proxy.OutboundTrust
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.connector.proxy.ProxyRuleSource
import io.mszymanski.orknux.connector.security.SecretCipher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import tools.jackson.databind.ObjectMapper

/**
 * Why a request did not go out is said once and then forgotten. Issue #616.
 *
 * The reason is handed from the request to the caller in a thread local, and
 * nothing ever cleared it: every pooled thread that had once failed to reach a
 * server held that sentence for as long as it lived. The thread local is read
 * by reflection because it is private on purpose - nothing else may read it -
 * and what is asked is only whether it is still set.
 */
class McpRefusalTest {

    private val proxies = ProxyRouter(ProxyRuleSource { emptyList() })

    /**
     * Credentials that cannot be read, so the request fails before it is sent -
     * the path that sets the reason, with nothing on the network involved.
     */
    private val client = McpClient(
        ObjectMapper(),
        McpProperties(),
        ConnectionProbe(ConnectionProperties(), proxies, SecretCipher("")),
        mock(ConnectionCredentials::class.java),
        proxies,
        OutboundTrust { null },
    )

    @Test
    fun `the reason a server could not be reached is reported, then not kept`() {
        val server = McpServer(workspaceId = 1, name = "Nowhere", address = "http://127.0.0.1:9/rpc")

        val listed = client.tools(server)

        assertThat(listed).isInstanceOf(McpListing.Failed::class.java)
        assertThat((listed as McpListing.Failed).reason).describedAs("the reason, still said").isNotBlank()
        assertThat(held()).describedAs("the reason, left on the thread").isNull()
    }

    private fun held(): String? {
        val field = McpClient::class.java.getDeclaredField("lastRefusal").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (field.get(client) as ThreadLocal<String?>).get()
    }
}
