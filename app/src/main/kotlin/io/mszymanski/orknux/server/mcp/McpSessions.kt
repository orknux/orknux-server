package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tuning
import io.mszymanski.orknux.server.user.AppUserTokenRepository
import io.mszymanski.orknux.server.user.ByToken
import jakarta.annotation.PreDestroy
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** One MCP client's connection to one workspace: who, with what, from where, and when. */
@Entity
@Table(name = "mcp_client_session")
class McpClientSession(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long = 0,

    /** The Mcp-Session-Id the client was handed, or one made up for a client that never sent one. */
    @Column(name = "session_key", nullable = false, length = 64)
    val sessionKey: String = "",

    @Column(nullable = false, length = 255)
    val username: String = "",

    @Column(name = "token_id")
    val tokenId: Long? = null,

    @Column(name = "token_name", length = 255)
    val tokenName: String? = null,

    @Column(name = "client_name", length = 255)
    var clientName: String? = null,

    @Column(name = "client_version", length = 100)
    var clientVersion: String? = null,

    @Column(name = "user_agent", length = 500)
    val userAgent: String? = null,

    @Column(name = "ip_address", nullable = false, length = 64)
    val ipAddress: String = "",

    /** The connection's own address, where a proxy said the client was somewhere else. */
    @Column(name = "peer_address", length = 64)
    val peerAddress: String? = null,

    @Column(length = 255)
    var hostname: String? = null,

    @Column(name = "started_at", nullable = false)
    val startedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "ended_at")
    var endedAt: OffsetDateTime? = null,

    @Column(nullable = false)
    var calls: Int = 0,
)

/** One thing an MCP client asked for, and how it went. */
@Entity
@Table(name = "mcp_call")
class McpCall(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "session_id", nullable = false)
    val sessionId: Long = 0,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long = 0,

    @Column(nullable = false, length = 100)
    val method: String = "",

    @Column(length = 255)
    val tool: String? = null,

    @Column(length = 1000)
    val arguments: String? = null,

    @Column(nullable = false)
    val failed: Boolean = false,

    @Column(length = 500)
    val error: String? = null,

    @Column(name = "duration_ms", nullable = false)
    val durationMs: Long = 0,

    @Column(nullable = false)
    val at: OffsetDateTime = OffsetDateTime.now(),
)

interface McpClientSessionRepository : JpaRepository<McpClientSession, Long>, JpaSpecificationExecutor<McpClientSession> {

    fun findBySessionKey(sessionKey: String): McpClientSession?

    /** The newest open session that looks like this client, for one that never sends its Mcp-Session-Id. */
    fun findFirstByWorkspaceIdAndUsernameAndTokenIdAndIpAddressAndUserAgentAndEndedAtIsNullAndLastSeenAtAfterOrderByLastSeenAtDesc(
        workspaceId: Long,
        username: String,
        tokenId: Long?,
        ipAddress: String,
        userAgent: String?,
        after: OffsetDateTime,
    ): McpClientSession?

    @Modifying
    @Transactional
    @Query("update McpClientSession s set s.hostname = :hostname where s.id = :id")
    fun nameHost(id: Long, hostname: String): Int

    @Modifying
    @Transactional
    @Query("update McpClientSession s set s.lastSeenAt = :at where s.id = :id")
    fun seen(id: Long, at: OffsetDateTime): Int

    @Modifying
    @Transactional
    @Query("update McpClientSession s set s.calls = s.calls + 1, s.lastSeenAt = :at where s.id = :id")
    fun called(id: Long, at: OffsetDateTime): Int

    @Modifying
    @Transactional
    @Query("delete from McpClientSession s where s.lastSeenAt < :before")
    fun forgetSeenBefore(before: OffsetDateTime): Int
}

interface McpCallRepository : JpaRepository<McpCall, Long>, JpaSpecificationExecutor<McpCall> {

    @Modifying
    @Transactional
    @Query("delete from McpCall c where c.at < :before")
    fun forgetBefore(before: OffsetDateTime): Int

    @Modifying
    @Transactional
    @Query("delete from McpCall c where c.sessionId in (select s.id from McpClientSession s where s.lastSeenAt < :before)")
    fun forgetOfSessionsSeenBefore(before: OffsetDateTime): Int
}

/** The session a request was counted under: its row and the key the client carries. */
data class McpSessionRef(val id: Long, val key: String)

