package io.mszymanski.orknux.connector

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException
import java.nio.channels.UnresolvedAddressException
import java.net.http.HttpTimeoutException
import java.security.cert.CertPathBuilderException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.time.Duration.Companion.milliseconds

/**
 * A call that got no answer, said as where it went and what stopped it.
 *
 * The exception a client throws is rarely the one worth reading. The OpenAI SDK
 * wraps every transport failure in an `OpenAIIoException` whose own message is
 * "Request failed", and the JDK client wraps its own in an `ExecutionException`
 * around a `ConnectException` with no message at all - so a step that said
 * `failure.message` said "Request failed", and the `Connection refused` from a
 * container that cannot see `localhost:11434` stayed in a log nobody was shown.
 * Issue: a 0.9.9.8 run whose agent step read "could not answer: Request failed".
 *
 * So the chain is walked and the cause that names the problem is the one said,
 * beside the address, because "connection refused" alone does not tell anybody
 * *which* server refused. One helper for every model client in this module,
 * since the copies are exactly how one of them keeps saying "Request failed".
 *
 * What it says is never a refusal by the provider - nothing came back - so the
 * callers keep such a failure retryable.
 */
object Unreachable {

    /**
     * The sentence for [failure] on a call to [endpoint].
     *
     * Something that is not a transport failure at all - no [IOException]
     * anywhere in the chain - is said in its own words, without the address,
     * because "could not reach" would then be a guess.
     *
     * @param timeout how long the call was given, where the caller knows it, so
     *   a timeout can say how long was waited.
     */
    fun describe(endpoint: String, failure: Throwable, timeout: java.time.Duration? = null): String {
        val chain = chainOf(failure)
        if (chain.none { it is IOException }) {
            return chain.lastOrNull { !it.message.isNullOrBlank() }?.message ?: failure.javaClass.simpleName
        }
        return "Could not reach ${shown(endpoint)}: ${causeOf(chain, timeout)}"
    }

    /** Whether [failure] is the transport giving out - nothing came back - rather than anything said. */
    fun isTransport(failure: Throwable): Boolean = chainOf(failure).any { it is IOException }

    /** Only the cause, for a caller that names the endpoint itself. */
    fun causeOf(failure: Throwable, timeout: java.time.Duration? = null): String = causeOf(chainOf(failure), timeout)

    private fun causeOf(chain: List<Throwable>, timeout: java.time.Duration?): String {
        // The JDK client says it with a channel exception, okhttp with the
        // resolver's own; both are the same news.
        if (chain.any { it is UnknownHostException || it is UnresolvedAddressException }) return "no such host"

        if (chain.any { it is CertPathBuilderException || it.message.orEmpty().contains("PKIX path") }) {
            return "certificate not trusted (PKIX path building failed)"
        }
        chain.firstOrNull { it is SSLPeerUnverifiedException }?.let { return "certificate does not match the host" }
        chain.firstOrNull { it is CertificateException }?.let { return "certificate not accepted: ${deepest(listOf(it))}" }

        if (chain.any { it is HttpConnectTimeoutException }) return "timed out connecting"
        if (chain.any(::timedOut)) {
            return if (timeout == null) "timed out" else "timed out after ${timeout.toMillis().milliseconds}"
        }

        chain.lastOrNull { it is ConnectException }?.let { refused ->
            return if (refused.message.orEmpty().contains("timed out", ignoreCase = true)) "timed out connecting" else "connection refused"
        }
        chain.firstOrNull { it is NoRouteToHostException }?.let { return "no route to host" }
        chain.firstOrNull { it is SSLHandshakeException }?.let { return "TLS handshake failed: ${deepest(chain)}" }
        chain.firstOrNull { it is SSLException }?.let { return "TLS failed: ${deepest(chain)}" }
        if (chain.any { it is EOFException || it.message.orEmpty().contains("end of stream", ignoreCase = true) }) {
            return "the connection closed before an answer"
        }
        chain.lastOrNull { it is SocketException && it.message.orEmpty().contains("reset", ignoreCase = true) }
            ?.let { return "connection reset" }
        return deepest(chain)
    }

    private fun timedOut(it: Throwable): Boolean =
        it is HttpTimeoutException ||
            it is SocketTimeoutException ||
            (it is InterruptedIOException && it.message.orEmpty().contains("timeout", ignoreCase = true))

    /** The innermost message there is, which is the one closest to the wire. */
    private fun deepest(chain: List<Throwable>): String {
        val said = chain.lastOrNull { !it.message.isNullOrBlank() && it.message != GENERIC }
        return said?.message?.trim() ?: chain.last().javaClass.simpleName
    }

    private fun chainOf(failure: Throwable): List<Throwable> {
        val chain = mutableListOf<Throwable>()
        var at: Throwable? = failure
        while (at != null && at !in chain) {
            chain += at
            at = at.cause
        }
        return chain
    }

    /**
     * The address without its query or user info: a key can travel in either,
     * and this sentence is shown on a step anybody in the workspace can read.
     */
    private fun shown(endpoint: String): String = runCatching {
        val uri = URI(endpoint)
        URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: endpoint.substringBefore('?')

    /** The SDK's own sentence, which says nothing. */
    private const val GENERIC = "Request failed"
}
