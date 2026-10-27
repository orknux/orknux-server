package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.server.memory.MemoryCatalog
import io.mszymanski.orknux.server.memory.MemoryCatalogRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * A catalog grant naming nothing in the agent's workspace is taken away, and a
 * renamed memory catalog takes its grants with it so none is left behind.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class DanglingCatalogGrantsTest(
    @Autowired val sweep: DanglingCatalogGrants,
    @Autowired val agents: AgentRepository,
    @Autowired val catalogs: MemoryCatalogRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
) {

    private fun workspace(): Long = requireNotNull(workspaces.save(Workspace(name = "grants-${System.nanoTime()}")).id)

    @Test
    fun `a grant of a catalog that is not in the workspace is taken away, and one that is stays`() {
        val id = workspace()
        catalogs.save(MemoryCatalog(workspaceId = id, name = "NFP"))
        val agent = agents.save(
            Agent(workspaceId = id, name = "Desk", type = AgentType.LLM).also {
                it.memoryCatalogs = mutableListOf("NFP", "OKO")
                it.variableCatalogs = mutableListOf("Elsewhere")
            },
        )

        val gone = sweep.clear(agent)

        assertThat(gone).containsExactlyInAnyOrder("memory catalog OKO", "values of Elsewhere")
        val held = requireNotNull(agents.findByIdOrNull(requireNotNull(agent.id)))
        assertThat(held.memoryCatalogs).containsExactly("NFP")
        assertThat(held.variableCatalogs).isEmpty()
    }

    @Test
    fun `renaming a memory catalog renames the grant`() {
        val id = workspace()
        val catalog = catalogs.save(MemoryCatalog(workspaceId = id, name = "OKO"))
        val agent = agents.save(Agent(workspaceId = id, name = "Desk", type = AgentType.LLM).also { it.memoryCatalogs = mutableListOf("OKO") })

        graphQlTester.document("""mutation { renameMemoryCatalog(id: ${catalog.id}, name: "OKO team") { name } }""")
            .execute().path("renameMemoryCatalog.name").entity(String::class.java).isEqualTo("OKO team")

        assertThat(requireNotNull(agents.findByIdOrNull(requireNotNull(agent.id))).memoryCatalogs).containsExactly("OKO team")
    }
}
