package io.mszymanski.orknux.server.diagram

import io.mszymanski.orknux.server.attachment.AttachmentDownloads
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.core.io.InputStreamResource
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.time.OffsetDateTime

/**
 * A picture a person put on a diagram - a logo, a screenshot, a photo of a
 * whiteboard - in the installation's attachment store. A box of kind
 * [DiagramNodeKind.IMAGE] names it by id. Its own rows rather than a chat's
 * attachments, which are private to whoever uploaded them: a picture on a
 * diagram is seen by everybody who sees the diagram.
 */
@Entity
@Table(name = "diagram_image")
class DiagramImage(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "diagram_id", nullable = false)
    val diagramId: Long,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Column(nullable = false, length = 255)
    val filename: String,

    @Column(name = "content_type", nullable = false, length = 100)
    val contentType: String,

    @Column(name = "size_bytes", nullable = false)
    val sizeBytes: Long,

    @Column(nullable = false, length = 1000)
    val location: String,

    @Column(name = "uploaded_by", nullable = false, length = 255)
    val uploadedBy: String,

    @Column(name = "uploaded_at", nullable = false)
    val uploadedAt: OffsetDateTime = OffsetDateTime.now(),
)

interface DiagramImageRepository : JpaRepository<DiagramImage, Long>

@org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NOT_FOUND)
class DiagramImageNotFoundException(val id: Long) : RuntimeException("No diagram picture with id $id")

@org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
class DiagramImageNotAPictureException(val name: String) :
    RuntimeException("$name is not a picture a diagram can show: PNG, JPEG, GIF or WebP")

/**
 * Putting a picture on a diagram, and showing it. Uploading needs the diagram
 * to be one the person sees, and is held to the installation's upload limit;
 * reading needs the same, so a picture is exactly as private as its diagram.
 */
@RestController
class DiagramImageAPI(
    private val images: DiagramImageRepository,
    private val api: DiagramAPI,
    private val store: AttachmentStore,
    private val settings: InstallationSettings,
    private val downloads: AttachmentDownloads,
    private val audit: WorkspaceAuditRecorder,
) {

    /** One picture, answered with its id and its size in pixels where it can be read, for the box it goes in. */
    @PostMapping("/api/diagrams/{diagramId}/images")
    @Transactional
    fun upload(@PathVariable diagramId: Long, @RequestParam("file") file: MultipartFile): ResponseEntity<Any> {
        val diagram = api.find(diagramId)
        val name = file.originalFilename?.trim()?.ifEmpty { null } ?: "picture"
        val lower = name.lowercase()
        val byName = PICTURES.entries.firstOrNull { lower.endsWith(it.value) || (it.value == ".jpg" && lower.endsWith(".jpeg")) }?.key
        val type = file.contentType?.lowercase()?.takeIf { it in PICTURES } ?: byName ?: throw DiagramImageNotAPictureException(name)
        settings.requireUploadable(name, file.size)
        val bytes = file.bytes
        val location = store.putFrom(diagram.workspaceId, name, bytes.inputStream())
        val kept = images.save(
            DiagramImage(
                diagramId = diagramId,
                workspaceId = diagram.workspaceId,
                filename = name.takeLast(255),
                contentType = type,
                sizeBytes = file.size,
                location = location,
                uploadedBy = SecurityContextHolder.getContext().authentication?.name ?: "system",
            ),
        )
        audit.record(diagram.workspaceId, WorkspaceAuditCategory.WORKSPACE, "Picture $name added to diagram ${diagram.name}")
        val size = runCatching { javax.imageio.ImageIO.read(bytes.inputStream()) }.getOrNull()
        return ResponseEntity.ok(
            mapOf("id" to kept.id.toString(), "filename" to kept.filename, "width" to size?.width, "height" to size?.height),
        )
    }

    @GetMapping("/api/diagram-images/{id}")
    fun show(@PathVariable id: Long): ResponseEntity<InputStreamResource> {
        val held = images.findByIdOrNull(id) ?: throw DiagramImageNotFoundException(id)
        api.find(held.diagramId)
        if (!store.exists(held.location)) throw DiagramImageNotFoundException(id)
        return downloads.serve(filename = held.filename, contentType = held.contentType, sizeBytes = held.sizeBytes, location = held.location)
    }

    private companion object {
        /** What a diagram shows: pictures a browser and the PNG renderer both draw. Not SVG, which can carry a script. */
        val PICTURES = mapOf("image/png" to ".png", "image/jpeg" to ".jpg", "image/gif" to ".gif", "image/webp" to ".webp")
    }
}
