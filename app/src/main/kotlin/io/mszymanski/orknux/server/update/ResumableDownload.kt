package io.mszymanski.orknux.server.update

import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.server.attachment.InstallationSettings
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * What one server jar download asks of [ResumableDownload]. Issue #602.
 *
 * The two sources differ only in what they send and how they word a refusal:
 * the official server wants its install key, and only on its own host; a URL
 * wants the administrator's credential, and only on the host it was typed for.
 */
class DownloadRequest(
    val start: URI,
    /** The headers to send to [URI] - asked at every hop, so a redirect elsewhere gets none. */
    val headersFor: (URI) -> Map<String, String>,
    /** The most bytes taken, counted as they arrive. */
    val limit: Long,
    val tooLarge: () -> RuntimeException,
    /**
     * A status that asking again will not change - a 401, a 404 - as the
     * refusal to end on. Statuses a retry may cure (408, 429, 5xx) never reach it.
     */
    val refused: (status: Int, at: URI) -> RuntimeException,
)

/** Where a download stands, handed out about once a second and at every change. */
data class DownloadProgress(
    val received: Long,
    val total: Long?,
    val bytesPerSecond: Long,
    val attempts: Int,
    val resumed: Int,
    val failedInRow: Int,
    /** Set while waiting to try again. */
    val nextAttemptAt: Instant?,
    val lastError: String?,
)

/** How it ended, for a test or a log line to read. */
data class DownloadOutcome(val received: Long, val attempts: Int, val resumed: Int)

/**
 * A server jar fetched over HTTP that survives its connection breaking. Issue #602.
 *
 * A third of a gigabyte over somebody's network breaks now and then, and a
 * download that starts from nothing every time can fail for ever on a link that
 * delivers nine tenths of it every time. So:
 *
 * - **It resumes.** A broken connection is followed by another asking for
 *   `Range: bytes=<held>-`, with `If-Range` where the first answer gave a
 *   validator, so the bytes already on disk are kept. A source that ignores the
 *   range answers 200, and the file starts again from its first byte; one that
 *   answers a range starting anywhere else is not trusted with the rest.
 * - **It notices silence.** No byte for [InstallationSettings.releaseDownloadSeconds]
 *   - to connect, to answer, or between two reads - breaks the connection, as a
 *   reset would.
 * - **It backs off and gives up.** The wait before the next connection starts at
 *   [InstallationSettings.releaseDownloadBackoffSeconds] and doubles up to
 *   [InstallationSettings.releaseDownloadBackoffMaxSeconds]; a connection that
 *   brought bytes resets both the wait and the count, and after
 *   [InstallationSettings.releaseDownloadAttempts] in a row that brought nothing
 *   it stops, saying why the last one broke. All three are Admin Settings.
 * - **Redirects are followed by hand**, at every attempt from the first address
 *   (a signed redirect may have expired since), with the rules a URL release
 *   has always had: http and https only, never down from https, and the headers
 *   decided afresh for every host.
 *
 * What comes back is a file and nothing more; the signature, the structure and
 * the digest are checked on the complete file by the caller, exactly as before.
 */
