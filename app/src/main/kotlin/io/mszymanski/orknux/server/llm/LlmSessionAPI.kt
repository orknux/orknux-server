package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.security.WorkspaceAccess
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import java.time.format.DateTimeFormatter

/**
 * What a list of sessions is ordered by.
 *
 * Three, which are the three questions asked of a list nobody can create rows
 * in: what has been talked to lately, what is this one called, and how far back
 * does it go.
 */
enum class LlmSessionOrder {
    KEY,
    CREATED,
    LAST_EVENT,
}

/**
 * What a transcript is ordered by.
 *
 * Time, which is what a transcript is, and kind, which is how somebody reads
 * all the tool calls at once without losing the ones between them. There is no
 * third: an actor sort is a filter written as an order, and the search already
 * matches the actor.
 */
enum class LlmSessionEventOrder {
    AT,
    KIND,
}

/**
 * Reading sessions back.
 *
 * Every query here reads and none writes, which is the shape the feature asks
 * for: a session is opened by an agent going to work, never by somebody
 * pressing a button, so there is nothing to create and nothing to edit. What
 * the interface needs is to find one and to read it, and both of those are
 * paged and searched on the server - a page that filtered the twenty rows it
 * had would be filtering the page rather than the workspace, which looks like
 * it worked until what somebody wanted turns out to be further down.
 *
 * Visible to whoever can see the workspace, like the agents whose conversations
 * these are. A session's transcript is asked for by its own id, so the check
 * happens on the session's workspace rather than on an id the caller supplies -
 * otherwise anybody could read any transcript by naming a workspace they can
 * see.
 */
