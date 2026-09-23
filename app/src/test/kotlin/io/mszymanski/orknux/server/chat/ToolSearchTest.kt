package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ProviderType
import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.connector.model.ToolSpec
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * An agent that finds the tool it needs rather than carrying all of them.
 *
 * Issue #368. Everything an agent held was declared on every call, and an agent
 * granted more than the provider accepts could not answer at all: OpenAI and
 * Azure refuse the whole request - "Invalid 'tools': array too long. Expected an
 * array with maximum length 128" - and what reached the person who asked was
 * that sentence, in a Slack thread. Nothing counted them until the provider did.
 *
 * Raising the cap is not available, because the number is the provider's. So
 * above the ceiling the agent is handed what it uses constantly and
 * `find_tools`, and what it searches for is put in the array for the next round.
 *
 * What is pinned here is the searching and the arithmetic around it: that a
 * reasonable query finds the right tool, that a search cannot fill the array
 * past what the provider will take, that what is found is written down against
 * the session so the next turn does not search again, and that the ceiling is
 * the provider's own number rather than one written at a call site.
 *
 * That the round loop declares what was found is `AgentConversation`'s own
 * arrangement - it rebuilds the request every round, which is the only reason
 * any of this can work.
 */
@SpringBootTest
class ToolSearchTest(
    @Autowired val searching: ToolSearchTools,
    @Autowired val sessions: LlmSessionRecorder,
    @Autowired val workspaces: WorkspaceRepository,
) {

    /*
     * Found rather than made again. A workspace name is unique and the table is
     * not emptied between classes, so a second save of the same name is a
     * constraint violation rather than a fixture.
     */
    private fun workspace(): Long = requireNotNull(
        (workspaces.findByName("tool search") ?: workspaces.save(Workspace(name = "tool search"))).id,
    )

    private val granted = listOf(
        ToolSpec("slack_postMessage", "Posts a message into a Slack channel."),
        ToolSpec("slack_readAttachment", "Reads a file somebody attached to a Slack message."),
        ToolSpec("jira_createIssue", "Files an issue in Jira."),
        ToolSpec("jira_searchIssues", "Finds issues in Jira by their fields."),
        ToolSpec("confluence_readPage", "Reads a page out of Confluence."),
        ToolSpec("deploy_rollback", "Puts the previous release back, and tells the channel it did."),
    )

    private fun call(query: String) = ToolCall("1", ToolSearchTools.FIND, """{"query":"$query"}""")

    /* --------------------------------------------------------- the searching */

    @Test
    fun `a query in words finds the tools it is about`() {
        val found = mutableSetOf<String>()
        val shed = searching.shed(granted, found, room = { 10 })

        val said = shed.run(call("send a message in slack"))

        assertThat(found).contains("slack_postMessage")
        assertThat(said).contains("slack_postMessage")
        // And they are declared from the next round, which is the only thing a
        // model can do with them - it cannot call what this request did not
        // declare.
        assertThat(said).contains("from your next message onwards")
    }

    /**
     * A name is worth more than a description.
     *
     * Somebody asking for "slack" means the tools called `slack_*`, not every
     * tool whose description happens to mention the word - which the rollback
     * tool's does.
     */
    @Test
    fun `the name counts for more than the description`() {
        val found = mutableSetOf<String>()
        val shed = searching.shed(granted, found, room = { 2 })

        shed.run(call("slack"))

        assertThat(found).containsExactlyInAnyOrder("slack_postMessage", "slack_readAttachment")
        assertThat(found).doesNotContain("deploy_rollback")
    }

    @Test
    fun `nothing matching says so rather than answering with everything`() {
        val found = mutableSetOf<String>()
        val shed = searching.shed(granted, found, room = { 10 })

        val said = shed.run(call("kubernetes"))

        assertThat(said).contains("Nothing among the 6 tools you hold")
        assertThat(found).isEmpty()
    }

    /**
     * The bound the whole feature exists for.
     *
     * A search that handed back more than the next request has space for would
     * fail the very call it was meant to make possible, with the provider's own
     * sentence about the array being too long.
     */
    @Test
    fun `a search cannot fill the array past what is left of it`() {
        val found = mutableSetOf<String>()
        val shed = searching.shed(granted, found, room = { 1 })

        val said = shed.run(call("jira slack confluence"))

        assertThat(found).hasSize(1)
        assertThat(said).contains("more matched and were left out")
    }

    @Test
    fun `the tool says how many are there and asks to be searched before giving up`() {
        val spec = searching.shed(granted, mutableSetOf(), room = { 10 }).specs().single()

        assertThat(spec.name).isEqualTo(ToolSearchTools.FIND)
        assertThat(spec.description).contains("You hold 6 of them")
        assertThat(spec.parameters.map { it.name }).containsExactly(ToolSearchTools.QUERY)
        assertThat(spec.parameters.single().required).isTrue()
    }

    /* ----------------------------------------------- what is found stays found */

    /**
     * Because an agent asked a follow-up would otherwise spend a round
     * rediscovering the tool it used a minute ago - and a second search is not
     * guaranteed to return what the first did, so what the agent believes it
     * can do would change under it between turns.
     */
    @Test
    fun `what was found is kept against the session and read back next turn`() {
        val session = sessions.open(workspace(), "test", "tool-search-check-${System.nanoTime()}")

        assertThat(sessions.toolsFound(session)).isEmpty()

        sessions.toolsFound(session, setOf("jira_createIssue", "slack_postMessage"))
        assertThat(sessions.toolsFound(session))
            .containsExactlyInAnyOrder("jira_createIssue", "slack_postMessage")

        // A turn that finds more keeps what the last one found: the whole set is
        // what is written, and the whole set is what comes back.
        sessions.toolsFound(session, sessions.toolsFound(session) + "confluence_readPage")
        assertThat(sessions.toolsFound(session)).hasSize(3)
    }

    @Test
    fun `an agent answering outside a session simply searches again`() {
        assertThat(sessions.toolsFound(null)).isEmpty()
        // And writing to no session is not an error, it is nothing.
        sessions.toolsFound(null, setOf("jira_createIssue"))
    }

    /* ------------------------------------------------------ whose number it is */

    /**
     * Asked of the provider rather than written at the call site. 128 is
     * OpenAI's and Azure's, and a caller holding a map of its own would be
     * holding one it had no way to keep true.
     */
    @Test
    fun `the ceiling is the provider's own number`() {
        assertThat(ProviderType.OPENAI.toolLimit).isEqualTo(128)
        assertThat(ProviderType.AZURE_OPENAI.toolLimit).isEqualTo(128)
        // Anthropic bounds a request by its size rather than by a count, so this
        // is a ceiling the product keeps rather than one it is given - a model
        // handed three hundred tools chooses badly long before a provider minds.
        assertThat(ProviderType.ANTHROPIC.toolLimit).isGreaterThan(ProviderType.OPENAI.toolLimit)
        assertThat(ProviderType.entries.map { it.toolLimit }).allSatisfy { assertThat(it).isGreaterThan(0) }
    }
}
