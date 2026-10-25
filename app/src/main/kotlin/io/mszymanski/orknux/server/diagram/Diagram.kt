package io.mszymanski.orknux.server.diagram

import io.mszymanski.orknux.server.graphql.Refusal
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime

/**
 * A folder of diagrams, in Explore -> Diagrams.
 *
 * Its own table rather than a label on a diagram, as a memory catalog is: a
 * catalog exists, and is listed, before anything has been put in it.
 */
@Entity
@Table(name = "diagram_catalog")
class DiagramCatalog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Column(nullable = false, length = Diagrams.CATALOG_NAME_LENGTH)
    var name: String,

    @Column(name = "created_by", nullable = false, length = 255)
    val createdBy: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
)

/**
 * What an agent may do with a diagram through the orknux_* tools.
 *
 * The editor's own chat is not held to it: a person is there asking for the
 * change, which is the permission this exists to stand in for.
 */
enum class DiagramAgentAccess {
    /** It may read and search it, and nothing else. */
    READ,

    /** It may change it, and delete it where the agent holds the delete tool. */
    CHANGE,
}

/**
 * A diagram: boxes, the lines between them, and notes on each box.
 *
 * The drawing is held as rows ([DiagramNode], [DiagramEdge]) because it is read
 * a node at a time as often as whole - searched across the workspace, and one
 * node's notes and neighbours read on their own. [revision] counts the saves,
 * so a save made against a diagram that has changed since is refused instead of
 * written over what changed it.
 */
@Entity
@Table(name = "diagram")
class Diagram(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Column(name = "catalog_id", nullable = false)
    var catalogId: Long,

    @Column(nullable = false, length = Diagrams.NAME_LENGTH)
    var name: String,

    /**
     * Change, for a new one: the reason diagrams are offered to agents at all is
     * that they keep them right as they learn, and a diagram somebody wants
     * left alone is one switch away.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "agent_access", nullable = false, length = 10)
    var agentAccess: DiagramAgentAccess = DiagramAgentAccess.CHANGE,

    @Column(nullable = false)
    var revision: Int = 1,

    @Column(name = "created_by", nullable = false, length = 255)
    val createdBy: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_by", nullable = false, length = 255)
    var updatedBy: String,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)

/** One box on a diagram. Its [key] is what the editor, the tools and the edges name it by. */
@Entity
@Table(name = "diagram_node")
class DiagramNode(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "diagram_id", nullable = false)
    val diagramId: Long,

    @Column(name = "node_key", nullable = false, length = Diagrams.ID_LENGTH)
    val key: String,

    /** Where it is in the drawing's order: what is drawn later is drawn over. */
    @Column(nullable = false)
    val position: Int,

    @Column(nullable = false, length = 20)
    val kind: String,

    @Column(nullable = false, length = Diagrams.LABEL_LENGTH)
    val label: String,

    @Column(nullable = false) val x: Int,
    @Column(nullable = false) val y: Int,
    @Column(nullable = false) val w: Int,
    @Column(nullable = false) val h: Int,

    /** `#rrggbb`, or null for the shape's own colour. */
    @Column(length = 7)
    val color: String? = null,

    @Column(columnDefinition = "text")
    val notes: String? = null,

    /** The group it sits in, by key. */
    @Column(name = "parent_key", length = Diagrams.ID_LENGTH)
    val parentKey: String? = null,
)

/** One line between two boxes, by their keys. */
@Entity
@Table(name = "diagram_edge")
class DiagramEdge(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "diagram_id", nullable = false)
    val diagramId: Long,

    @Column(name = "edge_key", nullable = false, length = Diagrams.ID_LENGTH)
    val key: String,

    @Column(nullable = false)
    val position: Int,

    @Column(name = "source_key", nullable = false, length = Diagrams.ID_LENGTH)
    val sourceKey: String,

    @Column(name = "target_key", nullable = false, length = Diagrams.ID_LENGTH)
    val targetKey: String,

    @Column(length = Diagrams.LABEL_LENGTH)
    val label: String? = null,

    @Column(nullable = false, length = 20)
    val kind: String,
)

interface DiagramCatalogRepository : JpaRepository<DiagramCatalog, Long> {
    fun findByWorkspaceIdOrderByNameAsc(workspaceId: Long): List<DiagramCatalog>
    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): DiagramCatalog?
}

interface DiagramRepository : JpaRepository<Diagram, Long> {
    /** A workspace's diagrams, the one worked on last first. */
    fun findByWorkspaceIdOrderByUpdatedAtDescIdDesc(workspaceId: Long): List<Diagram>
    fun countByCatalogId(catalogId: Long): Long
    fun existsByWorkspaceId(workspaceId: Long): Boolean
}

interface DiagramNodeRepository : JpaRepository<DiagramNode, Long> {
    fun findByDiagramIdOrderByPositionAsc(diagramId: Long): List<DiagramNode>
    fun countByDiagramId(diagramId: Long): Long

    /**
     * The nodes of [diagramIds] whose label or notes hold [pattern] - lower
     * case, with its `%` - for a search to rank. Narrowed in the database by one
     * word, ranked by all of them in [DiagramSearch].
     */
    @Query(
        "SELECT n FROM DiagramNode n WHERE n.diagramId IN :diagramIds AND " +
            "(LOWER(n.label) LIKE :pattern OR LOWER(n.notes) LIKE :pattern)",
    )
    fun matching(@Param("diagramIds") diagramIds: Collection<Long>, @Param("pattern") pattern: String): List<DiagramNode>

