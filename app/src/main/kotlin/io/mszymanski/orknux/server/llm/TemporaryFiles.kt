package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.server.attachment.AttachmentTypes
import io.mszymanski.orknux.server.attachment.ChatAttachmentShelf
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.workflow.script.StoredKind
import jakarta.annotation.PreDestroy
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.time.OffsetDateTime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * A file kept for a few minutes under a key, for the tools that take one.
 * The bytes are `temporary_file_part`, read and written over JDBC a piece at
 * a time, never through this entity.
 */
@Entity
@Table(name = "temporary_file")
class TemporaryFile(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long = 0,

    @Column(name = "session_id", nullable = false)
    val sessionId: Long = 0,

    @Column(nullable = false, length = 255)
    val filename: String = "",

    @Column(name = "content_type", nullable = false, length = 255)
    val contentType: String = "",

    @Column(name = "is_binary", nullable = false)
    val binary: Boolean = true,

    @Column(nullable = false)
    var size: Long = 0,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "expires_at", nullable = false)
    val expiresAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "created_by", length = 255)
    val createdBy: String? = null,
) {
    fun expired(now: OffsetDateTime = OffsetDateTime.now()): Boolean = !expiresAt.isAfter(now)
}

interface TemporaryFileRepository : JpaRepository<TemporaryFile, Long> {

    @Query("select count(t) from TemporaryFile t where t.expiresAt > :now")
    fun countLive(now: OffsetDateTime): Long

    @Query("select t from TemporaryFile t where t.sessionId = :sessionId and t.expiresAt > :now order by t.expiresAt")
    fun liveIn(sessionId: Long, now: OffsetDateTime): List<TemporaryFile>

    @Query("select t.id from TemporaryFile t where t.expiresAt <= :now")
    fun expiredIds(now: OffsetDateTime): List<Long>
}

/** What [TemporaryFiles.keep] answers: the file kept, the key it is under, and what it had to change about what was asked. */
data class KeptFile(val file: TemporaryFile, val key: String, val shortened: Boolean)

/** Why a file was not kept, in words a model can act on. */
class TemporaryFileRefusedException(message: String) : RuntimeException(message)

/**
 * Files kept for a short while under a key in a session's store, so a file
 * can be passed from one tool to another - uploaded by an MCP client and
 * attached by `jira_attach`, say - without anybody copying its content.
 *
 * Kept in the database, so every replica reads the same file and nothing is
 * left behind on a disk. Bounded four ways, each an administrator's to move
 * under Admin -> Settings: how large one file may be, how many one session may
 * hold at once, how many the installation holds at once, and how long one is
 * kept - which a caller may shorten and never lengthen.
 *
 * An expired file is gone the moment it expires, whatever the sweep has got to:
 * [LlmSessionStore] reads one past its time as nothing. The sweep, on a timer
 * and before every new file, is what frees the rows.
 */
