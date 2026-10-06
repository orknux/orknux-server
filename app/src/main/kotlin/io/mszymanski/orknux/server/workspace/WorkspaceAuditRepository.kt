package io.mszymanski.orknux.server.workspace

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime

interface WorkspaceAuditRepository : JpaRepository<WorkspaceAudit, Long>, JpaSpecificationExecutor<WorkspaceAudit> {

    fun findByWorkspaceId(workspaceId: Long, pageable: Pageable): Page<WorkspaceAudit>

    @Query("SELECT DISTINCT a.userId FROM WorkspaceAudit a WHERE a.workspaceId = :workspaceId ORDER BY a.userId")
    fun findUserIds(@Param("workspaceId") workspaceId: Long): List<String>

    /**
     * Everybody who appears anywhere in the log, for an administrator's filter.
     *
     * Asked of the database rather than worked out from the rows: the log only
     * grows, and reading every entry of it to name a handful of people was a
     * whole table held in memory on every load of the filter. Issue #616.
     */
    @Query("SELECT DISTINCT a.userId FROM WorkspaceAudit a")
    fun findAllUserIds(): List<String>

    /** Everybody who appears in the logs of [workspaceIds], for a filter somebody short of administrator sees. */
    @Query("SELECT DISTINCT a.userId FROM WorkspaceAudit a WHERE a.workspaceId IN :workspaceIds")
    fun findUserIdsIn(@Param("workspaceIds") workspaceIds: Collection<Long>): List<String>
}

/**
 * Filters for the workspace audit view. Built as a specification rather than JPQL so
 * the optional ones can simply be left out.
 */
fun auditFilter(
    /** Null means every workspace; an empty list means none. */
    workspaceIds: Collection<Long>?,
    category: WorkspaceAuditCategory?,
    userId: String?,
    since: OffsetDateTime?,
    search: String?,
    /**
     * Keeps the admin log to admin-level events: a workspace being
     * created, renamed or removed, and anything that belongs to no workspace.
     * Everything a workspace does inside itself belongs in that workspace's own log.
     */
    adminOnly: Boolean = false,
): Specification<WorkspaceAudit> = Specification { root, _, builder ->
    val predicates = mutableListOf(builder.conjunction())

    workspaceIds?.let { ids ->
        predicates += if (ids.isEmpty()) builder.disjunction() else root.get<Long>("workspaceId").`in`(ids)
    }

    if (adminOnly) {
        /*
         * Admin-level is a workspace's lifecycle - it appearing, being renamed
         * or removed, which is the entry that carries an operationType - and
         * anything that belongs to no workspace at all. Matching on the
         * WORKSPACE category instead swept in a workspace's own business filed
         * under it, the whole of the issue tracker included: an issue opened, an
         * observer added, an issue assigned are workspace activity, not
         * administration, and belong in that workspace's log rather than here.
         * Issue #410.
         */
        predicates += builder.or(
            builder.isNull(root.get<Long>("workspaceId")),
            builder.isNotNull(root.get<WorkspaceOperationType>("operationType")),
        )
    }

    category?.let { predicates += builder.equal(root.get<WorkspaceAuditCategory>("category"), it) }
    userId?.let { predicates += builder.equal(root.get<String>("userId"), it) }
    since?.let { predicates += builder.greaterThanOrEqualTo(root.get("date"), it) }
    search?.let {
        val pattern = "%${it.lowercase()}%"
        predicates += builder.or(
            builder.like(builder.lower(root.get("message")), pattern),
            builder.like(builder.lower(root.get("userId")), pattern),
        )
    }

    builder.and(*predicates.toTypedArray())
}
