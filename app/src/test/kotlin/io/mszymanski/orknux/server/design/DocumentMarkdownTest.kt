package io.mszymanski.orknux.server.design

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/**
 * The translation between a document and the Markdown a model reads and writes.
 *
 * What a model writes has to land as the editor's own nodes - a heading, a list
 * whose items open with a paragraph, a table of header and body cells, a
 * picture by its artifact id as a block of its own, a page break - and what it
 * reads has to say the same back, or a model that reads and rewrites a
 * document loses what it could not see.
 */
class DocumentMarkdownTest {

    private val mapper = JsonMapper.builder().build()
    private val markdown = DocumentMarkdown(mapper)
    private val content = DocumentContent(mapper)

    @Test
    fun `Markdown a model writes becomes the editor's nodes, and is checked as a save is`() {
        val doc = content.checked(
            markdown.document(
                """
                # Report

                Some **bold**, *italic*, ~~old~~, <u>underlined</u> and `code` with [a link](https://example.invalid).

                1. First
                2. Second
                   - nested

                > Quoted

                | Name | Count |
                | --- | --- |
                | Apples | 3 |

                Before ![A chart](artifact:DESIGN-7 "Sales by month") after

                <!-- pagebreak -->

                ```kotlin
                val x = 1
                ```
                """.trimIndent(),
            ),
            workspaceId = 1,
        )
        val blocks = doc.path("content")
        val types = (0 until blocks.size()).map { blocks.get(it).path("type").asText() }
        assertThat(types).containsExactly(
            "heading", "paragraph", "orderedList", "blockquote", "table", "paragraph", "image", "paragraph", "pageBreak", "codeBlock",
        )
        val inline = blocks.get(1).path("content")
        val marks = (0 until inline.size()).flatMap { at ->
            val held = inline.get(at).path("marks")
            (0 until held.size()).map { held.get(it).path("type").asText() }
        }.toSet()
        assertThat(marks).containsExactlyInAnyOrder("bold", "italic", "strike", "underline", "code", "link")
        val list = blocks.get(2)
        assertThat(list.path("content").get(0).path("content").get(0).path("type").asText()).isEqualTo("paragraph")
        assertThat(list.path("content").get(1).path("content").get(1).path("type").asText()).isEqualTo("bulletList")
        val table = blocks.get(4).path("content")
        assertThat(table.get(0).path("content").get(0).path("type").asText()).isEqualTo("tableHeader")
        assertThat(table.get(1).path("content").get(0).path("type").asText()).isEqualTo("tableCell")
        val picture = blocks.get(6).path("attrs")
        assertThat(picture.path("artifact").asText()).isEqualTo("DESIGN-7")
        assertThat(picture.path("caption").asText()).isEqualTo("Sales by month")
        assertThat(picture.path("src").asText()).isEqualTo("/api/workspaces/1/document-images/DESIGN-7")
        assertThat(blocks.get(9).path("attrs").path("language").asText()).isEqualTo("kotlin")
    }

    @Test
    fun `a document read back as Markdown says the same, and reads into the same nodes again`() {
        val written = """
            ## Plan

            Ship **the editor** now.

            - One
            - Two

            ![Red](artifact:DESIGN-3 "The red field")

            <!-- pagebreak -->

            | When | What |
            | --- | --- |
            | May | Pages |
        """.trimIndent()
        val doc = content.checked(markdown.document(written), 1)
        val again = markdown.write(doc)
        assertThat(again).contains("## Plan", "Ship **the editor** now.", "- One\n- Two", "![Red](artifact:DESIGN-3 \"The red field\")", "<!-- pagebreak -->", "| May | Pages |")
        assertThat(content.checked(markdown.document(again), 1)).isEqualTo(doc)
    }

    /** The text runs of the first block, each with its marks as type=value. */
    private fun runs(doc: tools.jackson.databind.JsonNode, block: Int = 0): List<Pair<String, Map<String, String>>> {
        val inner = doc.path("content").get(block).path("content")
        return (0 until inner.size()).map { inner.get(it) }.map { node ->
            val marks = node.path("marks")
            node.path("text").asText() to (0 until marks.size()).map { marks.get(it) }.associate { mark ->
                val attrs = mark.path("attrs")
                mark.path("type").asText() to when (mark.path("type").asText()) {
                    "textStyle" -> attrs.path("color").asText()
                    "note" -> attrs.path("note").asText() + "#" + attrs.path("id").asText()
                    else -> ""
                }
            }
        }
    }

