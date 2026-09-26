package io.mszymanski.orknux.server.issue

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

/**
 * One line in an issue's history.
 *
 * Ten kinds, and exactly two of them are not stored. [OPENED] is read off the
 * issue itself and [COMMENT] off the comments table, because both are already
 * recorded faithfully and a second copy of a comment is a copy that goes stale
 * the moment somebody edits it. They are in the same enum because the reader
 * sees one list: what happened to this issue, in order, whatever table it came
 * from.
 *
 * [COMMENT_REMOVED] is the odd one, and reading it beside [COMMENT] is the
 * clearest way to see why this table exists. A comment is not stored here
 * because the comments table holds it; a removed comment is stored here
 * because nothing else can hold it any more.
 */
enum class IssueEventKind {
    /** It was filed. Assembled from the issue's own date and reporter. */
    OPENED,

    /**
     * From here on, changes were written down.
     *
     * Stored once per issue that existed before this table did, by the
     * migration that created it. A history that began yesterday and says
     * nothing about it is a history that claims an issue opened last week had a
     * quiet week, and the one thing a record must never do is imply an absence
     * of events. Nothing writes one of these afterwards, so an issue filed
     * since has no such line and needs none.
     */
    RECORDING,

    /** It was closed, reopened, or picked up. */
    STATUS,

    /** A label was put on or taken off - one row for each, never a set. */
    LABEL,

    /**
     * Somebody said what kind of thing it is, or took that back.
     *
     * Both sides are the name the type had at the time, not its id - the same
     * choice [ASSIGNEE] makes and for the same reason. A type can be renamed,
     * and a history that read the row live would rewrite March to say what the
     * word became in June. Null on either side is untyped, which is a real
     * state an issue can be put back into.
     */
    TYPE,

    /** It changed hands, was handed out, or was put back down. */
    ASSIGNEE,

    /** Somebody started or stopped hearing about it. */
    OBSERVER,

    /**
     * It was linked to another issue, or the link was taken off.
     *
     * Written on both issues, phrased from each one's own side, because a link
     * is one fact about two of them: the history of the issue that was named
     * would otherwise be silent about the day somebody declared it a duplicate.
     */
    LINK,

    /** Somebody said something. Assembled from the comments table. */
    COMMENT,

    /**
     * A comment was taken off, and this line is all that is left of it.
     *
     * The one kind here that exists because a row went away. Every other event
     * is a change something else still records: a status is on the issue, a
     * label is in its labels, a comment is in the comments table. A removed
     * comment is in none of them, so a history assembled from those tables
     * would show a thread that had quietly lost a message - and a thread
     * nobody can trust to be whole is not a record of a conversation.
     *
     * [IssueEvent.was] is who wrote it and [IssueEvent.became] is null, the
     * way a label taken off is written. What it said is deliberately nowhere:
     * a record of the removal that reproduced the text would defeat the
     * removal, and the reason most worth removing a comment for is that the
     * text should not exist.
     */
    COMMENT_REMOVED,

}

/**
 * Something that happened to an issue, kept as a fact about the issue.
 *
 * Its own table rather than a reading of the workspace audit log, which is the
 * obvious thing to try and does not work. That log is free text keyed by
 * workspace, so finding one issue's lines means matching "Issue #4" inside a
 * sentence - which finds #4 in a workspace where the number has since been
 * handed to something else, misses everything an issue took with it when it was
 * moved, and cannot say what a change was *from*. It also never held two of the
 * four things anybody asks a history for: nothing anywhere recorded a label
 * changing or an issue changing hands, and the tools an agent works the tracker
 * through write no audit lines at all.
 *
 * So this is stored, and it is stored beside the audit log rather than instead
 * of it. The two answer different questions to different readers: the audit log
 * is what a workspace did, read in its settings by somebody looking across
 * everything; this is what happened to one issue, read on the issue by somebody
 * who wants to know why it is closed. Where both are worth writing, both are
 * written - and this one carries the shape the audit line cannot: what it was,
 * what it became, and an id rather than a number, so the row survives the issue
 * being moved to another workspace and goes with it when it is deleted.
 */
@Entity
@Table(name = "workspace_issue_event")
class IssueEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    /**
     * The issue's row id, not its number.
     *
     * The number is what people say and what the audit log wrote down, and it
     * is per workspace: an issue moved is given a free number where it lands
     * and the one it had goes to whatever is filed next. A history keyed by the
     * number would follow the wrong issue on both sides of that.
     */
    @Column(name = "issue_id", nullable = false)
    val issueId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val kind: IssueEventKind,

    /** Who did it, always - a history that cannot name anybody is a rumour. */
    @Column(nullable = false, length = 120)
    val actor: String,

    /** What it was: the old status, who it was taken from, the label removed. */
    @Column(columnDefinition = "text")
    val was: String? = null,

    /** What it became: the new status, who it went to, the label added. */
    @Column(columnDefinition = "text")
    val became: String? = null,

    @Column(nullable = false)
    val at: OffsetDateTime = OffsetDateTime.now(),
)

interface IssueEventRepository : JpaRepository<IssueEvent, Long> {

    /**
     * Everything that happened to one issue, oldest first.
     *
     * The id breaks the tie because a save that swaps three labels writes its
     * rows in the same instant, and a list that reorders itself between two
     * reads of the same issue is a list nobody trusts.
     */
    fun findByIssueIdOrderByAtAscIdAsc(issueId: Long): List<IssueEvent>

