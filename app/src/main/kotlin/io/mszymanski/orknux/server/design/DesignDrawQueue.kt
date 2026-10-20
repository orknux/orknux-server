package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.connector.model.ImageOptions
import io.mszymanski.orknux.connector.model.ModelImageClient
import io.mszymanski.orknux.connector.model.Picture
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.attachment.PictureFilenames
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.OffsetDateTime
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Draws the pictures asked for on Design's Images page, in the order they were
 * asked for, as many at once as Admin > Settings allows.
 *
 * Off the request that asked, because a drawing can take minutes and the page
 * that asked may be long gone: the row is the request, and the picture is filed
 * against it when it comes. A picture being drawn when the server stopped is
 * queued again when it starts.
 *
 * Every step is logged - queued, started, drawn, failed - with the model and how
 * long it took, because a drawing that never comes back is otherwise a page
 * that says "Drawing…" and a log that says nothing.
 */
@Service
class DesignDrawQueue(
    private val jobs: DesignImageJobRepository,
    private val images: DesignImageRepository,
    private val drawing: ModelImageClient,
    private val upscaling: io.mszymanski.orknux.connector.model.ModelUpscaleClient,
    private val store: AttachmentStore,
    private val settings: InstallationSettings,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val artifacts: io.mszymanski.orknux.server.workflow.WorkspaceArtifactAPI,
) : SmartLifecycle {

    private val log = LoggerFactory.getLogger(javaClass)

    private val workers: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "orknux-draw-${counter.incrementAndGet()}").apply { isDaemon = true }
    }

    private val counter = AtomicInteger()
    private val drawingNow = AtomicInteger()

    @Volatile
    private var running = false

    /** Queued: drawn once its turn comes, which is at once when nothing else is being drawn. */
    fun queued(job: DesignImageJob) {
        log.info(
            "Design image {} queued in workspace {}: {} at {} for {}",
            job.id, job.workspaceId, job.modelName, job.size ?: "the model's size", job.requestedBy,
        )
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    // On a thread of its own: this runs inside the transaction that has just
                    // committed, and a claim made from there has no transaction to update in.
                    override fun afterCommit() = workers.execute(::dispatch)
                },
            )
        } else {
            dispatch()
        }
    }

    /** Starts what the limit allows, oldest first. */
    @Synchronized
    fun dispatch() {
        if (!running) return
        val waiting = jobs.findByStatusOrderByRequestedAtAscIdAsc(DesignImageJobStatus.QUEUED).iterator()
        while (drawingNow.get() < settings.imageDrawsAtOnce() && waiting.hasNext()) {
            val id = requireNotNull(waiting.next().id)
            // Another server sharing the database may have taken it first.
            if (jobs.claim(id, OffsetDateTime.now()) != 1) continue
            drawingNow.incrementAndGet()
            try {
                workers.execute { draw(id) }
            } catch (refused: RuntimeException) {
                drawingNow.decrementAndGet()
                fail(id, "The server is stopping; ask again once it is back.")
                log.warn("Design image {} could not be started", id, refused)
            }
        }
    }

    private fun draw(id: Long) {
        try {
            val job = jobs.findByIdOrNull(id) ?: return
            // Stopped between its claim and now: nothing is asked of the model.
            if (job.status != DesignImageJobStatus.DRAWING) return
            val modelId = job.modelId ?: return fail(id, "${job.modelName} has been removed.")
            if (!settings.attachmentsEnabled()) {
                return fail(id, "A drawn picture is kept as an attachment, and attachments are turned off for this installation.")
            }
            val started = System.currentTimeMillis()
            val source = job.sourceId?.let { artifacts.storedOf(job.workspaceId, it) }
            if (job.sourceId != null && source == null) return fail(id, "The picture to change is not here any more.")
            val original = source?.let { store.open(it.location).use { stream -> stream.readBytes() } }
            val mask = job.maskLocation?.let { store.open(it).use { stream -> stream.readBytes() } }
            val scale = job.scale
            val strength = job.strength
            val asked = if (original != null && strength != null) {
                harmonized(id, job, modelId, original, strength)
            } else if (original != null && scale != null) {
                log.info("Design image {} upscaling {} {}x by {} with {}", id, job.sourceId, scale, job.upscaler ?: "its own algorithm", job.modelName)
                upscaling.upscale(modelId, original, job.upscaler, scale)
            } else if (original != null && mask != null) {
                log.info("Design image {} editing {} with {}", id, job.sourceId, job.modelName)
                drawing.edit(modelId, job.prompt, original, mask)
            } else {
                log.info("Design image {} drawing with {} at {}", id, job.modelName, job.size ?: "the model's size")
                drawing.draw(modelId, job.prompt, ImageOptions(job.size, job.quality, job.style))
            }
            // An edit keeps every pixel outside the selection: the original's own, not the model's redrawing of it.
            val result = if (asked is Picture.Drawn && original != null && mask != null) {
                MaskedComposite.keepOutside(original, asked.image, mask)
                    ?.let { Picture.Drawn(it, "image/png", asked.millis) } ?: asked
            } else {
                asked
            }
            if (jobs.findByIdOrNull(id)?.status == DesignImageJobStatus.STOPPED) {
                log.info("Design image {} was stopped; what {} answered after {} ms is thrown away", id, job.modelName, System.currentTimeMillis() - started)
                return
            }
            when (val picture = result) {
                is Picture.Failed -> {
                    log.warn("Design image {} was not drawn by {} after {} ms: {}", id, job.modelName, System.currentTimeMillis() - started, picture.reason)
                    fail(id, picture.reason)
                }
                is Picture.Drawn -> {
                    val filename = PictureFilenames.of(job.name ?: source?.view?.name ?: job.prompt, picture.contentType)
                    val saved = images.save(
                        DesignImage(
                            workspaceId = job.workspaceId,
                            modelId = modelId,
                            modelName = job.modelName,
                            prompt = job.prompt,
                            size = job.size,
                            quality = job.quality,
                            style = job.style,
                            filename = filename,
                            contentType = picture.contentType,
                            sizeBytes = picture.image.size.toLong(),
                            location = store.put(job.workspaceId, filename, picture.image),
                            drawnBy = job.requestedBy,
                            name = job.name ?: source?.view?.name ?: PictureFilenames.nameOf(job.prompt),
                            // An edit is a version of the picture it changes - of the picture itself, not of the round before.
                            versionOf = source?.let { it.view.versionOf ?: job.sourceId },
                        ),
                    )
                    // Stopped while the picture was being filed: the row decides, and the picture goes.
                    if (jobs.drawn(id, requireNotNull(saved.id)) != 1) {
                        images.delete(saved)
                        runCatching { store.remove(saved.location) }
                        log.info("Design image {} was stopped as it was drawn; its picture is thrown away", id)
                        return
                    }
                    // A version is no picture yet: Save is what puts one in the log.
                    if (source == null) {
                        auditRecorder.recordAutomated(
                            job.workspaceId,
                            WorkspaceAuditCategory.WORKFLOW,
                            "Image $filename drawn in Design with ${job.modelName}",
                            job.requestedBy,
                        )
                    }
                    log.info("Design image {} drawn by {} in {} ms, {} bytes", id, job.modelName, picture.millis, picture.image.size)
                }
            }
        } catch (failure: Exception) {
            log.warn("Design image {} failed while drawing", id, failure)
            fail(id, failure.message ?: "The drawing failed.")
        } finally {
            drawingNow.decrementAndGet()
            dispatch()
        }
    }

    /**
     * Harmonize, at no more than the installation's working size: a picture
     * longer than that is scaled down for the WebUI, which then draws in
     * minutes rather than hours, and the answer is scaled back up to the
     * picture's own size, so a version is the size of what it is a version of.
     */
    private fun harmonized(id: Long, job: DesignImageJob, modelId: Long, original: ByteArray, strength: Double): Picture {
        val longest = settings.imageHarmonizeSize()
        val image = PictureScaling.read(original)
        val working = image?.let { PictureScaling.workingSize(it.width, it.height, longest) }
        log.info(
            "Design image {} harmonizing {} at {} by {} with {} at {}",
            id, job.sourceId, strength, job.checkpoint ?: "its loaded checkpoint", job.modelName,
            if (image != null && working != null) "${working.first}x${working.second}, scaled from ${image.width}x${image.height}" else "its own size",
        )
        if (image == null || working == null) return upscaling.harmonize(modelId, original, job.prompt, strength, job.checkpoint)
        val sent = PictureScaling.png(PictureScaling.fitted(image, working.first, working.second))
        val answer = upscaling.harmonize(modelId, sent, job.prompt, strength, job.checkpoint)
        if (answer !is Picture.Drawn) return answer
        val redrawn = PictureScaling.read(answer.image) ?: return answer
        return Picture.Drawn(PictureScaling.png(PictureScaling.fitted(redrawn, image.width, image.height)), "image/png", answer.millis)
    }

    /**
     * Somebody stopped [job]. One still queued is simply never claimed; one
     * being drawn by a Stable Diffusion WebUI - an upscale or a Harmonize - is
     * interrupted there too, so the server stops working on it. The WebUI's
     * interrupt is for whatever it is drawing, not for one request, so with
     * more than one picture drawn at once on one WebUI it can stop another.
     * An image model has no standard way to cancel: its drawing is left to
     * finish and thrown away when it comes.
     */
    fun stopped(job: DesignImageJob) {
        log.info("Design image {} stopped in workspace {} while {}", job.id, job.workspaceId, if (job.startedAt == null) "queued" else "drawing")
        val modelId = job.modelId ?: return
        if (job.startedAt == null || (job.strength == null && job.scale == null)) return
        try {
            workers.execute {
                upscaling.interrupt(modelId)?.let { log.warn("Design image {} could not be interrupted at its WebUI: {}", job.id, it) }
                    ?: log.info("Design image {} interrupted at the WebUI of {}", job.id, job.modelName)
            }
        } catch (refused: RuntimeException) {
            log.warn("Design image {} could not be interrupted: the server is stopping", job.id, refused)
        }
    }

    private fun fail(id: Long, reason: String) {
        jobs.failed(id, reason)
    }

    override fun start() {
        running = true
        val left = jobs.findByStatusOrderByRequestedAtAscIdAsc(DesignImageJobStatus.DRAWING)
        if (left.isNotEmpty()) {
            log.info("Queueing again {} design image(s) that were being drawn when the server stopped", left.size)
            left.forEach {
                it.status = DesignImageJobStatus.QUEUED
                it.startedAt = null
            }
            jobs.saveAll(left)
        }
        dispatch()
    }

    override fun stop() {
        running = false
        workers.shutdownNow()
    }

    override fun isRunning(): Boolean = running
}
