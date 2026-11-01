package io.mszymanski.orknux.server.action

import io.mszymanski.orknux.server.condition.WorkflowConditionRepository
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

/** A workspace's JavaScript: what the list shows, and what the editor saves. */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class FunctionAPITest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val actions: WorkflowActionRepository,
    @Autowired val conditions: WorkflowConditionRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        actions.deleteAll()
        // A condition that asks a function holds it, so conditions go first.
        conditions.deleteAll()
        functions.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    @Test
    fun `a new function starts from a stub that runs, and reads as its signature`() {
        graphQlTester.document(
            """
            mutation {
              createFunction(input: {
                workspaceId: $workspaceId, name: "transformPayload", returnType: MAP,
                params: [{ name: "input", type: MAP }, { name: "format", type: STRING }]
              }) { name signature source returnType lastModifiedBy }
            }
            """,
        ).execute()
            .path("createFunction.signature").entity(String::class.java)
            .isEqualTo("(input: map, format: string)")
            .path("createFunction.lastModifiedBy").entity(String::class.java).isEqualTo("alice")
            .path("createFunction.source").entity(String::class.java)
            .satisfies { assertThat(it).contains("export default async function transformPayload(input, format)") }

        assertThat(audit.findAll().map { it.message }).contains("Function transformPayload created")
    }

    @Test
    fun `a workspace function may not take the name of one the release embeds`() {
        functions.save(
            WorkflowFunction(
                workspaceId = null,
                name = "charts_render",
                scope = FunctionScope.EMBEDDED,
                source = "export default async function charts_render() {}",
            ),
        )

        graphQlTester.document(
            """mutation { createFunction(input: { workspaceId: $workspaceId, name: "charts_render" }) { id } }""",
        ).execute().errors().expect { it.message?.contains("already exists") == true }.verify()

        val id = create("somethingElse")
        graphQlTester.document(
            """mutation { updateFunction(id: $id, input: { name: "charts_render" }) { id } }""",
        ).execute().errors().expect { it.message?.contains("already exists") == true }.verify()
    }

    @Test
    fun `the editor saves the code, and what will not parse is refused`() {
        val id = create("validateEmail")

        graphQlTester.document(
            """
            mutation {
              updateFunction(id: $id, input: {
                source: "export default function validateEmail(email) { return email.includes('@'); }",
                typescript: "export default function validateEmail(email) { return email.includes('@'); }",
                description: "Whether an address looks like one."
              }) { source description }
            }
            """,
        ).execute()
            .path("updateFunction.description").entity(String::class.java)
            .isEqualTo("Whether an address looks like one.")

        graphQlTester.document(
            // Both halves, so what is refused is the code failing to parse rather
            // than the pair being incomplete.
            """
            mutation {
              updateFunction(id: $id, input: {
                source: "export default function ( {", typescript: "export default function ( {"
              }) { id }
            }
            """,
        ).execute().errors().expect { it.message?.contains("Expected") == true || it.message?.contains("Error") == true }
            .verify()
    }

    @Test
    fun `validate answers rather than failing, and says where`() {
        graphQlTester.document(
            """
            mutation {
              validateFunctionSource(workspaceId: $workspaceId, source: "export default function ( {") {
                valid line
              }
            }
            """,
        ).execute()
            .path("validateFunctionSource.valid").entity(Boolean::class.java).isEqualTo(false)
            .path("validateFunctionSource.line").entity(Int::class.java).isEqualTo(1)

        graphQlTester.document(
            """
            mutation {
              validateFunctionSource(workspaceId: $workspaceId, source: "export default function ok() { return 1; }") {
                valid message
              }
            }
            """,
        ).execute().path("validateFunctionSource.valid").entity(Boolean::class.java).isEqualTo(true)
    }

    @Test
    fun `a name a script could not be called by is refused`() {
        graphQlTester.document(
            """mutation { createFunction(input: { workspaceId: $workspaceId, name: "not a name" }) { id } }""",
        ).execute().errors().expect { it.message?.contains("is not a name a script") == true }.verify()
    }

    @Test
    fun `a function an action still calls is not deleted`() {
        val id = create("transformPayload")
        graphQlTester.document(
            """
            mutation {
              createAction(input: {
                workspaceId: $workspaceId, name: "Transform Data", type: EXECUTE, subtype: FUNCTION, functionId: $id
              }) { id }
            }
            """,
        ).execute()

        graphQlTester.document("""mutation { deleteFunction(id: $id) }""")
            .execute().errors().expect { it.message?.contains("is called by Transform Data") == true }.verify()

        assertThat(functions.findAll()).hasSize(1)
    }

    /**
     * A rename follows into the scripts that called it by its name: the alias
     * on the import row and the `imports.name` in the importer's code move
     * together, so nobody is left reading a call to a function that no longer
     * exists. An alias the importer chose for itself would be left alone.
     */
    @Test
    fun `renaming a function renames the alias its importers called it by`() {
        val fooId = create("foo")

        val barId = graphQlTester.document(
            """
            mutation {
              createFunction(input: {
                workspaceId: $workspaceId, name: "bar",
                params: [{ name: "email", type: STRING }],
                imports: [{ functionId: $fooId, name: "foo" }],
                source: "export default async function bar(email) { return imports.foo(email); }",
                typescript: "export default async function bar(email: string) { return imports.foo(email); }"
              }) { id }
            }
            """,
        ).execute().path("createFunction.id").entity(Long::class.java).get()

        graphQlTester.document(
            """mutation { updateFunction(id: $fooId, input: { name: "fooRenamed" }) { name } }""",
        ).execute().path("updateFunction.name").entity(String::class.java).isEqualTo("fooRenamed")

        val bar = requireNotNull(functions.findById(barId).orElse(null))
        assertThat(bar.imports.single().importName).isEqualTo("fooRenamed")
        assertThat(bar.source).contains("imports.fooRenamed(email)")
        assertThat(bar.typescript).contains("imports.fooRenamed(email)")
        assertThat(audit.findAll().map { it.message })
            .contains("Function foo renamed to fooRenamed, followed in 1 importing script")
    }

    private fun create(name: String): Long = graphQlTester.document(
        """
        mutation {
          createFunction(input: {
            workspaceId: $workspaceId, name: "$name",
            params: [{ name: "email", type: STRING }]
          }) { id }
        }
        """,
    ).execute().path("createFunction.id").entity(Long::class.java).get()
}
