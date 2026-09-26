package io.mszymanski.orknux.server.issue

import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.OffsetDateTime

/**
 * Moving an issue to another workspace.
 *
 * An issue filed in the wrong place is a real and ordinary mistake, and until
 * now the only cure was to read it, retype it somewhere else and delete the
 * original - which loses the conversation, the files and the date it was
 * reported. This carries the whole record across instead.
 *
 * Its own controller rather than another mutation on [IssueAPI], because it is
 * the one operation in the tracker that has to know about every table an issue
 * touches, and burying that beside the ordinary edits would hide it. What it
 * knows is written down here in one place, so that adding a table to the
 * tracker and forgetting this file is a thing a reader can notice.
 *
 * What travels is everything hung on the issue's own id: its comments, its
 * labels, its links, its observers, its files and its history - which is keyed
 * by the id for exactly this reason, since a history keyed by the number would
 * be left behind for whatever is filed here next. What does not travel is its
 * number, which is per workspace and has to be one that is free where it is
 * going, and its news, which is a record of what was announced where and when.
 *
 * Nor does a link to another issue, which is why one standing there refuses the
 * move outright: that link is drawn as a number, and a number means one thing
 * per workspace.
 *
 * The type is the third kind of thing: it does not travel, but its *name* can.
 * A type is a row in one workspace's catalogue, so the issue is pointed at the
 * destination's row of the same name - and refused where the destination has no
 * such row, which is the same rule the assignee follows.
 *
 * Administrators of both workspaces, and seeing them is not enough - a move
 * takes an issue out of one team's tracker and puts it in another's, and the
 * number it had is immediately free for the next issue filed there, so it is
 * not something to be undone by moving it back.
 *
 * Both, not either. Administering only the source would be leave to push an
 * issue into a tracker somebody else leads; administering only the destination
 * would be leave to pull one out of theirs. Each workspace answers for what
 * enters and leaves it, and an installation administrator administers every
 * workspace, so nothing changes for them.
 */
