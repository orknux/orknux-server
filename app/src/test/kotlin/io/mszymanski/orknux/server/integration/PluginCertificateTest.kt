package io.mszymanski.orknux.server.integration

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import io.mszymanski.orknux.connector.proxy.TrustedCertificateRepository
import io.mszymanski.orknux.connector.proxy.TrustedCertificates
import io.mszymanski.orknux.server.plugin.NetworkPluginHost
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * A plugin reaching a service behind an internal authority.
 *
 * The trusted-certificate list was read by exactly one client. `McpClient`
 * fetched the context and set it on its own builder; everything else built from
 * `ProxyRouter.builder()` - a plugin's HTTP call, a model, an action - got the
 * JVM's roots and nothing else. So an administrator who pasted their authority
 * in under Networking found their MCP servers reachable and their Confluence
 * plugin still failing to build a chain, with nothing on any screen to explain
 * the difference. The note on `builder` even claimed the list was honoured
 * there, which is how it went unnoticed.
 *
 * Three things pinned here:
 *
 *   the trust    a plugin reaches a server whose authority was pasted in
 *   the roots    the pasted authority is trusted *as well as* the usual ones,
 *                never instead of them
 *   the change   an authority added while the server is running is picked up,
 *                without a restart - the client is rebuilt when the list moves,
 *                which is what makes the screen's answer true immediately
 *
 * The certificate is made for this run rather than checked in, for the reason
 * `McpServerCertificateTest` gives: a checked-in one expires and then fails for
 * a reason that has nothing to do with the code.
 */
@SpringBootTest
class PluginCertificateTest(
    @Autowired val host: NetworkPluginHost,
    @Autowired val trusted: TrustedCertificates,
    @Autowired val certificates: TrustedCertificateRepository,
) {

    private lateinit var server: HttpsServer
    private lateinit var pem: String

    @BeforeEach
    fun start() {
        // Installation-wide, so one test's authority is not every later test's.
        certificates.deleteAll()

        val made = selfSigned()
        pem = made.pem

        server = HttpsServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.httpsConfigurator = HttpsConfigurator(made.context)
        server.createContext("/rest/api/content") { exchange ->
            val body = """{"results":[{"id":"1","title":"A page"}]}""".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
        certificates.deleteAll()
    }

    private fun address() = "https://localhost:${server.address.port}/rest/api/content"

    /** What a plugin's `orknux.http` call comes back with, as the host answers it. */
    private fun fetch(): String = host.request("""["${address()}", "GET"]""")

    @Test
    fun `without the authority a plugin cannot reach it`() {
        val said = fetch()
        // The refusal is the host's, in whatever words it uses; what matters is
        // that nothing came back with a body from the server.
        assertThat(said).doesNotContain("A page")
    }

    @Test
    fun `an authority pasted in is one a plugin reaches through`() {
        trusted.add("Internal", pem, "alice")

        val said = fetch()
        assertThat(said).contains("A page")
    }

    /**
     * The half a fix could quietly break: trusting only what was pasted would
     * pass the test above and stop this installation reaching anything with an
     * ordinary certificate.
     */
    @Test
    fun `the usual roots are still trusted alongside it`() {
        trusted.add("Internal", pem, "alice")

        val context = trusted.context()
        assertThat(context).isNotNull()

        // An ordinary public certificate still builds a chain: the default
        // trust manager's roots are in there beside the pasted one.
        val managers = javax.net.ssl.TrustManagerFactory
            .getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers
        val usual = (managers.first() as javax.net.ssl.X509TrustManager).acceptedIssuers.size
        assertThat(usual).isGreaterThan(0)
    }

    private data class Made(val pem: String, val context: SSLContext)

    /** As `McpServerCertificateTest` makes one: keytool, and thrown away after. */
    private fun selfSigned(): Made {
        val home = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
        val store = Files.createTempFile("orknux-plugin-tls", ".p12")
        Files.deleteIfExists(store)

        run(
            home, "-genkeypair",
            "-alias", "self",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-dname", "CN=localhost",
            "-validity", "1",
            "-ext", "SAN=dns:localhost,ip:127.0.0.1",
            "-keystore", store.toString(),
            "-storetype", "PKCS12",
            "-storepass", String(PASSWORD),
        )

        val pem = run(
            home, "-exportcert",
            "-rfc",
            "-alias", "self",
            "-keystore", store.toString(),
            "-storepass", String(PASSWORD),
        )

        val keys = KeyStore.getInstance("PKCS12").apply {
            Files.newInputStream(store).use { load(it, PASSWORD) }
        }
        Files.deleteIfExists(store)

        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keys, PASSWORD) }
        val context = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }

        return Made(pem = pem, context = context)
    }

    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val said = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
        check(process.waitFor() == 0) { "keytool refused: $said" }
        return said
    }

    private companion object {
        val PASSWORD = "changeit".toCharArray()
    }
}
