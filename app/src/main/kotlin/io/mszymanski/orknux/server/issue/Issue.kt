package io.mszymanski.orknux.server.issue

import io.mszymanski.orknux.server.graphql.Refusal
import jakarta.persistence.CascadeType
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import jakarta.persistence.criteria.CriteriaBuilder
import jakarta.persistence.criteria.CriteriaQuery
import jakarta.persistence.criteria.Expression
import jakarta.persistence.criteria.Predicate
import jakarta.persistence.criteria.Root
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.domain.Specification
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Query
import java.time.OffsetDateTime

/**
 * What kind of thing an issue is assigned to.
 *
 * A person is the obvious one and not the only one: this is a product where
 * the thing doing the work is often an agent, and "assigned to the responder
 * agent" is a sentence a workspace wants to write.
 */
enum class AssigneeKind {
    USER,
    AGENT,
    MODEL,
}

/**
 * Who is looking at an issue.
 *
 * A kind and an id rather than three nullable columns: what it points at
 * differs, that it points at exactly one thing does not. Resolved to a name
 * when the issue is read, so a renamed agent reads correctly afterwards.
 */
@jakarta.persistence.Embeddable
class Assignee(
    @Enumerated(EnumType.STRING)
    @Column(name = "assignee_kind", length = 16)
    var kind: AssigneeKind? = null,

    @Column(name = "assignee_id", length = 120)
    var id: String? = null,
)

@Entity
@Table(name = "workspace_issue")
class Issue(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    /**
     * Which workspace holds it - not fixed, though it very nearly is.
     *
     * An administrator can move an issue that was filed in the wrong place, and
     * doing that changes both this and the number together: see [IssueMoveAPI]
     * for what travels with it and what the move refuses to do.
     */
    @Column(name = "workspace_id", nullable = false)
    var workspaceId: Long,

    /** Its number in this workspace: what "#3" means, and what people say. */
    @Column(nullable = false)
    var number: Int,

    @Column(nullable = false, length = 200)
    var title: String,

    @Column(columnDefinition = "text")
    var description: String? = null,

    /**
     * Where it is in its life, as the key of one of the workspace's statuses.
     *
     * A key and not a row, and not an enum either - which it was until #428.
     * The workspace decides its statuses now ([IssueStatusDefinition]), so the
     * set is a table and not a type; and the issue holds the key rather than the
     * row's id so that the history, the news and an agent's tool call all read
     * one spelling, and so that the column is exactly what it was before with
     * the enum's four names still in it. Every write goes through
     * [IssueStatusCatalogue], which is what says the key is one of this
     * workspace's.
     */
    @Column(nullable = false, length = IssueStatuses.KEY_LENGTH)
    var status: String = IssueStatuses.OPEN,

    /**
     * What kind of thing it is, or null for untyped.
     *
     * Null is a real state and not a gap. Every issue filed before there were
     * types has none, and giving them a default would be the record claiming
     * that a year of work was all bugs - so the list can be asked for the
     * untyped ones and the page says the word rather than showing a blank.
     *
     * Eagerly, unlike the comments. A to-one to a table holding a handful of
     * rows per workspace costs a lookup the session then has cached, and every
     * issue read outside a transaction - the news desk's, the mail's - would
     * otherwise be a page that renders or throws depending on who asked for it.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "type_id")
    var type: IssueType? = null,

    /** The username that filed it, kept as a name so it survives them. */
    @Column(nullable = false, length = 120)
    val reporter: String,

    /*
     * Null when nobody is looking at it - which is Hibernate's own answer:
     * an embeddable whose every column is null comes back as null, so the
     * field says so rather than being surprised by it.
     */
    @jakarta.persistence.Embedded
    var assignee: Assignee? = null,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "workspace_issue_label", joinColumns = [JoinColumn(name = "issue_id")])
    @Column(name = "label", nullable = false, length = 60)
    var labels: MutableSet<String> = mutableSetOf(),

    /*
     * Owned by the issue, and the column says so.
     *
     * `nullable = false` is what makes Hibernate write the issue's id in the
     * insert rather than inserting the comment with a null and updating it a
     * moment later - which the not-null column refuses, as it should.
     */
    @OneToMany(cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "issue_id", nullable = false)
    @OrderBy("createdAt asc")
    var comments: MutableList<IssueComment> = mutableListOf(),

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_modified_at", nullable = false)
    var lastModifiedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "last_modified_by", nullable = false, length = 120)
    var lastModifiedBy: String = "system",

    /**
     * When somebody last said something here, or null if nobody has.
     *
     * Not the same as [lastModifiedAt], which closing, relabelling or assigning
     * all move - so a list sorted by that puts the housekeeping at the top.
     * Somebody scanning for where the talking is wants this one.
     */
    @Column(name = "last_comment_at")
    var lastCommentAt: OffsetDateTime? = null,
)

