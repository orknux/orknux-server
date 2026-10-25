package io.mszymanski.orknux.server.design

import io.mszymanski.orknux.server.graphql.Refusal
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import java.time.OffsetDateTime

/**
 * A folder of documents in Design.
 *
 * Its own table rather than a label on a document, for the reason a memory
 * catalog is one: the page lists catalogs beside the documents of the one
 * selected, so a catalog exists, and has a count worth showing, before anything
 * has been filed in it. Not shared or lent between workspaces, as memory and
 * skill catalogs are not.
 */
@Entity
@Table(name = "document_catalog")
class DocumentCatalog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Column(nullable = false, length = Documents.CATALOG_NAME_LENGTH)
    var name: String,

    @Column(name = "created_by", nullable = false, length = 255)
    val createdBy: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
)

/**
 * A document: rich text written in the Design perspective, by hand in the
 * editor and by a model through the orknux_* document tools.
 *
 * [content] is the editor's own document - ProseMirror JSON, as TipTap writes
 * it - and is the one source of truth: the Markdown a model reads, the PDF, the
 * ODT and the search all come from it. [plainText] is a copy of its words kept
 * for the search, written by every save and never read back into the content.
 * Pictures are in the artifact store and are named in the content by artifact
 * id; [DesignDocumentImage] lists which, so a question about where a picture is
 * used does not have to read every document.
 *
 * [revision] counts the saves, so a save made against a document that has
 * changed since - by somebody else, or by a model in the chat beside it - is
 * refused instead of written over what changed it.
 */
@Entity
@Table(name = "design_document")
class DesignDocument(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Column(name = "catalog_id", nullable = false)
    var catalogId: Long,

    @Column(nullable = false, length = Documents.NAME_LENGTH)
    var name: String,

    /** `{"type":"doc","content":[...]}`, as [DocumentContent.write] writes it. */
    @Column(nullable = false, columnDefinition = "text")
    var content: String,

    @Column(name = "plain_text", nullable = false, columnDefinition = "text")
    var plainText: String = "",

    /** A [DocumentPageSize]: what the editor draws its pages as, and what the PDF and the ODT are laid out on. */
    @Column(name = "page_size", nullable = false, length = 10)
    var pageSize: String = DocumentPageSize.A4.name,

    /** PORTRAIT or LANDSCAPE. */
    @Column(nullable = false, length = 10)
    var orientation: String = DocumentOrientation.PORTRAIT.name,

    /** A [DocumentMargins]: how much white is left round the text. */
    @Column(nullable = false, length = 10)
    var margins: String = DocumentMargins.NORMAL.name,

    /**
     * What language the document is written in, as the interface names its
     * languages (`en`, `pl`): what its grammar is checked in. Null is whatever
     * the person reading it uses.
     */
    @Column(length = 10)
    var language: String? = null,

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

/**
 * One picture a document shows, by the id the Artifacts page gives it
 * (`DESIGN-12`, `SAVED-4`). Rewritten whole by every save, from the content.
 */
@Entity
@Table(name = "design_document_image")
class DesignDocumentImage(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "document_id", nullable = false)
    val documentId: Long,

    @Column(name = "artifact_id", nullable = false, length = 64)
    val artifactId: String,
)

interface DocumentCatalogRepository : JpaRepository<DocumentCatalog, Long> {

    fun findByWorkspaceIdOrderByNameAsc(workspaceId: Long): List<DocumentCatalog>

    fun findByWorkspaceIdAndName(workspaceId: Long, name: String): DocumentCatalog?
}

interface DesignDocumentRepository : JpaRepository<DesignDocument, Long>, JpaSpecificationExecutor<DesignDocument> {

    /** A workspace's documents, the one worked on last first. */
    fun findByWorkspaceIdOrderByUpdatedAtDescIdDesc(workspaceId: Long): List<DesignDocument>

    fun countByCatalogId(catalogId: Long): Long
}

interface DesignDocumentImageRepository : JpaRepository<DesignDocumentImage, Long> {

    fun findByDocumentId(documentId: Long): List<DesignDocumentImage>

    fun deleteByDocumentId(documentId: Long)
}

/** What a document's pages are, as the editor draws them and an export lays them out. */
enum class DocumentPageSize(
    /** The page, in millimetres. */
    val widthMm: Double,
    val heightMm: Double,
    /** How CSS `@page` names it. */
    val css: String,
) {
    A3(297.0, 420.0, "A3"),
    A4(210.0, 297.0, "A4"),
    A5(148.0, 210.0, "A5"),
    LETTER(215.9, 279.4, "letter"),
    LEGAL(215.9, 355.6, "legal"),
    ;

    companion object {
        fun of(asked: String?): DocumentPageSize? = entries.firstOrNull { it.name.equals(asked?.trim(), ignoreCase = true) }
    }
}

/** Which way up a page is: a landscape page is its size turned on its side. */
enum class DocumentOrientation {
    PORTRAIT,
    LANDSCAPE,
    ;

    companion object {
        fun of(asked: String?): DocumentOrientation? = entries.firstOrNull { it.name.equals(asked?.trim(), ignoreCase = true) }
    }
}

/** The white round the text, all four sides alike, in millimetres - the word processor's three. */
enum class DocumentMargins(val mm: Double) {
    NORMAL(25.0),
    NARROW(12.7),
    WIDE(38.1),
    ;

