package io.mszymanski.orknux.connector.proxy

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.Authenticator
import java.net.CookieHandler
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters

/** The header a client answers a proxy's `407` with, and the one this sends. */
internal const val PROXY_AUTHORIZATION = "Proxy-Authorization"

/**
 * What a proxy rule is matched against for a mail server.
 *
 * A rule's pattern is written against a URL, and a mail server is configured as
 * a host and a port with no URL anywhere - so one is spelled here rather than in
 * each of the two places that need to ask. `smtp://` and the port are both
 * present so that a rule can name either: `smtp\.example\.com` matches the
 * server, `^smtps?://` matches all mail and nothing else.
 *
 * The scheme is `smtp` whatever the security setting says, because implicit TLS
 * is the same conversation on a different port and a rule that stopped matching
 * when somebody switched a connection from STARTTLS to TLS would be an
 * unpleasant surprise.
 */
fun mailAddress(host: String, port: Int): String = "smtp://$host:$port"

/**
 * Decides which proxy, if any, an outbound request goes through, and hands out
 * HTTP clients that ask.
 *
 * **Why this is one object and not a setting on each client.** Every outbound
 * call in this application is routed from here, which is the only reason the
 * rules can be trusted to cover anything. A proxy rule that applies to some
 * calls and not others is worse than no rule at all: the ones it misses fail
 * against an endpoint nobody can reach, and nothing on the screen that lists the
 * rules says so. So the seam is the client itself - a client that was not built
 * here is a client the rules do not reach.
 *
 * Most callers get that by building from [builder]. The exception is a library
 * that will not be handed a [java.net.http.HttpClient] at all: Slack's SDK
 * brings its own HTTP and websocket stacks, so it is given [proxySelector] and
 * [resolve] instead. What matters is that both roads end at the same compiled
 * rules in the same order - the decision lives here and is made once, whatever
 * asks for it.
 *
 * **Why first match wins, by a position an administrator sets.** Two other
 * orderings were considered. "Most specific wins" needs a definition of specific
 * that regular expressions do not have; comparing two patterns for which is
 * narrower is not something that can be done soundly, and a rule silently losing
 * to another because of a guess would be exactly the invisible failure this page
 * exists to prevent. "Refuse overlapping rules when they are saved" needs to
 * decide whether two regular expressions can match the same string, which is
 * expensive and, worse, would refuse the arrangement people actually want: one
 * narrow rule for the endpoint that needs a particular proxy and a broad one
 * behind it for everything else. So the order is stated rather than inferred,
 * and the screen shows it, moves it, and will tell you for any URL which rule
 * answers and which rules were beaten to it.
 *
 * **Why a rule whose pattern does not compile is dropped rather than thrown.**
 * A pattern is validated when it is saved, so a broken one should not be here at
 * all - but "should not" is not a guarantee once rows can arrive from a restore
 * or a hand-edited database. Compiling on every request and letting the failure
 * out would mean one bad row stops every outbound call this installation makes,
 * including the ones that would tell somebody about it. The rule is left out of
 * the snapshot and logged instead, which costs that one rule and nothing else.
 *
 * **Why no [java.net.Authenticator], ever.** Proxy credentials used to be handed
 * over by one, and it was careful - it answered nothing but a proxy, and only the
 * proxy its own rule named. It never leaked anything. What it cost was paid by
 * every other call in the application: `HttpClient` throws
 * `IOException("WWW-Authenticate header missing for response code 401")` when a
 * `401` arrives without that header **and an authenticator is set**, before the
 * authenticator's own judgement is ever asked for. Azure's token endpoint answers
 * exactly that shape, so the most ordinary failure anybody has - a wrong or
 * expired key - was reported as a sentence about a header nobody sent, on screens
 * that never mention proxies. The credentials go on the request as
 * `Proxy-Authorization` instead (see [authorized]), the way the Slack clients
 * already send them, and no client built here has an authenticator for the JDK to
 * find.
 */
