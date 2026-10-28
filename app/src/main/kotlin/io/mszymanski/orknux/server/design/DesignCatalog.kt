package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.server.graphql.Refusal
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.NoRepositoryBean
import java.time.OffsetDateTime

/**
 * What a catalog in Design can hold. Each is a table of its own, as a document
 * or a diagram catalog is; what they share is how they behave, which is
 * [DesignCatalogs]'s.
 */
enum class DesignCatalogKind(
    /** What the catalog holds, in the words its refusals and audit entries use. */
    val things: String,
) {
    IMAGE("images"),
    WIREFRAME("wireframes"),
    VIDEO("videos"),
}

/**
 * A folder of one kind of Design's things.
 *
 * Its own table per kind, as `document_catalog` and `diagram_catalog` are: a
 * catalog exists, and is listed, before anything has been put in it. One per
 * workspace and kind is its default ([isDefault]), called General when it is
 * made: what nothing has to be filed in to be in, so nobody has to make a
 * catalog before they can make anything. A thing whose catalog is null is in
 * the default; see [DesignCatalogs.resolve].
 */
@MappedSuperclass
abstract class DesignCatalog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Column(nullable = false, length = DesignCatalogs.NAME_LENGTH)
    var name: String,

    @Column(name = "is_default", nullable = false)
    val isDefault: Boolean = false,

    @Column(name = "created_by", nullable = false, length = 255)
    val createdBy: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
)

@Entity
@Table(name = "image_catalog")
class ImageCatalog(workspaceId: Long, name: String, isDefault: Boolean = false, createdBy: String) :
    DesignCatalog(workspaceId = workspaceId, name = name, isDefault = isDefault, createdBy = createdBy)

@Entity
@Table(name = "wireframe_catalog")
class WireframeCatalog(workspaceId: Long, name: String, isDefault: Boolean = false, createdBy: String) :
    DesignCatalog(workspaceId = workspaceId, name = name, isDefault = isDefault, createdBy = createdBy)

@Entity
@Table(name = "video_catalog")
class VideoCatalog(workspaceId: Long, name: String, isDefault: Boolean = false, createdBy: String) :
    DesignCatalog(workspaceId = workspaceId, name = name, isDefault = isDefault, createdBy = createdBy)

/** The queries every kind's catalogs answer, written once; each kind's repository is this over its own entity. */
@NoRepositoryBean
interface DesignCatalogRepository<T : DesignCatalog> : JpaRepository<T, Long> {

    fun findByWorkspaceIdOrderByNameAsc(workspaceId: Long): List<T>

    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): T?

    fun findByWorkspaceIdAndIsDefaultTrue(workspaceId: Long): T?
}

interface ImageCatalogRepository : DesignCatalogRepository<ImageCatalog>

interface WireframeCatalogRepository : DesignCatalogRepository<WireframeCatalog>

interface VideoCatalogRepository : DesignCatalogRepository<VideoCatalog>

class DesignCatalogNotFoundException(val id: Long) : RuntimeException("No catalog with id $id here"), Refusal {
    override val arguments get() = mapOf("id" to id)
}

class DesignCatalogNameInvalidException :
    RuntimeException("A catalog needs a name, ${DesignCatalogs.NAME_LENGTH} characters at most")

class DesignCatalogNameTakenException(val name: String) :
    RuntimeException("This workspace already has a catalog called $name"), Refusal {
    override val arguments get() = mapOf("name" to name)
}

/**
 * A catalog with things in it, asked to be deleted. Refused rather than taking
 * them with it, as a document catalog is: a catalog is a folder, and emptying a
 * folder by deleting it is how somebody loses work they meant to keep.
 */
class DesignCatalogNotEmptyException(val name: String, val count: Int, val things: String) :
    RuntimeException("$name holds $count $things; move or delete them before deleting it"), Refusal {
    override val arguments get() = mapOf("name" to name, "count" to count, "things" to things)
}

/** The default catalog, asked to be deleted: there is always one, so nothing has to be filed to be made. */
class DesignCatalogDefaultException(val name: String) :
    RuntimeException("$name is where anything not filed elsewhere goes, so it cannot be deleted"), Refusal {
    override val arguments get() = mapOf("name" to name)
}
