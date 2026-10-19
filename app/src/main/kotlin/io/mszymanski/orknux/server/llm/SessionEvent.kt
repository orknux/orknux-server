package io.mszymanski.orknux.server.llm

import org.springframework.data.repository.findByIdOrNull

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * Something that arrived at a session for its agent to read: an agent it asked
 * answering, a timer it set coming due.
 *
 * The reason there is such a thing. An agent delegated, said it would check the
 * status later, and ended its turn - and nothing ever brought it back, so the
 * answer it had asked for landed with nobody to read it. The briefing now says
 * there is no later unless the agent makes one; this is the backup, so that an
 * answer it is owed reaches it whether or not it remembered to wait.
 */
@Entity
@Table(name = "session_event")
class SessionEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "session_id", nullable = false)
    val sessionId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val kind: SessionEventKind,

    @Column(nullable = false, columnDefinition = "text")
    val body: String,

    /** When it may be shown: now for an answer, later for a timer. */
    @Column(name = "due_at", nullable = false)
    val dueAt: OffsetDateTime = OffsetDateTime.now(),

    /** When the agent was shown it, and null until then, so it is read once. */
    @Column(name = "delivered_at")
    var deliveredAt: OffsetDateTime? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    /** Who wrote a [SessionEventKind.PERSON] message; null for everything the server posts. */
    @Column(name = "said_by")
    val saidBy: String? = null,
)

enum class SessionEventKind {
    /** An agent this session asked has answered. */
    ANSWER,

    /** A timer this session's agent set has come due. */
    TIMER,

    /** A watcher this session's agent set has fired, timed out or been stopped. Issue #606. */
    WATCHER,

    /** A person wrote in the chat while the agent was answering. */
    PERSON,
}

/**
 * A person's message, taken out of the inbox by a turn - so the chat can put it
 * into its thread at the point the agent read it, rather than when it was typed.
 */
data class PersonSaidInTurn(val sessionId: Long, val saidBy: String, val text: String)

interface SessionEventRepository : JpaRepository<SessionEvent, Long> {

    @Query(
        "SELECT e FROM SessionEvent e WHERE e.sessionId = :session AND e.deliveredAt IS NULL " +
            "AND e.dueAt <= :now ORDER BY e.dueAt, e.id",
    )
    fun due(@Param("session") session: Long, @Param("now") now: OffsetDateTime): List<SessionEvent>

    fun existsBySessionIdAndDeliveredAtIsNull(sessionId: Long): Boolean

    fun existsBySessionIdAndKindAndDeliveredAtIsNull(sessionId: Long, kind: SessionEventKind): Boolean

    fun existsBySessionIdAndKindAndDeliveredAtIsNullAndDueAtLessThanEqual(
        sessionId: Long,
        kind: SessionEventKind,
        now: OffsetDateTime,
    ): Boolean

    fun findFirstBySessionIdAndDeliveredAtIsNullOrderByDueAtAsc(sessionId: Long): SessionEvent?

    /** What of one kind is still to come at a session, soonest first - the reminders `timer_list` shows. */
    fun findBySessionIdAndKindAndDeliveredAtIsNullOrderByDueAtAscIdAsc(sessionId: Long, kind: SessionEventKind): List<SessionEvent>

    /** One kind at a session, read and unread, newest first - the session page's Timers panel. */
    fun findTop100BySessionIdAndKindOrderByCreatedAtDescIdDesc(sessionId: Long, kind: SessionEventKind): List<SessionEvent>

    @Query("SELECT DISTINCT e.sessionId FROM SessionEvent e WHERE e.deliveredAt IS NULL AND e.dueAt <= :now")
    fun sessionsDue(@Param("now") now: OffsetDateTime): List<Long>

    /** Sessions with something unread that came due in (after, upTo]: announced once each. */
    @Query(
        "SELECT DISTINCT e.sessionId FROM SessionEvent e WHERE e.deliveredAt IS NULL " +
            "AND e.dueAt > :after AND e.dueAt <= :upTo",
    )
    fun sessionsDueBetween(@Param("after") after: OffsetDateTime, @Param("upTo") upTo: OffsetDateTime): List<Long>
}

/**
 * Published when an event is due at a session, so whatever owns that session
 * can wake it. Published rather than called, the rule for what arrives: the
 * inbox knows nothing of workflow steps or tasks, and each of them listens.
 */
data class SessionEventDue(val sessionId: Long)

/**
 * A session's inbox: what arrived for its agent, and what has been read.
 *
 * Read in two places. A turn that is running takes what is due between its
 * rounds - see `AgentConversation` - so an answer landing mid-turn is in front
 * of the model at its next step. A session that is not running is woken by
 * [SessionEventDue], and the turn it wakes into reads the same way.
 */
