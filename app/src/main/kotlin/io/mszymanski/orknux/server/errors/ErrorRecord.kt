package io.mszymanski.orknux.server.errors

import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import io.mszymanski.orknux.workflow.execution.RunEnded
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

/** What failed: a workflow run, an agent's answer, or the quick chat's model. */
enum class ErrorSource { RUN, AGENT, MODEL }

/**
 * One thing that failed, for Admin > Reports > Errors. See V410.
 *
 * Written where the failure happens rather than read back out of runs and
 * transcripts, so the report is one query, and a transcript note that merely
 * mentions a failure is not mistaken for one.
 */
@Entity
@Table(name = "error_record")
class ErrorRecord(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val at: OffsetDateTime,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val source: ErrorSource,

    /** The workflow, agent or model by name, as it was called when it failed. */
    @Column(nullable = false)
    val subject: String,

    @Column(name = "execution_id")
    val executionId: Long? = null,

    @Column(name = "session_id")
    val sessionId: Long? = null,

    @Column(columnDefinition = "TEXT", nullable = false)
    val message: String,
)

interface ErrorRecordRepository : JpaRepository<ErrorRecord, Long>, JpaSpecificationExecutor<ErrorRecord>

/** The report's filter as conditions, written only for what it sets - see AGENTS.md on optional parameters. */
fun errorFilter(
    from: OffsetDateTime,
    to: OffsetDateTime,
    workspaceId: Long?,
    source: ErrorSource?,
    search: String?,
): Specification<ErrorRecord> = Specification { root, _, builder ->
    val predicates = mutableListOf(
        builder.greaterThanOrEqualTo(root.get("at"), from),
        builder.lessThan(root.get("at"), to),
    )
    workspaceId?.let { predicates += builder.equal(root.get<Long>("workspaceId"), it) }
    source?.let { predicates += builder.equal(root.get<ErrorSource>("source"), it) }
    search?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { said ->
        val like = "%$said%"
        predicates += builder.or(
            builder.like(builder.lower(root.get("subject")), like),
            builder.like(builder.lower(root.get("message")), like),
        )
    }
    builder.and(*predicates.toTypedArray())
}

/**
 * Where a failure is written down. Never throws: a failure that cannot be
 * recorded is logged, and whatever failed goes on failing the way it did.
 */
@Service
class ErrorRecorder(private val records: ErrorRecordRepository) {

    fun record(
        workspaceId: Long,
        source: ErrorSource,
        subject: String,
        message: String,
        executionId: Long? = null,
        sessionId: Long? = null,
    ) {
        try {
            records.save(
                ErrorRecord(
                    at = OffsetDateTime.now(),
                    workspaceId = workspaceId,
                    source = source,
                    subject = subject.take(SUBJECT_LENGTH),
                    executionId = executionId,
                    sessionId = sessionId,
                    message = message.ifBlank { "No reason was given." },
                ),
            )
        } catch (failure: Exception) {
            log.warn("A {} failure of {} could not be recorded", source, subject, failure)
        }
    }

    private companion object {
        const val SUBJECT_LENGTH = 255
        val log = LoggerFactory.getLogger(ErrorRecorder::class.java)
    }
}

/** A run that ended FAILED is an error; the execution module says only that it ended. */
@Component
class FailedRunRecorder(
    private val executions: WorkflowExecutionRepository,
    private val errors: ErrorRecorder,
) {

    @EventListener
    fun ended(event: RunEnded) {
        val run = executions.findByIdOrNull(event.executionId) ?: return
        if (run.status != ExecutionStatus.FAILED) return
        errors.record(run.workspaceId, ErrorSource.RUN, run.workflowName, run.error ?: "The run failed.", executionId = run.id)
    }
}