    @Test
    fun `a colour and a highlight read as the editor's tags and are read back as its marks`() {
        val doc = content.checked(
            mapper.readTree(
                """{"type":"doc","content":[{"type":"paragraph","content":[
                  {"type":"text","text":"Plain "},
                  {"type":"text","text":"red","marks":[{"type":"bold"},{"type":"textStyle","attrs":{"color":"#c0392b"}}]},
                  {"type":"text","text":" and "},
                  {"type":"text","text":"noted words","marks":[{"type":"note","attrs":{"note":"Say \"why\" | <here>\nplease","id":"nabc12"}}]},
                  {"type":"text","text":"."}
                ]},{"type":"table","content":[{"type":"tableRow","content":[{"type":"tableHeader","content":[{"type":"paragraph","content":[
                  {"type":"text","text":"cell","marks":[{"type":"note","attrs":{"note":"a | b","id":"ncell1"}}]}
                ]}]}]}]}]}""",
            ),
            1,
        )
        // An export and a version's history are words; the tags are for the Markdown a model is handed.
        assertThat(markdown.write(doc)).startsWith("Plain **red** and noted words.")
        val written = DocumentMarkdown(mapper, tags = true).write(doc)
        assertThat(written).contains(
            "<span style=\"color:#c0392b\">**red**</span>",
            "<mark data-note=\"Say &quot;why&quot; &#124; &lt;here&gt;&#10;please\" data-note-id=\"nabc12\">noted words</mark>",
            "<mark data-note=\"a &#124; b\" data-note-id=\"ncell1\">cell</mark>",
        )
        val again = content.checked(markdown.document(written), 1)
        assertThat(runs(again)).isEqualTo(runs(doc))
        assertThat(runs(again)[3].second).isEqualTo(mapOf("note" to "Say \"why\" | <here>\nplease#nabc12"))
        val cell = again.path("content").get(1).path("content").get(0).path("content").get(0)
        assertThat(runs(cell)).isEqualTo(listOf("cell" to mapOf("note" to "a | b#ncell1")))
    }

    @Test
    fun `a tag a model writes is held to what the editor keeps, and any other stays words`() {
        val long = "x".repeat(Documents.NOTE_LENGTH + 500)
        val doc = content.checked(
            markdown.document(
                "<span style=\"color: rgb(0, 128, 0); background:url(javascript:alert(1))\" onclick=\"alert(1)\">green</span> " +
                    "<span style=\"color:expression(alert(1))\">plain</span> " +
                    "<mark data-note=\"$long\" data-note-id=\"bad id!\" onmouseover=\"alert(1)\">long</mark> " +
                    "<script>alert(1)</script> <span style=\"color:red\">named</span> <img src=x onerror=alert(1)>",
            ),
            1,
        )
        val runs = runs(doc)
        assertThat(runs.first { it.first == "green" }.second).isEqualTo(mapOf("textStyle" to "#008000"))
        assertThat(runs.first { it.first.contains("plain") }.second).isEmpty()
        assertThat(runs.first { it.first.contains("named") }.second).isEmpty()
        val note = runs.first { it.first == "long" }.second.getValue("note")
        assertThat(note.substringBefore('#')).hasSize(Documents.NOTE_LENGTH)
        assertThat(note.substringAfter('#')).matches(DocumentContent.NOTE_ID.pattern).isNotEqualTo("bad id!")
        // What is not one of the editor's tags is kept as the words it was, never as markup the editor runs.
        val words = runs.joinToString("") { it.first }
        assertThat(words).contains("<script>", "<img src=x onerror=alert(1)>")
        assertThat(mapper.writeValueAsString(doc)).doesNotContain("onclick", "onmouseover", "javascript")
    }

    @Test
    fun `a picture at a web address is not fetched, and stays a link to it`() {
        val doc = content.checked(markdown.document("![logo](https://example.invalid/logo.png)"), 1)
        val node = doc.path("content").get(0).path("content").get(0)
        assertThat(node.path("text").asText()).isEqualTo("logo")
        assertThat(node.path("marks").get(0).path("attrs").path("href").asText()).isEqualTo("https://example.invalid/logo.png")
    }
}
