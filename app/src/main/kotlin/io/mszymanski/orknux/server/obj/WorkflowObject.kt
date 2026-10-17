package io.mszymanski.orknux.server.obj

import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OrderColumn
import jakarta.persistence.Table
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime

/**
 * The shape one property has.
 *
 * [OBJECT] names another object, and [ARRAY] holds many of something — either a
 * scalar or, again, another object. Keeping the reference as an id rather than a
 * name is what stops a rename from breaking every property that pointed at it.
 */
enum class PropertyKind {
    STRING,
    NUMBER,
    BOOLEAN,
    OBJECT,
    ARRAY,
}

/**
 * One property of an object: a name and a shape, and nothing about behaviour.
 *
 * An `@Embeddable` in an ordered collection rather than an entity of its own,
 * because a property has no life apart from the object holding it — the editor
 * saves the whole list, and a property nobody kept is one that is gone.
 */
@Embeddable
class ObjectProperty(
    @Column(nullable = false, length = 64)
    var name: String = "",

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    var kind: PropertyKind = PropertyKind.STRING,

    /** The object this points at, when the kind is one or an array of them. */
    @Column(name = "ref_object_id")
    var refObjectId: Long? = null,

    /** What an array holds when it holds scalars; null when it holds objects. */
    @Enumerated(EnumType.STRING)
    @Column(name = "element_kind", length = 16)
    var elementKind: PropertyKind? = null,

    /**
     * What this field means, for whoever — or whatever — reads it.
     *
     * A name says what a field is called and nothing about what belongs in it,
     * which is a gap a person fills from context and a model fills by guessing.
     * This is where the context goes, so that both of them read the same
     * sentence rather than two different inferences from one word.
     */
    @Column(length = 500)
    var description: String? = null,

    /**
     * The fields of an object defined in place, as JSON - see [InlineShapes].
     * Set on an OBJECT with no [refObjectId], or an ARRAY of such objects;
     * null everywhere else.
     */
    @Column(name = "inline_properties", columnDefinition = "text")
    var inlineProperties: String? = null,
)

/**
 * A named data structure the workspace can point at.
 *
 * The reason it exists is references: a field a node points at is unchecked and
 * unofferable until something knows what the event carries. An
 * object is that something, and one definition serves a trigger's output, a
 * function's argument and an agent's structured answer alike.
 *
 * Fields only. The name is deliberately not "record": an object with members
 * can grow methods later without the concept having to be renamed, and a record
 * that grew them would be a contradiction.
 */
@Entity
@Table(name = "workflow_object")
class WorkflowObject(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    /**
     * The workspace that drew this shape, or null for one a plugin brought.
     *
     * Nullable for the reason a plugin's function row has no workspace: a
     * plugin belongs to the installation, so what it exports belongs to every
     * workspace at once. Exactly one of this and [pluginId] is set.
     */
    @Column(name = "workspace_id")
    val workspaceId: Long? = null,

    /**
     * The plugin that exports this shape, or null for a workspace's own.
     *
     * Set means the row is the plugin's: it is replaced wholesale the next
     * time the plugin is loaded, goes when the plugin is unloaded, and there
     * is nowhere to edit it. A property pointing at it points by id like any
     * other, so everything that already follows a reference follows this one
     * without knowing it came from a plugin.
     */
    @Column(name = "plugin_id")
    var pluginId: Long? = null,

    @Column(nullable = false, length = 64)
    var name: String,

    @Column
    var description: String? = null,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "object_property", joinColumns = [JoinColumn(name = "object_id")])
    @OrderColumn(name = "position")
    var properties: MutableList<ObjectProperty> = mutableListOf(),

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "created_by", nullable = false, length = 255)
    val createdBy: String = "",

    @Column(name = "last_modified_at", nullable = false)
    var lastModifiedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_modified_by", nullable = false, length = 255)
    var lastModifiedBy: String = "",
)

interface WorkflowObjectRepository : JpaRepository<WorkflowObject, Long> {

    fun findByWorkspaceId(workspaceId: Long, pageable: Pageable): Page<WorkflowObject>

    /**
     * The same, narrowed to what a word appears in.
     *
     * The name and the description, which is what a row shows that somebody could be remembering.
     * Case-insensitive and a substring rather than a prefix: what people recall
     * is a phrase from the middle of a name, not how it opened.
     *
     * Asked of the database rather than sieved in the browser because the list
     * is paged - narrowing what arrived on page one would hide matches sitting
     * on page four and quietly call that "no results".
     */
    @Query(
        """
        SELECT e FROM WorkflowObject e
        WHERE e.workspaceId = :workspaceId
          AND (LOWER(e.name) LIKE LOWER(CONCAT('%', :looking, '%'))
            OR LOWER(COALESCE(e.description, '')) LIKE LOWER(CONCAT('%', :looking, '%')))
        """,
    )
    fun searching(
        @Param("workspaceId") workspaceId: Long,
        @Param("looking") looking: String,
        pageable: Pageable,
    ): Page<WorkflowObject>

    fun findByWorkspaceId(workspaceId: Long): List<WorkflowObject>

    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): WorkflowObject?

    /** What one plugin exports, for the reconcile that replaces it. */
    fun findByPluginId(pluginId: Long): List<WorkflowObject>

    /** Every plugin's, for a list that shows what a workspace may point at. */
    fun findByPluginIdIsNotNull(): List<WorkflowObject>

    fun findByPluginIdAndName(pluginId: Long, name: String): WorkflowObject?
}
