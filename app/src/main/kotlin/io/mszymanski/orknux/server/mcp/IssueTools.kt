package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.connector.model.ModelService
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.issue.Assignee
import io.mszymanski.orknux.server.issue.AssigneeKind
import io.mszymanski.orknux.server.issue.Issue
import io.mszymanski.orknux.server.issue.IssueComment
import io.mszymanski.orknux.server.issue.IssueHistoryRecorder
import io.mszymanski.orknux.server.issue.IssueNewsDesk
import io.mszymanski.orknux.server.issue.IssueObserver
import io.mszymanski.orknux.server.issue.IssueObserverRepository
import io.mszymanski.orknux.server.issue.IssueRelationRepository
import io.mszymanski.orknux.server.issue.IssueRelations
import io.mszymanski.orknux.server.issue.IssueRepository
import io.mszymanski.orknux.server.issue.IssueStatusCatalogue
import io.mszymanski.orknux.server.issue.IssueStatusUnknownException
import io.mszymanski.orknux.server.issue.IssueType
import io.mszymanski.orknux.server.issue.IssueTypeRepository
import io.mszymanski.orknux.server.issue.IssueTypeUnknownException
import io.mszymanski.orknux.server.issue.AnyType
import io.mszymanski.orknux.server.issue.OfType
import io.mszymanski.orknux.server.issue.Untyped
import io.mszymanski.orknux.server.issue.NewsReader
import io.mszymanski.orknux.server.issue.issueFilter
import io.mszymanski.orknux.server.security.WebProperties
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/**
 * The tracker, as something an assistant can work rather than read about.
 *
 * Its own service for a reason that is not tidiness: writing on an issue
 * touches its comments, which are lazy, so those calls need a transaction
 * around them - and a private method calling another private method inside one
 * class never gets one. Here each is a bean method, and each says whether it
 * writes.
 *
 * Everything is addressed by the number people say. "#4" is what the page
 * shows, what somebody types in a message, and what the address carries.
 *
 * **Every one of them opens its own transaction**, and that is the other reason
 * this is a bean. These are reached from a tool call, and a tool call is
 * reached from whatever the caller was in the middle of - a chat turn, which is
 * one transaction from the person's message to the answer. Joining it meant a
 * refused insert here left that transaction aborted, and the polite refusal
 * `OrknuxTools.run` handed back to the model was worth nothing: the next
 * statement the chat ran on the same connection threw, about a table nobody had
 * asked about, and the whole turn came back as an empty `INTERNAL_ERROR` with
 * the person's own message gone from it. `REQUIRES_NEW` is what makes the
 * promise those catches make true - a tool that failed costs the tool call and
 * nothing else - and it is the shape `ScheduledTriggerOccurrence` uses for the
 * same reason. The cost is stated plainly: a tool call that succeeded is
 * committed even if the turn around it does not survive, which is what "the
 * agent filed an issue" should have meant all along.
 */
