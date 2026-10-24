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

/** STOPPED is somebody's choice, neither made nor failed. */
enum class DesignVideoStatus { QUEUED, GENERATING, DONE, FAILED, STOPPED }

/**
 * A video asked for on Design's Videos page, from the moment it is asked for
 * to the file kept. Issue #658.
 *
 * One row rather than a request and a result, as a picture has, because a
 * video is never changed after it is made - there is no version of one to
 * keep beside it - and the request is what the page lists until the file
 * comes. What it was asked with is kept here for the page to show, and
 * [remoteId] is the backend's own job: a server that restarts while a video is
 * being made asks about that job again rather than asking for another.
 *
 * The settings are the ones the model said it takes, each null where it was
 * not asked; nothing here is a default this server chose.
 */
@Entity
@Table(name = "design_video")
class DesignVideo(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    /** Null once that model is removed; [modelName] still names it. */
    @Column(name = "model_id")
    val modelId: Long?,

    @Column(name = "model_name", nullable = false, length = 255)
    val modelName: String,

    @Column(nullable = false, columnDefinition = "text")
    val prompt: String,

    @Column(nullable = false, length = 255)
    var name: String,

    @Column
    val seconds: Int? = null,

    /** WIDTHxHEIGHT, for a backend that takes a size as one of its own words. */
    @Column(length = 40)
    val size: String? = null,

    @Column
    val frames: Int? = null,

    @Column
    val fps: Int? = null,

    @Column
    val width: Int? = null,

    @Column
    val height: Int? = null,

    @Column
    val seed: Long? = null,

    /** The picture the video starts from, by its artifact id; null for one made from the prompt alone. */
    @Column(name = "start_image_id", length = 80)
    val startImageId: String? = null,

    @Column(name = "requested_by", nullable = false, length = 255)
    val requestedBy: String,

    @Column(name = "requested_at", nullable = false)
    val requestedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "started_at")
    var startedAt: OffsetDateTime? = null,

    @Column(name = "finished_at")
    var finishedAt: OffsetDateTime? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: DesignVideoStatus = DesignVideoStatus.QUEUED,

    /** Why it was not made, in the backend's words where it gave any. */
    @Column(columnDefinition = "text")
    var reason: String? = null,

    /** The backend's job, prefixed with which backend it is; null until it was taken. */
    @Column(name = "remote_id", length = 255)
    var remoteId: String? = null,

    /** How far it has got, in percent, where the backend says. */
    @Column
    var progress: Int? = null,

    @Column(length = 255)
    var filename: String? = null,

    @Column(name = "content_type", length = 120)
    var contentType: String? = null,

    @Column(name = "size_bytes")
    var sizeBytes: Long? = null,

    /** Where the file is, as the attachment store that wrote it understands it. */
    @Column(length = 1000)
    var location: String? = null,

    /** A picture to show for it before it plays: the backend's, or the picture it started from. */
    @Column(name = "poster_location", length = 1000)
    var posterLocation: String? = null,

    @Column(name = "poster_content_type", length = 120)
    var posterContentType: String? = null,

    @Column(name = "duration_seconds")
    var durationSeconds: Double? = null,

    /**
     * The video this is a version of - the first one, as every version of a
     * picture points at the picture - or null for one made by a video model.
     */
    @Column(name = "version_of")
    val versionOf: Long? = null,

    /**
     * The video whose frames an upscale takes; null for anything else. It is
     * what marks a row as an upscale, which the image queue makes and the
     * video queue leaves alone.
     */
    @Column(name = "upscaled_from")
    val upscaledFrom: Long? = null,

    /** The WebUI's algorithm an upscale uses; null for the model's own, and for anything else. */
    @Column(length = 255)
    val upscaler: String? = null,

    /** How many times larger an upscale makes it. */
    @Column
    val scale: Double? = null,

    /** How many of an upscale's frames are done, of [framesTotal]. */
    @Column(name = "frames_done")
    var framesDone: Int? = null,

    @Column(name = "frames_total")
    var framesTotal: Int? = null,
)

interface DesignVideoRepository : JpaRepository<DesignVideo, Long> {

    /** A workspace's videos, newest first: the Videos page. */
    fun findByWorkspaceIdOrderByRequestedAtDescIdDesc(workspaceId: Long): List<DesignVideo>

