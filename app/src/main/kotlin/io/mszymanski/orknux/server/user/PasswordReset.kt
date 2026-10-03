package io.mszymanski.orknux.server.user

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

/**
 * A password reset link that has been mailed out.
 *
 * Only the hash is kept, the way [AppUserToken] keeps only the hash of a token:
 * the secret goes into one mail and is never recoverable from here, so a copy of
 * the database is not a drawer full of working links.
 *
 * Single use and short lived, and both are enforced when it is followed rather
 * than by anything sweeping the table. A row nobody uses simply stops working at
 * [expiresAt] and sits there being worthless.
 */
@Entity
@Table(name = "password_reset")
class PasswordReset(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "token_hash", nullable = false, length = 64)
    val tokenHash: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    /**
     * When the link stops working, decided when it was made.
     *
     * Stored rather than derived, so an installation that shortens the lifetime
     * does not reach back and cut short a link already sitting in somebody's
     * inbox - nor lengthen one it had promised would be dead by now.
     */
    @Column(name = "expires_at", nullable = false)
    val expiresAt: OffsetDateTime,

    /** Null until it is followed; after that this link is spent. */
    @Column(name = "used_at")
    var usedAt: OffsetDateTime? = null,
) {

    /** Whether following it now would work, which is the only question asked of it. */
    fun usable(now: OffsetDateTime): Boolean = usedAt == null && now.isBefore(expiresAt)
}

interface PasswordResetRepository : JpaRepository<PasswordReset, Long> {

    /** Looked up by hash, because the hash is all that was kept. */
    fun findByTokenHash(tokenHash: String): PasswordReset?

    fun findByUserId(userId: Long): List<PasswordReset>

    fun deleteByUserId(userId: Long)

    /**
     * Spends a link, once: one row changed means this caller spent it, none
     * means somebody else got there first. Issue #588.
     */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
        "update PasswordReset r set r.usedAt = :at where r.id = :id and r.usedAt is null",
    )
    fun spend(
        @org.springframework.data.repository.query.Param("id") id: Long,
        @org.springframework.data.repository.query.Param("at") at: java.time.OffsetDateTime,
    ): Int
}
