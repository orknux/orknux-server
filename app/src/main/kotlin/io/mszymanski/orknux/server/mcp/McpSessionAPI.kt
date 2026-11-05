package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import java.time.OffsetDateTime

/**
 * Who reaches a workspace over MCP, from where, and what they called - the
 * record [McpSessions] keeps, read.
 *
 * Asked without a workspace it is every workspace's, and only an administrator
 * may ask that. Asked about one workspace it is that workspace's alone, for
 * somebody who administers it: the page names people, their addresses and
 * what they passed to tools, which is more than everybody who can open the
 * workspace should read about everybody else.
 */
@Controller
class McpSessionAPI(
    private val sessions: McpClientSessionRepository,
    private val calls: McpCallRepository,
    private val recorder: McpSessions,
    private val workspaces: WorkspaceRepository,
    private val access: WorkspaceAccess,
) {

    @QueryMapping
    fun mcpSessions(
        @Argument workspaceId: Long?,
        @Argument connectedOnly: Boolean?,
        @Argument page: Int?,
        @Argument size: Int?,
    ): McpSessionPage {
        allowed(workspaceId)
        val now = OffsetDateTime.now()
        val since = now.minusMinutes(recorder.connectedMinutes())
        val where = inWorkspace<McpClientSession>(workspaceId)
        val connected = where.and { root, _, builder ->
            builder.and(builder.isNull(root.get<OffsetDateTime>("endedAt")), builder.greaterThan(root.get("lastSeenAt"), since))
        }
        val found = sessions.findAll(
            if (connectedOnly == true) connected else where,
            PageRequest.of(page ?: 0, (size ?: DEFAULT_SIZE).coerceIn(1, MOST), Sort.by(Sort.Order.desc("lastSeenAt"), Sort.Order.desc("id"))),
        )
        val names = namesOf(found.content.map { it.workspaceId })
        return McpSessionPage(
            content = found.content.map { McpSessionView.of(it, names[it.workspaceId], recorder.connected(it, now)) },
            page = found.number,
            size = found.size,
            totalElements = found.totalElements.toInt(),
            totalPages = found.totalPages,
            connectedCount = sessions.count(connected).toInt(),
        )
    }

    @QueryMapping
    fun mcpCalls(
        @Argument workspaceId: Long?,
        @Argument sessionId: Long?,
        @Argument failedOnly: Boolean?,
        @Argument page: Int?,
        @Argument size: Int?,
    ): McpCallPage {
        // A session asked about by itself is checked against the workspace it belongs to.
        val session = sessionId?.let { sessions.findById(it).orElse(null) }
        val scope = workspaceId ?: session?.workspaceId
        allowed(scope)
        if (session != null && workspaceId != null && session.workspaceId != workspaceId) {
            return McpCallPage(emptyList(), page ?: 0, size ?: DEFAULT_SIZE, 0, 0)
        }
        var where = inWorkspace<McpCall>(scope)
        if (sessionId != null) where = where.and { root, _, builder -> builder.equal(root.get<Long>("sessionId"), sessionId) }
        if (failedOnly == true) where = where.and { root, _, builder -> builder.isTrue(root.get("failed")) }
        val found: Page<McpCall> = calls.findAll(
            where,
            PageRequest.of(page ?: 0, (size ?: DEFAULT_SIZE).coerceIn(1, MOST), Sort.by(Sort.Order.desc("at"), Sort.Order.desc("id"))),
        )
        val owners = sessions.findAllById(found.content.map { it.sessionId }.toSet()).associateBy { it.id }
        val names = namesOf(found.content.map { it.workspaceId })
        return McpCallPage(
            content = found.content.map { McpCallView.of(it, owners[it.sessionId], names[it.workspaceId]) },
            page = found.number,
            size = found.size,
            totalElements = found.totalElements.toInt(),
            totalPages = found.totalPages,
        )
    }

    private fun allowed(workspaceId: Long?) {
        if (workspaceId == null) access.requireAdmin() else access.requireAdministers(workspaceId)
    }

    private fun <T : Any> inWorkspace(workspaceId: Long?): Specification<T> = Specification { root, _, builder ->
        if (workspaceId == null) builder.conjunction() else builder.equal(root.get<Long>("workspaceId"), workspaceId)
    }

    private fun namesOf(ids: Collection<Long>): Map<Long, String> =
        workspaces.findAllById(ids.toSet()).associate { requireNotNull(it.id) to it.name }

    private companion object {
        const val DEFAULT_SIZE = 25
        const val MOST = 200
    }
}

data class McpSessionView(
    val id: Long,
    val workspaceId: Long,
    val workspaceName: String?,
    val username: String,
    val tokenName: String?,
    val clientName: String?,
    val clientVersion: String?,
    val userAgent: String?,
    val ipAddress: String,
    val peerAddress: String?,
    val hostname: String?,
    val startedAt: String,
    val lastSeenAt: String,
    val endedAt: String?,
    val connected: Boolean,
    val calls: Int,
) {
    companion object {
        fun of(session: McpClientSession, workspaceName: String?, connected: Boolean) = McpSessionView(
            id = requireNotNull(session.id),
            workspaceId = session.workspaceId,
            workspaceName = workspaceName,
            username = session.username,
            tokenName = session.tokenName,
            clientName = session.clientName,
            clientVersion = session.clientVersion,
            userAgent = session.userAgent,
            ipAddress = session.ipAddress,
            peerAddress = session.peerAddress,
            hostname = session.hostname,
            startedAt = session.startedAt.toString(),
            lastSeenAt = session.lastSeenAt.toString(),
            endedAt = session.endedAt?.toString(),
            connected = connected,
            calls = session.calls,
        )
    }
}

data class McpSessionPage(
    val content: List<McpSessionView>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
    val connectedCount: Int,
)

data class McpCallView(
    val id: Long,
    val sessionId: Long,
    val workspaceId: Long,
    val workspaceName: String?,
    val username: String,
    val clientName: String?,
    val ipAddress: String,
    val hostname: String?,
    val method: String,
    val tool: String?,
    val arguments: String?,
    val failed: Boolean,
    val error: String?,
    val durationMs: Int,
    val at: String,
) {
    companion object {
        fun of(call: McpCall, session: McpClientSession?, workspaceName: String?) = McpCallView(
            id = requireNotNull(call.id),
            sessionId = call.sessionId,
            workspaceId = call.workspaceId,
            workspaceName = workspaceName,
            username = session?.username.orEmpty(),
            clientName = session?.clientName,
            ipAddress = session?.ipAddress.orEmpty(),
            hostname = session?.hostname,
            method = call.method,
            tool = call.tool,
            arguments = call.arguments,
            failed = call.failed,
            error = call.error,
            durationMs = call.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            at = call.at.toString(),
        )
    }
}

data class McpCallPage(
    val content: List<McpCallView>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
)
