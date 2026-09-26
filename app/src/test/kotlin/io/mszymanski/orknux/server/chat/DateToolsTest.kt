package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * An agent asking what the current time is. Issue #407.
 *
 * A model does not know what today is, so an agent reasoning about time has
 * nothing to reason from unless it is told. This shipped as a plugin; it is a
 * built-in now, and needs no running server to test - a fixed clock, and the
 * one tool it offers, is the whole of it.
 */
class DateToolsTest {

    private val mapper = ObjectMapper()

    // A Thursday, so the weekday is something to assert on rather than today's.
    private val clock = Clock.fixed(Instant.parse("2026-09-24T13:45:00Z"), ZoneOffset.UTC)
    private val dates = DateTools(mapper, clock)

    private fun call(args: String) = mapper.readTree(dates.shed().run(ToolCall("1", DateTools.NOW, args)))

    @Test
    fun `it offers one tool, current_time, and it takes an optional timezone`() {
        val spec = dates.shed().specs().single()
        assertThat(spec.name).isEqualTo(DateTools.NOW)
        assertThat(spec.parameters.single().name).isEqualTo(DateTools.TIMEZONE)
        assertThat(spec.parameters.single().required).isFalse()
    }

    @Test
    fun `it answers the time in UTC, with the weekday`() {
        val answer = call("{}")
        assertThat(answer.path("utc").stringValue()).isEqualTo("2026-09-24 13:45 UTC")
        assertThat(answer.path("weekday").stringValue()).isEqualTo("Thursday")
        // No zone asked, so no local time offered.
        assertThat(answer.has("local")).isFalse()
    }

    @Test
    fun `a timezone is answered in that zone as well as UTC`() {
        val answer = call("""{"timezone":"Europe/Warsaw"}""")
        // UTC is still there.
        assertThat(answer.path("utc").stringValue()).isEqualTo("2026-09-24 13:45 UTC")
        // And Warsaw is two hours ahead in September, reported by its IANA name.
        assertThat(answer.path("timezone").stringValue()).isEqualTo("Europe/Warsaw")
        assertThat(answer.path("local").stringValue()).isEqualTo("2026-09-24 15:45")
    }

    @Test
    fun `a zone that does not exist is refused, not guessed`() {
        val answer = call("""{"timezone":"Middle/Earth"}""")
        assertThat(answer.path("error").stringValue()).contains("Middle/Earth")
    }
}
