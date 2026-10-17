package io.mszymanski.orknux.server.workflow

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * A field an Object node names for itself may be an object defined in place,
 * as a saved object's property may: its fields are stored beside it, read
 * back as the editor draws them, and dropped where the field points at a
 * saved object instead.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class NodeFieldInlineTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private var workflowId: String = ""

    @BeforeEach
    fun make() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "field-inline-${System.nanoTime()}")).id)
        workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Fields" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(String::class.java).get()
    }

    @Test
    fun `a node field that is an object defined in place keeps its fields, and one pointing at a saved object does not`() {
        val saved = graphQlTester.document(
            """mutation { createObject(input: { workspaceId: $workspaceId, name: "Address", properties: [{ name: "city", kind: STRING }] }) { id } }""",
        ).execute().path("createObject.id").entity(String::class.java).get()

        val mappings = graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: "$workflowId", input: {
                nodes: [{ key: "held", kind: OBJECT, name: "Held", x: 0, y: 0, mappings: [
                  { name: "customer", expression: "", mode: VALUE, fieldKind: OBJECT, fieldProperties: [
                    { name: "name", kind: STRING, description: "Who" },
                    { name: "home", kind: OBJECT, properties: [{ name: "street", kind: STRING }] },
                    { name: "tags", kind: ARRAY, elementKind: STRING }
                  ] },
                  { name: "lines", expression: "", mode: VALUE, fieldKind: ARRAY, fieldProperties: [{ name: "sku", kind: STRING }] },
                  { name: "billing", expression: "", mode: VALUE, fieldKind: OBJECT, fieldRefObjectId: $saved, fieldProperties: [{ name: "ignored", kind: STRING }] },
                  { name: "count", expression: "3", mode: VALUE, fieldKind: NUMBER, fieldProperties: [{ name: "ignored", kind: STRING }] }
                ] }],
                edges: []
              }) { nodes { mappings { name fieldKind fieldRefObjectId fieldProperties { name kind display description properties { name kind } } } } }
            }
            """,
        ).execute()

        val at = "saveWorkflowGraph.nodes[0].mappings"
        mappings.path("$at[0].fieldProperties[*].name").entityList(String::class.java).containsExactly("name", "home", "tags")
            .path("$at[0].fieldProperties[0].description").entity(String::class.java).isEqualTo("Who")
            .path("$at[0].fieldProperties[1].display").entity(String::class.java).isEqualTo("object")
            .path("$at[0].fieldProperties[1].properties[0].name").entity(String::class.java).isEqualTo("street")
            .path("$at[0].fieldProperties[2].display").entity(String::class.java).isEqualTo("array<string>")
            .path("$at[1].fieldProperties[0].name").entity(String::class.java).isEqualTo("sku")
            // A saved object's fields are the saved object's; a scalar has none.
            .path("$at[2].fieldProperties").valueIsNull()
            .path("$at[2].fieldRefObjectId").entity(String::class.java).isEqualTo(saved)
            .path("$at[3].fieldProperties").valueIsNull()

        // Reopened, the same.
        graphQlTester.document(
            """{ workflowGraph(workspaceId: $workspaceId, workflowId: "$workflowId") { nodes { mappings { name fieldProperties { name properties { name } } } } } }""",
        ).execute()
            .path("workflowGraph.nodes[0].mappings[0].fieldProperties[1].properties[0].name").entity(String::class.java).isEqualTo("street")
    }

    @Test
    fun `an Object node offers the fields inside an object defined in place, by their dotted names`() {
        val saved = graphQlTester.document(
            """mutation { createObject(input: { workspaceId: $workspaceId, name: "Order", properties: [
                { name: "id", kind: STRING },
                { name: "customer", kind: OBJECT, properties: [{ name: "name", kind: STRING }, { name: "age", kind: NUMBER }] },
                { name: "lines", kind: ARRAY, properties: [{ name: "sku", kind: STRING }] }
            ] }) { id } }""",
        ).execute().path("createObject.id").entity(String::class.java).get()

        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: "$workflowId", input: {
                nodes: [
                  { key: "own", kind: OBJECT, name: "Own", x: 0, y: 0, mappings: [
                    { name: "count", expression: "3", mode: VALUE, fieldKind: NUMBER },
                    { name: "home", expression: "", mode: VALUE, fieldKind: OBJECT, fieldProperties: [
                      { name: "street", kind: STRING },
                      { name: "geo", kind: OBJECT, properties: [{ name: "lat", kind: NUMBER }] }
                    ] }
                  ] },
                  { key: "kept", kind: OBJECT, name: "Kept", x: 300, y: 0, objectId: $saved }
                ],
                edges: []
              }) { nodes { key outputs { name type } } }
            }
            """,
        ).execute()
            .path("saveWorkflowGraph.nodes[0].outputs[*].name").entityList(String::class.java)
            .containsExactly("count", "home", "home.street", "home.geo", "home.geo.lat")
            // A field of the node's own is the type it was given, no longer text whatever it was.
            .path("saveWorkflowGraph.nodes[0].outputs[0].type").entity(String::class.java).isEqualTo("NUMBER")
            .path("saveWorkflowGraph.nodes[0].outputs[4].type").entity(String::class.java).isEqualTo("NUMBER")
            // A saved shape the same; a list of objects stops at itself.
            .path("saveWorkflowGraph.nodes[1].outputs[*].name").entityList(String::class.java)
            .containsExactly("id", "customer", "customer.name", "customer.age", "lines")
    }

    @Test
    fun `a node field is held to the same rules as an object property`() {
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: "$workflowId", input: {
                nodes: [{ key: "held", kind: OBJECT, name: "Held", x: 0, y: 0, mappings: [
                  { name: "customer", expression: "", mode: VALUE, fieldKind: OBJECT, fieldProperties: [
                    { name: "name", kind: STRING }, { name: "name", kind: NUMBER }
                  ] }
                ] }],
                edges: []
              }) { nodes { key } }
            }
            """,
        ).execute().errors().expect { it.extensions["code"] == "ObjectPropertyInvalid" }.verify()
    }
}
