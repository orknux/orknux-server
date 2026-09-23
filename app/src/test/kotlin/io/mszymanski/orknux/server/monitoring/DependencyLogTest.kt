package io.mszymanski.orknux.server.monitoring

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * When a dependency going down is worth a line in the log.
 *
 * The monitoring screen was the only place this went, and a screen is a thing
 * somebody has to already be looking at. A directory that has been refusing
 * connections since Tuesday told whoever opened that page and nobody else - not
 * the log anybody greps, not whatever watches the log, and not the person
 * reading it a week later to work out when it started.
 *
 * What stopped it being logged is that the check runs on every poll of the page,
 * so the state is not the thing to say - the change is.
 */
class DependencyLogTest {

    @Test
    fun `a dependency that goes down says so`() {
        assertThat(MonitoringAPI.worthSaying(before = true, now = false)).isEqualTo(MonitoringAPI.Said.BROKEN)
    }

    @Test
    fun `and says nothing on every poll after that`() {
        assertThat(MonitoringAPI.worthSaying(before = false, now = false)).isEqualTo(MonitoringAPI.Said.NOTHING)
    }

    @Test
    fun `coming back is worth a line of its own`() {
        assertThat(MonitoringAPI.worthSaying(before = false, now = true)).isEqualTo(MonitoringAPI.Said.MENDED)
    }

    @Test
    fun `and answering goes on being unremarkable`() {
        assertThat(MonitoringAPI.worthSaying(before = true, now = true)).isEqualTo(MonitoringAPI.Said.NOTHING)
    }

    /**
     * An installation that starts with an unreachable directory should say so
     * without having to wait for it to first work.
     */
    @Test
    fun `the first reading of all, if it is down, is said`() {
        assertThat(MonitoringAPI.worthSaying(before = null, now = false)).isEqualTo(MonitoringAPI.Said.BROKEN)
    }

    /**
     * And if it is up it is not: every restart would otherwise announce that
     * everything is answering again, which reads like a recovery and is nothing
     * of the sort.
     */
    @Test
    fun `the first reading of all, if it is up, is not`() {
        assertThat(MonitoringAPI.worthSaying(before = null, now = true)).isEqualTo(MonitoringAPI.Said.NOTHING)
    }
}
