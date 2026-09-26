package io.mszymanski.orknux.server.issue

import io.mszymanski.orknux.connector.connection.MailDelivery
import io.mszymanski.orknux.connector.connection.MailMessage
import io.mszymanski.orknux.server.mail.InstallationMail
import io.mszymanski.orknux.server.security.WebProperties
import io.mszymanski.orknux.server.user.AppUserRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The thread the news is posted from, which is never the one that wrote it. */
@Configuration(proxyBeanMethods = false)
class IssueNewsMailConfig {

    /**
     * Where a notification is handed to the relay, off the thread that caused it.
     *
     * Somebody saving a comment must not wait for an SMTP round trip, and a relay
     * having a slow afternoon must not turn into a slow tracker. The same shape
     * the password reset mailer uses, for the same reasons: one thread, a bounded
     * queue, and anything past it dropped rather than queued into the heap.
     *
     * Dropping is the right answer at the far end of a flood. The bell is the
     * record of what happened and it has already been written by the time this
     * runs; a mail that never leaves costs somebody a look at the panel, while a
     * queue that grows without limit costs the process.
     */
    @Bean("issueNewsPost")
    fun issueNewsPost(): Executor = ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(QUEUED_MAILS),
        { runnable -> Thread(runnable, "issue-news-mail").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy(),
    )

    private companion object {
        /** Far more than a busy tracker produces at once, and a bounded number. */
        const val QUEUED_MAILS = 500
    }
}

/**
 * The news desk's second delivery: what the bell rings, posted as mail.
 *
 * **It decides nothing about audience.** [IssueNewsDesk] settles who hears about
 * an issue - whoever holds it, whoever filed it, whoever is observing, whoever
 * was named in a comment - and drops the person who caused it. What arrives here
 * is exactly the rows that were written, so a change to who hears about what is
 * made in one place and both deliveries follow it. Anything here that started
 * asking who should be told would be a second set of rules, and the one nobody
 * was looking at would be the one that was wrong.
 *
 * What it does decide is whether there is anywhere to post to: a mail server, a
 * base URL to build a link from, an address on the person, and their own
 * preference. Any of those missing means nothing is sent, and none of them is an
 * error - an installation with no relay configured is the default one.
 *
 * The bell is the record and this is a courtesy on top of it. Nothing here can
 * fail in a way that reaches the transaction that wrote the news: it is called
 * after that commit, from another thread, and a dead SMTP server produces a log
 * line and nothing else.
 */