/**
 * Keeps the record of who reaches a workspace over MCP: each client's session,
 * from which address and host, and every call it makes - what Admin -> MCP and
 * a workspace's MCP page show.
 *
 * A session is opened by `initialize`, which hands the client an
 * `Mcp-Session-Id` it sends on every request after, as the protocol has it.
 * A client that sends none - or one from before a restart nobody remembers -
 * is matched to the newest open session from the same person, token, address
 * and user agent, or given a new one, so every call is counted somewhere.
 *
 * The host is looked up from the address once, off the request's thread: a
 * reverse lookup can take seconds and the client is not kept waiting for it.
 * History older than Admin -> Settings says is let go of as sessions open.
 */
@Component
class McpSessions(
    private val sessions: McpClientSessionRepository,
    private val calls: McpCallRepository,
    private val tokens: AppUserTokenRepository,
    private val mapper: ObjectMapper,
    private val tuning: Tuning,
) {

    private val log = LoggerFactory.getLogger(McpSessions::class.java)
    private val lookups = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mcp-host-lookup").apply { isDaemon = true }
    }
    private val lastSweep = AtomicReference(OffsetDateTime.MIN)

    @PreDestroy
    fun stop() {
        lookups.shutdownNow()
    }

    /** Opens a session for a client that has just said `initialize`, with what it says it is. */
    fun open(workspaceId: Long, request: HttpServletRequest, params: JsonNode?): McpSessionRef {
        sweepNow()
        val info = params?.path("clientInfo")
        val created = create(
            workspaceId, request, UUID.randomUUID().toString(),
            info?.path("name")?.stringValue(null)?.take(255),
            info?.path("version")?.stringValue(null)?.take(100),
        )
        return McpSessionRef(requireNotNull(created.id), created.sessionKey)
    }

    /**
     * The session this request belongs to, counted as seen now: by the
     * Mcp-Session-Id it carries where that is one of this workspace's and
     * this person's, by what it looks like otherwise.
     */
    fun seen(workspaceId: Long, request: HttpServletRequest): McpSessionRef {
        val now = OffsetDateTime.now()
        val who = signedIn()
        val carried = request.getHeader(HEADER)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 }
        val held = carried?.let { sessions.findBySessionKey(it) }?.takeIf { it.workspaceId == workspaceId && it.username == who }
            ?: sessions.findFirstByWorkspaceIdAndUsernameAndTokenIdAndIpAddressAndUserAgentAndEndedAtIsNullAndLastSeenAtAfterOrderByLastSeenAtDesc(
                workspaceId, who, tokenId(), clientAddress(request), userAgent(request), now.minusMinutes(connectedMinutes()),
            )
        if (held != null) {
            sessions.seen(requireNotNull(held.id), now)
            return McpSessionRef(requireNotNull(held.id), held.sessionKey)
        }
        // A key it carried and nobody knows - a database restored, say - is kept as its key, so it goes on matching.
        val key = carried?.takeIf { sessions.findBySessionKey(it) == null } ?: UUID.randomUUID().toString()
        val created = create(workspaceId, request, key, null, null)
        return McpSessionRef(requireNotNull(created.id), created.sessionKey)
    }

    /** Records one call a session made. Never fails the call it records. */
    fun record(session: McpSessionRef, workspaceId: Long, method: String, tool: String?, arguments: JsonNode?, failed: Boolean, error: String?, durationMs: Long) {
        runCatching {
            val now = OffsetDateTime.now()
            calls.save(
                McpCall(
                    sessionId = session.id,
                    workspaceId = workspaceId,
                    method = method.take(100),
                    tool = tool?.take(255),
                    arguments = arguments?.let(::preview),
                    failed = failed,
                    error = error?.take(500),
                    durationMs = durationMs,
                    at = now,
                ),
            )
            sessions.called(session.id, now)
        }.onFailure { log.warn("Could not record an MCP call: {}", it.message) }
    }

    /** The client said it is done: a `DELETE` with its Mcp-Session-Id. */
    @Transactional
    fun end(workspaceId: Long, request: HttpServletRequest): Boolean {
        val key = request.getHeader(HEADER)?.trim() ?: return false
        val held = sessions.findBySessionKey(key)?.takeIf { it.workspaceId == workspaceId && it.username == signedIn() } ?: return false
        held.endedAt = OffsetDateTime.now()
        sessions.save(held)
        return true
    }

    /** How long since its last request a session still counts as connected. */
    fun connectedMinutes(): Long = tuning[Tunable.MCP_CONNECTED_MINUTES].toLong()

    /** Whether [session] counts as connected now. */
    fun connected(session: McpClientSession, now: OffsetDateTime = OffsetDateTime.now()): Boolean =
        session.endedAt == null && session.lastSeenAt.isAfter(now.minusMinutes(connectedMinutes()))

    /** Lets go of history older than allowed. Answers how many calls went. */
    fun sweep(): Int {
        val before = OffsetDateTime.now().minusDays(tuning[Tunable.MCP_HISTORY_DAYS].toLong())
        val gone = calls.forgetBefore(before) + calls.forgetOfSessionsSeenBefore(before)
        sessions.forgetSeenBefore(before)
        return gone
    }

    private fun sweepNow() {
        val now = OffsetDateTime.now()
        val last = lastSweep.get()
        if (last.isAfter(now.minusHours(1)) || !lastSweep.compareAndSet(last, now)) return
        runCatching { sweep() }.onFailure { log.warn("Could not let go of old MCP history: {}", it.message) }
    }

    private fun create(workspaceId: Long, request: HttpServletRequest, key: String, clientName: String?, clientVersion: String?): McpClientSession {
        val address = clientAddress(request)
        val peer = request.remoteAddr?.take(64)?.takeIf { it != address }
        val tokenId = tokenId()
        val now = OffsetDateTime.now()
        val created = sessions.save(
            McpClientSession(
                workspaceId = workspaceId,
                sessionKey = key,
                username = signedIn().take(255),
                tokenId = tokenId,
                tokenName = tokenId?.let { id -> tokens.findById(id).orElse(null)?.name?.take(255) },
                clientName = clientName,
                clientVersion = clientVersion,
                userAgent = userAgent(request),
                ipAddress = address,
                peerAddress = peer,
                startedAt = now,
                lastSeenAt = now,
            ),
        )
        val id = requireNotNull(created.id)
        lookups.execute {
            runCatching { hostOf(address) }.getOrNull()?.let { sessions.nameHost(id, it.take(255)) }
        }
        return created
    }

    /**
     * What the arguments were, short enough to list: each text cut to a line,
     * so a file sent as base64 is a few characters and not a page, and the
     * whole cut to what the column holds.
     */
    private fun preview(arguments: JsonNode): String {
        val shortened = shorten(arguments)
        return mapper.writeValueAsString(shortened).let { if (it.length > PREVIEW) it.take(PREVIEW - 1) + "…" else it }
    }

    private fun shorten(node: JsonNode): Any? = when {
        node.isObject -> node.properties().associate { (name, value) -> name to shorten(value) }
        node.isArray -> node.values().map(::shorten)
        node.isString -> node.stringValue().let { if (it.length > TEXT) it.take(TEXT) + "… (${it.length} characters)" else it }
        node.isNull -> null
        node.isBoolean -> node.booleanValue()
        node.isNumber -> node.numberValue()
        else -> node.toString()
    }

    companion object {
        const val HEADER = "Mcp-Session-Id"
        private const val PREVIEW = 1000
        private const val TEXT = 120

        private fun signedIn(): String = SecurityContextHolder.getContext().authentication?.name.orEmpty()

        private fun tokenId(): Long? = (SecurityContextHolder.getContext().authentication?.details as? ByToken)?.tokenId

        private fun userAgent(request: HttpServletRequest): String? = request.getHeader("User-Agent")?.take(500)

        /** The client's address: the first X-Forwarded-For hop where a proxy wrote one, the connection's otherwise. */
        fun clientAddress(request: HttpServletRequest): String =
            request.getHeader("X-Forwarded-For")?.split(',')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.take(64)
                ?: request.remoteAddr?.take(64).orEmpty()

        /** The address's name, where a reverse lookup finds one that is not the address itself. */
        fun hostOf(address: String): String? {
            if (address.isBlank()) return null
            // A literal only: the address may be whatever a client wrote in X-Forwarded-For, and a name would be looked up.
            val named = InetAddress.ofLiteral(address).canonicalHostName
            return named.takeIf { it.isNotBlank() && it != address }
        }
    }
}
