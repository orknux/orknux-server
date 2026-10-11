package io.mszymanski.orknux.server.chat

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * How far a model has got writing reaches a watcher about once a second: the
 * first report at once, so the line appears when writing starts, and then no
 * more often than that however many frames a second the provider sends.
 */
class WritingProgressTest {

    private class Heard : RoundWatch {
        val told = mutableListOf<Pair<String?, Int>>()
        override fun writing(tool: String?, chars: Int) {
            told += tool to chars
        }
    }

    @Test
    fun `the first report goes at once and the rest at most once a second, with the size grown`() {
        var now = 10_000L
        val heard = Heard()
        val writing = WritingProgress(heard, everyMillis = 1000) { now }

        writing.calling("orkbox_write", 10)
        now += 300
        writing.calling("orkbox_write", 400)
        now += 300
        writing.calling("orkbox_write", 900)
        now += 500
        writing.calling("orkbox_write", 1500)

        assertThat(heard.told).containsExactly("orkbox_write" to 10, "orkbox_write" to 1500)
    }

    @Test
    fun `an answer is counted in characters as its pieces arrive, with no tool`() {
        var now = 0L
        val heard = Heard()
        val writing = WritingProgress(heard, everyMillis = 1000) { now }

        writing.answer("Hello")
        now += 2000
        writing.answer(", world")

        assertThat(heard.told).containsExactly(null to 5, null to 12)
    }
}
