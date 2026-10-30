package io.mszymanski.orknux.server.usage

import io.mszymanski.orknux.connector.connection.SlackDirectory
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.user.AppUserRepository
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.slf4j.LoggerFactory
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

/** Where the person who prompted a model call is known from. */
enum class PromptSource {
    /** A Slack member, by Slack's id for them, on the connection the message came through. */
    SLACK,

    /** A person signed in to Orknux, by username: a chat, a task they started. */
    ORKNUX,

    /** Nobody: a schedule, a webhook, an API run, a workflow started by hand. */
    NONE,
}

/**
 * Who prompted the turn a model call runs in.
 *
 * A person in one system, not a human: the same human on Slack and in Orknux is
 * two prompters, because nothing here says they are one. [connectionId] is the
 * connection a Slack member's message came through, which is how their name is
 * looked up - not part of who they are: the report counts one Slack id as one
 * person whichever connection it arrived on (see [UsageReportAPI]).
 */
data class Prompter(
    val source: PromptSource,
    val key: String,
    val connectionId: Long? = null,
    /** What to call them, where the caller already knows; a Slack member's is looked up. */
    val name: String? = null,
) {
    companion object {
        val NOBODY = Prompter(PromptSource.NONE, "")

        /**
         * The Slack member a workflow run was started by, read off the trigger's
         * payload, or [NOBODY] for a run nothing in Slack started.
         *
         * An incoming connection's payload carries `user` (Slack's id for whoever
         * wrote the message) and `connection`; nothing else that starts a run
         * carries both, and a webhook's body naming a `user` is not a Slack member.
         */
        fun ofTrigger(trigger: String?, mapper: ObjectMapper): Prompter {
            if (trigger.isNullOrBlank()) return NOBODY
            val payload = runCatching { mapper.readTree(trigger) }.getOrNull() ?: return NOBODY
            // A webhook's body is whatever its caller sent, and a caller is not
            // to be believed about which Slack member it is.
            if (payload.has(WEBHOOK)) return NOBODY
            val user = payload.path("user").takeIf { it.isString }?.stringValue()
            val connection = payload.path("connection").let { held ->
                if (held.isNumber) held.asLong() else held.takeIf { it.isString }?.stringValue()?.toLongOrNull()
            }
            val action = payload.path("action").takeIf { it.isString }?.stringValue()
            if (user.isNullOrBlank() || connection == null || action !in SLACK_ACTIONS) return NOBODY
            return Prompter(PromptSource.SLACK, user, connection)
        }

        private val SLACK_ACTIONS = setOf("MENTION", "MESSAGE", "REPLY")
        private const val WEBHOOK = "webhook"
    }
}

/**
 * Who is prompting on this thread, for the model calls a turn makes.
 *
 * Set by whatever starts a turn - a chat, a workflow's agent step, a task -
 * around the turn, and read where each call's tokens are added up. A thread and
 * not a session, because a session is not one person: two Slack members in one
 * thread are two turns, possibly at once, into the same session.
 */
object Prompting {

    private val held = ThreadLocal<Prompter?>()

    fun current(): Prompter? = held.get()

    fun <T> by(prompter: Prompter, body: () -> T): T {
        val before = held.get()
        held.set(prompter)
        try {
            return body()
        } finally {
            if (before == null) held.remove() else held.set(before)
        }
    }
}

/** One model call, booked to who prompted it. A ledger row; see V369. */
@Entity
@Table(name = "person_usage")
class PersonUsage(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val at: OffsetDateTime,

    @Column(name = "workspace_id", nullable = false)
    val workspaceId: Long,

    @Column(name = "session_id", nullable = false)
    val sessionId: Long,

    @Column(name = "model_id", nullable = false)
    val modelId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    val source: PromptSource,

    @Column(name = "connection_id")
    val connectionId: Long?,

    @Column(name = "person_key", nullable = false)
    val personKey: String,

    @Column(name = "person_name")
    val personName: String?,

    @Column(name = "input_tokens", nullable = false)
    val inputTokens: Long,

    @Column(name = "cached_input_tokens", nullable = false)
    val cachedInputTokens: Long,

    @Column(name = "output_tokens", nullable = false)
    val outputTokens: Long,

    @Column(nullable = false, precision = 20, scale = 8)
    val cost: BigDecimal,

    @Column(nullable = false)
    val unpriced: Boolean,
)

interface PersonUsageRepository : JpaRepository<PersonUsage, Long> {

    /** The newest call in a session that somebody prompted. */
    fun findFirstBySessionIdAndSourceNotOrderByAtDesc(sessionId: Long, source: PromptSource): PersonUsage?
}

/**
 * Writes down who prompted each model call, while an administrator has usage
 * tracking switched on - and nothing at all while they have not.
 *
 * Called beside a session's own spend, for every call into a session: a call
 * made with nobody prompting it is booked to [Prompter.NOBODY] rather than left
 * out, so the report's total is the installation's total and the share nobody
 * asked for is visible instead of missing.
 */
