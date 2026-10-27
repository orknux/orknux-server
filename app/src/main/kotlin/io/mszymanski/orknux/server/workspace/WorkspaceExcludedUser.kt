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
 * A person kept out of one workspace although a role they hold opens it.
 *
 * Roles are the installation's, and a role taken away takes every workspace it
 * opens with it - and for somebody a directory signs in, their groups are not
 * this installation's to edit at all. So removing a member from one workspace
 * is a row here rather than a change to their roles: their other workspaces
 * stay exactly as they were. [io.mszymanski.orknux.server.security.WorkspaceAccess]
 * reads it on every check, so the workspace goes from every list, query, token
 * and endpoint at once. Deleting the row lets them back in.
 */
@Entity
@Table(name = "workspace_excluded_user")
class WorkspaceExcludedUser(
    @EmbeddedId
    val id: WorkspaceExcludedUserId,

    @Column(name = "excluded_at", nullable = false)
    val excludedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "excluded_by", nullable = false, length = 120)
    val excludedBy: String,
)

@Embeddable
data class WorkspaceExcludedUserId(
    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long = 0,

    @Column(nullable = false, length = 120)
    val username: String = "",
) : Serializable

interface WorkspaceExcludedUserRepository : JpaRepository<WorkspaceExcludedUser, WorkspaceExcludedUserId> {
    fun findAllByIdWorkspaceId(workspaceId: Long): List<WorkspaceExcludedUser>
    fun existsByIdWorkspaceIdAndIdUsername(workspaceId: Long, username: String): Boolean
}
