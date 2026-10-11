package io.mszymanski.orknux.server.box

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * One event of a box session's log, as this side recorded it.
 *
 * orkbox keeps a session's log only while it keeps the session, and the local
 * box keeps its sessions in memory - so without a copy here a restart of this
 * server leaves a conversation unable to say what was run in its sandboxes.
 */
@Entity
@Table(name = "tin_session_event")
class BoxSessionEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "tin_session_id", nullable = false)
    val boxSessionId: Long = 0,

    /** The box's own sequence number, unique within the session. */
    @Column(nullable = false)
    val seq: Long = 0,

    /** When, as the box wrote it. */
    @Column(nullable = false, length = 64)
    val at: String = "",

    @Column(nullable = false, length = 255)
    val type: String = "",

    /** The event's data, a JSON object as text. */
    @Column(nullable = false)
    val data: String = "",
)

interface BoxSessionEventRepository : JpaRepository<BoxSessionEvent, Long> {
    @Query("select max(e.seq) from BoxSessionEvent e where e.boxSessionId = :id")
    fun lastSeq(@Param("id") boxSessionId: Long): Long?

    fun findByBoxSessionIdAndSeqGreaterThanOrderBySeqAsc(boxSessionId: Long, seq: Long, limit: Limit): List<BoxSessionEvent>

    fun existsByBoxSessionId(boxSessionId: Long): Boolean
}

/**
 * What one finished command printed, the combined stream, as this side kept it
 * - up to [BoxSettings.localOutputKeptBytes].
 */
@Entity
@Table(name = "tin_session_output")
class BoxSessionOutput(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "tin_session_id", nullable = false)
    val boxSessionId: Long = 0,

    @Column(name = "execution_id", nullable = false, length = 255)
    val executionId: String = "",

    /** The byte offset in the stream [text] starts at; not 0 where the box had let the head go. */
    @Column(name = "start_offset", nullable = false)
    val startOffset: Long = 0,

    @Column(nullable = false)
    val text: String = "",

    /** Whether the stream went on past what was kept. */
    @Column(nullable = false)
    val truncated: Boolean = false,
)

interface BoxSessionOutputRepository : JpaRepository<BoxSessionOutput, Long> {
    fun findByBoxSessionIdAndExecutionId(boxSessionId: Long, executionId: String): BoxSessionOutput?
    fun existsByBoxSessionIdAndExecutionId(boxSessionId: Long, executionId: String): Boolean
}

/**
 * Copies a box session's log into [BoxSessionEvent] rows, and the output of
 * each command its log says has finished into a [BoxSessionOutput], and reads
 * both back.
 *
 * A copy is taken from the highest sequence number already held, so it costs
 * one call to the box when nothing happened and a page per [BOX_LOG_PAGE]
 * events when something did. `execution.output` is left with the box, as the panel that
 * reads this never asked for it.
 *
 * **The local box's only.** It holds its sessions in memory, so nothing of
 * them survives a restart of this server unless it is kept here. A remote box
 * is a server of its own that keeps its sessions' logs itself, and is read
 * live; [record] does nothing for one.
 *
 * Synchronised: two callers copying the same session at once would both insert
 * the same sequence numbers, and the second would be refused by the unique key.
 * One process, so one lock is enough.
 */
