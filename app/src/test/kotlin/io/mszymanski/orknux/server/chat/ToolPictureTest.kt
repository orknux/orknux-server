package io.mszymanski.orknux.server.chat

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A picture a tool made, on its way to something that can see it.
 *
 * The answer to a tool call is a `tool` message and its content is a string, so
 * base64 in a tool's answer is thousands of characters a model can read and
 * cannot look at. What closes that gap is two named fields: a tool says it made
 * a picture, the loop takes the bytes out and hangs them on a turn of their
 * own, and what the model reads as the tool's answer keeps a sentence where
 * four hundred kilobytes would have been.
 *
 * The contract is the field names, and it is held by two packages that ship
 * separately - the pdf plugin writes `picture`, this reads it. So the shape in
 * the first test is `pdf_preview`'s real answer rather than a sketch of one: if
 * either side renames the field, this is what says so.
 */
class ToolPictureTest {

    /** What `pdf_preview` answers, field for field. */
    private val preview = """
        {"picture":"$PNG","pictureType":"image/png","bytes":13592,
         "width":793,"height":1122,"pages":1,"key":"pdf.1k7e78y"}
    """.trimIndent()

    @Test
    fun `a tool that made a picture has it lifted out of its answer`() {
        val picture = AgentTools.pictureIn(preview)

        assertThat(picture).isNotNull()
        assertThat(picture?.dataUrl)
            .describedAs("a data url, which is what a content part takes")
            .isEqualTo("data:image/png;base64,$PNG")
        assertThat(picture?.type).isEqualTo("image/png")
    }

    /**
     * And what the model reads instead is short.
     *
     * The transcript keeps this too, for as long as the session lives - so a
     * copy of every picture any tool ever made, in base64, twice, is what the
     * alternative costs.
     */
    @Test
    fun `the answer the model reads has the bytes taken out of it`() {
        val picture = requireNotNull(AgentTools.pictureIn(preview))

        val said = AgentTools.withoutPicture(preview, picture)

        assertThat(said).doesNotContain(PNG)
        assertThat(said)
            .describedAs("and says that it was shown, so the answer is not silently shorter")
            .contains("\"shown\":true")
        assertThat(said)
            .describedAs("everything the tool said about the page is still there")
            .contains("\"pages\":1")
            .contains("\"width\":793")
            .contains("\"key\":\"pdf.1k7e78y\"")
    }

    /**
     * An ordinary answer is left exactly as it is.
     *
     * This runs over every tool result in the product, so what it must not do
     * is find pictures in things that are not pictures - a tool that answers a
     * long string is not a tool that drew something.
     */
    @Test
    fun `an answer with no picture in it is untouched`() {
        assertThat(AgentTools.pictureIn("""{"ok":true,"text":"$PNG"}""")).isNull()
        assertThat(AgentTools.pictureIn("""{"picture":""}""")).isNull()
        assertThat(AgentTools.pictureIn("not json at all")).isNull()
        assertThat(AgentTools.pictureIn("""["a","list"]""")).isNull()
    }

    /**
     * And what is not a picture is not hung on a turn as one.
     *
     * The type is named by the tool, so it is the tool's word for what it made
     * - and a plugin answering `application/pdf` under this field would
     * otherwise have a document put in front of a model as an image.
     */
    @Test
    fun `a field that is not an image is refused`() {
        assertThat(AgentTools.pictureIn("""{"picture":"$PNG","pictureType":"application/pdf"}""")).isNull()
        assertThat(AgentTools.pictureIn("""{"picture":"$PNG","pictureType":"text/plain"}""")).isNull()
    }

    /** A call that passed show false keeps its picture for the model; left out, true or unreadable, it is shown. */
    @Test
    fun `show false in the call keeps the picture for the model, and nothing else does`() {
        val answer = """{"picture":"$PNG","contentKey":"picture.1"}"""
        assertThat(AgentTools.pictureIn(answer, """{"svg":"x","show":false}""")?.forModel).isTrue()
        assertThat(AgentTools.pictureIn(answer, """{"show":"false"}""")?.forModel).describedAs("sent as text").isTrue()
        assertThat(AgentTools.pictureIn(answer, """{"show":true}""")?.forModel).isFalse()
        assertThat(AgentTools.pictureIn(answer, """{"svg":"x"}""")?.forModel).isFalse()
        assertThat(AgentTools.pictureIn(answer, "not json")?.forModel).isFalse()
        assertThat(AgentTools.pictureIn(answer)?.forModel).isFalse()

        val said = AgentTools.withoutPicture(answer, requireNotNull(AgentTools.pictureIn(answer, """{"show":false}""")), "in the chat")
        assertThat(said).contains("onlyYouSeeIt").contains("the person has not seen it").contains("\"contentKey\":\"picture.1\"")
            .doesNotContain("inConversation")
    }

    /** No type named is a PNG, which is what every drawing here comes back as. */
    @Test
    fun `a picture with no type named is taken as a png`() {
        val picture = AgentTools.pictureIn("""{"picture":"$PNG"}""")

        assertThat(picture?.type).isEqualTo("image/png")
        assertThat(picture?.dataUrl).startsWith("data:image/png;base64,")
    }

    private companion object {
        /** A real one-pixel PNG, so the shape is a picture rather than a word. */
        const val PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    }
}