@Controller
class LlmSessionAPI(
    private val sessions: LlmSessionRepository,
    private val events: LlmSessionEventRepository,
    private val access: WorkspaceAccess,
    /** Whether this installation lets a conversation be thrown away. */
    private val settings: io.mszymanski.orknux.server.attachment.InstallationSettings,
    /** What the agents in a conversation wrote down for themselves. Issue #371. */
    private val notes: LlmSessionNoteRepository,
    /** For reading the agent-details snapshot back into a shape. Issue #391. */
    private val mapper: tools.jackson.databind.ObjectMapper,
    /** The session's working files, listed and read beside its transcript. Issue #429. */
    private val pads: SessionScratchpadService,
    /** The runs that wrote into a session, read back from execution_step.session_id. Issue #420. */
    private val executions: io.mszymanski.orknux.workflow.execution.ExecutionService,
) {

    @QueryMapping
    @Transactional(readOnly = true)
    fun llmSessions(
        @Argument workspaceId: Long,
        @Argument search: String?,
        @Argument page: Int?,
        @Argument size: Int?,
        @Argument order: LlmSessionOrder?,
        @Argument ascending: Boolean?,
        @Argument includeSubagents: Boolean?,
    ): LlmSessionPageView {
        access.requireVisible(workspaceId)

        val by = when (order ?: LlmSessionOrder.LAST_EVENT) {
            LlmSessionOrder.KEY -> "sessionKey"
            LlmSessionOrder.CREATED -> "createdAt"
            LlmSessionOrder.LAST_EVENT -> "lastEventAt"
        }
        val direction = if (ascending == true) Sort.Direction.ASC else Sort.Direction.DESC
        val sorted = Sort.by(
            Sort.Order(direction, by)
                /*
                 * Case is ignored for the key and nowhere else. By the words
                 * rather than by their case is what "sort by name" means, and
                 * asking Postgres to lower() a timestamp is a function that does
                 * not exist - the query fails rather than sorting badly.
                 */
                .let { if (by == "sessionKey") it.ignoreCase() else it }
                /*
                 * A session opened and not yet written to has no last event, and
                 * Postgres sorts nulls first descending - which would open the
                 * list with every session that has nothing in it.
                 */
                .let { if (by == "lastEventAt") it.nullsLast() else it },
        )

        val asked = PageRequest.of((page ?: 0).coerceAtLeast(0), (size ?: PAGE).coerceIn(1, BIGGEST_PAGE), sorted)
        val found = sessions.search(workspaceId, search?.trim().orEmpty(), includeSubagents == true, asked)
        val ids = found.content.mapNotNull { it.id }
        val counts = countsFor(ids)
        // The status dot and the subagent count, each in one query for the whole
        // page rather than one per row. Issues #403, #404.
        val unfinished = if (ids.isEmpty()) emptySet() else events.unfinishedAmong(ids).toSet()
        val subagents = if (ids.isEmpty()) emptyMap() else
            sessions.subagentCountsFor(ids).associate { it.sessionId to it.total.toInt() }
        return LlmSessionPageView(
            totalElements = found.totalElements.toInt(),
            content = found.content.map {
                describe(it, counts[it.id] ?: 0, active(it, unfinished.contains(it.id)), subagents[it.id] ?: 0)
            },
        )
    }

    /**
     * Whether an agent is at work in it right now, the rule the family panel
     * uses: a line still going, or one written within the last minute. Issue
     * #404.
     */
    private fun active(session: LlmSession, unfinished: Boolean): Boolean =
        unfinished ||
            session.lastEventAt?.isAfter(java.time.OffsetDateTime.now().minusSeconds(ACTIVE_WINDOW_SECONDS)) == true

    /** One session, by its row id - which is what the list handed the page. */
    @QueryMapping
    @Transactional(readOnly = true)
    fun llmSession(@Argument id: Long): LlmSessionView? {
        val session = sessions.findByIdOrNull(id) ?: return null
        if (!access.canSee(session.workspaceId)) return null
        val unfinished = events.unfinished(id, Long.MAX_VALUE, PageRequest.of(0, 1)).isNotEmpty()
        val subagents = sessions.subagentCountsFor(listOf(id)).firstOrNull()?.total?.toInt() ?: 0
        return describe(session, events.countBySessionId(id).toInt(), active(session, unfinished), subagents)
    }

    /**
     * The workflow run or runs that wrote into this session. Issue #420.
     *
     * The reverse of what a run records: an agent step files which session it
     * talked into, and this reads a session back to the runs that produced it.
     * Usually one; several where more than one run computed the same session
     * key. Empty for a session nothing wrote into, such as a chat - so the page
     * draws nothing rather than an empty control.
     *
     * Checked on the session's own workspace, like the transcript beside it: the
     * access check is on the row rather than an id the caller names, so naming a
     * workspace one can see does not open another's runs.
     */
    @QueryMapping
    @Transactional(readOnly = true)
    fun sessionExecutions(
        @Argument sessionId: Long,
    ): List<io.mszymanski.orknux.workflow.execution.SessionExecutionLink> {
        val session = sessions.findByIdOrNull(sessionId) ?: throw LlmSessionNotFoundException(sessionId)
        access.requireVisible(session.workspaceId)
        return executions.executionsForSession(sessionId)
    }

    /**
     * Throws a whole conversation away.
     *
     * The one thing anybody may do to a session besides read it. Nothing can
     * create one - a session appears because a run computed its key - so this
     * is not the other half of a create, it is a way to be rid of a
     * conversation that should not have been kept: a key someone mistyped, a
     * transcript of a run they were only trying out.
     *
     * The events go with it. They are a session's contents rather than
     * something in their own right, and the row's foreign key already says so.
     *
     * Answers true when there was one to remove and false when there was not,
     * rather than raising - a second press of a delete button is somebody
     * making sure, not an error worth a red box. A session in a workspace this
     * caller cannot see is one that, to them, is not there.
     */
    @MutationMapping
    @Transactional
    fun removeLlmSession(@Argument id: Long): Boolean {
        /*
         * Refused where the installation has closed the door, and refused in
         * words rather than answered false: false already means "there was no
         * such session", and somebody who may not do this needs to be told that
         * rather than left thinking the conversation had already gone.
         *
         * Asked before the session is looked up, so an installation that does
         * not allow this cannot be used to find out which ids exist.
         */
        if (!settings.sessionsRemovable()) throw SessionsNotRemovableException()

        val session = sessions.findByIdOrNull(id) ?: return false
        if (!access.canSee(session.workspaceId)) return false
        access.requireVisible(session.workspaceId)
        events.deleteBySessionId(id)
        sessions.delete(session)
        return true
    }

    /**
     * One session's transcript, oldest first by default.
     *
     * Oldest first because a transcript is read as a conversation and the first
     * thing in it is the question. Paged rather than whole: a session that has
     * been running for a fortnight holds more than a page can draw, and this is
     * the query the detail view's search, filter and sort all go through.
     */
    @QueryMapping
    @Transactional(readOnly = true)
    fun llmSessionEvents(
        @Argument sessionId: Long,
        @Argument search: String?,
        @Argument kinds: List<LlmSessionEventKind>?,
        @Argument page: Int?,
        @Argument size: Int?,
        @Argument order: LlmSessionEventOrder?,
        @Argument ascending: Boolean?,
    ): LlmSessionEventPageView {
        val session = sessions.findByIdOrNull(sessionId) ?: throw LlmSessionNotFoundException(sessionId)
        access.requireVisible(session.workspaceId)

        /*
         * Newest first unless somebody asks otherwise.
         *
         * A transcript is read to find out what just happened - the last tool
         * call, the answer, the refusal - and on a session of any length that
         * meant paging to the end before the reading could start. The order it
         * happened in is still one press away, and is what somebody wants when
         * they are following a turn through rather than looking at its result.
         */
        val direction = if (ascending == true) Sort.Direction.ASC else Sort.Direction.DESC
        /*
         * The id is always the last word, whichever the order. A turn writes its
         * question, its tool calls and its answer inside the same millisecond,
         * so time alone leaves them in whatever order the database felt like -
         * and a transcript that reorders itself between two reads is one nobody
         * trusts.
         */
        val sorted = when (order ?: LlmSessionEventOrder.AT) {
            LlmSessionEventOrder.AT -> Sort.by(Sort.Order(direction, "at"), Sort.Order(direction, "id"))
            LlmSessionEventOrder.KIND -> Sort.by(
                Sort.Order(direction, "kind"),
                Sort.Order(Sort.Direction.ASC, "at"),
                Sort.Order(Sort.Direction.ASC, "id"),
            )
        }

        val asked = PageRequest.of((page ?: 0).coerceAtLeast(0), (size ?: PAGE).coerceIn(1, BIGGEST_PAGE), sorted)
        val wanted = search?.trim().orEmpty()
        // No kinds is no filter rather than a filter matching nothing: a page
        // that has cleared every checkbox is asking for everything.
        val found = if (kinds.isNullOrEmpty()) {
            events.search(sessionId, wanted, asked)
        } else {
            events.searchByKinds(sessionId, kinds, wanted, asked)
        }
        return LlmSessionEventPageView(found.totalElements.toInt(), found.content.map(::describe))
    }

    /** How many events each of these holds, in one query rather than one each. */
    private fun countsFor(ids: List<Long>): Map<Long, Int> {
        if (ids.isEmpty()) return emptyMap()
        return events.countsFor(ids).associate { it.sessionId to it.total.toInt() }
    }

    private fun describe(session: LlmSession, eventCount: Int, active: Boolean, subagentCount: Int) = LlmSessionView(
        id = requireNotNull(session.id),
        workspaceId = session.workspaceId,
        key = session.sessionKey,
        keyPrefix = session.keyPrefix,
        eventCount = eventCount,
        createdAt = session.createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        lastEventAt = session.lastEventAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        title = session.title,
        parentId = session.parentSessionId,
        active = active,
        subagentCount = subagentCount,
        agentDetails = session.agentDetails,
    )

    /**
     * A session's family: the main one and every session started from it, in
     * the order they were started. Issue #379.
     *
     * Asked of any member and answered from the top, so a page showing a
     * subagent's transcript lists the same family the main one does. One level:
     * an agent asked by an agent that was asked is listed under the main
     * session too, because that is the one a person opened.
     */
    @QueryMapping
    @Transactional(readOnly = true)
    fun llmSessionFamily(@Argument id: Long): List<LlmSessionMemberView> {
        val asked = sessions.findByIdOrNull(id)?.takeIf { access.canSee(it.workspaceId) }
            ?: throw LlmSessionNotFoundException(id)
        val root = generateSequence(asked) { held -> held.parentSessionId?.let { sessions.findByIdOrNull(it) } }
            .take(FAMILY_DEPTH)
            .last()
        return listOf(member(root, main = true, depth = 0)) +
            descendants(requireNotNull(root.id), depth = 1).map { (child, depth) -> member(child, main = false, depth = depth) }
    }

    /** Each session under this one, with how deep it sits - so the panel can nest them. Issue #379. */
    private fun descendants(parent: Long, depth: Int): List<Pair<LlmSession, Int>> {
        if (depth > FAMILY_DEPTH) return emptyList()
        return sessions.findByParentSessionIdOrderByCreatedAtAscIdAsc(parent).flatMap { child ->
            listOf(child to depth) + descendants(requireNotNull(child.id), depth + 1)
        }
    }

    /**
     * Whether an agent is at work in it right now: something started and not
     * finished - a tool called with no result yet, a thought still being
     * thought - or a line written within the last minute. The dot on the list.
     */
    private fun member(session: LlmSession, main: Boolean, depth: Int): LlmSessionMemberView {
        val id = requireNotNull(session.id)
        val unfinished = events.unfinished(id, Long.MAX_VALUE, org.springframework.data.domain.PageRequest.of(0, 1)).isNotEmpty()
        val recent = session.lastEventAt?.isAfter(java.time.OffsetDateTime.now().minusSeconds(ACTIVE_WINDOW_SECONDS)) == true
        return LlmSessionMemberView(
            id = id,
            key = session.sessionKey,
            title = if (main) "Main session" else (session.title ?: session.sessionKey),
            main = main,
            depth = depth,
            active = unfinished || recent,
            lastEventAt = session.lastEventAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        )
    }

    /**
     * What one conversation's agents wrote down. Issue #371.
     *
     * Resolved on the field rather than built with the rest of the session,
     * because the same view draws a page of the list: twenty rows scanned by
     * name and date would each fetch notes nothing on that screen shows. Here it
     * runs only where the field is asked for, which is one opened session, and a
     * session holds at most a score of them.
     */
    @SchemaMapping(typeName = "LlmSession")
    fun notes(session: LlmSessionView): List<LlmSessionNoteView> =
        notes.findBySessionIdOrderByWrittenAtAscIdAsc(session.id).map {
            LlmSessionNoteView(
                id = requireNotNull(it.id),
                note = it.note,
                writtenBy = it.writtenBy,
                writtenAt = it.writtenAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            )
        }

    /**
     * The setup of the agent that last started answering here, read back from
     * the JSON it was kept as. Null where none was recorded, or the record
     * cannot be read. Issues #391, #441.
     */
    @SchemaMapping(typeName = "LlmSession")
    fun agentDetails(session: LlmSessionView): SessionAgentDetailsView? =
        session.agentDetails?.let(::agentDetailsOf)

    /**
     * One snapshot, resolved into the shape the log draws. Shared by the
     * session's latest and the AGENT_DETAILS line, so the two cannot read the
     * same JSON two ways. Null where the record cannot be read. Issue #441.
     */
    private fun agentDetailsOf(held: String): SessionAgentDetailsView? =
        runCatching {
            val node = mapper.readTree(held)
            SessionAgentDetailsView(
                agent = node.path("agent").stringValue().orEmpty(),
                model = node.path("model").takeIf { it.isTextual }?.stringValue(),
                systemPrompt = node.path("systemPrompt").takeIf { it.isTextual }?.stringValue(),
                tools = node.path("tools").mapNotNull { it.stringValue() },
                // Absent from a snapshot written before #446, which reads as none.
                findable = node.path("findable").mapNotNull { it.stringValue() },
                skills = node.path("skills").mapNotNull { it.stringValue() },
                memory = node.path("memory").mapNotNull { it.stringValue() },
                connections = node.path("connections").mapNotNull { it.stringValue() },
            )
        }.getOrNull()

    /**
     * One session's scratchpads: the working files an agent kept within the
     * conversation. Issue #429.
     *
     * Its own, and the shared ones of the sessions it was started under - the
     * same list the agent's own tools see, so the page shows the files the
     * agent works on. Named and sized without the content, because a list is
     * scanned rather than read and a pad can be a whole document; the content is
     * asked for one at a time with [sessionScratchpad].
     *
     * Visible to whoever can see the workspace, like the transcript beside it:
     * the check is on the session's own workspace rather than an id the caller
     * names, so naming a workspace one can see does not open another's files.
     */
    @QueryMapping
    @Transactional(readOnly = true)
    fun sessionScratchpads(@Argument sessionId: Long): List<SessionScratchpadView> {
        val session = sessions.findByIdOrNull(sessionId) ?: throw LlmSessionNotFoundException(sessionId)
        access.requireVisible(session.workspaceId)
        return pads.list(sessionId).map { scratchpadView(it, sessionId) }
    }

    /**
     * One scratchpad opened, with the document itself. Null where this session
     * has none by that name. Issue #429.
     */
    @QueryMapping
    @Transactional(readOnly = true)
    fun sessionScratchpad(@Argument sessionId: Long, @Argument name: String): SessionScratchpadContentView? {
        val session = sessions.findByIdOrNull(sessionId) ?: throw LlmSessionNotFoundException(sessionId)
        access.requireVisible(session.workspaceId)
        val pad = pads.find(sessionId, name) ?: return null
        return scratchpadContent(pad, sessionId)
    }

    /**
     * Makes a new scratchpad in this session. Issue #429.
     *
     * Refused in words - a name taken here, one over the byte budget - so the
     * form asking can show why rather than a correlation id.
     */
    @MutationMapping
    @Transactional
    fun createSessionScratchpad(
        @Argument sessionId: Long,
        @Argument name: String,
        @Argument description: String?,
        @Argument content: String?,
    ): SessionScratchpadContentView {
        val session = sessions.findByIdOrNull(sessionId) ?: throw LlmSessionNotFoundException(sessionId)
        access.requireVisible(session.workspaceId)
        return when (val result = pads.create(sessionId, name, description, content.orEmpty())) {
            is ScratchpadResult.Ok -> scratchpadContent(result.pad, sessionId)
            is ScratchpadResult.No -> throw ScratchpadRefusedException(result.why)
        }
    }

    /**
     * Replaces a scratchpad's whole content, the way an agent's write tool does
     * and against the same byte budget. Issue #429.
     */
    @MutationMapping
    @Transactional
    fun writeSessionScratchpad(
        @Argument sessionId: Long,
        @Argument name: String,
        @Argument content: String,
    ): SessionScratchpadContentView {
        val session = sessions.findByIdOrNull(sessionId) ?: throw LlmSessionNotFoundException(sessionId)
        access.requireVisible(session.workspaceId)
        return when (val result = pads.write(sessionId, name, content)) {
            is ScratchpadResult.Ok -> scratchpadContent(result.pad, sessionId)
            is ScratchpadResult.No -> throw ScratchpadRefusedException(result.why)
        }
    }

    /**
     * Removes one of a session's scratchpads. Only the owner may; an inherited
     * one is refused in words. Answers true when it went. Issue #429.
     */
    @MutationMapping
    @Transactional
    fun deleteSessionScratchpad(@Argument sessionId: Long, @Argument name: String): Boolean {
        val session = sessions.findByIdOrNull(sessionId) ?: throw LlmSessionNotFoundException(sessionId)
        access.requireVisible(session.workspaceId)
        return when (val result = pads.delete(sessionId, name)) {
            is ScratchpadResult.Ok -> true
            is ScratchpadResult.No -> throw ScratchpadRefusedException(result.why)
        }
    }

    private fun scratchpadView(pad: SessionScratchpad, viewer: Long) = SessionScratchpadView(
        name = pad.name,
        description = pad.description,
        bytes = pad.bytes,
        shared = pad.shared,
        ownedHere = pad.sessionId == viewer,
    )

    private fun scratchpadContent(pad: SessionScratchpad, viewer: Long) = SessionScratchpadContentView(
        name = pad.name,
        description = pad.description,
        content = pad.content,
        bytes = pad.bytes,
        shared = pad.shared,
        ownedHere = pad.sessionId == viewer,
    )

    private fun describe(event: LlmSessionEvent) = LlmSessionEventView(
        id = requireNotNull(event.id),
        kind = event.kind,
        actor = event.actor,
        content = event.content,
        result = event.result,
        millis = event.millis,
        at = event.at.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        // The snapshot an AGENT_DETAILS line carries, resolved for the page the
        // way the session's latest is; null on every other kind. Issue #441.
        agentDetails = event.content?.takeIf { event.kind == LlmSessionEventKind.AGENT_DETAILS }?.let(::agentDetailsOf),
    )

    private companion object {
        /** Matches the default declared on the paged queries in the schema. */
        const val PAGE = 20

        const val BIGGEST_PAGE = 100

        /** How far up and down a family is followed; an agent asking an agent asking an agent is plenty. */
        const val FAMILY_DEPTH = 5

        /** A line written this recently means somebody is still there. */
        const val ACTIVE_WINDOW_SECONDS = 60L
    }
}

