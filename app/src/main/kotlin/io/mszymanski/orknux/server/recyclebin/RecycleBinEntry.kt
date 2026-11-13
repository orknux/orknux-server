package io.mszymanski.orknux.server.recyclebin

import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime

/**
 * A kind of object the recycle bin holds and can put back.
 *
 * A delete is made reversible by writing the whole object down - into one
 * snapshot, which for most kinds keeps every part of it inline (`ComponentSnapshot`
 * or the object's own door writes and reads it) - and re-creating it on restore.
 * A new kind is a new entry here, a [RecycleBin.bin] overload the delete calls,
 * and a new branch where a restore re-creates it; nothing else changes.
 *
 * [auditCategory] is where the log line goes - the same screen the object lives
 * on, so "Agent Foo restored" is filed where "Agent Foo deleted" was.
 */
enum class RecycleBinKind(val auditCategory: WorkspaceAuditCategory) {
    AGENT(WorkspaceAuditCategory.AGENT),
    TOOL(WorkspaceAuditCategory.AGENT),
    SKILL(WorkspaceAuditCategory.AGENT),
    FUNCTION(WorkspaceAuditCategory.WORKFLOW),
    DOCUMENT(WorkspaceAuditCategory.WORKSPACE),
    DIAGRAM(WorkspaceAuditCategory.WORKSPACE),
    SHEET(WorkspaceAuditCategory.WORKSPACE),
    ACTION(WorkspaceAuditCategory.WORKFLOW),
    WIREFRAME(WorkspaceAuditCategory.WORKSPACE),
    MEMORY(WorkspaceAuditCategory.MEMORY),
    IMAGE(WorkspaceAuditCategory.WORKFLOW),
    ISSUE(WorkspaceAuditCategory.WORKSPACE),
    WORKFLOW(WorkspaceAuditCategory.WORKFLOW),

    /*
     * The rest of the catalogue an action sits in. Each is written and put back
     * by its own door, and each may name something else in the workspace - a
     * condition its function, a trigger its connection, a variable its catalog -
     * so its snapshot keeps that as an id and a name; see [BinReference].
     */
    CONDITION(WorkspaceAuditCategory.WORKFLOW),
    TRIGGER(WorkspaceAuditCategory.WORKFLOW),
    OBJECT(WorkspaceAuditCategory.OBJECT),
    VARIABLE(WorkspaceAuditCategory.WORKFLOW),

    /**
     * A folder, with what it held when it was deleted - one entry, so the
     * folder comes back as a folder, its memories or skills in it, rather than
     * as its contents scattered into the default one.
     */
    MEMORY_CATALOG(WorkspaceAuditCategory.MEMORY),
    SKILL_CATALOG(WorkspaceAuditCategory.AGENT),
    ;

    /** How a line names it: "Agent", "Tool", "Memory catalog", … - the enum's words, the first capitalised. */
    val label: String get() = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/**
 * An object that was deleted, kept whole so it can be put back.
 *
 * The dual of [io.mszymanski.orknux.server.revision.ComponentRevision]: that
 * keeps what a *save* displaced, this keeps what a *delete* removed. The
 * [snapshot] is the same `ComponentSnapshot` JSON a version is kept in, so the
 * six kinds that already know how to write and read themselves need no second
 * format - the delete writes it, and the restore re-creates the object from it.
 *
 * [originalId] is the row id the object had, for the record and the log; a
 * restore re-creates the object with a new id, because nothing referenced the
 * old one - a delete is refused while anything does. [workspaceId] is a real
 * foreign key, and the only one that can be: the object lived in one of six
 * tables and is gone from it, so [originalId] can point at none of them. A
 * workspace deleted takes its bin with it through that key.
 */
@Entity
@Table(name = "recycle_bin")
class RecycleBinEntry(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val kind: RecycleBinKind,

    @Column(name = "original_id", nullable = false)
    val originalId: Long,

    /** The folder a document, diagram or skill was in, so a restore re-files it; null for the kinds with none. */
    @Column(name = "original_catalog_id")
    val originalCatalogId: Long? = null,

    @Column(nullable = false, length = 120)
    val name: String,

    @Column(name = "deleted_at", nullable = false)
    val deletedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "deleted_by", nullable = false, length = 120)
    val deletedBy: String,

    @Column(nullable = false, columnDefinition = "text")
    val snapshot: String,
)

interface RecycleBinRepository : JpaRepository<RecycleBinEntry, Long> {

