package io.mszymanski.orknux.server.embedded

import io.mszymanski.orknux.server.attachment.InstallationSettings
import org.springframework.stereotype.Component
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * What counts as a working day here. Issue #512.
 *
 * The date plugin asked a workspace for four things - a timezone, a holiday
 * list, and an opening and closing time - and every one of its interesting
 * answers depended on them: whether a moment is inside the working day, what
 * "three working days from now" means, how old a ticket is in working days.
 *
 * They are installation settings now, with the constant as the default, which
 * is the rule for every behavioural number here. An installation that never
 * touches them still gets sensible answers: Monday to Friday, nine to five, in
 * the machine's own timezone, no holidays.
 *
 * **Holidays are a list of dates rather than a calendar of rules.** Naming a
 * country and deriving Easter from it is a library and a yearly argument about
 * which of two dozen regional variants somebody meant; a list is what an
 * administrator can be certain of, and what they can correct when it is wrong.
 */
@Component
class WorkingCalendar(private val settings: InstallationSettings) {

    /** The timezone the working day is measured in. */
    fun zone(): ZoneId = runCatching { ZoneId.of(settings.workingTimezone()) }.getOrDefault(ZoneId.systemDefault())

    /** The zone a caller named, or the installation's where it named none. */
    fun zoneOf(named: String?): ZoneId? {
        val wanted = named?.trim()?.ifEmpty { null } ?: return zone()
        return runCatching { ZoneId.of(wanted) }.getOrNull()
    }

    fun opensAt(): LocalTime = runCatching { LocalTime.parse(settings.workingDayOpens()) }
        .getOrDefault(LocalTime.of(9, 0))

    fun closesAt(): LocalTime = runCatching { LocalTime.parse(settings.workingDayCloses()) }
        .getOrDefault(LocalTime.of(17, 0))

    /** The dates nobody works, as an administrator wrote them. */
    fun holidays(): Set<LocalDate> = settings.workingHolidays()
        .split(',', ';', ' ', 10.toChar())
        .mapNotNull { said -> runCatching { LocalDate.parse(said.trim()) }.getOrNull() }
        .toSet()

    fun isWeekend(date: LocalDate): Boolean =
        date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY

    fun isHoliday(date: LocalDate): Boolean = date in holidays()

    fun isBusinessDay(date: LocalDate): Boolean = !isWeekend(date) && !isHoliday(date)

    /**
     * A date moved by working days, skipping weekends and holidays.
     *
     * **Zero is not nothing.** It answers the same day where that is a working
     * one and the next working day where it is not - which is what "due today"
     * has to mean on a Sunday. Pinned in a test, because it is the rule a
     * rewrite gets wrong and the one an SLA is built on.
     */
    fun shiftBusinessDays(from: LocalDate, days: Int): LocalDate {
        if (days == 0) {
            var held = from
            while (!isBusinessDay(held)) held = held.plusDays(1)
            return held
        }
        val step = if (days > 0) 1L else -1L
        var held = from
        var left = kotlin.math.abs(days)
        while (left > 0) {
            held = held.plusDays(step)
            if (isBusinessDay(held)) left--
        }
        return held
    }

    /**
     * Working days between two dates, counting from `from` up to but **not
     * including** `to` - so a Monday to the Tuesday after it is one. Negative
     * where `to` is the earlier.
     */
    fun businessDaysBetween(from: LocalDate, to: LocalDate): Int {
        if (from == to) return 0
        val backwards = to.isBefore(from)
        val first = if (backwards) to else from
        val last = if (backwards) from else to
        var counted = 0
        var held = first
        while (held.isBefore(last)) {
            if (isBusinessDay(held)) counted++
            held = held.plusDays(1)
        }
        return if (backwards) -counted else counted
    }

    /** Whether a moment is inside the working day: a working day, and between the hours. */
    fun isBusinessHours(date: LocalDate, time: LocalTime): Boolean =
        isBusinessDay(date) && !time.isBefore(opensAt()) && time.isBefore(closesAt())
}
