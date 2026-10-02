package io.mszymanski.orknux.connector

import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.errors.OpenAIIoException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.security.cert.CertPathBuilderException
import java.time.Duration
import java.util.concurrent.ExecutionException
import javax.net.ssl.SSLHandshakeException

/**
 * What a call that got no answer is said as.
 *
 * The real failures, thrown by the real clients, rather than exceptions built
 * to look like them: the whole bug was that the exception a client throws is
 * not the one that says anything - the SDK's `OpenAIIoException` reads
 * "Request failed" and the JDK client's `ConnectException` reads nothing at
 * all - so a test that constructed the inner one directly would pass on code
 * that never looked past the outer.
 */
class UnreachableTest {

    /** A loopback port that was free a moment ago and has nothing on it now. */
    private fun closedPort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    @Test
    fun `the SDK's "Request failed" is read through to the refused connection under it`() {
        val base = "http://127.0.0.1:${closedPort()}/v1"
        val client = OpenAIOkHttpClient.builder().baseUrl(base).apiKey("sk-test").maxRetries(0).build()

        val failure = runCatching { client.models().list() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(OpenAIIoException::class.java).hasMessage("Request failed")
        assertThat(Unreachable.describe(base, failure!!)).isEqualTo("Could not reach $base: connection refused")
        assertThat(Unreachable.isTransport(failure)).isTrue()
    }

    @Test
    fun `the JDK client's empty ConnectException is read through too`() {
        val url = "http://127.0.0.1:${closedPort()}/v1/chat/completions"
        val failure = runCatching {
            HttpClient.newHttpClient()
                .sendAsync(HttpRequest.newBuilder(URI(url)).GET().build(), HttpResponse.BodyHandlers.ofString())
                .get()
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(ExecutionException::class.java)
        assertThat(Unreachable.describe(url, failure!!)).isEqualTo("Could not reach $url: connection refused")
    }

    /** `.invalid` never resolves, by RFC 6761, so this leaves the machine for nothing. */
    @Test
    fun `a name that does not resolve says so, through either client`() {
        val base = "http://models.invalid/v1"
        val sdk = runCatching {
            OpenAIOkHttpClient.builder().baseUrl(base).apiKey("sk-test").maxRetries(0).build().models().list()
        }.exceptionOrNull()!!
        val jdk = runCatching {
            HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI("$base/models")).build(), HttpResponse.BodyHandlers.ofString())
        }.exceptionOrNull()!!

        assertThat(Unreachable.describe(base, sdk)).isEqualTo("Could not reach $base: no such host")
        assertThat(Unreachable.describe("$base/models", jdk)).isEqualTo("Could not reach $base/models: no such host")
    }

    @Test
    fun `a timeout says how long was waited, where that is known`() {
        val failure = OpenAIIoException("Request failed", HttpTimeoutException("request timed out"))

        assertThat(Unreachable.describe("http://slow:8000/v1", failure, Duration.ofSeconds(30)))
            .isEqualTo("Could not reach http://slow:8000/v1: timed out after 30s")
        assertThat(Unreachable.causeOf(failure)).isEqualTo("timed out")
    }

    @Test
    fun `an untrusted certificate is named as one`() {
        val failure = OpenAIIoException(
            "Request failed",
            SSLHandshakeException("PKIX path building failed").apply {
                initCause(CertPathBuilderException("unable to find valid certification path to requested target"))
            },
        )

        assertThat(Unreachable.causeOf(failure)).isEqualTo("certificate not trusted (PKIX path building failed)")
    }

    /** A key can ride in the query, and this sentence is shown on a step. */
    @Test
    fun `the address is shown without its query or user info`() {
        val failure = java.net.ConnectException("Connection refused")

        assertThat(Unreachable.describe("https://user:pw@host.example:8443/v1/models?key=sk-secret", failure))
            .isEqualTo("Could not reach https://host.example:8443/v1/models: connection refused")
    }

    /** Not a transport failure at all, so "could not reach" would be a guess. */
    @Test
    fun `something that is not the network is said in its own words`() {
        assertThat(Unreachable.describe("http://x/v1", IllegalStateException("That host resolves to a link-local address")))
            .isEqualTo("That host resolves to a link-local address")
        assertThat(Unreachable.isTransport(IllegalStateException("no"))).isFalse()
    }
}