@Component
class ProxyRouter(
    private val source: ProxyRuleSource,
    /**
     * What this installation trusts on the way out, attached to every client
     * built here.
     *
     * It used to hang off nothing. The note on [builder] said the trusted list
     * was honoured "for the same reason" the proxy rules were - since both come
     * from this builder - and it was not true: only `McpClient` ever read the
     * list, by fetching the context and setting it itself. So an installation
     * that added its authority in Admin found its MCP servers reachable and
     * everything else - a plugin's HTTP call, a model, an action - still failing
     * to build a chain, with nothing on any screen to say why.
     *
     * Defaulted so the constructions that pass rules alone keep working; the
     * application always has the real one, because `TrustedCertificates` is a
     * bean and this takes it.
     */
    private val trusted: OutboundTrust = OutboundTrust { null },
) {

    /**
     * The rules with their patterns already compiled. Rebuilt on [reload], which
     * every write goes through, rather than read per request: this is consulted
     * once for every outbound call in the process.
     */
    @Volatile
    private var snapshot: List<CompiledRule>? = null

    private val selector = RuleSelector()

    /**
     * A client that consults the rules. Everything outbound is built from this.
     *
     * What comes back is not the JDK's builder but one wrapping it, so that the
     * client it builds is wrapped too. That is deliberate and it is the same
     * argument as [builder] itself: a proxy credential attached at each call site
     * is a credential the next call site forgets, and the one it forgets is
     * indistinguishable from a proxy that is refusing to talk. Attaching it where
     * every request already passes costs one place to be right.
     */
    fun builder(): HttpClient.Builder {
        val base = HttpClient.newBuilder().proxy(selector)
        // Only where something was added. Null is "use the JVM's own", and a
        // context built from the default roots alone is not the same thing: it
        // would take an installation that trusts nothing extra off the path it
        // has always been on, for no reason it asked for.
        trusted.context()?.let(base::sslContext)
        return RoutedBuilder(base, this)
    }

    /**
     * The rules as a [ProxySelector], for a client this cannot build.
     *
     * Slack's SDK carries its own HTTP stack and its own websocket stack, and
     * neither takes a [java.net.http.HttpClient]. Handing them this object is
     * how they are brought back under the same rules without a second copy of
     * the decision: it is the very selector [builder] attaches, so a rule cannot
     * mean one thing for an MCP call and another for a Slack one.
     */
    fun proxySelector(): ProxySelector = selector

    /** Forget the compiled rules, so the next request reads them again. */
    fun reload() {
        snapshot = null
    }

    /**
     * Every enabled rule matching this URL, in the order they are consulted.
     *
     * The first is the one that will be used and the rest are the ones it beats,
     * which is the whole answer to "why does my rule never fire". Empty means
     * the request goes out directly.
     */
    fun matching(url: String): List<ProxyChoice> =
        compiled().filter { it.rule.enabled && it.matches(url) }.map(CompiledRule::choice)

    /** The rule that will be used for this URL, or null to go direct. */
    fun resolve(url: String): ProxyChoice? = matching(url).firstOrNull()

    /**
     * The same request, carrying the proxy credentials its rule holds.
     *
     * **Why the credential cannot reach the wrong ear.** The rule is looked up
     * from the request's own URL - the very lookup [RuleSelector] makes for the
     * very same URL a moment later - so the proxy that receives this header is by
     * construction the proxy the rule named. That is a stronger guarantee than
     * the authenticator's was: it compared a challenger's address against the
     * rule and could decline, where this never has an address to compare because
     * only one was ever in play.
     *
     * **Why it does not leak past the proxy either.** The JDK strips `proxy-*`
     * headers from any request that is not going to a proxy, and from the request
     * sent inside an established tunnel - so this reaches the `CONNECT` and never
     * the service on the other side of it. A request that matches no rule is
     * returned untouched rather than copied.
     *
     * A caller that set the header itself is left alone; whoever wrote that knew
     * something this did not.
     */
    fun authorized(request: HttpRequest): HttpRequest {
        if (request.headers().firstValue(PROXY_AUTHORIZATION).isPresent) return request
        val header = resolve(request.uri().toString())?.basicAuthorization() ?: return request
        return HttpRequest.newBuilder(request) { _, _ -> true }
            .header(PROXY_AUTHORIZATION, header)
            .build()
    }

    private fun compiled(): List<CompiledRule> = snapshot ?: build().also { snapshot = it }

    private fun build(): List<CompiledRule> = source.rules().mapNotNull { rule ->
        try {
            CompiledRule(rule, Regex(rule.pattern, RegexOption.IGNORE_CASE))
        } catch (failure: Exception) {
            log.warn(
                "Proxy rule {} is ignored because its pattern will not compile: {}",
                rule.name,
                failure.message,
            )
            null
        }
    }

    private class CompiledRule(val rule: ProxyRule, private val expression: Regex) {

        /**
         * Found anywhere in the URL rather than matched end to end. See
         * [ProxyRule.pattern] for why, and note that a pattern which backtracks
         * badly costs this thread and not the whole application, because it is
         * one request's own call.
         */
        fun matches(url: String): Boolean = expression.containsMatchIn(url)

        val choice: ProxyChoice
            get() = ProxyChoice(
                ruleId = rule.id,
                ruleName = rule.name,
                host = rule.proxyHost,
                port = rule.proxyPort,
                username = rule.username?.ifBlank { null },
                password = rule.password?.ifBlank { null },
            )
    }

    /**
     * The rules as the JDK's HTTP client asks about them.
     *
     * [ProxySelector] cannot refuse a request - it returns proxies or it returns
     * none - so this never decides whether a call is allowed. That stays with
     * the address guard, which every caller asks before it gets this far.
     */
    private inner class RuleSelector : ProxySelector() {

        override fun select(uri: URI?): List<Proxy> {
            val choice = uri?.let { resolve(it.toString()) } ?: return DIRECT
            return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(choice.host, choice.port)))
        }

        /**
         * A proxy that would not take the connection. Logged and nothing else:
         * the alternative is remembering it and going direct next time, which
         * would send a request out on the path an administrator said it must
         * not take. Failing where the rule pointed is the honest answer, and it
         * fails the same way every time until somebody fixes the rule.
         */
        override fun connectFailed(uri: URI?, address: SocketAddress?, failure: IOException?) {
            log.warn("A proxy rule pointed {} at {}, which could not be reached", uri, address, failure)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(ProxyRouter::class.java)

        val DIRECT = listOf(Proxy.NO_PROXY)

        /**
         * Lets a proxy password be sent when opening an HTTPS tunnel.
         *
         * The JDK refuses Basic proxy authentication on a `CONNECT` by default,
         * because the credential would cross an unencrypted hop to the proxy.
         * That default makes this feature useless for the case it was asked
         * for - the endpoint that needs a proxy here is an HTTPS one - so it is
         * lifted, and only when the operator has not already said what they want
         * by setting the property themselves.
         *
         * It governs the header [authorized] sets just as it governed the
         * authenticator that used to set it: the JDK filters a `Proxy-Authorization`
         * carrying a disabled scheme off the `CONNECT` whoever put it there.
         *
         * It has to happen before the JDK reads it, which it does once, when its
         * HTTP client machinery is first loaded. Every client in this
         * application is built by [builder], so this class is loaded before any
         * of them exists.
         */
        init {
            if (System.getProperty(TUNNELING_SCHEMES) == null) System.setProperty(TUNNELING_SCHEMES, "")
        }

        const val TUNNELING_SCHEMES = "jdk.http.auth.tunneling.disabledSchemes"
    }
}