@Component
class ResumableDownload(
    private val proxies: ProxyRouter,
    private val settings: InstallationSettings,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Fetches [request] into [to], calling [report] as it goes. Throws the refusal it ends on. */
    fun fetch(request: DownloadRequest, to: Path, report: (DownloadProgress) -> Unit): DownloadOutcome {
        val silence = settings.releaseDownloadSeconds().toLong()
        val client = proxies.builder()
            .connectTimeout(Duration.ofSeconds(silence))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
        val state = Run(request, to, silence, report)
        Files.newByteChannel(to, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).close()

        while (true) {
            if (Thread.currentThread().isInterrupted) throw ServerReleaseFetchFailedException(request.start.host, "the download was interrupted")
            val before = state.held
            val broken = try {
                if (state.attempt(client)) {
                    state.progress()
                    return DownloadOutcome(state.held, state.attempts, state.resumed)
                }
                null
            } catch (why: Broken) {
                why
            }
            val reason = broken?.why ?: "it ended without the whole jar"
            if (state.held > before) {
                state.failedInRow = 0
            } else {
                state.failedInRow++
            }
            val allowed = settings.releaseDownloadAttempts()
            if (state.failedInRow >= allowed) {
                state.lastError = reason
                state.progress()
                throw ServerReleaseFetchFailedException(
                    state.host,
                    "it broke $allowed ${if (allowed == 1) "time" else "times"} in a row without sending anything more; the last time, $reason",
                )
            }
            val wait = backoff(state.failedInRow, broken?.retryAfter)
            log.info("Server jar download from {} broke at {} bytes ({}); trying again in {} s", state.host, state.held, reason, wait)
            state.waitFor(wait, reason)
        }
    }

    /** The wait before the next connection: the first value, doubled for each nothing in a row, at most the last. */
    private fun backoff(failedInRow: Int, retryAfter: Long?): Long {
        val first = settings.releaseDownloadBackoffSeconds().toLong()
        val most = maxOf(first, settings.releaseDownloadBackoffMaxSeconds().toLong())
        val doubled = (0 until maxOf(0, failedInRow - 1)).fold(first) { wait, _ -> minOf(most, wait * 2) }
        // A source that says when to come back is believed, within the same ceiling.
        return retryAfter?.coerceIn(first, most) ?: doubled
    }

    /** A connection that broke in a way another may not. */
    private class Broken(val why: String, val retryAfter: Long? = null) : RuntimeException(why, null, false, false)

    private inner class Run(
        val request: DownloadRequest,
        val to: Path,
        val silence: Long,
        val report: (DownloadProgress) -> Unit,
    ) {
        val host: String = request.start.host ?: "that address"
        var held = 0L
        var total: Long? = null
        var attempts = 0
        var resumed = 0
        var failedInRow = 0
        var lastError: String? = null

        /** What the first full answer said the jar is, so a resume can ask for the same one. */
        private var validator: String? = null

        /** (when, bytes held) over the last few seconds, which is what the speed is read off. */
        private val samples = ArrayDeque<Pair<Long, Long>>()
        private var lastReport = 0L

        fun progress(nextAttemptAt: Instant? = null) {
            val now = System.nanoTime()
            samples.addLast(now to held)
            while (samples.size > 1 && now - samples.first().first > SPEED_WINDOW_NANOS) samples.removeFirst()
            val (since, from) = samples.first()
            val speed = if (now - since <= 0) 0 else (held - from) * 1_000_000_000L / (now - since)
            lastReport = now
            report(DownloadProgress(held, total, maxOf(0, speed), attempts, resumed, failedInRow, nextAttemptAt, lastError))
        }

        /** Waits [seconds] before the next connection, saying so about once a second. */
        fun waitFor(seconds: Long, why: String) {
            lastError = why
            samples.clear()
            val until = Instant.now().plusSeconds(seconds)
            while (Instant.now().isBefore(until)) {
                progress(until)
                try {
                    Thread.sleep(minOf(REPORT_EVERY_MILLIS, maxOf(1, Duration.between(Instant.now(), until).toMillis())))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw ServerReleaseFetchFailedException(host, "the download was interrupted")
                }
            }
        }

        /** One connection: true once the whole jar is on disk, false or [Broken] where it is not. */
        fun attempt(client: HttpClient): Boolean {
            attempts++
            val from = held
            // Where this connection starts, so the speed is of what it brings and not of what was held.
            samples.addLast(System.nanoTime() to held)
            var at = request.start
            repeat(ReleaseUrlFetcher.MAX_REDIRECTS + 1) {
                val builder = HttpRequest.newBuilder(at).timeout(Duration.ofSeconds(silence)).GET()
                request.headersFor(at).forEach { (name, value) -> builder.header(name, value) }
                if (from > 0) {
                    builder.header("Range", "bytes=$from-")
                    validator?.let { builder.header("If-Range", it) }
                }
                var subscriber: Appending? = null
                val pending = client.sendAsync(builder.build()) { info ->
                    when (info.statusCode()) {
                        200 -> {
                            // The whole jar: a fresh start, or a source that ignored the range.
                            val length = info.headers().firstValueAsLong("Content-Length").orElse(-1)
                            if (length > request.limit) throw request.tooLarge()
                            remember(info)
                            total = length.takeIf { it >= 0 }
                            if (from > 0) log.info("{} ignored the range; the jar is fetched again from its first byte", host)
                            held = 0
                            Appending(to, 0, request.limit, request.tooLarge).also { subscriber = it }
                        }
                        206 -> {
                            val range = ContentRange.parse(info.headers().firstValue("Content-Range").orElse(null))
                            if (range == null || range.start != from) {
                                // Not the bytes asked for; the next connection asks for the whole jar.
                                validator = null
                                held = 0
                                throw Broken("it answered a different range than the one asked for")
                            }
                            if (range.total != null && range.total > request.limit) throw request.tooLarge()
                            total = range.total ?: total
                            resumed++
                            Appending(to, from, request.limit, request.tooLarge).also { subscriber = it }
                        }
                        else -> HttpResponse.BodySubscribers.replacing(null)
                    }
                }
                val answer = await(pending) { subscriber }
                when (val status = answer.statusCode()) {
                    200, 206 -> {
                        val known = total
                        return known == null || held == known
                    }
                    301, 302, 303, 307, 308 -> at = redirected(at, answer, status)
                    416 -> {
                        // Asked for past the end: either it is all here already, or the jar changed.
                        val length = ContentRange.parse(answer.headers().firstValue("Content-Range").orElse(null))?.total
                        if (length != null && length == held) {
                            total = length
                            return true
                        }
                        held = 0
                        validator = null
                        throw Broken("it would not send the rest of the jar (416)")
                    }
                    408, 425, 429, 500, 502, 503, 504, 520, 521, 522, 523, 524 ->
                        throw Broken("it answered $status", retryAfter(answer))
                    else -> throw request.refused(status, at)
                }
            }
            throw ServerReleaseFetchFailedException(host, "it redirected more than ${ReleaseUrlFetcher.MAX_REDIRECTS} times")
        }

        private fun remember(info: HttpResponse.ResponseInfo) {
            val etag = info.headers().firstValue("ETag").orElse(null)?.takeIf { !it.startsWith("W/") }
            validator = etag ?: info.headers().firstValue("Last-Modified").orElse(null)
        }

        /** Waits for one answer, reporting as bytes arrive and breaking the connection on silence. */
        private fun await(pending: CompletableFuture<HttpResponse<Long?>>, subscriber: () -> Appending?): HttpResponse<Long?> {
            var heard = System.nanoTime()
            var seen = held
            while (true) {
                try {
                    return pending.get(POLL_MILLIS, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    val appending = subscriber()
                    if (appending != null) held = appending.position
                    val now = System.nanoTime()
                    if (held != seen) {
                        seen = held
                        heard = now
                    }
                    if (now - lastReport >= TimeUnit.MILLISECONDS.toNanos(REPORT_EVERY_MILLIS)) progress()
                    if (now - heard > TimeUnit.SECONDS.toNanos(silence)) {
                        pending.cancel(true)
                        appending?.abandon()
                        throw Broken("nothing arrived for $silence seconds")
                    }
                } catch (failure: ExecutionException) {
                    subscriber()?.let {
                        held = it.position
                        it.abandon()
                    }
                    throw translated(failure.cause ?: failure)
                } catch (_: InterruptedException) {
                    pending.cancel(true)
                    subscriber()?.abandon()
                    Thread.currentThread().interrupt()
                    throw ServerReleaseFetchFailedException(host, "the download was interrupted")
                } finally {
                    subscriber()?.let { held = it.position }
                }
            }
        }

        private fun redirected(at: URI, answer: HttpResponse<*>, status: Int): URI {
            val location = answer.headers().firstValue("Location").orElse(null)
                ?: throw ServerReleaseFetchFailedException(at.host, "it answered $status without saying where to")
            val next = runCatching { at.resolve(location.trim()) }.getOrNull()
                ?: throw ServerReleaseUrlRefusedException("it redirected to an address that is not a URL")
            val scheme = next.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                throw ServerReleaseUrlRefusedException("it redirected to a ${scheme ?: "relative"}: address, and only http and https are followed")
            }
            if (scheme != at.scheme.lowercase() && scheme != "https") {
                throw ServerReleaseUrlRefusedException("it redirected from https to http, which is not followed")
            }
            if (next.host.isNullOrBlank()) throw ServerReleaseUrlRefusedException("it redirected to an address with no host")
            return next
        }

        /** A refusal stays a refusal; everything that happened to the wire is [Broken], and asked again. */
        private fun translated(cause: Throwable): RuntimeException {
            // A handler's own exception arrives wrapped, sometimes twice; what it was decides.
            generateSequence(cause) { it.cause?.takeIf { next -> next !== it } }.take(MAX_CAUSES).forEach {
                when (it) {
                    is Broken -> return it
                    is ServerReleaseTooLargeException, is ServerReleaseFetchFailedException, is ServerReleaseUrlRefusedException ->
                        return it as RuntimeException
                }
            }
            return wire(cause)
        }

        private fun wire(cause: Throwable): RuntimeException = when (cause) {
            is java.net.http.HttpConnectTimeoutException -> Broken("it could not be connected to within $silence seconds")
            is java.net.http.HttpTimeoutException -> Broken("it did not answer within $silence seconds")
            is java.net.ConnectException -> Broken("it refused the connection")
            is java.nio.channels.UnresolvedAddressException, is java.net.UnknownHostException -> Broken("the name does not resolve")
            is javax.net.ssl.SSLHandshakeException ->
                ServerReleaseFetchFailedException(host, "its certificate is not trusted here (${cause.message})")
            is java.io.EOFException -> Broken("the connection closed before the whole jar had arrived")
            is java.io.IOException -> Broken("the connection broke (${cause.message ?: cause.javaClass.simpleName})")
            else -> cause.cause?.takeIf { it !== cause }?.let(::wire)
                ?: Broken(cause.message ?: cause.javaClass.simpleName)
        }

        private fun retryAfter(answer: HttpResponse<*>): Long? =
            answer.headers().firstValue("Retry-After").orElse(null)?.trim()?.toLongOrNull()
    }

    /** `Content-Range: bytes 100-199/1000`, or a star for the range and the total alone, on a 416. */
    internal data class ContentRange(val start: Long?, val total: Long?) {
        companion object {
            private val SHAPE = Regex("""bytes\s+(?:(\d+)-(\d+)|\*)/(\d+|\*)""", RegexOption.IGNORE_CASE)

            fun parse(header: String?): ContentRange? {
                val match = SHAPE.matchEntire(header?.trim() ?: return null) ?: return null
                return ContentRange(match.groupValues[1].toLongOrNull(), match.groupValues[3].toLongOrNull())
            }
        }
    }

    /**
     * Writes the body at [offset] onwards a buffer at a time, counting against
     * the limit as it goes; [position] is read by the thread reporting progress.
     */
    private class Appending(
        to: Path,
        offset: Long,
        private val limit: Long,
        private val tooLarge: () -> RuntimeException,
    ) : HttpResponse.BodySubscriber<Long?> {
        private val result = CompletableFuture<Long?>()
        private val channel = FileChannel.open(to, StandardOpenOption.WRITE, StandardOpenOption.CREATE).apply {
            truncate(offset)
            position(offset)
        }
        private var subscription: Flow.Subscription? = null

        @Volatile
        var position: Long = offset
            private set

        override fun getBody(): CompletableFuture<Long?> = result

        override fun onSubscribe(subscription: Flow.Subscription) {
            this.subscription = subscription
            subscription.request(1)
        }

        override fun onNext(item: List<ByteBuffer>) {
            if (result.isDone) return
            try {
                for (buffer in item) {
                    if (position + buffer.remaining() > limit) return fail(tooLarge())
                    while (buffer.hasRemaining()) position += channel.write(buffer)
                }
                subscription?.request(1)
            } catch (failure: Exception) {
                fail(failure)
            }
        }

        override fun onError(throwable: Throwable) {
            close()
            result.completeExceptionally(throwable)
        }

        override fun onComplete() {
            close()
            result.complete(position)
        }

        fun abandon() {
            subscription?.cancel()
            close()
        }

        private fun fail(failure: Throwable) {
            subscription?.cancel()
            close()
            result.completeExceptionally(failure)
        }

        private fun close() = runCatching { channel.close() }
    }

    companion object {
        /** How often progress is handed out, which is also the heartbeat a row is kept fresh by. */
        const val REPORT_EVERY_MILLIS = 1000L

        private const val POLL_MILLIS = 250L

        /** How deep a wrapped failure is searched for the one that says what happened. */
        private const val MAX_CAUSES = 8

        /** The speed shown is over the last few seconds: smooth enough to read, recent enough to be true. */
        private val SPEED_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(5)
    }
}
