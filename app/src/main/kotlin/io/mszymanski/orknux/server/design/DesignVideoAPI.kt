package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.connector.model.ModelKind
import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.connector.model.ModelVideoClient
import io.mszymanski.orknux.connector.model.VideoSetting
import io.mszymanski.orknux.connector.model.VideoSettingKind
import io.mszymanski.orknux.connector.model.VideoSettingNames
import io.mszymanski.orknux.connector.model.VideoSettings
import io.mszymanski.orknux.server.attachment.AttachmentDownloads
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.attachment.PictureFilenames
import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.workflow.WorkspaceArtifactAPI
import io.mszymanski.orknux.server.workspace.ModelNotFoundForWorkspaceException
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.InputStreamResource
import org.springframework.core.io.Resource
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.ResponseBody
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Design's Videos page: asking a video model for a video, the list of what
 * was asked for and made, and serving the files. Issue #658.
 *
 * Asking queues and answers at once; [DesignVideoQueue] asks the backend and
 * keeps asking about it. Straight to the model chosen, as the image editor is,
 * with exactly the settings that model said it takes - a setting it does not
 * take is refused here rather than sent and silently dropped.
 */
@Controller
class DesignVideoAPI(
    private val videos: DesignVideoRepository,
    private val queue: DesignVideoQueue,
    private val models: ModelService,
    private val client: ModelVideoClient,
    private val store: AttachmentStore,
    private val settings: InstallationSettings,
    private val downloads: AttachmentDownloads,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val artifacts: WorkspaceArtifactAPI,
    private val upscaler: DesignVideoUpscaler,
    private val tuning: Tuning,
    private val exports: DesignVideoExports,
    /** The video catalogs a video is filed in. */
    private val catalogs: DesignCatalogs,
) {

    /** What one video model takes beyond a prompt, asked of its backend now. */
    @QueryMapping
    fun designVideoSettings(@Argument workspaceId: Long, @Argument modelId: Long): DesignVideoSettingsView {
        access.requireVisible(workspaceId)
        val model = models.model(modelId)?.takeIf { it.workspaceId == workspaceId }
            ?: throw ModelNotFoundForWorkspaceException(modelId)
        if (model.kind != ModelKind.VIDEO) throw DesignVideoRefusedException("${model.name} is not a video model.")
        return when (val taken = client.settings(modelId)) {
            is VideoSettings.Taken -> DesignVideoSettingsView(taken.settings.map(::settingView), null)
            is VideoSettings.Failed -> DesignVideoSettingsView(emptyList(), taken.reason)
        }
    }

    /** The workspace's videos, newest first - made, on their way, failed or stopped - matching [search] where one is given. */
    @QueryMapping
    @Transactional
    fun designVideos(@Argument workspaceId: Long, @Argument search: String?, @Argument catalogId: Long?): List<DesignVideoView> {
        access.requireVisible(workspaceId)
        val hunted = search?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val all = videos.findByWorkspaceIdOrderByRequestedAtDescIdDesc(workspaceId)
        val queued = queuedOrder()
        val named = filing(workspaceId)
        return all
            .filter { hunted == null || it.name.lowercase().contains(hunted) || it.prompt.lowercase().contains(hunted) }
            .filter { catalogId == null || (it.catalogId ?: named.first) == catalogId }
            .map { view(it, queued, named) }
    }

    /** One video, in whatever state it is; null for one not here. */
    @QueryMapping
    fun designVideo(@Argument workspaceId: Long, @Argument id: Long): DesignVideoView? {
        access.requireVisible(workspaceId)
        return videos.findByIdOrNull(id)?.takeIf { it.workspaceId == workspaceId }?.let { view(it, queuedOrder()) }
    }

    /** Refused here, before it waits, for anything that could never be made. */
    @MutationMapping
    @Transactional
    fun queueDesignVideo(@Argument input: QueueDesignVideoInput): DesignVideoView {
        val workspaceId = input.workspaceId.toLong()
        access.requireVisible(workspaceId)
        val modelId = input.modelId.toLong()
        val model = models.model(modelId)?.takeIf { it.workspaceId == workspaceId }
            ?: throw ModelNotFoundForWorkspaceException(modelId)
        if (model.kind != ModelKind.VIDEO) throw DesignVideoRefusedException("${model.name} is not a video model.")

        val prompt = input.prompt.trim()
        if (prompt.isEmpty()) throw DesignVideoRefusedException("Say what the video should be of.")
        val longest = settings.imageMaxPrompt()
        if (prompt.length > longest) {
            throw DesignVideoRefusedException("That description is too long to make a video from; say it in under $longest characters.")
        }
        if (!settings.attachmentsEnabled()) {
            throw DesignVideoRefusedException("A video is kept as an attachment, and attachments are turned off for this installation.")
        }
        val called = input.name?.trim()?.takeIf { it.isNotEmpty() }
        if (called != null && called.length > NAME_LENGTH) {
            throw DesignVideoRefusedException("A video's name is at most $NAME_LENGTH characters.")
        }

        // What the model takes, asked of it now: a setting outside that is refused, never sent and ignored.
        val taken = when (val asked = client.settings(modelId)) {
            is VideoSettings.Taken -> asked.settings.associateBy { it.name }
            is VideoSettings.Failed -> throw DesignVideoRefusedException(asked.reason)
        }
        fun number(name: String, value: Long?): Long? {
            value ?: return null
            val setting = taken[name] ?: throw DesignVideoSettingNotTakenException(model.name, name)
            if ((setting.least != null && value < setting.least!!) || (setting.most != null && value > setting.most!!)) {
                throw DesignVideoRefusedException("$value is outside what ${model.name} takes for $name: ${setting.least} to ${setting.most}.")
            }
            return value
        }
        fun choice(name: String, value: String?): String? {
            val chosen = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val setting = taken[name] ?: throw DesignVideoSettingNotTakenException(model.name, name)
            if (setting.kind == VideoSettingKind.CHOICE && chosen !in setting.choices) {
                throw DesignVideoRefusedException("${model.name} takes $name as one of ${setting.choices.joinToString(", ")}.")
            }
            return chosen
        }
        val startImageId = input.startImageId?.trim()?.takeIf { it.isNotEmpty() }
        if (startImageId != null) {
            if (VideoSettingNames.START_IMAGE !in taken) throw DesignVideoSettingNotTakenException(model.name, VideoSettingNames.START_IMAGE)
            val start = artifacts.storedOf(workspaceId, startImageId)
                ?: throw DesignVideoRefusedException("The picture to start from is not here any more.")
            if (!start.contentType.startsWith("image/")) throw DesignVideoRefusedException("A video starts from a picture, and that file is not one.")
        }

        val video = videos.save(
            DesignVideo(
                workspaceId = workspaceId,
                modelId = modelId,
                modelName = model.name,
                prompt = prompt,
                name = called ?: PictureFilenames.nameOf(prompt),
                seconds = choice(VideoSettingNames.SECONDS, input.seconds?.toString())?.toInt(),
                size = choice(VideoSettingNames.SIZE, input.size),
                frames = number(VideoSettingNames.FRAMES, input.frames?.toLong())?.toInt(),
                fps = number(VideoSettingNames.FPS, input.fps?.toLong())?.toInt(),
                width = number(VideoSettingNames.WIDTH, input.width?.toLong())?.toInt(),
                height = number(VideoSettingNames.HEIGHT, input.height?.toLong())?.toInt(),
                seed = number(VideoSettingNames.SEED, seedOf(input.seed)),
                startImageId = startImageId,
                requestedBy = currentName(),
                catalogId = catalogs.resolve(workspaceId, DesignCatalogKind.VIDEO, input.catalogId?.toLongOrNull()),
            ),
        )
        auditRecorder.record(workspaceId, WorkspaceAuditCategory.WORKFLOW, "Video queued in Design with ${model.name}")
        queue.queued(video)
        return view(video, queuedOrder())
    }

    /**
     * A finished video made larger by an upscale model: every frame through
     * the model's WebUI, put back together at the same rate with its sound.
     * Kept as a version of the video, which stays as it was. Refused here for
     * anything that could never be made - a video not finished, a model that
     * does not upscale, a result larger than the installation allows.
     */
    @MutationMapping
    @Transactional
    fun queueDesignVideoUpscale(@Argument input: UpscaleDesignVideoInput): DesignVideoView {
        val workspaceId = input.workspaceId.toLong()
        access.requireVisible(workspaceId)
        val modelId = input.modelId.toLong()
        val model = models.model(modelId)?.takeIf { it.workspaceId == workspaceId }
            ?: throw ModelNotFoundForWorkspaceException(modelId)
        if (model.kind != ModelKind.UPSCALE) throw DesignVideoRefusedException("${model.name} is not an upscale model.")
        if (input.scale !in 1.0..8.0) throw DesignVideoRefusedException("A video can be made 1 to 8 times larger.")
        if (!settings.attachmentsEnabled()) {
            throw DesignVideoRefusedException("A video is kept as an attachment, and attachments are turned off for this installation.")
        }
        val source = videos.findByIdOrNull(input.videoId.toLong())?.takeIf { it.workspaceId == workspaceId }
            ?: throw DesignVideoRefusedException("The video to make larger is not here any more.")
        val location = source.location?.takeIf { source.status == DesignVideoStatus.DONE && store.exists(it) }
            ?: throw DesignVideoRefusedException("Only a finished video can be made larger.")
        VideoFrames.unavailable()?.let { throw DesignVideoRefusedException(it) }
        val probe = probed(location)
        val (width, height) = DesignVideoUpscaler.upscaledSize(probe.width, probe.height, input.scale)
        val longest = tuning[Tunable.VIDEO_UPSCALE_MAX_PIXELS]
        if (maxOf(width, height) > longest) throw DesignVideoTooLargeException(width, height, longest)

        val video = videos.save(
            DesignVideo(
                workspaceId = workspaceId,
                modelId = modelId,
                modelName = model.name,
                prompt = source.prompt,
                name = source.name,
                frames = source.frames,
                fps = Math.round(probe.fps).toInt(),
                width = width,
                height = height,
                requestedBy = currentName(),
                versionOf = source.versionOf ?: source.id,
                upscaledFrom = source.id,
                upscaler = input.algorithm?.trim()?.takeIf { it.isNotEmpty() },
                scale = input.scale,
                // A version of the video, so filed where the video is.
                catalogId = source.catalogId,
            ),
        )
        auditRecorder.record(
            workspaceId,
            WorkspaceAuditCategory.WORKFLOW,
            "Video ${source.name} queued to be made ${input.scale}x larger in Design with ${model.name}",
        )
        upscaler.queued(video)
        return view(video, queuedOrder())
    }

    /** What a stored video is, read off a copy of its file: the store hands out streams, and FFmpeg wants a file it can seek. */
    private fun probed(location: String): VideoFrames.Probe {
        val copy = Files.createTempFile("orknux-video-probe-", ".video")
        return try {
            store.open(location).use { Files.copy(it, copy, StandardCopyOption.REPLACE_EXISTING) }
            VideoFrames.probe(copy)
        } catch (failure: Exception) {
            throw DesignVideoRefusedException("The video could not be read: ${failure.message ?: failure.javaClass.simpleName}")
        } finally {
            runCatching { Files.deleteIfExists(copy) }
        }
    }

    /**
     * Stops a video waiting or being made. One waiting is never started; one
     * being made is cancelled at its backend where the backend can be asked
     * to, and whatever comes of it is not kept.
     */
    @MutationMapping
    fun stopDesignVideo(@Argument workspaceId: Long, @Argument id: Long): DesignVideoView {
        access.requireVisible(workspaceId)
        val video = videos.findByIdOrNull(id)?.takeIf { it.workspaceId == workspaceId }
            ?: throw DesignVideoRefusedException("That video is not here any more.")
        if (videos.stop(id, java.time.OffsetDateTime.now()) != 1) {
            throw DesignVideoRefusedException("This video is not waiting or being made any more.")
        }
        val stopped = videos.findByIdOrNull(id) ?: video
        if (stopped.upscaledFrom != null) upscaler.stopped(stopped) else queue.stopped(stopped)
        auditRecorder.record(workspaceId, WorkspaceAuditCategory.WORKFLOW, "Video ${video.name} stopped in Design")
        return view(stopped, queuedOrder())
    }

    /**
     * Files a video in another of its workspace's video catalogs - and the
     * versions made of it with it, which are listed beside it wherever it is.
     */
    @MutationMapping
    @Transactional
    fun moveDesignVideo(@Argument workspaceId: Long, @Argument id: Long, @Argument catalogId: Long): DesignVideoView {
        access.requireVisible(workspaceId)
        val video = videos.findByIdOrNull(id)?.takeIf { it.workspaceId == workspaceId }
            ?: throw DesignVideoRefusedException("That video is not here any more.")
        val into = catalogs.catalog(workspaceId, DesignCatalogKind.VIDEO, catalogId)
        val root = video.versionOf ?: requireNotNull(video.id)
        val family = videos.findByWorkspaceIdOrderByRequestedAtDescIdDesc(workspaceId).filter { it.id == root || it.versionOf == root }
        if (family.any { it.catalogId != into.id }) {
            family.forEach { it.catalogId = into.id }
            videos.saveAll(family)
            auditRecorder.record(workspaceId, WorkspaceAuditCategory.WORKFLOW, "Video ${video.name} moved to ${into.name}")
        }
        return view(requireNotNull(videos.findByIdOrNull(id)), queuedOrder())
    }

    /** A video taken off the page, its file with it. Not one being made: stop it first. */
    @MutationMapping
    @Transactional
    fun removeDesignVideo(@Argument workspaceId: Long, @Argument id: Long): Boolean {
        access.requireVisible(workspaceId)
        val video = videos.findByIdOrNull(id)?.takeIf { it.workspaceId == workspaceId } ?: return false
        if (video.status == DesignVideoStatus.GENERATING) {
            throw DesignVideoRefusedException("This video is being made; stop it before removing it.")
        }
        videos.delete(video)
        exports.forget(id)
        video.location?.let { runCatching { store.remove(it) } }
        video.posterLocation?.let { runCatching { store.remove(it) } }
        auditRecorder.record(workspaceId, WorkspaceAuditCategory.WORKFLOW, "Video ${video.filename ?: video.name} removed from Design")
        return true
    }

    /**
     * A finished video converted to [format] - a GIF without its sound, an MP4
     * or an MKV with it - and kept to be downloaded from the address answered.
     * Converted here, while the person waits, so a refusal is said where they
     * asked rather than as a broken download.
     */
    @MutationMapping
    fun exportDesignVideo(@Argument workspaceId: Long, @Argument id: Long, @Argument format: DesignVideoExportFormat): DesignVideoExportView {
        access.requireVisible(workspaceId)
        val video = videos.findByIdOrNull(id)?.takeIf { it.workspaceId == workspaceId }
            ?: throw DesignVideoRefusedException("That video is not here any more.")
        if (video.status != DesignVideoStatus.DONE || video.location?.let(store::exists) != true) throw DesignVideoExportUnfinishedException()
        VideoFrames.unavailable()?.let { throw DesignVideoRefusedException(it) }
        val exported = exports.export(video, format)
        auditRecorder.record(workspaceId, WorkspaceAuditCategory.WORKFLOW, "Video ${video.name} exported as ${format.name} from Design")
        return DesignVideoExportView(
            url = "/api/design-videos/$id/export/${format.extension}",
            filename = exported.filename,
            contentType = exported.contentType,
            sizeBytes = exported.sizeBytes.toDouble(),
        )
    }

    /**
     * An export's file, as a download named after the video. Kept from
     * [exportDesignVideo]; converted again where it is not - another server,
     * or one let go since.
     */
    @GetMapping("/api/design-videos/{id}/export/{extension}")
    @ResponseBody
    fun exported(@PathVariable id: Long, @PathVariable extension: String): ResponseEntity<Resource> {
        val format = DesignVideoExportFormat.entries.firstOrNull { it.extension == extension.lowercase() }
            ?: return ResponseEntity.notFound().build()
        val video = videos.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?.takeIf { it.status == DesignVideoStatus.DONE && it.location?.let(store::exists) == true }
            ?: return ResponseEntity.notFound().build()
        if (VideoFrames.unavailable() != null) return ResponseEntity.notFound().build()
        val exported = try {
            exports.export(video, format)
        } catch (refused: RuntimeException) {
            return ResponseEntity.status(UNPROCESSABLE).contentType(MediaType.TEXT_PLAIN)
                .body(ByteArrayResource((refused.message ?: "").toByteArray(Charsets.UTF_8)))
        }
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(exported.contentType))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(exported.filename, Charsets.UTF_8).build().toString())
            .header(HttpHeaders.CONTENT_LENGTH, exported.sizeBytes.toString())
            .header("X-Content-Type-Options", "nosniff")
            .body(InputStreamResource(Files.newInputStream(exported.file)))
    }

    /** The file, to anybody who can see the workspace; answered as missing otherwise. */
    @GetMapping("/api/design-videos/{id}")
    @ResponseBody
    fun download(@PathVariable id: Long): ResponseEntity<InputStreamResource> {
        val video = videos.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
        val location = video?.location?.takeIf { store.exists(it) } ?: return ResponseEntity.notFound().build()
        return downloads.serve(
            filename = requireNotNull(video.filename),
            contentType = video.contentType ?: "application/octet-stream",
            sizeBytes = video.sizeBytes ?: 0,
            location = location,
        )
    }

    /** The picture shown for a video before it plays. */
    @GetMapping("/api/design-videos/{id}/poster")
    @ResponseBody
    fun poster(@PathVariable id: Long): ResponseEntity<InputStreamResource> {
        val video = videos.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
        val location = video?.posterLocation?.takeIf { store.exists(it) } ?: return ResponseEntity.notFound().build()
        val type = video.posterContentType ?: "image/png"
        return downloads.serve(
            filename = PictureFilenames.of(video.name, type),
            contentType = type,
            sizeBytes = runCatching { store.open(location).use { it.readAllBytes().size.toLong() } }.getOrDefault(0),
            location = location,
        )
    }

    /** Where each queued video stands in the installation's queue, which is the one the limit is counted over. */
    private fun queuedOrder(): Map<Long, Int> {
        // Two queues: videos a model makes wait for videos, upscales for the image queue's slots.
        val made = videos.findByStatusAndUpscaledFromIsNullOrderByRequestedAtAscIdAsc(DesignVideoStatus.QUEUED)
        val upscaled = videos.findByStatusAndUpscaledFromIsNotNullOrderByRequestedAtAscIdAsc(DesignVideoStatus.QUEUED)
        return listOf(made, upscaled).flatMap { queue -> queue.mapIndexedNotNull { index, video -> video.id?.let { it to index } } }.toMap()
    }

    /** The workspace's default video catalog, and every video catalog's name by id. */
    private fun filing(workspaceId: Long): Pair<Long, Map<Long, String>> =
        requireNotNull(catalogs.defaultOf(workspaceId, DesignCatalogKind.VIDEO).id) to catalogs.names(workspaceId, DesignCatalogKind.VIDEO)

    private fun view(
        video: DesignVideo,
        queued: Map<Long, Int>,
        named: Pair<Long, Map<Long, String>> = filing(video.workspaceId),
    ): DesignVideoView {
        val id = requireNotNull(video.id)
        val filed = video.catalogId ?: named.first
        val start = video.startImageId?.let { artifacts.storedOf(video.workspaceId, it) }?.view
        return DesignVideoView(
            id = id.toString(),
            status = video.status,
            name = video.name,
            prompt = video.prompt,
            modelId = video.modelId?.toString(),
            model = video.modelName,
            seconds = video.seconds,
            size = video.size,
            frames = video.frames,
            fps = video.fps,
            width = video.width,
            height = video.height,
            seed = video.seed?.toString(),
            startImageId = video.startImageId,
            startImageUrl = start?.url,
            requestedBy = video.requestedBy,
            requestedAt = video.requestedAt.toString(),
            startedAt = video.startedAt?.toString(),
            finishedAt = video.finishedAt?.toString(),
            reason = video.reason,
            progress = video.progress,
            progressSamplingOnly = video.remoteId?.startsWith(ModelVideoClient.SDCPP_PREFIX) == true,
            asOf = java.time.OffsetDateTime.now().toString(),
            ahead = if (video.status == DesignVideoStatus.QUEUED) queued[id] else null,
            url = video.location?.let { "/api/design-videos/$id" },
            posterUrl = video.posterLocation?.let { "/api/design-videos/$id/poster" },
            contentType = video.contentType,
            sizeBytes = video.sizeBytes?.toInt(),
            durationSeconds = video.durationSeconds,
            versionOf = video.versionOf?.toString(),
            upscaledFrom = video.upscaledFrom?.toString(),
            upscaler = video.upscaler,
            scale = video.scale,
            framesDone = video.framesDone,
            framesTotal = video.framesTotal,
            catalogId = filed.toString(),
            catalogName = named.second[filed].orEmpty(),
        )
    }

    private fun seedOf(text: String?): Long? {
        val said = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return said.toLongOrNull() ?: throw DesignVideoRefusedException("A seed is a whole number.")
    }

    private fun settingView(setting: VideoSetting) =DesignVideoSettingView(
        name = setting.name,
        kind = setting.kind,
        choices = setting.choices,
        least = setting.least?.toString(),
        most = setting.most?.toString(),
        preset = setting.preset,
    )

    private fun currentName(): String {
        val authentication = SecurityContextHolder.getContext().authentication
        return authentication?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }?.name ?: "somebody"
    }

    private companion object {
        /** The column's own width: a name is a title, not a description. */
        const val NAME_LENGTH = 255

        /** What a download that could not be made answers: the request was understood, and refused. */
        const val UNPROCESSABLE = 422
    }
}

