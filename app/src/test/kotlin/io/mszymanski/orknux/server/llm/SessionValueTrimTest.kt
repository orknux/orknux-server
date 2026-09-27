package io.mszymanski.orknux.server.llm

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper

/**
 * The rule, on its own.
 *
 * No database and no Spring, because what is being asserted is a decision about
 * one string and it should be readable as one. What the recorder does with the
 * answer is `LlmSessionRedactionTest`'s subject and `LlmSessionTest`'s; this is
 * the shape of the answer itself.
 *
 * Two of these tests are here for the halves that are easy to lose. The first is
 * that a short field beside a payload comes out untouched - a trim that took the
 * neighbours with it would still make the row smaller, and would have taken away
 * the only part anybody reads a tool call for. The second is that a call with no
 * payload in it comes back character for character, which is the promise
 * `LlmSessionRecorder.toolCalled` makes about recording arguments unprettied.
 */
class SessionValueTrimTest {

    /** A call with nothing long in it is the text that arrived, not a copy of it. */
    @Test
    fun `a call with no long value in it is stored exactly as it arrived`() {
        val asked = """{ "status": "OPEN",  "label": "p1" }"""

        // The spacing included. Re-serialising would be this deciding what the
        // model meant by how it wrote its own arguments.
        assertThat(SessionValueTrim.trim(asked)).isEqualTo(asked)
    }

    /** And a field just under the line is a field, not a payload. */
    @Test
    fun `a value at the line is left whole`() {
        val title = "x".repeat(SessionValueTrim.LONGEST_VALUE)
        val asked = """{"title":"$title"}"""

        assertThat(SessionValueTrim.trim(asked)).isEqualTo(asked)
    }

    /**
     * The row this was built for: one payload, and the arguments that say what
     * the call was.
     */
    @Test
    fun `a payload is cut and everything beside it survives`() {
        val payload = "A".repeat(5_867)
        val trimmed = SessionValueTrim.trim("""{"base64":"$payload","channel":"C123"}""")

        val read = reader.readTree(trimmed)
        // The short field is not shortened, not reordered and not re-encoded.
        assertThat(read.get("channel").stringValue()).isEqualTo("C123")
        // The long one keeps its first characters, so a reader can still see
        // what kind of thing it was.
        assertThat(read.get("base64").stringValue()).startsWith("A".repeat(SessionValueTrim.LONGEST_VALUE))
        // And says how much there was, so nobody has to guess that it was cut.
        assertThat(read.get("base64").stringValue()).contains("5867")
        // And that asking again is not how the rest is recovered. Issue #519.
        assertThat(read.get("base64").stringValue()).contains("returns the same answer")
        assertThat(read.get("base64").stringValue().length)
            .isLessThan(SessionValueTrim.LONGEST_VALUE + 160)

        // Still JSON, which is the whole reason this works field by field
        // rather than cutting the text at a length.
        assertThat(read.isObject).isTrue()
        assertThat(read.properties().map { it.key }).containsExactly("base64", "channel")
    }

    /**
     * Two levels in, because that is where a payload usually is.
     *
     * A Slack block, an attachment, a tool that wraps its answer in an envelope:
     * the image is almost never the top-level argument, and a walk that only
     * looked at the first level would have found none of the rows that caused
     * this.
     */
    @Test
    fun `a payload nested in an object is found`() {
        val trimmed = SessionValueTrim.trim(
            """{"message":{"text":"here it is","attachment":{"svg":"${"<rect/>".repeat(500)}"}}}""",
        )

        val read = reader.readTree(trimmed)
        assertThat(read.get("message").get("text").stringValue()).isEqualTo("here it is")
        assertThat(read.get("message").get("attachment").get("svg").stringValue())
            .startsWith("<rect/>")
            .contains("3500 characters")
    }

    /** And inside an array, which is how blocks and attachments arrive. */
    @Test
    fun `a payload inside an array is found, and its short neighbours are not`() {
        val trimmed = SessionValueTrim.trim(
            """{"blocks":[{"type":"section"},{"type":"image","url":"${"z".repeat(OVER)}"}]}""",
        )

        val blocks = reader.readTree(trimmed).get("blocks")
        assertThat(blocks.get(0).get("type").stringValue()).isEqualTo("section")
        assertThat(blocks.get(1).get("type").stringValue()).isEqualTo("image")
        assertThat(blocks.get(1).get("url").stringValue()).contains("$OVER characters")
    }

    /** A bare array is a shape a tool answers in as readily as an object. */
    @Test
    fun `an array at the top is walked too`() {
        val trimmed = SessionValueTrim.trim("""["#220 labels=['p1']","${"q".repeat(4_000)}"]""")

        val read = reader.readTree(trimmed)
        assertThat(read.get(0).stringValue()).isEqualTo("#220 labels=['p1']")
        assertThat(read.get(1).stringValue()).contains("4000 characters")
    }

    /**
     * Text that is not JSON is cut whole, on the same rule.
     *
     * The noisiest tools are the ones that answer in prose, so a rule that only
     * applied to well-formed JSON would be a rule that missed the output it was
     * written for.
     */
    @Test
    fun `text that is not JSON is shortened whole and says how long it was`() {
        val output = "not json at all. " + "y".repeat(3_000)
        val trimmed = SessionValueTrim.trim(output)

        assertThat(trimmed).startsWith("not json at all. ")
        assertThat(trimmed).contains("trimmed from 3017 characters")
        assertThat(trimmed.length).isLessThan(output.length)
    }

    /** Short text that is not JSON is not touched either; the line is a length. */
    @Test
    fun `short text that is not JSON is left alone`() {
        assertThat(SessionValueTrim.trim("#220 labels=['p1']")).isEqualTo("#220 labels=['p1']")
    }

    /**
     * A listing of short fields is long and is kept, which is the case the rule
     * is shaped around.
     *
     * The bloat this fixes is one enormous value, not a lot of small ones. A
     * tool that answers with two hundred issues is answering the question it was
     * asked, and `LlmSessionRecorder.recalled` still has all of it to put back
     * in front of the model - which is the bug that half of the session's memory
     * exists to fix.
     */
    @Test
    fun `a long listing of short fields is kept in full`() {
        val listing = (1..200).joinToString(",", "[", "]") {
            """{"id":$it,"labels":["p1"],"title":"the export runs twice"}"""
        }

        assertThat(listing.length).isGreaterThan(10_000)
        assertThat(SessionValueTrim.trim(listing)).isEqualTo(listing)
    }

    /** What is not a string has no length to bound, and is left as it stands. */
    @Test
    fun `numbers, booleans and nulls are carried through unchanged`() {
        val asked = """{"page":4,"open":true,"label":null,"note":"${"n".repeat(OVER)}"}"""

        val read = reader.readTree(SessionValueTrim.trim(asked))
        assertThat(read.get("page").asInt()).isEqualTo(4)
        assertThat(read.get("open").asBoolean()).isTrue()
        assertThat(read.get("label").isNull).isTrue()
        assertThat(read.get("note").stringValue()).contains("$OVER characters")
    }

    private val reader = ObjectMapper()

    private companion object {

        /**
         * A value comfortably over the line, whatever the line is.
         *
         * Written this way because it was not: two of these tests used a number
         * that happened to be over the old bound, and raising it to a thousand
         * for issue #519 turned them into tests of a value that is now simply
         * short. A test that stops testing anything when a default moves is one
         * nobody notices has stopped.
         */
        const val OVER = SessionValueTrim.LONGEST_VALUE * 2
    }
}
