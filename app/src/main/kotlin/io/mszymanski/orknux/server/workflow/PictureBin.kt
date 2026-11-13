package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.design.DesignCatalogKind
import io.mszymanski.orknux.server.design.DesignCatalogs
import io.mszymanski.orknux.server.design.DesignImage
import io.mszymanski.orknux.server.design.DesignImageLayers
import io.mszymanski.orknux.server.design.DesignImageRepository
import io.mszymanski.orknux.server.design.LayerPixels
import io.mszymanski.orknux.server.recyclebin.RecycleBin
import io.mszymanski.orknux.server.security.ActingIn
import io.mszymanski.orknux.server.task.TaskPicture
import io.mszymanski.orknux.server.task.TaskPictureRepository
import io.mszymanski.orknux.server.task.TaskRepository
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.util.Base64

/**
 * A deleted picture, written down whole for the recycle bin and put back from it.
 *
 * The three tables the Images page lists - a picture drawn there, one a run
 * drew and one a task drew - are binned alike, because to whoever deletes one
 * they are all a picture on that page. What sets a picture apart from every
 * other binned kind is that most of it is not in the database: the delete
 * removes its file, so the snapshot carries the bytes, and its layers' bytes,
 * as base64. That makes an entry megabytes rather than kilobytes, which is why
 * the bin lists itself without reading a snapshot.
 *
 * A saved artifact - a file an agent kept, a page a wireframe is hosted as - is
 * not a picture and is not binned.
 */
