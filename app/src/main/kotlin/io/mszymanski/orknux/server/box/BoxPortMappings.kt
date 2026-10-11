package io.mszymanski.orknux.server.box

import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.interaction.InteractionHandler
import io.mszymanski.orknux.server.interaction.InteractionOption
import io.mszymanski.orknux.server.interaction.InteractionRequest
import io.mszymanski.orknux.server.interaction.Interactions
import io.mszymanski.orknux.server.llm.SessionEventKind
import io.mszymanski.orknux.server.llm.SessionInbox
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Where a port an agent asked for stands. Nothing is opened until a person
 * says so: PENDING is asked and not answered, REFUSED said no, ENABLED is
 * forwarded now, DISABLED was and is not.
 */
enum class BoxPortState { PENDING, ENABLED, DISABLED, REFUSED }

/** A port an agent asked to open out of a box session, and what a person made of it. */
@Entity
@Table(name = "box_port")
class BoxPortMapping(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long = 0,

    @Column(name = "llm_session_id", nullable = false)
    val llmSessionId: Long = 0,

    /** The box session the port is in: a tin_session row. */
    @Column(name = "tin_session_id", nullable = false)
    val boxSessionId: Long = 0,

    @Column(name = "container_port", nullable = false)
    val containerPort: Int = 0,

    /** What the agent said it was for. */
    @Column(columnDefinition = "text")
    val reason: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var state: BoxPortState = BoxPortState.PENDING,

    @Column(name = "host_port")
    var hostPort: Int? = null,

    @Column(columnDefinition = "text")
    var url: String? = null,

    /** Set when the address opens only on the box's own machine; see [BoxPorts.of]. */
    @Column(columnDefinition = "text")
    var note: String? = null,

    @Column(name = "requested_by", length = 255)
    val requestedBy: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "decided_at")
    var decidedAt: OffsetDateTime? = null,

    @Column(name = "decided_by", length = 255)
    var decidedBy: String? = null,
)

interface BoxPortMappingRepository : JpaRepository<BoxPortMapping, Long> {
    fun findByLlmSessionIdOrderByCreatedAtAscIdAsc(llmSessionId: Long): List<BoxPortMapping>

    fun findByBoxSessionIdOrderByCreatedAtAscIdAsc(boxSessionId: Long): List<BoxPortMapping>

    fun findByBoxSessionIdAndContainerPort(boxSessionId: Long, containerPort: Int): BoxPortMapping?
}

class BoxPortNotFoundException(val id: Long) : RuntimeException("No exposed port with id $id"), Refusal {
    override val arguments get() = mapOf("id" to id)
}

class BoxPortSessionClosedException(val port: Int) : RuntimeException(
    "Port $port cannot be opened: its box session is not running any more",
), Refusal {
    override val arguments get() = mapOf("port" to port)
}

class BoxPortUnreachableException(val port: Int, val why: String) : RuntimeException(
    "Port $port could not be opened: $why",
), Refusal {
    override val arguments get() = mapOf("port" to port, "why" to why)
}

/**
 * The ports agents ask to open out of their box sessions: asked, decided by a
 * person, opened and closed on the box, and said back to the agent.
 *
 * Every change a person makes goes through here, from the session page's
 * Ports panel and from the question in the chat alike, so the two cannot come
 * to disagree; the question's line is kept in step through
 * [Interactions.settled].
 */