@Entity
@Table(name = "workspace_issue_comment")
class IssueComment(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, length = 120)
    val author: String,

    @Column(nullable = false, columnDefinition = "text")
    var content: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    /** When it was last changed, or null if it never was. */
    @Column(name = "edited_at")
    var editedAt: OffsetDateTime? = null,
)

/**
 * One label and how many issues in the workspace carry it.
 *
 * Counted by the database rather than by tallying a page of entities read back:
 * a page has a size, and a tally of one page is a count of that page and not of
 * the tracker. See [IssueRepository.labelCounts].
 */
data class LabelCount(val label: String, val issues: Long)

interface IssueRepository : JpaRepository<Issue, Long>, JpaSpecificationExecutor<Issue> {

    fun findByWorkspaceIdAndNumber(workspaceId: Long, number: Int): Issue?

    /**
     * The issue a comment is on. A comment knows its issue only through the
     * join column, so this is the one way from a comment id to its thread that
     * does not read every issue and every comment to find it. Issue #616.
     */
    @Query("select i from Issue i join i.comments c where c.id = :commentId")
    fun findByCommentId(commentId: Long): Issue?

    /** The highest number used here, so the next one follows it. */
    @Query("select coalesce(max(i.number), 0) from Issue i where i.workspaceId = :workspaceId")
    fun lastNumber(workspaceId: Long): Int

    /**
     * The list, filtered the way the page asks.
     *
     * One search reads the title, the description and the labels together -
     * somebody typing "slack" means any of the three, and asking them which
     * field they meant is asking them to know the schema. An empty search
     * matches everything, which is what makes this one query rather than two.
     *
     * The labels are asked about with `exists` rather than joined. A join
     * multiplies an issue by its labels and needs `distinct` to undo it, and
     * Postgres will not order a distinct select by an expression that is not in
     * the select list - so `order by lower(title)` failed outright the moment
     * sorting by name was offered.
     *
     * The search is never null. Postgres cannot type a null parameter inside
     * `lower()` - it guesses bytea and refuses the function - so "no filter" is
     * the empty string rather than a null.
     *
     * Only the text, and no status: this is what the box offering something to
     * link to reads, and that box wants every issue. The list's own filters are
     * [issueFilter]'s, since a JPQL method per combination of three optional
     * filters is eight methods and the eighth is the one nobody writes.
     */
    @Query(
        """
        select i from Issue i
        where i.workspaceId = :workspaceId
          and (
            :search = ''
            or lower(i.title) like lower(concat('%', :search, '%'))
            or lower(coalesce(i.description, '')) like lower(concat('%', :search, '%'))
            or exists (
              select 1 from Issue held join held.labels l
              where held = i and lower(l) like lower(concat('%', :search, '%'))
            )
          )
        """,
        countQuery = """
        select count(i) from Issue i
        where i.workspaceId = :workspaceId
          and (
            :search = ''
            or lower(i.title) like lower(concat('%', :search, '%'))
            or lower(coalesce(i.description, '')) like lower(concat('%', :search, '%'))
            or exists (
              select 1 from Issue held join held.labels l
              where held = i and lower(l) like lower(concat('%', :search, '%'))
            )
          )
        """,
    )
    fun search(workspaceId: Long, search: String, pageable: Pageable): Page<Issue>


    /** Every label in use here, for the filter to offer. */
    @Query("select distinct l from Issue i join i.labels l where i.workspaceId = :workspaceId order by l")
    fun labelsIn(workspaceId: Long): List<String>

