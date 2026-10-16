package io.mszymanski.orknux.server.memory

import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * Memory: catalogs, what is in them, and what an agent may read of it.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class MemoryAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val catalogs: MemoryCatalogRepository,
    @Autowired val memories: MemoryRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val tool: MemoryTool,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        memories.deleteAll()
        catalogs.deleteAll()
        agents.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    @Test
    fun `a catalog counts what is in it, and the count is what the badge shows`() {
        val id = catalog("API Documentation")
        memory(id, "REST API Authentication Flow", "Bearer JWT from the auth server. Tokens expire in 1 hour.")
        memory(id, "Stripe Webhook Event Processing", "Verify signatures natively.")

        graphQlTester.document("""{ memoryCatalogs(workspaceId: $workspaceId) { id name memoryCount } }""")
            .execute()
            .path("memoryCatalogs[0].name").entity(String::class.java).isEqualTo("API Documentation")
            .path("memoryCatalogs[0].memoryCount").entity(Int::class.java).isEqualTo(2)

        assertThat(audit.findAll().map { it.message }).contains("Memory catalog API Documentation added")
    }

    @Test
    fun `a catalog lists with no filters at all`() {
        val id = catalog("API Documentation")
        memory(id, "REST API Authentication Flow", "Bearer JWT from the auth server.")

        // The unfiltered call, and nothing typed before it. JPQL with
        // `:param IS NULL OR …` sends a null String Postgres cannot type, and
        // answers `function lower(bytea) does not exist` — but only when it is
        // the first execution, because a typed one first leaves a usable plan
        // behind. So this has to be the query that runs on its own.
        graphQlTester.document("""{ memories(catalogId: $id) { totalElements content { title } } }""")
            .execute()
            .path("memories.totalElements").entity(Int::class.java).isEqualTo(1)
    }

    @Test
    fun `search looks in the body as well as the title`() {
        val id = catalog("API Documentation")
        memory(id, "REST API Authentication Flow", "Bearer JWT obtained from the Orknux auth server.")
        memory(id, "Docker Compose Local Environment Setup", "Initialize schema migrations first.")

        graphQlTester.document("""{ memories(catalogId: $id, search: "jwt") { totalElements content { title } } }""")
            .execute()
            .path("memories.totalElements").entity(Int::class.java).isEqualTo(1)
            .path("memories.content[0].title").entity(String::class.java).isEqualTo("REST API Authentication Flow")

        // A blank search is no filter, which is what an empty box sends.
        graphQlTester.document("""{ memories(catalogId: $id, search: "") { totalElements } }""")
            .execute()
            .path("memories.totalElements").entity(Int::class.java).isEqualTo(2)
    }

    @Test
    fun `editing a memory does not rewrite who added it`() {
        val id = catalog("Code Standards")
        val memoryId = memory(id, "Monorepo Package Structure", "Use the @orknux/ prefix.")

        graphQlTester.document(
            """mutation { updateMemory(id: $memoryId, input: {
                 title: "Monorepo Package Structure", content: "Shared utilities live in package/shared."
               }) { createdBy lastModifiedBy } }""",
        ).execute()
            .path("updateMemory.createdBy").entity(String::class.java).isEqualTo("alice")
            .path("updateMemory.lastModifiedBy").entity(String::class.java).isEqualTo("alice")

        assertThat(memories.findAll().single().content).isEqualTo("Shared utilities live in package/shared.")
    }

    @Test
    fun `deleting a catalog takes what was in it`() {
        val id = catalog("Deployment Guides")
        memory(id, "Rollout", "Ship on a Tuesday.")

        graphQlTester.document("""mutation { deleteMemoryCatalog(id: $id) }""")
            .execute().path("deleteMemoryCatalog").entity(Boolean::class.java).isEqualTo(true)

        assertThat(memories.findAll()).isEmpty()
        assertThat(audit.findAll().map { it.message })
            .contains("Memory catalog Deployment Guides removed, with 1 memory")
    }

    /**
     * Issue #166: a catalog an agent may read cannot be deleted out from under it.
     *
     * The grant is a **name**, so nothing would have been left dangling to
     * notice — [MemoryTool] drops a name that matches no catalog on purpose, so
     * the agent would simply have stopped knowing any of this while its own
     * screen went on listing the grant. Worse, the name is re-bindable: a new
     * catalog called Notes tomorrow is silently handed to everyone still holding
     * it. The refusal names the agent because taking the grant off it is the way
     * out.
     */
    @Test
    fun `a memory catalog an agent is granted cannot be deleted`() {
        val id = catalog("Notes")
        memory(id, "Standup", "Nine o'clock, and keep it short.")
        agents.save(
            Agent(
                workspaceId = workspaceId,
                name = "Reviewer",
                type = AgentType.LLM,
                memoryCatalogs = mutableListOf("Notes"),
            ),
        )

        graphQlTester.document("""mutation { deleteMemoryCatalog(id: $id) }""")
            .execute()
            .errors()
            .satisfy { errors ->
                assertThat(errors.single().message)
                    .isEqualTo("Notes is granted to the agent Reviewer, so it cannot be deleted")
            }

        assertThat(catalogs.findAll().map { it.name }).containsExactly("Notes")
        assertThat(memories.findAll().map { it.title }).containsExactly("Standup")
    }

    /**
     * And the guard is a guard, not a ban.
     *
     * The agent is there and holds grants — just not this name — so what is
     * being checked is that the question is about the catalog's own name, not
     * that the workspace happens to have no agents in it.
     */
    /**
     * Granted by default: a new agent holds every memory catalog its workspace
     * has, and a catalog made later reaches the agents already there - each can
     * be unticked on the agent. See DefaultGrants.
     */
    @Test
    fun `a new agent holds the workspace's memory catalogs, and a catalog made later reaches it`() {
        catalog("Runbooks")
        val agentId = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Reviewer", type: LLM }) { id memoryCatalogs } }""",
        ).execute().path("createAgent.memoryCatalogs").entityList(String::class.java).containsExactly("Runbooks")
            .path("createAgent.id").entity(Long::class.java).get()

        catalog("Incidents")

        assertThat(agents.findById(agentId).get().memoryCatalogs).containsExactlyInAnyOrder("Runbooks", "Incidents")
        assertThat(audit.findAll().map { it.message }).contains("Memory catalog Incidents granted to 1 agent by default")
    }

    @Test
    fun `a memory catalog nobody was granted still deletes`() {
        val id = catalog("Unused")
        agents.save(
            Agent(
                workspaceId = workspaceId,
                name = "Reviewer",
                type = AgentType.LLM,
                memoryCatalogs = mutableListOf("SomethingElse"),
            ),
        )

        graphQlTester.document("""mutation { deleteMemoryCatalog(id: $id) }""")
            .execute().path("deleteMemoryCatalog").entity(Boolean::class.java).isEqualTo(true)

        assertThat(catalogs.findAll()).isEmpty()
    }

    @Test
    fun `an agent reads the catalogs it was granted, and nothing else`() {
        val granted = catalog("API Documentation")
        val withheld = catalog("Team Conventions")
        memory(granted, "REST API Authentication Flow", "Bearer JWT from the auth server.")
        memory(withheld, "Standup", "Nine o'clock, and keep it short.")

        val agent = agents.save(
            Agent(
                workspaceId = workspaceId,
                name = "researcher",
                type = AgentType.LLM,
                memoryCatalogs = mutableListOf("API Documentation"),
            ),
        )

        val found = tool.search(agent, query = null, catalog = null)
        assertThat(found.map { it.title }).containsExactly("REST API Authentication Flow")
        assertThat(found.single().catalog).isEqualTo("API Documentation")
        // The grant is the whole of it: nothing from the catalog it does not hold.
        assertThat(tool.search(agent, query = "standup", catalog = null)).isEmpty()
    }

    @Test
    fun `an agent granted nothing reads nothing`() {
        val id = catalog("API Documentation")
        memory(id, "REST API Authentication Flow", "Bearer JWT.")
        val agent = agents.save(Agent(workspaceId = workspaceId, name = "blank", type = AgentType.LLM))

        assertThat(tool.catalogsFor(agent)).isEmpty()
        assertThat(tool.search(agent, query = null, catalog = null)).isEmpty()
    }

    /**
     * The editor opens a memory by id, and where it sits in its catalog is not
     * its business. This is the case that made the query worth having: the
     * screen used to scan a page of the catalog and filter, so anything past
     * that page could not be opened at all.
     */
    @Test
    fun `a memory opens by id, wherever it falls in its catalog`() {
        val catalogId = catalog("Runbooks")
        repeat(25) { memory(catalogId, "Note $it", "body $it") }
        val last = memory(catalogId, "Restarting the broker", "Drain first.")

        graphQlTester.document("""query { memory(id: $last) { id title catalogId } }""")
            .execute()
            .path("memory.title").entity(String::class.java).isEqualTo("Restarting the broker")
            .path("memory.catalogId").entity(Long::class.java).isEqualTo(catalogId)
    }

    /** A memory that is not there is absent, not an error. */
    @Test
    fun `asking for a memory that does not exist answers null`() {
        graphQlTester.document("""query { memory(id: 9999) { id } }""")
            .execute()
            .path("memory").valueIsNull()
    }

    private fun catalog(name: String): Long = graphQlTester.document(
        """mutation { createMemoryCatalog(workspaceId: $workspaceId, name: "$name") { id } }""",
    ).execute().path("createMemoryCatalog.id").entity(Long::class.java).get()

    private fun memory(catalogId: Long, title: String, content: String): Long = graphQlTester.document(
        """mutation { createMemory(input: { catalogId: $catalogId, title: "$title", content: "$content" }) { id } }""",
    ).execute().path("createMemory.id").entity(Long::class.java).get()
}
