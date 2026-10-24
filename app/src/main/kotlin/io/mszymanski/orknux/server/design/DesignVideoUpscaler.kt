package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.connector.model.ModelUpscaleClient
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.attachment.PictureFilenames
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

/**
 * Makes a finished video larger: its frames taken out, sent to an upscale
 * model's Stable Diffusion WebUI a batch at a time, and put back together at
 * the same rate with the same sound. The result is a version of the video - a
 * row of its own, [DesignVideo.upscaledFrom] naming the one it came from -
 * and the original stays as it was.
 *
 * Under the image queue's limit, not the video queue's: the work is the
 * upscale model's, the same server Upscale in the image editor asks, so a
 * video being upscaled holds one of [DesignDrawSlots] for as long as it runs,
 * and a picture waits behind it as it would behind another picture.
 *
 * Frames live in a directory of their own under the temporary directory, the
 * originals deleted as each batch comes back, so the heap holds one batch at a
 * time whatever the video's length. A server that stops while one is being
 * made starts it again from the beginning on its next start.
 */
@Service
class DesignVideoUpscaler(
    private val videos: DesignVideoRepository,
    private val upscaling: ModelUpscaleClient,
    private val store: AttachmentStore,
    private val settings: InstallationSettings,
    private val tuning: Tuning,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val slots: DesignDrawSlots,
) : SmartLifecycle {

    private val log = LoggerFactory.getLogger(javaClass)

    private val counter = AtomicInteger()

    private val workers: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "orknux-video-upscale-${counter.incrementAndGet()}").apply { isDaemon = true }
    }

    @Volatile
    private var running = false

    init {
        slots.whenFreed(::dispatch)
    }

    /** Queued: started once a slot is free, which is at once when nothing else is being drawn. */
    fun queued(video: DesignVideo) {
        log.info(
            "Design video {} queued to be made {}x larger in workspace {} by {} for {}",
            video.id, video.scale, video.workspaceId, video.modelName, video.requestedBy,
        )
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    // On a thread of its own: a claim made from inside the commit has no transaction to update in.
                    override fun afterCommit() = later(::dispatch)
                },
            )
        } else {
            dispatch()
        }
    }

    /** Starts what the shared limit allows, oldest first. */
    @Synchronized
    fun dispatch() {
        if (!running) return
        val waiting = videos.findByStatusAndUpscaledFromIsNotNullOrderByRequestedAtAscIdAsc(DesignVideoStatus.QUEUED).iterator()
        while (waiting.hasNext()) {
            val id = requireNotNull(waiting.next().id)
            if (!slots.take()) break
            // Another server sharing the database may have taken it first.
            if (videos.claim(id, OffsetDateTime.now()) != 1) {
                slots.giveBack()
                continue
            }
            try {
                workers.execute { upscale(id) }
            } catch (refused: RuntimeException) {
                slots.giveBack()
                videos.failed(id, "The server is stopping; ask again once it is back.", OffsetDateTime.now())
                log.warn("Design video {} could not be started", id, refused)
            }
        }
    }

    @OptIn(ExperimentalPathApi::class)
    private fun upscale(id: Long) {
        var work: Path? = null
        try {
            val video = videos.findByIdOrNull(id) ?: return
            if (video.status != DesignVideoStatus.GENERATING) return
            val modelId = video.modelId ?: return fail(id, "${video.modelName} has been removed.")
            if (!settings.attachmentsEnabled()) {
                return fail(id, "A video is kept as an attachment, and attachments are turned off for this installation.")
            }
            VideoFrames.unavailable()?.let { return fail(id, it) }
            val source = video.upscaledFrom?.let { videos.findByIdOrNull(it) }?.takeIf { it.workspaceId == video.workspaceId }
            val sourceLocation = source?.location?.takeIf { store.exists(it) }
                ?: return fail(id, "The video to make larger is not here any more.")
            val scale = video.scale ?: return fail(id, "It was asked for without a scale.")

            work = Files.createTempDirectory("orknux-video-upscale-$id-")
            val input = work.resolve("source")
            store.open(sourceLocation).use { Files.copy(it, input) }
            val probe = VideoFrames.probe(input)
            val (width, height) = upscaledSize(probe.width, probe.height, scale)
            val longest = tuning[Tunable.VIDEO_UPSCALE_MAX_PIXELS]
            if (maxOf(width, height) > longest) {
                return fail(id, "It would be $width × $height, longer than the $longest pixels an upscaled video may be.")
            }

            val raw = work.resolve("frames")
            val larger = work.resolve("larger")
            Files.createDirectories(larger)
            val total = VideoFrames.explode(input, raw)
            if (total == 0) return fail(id, "The video has no frames that could be read.")
            log.info(
                "Design video {} upscaling {} frames of video {} {}x to {}x{} by {} with {}",
                id, total, source.id, scale, width, height, video.upscaler ?: "its own algorithm", video.modelName,
            )
            videos.framed(id, 0, total, 0)

            val batch = tuning[Tunable.VIDEO_UPSCALE_BATCH_FRAMES]
            var next = 1
            while (next <= total) {
                if (stopped(id)) return
                val last = minOf(total, next + batch - 1)
                val sent = (next..last).map { Files.readAllBytes(VideoFrames.framePath(raw, it)) }
                when (val answer = upscaling.upscaleBatch(modelId, sent, video.upscaler, scale)) {
                    is ModelUpscaleClient.Pictures.Failed -> {
                        log.warn("Design video {} frames {}-{} were not upscaled by {}: {}", id, next, last, video.modelName, answer.reason)
                        return fail(id, answer.reason)
                    }
                    is ModelUpscaleClient.Pictures.Drawn ->
                        answer.images.forEachIndexed { offset, bytes -> Files.write(VideoFrames.framePath(larger, next + offset), bytes) }
                }
                // Sent and answered: the original frames are not needed again.
                (next..last).forEach { Files.deleteIfExists(VideoFrames.framePath(raw, it)) }
                // Held under 100 while it is put back together, which the page says is finishing.
                videos.framed(id, last, total, last * FINISHING / total)
                next = last + 1
            }
            if (stopped(id)) return

            val mp4 = source.contentType?.lowercase()?.startsWith(MP4_TYPE) == true
            val format = if (mp4) VideoFrames.MP4 else VideoFrames.WEBM
            val contentType = if (mp4) MP4_TYPE else WEBM_TYPE
            val out = work.resolve("upscaled.$format")
            VideoFrames.assemble(
                frames = larger,
                count = total,
                fps = probe.fps,
                width = width,
                height = height,
                audioFrom = input.takeIf { probe.hasAudio },
                out = out,
                format = format,
                bitrate = bitrateOf(Files.size(input), total, probe.width, probe.height, width, height, probe.fps),
            )
            val maxBytes = tuning[Tunable.VIDEO_MAX_MB].toLong() * 1024 * 1024
            val bytes = Files.size(out)
            if (bytes > maxBytes) return fail(id, "It came to ${bytes / (1024 * 1024)} MB, more than the ${tuning[Tunable.VIDEO_MAX_MB]} MB a video may be.")
            keep(video, out, contentType, VideoFrames.framePath(larger, 1), total / probe.fps)
        } catch (failure: Exception) {
            log.warn("Design video {} failed while being upscaled", id, failure)
            fail(id, failure.message ?: "The video could not be made larger.")
        } finally {
            work?.let { dir -> runCatching { dir.deleteRecursively() } }
            slots.release()
        }
    }

    /** The file kept where attachments are, with its first frame to show before it plays. */
    private fun keep(video: DesignVideo, file: Path, contentType: String, firstFrame: Path, duration: Double) {
        val id = requireNotNull(video.id)
        val filename = DesignVideoQueue.filenameOf(video.name, contentType)
        val bytes = Files.readAllBytes(file)
        val location = store.put(video.workspaceId, filename, bytes)
        val posterLocation = runCatching { store.put(video.workspaceId, PictureFilenames.of(video.name, PNG_TYPE), Files.readAllBytes(firstFrame)) }
            .getOrNull()
        val kept = videos.made(
            id = id,
            filename = filename,
            contentType = contentType,
            sizeBytes = bytes.size.toLong(),
            location = location,
            posterLocation = posterLocation,
            posterContentType = posterLocation?.let { PNG_TYPE },
            duration = duration,
            at = OffsetDateTime.now(),
        )
        if (kept != 1) {
            // Stopped as it was being filed: the row decides, and the file goes.
            runCatching { store.remove(location) }
            posterLocation?.let { runCatching { store.remove(it) } }
            log.info("Design video {} was stopped as it was upscaled; its file is thrown away", id)
            return
        }
        auditRecorder.recordAutomated(
            video.workspaceId,
            WorkspaceAuditCategory.WORKFLOW,
            "Video $filename made ${video.scale}x larger in Design with ${video.modelName}",
            video.requestedBy,
        )
        log.info("Design video {} upscaled by {}, {} bytes", id, video.modelName, bytes.size)
    }

    private fun stopped(id: Long): Boolean {
        val now = videos.findByIdOrNull(id)?.status
        if (now == DesignVideoStatus.GENERATING) return false
        log.info("Design video {} is {} and is not upscaled further", id, now ?: "gone")
        return true
    }

    /**
     * Somebody stopped [video]. One still queued is simply never claimed; one
     * being upscaled stops after the batch it is on, and its WebUI is asked to
     * stop that batch - an interrupt that stops whatever the WebUI is drawing,
     * as an image's does.
     */
    fun stopped(video: DesignVideo) {
        log.info("Design video {} stopped in workspace {} while {}", video.id, video.workspaceId, if (video.startedAt == null) "queued" else "upscaling")
        val modelId = video.modelId ?: return
        if (video.startedAt == null) return
        later {
            upscaling.interrupt(modelId)?.let { log.warn("Design video {} could not be interrupted at its WebUI: {}", video.id, it) }
        }
    }

    private fun fail(id: Long, reason: String) {
        videos.failed(id, reason, OffsetDateTime.now())
    }

    private fun later(work: () -> Unit) {
        try {
            workers.execute(work)
        } catch (refused: RuntimeException) {
            log.warn("Design video upscale work could not be started: the server is stopping", refused)
        }
    }

    override fun start() {
        running = true
        val left = videos.findByStatusAndUpscaledFromIsNotNullOrderByRequestedAtAscIdAsc(DesignVideoStatus.GENERATING)
        if (left.isNotEmpty()) {
            // Its frames went with the temporary directory: it starts again from the first.
            log.info("Queueing again {} design video upscale(s) that were being made when the server stopped", left.size)
            left.forEach {
                it.status = DesignVideoStatus.QUEUED
                it.startedAt = null
                it.progress = null
                it.framesDone = null
                it.framesTotal = null
            }
            videos.saveAll(left)
        }
        later(::dispatch)
    }

    override fun stop() {
        running = false
        workers.shutdownNow()
    }

    override fun isRunning(): Boolean = running

    companion object {
        private const val MP4_TYPE = "video/mp4"
        private const val WEBM_TYPE = "video/webm"
        private const val PNG_TYPE = "image/png"

        /** Where progress stands once every frame is back: the page reads it as finishing. */
        private const val FINISHING = 99

        /**
         * The least an encoder is asked for, in bits a second: below this VP8
         * and OpenH264 draw blocks rather than pictures, whatever the source.
         */
        private const val LEAST_BITRATE = 250_000

        /**
         * A video [scale] times larger, each side even - H.264 and VP8 code
         * colour at half resolution, and an odd side is refused.
         */
        fun upscaledSize(width: Int, height: Int, scale: Double): Pair<Int, Int> {
            fun side(of: Int) = maxOf(2, (of * scale).toInt() / 2 * 2)
            return side(width) to side(height)
        }

        /**
         * The bitrate the source spent on each pixel of each frame, spent on
         * the larger one: an upscale keeps the quality it was given rather than
         * choosing one of its own.
         */
        fun bitrateOf(sourceBytes: Long, frames: Int, width: Int, height: Int, toWidth: Int, toHeight: Int, fps: Double): Int {
            val perPixel = sourceBytes * 8.0 / (frames.toDouble() * width * height).coerceAtLeast(1.0)
            return (perPixel * toWidth * toHeight * fps).coerceIn(LEAST_BITRATE.toDouble(), Int.MAX_VALUE.toDouble()).toInt()
        }
    }
}
