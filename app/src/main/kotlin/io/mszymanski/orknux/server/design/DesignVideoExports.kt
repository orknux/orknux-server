package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries

/** What a video may be exported as from the video editor; [container] is FFmpeg's name for the box. */
enum class DesignVideoExportFormat(val extension: String, val contentType: String, val container: String) {
    GIF("gif", "image/gif", VideoFrames.GIF),
    MP4("mp4", "video/mp4", VideoFrames.MP4),
    MKV("mkv", "video/x-matroska", VideoFrames.MATROSKA),
}

/**
 * A finished video converted into another format, through the FFmpeg the
 * upscale already uses - see [VideoFrames]. Converted while the person waits,
 * so under [Tunable.VIDEO_EXPORT_SECONDS] and [Tunable.VIDEO_EXPORT_MAX_MB].
 *
 * The newest exports are kept, [Tunable.VIDEO_EXPORT_KEPT] of them, in a
 * directory of this server's own under the temporary directory: a video's file
 * never changes once it is made - an upscale is a row of its own - so the
 * same video in the same format with the same GIF settings is the same file,
 * and asking twice should not convert twice. The directory goes when the
 * server stops; another server sharing the database converts for itself.
 */
@Service
class DesignVideoExports(
    private val store: AttachmentStore,
    private val tuning: Tuning,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** One conversion of one file at a time; two people asking for the same export wait for one. */
    private val locks = ConcurrentHashMap<String, Any>()

    private val kept: Path by lazy { Files.createTempDirectory("orknux-video-exports-") }

    /** An export ready to be handed over. */
    class Exported(val file: Path, val filename: String, val contentType: String, val sizeBytes: Long)

    /**
     * [video] as [format], converted now or kept from before. Throws a
     * refusal for an export over the limits, or one FFmpeg could not make.
     */
    fun export(video: DesignVideo, format: DesignVideoExportFormat): Exported {
        val id = requireNotNull(video.id)
        val location = requireNotNull(video.location)
        val gifFps = tuning[Tunable.VIDEO_EXPORT_GIF_FPS]
        val gifWidth = tuning[Tunable.VIDEO_EXPORT_GIF_WIDTH]
        // What the file depends on is in its name, so a changed setting is a different file.
        val key = if (format == DesignVideoExportFormat.GIF) "$id-$gifFps-$gifWidth.${format.extension}" else "$id.${format.extension}"
        val filename = DesignVideoQueue.filenameOf(video.name, format.contentType)
        synchronized(locks.computeIfAbsent(key) { Any() }) {
            // A temporary directory may be swept by the machine while the server runs.
            Files.createDirectories(kept)
            val ready = kept.resolve(key)
            if (Files.exists(ready)) {
                touch(ready)
                return Exported(ready, filename, format.contentType, Files.size(ready))
            }
            val work = Files.createTempFile(kept, "converting-", ".${format.extension}")
            val source = Files.createTempFile(kept, "source-", ".video")
            try {
                store.open(location).use { Files.copy(it, source, StandardCopyOption.REPLACE_EXISTING) }
                val limits = ExportLimits(
                    seconds = tuning[Tunable.VIDEO_EXPORT_SECONDS],
                    megabytes = tuning[Tunable.VIDEO_EXPORT_MAX_MB],
                    out = work,
                )
                val started = System.nanoTime()
                convert(video, format, source, work, gifFps, gifWidth, limits)
                limits.check(final = true)
                log.info(
                    "Design video {} exported as {} in {} ms, {} bytes",
                    id, format, (System.nanoTime() - started) / 1_000_000, Files.size(work),
                )
                Files.move(work, ready, StandardCopyOption.REPLACE_EXISTING)
                val size = Files.size(ready)
                evict()
                return Exported(ready, filename, format.contentType, size)
            } catch (refusal: DesignVideoExportTooLargeException) {
                throw refusal
            } catch (refusal: DesignVideoExportTimedOutException) {
                throw refusal
            } catch (failure: Exception) {
                log.warn("Design video {} could not be exported as {}", id, format, failure)
                throw DesignVideoExportFailedException(format.name, failure.message ?: failure.javaClass.simpleName)
            } finally {
                runCatching { Files.deleteIfExists(work) }
                runCatching { Files.deleteIfExists(source) }
            }
        }
    }

    private fun convert(
        video: DesignVideo,
        format: DesignVideoExportFormat,
        source: Path,
        out: Path,
        gifFps: Int,
        gifWidth: Int,
        limits: ExportLimits,
    ) {
        val already = video.contentType?.lowercase()?.substringBefore(';')?.trim() == format.contentType
        when {
            // Already what was asked for: the file itself, not a copy of a copy.
            already -> Files.copy(source, out, StandardCopyOption.REPLACE_EXISTING)
            format == DesignVideoExportFormat.GIF -> VideoFrames.toGif(source, out, gifFps, gifWidth) { limits.check() }
            else -> VideoFrames.transcode(source, out, format.container) { limits.check() }
        }
    }

    /** Every export of [videoId] let go: the video was removed. */
    fun forget(videoId: Long) {
        runCatching {
            kept.listDirectoryEntries()
                .filter { it.fileName.toString().let { name -> name.startsWith("$videoId-") || name.startsWith("$videoId.") } }
                .forEach { Files.deleteIfExists(it) }
        }
    }

    /** Only the newest [Tunable.VIDEO_EXPORT_KEPT] are kept; the rest go, oldest first. */
    private fun evict() {
        val most = tuning[Tunable.VIDEO_EXPORT_KEPT]
        val ready = runCatching {
            kept.listDirectoryEntries()
                .filter { !it.fileName.toString().startsWith("converting-") && !it.fileName.toString().startsWith("source-") }
                .sortedByDescending { Files.getLastModifiedTime(it) }
        }.getOrDefault(emptyList())
        ready.drop(most).forEach { runCatching { Files.deleteIfExists(it) } }
    }

    private fun touch(file: Path) {
        runCatching { Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis())) }
    }

    @OptIn(ExperimentalPathApi::class)
    @PreDestroy
    fun close() {
        runCatching { kept.deleteRecursively() }
    }
}

