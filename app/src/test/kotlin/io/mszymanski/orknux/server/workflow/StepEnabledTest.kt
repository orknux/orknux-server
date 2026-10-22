package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * A run's step says whether its node was switched on, so the run page can draw
 * the way through a switched-off node - skipped, and passed through - as
 * travelled, unlike a node the run never reached.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class StepEnabledTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
) {

    @Test
    fun `a switched-off node's step says so and is skipped, and a switched-on one says so too`() {
        val workspaceId = requireNotNull(workspaces.save(Workspace(name = "step-enabled-${System.nanoTime()}")).id)
        val workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Past a switched-off node" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()

        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "start", kind: TRIGGER, name: "Start", x: 0, y: 0 },
                  { key: "off", kind: OBJECT, name: "Off", enabled: false, x: 200, y: 0, mappings: [] },
                  { key: "after", kind: OBJECT, name: "After", x: 400, y: 0, mappings: [] }
                ],
                edges: [{ source: "start", target: "off" }, { source: "off", target: "after" }]
              }) { workflowId }
            }
            """,
        ).execute().path("saveWorkflowGraph.workflowId").hasValue()

        graphQlTester.document(
            """mutation { startExecution(workspaceId: $workspaceId, workflowId: $workflowId, input: "{}") { steps { key status enabled } } }""",
        ).execute()
            .path("startExecution.steps[?(@.key == 'off')].status").entityList(String::class.java).containsExactly("SKIPPED")
            .path("startExecution.steps[?(@.key == 'off')].enabled").entityList(Boolean::class.java).containsExactly(false)
            .path("startExecution.steps[?(@.key == 'after')].enabled").entityList(Boolean::class.java).containsExactly(true)
    }
}
