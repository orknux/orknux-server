package io.mszymanski.orknux.server.schedule

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
 * A reminder an agent set for itself that comes back on its own: every so many
 * seconds, or on a five-field cron. The periodic counterpart of `timer_set`.
 *
 * The row is the whole of its state - when it next runs, how many times it has
 * - because the clock that runs it is db-scheduler's and keeps nothing in
 * memory: a server that restarts finds its schedules where it left them. See
 * [ScheduleService.tick].
 *
 * Not called `Schedule`, which is what db-scheduler calls its own idea of when
 * a task runs, and the two meet in [ScheduleSchedulerConfig].
 */
@Entity
@Table(name = "agent_schedule")
class AgentSchedule(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    /** The conversation it reminds, which is the one it was set in. */
    @Column(name = "session_id", nullable = false)
    val sessionId: Long,

    /**
     * Whose it is, for the per-agent limit and for whose `schedule_cancel` may
     * end it. Null for a task worked by a bare model, which has no agent row;
     * such a schedule belongs to its session. No foreign key, as a watcher's
     * has none: an ended schedule is history.
     */
    @Column(name = "agent_id")
    val agentId: Long?,

    /** As it was called when it set this, for a page read after a rename or a delete. */
    @Column(name = "agent_name", nullable = false)
    val agentName: String,

    /** What the agent wanted reminding of, handed back on every run. */
    @Column(nullable = false, columnDefinition = "text")
    val note: String,

    /** Every this many seconds; null for a cron schedule. */
    @Column(name = "every_seconds")
    val everySeconds: Int? = null,

    /** A five-field cron; null for an interval schedule. */
    @Column(length = 255)
    val cron: String? = null,

    /** The zone a cron is read in, kept as it was resolved when set; null for an interval. */
    @Column(length = 64)
    val timezone: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var status: ScheduleStatus = ScheduleStatus.ACTIVE,

    @Column(nullable = false)
    var runs: Int = 0,

    /** The most runs it makes before it ends; null for no limit. */
    @Column(name = "max_runs")
    val maxRuns: Int? = null,

    /** When it ends by itself; null for never. */
    @Column(name = "until_at")
    val untilAt: OffsetDateTime? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    /** When it next runs; null once it has ended. */
    @Column(name = "next_run_at")
    var nextRunAt: OffsetDateTime?,

    @Column(name = "last_run_at")
    var lastRunAt: OffsetDateTime? = null,

    @Column(name = "finished_at")
    var finishedAt: OffsetDateTime? = null,

    /** How it ended, in a sentence, for the page and the agent's list. */
    @Column(length = 500)
    var outcome: String? = null,
)

enum class ScheduleStatus {
    /** Still coming up. */
    ACTIVE,

    /** Stopped before it ended by itself - by its agent, or by a person. */
    STOPPED,

    /** It reached its number of runs or its end time. */
    ENDED,
}

interface AgentScheduleRepository : JpaRepository<AgentSchedule, Long> {

    @Query(
        "SELECT s.id FROM AgentSchedule s WHERE s.status = :status AND s.nextRunAt <= :now " +
            "ORDER BY s.nextRunAt, s.id",
    )
    fun dueIds(@Param("status") status: ScheduleStatus, @Param("now") now: OffsetDateTime): List<Long>

    fun countByAgentIdAndStatus(agentId: Long, status: ScheduleStatus): Long

    fun countBySessionIdAndAgentIdIsNullAndStatus(sessionId: Long, status: ScheduleStatus): Long

    fun findByAgentIdAndStatusOrderByCreatedAtAscIdAsc(agentId: Long, status: ScheduleStatus): List<AgentSchedule>

    fun findBySessionIdAndAgentIdIsNullAndStatusOrderByCreatedAtAscIdAsc(
        sessionId: Long,
        status: ScheduleStatus,
    ): List<AgentSchedule>

    /** One session's schedules, every state, newest first - the session page's panel. */
    fun findTop100BySessionIdOrderByCreatedAtDescIdDesc(sessionId: Long): List<AgentSchedule>

    fun findByWorkspaceIdAndStatusOrderByCreatedAtDescIdDesc(
        workspaceId: Long,
        status: ScheduleStatus,
        pageable: Pageable,
    ): Page<AgentSchedule>

    fun findByWorkspaceIdAndStatusNotOrderByFinishedAtDescIdDesc(
        workspaceId: Long,
        status: ScheduleStatus,
        pageable: Pageable,
    ): Page<AgentSchedule>
}
