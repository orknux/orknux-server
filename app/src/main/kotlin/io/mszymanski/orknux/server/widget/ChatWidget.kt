package io.mszymanski.orknux.server.widget

import io.mszymanski.orknux.server.graphql.Refusal
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

/**
 * Something small and live an agent keeps in a chat, drawn above the composer:
 * a timer, a stopwatch.
 *
 * One row per widget with its kind's state as JSON, because each kind keeps a
 * different shape and adding a kind should be a [WidgetKind], not a migration.
 * The state holds moments rather than counts - when a timer ends, when a
 * stopwatch started - so nothing ticks on the server and every reader works
 * out the time left from the clock it has.
 */
@Entity
@Table(name = "chat_widget")
class ChatWidget(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "chat_session_id", nullable = false)
    val chatSessionId: Long,

    @Column(nullable = false, length = 32)
    val kind: String,

    @Column(nullable = false, length = 200)
    var label: String,

    @Column(nullable = false, columnDefinition = "text")
    var state: String,

    @Column(name = "created_by", nullable = false)
    val createdBy: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)

interface ChatWidgetRepository : JpaRepository<ChatWidget, Long> {
    fun findByChatSessionIdOrderByIdAsc(chatSessionId: Long): List<ChatWidget>

    fun countByChatSessionId(chatSessionId: Long): Long
}

/**
 * A widget as every reader is handed it - the agent's tools, the chat's stream
 * and the page's query alike, so the three cannot describe it differently.
 *
 * The moments ([endsAt], [startedAt]) are what a page ticks from; the counts
 * beside them ([remainingSeconds], [elapsedMillis]) are as of [asOf], for a
 * model that has no clock to work them out with.
 */
data class WidgetView(
    val id: Long,
    val kind: String,
    val label: String,
    val createdBy: String,
    val running: Boolean,
    val done: Boolean,
    val asOf: String,
    /** A timer's length, in seconds. */
    val durationSeconds: Long? = null,
    /** When a running timer reaches nought. Null while it is paused or done. */
    val endsAt: String? = null,
    val remainingSeconds: Long? = null,
    /** Whether the agent is told when the timer ends. */
    val wake: Boolean? = null,
    /** When a running stopwatch last started. Null while it is paused. */
    val startedAt: String? = null,
    /** A stopwatch's time while paused, and what it had before [startedAt] while running. */
    val accumulatedMillis: Long? = null,
    val elapsedMillis: Long? = null,
    /** A stopwatch's laps, as the elapsed time each was taken at. */
    val laps: List<Long>? = null,
)

/** Something asked of a widget that cannot be done, said so it can be put right. */
class WidgetRefusedException(message: String) : RuntimeException(message), Refusal {
    override val arguments get() = emptyMap<String, Any?>()
}

class ChatWidgetNotFoundException(val id: Long) : RuntimeException("This chat has no widget $id"), Refusal {
    override val arguments get() = mapOf("id" to id)
}
