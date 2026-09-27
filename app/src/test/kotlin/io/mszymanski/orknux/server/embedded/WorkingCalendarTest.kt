package io.mszymanski.orknux.server.embedded

import io.mszymanski.orknux.server.attachment.InstallationSettings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.LocalDate
import java.time.LocalTime

/**
 * The two rules a rewrite gets wrong. Issue #512.
 *
 * Eight functions came across from the plugin and most of them are arithmetic
 * anybody would get right. These two are not, they are what an SLA is built on,
 * and both are the kind of rule that looks like an off-by-one until somebody
 * explains it:
 *
 *  - `shiftBusinessDays(0)` is not nothing. It answers the same day where that
 *    is a working one and the *next* working day where it is not, because
 *    "due today" has to mean something on a Sunday.
 *  - `businessDaysBetween` counts from `from` up to but **not including** `to`,
 *    so a Monday to the Tuesday after it is one and not two.
 */
class WorkingCalendarTest {

    /*
     * A stub rather than a mock framework's: four values read back, which is
     * the whole of what the calendar asks its settings for. Written out because
     * it reads as the table it is, and because there is no mockito-kotlin here.
     */
    private val settings: InstallationSettings = Mockito.mock(InstallationSettings::class.java).also {
        Mockito.`when`(it.workingTimezone()).thenReturn("Europe/Warsaw")
        Mockito.`when`(it.workingDayOpens()).thenReturn("09:00")
        Mockito.`when`(it.workingDayCloses()).thenReturn("17:00")
        Mockito.`when`(it.workingHolidays()).thenReturn("2026-12-25,2026-12-26")
    }

    private val calendar = WorkingCalendar(settings)

    /* 2026-09-19 is a Saturday; 21st Monday, 22nd Tuesday, 25th Friday. */
    private val saturday = LocalDate.of(2026, 9, 19)
    private val monday = LocalDate.of(2026, 9, 21)
    private val tuesday = LocalDate.of(2026, 9, 22)

    @Test
    fun `zero working days from a Saturday is the Monday, because due today must mean something`() {
        assertThat(calendar.shiftBusinessDays(saturday, 0)).isEqualTo(monday)
    }

    @Test
    fun `zero working days from a working day is that day`() {
        assertThat(calendar.shiftBusinessDays(monday, 0)).isEqualTo(monday)
    }

    @Test
    fun `one working day from a Friday skips the weekend`() {
        val friday = LocalDate.of(2026, 9, 18)
        assertThat(calendar.shiftBusinessDays(friday, 1)).isEqualTo(monday)
    }

    @Test
    fun `working days skip a configured holiday`() {
        // Christmas Day 2026 is a Friday and is configured as a holiday, so one
        // working day from Thursday the 24th is Monday the 28th.
        val thursday = LocalDate.of(2026, 12, 24)
        assertThat(calendar.shiftBusinessDays(thursday, 1)).isEqualTo(LocalDate.of(2026, 12, 28))
    }

    @Test
    fun `a Monday to the Tuesday after it is one working day, not two`() {
        assertThat(calendar.businessDaysBetween(monday, tuesday)).isEqualTo(1)
    }

    @Test
    fun `the same day is no working days at all`() {
        assertThat(calendar.businessDaysBetween(monday, monday)).isEqualTo(0)
    }

    @Test
    fun `backwards is negative`() {
        assertThat(calendar.businessDaysBetween(tuesday, monday)).isEqualTo(-1)
    }

    @Test
    fun `a weekend is not inside the working day, whatever the hour`() {
        assertThat(calendar.isBusinessHours(saturday, LocalTime.of(11, 0))).isFalse()
    }

    @Test
    fun `nor is a working day before it opens or after it closes`() {
        assertThat(calendar.isBusinessHours(monday, LocalTime.of(8, 59))).isFalse()
        assertThat(calendar.isBusinessHours(monday, LocalTime.of(9, 0))).isTrue()
        assertThat(calendar.isBusinessHours(monday, LocalTime.of(16, 59))).isTrue()
        // Closing time is the end of the day rather than part of it.
        assertThat(calendar.isBusinessHours(monday, LocalTime.of(17, 0))).isFalse()
    }

    @Test
    fun `a configured holiday is not a business day`() {
        assertThat(calendar.isBusinessDay(LocalDate.of(2026, 12, 25))).isFalse()
        assertThat(calendar.isHoliday(LocalDate.of(2026, 12, 25))).isTrue()
    }
}
