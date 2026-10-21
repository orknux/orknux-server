package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/** A node's outline is kept with the graph, and a node given none is the plain card. */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class NodeShapeTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
) {

    @Test
    fun `a node's shape is saved and read back, and none is the plain card`() {
        val workspaceId = requireNotNull(workspaces.save(Workspace(name = "shapes-${System.nanoTime()}")).id)
        val workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Shapes" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()

        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "a", kind: TRIGGER, name: "Start", shape: HEXAGON, x: 0, y: 0 },
                  { key: "b", kind: OBJECT, name: "Plain", x: 200, y: 0, mappings: [] }
                ],
                edges: [{ source: "a", target: "b" }]
              }) { workflowId }
            }
            """,
        ).execute()

        val shapes = graphQlTester.document("{ workflowGraph(workspaceId: $workspaceId, workflowId: $workflowId) { nodes { key shape } } }")
            .execute().path("workflowGraph.nodes").entityList(Map::class.java).get()
            .associate { it["key"] to it["shape"] }
        assertThat(shapes).containsEntry("a", "HEXAGON").containsEntry("b", null)
    }
}
