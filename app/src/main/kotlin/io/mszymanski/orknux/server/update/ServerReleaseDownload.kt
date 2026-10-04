package io.mszymanski.orknux.server.update

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

/**
 * Where a server jar on its way in stands. Issue #602.
 *
 * In order: [DOWNLOADING], with [WAITING] between a broken connection and the
 * next; [VERIFYING] the complete file; [STORING] it in the database; then
 * [RESTARTING] for an update that starts the release, or [DONE]. [FAILED] at
 * any point, with the reason.
 */
enum class ServerReleaseDownloadState {
    DOWNLOADING, WAITING, VERIFYING, STORING, RESTARTING, DONE, FAILED;

    /** Somebody is still working on it: the states a heartbeat keeps fresh. */
    val working: Boolean get() = this == DOWNLOADING || this == WAITING || this == VERIFYING || this == STORING
}

/**
 * A server jar download, as the page and every replica see it. Issue #602.
 *
 * The replica doing the work writes it about once a second ([updatedAt] is the
 * heartbeat); everything else only reads it. The credential a URL may need is
 * not here and never was.
 */
@Entity
@Table(name = "server_release_download")
class ServerReleaseDownload(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val source: ServerReleaseSource = ServerReleaseSource.ORKNUX_AI,

    /** Listed by the official server, or read from a URL's jar once verified. */
    @Column(length = 64)
    var version: String? = null,

    @Column(nullable = false, length = 255)
    val host: String = "",

    /** Without a credential, a query or a fragment - see [ReleaseUrlFetcher.cleaned]. */
    @Column(name = "source_url", nullable = false, length = 2000)
    val sourceUrl: String = "",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var state: ServerReleaseDownloadState = ServerReleaseDownloadState.DOWNLOADING,

    @Column(nullable = false)
    var received: Long = 0,

    /** Null until the far end has said how long the jar is. */
    var total: Long? = null,

    @Column(name = "bytes_per_second", nullable = false)
    var bytesPerSecond: Long = 0,

    /** Connections opened. */
    @Column(nullable = false)
    var attempts: Int = 0,

    /** Of those, the ones that went on from bytes already held rather than from the start. */
    @Column(nullable = false)
    var resumed: Int = 0,

    /** Broken connections in a row that brought nothing; the give-up limit counts these. */
    @Column(name = "failed_in_row", nullable = false)
    var failedInRow: Int = 0,

    @Column(name = "next_attempt_at")
    var nextAttemptAt: OffsetDateTime? = null,

    /** Why the last connection broke, while it is being retried. */
    @Column(name = "last_error", length = 500)
    var lastError: String? = null,

    /** Update starts the release once stored; Fetch only stores it. */
    @Column(nullable = false)
    val activate: Boolean = false,

    @Column(name = "release_id")
    var releaseId: Long? = null,

    @Column(length = 1000)
    var failure: String? = null,

    @Column(name = "started_by", nullable = false, length = 120)
    val startedBy: String = "",

    @Column(name = "started_at", nullable = false)
    val startedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "finished_at")
    var finishedAt: OffsetDateTime? = null,

    @Column(nullable = false)
    var dismissed: Boolean = false,
)

interface ServerReleaseDownloadRepository : JpaRepository<ServerReleaseDownload, Long> {
    fun findFirstByOrderByIdDesc(): ServerReleaseDownload?
    fun findAllByStateIn(states: Collection<ServerReleaseDownloadState>): List<ServerReleaseDownload>
}
