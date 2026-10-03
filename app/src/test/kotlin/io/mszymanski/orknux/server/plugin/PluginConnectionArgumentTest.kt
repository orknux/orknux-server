package io.mszymanski.orknux.server.plugin

import io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository
import io.mszymanski.orknux.server.action.WorkflowActionRepository
import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.PluginToolCaller
import io.mszymanski.orknux.server.chat.AgentTools
import io.mszymanski.orknux.server.workflow.WorkflowEdgeRepository
import io.mszymanski.orknux.server.workflow.WorkflowNodeRepository
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.ExecutionLogRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.StepStatus
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.mock.web.MockMultipartFile
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * A connection passed to a plugin's function or tool as an argument.
 *
 * A plugin fronting hosts of its own kind - Prometheus, Jenkins - takes the
 * host as an argument, so one plugin reaches every Prometheus a workspace
 * keeps. The caller has an id (a node's picker) or an id or a name (a model);
 * the plugin needs the address and the credential. Only settings were ever
 * resolved, so the plugin was handed `"257"` and rightly refused it.
 *
 * Claims: the agent's tool call and the workflow node both arrive as the
 * handle a setting would; a name works as well as an id; another workspace's
 * connection and one of the wrong kind are refused with the ones that would
 * do, and with no credential; and the model is told which those are.
 *
 * An agent is held to the connections it was granted, as the briefing tells
 * it: one of the right kind it does not hold is refused as though it did not
 * exist, and the schema and every refusal list only its grants. A workflow
 * node has no agent, and is held to the workspace.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class PluginConnectionArgumentTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val upload: PluginUploadAPI,
    @Autowired val plugins: PluginRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val actions: WorkflowActionRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val pluginTools: PluginToolCaller,
    @Autowired val agentTools: AgentTools,
    @Autowired val connections: WorkspaceConnectionRepository,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val logs: ExecutionLogRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0
    private var elsewhereId: Long = 0

    /**
     * After each as well as before: an action naming the plugin's function
     * would outlive the class, and the next class to delete plugins trips
     * over its foreign key.
     */
    @AfterEach
    fun clear() {
        logs.deleteAll()
        steps.deleteAll()
        executions.deleteAll()
        nodes.deleteAll()
        edges.deleteAll()
        actions.deleteAll()
        assignments.deleteAll()
        workflows.deleteAll()
        agents.deleteAll()
        functions.deleteAll()
        connections.deleteAll()
        plugins.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
    }

    @BeforeEach
    fun reset() {
        clear()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        elsewhereId = requireNotNull(workspaces.save(Workspace(name = "frontend")).id)
        upload.upload(MockMultipartFile("file", "monitors.js", "text/javascript", SOURCE.toByteArray()), null, null)
    }

    /* ------------------------------------------------------- an agent's call ---- */

    @Test
    fun `an agent's tool call with a granted connection's id reaches the plugin as the handle`() {
        val prod = connect("Prod Prometheus", "monitors/prometheus")
        val agent = agent("monitors_query", "monitors_probe", holding = listOf(prod))

        // As a model writes it: the id as a string.
        val proxied = call(agent, "monitors_query", """{"prometheus":"$prod","promql":"up"}""")
        assertHandle(proxied.get("handed"), prod)
        assertThat(proxied.get("promql").asString()).isEqualTo("up")

        // And a tool with a run of its own, down the other dispatch, as a number.
        val own = call(agent, "monitors_probe", """{"prometheus":$prod}""")
        assertHandle(own.get("handed"), prod)
    }

    /**
     * Accepted, because a model writes the name it was told far more often
     * than the number behind it - and refusing that only costs a round trip to
     * learn the number. Exact first, then without regard to case; a name two
     * connections share that way names neither, and is refused.
     */
    @Test
    fun `a connection's name works as well as its id`() {
        val prod = connect("Prod Prometheus", "monitors/prometheus")
        val agent = agent("monitors_query", holding = listOf(prod))

        val answer = call(agent, "monitors_query", """{"prometheus":"prod prometheus","promql":"up"}""")

        assertHandle(answer.get("handed"), prod)
    }

    /**
     * Held to its grants, and not to the workspace. A grant is the agent's
     * permission to name a connection, and an argument is naming one - so one
     * it does not hold is refused exactly as one that does not exist would be.
     */
    @Test
    fun `an agent may not name a connection of the right kind it was not granted`() {
        val prod = connect("Prod Prometheus", "monitors/prometheus")
        val staging = connect("Staging Prometheus", "monitors/prometheus")
        val agent = agent("monitors_query", holding = listOf(prod))

        for (named in listOf("\"$staging\"", "\"staging prometheus\"")) {
            val answer = callRaw(agent, "monitors_query", """{"prometheus":$named,"promql":"up"}""")

            val error = mapper.readTree(answer).get("error").asString()
            assertThat(error).contains("prometheus argument").contains("you have been granted")
            assertThat(error).contains("$prod (Prod Prometheus)")
            // Nothing of it beyond what the caller itself wrote.
            assertThat(error).doesNotContain("Staging Prometheus").doesNotContain("$staging (")
            assertThat(answer).doesNotContain(SECRET).doesNotContain("Bearer")
        }
    }

    @Test
    fun `an agent granted no connection of the kind is told where a grant is made`() {
        val prod = connect("Prod Prometheus", "monitors/prometheus")
        val agent = agent("monitors_query")

        val answer = callRaw(agent, "monitors_query", """{"prometheus":"$prod","promql":"up"}""")
        val said = agentTools.specsFor(agent).single { it.name == "monitors_query" }
            .parameters.single { it.name == "prometheus" }.description

        val error = mapper.readTree(answer).get("error").asString()
        assertThat(error).contains("granted no Prometheus connection").contains("Connections setting")
        assertThat(error).doesNotContain("Prod Prometheus")
        assertThat(said).contains("granted no Prometheus connection").doesNotContain("Prod Prometheus")
    }

    @Test
    fun `another workspace's connection is refused, even granted, with the ones that would do and no credential`() {
        val prod = connect("Prod Prometheus", "monitors/prometheus")
        val theirs = connect("Their Prometheus", "monitors/prometheus", on = elsewhereId, secret = "their-secret")
        /*
         * A grant reaches only the agent's own workspace, whatever a row says.
         * The API refuses to grant this one, so it is written as a stale or
         * restored row would hold it.
         */
        val agent = agent("monitors_query", holding = listOf(prod)).let { held ->
            held.connections.add(theirs)
            agents.save(held)
        }

        val answer = callRaw(agent, "monitors_query", """{"prometheus":"$theirs","promql":"up"}""")

        val error = mapper.readTree(answer).get("error").asString()
        assertThat(error).contains("prometheus argument").contains("\"$theirs\"")
        assertThat(error).contains("$prod (Prod Prometheus)")
        // Nothing of the other workspace's - not even that it exists by name.
        assertThat(error).doesNotContain("Their Prometheus")
        assertThat(answer).doesNotContain("their-secret").doesNotContain(SECRET)
    }

    @Test
    fun `a granted connection of the wrong kind is refused, naming the kind it is`() {
        val prod = connect("Prod Prometheus", "monitors/prometheus")
        val wiki = connect("Wiki", null)
        val agent = agent("monitors_query", holding = listOf(prod, wiki))

        val answer = callRaw(agent, "monitors_query", """{"prometheus":"$wiki","promql":"up"}""")

        val error = mapper.readTree(answer).get("error").asString()
        assertThat(error).contains("Wiki").contains("HTTP").contains("Prometheus")
        assertThat(error).contains("$prod (Prod Prometheus)")
        assertThat(answer).doesNotContain(SECRET).doesNotContain("Bearer")
    }

    /** What the model reads before it calls: the kind, and which of its grants those are. */
    @Test
    fun `the tool's schema lists only the agent's granted connections of the kind`() {
        val prod = connect("Prod Prometheus", "monitors/prometheus")
        val wiki = connect("Wiki", null)
        connect("Staging Prometheus", "monitors/prometheus")
        val agent = agent("monitors_query", holding = listOf(prod, wiki))

        val spec = agentTools.specsFor(agent).single { it.name == "monitors_query" }
        val said = spec.parameters.single { it.name == "prometheus" }.description

        assertThat(said).contains("Prometheus").contains("id").contains("name").contains("granted")
            .contains("$prod (Prod Prometheus)")
        assertThat(said).doesNotContain("Wiki").doesNotContain("Staging").doesNotContain(SECRET)
    }

    /* ------------------------------------------------------ a workflow node ---- */

    /**
     * A node acts for the workspace, not for an agent, so it is held to the
     * workspace and kind - granted to no agent at all, this still runs.
     */
    @Test
    fun `a workflow node may name any of the workspace's connections of the kind`() {
        val prod = connect("Prod Prometheus", "monitors/prometheus")
        agent("monitors_query")

        val step = runNode("""[{ name: "prometheus", expression: "$prod" }, { name: "promql", expression: "up" }]""")

        assertThat(step.status).isEqualTo(StepStatus.COMPLETED)
        assertHandle(mapper.readTree(step.output).get("result").get("handed"), prod)
    }

    @Test
    fun `a workflow node with an unknown name is told the workspace's connections`() {
        val prod = connect("Prod Prometheus", "monitors/prometheus")

        val step = runNode("""[{ name: "prometheus", expression: "staging" }, { name: "promql", expression: "up" }]""")

        assertThat(step.status).isEqualTo(StepStatus.FAILED)
        assertThat(step.error).contains("this workspace's Prometheus connections").contains("$prod (Prod Prometheus)")
    }

    @Test
    fun `a workflow node's connection argument reaches the plugin as the handle`() {
        val prod = connect("Prod Prometheus", "monitors/prometheus")

        val step = runNode("""[{ name: "prometheus", expression: "$prod" }, { name: "promql", expression: "up" }]""")

        assertThat(step.status).isEqualTo(StepStatus.COMPLETED)
        val result = mapper.readTree(step.output).get("result")
        assertHandle(result.get("handed"), prod)
    }

    @Test
    fun `a workflow node naming another workspace's connection fails settled, without its credential`() {
        val theirs = connect("Their Prometheus", "monitors/prometheus", on = elsewhereId, secret = "their-secret")

        val step = runNode("""[{ name: "prometheus", expression: "$theirs" }, { name: "promql", expression: "up" }]""")

        assertThat(step.status).isEqualTo(StepStatus.FAILED)
        assertThat(step.error).contains("prometheus argument").doesNotContain("their-secret")
    }

    /* ------------------------------------------------------------- helpers ---- */

    private fun assertHandle(handed: JsonNode, id: Long) {
        assertThat(handed.get("id").asLong()).isEqualTo(id)
        assertThat(handed.get("pluginType").asString()).isEqualTo("monitors/prometheus")
        assertThat(handed.get("url").asString()).isEqualTo("https://prom.example.com")
        assertThat(handed.get("headers").get("Authorization").asString()).isEqualTo("Bearer $SECRET")
    }

    private fun connect(name: String, pluginType: String?, on: Long = workspaceId, secret: String = SECRET): Long =
        graphQlTester.document(
            """mutation { createWorkspaceConnection(input: {
                 workspaceId: $on, name: "$name", type: HTTP,
                 ${pluginType?.let { "pluginType: \"$it\"," } ?: ""}
                 url: "https://prom.example.com", authType: BEARER_TOKEN, secret: "$secret"
               }) { id } }""",
        ).execute().path("createWorkspaceConnection.id").entity(Long::class.java).get()

    /** An agent granted [tools], and the connections [holding] by id. */
    private fun agent(vararg tools: String, holding: List<Long> = emptyList()) = run {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Watcher", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "Watcher",
                 tools: [${tools.joinToString(", ") { "\"$it\"" }}],
                 connectionIds: [${holding.joinToString(", ")}] }) { id } }""",
        ).execute()
        requireNotNull(agents.findByIdOrNull(id))
    }

    private fun callRaw(agent: io.mszymanski.orknux.server.agent.Agent, name: String, arguments: String): String =
        pluginTools.call(agent, requireNotNull(pluginTools.resolve(agent, name)), arguments)

    private fun call(agent: io.mszymanski.orknux.server.agent.Agent, name: String, arguments: String): JsonNode {
        val answer = callRaw(agent, name, arguments)
        assertThat(answer).describedAs(answer).doesNotContain("\"error\"")
        return mapper.readTree(answer)
    }

    /** One action node calling the plugin's function, run once; the step it made. */
    private fun runNode(mappings: String): io.mszymanski.orknux.workflow.execution.ExecutionStep {
        val functionId = requireNotNull(functions.findAll().single { it.name == "monitors_query" }.id)
        val actionId = graphQlTester.document(
            """mutation { createAction(input: {
                 workspaceId: $workspaceId, name: "Ask Prometheus", type: EXECUTE, subtype: FUNCTION,
                 functionId: $functionId
               }) { id } }""",
        ).execute().path("createAction.id").entity(Long::class.java).get()
        val workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Check" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
        graphQlTester.document(
            """mutation { saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                 nodes: [{ key: "ask", kind: ACTION, name: "Ask", actionId: $actionId, x: 0, y: 0,
                           mappings: $mappings }],
                 edges: []
               }) { nodes { key } } }""",
        ).execute()
        graphQlTester.document(
            """mutation { startExecution(workspaceId: $workspaceId, workflowId: $workflowId, input: "{}") { id } }""",
        ).execute()
        return steps.findAll().single { it.actionId == actionId }
    }

    private companion object {
        const val SECRET = "tok-prod-123"

        /** A plugin fronting Prometheus servers, whose calls answer what they were handed. */
        val SOURCE = """
            export default class Monitors extends OrknuxPlugin {
              id() { return 'monitors'; }
              apiVersion() { return 1; }
              connectionTypes() {
                return [{ name: 'prometheus', label: 'Prometheus', description: 'A Prometheus server.' }];
              }
              functions() {
                return [
                  new OrknuxFunction({
                    name: 'query',
                    description: 'Asks a Prometheus.',
                    params: [{ name: 'prometheus', type: 'connection' }, { name: 'promql', type: 'string' }],
                    returnType: 'map',
                    run: (prometheus, promql) => ({ handed: prometheus, promql: promql }),
                  }),
                ];
              }
              tools() {
                return [
                  new OrknuxFunctionTool({ function: 'query' }),
                  new OrknuxTool({
                    name: 'probe',
                    description: 'Whether a Prometheus answers.',
                    params: [{ name: 'prometheus', type: 'connection' }],
                    returnType: 'map',
                    run: (prometheus) => ({ handed: prometheus }),
                  }),
                ];
              }
            }
        """.trimIndent()
    }
}
