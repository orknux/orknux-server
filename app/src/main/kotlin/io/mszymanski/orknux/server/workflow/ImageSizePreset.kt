package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.graphql.Refusal
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/** The bounds a preset's sides are held to, and what every workspace starts with. */
object ImageSizePresets {
    /** As long as an issue status label is: the two are read in the same kind of list. */
    const val NAME_LENGTH = 60

    /** No endpoint anybody points this at draws wider; the free-dimension spec stops at 4096. */
    const val MAX_SIDE = 8192

    /**
     * The seven sizes the two OpenAI families draw between them, largest and
     * most common first, in the words a person reaches for rather than the
     * endpoint's - "Landscape 1536" reads, "1536x1024" is what it fills in.
     */
    fun defaults(workspaceId: Long): List<ImageSizePreset> = listOf(
        ImageSizePreset(workspaceId = workspaceId, name = "Square 1024", width = 1024, height = 1024, position = 0),
        ImageSizePreset(workspaceId = workspaceId, name = "Landscape 1536", width = 1536, height = 1024, position = 1),
        ImageSizePreset(workspaceId = workspaceId, name = "Portrait 1536", width = 1024, height = 1536, position = 2),
        ImageSizePreset(workspaceId = workspaceId, name = "Wide 1792", width = 1792, height = 1024, position = 3),
        ImageSizePreset(workspaceId = workspaceId, name = "Tall 1792", width = 1024, height = 1792, position = 4),
        ImageSizePreset(workspaceId = workspaceId, name = "Square 512", width = 512, height = 512, position = 5),
        ImageSizePreset(workspaceId = workspaceId, name = "Square 256", width = 256, height = 256, position = 6),
    )
}

/**
 * A named width and height a workspace's image nodes can pick in one click.
 *
 * Per workspace, editable, and ordered, because a size is a house decision: the
 * team drawing thumbnails for a catalogue wants its 600x400 at the top, and the
 * one drawing posters wants nothing under 2048. A preset fills the two boxes on
 * a node whose model takes free dimensions; the node stores the numbers, not the
 * preset, so a preset renamed or removed changes no saved node. Issue #431.
 */
@Entity
@Table(name = "workspace_image_size_preset")
class ImageSizePreset(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Column(nullable = false, length = ImageSizePresets.NAME_LENGTH)
    var name: String,

    @Column(nullable = false)
    var width: Int,

    @Column(nullable = false)
    var height: Int,

    /** Where it sits in the menu. */
    @Column(nullable = false)
    var position: Int = 0,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
)

interface ImageSizePresetRepository : JpaRepository<ImageSizePreset, Long> {

    /** A workspace's presets in menu order; the id breaks a tie. */
    fun findByWorkspaceIdOrderByPositionAscIdAsc(workspaceId: Long): List<ImageSizePreset>
}

/**
 * Where a workspace's presets come from, and the seeding that keeps every
 * workspace holding some.
 *
 * The migration seeds every workspace that existed and `createWorkspace` seeds
 * each one made since, so in the product a workspace always has rows. A
 * workspace written straight into the table has none, and the tests around this
 * make workspaces that way; reading through [ensure] writes the seven down for
 * it, so the page that manages them has rows with ids to manage - the same
 * arrangement the issue statuses made in #428.
 */
@Service
class ImageSizePresetCatalogue(private val presets: ImageSizePresetRepository) {

    /** The workspace's presets in order, written down first if they never were. */
    @Transactional
    fun ensure(workspaceId: Long): List<ImageSizePreset> {
        val held = presets.findByWorkspaceIdOrderByPositionAscIdAsc(workspaceId)
        if (held.isNotEmpty()) return held
        return presets.saveAll(ImageSizePresets.defaults(workspaceId))
    }
}

class ImageSizePresetNotFoundException(val id: Long) : RuntimeException("No image size preset with id $id"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

class ImageSizePresetNameInvalidException :
    RuntimeException("An image size preset needs a name, ${ImageSizePresets.NAME_LENGTH} characters at most")

class ImageSizePresetSideInvalidException(val side: String, val value: Int) : RuntimeException(
    "A preset's $side is a whole number of pixels from 1 to ${ImageSizePresets.MAX_SIDE}; $value is not",
), Refusal {

    override val arguments get() = mapOf("side" to side, "value" to value)
}

class ImageSizePresetReorderException :
    RuntimeException("A new order names every one of this workspace's image size presets, once each")
