package io.mszymanski.orknux.server.workspace

import io.mszymanski.orknux.server.graphql.Refusal
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import org.hibernate.annotations.Fetch
import org.hibernate.annotations.FetchMode
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

/**
 * What one admin workspace's agents may read, beyond their own workspace.
 *
 * Read only: every scope here is a list and a read, and nothing an admin
 * workspace holds creates, changes, deletes or runs anything anywhere else.
 * [Workspace.adminWorkspace] says whether the workspace is one; this row says
 * what it may then reach, and is kept when the flag goes off so switching it
 * back on finds what was chosen before.
 *
 * The switches are rows of what is *on*, so a scope or a tool added in a later
 * release starts off on every admin workspace that exists - the careful
 * direction for something that reads other people's work. A new admin
 * workspace is given [AdminWorkspaceScope.STARTS_ON] and
 * [AdminWorkspaceTool.STARTS_ON] when it is first switched on.
 */
@Entity
@Table(name = "admin_workspace")
class AdminWorkspace(
    @Id
    @Column(name = "workspace_id")
    val workspaceId: Long = 0,

    /** Every workspace on the installation, or only [reach]. */
    @Column(name = "reach_all", nullable = false)
    var reachAll: Boolean = true,

    /** The chosen workspaces, read only while [reachAll] is false. A workspace deleted leaves it. */
    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SELECT)
    @CollectionTable(name = "admin_workspace_reach", joinColumns = [JoinColumn(name = "admin_workspace_id")])
    @Column(name = "workspace_id", nullable = false)
    var reach: MutableSet<Long> = mutableSetOf(),

    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SELECT)
    @CollectionTable(name = "admin_workspace_scope", joinColumns = [JoinColumn(name = "admin_workspace_id")])
    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 32)
    var scopes: MutableSet<AdminWorkspaceScope> = AdminWorkspaceScope.STARTS_ON.toMutableSet(),

    @ElementCollection(fetch = FetchType.EAGER)
    @Fetch(FetchMode.SELECT)
    @CollectionTable(name = "admin_workspace_tool", joinColumns = [JoinColumn(name = "admin_workspace_id")])
    @Enumerated(EnumType.STRING)
    @Column(name = "tool", nullable = false, length = 32)
    var tools: MutableSet<AdminWorkspaceTool> = AdminWorkspaceTool.STARTS_ON.toMutableSet(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_by", nullable = false, length = 120)
    var updatedBy: String = "system",
) {

    /** Whether [workspaceId] is within this admin workspace's reach. */
    fun reaches(workspaceId: Long): Boolean = reachAll || workspaceId in reach
}

interface AdminWorkspaceRepository : JpaRepository<AdminWorkspace, Long>

/**
 * What an admin workspace's agents may read in the workspaces it reaches.
 *
 * Each is a kind of thing with a list and a page of its own, read through the
 * same reader an agent in that workspace would use, bounded to that workspace.
 * [kind] is the word the tools take it by.
 */
enum class AdminWorkspaceScope(val kind: String) {
    /** Agents' conversations: a Slack thread, a task, a workflow step. */
    SESSIONS("sessions"),

    /** People's chats with agents and models, which are otherwise only their owner's to read. */
    CHATS("chats"),

    AGENTS("agents"),

    /** The action, trigger and condition catalogue, and the functions behind them. */
    ACTIONS("actions"),

    WORKFLOWS("workflows"),

    /** Workflow runs, and what each step produced. */
    RUNS("runs"),

    /** Research and the report each one wrote. */
    REPORTS("reports"),
    ;

    companion object {
        /** Every scope, as the owner asked: a new admin workspace reads everything it reaches until told otherwise. */
        val STARTS_ON: Set<AdminWorkspaceScope> = entries.toSet()

        fun ofKind(kind: String?): AdminWorkspaceScope? =
            entries.firstOrNull { it.kind.equals(kind?.trim(), ignoreCase = true) }
    }
}

/**
 * The installation-wide admin pages an admin workspace's agents may read, each
 * an agent tool of its own and each switched on its own. Read only, like the
 * scopes: nothing here changes a role, a user, a plugin or a setting.
 */
enum class AdminWorkspaceTool(val toolName: String) {
    REPORTS("admin_reports"),
    DOCTOR("admin_doctor"),
    ROLES("admin_roles"),
    MONITORING("admin_monitoring"),
    TEMPLATES("admin_templates"),
    PLUGINS("admin_plugins"),
    AUDIT("admin_audit"),
    USERS("admin_users"),
    ;

    companion object {
        /**
         * What a new admin workspace starts with: the pages about how the
         * installation is running. Roles, the audit log and the users are about
         * people - who holds what, who did what, where to write to them - and
         * start off, for an administrator to switch on having decided to.
         */
        val STARTS_ON: Set<AdminWorkspaceTool> = setOf(REPORTS, DOCTOR, MONITORING, TEMPLATES, PLUGINS)

        fun named(name: String): AdminWorkspaceTool? = entries.firstOrNull { it.toolName == name }
    }
}

/**
 * A workspace somebody who is not an installation administrator can open, asked
 * to become an admin workspace. Named with the roles in the way, because they
 * are what has to change, and the person asking is an administrator who can see
 * them already.
 */
class AdminWorkspaceOpenToOthersException(val name: String, val roles: List<String>, val people: List<String>) : RuntimeException(
    buildString {
        append("$name cannot be an admin workspace while people who are not installation administrators can open it")
        if (roles.isNotEmpty()) append(": take ${roles.joinToString(", ")} off it first")
        if (people.isNotEmpty()) append(if (roles.isEmpty()) ": " else ", and ").append("stop ${people.joinToString(", ")} administering it first")
        append(". Its agents read other workspaces, so whoever can open it could read them too.")
    },
), Refusal {
    override val arguments get() = mapOf("name" to name, "roles" to roles, "people" to people)
}

/**
 * A role that is not the administrator's, asked to open an admin workspace -
 * or a role opening one, asked to stop being the administrator's.
 */
class AdminWorkspaceMemberRefusedException(val workspaces: List<String>, val roles: List<String>) : RuntimeException(
    "${workspaces.joinToString(", ")} ${if (workspaces.size == 1) "is an admin workspace" else "are admin workspaces"}, " +
        "and only installation administrators may open one: ${roles.joinToString(", ")} " +
        "${if (roles.size == 1) "does" else "do"} not make somebody an administrator. " +
        "Switch it off in Admin > Workspaces first, or use a role with the Admin scope.",
), Refusal {
    override val arguments get() = mapOf("workspaces" to workspaces, "roles" to roles)
}

/** A reach list naming a workspace that is not there. */
class AdminWorkspaceReachUnknownException(val id: Long) : RuntimeException(
    "There is no workspace with id $id to reach.",
), Refusal {
    override val arguments get() = mapOf("id" to id)
}