    /**
     * The labels this workspace has put on an issue, most recently put on
     * first.
     *
     * The history is asked rather than the issues, because "recently used" is
     * about the moment somebody reached for a label and not about the issues
     * that happen to be carrying it: an issue edited today does not make a
     * label somebody stuck on it in March a label anybody is using now.
     *
     * Only what was added - a row with a `became` - counts. Taking a label off
     * is also a use of it, but offering it back at the top of the list is
     * offering somebody the thing they just decided against.
     *
     * A label added and later removed everywhere still comes back here, so the
     * caller keeps only the ones still in use.
     *
     * The id breaks the tie, for the reason one issue's history is read by it:
     * a save that puts three labels on writes its rows in the same instant, and
     * a clock is not what decides which of them was typed first.
     */
    @Query(
        """
        select e.became from IssueEvent e, Issue i
        where i.id = e.issueId
          and i.workspaceId = :workspaceId
          and e.kind = io.mszymanski.orknux.server.issue.IssueEventKind.LABEL
          and e.became is not null
        group by e.became
        order by max(e.at) desc, max(e.id) desc
        """,
    )
    fun labelsLastAddedIn(workspaceId: Long): List<String>
}

/**
 * Writes an issue's history, wherever the change came from.
 *
 * Called from both doors, exactly as the news desk is: a label added in a
 * browser and one added by an agent through the MCP tools are the same thing
 * happening to the issue, and a history that only knew about the browser would
 * be a history whose gaps line up precisely with the work nobody watched.
 *
 * Every method takes the actor rather than reading the security context, so a
 * caller that already knows who is asking - both of them do - cannot end up
 * writing a different name here than it writes on the change itself.
 */
@Service
class IssueHistoryRecorder(private val events: IssueEventRepository) {

    /**
     * Nothing is written where nothing changed; the callers check that too.
     *
     * Both sides are the status's key and not its label, for the reason the type
     * is written as a name: this is a record of what happened, and it has to
     * stay true after the status has been relabelled or - once nothing holds it
     * - removed. The key is the one spelling that never changes.
     */
    fun statusChanged(issue: Issue, was: String, became: String, actor: String) {
        if (was == became) return
        write(issue, IssueEventKind.STATUS, actor, was, became)
    }

    /**
     * It was typed, retyped, or untyped. Both sides are names.
     *
     * The name and not the type's id, exactly as [assigneeChanged] writes a
     * name: this is a record of something that happened, and it has to stay
     * true after the type has been renamed or - once nothing carries it -
     * deleted. Null on either side is untyped, which is a state an issue can
     * be put back into and not an absence of information.
     *
     * Nothing is written where nothing changed, which is what makes this safe
     * to call from a save that posts the whole form back unchanged.
     */
    fun typeChanged(issue: Issue, was: String?, became: String?, actor: String) {
        if (was == became) return
        write(issue, IssueEventKind.TYPE, actor, was, became)
    }

    /**
     * One row per label, and never a row holding a set.
     *
     * A joined list would need a separator, and a separator is a character a
     * label may contain - so the encoding would be the thing that decided
     * whether "slack, timing" was one label or two, long after anybody could
     * ask. A row each also reads the way it happened: added this, took that
     * off.
     */
    fun labelsChanged(issue: Issue, was: Set<String>, became: Set<String>, actor: String) {
        (became - was).forEach { write(issue, IssueEventKind.LABEL, actor, null, it) }
        (was - became).forEach { write(issue, IssueEventKind.LABEL, actor, it, null) }
    }

    /**
     * It changed hands. Both sides are names, resolved where it happened.
     *
     * Names rather than the kind and id the issue stores, for the reason the
     * reporter is a name: this is a record of something that happened, and it
     * stays true after the agent it names has been deleted. Null on either side
     * is nobody, which is a real state - an issue can be put back down.
     */
    fun assigneeChanged(issue: Issue, was: String?, became: String?, actor: String) {
        if (was == became) return
        write(issue, IssueEventKind.ASSIGNEE, actor, was, became)
    }

    /**
     * A comment was removed. Who wrote it, who took it, and not a word of it.
     *
     * The author is written down because a line saying only that something was
     * removed is a line that cannot be argued with later; the text is not,
     * because this table would then be the copy that survives the removal.
     * See [IssueEventKind.COMMENT_REMOVED].
     */
    fun commentRemoved(issue: Issue, author: String, actor: String) =
        write(issue, IssueEventKind.COMMENT_REMOVED, actor, author, null)

    fun observerAdded(issue: Issue, name: String, actor: String) =
        write(issue, IssueEventKind.OBSERVER, actor, null, name)

    fun observerRemoved(issue: Issue, name: String, actor: String) =
        write(issue, IssueEventKind.OBSERVER, actor, name, null)

    /**
     * Two issues were linked, written down on both of them.
     *
     * Each side gets the sentence that is true of it: the blocker's line says it
     * blocks, the blocked one's says it is blocked by. Two calls rather than one
     * method writing both rows, because the two lines say different things and a
     * caller that could only write one of them is a caller whose next change
     * leaves half the record behind.
     *
     * Written the way [IssueRelations.said] writes it, which is also how the
     * news says it - one encoding rather than two, since both are read by
     * something that has to turn it back into a sentence. The number is the one
     * the other issue had at the time, like the assignee beside it: this is a
     * record of something that happened, not a pointer that has to keep working.
     */
    fun linked(issue: Issue, kind: IssueRelationKind, otherNumber: Int, actor: String) =
        write(issue, IssueEventKind.LINK, actor, null, IssueRelations.said(kind, otherNumber))

    fun unlinked(issue: Issue, kind: IssueRelationKind, otherNumber: Int, actor: String) =
        write(issue, IssueEventKind.LINK, actor, IssueRelations.said(kind, otherNumber), null)

    private fun write(issue: Issue, kind: IssueEventKind, actor: String, was: String?, became: String?) {
        val issueId = issue.id ?: return
        events.save(IssueEvent(issueId = issueId, kind = kind, actor = actor, was = was, became = became))
    }
}