data class QueueDesignVideoInput(
    val workspaceId: String,
    /** A VIDEO model of this workspace. */
    val modelId: String,
    val prompt: String,
    val name: String? = null,
    val seconds: Int? = null,
    val size: String? = null,
    val frames: Int? = null,
    val fps: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** A whole number as text: a backend's seed is 64 bits, wider than a GraphQL Int. */
    val seed: String? = null,
    /** A picture of the workspace's to start from, by its artifact id. */
    val startImageId: String? = null,
    /** The video catalog to file it in; null is the workspace's default. */
    val catalogId: String? = null,
)

data class DesignVideoSettingView(
    val name: String,
    val kind: VideoSettingKind,
    val choices: List<String>,
    /** Bounds as text, for the same reason as a seed. */
    val least: String?,
    val most: String?,
    val preset: String?,
)

data class DesignVideoSettingsView(val settings: List<DesignVideoSettingView>, val reason: String?)

data class DesignVideoView(
    val id: String,
    val status: DesignVideoStatus,
    val name: String,
    val prompt: String,
    val modelId: String?,
    val model: String,
    val seconds: Int?,
    val size: String?,
    val frames: Int?,
    val fps: Int?,
    val width: Int?,
    val height: Int?,
    val seed: String?,
    val startImageId: String?,
    val startImageUrl: String?,
    val requestedBy: String,
    val requestedAt: String,
    val startedAt: String?,
    val finishedAt: String?,
    val reason: String?,
    val progress: Int?,
    /**
     * Whether [progress] counts only the sampling steps. stable-diffusion.cpp's job
     * reports the step of the pass it is sampling and nothing after it, so the VAE
     * decode that follows the last step - about as long again for Wan - is not in
     * it; OpenAI's progress is the whole job's.
     */
    val progressSamplingOnly: Boolean,
    /** This server's clock when the video was read, so a page can count from [startedAt] on the same clock. */
    val asOf: String,
    val ahead: Int?,
    val url: String?,
    val posterUrl: String?,
    val contentType: String?,
    val sizeBytes: Int?,
    val durationSeconds: Double?,
    /** The video this is a version of; null for one a video model made. */
    val versionOf: String? = null,
    /** The video an upscale took its frames from; null for anything else. */
    val upscaledFrom: String? = null,
    val upscaler: String? = null,
    val scale: Double? = null,
    /** How many of an upscale's frames are done, of [framesTotal]; null until it has counted them. */
    val framesDone: Int? = null,
    val framesTotal: Int? = null,
    /** The catalog it is filed in: the default where it was never filed. A version is in its original's. */
    val catalogId: String = "",
    val catalogName: String = "",
)

