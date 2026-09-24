package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.server.dependency.ComponentDependants
import io.mszymanski.orknux.server.dependency.DependencyKind
import io.mszymanski.orknux.server.dependency.phrases
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.server.workspace.pageRequest
import org.springframework.data.domain.Page
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import io.mszymanski.orknux.server.revision.ComponentRevisionKind
import io.mszymanski.orknux.server.revision.ComponentRevisionRecorder
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * A workspace's skills: the instruction sets its agents are guided by.
 *
 * A skill is markdown, so there is nothing to parse and nothing to run. What
 * `validateSkillContent` checks is the shape — the frontmatter an agent reads to
 * know what it has been handed. See [SkillFormat].
 */
@Controller
class SkillAPI(
    private val skills: AgentSkillRepository,
    private val catalogs: SkillCatalogRepository,
    private val workspaces: WorkspaceRepository,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val revisions: ComponentRevisionRecorder,
    private val dependants: ComponentDependants,
    /** What the loaded plugins bring, for the picker that grants a catalog. */
    private val fromPlugins: PluginSkills,
) {

    /**
     * The workspace's skills, or one catalog's.
     *
     * The catalog is optional so the flat list still works — the editor and the
     * agent both ask for skills without caring which folder they are in.
     */
    @QueryMapping
    fun workspaceSkills(
        @Argument workspaceId: Long,
        @Argument catalogId: Long?,
        @Argument page: Int?,
        @Argument size: Int?,
    ): SkillPage {
        requireWorkspaceAccess(workspaceId)
        val asked = pageRequest(page, size, Sort.by("name"))
        val found = if (catalogId == null) {
            skills.findByWorkspaceId(workspaceId, asked)
        } else {
            requireCatalogInWorkspace(workspaceId, catalogId)
            skills.findByCatalogId(catalogId, asked)
        }
        return SkillPage(found, ::describe)
    }

    /** The folders, by name, each with what it holds. */
    @QueryMapping
    fun skillCatalogs(@Argument workspaceId: Long): List<SkillCatalogView> {
        requireWorkspaceAccess(workspaceId)
        return catalogs.findByWorkspaceIdOrderByNameAsc(workspaceId).map(::describe)
    }

    /**
     * The folders the loaded plugins bring.
     *
     * Beside the workspace's own rather than among them: these are not rows,
     * so there is nothing here to rename, delete or put a skill into. An
     * agent's settings page grants them from the same field, by name, which is
     * the only thing the two kinds have to have in common.
     *
     * Installation-wide, like the plugins themselves, and readable by anybody
     * who may see this workspace — granting one is the decision, and a picker
     * that cannot show what is on offer cannot be used to make it.
     */
    @QueryMapping
    fun pluginSkillCatalogs(): List<PluginSkillCatalog> = fromPlugins.catalogs()

    @MutationMapping
    @Transactional
    fun createSkillCatalog(@Argument workspaceId: Long, @Argument name: String): SkillCatalogView {
        requireWorkspaceAccess(workspaceId)
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw SkillCatalogNameInvalidException()
        if (catalogs.findByWorkspaceIdAndName(workspaceId, trimmed) != null) {
            throw SkillCatalogNameTakenException(trimmed)
        }

        val created = catalogs.save(
            SkillCatalog(workspaceId = workspaceId, name = trimmed, createdBy = currentUser()),
        )
        auditRecorder.record(workspaceId, WorkspaceAuditCategory.AGENT, "Skill catalog $trimmed added")
        return describe(created)
    }

    @MutationMapping
    @Transactional
    fun renameSkillCatalog(@Argument id: Long, @Argument name: String): SkillCatalogView {
        val catalog = catalogs.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw SkillCatalogNotFoundException(id)

        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw SkillCatalogNameInvalidException()
        if (trimmed != catalog.name && catalogs.findByWorkspaceIdAndName(catalog.workspaceId, trimmed) != null) {
            throw SkillCatalogNameTakenException(trimmed)
        }

        val was = catalog.name
        catalog.name = trimmed
        auditRecorder.record(
            catalog.workspaceId,
            WorkspaceAuditCategory.AGENT,
            "Skill catalog $was renamed to $trimmed",
        )
        return describe(catalog)
    }

    /**
     * Deletes the catalog and the skills in it, unless an agent draws on it.
     *
     * The catalog is the unit an agent is granted, and the grant is a name — so
     * deleting one takes every skill in it away from every agent that had them
     * and nothing anywhere says so. [AgentGrants] is the argument; a single
     * skill is not asked about here because a single skill carries no grant.
     * Removing one leaves the catalog and its grant exactly as they were, which
     * is editing a folder rather than revoking a capability.
     */
    @MutationMapping
    @Transactional
    fun deleteSkillCatalog(@Argument id: Long): Boolean {
        val catalog = catalogs.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return false

        val granted = dependants.of(DependencyKind.SKILL_CATALOG, id)
        if (granted.isNotEmpty()) throw SkillCatalogInUseException(catalog.name, granted.phrases())

        val held = skills.countByCatalogId(id)
        // Every skill in it goes, so every skill's history goes with it -
        // deleteByCatalogId is one statement and no delete hook would see them.
        skills.findByCatalogId(id).forEach { skill ->
            skill.id?.let { revisions.forget(ComponentRevisionKind.SKILL, it) }
        }
        skills.deleteByCatalogId(id)
        catalogs.delete(catalog)
        auditRecorder.record(
            catalog.workspaceId,
            WorkspaceAuditCategory.AGENT,
            if (held == 0L) {
                "Skill catalog ${catalog.name} removed"
            } else {
                "Skill catalog ${catalog.name} removed with $held skills"
            },
        )
        return true
    }

    @QueryMapping
    fun skill(@Argument id: Long): SkillView? {
        val skill = skills.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return null
        return describe(skill)
    }

    @MutationMapping
    @Transactional
    fun createSkill(@Argument input: CreateSkillInput): SkillView {
        requireWorkspaceAccess(input.workspaceId)
        val name = input.name.trim()
        if (name.isEmpty()) throw SkillNameInvalidException()
        if (skills.findByWorkspaceIdAndName(input.workspaceId, name) != null) throw SkillNameTakenException(name)

        val description = input.description?.trim()?.ifEmpty { null }
        val content = input.content?.takeIf { it.isNotBlank() } ?: SkillFormat.starter(name, description)
        requireWellFormed(content)
        val catalogId = input.catalogId
            ?.also { requireCatalogInWorkspace(input.workspaceId, it) }
            ?: defaultCatalog(input.workspaceId)
        val key = keyFor(input.workspaceId, input.key, name, except = null)

        val skill = skills.save(
            AgentSkill(
                workspaceId = input.workspaceId,
                catalogId = catalogId,
                name = name,
                key = key,
                description = description,
                content = content,
                lastModifiedAt = OffsetDateTime.now(),
                lastModifiedBy = currentUser(),
            ),
        )
        auditRecorder.record(input.workspaceId, WorkspaceAuditCategory.AGENT, "Skill $name created")
        return describe(skill)
    }

    /** Backs the editor: the markdown on the left, the details on the right. */
    @MutationMapping
    @Transactional
    fun updateSkill(@Argument id: Long, @Argument input: UpdateSkillInput): SkillView {
        val skill = skills.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw SkillNotFoundException(id)

        // What it is about to stop being. A skill has no draft, so a save is a
        // version; the recorder holds that rule, this door only reports.
        revisions.saved(skill)

        val previousName = skill.name
        input.name?.trim()?.let { name ->
            if (name.isEmpty()) throw SkillNameInvalidException()
            if (name != skill.name && skills.findByWorkspaceIdAndName(skill.workspaceId, name) != null) {
                throw SkillNameTakenException(name)
            }
            skill.name = name
        }
        input.description?.let { skill.description = it.trim().ifEmpty { null } }
        input.key?.let { skill.key = keyFor(skill.workspaceId, it, skill.name, except = skill.id) }
        input.catalogId?.let {
            requireCatalogInWorkspace(skill.workspaceId, it)
            skill.catalogId = it
        }
        input.content?.let {
            requireWellFormed(it)
            skill.content = it
        }
        skill.lastModifiedAt = OffsetDateTime.now()
        skill.lastModifiedBy = currentUser()

        val message = if (previousName == skill.name) {
            "Skill ${skill.name} updated"
        } else {
            "Skill $previousName renamed to ${skill.name}"
        }
        auditRecorder.record(skill.workspaceId, WorkspaceAuditCategory.AGENT, message)
        return describe(skill)
    }

    @MutationMapping
    @Transactional
    fun setSkillEnabled(@Argument id: Long, @Argument enabled: Boolean): SkillView {
        val skill = skills.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw SkillNotFoundException(id)

        // The toggle is a save: it changes what the workspace has.
        revisions.saved(skill)
        skill.enabled = enabled
        skill.lastModifiedAt = OffsetDateTime.now()
        skill.lastModifiedBy = currentUser()
        val what = if (enabled) "enabled" else "disabled"
        auditRecorder.record(skill.workspaceId, WorkspaceAuditCategory.AGENT, "Skill ${skill.name} $what")
        return describe(skill)
    }

    /**
     * The editor's Validate. It answers rather than throws: badly shaped
     * frontmatter is what the button is for, not a failed request.
     */
    @MutationMapping
    fun validateSkillContent(@Argument workspaceId: Long, @Argument content: String): SourceValidationView {
        requireWorkspaceAccess(workspaceId)
        val checked = SkillFormat.check(content)
        return SourceValidationView(checked.valid, checked.message, checked.line, null)
    }

    @MutationMapping
    @Transactional
    fun deleteSkill(@Argument id: Long): Boolean {
        val skill = skills.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return false

        skills.delete(skill)
        revisions.forget(ComponentRevisionKind.SKILL, id)
        auditRecorder.record(skill.workspaceId, WorkspaceAuditCategory.AGENT, "Skill ${skill.name} deleted")
        return true
    }

    /**
     * The id a skill gets: the one typed, checked and refused if held; or the
     * name's derivation, moved along to the first free variant. A typed id
     * that clashes is refused rather than moved, because somebody typed it.
     * Issue #381.
     */
    private fun keyFor(workspaceId: Long, typed: String?, name: String, except: Long?): String {
        val taken = { key: String ->
            skills.findByWorkspaceIdAndKeyIgnoreCase(workspaceId, key)?.let { it.id != except } ?: false
        }
        val wanted = typed?.trim()?.takeIf { it.isNotEmpty() }
        if (wanted != null) {
            if (!SkillKeys.usable(wanted)) throw SkillKeyInvalidException(wanted)
            if (taken(wanted)) throw SkillKeyTakenException(wanted)
            return wanted
        }
        return SkillKeys.free(SkillKeys.derive(name), taken)
    }

    private fun describe(skill: AgentSkill) = SkillView(
        id = requireNotNull(skill.id),
        workspaceId = skill.workspaceId,
        catalogId = skill.catalogId,
        name = skill.name,
        key = skill.key,
        description = skill.description,
        content = skill.content,
        enabled = skill.enabled,
        lastModifiedAt = skill.lastModifiedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        lastModifiedBy = skill.lastModifiedBy,
    )

    private fun requireWellFormed(content: String) {
        val checked = SkillFormat.check(content)
        if (!checked.valid) throw SkillContentInvalidException(checked.message ?: "The skill is not well formed")
    }

    private fun describe(catalog: SkillCatalog) = SkillCatalogView(
        id = requireNotNull(catalog.id),
        workspaceId = catalog.workspaceId,
        name = catalog.name,
        skillCount = skills.countByCatalogId(requireNotNull(catalog.id)).toInt(),
        createdAt = catalog.createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        createdBy = catalog.createdBy,
    )

    /**
     * Where a skill goes when nobody said. A workspace that has never made a
     * catalog gets a General rather than being refused: creating a skill is not
     * the moment to teach somebody about folders.
     */
    private fun defaultCatalog(workspaceId: Long): Long {
        val existing = catalogs.findByWorkspaceIdOrderByNameAsc(workspaceId).firstOrNull()
        if (existing != null) return requireNotNull(existing.id)
        val made = catalogs.save(SkillCatalog(workspaceId = workspaceId, name = "General", createdBy = currentUser()))
        return requireNotNull(made.id)
    }

    /** A skill lives in its own workspace's folder and no other's. */
    private fun requireCatalogInWorkspace(workspaceId: Long, catalogId: Long) {
        val catalog = catalogs.findByIdOrNull(catalogId) ?: throw SkillCatalogNotFoundException(catalogId)
        if (catalog.workspaceId != workspaceId) throw SkillCatalogNotFoundException(catalogId)
    }

    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"

    private fun requireWorkspaceAccess(workspaceId: Long) {
        access.requireVisible(workspaceId)
    }
}

