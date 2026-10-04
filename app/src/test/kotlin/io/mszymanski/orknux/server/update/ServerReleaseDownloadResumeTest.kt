package io.mszymanski.orknux.server.update

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.update.TestReleaseJars.Shape
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * A server jar download that breaks, and goes on from where it broke. Issue #602.
 *
 * The source is a loopback HTTP server that cuts the connection part way
 * through the jar and answers `Range` the way orknux.ai and an Artifactory do;
 * the jar is a real signed one, padded so that "half" is a couple of megabytes.
 * What is asserted is what an administrator relies on: the jar stored is the
 * jar served, byte for byte, and it was finished from the bytes already held
 * rather than fetched again - plus giving up, and what the row says while it
 * runs.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ServerReleaseDownloadResumeTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val releases: ServerReleaseRepository,
    @Autowired val downloads: ServerReleaseDownloadRepository,
    @Autowired val settings: InstallationSettings,
    @Autowired val dataSource: DataSource,
    @Autowired val background: ServerReleaseDownloads,
    @Autowired val updates: ServerReleases,
) {

    private val jdbc = JdbcTemplate(dataSource)

    @BeforeEach
    fun reset() {
        jdbc.update("DELETE FROM server_release_part")
        jdbc.update("DELETE FROM server_release")
        jdbc.update("DELETE FROM server_release_download")
        jdbc.update("DELETE FROM installation_setting WHERE name LIKE 'release%'")
        // Waits of a second, so a test of three attempts costs three seconds.
        settings.setReleaseDownloadBackoffSeconds(1, "alice")
        settings.setReleaseDownloadBackoffMaxSeconds(1, "alice")
        asked.clear()
        servedBytes.set(0)
        paused = null
    }

    private fun fetched(path: String): ServerReleaseDownload {
        val id = graphQlTester.document(
            """mutation(${'$'}url: String!) { installServerReleaseFromUrl(url: ${'$'}url) { id state } }""",
        ).variable("url", "http://${where()}$path").execute()
            .path("installServerReleaseFromUrl.id").entity(String::class.java).get().toLong()
        return ReleaseDownloadsAwait.finished(downloads, id)
    }

    private fun storedBytes(release: ServerRelease): ByteArray {
        val file = Files.createTempDirectory("orknux-read-back-").resolve("release.jar")
        try {
            JdbcReleaseStore { dataSource.connection }.writeJar(release.id!!, file)
            return Files.readAllBytes(file)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `a connection cut half way is resumed with Range, and the jar stored is the one served, byte for byte`() {
        served = TestReleaseJars.signed(Shape(version = "9.7.1", padding = 2 * 1024 * 1024))
        val size = Files.size(served)

        val done = fetched("/cut-once/orknux-server.jar")

        assertThat(done.failure).isNull()
        assertThat(done.state).isEqualTo(ServerReleaseDownloadState.DONE)
        assertThat(done.attempts).isEqualTo(2)
        assertThat(done.resumed).isEqualTo(1)
        assertThat(done.received).isEqualTo(size)
        assertThat(done.total).isEqualTo(size)
        // The second connection asked for the rest, and only the rest was sent.
        assertThat(asked).containsExactly("none", "bytes=${size / 2}-")
        assertThat(servedBytes.get()).isEqualTo(size)

        val release = releases.findById(done.releaseId!!).get()
        assertThat(release.sha256).isEqualTo(ReleaseJarVerifier.sha256(served))
        assertThat(storedBytes(release)).isEqualTo(Files.readAllBytes(served))
    }

    @Test
    fun `a source that ignores Range is fetched again from the first byte, and that is said rather than resumed`() {
        served = TestReleaseJars.signed(Shape(version = "9.7.2", padding = 1024 * 1024))
        val size = Files.size(served)

        val done = fetched("/no-range/orknux-server.jar")

        assertThat(done.state).isEqualTo(ServerReleaseDownloadState.DONE)
        assertThat(done.attempts).isEqualTo(2)
        assertThat(done.resumed).isEqualTo(0)
        assertThat(asked).containsExactly("none", "bytes=${size / 2}-")
        assertThat(storedBytes(releases.findById(done.releaseId!!).get())).isEqualTo(Files.readAllBytes(served))
    }

    @Test
    fun `a link that breaks every quarter still finishes, because only attempts that bring nothing count`() {
        settings.setReleaseDownloadAttempts(2, "alice")
        served = TestReleaseJars.signed(Shape(version = "9.7.3", padding = 1024 * 1024))

        val done = fetched("/quarters/orknux-server.jar")

        assertThat(done.failure).isNull()
        assertThat(done.attempts).isEqualTo(4)
        assertThat(done.resumed).isEqualTo(3)
        assertThat(storedBytes(releases.findById(done.releaseId!!).get())).isEqualTo(Files.readAllBytes(served))
    }

    @Test
    fun `a source that never sends anything is given up on after the limit, saying why`() {
        settings.setReleaseDownloadAttempts(3, "alice")
        served = TestReleaseJars.signed(Shape(version = "9.7.4"))

        val failed = fetched("/dead/orknux-server.jar")

        assertThat(failed.state).isEqualTo(ServerReleaseDownloadState.FAILED)
        assertThat(failed.attempts).isEqualTo(3)
        assertThat(failed.failedInRow).isEqualTo(3)
        assertThat(failed.failure)
            .startsWith("Nothing was fetched from 127.0.0.1: it broke 3 times in a row without sending anything more; the last time, it answered 503")
        assertThat(releases.findAll()).isEmpty()

        // And it is what the page is shown, in a sentence.
        graphQlTester.document("{ serverReleaseDownload { state failure attempts } }").execute()
            .path("serverReleaseDownload.state").entity(String::class.java).isEqualTo("FAILED")
            .path("serverReleaseDownload.failure").entity(String::class.java).isEqualTo(failed.failure!!)
    }

    @Test
    fun `while it runs the row says how much of how much has arrived, how fast, and a second download is refused`() {
        served = TestReleaseJars.signed(Shape(version = "9.7.5", padding = 1024 * 1024))
        val size = Files.size(served)
        val latch = CountDownLatch(1)
        paused = latch

        graphQlTester.document(
            """mutation(${'$'}url: String!) { installServerReleaseFromUrl(url: ${'$'}url) { id state received } }""",
        ).variable("url", "http://${where()}/pause/orknux-server.jar").execute()
            // Answered at once, before a byte has arrived.
            .path("installServerReleaseFromUrl.state").entity(String::class.java).isEqualTo("DOWNLOADING")

        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            var seen: Map<String, Any?> = emptyMap()
            while (System.nanoTime() < deadline) {
                @Suppress("UNCHECKED_CAST")
                seen = graphQlTester.document("{ serverReleaseDownload { state received total bytesPerSecond } }").execute()
                    .path("serverReleaseDownload").entity(Map::class.java).get() as Map<String, Any?>
                if ((seen["received"] as Number).toLong() == size / 2 && (seen["bytesPerSecond"] as Number).toLong() > 0) break
                Thread.sleep(100)
            }
            assertThat(seen["state"]).isEqualTo("DOWNLOADING")
            assertThat((seen["received"] as Number).toLong()).isEqualTo(size / 2)
            assertThat((seen["total"] as Number).toLong()).isEqualTo(size)
            assertThat((seen["bytesPerSecond"] as Number).toLong()).isGreaterThan(0)

            graphQlTester.document(
                """mutation { installServerReleaseFromUrl(url: "http://${where()}/cut-once/orknux-server.jar") { id } }""",
            ).execute().errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("ServerReleaseDownloadRunning") }
        } finally {
            latch.countDown()
        }
        val done = ReleaseDownloadsAwait.newest(downloads)
        assertThat(done.state).isEqualTo(ServerReleaseDownloadState.DONE)
        assertThat(done.attempts).isEqualTo(1)
    }

    @Test
    fun `an official release is resumed too, the install key on every connection, and checked against its listing`() {
        served = TestReleaseJars.signed(Shape(version = "9.7.6", padding = 2 * 1024 * 1024))
        val size = Files.size(served)

        graphQlTester.document("""mutation { installServerRelease(version: "9.7.6") { id total activate } }""").execute()
            .path("installServerRelease.total").entity(Long::class.java).isEqualTo(size)
        val done = ReleaseDownloadsAwait.newest(downloads)

        assertThat(done.failure).isNull()
        assertThat(done.state).isEqualTo(ServerReleaseDownloadState.DONE)
        assertThat(done.resumed).isEqualTo(1)
        assertThat(asked).containsExactly("none", "bytes=${size / 2}-")
        assertThat(keyless.get()).isEqualTo(0)
        val release = releases.findById(done.releaseId!!).get()
        assertThat(release.state).isEqualTo(ServerReleaseState.ACTIVATING)
        assertThat(storedBytes(release)).isEqualTo(Files.readAllBytes(served))
    }

    @Test
    fun `a download whose server stopped keeping it fresh is reported as such, not drawn for ever`() {
        val row = downloads.save(
            ServerReleaseDownload(
                source = ServerReleaseSource.URL,
                host = "artifactory.invalid",
                sourceUrl = "https://artifactory.invalid/orknux.jar",
                state = ServerReleaseDownloadState.DOWNLOADING,
                received = 10,
                startedBy = "alice",
                updatedAt = java.time.OffsetDateTime.now().minusMinutes(10),
            ),
        )

        graphQlTester.document("{ serverReleaseDownload { id state failure } }").execute()
            .path("serverReleaseDownload.state").entity(String::class.java).isEqualTo("FAILED")
            .path("serverReleaseDownload.failure").entity(String::class.java)
            .isEqualTo("The server that was downloading it stopped before it finished; start it again.")
        // And no longer stands in the way of the next one.
        served = TestReleaseJars.signed(Shape(version = "9.7.7"))
        assertThat(fetched("/cut-once/orknux-server.jar").state).isEqualTo(ServerReleaseDownloadState.DONE)
        assertThat(downloads.findById(row.id!!).get().state).isEqualTo(ServerReleaseDownloadState.FAILED)
    }

    @Test
    fun `the server that comes back settles an update, done on the version it went for and failed in the launcher's words`() {
        val running = updates.runningVersion()
        val came = downloads.save(
            ServerReleaseDownload(
                source = ServerReleaseSource.ORKNUX_AI, version = running, host = "orknux.ai", sourceUrl = "https://orknux.ai/x.jar",
                state = ServerReleaseDownloadState.RESTARTING, activate = true, startedBy = "alice",
            ),
        )
        val release = updates.store(TestReleaseJars.signed(Shape(version = "9.7.8")), ServerReleaseSource.UPLOAD, "alice")
        jdbc.update("UPDATE server_release SET state = 'FAILED', failure = 'it did not start in 3 attempts' WHERE id = ?", release.id)
        val refused = downloads.save(
            ServerReleaseDownload(
                source = ServerReleaseSource.ORKNUX_AI, version = "9.7.8", host = "orknux.ai", sourceUrl = "https://orknux.ai/y.jar",
                state = ServerReleaseDownloadState.RESTARTING, activate = true, releaseId = release.id, startedBy = "alice",
            ),
        )

        background.settle()

        assertThat(downloads.findById(came.id!!).get().state).isEqualTo(ServerReleaseDownloadState.DONE)
        val failed = downloads.findById(refused.id!!).get()
        assertThat(failed.state).isEqualTo(ServerReleaseDownloadState.FAILED)
        assertThat(failed.failure).isEqualTo("Server release 9.7.8 did not start: it did not start in 3 attempts")
    }

    @Test
    fun `the retry numbers are Admin Settings, refused out of range`() {
        graphQlTester.document(
            """mutation { setReleaseDownloadAttempts(count: 9) { releaseDownloadAttempts releaseDownloadAttemptsConfigured } }""",
        ).execute()
            .path("setReleaseDownloadAttempts.releaseDownloadAttempts").entity(Int::class.java).isEqualTo(9)
            .path("setReleaseDownloadAttempts.releaseDownloadAttemptsConfigured").entity(Int::class.java).isEqualTo(5)
        graphQlTester.document("""mutation { setReleaseDownloadBackoffSeconds(seconds: 4) { releaseDownloadBackoffSeconds } }""")
            .execute().path("setReleaseDownloadBackoffSeconds.releaseDownloadBackoffSeconds").entity(Int::class.java).isEqualTo(4)
        graphQlTester.document("""mutation { setReleaseDownloadBackoffMaxSeconds(seconds: 90) { releaseDownloadBackoffMaxSeconds } }""")
            .execute().path("setReleaseDownloadBackoffMaxSeconds.releaseDownloadBackoffMaxSeconds").entity(Int::class.java).isEqualTo(90)

        graphQlTester.document("""mutation { setReleaseDownloadAttempts(count: 0) { releaseDownloadAttempts } }""").execute()
            .errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("ReleaseDownloadAttemptsOutOfRange") }
        graphQlTester.document("""mutation { setReleaseDownloadBackoffSeconds(seconds: 0) { releaseDownloadBackoffSeconds } }""").execute()
            .errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("ReleaseDownloadBackoffOutOfRange") }
        graphQlTester.document("""mutation { setReleaseDownloadBackoffMaxSeconds(seconds: 4000) { releaseDownloadBackoffMaxSeconds } }""")
            .execute()
            .errors().satisfy { assertThat(it.single().extensions["code"]).isEqualTo("ReleaseDownloadBackoffMaxOutOfRange") }
        assertThat(settings.releaseDownloadAttempts()).isEqualTo(9)
    }

    companion object {
        @Volatile
        lateinit var served: Path

        /** The Range header of every request for the jar, "none" where there was none. */
        val asked: MutableList<String> = CopyOnWriteArrayList()

        /** Bytes of the jar actually written to a socket, across every connection. */
        val servedBytes = java.util.concurrent.atomic.AtomicLong()

        /** Requests for the official jar that came without the install key. */
        val keyless = java.util.concurrent.atomic.AtomicInteger()

        /** Holds the second half of /pause/ back until a test lets it go. */
        @Volatile
        var paused: CountDownLatch? = null

        /** Requests per path, for the paths that behave differently the first time. */
        private val seen = java.util.concurrent.ConcurrentHashMap<String, Int>()

        private val stub: HttpServer =
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                executor = java.util.concurrent.Executors.newCachedThreadPool()

                /** Writes [from] until [until] of the jar and, where that is short of the end, cuts the connection. */
                fun send(exchange: HttpExchange, status: Int, from: Long, until: Long, length: Long) {
                    val bytes = Files.readAllBytes(served)
                    exchange.responseHeaders.add("Content-Type", "application/java-archive")
                    exchange.responseHeaders.add("Accept-Ranges", "bytes")
                    if (status == 206) exchange.responseHeaders.add("Content-Range", "bytes $from-${bytes.size - 1}/${bytes.size}")
                    exchange.sendResponseHeaders(status, length)
                    val out = exchange.responseBody
                    try {
                        out.write(bytes, from.toInt(), (until - from).toInt())
                        out.flush()
                        servedBytes.addAndGet(until - from)
                        if (until < bytes.size) {
                            // Short of what the length promised: the connection goes, as a reset would.
                            throw java.io.IOException("cut")
                        }
                        out.close()
                    } catch (_: java.io.IOException) {
                        exchange.close()
                    }
                }

                fun rangeOf(exchange: HttpExchange): Long? =
                    exchange.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toLongOrNull()

                createContext("/graphql") { exchange ->
                    exchange.requestBody.readBytes()
                    val version = ReleaseJarVerifier.versionOf(served)
                    val body = """
                        {"data":{"serverReleases":[{"version":"$version","publishedAt":"2026-10-04","changelog":"",
                          "jarUrl":"http://${where()}/official/orknux-server-$version.jar","sha256":"${ReleaseJarVerifier.sha256(served)}",
                          "size":${Files.size(served)}}]}}
                    """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                    exchange.close()
                }

                createContext("/") { exchange ->
                    val path = exchange.requestURI.path
                    val size = Files.size(served)
                    val range = rangeOf(exchange)
                    asked += exchange.requestHeaders.getFirst("Range") ?: "none"
                    val first = seen.merge(path, 1, Int::plus) == 1 && range == null
                    when {
                        // Half, then the connection goes; the rest to whoever asks for it.
                        path.startsWith("/cut-once/") || path.startsWith("/official/") -> {
                            if (path.startsWith("/official/") && exchange.requestHeaders.getFirst("X-Orknux-Install-Key").isNullOrBlank()) {
                                keyless.incrementAndGet()
                            }
                            if (range == null) send(exchange, 200, 0, size / 2, size) else send(exchange, 206, range, size, size - range)
                        }
                        // Half, then the connection goes; and every answer is the whole jar whatever was asked.
                        path.startsWith("/no-range/") ->
                            if (range == null && first) send(exchange, 200, 0, size / 2, size) else send(exchange, 200, 0, size, size)
                        // A quarter per connection.
                        path.startsWith("/quarters/") -> {
                            val from = range ?: 0
                            val until = minOf(size, from + size / 4 + 1)
                            if (range == null) send(exchange, 200, 0, until, size) else send(exchange, 206, from, until, size - from)
                        }
                        path.startsWith("/dead/") -> {
                            exchange.sendResponseHeaders(503, -1)
                            exchange.close()
                        }
                        // Half, a pause the test holds open, then the rest on the same connection.
                        path.startsWith("/pause/") -> {
                            val bytes = Files.readAllBytes(served)
                            exchange.sendResponseHeaders(200, size)
                            exchange.responseBody.use { out ->
                                out.write(bytes, 0, (size / 2).toInt())
                                out.flush()
                                paused?.await(30, TimeUnit.SECONDS)
                                out.write(bytes, (size / 2).toInt(), (size - size / 2).toInt())
                            }
                            exchange.close()
                        }
                        else -> {
                            exchange.sendResponseHeaders(404, -1)
                            exchange.close()
                        }
                    }
                }
                start()
            }

        fun where() = "${stub.address.hostString}:${stub.address.port}"

        @JvmStatic
        @AfterAll
        fun stop() = stub.stop(0)

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("orknux.marketplace.url") { "http://${where()}/graphql" }
            registry.add("orknux.update.certificate") { TestReleaseJars.trusted.pem.toUri().toString() }
        }
    }
}