@Service
class IssueTools(
    private val issues: IssueRepository,
    private val types: IssueTypeRepository,
    private val users: AppUserRepository,
    private val agents: AgentRepository,
    private val newsDesk: IssueNewsDesk,
    private val history: IssueHistoryRecorder,
    private val audit: WorkspaceAuditRecorder,
    private val observers: IssueObserverRepository,
    private val relations: IssueRelationRepository,
    private val models: ModelService,
    private val web: WebProperties,
    private val mapper: ObjectMapper,
    /** What this workspace's statuses are, and which one a model meant. */
    private val statuses: IssueStatusCatalogue,
    /** What a deleted issue was carrying, so the bytes go with the rows. Issue #478. */
    private val attachments: io.mszymanski.orknux.server.issue.IssueAttachmentRepository,
    private val store: io.mszymanski.orknux.server.attachment.AttachmentStore,
) {

    /**
     * The keys this workspace's statuses go by, in order, for the tool
     * descriptions to name.
     *
     * Read when the tools are described rather than written into the
     * description, because the list is the workspace's and changes when an
     * administrator changes it - a description that said "OPEN or CLOSED" is
     * how a model was once told two of three values existed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun statusKeys(scope: OrknuxScope): List<String> = statuses.of(scope.workspaceId).map { it.key }

    /** Where an issue can be opened, sent back with the issue itself. */
    private fun issueLink(workspaceId: Long, number: Int): String {
        val base = web.allowedOrigins.firstOrNull()?.trimEnd('/').orEmpty()
        return "$base/workspace/$workspaceId/issues/$number"
    }

    /** Says what went wrong in the shape every other tool answers in. */
    private fun refuse(reason: String): String = mapper.writeValueAsString(mapOf("error" to reason))

    private fun text(arguments: String, name: String): String? = runCatching {
        mapper.readTree(arguments).path(name).stringValue()?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun number(arguments: String, name: String): Long? = runCatching {
        val node = mapper.readTree(arguments).path(name)
        if (node.isNumber) node.asLong() else node.stringValue()?.trim()?.toLongOrNull()
    }.getOrNull()

    companion object {
        /**
         * How many issues one answer carries at most.
         *
         * Not private, and not a literal in a test either: what goes wrong past
         * this number is only visible to a test that creates more than it, and a
         * test that hard-codes 200 stops testing anything the day somebody moves
         * the constant. `IssueToolsPagingTest` fills a workspace to `MANY + 5`.
         */
        const val MANY = 200

        /** As long as `Issue.title` is. */
        const val TITLE = 200

        /** As long as one entry of `Issue.labels` is. */
        const val LABEL = 60
    }

    /**
     * Refuses text the column would refuse, and says the limit.
     *
     * A model writes its own titles and nothing warns it how long one may be,
     * so this is not an exotic input - it is the ordinary one, occasionally.
     * Letting it reach Postgres got the model `value too long for type
     * character varying(200)`, which is a fact about a column and not an
     * instruction: it says nothing about which field, and a model handed it can
     * only guess. Said here instead, with the field named and the limit in it,
     * a title that is too long is something the model can shorten and send
     * again - which is what it does.
     *
     * Every field a tool call can fill that lands in a bounded column goes
     * through this. There are two of them, the title and each label; the rest
     * of what these tools write is either `text`, or a name looked up before it
     * is used, or one of a fixed set.
     */
    private fun tooLong(what: String, given: String, limit: Int): String? =
        if (given.length <= limit) {
            null
        } else {
            "That $what is ${given.length} characters long. An issue $what has to be $limit characters or fewer - " +
                "shorten it and send it again."
        }

    /** The first label that will not fit, said the same way. */
    private fun labelsTooLong(labels: Collection<String>): String? =
        labels.firstNotNullOfOrNull { tooLong("label", it, LABEL) }

    /**
     * The tracker as a list, filtered the way somebody would ask for it.
     *
     * "Assigned to me" is the question this exists for, and it is asked by
     * name rather than by id: an assistant knows it is called Claude, not that
     * it is user 5. The name is matched against what the assignee resolves to,
     * so a person and an agent are found the same way - resolved to the kind
     * and id the column holds *before* the query, because the name lives in
     * another table and the filter has to be in the query.
     *
     * Every filter is in the query, and that is the whole point. Fetching a
     * page and filtering it afterwards filters the page: asking for everything
     * labelled `p1` in a tracker of 221 answered with the p1s among the newest
     * 200 and said nothing about the rest. What still cannot fit is said
     * plainly, with the total, rather than left to look like the whole answer.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun list(scope: OrknuxScope, arguments: String): String {
        val wanted = text(arguments, "status")?.let { asked ->
            try {
                statuses.match(scope.workspaceId, asked).key
            } catch (unknown: IssueStatusUnknownException) {
                return refuse(unknown.message.orEmpty())
            }
        }
        val assignee = text(arguments, "assignee")?.lowercase()
        /*
         * By name, because a name is what a model has: `bug`, not row 12. The
         * word `untyped` asks for the issues nobody has classified, which is
         * the one question the id-shaped filter in the browser can ask and a
         * name alone cannot - and it is reserved rather than ambiguous, since a
         * workspace that named a type "untyped" would have named it after the
         * absence of one.
         */
        val type = text(arguments, "type")?.trim()?.takeIf { it.isNotEmpty() }?.let { asked ->
            if (asked.equals("untyped", ignoreCase = true)) {
                Untyped
            } else {
                val found = typeNamed(scope, asked) ?: return refuse(unknownType(scope, asked))
                OfType(requireNotNull(found.id))
            }
        } ?: AnyType
        val search = text(arguments, "search").orEmpty()
        /*
         * Every one of them, not any: "p1, slack" asks for the urgent Slack
         * issues, not for everything urgent and everything about Slack.
         */
        val wantedLabels = text(arguments, "labels")
            ?.split(',')
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val newestFirst = PageRequest.of(0, MANY, Sort.by("number").descending())

        val page = issues.findAll(
            issueFilter(
                workspaceId = scope.workspaceId,
                status = wanted,
                search = search,
                labels = wantedLabels,
                assignedTo = assignee?.let { assignedTo(scope, it) },
                type = type,
            ),
            newestFirst,
        )
        val found = page.content

        val answer = mutableMapOf<String, Any?>(
            "issues" to found.map {
                mapOf(
                    "number" to it.number,
                    "title" to it.title,
                    "status" to it.status,
                    "type" to it.type?.name,
                    "reporter" to it.reporter,
                    "assignee" to nameOf(it),
                    "labels" to it.labels.sorted(),
                    "url" to issueLink(scope.workspaceId, it.number),
                )
            },
            "matching" to page.totalElements,
        )
        if (page.totalElements > found.size) {
            answer["note"] = "These are the newest ${found.size} of ${page.totalElements} matching issues. " +
                "Narrow it with status, type, labels, assignee or search to see the rest."
        }
        return mapper.writeValueAsString(answer)
    }

    /**
     * A name, as the assignees it could mean.
     *
     * The column holds a kind and an id; the question is asked with a name, and
     * the name belongs to a user, an agent or a model. Resolving it here rather
     * than filtering on [nameOf] afterwards is what lets the filter be part of
     * the query - and a name that matches nobody comes back empty, which the
     * filter reads as "no issue", not as "no filter".
     */
    private fun assignedTo(scope: OrknuxScope, name: String): List<Pair<AssigneeKind, String>> {
        val people = users.findAll()
            .filter { it.displayName.contains(name, ignoreCase = true) }
            .map { AssigneeKind.USER to requireNotNull(it.id).toString() }
        val theirAgents = agents.findByWorkspaceId(scope.workspaceId, Pageable.unpaged()).content
            .filter { it.name.contains(name, ignoreCase = true) }
            .map { AssigneeKind.AGENT to requireNotNull(it.id).toString() }
        val theirModels = models.models(scope.workspaceId)
            .filter { it.name.contains(name, ignoreCase = true) }
            .map { AssigneeKind.MODEL to it.id.toString() }
        return people + theirAgents + theirModels
    }

    /**
     * The labels this workspace uses, with how many issues carry each.
     *
     * Worth its own tool rather than a note in the list: a label only works as
     * a filter if you know it exists, and "p1" is not something anybody can
     * guess from the outside.
     *
     * The database does the counting. It used to read one page of issues and
     * tally the labels on it, which counted the page rather than the tracker -
     * 200 of 221, and which 221st went missing was the database's choice
     * because the page was not even sorted. A tool whose whole job is to say
     * how big a thing is has to be right about it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun labels(scope: OrknuxScope): String =
        mapper.writeValueAsString(
            mapOf(
                "labels" to issues.labelCounts(scope.workspaceId)
                    .map { mapOf("label" to it.label, "issues" to it.issues) },
            ),
        )

    /**
     * The kinds of thing this workspace files, with how many issues carry each.
     *
     * Its own tool for the reason `orknux_issue_labels` is one, and rather more
     * so: a label can be invented on the spot, and a type cannot. An assistant
     * that wants to file a bug has to know that this workspace calls it `bug`
     * and not `defect`, and the only alternative to telling it is a refusal
     * after the fact - which costs a round trip and, more often, an issue filed
     * untyped because the model gave up on the field.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun types(scope: OrknuxScope): String {
        val carried = issues.typeCounts(scope.workspaceId).associate { it.typeId to it.issues }
        return mapper.writeValueAsString(
            mapOf(
                "types" to types.findByWorkspaceIdOrderByNameAsc(scope.workspaceId)
                    .map { mapOf("type" to it.name, "issues" to (carried[it.id] ?: 0L)) },
            ),
        )
    }

    /** The type this workspace calls that, however it was capitalised. */
    private fun typeNamed(scope: OrknuxScope, name: String): IssueType? =
        types.named(scope.workspaceId, name)

    /**
     * What to say about a type nothing here is called.
     *
     * The known ones are in the sentence, because whoever reads it wrote a word
     * and can write another - a refusal that only says no costs a call to
     * `orknux_issue_types` that the refusal could have saved.
     */
    private fun unknownType(scope: OrknuxScope, asked: String): String =
        IssueTypeUnknownException(
            asked,
            types.findByWorkspaceIdOrderByNameAsc(scope.workspaceId).map { it.name },
        ).message.orEmpty()

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    fun one(scope: OrknuxScope, arguments: String): String {
        val held = issueIn(scope, arguments) ?: return refuse("Which issue? Give its number.")
        return mapper.writeValueAsString(
            mapOf(
                "number" to held.number,
                "title" to held.title,
                "description" to held.description,
                "status" to held.status,
                "type" to held.type?.name,
                "reporter" to held.reporter,
                "assignee" to nameOf(held),
                // Worth reading before writing a comment: it says who the next
                // thing said here will actually reach.
                "observers" to observerNames(held),
                "labels" to held.labels.sorted(),
                // The first thing worth knowing about work before starting it,
                // and the one thing here nothing else says: an issue that is
                // blocked or is a duplicate should not be picked up, and an
                // assistant reading a title and a description alone has no way
                // to find that out.
                "links" to linksOn(held),
                "comments" to held.comments.map {
                    mapOf("author" to it.author, "said" to it.content, "at" to it.createdAt.toString())
                },
                "url" to issueLink(scope.workspaceId, held.number),
            ),
        )
    }

    /**
     * Opening one.
     *
     * The tools could read, comment, relabel and close, and not file - so an
     * assistant that found something had to describe it in a conversation and
     * hope somebody wrote it down. Which is the failure the tracker exists to
     * prevent, one layer up.
     *
     * Filed under whoever is asking, and assigned to nobody by default:
     * deciding who should look at a thing is somebody else's judgement, and an
     * assistant that assigned its own findings to a person would be handing
     * out work.
     *
     * Observed by somebody, though, and that is the other half of the same
     * thought. Assigning is handing out work; observing is saying "this exists,
     * you should see it", which is precisely what an assistant that has found
     * something is entitled to say. An issue filed with neither reached an
     * audience of one - the assistant itself, whose own doing is not news to it
     * - so ten careful reports about security went to nobody at all, and the
     * silence was how anybody found out.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun open(scope: OrknuxScope, arguments: String): String {
        if (!scope.mayWrite) return refuse("This conversation may read issues, but not open them")
        val title = text(arguments, "title")?.trim() ?: return refuse("What should the issue be called?")
        tooLong("title", title, TITLE)?.let { return refuse(it) }

        val labels = text(arguments, "labels")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toMutableSet()
            ?: mutableSetOf()
        labelsTooLong(labels)?.let { return refuse(it) }

        /*
         * The whole of what #241 asked for on this side: an assistant filing a
         * bug should be able to say it is one. Absent is untyped, which stays a
         * real state - a tool that guessed would be filing something as a bug
         * because the model said nothing.
         */
        val type = text(arguments, "type")?.trim()?.takeIf { it.isNotEmpty() }?.let {
            typeNamed(scope, it) ?: return refuse(unknownType(scope, it))
        }

        val named = text(arguments, "observers")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val found = named.map { it to observerNamed(scope, it) }
        val missing = found.filter { it.second == null }.map { it.first }
        /*
         * Names that match nothing are reported rather than filed around. A
         * report that says it went to somebody who was never told is worse than
         * one that says it went nowhere, because only the second gets fixed.
         */
        if (missing.isNotEmpty()) {
            return refuse("There is nobody called ${missing.joinToString(", ")} in this workspace to observe an issue")
        }
        val watching = found.mapNotNull { it.second }.ifEmpty { administrators(scope) }

        val made = issues.save(
            Issue(
                workspaceId = scope.workspaceId,
                number = issues.lastNumber(scope.workspaceId) + 1,
                title = title,
                description = text(arguments, "description")?.trim(),
                status = statuses.initialOf(scope.workspaceId),
                type = type,
                reporter = currentUser(scope),
                labels = labels,
                lastModifiedBy = currentUser(scope),
            ),
        )
        audited(scope, scope.workspaceId, "Issue #${made.number} \"$title\" opened")
        watching.forEach {
            observers.save(
                IssueObserver(
                    issueId = requireNotNull(made.id),
                    kind = it.kind,
                    observerId = requireNotNull(it.id),
                    addedBy = currentUser(scope),
                ),
            )
            // Said the same way `observeIssue` says it, because it is the same
            // fact. Who was put on an issue is the question the audit gets
            // asked about observers, and it has to answer whether they were
            // named on the tool call that filed it or added from the page
            // afterwards.
            audited(scope, scope.workspaceId, "Issue #${made.number}: ${it.name} is now an observer")
        }
        // Opened rather than observing: at creation the news worth having is
        // that the issue exists. "You are now an observer" is the right sentence
        // for somebody added to an issue that was already there, and that is where
        // it still gets said.
        newsDesk.opened(made, currentUser(scope))
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
        history.labelsChanged(made, emptySet(), made.labels.toSet(), currentUser(scope))

        return mapper.writeValueAsString(
            mapOf(
                "issue" to made.number,
                "title" to made.title,
                // Said back plainly, so a report that reached nobody reads as
                // one that reached nobody rather than as a success.
                "observers" to watching.map { it.name },
                "url" to issueLink(scope.workspaceId, made.number),
            ),
        )
    }

    /**
     * Somebody in this workspace by the name an assistant would write.
     *
     * People by username or by the name on their card, agents by their own -
     * one lookup over both, the way the assignee box searches both, because
     * "put Michal on this" does not come with a kind attached. Never a model:
     * observing is a statement about who reads, and nothing reads a model's
     * news.
     */
    private fun observerNamed(scope: OrknuxScope, name: String): NewsReader? {
        val person = users.findAll().firstOrNull {
            it.username.equals(name, ignoreCase = true) || it.displayName.equals(name, ignoreCase = true)
        }
        if (person != null) return NewsReader(AssigneeKind.USER, person.username, requireNotNull(person.id).toString())

        val agent = agents.findNamed(scope.workspaceId, name) ?: return null
        return NewsReader(AssigneeKind.AGENT, agent.name, requireNotNull(agent.id).toString())
    }

    /**
     * Somebody to put on an issue, by the name an assistant would write.
     *
     * The same lookup as [observerNamed] and one kind wider, which is the whole
     * difference between the two questions: observing is a statement about who
     * reads, and nothing reads a model's news, while work can perfectly well be
     * handed to a model. So this is not that method with a flag - the kinds
     * differ because the questions do.
     *
     * People first, then agents, then models, and the first match wins. A name
     * that is two things in one workspace is somebody's naming problem rather
     * than something to refuse over, and the order is the one the assignee box
     * already searches in.
     */
    private fun assigneeNamed(scope: OrknuxScope, name: String): Assignee? {
        val person = users.findAll().firstOrNull {
            it.username.equals(name, ignoreCase = true) || it.displayName.equals(name, ignoreCase = true)
        }
        if (person != null) return Assignee(AssigneeKind.USER, requireNotNull(person.id).toString())

        agents.findNamed(scope.workspaceId, name)
            ?.let { return Assignee(AssigneeKind.AGENT, requireNotNull(it.id).toString()) }

        return models.models(scope.workspaceId)
            .firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?.let { Assignee(AssigneeKind.MODEL, it.id.toString()) }
    }

    /**
     * The words that mean nobody.
     *
     * Spelled out because a blank cannot carry it: [text] reads an empty string
     * as an absent argument, and absent has to keep meaning "left alone" or
     * every update that did not mention the assignee would clear it. So
     * unassigning is a word, and it is more than one word because a model
     * writes the one it would write to a person.
     */
    private fun nobody(said: String): Boolean =
        said.equals("nobody", ignoreCase = true) ||
            said.equals("none", ignoreCase = true) ||
            said.equals("no one", ignoreCase = true) ||
            said.equals("no-one", ignoreCase = true) ||
            said.equals("unassigned", ignoreCase = true)

    /**
     * Who hears about a finding nobody was named for.
     *
     * The argument against a default is the honest one: a subscription nobody
     * asked for is a subscription somebody has to go and cancel, and a machine
     * guessing who cares is a machine deciding for people. The argument for it
     * is what actually happened. An assistant filed ten security issues,
     * assigned them to nobody because handing out work is not its judgement,
     * wrote carefully on each, and told no one who could act - and the way that
     * came to light was somebody noticing the silence. A default that can be
     * overridden by naming anybody costs an administrator one click to undo;
     * having no default cost a fortnight of reports nobody read.
     *
     * Whoever is asking is left out. Observing an issue you filed yourself
     * subscribes you to your own doing, which the news desk drops anyway.
     */
    private fun administrators(scope: OrknuxScope): List<NewsReader> =
        users.findAll()
            .filter { person -> person.roles.any { it.administers } }
            .filterNot { it.username.equals(currentUser(scope), ignoreCase = true) }
            .map { NewsReader(AssigneeKind.USER, it.username, requireNotNull(it.id).toString()) }

    /**
     * Saying something on an issue, under whoever is asking.
     *
     * A token carries its owner, so a comment written through this is signed
     * by a real name and lands where everybody else is already reading -
     * rather than in a chat window nobody else can see.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun comment(scope: OrknuxScope, arguments: String): String {
        if (!scope.mayWrite) return refuse("This conversation may read issues, but not write on them")
        val held = issueIn(scope, arguments) ?: return refuse("Which issue? Give its number.")
        val said = text(arguments, "content") ?: return refuse("What should the comment say?")

        held.comments.add(IssueComment(author = currentUser(scope), content = said.trim()))
        held.lastCommentAt = OffsetDateTime.now()
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = currentUser(scope)
        /*
         * Flushed rather than saved, for the reason the controller's door
         * flushes: the news has to say which comment it is about, and a comment
         * that has not been written yet has no id to say. Without one, an agent's
         * comment would be the one nobody could ever fully remove.
         */
        val saved = issues.saveAndFlush(held)
        val posted = saved.comments.maxByOrNull { requireNotNull(it.id) }
        newsDesk.commented(saved, currentUser(scope), said.trim(), commentId = posted?.id)
        return mapper.writeValueAsString(
            mapOf("said" to true, "issue" to held.number, "url" to issueLink(scope.workspaceId, held.number)),
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun setStatus(scope: OrknuxScope, arguments: String): String {
        if (!scope.mayWrite) return refuse("This conversation may read issues, but not change them")
        val held = issueIn(scope, arguments) ?: return refuse("Which issue? Give its number.")
        val asked = text(arguments, "status")
            ?: return refuse("Which status? One of ${statusKeys(scope).joinToString(", ")}.")
        /*
         * The workspace's own list, and the refusal names it. A model that sent
         * DONE to a workspace whose word is CLOSED can only fix that if it is
         * told the word, and this is the one place it is going to be told.
         */
        val wanted = try {
            statuses.resolve(scope.workspaceId, asked).key
        } catch (unknown: IssueStatusUnknownException) {
            return refuse(unknown.message.orEmpty())
        }

        val was = held.status
        val moved = held.status != wanted
        held.status = wanted
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = currentUser(scope)
        issues.save(held)
        if (moved) {
            newsDesk.statusChanged(held, currentUser(scope))
            audited(scope, scope.workspaceId, "Issue #${held.number} ${statuses.auditedAs(scope.workspaceId, wanted, was)}")
        }
        // Written here as well as in the controller, because this is the other
        // door into the tracker and a history with a hole exactly where the
        // agents worked is worse than no history at all.
        history.statusChanged(held, was, wanted, currentUser(scope))
        return mapper.writeValueAsString(mapOf("issue" to held.number, "status" to wanted))
    }

    /**
     * Removing an issue, for good. Issue #478.
     *
     * The one tool here that destroys something. Written because the door
     * existed on the screen and not through the tracker's other door, so an
     * agent that had filed a duplicate, or opened one against the wrong
     * workspace, could only ask a person to tidy up after it.
     *
     * Deliberately unlike closing: closing is a statement about the work, and
     * this says the issue should never have been written down. So it names what
     * it is about to remove in the answer - the number and the title - because
     * an agent that deleted the wrong one has nothing else to tell anybody, and
     * the audit line is written the way every other door writes one.
     *
     * The attachments' bytes go with the rows, as they do in the controller: an
     * issue tracker that leaves its screenshots behind fills a disk with files
     * belonging to issues nobody can name.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun delete(scope: OrknuxScope, arguments: String): String {
        if (!scope.mayWrite) return refuse("This conversation may read issues, but not change them")
        val held = issueIn(scope, arguments) ?: return refuse("Which issue? Give its number.")

        val number = held.number
        val title = held.title
        attachments.findByIssueIdOrderByUploadedAtAsc(requireNotNull(held.id)).forEach { store.remove(it.location) }
        issues.delete(held)
        audited(scope, scope.workspaceId, "Issue #$number deleted")
        return mapper.writeValueAsString(mapOf("deleted" to number, "title" to title))
    }

    /**
     * Title, description, type, labels and who is on it, each left alone unless
     * given.
     *
     * Labels arrive as one comma-separated string rather than an array,
     * because "slack, timing" is what a model actually writes and insisting on
     * JSON here buys nothing. The assignee arrives the same way and for the
     * same reason - as the name somebody would say, not as a kind and an id,
     * which is a pair no assistant has and the browser only has because it
     * just drew the list to pick from.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun update(scope: OrknuxScope, arguments: String): String {
        if (!scope.mayWrite) return refuse("This conversation may read issues, but not change them")
        val held = issueIn(scope, arguments) ?: return refuse("Which issue? Give its number.")

        // Copied rather than aliased: the three ways labels change below all
        // work on the set the entity holds, and a reference to it would compare
        // the result with itself.
        val labelsWere = held.labels.toSet()

        /*
         * Checked before anything is changed, so a refusal leaves the issue as
         * it was rather than half edited. The transaction would undo it either
         * way; the entity in front of us would not.
         */
        text(arguments, "title")?.trim()?.let { given ->
            tooLong("title", given, TITLE)?.let { return refuse(it) }
        }
        listOf("labels", "add_labels").forEach { field ->
            text(arguments, field)?.let { given ->
                labelsTooLong(given.split(',').map { it.trim() }.filter { it.isNotEmpty() })
                    ?.let { return refuse(it) }
            }
        }

        /*
         * Who it is being handed to, worked out before anything is written.
         *
         * Absent leaves it alone, which is what every other field here does. A
         * name that matches nobody is refused by name rather than quietly
         * ignored: an issue silently left with the wrong person on it is the
         * failure that is never noticed, because the call answered as though it
         * had worked.
         */
        /*
         * The type, resolved before anything is written, like the assignee.
         * Absent leaves it alone; `none` or `untyped` puts it back to untyped,
         * which is a state somebody chooses and not a field they forgot; a name
         * nothing here is called is refused with the list of names that work.
         */
        val typing = text(arguments, "type")?.trim()
        val typedAs = if (typing == null || typing.isEmpty() || nobody(typing) ||
            typing.equals("untyped", ignoreCase = true)
        ) {
            null
        } else {
            typeNamed(scope, typing) ?: return refuse(unknownType(scope, typing))
        }
        val typeWas = held.type?.name

        val handing = text(arguments, "assignee")?.trim()
        val handedTo = if (handing == null || nobody(handing)) {
            null
        } else {
            assigneeNamed(scope, handing)
                ?: return refuse("There is nobody called $handing in this workspace")
        }
        val heldBy = nameOf(held)
        val was = held.assignee?.let { it.kind to it.id }

        handing?.let { held.assignee = handedTo }
        typing?.let { held.type = typedAs }
        text(arguments, "title")?.let { held.title = it.trim() }
        text(arguments, "description")?.let { given ->
            held.description = given.trim().takeIf { it.isNotEmpty() }
        }
        text(arguments, "labels")?.let { given ->
            held.labels = given.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
        }
        /*
         * Adding one and taking one away, beside replacing them all.
         *
         * Replacing was the only way, which meant reading the labels, adding
         * yours to them and writing all of them back - three steps in which
         * somebody else's label can be lost, and it will be the one that
         * mattered.
         */
        text(arguments, "add_labels")?.let { given ->
            given.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { wanted ->
                if (held.labels.none { it.equals(wanted, ignoreCase = true) }) held.labels.add(wanted)
            }
        }
        text(arguments, "remove_labels")?.let { given ->
            given.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { unwanted ->
                held.labels.removeIf { it.equals(unwanted, ignoreCase = true) }
            }
        }
        held.lastModifiedAt = OffsetDateTime.now()
        held.lastModifiedBy = currentUser(scope)
        issues.save(held)
        history.labelsChanged(held, labelsWere, held.labels.toSet(), currentUser(scope))
        // The other door writes the same line, for the reason the status does:
        // a history whose gaps line up with the work nobody watched is worse
        // than none.
        history.typeChanged(held, typeWas, held.type?.name, currentUser(scope))

        /*
         * And only if it actually moved.
         *
         * Naming the person who already has it is a thing an assistant does
         * every time it repeats itself, and a notification for that is a
         * notification whoever gets it learns to stop reading. Compared on the
         * kind and the id rather than on the name, because two people can share
         * a display name and neither of them is the other.
         */
        if (held.assignee?.let { it.kind to it.id } != was) {
            newsDesk.assigned(held, currentUser(scope))
            history.assigneeChanged(held, heldBy, nameOf(held), currentUser(scope))
            audited(
                scope,
                scope.workspaceId,
                nameOf(held)
                    ?.let { "Issue #${held.number} assigned to $it" }
                    ?: "Issue #${held.number} unassigned",
            )
        }
        return mapper.writeValueAsString(
            mapOf(
                "issue" to held.number,
                "title" to held.title,
                "type" to held.type?.name,
                "labels" to held.labels.sorted(),
                "assignee" to nameOf(held),
                "url" to issueLink(scope.workspaceId, held.number),
            ),
        )
    }

    /** By its number, which is what people say, and only in this workspace. */
    private fun issueIn(scope: OrknuxScope, arguments: String): Issue? {
        val number = number(arguments, "issue")?.toInt() ?: return null
        return issues.findByWorkspaceIdAndNumber(scope.workspaceId, number)
    }

    /**
     * Who has it, as a name.
     *
     * Resolved on every read, like everywhere else: a renamed agent reads
     * correctly afterwards and one that has been removed reads as gone rather
     * than as an id nobody recognises.
     */
    private fun nameOf(issue: Issue): String? {
        val held = issue.assignee ?: return null
        val kind = held.kind ?: return null
        val id = held.id ?: return null
        return when (kind) {
            AssigneeKind.USER -> users.findByIdOrNull(id.toLongOrNull() ?: -1)?.displayName
            AssigneeKind.AGENT -> agents.findByIdOrNull(id.toLongOrNull() ?: -1)?.name
            AssigneeKind.MODEL -> models.models(issue.workspaceId).firstOrNull { it.id.toString() == id }?.name
        }
    }

    /**
     * Who is watching this one, as names.
     *
     * Resolved on every read, like the assignee: somebody who has been removed
     * is dropped rather than read as an id nobody recognises.
     */
    private fun observerNames(issue: Issue): List<String> {
        val id = issue.id ?: return emptyList()
        return observers.findByIssueIdOrderByAddedAtAscIdAsc(id).mapNotNull { watching ->
            when (watching.kind) {
                AssigneeKind.USER -> users.findByIdOrNull(watching.observerId.toLongOrNull() ?: -1)?.displayName
                AssigneeKind.AGENT -> agents.findByIdOrNull(watching.observerId.toLongOrNull() ?: -1)?.name
                AssigneeKind.MODEL -> null
            }
        }
    }

    /**
     * What this issue has to do with others, read from its side.
     *
     * A sentence apiece rather than a kind and an id, because what reads this is
     * something that has to decide with it: "is blocked by #4" is the answer to
     * "should I start this", where a pair of fields is a thing to interpret.
     * The number and the title together, so a decision does not need a second
     * call to find out what #4 even is.
     *
     * Read only. Linking two issues is a claim about both of them and about
     * whose work waits on whose, which is the same judgement as handing out
     * work - the one thing [open] deliberately does not do.
     */
    private fun linksOn(issue: Issue): List<Map<String, Any?>> {
        val id = issue.id ?: return emptyList()
        return relations.touching(id).mapNotNull { link ->
            val (kind, otherId) = IssueRelations.seenFrom(link, id)
            val other = issues.findByIdOrNull(otherId) ?: return@mapNotNull null
            mapOf(
                "what" to "${kind.reads} #${other.number}",
                "issue" to other.number,
                "title" to other.title,
                "status" to other.status,
                "url" to issueLink(other.workspaceId, other.number),
            )
        }
    }

    /**
     * Whoever is asking.
     *
     * Three doors, three answers. A token on the MCP endpoint carries its owner,
     * so that is a real username and it wins - a name passed in a scope must
     * never be able to sign somebody else's name to a change. An agent working a
     * task comes through in-process with no session at all, and [OrknuxScope.actor]
     * is the agent's own name: issue #230, where every comment an agent left and
     * every status it moved was filed as "orknux", so an issue's history could
     * not say which agent had done the work or even that an agent had.
     *
     * The product's own name is what is left when neither applies, which is
     * something calling these tools with no principal and no agent behind it.
     */
    private fun currentUser(scope: OrknuxScope): String =
        SecurityContextHolder.getContext().authentication?.name ?: scope.actor ?: "orknux"

    /**
     * The same audit entry the controller writes, from the other door.
     *
     * Worded to the letter of what `IssueAPI` writes, because the audit log is
     * read as one list and searched as one list: "Issue #12 closed" filed by an
     * agent has to sort beside "Issue #12 closed" filed from the page, and a
     * second phrasing here would mean a search for one of them finds half the
     * closures. The category is `WORKSPACE` for the same reason - it is where
     * every issue entry already lives.
     *
     * `recordAutomated` rather than `record`, and the difference is not
     * cosmetic. `record` reads the security context itself and *throws* when
     * nobody is authenticated - which is the ordinary case on one of the three
     * ways in here: an agent's tool call inside a workflow that a schedule
     * started has no web request behind it and no principal. Under `record`
     * that agent's `open` would come back to the model as a failure after the
     * issue was already saved. The actor is passed instead, from the same
     * `currentUser` that fills `reporter` and `lastModifiedBy`, so the audit
     * names whoever the issue itself names - a real username where a token
     * carried one, and `orknux` where the platform acted on its own.
     */
    private fun audited(scope: OrknuxScope, workspaceId: Long, message: String) {
        audit.recordAutomated(workspaceId, WorkspaceAuditCategory.WORKSPACE, message, currentUser(scope))
    }

}