@Service
class ExposedPorts(
    private val mappings: BoxPortMappingRepository,
    private val sessions: BoxSessionRepository,
    private val boxes: Boxes,
    private val settings: BoxSettings,
    private val inbox: SessionInbox,
    private val auditRecorder: WorkspaceAuditRecorder,
    /** A provider, because the handler that answers questions about ports is built on this. */
    private val interactions: ObjectProvider<Interactions>,
) {
    /** What the last look at each enabled port found, and when; see [alive]. */
    private val looked = ConcurrentHashMap<Long, Pair<Boolean, OffsetDateTime>>()

    /**
     * An agent's request for [port] in [row]: a PENDING mapping, and a question
     * in the conversation. Asking again for a port already asked about answers
     * that mapping rather than asking twice.
     */
    @Transactional
    fun request(row: BoxSession, port: Int, reason: String?, agentName: String): Pair<BoxPortMapping, Boolean> {
        val sessionId = requireNotNull(row.id)
        mappings.findByBoxSessionIdAndContainerPort(sessionId, port)?.let { return it to false }
        val live = mappings.findByBoxSessionIdOrderByCreatedAtAscIdAsc(sessionId)
            .count { it.state == BoxPortState.PENDING || it.state == BoxPortState.ENABLED }
        val most = settings.maxPorts()
        if (live >= most) {
            throw BoxTools.Refused(
                "This session already has $live of the $most ports it may have asked for or open. Close one with " +
                    "the person's help on the session page, or open a new session.",
            )
        }
        val mapping = mappings.save(
            BoxPortMapping(
                workspaceId = row.workspaceId,
                llmSessionId = row.llmSessionId,
                boxSessionId = sessionId,
                containerPort = port,
                reason = reason,
                requestedBy = agentName,
            ),
        )
        interactions.getObject().ask(
            workspaceId = row.workspaceId,
            llmSessionId = row.llmSessionId,
            kind = BoxPortRequests.KIND,
            subjectId = mapping.id,
            askedBy = agentName,
            question = "$agentName wants to expose port $port of box session ${row.id} on ${row.boxName}" +
                (reason?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""),
            options = listOf(
                InteractionOption(BoxPortRequests.ALLOW, "Allow", "primary"),
                InteractionOption(BoxPortRequests.REJECT, "Reject", "danger"),
            ),
        )
        auditRecorder.recordAutomated(
            row.workspaceId,
            WorkspaceAuditCategory.AGENT,
            "$agentName asked to expose port $port of box session ${row.id}",
            agentName,
        )
        return mapping to true
    }

    fun of(llmSessionId: Long): List<BoxPortMapping> = mappings.findByLlmSessionIdOrderByCreatedAtAscIdAsc(llmSessionId)

    fun of(row: BoxSession): List<BoxPortMapping> = mappings.findByBoxSessionIdOrderByCreatedAtAscIdAsc(requireNotNull(row.id))

    fun find(id: Long): BoxPortMapping = mappings.findByIdOrNull(id) ?: throw BoxPortNotFoundException(id)

    /** Opens the forward on the box, and tells the agent where it is. */
    @Transactional
    fun enable(mapping: BoxPortMapping, by: String): String {
        val row = sessions.findByIdOrNull(mapping.boxSessionId) ?: throw BoxPortSessionClosedException(mapping.containerPort)
        if (!row.open) throw BoxPortSessionClosedException(mapping.containerPort)
        val box = boxes.byId(row.boxRef) ?: throw BoxPortSessionClosedException(mapping.containerPort)
        val published = try {
            boxes.client(box).forward(row.remoteSessionId, mapping.containerPort)
        } catch (failed: BoxCallException) {
            throw BoxPortUnreachableException(mapping.containerPort, failed.message ?: failed.code)
        }
        val port = BoxPorts.of(box, published)
        mapping.state = BoxPortState.ENABLED
        mapping.hostPort = published.hostPort
        mapping.url = port.url
        mapping.note = port.note
        decided(mapping, by)
        looked.remove(mapping.id)
        val outcome = "Allowed - ${port.url}"
        told(
            mapping,
            "Port ${mapping.containerPort} of box session ${row.id} is now reachable at ${port.url}" +
                (port.note?.let { " ($it)" } ?: "") + ". $by opened it. Serve on 0.0.0.0 or 127.0.0.1 inside the session.",
        )
        settle(mapping, outcome, by, "Port ${mapping.containerPort} of box session ${row.id} exposed at ${port.url}")
        return outcome
    }

    /** Says no to a request; the row stays, so it can still be enabled later. */
    @Transactional
    fun refuse(mapping: BoxPortMapping, by: String): String {
        mapping.state = BoxPortState.REFUSED
        decided(mapping, by)
        told(mapping, "$by did not allow port ${mapping.containerPort} of box session ${mapping.boxSessionId} to be exposed.")
        settle(mapping, "Rejected", by, "Exposing port ${mapping.containerPort} of box session ${mapping.boxSessionId} refused")
        return "Rejected"
    }

    /** Closes the forward and keeps the row. */
    @Transactional
    fun disable(mapping: BoxPortMapping, by: String): String {
        close(mapping)
        mapping.state = BoxPortState.DISABLED
        decided(mapping, by)
        told(mapping, "Port ${mapping.containerPort} of box session ${mapping.boxSessionId} was closed by $by.")
        settle(mapping, "Disabled", by, "Port ${mapping.containerPort} of box session ${mapping.boxSessionId} closed")
        return "Disabled"
    }

    /** Closes the forward and forgets the mapping. */
    @Transactional
    fun delete(mapping: BoxPortMapping, by: String) {
        val wasOpen = mapping.state == BoxPortState.ENABLED
        close(mapping)
        if (wasOpen || mapping.state == BoxPortState.PENDING) {
            told(mapping, "Port ${mapping.containerPort} of box session ${mapping.boxSessionId} was removed by $by; it is not exposed.")
        }
        interactions.getObject().settled(BoxPortRequests.KIND, requireNotNull(mapping.id), "Removed", by)
        auditRecorder.record(
            mapping.workspaceId,
            WorkspaceAuditCategory.AGENT,
            "Exposed port ${mapping.containerPort} of box session ${mapping.boxSessionId} removed",
        )
        looked.remove(mapping.id)
        mappings.delete(mapping)
    }

    /** The session closed: its forwards went with it, and the rows say so. */
    @Transactional
    fun closed(row: BoxSession) {
        of(row).filter { it.state == BoxPortState.ENABLED }.forEach { mapping ->
            mapping.state = BoxPortState.DISABLED
            mappings.save(mapping)
            interactions.getObject().settled(BoxPortRequests.KIND, requireNotNull(mapping.id), "Closed with the session", "orknux")
        }
    }

    /**
     * Whether an enabled port answers: a connection to its forward that the box
     * keeps open, rather than closing at once because nothing listens inside.
     * Looked at no more often than the Box Port Check setting says - a look is a
     * `docker exec` on the box - and a pending or disabled port is not alive
     * without looking.
     */
    fun alive(mapping: BoxPortMapping): Pair<Boolean, OffsetDateTime?> {
        if (mapping.state != BoxPortState.ENABLED || mapping.url == null || mapping.hostPort == null) return false to null
        val id = requireNotNull(mapping.id)
        looked[id]?.let { (alive, at) ->
            if (at.isAfter(OffsetDateTime.now().minusSeconds(settings.portCheckSeconds().toLong()))) return alive to at
        }
        val host = runCatching { URI(requireNotNull(mapping.url)).host }.getOrNull() ?: return false to null
        val wait = settings.portCheckWaitMillis()
        val alive = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, requireNotNull(mapping.hostPort)), wait)
                socket.soTimeout = wait
                try {
                    socket.getInputStream().read() != -1
                } catch (_: SocketTimeoutException) {
                    // Held open and silent: something inside took the connection and waits for a request.
                    true
                }
            }
        }.getOrDefault(false)
        val now = OffsetDateTime.now()
        looked[id] = alive to now
        return alive to now
    }

    private fun close(mapping: BoxPortMapping) {
        if (mapping.state != BoxPortState.ENABLED) return
        val row = sessions.findByIdOrNull(mapping.boxSessionId) ?: return
        val box = boxes.byId(row.boxRef) ?: return
        if (row.open) runCatching { boxes.client(box).unforward(row.remoteSessionId, mapping.containerPort) }
    }

    private fun decided(mapping: BoxPortMapping, by: String) {
        mapping.decidedAt = OffsetDateTime.now()
        mapping.decidedBy = by
        mappings.save(mapping)
    }

    /** Through the inbox, which also wakes a conversation that is not running. */
    private fun told(mapping: BoxPortMapping, text: String) =
        inbox.post(mapping.llmSessionId, SessionEventKind.ANSWER, text)

    private fun settle(mapping: BoxPortMapping, outcome: String, by: String, audit: String) {
        interactions.getObject().settled(BoxPortRequests.KIND, requireNotNull(mapping.id), outcome, by)
        auditRecorder.record(mapping.workspaceId, WorkspaceAuditCategory.AGENT, audit)
    }
}

/** The first kind of question: may this port be exposed. */
@Component
class BoxPortRequests(private val ports: ExposedPorts) : InteractionHandler {
    override val kind = KIND

    override fun answered(request: InteractionRequest, option: String, by: String): String {
        val mapping = ports.find(requireNotNull(request.subjectId))
        return when (option) {
            ALLOW -> ports.enable(mapping, by)
            else -> ports.refuse(mapping, by)
        }
    }

    companion object {
        const val KIND = "box.port"
        const val ALLOW = "allow"
        const val REJECT = "reject"
    }
}

