package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.connector.model.LlmModelView
import io.mszymanski.orknux.connector.model.ModelKind
import io.mszymanski.orknux.connector.model.NegativePrompts
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
    private val tuning: io.mszymanski.orknux.server.tuning.Tuning,
    private val layers: DesignImageLayers,
    private val estimates: DesignImageEstimates,
    /** The image catalogs a picture is filed in. */
    private val catalogs: DesignCatalogs,
) {

    /**
     * Where the image editor's own tools start, and how far each may go - the
     * installation's numbers on Admin -> Settings, so a page holds none of its own.
     */
    @QueryMapping
    fun designImageEditorSettings(@Argument workspaceId: Long): ImageEditorSettingsView {
        access.requireVisible(workspaceId)
        val brush = io.mszymanski.orknux.server.tuning.Tunable.IMAGE_BRUSH_PIXELS
        val blur = io.mszymanski.orknux.server.tuning.Tunable.IMAGE_BLUR_PIXELS
        val falloff = io.mszymanski.orknux.server.tuning.Tunable.IMAGE_BLUR_FALLOFF_PIXELS
        val feather = io.mszymanski.orknux.server.tuning.Tunable.IMAGE_FEATHER_PIXELS
        return ImageEditorSettingsView(
            brush = tuning[brush],
            brushMost = sliderReach(brush, BRUSH_SLIDER_REACH),
            blur = tuning[blur],
            blurMost = sliderReach(blur, BRUSH_SLIDER_REACH),
            blurFalloff = tuning[falloff],
            blurFalloffMost = sliderReach(falloff, FALLOFF_SLIDER_REACH),
            feather = tuning[feather],
            featherMost = sliderReach(feather, FEATHER_SLIDER_REACH),
            layersMost = tuning[io.mszymanski.orknux.server.tuning.Tunable.IMAGE_LAYERS_MOST],
            brushOpacity = tuning[io.mszymanski.orknux.server.tuning.Tunable.IMAGE_BRUSH_OPACITY_PERCENT],
            brushHardness = tuning[io.mszymanski.orknux.server.tuning.Tunable.IMAGE_BRUSH_HARDNESS_PERCENT],
            markerOpacity = tuning[io.mszymanski.orknux.server.tuning.Tunable.IMAGE_MARKER_OPACITY_PERCENT],
        )
    }

    /**
     * How far one of the image editor's sliders reaches: the setting's own
     * ceiling where it has one (the blur's 254), and otherwise [reach] - or the
     * installation's starting value, where that was set wider. A slider has to
     * end somewhere to be a slider; the setting itself has no ceiling.
     */
    private fun sliderReach(tunable: io.mszymanski.orknux.server.tuning.Tunable, reach: Int): Int =
        tunable.most ?: maxOf(reach, tuning[tunable])

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
                negativePrompt = negativeFor(model, input.negativePrompt),
                catalogId = catalogs.resolve(workspaceId, DesignCatalogKind.IMAGE, input.catalogId?.toLongOrNull()),
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
        val feathering = io.mszymanski.orknux.server.tuning.Tunable.IMAGE_FEATHER_PIXELS
        val feather = input.feather ?: tuning[feathering]
        // No ceiling, as the setting has none; MaskedComposite holds a wide one to the picture.
        if (feather < 0) {
            throw DesignImageRefusedException("A feather is 0 pixels or more.")
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
                name = input.name.orNullIfBlank(),
                sourceId = input.imageId,
                maskLocation = store.put(workspaceId, "mask.png", mask),
                negativePrompt = negativeFor(model, input.negativePrompt),
                feather = feather,
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
            if (input.scale == 1.0) {
                "${source.view.name ?: source.view.filename} queued to be cleaned up in Design with ${model.name}"
            } else {
                "${source.view.name ?: source.view.filename} queued to be made ${input.scale}x larger in Design with ${model.name}"
            },
        )
        queue.queued(job)
        return view(job)
    }

    /**
     * The whole picture redrawn lightly, so the seams a Magic edit left become
     * one picture - a version of it, as an edit is.
     *
     * Only through a Stable Diffusion WebUI - an upscale model stands for it -
     * which redraws by img2img at the strength asked. An image model was a
     * second road, the standard `/images/edits` with a mask over the whole
     * picture, and it is refused: that endpoint takes no strength, so it is not
     * a light redraw but a whole one, and a prompt such as "add birds" put
     * birds over all of it. The light redraw is the point of the tool.
     */
    @MutationMapping
    @Transactional
    fun queueDesignImageHarmonize(@Argument input: HarmonizeDesignImageInput): DesignImageJobView {
        val workspaceId = input.workspaceId.toLong()
        access.requireVisible(workspaceId)
        val modelId = input.modelId.toLong()
        val model = models.model(modelId)?.takeIf { it.workspaceId == workspaceId }
            ?: throw ModelNotFoundForWorkspaceException(modelId)
        when (model.kind) {
            ModelKind.UPSCALE -> Unit
            ModelKind.IMAGE -> throw DesignImageRefusedException(
                "${model.name} redraws a picture whole. Harmonize redraws it lightly through a Stable Diffusion WebUI; choose one of its models.",
            )
            else -> throw ModelNotImageException(model.name)
        }
        val webUi = true
        val prompt = input.prompt.trim()
        // May be empty: a light redraw with no words is the picture itself, smoothed, which is often the point.
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
        val mask: String? = null
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
                negativePrompt = negativeFor(model, input.negativePrompt),
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
            is ModelUpscaleClient.Algorithms.Offered ->
                UpscaleAlgorithmsView(offered.names, offered.preferred, null, offered.names.filter { RESTORING.containsMatchIn(it) })
            is ModelUpscaleClient.Algorithms.Failed -> UpscaleAlgorithmsView(emptyList(), model.modelId, offered.reason, emptyList())
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
        val filed = catalogs.resolve(workspaceId, DesignCatalogKind.IMAGE, input.catalogId?.toLongOrNull())
        if (!settings.attachmentsEnabled()) {
            throw DesignImageRefusedException("A picture is kept as an attachment, and attachments are turned off for this installation.")
        }
        fun decoded(text: String) = runCatching { java.util.Base64.getDecoder().decode(text.substringAfter("base64,")) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: throw DesignImageRefusedException("The file could not be read.")
        val maxBytes = settings.maxFileSizeMb() * 1024 * 1024
        // An .orkx archive brings its project with it; a picture alone brings only what the page says.
        val archived = input.archive.orNullIfBlank()?.let {
            DesignArchive.read(mapper, decoded(it), maxBytes, io.mszymanski.orknux.server.tuning.TunableArithmetic.plus(tuning[io.mszymanski.orknux.server.tuning.Tunable.IMAGE_LAYERS_MOST], 1))
        }
        val bytes = archived?.image ?: decoded(input.image.orNullIfBlank() ?: throw DesignImageRefusedException("There is no file to import."))
        // The same refusal every upload gives, naming the limit and where it is raised.
        settings.requireUploadable(input.name.orNullIfBlank() ?: "That picture", bytes.size.toLong())
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
                catalogId = filed,
            ),
        )
        // The archive's layers come with it, checked as a change's are.
        archived?.layers?.takeIf { it.isNotEmpty() }?.let { stack ->
            val read = layersOf(
                stack.map { DesignImageLayerInput(it.name, it.visible, it.picture, java.util.Base64.getEncoder().encodeToString(it.png)) },
                bytes,
            )
            layers.keep(workspaceId, "DESIGN-${saved.id}", read)
        }
        auditRecorder.record(workspaceId, WorkspaceAuditCategory.WORKFLOW, "Image $filename imported into Design")
        return artifacts.describe(saved)
    }

    /**
     * A change the image editor made itself - rotated, mirrored, cropped,
     * painted, blurred, layers merged - kept as a version of the picture, as a
     * model's change is, so Undo, Save and Discard treat the two alike. Issue #634.
     *
     * No model and no queue: the pixels were worked out in the browser, which
     * already holds the picture, and are only kept here. So the request is
     * drawn the moment it is filed, and answered with its version.
     */
    @MutationMapping
    @Transactional
    fun keepDesignImageChange(@Argument input: KeepDesignImageChangeInput): DesignImageJobView {
        val workspaceId = input.workspaceId.toLong()
        access.requireVisible(workspaceId)
        if (!settings.attachmentsEnabled()) {
            throw DesignImageRefusedException("A picture is kept as an attachment, and attachments are turned off for this installation.")
        }
        val change = input.change.trim()
        if (change.isEmpty()) throw DesignImageRefusedException("Say what was changed.")
        if (change.length > NAME_LENGTH) throw DesignImageRefusedException("What was changed is said in at most $NAME_LENGTH characters.")
        val source = artifacts.storedOf(workspaceId, input.imageId)
            ?: throw DesignImageRefusedException("The picture to change is not here any more.")
        val bytes = runCatching { java.util.Base64.getDecoder().decode(input.image.substringAfter("base64,")) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: throw DesignImageRefusedException("The changed picture could not be read.")
        if (bytes.size > settings.maxFileSizeMb() * 1024 * 1024) {
            throw DesignImageRefusedException("That picture is larger than this installation keeps.")
        }
        val contentType = pictureTypeOf(bytes)
            ?: throw DesignImageRefusedException("That file is not a picture this can keep: a PNG, JPEG, WebP or GIF.")
        val stack = input.layers?.let { layersOf(it, bytes) }
        val view = source.view
        val name = view.name ?: PictureFilenames.nameOf(view.prompt)
        val filename = PictureFilenames.of(name, contentType)
        val version = images.save(
            DesignImage(
                workspaceId = workspaceId,
                modelId = view.drawnWith?.modelId?.toLongOrNull(),
                modelName = view.drawnWith?.model ?: IMAGE_EDITOR,
                prompt = view.prompt,
                size = null,
                quality = null,
                style = null,
                filename = filename,
                contentType = contentType,
                sizeBytes = bytes.size.toLong(),
                location = store.put(workspaceId, filename, bytes),
                drawnBy = currentName(),
                name = name,
                // A version of the picture itself, not of the round before - as a model's change is.
                versionOf = view.versionOf ?: input.imageId,
            ),
        )
        // The layers it was made with, kept beside it; a change sent without any is the picture flat.
        if (!stack.isNullOrEmpty()) layers.keep(workspaceId, "DESIGN-${version.id}", stack)
        val job = jobs.save(
            DesignImageJob(
                workspaceId = workspaceId,
                modelId = null,
                modelName = IMAGE_EDITOR,
                prompt = change,
                size = null,
                quality = null,
                style = null,
                requestedBy = currentName(),
                startedAt = java.time.OffsetDateTime.now(),
                status = DesignImageJobStatus.DRAWN,
                imageId = version.id,
                name = name,
                sourceId = input.imageId,
            ),
        )
        auditRecorder.record(workspaceId, WorkspaceAuditCategory.WORKFLOW, "Change to $name made in Design: $change")
        return view(job)
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
     * The layers a change was sent with, read and checked: the picture itself
     * first and only there, as many as Admin Settings allows, each a PNG the
     * picture's own size - a layer of another size would not line up with the
     * flattened file it is kept beside.
     */
    private fun layersOf(given: List<DesignImageLayerInput>, flattened: ByteArray): List<LayerPixels> {
        if (given.isEmpty()) return emptyList()
        val most = tuning[io.mszymanski.orknux.server.tuning.Tunable.IMAGE_LAYERS_MOST]
        // given.size - 1 rather than most + 1, which wraps negative at Int.MAX_VALUE.
        if (given.size - 1 > most) throw DesignImageRefusedException("A picture keeps at most $most layers over it.")
        if (!given.first().picture || given.drop(1).any { it.picture }) {
            throw DesignImageRefusedException("The picture itself is the lowest layer, and the only one.")
        }
        val size = PictureScaling.read(flattened)?.let { it.width to it.height }
            ?: throw DesignImageRefusedException("The changed picture could not be read.")
        val maxBytes = settings.maxFileSizeMb() * 1024 * 1024
        return given.map { layer ->
            val png = runCatching { java.util.Base64.getDecoder().decode(layer.image.substringAfter("base64,")) }.getOrNull()
                ?.takeIf { it.isNotEmpty() && pictureTypeOf(it) == "image/png" }
                ?: throw DesignImageRefusedException("A layer could not be read; layers are kept as PNG.")
            if (png.size > maxBytes) throw DesignImageRefusedException("A layer is larger than this installation keeps.")
            val read = PictureScaling.read(png) ?: throw DesignImageRefusedException("A layer could not be read; layers are kept as PNG.")
            if (read.width to read.height != size) throw DesignImageRefusedException("Every layer is the picture's own size.")
            val name = layer.name?.trim()?.takeIf { it.isNotEmpty() }
            if (!layer.picture && name == null) throw DesignImageRefusedException("Every layer has a name.")
            if (name != null && name.length > NAME_LENGTH) throw DesignImageRefusedException("A layer's name is at most $NAME_LENGTH characters.")
            LayerPixels(name, layer.visible, layer.picture, png)
        }
    }

    /**
     * A picture's layers in the image editor, the picture itself first - its
     * pixels without the layers over it - or none for a picture kept flat.
     */
    @QueryMapping
    fun designImageLayers(@Argument workspaceId: Long, @Argument imageId: String): List<DesignImageLayerView> {
        access.requireVisible(workspaceId)
        artifacts.storedOf(workspaceId, imageId) ?: return emptyList()
        return layers.of(workspaceId, imageId).map {
            DesignImageLayerView(
                id = requireNotNull(it.id).toString(),
                name = it.name,
                visible = it.visible,
                picture = it.picture,
                url = "/api/design-image-layers/${it.id}",
            )
        }
    }

    /** A layer's pixels, to anybody who can see its workspace - as its picture is. */
    @GetMapping("/api/design-image-layers/{id}")
    @ResponseBody
    fun layer(@PathVariable id: Long): ResponseEntity<InputStreamResource> {
        val layer = layers.byId(id)?.takeIf { access.canSee(it.workspaceId) && store.exists(it.location) }
            ?: return ResponseEntity.notFound().build()
        return downloads.serve(filename = "layer-$id.png", contentType = "image/png", sizeBytes = layer.sizeBytes, location = layer.location)
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
     * What was asked of a picture in the image editor and is not yet saved or
     * discarded, newest first: each version drawn, and each change still
     * waiting, being drawn, failed or stopped - over the picture or over one of
     * its versions. The editor's strip, read again when the page is, so the
     * history survives a reload rather than living only in the page that asked.
     */
    @QueryMapping
    fun designImageHistory(@Argument workspaceId: Long, @Argument imageId: String): List<DesignImageJobView> {
        access.requireVisible(workspaceId)
        val versions = images.findByWorkspaceIdAndVersionOf(workspaceId, imageId).mapNotNull { it.id }.toSet()
        val over = versions.map { "DESIGN-$it" } + imageId
        return jobs.findByWorkspaceIdAndSourceIdInOrderByRequestedAtDescIdDesc(workspaceId, over)
            // A drawn one is history while its version is: saved or discarded, it is gone.
            .filter { it.status != DesignImageJobStatus.DRAWN || it.imageId in versions }
            .map(::view)
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

    /**
     * Stops a picture waiting or being drawn - a new one, an edit, an upscale
     * or a Harmonize. A queued one is never started; one being drawn is
     * stopped at its WebUI where it has one, and whatever comes back is thrown
     * away. Either way no version and no picture comes of it.
     */
    @MutationMapping
    fun stopDesignImageJob(@Argument workspaceId: Long, @Argument id: Long): DesignImageJobView {
        access.requireVisible(workspaceId)
        val job = jobs.findByIdOrNull(id)?.takeIf { it.workspaceId == workspaceId }
            ?: throw DesignImageRefusedException("That picture is not here any more.")
        if (jobs.stop(id) != 1) throw DesignImageRefusedException("This picture is not waiting or being drawn any more.")
        // Read again: it may have been claimed between the first read and the stop.
        val stopped = jobs.findByIdOrNull(id) ?: job
        queue.stopped(stopped)
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.WORKFLOW,
            "Image ${job.name ?: PictureFilenames.nameOf(job.prompt)} stopped in Design",
        )
        return view(stopped)
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
        forgetUndrawn(workspaceId, of)

        val kept: ArtifactView = if (asCopy == true) {
            // A picture of its own, beside the one it was made from, which stays as it was saved.
            val from = artifacts.storedOf(workspaceId, of)?.view
            version.versionOf = null
            // Beside it in its catalog, as a copy made in a folder is.
            version.catalogId = artifacts.catalogOf(workspaceId, of)
            version.name = "${from?.name ?: version.name ?: PictureFilenames.nameOf(version.prompt)} (copy)"
            version.filename = PictureFilenames.of(requireNotNull(version.name), version.contentType)
            artifacts.describe(images.save(version))
        } else {
            // Written over the picture itself, whatever drew it; the version's file becomes the picture's.
            if (!artifacts.replaceBytes(workspaceId, of, version.location, version.contentType, version.sizeBytes)) {
                throw DesignImageRefusedException("The picture these changes were made to is not here any more; save them as a copy.")
            }
            // And its layers, or none where it was kept flat: the picture is what the version was.
            layers.move(workspaceId, "DESIGN-${version.id}", of)
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

    /**
     * Files a picture on the Images page in another of its workspace's image
     * catalogs, whatever drew it - Design, a run or a task.
     */
    @MutationMapping
    @Transactional
    fun moveDesignImage(@Argument workspaceId: Long, @Argument id: String, @Argument catalogId: Long): ArtifactView {
        access.requireVisible(workspaceId)
        val into = catalogs.catalog(workspaceId, DesignCatalogKind.IMAGE, catalogId)
        val held = artifacts.storedOf(workspaceId, id)?.view ?: throw DesignImageRefusedException("That picture is not here any more.")
        if (artifacts.catalogOf(workspaceId, id) != into.id) {
            if (!artifacts.fileIn(workspaceId, id, requireNotNull(into.id))) {
                throw DesignImageRefusedException("Only a picture on the Images page is filed in an image catalog.")
            }
            auditRecorder.record(workspaceId, WorkspaceAuditCategory.WORKFLOW, "Image ${held.name ?: held.filename} moved to ${into.name}")
        }
        return requireNotNull(artifacts.storedOf(workspaceId, id)).view.copy(catalogId = into.id.toString(), catalogName = into.name)
    }

    /** Every unsaved version of a picture thrown away, files and all. */
    @MutationMapping
    @Transactional
    fun discardDesignImageVersions(@Argument workspaceId: Long, @Argument id: String): Boolean {
        access.requireVisible(workspaceId)
        return discard(workspaceId, id) > 0
    }

    /**
     * What failed or was stopped over a picture or its versions, gone with
     * them: the history of changes now saved or thrown away. Asked before a
     * version is written over the picture, while it still says which it was.
     */
    private fun forgetUndrawn(workspaceId: Long, of: String) {
        val versions = images.findByWorkspaceIdAndVersionOf(workspaceId, of).map { "DESIGN-${it.id}" }
        jobs.findByWorkspaceIdAndSourceIdInOrderByRequestedAtDescIdDesc(workspaceId, versions + of)
            .filter { it.status == DesignImageJobStatus.FAILED || it.status == DesignImageJobStatus.STOPPED }
            .let(jobs::deleteAll)
    }

    private fun discard(workspaceId: Long, of: String): Int {
        forgetUndrawn(workspaceId, of)
        val versions = images.findByWorkspaceIdAndVersionOf(workspaceId, of)
        versions.forEach {
            images.delete(it)
            runCatching { store.remove(it.location) }
            layers.forget(workspaceId, "DESIGN-${it.id}")
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
            layers.pixelsOf(workspaceId, artifactId).map { DesignArchive.Layer(it.name, it.visible, it.picture, it.png) },
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
        val asOf = java.time.OffsetDateTime.now()
        val wait = estimates.waitOf(job, asOf)
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
            negativePrompt = job.negativePrompt,
            scale = job.scale,
            feather = job.feather,
            image = job.imageId?.let { images.findByIdOrNull(it) }?.let(artifacts::describe),
            progress = wait.percent,
            secondsLeft = wait.secondsLeft,
            typicalSeconds = wait.typicalSeconds,
            asOf = asOf.toString(),
        )
    }

    private fun currentName(): String {
        val authentication = SecurityContextHolder.getContext().authentication
        return authentication?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }?.name ?: "somebody"
    }

    private fun String?.orNullIfBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * A negative prompt as a request keeps it, refused here - before it waits -
     * on a model whose provider takes none, rather than left out of the drawing.
     */
    private fun negativeFor(model: LlmModelView, given: String?): String? {
        val asked = given.orNullIfBlank() ?: return null
        if (!model.takesNegativePrompt) throw DesignImageRefusedException(NegativePrompts.notTaken(model.name) + ".")
        val longest = settings.imageMaxPrompt()
        if (asked.length > longest) {
            throw DesignImageRefusedException("That negative prompt is too long to draw from; say it in under $longest characters.")
        }
        return asked
    }

    private companion object {
        /** The column's own width: a name is a title, not a description. */
        const val NAME_LENGTH = 255

        /** What an imported picture says it was drawn with: nothing here drew it. */
        const val IMPORTED = "Imported"

        /**
         * A WebUI's restoration models by name: what Clean up offers first. SCUNet
         * and SwinIR come with AUTOMATIC1111 and Forge; FBCNN and 1x models such
         * as 1x_JPEGDestroyer are added to its models folder. A name, because the
         * WebUI's list says nothing else about what a model does. Issue #640.
         */
        val RESTORING = Regex("scunet|swinir|fbcnn|jpeg|(^|[^0-9])1x", RegexOption.IGNORE_CASE)

        /** What a change the editor made itself says it was made with: no model made it. */
        const val IMAGE_EDITOR = "Image editor"

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
    /** What the picture must not contain; null or blank is the model's default. */
    val negativePrompt: String? = null,
    /** The image catalog the drawn picture is filed in; null is the workspace's default. */
    val catalogId: String? = null,
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
    /** What the change must not bring in; null or blank is the model's default. */
    val negativePrompt: String? = null,
    /** How far the selection's edge is softened, in the picture's own pixels; null is the installation's. */
    val feather: Int? = null,
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
    /** The image catalog to file it in; null is the workspace's default. */
    val catalogId: String? = null,
)

data class ImageEditorSettingsView(
    /** How wide the brush starts, in the picture's own pixels. */
    val brush: Int,
    /** The widest it may be made. */
    val brushMost: Int,
    /** The blur's radius it starts at, in the picture's own pixels. */
    val blur: Int,
    /** The largest radius it may be given. */
    val blurMost: Int,
    /** How far the blur fades out inside its selection's edge, to start with; 0 is a hard edge. */
    val blurFalloff: Int,
    /** The furthest it may fade. */
    val blurFalloffMost: Int,
    /** How far a Magic edit's edge is softened, to start with; 0 is a hard edge. */
    val feather: Int,
    /** The furthest it may be softened. */
    val featherMost: Int,
    /** How many layers a picture may keep over it. */
    val layersMost: Int,
    /** How opaque the brush starts, in percent: every tip but the marker. */
    val brushOpacity: Int,
    /** How hard the soft tip's and the eraser's edge starts, in percent. */
    val brushHardness: Int,
    /** How opaque the marker starts, in percent. */
    val markerOpacity: Int,
)

data class KeepDesignImageChangeInput(
    val workspaceId: String,
    /** The picture the change was made to, by its artifact id. */
    val imageId: String,
    /** The changed picture's file, base64: with layers, them flattened. */
    val image: String,
    /** What was done, as the editor's history says it. */
    val change: String,
    /** The layers it was made with, the picture itself first; null or empty keeps it flat. */
    val layers: List<DesignImageLayerInput>? = null,
)

data class DesignImageLayerInput(
    /** What it is called; null for the picture itself. */
    val name: String? = null,
    val visible: Boolean,
    /** The picture itself, under every layer: first, and only there. */
    val picture: Boolean = false,
    /** Its pixels, a PNG the picture's own size, base64. */
    val image: String,
)

data class DesignImageLayerView(
    val id: String,
    val name: String?,
    val visible: Boolean,
    val picture: Boolean,
    val url: String,
)

data class UpscaleAlgorithmsView(
    val names: List<String>,
    val preferred: String?,
    val reason: String?,
    /** Of [names], the restoration models Clean up offers: SCUNet, SwinIR, FBCNN and 1x models, by name. */
    val restorers: List<String>,
)

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
    /** What the redraw must not bring in; null or blank is the model's default. */
    val negativePrompt: String? = null,
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
    /** What it was asked not to contain; null is none given. */
    val negativePrompt: String? = null,
    /** How far an edit's selection edge was softened, in pixels; null for anything else. */
    val feather: Int? = null,
    val image: ArtifactView?,
    /** How far its WebUI says it is, 0 to 100; null where its server reports nothing, or it is not being drawn. */
    val progress: Int? = null,
    /** How many seconds its WebUI reckons are left, as of [asOf]; null where it says nothing. */
    val secondsLeft: Int? = null,
    /**
     * How long the same model's newest pictures of the same kind took, the
     * median in seconds; null with too few of them, or when it is not being drawn.
     */
    val typicalSeconds: Int? = null,
    /** The server's clock when this was read, to count from startedAt on the same clock. */
    val asOf: String? = null,
)

/*
 * Where the image editor's brush, blur fade and feather sliders end unless the
 * installation starts them wider - the ceilings those settings had before they
 * lost them, kept as the slider's length rather than as a limit.
 */
private const val BRUSH_SLIDER_REACH = 500
private const val FALLOFF_SLIDER_REACH = 500
private const val FEATHER_SLIDER_REACH = 256

/** Why nothing was drawn, in the words the page shows. */
class DesignImageRefusedException(reason: String) : RuntimeException(reason)