/** One session of a family, as the panel on the session page lists it. Issue #379. */
data class LlmSessionMemberView(
    val id: Long,
    val key: String,
    /** "Main session" for the one at the top; what the asking agent called the task for the rest. */
    val title: String,
    val main: Boolean,
    /** How deep under the main session it sits: 0 for the main, 1 for what it asked, and so on. Issue #379. */
    val depth: Int,
    /** Green or orange: whether an agent is at work in it right now. */
    val active: Boolean,
    val lastEventAt: String?,
)

data class LlmSessionView(
    val id: Long,
    val workspaceId: Long,
    /** The composed identity - what the runner arrived at, not its halves. */
    val key: String,
    /** The prefix it was composed from, or null where there was none. */
    val keyPrefix: String?,
    /** How many lines it holds, which is what a list of transcripts is scanned by. */
    val eventCount: Int,
    val createdAt: String,
    /** Null on a session that has been opened and not yet written to. */
    val lastEventAt: String?,
    /** What the agent that started this session called the task; null where nobody did. Issue #379. */
    val title: String?,
    /** The session this one was started from, or null. */
    val parentId: Long?,
    /** Whether an agent is at work in it right now: something unfinished, or a line within the last minute. The list's status dot. Issue #404. */
    val active: Boolean,
    /** How many sessions were started under this one - what the list shows to say which conversations fanned out. Issue #403. */
    val subagentCount: Int,
    /** The setup of the agent that last started answering, as JSON; resolved into a shape by the field. Null on a session no agent wrote into. Issues #391, #441. */
    val agentDetails: String?,
)

