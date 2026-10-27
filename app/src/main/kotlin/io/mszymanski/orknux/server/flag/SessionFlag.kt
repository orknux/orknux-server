package io.mszymanski.orknux.server.flag

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

/** What a flag is about. The table's CHECK holds the same two, on both engines. */
enum class SessionFlagType(
    /** How the model names it in a call to flag_session: lower case, as a tool argument reads. */
    val said: String,
) {
    /** The person appears to be probing the product for a weakness. */
    SECURITY("security"),

    /** The agent found a fault in the harness or in how the installation is set up. */
    TECHNICAL_ISSUE("technical_issue"),
    ;

    companion object {
        /** The type a model named, whatever its capitals; null for anything else. */
        fun of(said: String?): SessionFlagType? =
            said?.trim()?.let { given -> entries.firstOrNull { it.said.equals(given, ignoreCase = true) || it.name.equals(given, ignoreCase = true) } }
    }
}

/** Whether a flag still stands, for the report's filter. */
enum class SessionFlagState { OPEN, CLEARED }

/**
 * A flag an agent raised on the session it was running in, through
 * [SessionFlagTools.FLAG]. See V636.
 *
 * Cleared rather than deleted, by the agent or by an administrator on the
 * report, so the report shows what was dealt with and by whom as well as what
 * is open.
 */
@Entity
@Table(name = "session_flag")
class SessionFlag(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "flagged_at", nullable = false)
    val flaggedAt: OffsetDateTime,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Column(name = "session_id", nullable = false)
    val sessionId: Long,

    /** The agent that raised it, by name: a flag outlives an agent renamed or removed. */
    @Column(name = "agent_name", nullable = false)
    val agentName: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    val type: SessionFlagType,

    /** From 0, harmless, to 5. */
    @Column(nullable = false)
    val severity: Int,

    @Column(nullable = false, columnDefinition = "TEXT")
    val note: String,

    /** When it was cleared; null while it stands. */
    @Column(name = "cleared_at")
    var clearedAt: OffsetDateTime? = null,

    /** Who cleared it: the agent's name where it took its own back, the administrator's otherwise. */
    @Column(name = "cleared_by")
    var clearedBy: String? = null,
) {
    val state: SessionFlagState get() = if (clearedAt == null) SessionFlagState.OPEN else SessionFlagState.CLEARED
}

interface SessionFlagRepository : JpaRepository<SessionFlag, Long>, JpaSpecificationExecutor<SessionFlag> {

    fun findBySessionIdOrderByFlaggedAtAscIdAsc(sessionId: Long): List<SessionFlag>

    fun countBySessionIdAndClearedAtIsNull(sessionId: Long): Long
}

/** The report's filter as conditions, written only for what it sets - see AGENTS.md on optional parameters. */
fun flagFilter(
    from: OffsetDateTime,
    to: OffsetDateTime,
    workspaceId: Long?,
    type: SessionFlagType?,
    severityFrom: Int?,
    severityTo: Int?,
    state: SessionFlagState?,
    search: String?,
): Specification<SessionFlag> = Specification { root, _, builder ->
    val predicates = mutableListOf(
        builder.greaterThanOrEqualTo(root.get("flaggedAt"), from),
        builder.lessThan(root.get("flaggedAt"), to),
    )
    workspaceId?.let { predicates += builder.equal(root.get<Long>("workspaceId"), it) }
    type?.let { predicates += builder.equal(root.get<SessionFlagType>("type"), it) }
    severityFrom?.let { predicates += builder.greaterThanOrEqualTo(root.get("severity"), it) }
    severityTo?.let { predicates += builder.lessThanOrEqualTo(root.get("severity"), it) }
    when (state) {
        SessionFlagState.OPEN -> predicates += builder.isNull(root.get<OffsetDateTime>("clearedAt"))
        SessionFlagState.CLEARED -> predicates += builder.isNotNull(root.get<OffsetDateTime>("clearedAt"))
        null -> Unit
    }
    search?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { said ->
        predicates += builder.like(builder.lower(root.get<String>("note")), "%$said%")
    }
    builder.and(*predicates.toTypedArray())
}
