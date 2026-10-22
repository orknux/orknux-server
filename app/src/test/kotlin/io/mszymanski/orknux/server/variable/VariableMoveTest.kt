package io.mszymanski.orknux.server.variable

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * A variable moved to another catalog. One moved into a catalog that already
 * held its name answered INTERNAL_ERROR - the move kept the name, so nothing
 * checked it, and the database's unique key refused the row instead.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class VariableMoveTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
) {

    @Test
    fun `a move into a catalog holding the name is refused by name, and one that fits moves`() {
        val workspace = requireNotNull(workspaces.save(Workspace(name = "variable move ${System.nanoTime()}")).id)
        fun catalog(name: String) = graphQlTester.document(
            """mutation { createVariableCatalog(workspaceId: $workspace, name: "$name") { id } }""",
        ).execute().path("createVariableCatalog.id").entity(Long::class.java).get()
        fun variable(catalog: Long, name: String) = graphQlTester.document(
            """mutation { createVariable(input: { workspaceId: $workspace, catalogId: $catalog, name: "$name", type: STRING, kind: VALUE }) { id } }""",
        ).execute().path("createVariable.id").entity(Long::class.java).get()

        val first = catalog("First")
        val second = catalog("Second")
        val moving = variable(first, "region")
        variable(second, "region")

        graphQlTester.document("""mutation { updateVariable(id: $moving, input: { catalogId: $second }) { id } }""")
            .execute().errors().satisfy {
                assertThat(it.single().extensions["code"]).isEqualTo("VariableNameTaken")
            }

        val third = catalog("Third")
        graphQlTester.document("""mutation { updateVariable(id: $moving, input: { catalogId: $third }) { catalogId } }""")
            .execute().path("updateVariable.catalogId").entity(Long::class.java).isEqualTo(third)
    }
}