@Component
class TemporaryFiles(
    private val files: TemporaryFileRepository,
    private val entries: LlmSessionStoreRepository,
    /** Lazy: the store reads temporary files through here. */
    @param:org.springframework.context.annotation.Lazy private val store: LlmSessionStore,
    dataSource: DataSource,
    transactions: org.springframework.transaction.PlatformTransactionManager,
    private val tuning: Tuning,
    @Value("\${orknux.temporary-files.sweep-seconds:60}") private val sweepSeconds: Long,
) {

    private val jdbc = JdbcTemplate(dataSource)
    private val transaction = TransactionTemplate(transactions)
    private val log = LoggerFactory.getLogger(TemporaryFiles::class.java)
    private val sweeper = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "temporary-files-sweep").apply { isDaemon = true }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun startSweeping() {
        if (sweepSeconds <= 0) return
        sweeper.scheduleWithFixedDelay(
            { runCatching { sweep() }.onFailure { log.warn("Sweeping temporary files failed: {}", it.message) } },
            sweepSeconds, sweepSeconds, TimeUnit.SECONDS,
        )
    }

    @PreDestroy
    fun stop() {
        sweeper.shutdownNow()
    }

    /** How long a file is kept when nobody says, and the longest anybody may ask for. */
    fun longestSeconds(): Int = tuning[Tunable.TEMPORARY_FILE_SECONDS]

    /** How many files one session may hold at once. */
    fun perSession(): Int = tuning[Tunable.TEMPORARY_FILES_PER_SESSION]

    /** The largest one file may be, in bytes. */
    fun mostBytes(): Long = tuning[Tunable.TEMPORARY_FILE_MB].toLong() * MEGABYTE

    /**
     * Keeps [input] as [filename] in session [sessionId]'s store for [seconds],
     * or for the longest allowed when that is null or longer.
     *
     * @param declared what the caller said the file is; a type naming no format gives way to the filename's extension.
     * @param size the length, where the caller knows it; a larger one is refused before a byte is read.
     * @throws TemporaryFileRefusedException with the reason, when it was not kept.
     */
    fun keep(
        sessionId: Long,
        workspaceId: Long,
        filename: String,
        declared: String?,
        input: InputStream,
        size: Long?,
        seconds: Int?,
        by: String?,
    ): KeptFile {
        val name = filename.trim().substringAfterLast('/').substringAfterLast('\\').takeLast(MAX_NAME)
        if (name.isEmpty()) throw TemporaryFileRefusedException("Say what the file is called, such as report.pdf: its extension says what it is")
        if (seconds != null && seconds < 1) throw TemporaryFileRefusedException("A file is kept for at least one second")
        val most = mostBytes()
        if (size != null && size > most) throw tooLarge(size)
        sweep()

        val longest = longestSeconds()
        val kept = (seconds ?: longest).coerceAtMost(longest)
        val now = OffsetDateTime.now()
        val perSession = tuning[Tunable.TEMPORARY_FILES_PER_SESSION]
        val held = files.liveIn(sessionId, now)
        if (held.size >= perSession) {
            throw TemporaryFileRefusedException(
                "This session already holds $perSession temporary files, the most it may at once: " +
                    held.joinToString("; ") { "${KEY_PREFIX}${it.id} (${it.filename}, ${secondsLeft(it, now)}s left)" } +
                    ". Forget one you are done with, or wait for one to expire.",
            )
        }
        val installation = tuning[Tunable.TEMPORARY_FILES_MOST]
        if (files.countLive(now) >= installation) {
            throw TemporaryFileRefusedException(
                "This installation already holds $installation temporary files, the most it may at once. " +
                    "Try again once one has expired - none is kept longer than $longest seconds.",
            )
        }

        val said = declared?.substringBefore(';')?.trim()?.lowercase()
        val named = if (said.isNullOrEmpty() || said in GENERIC) java.net.URLConnection.guessContentTypeFromName(name) else declared
        val type = AttachmentTypes.of(name, named)

        return transaction.execute {
            val file = files.saveAndFlush(
                TemporaryFile(
                    workspaceId = workspaceId,
                    sessionId = sessionId,
                    filename = name,
                    contentType = type,
                    binary = !ChatAttachmentShelf.isText(type),
                    createdAt = now,
                    expiresAt = now.plusSeconds(kept.toLong()),
                    createdBy = by,
                ),
            )
            val id = requireNotNull(file.id)
            val written = write(id, input, most)
            if (written == 0L) throw TemporaryFileRefusedException("There are no bytes to keep: send the file's content")
            file.size = written
            files.save(file)
            val key = KEY_PREFIX + id
            store.putTemporary(sessionId, key, id, StoredKind(type, file.binary))
                ?.let { reason -> throw TemporaryFileRefusedException("The file could not be kept: $reason") }
            KeptFile(file, key, seconds != null && seconds > longest)
        }!!
    }

    /** The files [sessionId] holds now, the one going first first. */
    fun held(sessionId: Long): List<TemporaryFile> = files.liveIn(sessionId, OffsetDateTime.now())

    /**
     * Lets go of the file kept under [key] in [sessionId]'s store before its
     * time. False where nothing temporary is kept there.
     */
    fun forget(sessionId: Long, key: String): Boolean {
        val id = idOf(key) ?: return false
        val file = files.findById(id).orElse(null)?.takeIf { it.sessionId == sessionId } ?: return false
        transaction.executeWithoutResult { drop(listOf(requireNotNull(file.id))) }
        return true
    }

    /** The file [key] names, where it is a temporary one still kept - for [LlmSessionStore]. */
    fun live(id: Long): TemporaryFile? = files.findById(id).orElse(null)?.takeUnless { it.expired() }

    /** The bytes of file [id], a piece at a time, as [StoredContent] reads them. */
    fun content(file: TemporaryFile): StoredContent = StoredContent(
        kind = StoredKind(file.contentType, file.binary),
        size = file.size,
        path = null,
        identity = "temporary:${file.id}",
    ) { open(requireNotNull(file.id)) }

    /** Frees every file past its time. Answers how many went. */
    fun sweep(): Int {
        val gone = files.expiredIds(OffsetDateTime.now())
        if (gone.isEmpty()) return 0
        transaction.executeWithoutResult { drop(gone) }
        return gone.size
    }

    private fun drop(ids: List<Long>) {
        ids.forEach { id ->
            // Said rather than left to the foreign keys, which SQLite honours only where the connection asked it to.
            entries.deleteByTemporaryFileId(id)
            jdbc.update("DELETE FROM temporary_file_part WHERE file_id = ?", id)
        }
        files.deleteAllById(ids)
    }

    private fun write(id: Long, input: InputStream, most: Long): Long {
        var total = 0L
        var part = 0
        val buffer = ByteArray(PART_BYTES)
        while (true) {
            val read = input.readNBytes(buffer, 0, PART_BYTES)
            if (read <= 0) break
            total += read
            if (total > most) throw tooLarge(total, atLeast = true)
            jdbc.update("INSERT INTO temporary_file_part (file_id, part, bytes) VALUES (?, ?, ?)", id, part, buffer.copyOf(read))
            part += 1
            if (read < PART_BYTES) break
        }
        return total
    }

    private fun open(id: Long): InputStream {
        // One piece in memory at a time: each is read when the one before it has been used up.
        val parts = jdbc.queryForList("SELECT part FROM temporary_file_part WHERE file_id = ? ORDER BY part", Int::class.java, id).iterator()
        val pieces = object : java.util.Enumeration<InputStream> {
            override fun hasMoreElements(): Boolean = parts.hasNext()
            override fun nextElement(): InputStream = ByteArrayInputStream(
                jdbc.queryForObject("SELECT bytes FROM temporary_file_part WHERE file_id = ? AND part = ?", ByteArray::class.java, id, parts.next())
                    ?: ByteArray(0),
            )
        }
        return SequenceInputStream(pieces)
    }

    private fun tooLarge(size: Long, atLeast: Boolean = false): TemporaryFileRefusedException {
        val mb = tuning[Tunable.TEMPORARY_FILE_MB]
        val was = (size + MEGABYTE - 1) / MEGABYTE
        return TemporaryFileRefusedException(
            "A temporary file is at most $mb MB, and that one is ${if (atLeast) "more than " else ""}$was MB " +
                "(an administrator sets this as ${Tunable.TEMPORARY_FILE_MB.label})",
        )
    }

    companion object {
        /** What a temporary file is kept under, before its id. */
        const val KEY_PREFIX = "temp."

        /** The temporary file [key] names, where it names one. */
        fun idOf(key: String): Long? = key.trim().takeIf { it.startsWith(KEY_PREFIX) }?.removePrefix(KEY_PREFIX)?.toLongOrNull()

        fun secondsLeft(file: TemporaryFile, now: OffsetDateTime = OffsetDateTime.now()): Long =
            // Rounded up: kept for 20 seconds reads as 20, not as the 19 and a bit a moment later leaves.
            ((java.time.Duration.between(now, file.expiresAt).toMillis() + 999) / 1000).coerceAtLeast(0)

        private const val MEGABYTE = 1024L * 1024L
        private const val PART_BYTES = 1024 * 1024
        private const val MAX_NAME = 255

        /** Types a client sends when it does not know, which say nothing about the file. */
        private val GENERIC = setOf("application/octet-stream", "application/x-www-form-urlencoded", "binary/octet-stream")
    }
}
