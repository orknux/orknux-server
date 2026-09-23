package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolSpec
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * An agent carrying only as many tools as it was given room for.
 *
 * Issue #372, which goes past #368. That one was about surviving a hard limit:
 * OpenAI and Azure refuse a request over 128 tools, so an agent granted more
 * could not answer at all. This is about the number *below* it - a model handed
 * eighty tools is already choosing from a list it cannot hold in mind, and the
 * context they occupy is paid for on every round of every turn.
 *
 * Three things that were not there before and are pinned here: a search that has
 * run out of room gives up what was looked up longest ago rather than refusing,
 * it says which tools went, and it loads several at once because a job usually
 * needs two or three from the same system.
 *
 * The eviction is the part worth being careful about. "You cannot have any more"
 * is a dead end for a model - it will either give up or keep asking - where
 * forgetting is what a person does without noticing.
 */
@SpringBootTest
class ToolBudgetTest(@Autowired val searching: ToolSearchTools) {

    private val granted = listOf(
        ToolSpec("slack_postMessage", "Posts a message into a Slack channel."),
        ToolSpec("slack_readAttachment", "Reads a file somebody attached to a Slack message."),
        ToolSpec("jira_createIssue", "Files an issue in Jira."),
        ToolSpec("jira_searchIssues", "Finds issues in Jira by their fields."),
        ToolSpec("confluence_readPage", "Reads a page out of Confluence."),
        ToolSpec("confluence_writePage", "Writes a page into Confluence."),
    )

    private fun call(query: String) = ToolCall("1", ToolSearchTools.FIND, """{"query":"$query"}""")

    /**
     * A budget with the oldest given up first, which is what an insertion-ordered
     * set gives: what the agent is using now was found most recently.
     */
    private fun budget(found: MutableSet<String>, room: () -> Int) =
        searching.shed(granted, found, room) { wanted ->
            val going = found.take(wanted)
            found.removeAll(going.toSet())
            going
        }

    /* ------------------------------------------------- loading in bulk ----- */

    /**
     * A job usually needs two or three tools from the same system, and making
     * the agent search once per tool spends a round on each.
     */
    @Test
    fun `one search puts several in the agent's hands`() {
        val found = mutableSetOf<String>()

        budget(found) { 10 }.run(call("jira"))

        assertThat(found).containsExactlyInAnyOrder("jira_createIssue", "jira_searchIssues")
    }

    /* ---------------------------------------------------- the eviction ----- */

    @Test
    fun `a search with no room gives up what was looked up longest ago`() {
        // Two already held, and room for exactly those two.
        val found = mutableSetOf("slack_postMessage", "slack_readAttachment")
        var room = 0

        val said = budget(found) { room }.run(call("confluence page"))

        // The oldest went; the newer one stayed.
        assertThat(found).doesNotContain("slack_postMessage")
        assertThat(found).contains("slack_readAttachment")
        assertThat(found).contains("confluence_readPage")
        // And it says so, because a tool the agent called two turns ago has just
        // gone - told, it searches again rather than concluding it has lost the
        // ability.
        assertThat(said).contains("no longer in your hands").contains("slack_postMessage")
        assertThat(room).isEqualTo(0)
    }

    /**
     * Nothing is given up where there was room, which is most searches: the
     * eviction is for a budget that is actually full.
     */
    @Test
    fun `nothing is given up while there is room`() {
        val found = mutableSetOf("slack_postMessage")

        val said = budget(found) { 10 }.run(call("jira"))

        assertThat(found).contains("slack_postMessage")
        assertThat(said).doesNotContain("no longer in your hands")
    }

    /**
     * A query matching one tool costs one forgotten rather than clearing the
     * shelf: what is asked for is what this search actually wants.
     */
    @Test
    fun `a search for one thing costs one`() {
        val found = mutableSetOf("slack_postMessage", "slack_readAttachment", "jira_createIssue")

        budget(found) { 0 }.run(call("confluence writePage"))

        // One went to make room for the one match that ranked highest.
        assertThat(found).hasSize(3)
        assertThat(found).doesNotContain("slack_postMessage")
    }

    /**
     * An agent whose whole budget is its core tools has nothing to give up, and
     * is told that rather than being handed an empty list to puzzle over.
     */
    @Test
    fun `a budget with nothing to give up says so`() {
        val found = mutableSetOf<String>()

        val said = searching.shed(granted, found, room = { 0 }).run(call("jira"))

        assertThat(said).contains("no room for another tool")
        assertThat(found).isEmpty()
    }
}
