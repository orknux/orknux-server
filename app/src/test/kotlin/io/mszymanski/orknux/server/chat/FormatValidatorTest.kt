package io.mszymanski.orknux.server.chat

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * Reading a document to say whether it is one. Issue #416.
 *
 * What is pinned is the line between the two answers: a document a parser can
 * read is valid however ugly it is, and one it cannot is reported with where it
 * gave up. Markdown is the case worth its own assertions, since everything
 * parses and the useful answer is about the few things that break a page.
 */
@SpringBootTest
class FormatValidatorTest(@Autowired val validator: FormatValidator) {

    @Test
    fun `it reads the four formats and says where each fails`() {
        assertThat(validator.json("""{"a": [1, 2], "b": null}""")).isEmpty()
        assertThat(validator.json("""{"a": [1, 2,}""")).isNotEmpty()
        // Where, not only that: a fault with no position sends somebody reading
        // the whole document.
        assertThat(validator.json("""{"a": 1,}""").first().line).isNotNull()

        assertThat(validator.yaml(listOf("name: orknux", "ports:", "  - 80").joinToString("\n"))).isEmpty()
        assertThat(validator.yaml(listOf("name: orknux", "  ports: 80").joinToString("\n"))).isNotEmpty()
        // A second document in the stream is read too, which is what makes a
        // file of three worth checking at all.
        assertThat(validator.yaml(listOf("a: 1", "---", "b: [1,").joinToString("\n"))).isNotEmpty()

        assertThat(validator.html("<p>Hello <b>there</b></p>")).isEmpty()
        assertThat(validator.markdown(listOf("# Title", "", "A line, and `code`.").joinToString("\n"))).isEmpty()
    }

    /** Ugly is not invalid: a validator with taste is one people learn to ignore. */
    @Test
    fun `a valid document nobody would praise is still valid`() {
        assertThat(validator.json("""{"a":1,    "b":2}""")).isEmpty()
        assertThat(validator.yaml(listOf("a:    1", "b: 2").joinToString("\n"))).isEmpty()
        assertThat(validator.markdown("no heading, no structure, just a line")).isEmpty()
    }

    /**
     * The markdown mistakes that are mistakes.
     *
     * Everything parses as markdown, so a parser alone answers yes to a page
     * whose code fence ate the rest of the document. These three are what
     * actually goes wrong when a model writes one.
     */
    @Test
    fun `markdown reports a fence, a link and a table that do not hold`() {
        val fence = validator.markdown(listOf("Text", "", "```kotlin", "val a = 1").joinToString("\n"))
        assertThat(fence).hasSize(1)
        assertThat(fence.first().message).contains("never closed")
        assertThat(fence.first().line).isEqualTo(3)

        val link = validator.markdown("See [the docs](https://example.com")
        assertThat(link.map { it.message }).anyMatch { it.contains("never closed") }

        val table = validator.markdown(listOf("| a | b |", "| --- | --- |", "| 1 |").joinToString("\n"))
        assertThat(table.map { it.message }).anyMatch { it.contains("cells") }

        // And a fence that closes is not reported, which is the half that would
        // make this tool a nuisance if it were wrong.
        assertThat(validator.markdown(listOf("```", "val a = 1", "```").joinToString("\n"))).isEmpty()
    }

    /** The one door the script and the tool both come through. */
    @Test
    fun `the one door answers in the shape everything else here answers in`() {
        assertThat(validator.check("""{"format":"json","text":"{}"}""")).contains("\"valid\":true")
        assertThat(validator.check("""{"format":"json","text":"{"}""")).contains("\"valid\":false")
        assertThat(validator.check("""{"format":"klingon","text":"{}"}""")).contains("no format called")
        assertThat(validator.check("not json at all")).contains("not readable JSON")
    }
}
