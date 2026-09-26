package io.mszymanski.orknux.server.action

import io.mszymanski.orknux.server.plugin.PluginActions
import io.mszymanski.orknux.server.plugin.PluginDeclarations
import io.mszymanski.orknux.server.plugin.PluginRepository
import io.mszymanski.orknux.server.plugin.PluginUploadAPI
import io.mszymanski.orknux.server.workflow.WorkflowEdgeRepository
import io.mszymanski.orknux.server.workflow.WorkflowNodeRepository
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.ExecutionLogRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.StepStatus
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper

/**
 * A plugin declaring workflow actions, and an Action node running one. Issue #438.
 *
 * A plugin's functions were the only way a plugin reached a workflow, and a
 * function is called positionally with the arguments its row declares - so a
 * trigger's list of commands had no way to arrive as the list it is. An action
 * is declared with parameters by name and called with one object, and what it
 * answers is handed on under the outputs it declared.
 *
 * Makes its own workspace and workflow; loads its own plugin.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class PluginActionTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val upload: PluginUploadAPI,
    @Autowired val plugins: PluginRepository,
    @Autowired val declarations: PluginDeclarations,
    @Autowired val pluginActions: PluginActions,
    @Autowired val actions: WorkflowActionRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val logs: ExecutionLogRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0
    private var workflowId: Long = 0

    @BeforeEach
    fun reset() {
        logs.deleteAll()
        steps.deleteAll()
        executions.deleteAll()
        nodes.deleteAll()
        edges.deleteAll()
        actions.deleteAll()
        functions.deleteAll()
        plugins.deleteAll()
        assignments.deleteAll()
        workflows.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()

        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Answer the command" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
    }

    /**
     * A plugin with two actions: one that counts what it was handed - and
     * reports the shape of the call, which is what these tests are about - and
     * one that a later version stops declaring.
     */
    private fun source(withGone: Boolean = true) = """
        export default class Echoes extends OrknuxPlugin {
          id() { return 'echoes'; }
          apiVersion() { return 1; }
          actions() {
            return [
              {
                name: 'count',
                label: 'Count the commands',
                description: 'How many commands arrived, and which came first.',
                parameters: [
                  { name: 'commands', type: 'array', description: 'What the trigger heard.' },
                  { name: 'channel', type: 'string' },
                  { name: 'note', type: 'string', required: false },
                ],
                outputs: [
                  { name: 'count', type: 'number' },
                  { name: 'first', type: 'string' },
                ],
                run: (input, context) => ({
                  count: input.commands.length,
                  first: input.commands[0],
                  isArray: Array.isArray(input.commands),
                  told: Object.keys(input).sort(),
                  workspace: context.workspaceId,
                  settingsSeen: typeof context.settings === 'object' && context.settings !== null,
                }),
              },
              ${if (withGone) "{ name: 'gone', label: 'Soon gone', run: () => ({ ok: true }) }," else ""}
            ];
          }
        }
    """.trimIndent()

    private fun load(text: String = source()) =
        upload.upload(MockMultipartFile("file", "echoes.js", "text/javascript", text.toByteArray()), null, null)

    /* ------------------------------------------------- what is declared ---- */

    @Test
    fun `a plugin's actions are read from the code, kept, and offered to the editor`() {
        load()

        val stored = plugins.findByKey("echoes")!!
        val held = declarations.readActions(stored.declaredActions, stored.key, stored.name)
        assertThat(held.map { it.name }).containsExactly("count", "gone")

        val count = held.first()
        assertThat(count.label).isEqualTo("Count the commands")
        // Types are this server's names, so the node's ports need no sandbox;
        // `array` is kept as ARRAY, and only the optional one says so.
        assertThat(count.parameters.map { Triple(it.name, it.type, it.required) }).containsExactly(
            Triple("commands", "ARRAY", true),
            Triple("channel", "STRING", true),
            Triple("note", "STRING", false),
        )
        assertThat(count.outputs.map { it.name to it.type }).containsExactly("count" to "NUMBER", "first" to "STRING")

        assertThat(pluginActions.declared("echoes", "count")?.label).isEqualTo("Count the commands")
        assertThat(pluginActions.declared("echoes", "nothing")).isNull()

        graphQlTester.document(
            "query { pluginActions(workspaceId: $workspaceId) { pluginKey name label parameters { name type required } } }",
        ).execute()
            .path("pluginActions[0].pluginKey").entity(String::class.java).isEqualTo("echoes")
            .path("pluginActions[0].label").entity(String::class.java).isEqualTo("Count the commands")
            .path("pluginActions[0].parameters[0].type").entity(String::class.java).isEqualTo("ARRAY")
            .path("pluginActions[0].parameters[2].required").entity(Boolean::class.java).isEqualTo(false)
    }

    @Test
    fun `an action names the plugin and the action, and reads its ports off the declaration`() {
        load()

        val id = pluginAction("count", "Count them")

        graphQlTester.document(
            "query { action(id: $id) { subtype pluginKey pluginAction pluginActionLabel inputParams { display } outputParams { display } } }",
        ).execute()
            .path("action.subtype").entity(String::class.java).isEqualTo("PLUGIN_ACTION")
            .path("action.pluginKey").entity(String::class.java).isEqualTo("echoes")
            .path("action.pluginActionLabel").entity(String::class.java).isEqualTo("Count the commands")
            .path("action.inputParams[*].display").entityList(String::class.java)
            .containsExactly("commands: array", "channel: string", "note: string")
            .path("action.outputParams[*].display").entityList(String::class.java)
            .containsExactly("count: number", "first: string")
    }

    @Test
    fun `a shared action naming what no plugin declares is refused`() {
        load()

        graphQlTester.document(
            """mutation { createAction(input: {
                 workspaceId: $workspaceId, name: "Nothing", type: EXECUTE, subtype: PLUGIN_ACTION,
                 pluginKey: "echoes", pluginAction: "nothing"
               }) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().message).contains("does not declare nothing")
        }
        assertThat(actions.findAll()).isEmpty()
    }

    /* ---------------------------------------------------------- running ---- */

    @Test
    fun `a node hands the plugin its inputs as one object, arrays intact, and hands the answer on`() {
        load()
        val actionId = pluginAction("count", "Count them")
        graph(actionId)

        start(input = """{"commands":["/deploy","/status"],"channel":"C1"}""")

        val step = steps.findAll().single { it.actionId == actionId }
        assertThat(step.status).isEqualTo(StepStatus.COMPLETED)

        val output = mapper.readTree(step.output)
        // The list arrived as a list, under its own name, and nothing else the
        // plugin did not declare - and `note`, wired to a field that is not
        // there, was left out rather than sent as null.
        assertThat(output.get("isArray").asBoolean()).isTrue()
        assertThat(output.get("count").asInt()).isEqualTo(2)
        assertThat(output.get("first").asString()).isEqualTo("/deploy")
        assertThat(output.get("told").values().map { it.asString() }).containsExactly("channel", "commands")
        // The same context a function is told, with the plugin's settings on it.
        assertThat(output.get("workspace").asLong()).isEqualTo(workspaceId)
        assertThat(output.get("settingsSeen").asBoolean()).isTrue()
        // Beside what arrived, not instead of it: the declared outputs are
        // fields the next node reads by name, and the commands are still there.
        assertThat(output.get("commands").values().map { it.asString() }).containsExactly("/deploy", "/status")
        assertThat(output.get("channel").asString()).isEqualTo("C1")

        assertThat(executions.findAll().single().status).isEqualTo(ExecutionStatus.COMPLETED)
    }

    @Test
    fun `an action the plugin no longer declares fails the step for good`() {
        load()
        val actionId = pluginAction("gone", "Soon gone")
        graph(actionId, retries = 3)

        // A new version of the plugin, without the action the node points at.
        load(source(withGone = false))
        start(expectFailure = true)

        val step = steps.findAll().single { it.actionId == actionId }
        assertThat(step.status).isEqualTo(StepStatus.FAILED)
        assertThat(step.error).contains("gone").contains("echoes plugin no longer declares")
        // Permanent: the node allowed three attempts, and the run spent one.
        assertThat(step.attempts).isEqualTo(1)
        assertThat(executions.findAll().single().status).isEqualTo(ExecutionStatus.FAILED)
    }

    @Test
    fun `an action whose plugin has been unloaded fails the step for good`() {
        load()
        val actionId = pluginAction("count", "Count them")
        graph(actionId, retries = 3)

        plugins.deleteAll()
        start(input = """{"commands":[],"channel":"C1"}""", expectFailure = true)

        val step = steps.findAll().single { it.actionId == actionId }
        assertThat(step.status).isEqualTo(StepStatus.FAILED)
        assertThat(step.error).contains("echoes plugin, which is not loaded")
        assertThat(step.attempts).isEqualTo(1)
    }

    /* ------------------------------------------------------------ how ---- */

    private fun pluginAction(name: String, called: String): Long = graphQlTester.document(
        """
        mutation {
          createAction(input: {
            workspaceId: $workspaceId, name: "$called", type: EXECUTE, subtype: PLUGIN_ACTION,
            pluginKey: "echoes", pluginAction: "$name"
          }) { id }
        }
        """,
    ).execute().path("createAction.id").entity(Long::class.java).get()

    /**
     * One action node, which is the whole workflow, wired the way the editor
     * seeds it: each parameter reads the field of its own name.
     */
    private fun graph(actionId: Long, retries: Int? = null) {
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [{
                  key: "act", kind: ACTION, name: "Act", actionId: $actionId, x: 0, y: 0,
                  ${retries?.let { "retryAttempts: $it," } ?: ""}
                  mappings: [
                    { name: "commands", expression: "commands", mode: REFERENCE },
                    { name: "channel", expression: "channel", mode: REFERENCE },
                    { name: "note", expression: "note", mode: REFERENCE }
                  ]
                }],
                edges: []
              }) { nodes { key actionId } }
            }
            """,
        ).execute().path("saveWorkflowGraph.nodes[0].actionId").entity(Long::class.java).isEqualTo(actionId)
    }

    private fun start(input: String = """{"commands":["/deploy"],"channel":"C1"}""", expectFailure: Boolean = false): Long {
        val id = graphQlTester.document(
            """
            mutation(${'$'}input: String) {
              startExecution(workspaceId: $workspaceId, workflowId: $workflowId, input: ${'$'}input) { id status }
            }
            """,
        ).variable("input", input).execute().path("startExecution.id").entity(Long::class.java).get()

        val run = executions.findAll().single { it.id == id }
        if (!expectFailure) assertThat(run.status).isIn(ExecutionStatus.COMPLETED, ExecutionStatus.RUNNING)
        return id
    }
}
