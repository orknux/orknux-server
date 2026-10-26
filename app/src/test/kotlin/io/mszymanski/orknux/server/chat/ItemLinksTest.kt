package io.mszymanski.orknux.server.chat

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Which page a tool's answer names, on its own. Issue #660. The chat's half -
 * the link reaching a reloaded thread - is `ChatInSessionTest`'s.
 */
class ItemLinksTest {

    @Test
    fun `an answer on this installation's address links to the page, by its path`() {
        val link = ItemLinks.of("""{"started":12,"workflow":"Nightly export","url":"$BASE/workspace/3/executions/12"}""", BASE)

        assertThat(link).isEqualTo(ItemLink("/workspace/3/executions/12", "Nightly export"))
    }

    @Test
    fun `a path is already this installation's`() {
        assertThat(ItemLinks.of("""{"started":4,"url":"/workspace/3/research/4"}""", BASE))
            .isEqualTo(ItemLink("/workspace/3/research/4", null))
    }

    @Test
    fun `the name is the title before anything else the answer says`() {
        val link = ItemLinks.of("""{"started":28,"title":"Write the newsletter","agent":"Writer","url":"/workspace/3/tasks/28"}""", BASE)

        assertThat(link?.name).isEqualTo("Write the newsletter")
    }

    @Test
    fun `somebody else's page is not linked`() {
        assertThat(ItemLinks.of("""{"url":"https://example.com/workspace/3/issues/1","title":"x"}""", BASE)).isNull()
        assertThat(ItemLinks.of("""{"url":"$BASE/manual/agents"}""", BASE)).isNull()
    }

    @Test
    fun `a listing names no one item`() {
        val listing = """{"issues":[{"number":1,"url":"/workspace/3/issues/1"},{"number":2,"url":"/workspace/3/issues/2"}]}"""

        assertThat(ItemLinks.of(listing, BASE)).isNull()
    }

    @Test
    fun `an MCP answer is read inside its envelope`() {
        val inner = """{"issue":9,"title":"Flaky check","url":"$BASE/workspace/3/issues/9"}"""
        val answer = tools.jackson.databind.ObjectMapper().writeValueAsString(mapOf("result" to inner))

        assertThat(ItemLinks.of(answer, BASE)).isEqualTo(ItemLink("/workspace/3/issues/9", "Flaky check"))
    }

    @Test
    fun `text, a refusal and nothing name nothing`() {
        assertThat(ItemLinks.of("Done.", BASE)).isNull()
        assertThat(ItemLinks.of("""{"error":"No such issue"}""", BASE)).isNull()
        assertThat(ItemLinks.of(null, BASE)).isNull()
    }

    private companion object {
        const val BASE = "https://orknux.example.com"
    }
}
