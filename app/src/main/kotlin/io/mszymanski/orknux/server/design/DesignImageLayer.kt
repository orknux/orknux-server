package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.server.attachment.AttachmentStore
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.slf4j.LoggerFactory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service

/**
 * One layer of a picture in the image editor: its pixels, its name, where it is
 * in the stack and whether it is shown.
 *
 * Kept with the picture - or with one of its unsaved versions - by that one's
 * artifact id, so a run's or a task's picture keeps its layers as a Design one
 * does. The picture's own file is always the layers flattened, which is what
 * the gallery, a chat, an agent and a workflow read; these rows are only what
 * the editor opens again, so nothing else has to know layers exist.
 *
 * The row marked [picture] is the picture itself under the layers - its pixels
 * without them - at the foot of the stack. Without it the editor would open
 * the flattened file as the picture and lay the layers over it a second time.
 *
 * A row is never changed in place but for which picture it belongs to: a
 * change in the editor is a new version, and a new version has rows of its own.
 */
@Entity
@Table(name = "design_image_layer")
class DesignImageLayer(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    /** The picture, or the version of one, the layer belongs to, by its artifact id. */
    @Column(name = "image_id", nullable = false, length = 80)
    var imageId: String,

    /** Its place in the stack, counted from the foot: the picture itself is 0. */
    @Column(name = "layer_order", nullable = false)
    val place: Int,

    /** The picture itself, under every layer; never renamed, moved or taken away. */
    @Column(nullable = false)
    val picture: Boolean,

    /** What it is called; null for the picture itself, which the editor names. */
    @Column(length = 255)
    val name: String?,

    @Column(nullable = false)
    val visible: Boolean,

    @Column(name = "size_bytes", nullable = false)
    val sizeBytes: Long,

    /** Where its PNG is, as the attachment store that wrote it understands it. */
    @Column(nullable = false, length = 1000)
    val location: String,
)

interface DesignImageLayerRepository : JpaRepository<DesignImageLayer, Long> {

    fun findByWorkspaceIdAndImageIdOrderByPlaceAsc(workspaceId: Long, imageId: String): List<DesignImageLayer>
}

/** A layer as it arrives or leaves: everything but where its bytes are kept. */
data class LayerPixels(val name: String?, val visible: Boolean, val picture: Boolean, val png: ByteArray) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * The one place layers are kept, copied, moved and thrown away, so a version,
 * a save, a discard, a delete and the draw queue cannot each come to a rule of
 * their own about the files behind them. Through the [AttachmentStore], as the
 * pictures themselves are: a second store would be a second place to back up.
 */
@Service
class DesignImageLayers(
    private val rows: DesignImageLayerRepository,
    private val store: AttachmentStore,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** A picture's layers, the picture itself first; empty for one that has none. */
    fun of(workspaceId: Long, imageId: String): List<DesignImageLayer> =
        rows.findByWorkspaceIdAndImageIdOrderByPlaceAsc(workspaceId, imageId)

    /** One layer, by its own id: what its address serves. */
    fun byId(id: Long): DesignImageLayer? = rows.findById(id).orElse(null)

    /** The layers' pixels, read back, in the order they stack. */
    fun pixelsOf(workspaceId: Long, imageId: String): List<LayerPixels> = of(workspaceId, imageId).map {
        LayerPixels(it.name, it.visible, it.picture, store.open(it.location).use { stream -> stream.readBytes() })
    }

    /** Keeps [layers] as [imageId]'s, the first lowest, in place of any it had. */
    fun keep(workspaceId: Long, imageId: String, layers: List<LayerPixels>) {
        forget(workspaceId, imageId)
        layers.forEachIndexed { at, layer ->
            rows.save(
                DesignImageLayer(
                    workspaceId = workspaceId,
                    imageId = imageId,
                    place = at,
                    picture = layer.picture,
                    name = if (layer.picture) null else layer.name,
                    visible = layer.visible,
                    sizeBytes = layer.png.size.toLong(),
                    location = store.put(workspaceId, "layer.png", layer.png),
                ),
            )
        }
    }

    /**
     * Another picture's layers given to [imageId] - the version the draw queue
     * files, which is the picture it was drawn from with a change on top - each
     * made [width] by [height] where it was another size, as an upscale is.
     */
    fun copied(workspaceId: Long, from: String, width: Int, height: Int): List<LayerPixels> =
        pixelsOf(workspaceId, from).map { layer ->
            val image = PictureScaling.read(layer.png) ?: return@map layer
            if (image.width == width && image.height == height) layer else layer.copy(png = PictureScaling.png(PictureScaling.fitted(image, width, height)))
        }

    /**
     * [from]'s layers made [to]'s, and any [to] had thrown away: a version saved
     * over its picture takes its layers with it.
     */
    fun move(workspaceId: Long, from: String, to: String) {
        forget(workspaceId, to)
        rows.findByWorkspaceIdAndImageIdOrderByPlaceAsc(workspaceId, from).forEach {
            it.imageId = to
            rows.save(it)
        }
    }

    /** A picture's layers gone, files and all: the picture flattened, discarded or deleted. */
    fun forget(workspaceId: Long, imageId: String) {
        val gone = rows.findByWorkspaceIdAndImageIdOrderByPlaceAsc(workspaceId, imageId)
        if (gone.isEmpty()) return
        rows.deleteAll(gone)
        gone.forEach { layer ->
            runCatching { store.remove(layer.location) }
                .onFailure { log.warn("Layer {} of {} deleted but its file at {} was not", layer.id, imageId, layer.location, it) }
        }
    }
}