    /**
     * Every label in use here with how many issues carry it, counted by the
     * database.
     *
     * The count has to be the query's, because the alternative - read a page of
     * issues and tally their labels - counts the page. That is what it did, with
     * a page of 200 against a tracker of 221, and every number it reported was
     * short by however many issues fell off the end. A `group by` has no page.
     *
     * Commonest first and alphabetically within a tie, which is the order the
     * answer is read in: what a workspace uses most is what a filter is most
     * likely to want.
     */
    @Query(
        """
        select new io.mszymanski.orknux.server.issue.LabelCount(l, count(i))
        from Issue i join i.labels l
        where i.workspaceId = :workspaceId
        group by l
        order by count(i) desc, l asc
        """,
    )
    fun labelCounts(workspaceId: Long): List<LabelCount>

    /**
     * How many issues carry each type here, counted by the database.
     *
     * Untyped is not a row in this: the types are what the catalogue holds, and
     * a count of what is on none of them belongs to the list rather than to the
     * settings page.
     */
    @Query(
        """
        select new io.mszymanski.orknux.server.issue.IssueTypeCount(t.id, count(i))
        from Issue i join i.type t
        where i.workspaceId = :workspaceId
        group by t.id
        """,
    )
    fun typeCounts(workspaceId: Long): List<IssueTypeCount>

    /** What deleting a type has to ask, and what the refusal reports. */
    fun countByTypeId(typeId: Long): Long

    /**
     * How many issues here hold each status, counted by the database.
     *
     * The settings page's numbers, and what removing a status is refused with.
     * Keyed by the status's key rather than joined to its row, because the issue
     * holds the key: a status nothing is called any more still counts here,
     * which is exactly the row an administrator would want to know about.
     */
    @Query(
        """
        select new io.mszymanski.orknux.server.issue.IssueStatusCount(i.status, count(i))
        from Issue i
        where i.workspaceId = :workspaceId
        group by i.status
        """,
    )
    fun statusCounts(workspaceId: Long): List<IssueStatusCount>

    /** What removing a status has to ask, and what the refusal reports. */
    fun countByWorkspaceIdAndStatus(workspaceId: Long, status: String): Long
}

/**
 * What a caller wants of the type: one of them, none of them, or never mind.
 *
 * Spelled out rather than left to a nullable id, because untyped is a state
 * somebody filters for and a null already means "no filter". Two meanings on
 * one absent value is how a filter ends up unable to ask the question the
 * tracker is most often asked - what has nobody classified yet.
 */
sealed interface IssueTypeWanted

/** No filter: every issue, typed or not. */
data object AnyType : IssueTypeWanted

/** Only the issues nobody has said what they are. */
data object Untyped : IssueTypeWanted

/** Only the issues carrying this one. */
data class OfType(val id: Long) : IssueTypeWanted

/**
 * The tracker filtered the way somebody asks for it, as one query.
 *
 * A specification rather than JPQL for the reason the audit log's is one:
 * `:enum IS NULL OR …` fails in Hibernate 6, and every one of these is
 * optional. `WorkspaceAuditRepository.auditFilter` is the shape.
 *
 * The point of it being a query at all is that a filter applied after a page
 * has been fetched filters the page. Asking for everything labelled `p1` in a
 * tracker of 221 got the p1s among the newest 200, handed back as though it
 * were all of them.
 */
