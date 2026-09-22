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
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.core.context.SecurityContextHolder

/**
 * An agent writing a memory with nobody signed in.
 *
 * Which is most of them. An agent answering a Slack message, running as a
 * workflow node or working a task has no authenticated user behind it - there is
 * no request and nobody is watching. Only a chat has one.
 *
 * `memory_save` wrote its audit line with `record`, which reads the signed-in
 * user and *fails* where there is none. So every save from those three came back
 * "No authenticated user to attribute this change to" - and because the audit
 * throws inside the same transaction as the write, the memory was rolled back
 * with it. The agent then either said it could not remember, or said it had and
 * nothing was there. It worked from a chat, which is why it looked intermittent.
 *
 * So this runs with the security context deliberately empty, which is the state
 * the three surfaces are actually in. `WithMockUser` would have hidden the whole
 * bug, which is why it is not here.
 */
@SpringBootTest
class AgentMemorySaveTest(
    @Autowired val memoryTool: MemoryTool,
    @Autowired val catalogs: MemoryCatalogRepository,
    @Autowired val memories: MemoryRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private lateinit var agent: Agent
    private lateinit var catalog: MemoryCatalog

    @BeforeEach
    fun make() {
        // Nobody signed in, which is the whole point: a Slack message, a
        // workflow node and a task all arrive like this.
        SecurityContextHolder.clearContext()

        val workspaceId = requireNotNull(
            (workspaces.findByName("agent memory") ?: workspaces.save(Workspace(name = "agent memory"))).id,
        )
        catalog = catalogs.findByWorkspaceIdAndName(workspaceId, "What the desk knows")
            ?: catalogs.save(MemoryCatalog(workspaceId = workspaceId, name = "What the desk knows", createdBy = "test"))
        /*
         * Emptied with the bulk forms, which carry their own transaction: the
         * derived `deleteByCatalogId` and a `delete` per row both need one, and
         * this test deliberately runs outside a transaction so that what the
         * save commits is what is read back.
         */
        memories.deleteAll(memories.findByCatalogIdInOrderByLastModifiedAtDesc(setOf(requireNotNull(catalog.id))))
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

    @Test
    fun `an agent with nobody signed in can still write down what it was told`() {
        val saved = memoryTool.save(
            agent,
            catalog = catalog.name,
            title = "OPS abbreviation",
            content = "order-processing-service is abbreviated as OPS.",
        )

        assertThat(saved.title).isEqualTo("OPS abbreviation")
        assertThat(saved.updated).isFalse()

        // The row is there. This is the assertion the bug failed: the audit
        // threw inside the transaction and took the write with it.
        val held = memories.findByCatalogIdInOrderByLastModifiedAtDesc(setOf(requireNotNull(catalog.id)))
        assertThat(held).singleElement().satisfies({
            assertThat(it.title).isEqualTo("OPS abbreviation")
            // Signed by the agent, which is the true answer to who wrote it.
            assertThat(it.createdBy).isEqualTo(agent.name)
        })
    }

    @Test
    fun `and it can read back what it just wrote`() {
        memoryTool.save(agent, catalog.name, "OPS abbreviation", "order-processing-service is abbreviated as OPS.")

        // By a word that is in it, and by nothing at all - which is what the
        // tool says to do to see what a catalog holds.
        assertThat(memoryTool.search(agent, query = "OPS", catalog = null)).hasSize(1)
        assertThat(memoryTool.search(agent, query = null, catalog = null)).hasSize(1)
    }

    /**
     * The log still says who, which is the reason the audit call was there.
     *
     * `recordAutomated` is what a thing done with nobody asking uses - the same
     * call a trigger starting a workflow makes - and the actor stands where a
     * user id normally does, so the line names the agent rather than a person
     * who was not there.
     */
    @Test
    fun `the audit line names the agent rather than nobody`() {
        memoryTool.save(agent, catalog.name, "OPS abbreviation", "order-processing-service is abbreviated as OPS.")

        val lines = audit.findAll().filter { it.message.contains("OPS abbreviation") }
        assertThat(lines).isNotEmpty()
        assertThat(lines.map { it.userId }).contains(agent.name)
        assertThat(lines.map { it.message }).anySatisfy { assertThat(it).contains("by the agent ${agent.name}") }
    }

    @Test
    fun `writing the same title again updates it rather than doubling it`() {
        memoryTool.save(agent, catalog.name, "OPS abbreviation", "order-processing-service.")
        val again = memoryTool.save(agent, catalog.name, "OPS abbreviation", "order-processing-service is OPS.")

        assertThat(again.updated).isTrue()
        val held = memories.findByCatalogIdInOrderByLastModifiedAtDesc(setOf(requireNotNull(catalog.id)))
        assertThat(held).singleElement().satisfies({
            assertThat(it.content).isEqualTo("order-processing-service is OPS.")
            assertThat(it.lastModifiedBy).isEqualTo(agent.name)
        })
    }
}
