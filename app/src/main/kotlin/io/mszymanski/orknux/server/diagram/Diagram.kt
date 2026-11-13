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

    /** What it is for, in a sentence or two - what an agent picks a diagram by. Held to a Tunable. */
    @Column(columnDefinition = "text")
    var description: String? = null,

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

    /**
     * Its layers, bottom first, as JSON: `[{"id","name","visible","locked"}]`.
     * Null for a diagram with none of its own, which is one layer - Base - that
     * every box and line is on. Written with the drawing, by [DiagramDocument].
     */
    @Column(columnDefinition = "text")
    var layers: String? = null,

    @Column(name = "created_by", nullable = false, length = 255)
    val createdBy: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_by", nullable = false, length = 255)
    var updatedBy: String,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),

    /**
     * The [DiagramDoor] the live drawing was saved through, which the revision
     * the next save displaces it into says. Null on a diagram not saved since V658.
     */
    @Column(name = "updated_via", length = 16)
    var updatedVia: String? = null,

    /** The diagram chat's turn that saved it last; the saves one turn makes keep one version between them. */
    @Column(name = "updated_turn", length = 64)
    var updatedTurn: String? = null,
)

/**
 * Which door a diagram's drawing was saved through, as its history says it.
 * The names are a document's `DocumentDoor`'s, so the interface says both in
 * the same words.
 */
enum class DiagramDoor {
    EDITOR,
    CHAT,
    TOOL,
    RESTORE,
}

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

    /** The layer it is on; null is the first, Base. */
    @Column(name = "layer_key", length = Diagrams.ID_LENGTH)
    val layerKey: String? = null,

    /** What it links to: a web address, or another diagram as `diagram:<id>`. V656. */
    @Column(columnDefinition = "text")
    val link: String? = null,

    /** The picture it shows, where it is an image: a [DiagramImage]'s id, or an address on the web. V660. */
    @Column(columnDefinition = "text")
    val image: String? = null,

    /** Its outline's colour and thickness; null is as every box was drawn. V657. */
    @Column(name = "border_color", length = 7)
    val borderColor: String? = null,

    @Column(name = "border_width")
    val borderWidth: Int? = null,

    /** How far it is turned about its middle, in whole degrees clockwise; null is upright. V685. */
    val rotation: Int? = null,
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

    /** The nearest of the four kinds, kept for a reader that knows only them - an older jar among them. */
    @Column(nullable = false, length = 20)
    val kind: String,

    /** How the line is drawn and what each end carries; null on a row written before they existed, read off [kind]. V629. */
    @Column(name = "line_style", length = 20)
    val lineStyle: String? = null,

    @Column(name = "start_end", length = 20)
    val startEnd: String? = null,

    @Column(name = "end_end", length = 20)
    val endEnd: String? = null,

    /** How the line finds its way between its two nodes; null on a row written before V633, which is straight. */
    @Column(length = 20)
    val routing: String? = null,

    /** The layer it is on; null is the first, Base. */
    @Column(name = "layer_key", length = Diagrams.ID_LENGTH)
    val layerKey: String? = null,

    /** Its own corners, "x,y x,y", where it was routed around what is in its way; null where it has none. V655. */
    @Column(columnDefinition = "text")
    val points: String? = null,

    /** Where along it its label sits, a share of its length, where the router moved it; null is the middle of its longest run. V661. */
    @Column(name = "label_at")
    val labelAt: Double? = null,

    /** Its colour and thickness; null is as every line was drawn. V657. */
    @Column(length = 7)
    val color: String? = null,

    @Column
    val width: Int? = null,

    /** The side of its source box it is pinned to; null is the floating end every line had. V684. */
    @Column(name = "from_side", length = 8)
    val fromSide: String? = null,

    /** The side of its target box it is pinned to; null is the floating end every line had. V684. */
    @Column(name = "to_side", length = 8)
    val toSide: String? = null,
)

interface DiagramCatalogRepository : JpaRepository<DiagramCatalog, Long> {
    fun findByWorkspaceIdOrderByNameAsc(workspaceId: Long): List<DiagramCatalog>
    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): DiagramCatalog?
}

interface DiagramRepository : JpaRepository<Diagram, Long> {

    /**
     * Locks the diagram's row where it is still at [revision], changing
     * nothing; 0 where somebody saved since. Held until the save's transaction
     * ends, so a second save over the same revision waits and then finds it
     * moved. Issue #683.
     */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query("update Diagram d set d.revision = d.revision where d.id = :id and d.revision = :revision")
    fun holdRevision(@org.springframework.data.repository.query.Param("id") id: Long, @org.springframework.data.repository.query.Param("revision") revision: Int): Int
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

