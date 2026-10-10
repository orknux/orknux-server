package io.mszymanski.orknux.server.workspace

import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

/**
 * Writes the audit trail. The user id is the LDAP uid of the authenticated
 * caller, so every entry is attributable.
 *
 * **Every message passes through [AuditRedaction] on the way in.** A message
 * can carry a command line or an address somebody typed, and a command line
 * is where a live credential most often ends up in the clear — a token in a git
 * remote, a password after `curl -u`. Redacting here rather than at each caller
 * is the point: this is the only place rows are written, so a caller added
 * later cannot forget, and redacting here rather than when the log is read
 * means the secret is never on disk or in a backup in the first place. Read the
 * [AuditRedaction] documentation for what that does and does not find; it is a
 * list of patterns, not a guarantee.
 *
 * Rows written before this existed are left exactly as they are.
 */
@Service
class WorkspaceAuditRecorder(
    private val repository: WorkspaceAuditRepository,
) {

    /** Workspace lifecycle: keeps the names either side so the org view can read them. */
    fun record(
        workspaceId: Long,
        operationType: WorkspaceOperationType,
        oldWorkspaceName: String? = null,
        newWorkspaceName: String? = null,
    ): WorkspaceAudit = repository.save(
        WorkspaceAudit(
            workspaceId = workspaceId,
            category = WorkspaceAuditCategory.WORKSPACE,
            message = AuditRedaction.redact(workspaceMessage(operationType, oldWorkspaceName, newWorkspaceName)),
            oldWorkspaceName = oldWorkspaceName,
            newWorkspaceName = newWorkspaceName,
            operationType = operationType,
            date = OffsetDateTime.now(),
            userId = currentUserId(),
        ),
    )

    /**
     * Anything else that happened, already worded for display. A null [workspaceId]
     * records an admin-level change, which only the admin audit log shows.
     */
    fun record(workspaceId: Long?, category: WorkspaceAuditCategory, message: String): WorkspaceAudit = repository.save(
        WorkspaceAudit(
            workspaceId = workspaceId,
            category = category,
            message = AuditRedaction.redact(message),
            date = OffsetDateTime.now(),
            userId = currentUserId(),
        ),
    )

    /**
     * Something the system did with nobody asking: an event arriving on a
     * connection and starting a workflow. [actor] stands where a user id
     * normally does, so the log says what set it off instead of naming a person
     * who was not there.
     */
    fun recordAutomated(
        workspaceId: Long?,
        category: WorkspaceAuditCategory,
        message: String,
        actor: String,
    ): WorkspaceAudit = repository.save(
        WorkspaceAudit(
            workspaceId = workspaceId,
            category = category,
            message = AuditRedaction.redact(message),
            date = OffsetDateTime.now(),
            userId = actor,
        ),
    )

    private fun workspaceMessage(
        operationType: WorkspaceOperationType,
        oldWorkspaceName: String?,
        newWorkspaceName: String?,
    ): String = when (operationType) {
        WorkspaceOperationType.ADD -> "Workspace $newWorkspaceName created"
        WorkspaceOperationType.RENAME -> "Workspace $oldWorkspaceName renamed to $newWorkspaceName"
        WorkspaceOperationType.REMOVE -> "Workspace $oldWorkspaceName deleted"
    }

    private fun currentUserId(): String {
        val authentication = SecurityContextHolder.getContext().authentication
        check(
            authentication != null &&
                authentication.isAuthenticated &&
                authentication !is AnonymousAuthenticationToken,
        ) {
            "No authenticated user to attribute this change to"
        }
        return authentication.name
    }
}
