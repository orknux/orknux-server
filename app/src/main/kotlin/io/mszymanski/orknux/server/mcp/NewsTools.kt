package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.issue.AssigneeKind
import io.mszymanski.orknux.server.issue.IssueNewsDesk
import io.mszymanski.orknux.server.issue.IssueNewsItem
import io.mszymanski.orknux.server.issue.IssueNewsKind
import io.mszymanski.orknux.server.issue.IssueRelations
import io.mszymanski.orknux.server.issue.NewsReader
import io.mszymanski.orknux.server.security.WebProperties
import org.springframework.beans.factory.DisposableBean
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * What has happened on the issues that concern you, with the option of waiting
 * for it.
 *
 * The tracker exists so that work is discussed where everybody can read it, and
 * that only works if the people in it find out. A person has a page they can
 * look at; an assistant has nothing, so until now somebody had to say "I
 * replied on #6" by hand - which is the message the tracker was supposed to
 * replace.
 *
 * **Waiting is a parameter, not a protocol.** MCP can push, over an event
 * stream a client holds open; this server speaks plain request and answer, and
 * every client that would have to hold that stream open is instead perfectly
 * happy to hold one call open. So the call blocks until something happens or
 * the time runs out. Nothing to reconnect, nothing to resume, and it works
 * through anything that passes HTTP.
 *
 * **The call is held open; a thread is not.** The answer is a promise, kept
 * when the news arrives or when the time is up, and the caller's request is
 * answered from there. That is not tidiness. Tomcat has two hundred threads,
 * five minutes is a wait anybody may ask for, and anybody who can sign in can
 * ask for it - so a wait that cost a thread meant two hundred calls could take
 * the whole server off the air for five minutes, and the tool whose entire
 * purpose is waiting would be the way to do it. Between one wake and the next
 * a waiter holds no thread, no transaction and no database connection: it is a
 * promise on a list and nothing else.
 *
 * **Reading marks it read**, and the mark is kept here rather than handed back
 * as a cursor. An assistant restarted between sessions remembers nothing, and a
 * cursor it forgets means either a week of events repeated or a day of them
 * silently skipped.
 */
