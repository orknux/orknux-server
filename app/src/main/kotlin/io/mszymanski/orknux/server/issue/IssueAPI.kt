package io.mszymanski.orknux.server.issue

import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.attachment.AttachmentsDisabledException
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * A workspace's issues.
 *
 * Visible to whoever can see the workspace, like everything else in it: an
 * issue is a note about this workspace's work, and hiding it from the people
 * doing that work would leave them filing it somewhere else.
 *
 * The one thing here that is not obvious is the assignee. It can be a person,
 * an agent or a model, because this is a product where the thing doing the
 * work is often not a person - and what is stored is a kind and an id,
 * resolved to a name on the way out, so a rename reads correctly afterwards
 * and a deletion reads as "no longer here" rather than as a dangling id.
 */
/**
 * What a list of issues is ordered by.
 *
 * The questions somebody actually asks of a tracker, and no more than those:
 * what is newest, what is this thing called, what is moving, where is the
 * talking, and what kind of thing are these.
 */
enum class IssueOrder {
    NUMBER,
    TITLE,
    UPDATED,
    LAST_COMMENT,

    /**
     * By what the issues are, which is how a tracker is read when somebody is
     * deciding what to do next rather than looking for one thing.
     *
     * Untyped last either way round, like an absent last comment: an issue
     * nobody has classified is not the first kind of thing, it is the absence
     * of a kind.
     */
    TYPE,
}

