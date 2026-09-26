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
@org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
@org.springframework.security.test.context.support.WithMockUser(username = "alice", roles = ["ADMINS"])
class ToolSearchTest(
    @Autowired val graphQlTester: org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester,
    @Autowired val searching: ToolSearchTools,
    @Autowired val sessions: LlmSessionRecorder,
    @Autowired val workspaces: WorkspaceRepository,
    /** For how many tools are named outright, which Admin -> Settings decides. Issue #442. */
    @Autowired val settings: io.mszymanski.orknux.server.attachment.InstallationSettings,
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

    /**
     * By the word, not by the letters. "uploads" used to miss `slack_upload`
     * while "up" matched half the list; a name is read as the words it is made
     * of and a word meets another from a four-letter prefix on. Issue #451.
     */
    @Test
    fun `a query meets a tool by its words, with a prefix either way, and never by a scrap of letters`() {
        val tools = granted + listOf(
            ToolSpec("slack_uploadBinary", "Puts bytes into Slack as a file."),
            ToolSpec("slack_readAttachment", "Reads a file somebody attached."),
        )

        fun found(query: String): List<String> {
            val held = mutableSetOf<String>()
            searching.shed(tools, held, room = { 10 }).run(call(query))
            return held.toList()
        }

        assertThat(found("uploads")).containsExactlyInAnyOrder("slack_uploadBinary")
        assertThat(found("attachment")).describedAs("camel case is split into words")
            .containsExactlyInAnyOrder("slack_readAttachment")
        assertThat(found("up")).describedAs("two letters are not a word of anything").isEmpty()
        assertThat(found("post message")).first().isEqualTo("slack_postMessage")
    }

    /**
     * One call, several searches. Issue #464: a job that touches two systems
     * used to cost a round each, and the room is shared, so the first search
     * must not spend all of it.
     */
    @Test
    fun `several searches in one call each bring back their own tools`() {
        val found = mutableSetOf<String>()
        val shed = searching.shed(granted, found, room = { 4 })

        val said = shed.run(call("send a message in slack; file a jira issue"))

        assertThat(found).contains("slack_postMessage", "jira_createIssue")
        assertThat(said).contains("from your next message onwards")

        // A new line does the same, and a search that matched nothing is named.
        val second = mutableSetOf<String>()
        val again = searching.shed(granted, second, room = { 4 })
            .run(call("read a page in confluence\\nkubernetes"))
        assertThat(second).contains("confluence_readPage")
        assertThat(again).contains("Nothing matched: \"kubernetes\"")
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

    /**
     * Six tools are not "too many to be listed"; seventeen were not either, and a
     * model told so had to guess words for a search when the names would have
     * fitted in the sentence. Issue #442.
     */
    @Test
    fun `few enough tools are named in the description and in a miss, so the model asks by name`() {
        val shed = searching.shed(granted, mutableSetOf(), room = { 10 })
        val description = shed.specs().single().description

        assertThat(description).contains("You hold 6 of them: confluence_readPage, deploy_rollback, jira_createIssue")
        assertThat(description).contains("Ask for one by name")
        assertThat(description).doesNotContain("too many to be listed")

        assertThat(shed.run(call("kubernetes"))).contains("They are: confluence_readPage, deploy_rollback")
    }

    @Test
    fun `a long list is still counted rather than named`() {
        val many = (1..60).map { ToolSpec("tool_$it", "Tool number $it.") }
        val shed = searching.shed(many, mutableSetOf(), room = { 10 })

        assertThat(shed.specs().single().description).contains("You hold 60 of them - too many to be listed at once")
        assertThat(shed.run(call("kubernetes"))).contains("Try the name of the system")
    }

    /**
     * Where "few enough" ends is the installation's to say, not the source's:
     * forty names are nothing to a large model and a wall to a small one. The
     * default is the configured one; zero switches the naming off. Issue #442.
     */
    @Test
    fun `how many are named is a setting, and zero names none`() {
        assertThat(settings.toolsNamedInSearch()).isEqualTo(settings.toolsNamedInSearchConfigured())
        try {
            settings.setToolsNamedInSearch(0, "alice")
            val description = searching.shed(granted, mutableSetOf(), room = { 10 }).specs().single().description
            assertThat(description).contains("You hold 6 of them - too many to be listed at once")

            settings.setToolsNamedInSearch(6, "alice")
            assertThat(searching.shed(granted, mutableSetOf(), room = { 10 }).specs().single().description)
                .contains("You hold 6 of them: confluence_readPage")

            org.junit.jupiter.api.assertThrows<io.mszymanski.orknux.server.attachment.ToolsNamedOutOfRangeException> {
                settings.setToolsNamedInSearch(501, "alice")
            }
            // And over the door the screen uses: a sentence, not a correlation
            // id - the resolver has to know this refusal by name.
            graphQlTester.document("""mutation { setToolsNamedInSearch(count: 501) { toolsNamedInSearch } }""")
                .execute().errors().expect { it.message!!.contains("between 0 and 500") }.verify()
            graphQlTester.document("""mutation { setToolsNamedInSearch(count: 7) { toolsNamedInSearch } }""")
                .execute().path("setToolsNamedInSearch.toolsNamedInSearch").entity(Int::class.java).isEqualTo(7)
        } finally {
            settings.setToolsNamedInSearch(settings.toolsNamedInSearchConfigured(), "alice")
        }
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