/**
 * What one export may take: [seconds] of converting and [megabytes] of file.
 * Checked after every frame written, so an export over either stops there
 * rather than at the end.
 */
class ExportLimits(
    private val seconds: Int,
    private val megabytes: Int,
    private val out: Path,
    private val clock: () -> Long = System::nanoTime,
) {
    private val deadline = clock() + seconds * 1_000_000_000L
    private var frames = 0

    fun check(final: Boolean = false) {
        // Compared as a difference: with seconds at Int.MAX_VALUE the deadline may have wrapped past Long's end.
        if (clock() - deadline > 0) throw DesignVideoExportTimedOutException(seconds)
        // The file's size is a look at the disk; every frame of a long video would be thousands of them.
        if (final || ++frames % SIZE_EVERY == 0) {
            val bytes = runCatching { Files.size(out) }.getOrDefault(0L)
            if (bytes > megabytes * 1024L * 1024L) throw DesignVideoExportTooLargeException(megabytes)
        }
    }

    private companion object {
        const val SIZE_EVERY = 16
    }
}

/** An export asked of a video that is not finished, or whose file has gone. */
class DesignVideoExportUnfinishedException : RuntimeException("Only a finished video can be exported.")

/** An export that came to more than an installation lets one be. */
class DesignVideoExportTooLargeException(val most: Int) : RuntimeException(
    "The export came to more than the $most MB an export may be.",
), Refusal {
    override val arguments get() = mapOf("most" to most)
}

/** An export still converting when its time ran out. */
class DesignVideoExportTimedOutException(val seconds: Int) : RuntimeException(
    "The export took longer than the $seconds seconds an export may take.",
), Refusal {
    override val arguments get() = mapOf("seconds" to seconds)
}

/** An export FFmpeg could not make, with what it said. */
class DesignVideoExportFailedException(val format: String, val reason: String) : RuntimeException(
    "The video could not be exported as $format: $reason",
), Refusal {
    override val arguments get() = mapOf("format" to format, "reason" to reason)
}
