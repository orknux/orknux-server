package io.mszymanski.orknux.server.update

import org.springframework.data.repository.findByIdOrNull

/**
 * Waits out a background server release download, #602: the mutation answers
 * at once, and what a test asserts on is where the row ended.
 */
object ReleaseDownloadsAwait {
    fun finished(downloads: ServerReleaseDownloadRepository, id: Long, seconds: Long = 60): ServerReleaseDownload {
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        while (System.nanoTime() < deadline) {
            val row = downloads.findByIdOrNull(id) ?: error("no download $id")
            if (!row.state.working) return row
            Thread.sleep(50)
        }
        error("download $id did not finish within $seconds seconds: ${downloads.findByIdOrNull(id)?.state}")
    }

    /** The newest download's row, finished. */
    fun newest(downloads: ServerReleaseDownloadRepository, seconds: Long = 60): ServerReleaseDownload =
        finished(downloads, downloads.findFirstByOrderByIdDesc()?.id ?: error("no download was started"), seconds)
}