/**
 * One thing an agent wrote down for itself; see [LlmSessionNote].
 *
 * Reached by asking an opened session for `notes`, which [LlmSessionAPI.notes]
 * answers. Shown because it is the one part of a conversation an agent chose to
 * keep rather than merely said: a transcript is what happened, and these are the
 * few lines it decided it must not lose. Somebody reading a session to work out
 * what an agent was doing wants them first.
 */
data class LlmSessionNoteView(
    val id: Long,
    val note: String,
    /** Which agent wrote it, since a conversation can be shared. */
    val writtenBy: String,
    val writtenAt: String,
)

/**
 * An agent's setup as it stood when it started answering, read back for the
 * log - the payload of an AGENT_DETAILS line, and the session's latest. Issues
 * #391, #441.
 */
data class SessionAgentDetailsView(
    val agent: String,
    val model: String?,
    val systemPrompt: String?,
    /** Every tool declared to the model on every turn, built-ins and lent ones included. Issue #446. */
    val tools: List<String>,
    /** The tools it finds rather than carries, where it has a ceiling of its own; empty otherwise. */
    val findable: List<String>,
    val skills: List<String>,
    val memory: List<String>,
    val connections: List<String>,
)

/**
 * One of a session's scratchpads, listed beside the transcript. Issue #429.
 *
 * Named and sized without the document itself: a list is scanned rather than
 * read, and a pad can be a whole page. The content is asked for one at a time
 * with `sessionScratchpad`, which answers a [SessionScratchpadContentView].
 */