/** Where an export is downloaded from, and what it is. */
data class DesignVideoExportView(
    val url: String,
    val filename: String,
    val contentType: String,
    /** A Float rather than an Int: a GraphQL Int stops at 2 GB. */
    val sizeBytes: Double,
)

data class UpscaleDesignVideoInput(
    val workspaceId: String,
    /** A finished video of this workspace. */
    val videoId: String,
    /** An UPSCALE model of this workspace. */
    val modelId: String,
    /** One of the WebUI's algorithms; null or blank is the model's own. */
    val algorithm: String? = null,
    val scale: Double,
)

/** Why no video was asked for, in the words the page shows. */
class DesignVideoRefusedException(reason: String) : RuntimeException(reason)

/** An upscale whose result would be larger than the installation lets a video be made. */
class DesignVideoTooLargeException(val width: Int, val height: Int, val longest: Int) : RuntimeException(
    "It would be $width × $height, longer than the $longest pixels an upscaled video may be.",
), Refusal {
    override val arguments get() = mapOf("width" to width, "height" to height, "longest" to longest)
}

/** A setting the chosen model does not take: refused, never sent and dropped. */
class DesignVideoSettingNotTakenException(val model: String, val setting: String) : RuntimeException(
    "$model does not take $setting.",
), Refusal {
    override val arguments get() = mapOf("model" to model, "setting" to setting)
}
