package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.connector.model.ModelKind
import io.mszymanski.orknux.connector.model.ModelService
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
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.ResponseBody

/**
 * Asking for a picture on Design's Images page, the queue it waits in, and
 * serving what was drawn.
 *
 * Asking queues and answers at once; [DesignDrawQueue] draws. A drawing can take
 * minutes, and whoever asked may leave, ask for more, and find each picture in
 * the gallery when it comes - with what it is waiting for shown until then.
 *
 * Straight to the image model somebody picked, not through an agent: the page
 * is the interface - the prompt, the model and its parameters - and an agent in
 * between would decide some of that for them.
 */
@Controller
class DesignImageAPI(
    private val images: DesignImageRepository,
    private val jobs: DesignImageJobRepository,
    private val queue: DesignDrawQueue,
    private val models: ModelService,
    private val store: AttachmentStore,
    private val settings: InstallationSettings,
    private val downloads: AttachmentDownloads,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val artifacts: WorkspaceArtifactAPI,
) {

    /** Refused here, before it waits, for anything that could never be drawn. */
    @MutationMapping
    @Transactional
    fun queueDesignImage(@Argument input: DrawDesignImageInput): DesignImageJobView {
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
        val called = input.name.orNullIfBlank()
        if (called != null && called.length > NAME_LENGTH) {
            throw DesignImageRefusedException("A picture's name is at most $NAME_LENGTH characters.")
        }

        val job = jobs.save(
            DesignImageJob(
                workspaceId = workspaceId,
                modelId = modelId,
                modelName = model.name,
                prompt = prompt,
                size = input.size.orNullIfBlank(),
                quality = input.quality.orNullIfBlank(),
                style = input.style.orNullIfBlank(),
                requestedBy = currentName(),
                name = called,
            ),
        )
        auditRecorder.record(workspaceId, WorkspaceAuditCategory.WORKFLOW, "Image queued in Design with ${model.name}")
        queue.queued(job)
        return view(job)
    }

    /**
     * Queues a change to part of a picture: the picture, a mask the same size
     * that is transparent where the change goes, and what to change. Any
     * picture in the gallery can be changed; the result is a new picture, and
     * the one changed stays as it was.
     */
    @MutationMapping
    @Transactional
    fun queueDesignImageEdit(@Argument input: EditDesignImageInput): DesignImageJobView {
        val workspaceId = input.workspaceId.toLong()
        access.requireVisible(workspaceId)
        val modelId = input.modelId.toLong()
        val model = models.model(modelId)?.takeIf { it.workspaceId == workspaceId }
            ?: throw ModelNotFoundForWorkspaceException(modelId)
        if (model.kind != ModelKind.IMAGE) throw ModelNotImageException(model.name)
        val prompt = input.prompt.trim()
        if (prompt.isEmpty()) throw DesignImageRefusedException("Say what to change.")
        val longest = settings.imageMaxPrompt()
        if (prompt.length > longest) {
            throw DesignImageRefusedException("That description is too long to draw from; say it in under $longest characters.")
        }
        if (!settings.attachmentsEnabled()) {
            throw DesignImageRefusedException("A drawn picture is kept as an attachment, and attachments are turned off for this installation.")
        }
        val source = artifacts.storedOf(workspaceId, input.imageId)
            ?: throw DesignImageRefusedException("The picture to change is not here any more.")
        val mask = runCatching { java.util.Base64.getDecoder().decode(input.mask.substringAfter("base64,")) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: throw DesignImageRefusedException("The selection could not be read; select again.")

        val job = jobs.save(
            DesignImageJob(
                workspaceId = workspaceId,
                modelId = modelId,
                modelName = model.name,
                prompt = prompt,
                size = null,
                quality = null,
                style = null,
                requestedBy = currentName(),
                name = input.name.orNullIfBlank(),
                sourceId = input.imageId,
                maskLocation = store.put(workspaceId, "mask.png", mask),
            ),
        )
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.WORKFLOW,
            "Change to ${source.view.name ?: source.view.filename} queued in Design with ${model.name}",
        )
        queue.queued(job)
        return view(job)
    }

    /**
     * What the workspace has waiting, being drawn, or failed - oldest first.
     * Not a change to a picture: that becomes a version in the editor, not a
     * picture in the gallery.
     */
    @QueryMapping
    fun designImageQueue(@Argument workspaceId: Long): List<DesignImageJobView> {
        access.requireVisible(workspaceId)
        return jobs.findByWorkspaceIdAndStatusInOrderByRequestedAtAscIdAsc(workspaceId, SHOWN)
            .filter { it.sourceId == null }
            .map(::view)
    }

    /** One request, in whatever state it is - with its picture once drawn. Null for one not here. */
    @QueryMapping
    fun designImageJob(@Argument workspaceId: Long, @Argument id: Long): DesignImageJobView? {
        access.requireVisible(workspaceId)
        return jobs.findByIdOrNull(id)?.takeIf { it.workspaceId == workspaceId }?.let(::view)
    }

    /**
     * Takes a request off the queue, or a failed one off the gallery.
     *
     * Not one being drawn: the model has it, and taking the row away would not
     * stop the drawing, only lose the picture.
     */
    @MutationMapping
    @Transactional
    fun dismissDesignImageJob(@Argument id: Long): Boolean {
        val job = jobs.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return false
        if (job.status == DesignImageJobStatus.DRAWING) {
            throw DesignImageRefusedException("This picture is being drawn and cannot be taken off now.")
        }
        jobs.delete(job)
        if (job.status == DesignImageJobStatus.QUEUED) {
            auditRecorder.record(job.workspaceId, WorkspaceAuditCategory.WORKFLOW, "Queued image taken off in Design")
        }
        return true
    }

    /** A new name, and the file named after it, so a download says what the picture is called. */
    @MutationMapping
    @Transactional
    fun renameDesignImage(@Argument workspaceId: Long, @Argument id: String, @Argument name: String): ArtifactView {
        access.requireVisible(workspaceId)
        val image = id.removePrefix("DESIGN-").toLongOrNull()?.let { images.findByIdOrNull(it) }?.takeIf { it.workspaceId == workspaceId }
            ?: throw DesignImageRefusedException("That picture is not here any more.")
        val called = name.trim()
        if (called.isEmpty()) throw DesignImageRefusedException("A picture needs a name.")
        if (called.length > NAME_LENGTH) throw DesignImageRefusedException("A picture's name is at most $NAME_LENGTH characters.")
        if (called != image.name) {
            val before = image.name ?: PictureFilenames.nameOf(image.prompt)
            image.name = called
            image.filename = PictureFilenames.of(called, image.contentType)
            images.save(image)
            auditRecorder.record(workspaceId, WorkspaceAuditCategory.WORKFLOW, "Image $before renamed to $called in Design")
        }
        return artifacts.describe(image)
    }

    /**
     * A version saved: over the picture it is a version of - its pixels, the
     * picture's name and everything else kept, whatever drew it - or, asCopy, as
     * a Design picture of its own beside it. Either way the picture's other
     * versions go.
     */
    @MutationMapping
    @Transactional
    fun saveDesignImageVersion(@Argument workspaceId: Long, @Argument id: String, @Argument asCopy: Boolean?): ArtifactView {
        access.requireVisible(workspaceId)
        val version = id.removePrefix("DESIGN-").toLongOrNull()?.let { images.findByIdOrNull(it) }
            ?.takeIf { it.workspaceId == workspaceId && it.versionOf != null }
            ?: throw DesignImageRefusedException("There is no unsaved change to save.")
        val of = requireNotNull(version.versionOf)

        val kept: ArtifactView = if (asCopy == true) {
            // A picture of its own, beside the one it was made from, which stays as it was saved.
            val from = artifacts.storedOf(workspaceId, of)?.view
            version.versionOf = null
            version.name = "${from?.name ?: version.name ?: PictureFilenames.nameOf(version.prompt)} (copy)"
            version.filename = PictureFilenames.of(requireNotNull(version.name), version.contentType)
            artifacts.describe(images.save(version))
        } else {
            // Written over the picture itself, whatever drew it; the version's file becomes the picture's.
            if (!artifacts.replaceBytes(workspaceId, of, version.location, version.contentType, version.sizeBytes)) {
                throw DesignImageRefusedException("The picture these changes were made to is not here any more; save them as a copy.")
            }
            images.delete(version)
            requireNotNull(artifacts.storedOf(workspaceId, of)).view
        }
        discard(workspaceId, of)
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.WORKFLOW,
            if (asCopy == true) "Changes saved as ${kept.name ?: kept.filename} in Design" else "Changes to ${kept.name ?: kept.filename} saved in Design",
        )
        return kept
    }

    /** Every unsaved version of a picture thrown away, files and all. */
    @MutationMapping
    @Transactional
    fun discardDesignImageVersions(@Argument workspaceId: Long, @Argument id: String): Boolean {
        access.requireVisible(workspaceId)
        return discard(workspaceId, id) > 0
    }

    private fun discard(workspaceId: Long, of: String): Int {
        val versions = images.findByWorkspaceIdAndVersionOf(workspaceId, of)
        versions.forEach {
            images.delete(it)
            runCatching { store.remove(it.location) }
        }
        return versions.size
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

    private fun view(job: DesignImageJob): DesignImageJobView {
        // How many are drawn before this one: the installation's queue, since the limit is the installation's.
        val ahead = if (job.status != DesignImageJobStatus.QUEUED) {
            null
        } else {
            jobs.findByStatusOrderByRequestedAtAscIdAsc(DesignImageJobStatus.QUEUED).indexOfFirst { it.id == job.id }.takeIf { it >= 0 }
        }
        return DesignImageJobView(
            id = requireNotNull(job.id).toString(),
            status = job.status,
            prompt = job.prompt,
            modelId = job.modelId?.toString(),
            model = job.modelName,
            size = job.size,
            quality = job.quality,
            style = job.style,
            requestedBy = job.requestedBy,
            requestedAt = job.requestedAt.toString(),
            startedAt = job.startedAt?.toString(),
            reason = job.reason,
            ahead = ahead,
            name = job.name ?: PictureFilenames.nameOf(job.prompt),
            sourceId = job.sourceId,
            image = job.imageId?.let { images.findByIdOrNull(it) }?.let(artifacts::describe),
        )
    }

    private fun currentName(): String {
        val authentication = SecurityContextHolder.getContext().authentication
        return authentication?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }?.name ?: "somebody"
    }

    private fun String?.orNullIfBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        /** The column's own width: a name is a title, not a description. */
        const val NAME_LENGTH = 255

        val SHOWN = listOf(DesignImageJobStatus.QUEUED, DesignImageJobStatus.DRAWING, DesignImageJobStatus.FAILED)
    }
}

data class DrawDesignImageInput(
    val workspaceId: String,
    val modelId: String,
    val prompt: String,
    val size: String? = null,
    val quality: String? = null,
    val style: String? = null,
    /** What to call it; null or blank names it after its prompt's first words. */
    val name: String? = null,
)

data class EditDesignImageInput(
    val workspaceId: String,
    /** The picture to change, by its artifact id. */
    val imageId: String,
    val modelId: String,
    val prompt: String,
    /** A PNG the picture's size, base64, transparent where the change goes. */
    val mask: String,
    val name: String? = null,
)

data class DesignImageJobView(
    val id: String,
    val status: DesignImageJobStatus,
    val prompt: String,
    val modelId: String?,
    val model: String,
    val size: String?,
    val quality: String?,
    val style: String?,
    val requestedBy: String,
    val requestedAt: String,
    val startedAt: String?,
    val reason: String?,
    /** How many queued pictures are drawn before this one; null unless it is queued. */
    val ahead: Int?,
    val name: String,
    /** The picture an edit changes; null for one drawn from nothing. */
    val sourceId: String?,
    val image: ArtifactView?,
)

/** Why nothing was drawn, in the words the page shows. */
class DesignImageRefusedException(reason: String) : RuntimeException(reason)
