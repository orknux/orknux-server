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

    @Test
    fun `a picture at a web address is not fetched, and stays a link to it`() {
        val doc = content.checked(markdown.document("![logo](https://example.invalid/logo.png)"), 1)
        val node = doc.path("content").get(0).path("content").get(0)
        assertThat(node.path("text").asText()).isEqualTo("logo")
        assertThat(node.path("marks").get(0).path("attrs").path("href").asText()).isEqualTo("https://example.invalid/logo.png")
    }
}
