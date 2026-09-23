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
        val found = sessions.search(workspaceId, search?.trim().orEmpty(), asked)
        val counts = countsFor(found.content.mapNotNull { it.id })
        return LlmSessionPageView(
            totalElements = found.totalElements.toInt(),
            content = found.content.map { describe(it, counts[it.id] ?: 0) },
        )
    }

    /** One session, by its row id - which is what the list handed the page. */
    @QueryMapping
    @Transactional(readOnly = true)
    fun llmSession(@Argument id: Long): LlmSessionView? {
        val session = sessions.findByIdOrNull(id) ?: return null
        if (!access.canSee(session.workspaceId)) return null
        return describe(session, events.countBySessionId(id).toInt())
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

    private fun describe(session: LlmSession, eventCount: Int) = LlmSessionView(
        id = requireNotNull(session.id),
        workspaceId = session.workspaceId,
        key = session.sessionKey,
        keyPrefix = session.keyPrefix,
        eventCount = eventCount,
        createdAt = session.createdAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
        lastEventAt = session.lastEventAt?.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
    )

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

    private fun describe(event: LlmSessionEvent) = LlmSessionEventView(
        id = requireNotNull(event.id),
        kind = event.kind,
        actor = event.actor,
        content = event.content,
        result = event.result,
        millis = event.millis,
        at = event.at.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
    )

    private companion object {
        /** Matches the default declared on the paged queries in the schema. */
        const val PAGE = 20

        const val BIGGEST_PAGE = 100
    }
}

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