/**
 * A proxy a request will go through, and the rule that said so.
 *
 * [password] never leaves this module: it is here because the one thing that
 * writes a `Proxy-Authorization` needs it, and everything the API returns is
 * built from the fields beside it.
 */
data class ProxyChoice(
    val ruleId: Long?,
    val ruleName: String,
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
)

/**
 * The proxy this rule names, as the header a `407` is answered with.
 *
 * Null when the rule holds no account, which is a proxy that does not ask.
 */
internal fun ProxyChoice.basicAuthorization(): String? {
    val user = username ?: return null
    val credentials = "$user:${password.orEmpty()}".toByteArray(StandardCharsets.ISO_8859_1)
    return "Basic ${Base64.getEncoder().encodeToString(credentials)}"
}

/** Whether this rule is the reason that proxy is the one being spoken to. */
internal fun ProxyChoice.answers(address: InetSocketAddress): Boolean =
    host.equals(address.hostString, ignoreCase = true) && port == address.port

/**
 * The JDK's builder, building a client that carries proxy credentials.
 *
 * Every fluent method is overridden to return this rather than the object it
 * delegates to. That is not decoration: the JDK's builder returns itself, so a
 * caller writing `builder().connectTimeout(x).build()` would otherwise be
 * holding the plain builder by the time it said `build`, and would get a plain
 * client - proxied correctly, and silently unable to authenticate to the proxy.
 */
