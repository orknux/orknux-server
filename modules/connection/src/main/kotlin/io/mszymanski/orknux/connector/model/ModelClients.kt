package io.mszymanski.orknux.connector.model

import com.openai.azure.AzureUrlPathMode
import com.openai.azure.credential.AzureApiKeyCredential
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.http.ProxyAuthenticator
import com.openai.errors.OpenAIIoException
import com.openai.credential.BearerTokenCredential
import com.openai.credential.Credential
import io.mszymanski.orknux.connector.proxy.ProxyChoice
import io.mszymanski.orknux.connector.proxy.ProxyRouter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.InetSocketAddress
import java.net.Proxy
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier

/**
 * Hands out the SDK client a provider is called through.
 *
 * **Why the SDK rather than a URL built here.** Every provider but Anthropic
 * speaks the OpenAI shape, and the one that varies is Azure - which serves two
 * different URL layouts from a single resource. The older puts the deployment
 * and an API version in the path; the newer is the OpenAI shape unchanged under
 * `/openai/v1`, addressing the model by name with no version at all. Which one
 * a resource answers on depends on the resource and on the model deployed to
 * it, and Azure adds API versions every few months. A path assembled here
 * encodes whichever arrangement was true the day it was written, and a resource
 * serving the other answers `404 Resource not found` - which reads as a wrong
 * deployment name and sends whoever is debugging to a field that was correct.
 * [AzureUrlPathMode.AUTO] is the SDK deciding that from the address, maintained
 * by the people who change the API. See AGENTS.md.
 *
 * **Why the clients are cached.** An [OpenAIClient] carries an OkHttp connection
 * pool and its dispatcher threads, so one per request would open a new pool for
 * every message. One per provider and surface, built for everything that decides
 * who is being called and as whom, so pointing a provider somewhere else - or
 * rotating its secret - builds a new client and closes the old one rather than
 * going on with the old connections.
 *
 * **Why the proxy is passed in explicitly.** [ProxyRouter]'s guarantee is that
 * a client it did not build is a client the rules do not reach, and this is one
 * it cannot build: the SDK brings its own OkHttp stack. So the rule is resolved
 * here for the base URL and handed over as a [Proxy] and, where the rule holds
 * an account, a [ProxyAuthenticator] - the same road already taken for Slack's
 * SDK, ending at the same compiled rules in the same order.
 */
@Component
class ModelClients(private val proxies: ProxyRouter) {

    /**
     * The client each provider is called through, by provider and surface.
     *
     * Issue #616: keyed only by what the client was built for, a provider
     * whose address or secret changed got a new entry and its old client stayed
     * beside it - pool, dispatcher threads and all - for the life of the
     * process. Keyed by the provider, a change has an old client to replace,
     * and the one replaced is closed. It also stops two providers sharing a
     * client because they share an address: a plain API key's client was keyed
     * as "bearer", so a second workspace at the same base was called with the
     * first one's key.
     */
    private val cache = ConcurrentHashMap<Slot, Held>()

    /**
     * The client for this provider, built once.
     *
     * [credential] is resolved by the caller because reading it can fail in ways
     * worth reporting - a workspace secret that has been deleted, a token grant
     * Entra refused - and those are sentences a provider's card shows rather
     * than exceptions to throw here.
     */
    fun clientFor(provider: ModelProvider, credential: Credential): OpenAIClient {
        return held(provider.openAiBase(), provider, credential)
    }

    /**
     * The client a provider's Responses API calls go through.
     *
     * The same client as [clientFor] wherever the base is already right, and
     * that is every base but one: an Azure resource given as its bare host. The
     * SDK reads a bare Azure host as the older layout and would put the
     * deployment and an API version in the path -
     * `/openai/deployments/{name}/responses?api-version=…` - where Azure serves
     * no Responses at all. Its Responses live on the v1 surface of the same
     * resource, `/openai/v1/responses`, with the model in the body. So the base
     * handed over is that surface, and the SDK recognises it as the unified
     * layout and builds the path itself. See [responsesBase].
     */
    fun responsesClientFor(provider: ModelProvider, credential: Credential): OpenAIClient {
        return held(responsesBase(provider), provider, credential)
    }