data class SessionScratchpadView(
    /** Its name within the session, which is how the agent addresses it - like a filename. */
    val name: String,
    /** What it is for, in a line, or null where none was set. */
    val description: String?,
    /** Its size in bytes, which is what the session's scratchpad budget is spent in. */
    val bytes: Int,
    /** Whether the sessions started under the owner may read and add to it. */
    val shared: Boolean,
    /** Whether this session owns it, or only inherited it shared from an ancestor - only the owner may delete it. */
    val ownedHere: Boolean,
)

/** One scratchpad opened: the listed row, with the document itself. Issue #429. */
data class SessionScratchpadContentView(
    val name: String,
    val description: String?,
    /** The document itself. */
    val content: String,
    val bytes: Int,
    val shared: Boolean,
    val ownedHere: Boolean,
)

data class LlmSessionPageView(val totalElements: Int, val content: List<LlmSessionView>)

data class LlmSessionEventView(
    val id: Long,
    val kind: LlmSessionEventKind,
    /** The agent, the tool, whoever asked, or "system". */
    val actor: String,
    /** The words, a call's arguments, or the note - whichever the kind says. */
    val content: String?,
    /**
     * What a call gave back, and null on every line that is not one.
     *
     * Null on a call too, while its tool has not answered - the call is written
     * before the tool runs, so a line with arguments and no result is a lookup
     * that was asked for and never came back.
     */
    val result: String?,
    /**
     * How long a THINKING line's reasoning went on for, and null on every other
     * kind.
     *
     * Null also means it is still arriving, on a thinking line - the duration is
     * written once, when the model stops thinking. So a page reading a session
     * after the fact can tell a block that was finished from one whose process
     * died in the middle of it, which are two different things to draw.
     */
    val millis: Long?,
    /** ISO-8601 offset date-time. */
    val at: String,
    /**
     * The agent's setup an AGENT_DETAILS line carries, resolved from its
     * content, and null on every other kind - or on a line whose record cannot
     * be read, which the page then draws as a plain line. Issue #441.
     */
    val agentDetails: SessionAgentDetailsView?,
)