private class RoutedBuilder(
    private val delegate: HttpClient.Builder,
    private val router: ProxyRouter,
) : HttpClient.Builder by delegate {

    override fun cookieHandler(handler: CookieHandler): HttpClient.Builder = also { delegate.cookieHandler(handler) }

    override fun connectTimeout(duration: Duration): HttpClient.Builder = also { delegate.connectTimeout(duration) }

    override fun sslContext(context: SSLContext): HttpClient.Builder = also { delegate.sslContext(context) }

    override fun sslParameters(parameters: SSLParameters): HttpClient.Builder =
        also { delegate.sslParameters(parameters) }

    override fun executor(executor: Executor): HttpClient.Builder = also { delegate.executor(executor) }

    override fun followRedirects(policy: HttpClient.Redirect): HttpClient.Builder =
        also { delegate.followRedirects(policy) }

    override fun version(version: HttpClient.Version): HttpClient.Builder = also { delegate.version(version) }

    override fun priority(priority: Int): HttpClient.Builder = also { delegate.priority(priority) }

    override fun proxy(selector: ProxySelector): HttpClient.Builder = also { delegate.proxy(selector) }

    /**
     * Passed on rather than refused, because refusing a method of an interface
     * this implements is worse than the mistake it would prevent. Nothing here
     * calls it, and [ProxyRouter] says at length why nothing should.
     */
    override fun authenticator(authenticator: Authenticator): HttpClient.Builder =
        also { delegate.authenticator(authenticator) }

    override fun localAddress(address: InetAddress?): HttpClient.Builder = also { delegate.localAddress(address) }

    override fun build(): HttpClient = RoutedClient(delegate.build(), router)
}

/**
 * A client that puts the proxy credentials on every request it sends.
 *
 * Nothing but [send] and [sendAsync] is this class's business; the rest is the
 * JDK's client answering for itself. It is a subclass rather than a wrapper
 * interface because [java.net.http.HttpClient] is a class, which is also why
 * this file has to say `delegate` a dozen times to add one header.
 */
private class RoutedClient(private val delegate: HttpClient, private val router: ProxyRouter) : HttpClient() {

    override fun <T> send(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> =
        delegate.send(router.authorized(request), handler)

    override fun <T> sendAsync(
        request: HttpRequest,
        handler: HttpResponse.BodyHandler<T>,
    ): CompletableFuture<HttpResponse<T>> = delegate.sendAsync(router.authorized(request), handler)

    override fun <T> sendAsync(
        request: HttpRequest,
        handler: HttpResponse.BodyHandler<T>,
        promises: HttpResponse.PushPromiseHandler<T>?,
    ): CompletableFuture<HttpResponse<T>> = delegate.sendAsync(router.authorized(request), handler, promises)

    override fun cookieHandler(): Optional<CookieHandler> = delegate.cookieHandler()

    override fun connectTimeout(): Optional<Duration> = delegate.connectTimeout()

    override fun followRedirects(): Redirect = delegate.followRedirects()

    override fun proxy(): Optional<ProxySelector> = delegate.proxy()

    override fun sslContext(): SSLContext = delegate.sslContext()

    override fun sslParameters(): SSLParameters = delegate.sslParameters()

    override fun authenticator(): Optional<Authenticator> = delegate.authenticator()

    override fun version(): Version = delegate.version()

    override fun executor(): Optional<Executor> = delegate.executor()

    override fun newWebSocketBuilder(): WebSocket.Builder = delegate.newWebSocketBuilder()

    override fun shutdown() = delegate.shutdown()

    override fun shutdownNow() = delegate.shutdownNow()

    override fun isTerminated(): Boolean = delegate.isTerminated

    override fun awaitTermination(duration: Duration): Boolean = delegate.awaitTermination(duration)

    override fun close() = delegate.close()

    override fun toString(): String = delegate.toString()
}