data class CreateSkillInput(
    val workspaceId: Long,
    val name: String,
    /** Its id; left out, the name's letters, underscores and hyphens. Issue #381. */
    val key: String? = null,
    val description: String? = null,
    /** Left out for a new skill, which starts from the shape with its parts named. */
    val content: String? = null,
    /** Which folder it goes in; the workspace's first when nobody says. */
    val catalogId: Long? = null,
)

data class UpdateSkillInput(
    val name: String? = null,
    /** A new id; null leaves it. Issue #381. */
    val key: String? = null,
    val description: String? = null,
    val content: String? = null,
    /** Moves it to another folder; null leaves it where it is. */
    val catalogId: Long? = null,
)

data class SkillView(
    val id: Long,
    val workspaceId: Long,
    val catalogId: Long,
    val name: String,
    val key: String,
    val description: String?,
    val content: String,
    val enabled: Boolean,
    val lastModifiedAt: String,
    val lastModifiedBy: String,
)

data class SkillCatalogView(
    val id: Long,
    val workspaceId: Long,
    val name: String,
    /** What the count badge shows, so an empty catalog reads as empty. */
    val skillCount: Int,
    val createdAt: String,
    val createdBy: String,
)

data class SkillPage(
    val content: List<SkillView>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    constructor(page: Page<AgentSkill>, describe: (AgentSkill) -> SkillView) : this(
        content = page.content.map(describe),
        page = page.number,
        size = page.size,
        totalElements = page.totalElements.toInt(),
        totalPages = page.totalPages,
    )
}