fun issueFilter(
    workspaceId: Long,
    /** One of the workspace's status keys, as [IssueStatusCatalogue.match] spells it; null is every status. */
    status: String?,
    /** Read across the title, the description and the labels together. */
    search: String?,
    /**
     * Every one of them, not any: "p1, slack" asks for the urgent Slack issues,
     * not for everything urgent and everything about Slack. One `exists` each,
     * rather than a join, because a join multiplies an issue by its labels and
     * then needs a `distinct` that Postgres will not order by an expression
     * outside the select list.
     */
    labels: Collection<String> = emptyList(),
    /**
     * Who has it, as the kind and id an assignee is stored as - resolved from a
     * name by the caller, because a name lives in the users, the agents or the
     * models and not in this table. Null means anybody; an empty collection
     * means the name matched nobody, which matches no issue rather than every
     * issue.
     */
    assignedTo: Collection<Pair<AssigneeKind, String>>? = null,
    /**
     * Which type, or [Untyped], or [AnyType] for no filter at all.
     *
     * Three states rather than a nullable id, because untyped is a real state
     * somebody asks for: a nullable parameter can only say "this one" and
     * "never mind", and the question a tracker actually gets is "what has
     * nobody classified yet".
     */
    type: IssueTypeWanted = AnyType,
): Specification<Issue> = Specification { root, query, builder ->
    val predicates = mutableListOf(builder.equal(root.get<Long>("workspaceId"), workspaceId))

    status?.let { predicates += builder.equal(root.get<String>("status"), it) }

    when (type) {
        AnyType -> Unit
        Untyped -> predicates += builder.isNull(root.get<IssueType>("type"))
        is OfType -> predicates += builder.equal(root.get<IssueType>("type").get<Long>("id"), type.id)
    }

    search?.trim()?.ifEmpty { null }?.let {
        val pattern = "%${it.lowercase()}%"
        predicates += builder.or(
            builder.like(builder.lower(root.get("title")), pattern),
            builder.like(builder.lower(builder.coalesce(root.get<String>("description"), "")), pattern),
            carriesLabel(root, query, builder) { label -> builder.like(label, pattern) },
        )
    }

    labels.forEach { wanted ->
        val spelled = wanted.lowercase()
        predicates += carriesLabel(root, query, builder) { label -> builder.equal(label, spelled) }
    }

    assignedTo?.let { held ->
        predicates += if (held.isEmpty()) {
            builder.disjunction()
        } else {
            val assignee = root.get<Any>("assignee")
            builder.or(
                *held.map { (kind, id) ->
                    builder.and(
                        builder.equal(assignee.get<AssigneeKind>("kind"), kind),
                        builder.equal(assignee.get<String>("id"), id),
                    )
                }.toTypedArray(),
            )
        }
    }

    builder.and(*predicates.toTypedArray())
}

/** `exists (select 1 from the issue's labels where …)`, spelled once. */
private fun carriesLabel(
    root: Root<Issue>,
    query: CriteriaQuery<*>,
    builder: CriteriaBuilder,
    matching: (Expression<String>) -> Predicate,
): Predicate {
    val sub = query.subquery(Int::class.java)
    val held = sub.from(Issue::class.java)
    val label = held.join<Issue, String>("labels")
    sub.select(builder.literal(1))
    sub.where(builder.equal(held, root), matching(builder.lower(label)))
    return builder.exists(sub)
}

class IssueNotFoundException(val id: Long) : RuntimeException("No issue with id $id"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

class IssueTitleInvalidException : RuntimeException("An issue needs a title")

class IssueCommentEmptyException : RuntimeException("A comment needs something in it")

class IssueCommentNotFoundException(val id: Long) : RuntimeException("No comment with id $id"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

/**
 * Somebody tried to edit a comment that is not theirs.
 *
 * Said plainly because it is not a permission that can be granted: what
 * somebody else wrote is what they wrote, and an issue whose history could be
 * rewritten by anybody reading it is not a record of anything.
 */
class IssueCommentNotYoursException :
    RuntimeException("A comment can only be edited by whoever wrote it")

/**
 * Somebody tried to remove a comment that is neither theirs nor their
 * workspace's to remove.
 *
 * A wider rule than [IssueCommentNotYoursException], deliberately, and the
 * difference between the two is the difference between rewriting a record and
 * subtracting from one. An edit can put words in somebody's mouth and leaves
 * nothing behind saying whose they were; a removal can only take something
 * away, and the history says it happened, who wrote it and who took it - so
 * there is nothing an administrator could do here that the thread would not
 * report.
 *
 * The case it exists for is the one that makes this feature urgent at all: a
 * comment carrying a credential has to be removable by whoever notices, and
 * whoever notices is very often not whoever pasted it - who may be an agent, or
 * gone. The only alternative already in the product is deleting the issue,
 * which anybody who can see the workspace may do and which takes the whole
 * thread with it. A narrower power is the safer one to hand out.
 */
class IssueCommentNotYoursToRemoveException :
    RuntimeException("A comment can only be removed by whoever wrote it, or by an administrator of this workspace")

class IssueAssigneeInvalidException(val what: String) :
    RuntimeException("$what is not something in this workspace to assign an issue to"), Refusal {

    override val arguments get() = mapOf("what" to what)
}

class IssueAssigneeKindMissingException(val id: String) :
    RuntimeException("An assignee is a kind and an id together; $id arrived without a kind"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

