package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.connector.model.ModelVideoClient
import io.mszymanski.orknux.connector.model.VideoAsk
import io.mszymanski.orknux.connector.model.VideoStart
import io.mszymanski.orknux.connector.model.VideoState
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.attachment.PictureFilenames
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.workflow.WorkspaceArtifactAPI
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Makes the videos asked for on Design's Videos page, in the order they were
 * asked for, as many at once as Admin > Settings allows. Issue #658.
 *
 * A video is a job its backend runs, so this does two things for each: asks
 * for it, which answers at once with the backend's id, and then asks about that
 * id every few seconds until the file is there - keeping how far it has got on
 * the row, which is what the page shows. Off the request that asked, because
 * a video takes minutes and the page may be long gone.
 *
 * The row is the whole of the state. A server that stops while a video is
 * being made finds it on start: one the backend had already taken is asked
 * about again - the backend went on making it - and one it had not is queued
 * again.
 */
@Service
class DesignVideoQueue(
    private val videos: DesignVideoRepository,
    private val client: ModelVideoClient,
    private val store: AttachmentStore,
    private val settings: InstallationSettings,
    private val tuning: Tuning,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val artifacts: WorkspaceArtifactAPI,
) : SmartLifecycle {

    private val log = LoggerFactory.getLogger(javaClass)

    private val counter = AtomicInteger()

    private val workers: ScheduledExecutorService = Executors.newScheduledThreadPool(WORKERS) { runnable ->
        Thread(runnable, "orknux-video-${counter.incrementAndGet()}").apply { isDaemon = true }
    }

    @Volatile
    private var running = false

    /** Queued: started once its turn comes, which is at once when nothing else is being made. */
    fun queued(video: DesignVideo) {
        log.info("Design video {} queued in workspace {}: {} for {}", video.id, video.workspaceId, video.modelName, video.requestedBy)
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

    /** Starts what the limit allows, oldest first. The limit counts rows, so it holds across servers and restarts. */
    @Synchronized
    fun dispatch() {
        if (!running) return
        // An upscale is the image queue's (DesignVideoUpscaler): it is neither counted nor taken here.
        var making = videos.findByStatusAndUpscaledFromIsNullOrderByRequestedAtAscIdAsc(DesignVideoStatus.GENERATING).size
        val waiting = videos.findByStatusAndUpscaledFromIsNullOrderByRequestedAtAscIdAsc(DesignVideoStatus.QUEUED).iterator()
        while (making < tuning[Tunable.VIDEOS_AT_ONCE] && waiting.hasNext()) {
            val id = requireNotNull(waiting.next().id)
            if (videos.claim(id, OffsetDateTime.now()) != 1) continue
            making++
            later { begin(id) }
        }
    }

    /** Asks the backend for the video, and starts asking about it. */
    private fun begin(id: Long) {
        try {
            val video = videos.findByIdOrNull(id) ?: return
            if (video.status != DesignVideoStatus.GENERATING) return
            val modelId = video.modelId ?: return finish(id, "${video.modelName} has been removed.")
            if (!settings.attachmentsEnabled()) {
                return finish(id, "A video is kept as an attachment, and attachments are turned off for this installation.")
            }
            val start = video.startImageId?.let { artifacts.storedOf(video.workspaceId, it) }
            if (video.startImageId != null && start == null) return finish(id, "The picture to start from is not here any more.")
            val ask = VideoAsk(
                prompt = video.prompt,
                seconds = video.seconds,
                size = video.size,
                frames = video.frames,
                fps = video.fps,
                width = video.width,
                height = video.height,
                seed = video.seed,
                startImage = start?.let { store.open(it.location).use { stream -> stream.readBytes() } },
                startImageType = start?.contentType,
            )
            log.info("Design video {} asked of {}", id, video.modelName)
            when (val started = client.start(modelId, ask)) {
                is VideoStart.Failed -> {
                    log.warn("Design video {} was not started by {}: {}", id, video.modelName, started.reason)
                    finish(id, started.reason)
                }
                is VideoStart.Started -> {
                    // Stopped while it was being asked for: the backend is told, and nothing is kept.
                    if (videos.progressed(id, started.remoteId, 0) != 1) {
                        client.cancel(modelId, started.remoteId)
                        return
                    }
                    schedule(id)
                }
            }
        } catch (failure: Exception) {
            log.warn("Design video {} failed while being asked for", id, failure)
            finish(id, failure.message ?: "The video could not be asked for.")
        }
    }

    private fun schedule(id: Long) {
        if (!running) return
        try {
            workers.schedule({ poll(id) }, tuning[Tunable.VIDEO_POLL_SECONDS].toLong(), TimeUnit.SECONDS)
        } catch (refused: RuntimeException) {
            log.warn("Design video {} could not be asked about again: the server is stopping", id, refused)
        }
    }

    /** Asks the backend about one video once, and again later unless it is settled. */
    private fun poll(id: Long) {
        try {
            val video = videos.findByIdOrNull(id) ?: return
            val modelId = video.modelId
            val remoteId = video.remoteId
            if (video.status == DesignVideoStatus.STOPPED) {
                if (modelId != null && remoteId != null) client.cancel(modelId, remoteId)?.let { log.warn("Design video {} could not be cancelled: {}", id, it) }
                return
            }
            if (video.status != DesignVideoStatus.GENERATING) return
            if (modelId == null || remoteId == null) return finish(id, "${video.modelName} has been removed.")
            val since = video.startedAt ?: video.requestedAt
            val longest = Duration.ofMinutes(tuning[Tunable.VIDEO_LONGEST_MINUTES].toLong())
            if (Duration.between(since, OffsetDateTime.now()) > longest) {
                client.cancel(modelId, remoteId)
                return finish(id, "It was not made within ${longest.toMinutes()} minutes.")
            }
            val maxBytes = tuning[Tunable.VIDEO_MAX_MB].toLong() * 1024 * 1024
            val millis = Duration.between(since, OffsetDateTime.now()).toMillis()
            when (val state = client.poll(modelId, remoteId, maxBytes, millis)) {
                is VideoState.Waiting -> {
                    videos.progressed(id, remoteId, state.progress)
                    schedule(id)
                }
                is VideoState.Failed -> {
                    log.warn("Design video {} was not made by {}: {}", id, video.modelName, state.reason)
                    finish(id, state.reason)
                }
                is VideoState.Made -> keep(video, state)
            }
        } catch (failure: Exception) {
            // One ask going wrong is not the video going wrong; it is asked about again.
            log.warn("Design video {} could not be asked about", id, failure)
            schedule(id)
        }
    }

    /** The file kept where attachments are, with a picture to show for it. */
    private fun keep(video: DesignVideo, made: VideoState.Made) {
        val id = requireNotNull(video.id)
        val filename = filenameOf(video.name, made.contentType)
        val location = store.put(video.workspaceId, filename, made.video)
        // The backend's own poster; otherwise the picture it started from, which is its first frame.
        val poster = made.poster?.let { it to (made.posterType ?: "image/png") }
            ?: video.startImageId?.let { artifacts.storedOf(video.workspaceId, it) }
                ?.let { stored -> store.open(stored.location).use { it.readBytes() } to stored.contentType }
        val posterLocation = poster?.let { (bytes, type) -> store.put(video.workspaceId, PictureFilenames.of(video.name, type), bytes) }
        val kept = videos.made(
            id = id,
            filename = filename,
            contentType = made.contentType,
            sizeBytes = made.video.size.toLong(),
            location = location,
            posterLocation = posterLocation,
            posterContentType = poster?.second,
            duration = made.durationSeconds,
            at = OffsetDateTime.now(),
        )
        if (kept != 1) {
            // Stopped as it was being collected: the row decides, and the file goes.
            runCatching { store.remove(location) }
            posterLocation?.let { runCatching { store.remove(it) } }
            log.info("Design video {} was stopped as it was made; its file is thrown away", id)
        } else {
            auditRecorder.recordAutomated(
                video.workspaceId,
                WorkspaceAuditCategory.WORKFLOW,
                "Video $filename made in Design with ${video.modelName}",
                video.requestedBy,
            )
            log.info("Design video {} made by {}, {} bytes", id, video.modelName, made.video.size)
        }
        dispatch()
    }

    /** Somebody stopped [video]: a backend already making it is told, on a thread of its own. */
    fun stopped(video: DesignVideo) {
        log.info("Design video {} stopped in workspace {}", video.id, video.workspaceId)
        val modelId = video.modelId ?: return
        val remoteId = video.remoteId ?: return
        later {
            client.cancel(modelId, remoteId)?.let { log.warn("Design video {} could not be cancelled at its server: {}", video.id, it) }
            dispatch()
        }
    }

    private fun finish(id: Long, reason: String) {
        videos.failed(id, reason, OffsetDateTime.now())
        later(::dispatch)
    }

    private fun later(work: () -> Unit) {
        try {
            workers.execute(work)
        } catch (refused: RuntimeException) {
            log.warn("Design video work could not be started: the server is stopping", refused)
        }
    }

    override fun start() {
        running = true
        val left = videos.findByStatusAndUpscaledFromIsNullOrderByRequestedAtAscIdAsc(DesignVideoStatus.GENERATING)
        // Taken by the backend: it went on making it, so it is asked about again. Not yet taken: queued again.
        val (taken, untaken) = left.partition { it.remoteId != null }
        if (untaken.isNotEmpty()) {
            log.info("Queueing again {} design video(s) that were being asked for when the server stopped", untaken.size)
            untaken.forEach {
                it.status = DesignVideoStatus.QUEUED
                it.startedAt = null
                it.progress = null
            }
            videos.saveAll(untaken)
        }
        if (taken.isNotEmpty()) log.info("Asking again about {} design video(s) being made when the server stopped", taken.size)
        taken.forEach { schedule(requireNotNull(it.id)) }
        later(::dispatch)
    }

    override fun stop() {
        running = false
        workers.shutdownNow()
    }

    override fun isRunning(): Boolean = running

    companion object {
        /** Threads that ask; the work on each is a request and an answer, never the video being made. */
        private const val WORKERS = 4

        /** What a video file is called: its name's first words, and the extension its type has. */
        fun filenameOf(name: String, contentType: String): String {
            val stem = PictureFilenames.of(name, "video/webm").removeSuffix(".webm").ifEmpty { "video" }
            val extension = when (contentType.lowercase().substringBefore(';').trim()) {
                "video/mp4" -> "mp4"
                "video/webm" -> "webm"
                "image/webp" -> "webp"
                "video/x-msvideo" -> "avi"
                "video/ogg" -> "ogv"
                "video/x-matroska" -> "mkv"
                "image/gif" -> "gif"
                else -> "bin"
            }
            return "$stem.$extension"
        }
    }
}
