package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolParameterSpec
import io.mszymanski.orknux.connector.model.ToolSpec
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * An agent asking what the current time is. Issue #407.
 *
 * ### What it is for
 *
 * A model does not know what today is: its training stopped at some past date,
 * and nothing since is in it. So an agent reasoning about time - is this overdue,
 * how long until the deadline, what day of the week a run lands on - has nothing
 * to reason from unless it is told, and the honest way to tell it is to let it
 * ask when it needs to know rather than to guess.
 *
 * This shipped as a plugin an installation had to load. Reasoning about the
 * current time is not specific to any installation, so it is a built-in now,
 * beside the other core agent tools, and every installation has it without a
 * separate load.
 *
 * ### Why it is a tool and not a fact in the prompt
 *
 * It could be stated once at the top of every turn, and for a single-shot answer
 * that would do. But an agent that parks and wakes an hour later, or works a job
 * across a day, needs the time *now* rather than the time the turn began; and one
 * reasoning in somebody's own timezone needs to name that zone rather than take
 * the server's. A tool answers both - it is asked when the answer is wanted, in
 * the zone the question names - where a line in the prompt answers neither.
 *
 * ### Why it needs no session
 *
 * Unlike a note or a to-do list, the time is not something the agent keeps; it
 * is read off the clock each time. So this is offered wherever an agent runs
 * unattended - a workflow node, a task - with nothing to keep and nothing to
 * gate it on.
 */
@Service
class DateTools(
    private val mapper: ObjectMapper,
    /** The clock, defaulted so production reads real time and a test can fix it. */
    private val clock: Clock = Clock.systemUTC(),
) {

    /** The shed for one turn. Always lent: the time needs nothing kept to answer. */
    fun shed(): ToolShed = Shed()

    private inner class Shed : ToolShed {

        override fun specs(): List<ToolSpec> = listOf(
            ToolSpec(
                name = NOW,
                description = "Tells you the current date and time. Your training does not tell you what today " +
                    "is, so use this whenever you reason about time - whether something is overdue, how long " +
                    "until a deadline, what day of the week a date falls on. The answer is in UTC; give " +
                    "$TIMEZONE (an IANA name like \"Europe/Warsaw\" or \"America/New_York\") to also get the " +
                    "time in that zone.",
                parameters = listOf(
                    ToolParameterSpec(
                        TIMEZONE,
                        "An IANA timezone name to also report the local time in. Omit for UTC only.",
                        required = false,
                    ),
                ),
            ),
        )

        override fun handles(name: String): Boolean = name == NOW

        override fun run(call: ToolCall): String {
            val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
            val answer = mutableMapOf<String, Any>(
                "utc" to "${now.format(STAMP)} UTC",
                "weekday" to now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH),
            )

            asked(call)?.let { name ->
                val zone = runCatching { ZoneId.of(name) }.getOrNull()
                    ?: return refusal("There is no timezone named \"$name\"; give an IANA name like \"Europe/Warsaw\".")
                val local = now.atZoneSameInstant(zone)
                // The zone reported as its IANA name rather than an abbreviation:
                // "Europe/Warsaw" is unambiguous where "CEST" is a guess about
                // the season, and the model reads the id it was given back.
                answer["timezone"] = zone.id
                answer["local"] = local.format(STAMP)
                answer["localWeekday"] = local.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            }

            return mapper.writeValueAsString(answer)
        }

        private fun asked(call: ToolCall): String? = runCatching {
            mapper.readTree(call.arguments).path(TIMEZONE).takeIf { it.isTextual }?.stringValue()?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()

        private fun refusal(said: String): String = mapper.writeValueAsString(mapOf("error" to said))
    }

    companion object {
        const val NOW = "current_time"
        const val TIMEZONE = "timezone"

        /** A stamp a model reads without a second thought: date and time to the minute. */
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH)
    }
}