@Service
class NewsTools(
    private val desk: IssueNewsDesk,
    private val agents: AgentRepository,
    private val web: WebProperties,
    private val address: io.mszymanski.orknux.server.security.InstallationAddress,
    private val mapper: ObjectMapper,
) : DisposableBean {

    /**
     * The one thing waiting does spend: a couple of threads, shared by every
     * waiter there is, that ring the alarm when a wait is up and carry the
     * look-again that follows a wake.
     *
     * Small on purpose. Each turn on one of these is a single indexed select
     * and then back to waiting, so a handful of threads carries as many waiters
     * as the machine has memory for. Daemon, so a shutdown is not held up by
     * somebody's five minutes.
     */
    private val clock = Executors.newScheduledThreadPool(WAITERS) { work ->
        Thread(work, "issue-news").apply { isDaemon = true }
    }

    /**
     * @param arguments `wait` in seconds, and `as` to read an agent's news
     *   instead of your own.
     */
    fun news(scope: OrknuxScope, arguments: String): CompletableFuture<String> {
        val asked = mapper.readTree(arguments)
        val onBehalfOf = asked.path("as").let { if (it.isString) it.stringValue() else null }?.takeIf { it.isNotBlank() }
        val reader = readerFor(scope, onBehalfOf)
            ?: return CompletableFuture.completedFuture(
                mapper.writeValueAsString(
                    mapOf("error" to "There is nobody called $onBehalfOf in this workspace to read the news of"),
                ),
            )

        val seconds = secondsIn(asked.path("wait")).coerceIn(0, LONGEST_WAIT)
        if (seconds == 0) {
            return CompletableFuture.completedFuture(answer(reader, desk.unread(scope.workspaceId, reader, MANY), 0))
        }

        /*
         * Who is asking was read above, on the caller's own thread, because
         * that is the only thread the security context is on. Everything past
         * here works from [reader] and needs nobody.
         */
        val waiting = CompletableFuture<List<IssueNewsItem>>()
        lookAgain(scope, reader, System.currentTimeMillis() + seconds * 1000L, waiting)
        return waiting.thenApply { arrived -> answer(reader, arrived, seconds) }
    }

    /**
     * Look, and if there is nothing, arrange to be told and look again.
     *
     * The first turn runs on the caller's thread, so news already waiting is
     * answered without a hop. Every turn after one is on [clock]: a wake is a
     * promise kept from inside the writer's own commit, and the reader's
     * database work has no business happening there.
     *
     * The looking again is not decoration. Everybody waiting is woken by
     * anybody's news, so most wakes are somebody else's and the answer is to
     * look, find nothing, and wait out what is left. The alarm bounds it
     * regardless, so a wake that was somehow missed costs seconds rather than
     * the whole wait.
     */
    private fun lookAgain(
        scope: OrknuxScope,
        reader: NewsReader,
        until: Long,
        answer: CompletableFuture<List<IssueNewsItem>>,
    ) {
        val left = until - System.currentTimeMillis()
        if (left <= 0) {
            answer.complete(emptyList())
            return
        }

        // Taken before the look rather than after it, so news written while we
        // are looking rings this instead of arriving to an empty room.
        val bell = desk.nextNews()

        val arrived = try {
            desk.unread(scope.workspaceId, reader, MANY)
        } catch (failure: Exception) {
            bell.complete(false)
            answer.completeExceptionally(failure)
            return
        }
        if (arrived.isNotEmpty()) {
            bell.complete(false)
            answer.complete(arrived)
            return
        }

        val alarm = clock.schedule({ bell.complete(false) }, minOf(left, LOOK_AGAIN_MS), TimeUnit.MILLISECONDS)
        bell.whenCompleteAsync(
            { _, _ ->
                alarm.cancel(false)
                lookAgain(scope, reader, until, answer)
            },
            clock,
        )
    }

    /** The answer, in the shape a caller reads whether it waited or not. */
    private fun answer(reader: NewsReader, told: List<IssueNewsItem>, seconds: Int): String =
        mapper.writeValueAsString(
            mapOf(
                "reading" to reader.name,
                "news" to told.map(::described),
                // Said plainly, because "nothing" and "nothing yet" are
                // different answers and only one of them is worth asking again.
                "waited" to seconds,
            ),
        )

    /**
     * Whose news this is.
     *
     * Yours by default - a token carries the person it was minted for. Naming
     * an agent reads that agent's instead, which is how something acting for an
     * agent finds out that an issue has been handed to it: the agent is not a
     * client and has nobody to hold a call open on its behalf.
     */
    private fun readerFor(scope: OrknuxScope, name: String?): NewsReader? {
        if (name == null) {
            val me = SecurityContextHolder.getContext().authentication?.name ?: return null
            return NewsReader(AssigneeKind.USER, me)
        }
        val agent = agents.findNamed(scope.workspaceId, name) ?: return null
        return NewsReader(AssigneeKind.AGENT, agent.name, agent.id?.toString())
    }

    private fun described(item: IssueNewsItem): Map<String, Any?> = mapOf(
        "what" to when (item.kind) {
            IssueNewsKind.ASSIGNED -> "assigned to you"
            IssueNewsKind.STATUS ->
                if (item.says == "CLOSED") "closed" else "reopened"
            IssueNewsKind.COMMENT -> "commented"
            IssueNewsKind.MENTIONED -> "mentioned you"
            // Reads as what happened, not as what you now are: an assistant
            // told "opened" knows there is something new to look at, where
            // "made you an observer" describes a subscription and buries the
            // issue behind it.
            IssueNewsKind.OPENED -> "opened"
            IssueNewsKind.OBSERVING -> "made you an observer"
            // Read from the side of the issue this is news about, so an
            // assistant deciding what to pick up next is told the thing that
            // decides it: this one is blocked, this one is a duplicate.
            IssueNewsKind.LINKED -> IssueRelations.reading(item.says) ?: "linked"
            // Not about an issue at all. An agent observing an issue that a
            // task is working on is told the task stopped, which is the one
            // piece of news in this feed that somebody has to act on before
            // anything else happens.
            IssueNewsKind.TASK_WAITING -> "a task is waiting for an answer"
            IssueNewsKind.TASK_FINISHED -> "a task finished"
        },
        "issue" to item.issueNumber,
        "task" to item.taskId,
        "title" to (item.issueTitle ?: item.taskTitle),
        "by" to item.actor,
        "at" to item.at.toString(),
        // The comment itself, or what a parked task is asking for, so the answer
        // to "what happened" does not need a second call to be worth reading.
        "said" to item.says.takeIf {
            item.kind == IssueNewsKind.COMMENT ||
                item.kind == IssueNewsKind.MENTIONED ||
                item.kind == IssueNewsKind.TASK_WAITING
        },
        "url" to link(item),
    )

    /** A number however it was sent: models write "60" as often as 60. */
    private fun secondsIn(node: tools.jackson.databind.JsonNode): Int = when {
        node.isNumber -> node.asInt()
        node.isString -> node.stringValue().trim().toIntOrNull() ?: 0
        else -> 0
    }

    /** Where to look, which depends on which of the two subjects this is about. */
    private fun link(item: IssueNewsItem): String {
        val base = address.base().ifEmpty { web.allowedOrigins.firstOrNull()?.trimEnd('/').orEmpty() }
        return item.taskId
            ?.let { "$base/workspace/${item.workspaceId}/tasks/$it" }
            ?: "$base/workspace/${item.workspaceId}/issues/${item.issueNumber}"
    }

    override fun destroy() {
        clock.shutdownNow()
    }

    private companion object {
        /** As long as a client will hold a call open, and no longer. */
        const val LONGEST_WAIT = 300

        /** How long one wait lasts before looking again regardless. */
        const val LOOK_AGAIN_MS = 5_000L

        /** Everything waiting, in one answer: an inbox is not paginated. */
        const val MANY = 50

        /** Enough to carry every waiter there is; see [clock]. */
        const val WAITERS = 2
    }
}
