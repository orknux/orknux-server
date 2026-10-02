package io.mszymanski.orknux.server.update

import io.mszymanski.orknux.connector.proxy.ProxyRouter
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.graphql.Refusal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
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
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A release a company's repository lists in its releases.json. */
data class ListedServerRelease(val version: String, val jarUrl: String)

/**
 * A server jar from a URL an administrator gives - a company's Artifactory, or
 * any repository the platform team fills. Issue #589.
 *
 * The source is trusted for availability only: what comes back is a file in a
 * temporary directory and nothing more, and [ServerReleases.store] verifies it
 * exactly as it does an upload. What this class owns is getting there safely:
 *
 * - **Only http and https**, at the start and at every redirect, and a redirect
 *   may not step down from https. `file:`, `jar:` and the rest would read this
 *   server's own disk or classpath.
 * - **The credential goes to the host it was typed for**, as `Authorization`,
 *   and is dropped at a redirect to anywhere else - an Artifactory that hands
 *   the download to a storage bucket must not hand it the token as well. It is
 *   never stored, logged or audited; what is written down is [cleaned].
 * - **Through [ProxyRouter]**, so the installation's proxy rules and trusted
 *   authorities decide what this server may reach, as for every outbound call.
 * - **Bounded twice:** by the Admin Settings jar size, counted as it arrives
 *   rather than trusted from a header, and by the download time, which covers
 *   connecting, answering and the whole body - a stalled stream would
 *   otherwise hold the administrator's request for ever.
 */
