package io.mszymanski.orknux.server.tin

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
 * One event of a tin session's log, as this side recorded it.
 *
 * orktin keeps a session's log only while it keeps the session, and the local
 * tin keeps its sessions in memory - so without a copy here a restart of this
 * server leaves a conversation unable to say what was run in its sandboxes.
 */
@Entity
@Table(name = "tin_session_event")
class TinSessionEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "tin_session_id", nullable = false)
    val tinSessionId: Long = 0,

    /** The tin's own sequence number, unique within the session. */
    @Column(nullable = false)
    val seq: Long = 0,

    /** When, as the tin wrote it. */
    @Column(nullable = false, length = 64)
    val at: String = "",

    @Column(nullable = false, length = 255)
    val type: String = "",

    /** The event's data, a JSON object as text. */
    @Column(nullable = false)
    val data: String = "",
)

interface TinSessionEventRepository : JpaRepository<TinSessionEvent, Long> {
    @Query("select max(e.seq) from TinSessionEvent e where e.tinSessionId = :id")
    fun lastSeq(@Param("id") tinSessionId: Long): Long?

    fun findByTinSessionIdAndSeqGreaterThanOrderBySeqAsc(tinSessionId: Long, seq: Long, limit: Limit): List<TinSessionEvent>

    fun existsByTinSessionId(tinSessionId: Long): Boolean
}

/**
 * What one finished command printed, the combined stream, as this side kept it
 * - up to [TinSettings.localOutputKeptBytes].
 */
@Entity
@Table(name = "tin_session_output")
class TinSessionOutput(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "tin_session_id", nullable = false)
    val tinSessionId: Long = 0,

    @Column(name = "execution_id", nullable = false, length = 255)
    val executionId: String = "",

    /** The byte offset in the stream [text] starts at; not 0 where the tin had let the head go. */
    @Column(name = "start_offset", nullable = false)
    val startOffset: Long = 0,

    @Column(nullable = false)
    val text: String = "",

    /** Whether the stream went on past what was kept. */
    @Column(nullable = false)
    val truncated: Boolean = false,
)

interface TinSessionOutputRepository : JpaRepository<TinSessionOutput, Long> {
    fun findByTinSessionIdAndExecutionId(tinSessionId: Long, executionId: String): TinSessionOutput?
    fun existsByTinSessionIdAndExecutionId(tinSessionId: Long, executionId: String): Boolean
}

/**
 * Copies a tin session's log into [TinSessionEvent] rows, and the output of
 * each command its log says has finished into a [TinSessionOutput], and reads
 * both back.
 *
 * A copy is taken from the highest sequence number already held, so it costs
 * one call to the tin when nothing happened and a page per [TIN_LOG_PAGE]
 * events when something did. `execution.output` is left with the tin, as the panel that
 * reads this never asked for it.
 *
 * **The local tin's only.** It holds its sessions in memory, so nothing of
 * them survives a restart of this server unless it is kept here. A remote tin
 * is a server of its own that keeps its sessions' logs itself, and is read
 * live; [record] does nothing for one.
 *
 * Synchronised: two callers copying the same session at once would both insert
 * the same sequence numbers, and the second would be refused by the unique key.
 * One process, so one lock is enough.
 */
@Service
class TinSessionLog(
    private val events: TinSessionEventRepository,
    private val outputs: TinSessionOutputRepository,
    private val settings: TinSettings,
    private val mapper: ObjectMapper,
) {

    /** Copies what the tin has beyond what is held. A tin that refuses is the caller's to judge; see [recordQuietly]. */
    @Synchronized
    fun record(row: TinSession, client: TinClient) {
        if (row.tinKind != TinKind.LOCAL) return
        val id = requireNotNull(row.id)
        var since = events.lastSeq(id) ?: 0
        // Ends: every page either holds an event past `since` and moves it on,
        // or ends the copy.
        while (true) {
            val page = client.logs(row.remoteSessionId, since, TIN_LOG_PAGE, null)
            val fresh = page.events.filter { it.seq > since }
            if (fresh.isEmpty()) return
            events.saveAll(fresh.map { TinSessionEvent(tinSessionId = id, seq = it.seq, at = it.at, type = it.type, data = it.data) })
            fresh.filter { it.type == FINISHED }.forEach { keepOutput(row, client, it) }
            since = fresh.maxOf { it.seq }
            if (page.events.size < TIN_LOG_PAGE) return
        }
    }

    /**
     * Keeps what the execution [finished] names printed, up to the setting. A
     * failure here is logged and passed over rather than left to stop the
     * events after it being recorded: the events are the log, the output a
     * convenience beside it.
     */
    private fun keepOutput(row: TinSession, client: TinClient, finished: TinLogEvent) {
        val most = settings.localOutputKeptBytes()
        if (most <= 0) return
        val id = requireNotNull(row.id)
        val execution = runCatching { mapper.readTree(finished.data).path("executionId").stringValue() }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return
        if (outputs.existsByTinSessionIdAndExecutionId(id, execution)) return
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
                TinSessionOutput(
                    tinSessionId = id,
                    executionId = execution,
                    startOffset = start ?: 0,
                    text = String(bytes.toByteArray(), Charsets.UTF_8),
                    truncated = truncated,
                ),
            )
        } catch (failed: TinCallException) {
            log.debug("The output of {} in tin session {} could not be kept: {} {}", execution, row.id, failed.code, failed.message)
        }
    }

    /** What was kept of [executionId]'s output; null when nothing was. */
    fun output(tinSessionId: Long, executionId: String): TinSessionOutput? =
        outputs.findByTinSessionIdAndExecutionId(tinSessionId, executionId)

    /** [record], for a caller that must not fail because the log could not be copied. */
    fun recordQuietly(row: TinSession, client: TinClient) {
        try {
            record(row, client)
        } catch (failed: TinCallException) {
            log.debug("The log of tin session {} could not be copied: {} {}", row.id, failed.code, failed.message)
        } catch (failed: Exception) {
            log.warn("The log of tin session {} could not be copied", row.id, failed)
        }
    }

    /** The recorded events after [after], oldest first, at most [limit]. */
    fun after(tinSessionId: Long, after: Long, limit: Int): List<TinSessionEvent> =
        events.findByTinSessionIdAndSeqGreaterThanOrderBySeqAsc(tinSessionId, after, Limit.of(limit))

    fun any(tinSessionId: Long): Boolean = events.existsByTinSessionId(tinSessionId)

    companion object {
        private val log = LoggerFactory.getLogger(TinSessionLog::class.java)

        const val FINISHED = "execution.finished"
        const val COMBINED = "combined"
    }
}

/** How many log events are asked of a tin at once, and how many one page of a conversation's Tin panel carries. */
const val TIN_LOG_PAGE = 1000
