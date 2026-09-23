package io.mszymanski.orknux.server.security

import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * The roles this installation defines: what somebody may do, in this application's
 * own terms rather than the identity provider's.
 *
 * Administrators only, all of it. Reading the list is as restricted as changing it:
 * the roles are the shape of who can see what, and that is not something to hand to
 * everybody who can sign in.
 */
@Controller
class RoleAPI(
    private val roles: RoleRepository,
    private val access: WorkspaceAccess,
    private val workspaces: WorkspaceRepository,
) {

    @QueryMapping
    fun roles(): List<RoleView> {
        access.requireAdmin()
        // Built-in first, then by name: the administrator role is the one somebody
        // is looking for when they arrive, and it is the one they may not change.
        return roles.findAll()
            .sortedWith(compareByDescending<Role> { it.builtin }.thenBy { it.name.lowercase() })
            .map(::describe)
    }

    @QueryMapping
    fun role(@Argument id: Long): RoleView? {
        access.requireAdmin()
        return roles.findByIdOrNull(id)?.let(::describe)
    }

    @MutationMapping
    @Transactional
    fun createRole(@Argument input: RoleInput): RoleView {
        access.requireAdmin()
        val name = requireName(input.name)
        if (roles.findByName(name) != null) throw RoleNameTakenException(name)

        val role = roles.save(
            Role(
                name = name,
                description = input.description?.trim()?.ifEmpty { null },
                scopes = scopesOf(input.scopes),
                matches = matchesOf(input.matches),
                lastModifiedAt = OffsetDateTime.now(),
                lastModifiedBy = currentUser(),
            ),
        )
        return describe(role)
    }

    @MutationMapping
    @Transactional
    fun updateRole(@Argument id: Long, @Argument input: RoleInput): RoleView {
        access.requireAdmin()
        val role = roles.findByIdOrNull(id) ?: throw RoleNotFoundException(id)
        if (role.builtin) throw RoleBuiltInException(role.name)

        val name = requireName(input.name)
        val taken = roles.findByName(name)
        if (taken != null && taken.id != role.id) throw RoleNameTakenException(name)

        role.name = name
        role.description = input.description?.trim()?.ifEmpty { null }
        role.scopes = scopesOf(input.scopes)
        role.matches = matchesOf(input.matches)
        role.lastModifiedAt = OffsetDateTime.now()
        role.lastModifiedBy = currentUser()
        return describe(role)
    }

    @MutationMapping
    @Transactional
    fun deleteRole(@Argument id: Long): Boolean {
        access.requireAdmin()
        val role = roles.findByIdOrNull(id) ?: return false
        if (role.builtin) throw RoleBuiltInException(role.name)

        /*
         * A role a workspace depends on is not one to remove quietly.
         *
         * The link is a join table with a cascade, so deleting the role took
         * the row with it and whoever held that role lost those workspaces -
         * silently, and without anybody deciding it. The refusal was written
         * and mapped already; nothing threw it.
         */
        val depending = workspaces.findAll().filter { held -> held.roles.any { it.id == role.id } }
        if (depending.isNotEmpty()) throw RoleInUseException(role.name, depending.map { it.name })

        roles.delete(role)
        return true
    }

    /**
     * A role with no scope can do nothing at all, which is not a role somebody meant
     * to make. Empty means USER — the ordinary one — rather than a refusal, because
     * "what may they do" is a question with an obvious default and no good error.
     */
    /**
     * The directory names written on a role, tidied.
     *
     * Trimmed and de-duplicated without regard to case, because these are
     * compared without regard to case: two rows differing only in capitals would
     * be one rule shown twice, and whoever removed the wrong one would find it
     * still working.
     *
     * The blanks go. A row somebody left empty grants nothing and reads like a
     * rule.
     */
    private fun matchesOf(matches: List<String>?): MutableSet<String> {
        val seen = mutableSetOf<String>()
        return matches.orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() && seen.add(it.lowercase()) }
            .toMutableSet()
    }

    private fun scopesOf(scopes: List<RoleScope>?): MutableSet<RoleScope> =
        scopes?.toMutableSet()?.takeIf { it.isNotEmpty() } ?: mutableSetOf(RoleScope.USER)

    private fun requireName(name: String?): String =
        name?.trim()?.ifEmpty { null } ?: throw RoleNameInvalidException()

    private fun describe(role: Role) = RoleView(
        matches = role.matches.sortedBy { it.lowercase() },
        id = requireNotNull(role.id),
        name = role.name,
        description = role.description,
        // Listed in the enum's own order, so two roles with the same scopes read
        // the same way wherever they are shown.
        scopes = RoleScope.entries.filter { it in role.scopes },
        builtin = role.builtin,
        lastModifiedAt = role.lastModifiedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        lastModifiedBy = role.lastModifiedBy,
    )

    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"
}

data class RoleInput(
    val name: String? = null,
    val description: String? = null,
    /** Empty or absent means an ordinary role: USER. */
    val scopes: List<RoleScope>? = null,
    /**
     * Directory names that grant this role, beyond its own name.
     *
     * Absent leaves none, which is right for every role named after its group.
     */
    val matches: List<String>? = null,
)

data class RoleView(
    val id: Long,
    val name: String,
    val description: String?,
    val scopes: List<RoleScope>,
    /**
     * Which of the provider's names grant this role, beyond its own.
     *
     * For a group this installation cannot name a role after - `dev.TL`,
     * `BoarCMS Group` - which before this could only be mapped in the
     * configuration file, where an administrator could neither see it nor
     * change it.
     */
    val matches: List<String>,
    /** True for the administrator role, which the screen shows without its controls. */
    val builtin: Boolean,
    val lastModifiedAt: String,
    val lastModifiedBy: String,
)