@Controller
class IssueAPI(
    private val issues: IssueRepository,
    private val types: IssueTypeRepository,
    private val workspaces: WorkspaceRepository,
    private val users: AppUserRepository,
    private val agents: AgentRepository,
    private val models: ModelService,
    private val audit: WorkspaceAuditRecorder,
    private val access: WorkspaceAccess,
    private val newsDesk: IssueNewsDesk,
    private val history: IssueHistoryRecorder,
    private val events: IssueEventRepository,
    private val attachments: IssueAttachmentRepository,
    private val links: IssueLinkRepository,
    private val relations: IssueRelationRepository,
    private val observers: IssueObserverRepository,
    private val store: AttachmentStore,
    private val installation: InstallationSettings,
    /** What the workspace's statuses are, and which one a caller meant. */
    private val statuses: IssueStatusCatalogue,
) {

    /*
     * Transactional because describing an issue reads its comments, and the
     * comments are lazy: outside a session that read is an exception rather
     * than a query. Read-only, since nothing here writes.
     */
    @QueryMapping
    @Transactional(readOnly = true)
    fun workspaceIssues(
        @Argument workspaceId: Long,
        /**
         * One of the workspace's status keys, in any case, or absent for every
         * status. A key the workspace does not have is refused rather than
         * answered with nothing, for the reason an unknown type id is: an empty
         * list somebody believes is worse than an error.
         */
        @Argument status: String?,
        /**
         * Which type, spelled the way the assignee is: absent is every issue,
         * an empty string is the untyped ones, an id is that type. One argument
         * with three states rather than two arguments that can contradict each
         * other, and it is the convention already in [IssueInput.assigneeId] -
         * absent leaves it alone, empty means nobody.
         */
        @Argument typeId: String?,
        @Argument search: String?,
        @Argument page: Int?,
        @Argument size: Int?,
        @Argument order: IssueOrder?,
        @Argument ascending: Boolean?,
    ): IssuePageView {
        requireWorkspaceAccess(workspaceId)
        /*
         * Newest first by default: an issue tracker is read from the top.
         *
         * Sorted on the server rather than in the page, because the page holds
         * twenty rows of a hundred and sorting those twenty would order the
         * page rather than the tracker - which looks like it worked until the
         * row somebody wanted turns out to be on page three.
         */
        val by = when (order ?: IssueOrder.NUMBER) {
            IssueOrder.NUMBER -> "number"
            IssueOrder.TITLE -> "title"
            IssueOrder.UPDATED -> "lastModifiedAt"
            IssueOrder.LAST_COMMENT -> "lastCommentAt"
            IssueOrder.TYPE -> "type.name"
        }
        val direction = if (ascending == true) Sort.Direction.ASC else Sort.Direction.DESC
        /*
         * Case is ignored for the title and nowhere else. By the words rather
         * than by their case is what somebody means by "sort by name", and
         * Postgres would otherwise put every capital first - but asking it to
         * lower() a number or a timestamp is a function that does not exist,
         * and the query fails rather than sorting badly.
         */
        val sorted = Sort.by(
            Sort.Order(direction, by)
                .let { if (by == "title" || by == "type.name") it.ignoreCase() else it }
                /*
                 * An issue nobody has replied to has no last comment, and
                 * Postgres sorts nulls first when the order is descending -
                 * which would open a list sorted by conversation with every
                 * issue that has none. Last, either way round: an absent answer
                 * is not the newest one. An untyped issue is the same story:
                 * having no kind is not being the first kind.
                 */
                .let { if (by == "lastCommentAt" || by == "type.name") it.nullsLast() else it },
        )
        val asked = PageRequest.of(page ?: 0, (size ?: 20).coerceIn(1, 100), sorted)
        /*
         * Filtered in the query, through the specification the tools already
         * use, rather than through the two JPQL methods this had one of for
         * each state of the status filter. A third optional filter would have
         * been four of them, and the fourth would have been the one nobody
         * wrote - which is the argument the specification exists to settle.
         */
        val found = issues.findAll(
            issueFilter(
                workspaceId = workspaceId,
                status = status?.let { statuses.match(workspaceId, it).key },
                search = search,
                type = typeWanted(workspaceId, typeId),
            ),
            asked,
        )
        return IssuePageView(found.totalElements.toInt(), found.content.map(::describe))
    }

    /**
     * The type argument as the filter reads it.
     *
     * An id naming no type here is refused rather than answered with nothing.
     * A filter that silently matches no issue looks exactly like a tracker that
     * has none, and the one thing worse than an error is an empty list somebody
     * believes.
     */
    private fun typeWanted(workspaceId: Long, typeId: String?): IssueTypeWanted = when {
        typeId == null -> AnyType
        typeId.isBlank() -> Untyped
        else -> {
            val id = typeId.toLongOrNull() ?: throw IssueTypeNotFoundException(-1)
            val held = types.findByIdOrNull(id)?.takeIf { it.workspaceId == workspaceId }
                ?: throw IssueTypeNotFoundException(id)
            OfType(requireNotNull(held.id))
        }
    }

    /**
     * One issue, by the number people say.
     *
     * Not by its row id. "#4" is what the page shows, what somebody types in a
     * message and what the address should carry - an address holding the id
     * instead showed `/issues/17` above a page titled `#15`, and two of us
     * spent a while believing the wrong issue had been answered.
     */
    @QueryMapping
    @Transactional(readOnly = true)
    fun workspaceIssue(@Argument workspaceId: Long, @Argument number: Int): IssueView? {
        if (!access.canSee(workspaceId)) return null
        return issues.findByWorkspaceIdAndNumber(workspaceId, number)?.let(::describe)
    }

    /**
     * Every label in use here, so the filter offers what exists rather than a
     * box - the ones this workspace has reached for lately first.
     *
     * A tracker settles into a handful of labels it is actually using and a
     * long tail of ones it used once, and the alphabet files both in the same
     * place. The box under the label field offers six, so alphabetical order
     * meant six labels beginning with whatever letter somebody favoured in
     * March, and the milestone everything is being tagged with this week was
     * not among them.
     *
     * What the history has not heard of follows, in the alphabet: labels on
     * issues filed before any of this was recorded are a list read by name.
     */
    @QueryMapping
    fun workspaceIssueLabels(@Argument workspaceId: Long): List<String> {
        requireWorkspaceAccess(workspaceId)
        val inUse = issues.labelsIn(workspaceId)
        val held = inUse.toSet()
        val lately = events.labelsLastAddedIn(workspaceId).filter { it in held }
        val recent = lately.toSet()
        return lately + inUse.filterNot { it in recent }
    }

    /**
     * What an issue can be assigned to, by name.
     *
     * One list of three kinds, the way the box that offers it works: somebody
     * typing "sup" wants the support agent or the support desk user and should
     * not have to say which kind first.
     */
    @QueryMapping
    fun issueAssignees(@Argument workspaceId: Long, @Argument search: String?): List<AssigneeView> {
        requireWorkspaceAccess(workspaceId)
        val wanted = search?.trim()?.lowercase().orEmpty()

        val people = users.findAll().map {
            AssigneeView(AssigneeKind.USER, requireNotNull(it.id).toString(), it.displayName, it.username)
        }
        val theAgents = agents.findByWorkspaceId(workspaceId, PageRequest.of(0, MANY, Sort.by("name")))
            .content
            .map { AssigneeView(AssigneeKind.AGENT, requireNotNull(it.id).toString(), it.name, "agent") }
        val theModels = models.models(workspaceId)
            .map { AssigneeView(AssigneeKind.MODEL, it.id.toString(), it.name, it.providerName) }

        return (people + theAgents + theModels)
            .filter { wanted.isEmpty() || it.name.lowercase().contains(wanted) || it.hint.lowercase().contains(wanted) }
            .sortedBy { it.name.lowercase() }
            .take(MANY)
    }

    @MutationMapping
    @Transactional
    fun createIssue(@Argument input: IssueInput): IssueView {
        val workspaceId = requireNotNull(input.workspaceId) { "An issue belongs to a workspace" }
        requireWorkspaceAccess(workspaceId)

        val title = input.title?.trim().orEmpty()
        if (title.isEmpty()) throw IssueTitleInvalidException()

        val made = issues.save(
            Issue(
                workspaceId = workspaceId,
                number = issues.lastNumber(workspaceId) + 1,
                title = title,
                description = input.description?.trim()?.takeIf { it.isNotEmpty() },
                status = input.status?.let { statuses.resolve(workspaceId, it).key } ?: statuses.initialOf(workspaceId),
                type = typeFrom(workspaceId, input.typeId),
                reporter = currentUser(),
                assignee = assigneeFrom(workspaceId, input),
                labels = cleanLabels(input.labels),
                lastModifiedBy = currentUser(),
            ),
        )
        audit.record(workspaceId, WorkspaceAuditCategory.WORKSPACE, "Issue #${made.number} \"$title\" opened")
        newsDesk.assigned(made, currentUser())
        newsDesk.opened(made, currentUser())
        /*
         * The labels an issue is filed with are recorded like any other.
         *
         * A label put on at the door and one added a day later are the same
         * act, and a history that only heard about the second read as though
         * the first had always been there - with nobody's name against it. It
         * is also what "recently used" is read from, and a tracker where every
         * label arrives with the issue would otherwise have no record of a
         * label being used at all.
         */
        history.labelsChanged(made, emptySet(), made.labels.toSet(), currentUser())
        return describe(made)
    }

    @MutationMapping
    @Transactional
    fun updateIssue(@Argument id: Long, @Argument input: IssueInput): IssueView {
        val held = issues.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw IssueNotFoundException(id)

        input.title?.trim()?.let {
            if (it.isEmpty()) throw IssueTitleInvalidException()
            held.title = it
        }
        // An empty description is a description somebody cleared, so it is
        // written; an absent one is a field they did not touch.
        input.description?.let { held.description = it.trim().takeIf { text -> text.isNotEmpty() } }
        /*
         * Copied before it is replaced, and copied rather than aliased: the set
         * on the entity is the one about to be written over, so a reference to
         * it would report every issue as having always had the labels it ends
         * up with.
         */
        val labelsWere = held.labels.toSet()
        input.labels?.let { held.labels = cleanLabels(it) }
        /*
         * Read the way the assignee is: absent leaves it alone, and an empty
         * string is somebody choosing "Untyped" rather than a client that
         * forgot the field. The name is kept before the row is replaced,
         * because the history stores what it was called and not which row it
         * was - see `IssueHistoryRecorder.typeChanged`.
         */
        val typeWas = held.type?.name
        input.typeId?.let { held.type = typeFrom(held.workspaceId, it) }
        val statusWas = held.status
        var statusChanged = false
        input.status?.let { asked ->
            // The workspace's own spelling of what was asked for, or a refusal
            // naming what it has: an issue never holds a key nothing is called.
            val wanted = statuses.resolve(held.workspaceId, asked).key
            if (wanted != held.status) {
                audit.record(
                    held.workspaceId,
                    WorkspaceAuditCategory.WORKSPACE,
                    "Issue #${held.number} ${statuses.auditedAs(held.workspaceId, wanted, statusWas)}",
                )
                statusChanged = true
            }
            held.status = wanted
        }
        /*
         * Handed to somebody else is news to the somebody else, and only if it
         * is somebody else: saving the page without touching the assignee sends
         * the same one back, and a notification for that would train whoever
         * gets it to stop reading them.
         */
        val before = held.assignee?.let { it.kind to it.id }
        val heldBy = nameFor(held)?.name
        /*
         * The id is what says whether the assignee was touched: absent, the
         * caller did not mention it; empty, they chose nobody. A kind on its
         * own names no one and is left alone rather than read as a clear -
         * half a pair is a client that forgot the other half, not an
         * instruction.
         */
        if (input.assigneeId != null) {
            held.assignee = assigneeFrom(held.workspaceId, input)
        }
        val handedOver = held.assignee?.let { it.kind to it.id } != before

        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = currentUser()
        val saved = issues.save(held)
        if (handedOver) newsDesk.assigned(saved, currentUser())
        if (statusChanged) newsDesk.statusChanged(saved, currentUser())
        /*
         * The history is written from what changed rather than from what was
         * sent: the page posts the whole form on every save, so an issue saved
         * for its description arrives carrying the same status, the same labels
         * and the same assignee it already had. Each recorder call compares and
         * writes nothing where the two sides match.
         */
        history.statusChanged(saved, statusWas, saved.status, currentUser())
        history.typeChanged(saved, typeWas, saved.type?.name, currentUser())
        history.labelsChanged(saved, labelsWere, saved.labels.toSet(), currentUser())
        if (handedOver) history.assigneeChanged(saved, heldBy, nameFor(saved)?.name, currentUser())
        return describe(saved)
    }

    /**
     * Changing what you wrote.
     *
     * Only the author, and deliberately not administrators either: what
     * somebody else wrote is what they wrote, and a record that anybody
     * reading it could rewrite is not a record. The edit is marked, so a
     * comment that has changed says so.
     */
    @MutationMapping
    @Transactional
    fun editIssueComment(@Argument id: Long, @Argument content: String): IssueView {
        val issue = issues.findAll()
            .firstOrNull { held -> held.comments.any { it.id == id } }
            ?.takeIf { access.canSee(it.workspaceId) }
            ?: throw IssueCommentNotFoundException(id)

        val comment = issue.comments.first { it.id == id }
        if (comment.author != currentUser()) throw IssueCommentNotYoursException()

        val said = content.trim()
        if (said.isEmpty()) throw IssueCommentEmptyException()

        comment.content = said
        comment.editedAt = OffsetDateTime.now()
        return describe(issues.save(issue))
    }

    /**
     * Taking a comment off a thread.
     *
     * **Removed, and not marked removed.** A tombstone in the comments table
     * would read better in the thread - the reader would see where a message
     * used to be - and it would be the wrong shape for what this is mostly
     * going to be used for. The report that asked for this calls a comment
     * carrying something that should not be there permanent, and a row kept
     * with its content blanked is a row somebody has to trust was blanked
     * everywhere: in the comments table, in the excerpt the history draws, in
     * the copy of the text the news desk wrote for every watcher. So the row
     * goes, and the record of it goes somewhere the text never was.
     *
     * That somewhere is the issue's history, which is what makes deleting safe
     * rather than silent. [IssueHistoryRecorder.commentRemoved] writes who
     * wrote it and who took it and not a word of what it said, so a thread
     * cannot lose a message without the tab beside it saying so. A history
     * that reproduced the comment would have been a tombstone by another name.
     *
     * Whoever wrote it, or an administrator of the workspace. Wider than
     * [editIssueComment] on purpose, and the reasoning is on
     * [IssueCommentNotYoursToRemoveException].
     *
     * Its files go with it, rows and bytes, exactly as they do when an issue is
     * deleted. A comment's attachment is claimed by that comment and by nothing
     * else - [tie] only ever takes a file that is spoken for by nothing - so
     * there is nothing else pointing at it to break, and a file whose comment
     * is gone is one no page can draw and nobody can remove: a leak, and often
     * the screenshot with the credential in it.
     */
    @MutationMapping
    @Transactional
    fun removeIssueComment(@Argument id: Long): IssueView {
        val issue = issues.findAll()
            .firstOrNull { held -> held.comments.any { it.id == id } }
            ?.takeIf { access.canSee(it.workspaceId) }
            ?: throw IssueCommentNotFoundException(id)

        val comment = issue.comments.first { it.id == id }
        if (comment.author != currentUser() && !access.canAdminister(issue.workspaceId)) {
            throw IssueCommentNotYoursToRemoveException()
        }

        /*
         * The bytes first, and by hand.
         *
         * `workspace_issue_attachment.comment_id` cascades, so the rows would
         * go on their own the moment the comment did - and the bytes never
         * would, which is precisely the leak `deleteIssue` writes its own note
         * about. Flushed here so the deletes are ordered ahead of the comment's
         * rather than left to whichever order the action queue settles on.
         */
        val files = attachments.findByIssueIdOrderByUploadedAtAsc(requireNotNull(issue.id))
            .filter { it.commentId == comment.id }
        files.forEach { store.remove(it.location) }
        attachments.deleteAll(files)
        attachments.flush()

        newsDesk.forgetComment(id)
        history.commentRemoved(issue, comment.author, currentUser())

        issue.comments.remove(comment)
        /*
         * Worked out again rather than cleared, because "the last comment" is a
         * fact about the comments that are left. Removing the newest one moves
         * this back to the one before it; removing the only one makes it null,
         * which is what the field already means when nobody has said anything.
         * A field left pointing at a comment that no longer exists is a lie the
         * LAST_COMMENT sort would go on reading.
         */
        issue.lastCommentAt = issue.comments.maxOfOrNull { it.createdAt }
        issue.lastModifiedAt = OffsetDateTime.now()
        issue.lastModifiedBy = currentUser()

        val saved = issues.save(issue)
        audit.record(
            saved.workspaceId,
            WorkspaceAuditCategory.WORKSPACE,
            "Issue #${saved.number}: removed a comment by ${comment.author}",
        )
        return describe(saved)
    }

    @MutationMapping
    @Transactional
    fun deleteIssue(@Argument id: Long): Boolean {
        val held = issues.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw IssueNotFoundException(id)
        /*
         * The rows go with the issue because the database says so; the bytes do
         * not, and nothing would ever come looking for them again. An issue
         * tracker that leaves its screenshots behind fills somebody's disk with
         * files that belong to issues nobody can name.
         */
        attachments.findByIssueIdOrderByUploadedAtAsc(requireNotNull(held.id))
            .forEach { store.remove(it.location) }
        issues.delete(held)
        audit.record(held.workspaceId, WorkspaceAuditCategory.WORKSPACE, "Issue #${held.number} deleted")
        return true
    }

    @MutationMapping
    @Transactional
    fun commentOnIssue(
        @Argument id: Long,
        @Argument content: String,
        @Argument attachmentIds: List<Long>?,
    ): IssueView {
        val held = issues.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw IssueNotFoundException(id)

        val said = content.trim()
        if (said.isEmpty()) throw IssueCommentEmptyException()

        held.comments.add(IssueComment(author = currentUser(), content = said))
        held.lastCommentAt = OffsetDateTime.now()
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = currentUser()
        /*
         * Flushed rather than saved, because the files need the comment's id
         * and a comment that has not been written yet does not have one: the
         * insert would otherwise happen at the end of the transaction, which is
         * after the point where this needs the answer.
         */
        val saved = issues.saveAndFlush(held)
        /*
         * The comment as the persistence context now holds it, rather than the
         * object added a line ago: saving merges, and a merge copies a new
         * child into a managed instance of its own - which is the one that was
         * given the id, leaving the original still holding null. The newest
         * comment is the highest id, the column being an identity.
         */
        val posted = requireNotNull(saved.comments.maxByOrNull { requireNotNull(it.id) })
        tie(saved, attachmentIds.orEmpty(), commentId = posted.id)
        newsDesk.commented(saved, currentUser(), said, commentId = posted.id)
        return describe(saved)
    }

    /**
     * The assignee an input names, checked against this workspace.
     *
     * The id carries the answer, the way an empty description does: a blank
     * one is nobody, which is how the page says "No one". A kind without an
     * id names no one either. An id without a kind is refused rather than
     * read as one or the other - the two halves mean nothing apart, and
     * guessing which was meant would clear an issue somebody was assigning.
     *
     * Nothing else is trusted from the caller past its kind: an id that names
     * no agent here is a mistake worth reporting now rather than an issue
     * assigned to a number.
     */
    private fun assigneeFrom(workspaceId: Long, input: IssueInput): Assignee? {
        val id = input.assigneeId?.takeIf { it.isNotBlank() } ?: return null
        val kind = input.assigneeKind ?: throw IssueAssigneeKindMissingException(id)

        val known = when (kind) {
            AssigneeKind.USER -> users.findByIdOrNull(id.toLongOrNull() ?: -1) != null
            AssigneeKind.AGENT -> agents.findByIdOrNull(id.toLongOrNull() ?: -1)?.workspaceId == workspaceId
            AssigneeKind.MODEL -> models.models(workspaceId).any { it.id.toString() == id }
        }
        if (!known) throw IssueAssigneeInvalidException("$kind $id")
        return Assignee(kind = kind, id = id)
    }

    /**
     * The type an input names, checked against this workspace.
     *
     * Empty is untyped, the way an empty assignee is nobody: it is a state
     * somebody chooses from the picker and not an omission. An id that names a
     * type of another workspace is refused rather than read as untyped, because
     * a browser that sent one has a bug and quietly clearing the field is how a
     * bug becomes an issue somebody has to reclassify.
     */
    private fun typeFrom(workspaceId: Long, typeId: String?): IssueType? {
        val id = typeId?.takeIf { it.isNotBlank() } ?: return null
        val row = id.toLongOrNull()?.let { types.findByIdOrNull(it) }
        return row?.takeIf { it.workspaceId == workspaceId } ?: throw IssueTypeNotFoundException(id.toLongOrNull() ?: -1)
    }

    /** Trimmed, deduplicated, and never empty strings: a label is a word. */
    private fun cleanLabels(labels: List<String>?): MutableSet<String> =
        labels.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()

    private fun describe(issue: Issue): IssueView {
        /*
         * The issue's files and its comments' files in one read, sorted out
         * here rather than asked for a comment at a time: an issue with fifteen
         * comments is ordinary, and reading it should not be fifteen round
         * trips to discover that fourteen of them brought nothing.
         */
        val files = attachments.findByIssueIdOrderByUploadedAtAsc(requireNotNull(issue.id))
        return describe(
            issue,
            files,
            links.findByIssueIdOrderByAddedAtAscIdAsc(requireNotNull(issue.id)),
            relations.touching(requireNotNull(issue.id)),
            observers.findByIssueIdOrderByAddedAtAscIdAsc(requireNotNull(issue.id)),
        )
    }

    private fun describe(
        issue: Issue,
        files: List<IssueAttachment>,
        addresses: List<IssueLink>,
        linked: List<IssueRelation>,
        watching: List<IssueObserver>,
    ): IssueView {
        /*
         * Asked once for the whole issue rather than once per comment: the
         * answer is about the reader and the workspace, and an issue with
         * fifteen comments would otherwise be fifteen reads of the same
         * workspace to draw fifteen identical buttons.
         */
        val administers = access.canAdminister(issue.workspaceId)
        return IssueView(
            id = requireNotNull(issue.id),
            workspaceId = issue.workspaceId,
            number = issue.number,
            title = issue.title,
            description = issue.description,
            status = issue.status,
            type = issue.type?.let { IssueTypeView(requireNotNull(it.id), it.workspaceId, it.name, 0) },
            reporter = issue.reporter,
            assignee = nameFor(issue),
            labels = issue.labels.sorted(),
            attachments = files.filter { it.commentId == null }.map(::describeFile),
            links = addresses.map(::describeLink),
            related = describeRelations(issue, linked),
            observers = watching.map { describeObserver(issue.workspaceId, it) },
            comments = issue.comments.map { comment ->
                IssueCommentView(
                    id = requireNotNull(comment.id),
                    author = comment.author,
                    content = comment.content,
                    createdAt = comment.createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    editedAt = comment.editedAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                    attachments = files.filter { it.commentId == comment.id }.map(::describeFile),
                    /*
                     * Whether the person reading this may change it. Answered here
                     * rather than compared in the browser, so the button and the
                     * refusal agree - and so a second window signed in as somebody
                     * else does not offer an edit that would be refused.
                     */
                    mine = comment.author == currentUser(),
                    /*
                     * Whether they may take it off, which is a wider question than
                     * `mine` and so cannot be read off it. Answered here for the
                     * same reason `mine` is: a button drawn on a rule the browser
                     * guessed at is a button that disagrees with the refusal.
                     */
                    mayRemove = comment.author == currentUser() || administers,
                )
            },
            lastCommentAt = issue.lastCommentAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            createdAt = issue.createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            lastModifiedAt = issue.lastModifiedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            lastModifiedBy = issue.lastModifiedBy,
        )
    }

    /**
     * The assignee as a name, looked up now.
     *
     * Stored as an id so a rename reads correctly here; resolved every read so
     * something that has since been removed reads as gone rather than as a
     * number nobody recognises.
     */
    private fun nameFor(issue: Issue): AssigneeView? {
        val held = issue.assignee ?: return null
        val kind = held.kind ?: return null
        val id = held.id ?: return null
        return resolve(issue.workspaceId, kind, id)
            ?: AssigneeView(kind, id, "No longer here", kind.name.lowercase())
    }

    /**
     * A kind and an id as the name and the second line a box shows, or null
     * where it names nothing here.
     *
     * The same lookup serves the assignee and the observers, because the two
     * are the same question asked about different rows - and one that answered
     * them separately is one that would drift the moment either grew a kind.
     */
    private fun resolve(workspaceId: Long, kind: AssigneeKind, id: String): AssigneeView? = when (kind) {
        AssigneeKind.USER -> users.findByIdOrNull(id.toLongOrNull() ?: -1)
            ?.let { AssigneeView(kind, id, it.displayName, it.username) }

        AssigneeKind.AGENT -> agents.findByIdOrNull(id.toLongOrNull() ?: -1)
            ?.let { AssigneeView(kind, id, it.name, "agent") }

        AssigneeKind.MODEL -> models.models(workspaceId).firstOrNull { it.id.toString() == id }
            ?.let { AssigneeView(kind, id, it.name, it.providerName) }
    }

    private fun describeFile(attachment: IssueAttachment) = IssueAttachmentView(
        id = requireNotNull(attachment.id),
        filename = attachment.filename,
        contentType = attachment.contentType,
        sizeBytes = attachment.sizeBytes,
        uploadedBy = attachment.uploadedBy,
        uploadedAt = attachment.uploadedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        /*
         * Whether the person reading this may take it off again, answered here
         * for the reason a comment's `mine` is: the button and the refusal have
         * to agree, and comparing names in the browser is how they stop
         * agreeing.
         */
        mine = attachment.uploadedBy == currentUser(),
    )

    /**
     * A link as the page shows it, with its GitHub reading worked out now.
     *
     * Read rather than stored, on purpose. What `owner/repo#123` is worth
     * depends only on the address, so keeping a copy of the answer beside it
     * would be keeping something that can go stale against the rules that
     * produced it - and improving those rules would mean a migration over every
     * link anybody has ever added rather than a deployment.
     */
    private fun describeLink(link: IssueLink) = IssueLinkView(
        id = requireNotNull(link.id),
        url = link.url,
        title = link.title,
        github = GitHubAddress.shortNameOf(link.url),
        addedBy = link.addedBy,
        addedAt = link.addedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        /*
         * Whether the person reading this may take it off, answered here for
         * the reason a comment's `mine` is: the button and the refusal have to
         * agree, and comparing names in the browser is how they stop agreeing.
         */
        mine = link.addedBy == currentUser(),
    )

    /**
     * Hangs an address on an issue.
     *
     * Nothing is done with it beyond keeping it and reading its shape - see
     * [GitHubAddress] for why the recognition never asks GitHub anything. What
     * is done is refusing what a browser should not be handed: [IssueLinks]
     * decides that, because the page renders this as an anchor other people
     * click.
     */
    @MutationMapping
    @Transactional
    fun addIssueLink(@Argument id: Long, @Argument url: String, @Argument title: String?): IssueView {
        val held = issues.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw IssueNotFoundException(id)

        val address = IssueLinks.clean(url)
        val added = links.save(
            IssueLink(
                issueId = requireNotNull(held.id),
                url = address,
                title = title?.trim()?.take(IssueLinks.MAX_TITLE)?.takeIf { it.isNotEmpty() },
                addedBy = currentUser(),
            ),
        )
        /*
         * The audit says what a reader would recognise rather than the address:
         * a line of forty characters of query string tells whoever is scanning
         * the log nothing, and `owner/repo#123` tells them what was linked.
         */
        audit.record(
            held.workspaceId,
            WorkspaceAuditCategory.WORKSPACE,
            "Issue #${held.number}: linked ${nameOf(added)}",
        )
        return describe(held)
    }

    /**
     * Taking a link off again.
     *
     * Only whoever added it, exactly as only whoever wrote a comment may change
     * it and only whoever attached a file may remove it. Not administrators
     * either - what somebody else put on an issue is part of the record, and an
     * administrator who needs it gone can delete the issue, which shows.
     */
    @MutationMapping
    @Transactional
    fun removeIssueLink(@Argument id: Long): Boolean {
        val held = links.findByIdOrNull(id) ?: throw IssueLinkNotFoundException(id)
        // The argument is a link's id, so a link on an issue the caller cannot
        // see answers as a link that is not there. Naming the issue instead
        // would say the number was real and whose it was.
        val issue = issues.findByIdOrNull(held.issueId)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw IssueLinkNotFoundException(id)
        if (held.addedBy != currentUser()) throw IssueLinkNotYoursException()

        links.delete(held)
        audit.record(
            issue.workspaceId,
            WorkspaceAuditCategory.WORKSPACE,
            "Issue #${issue.number}: unlinked ${nameOf(held)}",
        )
        return true
    }

    /** What to call a link where one line is all there is: the page's own order. */
    private fun nameOf(link: IssueLink) =
        link.title ?: GitHubAddress.shortNameOf(link.url) ?: link.url

    /**
     * The other issues this one is linked to, each read from this one's side.
     *
     * A row is the whole link and is stored facing one way, so the same row is
     * "blocks #7" on one issue and "is blocked by #4" on the other. Turning it
     * round happens here, once, rather than in whatever is drawing it - a page
     * that had to know which end it was looking at is a page that will one day
     * get it backwards.
     *
     * The far ends are read in one go. An issue with six links would otherwise
     * be six queries to discover six titles.
     */
    private fun describeRelations(issue: Issue, linked: List<IssueRelation>): List<IssueRelationView> {
        val here = requireNotNull(issue.id)
        val ends = linked.map { IssueRelations.seenFrom(it, here) }
        val others = issues.findAllById(ends.map { it.second }).associateBy { requireNotNull(it.id) }
        return linked.mapIndexedNotNull { at, relation ->
            val (kind, otherId) = ends[at]
            val other = others[otherId] ?: return@mapIndexedNotNull null
            IssueRelationView(
                id = requireNotNull(relation.id),
                kind = kind,
                issueId = otherId,
                number = other.number,
                title = other.title,
                status = other.status,
                linkedBy = relation.linkedBy,
                linkedAt = relation.linkedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            )
        }
    }

    /**
     * What this issue could be linked to, narrowed by what was typed.
     *
     * Issues are referred to by number here - the page turns `#124` in a comment
     * into a link, and that is how people say them out loud - so the number is
     * what the box has to find first, with or without the hash. The text search
     * runs as well and behind it, because "#12" is also a perfectly good thing
     * to have in a title, and somebody who typed a number and meant the words is
     * better served by a short list than by a refusal.
     *
     * Nothing already linked is offered, and neither is the issue itself: a row
     * that can only be refused is a row that should not be in the list.
     */
    @QueryMapping
    @Transactional(readOnly = true)
    fun issuesToLink(@Argument id: Long, @Argument search: String?): List<IssueRefView> {
        val held = issues.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw IssueNotFoundException(id)

        val wanted = search?.trim().orEmpty()
        val byNumber = wanted.removePrefix("#").toIntOrNull()
            ?.let { issues.findByWorkspaceIdAndNumber(held.workspaceId, it) }
        /*
         * Newest first when nothing has been typed, which is what an empty box
         * should offer: an issue is nearly always linked to something filed
         * around the same time as it.
         */
        val byText = issues.search(
            held.workspaceId,
            wanted,
            PageRequest.of(0, SOME, Sort.by(Sort.Direction.DESC, "number")),
        ).content

        val taken = relations.touching(requireNotNull(held.id))
            .map { IssueRelations.seenFrom(it, requireNotNull(held.id)).second }
            .toSet() + requireNotNull(held.id)

        return (listOfNotNull(byNumber) + byText)
            .distinctBy { requireNotNull(it.id) }
            .filterNot { requireNotNull(it.id) in taken }
            .take(SOME)
            .map { IssueRefView(requireNotNull(it.id), it.number, it.title, it.status) }
    }

    /**
     * Says that one issue has something to do with another.
     *
     * The relation is given as this issue reads it - "is blocked by" is a thing
     * somebody chooses from a list on this page - and [IssueRelations.of] turns
     * it round into the one row that will be stored. Both issues are written to:
     * the history of the issue that was named would otherwise be silent about
     * the day it acquired a duplicate.
     *
     * Twice the same way is not an error, for the reason a second press of the
     * watch button is not: it is the state somebody asked for, and it is already
     * so. Twice a different way is refused rather than replaced, because two
     * issues are connected in one way or not at all and changing which way is a
     * thing to do on purpose.
     */
    @MutationMapping
    @Transactional
    fun relateIssue(
        @Argument id: Long,
        @Argument otherId: Long,
        @Argument kind: IssueRelationKind,
    ): IssueView {
        val held = issues.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw IssueNotFoundException(id)
        if (id == otherId) throw IssueRelationToItselfException()

        /*
         * The far end has to be in the same workspace, and one that is not reads
         * as being somewhere else rather than as not existing - the caller has
         * an id in their hand either way, and which of the two it is tells them
         * nothing they could act on differently.
         */
        val other = issues.findByIdOrNull(otherId)?.takeIf { it.workspaceId == held.workspaceId }
            ?: throw IssueRelationElsewhereException()

        relations.between(id, otherId)?.let { already ->
            val (facing, _) = IssueRelations.seenFrom(already, id)
            if (facing == kind) return describe(held)
            throw IssueRelationAlreadyException("#${held.number} ${facing.reads} #${other.number}")
        }

        relations.save(IssueRelations.of(id, otherId, kind, currentUser()))

        history.linked(held, kind, other.number, currentUser())
        history.linked(other, kind.opposite, held.number, currentUser())
        audit.record(
            held.workspaceId,
            WorkspaceAuditCategory.WORKSPACE,
            "Issue #${held.number} ${kind.reads} #${other.number}",
        )
        newsDesk.linked(held, currentUser(), IssueRelations.said(kind, other.number))
        newsDesk.linked(other, currentUser(), IssueRelations.said(kind.opposite, held.number))
        return describe(held)
    }

    /**
     * Takes a link between two issues off again.
     *
     * Anybody who can see them, which is where this parts company with the
     * address links and the files beside it. Those are things one person put on
     * an issue and are part of what that person said; a link between two issues
     * is a claim about both of them, maintained the way the labels are. The team
     * at the far end never made it and must be able to take it off, and a wrong
     * "duplicates #4" that only its author can remove is a wrong answer the rest
     * of the tracker has to work around after they leave.
     *
     * Written into both histories, like the making of it, so neither issue is
     * left saying something the other has forgotten.
     */
    @MutationMapping
    @Transactional
    fun unrelateIssue(@Argument id: Long): Boolean {
        val link = relations.findByIdOrNull(id) ?: throw IssueRelationNotFoundException(id)
        // The argument is a link's id, so a link between issues the caller
        // cannot see reads as a link that is not there. The same silence
        // removeIssueLink keeps, for the same reason.
        val held = issues.findByIdOrNull(link.issueId)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw IssueRelationNotFoundException(id)
        val other = issues.findByIdOrNull(link.otherIssueId) ?: throw IssueRelationNotFoundException(id)

        relations.delete(link)

        history.unlinked(held, link.kind, other.number, currentUser())
        history.unlinked(other, link.kind.opposite, held.number, currentUser())
        audit.record(
            held.workspaceId,
            WorkspaceAuditCategory.WORKSPACE,
            "Issue #${held.number} unlinked from #${other.number}",
        )
        return true
    }

    /**
     * Asking to hear about an issue.
     *
     * Two permissions on one mutation, the shape `createUserToken` already
     * uses: nothing named means yourself, and naming somebody else needs the
     * administrator role. Two mutations would be the same two rules written
     * twice, and the second one is where they would drift apart.
     *
     * Adding somebody is told to them. Being made an observer is the moment the
     * issue starts concerning you, and everything that arrives afterwards
     * arrives without explanation otherwise - while adding yourself is silent,
     * because you were looking at the page when you did it.
     */
    @MutationMapping
    @Transactional
    fun observeIssue(
        @Argument id: Long,
        @Argument observerKind: AssigneeKind?,
        @Argument observerId: String?,
    ): IssueView {
        val held = issues.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw IssueNotFoundException(id)

        val (kind, who) = observerAsked(held.workspaceId, observerKind, observerId)
        val issueId = requireNotNull(held.id)
        // Twice is the same subscription, and the unique index says so - but a
        // second press of the button is not an error worth showing anybody.
        if (observers.findByIssueIdAndKindAndObserverId(issueId, kind, who) == null) {
            observers.save(IssueObserver(issueId = issueId, kind = kind, observerId = who, addedBy = currentUser()))
            val name = resolve(held.workspaceId, kind, who)
            audit.record(
                held.workspaceId,
                WorkspaceAuditCategory.WORKSPACE,
                "Issue #${held.number}: ${name?.name ?: who} is now an observer",
            )
            history.observerAdded(held, name?.name ?: who, currentUser())
            newsDesk.observing(held, currentUser(), readersOf(held.workspaceId, kind, who))
        }
        return describe(held)
    }

    /**
     * Taking somebody off again, under the same two rules.
     *
     * Not the link rule, deliberately. A link somebody added is part of the
     * record and only theirs to remove; an observer is a subscription, and one
     * an administrator put there is one an administrator can take away - which
     * is what "an administrator can add or remove somebody else" means.
     */
    @MutationMapping
    @Transactional
    fun unobserveIssue(
        @Argument id: Long,
        @Argument observerKind: AssigneeKind?,
        @Argument observerId: String?,
    ): IssueView {
        val held = issues.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw IssueNotFoundException(id)

        val (kind, who) = observerAsked(held.workspaceId, observerKind, observerId)
        observers.findByIssueIdAndKindAndObserverId(requireNotNull(held.id), kind, who)?.let {
            observers.delete(it)
            val name = resolve(held.workspaceId, kind, who)
            audit.record(
                held.workspaceId,
                WorkspaceAuditCategory.WORKSPACE,
                "Issue #${held.number}: ${name?.name ?: who} is no longer an observer",
            )
            history.observerRemoved(held, name?.name ?: who, currentUser())
        }
        return describe(held)
    }

    /**
     * Who the caller asked about, and whether they are allowed to ask.
     *
     * Absent means yourself, which is the common case and the one that needs no
     * permission at all. A person who is you is still yourself however it was
     * spelled - the page sends the row it has rather than nothing - so the
     * administrator check is on being somebody else, not on having named
     * anybody.
     *
     * An agent is never yourself. Something acting for an agent reaches the
     * tracker through the tools rather than through this, so an agent named
     * here is always somebody putting an agent on an issue - which is deciding
     * for somebody else, and needs the role.
     *
     * The role is a role that administers *this* workspace, not the
     * installation. Putting somebody on an issue is signing them up to be told
     * about it, and who is on this workspace's issues is this workspace's
     * business - it was only ever an installation administrator's because
     * there was nothing smaller to ask for. An installation administrator
     * still qualifies everywhere, and somebody who merely belongs to the
     * workspace still cannot do it.
     */
    private fun observerAsked(
        workspaceId: Long,
        kind: AssigneeKind?,
        id: String?,
    ): Pair<AssigneeKind, String> {
        val me = users.findByUsername(currentUser())
        if (kind == null || id.isNullOrBlank()) {
            val mine = me ?: throw IssueObserverInvalidException(currentUser())
            return AssigneeKind.USER to requireNotNull(mine.id).toString()
        }

        if (kind == AssigneeKind.MODEL) throw IssueObserverInvalidException("A model")
        val itIsMe = kind == AssigneeKind.USER && me?.id?.toString() == id
        if (!itIsMe) access.requireAdministers(workspaceId)

        // Nothing is trusted from the caller past its kind, exactly as an
        // assignee is not: an id that names no agent here is a mistake worth
        // reporting now rather than a subscription for a number.
        val known = when (kind) {
            AssigneeKind.USER -> users.findByIdOrNull(id.toLongOrNull() ?: -1) != null
            AssigneeKind.AGENT -> agents.findByIdOrNull(id.toLongOrNull() ?: -1)?.workspaceId == workspaceId
            AssigneeKind.MODEL -> false
        }
        if (!known) throw IssueObserverInvalidException("$kind $id")
        return kind to id
    }

    /** One observer as the news desk addresses them, or nobody if they have gone. */
    private fun readersOf(workspaceId: Long, kind: AssigneeKind, id: String): List<NewsReader> {
        val name = when (kind) {
            AssigneeKind.USER -> users.findByIdOrNull(id.toLongOrNull() ?: -1)?.username
            AssigneeKind.AGENT -> agents.findByIdOrNull(id.toLongOrNull() ?: -1)?.name
            AssigneeKind.MODEL -> null
        } ?: return emptyList()
        return listOf(NewsReader(kind, name, id))
    }

    /**
     * An observer as the page shows them.
     *
     * Resolved every read, like the assignee: a renamed agent reads correctly
     * afterwards and somebody who has been removed reads as gone rather than as
     * a number nobody recognises.
     */
    private fun describeObserver(workspaceId: Long, watching: IssueObserver): IssueObserverView {
        val who = resolve(workspaceId, watching.kind, watching.observerId)
        return IssueObserverView(
            kind = watching.kind,
            id = watching.observerId,
            name = who?.name ?: "No longer here",
            hint = who?.hint ?: watching.kind.name.lowercase(),
            addedBy = watching.addedBy,
            addedAt = watching.addedAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            /*
             * Whether this row is the person reading, answered here for the
             * reason a comment's `mine` is: the page draws one button for
             * yourself and another for everybody else, and comparing names in
             * the browser is how the button and the refusal stop agreeing.
             */
            mine = watching.kind == AssigneeKind.USER &&
                users.findByIdOrNull(watching.observerId.toLongOrNull() ?: -1)?.username
                    .equals(currentUser(), ignoreCase = true),
        )
    }

    /**
     * Says which issue - or which of its comments - these files belong to.
     *
     * Only files of the issue's own workspace, and only ones not already spoken
     * for: an id from somewhere else is dropped rather than argued with, the
     * way a chat drops one, since whoever sent it is already looking at the
     * next screen. Uploading is what takes the time, and this is what settles
     * where it went.
     */
    private fun tie(issue: Issue, attachmentIds: List<Long>, commentId: Long?): List<IssueAttachment> {
        if (attachmentIds.isEmpty()) return emptyList()
        // Checked again here, not only at the upload: an administrator can turn
        // attachments off between the two, and the half that writes the row is
        // the half worth stopping.
        if (!installation.attachmentsEnabled()) throw AttachmentsDisabledException()

        return attachmentIds.mapNotNull { attachments.findByIdOrNull(it) }
            .filter { it.workspaceId == issue.workspaceId && it.issueId == null }
            .onEach {
                it.issueId = issue.id
                it.commentId = commentId
            }
    }

    /**
     * Puts uploaded files on an issue.
     *
     * Separate from the upload because the two happen at different moments: the
     * bytes go up while the report is still being written, and which issue they
     * belong to is only settled when it is filed - a new issue has no id until
     * then.
     */
    @MutationMapping
    @Transactional
    fun attachToIssue(@Argument id: Long, @Argument attachmentIds: List<Long>): IssueView {
        val held = issues.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw IssueNotFoundException(id)

        val tied = tie(held, attachmentIds, commentId = null)
        if (tied.isNotEmpty()) {
            val what = if (tied.size == 1) tied.first().filename else "${tied.size} files"
            audit.record(
                held.workspaceId,
                WorkspaceAuditCategory.WORKSPACE,
                "Issue #${held.number}: attached $what",
            )
        }
        return describe(held)
    }

    /**
     * Taking a file off again.
     *
     * Only whoever attached it, exactly as only whoever wrote a comment may
     * change it, and for the same reason: what somebody else put on an issue is
     * part of the record. Not administrators either - one who needs a file gone
     * can delete the issue, which is a thing that leaves a mark.
     *
     * The bytes go with the row. A file nobody can reach any more is still a
     * file taking up the disk of whoever runs this.
     */
    @MutationMapping
    @Transactional
    fun removeIssueAttachment(@Argument id: Long): Boolean {
        val held = attachments.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw IssueAttachmentNotFoundException(id)
        if (held.uploadedBy != currentUser()) throw IssueAttachmentNotYoursException()

        attachments.delete(held)
        store.remove(held.location)

        held.issueId?.let { on ->
            issues.findByIdOrNull(on)?.let {
                audit.record(
                    it.workspaceId,
                    WorkspaceAuditCategory.WORKSPACE,
                    "Issue #${it.number}: removed ${held.filename}",
                )
            }
        }
        return true
    }

    private fun currentUser(): String =
        SecurityContextHolder.getContext().authentication?.name ?: "system"

    private fun requireWorkspaceAccess(workspaceId: Long) {
        access.requireVisible(workspaceId)
    }

    private companion object {
        /** Enough of anything to choose from in a box that also has a search. */
        const val MANY = 200

        /**
         * As many issues as a box offering something to link to should show.
         *
         * A tenth of [MANY]: this list is read by somebody who already knows
         * which issue they mean and is typing its number, and a long one would
         * be scrolled past rather than read.
         */
        const val SOME = 10
    }
}

