package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.connector.model.ModelKind
import io.mszymanski.orknux.connector.model.ModelUpscaleClient
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.server.attachment.AttachmentDownloads
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.attachment.MAX_HARMONIZE_STRENGTH
import io.mszymanski.orknux.server.attachment.MIN_HARMONIZE_STRENGTH
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
    private val upscaling: ModelUpscaleClient,
    private val mapper: tools.jackson.databind.ObjectMapper,
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
     * A picture made larger by an upscale model: a version of the picture, as an
     * edit is, kept until saved over it or thrown away.
     */
    @MutationMapping
    @Transactional
    fun queueDesignImageUpscale(@Argument input: UpscaleDesignImageInput): DesignImageJobView {
        val workspaceId = input.workspaceId.toLong()
        access.requireVisible(workspaceId)
        val modelId = input.modelId.toLong()
        val model = models.model(modelId)?.takeIf { it.workspaceId == workspaceId }
            ?: throw ModelNotFoundForWorkspaceException(modelId)
        if (model.kind != ModelKind.UPSCALE) throw DesignImageRefusedException("${model.name} is not an upscale model.")
        if (input.scale !in 1.0..8.0) throw DesignImageRefusedException("A picture can be made 1 to 8 times larger.")
        if (!settings.attachmentsEnabled()) {
            throw DesignImageRefusedException("A drawn picture is kept as an attachment, and attachments are turned off for this installation.")
        }
        val source = artifacts.storedOf(workspaceId, input.imageId)
            ?: throw DesignImageRefusedException("The picture to make larger is not here any more.")
        val job = jobs.save(
            DesignImageJob(
                workspaceId = workspaceId,
                modelId = modelId,
                modelName = model.name,
                prompt = source.view.prompt,
                size = null,
                quality = null,
                style = null,
                requestedBy = currentName(),
                sourceId = input.imageId,
                upscaler = input.algorithm.orNullIfBlank(),
                scale = input.scale,
            ),
        )
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.WORKFLOW,
            "${source.view.name ?: source.view.filename} queued to be made ${input.scale}x larger in Design with ${model.name}",
        )
        queue.queued(job)
        return view(job)
    }

    /**
     * The whole picture redrawn lightly, so the seams a Magic edit left become
     * one picture - a version of it, as an edit is.
     *
     * Two roads, by the model chosen. An upscale model stands for its Stable
     * Diffusion WebUI, which redraws by img2img at the strength asked. Any
     * image model gets the standard `/images/edits` with a mask over the whole
     * picture - which takes no strength, so none is sent rather than one that
     * would be ignored.
     */
    @MutationMapping
    @Transactional
    fun queueDesignImageHarmonize(@Argument input: HarmonizeDesignImageInput): DesignImageJobView {
        val workspaceId = input.workspaceId.toLong()
        access.requireVisible(workspaceId)
        val modelId = input.modelId.toLong()
        val model = models.model(modelId)?.takeIf { it.workspaceId == workspaceId }
            ?: throw ModelNotFoundForWorkspaceException(modelId)
        val webUi = when (model.kind) {
            ModelKind.UPSCALE -> true
            ModelKind.IMAGE -> false
            else -> throw ModelNotImageException(model.name)
        }
        val prompt = input.prompt.trim()
        if (prompt.isEmpty()) throw DesignImageRefusedException("Say what the picture is of.")
        val longest = settings.imageMaxPrompt()
        if (prompt.length > longest) {
            throw DesignImageRefusedException("That description is too long to draw from; say it in under $longest characters.")
        }
        val strength = if (webUi) input.strength ?: settings.imageHarmonizeStrength() else null
        if (strength != null && strength !in MIN_HARMONIZE_STRENGTH..MAX_HARMONIZE_STRENGTH) {
            throw DesignImageRefusedException("A strength is between $MIN_HARMONIZE_STRENGTH and $MAX_HARMONIZE_STRENGTH.")
        }
        if (!settings.attachmentsEnabled()) {
            throw DesignImageRefusedException("A drawn picture is kept as an attachment, and attachments are turned off for this installation.")
        }
        val source = artifacts.storedOf(workspaceId, input.imageId)
            ?: throw DesignImageRefusedException("The picture to harmonize is not here any more.")
        val mask = if (webUi) {
            null
        } else {
            val picture = runCatching { store.open(source.location).use { javax.imageio.ImageIO.read(it) } }.getOrNull()
                ?: throw DesignImageRefusedException("The picture to harmonize could not be read.")
            // Transparent everywhere: the edit's own way of saying change all of it.
            val clear = java.awt.image.BufferedImage(picture.width, picture.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            val bytes = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(clear, "png", it) }.toByteArray()
            store.put(workspaceId, "mask.png", bytes)
        }
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
                sourceId = input.imageId,
                maskLocation = mask,
                strength = strength,
                checkpoint = if (webUi) input.checkpoint.orNullIfBlank() else null,
            ),
        )
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.WORKFLOW,
            "${source.view.name ?: source.view.filename} queued to be harmonized in Design with ${model.name}",
        )
        queue.queued(job)
        return view(job)
    }

    /**
     * What Harmonize can be asked with one model: whether it takes a strength,
     * where it starts, and - for a WebUI - the checkpoints it redraws with.
     */
    @QueryMapping
    fun designHarmonizeOptions(@Argument workspaceId: Long, @Argument modelId: Long): HarmonizeOptionsView {
        access.requireVisible(workspaceId)
        val model = models.model(modelId)?.takeIf { it.workspaceId == workspaceId }
            ?: throw ModelNotFoundForWorkspaceException(modelId)
        val strength = settings.imageHarmonizeStrength()
        if (model.kind != ModelKind.UPSCALE) return HarmonizeOptionsView(false, strength, emptyList(), null, null)
        return when (val offered = upscaling.checkpoints(modelId)) {
            is ModelUpscaleClient.Checkpoints.Offered -> HarmonizeOptionsView(true, strength, offered.titles, offered.preferred, null)
            is ModelUpscaleClient.Checkpoints.Failed -> HarmonizeOptionsView(true, strength, emptyList(), null, offered.reason)
        }
    }

    /** The algorithms an upscale model's server offers, asked of it now. */
    @QueryMapping
    fun designUpscaleAlgorithms(@Argument workspaceId: Long, @Argument modelId: Long): UpscaleAlgorithmsView {
        access.requireVisible(workspaceId)
        val model = models.model(modelId)?.takeIf { it.workspaceId == workspaceId }
            ?: throw ModelNotFoundForWorkspaceException(modelId)
        return when (val offered = upscaling.algorithms(modelId)) {
            is ModelUpscaleClient.Algorithms.Offered -> UpscaleAlgorithmsView(offered.names, offered.preferred, null)
            is ModelUpscaleClient.Algorithms.Failed -> UpscaleAlgorithmsView(emptyList(), model.modelId, offered.reason)
        }
    }

    /**
     * A picture brought in from outside, kept as a Design picture of its own -
     * there to be edited, upscaled and saved like one drawn here.
     */
    @MutationMapping
    @Transactional
    fun importDesignImage(@Argument input: ImportDesignImageInput): ArtifactView {
        val workspaceId = input.workspaceId.toLong()
        access.requireVisible(workspaceId)
        if (!settings.attachmentsEnabled()) {
            throw DesignImageRefusedException("A picture is kept as an attachment, and attachments are turned off for this installation.")
        }
        fun decoded(text: String) = runCatching { java.util.Base64.getDecoder().decode(text.substringAfter("base64,")) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: throw DesignImageRefusedException("The file could not be read.")
        val maxBytes = settings.maxFileSizeMb() * 1024 * 1024
        // An .orkx archive brings its project with it; a picture alone brings only what the page says.
        val archived = input.archive.orNullIfBlank()?.let { DesignArchive.read(mapper, decoded(it), maxBytes) }
        val bytes = archived?.image ?: decoded(input.image.orNullIfBlank() ?: throw DesignImageRefusedException("There is no file to import."))
        if (bytes.size > maxBytes) throw DesignImageRefusedException("That picture is larger than this installation keeps.")
        val contentType = pictureTypeOf(bytes)
            ?: throw DesignImageRefusedException("That file is not a picture this can keep: a PNG, JPEG, WebP or GIF.")
        val project = archived?.project
        val name = (input.name.orNullIfBlank() ?: project?.name)?.take(NAME_LENGTH) ?: "Imported picture"
        val filename = PictureFilenames.of(name, contentType)
        // The model it was drawn with, where this workspace has one of that name; named either way.
        val drawnWith = project?.model?.let { wanted ->
            models.models(workspaceId).firstOrNull { it.name == wanted && it.kind == ModelKind.IMAGE }
        }
        val saved = images.save(
            DesignImage(
                workspaceId = workspaceId,
                modelId = drawnWith?.id,
                modelName = project?.model?.take(255) ?: IMPORTED,
                prompt = input.prompt.orNullIfBlank() ?: project?.prompt ?: name,
                size = project?.size?.take(40),
                quality = project?.quality?.take(40),
                style = project?.style?.take(40),
                filename = filename,
                contentType = contentType,
                sizeBytes = bytes.size.toLong(),
                location = store.put(workspaceId, filename, bytes),
                drawnBy = project?.drawnBy?.take(255) ?: currentName(),
                name = name,
            ),
        )
        auditRecorder.record(workspaceId, WorkspaceAuditCategory.WORKFLOW, "Image $filename imported into Design")
        return artifacts.describe(saved)
    }

    /**
     * What a file is, by its first bytes rather than by what the browser said:
     * the type is what the gallery serves it as, and a name is no evidence.
     */
    private fun pictureTypeOf(bytes: ByteArray): String? {
        fun starts(vararg head: Int) = bytes.size >= head.size && head.indices.all { (bytes[it].toInt() and 0xFF) == head[it] }
        return when {
            starts(0x89, 0x50, 0x4E, 0x47) -> "image/png"
            starts(0xFF, 0xD8, 0xFF) -> "image/jpeg"
            starts(0x47, 0x49, 0x46, 0x38) -> "image/gif"
            bytes.size >= 12 && starts(0x52, 0x49, 0x46, 0x46) && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> null
        }
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

    /**
     * Any picture in the workspace's gallery as an .orkx archive: its file and
     * its project - name, prompt, and what it was drawn with.
     */
    @GetMapping("/api/workspaces/{workspaceId}/design/archive/{artifactId}")
    @ResponseBody
    fun archive(@PathVariable workspaceId: Long, @PathVariable artifactId: String): ResponseEntity<ByteArray> {
        if (!access.canSee(workspaceId)) return ResponseEntity.notFound().build()
        val stored = artifacts.storedOf(workspaceId, artifactId)?.takeIf { store.exists(it.location) }
            ?: return ResponseEntity.notFound().build()
        val view = stored.view
        val drawn = view.drawnWith
        val bytes = DesignArchive.write(
            mapper,
            DesignArchive.Project(
                name = view.name ?: PictureFilenames.nameOf(view.prompt),
                prompt = view.prompt,
                model = drawn?.model,
                size = drawn?.size,
                quality = drawn?.quality,
                style = drawn?.style,
                drawnBy = drawn?.drawnBy,
                drawnAt = view.drawnAt,
            ),
            store.open(stored.location).use { it.readBytes() },
            stored.contentType,
        )
        val filename = PictureFilenames.of(view.name ?: PictureFilenames.nameOf(view.prompt), "image/png").substringBeforeLast('.') + ".orkx"
        return ResponseEntity.ok()
            .contentType(org.springframework.http.MediaType.parseMediaType("application/zip"))
            .header(
                org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                org.springframework.http.ContentDisposition.attachment().filename(filename, Charsets.UTF_8).build().toString(),
            )
            .body(bytes)
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
            upscaler = job.upscaler,
            strength = job.strength,
            checkpoint = job.checkpoint,
            scale = job.scale,
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

        /** What an imported picture says it was drawn with: nothing here drew it. */
        const val IMPORTED = "Imported"

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

data class UpscaleDesignImageInput(
    val workspaceId: String,
    /** The picture to make larger, by its artifact id. */
    val imageId: String,
    val modelId: String,
    /** One of the server's algorithms; null or blank is the model's own. */
    val algorithm: String? = null,
    val scale: Double,
)

data class ImportDesignImageInput(
    val workspaceId: String,
    /** A picture's file, base64; or [archive]. */
    val image: String? = null,
    /** An .orkx archive, base64: the picture with its project. */
    val archive: String? = null,
    val name: String? = null,
    val prompt: String? = null,
)

data class UpscaleAlgorithmsView(val names: List<String>, val preferred: String?, val reason: String?)

data class HarmonizeDesignImageInput(
    val workspaceId: String,
    /** The picture to redraw, by its artifact id. */
    val imageId: String,
    /** An image model, or an upscale model for its Stable Diffusion WebUI. */
    val modelId: String,
    val prompt: String,
    /** A WebUI's denoising strength; null is the installation's. An image model takes none. */
    val strength: Double? = null,
    /** A WebUI checkpoint by title; null or blank is the one it has loaded. */
    val checkpoint: String? = null,
)

data class HarmonizeOptionsView(
    val strengthTaken: Boolean,
    val strength: Double,
    val checkpoints: List<String>,
    val preferredCheckpoint: String?,
    val reason: String?,
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
    /** The algorithm an upscale uses; null for anything else. */
    val upscaler: String? = null,
    /** How many times larger an upscale makes it; null for anything else. */
    val scale: Double? = null,
    /** How strongly a Harmonize through a WebUI redraws; null for anything else. */
    val strength: Double? = null,
    /** The WebUI checkpoint a Harmonize redraws with; null for anything else. */
    val checkpoint: String? = null,
    val image: ArtifactView?,
)

/** Why nothing was drawn, in the words the page shows. */
class DesignImageRefusedException(reason: String) : RuntimeException(reason)
