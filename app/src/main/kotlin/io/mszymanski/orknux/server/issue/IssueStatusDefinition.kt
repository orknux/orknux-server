package io.mszymanski.orknux.server.issue

import io.mszymanski.orknux.server.graphql.Refusal
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

/**
 * The four keys every workspace starts with, and the shape of any it adds.
 *
 * Constants rather than an enum, because the enum is exactly what #428 took
 * away: a workspace decides its own statuses now, and code that special-cases
 * one of them - the task starter that picks an issue up, the mail that says
 * "started" - is special-casing a *key* the workspace may or may not still
 * have. Read through [IssueStatusCatalogue], which says what a key means here.
 */
object IssueStatuses {
    const val OPEN = "OPEN"
    const val IN_PROGRESS = "IN_PROGRESS"
    const val REVIEW = "REVIEW"
    const val CLOSED = "CLOSED"

    /** As long as `workspace_issue_status.key` is. */
    const val KEY_LENGTH = 32

    /** As long as a label is, which is as long as a type's name: they are read side by side. */
    const val LABEL_LENGTH = 60

    /** Long enough for `rgb(255, 255, 255)` and any hex; too short for a stylesheet. */
    const val COLOR_LENGTH = 32

    /**
     * What a key looks like: `REVIEW`, `WONTFIX`, `NEEDS_INFO`.
     *
     * Upper snake case, because a key is what an agent writes on a tool call and
     * what an address carries - a spelling with one form has one form to
     * remember. The label is where the words go.
     */
    val KEY = Regex("[A-Z][A-Z0-9_]*")

    /** What a colour may be spelled with: a hex, an `rgb()`, a `var()`. Never a semicolon. */
    val COLOR = Regex("[#A-Za-z0-9(),.%\\- ]+")

    /**
     * What every workspace begins with, in order.
     *
     * Open is where a new issue lands and Closed is what counts as done; the
     * two between them are the two most trackers grow first. Colours are left
     * null on purpose: the interface knows these four keys and draws them the
     * way it always has, so a workspace that never touches this looks the same
     * as it did before it could.
     */
    fun defaults(workspaceId: Long): List<IssueStatusDefinition> = listOf(
        IssueStatusDefinition(workspaceId = workspaceId, key = OPEN, label = "Open", position = 0, initial = true),
        IssueStatusDefinition(workspaceId = workspaceId, key = IN_PROGRESS, label = "In progress", position = 1),
        IssueStatusDefinition(workspaceId = workspaceId, key = REVIEW, label = "Review", position = 2),
        IssueStatusDefinition(workspaceId = workspaceId, key = CLOSED, label = "Closed", position = 3, closed = true),
    )
}

/**
 * One status a workspace's issues can be in.
 *
 * A row per workspace rather than an enum, for the reason the types are: a
 * tracker that cannot say "won't fix" or "needs information" makes people say it
 * in a label, and a label is a set that cannot be the answer to "where is this".
 *
 * The key is what an issue stores and what a tool call names; the label is what
 * a person reads. Two fields because the two change at different rates - a
 * label is reworded, a key is forever, since two hundred issues hold it.
 *
 * Two flags carry everything the code has to know about a status without
 * knowing which one it is. [initial] is where a new issue lands, and there is
 * exactly one; [closed] is what counts as done, and there may be several, since
 * "fixed" and "won't fix" are both finished and not the same.
 */
@Entity
@Table(name = "workspace_issue_status")
class IssueStatusDefinition(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    /** Upper snake case, unique in the workspace whatever the case it was typed in. */
    @Column(name = "key", nullable = false, length = IssueStatuses.KEY_LENGTH)
    val key: String,

    @Column(nullable = false, length = IssueStatuses.LABEL_LENGTH)
    var label: String,

    /** A CSS colour for the dot and the badge, or null for the interface's own choice. */
    @Column(length = IssueStatuses.COLOR_LENGTH)
    var color: String? = null,

    /** Where it sits in the list, the filter bar and the cycle the status button walks. */
    @Column(nullable = false)
    var position: Int = 0,

    /** Where a new issue lands. Exactly one per workspace. */
    @Column(nullable = false)
    var initial: Boolean = false,

    /** Terminal: an issue here is done, for the list, the mail and the task starter alike. */
    @Column(nullable = false)
    var closed: Boolean = false,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
)

