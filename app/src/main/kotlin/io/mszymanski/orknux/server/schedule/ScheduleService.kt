package io.mszymanski.orknux.server.schedule

import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.graphql.Refusal
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.SessionEventKind
import io.mszymanski.orknux.server.llm.SessionInbox
import io.mszymanski.orknux.server.trigger.sixField
import io.mszymanski.orknux.server.workspace.WorkspaceAuditCategory
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRecorder
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * What a schedule asks for, refused in words the model that asked can act on.
 *
 * Not a GraphQL refusal: it never reaches a screen. The tool hands the message
 * back as `{"error": ...}`, and the model tries again.
 */
class ScheduleRefused(message: String) : RuntimeException(message)

/** What `schedule_set` is handed, already parsed: exactly one of [everySeconds] and [cron]. */
data class ScheduleRequest(
    val note: String,
    val everySeconds: Long? = null,
    val cron: String? = null,
    /** The zone a cron is read in; null for the installation's working timezone. */
    val timezone: String? = null,
    /** As the agent wrote it, ISO 8601; read in the schedule's zone when it names none. */
    val until: String? = null,
    val times: Int? = null,
)

/**
 * Schedules: set by an agent, run by the clock, ended by their own limits, the
 * agent, a person, or the conversation going away.
 *
 * **What a run is.** The note, posted to the session's inbox - the one wake-up
 * there is, so a chat answers, a task carries on and a parked step is cut
 * short exactly as they are for a timer. A schedule calls nothing itself; what
 * the agent does when reminded is its own turn, with its own grants.
 *
 * **No burst after downtime.** A server that was down for a day finds a
 * schedule of every five minutes long overdue. It runs once - saying it is late
 * and that the runs missed are not made up - and the next run is the next time
 * the schedule would have come up after now. Two hundred and eighty-eight
 * reminders of the same thing at once would be worse than none.
 *
 * **Durable.** Every fact is on the row and the clock is db-scheduler's - see
 * [ScheduleSchedulerConfig] - so [tick] asks the table what is due.
 */
