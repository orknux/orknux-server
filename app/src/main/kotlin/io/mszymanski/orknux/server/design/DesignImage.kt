package io.mszymanski.orknux.server.design

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

/**
 * A picture somebody drew on Design's Images page.
 *
 * A table of its own beside the runs' and the tasks' pictures because what it
 * holds is what they do not: what the model was asked for beyond the prompt,
 * which model, and who asked - the parameters the page shows next to the
 * picture. The model is kept by name as well as by id, so a picture still says
 * what drew it after that model is removed.
 */
@Entity
@Table(name = "design_image")
class DesignImage(
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

    /** As asked, WIDTHxHEIGHT; null where the model drew at its own default. */
    @Column(length = 40)
    val size: String?,

    @Column(length = 40)
    val quality: String?,

    @Column(length = 40)
    val style: String?,

    @Column(nullable = false, length = 255)
    var filename: String,

    @Column(name = "content_type", nullable = false, length = 120)
    var contentType: String,

    @Column(name = "size_bytes", nullable = false)
    var sizeBytes: Long,

    /** Where the bytes are, as the attachment store that wrote them understands it. */
    @Column(nullable = false, length = 1000)
    var location: String,

    @Column(name = "drawn_by", nullable = false, length = 255)
    val drawnBy: String,

    @Column(name = "drawn_at", nullable = false)
    val drawnAt: OffsetDateTime = OffsetDateTime.now(),

    /** What it is called; null for one drawn before pictures had names, which reads its prompt instead. */
    @Column(length = 255)
    var name: String? = null,

    /**
     * The picture this is a version of, by its artifact id, while it is one:
     * a change made in the image editor and not saved yet. Such a row is on no
     * list. Saved, it becomes that picture; thrown away, it is deleted. Every
     * round of changes is a version of the same picture, not of the round
     * before, so one Save or one Discard settles them all.
     */
    @Column(name = "version_of", length = 80)
    var versionOf: String? = null,
)

interface DesignImageRepository : JpaRepository<DesignImage, Long> {

    /** Unpaged: the Artifacts list merges this with the other tables and pages the whole. Versions are on no list. */
    fun findByWorkspaceIdAndVersionOfIsNull(workspaceId: Long): List<DesignImage>

    /** Every unsaved version of one picture. */
    fun findByWorkspaceIdAndVersionOf(workspaceId: Long, versionOf: String): List<DesignImage>
}
