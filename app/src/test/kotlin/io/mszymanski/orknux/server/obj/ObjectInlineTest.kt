package io.mszymanski.orknux.server.obj

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper

/**
 * An object defined inside a property, rather than pointed at: drawn in the
 * editor field by field, read back the same way, described to a model and
 * checked against a value as a named object is - and read off a JSON Schema.
 * How deep any of it goes is the administrator's number.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ObjectInlineTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val shapes: ObjectShapes,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun make() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "inline-${System.nanoTime()}")).id)
    }

    @AfterEach
    fun depthBack() {
        graphQlTester.document("mutation { setObjectMaxDepth(depth: 30) { objectMaxDepth } }").execute()
    }

    @Test
    fun `an object inside a property is stored, read back and described field by field`() {
        val made = graphQlTester.document(
            """
            mutation {
              createObject(input: {
                workspaceId: $workspaceId, name: "Order",
                properties: [
                  { name: "id", kind: STRING },
                  { name: "customer", kind: OBJECT, description: "Who ordered", properties: [
                    { name: "name", kind: STRING },
                    { name: "address", kind: OBJECT, properties: [
                      { name: "city", kind: STRING }, { name: "zip", kind: STRING }
                    ] }
                  ] },
                  { name: "lines", kind: ARRAY, properties: [
                    { name: "sku", kind: STRING }, { name: "qty", kind: NUMBER }
                  ] }
                ]
              }) {
                id
                properties { name kind display elementKind properties { name kind display properties { name kind } } }
              }
            }
            """,
        ).execute()
        made.path("createObject.properties[1].display").entity(String::class.java).isEqualTo("object")
        made.path("createObject.properties[1].properties[1].properties[*].name").entityList(String::class.java).containsExactly("city", "zip")
        made.path("createObject.properties[2].elementKind").entity(String::class.java).isEqualTo("OBJECT")
        made.path("createObject.properties[2].display").entity(String::class.java).isEqualTo("array<object>")
        made.path("createObject.properties[2].properties[*].name").entityList(String::class.java).containsExactly("sku", "qty")
        val id = made.path("createObject.id").entity(Long::class.java).get()

        // Reopened, it is the same shape - and a model reading it sees the nested fields.
        graphQlTester.document("query { workflowObject(id: $id) { properties { name properties { name properties { name } } } } }").execute()
            .path("workflowObject.properties[1].properties[1].properties[0].name").entity(String::class.java).isEqualTo("city")
        assertThat(shapes.described(id)).contains("\"city\": string").contains("\"qty\": number")

        // And a value is held to it as deep as it goes.
        val wrong = mapper.readTree("""{"id":"1","customer":{"name":"Ann","address":{"city":"Oslo"}},"lines":[{"sku":"a","qty":"two"}]}""")
        assertThat(shapes.problems(id, wrong)).contains("customer.address.zip is missing", "lines[0].qty has to be a number")
        val right = mapper.readTree("""{"id":"1","customer":{"name":"Ann","address":{"city":"Oslo","zip":"0150"}},"lines":[{"sku":"a","qty":2}]}""")
        assertThat(shapes.problems(id, right)).isEmpty()
    }

    @Test
    fun `validate reads an inline shape, and names the field that is wrong`() {
        graphQlTester.document(
            """
            mutation { validateObject(workspaceId: $workspaceId, properties: [
              { name: "customer", kind: OBJECT, properties: [ { name: "name", kind: STRING }, { name: "name", kind: NUMBER } ] }
            ]) { valid message } }
            """,
        ).execute()
            .path("validateObject.valid").entity(Boolean::class.java).isEqualTo(false)
            .path("validateObject.message").entity(String::class.java).isEqualTo("customer: name is named twice")
    }

    @Test
    fun `how deep a shape may go is the administrator's number`() {
        graphQlTester.document("mutation { setObjectMaxDepth(depth: 1) { objectMaxDepth } }").execute()
            .path("setObjectMaxDepth.objectMaxDepth").entity(Int::class.java).isEqualTo(1)

        graphQlTester.document(
            """
            mutation { createObject(input: { workspaceId: $workspaceId, name: "Deep", properties: [
              { name: "a", kind: OBJECT, properties: [ { name: "b", kind: OBJECT, properties: [ { name: "c", kind: STRING } ] } ] }
            ] }) { id } }
            """,
        ).execute().errors().expect { it.extensions["code"] == "ObjectPropertyInvalid" && it.message.orEmpty().contains("1 levels") }.verify()

        graphQlTester.document("mutation { setObjectMaxDepth(depth: 0) { objectMaxDepth } }").execute()
            .errors().expect { it.extensions["code"] == "ObjectDepthOutOfRange" }.verify()
    }

    @Test
    fun `an object is read off a JSON Schema, refs and arrays included`() {
        val schema = """
            {
              "type": "object",
              "properties": {
                "id": { "type": "integer", "description": "The order number" },
                "paid": { "type": ["boolean", "null"] },
                "customer": { "${'$'}ref": "#/definitions/Customer" },
                "lines": { "type": "array", "items": { "type": "object", "properties": { "sku": { "type": "string" }, "qty": { "type": "number" } } } },
                "tags": { "type": "array", "items": { "type": "string" } }
              },
              "definitions": { "Customer": { "type": "object", "properties": { "name": { "type": "string" } } } }
            }
        """.trimIndent()
        val made = graphQlTester.document(
            """mutation(${'$'}schema: String!) { createObjectFromSchema(workspaceId: $workspaceId, name: "FromSchema", schema: ${'$'}schema) {
                 properties { name kind display description elementKind properties { name kind } }
               } }""",
        ).variable("schema", schema).execute()
        made.path("createObjectFromSchema.properties[*].name").entityList(String::class.java).containsExactly("id", "paid", "customer", "lines", "tags")
        made.path("createObjectFromSchema.properties[0].kind").entity(String::class.java).isEqualTo("NUMBER")
        made.path("createObjectFromSchema.properties[0].description").entity(String::class.java).isEqualTo("The order number")
        made.path("createObjectFromSchema.properties[1].kind").entity(String::class.java).isEqualTo("BOOLEAN")
        made.path("createObjectFromSchema.properties[2].properties[0].name").entity(String::class.java).isEqualTo("name")
        made.path("createObjectFromSchema.properties[3].display").entity(String::class.java).isEqualTo("array<object>")
        made.path("createObjectFromSchema.properties[4].elementKind").entity(String::class.java).isEqualTo("STRING")

        graphQlTester.document(
            """mutation { createObjectFromSchema(workspaceId: $workspaceId, name: "NotOne", schema: "{\"type\":\"string\"}") { id } }""",
        ).execute().errors().expect { it.extensions["code"] == "ObjectSchemaUnreadable" }.verify()
    }
}
