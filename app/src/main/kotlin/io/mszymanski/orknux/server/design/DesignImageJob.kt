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

/** STOPPED is somebody's choice, neither drawn nor failed: a stopped picture is never started, or its drawing is thrown away. */
enum class DesignImageJobStatus { QUEUED, DRAWING, DRAWN, FAILED, STOPPED }

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

    /**
     * The picture this changes, by its artifact id, for an edit; null for a
     * picture drawn from nothing. Any picture in the gallery can be the source.
     */
    @Column(name = "source_id", length = 80)
    val sourceId: String? = null,

    /** Where the mask is kept, for an edit: transparent where the change goes. */
    @Column(name = "mask_location", length = 1000)
    val maskLocation: String? = null,

    /** The algorithm an upscale makes [sourceId] larger with; null is the model's own. */
    @Column(length = 255)
    val upscaler: String? = null,

    /** How many times larger an upscale makes it; null for anything but an upscale. */
    @Column
    val scale: Double? = null,

    /**
     * How strongly a Harmonize through a Stable Diffusion WebUI redraws the
     * whole of [sourceId]; null for anything else. A Harmonize by an image
     * model is an edit whose mask covers everything, and keeps none.
     */
    @Column
    val strength: Double? = null,

    /** The WebUI checkpoint a Harmonize redraws with; null is the one it has loaded. */
    @Column(length = 255)
    val checkpoint: String? = null,

    /**
     * What the picture must not contain, as it was asked; null is none given,
     * and the model's own default goes in its place. Issue #657.
     */
    @Column(name = "negative_prompt", columnDefinition = "text")
    val negativePrompt: String? = null,

    /**
     * How far the edge of an edit's selection is softened, in the picture's own
     * pixels, when the change is laid over the picture; 0 or null is a hard
     * edge, and null on anything but an edit. Issue #638.
     */
    @Column
    val feather: Int? = null,
)

interface DesignImageJobRepository : JpaRepository<DesignImageJob, Long> {

    /** What a workspace has waiting, drawing or failed - the tiles at the front of its gallery. */
    fun findByWorkspaceIdAndStatusInOrderByRequestedAtAscIdAsc(
        workspaceId: Long,
        statuses: Collection<DesignImageJobStatus>,
    ): List<DesignImageJob>

    fun findByStatusOrderByRequestedAtAscIdAsc(status: DesignImageJobStatus): List<DesignImageJob>

    /** What was asked of any of these pictures, newest first: the history the image editor shows. */
    fun findByWorkspaceIdAndSourceIdInOrderByRequestedAtDescIdDesc(workspaceId: Long, sourceIds: Collection<String>): List<DesignImageJob>

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

    /**
     * Stops a picture waiting or being drawn. Conditioned on the status as
     * [claim] is, so a stop and a claim, or a stop and a drawing that has just
     * finished, cannot both win: whichever changes the row first does.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(
        "UPDATE DesignImageJob j SET j.status = io.mszymanski.orknux.server.design.DesignImageJobStatus.STOPPED " +
            "WHERE j.id = :id AND j.status IN (io.mszymanski.orknux.server.design.DesignImageJobStatus.QUEUED, io.mszymanski.orknux.server.design.DesignImageJobStatus.DRAWING)",
    )
    fun stop(@Param("id") id: Long): Int

    /** Files the picture drawn for one still being drawn; 0 when it was stopped meanwhile. */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(
        "UPDATE DesignImageJob j SET j.status = io.mszymanski.orknux.server.design.DesignImageJobStatus.DRAWN, j.imageId = :imageId " +
            "WHERE j.id = :id AND j.status = io.mszymanski.orknux.server.design.DesignImageJobStatus.DRAWING",
    )
    fun drawn(@Param("id") id: Long, @Param("imageId") imageId: Long): Int

    /** Says why one was not drawn - unless it was stopped, which is no failure. */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(
        "UPDATE DesignImageJob j SET j.status = io.mszymanski.orknux.server.design.DesignImageJobStatus.FAILED, j.reason = :reason " +
            "WHERE j.id = :id AND j.status IN (io.mszymanski.orknux.server.design.DesignImageJobStatus.QUEUED, io.mszymanski.orknux.server.design.DesignImageJobStatus.DRAWING)",
    )
    fun failed(@Param("id") id: Long, @Param("reason") reason: String): Int
}
