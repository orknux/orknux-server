package io.mszymanski.orknux.server.embedded

import io.mszymanski.orknux.server.action.ValueType
import io.mszymanski.orknux.workflow.script.ScriptResult
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.time.format.TextStyle
import java.util.Locale

/**
 * Dates, and the working calendar. Issue #512.
 *
 * The date plugin's eight functions, brought here whole and under the same
 * names, so every workflow action pointing at one goes on pointing at it. That
 * is the entire reason the names are not tidied up on the way in.
 *
 * Knowing what day it is, and whether that day is a working one, is not an
 * integration with somebody else's system - the built-in clock already answered
 * half of it, and a model that has to reach for a plugin to ask whether a
 * deadline has passed is a model reaching past the product.
 *
 * What a working day *is* comes from [WorkingCalendar], which reads the
 * installation's settings. The plugin asked each workspace; this asks the
 * installation, and every call may still name a timezone of its own.
 */
@Component
class DateCapability(
    private val calendar: WorkingCalendar,
    /*
     * Defaulted rather than injected, because there is no Clock bean here and
     * the two other users of one do the same. A test passes a fixed clock.
     */
    private val clock: Clock = Clock.systemDefaultZone(),
    private val mapper: ObjectMapper,
) : EmbeddedCapability {

    override val key = "date"
    override val name = "Dates and the working calendar"

    override fun tools(): List<EmbeddedTool> = listOf(
        EmbeddedTool(
            name = TODAY,
            summary = "Today's date, in the working timezone.",
            description = "Today's date as YYYY-MM-DD, in the working timezone or in $TZ when one is given. Ask this " +
                "rather than assuming: you do not know today's date, and a guess is wrong by however long " +
                "ago you were trained.",
            params = listOf(EmbeddedParam(TZ, ValueType.STRING, "An IANA name like Europe/Warsaw.")),
        ),
        EmbeddedTool(
            name = NOW,
            summary = "The date and time now, with the working-day flags.",
            description = "The date and time now: iso, date, time, weekday, week, quarter, year, and whether today is a " +
                "weekend, a holiday or a business day.",
            params = listOf(EmbeddedParam(TZ, ValueType.STRING, "An IANA name like Europe/Warsaw.")),
        ),
        EmbeddedTool(
            name = DESCRIBE,
            summary = "The same account of any date you name.",
            description = "The same account as $NOW, for a date you name. Pass an ISO date (2026-09-19) or an instant " +
                "(2026-09-19T14:30:00Z); a bare date means midnight in the working timezone.",
            params = listOf(
                EmbeddedParam(WHEN, ValueType.STRING, "The date or instant.", required = true),
                EmbeddedParam(TZ, ValueType.STRING, "An IANA name like Europe/Warsaw."),
            ),
        ),
        EmbeddedTool(
            name = SHIFT,
            summary = "Moves a date by plain time - days, months, years.",
            description = "Moves a date by a plain amount of time and answers the new one as ISO. $UNIT is seconds, " +
                "minutes, hours, days, weeks, months or years; $AMOUNT may be negative. Months and years " +
                "keep the day where they can and clamp where they cannot - 31 January plus one month is 28 " +
                "February. This counts every day: use $SHIFT_BUSINESS to skip weekends and holidays.",
            params = listOf(
                EmbeddedParam(WHEN, ValueType.STRING, "The date to move.", required = true),
                EmbeddedParam(AMOUNT, ValueType.NUMBER, "How far, negative to go back.", required = true),
                EmbeddedParam(UNIT, ValueType.STRING, "seconds to years.", required = true),
            ),
        ),
        EmbeddedTool(
            name = SHIFT_BUSINESS,
            summary = "Moves a date by working days - the one for an SLA.",
            description = "Moves a date by working days, skipping the weekend and the configured holidays, and answers " +
                "YYYY-MM-DD. $DAYS may be negative. Zero answers the same day where it is a working one and " +
                "the next working day where it is not - which is what \"due today\" means on a Sunday. This " +
                "is the one to use for an SLA or a due date.",
            params = listOf(
                EmbeddedParam(WHEN, ValueType.STRING, "The date to move.", required = true),
                EmbeddedParam(DAYS, ValueType.NUMBER, "How many working days.", required = true),
            ),
        ),
        EmbeddedTool(
            name = BETWEEN,
            summary = "How much time lies between two dates.",
            description = "How much time lies between two dates in the $UNIT asked for, as whole units rounded towards " +
                "zero, negative where $TO is the earlier. Months and years are counted by the calendar, so " +
                "1 January to 1 March is two months exactly.",
            params = listOf(
                EmbeddedParam(FROM, ValueType.STRING, "The earlier date.", required = true),
                EmbeddedParam(TO, ValueType.STRING, "The later date.", required = true),
                EmbeddedParam(UNIT, ValueType.STRING, "seconds to years.", required = true),
            ),
        ),
        EmbeddedTool(
            name = BETWEEN_BUSINESS,
            summary = "How many working days lie between two dates.",
            description = "How many working days lie between two dates, skipping weekends and holidays. Counts from " +
                "$FROM up to but not including $TO, so a Monday to the Tuesday after it is one; negative " +
                "where $TO is the earlier. Use it to ask how old a ticket is in working days.",
            params = listOf(
                EmbeddedParam(FROM, ValueType.STRING, "The earlier date.", required = true),
                EmbeddedParam(TO, ValueType.STRING, "The later date.", required = true),
            ),
        ),
        EmbeddedTool(
            name = IS_BUSINESS_HOURS,
            summary = "Whether a moment is inside the working day.",
            description = "Whether a moment falls inside the working day: a working day at all - not a weekend, not a " +
                "configured holiday - and between the configured opening and closing times. Leave $WHEN " +
                "out for now. The gate to put in front of anything that should wait until somebody is at " +
                "their desk.",
            params = listOf(
                EmbeddedParam(WHEN, ValueType.STRING, "The moment. Left out, now."),
                EmbeddedParam(TZ, ValueType.STRING, "An IANA name like Europe/Warsaw."),
            ),
        ),
    )

    /** The same eight, as rows a graph points at. */
    override fun functions(): List<EmbeddedFunction> = tools().map { tool ->
        EmbeddedFunction(
            name = tool.name,
            description = tool.description,
            returnType = when (tool.name) {
                TODAY, SHIFT, SHIFT_BUSINESS -> ValueType.STRING
                BETWEEN, BETWEEN_BUSINESS -> ValueType.NUMBER
                IS_BUSINESS_HOURS -> ValueType.BOOLEAN
                else -> ValueType.MAP
            },
            params = tool.params,
        )
    }

    /* ------------------------------------------------------------- as a tool */

    override fun run(name: String, arguments: String, workspaceId: Long, sessionId: Long?): String {
        val asked = runCatching { mapper.readTree(arguments) }.getOrNull()
            ?: return refusal("That is not valid JSON.")
        val zone = calendar.zoneOf(text(asked, TZ))
            ?: return refusal("There is no timezone named \"${text(asked, TZ)}\"; give an IANA name.")

        return when (name) {
            TODAY -> mapper.writeValueAsString(mapOf("date" to LocalDate.now(clock.withZone(zone)).toString()))
            NOW -> mapper.writeValueAsString(accountOf(ZonedDateTime.now(clock.withZone(zone))))
            DESCRIBE -> {
                val moment = momentIn(text(asked, WHEN), zone)
                    ?: return refusal(notADate(text(asked, WHEN)))
                mapper.writeValueAsString(accountOf(moment))
            }

            SHIFT -> {
                val moment = momentIn(text(asked, WHEN), zone) ?: return refusal(notADate(text(asked, WHEN)))
                val unit = unitOf(text(asked, UNIT)) ?: return refusal(notAUnit(text(asked, UNIT)))
                val amount = number(asked, AMOUNT)?.toLong() ?: return refusal("Give $AMOUNT as a number.")
                mapper.writeValueAsString(mapOf("date" to moment.plus(amount, unit).toOffsetDateTime().toString()))
            }

            SHIFT_BUSINESS -> {
                val moment = momentIn(text(asked, WHEN), zone) ?: return refusal(notADate(text(asked, WHEN)))
                val days = number(asked, DAYS) ?: return refusal("Give $DAYS as a number.")
                mapper.writeValueAsString(
                    mapOf("date" to calendar.shiftBusinessDays(moment.toLocalDate(), days).toString()),
                )
            }

            BETWEEN -> {
                val from = momentIn(text(asked, FROM), zone) ?: return refusal(notADate(text(asked, FROM)))
                val to = momentIn(text(asked, TO), zone) ?: return refusal(notADate(text(asked, TO)))
                val unit = unitOf(text(asked, UNIT)) ?: return refusal(notAUnit(text(asked, UNIT)))
                mapper.writeValueAsString(mapOf("amount" to unit.between(from, to)))
            }

            BETWEEN_BUSINESS -> {
                val from = momentIn(text(asked, FROM), zone) ?: return refusal(notADate(text(asked, FROM)))
                val to = momentIn(text(asked, TO), zone) ?: return refusal(notADate(text(asked, TO)))
                mapper.writeValueAsString(
                    mapOf("days" to calendar.businessDaysBetween(from.toLocalDate(), to.toLocalDate())),
                )
            }

            IS_BUSINESS_HOURS -> {
                val moment = text(asked, WHEN)?.takeIf { it.isNotBlank() }?.let { momentIn(it, zone) }
                    ?: ZonedDateTime.now(clock.withZone(zone))
                mapper.writeValueAsString(
                    mapOf("inside" to calendar.isBusinessHours(moment.toLocalDate(), moment.toLocalTime())),
                )
            }

            else -> refusal("There is no tool called date_$name.")
        }
    }

    /* --------------------------------------------------------- as a function */

    override fun call(name: String, arguments: List<String>, workspaceId: Long, sessionId: Long?): ScriptResult? {
        /*
         * A graph passes arguments positionally and in declared order, so they
         * are named back into the object the tool half already reads. One
         * implementation, two callers - the alternative is two of everything
         * above and a day where they disagree about what a Sunday means.
         */
        val declared = functions().firstOrNull { it.name == name } ?: return null
        val named = linkedMapOf<String, Any?>()
        declared.params.forEachIndexed { at, param ->
            val given = unquoted(arguments.getOrNull(at)) ?: return@forEachIndexed
            named[param.name] = when (param.type) {
                ValueType.NUMBER -> given.toDoubleOrNull() ?: given
                else -> given
            }
        }
        val said = run(name, mapper.writeValueAsString(named), workspaceId, sessionId)
        val answered = runCatching { mapper.readTree(said) }.getOrNull()
        answered?.path("error")?.takeIf { it.isTextual }?.let {
            return ScriptResult.Failed(it.stringValue(), 0)
        }
        return ScriptResult.Returned(said, 0)
    }

    /* ---------------------------------------------------------------- shared */

    /** The account every one of these gives of a moment. */
    private fun accountOf(moment: ZonedDateTime): Map<String, Any> {
        val date = moment.toLocalDate()
        return linkedMapOf(
            "iso" to moment.toOffsetDateTime().toString(),
            "date" to date.toString(),
            "time" to moment.toLocalTime().withNano(0).toString(),
            "weekday" to date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH),
            "week" to date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR),
            "quarter" to date.get(IsoFields.QUARTER_OF_YEAR),
            "year" to date.year,
            "timezone" to moment.zone.id,
            "isWeekend" to calendar.isWeekend(date),
            "isHoliday" to calendar.isHoliday(date),
            "isBusinessDay" to calendar.isBusinessDay(date),
        )
    }

    /**
     * A moment, from an ISO date or an instant.
     *
     * A bare date means midnight in the working timezone, which is what
     * somebody writing `2026-09-19` means by it - reading it as UTC would put
     * it on the day before for half the world.
     */
    private fun momentIn(said: String?, zone: ZoneId): ZonedDateTime? {
        val written = said?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { OffsetDateTime.parse(written) }.getOrNull()?.let { return it.atZoneSameInstant(zone) }
        runCatching { LocalDateTime.parse(written) }.getOrNull()?.let { return it.atZone(zone) }
        runCatching { LocalDate.parse(written) }.getOrNull()?.let { return it.atStartOfDay(zone) }
        return null
    }

    private fun unitOf(said: String?): ChronoUnit? = when (said?.trim()?.lowercase()) {
        "second", "seconds" -> ChronoUnit.SECONDS
        "minute", "minutes" -> ChronoUnit.MINUTES
        "hour", "hours" -> ChronoUnit.HOURS
        "day", "days" -> ChronoUnit.DAYS
        "week", "weeks" -> ChronoUnit.WEEKS
        "month", "months" -> ChronoUnit.MONTHS
        "year", "years" -> ChronoUnit.YEARS
        else -> null
    }

    private fun notADate(said: String?): String =
        "\"${said.orEmpty()}\" is not a date; write it as 2026-09-19 or 2026-09-19T14:30:00Z."

    private fun notAUnit(said: String?): String =
        "\"${said.orEmpty()}\" is not a unit; use seconds, minutes, hours, days, weeks, months or years."

    private fun text(node: JsonNode, name: String): String? =
        node.path(name).takeIf { it.isTextual }?.stringValue()

    private fun number(node: JsonNode, name: String): Int? =
        node.path(name).takeIf { it.isNumber }?.intValue()

    private fun unquoted(argument: String?): String? {
        val given = argument?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
        return runCatching { mapper.readTree(given) }.getOrNull()?.takeIf { it.isTextual }?.stringValue() ?: given
    }

    private fun refusal(said: String): String = mapper.writeValueAsString(mapOf("error" to said))

    private companion object {
        const val TODAY = "today"
        const val NOW = "now"
        const val DESCRIBE = "describe"
        const val SHIFT = "shift"
        const val SHIFT_BUSINESS = "shiftBusinessDays"
        const val BETWEEN = "between"
        const val BETWEEN_BUSINESS = "businessDaysBetween"
        const val IS_BUSINESS_HOURS = "isBusinessHours"

        const val TZ = "timezone"
        const val WHEN = "when"
        const val AMOUNT = "amount"
        const val UNIT = "unit"
        const val DAYS = "days"
        const val FROM = "from"
        const val TO = "to"
    }
}
