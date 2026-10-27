package io.mszymanski.orknux.server.design

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/**
 * A sticky note is at least as tall as its words: one drawn shorter - by a
 * model writing a mockup, or by somebody dragging it smaller - showed the start
 * of a sentence and hid the rest.
 */
class WireframeNotesTest {

    private val document = WireframeDocument(JsonMapper.builder().build())

    private fun note(w: Int, h: Int, text: String) =
        document.read("""{"elements":[{"id":"n","type":"note","x":0,"y":0,"w":$w,"h":$h,"text":"$text"}]}""").single()

    @Test
    fun `a note too short for its words is read tall enough for them`() {
        val words = "Select offers only workspaces not listed and not this one. Add puts it in the list."
        val read = note(120, 40, words)

        // Four words a line at most at that width, so well over 40 pixels.
        assertThat(read.h).isGreaterThan(40).isEqualTo(WireframeNotes.heightFor(words, 120))
        assertThat(read.h).isGreaterThanOrEqualTo(8 * 18)
    }

    @Test
    fun `a note taller than its words keeps the height it was given`() {
        assertThat(note(300, 400, "Short.").h).isEqualTo(400)
    }

    @Test
    fun `lines a person broke count as lines`() {
        val one = WireframeNotes.heightFor("a", 300)
        assertThat(WireframeNotes.heightFor("a\nb\nc", 300)).isGreaterThan(one + 30)
    }

    @Test
    fun `only notes grow`() {
        val read = document.read("""{"elements":[{"id":"t","type":"text","x":0,"y":0,"w":60,"h":10,"text":"a long run of words that does not fit"}]}""").single()
        assertThat(read.h).isEqualTo(10)
    }
}