data class IssuePageView(val totalElements: Int, val content: List<IssueView>)

data class IssueView(
    val id: Long,
    val workspaceId: Long,
    val number: Int,
    val title: String,
    val description: String?,
    /** The key of one of the workspace's statuses; the page reads its label off the definitions. */
    val status: String,
    /**
     * What kind of thing it is, or null for untyped.
     *
     * The count on it is not filled in here and is always zero: how many issues
     * carry a type is a fact about the workspace's catalogue that the settings
     * page asks for, and answering it on every issue in a page of twenty would
     * be twenty group-bys to draw one chip.
     */
    val type: IssueTypeView?,
    val reporter: String,
    val assignee: AssigneeView?,
    val labels: List<String>,
    /** What was attached to the issue itself; a comment's files are on the comment. */
    val attachments: List<IssueAttachmentView>,
    /** Addresses hung on the issue, oldest first. */
    val links: List<IssueLinkView>,
    /** Other issues this one is linked to, each read from this one's side. */
    val related: List<IssueRelationView>,
    /**
     * Whoever asked to hear about it, oldest first.
     *
     * Only the people explicitly added. The reporter and the assignee already
     * hear about everything and have a place of their own on the page, and
     * showing them here as rows nobody can take off would be a list where half
     * the crosses do nothing - besides which the assignee changes, so an
     * implicit observer would appear and disappear without anybody choosing it.
     */
    val observers: List<IssueObserverView>,
    val comments: List<IssueCommentView>,
    /** When somebody last said something here, or null if nobody has. */
    val lastCommentAt: String?,
    val createdAt: String,
    val lastModifiedAt: String,
    val lastModifiedBy: String,
)

