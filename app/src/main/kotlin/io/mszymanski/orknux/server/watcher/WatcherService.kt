package io.mszymanski.orknux.server.watcher

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.chat.AgentRunTools
import io.mszymanski.orknux.server.chat.AgentTools
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.SessionEventKind
import io.mszymanski.orknux.server.llm.SessionInbox
import io.mszymanski.orknux.server.task.TaskRepository
import io.mszymanski.orknux.server.task.TaskWorker
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.OffsetDateTime

/**
 * What a watcher asks for, refused in words the model that asked can act on.
 *
 * Not a GraphQL refusal: it never reaches a screen. The tool hands the message
 * back as `{"error": ...}`, the way every tool tells a model it got something
 * wrong, and the model tries again.
 */
class WatcherRefused(message: String) : RuntimeException(message)

/** What `watcher_set` is handed, already parsed. */
data class WatcherRequest(
    val tool: String,
    val arguments: String,
    val kind: WatcherConditionKind,
    val condition: String,
    /** A JSONPath into the tool's result; `$` for all of it. */
    val toolResultPath: String,
    val intervalSeconds: Int,
    val timeoutSeconds: Int,
    val note: String?,
    /** How often to wake the agent to look at the result itself; null for never. #618. */
    val agentCheckIntervalSeconds: Int? = null,
    /** One line for the Watchers page saying what it is for. #621. */
    val description: String? = null,
)

/**
 * What `watcher_update` is handed: only what is to change, null for what stays.
 * The tool a watcher calls and how long it may run are not among it - a
 * different tool is a different watcher, and a longer run is one more than the
 * agent was allowed when it asked. #618.
 */
data class WatcherChange(
    val arguments: String? = null,
    val kind: WatcherConditionKind? = null,
    val condition: String? = null,
    val toolResultPath: String? = null,
    val intervalSeconds: Int? = null,
    /** 0 switches the agent's looks off. */
    val agentCheckIntervalSeconds: Int? = null,
    val note: String? = null,
    /** Blank clears it. #621. */
    val description: String? = null,
)

/**
 * Watchers: set by an agent, checked by the clock, ended by a match, a timeout,
 * the agent or a person. Issue #606.
 *
 * **Who it calls as.** The agent that set it, with that agent's grants as they
 * stand at each check: the tool is called through [AgentTools.run], the same
 * door every one of the agent's own tool calls goes through, so a watcher can
 * do nothing its agent could not do by calling the tool itself - and a tool
 * taken away from the agent after the watcher was set ends the watcher rather
 * than being called anyway. A task's agent is the task's working agent, grants
 * widened by whatever a person approved, because that is what the agent held
 * when it asked.
 *
 * **Durable.** Every fact about a watcher is on its row and the clock is
 * db-scheduler's, so a restart loses nothing: [tick] asks the table what is
 * due, never memory. See [WatcherSchedulerConfig].
 *
 * **What arrives goes through the inbox.** A firing, a timeout and a person
 * stopping it are all posted to the session's inbox, which is what wakes a
 * chat, a parked workflow step or a task - the one wake-up there is, rather
 * than a watcher growing its own. And each is written into the session's log as
 * it happens, so a person reading the conversation sees when the watcher was
 * set and when it went off.
 */
