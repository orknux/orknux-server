package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.connector.model.ImageOptions
import io.mszymanski.orknux.connector.model.ModelImageClient
import io.mszymanski.orknux.connector.model.ModelKind
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.connector.model.Picture
import io.mszymanski.orknux.server.attachment.AttachmentDownloads
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.attachment.PictureFilenames
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workflow.ArtifactView
import io.mszymanski.orknux.server.workflow.WorkspaceArtifactAPI
import io.mszymanski.orknux.server.workspace.ModelNotFoundForWorkspaceException
import io.mszymanski.orknux.server.workspace.ModelNotImageException
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.core.io.InputStreamResource
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.ResponseBody

/**
 * Drawing a picture on Design's Images page, and serving what was drawn.
 *
 * Straight to the image model somebody picked, not through an agent: the page
 * is the interface - the prompt, the model and its parameters - and an agent in
 * between would decide some of that for them.
 */
@Controller
class DesignImageAPI(
    private val images: DesignImageRepository,
    private val models: ModelService,
    private val drawing: ModelImageClient,
    private val store: AttachmentStore,
    private val settings: InstallationSettings,
    private val downloads: AttachmentDownloads,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val artifacts: WorkspaceArtifactAPI,
) {

    @MutationMapping
    fun drawDesignImage(@Argument input: DrawDesignImageInput): ArtifactView {
        val workspaceId = input.workspaceId.toLong()
        access.requireVisible(workspaceId)
        val modelId = input.modelId.toLong()
        val model = models.model(modelId)?.takeIf { it.workspaceId == workspaceId }
            ?: throw ModelNotFoundForWorkspaceException(modelId)
        if (model.kind != ModelKind.IMAGE) throw ModelNotImageException(model.name)

        val prompt = input.prompt.trim()
        if (prompt.isEmpty()) throw DesignImageRefusedException("Say what the picture should be of.")
        val longest = settings.imageMaxPrompt()
        if (prompt.length > longest) {
            throw DesignImageRefusedException("That description is too long to draw from; say it in under $longest characters.")
        }
        // A drawn picture is kept where attachments are, as a task's is.
        if (!settings.attachmentsEnabled()) {
            throw DesignImageRefusedException("A drawn picture is kept as an attachment, and attachments are turned off for this installation.")
        }

        val options = ImageOptions(input.size.orNullIfBlank(), input.quality.orNullIfBlank(), input.style.orNullIfBlank())
        val drawn = when (val picture = drawing.draw(modelId, prompt, options)) {
            is Picture.Failed -> throw DesignImageRefusedException(picture.reason)
            is Picture.Drawn -> picture
        }

        val filename = PictureFilenames.of(prompt, drawn.contentType)
        val saved = images.save(
            DesignImage(
                workspaceId = workspaceId,
                modelId = modelId,
                modelName = model.name,
                prompt = prompt,
                size = options.size,
                quality = options.quality,
                style = options.style,
                filename = filename,
                contentType = drawn.contentType,
                sizeBytes = drawn.image.size.toLong(),
                location = store.put(workspaceId, filename, drawn.image),
                drawnBy = currentName(),
            ),
        )
        auditRecorder.record(workspaceId, WorkspaceAuditCategory.WORKFLOW, "Image $filename drawn in Design with ${model.name}")
        return artifacts.describe(saved)
    }

    /** To anybody who can see the workspace, answered as missing otherwise - as a task's picture is. */
    @GetMapping("/api/design-images/{id}")
    @ResponseBody
    fun download(@PathVariable id: Long): ResponseEntity<InputStreamResource> {
        val image = images.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) && store.exists(it.location) }
            ?: return ResponseEntity.notFound().build()
        return downloads.serve(
            filename = image.filename,
            contentType = image.contentType,
            sizeBytes = image.sizeBytes,
            location = image.location,
        )
    }

    private fun currentName(): String {
        val authentication = SecurityContextHolder.getContext().authentication
        return authentication?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }?.name ?: "somebody"
    }

    private fun String?.orNullIfBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}

data class DrawDesignImageInput(
    val workspaceId: String,
    val modelId: String,
    val prompt: String,
    val size: String? = null,
    val quality: String? = null,
    val style: String? = null,
)

/** Why nothing was drawn, in the words the page shows. */
class DesignImageRefusedException(reason: String) : RuntimeException(reason)