@Service
class ScheduleService(
    private val schedules: AgentScheduleRepository,
    private val settings: ScheduleSettings,
    private val installation: InstallationSettings,
    private val inbox: SessionInbox,
    private val recorder: LlmSessionRecorder,
    private val audit: WorkspaceAuditRecorder,
    transactions: PlatformTransactionManager,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val inTransaction = TransactionTemplate(transactions)

    /** Sets one, or refuses with the reason. */
    fun create(agent: Agent, sessionId: Long, asked: ScheduleRequest, now: OffsetDateTime = OffsetDateTime.now()): AgentSchedule {
        val most = settings.maxPerAgent()
        if (most <= 0) throw ScheduleRefused("Schedules are switched off on this installation.")
        val note = asked.note.trim()
        if (note.isEmpty()) {
            throw ScheduleRefused("Say in ${ScheduleTools.NOTE} what to remind yourself of; you will read it on every run.")
        }
        if ((asked.everySeconds == null) == (asked.cron == null)) {
            throw ScheduleRefused(
                "Give exactly one of ${ScheduleTools.EVERY} (a number of seconds) and ${ScheduleTools.CRON} " +
                    "(five fields: minute hour day-of-month month day-of-week).",
            )
        }
        val shortest = settings.minIntervalSeconds()

        val every = asked.everySeconds?.let { seconds ->
            if (seconds < shortest) {
                throw ScheduleRefused("${ScheduleTools.EVERY} must be at least $shortest on this installation.")
            }
            if (seconds > Int.MAX_VALUE) throw ScheduleRefused("${ScheduleTools.EVERY} is longer than any schedule can be.")
            seconds.toInt()
        }

        val zone = asked.timezone?.trim()?.ifEmpty { null }?.let { named ->
            runCatching { ZoneId.of(named) }.getOrElse {
                throw ScheduleRefused("$named is not a timezone. Name one such as Europe/Warsaw or UTC, or leave it out.")
            }
        } ?: installation.workingZone()

        val cron = asked.cron?.trim()?.replace(Regex("\\s+"), " ")?.let { written ->
            val expression = expressionOf(written)
                ?: throw ScheduleRefused(
                    "$written is not a five-field cron. Write minute hour day-of-month month day-of-week, " +
                        "e.g. 0 9 * * 1-5 for nine on weekdays.",
                )
            val gap = shortestGap(expression, zone, now)
                ?: throw ScheduleRefused("$written never comes up, so there is nothing to schedule.")
            if (gap < shortest) {
                throw ScheduleRefused(
                    "$written comes up as often as every ${gap}s; a schedule may come up at most every " +
                        "${shortest}s on this installation.",
                )
            }
            written
        }

        if (asked.times != null && asked.times < 1) {
            throw ScheduleRefused("${ScheduleTools.TIMES} must be at least 1, or left out for no limit.")
        }

        val first = if (every != null) now.plusSeconds(every.toLong()) else nextCron(requireNotNull(cron), zone, now)
            ?: throw ScheduleRefused("$cron never comes up, so there is nothing to schedule.")

        val until = asked.until?.trim()?.ifEmpty { null }?.let { written ->
            instantOf(written, zone) ?: throw ScheduleRefused(
                "${ScheduleTools.UNTIL} must be an ISO 8601 time such as 2026-12-31T18:00:00+01:00; $written is not one.",
            )
        }
        if (until != null && until.isBefore(first)) {
            throw ScheduleRefused(
                "${ScheduleTools.UNTIL} is before the first run at $first, so it would never come up. " +
                    "For a single reminder use ${io.mszymanski.orknux.server.chat.TimerTools.SET}.",
            )
        }

        val running = agent.id?.let { schedules.countByAgentIdAndStatus(it, ScheduleStatus.ACTIVE) }
            ?: schedules.countBySessionIdAndAgentIdIsNullAndStatus(sessionId, ScheduleStatus.ACTIVE)
        if (running >= most) {
            throw ScheduleRefused(
                "You already have $running schedules running, the most this installation allows one agent. " +
                    "Stop one you no longer need with ${ScheduleTools.CANCEL} first; ${ScheduleTools.LIST} shows them.",
            )
        }

        return requireNotNull(
            inTransaction.execute {
                val saved = schedules.save(
                    AgentSchedule(
                        workspaceId = agent.workspaceId,
                        sessionId = sessionId,
                        agentId = agent.id,
                        agentName = agent.name,
                        note = note,
                        everySeconds = every,
                        cron = cron,
                        timezone = if (cron != null) zone.id else null,
                        maxRuns = asked.times,
                        untilAt = until,
                        createdAt = now,
                        nextRunAt = first,
                    ),
                )
                recorder.note(sessionId, "Schedule #${saved.id} set by ${agent.name}: ${describe(saved)}, first at $first.")
                audit.recordAutomated(
                    agent.workspaceId,
                    WorkspaceAuditCategory.AGENT,
                    "Schedule #${saved.id} set by agent ${agent.name}",
                    agent.name,
                )
                saved
            },
        )
    }

    /** The agent's running schedules: by agent, or by conversation for a task with none. */
    fun runningFor(agent: Agent, sessionId: Long): List<AgentSchedule> =
        agent.id?.let { schedules.findByAgentIdAndStatusOrderByCreatedAtAscIdAsc(it, ScheduleStatus.ACTIVE) }
            ?: schedules.findBySessionIdAndAgentIdIsNullAndStatusOrderByCreatedAtAscIdAsc(sessionId, ScheduleStatus.ACTIVE)

    /** Stopped by the agent that set it, which needs telling nothing. */
    fun cancel(agent: Agent, sessionId: Long, id: Long): AgentSchedule {
        val schedule = schedules.findByIdOrNull(id)?.takeIf { ownedBy(it, agent, sessionId) }
            ?: throw ScheduleRefused("You have no schedule #$id. ${ScheduleTools.LIST} lists the ones you have.")
        if (schedule.status != ScheduleStatus.ACTIVE) {
            throw ScheduleRefused("Schedule #$id has already ended: ${schedule.outcome ?: schedule.status.name}.")
        }
        return requireNotNull(
            inTransaction.execute {
                end(schedule, ScheduleStatus.STOPPED, "Stopped by ${agent.name}.")
                recorder.note(schedule.sessionId, "Schedule #$id stopped by ${agent.name}.")
                schedule
            },
        )
    }

    /**
     * Stopped by a person, from the Schedules page - and its agent is told, so
     * that it does not set the same thing again at its next run of thought.
     */
    fun stop(id: Long, by: String): AgentSchedule = requireNotNull(
        inTransaction.execute {
            val schedule = schedules.findByIdOrNull(id) ?: throw ScheduleNotFoundException(id)
            if (schedule.status != ScheduleStatus.ACTIVE) throw ScheduleNotActiveException(id)
            end(schedule, ScheduleStatus.STOPPED, "Stopped by $by.")
            recorder.note(schedule.sessionId, "Schedule #$id stopped by $by from the Schedules page.")
            inbox.post(
                schedule.sessionId,
                SessionEventKind.SCHEDULE,
                "Your schedule #$id (${describe(schedule)}: ${schedule.note}) was stopped by $by after " +
                    "${schedule.runs} runs. It will not come up again; do not set it again unless you are asked to.",
            )
            schedule
        },
    )

    /**
     * Runs every schedule that is due. Called by the clock - see
     * [ScheduleSchedulerConfig] - and by a test that wants a tick now.
     *
     * Each in its own boundary, so one that cannot run takes only itself down.
     * Returns how many ran, which is what a test asserts on.
     */
    fun tick(now: OffsetDateTime = OffsetDateTime.now()): Int {
        val due = schedules.dueIds(ScheduleStatus.ACTIVE, now)
        var ran = 0
        due.forEach { id ->
            runCatching { if (run(id, now)) ran++ }.onFailure { log.warn("Schedule {} could not run", id, it) }
        }
        return ran
    }

    private fun run(id: Long, now: OffsetDateTime): Boolean = inTransaction.execute {
        val schedule = schedules.findByIdOrNull(id)
            ?.takeIf { it.status == ScheduleStatus.ACTIVE && it.nextRunAt?.isAfter(now) == false }
            ?: return@execute false
        val due = requireNotNull(schedule.nextRunAt)
        schedule.runs += 1
        schedule.lastRunAt = now
        // Missed while the server was down: said once, never replayed.
        val missed = nextAfter(schedule, due)?.let { !it.isAfter(now) } == true
        val next = nextAfter(schedule, now)
        val most = schedule.maxRuns
        val until = schedule.untilAt
        val ending = when {
            most != null && schedule.runs >= most -> "Ended after ${schedule.runs} runs, as many as it was set to make."
            next == null -> "Ended: it does not come up again."
            until != null && next.isAfter(until) -> "Ended at its end time, $until."
            else -> null
        }
        val which = schedule.maxRuns?.let { "run ${schedule.runs} of $it" } ?: "run ${schedule.runs}"
        val body = buildString {
            append("Your schedule #$id has come up ($which, ${describe(schedule)}): ${schedule.note}")
            if (missed) {
                append(" It was due at $due and is late, because the server was not running; the runs missed ")
                append("in between are not made up.")
            }
            if (ending == null) {
                append(" It comes up again at $next; ${ScheduleTools.CANCEL} $id stops it.")
            } else {
                append(" That was its last run; the schedule has ended.")
            }
        }
        if (ending == null) {
            schedule.nextRunAt = next
            schedules.save(schedule)
        } else {
            end(schedule, ScheduleStatus.ENDED, ending)
        }
        inbox.post(schedule.sessionId, SessionEventKind.SCHEDULE, body)
        true
    } ?: false

    private fun end(schedule: AgentSchedule, status: ScheduleStatus, outcome: String) {
        schedule.status = status
        schedule.outcome = outcome
        schedule.nextRunAt = null
        schedule.finishedAt = OffsetDateTime.now()
        schedules.save(schedule)
    }

    private fun ownedBy(schedule: AgentSchedule, agent: Agent, sessionId: Long): Boolean =
        if (agent.id != null) schedule.agentId == agent.id else schedule.agentId == null && schedule.sessionId == sessionId

    companion object {
        /**
         * How many of a cron's coming occurrences are compared to find how
         * often it comes up. Not a limit anybody tunes: it is how far ahead a
         * five-field cron has to be read before its tightest gap has shown
         * itself, and a thousand covers every weekday for four years.
         */
        private const val LOOKED_AHEAD = 1000

        /** A five-field cron as Spring reads it, or null for anything else - six fields included. */
        fun expressionOf(cron: String): CronExpression? {
            val fields = cron.trim().split(Regex("\\s+"))
            if (fields.size != 5) return null
            return runCatching { CronExpression.parse(sixField(cron)) }.getOrNull()
        }

        /** The first time after [after] a cron comes up, read in [zone]. */
        fun nextCron(cron: String, zone: ZoneId, after: OffsetDateTime): OffsetDateTime? =
            expressionOf(cron)?.next(after.atZoneSameInstant(zone))?.toOffsetDateTime()

        /** The tightest gap between two of the cron's coming runs, in seconds; null where it never comes up. */
        fun shortestGap(expression: CronExpression, zone: ZoneId, from: OffsetDateTime): Long? {
            var at: ZonedDateTime = expression.next(from.atZoneSameInstant(zone)) ?: return null
            var shortest: Long? = null
            repeat(LOOKED_AHEAD) {
                val next = expression.next(at) ?: return shortest ?: Long.MAX_VALUE
                val gap = Duration.between(at, next).seconds
                shortest = minOf(shortest ?: gap, gap)
                at = next
            }
            return shortest ?: Long.MAX_VALUE
        }

        /**
         * When the schedule next comes up strictly after [after]: for an
         * interval, the next step of the cadence it was set on rather than
         * [after] plus the interval, so a late run does not shift every run
         * after it.
         */
        fun nextAfter(schedule: AgentSchedule, after: OffsetDateTime): OffsetDateTime? {
            schedule.everySeconds?.let { every ->
                val base = schedule.nextRunAt ?: schedule.lastRunAt ?: schedule.createdAt
                if (base.isAfter(after)) return base
                val steps = Duration.between(base, after).seconds / every + 1
                return base.plusSeconds(steps * every)
            }
            val zone = schedule.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.of("UTC")
            return schedule.cron?.let { nextCron(it, zone, after) }
        }

        /** An ISO time, with an offset or without one, in which case it is read in [zone]. */
        fun instantOf(written: String, zone: ZoneId): OffsetDateTime? =
            runCatching { OffsetDateTime.parse(written) }.getOrNull()
                ?: runCatching { java.time.LocalDateTime.parse(written).atZone(zone).toOffsetDateTime() }.getOrNull()
                ?: runCatching { java.time.LocalDate.parse(written).atStartOfDay(zone).toOffsetDateTime() }.getOrNull()

        /** How it comes round, as a sentence names it: "every 300s", "on 0 9 * * 1-5 (Europe/Warsaw)". */
        fun describe(schedule: AgentSchedule): String =
            schedule.everySeconds?.let { "every ${it}s" } ?: "on ${schedule.cron} (${schedule.timezone})"
    }
}

class ScheduleNotFoundException(val id: Long) : RuntimeException("There is no schedule #$id."), Refusal {
    override val arguments get() = mapOf("id" to id)
}

class ScheduleNotActiveException(val id: Long) : RuntimeException("Schedule #$id has already ended."), Refusal {
    override val arguments get() = mapOf("id" to id)
}
