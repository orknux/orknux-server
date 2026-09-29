package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.attachment.MAX_SLEEP_SECONDS
import io.mszymanski.orknux.server.attachment.MAX_SLEEP_TIMES
import io.mszymanski.orknux.server.attachment.MIN_SLEEP_SECONDS
import io.mszymanski.orknux.server.attachment.MIN_SLEEP_TIMES
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.time.Duration

/**
 * An agent that stops its turn to wait, and what bounds it.
 *
 * Issue #367. A turn could end two ways: an answer, or `finish_answer`. Neither
 * fits work that is not finished and not failing either - a build running, a
 * colleague who has been asked, a batch job that lands at six. What an agent did
 * with that was hold the round open, billed by the minute and lost the moment
 * the worker died, or answer as though the work were done and lose it. So the
 * ending takes a wake-up: the step parks, and the run comes back to that node
 * when the time is up.
 *
 * Bounded at both ends by the installation, because waiting is a decision the
 * model takes again every time it wakes and one that keeps deciding to wait a
 * little longer never finishes.
 *
 * What is pinned here is the decision rather than the engine: what the model is
 * offered, what it gets when it asks for more than it may have, and what happens
 * when the waiting has run out. That the parked step is picked up again is the
 * execution engine's own arrangement, which the waiting action has been using
 * since before this existed.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class AgentSleepTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val settings: InstallationSettings,
    @Autowired val finishing: FinishAnswerTools,
) {

    @BeforeEach
    fun reset() {
        // Back to the file's own numbers: the settings are rows, and rows
        // outlive the class that changed them.
        settings.setAgentSleepSeconds(settings.agentSleepSecondsConfigured(), "alice")
        settings.setAgentSleepTimes(settings.agentSleepTimesConfigured(), "alice")
    }

    private fun call(arguments: String) = ToolCall("1", FinishAnswerTools.FINISH, arguments)

    private fun sleeping(left: Int, spent: Int = 0, longest: Duration = Duration.ofMinutes(5)) =
        FinishAnswerTools.Sleeping(longest, left = left, spent = spent)

    /* ------------------------------------------------------- the two settings */

    @Test
    fun `an installation with nothing set follows its configuration`() {
        assertThat(settings.agentSleepSeconds()).isEqualTo(settings.agentSleepSecondsConfigured())
        assertThat(settings.agentSleepTimes()).isEqualTo(settings.agentSleepTimesConfigured())
    }

    @Test
    fun `both numbers are what the screen sets and reads back`() {
        graphQlTester
            .document(
                "mutation { setAgentSleepSeconds(seconds: 120) " +
                    "{ agentSleepSeconds agentSleepSecondsConfigured } }",
            )
            .execute()
            .path("setAgentSleepSeconds.agentSleepSeconds").entity(Int::class.java).isEqualTo(120)
            // What the file says is unchanged: the screen stores an answer
            // beside it rather than over it.
            .path("setAgentSleepSeconds.agentSleepSecondsConfigured").entity(Int::class.java)
            .isEqualTo(settings.agentSleepSecondsConfigured())

        graphQlTester
            .document("mutation { setAgentSleepTimes(times: 3) { agentSleepTimes } }")
            .execute()
            .path("setAgentSleepTimes.agentSleepTimes").entity(Int::class.java).isEqualTo(3)

        assertThat(settings.agentSleepSeconds()).isEqualTo(120)
        assertThat(settings.agentSleepTimes()).isEqualTo(3)
    }

    @Test
    fun `a number outside the bounds is refused rather than stored`() {
        graphQlTester
            .document("mutation { setAgentSleepSeconds(seconds: ${MAX_SLEEP_SECONDS + 1}) { agentSleepSeconds } }")
            .execute().errors().satisfy { errors ->
                assertThat(errors).singleElement()
                    .satisfies({ assertThat(it.message).contains("not a length of time") })
            }
        graphQlTester
            .document("mutation { setAgentSleepTimes(times: ${MAX_SLEEP_TIMES + 1}) { agentSleepTimes } }")
            .execute().errors().satisfy { errors ->
                assertThat(errors).singleElement()
                    .satisfies({ assertThat(it.message).contains("not a number of times") })
            }

        assertThatThrownBy { settings.setAgentSleepSeconds(MIN_SLEEP_SECONDS - 1, "alice") }
            .hasMessageContaining("not a length of time")
        assertThatThrownBy { settings.setAgentSleepTimes(MIN_SLEEP_TIMES - 1, "alice") }
            .hasMessageContaining("not a number of times")

        assertThat(settings.agentSleepSeconds()).isEqualTo(settings.agentSleepSecondsConfigured())
        assertThat(settings.agentSleepTimes()).isEqualTo(settings.agentSleepTimesConfigured())
    }

    /* ----------------------------------------------- what the model is offered */

    /**
     * A model is not taught about a thing it will only be refused, which is the
     * rule every other tool here follows: an agent with no waiting left, and one
     * on a step that cannot park at all, are shown the ending it always had.
     */
    @Test
    fun `the wake-up is on the tool only where there is waiting left`() {
        val plain = finishing.shed()!!.specs().single()
        assertThat(plain.parameters.map { it.name }).containsExactly("answer")

        val spent = finishing.shed(sleeping = sleeping(left = 0, spent = 4))!!.specs().single()
        assertThat(spent.parameters.map { it.name }).containsExactly("answer")

        val waiting = finishing.shed(sleeping = sleeping(left = 2))!!.specs().single()
        assertThat(waiting.parameters.map { it.name }).containsExactly("answer", FinishAnswerTools.WAKE)
        // Told what is left, so a model with two waits spends them on the two
        // things worth waiting for rather than finding out by being refused.
        assertThat(waiting.description).contains("2 of those left").contains("300000 ms")
    }

    /* ---------------------------------------------------- what the model gets */

    /**
     * The wake-up is offered as a timer as well as a wait. Issue #568: asked to
     * say hi every five seconds, an agent said it could not run a timer, and
     * then did it five times when asked for five.
     */
    @Test
    fun `the wake-up says it repeats on a timer and that an open-ended ask is done as far as it goes`() {
        val waiting = finishing.shed(sleeping = sleeping(left = 2))!!.specs().single()
        assertThat(waiting.description).contains("on a timer").contains("rather than saying you cannot")
    }

    /**
     * Reported from production: an agent pinging somebody every five minutes
     * left itself "I have started pinging him", woke, read that as work done,
     * and finished after one round. The note is for the agent that wakes, and
     * waking is the next round.
     */
    @Test
    fun `a wait's answer is a note to the agent that wakes, saying what to do next`() {
        val waiting = finishing.shed(sleeping = sleeping(left = 2))!!.specs().single()
        assertThat(waiting.description).contains("`answer` is that note").contains("not what you have done")
    }

    @Test
    fun `waking says the wait is over, nothing ran meanwhile, and a repeating job goes round again`() {
        val woken = WakeNote.wokenQuestion(
            asked = "Ping Darek every 5 minutes until somebody confirms the fix is on UAT.",
            note = "I have started pinging Darek every 5 minutes and will stop when somebody confirms.",
            spent = 1,
            allowed = 5,
        )
        assertThat(woken)
            .startsWith("Ping Darek every 5 minutes")
            .contains("I have started pinging Darek")
            .contains("wait 1 of 5; 4 left")
            .contains("Nothing happened on your behalf while you were stopped")
            .contains("this is its next round")
            .contains("wake_after_ms again")
    }

    /**
     * Reported from production: an agent posted "after the PR is up I will check
     * the build every 10 minutes" and ended with finish_answer {}. Where waiting
     * is offered the model has to say which, every time: -1 is never.
     */
    @Test
    fun `where waiting is offered, leaving the wake-up out is refused and the turn goes on`() {
        val shed = finishing.shed(sleeping = sleeping(left = 2))!!
        val said = shed.run(call("""{"answer":"done"}"""))
        assertThat(said).contains("wake_after_ms is required").contains("-1")

        val spec = shed.specs().single().parameters.single { it.name == FinishAnswerTools.WAKE }
        assertThat(spec.required).isTrue()
    }

    @Test
    fun `minus one is the ending it always was`() {
        val shed = finishing.shed(sleeping = sleeping(left = 2))!!

        for (never in listOf("-1", "\"-1\"")) {
            assertThatThrownBy { shed.run(call("""{"answer":"done","wake_after_ms":$never}""")) }
                .isInstanceOfSatisfying(AnswerFinished::class.java) {
                    assertThat(it.answer).isEqualTo("done")
                    assertThat(it.wake).isNull()
                }
        }
    }

    @Test
    fun `with no waiting on offer, finishing needs no wake-up`() {
        val shed = finishing.shed(sleeping = sleeping(left = 0, spent = 4))!!
        assertThatThrownBy { shed.run(call("""{"answer":"done"}""")) }
            .isInstanceOfSatisfying(AnswerFinished::class.java) { assertThat(it.wake).isNull() }
    }

    @Test
    fun `a wake-up ends the turn carrying how long and the note it left`() {
        val shed = finishing.shed(sleeping = sleeping(left = 2))!!

        assertThatThrownBy { shed.run(call("""{"answer":"waiting on the build","wake_after_ms":60000}""")) }
            .isInstanceOfSatisfying(AnswerFinished::class.java) {
                assertThat(it.wake).isEqualTo(Duration.ofMinutes(1))
                assertThat(it.answer).isEqualTo("waiting on the build")
                assertThat(it.clipped).isFalse()
            }
    }

    /**
     * A parameter has no declared type - every one of them reaches a provider as
     * a string - so which of `60000` and `"60000"` a given model sends is not
     * something to build on.
     */
    @Test
    fun `the number is taken however the model wrote it`() {
        val shed = finishing.shed(sleeping = sleeping(left = 2))!!

        assertThatThrownBy { shed.run(call("""{"wake_after_ms":"60000"}""")) }
            .isInstanceOfSatisfying(AnswerFinished::class.java) {
                assertThat(it.wake).isEqualTo(Duration.ofMinutes(1))
            }
    }

    @Test
    fun `a wait longer than the installation allows is shortened to it`() {
        val shed = finishing.shed(sleeping = sleeping(left = 2))!!

        assertThatThrownBy { shed.run(call("""{"wake_after_ms":86400000}""")) }
            .isInstanceOfSatisfying(AnswerFinished::class.java) {
                assertThat(it.wake).isEqualTo(Duration.ofMinutes(5))
                // Said, because a wait that is not the one the agent asked for
                // is exactly what somebody reads the run's log to find out.
                assertThat(it.clipped).isTrue()
            }
    }

    /**
     * Answered in words rather than by ending the turn.
     *
     * A refusal that halted the round would end the agent's turn on a tool call
     * it never got an answer to - so the round goes on, and the model can finish
     * properly instead.
     */
    @Test
    fun `an agent with its waiting spent is told so and keeps its round`() {
        val shed = finishing.shed(sleeping = sleeping(left = 0, spent = 4))!!

        val said = shed.run(call("""{"wake_after_ms":60000}"""))
        assertThat(said).contains("no waiting left").contains("4 of 4")
    }

    @Test
    fun `a wait of nothing is refused in the same way`() {
        val shed = finishing.shed(sleeping = sleeping(left = 2))!!

        assertThat(shed.run(call("""{"wake_after_ms":0}"""))).contains("greater than zero")
    }
}
