package io.mszymanski.orknux.server.workspace

import io.mszymanski.orknux.server.security.WorkspaceAccess
import org.springframework.data.domain.Page
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

@Controller
class WorkspaceAuditAPI(
    private val repository: WorkspaceAuditRepository,
    private val workspaces: WorkspaceRepository,
    private val access: WorkspaceAccess,
) {

    /**
     * The admin audit view.
     *
     * It shows what happened at admin level — workspaces appearing, being
     * renamed, going away, and anything that belongs to no workspace — and not what
     * a workspace did inside itself. A workflow saved or a condition renamed is the
     * workspace's own business, and its own audit log has it.
     *
     * Asking for one workspace is the exception: that is the workspace's log, reached
     * through the same query, so it shows everything.
     */
    /**
     * The columns this feed can be put in the order of. Issue #358.
     *
     * The id breaks every tie for the reason the note on `workspaceAudit` gives:
     * two entries written in the same moment sort alike by date, and the key is
     * the only thing that orders them the same way twice.
     */
    private val AUDIT_ORDERS = mapOf(
        "ACTION" to listOf("message", "id"),
        "USER" to listOf("userId", "date", "id"),
        "AT" to listOf("date", "id"),
    )

    @QueryMapping
    fun workspaceAudit(
        @Argument workspaceId: Long?,
        @Argument page: Int?,
        @Argument size: Int?,
        @Argument search: String?,
        @Argument category: WorkspaceAuditCategory?,
        @Argument userId: String?,
        @Argument days: Int?,
        @Argument order: String?,
        @Argument ascending: Boolean?,
    ): WorkspaceAuditPage {
        /*
         * Newest first, and `id` to break a tie.
         *
         * Two entries written in the same moment sort by date alike, and what
         * came back then was whatever the engine felt like - so one feed could
         * come back in a different order on the next page load. SQLite is where
         * it shows: it keeps less of a timestamp than Postgres does, so two
         * writes a few hundred microseconds apart land on one value there and
         * stay distinct here. It went red on CI and green on every machine that
         * ran it by hand, which is the shape of this class of bug.
         *
         * The key is monotonic, so within one instant it orders them the way
         * the date orders them everywhere else.
         */
        val pageable = pageRequest(page, size, sortBy(order, ascending, AUDIT_ORDERS, "AT", fallbackAscending = false))
        val since = days?.takeIf { it > 0 }?.let { OffsetDateTime.now().minusDays(it.toLong()) }
        val term = search?.trim()?.ifEmpty { null }

        val workspaceIds = when {
            workspaceId != null -> {
                val workspace = workspaces.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
                access.requireVisible(workspace)
                listOf(workspaceId)
            }
            access.isAdmin() -> null
            // Entries for workspaces that no longer exist can no longer be matched to a
            // directory group, so they stay with administrators.
            else -> workspaces.findAll().filter(access::canSee).mapNotNull { it.id }
        }

        val filter = auditFilter(
            workspaceIds = workspaceIds,
            category = category,
            userId = userId?.ifEmpty { null },
            since = since,
            search = term,
            adminOnly = workspaceId == null,
        )
        return WorkspaceAuditPage(repository.findAll(filter, pageable))
    }

    /**
     * The users who appear in the admin audit log, for the filter.
     *
     * Admin-level entries carry no workspace, so they stay with administrators,
     * and so do entries of a workspace that is gone: nobody else can be matched
     * to it. Sorted here rather than by the database, so the order is the same
     * on both engines whatever collation either was set up with.
     */
    @QueryMapping
    fun auditUsers(): List<String> {
        val users = if (access.isAdmin()) {
            repository.findAllUserIds()
        } else {
            val visible = workspaces.findAll().filter(access::canSee).mapNotNull { it.id }
            if (visible.isEmpty()) emptyList() else repository.findUserIdsIn(visible)
        }
        return users.distinct().sorted()
    }

    /** The workspace audit view: everything that happened inside one workspace. */
    @QueryMapping
    fun workspaceActivity(
        @Argument workspaceId: Long,
        @Argument page: Int?,
        @Argument size: Int?,
        @Argument search: String?,
        @Argument category: WorkspaceAuditCategory?,
        @Argument userId: String?,
        @Argument days: Int?,
        @Argument order: String?,
        @Argument ascending: Boolean?,
    ): WorkspaceAuditPage {
        val workspace = workspaces.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)

        /*
         * The same tie, and the same columns, as the installation's own log.
         */
        val pageable = pageRequest(page, size, sortBy(order, ascending, AUDIT_ORDERS, "AT", fallbackAscending = false))
        val since = days?.takeIf { it > 0 }?.let { OffsetDateTime.now().minusDays(it.toLong()) }
        val term = search?.trim()?.ifEmpty { null }

        val filter = auditFilter(listOf(workspaceId), category, userId?.ifEmpty { null }, since, term)
        return WorkspaceAuditPage(repository.findAll(filter, pageable))
    }

    /** The users who appear in a workspace's audit log, for the filter. */
    @QueryMapping
    fun workspaceActivityUsers(@Argument workspaceId: Long): List<String> {
        val workspace = workspaces.findByIdOrNull(workspaceId) ?: throw WorkspaceNotFoundException(workspaceId)
        access.requireVisible(workspace)
        return repository.findUserIds(workspaceId)
    }
}

data class WorkspaceAuditEntry(
    val id: Long,
    /** Null for admin-level entries such as default connection changes. */
    val workspaceId: Long?,
    val category: WorkspaceAuditCategory,
    val message: String,
    val oldWorkspaceName: String?,
    val newWorkspaceName: String?,
    val operationType: WorkspaceOperationType?,
    val date: String,
    val userId: String,
) {
    constructor(audit: WorkspaceAudit) : this(
        id = requireNotNull(audit.id),
        workspaceId = audit.workspaceId,
        category = audit.category,
        message = audit.message,
        oldWorkspaceName = audit.oldWorkspaceName,
        newWorkspaceName = audit.newWorkspaceName,
        operationType = audit.operationType,
        date = audit.date.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        userId = audit.userId,
    )
}

data class WorkspaceAuditPage(
    val content: List<WorkspaceAuditEntry>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    constructor(page: Page<WorkspaceAudit>) : this(
        content = page.content.map(::WorkspaceAuditEntry),
        page = page.number,
        size = page.size,
        totalElements = page.totalElements.toInt(),
        totalPages = page.totalPages,
    )
}
