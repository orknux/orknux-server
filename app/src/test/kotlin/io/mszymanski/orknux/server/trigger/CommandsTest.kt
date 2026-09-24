package io.mszymanski.orknux.server.trigger

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The commands in a message: every word starting with the marker. Issue #381.
 *
 * Orknux's own syntax, because Slack polices `/`; so what is measured is that
 * ordinary text never becomes a command, that several in one message are all
 * kept, and that the marker is whatever the workspace made it.
 */
class CommandsTest {

    @Test
    fun `a word starting with the marker is a command, the rest of the message is not`() {
        assertThat(Commands.parse("<@U123> !review PR 12 please", "!")).containsExactly("review")
    }

    @Test
    fun `several in one message are all kept, in order, once each`() {
        assertThat(Commands.parse("!review !security then !review again", "!"))
            .containsExactly("review", "security")
    }

    @Test
    fun `punctuation after the word is not part of it, and a bare marker is nothing`() {
        assertThat(Commands.parse("!review, !security. !! !", "!")).containsExactly("review", "security")
    }

    @Test
    fun `the marker has to start the word - one in the middle is not one`() {
        assertThat(Commands.parse("wow!review really!", "!")).isEmpty()
    }

    @Test
    fun `digits end a command, because an id holds none`() {
        assertThat(Commands.parse("!review2 !v-two", "!")).containsExactly("review", "v-two")
    }

    @Test
    fun `the marker is the workspace's own, and may be more than one character`() {
        assertThat(Commands.parse("::deploy !review", "::")).containsExactly("deploy")
    }

    @Test
    fun `nothing said is no command`() {
        assertThat(Commands.parse(null, "!")).isEmpty()
        assertThat(Commands.parse("   ", "!")).isEmpty()
    }

    @Test
    fun `a marker is one to three characters and never a letter, a digit or a space`() {
        assertThat(Commands.usableMarker("!")).isTrue()
        assertThat(Commands.usableMarker("::")).isTrue()
        assertThat(Commands.usableMarker(">>>")).isTrue()
        assertThat(Commands.usableMarker("")).isFalse()
        assertThat(Commands.usableMarker("!!!!")).isFalse()
        assertThat(Commands.usableMarker("x")).describedAs("xylophone would be a command").isFalse()
        assertThat(Commands.usableMarker("1")).isFalse()
        assertThat(Commands.usableMarker("! ")).isFalse()
    }
}