data class IssueCommentView(
    val id: Long,
    val author: String,
    val content: String,
    val createdAt: String,
    /** Null until somebody changes it. */
    val editedAt: String?,
    val attachments: List<IssueAttachmentView>,
    /** Whether the person reading this wrote it, and so may change it. */
    val mine: Boolean,
    /**
     * Whether they may take it off: whoever wrote it, or an administrator of
     * the workspace. Wider than [mine], so it is its own answer rather than
     * something the page could work out from that one.
     */
    val mayRemove: Boolean,
)

/** A file on an issue, as the page shows it. */
data class IssueAttachmentView(
    val id: Long,
    val filename: String,
    val contentType: String,
    val sizeBytes: Long,
    val uploadedBy: String,
    val uploadedAt: String,
    /** Whether the person reading this attached it, and so may remove it. */
    val mine: Boolean,
)

/**
 * An address on an issue, as the page shows it.
 *
 * Three things to show it by, in order: what somebody called it, what GitHub
 * would call it, and failing both the address itself. The page picks the first
 * that is there rather than being told which to use, so the same link reads the
 * same wherever it appears.
 */
data class IssueLinkView(
    val id: Long,
    val url: String,
    /** What whoever added it called it, or null when they let the address speak. */
    val title: String?,
    /**
     * The address as GitHub reads it, or null when it is not a GitHub one.
     *
     * By the shape of the address alone - nothing here asks GitHub anything.
     */
    val github: String?,
    val addedBy: String,
    val addedAt: String,
    /** Whether the person reading this added it, and so may remove it. */
    val mine: Boolean,
)