/** One status and how many issues in the workspace hold it. */
data class IssueStatusCount(val status: String, val issues: Long)

interface IssueStatusRepository : JpaRepository<IssueStatusDefinition, Long> {

    /** A workspace's statuses in the order they are shown; the id breaks a tie. */
    fun findByWorkspaceIdOrderByPositionAscIdAsc(workspaceId: Long): List<IssueStatusDefinition>

    /** The one called this, whatever case it was typed in. */
    @Query("select s from IssueStatusDefinition s where s.workspaceId = :workspaceId and upper(s.key) = upper(:key)")
    fun named(workspaceId: Long, key: String): IssueStatusDefinition?
}

/**
 * What a workspace's statuses are and what each of them means.
 *
 * Every question the code used to put to the enum is put here instead: which
 * key a caller meant, whether it counts as closed, what word the audit uses for
 * a move into it. One place, so the tools, the page, the task starter and the
 * mail cannot disagree about a status the way they once disagreed about
 * "reopened".
 *
 * **A workspace with no rows reads as having the four defaults.** The migration
 * seeds every workspace that existed and `createWorkspace` seeds each one made
 * since, so in the product this never happens - but a workspace written straight
 * into the table has no statuses, and refusing every status change in it would
 * be a rule about seeding masquerading as a rule about issues. Reading answers
 * with the defaults unsaved; the first write through [ensure] saves them.
 */
@Service
class IssueStatusCatalogue(private val statuses: IssueStatusRepository) {

    /** The workspace's statuses in order, or the defaults where none have been written. */
    fun of(workspaceId: Long): List<IssueStatusDefinition> =
        statuses.findByWorkspaceIdOrderByPositionAscIdAsc(workspaceId).ifEmpty { IssueStatuses.defaults(workspaceId) }

    /**
     * The same, written down if they were not.
     *
     * Only from a transaction that may write: a read-only one would be handed
     * the defaults with no ids, which is exactly the case [of] exists for.
     */
    @Transactional
    fun ensure(workspaceId: Long): List<IssueStatusDefinition> {
        val held = statuses.findByWorkspaceIdOrderByPositionAscIdAsc(workspaceId)
        if (held.isNotEmpty()) return held
        return statuses.saveAll(IssueStatuses.defaults(workspaceId))
    }

    /**
     * The status a caller named, however they spelled its case, or a refusal
     * that lists what they could have named.
     *
     * The list is in the sentence because whoever reads it typed a word and
     * can type another - an agent most of all, which has no settings page to
     * look at and no other way to learn what this workspace calls done.
     */
    fun match(workspaceId: Long, asked: String): IssueStatusDefinition {
        val held = of(workspaceId)
        return held.firstOrNull { it.key.equals(asked.trim(), ignoreCase = true) }
            ?: throw IssueStatusUnknownException(asked.trim(), held.map { it.key })
    }

    /** [match], for a write: the defaults are saved first if nothing was. */
    fun resolve(workspaceId: Long, asked: String): IssueStatusDefinition {
        ensure(workspaceId)
        return match(workspaceId, asked)
    }

    /** Where a new issue lands here. */
    fun initialOf(workspaceId: Long): String =
        ensure(workspaceId).first { it.initial }.key

    fun isClosed(workspaceId: Long, key: String): Boolean =
        of(workspaceId).firstOrNull { it.key == key }?.closed == true

    /** What a key is called here, or the key itself where nothing is called that any more. */
    fun labelOf(workspaceId: Long, key: String): String =
        of(workspaceId).firstOrNull { it.key == key }?.label ?: key

