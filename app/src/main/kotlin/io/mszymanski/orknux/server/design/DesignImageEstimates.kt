package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.connector.model.ModelUpscaleClient
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * How long a picture being drawn has left, as far as anything knows - and
 * nothing where nothing does.
 *
 * Two sources, and neither is a guess. A Stable Diffusion WebUI says how far
 * it is and how long it reckons is left (`/sdapi/v1/progress`), so while one
 * draws an upscale, a Clean up or a Harmonize it is asked every
 * [Tunable.IMAGE_PROGRESS_POLL_SECONDS] and its last answer is kept here, in
 * memory: it is about this server's drawing and is worthless once it ends.
 * Every other server - stable-diffusion.cpp's, an OpenAI-shaped FLUX edit
 * server, OpenAI itself - answers the request when it is done and says nothing
 * meanwhile, so for those the time a picture usually takes is measured off the
 * same model's newest pictures of the same kind: the median of the newest
 * [Tunable.IMAGE_ESTIMATE_JOBS], and only once there are at least
 * [Tunable.IMAGE_ESTIMATE_LEAST]. Fewer, and there is no figure at all, and the
 * editor says only how long it has taken.
 *
 * The median rather than the mean, as research's estimate takes it: one
 * picture that waited for a model to load says nothing of the next.
 */
@Component
class DesignImageEstimates(
    private val jobs: DesignImageJobRepository,
    private val upscaling: ModelUpscaleClient,
    private val tuning: Tuning,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** The last thing each WebUI said about a picture this server is drawing, by job id. */
    private val readings = ConcurrentHashMap<Long, Reading>()

    /** The pictures being asked about now; a reading that arrives after its picture is done is dropped. */
    private val watched: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    private val counter = AtomicInteger()

    private val asking: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "orknux-draw-progress-${counter.incrementAndGet()}").apply { isDaemon = true }
    }

    /** What a WebUI said, and when; [secondsLeft] is as of [at]. */
    data class Reading(val percent: Int, val secondsLeft: Int?, val at: OffsetDateTime)

    /**
     * What is known about [job]'s wait as of [now]: the WebUI's last reading,
     * its time left counted down to [now], and what the same model's pictures
     * of this kind usually take.
     */
    data class Wait(val percent: Int?, val secondsLeft: Int?, val typicalSeconds: Int?)

    /**
     * Asks [job]'s WebUI how far it is until the returned handle is closed,
     * for a picture a WebUI draws - an upscale, a Clean up, a Harmonize; for
     * anything else nothing is asked and the handle does nothing.
     */
    fun watching(job: DesignImageJob): AutoCloseable {
        val id = job.id ?: return AutoCloseable {}
        val modelId = job.modelId ?: return AutoCloseable {}
        if (job.strength == null && job.scale == null) return AutoCloseable {}
        watched += id
        val every = pollSeconds()
        val task: ScheduledFuture<*>? = runCatching {
            asking.scheduleWithFixedDelay({ ask(id, modelId) }, every, every, TimeUnit.SECONDS)
        }.getOrNull()
        return AutoCloseable {
            watched -= id
            task?.cancel(false)
            readings.remove(id)
        }
    }

    private fun ask(id: Long, modelId: Long) {
        try {
            // A WebUI's progress is about whatever it is drawing, not one request:
            // with two of this model's pictures drawn at once it could be the other's.
            if (jobs.countByModelIdAndStatus(modelId, DesignImageJobStatus.DRAWING) != 1L) {
                readings.remove(id)
                return
            }
            val said = upscaling.progress(modelId, Duration.ofSeconds(pollSeconds())) ?: return
            readings[id] = Reading(said.percent, said.secondsLeft, OffsetDateTime.now())
            if (id !in watched) readings.remove(id)
        } catch (failure: Exception) {
            log.debug("Design image {}: its progress could not be asked for", id, failure)
        }
    }

    /** What is known about [job]'s wait; all null for one not being drawn. */
    fun waitOf(job: DesignImageJob, now: OffsetDateTime = OffsetDateTime.now()): Wait {
        if (job.status != DesignImageJobStatus.DRAWING) return Wait(null, null, null)
        val reading = job.id?.let { readings[it] }
        val left = reading?.secondsLeft?.let { it - Duration.between(reading.at, now).seconds }?.takeIf { it > 0 }?.toInt()
        return Wait(reading?.percent, left, typicalSeconds(job))
    }

    /**
     * The median of how long the same model's newest pictures of the same kind
     * took; null with fewer than [Tunable.IMAGE_ESTIMATE_LEAST] of them.
     */
    fun typicalSeconds(job: DesignImageJob): Int? {
        val modelId = job.modelId ?: return null
        val least = tuning[Tunable.IMAGE_ESTIMATE_LEAST]
        val page = PageRequest.of(0, maxOf(tuning[Tunable.IMAGE_ESTIMATE_JOBS], least))
        val drawn = DesignImageJobStatus.DRAWN
        val scale = job.scale
        val like = when {
            job.strength != null ->
                jobs.findByModelIdAndStatusAndStrengthIsNotNullAndFinishedAtIsNotNullAndStartedAtIsNotNullOrderByFinishedAtDesc(modelId, drawn, page)
            // By its scale: a picture made four times larger is not made twice as large in the same time.
            scale != null ->
                jobs.findByModelIdAndStatusAndScaleAndFinishedAtIsNotNullAndStartedAtIsNotNullOrderByFinishedAtDesc(modelId, drawn, scale, page)
            job.maskLocation != null ->
                jobs.findByModelIdAndStatusAndMaskLocationIsNotNullAndScaleIsNullAndStrengthIsNullAndFinishedAtIsNotNullAndStartedAtIsNotNullOrderByFinishedAtDesc(modelId, drawn, page)
            job.sourceId == null ->
                jobs.findByModelIdAndStatusAndSourceIdIsNullAndFinishedAtIsNotNullAndStartedAtIsNotNullOrderByFinishedAtDesc(modelId, drawn, page)
            else -> return null
        }
        val took = like.filter { it.id != job.id }.mapNotNull { one ->
            val began = one.startedAt ?: return@mapNotNull null
            val ended = one.finishedAt ?: return@mapNotNull null
            Duration.between(began, ended).seconds.takeIf { it >= 0 }
        }
        if (took.size < least) return null
        val sorted = took.sorted()
        val middle = sorted.size / 2
        return (if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2).toInt()
    }

    private fun pollSeconds(): Long = tuning[Tunable.IMAGE_PROGRESS_POLL_SECONDS].toLong()

    @jakarta.annotation.PreDestroy
    fun stop() {
        asking.shutdownNow()
    }
}