@Service
class PictureBin(
    private val designImages: DesignImageRepository,
    private val pictures: ExecutionPictureRepository,
    private val taskPictures: TaskPictureRepository,
    private val tasks: TaskRepository,
    private val store: AttachmentStore,
    private val layers: DesignImageLayers,
    @param:Lazy private val catalogs: DesignCatalogs,
    private val recycleBin: RecycleBin,
    private val mapper: ObjectMapper,
    private val entities: EntityManager,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun bin(image: DesignImage) = write(
        ArtifactKind.DESIGN, image.id, image.workspaceId, image.catalogId, image.name ?: image.filename, image.location,
        mapOf(
            "modelId" to image.modelId,
            "modelName" to image.modelName,
            "prompt" to image.prompt,
            "size" to image.size,
            "quality" to image.quality,
            "style" to image.style,
            "filename" to image.filename,
            "contentType" to image.contentType,
            "drawnBy" to image.drawnBy,
            "drawnAt" to image.drawnAt.toString(),
            "pictureName" to image.name,
        ),
    )

    fun bin(picture: ExecutionPicture) = write(
        ArtifactKind.IMAGE, picture.id, picture.workspaceId, picture.catalogId, picture.filename, picture.location,
        mapOf(
            "executionId" to picture.executionId,
            "nodeKey" to picture.nodeKey,
            "prompt" to picture.prompt,
            "filename" to picture.filename,
            "contentType" to picture.contentType,
            "drawnAt" to picture.drawnAt.toString(),
        ),
    )

    fun bin(picture: TaskPicture) = write(
        ArtifactKind.TASK, picture.id, picture.workspaceId, picture.catalogId, picture.filename, picture.location,
        mapOf(
            "taskId" to picture.taskId,
            "prompt" to picture.prompt,
            "filename" to picture.filename,
            "contentType" to picture.contentType,
            "drawnAt" to picture.drawnAt.toString(),
        ),
    )

    private fun write(
        kind: ArtifactKind,
        rowId: Long?,
        workspaceId: Long,
        catalogId: Long?,
        name: String,
        location: String,
        fields: Map<String, Any?>,
    ) {
        val id = rowId ?: return
        // A picture whose file is already gone has nothing to be put back from: it is deleted as it always was.
        val bytes = runCatching { store.open(location).use { it.readBytes() } }
            .onFailure { log.warn("Picture {}-{} is deleted without a recycle bin entry: its file at {} cannot be read", kind, id, location, it) }
            .getOrNull() ?: return
        val encoder = Base64.getEncoder()
        val drawn = runCatching { layers.pixelsOf(workspaceId, "${kind.name}-$id") }.getOrDefault(emptyList())
        val snapshot = fields + mapOf(
            "name" to name,
            "source" to kind.name,
            "bytes" to encoder.encodeToString(bytes),
            "layers" to drawn.map {
                mapOf("name" to it.name, "visible" to it.visible, "picture" to it.picture, "png" to encoder.encodeToString(it.png))
            },
        )
        recycleBin.binPicture(workspaceId, id, catalogId, name, mapper.writeValueAsString(snapshot))
    }

    /**
     * Puts a picture back: its file written again, a fresh row beside the ones
     * it was deleted from, and its layers. A run's or a task's picture goes back
     * to that run or task while it still exists; where it does not, the picture
     * comes back as one of the Images page's own, since a row cannot name a
     * parent that is gone. Answers the new artifact id.
     */
    @Transactional
    fun recreate(workspaceId: Long, catalogId: Long?, snapshot: String): String {
        val tree = mapper.readTree(snapshot)
        val decoder = Base64.getDecoder()
        val bytes = decoder.decode(tree.path("bytes").asString())
        val filename = tree.text("filename") ?: tree.path("name").asString()
        val contentType = tree.text("contentType") ?: "image/png"
        val prompt = tree.text("prompt").orEmpty()
        val drawnAt = tree.text("drawnAt")?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() } ?: OffsetDateTime.now()
        // Null is the default catalog and stays null; one that has gone since becomes the default.
        val catalog = catalogId?.let { catalogs.resolveOrDefault(workspaceId, DesignCatalogKind.IMAGE, it) }
        val location = store.put(workspaceId, filename, bytes)

        val executionId = tree.long("executionId")
        val taskId = tree.long("taskId")
        val artifactId = when {
            tree.text("source") == ArtifactKind.IMAGE.name && executionId != null && runExists(executionId) -> {
                val made = pictures.save(
                    ExecutionPicture(
                        executionId = executionId,
                        nodeKey = tree.text("nodeKey").orEmpty(),
                        workspaceId = workspaceId,
                        prompt = prompt,
                        filename = filename,
                        contentType = contentType,
                        sizeBytes = bytes.size.toLong(),
                        location = location,
                        drawnAt = drawnAt,
                        catalogId = catalog,
                    ),
                )
                "${ArtifactKind.IMAGE.name}-${made.id}"
            }

            tree.text("source") == ArtifactKind.TASK.name && taskId != null && tasks.existsById(taskId) -> {
                val made = taskPictures.save(
                    TaskPicture(
                        taskId = taskId,
                        workspaceId = workspaceId,
                        prompt = prompt,
                        filename = filename,
                        contentType = contentType,
                        sizeBytes = bytes.size.toLong(),
                        location = location,
                        drawnAt = drawnAt,
                        catalogId = catalog,
                    ),
                )
                "${ArtifactKind.TASK.name}-${made.id}"
            }

            else -> {
                val made = designImages.save(
                    DesignImage(
                        workspaceId = workspaceId,
                        modelId = tree.long("modelId"),
                        modelName = tree.text("modelName").orEmpty(),
                        prompt = prompt,
                        size = tree.text("size"),
                        quality = tree.text("quality"),
                        style = tree.text("style"),
                        filename = filename,
                        contentType = contentType,
                        sizeBytes = bytes.size.toLong(),
                        location = location,
                        drawnBy = tree.text("drawnBy") ?: who(),
                        drawnAt = drawnAt,
                        name = tree.text("pictureName"),
                        catalogId = catalog,
                    ),
                )
                "${ArtifactKind.DESIGN.name}-${made.id}"
            }
        }

        val drawn = tree.path("layers").values().map {
            LayerPixels(it.text("name"), it.path("visible").asBoolean(true), it.path("picture").asBoolean(false), decoder.decode(it.path("png").asString()))
        }
        if (drawn.isNotEmpty()) layers.keep(workspaceId, artifactId, drawn)
        return artifactId
    }

    /** Asked of the table rather than of the execution module, which has no reason to answer this for anybody else. */
    private fun runExists(executionId: Long): Boolean =
        (entities.createNativeQuery("select count(*) from workflow_execution where id = :id")
            .setParameter("id", executionId).singleResult as Number).toLong() > 0

    private fun JsonNode.text(field: String): String? = path(field).takeIf { it.isString }?.asString()

    private fun JsonNode.long(field: String): Long? = path(field).takeIf { it.isNumber }?.asLong()

    private fun who(): String =
        SecurityContextHolder.getContext().authentication?.name ?: ActingIn.actor() ?: "orknux"
}
