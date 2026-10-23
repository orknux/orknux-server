package io.mszymanski.orknux.server.rating

import io.mszymanski.orknux.server.usage.PromptSource
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import java.time.OffsetDateTime

/**
 * A review the person an agent was talking to wrote about the conversation,
 * sent on by the agent through [RatingTools]. See V403.
 *
 * Who rated is named the way the usage ledger names who prompted, so the two
 * reports say the same thing about the same person.
 */
@Entity
@Table(name = "session_rating")
class SessionRating(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val at: OffsetDateTime,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Column(name = "session_id", nullable = false)
    val sessionId: Long,

    @Column(name = "agent_name", nullable = false)
    val agentName: String,

    /** From 1 to 5. */
    @Column(nullable = false)
    val score: Int,

    /** What they wrote, where they wrote anything. */
    @Column(columnDefinition = "TEXT")
    val review: String?,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val source: PromptSource,

    @Column(name = "connection_id")
    val connectionId: Long?,

    @Column(name = "person_key", nullable = false)
    val personKey: String,

    @Column(name = "person_name")
    val personName: String?,

    /** When the review was taken back; null while it stands. Kept rather than deleted, so the chat can say so. */
    @Column(name = "withdrawn_at")
    var withdrawnAt: OffsetDateTime? = null,
)

interface SessionRatingRepository : JpaRepository<SessionRating, Long>, JpaSpecificationExecutor<SessionRating> {

    /** The reviews a conversation has sent that still stand: one taken back no longer counts against its share. */
    fun countBySessionIdAndWithdrawnAtIsNull(sessionId: Long): Long

    fun findBySessionIdOrderByAtAsc(sessionId: Long): List<SessionRating>
}

/** The report's filter as conditions, written only for what it sets - see AGENTS.md on optional parameters. */
fun ratingFilter(
    from: OffsetDateTime,
    to: OffsetDateTime,
    workspaceId: Long?,
    source: PromptSource?,
    search: String?,
): Specification<SessionRating> = Specification { root, _, builder ->
    val predicates = mutableListOf(
        // A review taken back is not a review the report counts.
        builder.isNull(root.get<OffsetDateTime>("withdrawnAt")),
        builder.greaterThanOrEqualTo(root.get("at"), from),
        builder.lessThan(root.get("at"), to),
    )
    workspaceId?.let { predicates += builder.equal(root.get<Long>("workspaceId"), it) }
    source?.let { predicates += builder.equal(root.get<PromptSource>("source"), it) }
    search?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { said ->
        val like = "%$said%"
        predicates += builder.or(
            builder.like(builder.lower(builder.coalesce(root.get("personName"), "")), like),
            builder.like(builder.lower(root.get("personKey")), like),
            builder.like(builder.lower(root.get("agentName")), like),
            builder.like(builder.lower(builder.coalesce(root.get("review"), "")), like),
        )
    }
    builder.and(*predicates.toTypedArray())
}