    /**
     * Bulk, so it is done before the rows that replace it are inserted. And the
     * rows already read in this transaction - the drawing a save keeps as a
     * version - are let go with it: SQLite gives a new row the id a deleted one
     * had, and a row still held under that id refuses the insert.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM DiagramNode n WHERE n.diagramId = :diagramId")
    fun deleteByDiagram(@Param("diagramId") diagramId: Long): Int
}

interface DiagramEdgeRepository : JpaRepository<DiagramEdge, Long> {
    fun findByDiagramIdOrderByPositionAsc(diagramId: Long): List<DiagramEdge>
    fun countByDiagramId(diagramId: Long): Long

    @Query("SELECT e FROM DiagramEdge e WHERE e.diagramId IN :diagramIds AND LOWER(e.label) LIKE :pattern")
    fun matching(@Param("diagramIds") diagramIds: Collection<Long>, @Param("pattern") pattern: String): List<DiagramEdge>

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM DiagramEdge e WHERE e.diagramId = :diagramId")
    fun deleteByDiagram(@Param("diagramId") diagramId: Long): Int
}

/** The formats a diagram is held to. How long notes may be, and how much one holds, are Tunables. */
object Diagrams {
    const val NAME_LENGTH = 200
    const val CATALOG_NAME_LENGTH = 120

    /** A node's or an edge's own words. */
    const val LABEL_LENGTH = 500

    /** How long a box's link may be; a format - an address longer than this is not one a person typed. */
    const val LINK_LENGTH = 2000

    /** How a box links to another diagram: this, then the diagram's id. */
    const val DIAGRAM_LINK = "diagram:"

    /** And to one of the workspace's documents, or its wireframes: this, then its id. */
    const val DOCUMENT_LINK = "document:"
    const val WIREFRAME_LINK = "wireframe:"

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
    DATABASE("database", "a cylinder: a database or any other data store", 120, 80),
    DOCUMENT("document", "a page with a wavy foot: a document, a report, a file", 160, 80),
    HEXAGON("hexagon", "a hexagon: a preparation step, a setup", 160, 70),
    PARALLELOGRAM("parallelogram", "a slanted box: input or output", 170, 60),
    TRAPEZOID("trapezoid", "a box narrower at its foot: a manual operation", 170, 60),
    TRIANGLE("triangle", "a triangle: a warning, a merge, an extract", 120, 100),
    CLOUD("cloud", "a cloud: the internet, a cloud provider, something outside", 180, 110),
    ACTOR("actor", "a UML actor, a stick figure with its label beneath: a user, a person, an outside system that acts", 80, 110),
    CLASS("class", "a UML class: the label's first line is the name, and a line of just -- starts the next compartment, attributes then operations", 200, 120),
    COMPONENT("component", "a UML component, a box with two tabs on its left: a deployable part, a module", 170, 70),
    PACKAGE("package", "a UML package, a tabbed folder: a namespace, a module holding others", 170, 90),
    INTERFACE("interface", "a UML interface, a small circle with its label beneath: a provided interface, an API", 80, 80),
    DEVICE("device", "a 3D box, the UML node: a machine, a device, an execution environment", 170, 90),
    BOUNDARY("boundary", "a UML boundary: a screen or an endpoint, where an actor meets the system", 90, 90),
    CONTROL("control", "a UML control: logic that coordinates a use case", 90, 90),
    ENTITY("entity", "a UML entity: data the system keeps", 90, 90),
    SUBROUTINE("subroutine", "a box with double sides: a predefined process, a subroutine, a step described in another diagram", 170, 60),
    MANUAL_INPUT("manual-input", "a box with a sloping top: data typed in by hand, a form a person fills in", 170, 70),
    DELAY("delay", "a D shape: a wait, a delay, a timer, a scheduled pause", 150, 70),
    DISPLAY("display", "a screen shape: something shown to a person, a report on screen, a dashboard", 170, 70),
    OFF_PAGE("off-page", "a pentagon pointing down: a jump to another page or another diagram - give it a link", 100, 80),
    DOCUMENTS("documents", "a stack of pages: many documents, a batch of files, a set of reports", 170, 90),
    STEP("step", "a chevron: one stage of a pipeline or a value chain, drawn one after another left to right", 170, 60),
    CALLOUT("callout", "a speech bubble: a comment, a quote, what somebody says or asks", 180, 90),
    OCTAGON("octagon", "an octagon: a stop, a hard limit, a point where a flow ends or is refused", 100, 100),
    STAR("star", "a star: a highlight, a key result, a milestone", 120, 120),
    QUEUE("queue", "a cylinder on its side: a message queue, a topic, a stream - Kafka, RabbitMQ, SQS", 180, 60),
    STORAGE("storage", "a bucket: object or file storage - S3, blob storage, a shared drive", 120, 100),
    SERVER("server", "a server rack: a machine, a host, a virtual machine, a cluster of them", 120, 100),
    BROWSER("browser", "a browser window: a web application, a web page, a front end", 200, 120),
    MOBILE("mobile", "a phone: a mobile app, somebody on their phone", 80, 140),
    IMAGE("image", "a picture, its label a caption beneath: give image an address on the web (https://...) to show; one a person uploaded is named by its id", 240, 160),
    TEXT("text", "words alone, with no box, outline or fill: a title, a heading over part of the diagram, a caption or a remark placed anywhere", 200, 40),
    LINK("link", "a link card: another diagram, a document, a wireframe or a web page this one leads to - set its link (diagram:<id>, document:<id>, wireframe:<id> or https://...), and its label is the name shown on the card", 220, 64),
    GROUP("group", "a labelled area other nodes sit inside, given as their parent: a boundary, a network, a team", 360, 240),
    ;