@Service
class UsageTracking(
    private val settings: InstallationSettings,
    private val rows: PersonUsageRepository,
    private val users: AppUserRepository,
    private val slack: SlackDirectory,
    private val sessions: LlmSessionRepository,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Slack names, looked up once an hour per member rather than once per call. */
    private val names = ConcurrentHashMap<Pair<Long, String>, Pair<String?, Instant>>()

    fun record(
        sessionId: Long,
        modelId: Long,
        input: Long,
        cached: Long,
        output: Long,
        cost: BigDecimal?,
    ) {
        if (!settings.usageTracking()) return
        val workspaceId = sessions.findByIdOrNull(sessionId)?.workspaceId ?: return
        val prompter = Prompting.current() ?: inherited(sessionId) ?: Prompter.NOBODY
        // Its own failure, never the call's: a report that could not be written
        // is a missing row, not a turn that failed after the model answered.
        runCatching {
            rows.save(
                PersonUsage(
                    at = OffsetDateTime.now(),
                    workspaceId = workspaceId,
                    sessionId = sessionId,
                    modelId = modelId,
                    source = prompter.source,
                    connectionId = prompter.connectionId,
                    personKey = prompter.key,
                    personName = nameOf(prompter),
                    inputTokens = input,
                    cachedInputTokens = cached,
                    outputTokens = output,
                    cost = cost ?: BigDecimal.ZERO,
                    unpriced = cost == null,
                ),
            )
        }.onFailure { log.warn("The usage of a call into session {} could not be recorded", sessionId, it) }
    }

    /**
     * A call made into no session, so the report's total stays the
     * installation's total. Under [NO_SESSION] rather than a null: an older jar
     * reads this column as a number, and rollback has to keep working.
     */
    fun recordUnkept(workspaceId: Long, modelId: Long, input: Long, cached: Long, output: Long, cost: BigDecimal?) {
        if (!settings.usageTracking()) return
        val prompter = Prompting.current() ?: Prompter.NOBODY
        runCatching {
            rows.save(
                PersonUsage(
                    at = OffsetDateTime.now(),
                    workspaceId = workspaceId,
                    sessionId = NO_SESSION,
                    modelId = modelId,
                    source = prompter.source,
                    connectionId = prompter.connectionId,
                    personKey = prompter.key,
                    personName = nameOf(prompter),
                    inputTokens = input,
                    cachedInputTokens = cached,
                    outputTokens = output,
                    cost = cost ?: BigDecimal.ZERO,
                    unpriced = cost == null,
                ),
            )
        }.onFailure { log.warn("The usage of a call into no session could not be recorded", it) }
    }

    /**
     * Who is prompting [sessionId] now, named, by the rule the ledger books a
     * call by - recorded whether tracking is on or not, for whoever asks.
     */
    fun prompterOf(sessionId: Long): Prompter =
        named(Prompting.current() ?: inherited(sessionId) ?: Prompter.NOBODY)

    /** [prompter] with what to call them, as the ledger would name them. */
    fun named(prompter: Prompter): Prompter = prompter.copy(name = nameOf(prompter))

    /**
     * Who prompted the conversation above a session that has nobody of its own.
     *
     * An asked agent's session is under the one that asked it, and the turn
     * woken there later - by a reminder it set, a watcher - runs with nobody on
     * its thread, so all of its calls were booked to nobody and a report could
     * not say whose question they answered. They are booked to whoever last
     * prompted the session it hangs from, nearest first.
     */
    private fun inherited(sessionId: Long): Prompter? {
        var at = sessions.findByIdOrNull(sessionId)?.parentSessionId
        repeat(MAX_DEPTH) {
            val above = at ?: return null
            rows.findFirstBySessionIdAndSourceNotOrderByAtDesc(above, PromptSource.NONE)?.let { row ->
                return Prompter(row.source, row.personKey, row.connectionId, row.personName)
            }
            at = sessions.findByIdOrNull(above)?.parentSessionId
        }
        return null
    }

    private fun nameOf(prompter: Prompter): String? {
        prompter.name?.let { return it }
        return when (prompter.source) {
            PromptSource.NONE -> null
            PromptSource.ORKNUX -> users.findByUsername(prompter.key)?.displayName?.takeIf { it.isNotBlank() }
            PromptSource.SLACK -> prompter.connectionId?.let { slackName(it, prompter.key) }
        }
    }

    /**
     * What a Slack member is called, asked through [connectionId] and kept for
     * an hour - or null where Slack would not say.
     *
     * [askAgain] is the report's: a row's name is fixed when its call is booked,
     * so one lookup that failed while a member's calls were booked - a token
     * being replaced, Slack refusing for a moment - left every one of those rows
     * nameless for good, and the report showed `U…` beside members it could name
     * a minute later. The report asks again for those, past a remembered miss;
     * booking does not, because it runs on every model call.
     */
    fun slackName(connectionId: Long, member: String, askAgain: Boolean = false): String? {
        val key = connectionId to member
        val now = Instant.now()
        names[key]?.takeIf { Duration.between(it.second, now) < NAME_KEPT && (it.first != null || !askAgain) }
            ?.let { return it.first }
        return slack.memberName(connectionId, member).also { names[key] = it to now }
    }

    companion object {
        /** What the ledger's session column says for a call that kept none. No session has this id. */
        const val NO_SESSION = 0L

        private val NAME_KEPT: Duration = Duration.ofHours(1)

        /** How far up a family is looked; asks go one level, so this is room, not a tunable. */
        private const val MAX_DEPTH = 8
    }
}
