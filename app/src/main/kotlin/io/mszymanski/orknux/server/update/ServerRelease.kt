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
 * Where a stored server jar came from: the official server, a file an
 * administrator uploaded, or a URL they gave - a company's own repository,
 * #589. The source is trusted for availability only; every one is verified the
 * same way.
 */
enum class ServerReleaseSource { ORKNUX_AI, UPLOAD, URL }

/**
 * A server jar this installation holds, without its bytes. Issue #584.
 *
 * The bytes are `server_release_part`, written and read over JDBC a piece at a
 * time ([JdbcReleaseStore]) and never through this entity: a jar loaded into a
 * persistence context would be a third of a gigabyte on the heap for as long as
 * the transaction lasts. The launcher moves [state] and [bootAttempts] over the
 * same plain JDBC, before any context exists.
 */
@Entity
@Table(name = "server_release")
class ServerRelease(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, length = 64)
    val version: String = "",

    /** Of the bytes as stored. Integrity only - the signature is the proof. */
    @Column(nullable = false, length = 64)
    val sha256: String = "",

    @Column(nullable = false)
    val size: Long = 0,

    @Column(name = "schema_version", nullable = false)
    val schemaVersion: Int = 0,

    @Column(name = "schema_floor", nullable = false)
    val schemaFloor: Int = 0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val source: ServerReleaseSource = ServerReleaseSource.UPLOAD,

    /**
     * Where a [ServerReleaseSource.URL] release was fetched from, without the
     * credential, the query or the fragment - so nothing that authenticated the
     * download is ever written down. Null for the other sources.
     */
    @Column(name = "source_url", length = 2000)
    val sourceUrl: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var state: ServerReleaseState = ServerReleaseState.STORED,

    @Column(name = "boot_attempts", nullable = false)
    var bootAttempts: Int = 0,

    /** What to go back to if this never starts; null is the image's own jar. */
    @Column(name = "fallback_id")
    var fallbackId: Long? = null,

    /** The image's version when this was activated; a newer image wins over it. */
    @Column(name = "image_version", length = 64)
    var imageVersion: String? = null,

    /** Why it is FAILED, in the words the launcher or the server used. */
    @Column(length = 500)
    var failure: String? = null,

    /** False until a server that came up has put the failure in the audit log. */
    @Column(name = "failure_reported", nullable = false)
    var failureReported: Boolean = true,

    @Column(name = "stored_at", nullable = false)
    val storedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "stored_by", nullable = false, length = 120)
    val storedBy: String = "",

    @Column(name = "activated_at")
    var activatedAt: OffsetDateTime? = null,

    @Column(name = "activated_by", length = 120)
    var activatedBy: String? = null,

    /** When it last came up; null for one that never has. */
    @Column(name = "booted_at")
    var bootedAt: OffsetDateTime? = null,
)

interface ServerReleaseRepository : JpaRepository<ServerRelease, Long> {
    fun findBySha256(sha256: String): ServerRelease?
    fun findFirstByVersionOrderByIdDesc(version: String): ServerRelease?
    fun findAllByOrderByStoredAtDescIdDesc(): List<ServerRelease>
    fun findAllByStateIn(states: Collection<ServerReleaseState>): List<ServerRelease>
    fun findAllByStateAndFailureReportedFalse(state: ServerReleaseState): List<ServerRelease>
}