    companion object {
        fun of(asked: String?): DocumentMargins? = entries.firstOrNull { it.name.equals(asked?.trim(), ignoreCase = true) }
    }
}

/** A document's page as laid out: its size turned the way it is, and the margins round it. */
data class DocumentPage(val widthMm: Double, val heightMm: Double, val marginMm: Double) {
    companion object {
        fun of(held: DesignDocument): DocumentPage {
            val size = DocumentPageSize.of(held.pageSize) ?: DocumentPageSize.A4
            val landscape = DocumentOrientation.of(held.orientation) == DocumentOrientation.LANDSCAPE
            val margins = DocumentMargins.of(held.margins) ?: DocumentMargins.NORMAL
            return if (landscape) {
                DocumentPage(size.heightMm, size.widthMm, margins.mm)
            } else {
                DocumentPage(size.widthMm, size.heightMm, margins.mm)
            }
        }
    }
}

/** The languages a document can be written in: the ones the interface is read in. */
object DocumentLanguages {
    val ALL = listOf("en", "pl")

    fun of(asked: String?): String? = asked?.trim()?.lowercase()?.substringBefore('-')?.takeIf { it in ALL }
}

/** The formats a document is held to; how long one may be is a Tunable. */
object Documents {
    const val NAME_LENGTH = 200
    const val CATALOG_NAME_LENGTH = 120

    /** How deep blocks may nest - a list in a quote in a table cell - before it is not a document but a puzzle. */
    const val DEPTH = 40

    /** What a workspace's first catalog is called, made the first time a document is filed with none named. */
    const val FIRST_CATALOG = "General"
}

/**
 * The documents a search asks for: by name and by the words in them, in one
 * catalog or all of them. A specification rather than JPQL for the reason
 * `memoryFilter` gives: an absent parameter is left out rather than sent as a
 * null Postgres cannot type.
 */
fun documentFilter(workspaceId: Long, catalogId: Long?, search: String?): Specification<DesignDocument> =
    Specification { root, _, builder ->
        val predicates = mutableListOf(builder.equal(root.get<Long>("workspaceId"), workspaceId))
        catalogId?.let { predicates += builder.equal(root.get<Long>("catalogId"), it) }
        search?.trim()?.takeIf { it.isNotEmpty() }?.let {
            val pattern = "%${it.lowercase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")}%"
            predicates += builder.or(
                builder.like(builder.lower(root.get("name")), pattern, '\\'),
                builder.like(builder.lower(root.get("plainText")), pattern, '\\'),
            )
        }
        builder.and(*predicates.toTypedArray())
    }

class DocumentNotFoundException(val id: Long) : RuntimeException("No document with id $id"), Refusal {
    override val arguments get() = mapOf("id" to id)
}

class DocumentNameInvalidException :
    RuntimeException("A document needs a name, ${Documents.NAME_LENGTH} characters at most")

class DocumentPageSizeInvalidException(val asked: String) :
    RuntimeException("A page is A3, A4, A5, LETTER or LEGAL, not $asked"), Refusal {
    override val arguments get() = mapOf("asked" to asked)
}

/** An orientation, margins or language a document cannot have, named with what it can. */
class DocumentSettingInvalidException(val setting: String, val asked: String, val allowed: String) :
    RuntimeException("A document's $setting is $allowed, not $asked"), Refusal {
    override val arguments get() = mapOf("setting" to setting, "asked" to asked, "allowed" to allowed)
}

class DocumentContentInvalidException(val why: String) :
    RuntimeException("The document cannot be saved: $why"), Refusal {
    override val arguments get() = mapOf("why" to why)
}

class DocumentTooLargeException(val characters: Int, val most: Int) :
    RuntimeException("A document holds at most $most characters, and this one would hold $characters"), Refusal {
    override val arguments get() = mapOf("characters" to characters, "most" to most)
}

class DocumentChangedException(val name: String) :
    RuntimeException("Document $name was changed since it was opened; open it again before saving"), Refusal {
    override val arguments get() = mapOf("name" to name)
}

class DocumentAnswerFailedException(val reason: String) :
    RuntimeException("The model could not answer: $reason"), Refusal {
    override val arguments get() = mapOf("reason" to reason)
}

class DocumentCatalogNotFoundException(val id: Long) : RuntimeException("No document catalog with id $id"), Refusal {
    override val arguments get() = mapOf("id" to id)
}

class DocumentCatalogNameInvalidException :
    RuntimeException("A document catalog needs a name, ${Documents.CATALOG_NAME_LENGTH} characters at most")

class DocumentCatalogNameTakenException(val name: String) :
    RuntimeException("This workspace already has a document catalog called $name"), Refusal {
    override val arguments get() = mapOf("name" to name)
}

/**
 * A catalog with documents in it, asked to be deleted. Refused rather than
 * taking the documents with it: a catalog is a folder, and emptying a folder
 * by deleting it is how somebody loses work they meant to keep.
 */
class DocumentCatalogNotEmptyException(val name: String, val count: Long) :
    RuntimeException("$name holds $count documents; move or delete them before deleting it"), Refusal {
    override val arguments get() = mapOf("name" to name, "count" to count)
}