    /** A workspace's bin, newest first, whole - snapshots and all. Only what empties a bin reads it this way. */
    fun findByWorkspaceIdOrderByDeletedAtDescIdDesc(workspaceId: Long, pageable: Pageable): Page<RecycleBinEntry>

    /**
     * A workspace's bin, newest first, as the page lists it: every column but
     * the snapshot. A deleted picture or an issue with screenshots carries its
     * bytes in there, and a list that read twenty of those to print twenty
     * names would read megabytes to show a line each.
     */
    @Query(
        "select new io.mszymanski.orknux.server.recyclebin.RecycleBinLine(e.id, e.kind, e.name, e.deletedAt, e.deletedBy) " +
            "from RecycleBinEntry e where e.workspaceId = :workspaceId order by e.deletedAt desc, e.id desc",
        countQuery = "select count(e) from RecycleBinEntry e where e.workspaceId = :workspaceId",
    )
    fun lines(@Param("workspaceId") workspaceId: Long, pageable: Pageable): Page<RecycleBinLine>

    /** The workflow definitions a workspace's bin holds, which go for good when the bin is emptied. */
    @Query("select e.originalId from RecycleBinEntry e where e.workspaceId = :workspaceId and e.kind = :kind")
    fun originalsIn(@Param("workspaceId") workspaceId: Long, @Param("kind") kind: RecycleBinKind): List<Long>

    /** The same, for everything the retention sweep is about to take. */
    @Query("select e.originalId from RecycleBinEntry e where e.deletedAt < :before and e.kind = :kind")
    fun originalsDeletedBefore(@Param("before") before: OffsetDateTime, @Param("kind") kind: RecycleBinKind): List<Long>

    /** Whether any bin still holds a workflow definition, before it is deleted for good. */
    fun existsByKindAndOriginalId(kind: RecycleBinKind, originalId: Long): Boolean

    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RecycleBinEntry e where e.workspaceId = :workspaceId")
    fun deleteByWorkspace(@Param("workspaceId") workspaceId: Long): Int

    /** What the retention sweep takes: everything deleted before a date, across every workspace. */
    @Query("select e.id from RecycleBinEntry e where e.deletedAt < :before")
    fun idsDeletedBefore(@Param("before") before: OffsetDateTime): List<Long>
}

/** One line of a bin, without what the object held. */
data class RecycleBinLine(
    val id: Long,
    val kind: RecycleBinKind,
    val name: String,
    val deletedAt: OffsetDateTime,
    val deletedBy: String,
)

/**
 * A binned workflow whose definition is no longer there to put back - which
 * takes something outside the bin having removed it, since the bin itself keeps
 * a definition for as long as it holds the entry.
 */
class RecycleBinWorkflowGoneException(val name: String) :
    RuntimeException("The workflow $name can no longer be restored: its definition is gone"), Refusal {

    override val arguments get() = mapOf("name" to name)
}

/**
 * A binned object that names something the workspace no longer has - the
 * function a condition calls, the connection a trigger listens on.
 *
 * Refused rather than put back without it. A delete is refused while anything
 * uses the object, but nothing holds in place what the object itself used, so
 * that may have gone since; and a trigger restored without its condition fires
 * on everything, which is a different trigger from the one that was deleted.
 * [what] and [called] say what to put back first - "the function", "isVip" - and
 * a restore finds it by that name, so making a new one called that does as well
 * as restoring the old; which matters for a connection, which the bin does not
 * keep.
 */
class RecycleBinReferenceGoneException(val kind: String, val name: String, val what: String, val called: String?) :
    RuntimeException(
        "The $kind $name cannot be restored yet: ${listOfNotNull(what, called).joinToString(" ")}, which it uses, " +
            "is no longer here. Restore that first, or make one with that name.",
    ),
    Refusal {

    /**
     * `missing` is the English phrase the sentence uses; `missingName` is the
     * name alone, which is the half a translation can put in a sentence of its
     * own without an English noun in the middle of it.
     */
    override val arguments
        get() = mapOf(
            "kind" to kind,
            "name" to name,
            "missing" to listOfNotNull(what, called).joinToString(" "),
            "missingName" to (called ?: what),
        )
}

class RecycleBinEntryNotFoundException(val id: Long) :
    RuntimeException("No recycle bin entry with id $id"), Refusal {

    override val arguments get() = mapOf("id" to id)
}
