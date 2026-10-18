package io.mszymanski.orknux.server.design

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

enum class DesignImageJobStatus { QUEUED, DRAWING, DRAWN, FAILED }

/**
 * A picture asked for on Design's Images page, waiting to be drawn or being
 * drawn.
 *
 * A row rather than a call held open by the page, because a drawing can take
 * minutes - a local model on a CPU took twelve for one picture - and whoever
 * asked must be free to leave, ask for more, and come back to find them. What
 * it was asked with is kept here so the picture it becomes is filed with it;
 * [imageId] names that picture once it is drawn.
 */
@Entity
@Table(name = "design_image_job")
class DesignImageJob(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Column(name = "model_id")
    val modelId: Long?,

    @Column(name = "model_name", nullable = false, length = 255)
    val modelName: String,

    @Column(nullable = false, columnDefinition = "text")
    val prompt: String,

    @Column(length = 40)
    val size: String?,

    @Column(length = 40)
    val quality: String?,

    @Column(length = 40)
    val style: String?,

    @Column(name = "requested_by", nullable = false, length = 255)
    val requestedBy: String,

    @Column(name = "requested_at", nullable = false)
    val requestedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "started_at")
    var startedAt: OffsetDateTime? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: DesignImageJobStatus = DesignImageJobStatus.QUEUED,

    /** Why it was not drawn, in the provider's words where it gave any. */
    @Column(columnDefinition = "text")
    var reason: String? = null,

    @Column(name = "image_id")
    var imageId: Long? = null,

    /** What it was named before it was drawn; null names it after its prompt. */
    @Column(length = 255)
    val name: String? = null,
)

interface DesignImageJobRepository : JpaRepository<DesignImageJob, Long> {

    /** What a workspace has waiting, drawing or failed - the tiles at the front of its gallery. */
    fun findByWorkspaceIdAndStatusInOrderByRequestedAtAscIdAsc(
        workspaceId: Long,
        statuses: Collection<DesignImageJobStatus>,
    ): List<DesignImageJob>

    fun findByStatusOrderByRequestedAtAscIdAsc(status: DesignImageJobStatus): List<DesignImageJob>

    fun countByStatus(status: DesignImageJobStatus): Long

    /**
     * Takes one queued picture for drawing, or reports that somebody else did.
     *
     * An update conditioned on the status rather than a read and a save, so
     * two servers sharing a database cannot both draw the same picture: only
     * one of them changes the row.
     */
    @Modifying
    @Transactional
    @Query(
        "UPDATE DesignImageJob j SET j.status = io.mszymanski.orknux.server.design.DesignImageJobStatus.DRAWING, " +
            "j.startedAt = :at WHERE j.id = :id AND j.status = io.mszymanski.orknux.server.design.DesignImageJobStatus.QUEUED",
    )
    fun claim(@Param("id") id: Long, @Param("at") at: OffsetDateTime): Int
}
