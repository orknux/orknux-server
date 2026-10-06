package io.mszymanski.orknux.server.integration

import io.mszymanski.orknux.connector.proxy.OutboundTrust
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.connector.proxy.ProxyRuleSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import javax.net.ssl.SSLContext

/**
 * A script's requests share one client rather than building one each.
 *
 * Issue #616: `orknux.http` built a client per request, and every client keeps
 * a selector thread and native buffers until a full collection finds it. A
 * Slack search paging through history built hundreds in one call and a server
 * for one person ran out of memory. What is pinned here is the reuse, and that
 * new trusted certificates still reach the next request.
 */
class SharedOutboundClientTest {

    @Test
    fun `one client answers every request until the trust changes`() {
        var context: SSLContext? = null
        val router = ProxyRouter(ProxyRuleSource { emptyList() }, OutboundTrust { context })

        val first = router.client()
        assertThat(router.client()).isSameAs(first)
        assertThat((1..200).map { router.client() }.toSet()).containsExactly(first)

        context = SSLContext.getInstance("TLS").apply { init(null, null, null) }
        val after = router.client()
        assertThat(after).isNotSameAs(first)
        assertThat(after.sslContext()).isSameAs(context)
        assertThat(router.client()).isSameAs(after)
        // The one replaced is let go of, not kept running beside the new one.
        assertThat(first.isTerminated || runCatching { first.awaitTermination(java.time.Duration.ofSeconds(5)) }.getOrDefault(false))
            .isTrue()
    }

    @Test
    fun `a script's requests do not each start a client`() {
        val before = selectorThreads()
        val router = ProxyRouter(ProxyRuleSource { emptyList() })
        repeat(50) { router.client() }
        // One selector thread for the one client, however many requests asked.
        assertThat(selectorThreads() - before).isLessThanOrEqualTo(1)
    }

    private fun selectorThreads() =
        Thread.getAllStackTraces().keys.count { it.name.contains("SelectorManager") && it.isAlive }
}
