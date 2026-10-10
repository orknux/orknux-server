package io.mszymanski.orknux.server.workspace

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

enum class WorkspaceAuditCategory {
    WORKSPACE,
    WORKFLOW,
    AGENT,
    INTEGRATION,

    /** LLM providers, the models reached through them, and their quotas. */
    MODEL,

    /** Memory catalogs, and what the workspace has written down in them. */
    MEMORY,

    /** The shapes a workspace's workflows pass around. */
    OBJECT,

    /** What happens inside a chat: files attached to a message, and the like. */
    CHAT,

    /**
     * Commands run on a machine over SSH, and the shells they ran on.
     *
     * Nothing writes it any more: shells were removed. It stays because the
     * entries written while they existed hold it, and a row whose category no
     * longer parses would take the whole audit page with it.
     */
    SHELL,

    /**
     * Tasks: one started, one stopped, and what a person let one of them do.
     *
     * Its own category because a capability granted to a task is the one
     * decision in this application that widens what an agent may reach without
     * changing the agent, and somebody auditing that has to be able to find
     * those rows without reading every save of every agent.
     */
    TASK,
}

@Entity
@Table(name = "workspace_audit")
class WorkspaceAudit(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    /** Null for admin-level changes, which belong to no single workspace. */
    val workspaceId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val category: WorkspaceAuditCategory = WorkspaceAuditCategory.WORKSPACE,

    /** What happened, ready to show: "Agent Research Agent enabled". */
    @Column(nullable = false, length = 500)
    val message: String,

    /** Null for [WorkspaceOperationType.ADD], where there is no previous name. */
    val oldWorkspaceName: String? = null,

    /** Null for [WorkspaceOperationType.REMOVE], where there is no resulting name. */
    val newWorkspaceName: String? = null,

    /** Only set for workspace lifecycle entries. */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    val operationType: WorkspaceOperationType? = null,

    @Column(nullable = false)
    val date: OffsetDateTime,

    @Column(nullable = false)
    val userId: String,
)
