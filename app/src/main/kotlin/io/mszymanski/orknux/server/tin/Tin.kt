package io.mszymanski.orknux.server.tin

import io.mszymanski.orknux.connector.security.SECRET_COLUMN_LENGTH
import io.mszymanski.orknux.connector.security.SecretConverter
import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

/** Whether a tin is the one embedded in this process or one reached over HTTP. */
enum class TinKind { LOCAL, REMOTE }

/**
 * A remote tin: an orktin server an administrator registered.
 *
 * The local tin has no row. It is configured in Admin Settings and lives in
 * this process, so a row for it would be a second place its settings could be
 * read from.
 *
 * The token is a credential and goes through [SecretConverter] like every
 * other; nothing outside [Tins] reads it, and nothing hands it to a browser -
 * the API says only whether one is stored.
 */
@Entity
@Table(name = "tin")
class Tin(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    /** What an agent names it by when it opens a session; unique, and never `local`. */
    @Column(nullable = false, length = 255)
    var name: String = "",

    /** What an agent's grant names; null is a tin no agent may use. */
    @Column(length = 255)
    var label: String? = null,

    /** The orktin server's base URL, without a trailing slash. */
    @Column(nullable = false, length = 1000)
    var address: String = "",

    @Convert(converter = SecretConverter::class)
    @Column(name = "token", length = SECRET_COLUMN_LENGTH)
    var token: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_modified_at", nullable = false)
    var lastModifiedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_modified_by", nullable = false, length = 255)
    var lastModifiedBy: String = "",
)

interface TinRepository : JpaRepository<Tin, Long> {
    fun findByName(name: String): Tin?
    fun findAllByOrderByNameAsc(): List<Tin>
}

/**
 * One sandbox session an agent opened on a tin, as this side remembers it.
 *
 * orktin keeps the session itself - its files, its executions, its log - and
 * forgets it a retention period after it stops. This is what lets a
 * conversation say which sessions it used after that, and what scopes a tool
 * call: an agent names a session by this row's id, and only the rows of its own
 * conversation answer.
 *
 * [tinId] and [agentId] carry no foreign key, as a watcher's agent does not: a
 * row is history, and a tin removed later should not take the record of what
 * was run on it with it.
 */
@Entity
@Table(name = "tin_session")
class TinSession(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long = 0,

    @Column(name = "llm_session_id", nullable = false)
    val llmSessionId: Long = 0,

    @Column(name = "agent_id")
    val agentId: Long? = null,

    @Column(name = "agent_name", length = 255)
    val agentName: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "tin_kind", nullable = false, length = 16)
    val tinKind: TinKind = TinKind.LOCAL,

    /** The remote tin's id; null for the local tin. */
    @Column(name = "tin_id")
    val tinId: Long? = null,

    /** What the tin was called when the session was opened. */
    @Column(name = "tin_name", nullable = false, length = 255)
    val tinName: String = "",

    @Column(name = "remote_session_id", nullable = false, length = 255)
    val remoteSessionId: String = "",

    /** orktin's own word for it, as last seen: creating, ready, stopping, stopped, failed. */
    @Column(nullable = false, length = 32)
    var status: String = "",

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_used_at")
    var lastUsedAt: OffsetDateTime? = null,

    @Column(name = "closed_at")
    var closedAt: OffsetDateTime? = null,
) {
    /** The tin's id as the API spells it: `local`, or the row id. */
    val tinRef: String get() = if (tinKind == TinKind.LOCAL) LOCAL_TIN_ID else tinId.toString()

    /** Whether orktin's word for it, as last seen, is one a session can still be used in. */
    val open: Boolean get() = status in OPEN_STATUSES
}

interface TinSessionRepository : JpaRepository<TinSession, Long> {
    fun findByLlmSessionIdOrderByCreatedAtDescIdDesc(llmSessionId: Long): List<TinSession>
    fun findByTinKindAndTinIdAndClosedAtIsNull(tinKind: TinKind, tinId: Long?): List<TinSession>
    fun findTop200ByTinKindAndTinIdOrderByCreatedAtDescIdDesc(tinKind: TinKind, tinId: Long?): List<TinSession>
}

/** The id the local tin is listed under. */
const val LOCAL_TIN_ID = "local"

/** The name an agent opens a session on the local tin by. */
const val LOCAL_TIN_NAME = "local"

/** orktin's statuses for a session that still has a sandbox. */
val OPEN_STATUSES = setOf("creating", "ready")