    /**
     * The provider's client for this base: the one held where it was built for
     * the same thing, otherwise a new one, closing whatever it replaces.
     *
     * Closed outside the map's lock, since closing waits on nothing here but is
     * still the SDK's code. A call already under way on the old client finishes
     * - closing stops its dispatcher taking new work and drops idle connections -
     * and the next call asks for the client again, as every caller does.
     */
    private fun held(base: String, provider: ModelProvider, credential: Credential): OpenAIClient {
        val key = ClientKey(base, provider.type, identity(credential))
        // A provider not saved yet - one being tried before it is - has no id to
        // keep a slot by, so what it was built for is the slot.
        val slot = Slot(provider.id, base, if (provider.id == null) key else null)
        var replaced: OpenAIClient? = null
        val held = cache.compute(slot) { _, was ->
            if (was?.key == key) {
                was
            } else {
                replaced = was?.client
                Held(key, build(base, provider, credential))
            }
        }
        replaced?.let(::closeQuietly)
        return requireNotNull(held).client
    }

    private fun closeQuietly(client: OpenAIClient) {
        runCatching { client.close() }.onFailure { log.debug("A replaced model client did not close cleanly", it) }
    }

    /**
     * The same call again when the connection died before it was answered.
     *
     * **Why this and not the SDK's own retry.** A self-hosted server keeps an
     * idle connection for a few seconds - llama.cpp says five - while the pool
     * holding it does not know that, so a request written into one the server
     * has closed comes back as `unexpected end of stream` with no response at
     * all. Shortening how long a connection is kept narrows that window and
     * cannot close it: the socket looks open right up until it is written to.
     *
     * The SDK will retry this, and it also retries a `429`, which is not its
     * decision to make - an agent node has a retry count, a backoff and a
     * screen showing the attempts, and a library absorbing the refusal
     * underneath makes all three lie. Measured: with the SDK retrying, a rate
     * limited call reported one attempt where the policy allowed three.
     *
     * So only this, and only once. [OpenAIIoException] is the transport
     * failing, never an answer: a provider that refuses, rate limits or breaks
     * raises `OpenAIServiceException` instead and goes straight out to whoever
     * is counting attempts. Once, because a second failure is a server that is
     * really gone rather than a socket that was already closed.
     */
    fun <T> again(call: () -> T): T = try {
        call()
    } catch (lost: OpenAIIoException) {
        log.debug("A model connection was closed before it answered; asking once more", lost)
        call()
    }

    /** Forget every built client, so the next call reads the rules again - closing each one forgotten. */
    fun reload() {
        cache.keys.toList().forEach { slot -> cache.remove(slot)?.let { closeQuietly(it.client) } }
    }

    /** And every one of them when the application stops. */
    @jakarta.annotation.PreDestroy
    fun close() = reload()

    /** How many clients are held, for the test that a replaced one is not. Issue #616. */
    internal fun held(): Int = cache.size