    fun findByStatusOrderByRequestedAtAscIdAsc(status: DesignVideoStatus): List<DesignVideo>

    /** Videos a video model makes, in one state: what the video queue counts and takes. */
    fun findByStatusAndUpscaledFromIsNullOrderByRequestedAtAscIdAsc(status: DesignVideoStatus): List<DesignVideo>

    /** Upscales in one state: what the image queue's limit takes them from. */
    fun findByStatusAndUpscaledFromIsNotNullOrderByRequestedAtAscIdAsc(status: DesignVideoStatus): List<DesignVideo>

    /**
     * Takes one queued video, or reports that somebody else did - an update
     * conditioned on the status, so two servers sharing a database cannot
     * both start it.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(
        "UPDATE DesignVideo v SET v.status = io.mszymanski.orknux.server.design.DesignVideoStatus.GENERATING, " +
            "v.startedAt = :at, v.progress = 0 WHERE v.id = :id AND v.status = io.mszymanski.orknux.server.design.DesignVideoStatus.QUEUED",
    )
    fun claim(@Param("id") id: Long, @Param("at") at: OffsetDateTime): Int

    /** Stops one waiting or being made; 0 when it was neither any more. */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(
        "UPDATE DesignVideo v SET v.status = io.mszymanski.orknux.server.design.DesignVideoStatus.STOPPED, v.finishedAt = :at " +
            "WHERE v.id = :id AND v.status IN (io.mszymanski.orknux.server.design.DesignVideoStatus.QUEUED, " +
            "io.mszymanski.orknux.server.design.DesignVideoStatus.GENERATING)",
    )
    fun stop(@Param("id") id: Long, @Param("at") at: OffsetDateTime): Int

    /** Says why one was not made - unless it was stopped, which is no failure. */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(
        "UPDATE DesignVideo v SET v.status = io.mszymanski.orknux.server.design.DesignVideoStatus.FAILED, v.reason = :reason, " +
            "v.finishedAt = :at WHERE v.id = :id AND v.status IN (io.mszymanski.orknux.server.design.DesignVideoStatus.QUEUED, " +
            "io.mszymanski.orknux.server.design.DesignVideoStatus.GENERATING)",
    )
    fun failed(@Param("id") id: Long, @Param("reason") reason: String, @Param("at") at: OffsetDateTime): Int

    /** The backend's job and how far it has got, for one still being made. */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(
        "UPDATE DesignVideo v SET v.remoteId = :remoteId, v.progress = :progress " +
            "WHERE v.id = :id AND v.status = io.mszymanski.orknux.server.design.DesignVideoStatus.GENERATING",
    )
    fun progressed(@Param("id") id: Long, @Param("remoteId") remoteId: String, @Param("progress") progress: Int?): Int

    /** How many of an upscale's frames are done, for one still being made. */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(
        "UPDATE DesignVideo v SET v.framesDone = :done, v.framesTotal = :total, v.progress = :progress " +
            "WHERE v.id = :id AND v.status = io.mszymanski.orknux.server.design.DesignVideoStatus.GENERATING",
    )
    fun framed(@Param("id") id: Long, @Param("done") done: Int, @Param("total") total: Int, @Param("progress") progress: Int): Int

    /** Files the video made for one still being made; 0 when it was stopped meanwhile, and the file is then not kept. */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(
        "UPDATE DesignVideo v SET v.status = io.mszymanski.orknux.server.design.DesignVideoStatus.DONE, v.progress = 100, " +
            "v.filename = :filename, v.contentType = :contentType, v.sizeBytes = :sizeBytes, v.location = :location, " +
            "v.posterLocation = :posterLocation, v.posterContentType = :posterContentType, v.durationSeconds = :duration, " +
            "v.finishedAt = :at WHERE v.id = :id AND v.status = io.mszymanski.orknux.server.design.DesignVideoStatus.GENERATING",
    )
    fun made(
        @Param("id") id: Long,
        @Param("filename") filename: String,
        @Param("contentType") contentType: String,
        @Param("sizeBytes") sizeBytes: Long,
        @Param("location") location: String,
        @Param("posterLocation") posterLocation: String?,
        @Param("posterContentType") posterContentType: String?,
        @Param("duration") duration: Double?,
        @Param("at") at: OffsetDateTime,
    ): Int
}
