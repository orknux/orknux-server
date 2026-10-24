package io.mszymanski.orknux.server.schedule

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.chat.TimerTools
import io.mszymanski.orknux.server.chat.ToolShed
import io.mszymanski.orknux.server.watcher.WatcherTools
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.OffsetDateTime

/**
 * The tools an agent sets, lists and stops its schedules with - reminders that
 * come back on their own, where `timer_set` comes back once.
 *
 * Lent per turn like the timers, because a schedule belongs to a conversation:
 * it reminds the session it was set in, and without one there is nowhere to
 * remind, so none is offered. Built-ins on the Tools list all the same -
 * [io.mszymanski.orknux.server.chat.BuiltInTools.GRANTED] names them.
 *
 * On by default, as the timer is: a schedule reaches nothing - it hands the
 * agent its own note back - and the installation bounds how often and how many,
 * see [ScheduleSettings]. None of them is offered where the installation allows
 * no schedules at all: a model is only ever offered tools that will run.
 *
 * The other tools are named by their constants, never written out, so a rename
 * there cannot leave this telling a model to call something that is not there.
 */
@Service
class ScheduleTools(
    private val service: ScheduleService,
    private val settings: ScheduleSettings,
    private val installation: InstallationSettings,
    private val mapper: ObjectMapper,
) {

    /** The shed for one turn, or null with no session - there is nothing to remind. */
    fun shed(agent: Agent, session: Long?): ToolShed? = session?.let { Shed(agent, it) }

    private inner class Shed(private val agent: Agent, private val session: Long) : ToolShed {

        override fun specs(): List<ToolSpec> {
            val most = settings.maxPerAgent()
            if (most <= 0) return emptyList()
            val shortest = settings.minIntervalSeconds()
            return listOf(
                ToolSpec(
                    name = SET,
                    description = "Sets a schedule: a reminder for yourself that comes back on its own, every " +
                        "$EVERY seconds or on a $CRON, until you stop it with $CANCEL or it ends by itself at a " +
                        "time ($UNTIL) or after a number of runs ($TIMES). Returns at once, so you carry on. Each time it comes up you are told, with " +
                        "your note: between two steps if you are working, or by being started again if you have " +
                        "finished. Use it for something to do again and again - a report every morning, a check " +
                        "on a queue every hour. For something to do once, at a time, use ${TimerTools.SET} " +
                        "instead; to be told when something one of your tools can see changes, set a watcher " +
                        "with ${WatcherTools.SET}, which checks for you. Runs missed while the server was down " +
                        "are not made up: one late run, then the next on time. It comes up at most every " +
                        "$shortest seconds, and you may have $most running at once. Answers the schedule's id " +
                        "and when it first comes up.",
                    summary = "a reminder that repeats, every so many seconds or on a cron",
                    parameters = listOf(
                        ToolParameterSpec(NOTE, "What to remind yourself of each time, in your own words.", required = true),
                        ToolParameterSpec(
                            EVERY,
                            "Seconds between two runs, at least $shortest; the first is this long from now. " +
                                "Give this or $CRON, not both.",
                            schema = mapOf("type" to "integer"),
                        ),
                        ToolParameterSpec(
                            CRON,
                            "Five fields - minute hour day-of-month month day-of-week - e.g. \"0 9 * * 1-5\" for " +
                                "nine on weekdays. It may come up no more often than every $shortest seconds. " +
                                "Give this or $EVERY, not both.",
                        ),
                        ToolParameterSpec(
                            TIMEZONE,
                            "The zone the $CRON is read in, e.g. Europe/Warsaw. Left out, this installation's " +
                                "working timezone, ${installation.workingTimezone()}.",
                        ),
                        ToolParameterSpec(
                            UNTIL,
                            "An ISO 8601 time after which it ends by itself, e.g. 2026-12-31T18:00:00+01:00. " +
                                "Left out, it runs until stopped.",
                        ),
                        ToolParameterSpec(
                            TIMES,
                            "The most times it comes up before it ends by itself. Left out, no limit.",
                            schema = mapOf("type" to "integer"),
                        ),
                    ),
                ),
                ToolSpec(
                    name = LIST,
                    readOnly = true,
                    description = "Lists your running schedules - from every conversation, only yours: each " +
                        "one's id, note, how it comes round, how many times it has run, its limits and when it " +
                        "next comes up. Look here before setting another, so you do not schedule the same thing twice.",
                    summary = "list your running schedules",
                    parameters = emptyList(),
                ),
                ToolSpec(
                    name = CANCEL,
                    description = "Stops one of your schedules, by the id $SET or $LIST gave; it does not come " +
                        "up again. Use it when what it reminds you of is no longer wanted - the person said stop, " +
                        "or the work it was for is done.",
                    summary = "stop one of your schedules",
                    parameters = listOf(
                        ToolParameterSpec(ID, "The schedule's id.", required = true, schema = mapOf("type" to "integer")),
                    ),
                ),
            )
        }

        override fun handles(name: String): Boolean = name in NAMES && settings.maxPerAgent() > 0

        override fun run(call: ToolCall): String = try {
            val asked = runCatching { mapper.readTree(call.arguments) }.getOrNull()
            when (call.name) {
                SET -> set(asked)
                LIST -> answer(linkedMapOf("schedules" to service.runningFor(agent, session).map(::described)))
                CANCEL -> {
                    val id = whole(asked?.path(ID)) ?: throw ScheduleRefused("$ID must be the number $SET or $LIST answered.")
                    val stopped = service.cancel(agent, session, id)
                    answer(linkedMapOf("stopped" to true, "id" to stopped.id, "note" to "It will not come up again."))
                }
                else -> refusal("There is no tool called ${call.name}")
            }
        } catch (refused: ScheduleRefused) {
            refusal(refused.message ?: "That schedule could not be set.")
        }

        private fun set(asked: JsonNode?): String {
            val every = present(asked?.path(EVERY))?.let {
                whole(it)?.takeIf { seconds -> seconds > 0 }
                    ?: throw ScheduleRefused("$EVERY must be a whole number of seconds greater than zero.")
            }
            val times = present(asked?.path(TIMES))?.let {
                whole(it)?.takeIf { count -> count in 1..Int.MAX_VALUE }?.toInt()
                    ?: throw ScheduleRefused("$TIMES must be a whole number, at least 1.")
            }
            val now = OffsetDateTime.now()
            val schedule = service.create(
                agent,
                session,
                ScheduleRequest(
                    note = text(asked?.path(NOTE)).orEmpty(),
                    everySeconds = every,
                    cron = text(present(asked?.path(CRON))),
                    timezone = text(present(asked?.path(TIMEZONE))),
                    until = text(present(asked?.path(UNTIL))),
                    times = times,
                ),
                now,
            )
            val next = requireNotNull(schedule.nextRunAt)
            return answer(
                linkedMapOf(
                    "set" to true,
                    "id" to schedule.id,
                    "nextRunAt" to next.toString(),
                    "nextRunInSeconds" to Duration.between(now, next).seconds.coerceAtLeast(0),
                    "note" to "Carry on; you will be told each time it comes up. $CANCEL ${schedule.id} stops it.",
                ),
            )
        }

        private fun described(schedule: AgentSchedule): Map<String, Any?> = linkedMapOf(
            "id" to schedule.id,
            "note" to schedule.note,
            "everySeconds" to schedule.everySeconds,
            "cron" to schedule.cron,
            "timezone" to schedule.timezone,
            "runs" to schedule.runs,
            "times" to schedule.maxRuns,
            "until" to schedule.untilAt?.toString(),
            "nextRunAt" to schedule.nextRunAt?.toString(),
            "lastRunAt" to schedule.lastRunAt?.toString(),
            "createdAt" to schedule.createdAt.toString(),
            "thisConversation" to (schedule.sessionId == session),
        )
    }

    private fun text(node: JsonNode?): String? = when {
        node == null || node.isMissingNode || node.isNull -> null
        node.isTextual -> node.stringValue()
        node.isValueNode -> node.asString()
        else -> null
    }

    /** The node where something was given, null where it was left out. */
    private fun present(node: JsonNode?): JsonNode? =
        node?.takeUnless { it.isMissingNode || it.isNull || (it.isTextual && it.stringValue().isBlank()) }

    private fun whole(node: JsonNode?): Long? = when {
        node == null -> null
        node.isIntegralNumber -> node.asLong()
        node.isTextual -> node.stringValue().trim().toLongOrNull()
        else -> null
    }

    private fun answer(said: Map<String, Any?>): String = mapper.writeValueAsString(said)

    private fun refusal(said: String): String = mapper.writeValueAsString(mapOf("error" to said))

    companion object {
        const val SET = "schedule_set"
        const val LIST = "schedule_list"
        const val CANCEL = "schedule_cancel"
        val NAMES = setOf(SET, LIST, CANCEL)

        const val NOTE = "note"
        const val EVERY = "every_seconds"
        const val CRON = "cron"
        const val TIMEZONE = "timezone"
        const val UNTIL = "until"
        const val TIMES = "times"
        const val ID = "id"
    }
}
