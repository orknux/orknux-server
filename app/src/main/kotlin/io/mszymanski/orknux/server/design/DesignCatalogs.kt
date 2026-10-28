package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.server.security.ActingIn
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import jakarta.persistence.EntityManager
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service

/**
 * The catalogs of Design's images, wireframes and videos: one set of rules for
 * the three, over a table each.
 *
 * **There is always a default.** Each workspace has one catalog of each kind
 * marked default - General, when it is made - and a thing with no catalog of
 * its own is in it ([resolve]). So nothing has to be filed to be made, a row an
 * older release wrote (with no catalog) is listed where it always was, and a
 * workspace made after the migration gets its default the first time its
 * catalogs are asked for ([defaultOf]). The default can be renamed and never
 * deleted; any other catalog can be deleted once it is empty.
 *
 * Access is the caller's to have checked - every door here is a controller
 * that checks the workspace first - and so is the audit of what is filed where.
 */
@Service
class DesignCatalogs(
    private val images: ImageCatalogRepository,
    private val wireframes: WireframeCatalogRepository,
    private val videos: VideoCatalogRepository,
    private val audit: WorkspaceAuditRecorder,
    private val entities: EntityManager,
) {

    /** A workspace's catalogs of one kind, the default first and the rest by name. */
    fun catalogs(workspaceId: Long, kind: DesignCatalogKind): List<DesignCatalog> {
        val first = defaultOf(workspaceId, kind)
        return listOf(first) + repository(kind).findByWorkspaceIdOrderByNameAsc(workspaceId).filter { it.id != first.id }
    }

    /**
     * The workspace's default catalog of [kind], made the first time it is
     * needed. Made under a lock: two first requests for a fresh workspace's
     * catalogs would otherwise both make one, and the second would fail on the
     * index that allows one default.
     */
    fun defaultOf(workspaceId: Long, kind: DesignCatalogKind): DesignCatalog {
        repository(kind).findByWorkspaceIdAndIsDefaultTrue(workspaceId)?.let { return it }
        synchronized(this) {
            repository(kind).findByWorkspaceIdAndIsDefaultTrue(workspaceId)?.let { return it }
            // A catalog somebody already called General is not taken over; the default is called something else.
            val name = generateSequence(1) { it + 1 }
                .map { if (it == 1) FIRST else "$FIRST $it" }
                .first { repository(kind).findByWorkspaceIdAndName(workspaceId, it) == null }
            return save(kind, made(kind, workspaceId, name, isDefault = true))
        }
    }

    /** One catalog of [kind] in [workspaceId], or a refusal that does not say whether it is somebody else's. */
    fun catalog(workspaceId: Long, kind: DesignCatalogKind, id: Long): DesignCatalog =
        repository(kind).findByIdOrNull(id)?.takeIf { it.workspaceId == workspaceId } ?: throw DesignCatalogNotFoundException(id)

    /**
     * The catalog something is filed in: [catalogId] where it is given, checked
     * to be this workspace's and this kind's; the default where it is not.
     */
    fun resolve(workspaceId: Long, kind: DesignCatalogKind, catalogId: Long?): Long =
        requireNotNull((if (catalogId == null) defaultOf(workspaceId, kind) else catalog(workspaceId, kind, catalogId)).id)

    /** Each catalog's name, by id, for a list that names the catalog of every row. */
    fun names(workspaceId: Long, kind: DesignCatalogKind): Map<Long, String> =
        catalogs(workspaceId, kind).associate { requireNotNull(it.id) to it.name }

    /**
     * How many things each catalog holds - what the list counts and what a
     * delete is refused on. Read off the rows themselves rather than kept: a
     * count kept beside them is a count that drifts the first time a run draws
     * a picture. A version (an image's unsaved change, a video made larger) is
     * not a thing of its own and is not counted.
     */
    fun counts(workspaceId: Long, kind: DesignCatalogKind): Map<Long, Int> {
        val fallback = requireNotNull(defaultOf(workspaceId, kind).id)
        return membersOf(workspaceId, kind).groupingBy { it ?: fallback }.eachCount()
    }

    fun create(workspaceId: Long, kind: DesignCatalogKind, name: String): DesignCatalog {
        defaultOf(workspaceId, kind)
        val called = cleanName(name)
        if (repository(kind).findByWorkspaceIdAndName(workspaceId, called) != null) throw DesignCatalogNameTakenException(called)
        val made = save(kind, made(kind, workspaceId, called, isDefault = false))
        audit.record(workspaceId, WorkspaceAuditCategory.WORKSPACE, "${label(kind)} catalog $called added")
        return made
    }

    fun rename(workspaceId: Long, kind: DesignCatalogKind, id: Long, name: String): DesignCatalog {
        val held = catalog(workspaceId, kind, id)
        val called = cleanName(name)
        if (called == held.name) return held
        repository(kind).findByWorkspaceIdAndName(workspaceId, called)?.takeIf { it.id != id }
            ?.let { throw DesignCatalogNameTakenException(called) }
        val was = held.name
        held.name = called
        val saved = save(kind, held)
        audit.record(workspaceId, WorkspaceAuditCategory.WORKSPACE, "${label(kind)} catalog $was renamed to $called")
        return saved
    }

    fun delete(workspaceId: Long, kind: DesignCatalogKind, id: Long) {
        val held = catalog(workspaceId, kind, id)
        if (held.isDefault) throw DesignCatalogDefaultException(held.name)
        val count = counts(workspaceId, kind)[id] ?: 0
        if (count > 0) throw DesignCatalogNotEmptyException(held.name, count, kind.things)
        deleteRow(kind, held)
        audit.record(workspaceId, WorkspaceAuditCategory.WORKSPACE, "${label(kind)} catalog ${held.name} removed")
    }

    /**
     * The catalog of each thing of [kind] in the workspace, as stored - null
     * meaning the default. One query per table, selecting the column and
     * nothing else: a wireframe's drawing or a picture's prompt is no part of
     * counting it.
     */
    private fun membersOf(workspaceId: Long, kind: DesignCatalogKind): List<Long?> = when (kind) {
        DesignCatalogKind.IMAGE ->
            column("select x.catalogId from DesignImage x where x.workspaceId = :w and x.versionOf is null", workspaceId) +
                column("select x.catalogId from ExecutionPicture x where x.workspaceId = :w", workspaceId) +
                column("select x.catalogId from TaskPicture x where x.workspaceId = :w", workspaceId)
        DesignCatalogKind.WIREFRAME -> column("select x.catalogId from Wireframe x where x.workspaceId = :w", workspaceId)
        DesignCatalogKind.VIDEO -> column("select x.catalogId from DesignVideo x where x.workspaceId = :w and x.versionOf is null", workspaceId)
    }

    private fun column(query: String, workspaceId: Long): List<Long?> =
        entities.createQuery(query, java.lang.Long::class.java).setParameter("w", workspaceId).resultList.map { it?.toLong() }

    @Suppress("UNCHECKED_CAST")
    private fun repository(kind: DesignCatalogKind): DesignCatalogRepository<DesignCatalog> = when (kind) {
        DesignCatalogKind.IMAGE -> images
        DesignCatalogKind.WIREFRAME -> wireframes
        DesignCatalogKind.VIDEO -> videos
    } as DesignCatalogRepository<DesignCatalog>

    private fun save(kind: DesignCatalogKind, catalog: DesignCatalog): DesignCatalog = repository(kind).saveAndFlush(catalog)

    private fun deleteRow(kind: DesignCatalogKind, catalog: DesignCatalog) = repository(kind).delete(catalog)

    private fun made(kind: DesignCatalogKind, workspaceId: Long, name: String, isDefault: Boolean): DesignCatalog = when (kind) {
        DesignCatalogKind.IMAGE -> ImageCatalog(workspaceId, name, isDefault, who())
        DesignCatalogKind.WIREFRAME -> WireframeCatalog(workspaceId, name, isDefault, who())
        DesignCatalogKind.VIDEO -> VideoCatalog(workspaceId, name, isDefault, who())
    }

    private fun cleanName(name: String): String =
        name.trim().takeIf { it.isNotEmpty() && it.length <= NAME_LENGTH } ?: throw DesignCatalogNameInvalidException()

    /** Whoever is signed in; an agent working through the orknux_* tools, by its name. */
    private fun who(): String {
        val signedIn = SecurityContextHolder.getContext().authentication
            ?.takeIf { it.isAuthenticated && it !is AnonymousAuthenticationToken }
        return signedIn?.name ?: ActingIn.actor() ?: "orknux"
    }

    companion object {
        const val NAME_LENGTH = 120

        /** What a workspace's default catalog of each kind is called when it is made: documents' first catalog's name. */
        const val FIRST = Documents.FIRST_CATALOG

        /** How the audit names a kind's catalog: "Image catalog Holiday added". */
        fun label(kind: DesignCatalogKind): String = when (kind) {
            DesignCatalogKind.IMAGE -> "Image"
            DesignCatalogKind.WIREFRAME -> "Wireframe"
            DesignCatalogKind.VIDEO -> "Video"
        }
    }
}