    companion object {
        fun byKey(key: String?): DiagramNodeKind? = entries.firstOrNull { it.key == key?.trim()?.lowercase() }
    }
}

/**
 * What a line between two nodes was drawn as before it had a style and two
 * ends of its own: still read, from a model or a drawing written then, and
 * still written beside them as the nearest of the four - so an older jar, and
 * a reader that knows only these, draws something close. Each is exactly one
 * line style and two ends, which is what keeps an old drawing looking as it did.
 */
enum class DiagramEdgeKind(
    val key: String,
    val said: String,
    val line: DiagramLineStyle,
    val start: DiagramEndStyle,
    val end: DiagramEndStyle,
) {
    ARROW("arrow", "an arrow from one node to the other: calls, flows to, depends on", DiagramLineStyle.SOLID, DiagramEndStyle.NONE, DiagramEndStyle.ARROW),
    LINE("line", "a plain line: is related to", DiagramLineStyle.SOLID, DiagramEndStyle.NONE, DiagramEndStyle.NONE),
    DASHED("dashed", "a dashed arrow: optional, asynchronous, or happens sometimes", DiagramLineStyle.DASHED, DiagramEndStyle.NONE, DiagramEndStyle.ARROW),
    BOTH("both", "an arrow at both ends: talks both ways", DiagramLineStyle.SOLID, DiagramEndStyle.ARROW, DiagramEndStyle.ARROW),
    ;

    companion object {
        fun byKey(key: String?): DiagramEdgeKind? = entries.firstOrNull { it.key == key?.trim()?.lowercase() }

        /**
         * The one of the four nearest a line drawn as [line] with [start] and
         * [end]. None of the four has a mark at its start alone, so a line with
         * nothing at its end is a plain line, whatever its start carries.
         */
        fun nearest(line: DiagramLineStyle, start: DiagramEndStyle, end: DiagramEndStyle): DiagramEdgeKind = when {
            end == DiagramEndStyle.NONE -> LINE
            start != DiagramEndStyle.NONE -> BOTH
            line != DiagramLineStyle.SOLID -> DASHED
            else -> ARROW
        }
    }
}

/** How a line between two nodes is drawn along its length. */
enum class DiagramLineStyle(val key: String, val said: String) {
    SOLID("solid", "a solid line"),
    DASHED("dashed", "a dashed line: optional, asynchronous, a UML dependency or realisation"),
    DOTTED("dotted", "a dotted line: weaker still, a hint of a relation"),
    ;

    companion object {
        fun byKey(key: String?): DiagramLineStyle? = entries.firstOrNull { it.key == key?.trim()?.lowercase() }
    }
}

/**
 * What one end of a line is drawn with, where it meets its node. Enough of
 * UML's ends for its relations: an open arrow for a dependency or navigation,
 * a hollow triangle for inheritance, a hollow diamond for aggregation and a
 * filled one for composition, at the whole's end.
 */
enum class DiagramEndStyle(val key: String, val said: String) {
    NONE("none", "nothing"),
    ARROW("arrow", "a filled arrowhead: flows to, calls"),
    OPEN("open", "an open arrowhead: depends on, navigates to"),
    TRIANGLE("triangle", "a hollow triangle: inherits from, implements"),
    DIAMOND("diamond", "a hollow diamond, at the whole: aggregation, holds"),
    FILLED_DIAMOND("filled-diamond", "a filled diamond, at the whole: composition, owns"),
    CIRCLE("circle", "a small circle: an interface end, an optional link"),
    ;

    companion object {
        fun byKey(key: String?): DiagramEndStyle? = entries.firstOrNull { it.key == key?.trim()?.lowercase() }
    }
}

/**
 * How a line finds its way from one node to the other. Straight is what every
 * line was before there was a choice, so a line that says nothing is drawn as
 * it always was.
 */
enum class DiagramRouting(val key: String, val said: String) {
    STRAIGHT("straight", "straight across, edge to edge"),
    ORTHOGONAL("right-angles", "in horizontal and vertical runs with right-angled turns, side to side"),
    CURVED("curved", "a smooth curve, side to side"),
    ;

    companion object {
        fun byKey(key: String?): DiagramRouting? = entries.firstOrNull { it.key == key?.trim()?.lowercase() }
    }
}

/**
 * The side of a box one end of a line is pinned to, where somebody dragged it
 * there. Null is the line left to find its own way out - the side facing the
 * other box, wherever the boxes are - which is how every line ran before an end
 * could be pinned, and what a line that says nothing still does.
 */
enum class DiagramSide(val key: String) {
    TOP("top"),
    RIGHT("right"),
    BOTTOM("bottom"),
    LEFT("left"),
    ;

    companion object {
        fun byKey(key: String?): DiagramSide? = entries.firstOrNull { it.key == key?.trim()?.lowercase() }
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

class DiagramDescriptionTooLongException(val length: Int, val most: Int) :
    RuntimeException("A diagram's description may be $most characters at most, and this one is $length"), Refusal {
    override val arguments get() = mapOf("length" to length, "most" to most)
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
