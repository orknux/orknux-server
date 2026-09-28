package io.mszymanski.orknux.server.memory

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
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * What a workspace has decided, kept where its agents can be given it.
 *
 * Catalogs are folders and memories are what is in them. Both are the
 * workspace's own — there is nothing to run and nothing to check against a
 * provider, so this is the plainest kind of thing the app owns: text, who wrote
 * it, and when.
 */
@Controller
class MemoryAPI(
    private val catalogs: MemoryCatalogRepository,
    private val memories: MemoryRepository,
    private val workspaces: WorkspaceRepository,
    private val access: WorkspaceAccess,
    private val auditRecorder: WorkspaceAuditRecorder,
    private val dependants: ComponentDependants,
) {

    @QueryMapping
    fun memoryCatalogs(@Argument workspaceId: Long): List<MemoryCatalogView> {
        requireWorkspaceAccess(workspaceId)
        return catalogs.findByWorkspaceIdOrderByNameAsc(workspaceId).map(::describe)
    }

    @QueryMapping
    fun memoryCatalog(@Argument id: Long): MemoryCatalogView? {
        val catalog = catalogs.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return null
        return describe(catalog)
    }

    /**
     * One memory, by id. What the editor opens: a memory belongs to a catalog,
     * but which page of that catalog it falls on is nothing the editor should
     * have to work out — and past the last page it could not find it at all.
     */
    @QueryMapping
    fun memory(@Argument id: Long): MemoryView? {
        val memory = memories.findByIdOrNull(id) ?: return null
        catalogs.findByIdOrNull(memory.catalogId)?.takeIf { access.canSee(it.workspaceId) } ?: return null
        return describe(memory)
    }

    /**
     * One page of a catalog, as the toolbar asked for it.
     *
     * A blank search or author is no filter rather than a search for nothing:
     * the screen sends what is in the boxes, and empty boxes mean unfiltered.
     */
    @QueryMapping
    fun memories(
        @Argument catalogId: Long,
        @Argument search: String?,
        @Argument author: String?,
        @Argument sort: MemorySort?,
        @Argument page: Int?,
        @Argument size: Int?,
    ): MemoryPage {
        // A page is not something the schema lets us answer null for, so a
        // catalog in a workspace the caller cannot see is refused exactly as one
        // that is not there rather than with a different error.
        catalogs.findByIdOrNull(catalogId)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw MemoryCatalogNotFoundException(catalogId)

        val order = when (sort ?: MemorySort.LAST_MODIFIED) {
            MemorySort.LAST_MODIFIED -> Sort.by(Sort.Direction.DESC, "lastModifiedAt")
            MemorySort.CREATED -> Sort.by(Sort.Direction.DESC, "createdAt")
            MemorySort.TITLE -> Sort.by("title")
        }
        val found = memories.findAll(
            memoryFilter(
                catalogId = catalogId,
                search = search?.trim()?.ifEmpty { null },
                author = author?.trim()?.ifEmpty { null },
            ),
            pageRequest(page, size, order),
        )
        return MemoryPage(found, ::describe)
    }

    /** The names the author filter offers, which are the ones actually present. */
    @QueryMapping
    fun memoryAuthors(@Argument catalogId: Long): List<String> {
        catalogs.findByIdOrNull(catalogId)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw MemoryCatalogNotFoundException(catalogId)
        return memories.authors(catalogId)
    }

    @MutationMapping
    @Transactional
    fun createMemoryCatalog(@Argument workspaceId: Long, @Argument name: String): MemoryCatalogView {
        requireWorkspaceAccess(workspaceId)
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw MemoryCatalogNameInvalidException()
        if (catalogs.findByWorkspaceIdAndName(workspaceId, trimmed) != null) {
            throw MemoryCatalogNameTakenException(trimmed)
        }

        val created = catalogs.save(
            MemoryCatalog(workspaceId = workspaceId, name = trimmed, createdBy = currentUser()),
        )
        auditRecorder.record(workspaceId, WorkspaceAuditCategory.MEMORY, "Memory catalog $trimmed added")
        return describe(created)
    }

    @MutationMapping
    @Transactional
    fun renameMemoryCatalog(@Argument id: Long, @Argument name: String): MemoryCatalogView {
        val catalog = catalogs.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw MemoryCatalogNotFoundException(id)

        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw MemoryCatalogNameInvalidException()
        if (trimmed != catalog.name && catalogs.findByWorkspaceIdAndName(catalog.workspaceId, trimmed) != null) {
            throw MemoryCatalogNameTakenException(trimmed)
        }

        val was = catalog.name
        catalog.name = trimmed
        auditRecorder.record(
            catalog.workspaceId,
            WorkspaceAuditCategory.MEMORY,
            "Memory catalog $was renamed to $trimmed",
        )
        return describe(catalog)
    }

    /**
     * Takes the memories in it, which is what the cascade is for — unless an
     * agent was granted the catalog.
     *
     * The catalog is the unit an agent is granted, and the grant is a **name**,
     * so nothing here would have been left dangling for anything to report:
     * [MemoryTool] drops a granted name that matches no catalog, deliberately,
     * and the agent simply stops knowing everything the folder held while its
     * own screen goes on listing the grant. A name is also re-bindable — make a
     * catalog with that name again tomorrow and every agent still holding the
     * grant is silently handed the new one. That is why the question is asked
     * here rather than by the next thing to read the grant. [AgentGrants] is
     * where the argument is written down; deleting a single memory is not asked
     * about, because a memory carries no grant.
     */
    @MutationMapping
    @Transactional
    fun deleteMemoryCatalog(@Argument id: Long): Boolean {
        val catalog = catalogs.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) } ?: return false

        /*
         * The one the workspace always has. Refused rather than hidden on the
         * screen alone, for the reason every other bound here is checked at the
         * mutation: a form is not a boundary, and this one is reachable from the
         * API. Renaming it is the way out of a name nobody likes.
         */
        if (catalog.isDefault) throw MemoryCatalogNotRemovableException(catalog.name)

        val granted = dependants.of(DependencyKind.MEMORY_CATALOG, id)
        if (granted.isNotEmpty()) throw MemoryCatalogInUseException(catalog.name, granted.phrases())

        val held = memories.countByCatalogId(id)
        memories.deleteByCatalogId(id)
        catalogs.delete(catalog)
        auditRecorder.record(
            catalog.workspaceId,
            WorkspaceAuditCategory.MEMORY,
            if (held == 0L) {
                "Memory catalog ${catalog.name} removed"
            } else {
                "Memory catalog ${catalog.name} removed, with $held ${if (held == 1L) "memory" else "memories"}"
            },
        )
        return true
    }

    @MutationMapping
    @Transactional
    fun createMemory(@Argument input: CreateMemoryInput): MemoryView {
        val catalog = catalogs.findByIdOrNull(input.catalogId)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw MemoryCatalogNotFoundException(input.catalogId)

        val title = input.title.trim()
        val content = input.content.trim()
        if (title.isEmpty()) throw MemoryTitleInvalidException()
        if (content.isEmpty()) throw MemoryContentInvalidException()

        val now = OffsetDateTime.now()
        val who = currentUser()
        val created = memories.save(
            Memory(
                catalogId = input.catalogId,
                title = title,
                content = content,
                createdAt = now,
                createdBy = who,
                lastModifiedAt = now,
                lastModifiedBy = who,
            ),
        )
        auditRecorder.record(
            catalog.workspaceId,
            WorkspaceAuditCategory.MEMORY,
            "Memory $title added to ${catalog.name}",
        )
        return describe(created)
    }

    /**
     * Edits one. The author is not touched: whoever fixes a typo does not become
     * the person who contributed the memory.
     */
    @MutationMapping
    @Transactional
    fun updateMemory(@Argument id: Long, @Argument input: UpdateMemoryInput): MemoryView {
        val memory = memories.findByIdOrNull(id) ?: throw MemoryNotFoundException(id)
        // The number being walked here is the memory's, so a memory in a catalog
        // the caller cannot see has to be refused the way an invented memory id
        // already is, rather than by naming its catalog.
        val catalog = catalogs.findByIdOrNull(memory.catalogId)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw MemoryNotFoundException(id)

        val title = input.title.trim()
        val content = input.content.trim()
        if (title.isEmpty()) throw MemoryTitleInvalidException()
        if (content.isEmpty()) throw MemoryContentInvalidException()

        val was = memory.title
        memory.title = title
        memory.content = content
        memory.lastModifiedAt = OffsetDateTime.now()
        memory.lastModifiedBy = currentUser()

        // Moving it between catalogs is a move, and the audit says so.
        val moved = input.catalogId != null && input.catalogId != memory.catalogId
        if (moved) {
            val destination = catalogs.findByIdOrNull(input.catalogId)
                ?.takeIf { access.canSee(it.workspaceId) }
                ?: throw MemoryCatalogNotFoundException(requireNotNull(input.catalogId))
            memory.catalogId = requireNotNull(destination.id)
            auditRecorder.record(
                catalog.workspaceId,
                WorkspaceAuditCategory.MEMORY,
                "Memory $title moved to ${destination.name}",
            )
        } else {
            auditRecorder.record(
                catalog.workspaceId,
                WorkspaceAuditCategory.MEMORY,
                if (was == title) "Memory $title updated" else "Memory $was renamed to $title",
            )
        }
        return describe(memory)
    }

    /**
     * A copy of a memory in the same catalog: "Deploy steps (copy)", or the first
     * number after it that is free. Written by whoever pressed Duplicate, since
     * they are the one who made this copy.
     */
    @MutationMapping
    @Transactional
    fun duplicateMemory(@Argument id: Long): MemoryView {
        val memory = memories.findByIdOrNull(id) ?: throw MemoryNotFoundException(id)
        val catalog = catalogs.findByIdOrNull(memory.catalogId)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw MemoryNotFoundException(id)
        val title = generateSequence(1) { it + 1 }
            .map { n -> if (n == 1) "${memory.title} (copy)" else "${memory.title} (copy $n)" }
            .first { memories.findByCatalogIdAndTitle(memory.catalogId, it) == null }
        val now = OffsetDateTime.now()
        val who = currentUser()
        val copy = memories.save(
            Memory(
                catalogId = memory.catalogId,
                title = title,
                content = memory.content,
                createdAt = now,
                createdBy = who,
                lastModifiedAt = now,
                lastModifiedBy = who,
            ),
        )
        auditRecorder.record(catalog.workspaceId, WorkspaceAuditCategory.MEMORY, "Memory ${memory.title} duplicated as $title")
        return describe(copy)
    }

    @MutationMapping
    @Transactional
    fun deleteMemory(@Argument id: Long): Boolean {
        val memory = memories.findByIdOrNull(id) ?: return false
        // False for a memory that is not there, and so false for one the caller
        // cannot see: an error here would say the id was real.
        val catalog = catalogs.findByIdOrNull(memory.catalogId)?.takeIf { access.canSee(it.workspaceId) }
            ?: return false

        memories.delete(memory)
        auditRecorder.record(
            catalog.workspaceId,
            WorkspaceAuditCategory.MEMORY,
            "Memory ${memory.title} removed from ${catalog.name}",
        )
        return true
    }

    private fun describe(catalog: MemoryCatalog) = MemoryCatalogView(
        id = requireNotNull(catalog.id),
        workspaceId = catalog.workspaceId,
        name = catalog.name,
        // What the count badge shows, so an empty catalog reads as empty.
        memoryCount = memories.countByCatalogId(requireNotNull(catalog.id)).toInt(),
        createdAt = catalog.createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        createdBy = catalog.createdBy,
        isDefault = catalog.isDefault,
    )

    private fun describe(memory: Memory) = MemoryView(
        id = requireNotNull(memory.id),
        catalogId = memory.catalogId,
        title = memory.title,
        content = memory.content,
        createdAt = memory.createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        createdBy = memory.createdBy,
        lastModifiedAt = memory.lastModifiedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        lastModifiedBy = memory.lastModifiedBy,
    )

    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"

    private fun requireWorkspaceAccess(workspaceId: Long) {
        access.requireVisible(workspaceId)
    }
}

