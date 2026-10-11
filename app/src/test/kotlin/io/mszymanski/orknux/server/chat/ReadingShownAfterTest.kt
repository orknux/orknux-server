package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.PromptProgress
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A server reading a prompt is reported only once it has been reading for the
 * Admin setting Reading Progress Shown After. The "Reading the prompt…" card
 * flashed up for every read of a second or two, which once the prompt cache
 * holds is nearly all of them.
 */
class ReadingShownAfterTest {

    private class Heard : RoundWatch {
        val processed = mutableListOf<Long>()
        override fun reading(total: Long, cached: Long, processed: Long) {
            this.processed += processed
        }
    }

    /** A read of 1,500 ms reported every 250 ms, as llama.cpp sends it, with what the watcher heard. */
    private fun read(shownAfterMillis: Long): List<Long> {
        var now = 0L
        val heard = Heard()
        val progress = WritingProgress(heard, everyMillis = 200, readingShownAfterMillis = shownAfterMillis) { now }
        for (step in 0..6) {
            now = step * 250L
            progress.reading(PromptProgress(total = 6000, cached = 0, processed = step * 1000L))
        }
        return heard.processed
    }

    @Test
    fun `a read is reported only once it has gone on for the delay`() {
        assertThat(read(shownAfterMillis = 1000)).containsExactly(4000L, 5000L, 6000L)
    }

    @Test
    fun `a read shorter than the delay is never reported`() {
        assertThat(read(shownAfterMillis = 5000)).isEmpty()
    }

    @Test
    fun `no delay reports from the first frame`() {
        assertThat(read(shownAfterMillis = 0)).containsExactly(0L, 1000L, 2000L, 3000L, 4000L, 5000L, 6000L)
    }
}
