package io.mszymanski.orknux.server.sharing

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

/** What one workspace may lend another. The list grows a kind at a time; see V405's CHECK. */
enum class ShareKind {
    VARIABLE_CATALOG,
    MODEL_PROVIDER,
}

/**
 * One component of [ownerWorkspaceId] lent to [granteeWorkspaceId]. See V405.
 *
 * The grantee's agents use it; the grantee's people do not thereby see the
 * owner's workspace, and the owner's people decide what is lent.
 */
@Entity
@Table(name = "workspace_share")
class WorkspaceShare(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "owner_workspace_id", nullable = false)
    val ownerWorkspaceId: Long,

    @Column(name = "grantee_workspace_id", nullable = false)
    val granteeWorkspaceId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    val kind: ShareKind,

    @Column(name = "component_id", nullable = false)
    val componentId: Long,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "created_by", nullable = false)
    val createdBy: String,
)

interface WorkspaceShareRepository : JpaRepository<WorkspaceShare, Long> {

    fun findByOwnerWorkspaceIdOrderByCreatedAtAsc(ownerWorkspaceId: Long): List<WorkspaceShare>

    fun findByGranteeWorkspaceIdOrderByCreatedAtAsc(granteeWorkspaceId: Long): List<WorkspaceShare>

    fun findByGranteeWorkspaceIdAndKind(granteeWorkspaceId: Long, kind: ShareKind): List<WorkspaceShare>

    fun findByKindAndComponentId(kind: ShareKind, componentId: Long): List<WorkspaceShare>

    fun existsByGranteeWorkspaceIdAndKindAndComponentId(granteeWorkspaceId: Long, kind: ShareKind, componentId: Long): Boolean
}