@Service
class BoxSessionLog(
    private val events: BoxSessionEventRepository,
    private val outputs: BoxSessionOutputRepository,
    private val settings: BoxSettings,
    private val mapper: ObjectMapper,
    /** Kept masked: what is stored here is shown later, and must not hold a secret a command was given. */
    private val secrets: BoxSecrets,
) {

    /** Copies what the box has beyond what is held. A box that refuses is the caller's to judge; see [recordQuietly]. */
    @Synchronized
    fun record(row: BoxSession, client: BoxClient) {
        if (row.boxKind != BoxKind.LOCAL) return
        val id = requireNotNull(row.id)
        var since = events.lastSeq(id) ?: 0
        // Ends: every page either holds an event past `since` and moves it on,
        // or ends the copy.
        while (true) {
            val page = client.logs(row.remoteSessionId, since, BOX_LOG_PAGE, null)
            val fresh = page.events.filter { it.seq > since }
            if (fresh.isEmpty()) return
            events.saveAll(fresh.map { BoxSessionEvent(boxSessionId = id, seq = it.seq, at = it.at, type = it.type, data = secrets.mask(row, it.data)) })
            fresh.filter { it.type == FINISHED }.forEach { keepOutput(row, client, it) }
            since = fresh.maxOf { it.seq }
            if (page.events.size < BOX_LOG_PAGE) return
        }
    }

    /**
     * Keeps what the execution [finished] names printed, up to the setting. A
     * failure here is logged and passed over rather than left to stop the
     * events after it being recorded: the events are the log, the output a
     * convenience beside it.
     */
    private fun keepOutput(row: BoxSession, client: BoxClient, finished: BoxLogEvent) {
        val most = settings.localOutputKeptBytes()
        if (most <= 0) return
        val id = requireNotNull(row.id)
        val execution = runCatching { mapper.readTree(finished.data).path("executionId").stringValue() }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return
        if (outputs.existsByBoxSessionIdAndExecutionId(id, execution)) return
        try {
            val bytes = java.io.ByteArrayOutputStream()
            var start: Long? = null
            var next = 0L
            var truncated = false
            while (true) {
                val room = most - bytes.size()
                if (room <= 0) {
                    // One byte more says whether there was more to keep.
                    truncated = client.output(row.remoteSessionId, execution, COMBINED, next, 1).let { it.next > it.offset }
                    break
                }
                val chunk = client.output(row.remoteSessionId, execution, COMBINED, next, minOf(room, settings.outputBytes()))
                if (start == null) start = chunk.offset
                val read = chunk.text.toByteArray(Charsets.UTF_8)
                val got = (chunk.next - chunk.offset).toInt()
                if (got <= 0) break
                bytes.write(read, 0, minOf(read.size, room))
                next = chunk.next
            }
            outputs.save(
                BoxSessionOutput(
                    boxSessionId = id,
                    executionId = execution,
                    startOffset = start ?: 0,
                    text = secrets.mask(row, String(bytes.toByteArray(), Charsets.UTF_8)),
                    truncated = truncated,
                ),
            )
        } catch (failed: BoxCallException) {
            log.debug("The output of {} in box session {} could not be kept: {} {}", execution, row.id, failed.code, failed.message)
        }
    }

    /** What was kept of [executionId]'s output; null when nothing was. */
    fun output(boxSessionId: Long, executionId: String): BoxSessionOutput? =
        outputs.findByBoxSessionIdAndExecutionId(boxSessionId, executionId)

    /** [record], for a caller that must not fail because the log could not be copied. */
    fun recordQuietly(row: BoxSession, client: BoxClient) {
        try {
            record(row, client)
        } catch (failed: BoxCallException) {
            log.debug("The log of box session {} could not be copied: {} {}", row.id, failed.code, failed.message)
        } catch (failed: Exception) {
            log.warn("The log of box session {} could not be copied", row.id, failed)
        }
    }

    /** The recorded events after [after], oldest first, at most [limit]. */
    fun after(boxSessionId: Long, after: Long, limit: Int): List<BoxSessionEvent> =
        events.findByBoxSessionIdAndSeqGreaterThanOrderBySeqAsc(boxSessionId, after, Limit.of(limit))

    fun any(boxSessionId: Long): Boolean = events.existsByBoxSessionId(boxSessionId)

    companion object {
        private val log = LoggerFactory.getLogger(BoxSessionLog::class.java)

        const val FINISHED = "execution.finished"
        const val COMBINED = "combined"
    }
}

/** How many log events are asked of a box at once, and how many one page of a conversation's Box panel carries. */
const val BOX_LOG_PAGE = 1000
