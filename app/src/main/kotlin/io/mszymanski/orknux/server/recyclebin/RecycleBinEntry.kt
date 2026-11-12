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
 * The six that keep their whole state in one snapshot and carry no file of their
 * own (`ComponentSnapshot` writes and reads every one of them), which is what
 * makes a delete reversible by writing the object down and re-creating it. A
 * seventh kind is a seventh entry here and a seventh branch where a delete is
 * turned into a [RecycleBin.bin] call and where a restore re-creates it; nothing
 * else changes.
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
    ;

    /** How a line names it: "Agent", "Tool", … - the enum word, capitalised. */
    val label: String get() = name.lowercase().replaceFirstChar { it.uppercase() }
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

    /** A workspace's bin, newest first, which is the only way the page reads it. */
    fun findByWorkspaceIdOrderByDeletedAtDescIdDesc(workspaceId: Long, pageable: Pageable): Page<RecycleBinEntry>

    /** What the retention sweep takes: everything deleted before a date, across every workspace. */
    @Query("select e.id from RecycleBinEntry e where e.deletedAt < :before")
    fun idsDeletedBefore(@Param("before") before: OffsetDateTime): List<Long>
}

class RecycleBinEntryNotFoundException(val id: Long) :
    RuntimeException("No recycle bin entry with id $id"), Refusal {

    override val arguments get() = mapOf("id" to id)
}
