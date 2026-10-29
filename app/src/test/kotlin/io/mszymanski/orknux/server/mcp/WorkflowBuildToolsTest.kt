package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.core.context.SecurityContextHolder
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * A model building a workflow end to end through the orknux_* tools: reading
 * what the fields mean, creating the trigger and action its nodes point at,
 * saving the graph and publishing it.
 *
 * Nobody is signed in here, which is how an agent working a task calls these:
 * the scope was checked when it was built, and the workspace's controllers,
 * which check again, are reached for that one workspace only.
 */
@SpringBootTest
class WorkflowBuildToolsTest(
    @Autowired val tools: OrknuxTools,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0
    private var elsewhereId: Long = 0

    @BeforeEach
    fun reset() {
        SecurityContextHolder.clearContext()
        audit.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "build-${System.nanoTime()}")).id)
        elsewhereId = requireNotNull(workspaces.save(Workspace(name = "elsewhere-${System.nanoTime()}")).id)
    }

    private fun agent() = OrknuxScope(workspaceId = workspaceId, mayWrite = true, actor = "Builder")

    private fun call(name: String, arguments: String, scope: OrknuxScope = agent()): JsonNode =
        mapper.readTree(tools.run(scope, name, arguments))

    private fun listed(array: JsonNode, field: String): List<String> =
        (0 until array.size()).map { array.get(it).path(field).asText() }

    @Test
    fun `the reference prints the fields from the schema`() {
        val graph = call("orknux_workflow_reference", "{}").path("schema").stringValue()
        assertThat(graph).contains("input WorkflowNodeInput").contains("enum NodeKind").contains("enum EdgeBranch")
            .contains("input NodeMappingInput")

        val action = call("orknux_workflow_reference", """{"part":"action"}""").path("schema").stringValue()
        assertThat(action).contains("input CreateActionInput").contains("enum ActionSubtype")
    }

    @Test
    fun `an agent with nobody signed in builds and publishes a workflow`() {
        val workflow = call("orknux_create_workflow", """{"name":"Nightly report","description":"Fetches the numbers."}""")
        assertThat(workflow.has("error")).describedAs(workflow.toString()).isFalse()
        val workflowId = workflow.path("id").asLong()

        val trigger = call(
            "orknux_create_trigger",
            """{"fields":{"name":"Every night","type":"SCHEDULED","cron":"0 0 2 * * *"}}""",
        )
        assertThat(trigger.has("error")).describedAs(trigger.toString()).isFalse()
        val action = call(
            "orknux_create_action",
            // A workspace named in the fields is not the one it goes to: the scope's is.
            """{"fields":{"workspaceId":$elsewhereId,"name":"Fetch numbers","type":"EXECUTE",
               "subtype":"HTTP_REQUEST","url":"https://example.invalid/numbers","method":"GET"}}""",
        )
        assertThat(action.has("error")).describedAs(action.toString()).isFalse()

        val parts = call("orknux_workflow_parts", "{}")
        assertThat(listed(parts.path("triggers"), "name")).contains("Every night")
        assertThat(listed(parts.path("actions"), "name")).contains("Fetch numbers")

        val saved = call(
            "orknux_save_workflow_graph",
            """{"workflow":"Nightly report",
               "nodes":[
                 {"key":"start","kind":"TRIGGER","name":"Every night","triggerId":${trigger.path("id").asLong()}},
                 {"key":"fetch","kind":"ACTION","name":"Fetch numbers","actionId":${action.path("id").asLong()}}
               ],
               "edges":[{"source":"start","target":"fetch"}]}""",
        )
        assertThat(saved.has("error")).describedAs(saved.toString()).isFalse()
        assertThat(listed(saved.path("nodes"), "key")).containsExactly("start", "fetch")
        // Laid out left to right where no position was given.
        assertThat((0 until saved.path("nodes").size()).map { saved.path("nodes").get(it).path("x").asDouble() }).containsExactly(0.0, 380.0)
        assertThat(listed(saved.path("edges"), "target")).containsExactly("fetch")

        val published = call("orknux_publish_workflow", """{"workflow":"$workflowId"}""")
        assertThat(published.path("status").stringValue()).describedAs(published.toString()).isEqualTo("PUBLISHED")

        assertThat(call("orknux_workflow", """{"workflow":"Nightly report"}""").path("nodes").size()).isEqualTo(2)
        assertThat(audit.findAll().map { it.message })
            .contains("Workflow Nightly report created", "Workflow Nightly report graph updated", "Workflow Nightly report published")
        // Under the agent's own name, there being nobody signed in to name.
        assertThat(audit.findAll().filter { it.message == "Workflow Nightly report created" }.map { it.userId })
            .containsExactly("Builder")
    }

    /**
     * A model places nodes as points and a card is 260 pixels wide: positions
     * that put two cards on each other are laid out instead, and ones that do
     * not are kept.
     */
    @Test
    fun `overlapping positions are laid out, apart ones are kept`() {
        call("orknux_create_workflow", """{"name":"Piled up"}""")
        fun xs(saved: JsonNode) = (0 until saved.path("nodes").size()).map { saved.path("nodes").get(it).path("x").asDouble() }
        fun save(ax: Int, bx: Int) = call(
            "orknux_save_workflow_graph",
            """{"workflow":"Piled up","overwrite_draft":true,
               "nodes":[{"key":"a","kind":"OBJECT","name":"A","x":$ax,"y":0},{"key":"b","kind":"OBJECT","name":"B","x":$bx,"y":0}],
               "edges":[{"source":"a","target":"b"}]}""",
        )

        assertThat(xs(save(0, 100))).containsExactly(0.0, 380.0)
        assertThat(xs(save(0, 500))).containsExactly(0.0, 500.0)
    }

    /** Some clients send an array parameter as the JSON text of one, whatever the schema says. */
    @Test
    fun `nodes and edges sent as JSON text are read as lists`() {
        call("orknux_create_workflow", """{"name":"Sent as text"}""")
        val nodes = mapper.writeValueAsString("""[{"key":"a","kind":"OBJECT","name":"A"},{"key":"b","kind":"OBJECT","name":"B"}]""")
        val edges = mapper.writeValueAsString("""[{"source":"a","target":"b"}]""")
        val saved = call("orknux_save_workflow_graph", """{"workflow":"Sent as text","nodes":$nodes,"edges":$edges}""")
        assertThat(saved.has("error")).describedAs(saved.toString()).isFalse()
        assertThat(listed(saved.path("nodes"), "key")).containsExactly("a", "b")
        assertThat(listed(saved.path("edges"), "target")).containsExactly("b")

        val garbled = call("orknux_save_workflow_graph", """{"workflow":"Sent as text","overwrite_draft":true,"nodes":"[{","edges":[]}""")
        assertThat(garbled.path("error").stringValue()).contains("nodes, as a list")
    }

    @Test
    fun `a draft with unpublished changes is replaced only when told to`() {
        call("orknux_create_workflow", """{"name":"Half drawn"}""")
        val node = """{"key":"a","kind":"OBJECT","name":"Shape"}"""
        val first = call("orknux_save_workflow_graph", """{"workflow":"Half drawn","nodes":[$node],"edges":[]}""")
        assertThat(first.has("error")).describedAs(first.toString()).isFalse()

        val again = call("orknux_save_workflow_graph", """{"workflow":"Half drawn","nodes":[],"edges":[]}""")
        assertThat(again.path("error").stringValue()).contains("overwrite_draft")
        assertThat(call("orknux_workflow", """{"workflow":"Half drawn"}""").path("nodes").size()).isEqualTo(1)

        val told = call(
            "orknux_save_workflow_graph",
            """{"workflow":"Half drawn","nodes":[$node,{"key":"b","kind":"OBJECT","name":"Other"}],"edges":[],
               "overwrite_draft":true}""",
        )
        assertThat(told.path("nodes").size()).describedAs(told.toString()).isEqualTo(2)
    }

    @Test
    fun `a conversation that may only read is offered no building and refused it`() {
        val reading = OrknuxScope(workspaceId = workspaceId)
        val offered = tools.specs(reading).map { it.name }
        assertThat(offered).contains("orknux_workflow", "orknux_workflow_parts", "orknux_workflow_reference")
        assertThat(offered).doesNotContain("orknux_create_workflow", "orknux_save_workflow_graph", "orknux_publish_workflow")

        assertThat(call("orknux_create_workflow", """{"name":"Sneaky"}""", reading).path("error").stringValue())
            .contains("not change")
        assertThat(assignments.existsByWorkspaceIdAndWorkflowName(workspaceId, "Sneaky")).isFalse()
    }

    @Test
    fun `another workspace's workflow is not there`() {
        call("orknux_create_workflow", """{"name":"Theirs"}""", OrknuxScope(workspaceId = elsewhereId, mayWrite = true))

        assertThat(call("orknux_workflow", """{"workflow":"Theirs"}""").path("error").stringValue())
            .contains("no workflow called Theirs")
    }

    @Test
    fun `the grant lasts only as long as the call`() {
        call("orknux_workflow_parts", "{}")

        // Back outside a call, nobody is signed in and nothing is visible.
        val access = org.springframework.test.util.ReflectionTestUtils.getField(tools, "access")
            as io.mszymanski.orknux.server.security.WorkspaceAccess
        assertThat(access.canSee(workspaceId)).isFalse()
    }
}
