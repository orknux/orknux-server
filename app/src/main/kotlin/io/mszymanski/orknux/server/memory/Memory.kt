package io.mszymanski.orknux.server.memory

import io.mszymanski.orknux.server.graphql.Refusal
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import java.time.OffsetDateTime

/**
 * A folder of memories.
 *
 * Its own table rather than a label on a memory, because the screen lists
 * catalogs beside the memories of the one selected: a catalog is a thing that
 * exists, and has a count worth showing, before anything has been put in it.
 */
@Entity
@Table(name = "memory_catalog")
class MemoryCatalog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Column(nullable = false, length = 120)
    var name: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "created_by", nullable = false, length = 120)
    val createdBy: String = "",

    /**
     * The one catalog this workspace always has.
     *
     * A catalog is what an agent is granted and what a memory is filed into, so
     * a workspace with none has nowhere for either - `memory_save` refuses
     * because there is nothing to write to, and the two memory tools are not
     * offered at all. The first thing anybody has to do before an agent can
     * remember anything was therefore a piece of setup nobody is told about.
     *
     * This one cannot be deleted, so that floor cannot be taken away again. It
     * can be renamed, which is what makes "General" a starting point rather than
     * a name somebody is stuck with - and it is why this is a flag rather than a
     * name: a catalog renamed to "What the desk knows" is still the one that
     * stays.
     */
    @Column(name = "is_default", nullable = false)
    var isDefault: Boolean = false,
)

/**
 * One thing the workspace wants remembered.
 *
 * [createdBy] is kept beside [lastModifiedBy] because the card says who added
 * it and the filter groups by that — an editor is not the author, and
 * collapsing the two would rewrite who contributed something the moment
 * somebody fixed a typo in it.
 */
@Entity
@Table(name = "memory")
class Memory(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "catalog_id", nullable = false)
    var catalogId: Long,

    @Column(nullable = false, length = 200)
    var title: String,

    @Column(nullable = false, columnDefinition = "text")
    var content: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "created_by", nullable = false, length = 120)
    val createdBy: String = "",

    @Column(name = "last_modified_at", nullable = false)
    var lastModifiedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_modified_by", nullable = false, length = 120)
    var lastModifiedBy: String = "",
)

interface MemoryCatalogRepository : JpaRepository<MemoryCatalog, Long> {

    fun findByWorkspaceIdOrderByNameAsc(workspaceId: Long): List<MemoryCatalog>

    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): MemoryCatalog?

    fun deleteByWorkspaceId(workspaceId: Long)
}

interface MemoryRepository : JpaRepository<Memory, Long>, JpaSpecificationExecutor<Memory> {

    fun countByCatalogId(catalogId: Long): Long

    fun findByCatalogIdAndTitle(catalogId: Long, title: String): Memory?

    /** Who has written in this catalog, for the author filter. */
    @Query("SELECT DISTINCT m.createdBy FROM Memory m WHERE m.catalogId = :catalogId ORDER BY m.createdBy")
    fun authors(catalogId: Long): List<String>

    /** Everything in a set of catalogs, newest change first: what an agent is given. */
    fun findByCatalogIdInOrderByLastModifiedAtDesc(catalogIds: Collection<Long>): List<Memory>

    fun deleteByCatalogId(catalogId: Long)
}

/**
 * What the toolbar asked for, as a specification rather than JPQL.
 *
 * A `:param IS NULL OR …` in JPQL sends a null String with no inferable type,
 * and Postgres answers `function lower(bytea) does not exist`. Leaving the
 * clause out entirely is the fix, which is what a specification is for — the
 * same reason `auditFilter` is built this way.
 *
 * The search covers the title and the body together: somebody looking for a
 * memory remembers what it said as often as what it was called.
 */
fun memoryFilter(catalogId: Long, search: String?, author: String?): Specification<Memory> =
    Specification { root, _, builder ->
        val predicates = mutableListOf(builder.equal(root.get<Long>("catalogId"), catalogId))

        author?.let { predicates += builder.equal(root.get<String>("createdBy"), it) }
        search?.let {
            val pattern = "%${it.lowercase()}%"
            predicates += builder.or(
                builder.like(builder.lower(root.get("title")), pattern),
                builder.like(builder.lower(root.get("content")), pattern),
            )
        }

        builder.and(*predicates.toTypedArray())
    }

class MemoryCatalogNotFoundException(val id: Long) : RuntimeException("No memory catalog with id $id"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

class MemoryCatalogNameTakenException(val name: String) :
    RuntimeException("A memory catalog named \"$name\" already exists in this workspace"), Refusal {

    override val arguments get() = mapOf("name" to name)
}

class MemoryCatalogNameInvalidException : RuntimeException("A catalog name is required")

/**
 * A memory catalog an agent may read is not one to delete.
 *
 * Said of the catalog and not of the memories in it, because the catalog is what
 * an agent is granted: the memories go with it, so the loss is everything in the
 * folder at once. Named agents rather than a count, because the way out is to go
 * and take the grant off each of them and "2 agents" does not say which.
 */
class MemoryCatalogInUseException(val name: String, val agents: List<String>) : RuntimeException(
    "$name is granted to ${agents.joinToString(", ")}, so it cannot be deleted",
), Refusal {

    override val arguments get() = mapOf("name" to name, "agents" to agents)
}

/**
 * The one catalog a workspace always has, asked to be deleted.
 *
 * Said rather than quietly ignored, and it names the way out: the catalog can be
 * renamed, so somebody who does not want a catalog called General is one edit
 * away rather than stuck.
 */
/**
 * What a workspace's one catalog is called to begin with.
 *
 * Here rather than at the two places that make one - the migration seeds what
 * already existed and `createWorkspace` seeds everything since - because the two
 * have to agree, and a word written out twice is a word that drifts.
 */
const val FIRST_MEMORY_CATALOG = "General"

class MemoryCatalogNotRemovableException(val name: String) : RuntimeException(
    "$name is this workspace's own catalog and is always here, so it cannot be deleted. " +
        "It can be renamed.",
), Refusal {

    override val arguments get() = mapOf("name" to name)
}

class MemoryNotFoundException(val id: Long) : RuntimeException("No memory with id $id"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

class MemoryTitleInvalidException : RuntimeException("A memory title is required")

class MemoryContentInvalidException : RuntimeException("A memory needs something to remember")
