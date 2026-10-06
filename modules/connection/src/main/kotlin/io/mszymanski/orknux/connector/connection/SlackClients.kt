package io.mszymanski.orknux.connector.connection

import com.slack.api.Slack
import com.slack.api.SlackConfig
import com.slack.api.util.http.SlackHttpClient
import io.mszymanski.orknux.connector.proxy.PROXY_AUTHORIZATION
import io.mszymanski.orknux.connector.proxy.ProxyChoice
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.connector.proxy.answers
import io.mszymanski.orknux.connector.proxy.basicAuthorization
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import org.springframework.stereotype.Component
import java.net.InetSocketAddress

/**
 * The Slack clients this application talks to Slack with, built so that the
 * proxy rules reach them.
 *
 * **Why this exists.** Slack's SDK will not take a [java.net.http.HttpClient],
 * so it cannot be built from [ProxyRouter.builder] the way every other outbound
 * caller here is. Left to itself it uses its own OkHttp client for the Web API
 * and its own Tyrus websocket stack for Socket Mode, and neither has ever seen
 * a rule. The seam this class provides is narrow on purpose: the rules are
 * still compiled and ordered in exactly one place, and nothing below decides
 * which proxy answers - it only carries the answer into a shape the SDK takes.
 *
 * **Two shapes, because Slack needs two.** The Web API can be given a
 * [java.net.ProxySelector], so it is asked per call, which is what a table of
 * rules means. Socket Mode cannot; see [RoutedConfig] for what it takes
 * instead.
 *
 * **What is deliberately not honoured.** [SlackConfig]'s constructor picks up
 * `http.proxyHost` and the `HTTPS_PROXY` environment variable, and both are
 * dropped here. Every other client in this application ignores them, because
 * the rules are where a proxy is decided and the screen that lists them is
 * where an administrator looks. Leaving one in place would also be worse than
 * useless: OkHttp pins a fixed proxy ahead of the selector and would then never
 * ask about a rule at all. Somebody who wants that proxy writes it as a rule,
 * where it can be seen.
 *
 * **One thing a rule cannot say for Slack.** OkHttp asks a selector about the
 * origin - scheme, host and port - and not the path, so a rule whose pattern
 * only matches part of a path will not fire for a Slack call, where it would
 * for the JDK's client. A rule naming a host, which is what a rule for Slack
 * is, behaves the same here as everywhere else.
 */
@Component
class SlackClients(private val router: ProxyRouter) {

    /**
     * The client every Slack Web API call is made from - `chat.postMessage` and
     * the `apps.connections.open` that issues a Socket Mode URL alike.
     *
     * One for the application rather than one per connection, because a [Slack]
     * is thread-safe and the token is passed per call, and because each one
     * costs a connection pool and a dispatcher.
     */
    val webApi: Slack = build(SlackConfig())

    /**
     * A Slack for one Socket Mode session, whose websocket the rules also
     * decide.
     */
    fun forSocketMode(): SocketModeSlack {
        val config = RoutedConfig(router)
        return SocketModeSlack(build(config), config)
    }

    private fun build(config: SlackConfig): Slack {
        // Read before the client is built and only to be thrown away: see the
        // note above on why the environment's proxy is not this application's
        // business.
        config.proxyUrl = null
        // The SDK keeps a record of every call it makes, for rate-limit numbers
        // nothing here reads, and runs a maintenance job over them that was the
        // second-largest allocator in a profile of a busy server - more than
        // any code of ours. Every Slack built here, the Socket Mode ones too,
        // would start its own. Issue #616.
        config.isStatsEnabled = false
        // The SDK's own client, so its user agent, its redirect handling and
        // whatever it adds next are kept, with the rules attached on top. At
        // this moment the config names no proxy - a RoutedConfig has not been
        // pointed at a websocket yet - so nothing here pins one and the
        // selector stays the thing that answers.
        val http = SlackHttpClient(
            SlackHttpClient.buildOkHttpClient(config)
                .newBuilder()
                .proxySelector(router.proxySelector())
                .proxyAuthenticator(RuleCredentials())
                .build(),
        )
        // Tyrus reads its proxy off this same object, reached through the Slack
        // instance, so the two have to be the one config and not two.
        http.config = config
        return Slack.getInstance(config, http)
    }

    /**
     * Answers a proxy's `407` on a Slack call, from the rule that routed it.
     *
     * OkHttp asks rather than being told, and asks again about the request it is
     * retrying - so an answer that was already refused is not offered a second
     * time, which is how this ends rather than loops when a proxy rejects the
     * credentials a rule holds.
     */
    private inner class RuleCredentials : Authenticator {

        override fun authenticate(route: Route?, response: Response): Request? {
            if (response.request.header(PROXY_AUTHORIZATION) != null) return null

            // On a tunnel this is the target's origin and on a plain request the
            // whole URL; either way it is the URL this connection was routed by.
            val choice = router.resolve(response.request.url.toString()) ?: return null

            // Whatever the rule was found by, the credentials only go to the
            // proxy the rule names, never to whoever happened to challenge.
            val challenger = route?.proxy?.address()
            if (challenger is InetSocketAddress && !choice.answers(challenger)) return null

            val header = choice.basicAuthorization() ?: return null
            return response.request.newBuilder().header(PROXY_AUTHORIZATION, header).build()
        }
    }
}

/**
 * A [SlackConfig] whose proxy is the rule answering for the websocket the
 * session is on, asked at the moment Tyrus wants it.
 *
 * Socket Mode is the one Slack call a selector cannot route.
 * `SocketModeClientTyrusImpl.connect` reads a single proxy address and a single
 * set of proxy headers off this object, puts them on a Tyrus `ClientManager`,
 * and never says which URI it is about to open. So the URI travels the other
 * way instead: [SocketModeSlack.routeAgainst] gives this config a way to ask the
 * client where it is going, and the rules are consulted then rather than when
 * the session was set up. That also means a reconnect landing on a different
 * Slack host is routed by the rule for that host, which resolving once at
 * startup would get wrong.
 */
internal class RoutedConfig(private val router: ProxyRouter) : SlackConfig() {

    @Volatile
    var target: () -> String? = { null }

    private fun choice(): ProxyChoice? = target()?.let(router::resolve)

    /**
     * Without the credentials in it. Slack would parse them back out of a
     * `user:password@host` URL, but a password holding a `:` or an `@` does not
     * survive that trip and there is no reason to make it: the header they end
     * up in is set below.
     */
    override fun getProxyUrl(): String? = choice()?.let { "http://${it.host}:${it.port}" }

    override fun getProxyHeaders(): Map<String, String>? {
        val header = choice()?.basicAuthorization() ?: return super.getProxyHeaders()
        return super.getProxyHeaders().orEmpty() + mapOf(PROXY_AUTHORIZATION to header)
    }
}

/**
 * A Slack instance for one Socket Mode session, and the way to tell it where the
 * session's websocket is.
 *
 * Two steps rather than one because of the order the SDK does things in: the
 * websocket URL is issued by Slack when the client is created, inside
 * `SocketModeApp`, so a caller can only point this at it once that client
 * exists - which is still before anything connects, because `SocketModeApp`
 * creates its client and connects it in one call, in that order.
 */
class SocketModeSlack internal constructor(val slack: Slack, private val config: RoutedConfig) {

    /**
     * @param wssUri where the session is connecting, read afresh every time the
     *   socket opens, so a reconnect to another host is routed as that host.
     */
    fun routeAgainst(wssUri: () -> String?) {
        config.target = wssUri
    }
}