@Controller
class IssueMoveAPI(
    private val issues: IssueRepository,
    private val types: IssueTypeRepository,
    private val agents: AgentRepository,
    private val models: ModelService,
    private val attachments: IssueAttachmentRepository,
    private val observers: IssueObserverRepository,
    private val relations: IssueRelationRepository,
    private val store: AttachmentStore,
    private val audit: WorkspaceAuditRecorder,
    private val access: WorkspaceAccess,
    private val newsDesk: IssueNewsDesk,
    private val reading: IssueAPI,
    private val statuses: IssueStatusCatalogue,
) {

    /**
     * Takes an issue to another workspace, or says why it cannot go.
     *
     * Which workspaces have to be administered is what decides the order here,
     * and it is no longer one check before everything: the role is per
     * workspace now, so there is nothing to ask until the issue says which
     * workspace it is in.
     *
     * Each of the three refusals is the one that says least. An issue nobody
     * can see reads as not there, so walking the ids tells you nothing. A
     * workspace the caller cannot see reads as not there too, for the reason
     * `WorkspaceAccess.requireVisible` gives. And one they can see but do not
     * administer says exactly that, naming the workspace, because by then they
     * are looking at it and what they need is the name of the thing they are
     * missing - which was the point of checking the role first in the version
     * before this.
     */
    @MutationMapping
    @Transactional
    fun moveIssue(@Argument id: Long, @Argument workspaceId: Long): IssueView {
        val held = issues.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw IssueNotFoundException(id)
        val source = access.requireAdministers(held.workspaceId)
        val destination = access.requireAdministers(workspaceId)

        if (destination.id == source.id) {
            throw IssueMoveRefusedException("Issue #${held.number} is already in ${source.name}")
        }

        refuseWhatCannotFollow(held, destination)

        /*
         * The type is re-pointed rather than carried. A type is a row in one
         * workspace's catalogue, so the row this issue holds cannot go with it;
         * what goes with it is the word, onto the destination's own row of the
         * same name. That the destination has one was settled above - a
         * workspace that does not is where the move is refused.
         */
        held.type = held.type?.let { types.named(requireNotNull(destination.id), it.name) }

        val was = held.number
        val files = attachments.findByIssueIdOrderByUploadedAtAsc(requireNotNull(held.id))
        carry(files, requireNotNull(destination.id))

        /*
         * A number free where it is going, which is almost never the one it
         * had. Two workspaces both have a #1, so keeping the old number would
         * either collide with a real issue - the unique index refuses that, and
         * rightly - or hand this one a number out of sequence in a tracker
         * people read from the top.
         *
         * Asked before the issue is told where it is going, and that ordering
         * is the whole of it: a query flushes what is pending first, so an
         * issue already carrying the destination's id would be counted among
         * the issues there and hand itself the number after its own.
         */
        val given = issues.lastNumber(requireNotNull(destination.id)) + 1

        held.workspaceId = requireNotNull(destination.id)
        held.number = given
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = currentUser()

        /*
         * The move written into the issue itself, not only into the audit.
         *
         * Somebody who followed a stale reference and landed on the wrong issue
         * needs to be able to find out where this one went, and the audit log
         * is behind the workspace settings where nobody reading a bug report
         * will look. The old number is spelled out in words on purpose: written
         * as a hash and digits it would be turned into a link by the same
         * shorthand that makes references go stale, and it would point at
         * whatever holds that number here.
         */
        val said = "Moved here from ${source.name}, where it was number $was."
        held.comments.add(IssueComment(author = currentUser(), content = said))
        held.lastCommentAt = OffsetDateTime.now()

        val saved = issues.saveAndFlush(held)
        files.forEach(attachments::save)

        audit.record(
            requireNotNull(source.id),
            WorkspaceAuditCategory.WORKSPACE,
            "Issue #$was \"${held.title}\" moved to ${destination.name} as #${saved.number}",
        )
        audit.record(
            requireNotNull(destination.id),
            WorkspaceAuditCategory.WORKSPACE,
            "Issue #${saved.number} \"${held.title}\" moved here from ${source.name}",
        )
        /*
         * Announced as what it is, a comment, through the desk everything else
         * goes through. Whoever was following this issue is told once, in the
         * workspace where they will now find it, and the words they are told
         * are the words on the issue.
         */
        newsDesk.commented(
            saved,
            currentUser(),
            said,
            // Which comment, so that removing it later takes this news with it.
            // The newest is the highest id, the column being an identity.
            commentId = saved.comments.maxByOrNull { requireNotNull(it.id) }?.id,
        )

        return requireNotNull(reading.workspaceIssue(requireNotNull(destination.id), saved.number))
    }

    /**
     * Says no where something on the issue could not exist where it is going.
     *
     * Refusing rather than quietly tidying up. An issue whose assignee was
     * silently cleared arrives in the destination looking like nobody's work,
     * and the person who was doing it finds out by never hearing about it
     * again - which is exactly the surprise that is worse than being stopped.
     * Every sentence here names the thing in the way and can be acted on: take
     * it off, or put a counterpart in the destination, and move it again.
     *
     * A person is never in the way. Users are the installation's rather than a
     * workspace's, so somebody assigned an issue here is still somebody there,
     * and the same goes for an `@name` written into a comment - mentions are
     * matched against usernames, which the move does not touch. Agents and
     * models are a workspace's own, and those are what this looks at.
     */
    private fun refuseWhatCannotFollow(issue: Issue, destination: Workspace) {
        val there = requireNotNull(destination.id)

        /*
         * A status is a key in one workspace's list, so an issue in REVIEW can
         * only stay in review where REVIEW also exists. Matched by key, which is
         * what the issue holds and what the two workspaces would have to agree
         * on. Refused rather than reset, like the type below it: every workspace
         * begins with the same four, so this is the case where somebody has
         * taken one out or added one, and either is a decision worth asking
         * about.
         */
        if (statuses.of(there).none { it.key == issue.status }) {
            val called = statuses.labelOf(issue.workspaceId, issue.status)
            throw IssueMoveRefusedException(
                "This issue is in $called (${issue.status}), and ${destination.name} has no such status. " +
                    "Add it to its issue statuses or move this issue to another status, then move it.",
            )
        }

        /*
         * A type belongs to one workspace's catalogue, so an issue typed `bug`
         * can only stay typed where `bug` also exists. Matched by name and not
         * by id, because the name is what the type means to a reader and the id
         * is a row number that means nothing over there.
         *
         * Refused rather than untyped on the way, like the assignee above it:
         * an issue that quietly lost what it was arrives in the other tracker
         * looking unclassified, and whoever sorted it there finds out by it
         * never turning up in the filter again. Both workspaces begin with
         * `bug` and `feature`, so this is the case where somebody has taken one
         * out - which is a decision they made, and worth being asked about.
         */
        issue.type?.let { held ->
            if (types.named(there, held.name) == null) {
                throw IssueMoveRefusedException(
                    "This issue is a ${held.name}, and ${destination.name} does not file those. " +
                        "Add ${held.name} to its issue types or change this issue's type, then move it.",
                )
            }
        }

        issue.assignee?.let { held ->
            val kind = held.kind
            val id = held.id
            if (kind != null && id != null && !existsIn(there, kind, id)) {
                throw IssueMoveRefusedException(
                    "This issue is assigned to ${nameOf(issue.workspaceId, kind, id)}, " +
                        "which is not in ${destination.name}. Change the assignee, then move it.",
                )
            }
        }

        /*
         * A link between two issues is a link within one tracker, because what
         * it draws is a number and a number means one thing per workspace. An
         * issue carried out of here would leave its links pointing at whatever
         * eventually holds those numbers - which is precisely the mistake the
         * page's address avoided by carrying the number rather than the id, and
         * it is worse here because nothing would look wrong.
         *
         * Refused rather than quietly dropped, like everything else in this
         * method: somebody who wrote down that this blocks three other things
         * should be the one who decides those three facts are no longer worth
         * keeping.
         */
        val linked = relations.touching(requireNotNull(issue.id))
        if (linked.isNotEmpty()) {
            val named = linked
                .map { IssueRelations.seenFrom(it, requireNotNull(issue.id)).second }
                .mapNotNull { issues.findByIdOrNull(it)?.number }
                .joinToString(", ") { "#$it" }
            throw IssueMoveRefusedException(
                "This issue is linked to $named, which cannot follow it to ${destination.name}. " +
                    "Take the links off, then move it.",
            )
        }

        val stranded = observers.findByIssueIdOrderByAddedAtAscIdAsc(requireNotNull(issue.id))
            .filter { !existsIn(there, it.kind, it.observerId) }
        if (stranded.isNotEmpty()) {
            val named = stranded.joinToString(", ") { nameOf(issue.workspaceId, it.kind, it.observerId) }
            throw IssueMoveRefusedException(
                "$named would stop hearing about this issue, not being in ${destination.name}. " +
                    "Take them off, then move it.",
            )
        }
    }

    /**
     * What to call the thing standing in the way, read where the issue still is.
     *
     * By name rather than by kind and id, because the sentence is read by
     * somebody who has to go and change it: "the support responder" is a thing
     * they can find, and "AGENT 14" is a row number.
     */
    private fun nameOf(workspaceId: Long, kind: AssigneeKind, id: String): String = when (kind) {
        AssigneeKind.USER -> null
        AssigneeKind.AGENT -> agents.findByIdOrNull(id.toLongOrNull() ?: -1)?.name
        AssigneeKind.MODEL -> models.models(workspaceId).firstOrNull { it.id.toString() == id }?.name
    } ?: "the ${kind.name.lowercase()} it names"

    /**
     * Whether a kind and an id name something the destination has.
     *
     * A user is not asked about the workspace, because a user does not belong
     * to one. An agent and a model do, and an id that names one of another
     * workspace's is the case this exists to catch.
     */
    private fun existsIn(workspaceId: Long, kind: AssigneeKind, id: String): Boolean = when (kind) {
        AssigneeKind.USER -> true
        AssigneeKind.AGENT -> agents.findByIdOrNull(id.toLongOrNull() ?: -1)?.workspaceId == workspaceId
        AssigneeKind.MODEL -> models.models(workspaceId).any { it.id.toString() == id }
    }

    /**
     * Takes the bytes across, and puts them back if the move does not happen.
     *
     * Files are stored one directory per workspace, which is what decides who
     * can be handed them, so an issue that moved and left its screenshots
     * behind is an issue whose pictures no longer load. The row is told where
     * they went; both halves change together or neither does.
     *
     * Two ways this is undone. A file that will not move takes the ones already
     * moved back immediately, before the exception leaves - there is no
     * transaction to roll back a rename. A transaction that fails afterwards
     * for some reason of its own does the same from the synchronisation, since
     * the rows it wrote are being thrown away and the files would otherwise be
     * in a folder nothing points at.
     */
    private fun carry(files: List<IssueAttachment>, destination: Long) {
        if (files.isEmpty()) return

        val moved = mutableListOf<WasAt>()
        try {
            for (file in files) {
                val from = WasAt(file, file.location, file.workspaceId)
                file.location = store.move(file.location, destination)
                file.workspaceId = destination
                moved += from
            }
        } catch (cause: Exception) {
            putBack(moved)
            throw IssueMoveRefusedException(
                "The files on this issue could not be moved with it, so nothing was moved: ${cause.message}",
            )
        }

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCompletion(status: Int) {
                        if (status != TransactionSynchronization.STATUS_COMMITTED) putBack(moved)
                    }
                },
            )
        }
    }

    /** The files where they were, best effort: there is nothing better to try. */
    private fun putBack(moved: List<WasAt>) {
        moved.forEach { was ->
            runCatching { store.move(was.file.location, was.workspaceId) }
            was.file.location = was.location
            was.file.workspaceId = was.workspaceId
        }
    }

    /** Where one file was before the move, which is what putting it back needs. */
    private class WasAt(val file: IssueAttachment, val location: String, val workspaceId: Long)

    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"
}

/**
 * A move that would have broken something, refused in a sentence.
 *
 * The words matter more here than in most refusals: whoever reads this is an
 * administrator who has just pressed Move and needs to know what to change
 * before pressing it again, so each one names the thing in the way.
 */
class IssueMoveRefusedException(val why: String) : RuntimeException(why), Refusal {

    override val arguments get() = mapOf("why" to why)
}

