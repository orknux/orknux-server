package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.domain.Specification
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.CriteriaQuery
import jakarta.persistence.criteria.Expression
import jakarta.persistence.criteria.Root
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
        @Argument order: McpSessionOrder?,
        @Argument ascending: Boolean?,
    ): McpSessionPage {
        allowed(workspaceId)
        val now = OffsetDateTime.now()
        val since = now.minusMinutes(recorder.connectedMinutes())
        val where = inWorkspace<McpClientSession>(workspaceId)
        val connected = where.and { root, _, builder ->
            builder.and(builder.isNull(root.get<OffsetDateTime>("endedAt")), builder.greaterThan(root.get("lastSeenAt"), since))
        }
        val by = order ?: McpSessionOrder.LAST_SEEN
        val found = sessions.findAll(
            ordered(if (connectedOnly == true) connected else where, ascending ?: (by != McpSessionOrder.LAST_SEEN && by != McpSessionOrder.CALLS)) { root, query, builder ->
                when (by) {
                    // Connected, idle, ended - the order the pills mean - and the most recent first within each.
                    McpSessionOrder.STATUS -> listOf(
                        builder.selectCase<Int>()
                            .`when`(builder.isNotNull(root.get<OffsetDateTime>("endedAt")), 2)
                            .`when`(builder.greaterThan(root.get("lastSeenAt"), since), 0)
                            .otherwise(1),
                    )
                    McpSessionOrder.CLIENT -> listOf(builder.lower(builder.coalesce(root.get<String>("clientName"), "")), root.get<String>("clientVersion"))
                    McpSessionOrder.PERSON -> listOf(builder.lower(root.get("username")), root.get<String>("tokenName"))
                    McpSessionOrder.ADDRESS -> listOf(root.get<String>("ipAddress"))
                    McpSessionOrder.WORKSPACE -> listOf(workspaceName(root, query, builder))
                    McpSessionOrder.LAST_SEEN -> listOf(root.get<OffsetDateTime>("lastSeenAt"))
                    McpSessionOrder.CALLS -> listOf(root.get<Int>("calls"))
                }
            },
            PageRequest.of(page ?: 0, (size ?: DEFAULT_SIZE).coerceIn(1, MOST)),
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
        @Argument order: McpCallOrder?,
        @Argument ascending: Boolean?,
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
        val by = order ?: McpCallOrder.AT
        val found: Page<McpCall> = calls.findAll(
            ordered(where, ascending ?: (by != McpCallOrder.AT && by != McpCallOrder.DURATION && by != McpCallOrder.RESULT)) { root, query, builder ->
                when (by) {
                    McpCallOrder.AT -> listOf(root.get<OffsetDateTime>("at"))
                    McpCallOrder.CALLED -> listOf(builder.lower(builder.coalesce(root.get<String>("tool"), root.get<String>("method"))))
                    McpCallOrder.PERSON -> listOf(ofSession(root, query, builder, "username"))
                    McpCallOrder.ADDRESS -> listOf(ofSession(root, query, builder, "ipAddress"))
                    McpCallOrder.WORKSPACE -> listOf(workspaceName(root, query, builder))
                    McpCallOrder.RESULT -> listOf(root.get<Boolean>("failed"))
                    McpCallOrder.DURATION -> listOf(root.get<Long>("durationMs"))
                }
            },
            PageRequest.of(page ?: 0, (size ?: DEFAULT_SIZE).coerceIn(1, MOST)),
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

    /**
     * [where], put in the order of [keys] and then the newest row first.
     *
     * In the specification rather than a `Sort`, because three of the columns
     * are not this table's: a workspace is read by its name and a call by its
     * session's person and address, which a `Sort` on a property cannot reach.
     * Left off the count query, which has nothing to order.
     */
    private fun <T : Any> ordered(
        where: Specification<T>,
        ascending: Boolean,
        keys: (Root<T>, CriteriaQuery<*>, CriteriaBuilder) -> List<Expression<*>>,
    ): Specification<T> = Specification { root, query, builder ->
        if (query.resultType != Long::class.javaObjectType) {
            val orders = keys(root, query, builder).map { if (ascending) builder.asc(it) else builder.desc(it) }
            query.orderBy(orders + builder.desc(root.get<Long>("id")))
        }
        where.toPredicate(root, query, builder)
    }

    private fun <T : Any> workspaceName(root: Root<T>, query: CriteriaQuery<*>, builder: CriteriaBuilder): Expression<String> {
        val name = query.subquery(String::class.java)
        val workspace = name.from(Workspace::class.java)
        return name.select(builder.lower(workspace.get("name")))
            .where(builder.equal(workspace.get<Long>("id"), root.get<Long>("workspaceId")))
    }

    private fun ofSession(root: Root<McpCall>, query: CriteriaQuery<*>, builder: CriteriaBuilder, field: String): Expression<String> {
        val held = query.subquery(String::class.java)
        val session = held.from(McpClientSession::class.java)
        return held.select(builder.lower(session.get(field)))
            .where(builder.equal(session.get<Long>("id"), root.get<Long>("sessionId")))
    }

    private fun namesOf(ids: Collection<Long>): Map<Long, String> =
        workspaces.findAllById(ids.toSet()).associate { requireNotNull(it.id) to it.name }

    private companion object {
        const val DEFAULT_SIZE = 25
        const val MOST = 200
    }
}

/** A column of Admin -> MCP's sessions table, to put the sessions in the order of. */
enum class McpSessionOrder { STATUS, CLIENT, PERSON, ADDRESS, WORKSPACE, LAST_SEEN, CALLS }

/** A column of its history, to put the calls in the order of; the arguments are not one. */
enum class McpCallOrder { AT, CALLED, PERSON, ADDRESS, WORKSPACE, RESULT, DURATION }

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
