package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.server.security.WorkspaceAccess
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional

/**
 * The catalogs of Design's images, wireframes and videos, through one door for
 * the three: they behave alike, so the interface draws one catalog column for
 * all of them and asks for it by kind. Moving a thing between catalogs is its
 * own controller's, beside the rest of what is done to it.
 */
@Controller
class DesignCatalogAPI(
    private val catalogs: DesignCatalogs,
    private val access: WorkspaceAccess,
) {

    /** Not read-only: a workspace's default catalog is made the first time its catalogs are asked for. */
    @QueryMapping
    @Transactional
    fun designCatalogs(@Argument workspaceId: Long, @Argument kind: DesignCatalogKind): List<DesignCatalogView> {
        access.requireVisible(workspaceId)
        val counts = catalogs.counts(workspaceId, kind)
        return catalogs.catalogs(workspaceId, kind).map { view(it, counts) }
    }

    @MutationMapping
    @Transactional
    fun createDesignCatalog(@Argument workspaceId: Long, @Argument kind: DesignCatalogKind, @Argument name: String): DesignCatalogView {
        access.requireVisible(workspaceId)
        return view(catalogs.create(workspaceId, kind, name), emptyMap())
    }

    @MutationMapping
    @Transactional
    fun renameDesignCatalog(
        @Argument workspaceId: Long,
        @Argument kind: DesignCatalogKind,
        @Argument id: Long,
        @Argument name: String,
    ): DesignCatalogView {
        access.requireVisible(workspaceId)
        return view(catalogs.rename(workspaceId, kind, id, name), catalogs.counts(workspaceId, kind))
    }

    @MutationMapping
    @Transactional
    fun deleteDesignCatalog(@Argument workspaceId: Long, @Argument kind: DesignCatalogKind, @Argument id: Long): Boolean {
        access.requireVisible(workspaceId)
        catalogs.delete(workspaceId, kind, id)
        return true
    }

    private fun view(catalog: DesignCatalog, counts: Map<Long, Int>) = DesignCatalogView(
        id = requireNotNull(catalog.id).toString(),
        name = catalog.name,
        isDefault = catalog.isDefault,
        count = counts[catalog.id] ?: 0,
    )
}

data class DesignCatalogView(val id: String, val name: String, val isDefault: Boolean, val count: Int)