/**
 * Another issue this one is linked to, as the page shows it.
 *
 * The relation is the one facing the reader: the same stored row is "blocks
 * #7" on one issue and "is blocked by #4" on the other, and which of the two
 * this is has already been decided by the time it gets here.
 *
 * The far issue's number, title and status travel with it because the row is
 * read rather than clicked through. Whether the thing blocking this one is
 * closed is the entire question somebody has when they see the word "blocked",
 * and a link that made them open another page to answer it is a link that will
 * be ignored.
 */
data class IssueRelationView(
    /** The link's own id, which is what taking it off again needs. */
    val id: Long,
    val kind: IssueRelationKind,
    /** The issue at the far end - its row id, since that is what a mutation takes. */
    val issueId: Long,
    val number: Int,
    val title: String,
    val status: String,
    val linkedBy: String,
    val linkedAt: String,
)

/**
 * An issue as something to be picked out of a list, and nothing more.
 *
 * What a box offering something to link to needs: the number to recognise it
 * by, the title to be sure, the status to know whether it matters, and the id
 * the mutation is given. Not the whole issue - the list is ten of them, and
 * nine will be scrolled past.
 */
data class IssueRefView(
    val id: Long,
    val number: Int,
    val title: String,
    val status: String,
)

/** Something an issue can be assigned to, as the box shows it. */
data class AssigneeView(
    val kind: AssigneeKind,
    val id: String,
    val name: String,
    /** The second line: a username, a provider, or what kind of thing it is. */
    val hint: String,
)

data class IssueInput(
    val workspaceId: Long?,
    val title: String?,
    val description: String?,
    /** One of the workspace's status keys, in any case; absent leaves it alone. */
    val status: String?,
    /**
     * Which type, or empty for untyped, or absent to leave it alone.
     *
     * The same three states [assigneeId] has and for the same reason: the page
     * posts the whole form on every save, so "the field was not sent" and "the
     * field was cleared" have to be different values or every save would untype
     * the issue.
     */
    val typeId: String?,
    val labels: List<String>?,
    val assigneeKind: AssigneeKind?,
    val assigneeId: String?,
)

/**
 * Somebody who asked to hear about an issue, as the page shows them.
 *
 * The same shape an assignee is shown in, because it is the same question -
 * who is this, and what kind of thing are they - with the two facts a
 * subscription adds: who put them here, and whether the person reading is
 * looking at themselves.
 */
data class IssueObserverView(
    val kind: AssigneeKind,
    val id: String,
    val name: String,
    /** The second line: a username, or what kind of thing it is. */
    val hint: String,
    /** Themselves, in the ordinary case, or the administrator who decided. */
    val addedBy: String,
    val addedAt: String,
    /** Whether this is the person reading, so the page knows which button to draw. */
    val mine: Boolean,
)