@Component
class ReleaseUrlFetcher(
    private val proxies: ProxyRouter,
    private val settings: InstallationSettings,
    private val mapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Fetches [url] into [to], following up to [MAX_REDIRECTS] redirects.
     * Answers the URL as it may be written down.
     */
    fun fetch(url: String, credential: String?, to: Path): String {
        val start = checked(url)
        val limit = settings.releaseMaxMb() * 1024L * 1024L
        get(start, credential, limit, to) { ServerReleaseTooLargeException(settings.releaseMaxMb()) }
        log.info("Server release jar fetched from {}", cleaned(start))
        return cleaned(start)
    }

    /**
     * What `releases.json` in the directory [url] lists, newer than [after],
     * newest first. Each `jarUrl` is resolved against the directory, so the
     * file may name its jars relatively.
     */
    fun listed(url: String, credential: String?, after: String): List<ListedServerRelease> {
        val directory = checked(url)
        if (!directory.path.orEmpty().endsWith("/")) throw ServerReleaseUrlRefusedException(NOT_A_DIRECTORY)
        val index = directory.resolve(INDEX)
        val file = Files.createTempFile("orknux-releases-", ".json")
        try {
            get(index, credential, MAX_INDEX_BYTES, file) {
                ServerReleaseFetchFailedException(index.host, "its $INDEX is larger than ${MAX_INDEX_BYTES / 1024} KB")
            }
            val tree = try {
                mapper.readTree(file.toFile())
            } catch (_: Exception) {
                throw ServerReleaseFetchFailedException(index.host, "its $INDEX is not JSON")
            }
            if (!tree.isArray) throw ServerReleaseFetchFailedException(index.host, "its $INDEX is not a list")
            return tree.values().toList().mapNotNull { node ->
                val version = node.path("version").asString("").trim()
                val jar = node.path("jarUrl").asString("").trim()
                if (ReleaseVersion.parse(version) == null || jar.isEmpty()) return@mapNotNull null
                val resolved = runCatching { directory.resolve(jar) }.getOrNull() ?: return@mapNotNull null
                if (resolved.scheme?.lowercase() !in SCHEMES) return@mapNotNull null
                ListedServerRelease(version, resolved.toString())
            }
                .filter { ReleaseVersion.newer(it.version, after) }
                .sortedByDescending { ReleaseVersion.parse(it.version) }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun get(start: URI, credential: String?, limit: Long, to: Path, tooLarge: () -> RuntimeException) {
        val seconds = settings.releaseDownloadSeconds().toLong()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        val authorization = credential?.trim()?.ifEmpty { null }?.let(::authorization)
        val client = proxies.builder()
            .connectTimeout(Duration.ofSeconds(seconds))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

        var at = start
        repeat(MAX_REDIRECTS + 1) {
            val request = HttpRequest.newBuilder(at).timeout(Duration.ofSeconds(seconds)).GET().apply {
                if (authorization != null && sameOrigin(at, start)) header("Authorization", authorization)
            }.build()
            var subscriber: LimitedFile? = null
            val pending = client.sendAsync(request) { info ->
                if (info.statusCode() == 200) {
                    val declared = info.headers().firstValueAsLong("Content-Length").orElse(-1)
                    LimitedFile(to, limit, declared, tooLarge).also { subscriber = it }
                } else {
                    HttpResponse.BodySubscribers.replacing(null)
                }
            }
            val answer = try {
                pending.get(remaining(deadline, at), TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) {
                pending.cancel(true)
                subscriber?.abandon()
                throw ServerReleaseFetchFailedException(at.host, "it did not finish within $seconds seconds")
            } catch (failure: ExecutionException) {
                subscriber?.abandon()
                throw translated(failure.cause ?: failure, at)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                pending.cancel(true)
                throw ServerReleaseFetchFailedException(at.host, "the request was interrupted")
            }
            when (val status = answer.statusCode()) {
                200 -> return
                301, 302, 303, 307, 308 -> {
                    val location = answer.headers().firstValue("Location").orElse(null)
                        ?: throw ServerReleaseFetchFailedException(at.host, "it answered $status without saying where to")
                    val next = runCatching { at.resolve(location.trim()) }.getOrNull()
                        ?: throw ServerReleaseUrlRefusedException("it redirected to an address that is not a URL")
                    val scheme = next.scheme?.lowercase()
                    if (scheme !in SCHEMES) {
                        throw ServerReleaseUrlRefusedException("it redirected to a ${scheme ?: "relative"}: address, and only http and https are followed")
                    }
                    if (scheme != at.scheme.lowercase() && scheme != "https") {
                        throw ServerReleaseUrlRefusedException("it redirected from https to http, which is not followed")
                    }
                    if (next.host.isNullOrBlank()) throw ServerReleaseUrlRefusedException("it redirected to an address with no host")
                    at = next
                }
                401, 403 -> throw ServerReleaseFetchFailedException(
                    at.host,
                    if (authorization == null) "it answered $status; it wants a credential" else "it answered $status; check the credential",
                )
                else -> throw ServerReleaseFetchFailedException(at.host, "it answered $status")
            }
        }
        throw ServerReleaseFetchFailedException(start.host, "it redirected more than $MAX_REDIRECTS times")
    }

    private fun remaining(deadline: Long, at: URI): Long {
        val left = deadline - System.nanoTime()
        if (left <= 0) {
            throw ServerReleaseFetchFailedException(at.host, "it did not finish within ${settings.releaseDownloadSeconds()} seconds")
        }
        return left
    }

    /** What went wrong on the wire, as one of this class's refusals; never the request itself. */
    private fun translated(cause: Throwable, at: URI): RuntimeException = when (cause) {
        is ServerReleaseTooLargeException, is ServerReleaseFetchFailedException, is ServerReleaseUrlRefusedException -> cause as RuntimeException
        is java.net.http.HttpConnectTimeoutException -> ServerReleaseFetchFailedException(at.host, "it could not be connected to in time")
        is java.net.http.HttpTimeoutException -> ServerReleaseFetchFailedException(at.host, "it did not answer in time")
        is java.net.ConnectException -> ServerReleaseFetchFailedException(at.host, "it refused the connection")
        is java.nio.channels.UnresolvedAddressException, is java.net.UnknownHostException ->
            ServerReleaseFetchFailedException(at.host, "the name does not resolve")
        is javax.net.ssl.SSLException -> ServerReleaseFetchFailedException(at.host, "its certificate is not trusted here (${cause.message})")
        else -> cause.cause?.takeIf { it !== cause }?.let { translated(it, at) }
            ?: ServerReleaseFetchFailedException(at.host, cause.message ?: cause.javaClass.simpleName)
    }

    /**
     * Writes the body to a file a buffer at a time, counting as it goes, and
     * stops the stream the moment it passes the limit - the bytes past it are
     * never asked for, let alone written.
     */
    private class LimitedFile(
        private val to: Path,
        private val limit: Long,
        /** What the answer said it would send; refused before a byte is read where that is already too much. */
        private val declared: Long,
        private val tooLarge: () -> RuntimeException,
    ) : HttpResponse.BodySubscriber<Long?> {
        private val result = CompletableFuture<Long?>()
        private val channel = FileChannel.open(
            to,
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        private var subscription: Flow.Subscription? = null
        private var total = 0L

        override fun getBody(): CompletableFuture<Long?> = result

        override fun onSubscribe(subscription: Flow.Subscription) {
            this.subscription = subscription
            if (declared > limit) return fail(tooLarge())
            subscription.request(1)
        }

        override fun onNext(item: List<ByteBuffer>) {
            if (result.isDone) return
            try {
                for (buffer in item) {
                    total += buffer.remaining()
                    if (total > limit) return fail(tooLarge())
                    while (buffer.hasRemaining()) channel.write(buffer)
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
            result.complete(total)
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
        private val SCHEMES = setOf("http", "https")
        const val MAX_REDIRECTS = 5
        const val INDEX = "releases.json"

        /** A list of versions, not a jar; anything bigger is not one. */
        const val MAX_INDEX_BYTES = 1024L * 1024L

        private const val NOT_A_DIRECTORY = "it does not end in /, so it is a file rather than a directory to list"

        /** [url] if it is an absolute http or https URL with a host and no credential in it; refused otherwise. */
        fun checked(url: String): URI {
            val uri = try {
                URI(url.trim())
            } catch (_: Exception) {
                throw ServerReleaseUrlRefusedException("it is not a URL")
            }
            val scheme = uri.scheme?.lowercase()
            if (scheme !in SCHEMES) {
                throw ServerReleaseUrlRefusedException("only http and https are fetched, not ${scheme?.let { "$it:" } ?: "a relative address"}")
            }
            if (uri.host.isNullOrBlank()) throw ServerReleaseUrlRefusedException("it names no host")
            if (uri.rawUserInfo != null) {
                throw ServerReleaseUrlRefusedException("it carries a credential; give that in the credential field instead")
            }
            return uri
        }

        /**
         * The URL as it may be written down: scheme, host, port and path, and
         * nothing that could have authenticated the request.
         */
        fun cleaned(uri: URI): String = URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()

        /**
         * The `Authorization` header for what an administrator typed: a header
         * value already (`Bearer …`, `Basic …`) as it is, `user:password` as
         * Basic, and a bare token as Bearer.
         */
        fun authorization(credential: String): String {
            val scheme = credential.substringBefore(' ', "").lowercase()
            return when {
                scheme == "bearer" || scheme == "basic" -> credential
                ':' in credential -> "Basic " + Base64.getEncoder().encodeToString(credential.toByteArray(Charsets.UTF_8))
                else -> "Bearer $credential"
            }
        }

        private fun sameOrigin(a: URI, b: URI): Boolean =
            a.scheme.equals(b.scheme, ignoreCase = true) &&
                a.host.equals(b.host, ignoreCase = true) &&
                port(a) == port(b)

        private fun port(uri: URI): Int = if (uri.port != -1) uri.port else if (uri.scheme.equals("https", true)) 443 else 80
    }
}

/** A URL this server will not fetch a release from, and why. */
class ServerReleaseUrlRefusedException(val why: String) :
    RuntimeException("That URL cannot be used: $why."), Refusal {
    override val arguments get() = mapOf("why" to why)
}

/** A fetch that did not bring a jar back. Names the host and never the URL, which may carry more. */
class ServerReleaseFetchFailedException(val host: String?, val why: String) :
    RuntimeException("Nothing was fetched from ${host ?: "that address"}: $why."), Refusal {
    override val arguments get() = mapOf("host" to (host ?: ""), "why" to why)
}