    private fun build(base: String, provider: ModelProvider, credential: Credential): OpenAIClient {
        val builder = OpenAIOkHttpClient.builder()
            .baseUrl(base)
            .credential(credential)
            /*
             * Asked once, because somebody else is counting.
             *
             * This application has its own retry policy - a node's, with its own
             * backoff, its own ceiling and a screen that shows the attempts. The
             * SDK ships one too, and two of them compose by multiplying: a step
             * allowed three attempts made nine, and a request the provider had
             * flatly refused was sent again twice before anything here was told
             * about it. Whether a failure is worth repeating is a decision this
             * application already makes and can explain, so the SDK is asked to
             * make it no more.
             */
            .maxRetries(0)
            /*
             * And a connection is not kept longer than the other end keeps it.
             *
             * OkHttp pools an idle connection for five minutes. llama.cpp
             * answers `Keep-Alive: timeout=5` and drops it after five seconds,
             * and the provider check runs every five minutes - so every check
             * after the first wrote its request into a socket the server had
             * closed long ago and read back `unexpected end of stream`. Ollama
             * and most self-hosted servers are the same shape; the hosted ones
             * hold a connection far longer than this and lose nothing by it.
             *
             * Two seconds rather than a retry. The SDK's own retry would cover
             * this, and it also retries a 429 - which is the application's
             * decision to make, not the library's: a node's retry policy has a
             * count, a backoff and a screen that shows the attempts, and a
             * library quietly absorbing the refusal underneath makes all three
             * lie. Measured: with the SDK retrying, a rate-limited call reports
             * one attempt where the policy allowed three.
             */
            .maxIdleConnections(MAX_IDLE_CONNECTIONS)
            .keepAliveDuration(Duration.ofSeconds(KEEP_ALIVE_SECONDS))

        // Azure decides its own URL layout from the address it was given.
        if (provider.type == ProviderType.AZURE_OPENAI) builder.azureUrlPathMode(AzureUrlPathMode.AUTO)

        proxies.resolve(base)?.let { rule ->
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(rule.host, rule.port)))
            rule.username?.let { user -> builder.proxyAuthenticator(ProxyAuthenticator.basic(user, rule.password.orEmpty())) }
        }

        return builder.build()
    }

    /**
     * What decides the client is a different one.
     *
     * A credential's own identity rather than the object: two [Credential]s
     * holding the same key are the same caller, and a [BearerTokenCredential]
     * built around a supplier is one caller whose token changes underneath it -
     * which is the point of the supplier, and why the token itself is not part
     * of this.
     */
    private fun identity(credential: Credential): String = when (credential) {
        is AzureApiKeyCredential -> "azure-key:${digest(credential.apiKey())}"
        // A key made by [apiKey] is a fixed token, so a different one is a different caller; a
        // supplier's token is meant to change, and is never asked for here because asking fetches one.
        is BearerTokenCredential -> keys[credential] ?: "bearer"
        else -> credential.javaClass.name
    }

    private data class ClientKey(val base: String, val type: ProviderType, val credential: String)

    /** Where a client is kept: a saved provider's surface, or for one not saved, what it was built for. */
    private data class Slot(val provider: Long?, val base: String, val unsaved: ClientKey?)

    private class Held(val key: ClientKey, val client: OpenAIClient)

    companion object {
        private val log = LoggerFactory.getLogger(ModelClients::class.java)

        /**
         * How many idle connections are kept, and for how long.
         *
         * Both or neither: the SDK refuses a builder that sets one alone. The
         * duration is the point - see [build] - and the count is OkHttp's own
         * default, written down beside it so the pair reads as one decision.
         */
        private const val MAX_IDLE_CONNECTIONS = 5
        private const val KEEP_ALIVE_SECONDS = 2L

        /** Azure's unified surface, which is where its Responses API is served. */
        private const val AZURE_V1 = "/openai/v1"

        /**
         * Where a provider's Responses API begins: its OpenAI base, except that
         * an Azure resource is taken to its `/openai/v1` surface - after any path
         * a gateway put in front of it, and without doubling one already there.
         */
        fun responsesBase(provider: ModelProvider): String {
            val base = provider.openAiBase()
            if (provider.type != ProviderType.AZURE_OPENAI) return base
            return when {
                base.endsWith(AZURE_V1) -> base
                base.endsWith("/openai") -> "$base/v1"
                else -> "$base$AZURE_V1"
            }
        }

        /** A token read afresh on every call, so a rotated one is picked up. */
        fun bearer(token: Supplier<String>): Credential = BearerTokenCredential.create(token)

        /** Azure's own key header, which is not `Authorization`. */
        fun azureKey(key: String): Credential = AzureApiKeyCredential.create(key)

        /**
         * Every other provider: a key sent as a bearer token.
         *
         * Remembered by a digest beside the credential, because the SDK's bearer
         * credential cannot say whether it holds a fixed key or a supplier, and
         * only a fixed key may be read to tell two callers apart. Weakly, so the
         * credential going is the entry going.
         */
        fun apiKey(key: String): Credential =
            BearerTokenCredential.create(key).also { keys[it] = "key:${digest(key)}" }

        private val keys: MutableMap<Credential, String> =
            java.util.Collections.synchronizedMap(java.util.WeakHashMap())

        /** A key's fingerprint, so the key itself is not kept a second time in a map key. */
        private fun digest(key: String): String =
            java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)),
            )
    }
}