@Service
class WatcherService(
    private val watchers: WatcherRepository,
    private val settings: WatcherSettings,
    private val agents: AgentRepository,
    private val agentTools: ObjectProvider<AgentTools>,
    private val tasks: TaskRepository,
    private val worker: ObjectProvider<TaskWorker>,
    private val inbox: SessionInbox,
    private val recorder: LlmSessionRecorder,
    private val audit: WorkspaceAuditRecorder,
    transactions: PlatformTransactionManager,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val inTransaction = TransactionTemplate(transactions)

    /**
     * The tools this agent may have watched: what it is offered, less the two
     * that ask another agent. Calling `ask_agent` every fifteen seconds would
     * start a conversation every fifteen seconds, and `agent_wait` blocks the
     * clock for as long as it waits; neither is something to poll.
     */
    fun watchable(agent: Agent): List<String> =
        agentTools.getObject().specsFor(agent).map { it.name }.filterNot { it in UNWATCHABLE }.distinct()

    /** How many watchers are still running for this conversation; a step owed one parks. */
    fun runningIn(sessionId: Long): Long = watchers.countBySessionIdAndStatus(sessionId, WatcherStatus.ACTIVE)

    /** Sets one, or refuses with the reason. */
    fun create(agent: Agent, sessionId: Long, asked: WatcherRequest): Watcher {
        val most = settings.maxPerAgent()
        if (most <= 0) throw WatcherRefused("Watchers are switched off on this installation.")

        if (asked.tool.isBlank()) throw WatcherRefused("Say in tool which of your tools the watcher should call.")
        if (asked.tool !in watchable(agent)) {
            throw WatcherRefused(
                "You have no tool called ${asked.tool} that a watcher can call. A watcher calls one of your own " +
                    "tools, spelled exactly as your list of tools spells it, with your own permissions. " +
                    "${UNWATCHABLE.joinToString(" and ")} cannot be watched, and neither can the watcher, timer, " +
                    "note, to-do and scratchpad tools.",
            )
        }

        WatcherCondition.problemWith(asked.kind, asked.condition)?.let { throw WatcherRefused(it) }
        WatcherCondition.problemWithPath(asked.toolResultPath)?.let { throw WatcherRefused(it) }

        val shortest = settings.minIntervalSeconds()
        val longest = settings.maxSeconds()
        if (asked.timeoutSeconds <= 0) {
            throw WatcherRefused("timeout_seconds must be a number of seconds greater than zero.")
        }
        if (asked.timeoutSeconds > longest) {
            throw WatcherRefused("A watcher can run for at most $longest seconds on this installation.")
        }
        if (asked.intervalSeconds < shortest) {
            throw WatcherRefused("interval_seconds must be at least $shortest on this installation.")
        }
        if (asked.intervalSeconds > asked.timeoutSeconds) {
            throw WatcherRefused(
                "interval_seconds is longer than timeout_seconds, so the watcher would end before it looked once.",
            )
        }
        asked.agentCheckIntervalSeconds?.let { agentCheckProblem(it, asked.intervalSeconds, asked.timeoutSeconds) }
            ?.let { throw WatcherRefused(it) }

        val running = agent.id?.let { watchers.countByAgentIdAndStatus(it, WatcherStatus.ACTIVE) }
            ?: watchers.countBySessionIdAndAgentIdIsNullAndStatus(sessionId, WatcherStatus.ACTIVE)
        if (running >= most) {
            throw WatcherRefused(
                "You already have $running watchers running, the most this installation allows one agent. " +
                    "End one with ${WatcherTools.FINISH} first, or wait for one to fire.",
            )
        }

        /*
         * Looked at once now, before anything is saved. An agent asked to wait
         * for http://localhost:8077/ to return 0 wrote the regex `0`, which a
         * regex finds anywhere in the result - and http_get's result is JSON
         * with the status in it, so the 0 of 200 fired it on its first check
         * while the body still said 1. A condition that is already true now is
         * either the thing having happened already or a condition too broad to
         * mean anything; both are the agent's to decide, today, not a wake-up
         * later. Outside any transaction, as every check is.
         */
        val now0 = agentTools.getObject()
            .run(agent, ToolCall("watcher-set-check", asked.tool, asked.arguments), sessionId)
        WatcherCondition.match(asked.kind, asked.condition, asked.toolResultPath, now0)?.let { matched ->
            throw WatcherAlreadyMatches(matched, now0)
        }

        val now = OffsetDateTime.now()
        val saved = inTransaction.execute {
            val watcher = watchers.save(
                Watcher(
                    workspaceId = agent.workspaceId,
                    sessionId = sessionId,
                    agentId = agent.id,
                    agentName = agent.name,
                    tool = asked.tool,
                    arguments = asked.arguments,
                    conditionKind = asked.kind,
                    condition = asked.condition,
                    toolResultPath = asked.toolResultPath,
                    intervalSeconds = asked.intervalSeconds,
                    timeoutSeconds = asked.timeoutSeconds,
                    note = asked.note,
                    description = asked.description,
                    createdAt = now,
                    expiresAt = now.plusSeconds(asked.timeoutSeconds.toLong()),
                    nextCheckAt = now.plusSeconds(asked.intervalSeconds.toLong()),
                    agentCheckIntervalSeconds = asked.agentCheckIntervalSeconds,
                    nextAgentCheckAt = asked.agentCheckIntervalSeconds?.let { now.plusSeconds(it.toLong()) },
                ),
            )
            recorder.note(
                sessionId,
                "Watcher #${watcher.id} set by ${agent.name}: ${asked.tool} every ${asked.intervalSeconds}s " +
                    "until ${describe(watcher)} matches, for up to ${asked.timeoutSeconds}s.",
            )
            audit.recordAutomated(
                agent.workspaceId,
                WorkspaceAuditCategory.AGENT,
                "Watcher #${watcher.id} on ${asked.tool} set by agent ${agent.name}",
                agent.name,
            )
            watcher
        }
        return requireNotNull(saved)
    }

    /** The agent's running watchers: by agent, or by conversation for a task with none. */
    fun runningFor(agent: Agent, sessionId: Long): List<Watcher> =
        agent.id?.let { watchers.findByAgentIdAndStatusOrderByCreatedAtAscIdAsc(it, WatcherStatus.ACTIVE) }
            ?: watchers.findBySessionIdAndAgentIdIsNullAndStatusOrderByCreatedAtAscIdAsc(sessionId, WatcherStatus.ACTIVE)

    /**
     * Every watcher this agent ever set, ended ones too, oldest first - and
     * never another agent's: the same ownership [finish] holds to.
     */
    fun everyOneOf(agent: Agent, sessionId: Long): List<Watcher> =
        agent.id?.let { watchers.findByAgentIdOrderByCreatedAtAscIdAsc(it) }
            ?: watchers.findBySessionIdAndAgentIdIsNullOrderByCreatedAtAscIdAsc(sessionId)

    /**
     * Changed by the agent that set it, usually after a look it asked for showed
     * the condition was not the right one. What is not named stays; the change
     * is held to the same rules a new watcher is, and written into the session
     * so a person reading it sees the watcher it had become. #618.
     */
    fun update(agent: Agent, sessionId: Long, id: Long, change: WatcherChange): Watcher {
        val watcher = watchers.findByIdOrNull(id)?.takeIf { ownedBy(it, agent, sessionId) }
            ?: throw WatcherRefused("You have no watcher #$id. ${WatcherTools.LIST} lists the ones you have.")
        if (watcher.status != WatcherStatus.ACTIVE) {
            throw WatcherRefused("Watcher #$id has already ended: ${watcher.outcome ?: watcher.status.name}.")
        }
        val kind = change.kind ?: watcher.conditionKind
        val condition = change.condition ?: watcher.condition
        if (change.kind != null || change.condition != null) {
            WatcherCondition.problemWith(kind, condition)?.let { throw WatcherRefused(it) }
        }
        change.toolResultPath?.let { path -> WatcherCondition.problemWithPath(path)?.let { throw WatcherRefused(it) } }
        val interval = change.intervalSeconds ?: watcher.intervalSeconds
        if (change.intervalSeconds != null) {
            val shortest = settings.minIntervalSeconds()
            if (interval < shortest) throw WatcherRefused("interval_seconds must be at least $shortest on this installation.")
            if (interval > watcher.timeoutSeconds) {
                throw WatcherRefused("interval_seconds is longer than the watcher's timeout of ${watcher.timeoutSeconds}s.")
            }
        }
        val agentCheck = when (change.agentCheckIntervalSeconds) {
            null -> watcher.agentCheckIntervalSeconds
            else -> change.agentCheckIntervalSeconds
        }
        if (change.agentCheckIntervalSeconds != null && agentCheck != null) {
            agentCheckProblem(agentCheck, interval, watcher.timeoutSeconds)?.let { throw WatcherRefused(it) }
        }

        return requireNotNull(
            inTransaction.execute {
                val held = watchers.findByIdOrNull(id)?.takeIf { it.status == WatcherStatus.ACTIVE }
                    ?: throw WatcherRefused("Watcher #$id ended while it was being changed.")
                val now = OffsetDateTime.now()
                change.arguments?.let { held.arguments = it }
                held.conditionKind = kind
                held.condition = condition
                change.toolResultPath?.let { held.toolResultPath = it }
                change.note?.let { held.note = it.ifBlank { null } }
                change.description?.let { held.description = it.trim().ifBlank { null } }
                if (change.intervalSeconds != null) {
                    held.intervalSeconds = interval
                    val next = now.plusSeconds(interval.toLong())
                    held.nextCheckAt = if (next.isAfter(held.expiresAt)) held.expiresAt else next
                }
                if (change.agentCheckIntervalSeconds != null) {
                    held.agentCheckIntervalSeconds = agentCheck
                    held.nextAgentCheckAt = agentCheck?.let { now.plusSeconds(it.toLong()) }
                }
                watchers.save(held)
                recorder.note(
                    sessionId,
                    "Watcher #$id changed by ${agent.name}: ${held.tool} every ${held.intervalSeconds}s until " +
                        "${describe(held)} matches" +
                        (held.agentCheckIntervalSeconds?.let { ", shown to the agent every ${it}s" } ?: "") + ".",
                )
                held
            },
        )
    }

    /**
     * Why an agent check this often cannot be had, or null where it can. At
     * least the installation's floor - each look is a model turn - and no more
     * often than the tool is called, since a look between two checks would show
     * the same result twice; no longer than the watcher runs, or it never looks.
     */
    private fun agentCheckProblem(seconds: Int, interval: Int, timeout: Int): String? {
        val floor = maxOf(settings.minAgentCheckSeconds(), interval)
        return when {
            seconds < floor -> "agent_check_interval_seconds must be at least $floor: no shorter than this " +
                "installation allows (${settings.minAgentCheckSeconds()}s) and no shorter than interval_seconds."
            else -> null
        }
    }

    /** Ended by the agent that set it, which needs telling nothing. */
    fun finish(agent: Agent, sessionId: Long, id: Long): Watcher {
        val watcher = watchers.findByIdOrNull(id)?.takeIf { ownedBy(it, agent, sessionId) }
            ?: throw WatcherRefused("You have no watcher #$id. ${WatcherTools.LIST} lists the ones you have.")
        if (watcher.status != WatcherStatus.ACTIVE) {
            throw WatcherRefused("Watcher #$id has already ended: ${watcher.outcome ?: watcher.status.name}.")
        }
        return requireNotNull(
            inTransaction.execute {
                end(watcher, WatcherStatus.FINISHED, "Finished by ${agent.name}.", by = agent.name)
                recorder.note(watcher.sessionId, "Watcher #$id finished by ${agent.name}.")
                watcher
            },
        )
    }

    /**
     * Ended by a person, from the Watchers page - and its agent is told, since
     * it may be waiting on it and would otherwise wait for ever.
     */
    fun stop(id: Long, by: String): Watcher = requireNotNull(
        inTransaction.execute {
            val watcher = watchers.findByIdOrNull(id) ?: throw WatcherNotFoundException(id)
            if (watcher.status != WatcherStatus.ACTIVE) throw WatcherNotActiveException(id)
            end(watcher, WatcherStatus.STOPPED, "Stopped by $by.", by = by)
            recorder.note(watcher.sessionId, "Watcher #$id stopped by $by from the Watchers page.")
            inbox.post(
                watcher.sessionId,
                SessionEventKind.WATCHER,
                "Watcher #$id, which called ${watcher.tool} until ${describe(watcher)} matched, was stopped by " +
                    "$by before it matched.${noteOf(watcher)} It will not wake you again.",
            )
            watcher
        },
    )

    /**
     * Checks every watcher that is due. Called by the clock - see
     * [WatcherSchedulerConfig] - and by a test that wants a tick now.
     *
     * One at a time, each in its own boundary: a watcher whose tool throws, or
     * whose session went away under it, takes only itself down. Returns how
     * many were checked, which is what a test asserts on.
     */
    fun tick(now: OffsetDateTime = OffsetDateTime.now()): Int {
        val due = watchers.dueIds(WatcherStatus.ACTIVE, now)
        due.forEach { id ->
            runCatching { check(id, now) }.onFailure { log.warn("Watcher {} could not be checked", id, it) }
        }
        return due.size
    }

    private fun check(id: Long, now: OffsetDateTime) {
        val watcher = watchers.findByIdOrNull(id)?.takeIf { it.status == WatcherStatus.ACTIVE } ?: return
        val agent = agentFor(watcher)
        if (agent == null) {
            fail(id, "Its agent, ${watcher.agentName}, no longer exists.")
            return
        }
        if (watcher.tool !in watchable(agent)) {
            fail(id, "${watcher.tool} is no longer one of ${agent.name}'s tools.")
            return
        }

        // Outside any transaction: a tool may take as long as the thing it calls.
        val result = agentTools.getObject()
            .run(agent, ToolCall("watcher-$id-${watcher.checks + 1}", watcher.tool, watcher.arguments), watcher.sessionId)
        val matched = WatcherCondition.match(watcher.conditionKind, watcher.condition, watcher.toolResultPath, result)

        inTransaction.executeWithoutResult {
            // Read again: a person may have stopped it while the tool ran.
            val held = watchers.findByIdOrNull(id)?.takeIf { it.status == WatcherStatus.ACTIVE } ?: return@executeWithoutResult
            held.checks += 1
            held.lastCheckedAt = now
            held.lastResult = result
            when {
                matched != null -> fire(held, matched, result, now)
                !now.isBefore(held.expiresAt) -> timeOut(held, result)
                else -> {
                    val next = now.plusSeconds(held.intervalSeconds.toLong())
                    held.nextCheckAt = if (next.isAfter(held.expiresAt)) held.expiresAt else next
                    lookDue(held, result, now)
                    watchers.save(held)
                }
            }
        }
    }

    private fun fire(watcher: Watcher, matched: String, result: String, now: OffsetDateTime) {
        val id = watcher.id
        watcher.matched = matched
        end(watcher, WatcherStatus.FIRED, "Fired on check ${watcher.checks}.")
        recorder.note(
            watcher.sessionId,
            "Watcher #$id fired on check ${watcher.checks}: ${describe(watcher)} matched $matched.",
        )
        val after = Duration.between(watcher.createdAt, now).toSeconds()
        inbox.post(
            watcher.sessionId,
            SessionEventKind.WATCHER,
            "Watcher #$id fired. You asked to be told when ${watcher.tool} returned something matching " +
                "${describe(watcher)}; on check ${watcher.checks}, ${after}s after you set it, it did." +
                noteOf(watcher) + " What matched: $matched. What ${watcher.tool} returned: $result " +
                "The watcher has ended; set another with ${WatcherTools.SET} if you need to keep watching.",
        )
    }

    /**
     * The look the agent asked for, where one is due: the latest result handed
     * to it while the watcher carries on. The condition is a guess at what
     * "done" looks like, written before the agent saw a single result; this is
     * where it finds out it guessed wrong, and can change it rather than wait
     * a week for a match that was never coming. #618.
     */
    private fun lookDue(watcher: Watcher, result: String, now: OffsetDateTime) {
        val every = watcher.agentCheckIntervalSeconds ?: return
        val due = watcher.nextAgentCheckAt ?: return
        if (now.isBefore(due)) return
        watcher.nextAgentCheckAt = now.plusSeconds(every.toLong())
        recorder.note(watcher.sessionId, "Watcher #${watcher.id} showed ${watcher.agentName} its result after ${watcher.checks} checks.")
        inbox.post(
            watcher.sessionId,
            SessionEventKind.WATCHER,
            "Watcher #${watcher.id} has not matched yet, and this is the look you asked for every ${every}s. " +
                "You asked to be told when ${watcher.tool} returned something matching ${describe(watcher)}." +
                noteOf(watcher) + " On check ${watcher.checks} it returned: $result " +
                "Judge it yourself. If what you are waiting for has in fact happened, end the watcher with " +
                "${WatcherTools.FINISH} and act on it. If the condition or the arguments are wrong, change them " +
                "with ${WatcherTools.UPDATE}. If it is simply not there yet, there is nothing to tell anyone: it carries on by itself.",
        )
    }

    private fun timeOut(watcher: Watcher, result: String) {
        val id = watcher.id
        end(watcher, WatcherStatus.TIMED_OUT, "Timed out after ${watcher.timeoutSeconds}s and ${watcher.checks} checks.")
        recorder.note(
            watcher.sessionId,
            "Watcher #$id timed out after ${watcher.timeoutSeconds}s and ${watcher.checks} checks without " +
                "${describe(watcher)} matching.",
        )
        inbox.post(
            watcher.sessionId,
            SessionEventKind.WATCHER,
            "Watcher #$id timed out: ${watcher.tool} was called ${watcher.checks} times over " +
                "${watcher.timeoutSeconds}s and ${describe(watcher)} never matched." + noteOf(watcher) +
                " What it returned last: $result The watcher has ended.",
        )
    }

    private fun fail(id: Long, why: String) = inTransaction.executeWithoutResult {
        val watcher = watchers.findByIdOrNull(id)?.takeIf { it.status == WatcherStatus.ACTIVE } ?: return@executeWithoutResult
        end(watcher, WatcherStatus.FAILED, why)
        recorder.note(watcher.sessionId, "Watcher #$id ended: $why")
        inbox.post(
            watcher.sessionId,
            SessionEventKind.WATCHER,
            "Watcher #$id, which called ${watcher.tool} until ${describe(watcher)} matched, has ended without " +
                "matching: $why" + noteOf(watcher),
        )
    }

    private fun end(watcher: Watcher, status: WatcherStatus, outcome: String, by: String? = null) {
        watcher.status = status
        watcher.outcome = outcome
        watcher.finishedBy = by
        watcher.finishedAt = OffsetDateTime.now()
        watchers.save(watcher)
    }

    /**
     * Whose grants a check runs with, as they stand now.
     *
     * A task's conversation is answered by the task's working agent - its
     * agent's grants widened by what a person approved, or a bare model's - and
     * [TaskWorker] is what builds that, so it is asked rather than copied.
     * Anything else is the agent row itself.
     */
    fun agentFor(watcher: Watcher): Agent? {
        tasks.findFirstBySessionId(watcher.sessionId)?.let { task ->
            runCatching { worker.getObject().of(task).agent }.getOrNull()?.let { return it }
        }
        return watcher.agentId?.let { agents.findByIdOrNull(it) }
    }

    private fun ownedBy(watcher: Watcher, agent: Agent, sessionId: Long): Boolean =
        if (agent.id != null) watcher.agentId == agent.id else watcher.agentId == null && watcher.sessionId == sessionId

    private fun noteOf(watcher: Watcher): String =
        watcher.note?.takeIf { it.isNotBlank() }?.let { " Your note: $it." }.orEmpty()

    companion object {
        /** Asking another agent, which is not something to poll; see [watchable]. */
        val UNWATCHABLE = listOf(AgentRunTools.ASK, AgentRunTools.WAIT)

        /** The condition as a sentence names it: "the JSONPath $.status". */
        fun describe(watcher: Watcher): String = when (watcher.conditionKind) {
            WatcherConditionKind.JSONPATH -> "the JSONPath ${watcher.condition}"
            WatcherConditionKind.REGEX -> "the regular expression ${watcher.condition}"
        } + (watcher.toolResultPath?.takeIf { it.trim() != WatcherCondition.WHOLE }?.let { " at $it" } ?: "")
    }
}

class WatcherNotFoundException(val id: Long) : RuntimeException("There is no watcher #$id."),
    io.mszymanski.orknux.server.graphql.Refusal {
    override val arguments get() = mapOf("id" to id)
}

class WatcherNotActiveException(val id: Long) : RuntimeException("Watcher #$id has already ended."),
    io.mszymanski.orknux.server.graphql.Refusal {
    override val arguments get() = mapOf("id" to id)
}

/**
 * The condition is already true of what the tool returns right now, so there is
 * nothing to wait for - or the condition is too broad to wait with. Answered to
 * the model, not refused: it decides which.
 */
class WatcherAlreadyMatches(val matched: String, val result: String) : RuntimeException("already matches")
