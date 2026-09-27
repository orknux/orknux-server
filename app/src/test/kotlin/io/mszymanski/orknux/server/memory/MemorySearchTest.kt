package io.mszymanski.orknux.server.memory

import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.chat.AgentBriefing
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.OffsetDateTime

/**
 * Finding what the workspace wrote down, and knowing it is there.
 *
 * Two complaints, and they are the same one seen from either end: the search was
 * too strict to find anything, and the agent never looked.
 *
 * The search was a single `contains` over the whole query, which only answers a
 * question somebody already knows the answer to. "order-processing-service is
 * abbreviated as OPS" was not found by "what does OPS stand for", by "OPS
 * abbreviation", or by anything but a fragment of its own text - so an agent
 * that had written something down could not find it again and said it knew
 * nothing. It failed invisibly: an empty list looks the same whether nothing
 * matched or nothing is there.
 *
 * And the briefing said nothing about memory at all, on the reasoning that it
 * "is looked up when it turns out to be needed" - while nothing ever told the
 * agent it would turn out to be needed. It had a tool description among twenty
 * others, so it searched when somebody said "check your memory" and never
 * otherwise.
 *
 * Makes its own workspace and catalogue, and empties the catalogue each time.
 */
@SpringBootTest
class MemorySearchTest(
    @Autowired val memoryTool: MemoryTool,
    @Autowired val briefing: AgentBriefing,
    @Autowired val catalogs: MemoryCatalogRepository,
    @Autowired val memories: MemoryRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private lateinit var agent: Agent
    private lateinit var catalog: MemoryCatalog

    @BeforeEach
    fun make() {
        val workspaceId = requireNotNull(
            (workspaces.findByName("searching memory") ?: workspaces.save(Workspace(name = "searching memory"))).id,
        )
        catalog = catalogs.findByWorkspaceIdAndName(workspaceId, "What the desk knows")
            ?: catalogs.save(
                MemoryCatalog(workspaceId = workspaceId, name = "What the desk knows", createdBy = "test"),
            )
        memories.deleteAll(
            memories.findByCatalogIdInOrderByLastModifiedAtDesc(setOf(requireNotNull(catalog.id))),
        )
        agents.deleteAll(agents.findAll().filter { it.workspaceId == workspaceId })
        agent = agents.save(
            Agent(
                workspaceId = workspaceId,
                name = "Support responder",
                type = AgentType.LLM,
                memoryCatalogs = mutableListOf(catalog.name),
            ),
        )
    }

    private fun remember(title: String, content: String) {
        val now = OffsetDateTime.now()
        memories.save(
            Memory(
                catalogId = requireNotNull(catalog.id),
                title = title,
                content = content,
                createdAt = now,
                createdBy = "alice",
                lastModifiedAt = now,
                lastModifiedBy = "alice",
            ),
        )
    }

    private fun found(query: String) = memoryTool.search(agent, query, catalog = null).map { it.title }

    /* ------------------------------------------------- finding it at all --- */

    /**
     * The exact case that was reported: written down, and then unfindable by
     * every way anybody would ask for it.
     */
    @Test
    fun `a memory is found by the words of a question rather than by its own text`() {
        remember("OPS abbreviation", "order-processing-service is abbreviated as OPS.")

        assertThat(found("what does OPS stand for")).contains("OPS abbreviation")
        assertThat(found("OPS abbreviation")).contains("OPS abbreviation")
        assertThat(found("abbreviations")).contains("OPS abbreviation")
        // And still by a fragment of its own text, which is all it could do before.
        assertThat(found("order-processing-service")).contains("OPS abbreviation")
    }

    /**
     * Any word rather than all of them, which is the opposite of the connection
     * search: a question put to memory is a handful of words about a subject and
     * most of them will not appear anywhere.
     */
    @Test
    fun `a question is answered even though most of its words appear nowhere`() {
        remember("Billing export", "The billing export runs at 02:00 UTC.")

        assertThat(found("when does the billing export run in the morning"))
            .contains("Billing export")
    }

    /* -------------------------------------------------------- the ranking --- */

    /**
     * A title is what somebody filed it under, so a memory called "OPS
     * abbreviation" is more about OPS than one that mentions it in passing.
     */
    @Test
    fun `the memory a question is about comes above the one that mentions it`() {
        remember("Deployment rota", "The on-call rota covers OPS deployments out of hours.")
        remember("OPS abbreviation", "order-processing-service is abbreviated as OPS.")

        assertThat(found("OPS")).first().isEqualTo("OPS abbreviation")
    }

    @Test
    fun `carrying more of the words ranks above carrying one`() {
        remember("Status page", "The status page is written by hand.")
        remember("Billing export", "The billing export runs at 02:00 UTC and writes the status page.")

        assertThat(found("billing export status")).first().isEqualTo("Billing export")
    }

    /* ------------------------------------------------------- what it drops -- */

    /**
     * `contains` alone made "ops" match "operations" and "cops", which is how a
     * three-letter query came back with the whole catalogue.
     */
    @Test
    fun `a short word does not match every longer word it happens to sit inside`() {
        remember("Operations handbook", "Cooperation between teams is covered in the operations handbook.")

        assertThat(found("OPS")).isEmpty()
    }

    /**
     * A question is mostly "what", "the" and "is". Scoring those ranks a memory
     * by how wordy it is rather than by what it is about.
     */
    @Test
    fun `words that say nothing about a subject find nothing`() {
        remember("Billing export", "The billing export runs at 02:00 UTC.")

        assertThat(found("what is the")).isEmpty()
    }

    @Test
    fun `nothing asked for is still everything there, newest first`() {
        remember("Billing export", "The billing export runs at 02:00 UTC.")
        remember("Status page", "The status page is written by hand.")

        assertThat(memoryTool.search(agent, query = null, catalog = null)).hasSize(2)
        assertThat(memoryTool.search(agent, query = "  ", catalog = null)).hasSize(2)
    }

    /* ------------------------------------------ and that the agent knows --- */

    /**
     * The other half of the complaint. Nothing told the agent there was anything
     * to find, so it searched when it was told to and never otherwise.
     */
    @Test
    fun `the briefing says how much is written down and when to go and look`() {
        remember("Billing export", "The billing export runs at 02:00 UTC.")
        remember("Status page", "The status page is written by hand.")

        val said = briefing.of(agent).orEmpty()

        assertThat(said).contains("written 2 things down")
        assertThat(said).contains(catalog.name)
        // Named, not listed: what the memories say is what the tool is for.
        assertThat(said).doesNotContain("02:00")
        // And told when, because "you may search" is a capability and changes
        // nothing about what an agent does.
        assertThat(said).contains("memory_search before answering")
        assertThat(said).contains("memory_save")
    }

    @Test
    fun `an empty catalogue is not announced, because there is nothing to find`() {
        val said = briefing.of(agent).orEmpty()

        // The paragraph that invites a search. The tool itself is still named in
        // the list of every tool the agent holds (#481), which is not an invitation.
        assertThat(said).doesNotContain("Search it with memory_search")
    }

    @Test
    fun `an agent granted no catalogue is told nothing about memory`() {
        remember("Billing export", "The billing export runs at 02:00 UTC.")
        val ungranted = agents.save(
            Agent(workspaceId = agent.workspaceId, name = "No memory", type = AgentType.LLM),
        )

        assertThat(briefing.of(ungranted).orEmpty()).doesNotContain("memory_search")
    }
}
