package io.mszymanski.orknux.server.security

import io.mszymanski.orknux.server.graphql.Refusal
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.OffsetDateTime

/**
 * What a role lets somebody do, beyond the workspaces it is assigned to.
 *
 * Two for now, and deliberately a set rather than a single value: a role is a
 * bundle of what somebody may do, and the day a third is added — auditor, say, or
 * someone who may run a workflow but not edit one — a column would have to become
 * this anyway.
 */
enum class RoleScope {

    /** Sees the Admin section and every workspace, whatever else is assigned. */
    ADMIN,

    /** Signs in, and sees the workspaces this role is assigned to. */
    USER,
}

/**
 * A role this installation defines.
 *
 * Not a group from a directory. Access used to be decided by whatever the identity
 * provider called its groups, which works for exactly one provider; with a second
 * arriving, what gets matched has to belong here. A provider's group or an OIDC
 * claim is mapped onto one of these, and everything past the front door — who is an
 * administrator, who sees which workspace — only ever deals in roles.
 */
@Entity
@Table(name = "security_role")
class Role(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, length = 120)
    var name: String,

    @Column(length = 500)
    var description: String? = null,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "security_role_scope", joinColumns = [JoinColumn(name = "role_id")])
    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 16)
    var scopes: MutableSet<RoleScope> = mutableSetOf(RoleScope.USER),

    /**
     * Which of the provider's names grant this role, beyond its own.
     *
     * A role is matched to a group by name already: `Backend` is granted to
     * whoever holds `ROLE_BACKEND`, which is what the directory sends. That
     * carries most installations and falls down on the first group called
     * `dev.TL` or `BoarCMS Group` - and then the only way to grant it was
     * `orknux.security.role-mapping` in the configuration file, which an
     * administrator cannot see, cannot reach, and cannot change without somebody
     * redeploying the installation on their behalf.
     *
     * So it is here, where the role is, and editable by whoever administers.
     * Each one is a name the provider may use: an LDAP group's common name or
     * its full DN, or the value of an OIDC claim. The configured mapping is
     * still read as well, because an installation that has one in its file
     * should not lose it by upgrading.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "security_role_match", joinColumns = [JoinColumn(name = "role_id")])
    @Column(name = "value", nullable = false, length = 500)
    var matches: MutableSet<String> = mutableSetOf(),

    /**
     * True for the one role that is not somebody's to change.
     *
     * An installation with no administrator role is one nobody can administer, and
     * a delete button able to do that is one that eventually will. Refused in the
     * API rather than only hidden in the interface, so it holds for anything calling
     * it directly.
     */
    @Column(nullable = false)
    val builtin: Boolean = false,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_modified_at", nullable = false)
    var lastModifiedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_modified_by", nullable = false, length = 120)
    var lastModifiedBy: String = "system",
) {

    /** Whether holding this role makes somebody an administrator. */
    val administers: Boolean
        get() = RoleScope.ADMIN in scopes
}

interface RoleRepository : JpaRepository<Role, Long> {

    /**
     * By name, however it was typed.
     *
     * Roles are named in three places — the list somebody picks from, the workspace
     * they are assigned to, and the configuration that maps a provider's group onto
     * one — and the case they are typed in should not be the difference between a
     * mapping working and silently granting nothing.
     */
    @Query("select r from Role r where lower(r.name) = lower(:name)")
    fun findByName(name: String): Role?

    fun findByBuiltinTrue(): List<Role>
}

class RoleNotFoundException(val id: Long) : RuntimeException("No role with id $id"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

class RoleNameTakenException(val name: String) : RuntimeException("A role named \"$name\" already exists"), Refusal {

    override val arguments get() = mapOf("name" to name)
}

class RoleNameInvalidException : RuntimeException("A role needs a name")

/**
 * Somebody tried to change or remove the built-in role.
 *
 * Says which one and why, because "forbidden" invites a second attempt and this is
 * not something that becomes possible with more permission — nobody has it.
 */
class RoleBuiltInException(val name: String) : RuntimeException(
    "\"$name\" is built in and cannot be edited or removed. An installation with no " +
        "administrator role is one nobody can administer.",
), Refusal {

    override val arguments get() = mapOf("name" to name)
}

/** A role cannot be removed while a workspace still depends on it for access. */
class RoleInUseException(val name: String, val workspaces: List<String>) : RuntimeException(
    "$name is assigned to ${workspaces.joinToString(", ")}. Take it off those workspaces first, " +
        "or whoever holds it loses access to them without anybody deciding that.",
), Refusal {

    override val arguments get() = mapOf("name" to name, "workspaces" to workspaces)
}