@Service
class SessionInbox(
    private val events: SessionEventRepository,
    private val recorder: LlmSessionRecorder,
    private val published: ApplicationEventPublisher,
) {

    /** Leaves something for the session's agent, due now unless a later time is given. */
    @Transactional
    fun post(sessionId: Long, kind: SessionEventKind, body: String, dueAt: OffsetDateTime = OffsetDateTime.now()) {
        posted(sessionId, kind, body, dueAt)
    }

    /** The same, answering the id it was left under - so something that may be called off can be. */
    @Transactional
    fun posted(sessionId: Long, kind: SessionEventKind, body: String, dueAt: OffsetDateTime = OffsetDateTime.now()): Long {
        val saved = events.save(SessionEvent(sessionId = sessionId, kind = kind, body = body, dueAt = dueAt))
        if (!dueAt.isAfter(OffsetDateTime.now())) published.publishEvent(SessionEventDue(sessionId))
        return requireNotNull(saved.id)
    }

    /**
     * Calls off something left for later and not yet read - a timer paused or
     * removed before it rang. One already read stays: it happened.
     */
    @Transactional
    fun withdraw(id: Long) {
        events.findByIdOrNull(id)?.takeIf { it.deliveredAt == null }?.let(events::delete)
    }

    /**
     * The same, asked from inside a session: only what was left for that session
     * and is still unread may go, so an agent can call off its own reminder and
     * nobody else's. Answers whether anything was there to call off.
     */
    @Transactional
    fun withdraw(sessionId: Long, id: Long): Boolean {
        val event = events.findByIdOrNull(id)?.takeIf { it.sessionId == sessionId && it.deliveredAt == null } ?: return false
        events.delete(event)
        return true
    }

    /** What of one kind is still to come at a session, soonest first. */
    fun waiting(sessionId: Long, kind: SessionEventKind): List<SessionEvent> =
        events.findBySessionIdAndKindAndDeliveredAtIsNullOrderByDueAtAscIdAsc(sessionId, kind)

    /**
     * Leaves what a person wrote while the agent was answering. The turn reads
     * it at its next round, as the person speaking.
     */
    @Transactional
    fun postFromPerson(sessionId: Long, saidBy: String, text: String) {
        events.save(SessionEvent(sessionId = sessionId, kind = SessionEventKind.PERSON, body = text, saidBy = saidBy))
        published.publishEvent(SessionEventDue(sessionId))
    }

    /**
     * What is due, marked read and written into the transcript as said to the
     * agent - so a later turn recalls it like anything else it was told.
     *
     * A person's message is written under their name and handed to the model
     * saying who wrote it and when: as a bare user turn it read like the
     * server's own reports, and an agent half-way through a task took it for one.
     */
    @Transactional
    fun take(sessionId: Long): List<String> {
        val due = events.due(sessionId, OffsetDateTime.now())
        val now = OffsetDateTime.now()
        due.forEach { event ->
            event.deliveredAt = now
            val person = event.saidBy?.takeIf { event.kind == SessionEventKind.PERSON }
            if (person == null) {
                recorder.userSaid(sessionId, FROM, event.body)
            } else {
                recorder.userSaid(sessionId, person, event.body)
                published.publishEvent(PersonSaidInTurn(sessionId, person, event.body))
            }
        }
        events.saveAll(due)
        return due.map { event ->
            if (event.kind == SessionEventKind.PERSON) {
                "${event.saidBy ?: "The person"} wrote this while you were working - read it before your next step:\n\n${event.body}"
            } else {
                event.body
            }
        }
    }

    /** Whether anything is still to come for this session, due or not. */
    fun pending(sessionId: Long): Boolean = events.existsBySessionIdAndDeliveredAtIsNull(sessionId)

    /** Whether something of this kind is still unread here. #618. */
    fun pending(sessionId: Long, kind: SessionEventKind): Boolean =
        events.existsBySessionIdAndKindAndDeliveredAtIsNull(sessionId, kind)

    /** Whether something of this kind is unread here and already due - a reminder that has come up, not one still to come. */
    fun due(sessionId: Long, kind: SessionEventKind): Boolean =
        events.existsBySessionIdAndKindAndDeliveredAtIsNullAndDueAtLessThanEqual(sessionId, kind, OffsetDateTime.now())

    /** When the next thing is due, or null where nothing is waiting. */
    fun nextDue(sessionId: Long): OffsetDateTime? =
        events.findFirstBySessionIdAndDeliveredAtIsNullOrderByDueAtAsc(sessionId)?.dueAt

    /** Every session with something due and unread, for the sweep that wakes them. */
    fun sessionsDue(): List<Long> = events.sessionsDue(OffsetDateTime.now())

    companion object {
        /** Who an event is said by in the transcript. */
        const val FROM = "orknux"
    }
}
