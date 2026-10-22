package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.ExecutionService
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.StartExecutionInput
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.context.TestPropertySource

/**
 * A workspace variable inside a written value: `Hello {{variables.<id>}}`.
 * Filled when the step runs by the rules a reference to one is read by - the
 * workspace's own or a lent catalog's, never a secret - and refused on save
 * where it could not be filled.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
@TestPropertySource(properties = ["orknux.temporal.enabled=false"])
class PlaceholderVariablesTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val runs: ExecutionService,
    @Autowired val steps: ExecutionStepRepository,
) {

    private var workspaceId: Long = 0
    private var workflowId: Long = 0

    @BeforeEach
    fun make() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "greeting-${System.nanoTime()}")).id)
        workflowId = id("""mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Greet" }) { workflowId } }""", "createWorkflow.workflowId")
    }

    private fun id(document: String, path: String): Long =
        graphQlTester.document(document).execute().errors().verify().path(path).entity(Long::class.java).get()

    private fun variable(workspace: Long, name: String, value: String, kind: String = "VALUE"): Long {
        val catalog = catalog(workspace)
        return variableIn(workspace, catalog, name, value, kind)
    }

    private fun catalog(workspace: Long): Long =
        id("""mutation { createVariableCatalog(workspaceId: $workspace, name: "c-${System.nanoTime()}") { id } }""", "createVariableCatalog.id")

    private fun variableIn(workspace: Long, catalog: Long, name: String, value: String, kind: String = "VALUE"): Long = id(
        """mutation { createVariable(input: { workspaceId: $workspace, catalogId: $catalog, name: "$name",
             type: STRING, kind: $kind, value: "$value" }) { id } }""",
        "createVariable.id",
    )

    private fun save(value: String) = graphQlTester.document(
        """mutation { saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
             nodes: [{ key: "held", kind: OBJECT, name: "Greeting", outputName: "note", x: 0, y: 0,
               mappings: [{ name: "said", expression: "$value", mode: VALUE }] }],
             edges: [] }) { workflowId } }""",
    ).execute()

    private fun said(): String {
        val run = runs.startExecution(StartExecutionInput(workspaceId = workspaceId, workflowId = workflowId, trigger = ExecutionTrigger.MANUAL))
        return steps.findByExecutionIdOrderByOrderAsc(run.id).single { it.nodeKey == "held" }.output.orEmpty()
    }

    @Test
    fun `a written value holding a variable is saved, and the run writes in what the variable holds`() {
        val region = variable(workspaceId, "region", "westeurope")

        save("Deploying to {{variables.$region}} now").errors().verify()

        assertThat(said()).contains("Deploying to westeurope now")
    }

    @Test
    fun `a variable from a catalog lent to the workspace is written in too`() {
        val owner = requireNotNull(workspaces.save(Workspace(name = "platform-${System.nanoTime()}")).id)
        val lent = catalog(owner)
        val zone = variableIn(owner, lent, "zone", "eu-west-1")

        // Not lent yet: no variable this workspace can use.
        save("Zone {{variables.$zone}}").errors().satisfy { assertThat(it.single().message).contains("{{variables.$zone}}", "no variable this workspace can use") }

        graphQlTester.document(
            """mutation { shareWithWorkspace(input: { workspaceId: $owner, granteeWorkspaceId: $workspaceId, kind: VARIABLE_CATALOG, componentId: $lent }) { id } }""",
        ).execute().errors().verify()
        save("Zone {{ variables.$zone }}").errors().verify()

        assertThat(said()).contains("Zone eu-west-1")
    }

    @Test
    fun `a secret is refused on save, and an unknown id with it`() {
        val token = variable(workspaceId, "token", "sk-live-123", kind = "SECRET")

        save("Bearer {{variables.$token}}").errors().satisfy {
            assertThat(it.single().message).contains("{{variables.$token}}", "token is a secret").doesNotContain("sk-live-123")
        }
        save("Hello {{variables.987654321}}").errors().satisfy {
            assertThat(it.single().message).contains("{{variables.987654321}}", "no variable this workspace can use")
        }
        save("Hello {{variables.region}}").errors().satisfy {
            assertThat(it.single().message).contains("no variable this workspace can use")
        }
    }
}
