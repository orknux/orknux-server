package io.mszymanski.orknux.connector.connection

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Somebody pressing Reconnect on a Slack connection, written down where every
 * server instance reads it.
 *
 * A socket belongs to the process that opened it, and an installation with two
 * replicas has two - one per replica, each holding its own. The press reaches
 * one of them, so the press is recorded rather than only acted on, and every
 * [SlackListener] compares it with the session it holds on its next pass.
 *
 * **A generation, not a time.** Each press adds one, and a session remembers the
 * generation it was opened under; a newer one is a request it has not honoured.
 * A timestamp compared with when a session opened would be one replica's clock
 * against another's, and a replica a few seconds behind would honour the same
 * press twice or not at all.
 */
interface SlackReconnectRequests {

    /** The newest generation of every connection somebody has asked about. Absent is zero. */
    fun generations(): Map<Long, Long>

    /** Records one more request for this connection, and answers its generation. */
    fun request(connectionId: Long): Long
}

/** One row per connection that has ever been reconnected. */
@Entity
@Table(name = "slack_reconnect_request")
class SlackReconnectRequest(
    @Id
    @Column(name = "connection_id")
    val connectionId: Long = 0,

    @Column(nullable = false)
    var generation: Long = 0,

    @Column(name = "requested_at", nullable = false)
    var requestedAt: OffsetDateTime = OffsetDateTime.now(),
)

interface SlackReconnectRequestRepository : JpaRepository<SlackReconnectRequest, Long> {

    /** One more, in the database, so two presses on two replicas are two and not one. */
    @Modifying
    @Query(
        "update SlackReconnectRequest r set r.generation = r.generation + 1, r.requestedAt = :at " +
            "where r.connectionId = :connectionId",
    )
    fun bump(@Param("connectionId") connectionId: Long, @Param("at") at: OffsetDateTime): Int
}

/** The requests as the database holds them, which is what makes them every replica's. */
@Component
class StoredSlackReconnectRequests(
    private val repository: SlackReconnectRequestRepository,
    transactionManager: PlatformTransactionManager,
) : SlackReconnectRequests {

    private val transactions = TransactionTemplate(transactionManager)

    override fun generations(): Map<Long, Long> =
        repository.findAll().associate { it.connectionId to it.generation }

    override fun request(connectionId: Long): Long {
        val at = OffsetDateTime.now()
        // The first press inserts and every later one adds to it. Two first
        // presses at once collide on the key, and the loser simply adds.
        val bumped = transactions.execute { repository.bump(connectionId, at) } ?: 0
        if (bumped == 0) {
            try {
                transactions.execute { repository.saveAndFlush(SlackReconnectRequest(connectionId, 1, at)) }
            } catch (_: DataIntegrityViolationException) {
                transactions.execute { repository.bump(connectionId, at) }
            }
        }
        return repository.findById(connectionId).map { it.generation }.orElse(1)
    }
}

/**
 * The same, held in one process's memory.
 *
 * What a listener built by hand gets when it is handed nothing: a test, which
 * shares one of these between two listeners to stand for two replicas reading
 * one database.
 */
class InMemorySlackReconnectRequests : SlackReconnectRequests {

    private val held = ConcurrentHashMap<Long, Long>()

    override fun generations(): Map<Long, Long> = held.toMap()

    override fun request(connectionId: Long): Long = held.merge(connectionId, 1L, Long::plus)!!
}