data class LlmSessionEventPageView(val totalElements: Int, val content: List<LlmSessionEventView>)

/**
 * A conversation asked to be thrown away on an installation that does not allow
 * it.
 *
 * A session is the record of what an agent was asked and what it answered, and
 * on some installations that is the only account of a decision anybody has. An
 * operator can close the door; this is what says so.
 *
 * In words rather than a false, which the mutation already uses to mean "there
 * was no such session": somebody who may not do this needs to be told that
 * rather than left believing the conversation had already gone.
 */
class SessionsNotRemovableException : RuntimeException(
    "This installation does not allow conversations to be removed. " +
        "An administrator can change that under Admin, Settings.",
), Refusal {

    /** Nothing to carry: the refusal is about the installation, not the id. */
    override val arguments get() = emptyMap<String, Any?>()
}

/**
 * A scratchpad create, write or delete the service refused, in its own words.
 * Issue #429.
 *
 * The bounds live in [SessionScratchpadService], which answers a name that is
 * taken, a write over the byte budget or an inherited pad nobody here may
 * delete as a [ScratchpadResult.No] carrying a sentence. The page asking has to
 * show that sentence, so it comes back as a refusal rather than a correlation
 * id. The words are already the message; there is nothing to interpolate.
 */
class ScratchpadRefusedException(why: String) : RuntimeException(why), Refusal {

    override val arguments get() = emptyMap<String, Any?>()
}
