package io.mszymanski.orknux.server.watcher

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime

/**
 * A tool an agent asked to have called on an interval, until what it returns
 * matches a condition. Issue #606.
 *
 * The row is the whole of its state - when it is next due, how often it has
 * looked, what came back last - because the clock that checks it is
 * db-scheduler's and keeps nothing in memory: a server that restarts finds its
 * watchers where it left them and carries on. See [WatcherService.tick].
 *
 * One firing and it ends. A condition that has become true usually stays true,
 * and a watcher that went on firing would wake its agent every interval about
 * the same thing; an agent that wants to keep watching sets another, having
 * read what it was woken for.
 */
@Entity
@Table(name = "watcher")
class Watcher(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    /** The conversation it wakes, which is the one it was set in. */
    @Column(name = "session_id", nullable = false)
    val sessionId: Long,

    /**
     * Whose grants it calls with. Null for a task worked by a bare model, which
     * has no agent row and whose grants are the task's - see
     * [WatcherService.agentFor]. No foreign key: a finished watcher is history,
     * and deleting an agent should not delete what it watched.
     */
    @Column(name = "agent_id")
    val agentId: Long?,

    /** As it was called when it set this, for a page read after a rename or a delete. */
    @Column(name = "agent_name", nullable = false)
    val agentName: String,

    @Column(nullable = false)
    val tool: String,

    /** The JSON object the tool is called with, as the agent wrote it. */
    @Column(nullable = false, columnDefinition = "text")
    val arguments: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "condition_kind", nullable = false, length = 16)
    val conditionKind: WatcherConditionKind,

    @Column(nullable = false, columnDefinition = "text")
    val condition: String,

    @Column(name = "interval_seconds", nullable = false)
    val intervalSeconds: Int,

    @Column(name = "timeout_seconds", nullable = false)
    val timeoutSeconds: Int,

    /** What the agent wanted it for, in its own words, handed back when it fires. */
    @Column(columnDefinition = "text")
    val note: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: WatcherStatus = WatcherStatus.ACTIVE,

    @Column(nullable = false)
    var checks: Int = 0,

    @Column(name = "last_result", columnDefinition = "text")
    var lastResult: String? = null,

    /** What matched: the value the path found, or the text the expression did. */
    @Column(columnDefinition = "text")
    var matched: String? = null,

    /** How it ended, in a sentence, for the page and the log. */
    @Column(length = 500)
    var outcome: String? = null,

    /** Who stopped it, where somebody did. */
    @Column(name = "finished_by")
    var finishedBy: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "expires_at", nullable = false)
    val expiresAt: OffsetDateTime,

    @Column(name = "next_check_at", nullable = false)
    var nextCheckAt: OffsetDateTime,

    @Column(name = "last_checked_at")
    var lastCheckedAt: OffsetDateTime? = null,

    @Column(name = "finished_at")
    var finishedAt: OffsetDateTime? = null,
)

enum class WatcherConditionKind {
    /** A JSONPath that finds something in the result. */
    JSONPATH,

    /** A regular expression found somewhere in the result. */
    REGEX,
}

enum class WatcherStatus {
    /** Still being checked. */
    ACTIVE,

    /** Its condition matched, and its agent was woken. */
    FIRED,

    /** Its time ran out without the condition matching. */
    TIMED_OUT,

    /** Its agent ended it with `watcher_finish`. */
    FINISHED,

    /** A person ended it from the Watchers page. */
    STOPPED,

    /** It could not go on: its agent or its tool is no longer there. */
    FAILED,
}

interface WatcherRepository : JpaRepository<Watcher, Long> {

    @Query("SELECT w.id FROM Watcher w WHERE w.status = :status AND w.nextCheckAt <= :now ORDER BY w.nextCheckAt, w.id")
    fun dueIds(@Param("status") status: WatcherStatus, @Param("now") now: OffsetDateTime): List<Long>

    fun countByAgentIdAndStatus(agentId: Long, status: WatcherStatus): Long

    fun countBySessionIdAndStatus(sessionId: Long, status: WatcherStatus): Long

    fun countBySessionIdAndAgentIdIsNullAndStatus(sessionId: Long, status: WatcherStatus): Long

    fun findByAgentIdAndStatusOrderByCreatedAtAscIdAsc(agentId: Long, status: WatcherStatus): List<Watcher>

    fun findBySessionIdAndAgentIdIsNullAndStatusOrderByCreatedAtAscIdAsc(sessionId: Long, status: WatcherStatus): List<Watcher>

    fun findByAgentIdOrderByCreatedAtAscIdAsc(agentId: Long): List<Watcher>

    fun findBySessionIdAndAgentIdIsNullOrderByCreatedAtAscIdAsc(sessionId: Long): List<Watcher>

    fun findByWorkspaceIdAndStatusOrderByCreatedAtDescIdDesc(
        workspaceId: Long,
        status: WatcherStatus,
        pageable: Pageable,
    ): Page<Watcher>

    fun findByWorkspaceIdAndStatusNotOrderByFinishedAtDescIdDesc(
        workspaceId: Long,
        status: WatcherStatus,
        pageable: Pageable,
    ): Page<Watcher>
}
