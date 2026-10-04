package io.mszymanski.orknux.server.update

import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.plugin.Marketplace
import io.mszymanski.orknux.server.plugin.OfferedServerRelease
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Server jars downloaded in the background. Issue #602.
 *
 * Update and Fetch used to download, verify and store a third of a gigabyte
 * inside the administrator's request, and whatever sat in front of the server
 * and cut long requests cut the update with it - the browser was left with
 * "Failed to fetch" and the server, as like as not, carried on. Now the request
 * writes a [ServerReleaseDownload] row, starts the work on a thread of its own
 * and answers at once; the page polls the row, and so can any other replica.
 *
 * The work is [ResumableDownload] for the bytes, then exactly what the request
 * used to do: the digest and size against the listing for an official
 * release, [ServerReleases.store] for the signature and structure, and for an
 * update the activation and the restart. Each step is written to the row as
 * it starts, so the page can say which one it is on and which one failed.
 *
 * The replica doing it keeps the row fresh about once a second. One that has
 * not been touched for longer than a connection may go silent belongs to a
 * server that went away - a crash, a redeploy - and is reported as failed the
 * next time anybody looks, rather than drawn as a download for ever.
 */
@Service
class ServerReleaseDownloads(
    private val downloads: ServerReleaseDownloadRepository,
    private val updates: ServerReleases,
    private val download: ResumableDownload,
    private val marketplace: Marketplace,
    private val fetcher: ReleaseUrlFetcher,
    private val settings: InstallationSettings,
    private val restart: ServerRestart,
    private val audit: WorkspaceAuditRecorder,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * The newest download nobody has put away, with one whose server stopped
     * reported as failed. Null where there is none.
     */
    fun current(): ServerReleaseDownload? {
        val newest = downloads.findFirstByOrderByIdDesc() ?: return null
        if (newest.dismissed) return null
        return abandoned(newest)
    }

    /**
     * Starts [listed] from the official server: downloaded, checked against the
     * listing and the release key, stored, and started. A release the database
     * already holds by digest is only started, here and now.
     */
    fun startOfficial(listed: OfferedServerRelease, by: String): ServerReleaseDownload {
        requireNoneWorking()
        val limit = settings.releaseMaxMb() * 1024L * 1024L
        if (listed.size > limit) throw ServerReleaseTooLargeException(settings.releaseMaxMb())
        val request = marketplace.serverJarRequest(listed.jarUrl, limit) { ServerReleaseTooLargeException(settings.releaseMaxMb()) }

        val row = downloads.save(
            ServerReleaseDownload(
                source = ServerReleaseSource.ORKNUX_AI,
                version = listed.version,
                host = request.start.host,
                sourceUrl = ReleaseUrlFetcher.cleaned(request.start),
                total = listed.size.takeIf { it > 0 },
                activate = true,
                startedBy = by,
            ),
        )
        val held = updates.bySha256(listed.sha256)
        if (held != null) {
            // Nothing to fetch: the bytes are here, and Update means start them.
            val job = Job(row)
            try {
                job.received(listed.size)
                job.activated(held.id!!)
            } catch (refused: RuntimeException) {
                job.finish(ServerReleaseDownloadState.FAILED, failure = refused.message)
                throw refused
            }
            return job.row
        }
        launch(row) { job, file ->
            download.fetch(request, file, job::progress)
            job.step(ServerReleaseDownloadState.VERIFYING)
            if (Files.size(file) != listed.size || ReleaseJarVerifier.sha256(file) != listed.sha256) {
                throw ServerReleaseDownloadMismatchException(listed.version)
            }
            val release = updates.store(file, ServerReleaseSource.ORKNUX_AI, by) { job.step(ServerReleaseDownloadState.STORING) }
            job.activated(release.id!!)
        }
        return row
    }

    /**
     * Starts fetching the jar at [url] - a company's repository, #589 -
     * verified and stored as an upload is, and not started. [credential] lives
     * in this call's memory and the download thread's, and nowhere else.
     */
    fun startUrl(url: String, credential: String?, by: String): ServerReleaseDownload {
        requireNoneWorking()
        val request = fetcher.jarRequest(url, credential)
        val from = ReleaseUrlFetcher.cleaned(request.start)
        val row = downloads.save(
            ServerReleaseDownload(
                source = ServerReleaseSource.URL,
                host = request.start.host,
                sourceUrl = from,
                activate = false,
                startedBy = by,
            ),
        )
        launch(row) { job, file ->
            download.fetch(request, file, job::progress)
            job.step(ServerReleaseDownloadState.VERIFYING)
            val release = updates.store(file, ServerReleaseSource.URL, by, sourceUrl = from) { verified ->
                job.step(ServerReleaseDownloadState.STORING, version = verified.version)
            }
            log.info("Server release jar fetched from {}", from)
            audit.recordAutomated(
                null,
                WorkspaceAuditCategory.WORKSPACE,
                "Server release ${release.version} fetched from ${URI(from).host}",
                actor = by,
            )
            job.finish(ServerReleaseDownloadState.DONE, releaseId = release.id)
        }
        return row
    }

    /** Puts away how a download ended, so the page stops showing it. Refused for one still working. */
    fun dismiss(id: Long): ServerReleaseDownload {
        val row = downloads.findByIdOrNull(id)?.let(::abandoned) ?: throw ServerReleaseDownloadNotFoundException(id)
        if (row.state.working) throw ServerReleaseDownloadRunningException(row.version ?: row.host)
        row.dismissed = true
        return downloads.save(row)
    }

    /**
     * An update that restarted the server, settled by the server that came
     * back: on the version it went for is done, and one whose release the
     * launcher gave up on failed, in the launcher's words. A release still
     * being tried is left as it is.
     */
    @EventListener(ApplicationReadyEvent::class)
    @Order(Int.MAX_VALUE)
    fun settle() {
        for (row in downloads.findAllByStateIn(listOf(ServerReleaseDownloadState.RESTARTING))) {
            val release = row.releaseId?.let { updates.stored().firstOrNull { stored -> stored.id == it } }
            when {
                row.version == updates.runningVersion() -> {
                    row.state = ServerReleaseDownloadState.DONE
                }
                release?.state == ServerReleaseState.FAILED -> {
                    row.state = ServerReleaseDownloadState.FAILED
                    row.failure = "Server release ${row.version} did not start: ${release.failure ?: "no reason was given"}"
                }
                release?.state == ServerReleaseState.ACTIVATING -> continue
                else -> {
                    row.state = ServerReleaseDownloadState.FAILED
                    row.failure = "The server came back on ${updates.runningVersion()} rather than ${row.version}."
                }
            }
            row.finishedAt = now()
            row.updatedAt = now()
            downloads.save(row)
        }
    }

    private fun requireNoneWorking() {
        downloads.findAllByStateIn(ServerReleaseDownloadState.entries.filter { it.working })
            .map(::abandoned)
            .firstOrNull { it.state.working }
            ?.let { throw ServerReleaseDownloadRunningException(it.version ?: it.host) }
    }

    /** [row], or the same row marked failed where its server stopped keeping it fresh. */
    private fun abandoned(row: ServerReleaseDownload): ServerReleaseDownload {
        if (!row.state.working) return row
        val silent = OffsetDateTime.now().minusSeconds(settings.releaseDownloadSeconds().toLong())
        if (!row.updatedAt.isBefore(silent)) return row
        row.state = ServerReleaseDownloadState.FAILED
        row.failure = "The server that was downloading it stopped before it finished; start it again."
        row.finishedAt = now()
        return downloads.save(row)
    }

    /** Runs [work] on a thread of its own, with a temporary file it removes, and any refusal written down. */
    private fun launch(row: ServerReleaseDownload, work: (Job, Path) -> Unit) {
        val job = Job(row)
        Thread.ofVirtual().name("orknux-release-download-${row.id}").start {
            val file = Files.createTempFile("orknux-release-", ".jar")
            val heartbeat = Thread.ofVirtual().name("orknux-release-download-heartbeat-${row.id}").start {
                // Verifying and storing a third of a gigabyte take a while and report nothing.
                try {
                    while (true) {
                        Thread.sleep(ResumableDownload.REPORT_EVERY_MILLIS)
                        job.heartbeat()
                    }
                } catch (_: InterruptedException) {
                    // Done.
                }
            }
            try {
                work(job, file)
            } catch (failure: Exception) {
                val why = failure.message ?: failure.javaClass.simpleName
                if (failure is Refusal || failure is io.mszymanski.orknux.server.plugin.MarketplaceUnreachableException) {
                    log.warn("Server release download from {} failed: {}", row.host, why)
                } else {
                    log.error("Server release download from {} failed", row.host, failure)
                }
                job.finish(ServerReleaseDownloadState.FAILED, failure = why)
            } finally {
                heartbeat.interrupt()
                runCatching { Files.deleteIfExists(file) }
            }
        }
    }

    /**
     * One download's row, written by the thread doing it and by its heartbeat,
     * one at a time. Each write is the whole row, so it is merged rather than
     * patched; a row somebody else already settled is not written over.
     */
    private inner class Job(var row: ServerReleaseDownload) {
        private val lock = Any()

        fun progress(progress: DownloadProgress) = write {
            received = progress.received
            total = progress.total ?: total
            bytesPerSecond = progress.bytesPerSecond
            attempts = progress.attempts
            resumed = progress.resumed
            failedInRow = progress.failedInRow
            lastError = progress.lastError
            nextAttemptAt = progress.nextAttemptAt?.atOffset(ZoneOffset.UTC)
            state = if (progress.nextAttemptAt != null) ServerReleaseDownloadState.WAITING else ServerReleaseDownloadState.DOWNLOADING
        }

        fun received(bytes: Long) = write {
            received = bytes
            total = bytes
        }

        fun step(next: ServerReleaseDownloadState, version: String? = null) = write {
            state = next
            bytesPerSecond = 0
            nextAttemptAt = null
            total?.let { received = it }
            if (version != null) this.version = version
        }

        fun heartbeat() = write { }

        /** The release is stored, or was already: make it the one every server runs, and restart. */
        fun activated(releaseId: Long) {
            val before = updates.runningVersion()
            val release = updates.activate(releaseId, row.startedBy)
            val wording = when {
                ReleaseVersion.newer(release.version, before) -> "Server updated to ${release.version}"
                release.version == before -> "Server release ${release.version} re-applied"
                else -> "Server rolled back to ${release.version}"
            }
            audit.recordAutomated(null, WorkspaceAuditCategory.WORKSPACE, wording, actor = row.startedBy)
            write {
                this.releaseId = releaseId
                version = release.version
            }
            if (restart.soon()) {
                step(ServerReleaseDownloadState.RESTARTING)
            } else {
                // Nobody can restart this server; the page says somebody has to.
                finish(ServerReleaseDownloadState.DONE, releaseId = releaseId)
            }
        }

        fun finish(end: ServerReleaseDownloadState, failure: String? = null, releaseId: Long? = null) = write {
            state = end
            this.failure = failure?.take(1000)
            if (releaseId != null) this.releaseId = releaseId
            bytesPerSecond = 0
            nextAttemptAt = null
            finishedAt = now()
        }

        private fun write(change: ServerReleaseDownload.() -> Unit) {
            synchronized(lock) {
                val fresh = downloads.findByIdOrNull(row.id!!) ?: return
                // Settled elsewhere - by a page that judged this server gone - and not undone here.
                if (fresh.state == ServerReleaseDownloadState.FAILED && row.state != ServerReleaseDownloadState.FAILED) return
                row.change()
                row.updatedAt = now()
                row = downloads.save(row)
            }
        }
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now()
}

/** A second download while one is still under way. */
class ServerReleaseDownloadRunningException(val what: String) :
    RuntimeException("A server release download ($what) is already under way; wait for it to finish."), Refusal {
    override val arguments get() = mapOf("what" to what)
}

class ServerReleaseDownloadNotFoundException(val id: Long) :
    RuntimeException("There is no server release download $id."), Refusal {
    override val arguments get() = mapOf("id" to id)
}
