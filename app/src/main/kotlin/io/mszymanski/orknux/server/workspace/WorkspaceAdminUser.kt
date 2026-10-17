package io.mszymanski.orknux.server.workspace

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.io.Serializable
import java.time.OffsetDateTime

/**
 * A person who administers one workspace, named on it directly.
 *
 * The other way somebody administers a workspace is through a role an
 * installation administrator put on `adminRoles`. This is the half a workspace
 * decides for itself, on its own settings page: of the people its roles let in,
 * which may change what it does. A row is the grant; there is none for the
 * ordinary member, so taking it away is deleting the row.
 */
@Entity
@Table(name = "workspace_admin_user")
class WorkspaceAdminUser(
    @EmbeddedId
    val id: WorkspaceAdminUserId,

    @Column(name = "granted_at", nullable = false)
    val grantedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "granted_by", nullable = false, length = 120)
    val grantedBy: String,
)

@Embeddable
data class WorkspaceAdminUserId(
    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long = 0,

    @Column(nullable = false, length = 120)
    val username: String = "",
) : Serializable

interface WorkspaceAdminUserRepository : JpaRepository<WorkspaceAdminUser, WorkspaceAdminUserId> {
    fun findAllByIdWorkspaceId(workspaceId: Long): List<WorkspaceAdminUser>
    fun existsByIdWorkspaceIdAndIdUsername(workspaceId: Long, username: String): Boolean
}