@Service
class IssueNewsMailer(
    private val users: AppUserRepository,
    private val workspaces: WorkspaceRepository,
    private val mail: InstallationMail,
    private val web: WebProperties,
    @Qualifier("issueNewsPost") private val mailer: Executor,
    /** Only to say what a status a subject line names means in that workspace. */
    private val statuses: IssueStatusCatalogue,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Said once, however many comments follow.
     *
     * A base URL is a misconfiguration rather than a supported state - the
     * default points at the development interface, so an installation reaches
     * this only by emptying it - and an operator needs to be told. Once: a line
     * per comment would be a log nobody reads on a tracker somebody is using.
     */
    private val saidAboutTheBaseUrl = AtomicBoolean(false)

    /**
     * Post what the desk has just written, or decide there is nothing to post.
     *
     * Called from the thread that committed the news, so it does as little as it
     * can: the checks that answer "is this installation sending mail at all" are
     * cheap and settle it for the common case, and everything else - reading
     * addresses, building messages, opening a session - happens on the mailer's
     * own thread.
     */
    fun post(items: List<IssueNewsItem>) {
        if (items.isEmpty()) return
        if (!mail.configured) {
            // The default installation. Debug rather than warn: it is not
            // misconfigured, it simply does not send mail, and it would say this
            // about every comment anybody ever wrote.
            log.debug("{} notification(s) were not posted: this installation has no mail server", items.size)
            return
        }
        if (base() == null) {
            if (saidAboutTheBaseUrl.compareAndSet(false, true)) {
                log.warn(
                    "Issue notifications cannot be mailed because ORKNUX_BASE_URL " +
                        "(orknux.web.base-url) is empty, so there is no address to build a link " +
                        "from. Said once.",
                )
            }
            return
        }
        mailer.execute {
            // Whatever went wrong, it went wrong after the news was safely
            // written, and this thread has nobody to report it to.
            runCatching { deliver(items) }
                .onFailure { failure -> log.warn("Could not post {} issue notification(s)", items.size, failure) }
        }
    }

    private fun deliver(items: List<IssueNewsItem>) {
        val names = mutableMapOf<Long, String?>()
        for (item in worthPosting(items)) {
            val address = addressFor(item) ?: continue
            val workspace = names.getOrPut(item.workspaceId) { workspaces.findByIdOrNull(item.workspaceId)?.name }

            val message = MailMessage(
                to = listOf(address),
                subject = subject(item),
                body = body(item, workspace),
            )
            when (val delivered = mail.send(message)) {
                is MailDelivery.Sent ->
                    log.debug("Issue #{} news was posted to {}", item.issueNumber, item.audienceName)

                is MailDelivery.Refused ->
                    log.warn("Issue #{} news for {} was refused: {}", item.issueNumber, item.audienceName, delivered.reason)

                is MailDelivery.NotPossible ->
                    log.warn("Issue #{} news for {} was not posted: {}", item.issueNumber, item.audienceName, delivered.reason)
            }
        }
    }

    /**
     * One mail per thing that happened, except where two of them are the same
     * thing.
     *
     * A comment naming somebody who is already watching the issue is two rows on
     * purpose: the bell wants to show both "commented" and "mentioned you",
     * because they are different reasons to look. An inbox does not - two
     * messages about one comment, arriving together, reads as a fault. The
     * mention wins, since it is the one that says why it was sent to them.
     */
    private fun worthPosting(items: List<IssueNewsItem>): List<IssueNewsItem> {
        val mentioned = items.filter { it.kind == IssueNewsKind.MENTIONED }.map(::reading).toSet()
        return items.filterNot { it.kind == IssueNewsKind.COMMENT && reading(it) in mentioned }
    }

    /** One reader, one issue: the pair two rows about the same comment share. */
    private fun reading(item: IssueNewsItem) =
        Triple(item.audienceKind, item.audienceName.lowercase(), item.issueId)

    /**
     * Where to write to whoever this was addressed to, or nowhere.
     *
     * Nowhere for an agent or a model: they read their news through the MCP
     * tools, which is their inbox, and there is no address to invent for them.
     * Nowhere, quietly, for somebody who has turned this off, and for somebody
     * with no address at all - an administrator's internal account and the
     * bootstrap admin usually have none, and that is an ordinary state rather
     * than a fault worth a warning per comment.
     */
    private fun addressFor(item: IssueNewsItem): String? {
        if (item.audienceKind != AssigneeKind.USER) return null
        val reader = users.findByUsername(item.audienceName) ?: return null
        if (!reader.emailNotifications) return null
        return reader.email?.trim()?.ifEmpty { null }
    }

    /**
     * What shows in a list of mail, and on a lock screen.
     *
     * The actor first, because that is what somebody scans for; then what they
     * did, then the issue. It carries the issue's title and nothing else from the
     * issue - never a comment, never the description - because a subject is the
     * part that is shown without anybody choosing to look at it.
     */
    private fun subject(item: IssueNewsItem): String =
        oneLine("${happening(item)}: ${shortened(titleOf(item), LONGEST_TITLE)}")

    /** Whichever of the two subjects this row is about. */
    private fun titleOf(item: IssueNewsItem): String =
        item.issueTitle ?: item.taskTitle ?: "(untitled)"

    /**
     * A subject is one line, whatever was typed into the title.
     *
     * A header ends at a newline, so a title carrying one would end the subject
     * and start whatever came after it as a header of its own. Nothing in the
     * tracker offers a multi-line title, which is exactly why this is here: the
     * one that eventually does must not be able to write mail headers.
     */
    private fun oneLine(text: String): String = text.replace(WHITESPACE, " ").trim()

    /** What was done, to which issue, by whom - and nothing that was written. */
    private fun happening(item: IssueNewsItem): String {
        val issue = "#${item.issueNumber}"
        return when (item.kind) {
            /*
             * A task speaks for itself, so the actor is the word "task" rather
             * than a person - which is why these two read differently from every
             * other line here. What it is waiting for goes in the body: a subject
             * line is shown without anybody choosing to look at it, and a request
             * for shell access is not something to put on a lock screen.
             */
            IssueNewsKind.TASK_WAITING -> "A task is waiting for you"
            IssueNewsKind.TASK_FINISHED -> "A task has finished"

            IssueNewsKind.OPENED -> "${item.actor} opened $issue"
            IssueNewsKind.ASSIGNED -> "${item.actor} assigned you $issue"
            IssueNewsKind.STATUS -> "${item.actor} ${statusSaid(item)}"
            IssueNewsKind.COMMENT -> "${item.actor} commented on $issue"
            IssueNewsKind.MENTIONED -> "${item.actor} mentioned you on $issue"
            IssueNewsKind.OBSERVING -> "${item.actor} added you to $issue"
            // The relation is the point, not the linking: "#7 is blocked by #4"
            // is a sentence somebody can act on where "linked #7" is a sentence
            // that has to be opened to mean anything.
            IssueNewsKind.LINKED -> "${item.actor} recorded that $issue ${IssueRelations.reading(item.says)}"
        }
    }

    /**
     * The status as something somebody did, since the subject is a sentence
     * about them: "closed #4", "started #4", "reopened #4", "moved #4 to Review".
     *
     * Read off the workspace's definitions rather than off the key, so a status
     * this workspace added reads as done where it counts as done. The words the
     * audit uses are the catalogue's; these are the mail's, because a subject
     * line is about the person and an audit line is about the issue.
     */
    private fun statusSaid(item: IssueNewsItem): String {
        val issue = "#${item.issueNumber}"
        val key = item.says ?: return "moved $issue"
        val held = statuses.of(item.workspaceId)
        val to = held.firstOrNull { it.key == key }
        return when {
            to?.closed == true -> "closed $issue"
            to?.initial == true -> "reopened $issue"
            key == IssueStatuses.IN_PROGRESS -> "started $issue"
            else -> "moved $issue to ${to?.label ?: key}"
        }
    }

    /**
     * Enough to decide whether to open it, and no more.
     *
     * What happened, where, a few lines of the comment where there is one, and
     * the link. Not the thread: somebody who wants the discussion is one click
     * away from all of it, and a mail that repeats the whole issue is a mail
     * nobody finishes reading.
     */
    private fun body(item: IssueNewsItem, workspace: String?): String {
        val where = workspace?.let { " in $it" }.orEmpty()
        val said = item.says
            ?.takeIf { item.kind == IssueNewsKind.COMMENT || item.kind == IssueNewsKind.MENTIONED }
            ?.trim()
            ?.ifEmpty { null }

        return buildString {
            append(happening(item)).append(where).append(".\n\n")
            append(if (item.taskId != null) "Task: " else "Issue: ").append(titleOf(item)).append('\n')
            // What a task is waiting for, in full. A permission nobody reads is
            // a task nobody unblocks.
            item.says?.takeIf { item.kind == IssueNewsKind.TASK_WAITING }?.let {
                append('\n').append(shortened(it, LONGEST_QUOTE)).append('\n')
            }
            if (said != null) {
                append('\n')
                shortened(said, LONGEST_QUOTE).lines().forEach { append("    ").append(it).append('\n') }
            }
            append("\nOpen it: ").append(link(item)).append("\n\n")
            append(reason(item.kind)).append('\n')
            append("Turn these off under Preferences: ").append(base()).append("/preferences\n")
        }
    }

    /** Why this arrived, which is the first thing anybody asks of a notification. */
    private fun reason(kind: IssueNewsKind): String = when (kind) {
        IssueNewsKind.MENTIONED -> "You are hearing about this because your name is in the comment."
        IssueNewsKind.TASK_WAITING, IssueNewsKind.TASK_FINISHED ->
            "You are hearing about this because you asked for the task, or you are observing what it works on."
        IssueNewsKind.ASSIGNED -> "You are hearing about this because it was assigned to you."
        IssueNewsKind.OBSERVING -> "You are hearing about this because you were made an observer."
        else ->
            "You are hearing about this because the issue concerns you: you filed it, " +
                "it is yours, or you are observing it."
    }

    /**
     * Where the issue is, built from configuration and never from a request.
     *
     * The same rule the password reset link follows: a Host header is written by
     * whoever is calling, so a link built from one is a link an attacker chooses
     * the address of.
     */
    private fun link(item: IssueNewsItem): String = item.taskId
        ?.let { "${base()}/workspace/${item.workspaceId}/tasks/$it" }
        ?: "${base()}/workspace/${item.workspaceId}/issues/${item.issueNumber}"

    private fun base(): String? = web.baseUrl.trim().trimEnd('/').ifEmpty { null }

    private fun shortened(text: String, longest: Int): String =
        if (text.length <= longest) text else text.take(longest - 1).trimEnd() + "…"

    private companion object {
        /** Long enough for a real title, short enough that the subject still fits a list. */
        const val LONGEST_TITLE = 120

        /** A few lines of the comment. Whoever wants the rest has the link above it. */
        const val LONGEST_QUOTE = 500

        /** Anything a header would treat as the end of the line, and its neighbours. */
        val WHITESPACE = Regex("""\s+""")
    }
}
