package io.mszymanski.orknux.server.design

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

/**
 * A document's colours and highlights, carried across a model's rewrite that left their tags out.
 *
 * Pinned: marks on words that came back unchanged are put back on exactly those words; a tag the model
 * wrote itself wins; a highlight whose words were all rewritten moves onto the replacement only when one
 * rewrite replaced exactly its words, and is otherwise dropped and named; past the Tunable nothing is
 * carried and what was lost is still said; a space that happens to survive carries nothing.
 */
class DocumentMarkCarryTest {

    private val mapper = JsonMapper.builder().build()
    private val markdown = DocumentMarkdown(mapper)
    private val carry = DocumentMarkCarry(mapper, 20_000)

    private fun blocks(json: String): List<JsonNode> = mapper.readTree(json).let { list -> (0 until list.size()).map { list.get(it) } }

    private val before = blocks(
        """[
          {"type":"paragraph","content":[
            {"type":"text","text":"The "},
            {"type":"text","text":"red words","marks":[{"type":"textStyle","attrs":{"color":"#c0392b"}}]},
            {"type":"text","text":" stay, and "},
            {"type":"text","text":"this note","marks":[{"type":"note","attrs":{"note":"Check this","id":"nkeep1"}}]},
            {"type":"text","text":" too."}
          ]},
          {"type":"paragraph","content":[
            {"type":"text","text":"Please check "},
            {"type":"text","text":"this","marks":[{"type":"note","attrs":{"note":"Which one?","id":"nmove1"}}]},
            {"type":"text","text":" sentence."}
          ]},
          {"type":"paragraph","content":[
            {"type":"text","text":"Old "},
            {"type":"text","text":"gone words","marks":[{"type":"note","attrs":{"note":"Lost","id":"ngone1"}}]},
            {"type":"text","text":" here."}
          ]}
        ]""",
    )

    /** The words of each text node with the colour and the highlight id on it, run by run. */
    private fun runs(blocks: List<JsonNode>): List<String> = blocks.flatMap { block ->
        val inner = block.path("content")
        (0 until inner.size()).map { inner.get(it) }.map { node ->
            val marks = node.path("marks")
            val said = (0 until marks.size()).map { marks.get(it) }.joinToString(",") { mark ->
                when (mark.path("type").asText()) {
                    "textStyle" -> mark.path("attrs").path("color").asText()
                    "note" -> mark.path("attrs").path("id").asText()
                    else -> mark.path("type").asText()
                }
            }
            if (said.isEmpty()) node.path("text").asText() else node.path("text").asText() + "[" + said + "]"
        }
    }

    private fun written(md: String): List<ObjectNode> = markdown.read(md)

    @Test
    fun `marks on unchanged words come back on them, and a highlight whose words went is said`() {
        val carried = carry.carry(
            before,
            written("The red words stay, and this note too, now longer.\n\nPlease check that sentence.\n\nCompletely different text."),
        )
        val runs = runs(carried.blocks)
        assertThat(runs).contains("red words[#c0392b]", "this note[nkeep1]", "that[nmove1]")
        assertThat(runs.joinToString("|")).doesNotContain("ngone1")
        assertThat(carried.dropped).containsExactly("highlight 'gone words' was removed with its words")
    }

    @Test
    fun `a highlight on words rewritten together moves with them, and one in a rewritten sentence does not`() {
        val moved = carry.carry(before.subList(2, 3), written("Old lost text here."))
        assertThat(runs(moved.blocks)).containsExactly("Old ", "lost text[ngone1]", " here.")
        assertThat(moved.dropped).isEmpty()

        val rewritten = carry.carry(before.subList(1, 2), written("Kindly look again at what the paragraph claims."))
        assertThat(runs(rewritten.blocks).joinToString("|")).doesNotContain("nmove1")
        assertThat(rewritten.dropped).containsExactly("highlight 'this' was removed with its words")
    }

    @Test
    fun `what the model wrote itself wins, and a kept highlight is not reported`() {
        val carried = carry.carry(
            before.subList(0, 1),
            written("The <span style=\"color:#0000ff\">red words</span> stay, and <mark data-note=\"Check this\" data-note-id=\"nkeep1\">this</mark> note too."),
        )
        val runs = runs(carried.blocks)
        assertThat(runs).contains("red words[#0000ff]", "this[nkeep1]")
        assertThat(runs).noneMatch { it.contains("#c0392b") }
        assertThat(carried.dropped).isEmpty()
    }

    @Test
    fun `a space left where it was carries no mark`() {
        val carried = carry.carry(before.subList(2, 3), written("Something else entirely."))
        assertThat(runs(carried.blocks)).containsExactly("Something else entirely.")
        assertThat(carried.dropped).containsExactly("highlight 'gone words' was removed with its words")
    }

    @Test
    fun `past the Tunable nothing is carried, and the lost highlights are still named`() {
        val small = DocumentMarkCarry(mapper, 10)
        val carried = small.carry(before.subList(0, 1), written("The red words stay, and this note too."))
        assertThat(runs(carried.blocks)).containsExactly("The red words stay, and this note too.")
        assertThat(carried.dropped).containsExactly("highlight 'this note' was removed with its words")
    }

    @Test
    fun `a document with no colours or highlights is handed back as written`() {
        val plain = blocks("""[{"type":"paragraph","content":[{"type":"text","text":"Plain words."}]}]""")
        val after = written("Plain words, changed.")
        val carried = carry.carry(plain, after)
        assertThat(carried.blocks).isSameAs(after)
        assertThat(carried.dropped).isEmpty()
    }
}