/** What the grid can be ordered by. */
enum class MemorySort {
    LAST_MODIFIED,
    CREATED,
    TITLE,
}

data class CreateMemoryInput(
    val catalogId: Long,
    val title: String,
    val content: String,
)

/** A null `catalogId` leaves the memory where it is. */
data class UpdateMemoryInput(
    val title: String,
    val content: String,
    val catalogId: Long? = null,
)

data class MemoryCatalogView(
    val id: Long,
    val workspaceId: Long,
    val name: String,
    val memoryCount: Int,
    val createdAt: String,
    val createdBy: String,
    /** The one this workspace always has: renameable, and not removable. */
    val isDefault: Boolean,
)

data class MemoryView(
    val id: Long,
    val catalogId: Long,
    val title: String,
    val content: String,
    val createdAt: String,
    val createdBy: String,
    val lastModifiedAt: String,
    val lastModifiedBy: String,
)

data class MemoryPage(
    val content: List<MemoryView>,
    val page: Int,
    val size: Int,
    val totalElements: Int,
    val totalPages: Int,
) {
    constructor(page: Page<Memory>, describe: (Memory) -> MemoryView) : this(
        content = page.content.map(describe),
        page = page.number,
        size = page.size,
        totalElements = page.totalElements.toInt(),
        totalPages = page.totalPages,
    )
}
