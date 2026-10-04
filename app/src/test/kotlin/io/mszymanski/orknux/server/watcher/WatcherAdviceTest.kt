package io.mszymanski.orknux.server.watcher

import io.mszymanski.orknux.server.agent.FinishAnswerTools
import io.mszymanski.orknux.server.agent.WakeNote
import io.mszymanski.orknux.server.chat.AgentBriefing
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/**
 * What a model is told about waiting for something: a watcher first, a wake-up
 * as the last resort. Issue #606.
 *
 * Every place that used to tell an agent to check something periodically by
 * ending its turn with `finish_answer` and `wake_after_ms` now names
 * `watcher_set` before it - because a wake to call the same tool again is a
 * model call per look, and a watcher looks without one. What a model believes
 * about how to wait is decided by these sentences, so the order is pinned
 * rather than left to whoever edits them next.
 */
class WatcherAdviceTest {

    /** Where [first] is said before [then], both being said at all. */
    private fun assertBefore(text: String, first: String, then: String) {
        val a = text.indexOf(first)
        val b = text.indexOf(then)
        assertThat(a).describedAs("'$first' is said").isNotNegative()
        assertThat(b).describedAs("'$then' is said").isNotNegative()
        assertThat(a).describedAs("'$first' comes before '$then' in: $text").isLessThan(b)
    }

    @Test
    fun `a woken agent is told to set a watcher before waking again`() {
        val note = WakeNote.wokenQuestion("Watch the build", "check the build", spent = 1, allowed = 5)
        assertBefore(note, WatcherTools.SET, "wake_after_ms")
        assertThat(note).contains("only where no tool can observe it").doesNotContain("a check until something happens - this is its next round")
    }

    @Test
    fun `finish_answer with a wake names the watcher first and the wake as the last resort`() {
        val shed = requireNotNull(
            FinishAnswerTools(JsonMapper.builder().build())
                .shed(sleeping = FinishAnswerTools.Sleeping(Duration.ofHours(1), left = 3)),
        )
        val described = shed.specs().single().description
        assertBefore(described, WatcherTools.SET, "pass `${FinishAnswerTools.WAKE}` and this step stops")
        assertThat(described).contains("last resort").doesNotContain("a check every minute")

        // And without a wake on offer, finishing still points at the watcher.
        val plain = requireNotNull(FinishAnswerTools(JsonMapper.builder().build()).shed()).specs().single()
        assertThat(plain.description).contains(WatcherTools.SET)
    }

    @Test
    fun `the briefing says to set a watcher before ending with a wake-up`() {
        assertBefore(AgentBriefing.HOW_YOU_RUN, WatcherTools.SET, "wake-up, where one is")
        assertThat(AgentBriefing.HOW_YOU_RUN).contains("last resort")
    }
}