    /**
     * What an audit line calls a move to a status.
     *
     * Decided once, for both doors, because both doors had it wrong when each
     * decided for itself: `if (wanted == CLOSED) "closed" else "reopened"` is a
     * question with two answers put to something with four, and every issue
     * anybody picked up was recorded as reopened.
     *
     * Read off the flags and not the keys, so a workspace's own "Won't fix" is
     * "closed" in the audit the way Closed is. Reopening is the one that needs
     * to know where it came from: only a closed issue can be reopened, and one
     * moved back to open from in progress was put down, which is a different
     * thing to have happened. Picked up keeps its word because it is the one
     * move the product makes on a person's behalf, and "Issue #4 picked up" is
     * what the task starter has always written.
     */
    fun auditedAs(workspaceId: Long, became: String, was: String): String {
        val held = of(workspaceId)
        val to = held.firstOrNull { it.key == became }
        return when {
            to?.closed == true -> "closed"
            to?.initial == true -> if (held.firstOrNull { it.key == was }?.closed == true) "reopened" else "put back to open"
            became == IssueStatuses.IN_PROGRESS -> "picked up"
            else -> "moved to ${to?.label ?: became}"
        }
    }
}

/**
 * A status a workspace does not have, named as it was asked for and beside
 * the ones it does. Worded for the tool that most often hears it.
 */
class IssueStatusUnknownException(val key: String, val known: List<String>) : RuntimeException(
    "There is no issue status called $key in this workspace; it has: ${known.joinToString(", ")}",
), Refusal {

    override val arguments get() = mapOf("key" to key, "known" to known)
}

class IssueStatusNotFoundException(val id: Long) : RuntimeException("No issue status with id $id"), Refusal {

    override val arguments get() = mapOf("id" to id)
}

class IssueStatusKeyInvalidException(val key: String) : RuntimeException(
    "An issue status key is capital letters, digits and underscores, ${IssueStatuses.KEY_LENGTH} at most, like WONTFIX",
), Refusal {

    override val arguments get() = mapOf("key" to key)
}

class IssueStatusKeyTakenException(val key: String) :
    RuntimeException("This workspace already has an issue status $key"), Refusal {

    override val arguments get() = mapOf("key" to key)
}

class IssueStatusLabelInvalidException :
    RuntimeException("An issue status needs a label, ${IssueStatuses.LABEL_LENGTH} characters at most")

class IssueStatusColorInvalidException(val color: String) : RuntimeException(
    "A status colour is a CSS colour, ${IssueStatuses.COLOR_LENGTH} characters at most, like #8b5cf6",
), Refusal {

    override val arguments get() = mapOf("color" to color)
}

/**
 * A status that issues hold, refused with the count.
 *
 * The number and not the issues, as a type in use is: two hundred issues is a
 * refusal nobody reads if it lists them, and the filter is one click from
 * showing which.
 */
class IssueStatusInUseException(val label: String, val issues: Long) : RuntimeException(
    "$label is on ${if (issues == 1L) "1 issue" else "$issues issues"}, so it cannot be removed. " +
        "Move those issues to another status, then remove it.",
), Refusal {

    override val arguments get() = mapOf("label" to label, "issues" to issues)
}

class IssueStatusInitialException(val label: String) :
    RuntimeException("$label is where a new issue starts, so it cannot be removed"), Refusal {

    override val arguments get() = mapOf("label" to label)
}

class IssueStatusInitialClosedException(val label: String) :
    RuntimeException("$label is where a new issue starts, so it cannot count as closed"), Refusal {

    override val arguments get() = mapOf("label" to label)
}

class IssueStatusLastClosedException(val label: String) :
    RuntimeException("$label is the only status that counts as closed, and a workspace needs one"), Refusal {

    override val arguments get() = mapOf("label" to label)
}

class IssueStatusReorderException :
    RuntimeException("A new order names every one of this workspace's statuses, once each")
