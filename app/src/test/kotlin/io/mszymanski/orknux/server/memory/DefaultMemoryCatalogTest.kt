package io.mszymanski.orknux.server.memory

import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * Every workspace has one memory catalog, and it stays.
 *
 * A catalog is what an agent is granted and what a memory is filed into, so a
 * workspace with none has nowhere for either: `memory_save` refuses because
 * there is nothing to write to, and `AgentTools` does not offer the two memory
 * tools at all. The first thing anybody had to do before an agent could remember
 * anything was therefore a piece of setup nobody is told about.
 *
 * So a workspace arrives with one and cannot be left without it. What is pinned
 * here is the floor and the one way out of it: the catalog cannot be deleted,
 * and it can be renamed - which is why the thing that marks it is a flag rather
 * than its name.
 *
 * Makes its own workspace and leaves it.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class DefaultMemoryCatalogTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val catalogs: MemoryCatalogRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    /** A workspace made through the API, which is the path that seeds one. */
    private fun workspace(name: String): Long {
        workspaces.findByName(name)?.let { return requireNotNull(it.id) }
        return graphQlTester
            .document("""mutation { createWorkspace(input: { name: "$name" }) { id } }""")
            .execute()
            .path("createWorkspace.id")
            .entity(String::class.java)
            .get()
            .toLong()
    }

    private fun only(workspaceId: Long): MemoryCatalog =
        catalogs.findByWorkspaceIdOrderByNameAsc(workspaceId).single { it.isDefault }

    @Test
    fun `a new workspace arrives with one, and it is the one that stays`() {
        val id = workspace("memory floor")

        val held = catalogs.findByWorkspaceIdOrderByNameAsc(id)
        assertThat(held).isNotEmpty()

        val standing = only(id)
        assertThat(standing.name).isEqualTo(FIRST_MEMORY_CATALOG)
        // Made by the workspace rather than by whoever made the workspace: it
        // arrives with it, the way the default connections and issue types do.
        assertThat(standing.createdBy).isEqualTo("system")
    }

    @Test
    fun `the screen is told which one it is`() {
        val id = workspace("memory floor told")

        graphQlTester
            .document("""query { memoryCatalogs(workspaceId: $id) { name isDefault } }""")
            .execute()
            .path("memoryCatalogs[?(@.isDefault == true)].name")
            .entityList(String::class.java)
            .containsExactly(FIRST_MEMORY_CATALOG)
    }

    /**
     * The floor, which is the whole point: it cannot be taken away again.
     *
     * Refused at the mutation rather than only left off the screen, because the
     * same call is reachable from the API and a form is not a boundary.
     */
    @Test
    fun `it cannot be deleted, and the refusal says what can be done instead`() {
        val id = workspace("memory floor kept")
        val standing = only(id)

        graphQlTester
            .document("""mutation { deleteMemoryCatalog(id: ${standing.id}) }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors).singleElement().satisfies({
                    assertThat(it.message).contains("cannot be deleted").contains("can be renamed")
                })
            }

        assertThat(catalogs.findById(requireNotNull(standing.id))).isPresent()
    }

    /**
     * And the way out of a name nobody likes.
     *
     * Which is why what marks it is a flag: a catalog renamed to "What the desk
     * knows" is still the one that stays, and a rule written on the name would
     * have lost it at the first rename.
     */
    @Test
    fun `it can be renamed and is still the one that stays`() {
        val id = workspace("memory floor renamed")
        val standing = only(id)

        graphQlTester
            .document("""mutation { renameMemoryCatalog(id: ${standing.id}, name: "What the desk knows") {
                 name isDefault
               } }""")
            .execute()
            .path("renameMemoryCatalog.name").entity(String::class.java).isEqualTo("What the desk knows")
            .path("renameMemoryCatalog.isDefault").entity(Boolean::class.java).isEqualTo(true)

        // Still refused under its new name, which is the half a rule written on
        // the name would have got wrong.
        graphQlTester
            .document("""mutation { deleteMemoryCatalog(id: ${standing.id}) }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors).singleElement().satisfies({
                    assertThat(it.message).contains("What the desk knows")
                })
            }
    }

    @Test
    fun `another catalog beside it is still ordinary and can be removed`() {
        val id = workspace("memory floor plus one")

        val made = graphQlTester
            .document("""mutation { createMemoryCatalog(workspaceId: $id, name: "Customers") {
                 id isDefault
               } }""")
            .execute()
            .path("createMemoryCatalog.isDefault").entity(Boolean::class.java).isEqualTo(false)
            .path("createMemoryCatalog.id").entity(String::class.java).get()

        graphQlTester
            .document("""mutation { deleteMemoryCatalog(id: $made) }""")
            .execute().path("deleteMemoryCatalog").entity(Boolean::class.java).isEqualTo(true)

        // And the workspace is left with the one it always has.
        assertThat(catalogs.findByWorkspaceIdOrderByNameAsc(id).count { it.isDefault }).isEqualTo(1)
    }
}