    /** Bulk, so it is done before the rows that replace it are inserted. */
    @Modifying
    @Query("DELETE FROM DiagramNode n WHERE n.diagramId = :diagramId")
    fun deleteByDiagram(@Param("diagramId") diagramId: Long): Int
}

interface DiagramEdgeRepository : JpaRepository<DiagramEdge, Long> {
    fun findByDiagramIdOrderByPositionAsc(diagramId: Long): List<DiagramEdge>
    fun countByDiagramId(diagramId: Long): Long

    @Query("SELECT e FROM DiagramEdge e WHERE e.diagramId IN :diagramIds AND LOWER(e.label) LIKE :pattern")
    fun matching(@Param("diagramIds") diagramIds: Collection<Long>, @Param("pattern") pattern: String): List<DiagramEdge>

    @Modifying
    @Query("DELETE FROM DiagramEdge e WHERE e.diagramId = :diagramId")
    fun deleteByDiagram(@Param("diagramId") diagramId: Long): Int
}

/** The formats a diagram is held to. How long notes may be, and how much one holds, are Tunables. */
object Diagrams {
    const val NAME_LENGTH = 200
    const val CATALOG_NAME_LENGTH = 120

    /** A node's or an edge's own words. */
    const val LABEL_LENGTH = 500

    /** What a node or an edge is named by. */
    const val ID_LENGTH = 64

    /** How far from the origin anything may be put, either way, in pixels. */
    const val REACH = 100_000

    /** What a new workspace's first catalog is called, when the first diagram needs one. */
    const val FIRST_CATALOG = "General"
}

/**
 * What a node can be drawn as. The [said] of each is what a model is told the
 * shape is for, read off here by the tools, so the editor's palette and the
 * model's vocabulary are one list.
 */
enum class DiagramNodeKind(val key: String, val said: String, val w: Int, val h: Int) {
    BOX("box", "a plain rectangle: a system, a service, a step", 160, 60),
    ROUNDED("rounded", "a rectangle with round corners: a start or end, a person, a team", 160, 60),
    ELLIPSE("ellipse", "an ellipse: a state, an event, a data store", 160, 70),
    DIAMOND("diamond", "a diamond: a decision or a choice", 140, 90),
    NOTE("note", "a sticky note: a remark about the diagram rather than part of it", 180, 100),
    GROUP("group", "a labelled area other nodes sit inside, given as their parent: a boundary, a network, a team", 360, 240),
    ;

    companion object {
        fun byKey(key: String?): DiagramNodeKind? = entries.firstOrNull { it.key == key?.trim()?.lowercase() }
    }
}

/** What a line between two nodes is drawn as. */
enum class DiagramEdgeKind(val key: String, val said: String) {
    ARROW("arrow", "an arrow from one node to the other: calls, flows to, depends on"),
    LINE("line", "a plain line: is related to"),
    DASHED("dashed", "a dashed arrow: optional, asynchronous, or happens sometimes"),
    BOTH("both", "an arrow at both ends: talks both ways"),
    ;

    companion object {
        fun byKey(key: String?): DiagramEdgeKind? = entries.firstOrNull { it.key == key?.trim()?.lowercase() }
    }
}

class DiagramNotFoundException(val id: Long) : RuntimeException("No diagram with id $id"), Refusal {
    override val arguments get() = mapOf("id" to id)
}

class DiagramCatalogNotFoundException(val id: Long) : RuntimeException("No diagram catalog with id $id"), Refusal {
    override val arguments get() = mapOf("id" to id)
}

class DiagramNameInvalidException :
    RuntimeException("A diagram needs a name, ${Diagrams.NAME_LENGTH} characters at most")

class DiagramCatalogNameInvalidException :
    RuntimeException("A diagram catalog needs a name, ${Diagrams.CATALOG_NAME_LENGTH} characters at most")

class DiagramCatalogNameTakenException(val name: String) :
    RuntimeException("A diagram catalog named \"$name\" already exists in this workspace"), Refusal {
    override val arguments get() = mapOf("name" to name)
}

/** A catalog with diagrams in it is not deleted with them: they are moved or deleted first. */
class DiagramCatalogNotEmptyException(val name: String, val count: Long) :
    RuntimeException("$name holds $count diagrams; move or delete them first"), Refusal {
    override val arguments get() = mapOf("name" to name, "count" to count)
}

class DiagramDocumentInvalidException(val why: String) :
    RuntimeException("The diagram cannot be saved: $why"), Refusal {
    override val arguments get() = mapOf("why" to why)
}

class DiagramNotesTooLongException(val node: String, val length: Int, val most: Int) :
    RuntimeException("The notes on $node are $length characters, and a node's notes may be $most at most"), Refusal {
    override val arguments get() = mapOf("node" to node, "length" to length, "most" to most)
}

class DiagramTooLargeException(val count: Int, val most: Int) :
    RuntimeException("A diagram holds at most $most nodes and connections, and this one would hold $count"), Refusal {
    override val arguments get() = mapOf("count" to count, "most" to most)
}

class DiagramChangedException(val name: String) :
    RuntimeException("Diagram $name was changed since it was opened; open it again before saving"), Refusal {
    override val arguments get() = mapOf("name" to name)
}

/** Said to an agent that tried to change a diagram set to Read only. */
class DiagramReadOnlyException(val name: String) :
    RuntimeException("Diagram $name is read only for agents; a person can change that in its Agents setting"), Refusal {
    override val arguments get() = mapOf("name" to name)
}

class DiagramAnswerFailedException(val reason: String) :
    RuntimeException("The model could not answer: $reason"), Refusal {
    override val arguments get() = mapOf("reason" to reason)
}
