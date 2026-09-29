package io.mszymanski.orknux.server.workflow

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.connector.model.ModelUsageRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.json.JsonMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A decision model provider, and the node that asks one. Issue #577.
 *
 * Everything goes through the doors the editor and the Models screen use - the
 * provider and model mutations, `saveWorkflowGraph`, `startExecution` - against
 * a stub on the loopback answering the API Jev and Laya share: `POST
 * /v1/systemone` with TypeSafe's documented answer shapes, Jev's `GET
 * /v1/models`, or Laya's `GET /health` where that is missing. So what is pinned
 * is the wire in both directions and the run's path through the branches: the
 * option the model picked is the edge the run left by, an answer under the
 * threshold leaves by the unsure edge, and the others are skipped.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class DecisionNodeTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val usage: ModelUsageRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val publications: WorkflowPublicationRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer

    /** Every decision request the stub was sent, and the headers it came with. */
    private val sent = CopyOnWriteArrayList<String>()
    private val authorizations = CopyOnWriteArrayList<String>()

    /** What the stub answers a decision with; each test sets its own. */
    @Volatile
    private var answer: String = choice("billing", 0.94)

    /** Whether the stub is Jev (lists models) or Laya (answers only /health). */
    @Volatile
    private var laya = false

    @BeforeEach
    fun reset() {
        steps.deleteAll()
        executions.deleteAll()
        publications.deleteAll()
        nodes.deleteAll()
        edges.deleteAll()
        assignments.deleteAll()
        workflows.deleteAll()
        usage.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "support")).id)
        sent.clear()
        authorizations.clear()
        laya = false

        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/v1/systemone") { exchange ->
            sent += exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            authorizations += exchange.requestHeaders.getFirst("Authorization") ?: "none"
            reply(exchange, 200, answer)
        }
        server.createContext("/v1/models") { exchange ->
            if (laya) {
                reply(exchange, 404, """{"detail":"Not Found"}""")
            } else {
                reply(exchange, 200, """{"data":[{"id":"jev-1.13.0"},{"id":"jev-latest"}]}""")
            }
        }
        server.createContext("/health") { exchange ->
            reply(exchange, 200, """{"status":"ok","loaded":["laya-base","laya-multilingual"],"device":"cpu"}""")
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    /* ------------------------------------------------------------ the provider */

    @Test
    fun `a decision provider needs no key, and a check lists Jev's models`() {
        val providerId = provider(secret = null)

        graphQlTester.document("""mutation { testModelProvider(id: $providerId) { status lastCheckMessage } }""")
            .execute()
            .path("testModelProvider.status").entity(String::class.java).isEqualTo("CONNECTED")
            .path("testModelProvider.lastCheckMessage").entity(String::class.java).isEqualTo("Connected; 2 models listed")
    }

    @Test
    fun `a Laya with no model list is checked by its health`() {
        laya = true
        val providerId = provider(secret = null)

        graphQlTester.document("""query { discoveredModels(providerId: $providerId) { modelId } }""")
            .execute()
            .path("discoveredModels[*].modelId").entityList(String::class.java)
            .containsExactly("laya-base", "laya-multilingual")
    }

    @Test
    fun `a decision provider holds only decision models, and only it holds them`() {
        val decisionProvider = provider(secret = null)

        graphQlTester.document(
            """mutation { createModel(input: { providerId: $decisionProvider, name: "Chatty", modelId: "x", kind: CHAT }) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.first().extensions["code"]).isEqualTo("ModelKindNotOffered")
        }

        val chatProvider = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Chat", endpoint: "http://chat.invalid", type: OPENAI, secret: "sk"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        graphQlTester.document(
            """mutation { createModel(input: { providerId: $chatProvider, name: "Jev", modelId: "jev-latest", kind: DECISION }) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.first().extensions["code"]).isEqualTo("ModelKindNotOffered")
        }
    }

    /* ------------------------------------------------------------ the graph */

    @Test
    fun `a decision node keeps its model, questions, threshold and option edges across a save`() {
        val workflowId = workflow()
        val modelId = decisionModel()

        val problems = saveBranching(workflowId, modelId, threshold = 0.8)
            .path("saveWorkflowGraph.problems[*].message").entityList(String::class.java).get()
        assertThat(problems).noneMatch { it.contains("no line for") }

        graphQlTester.document(
            """query { workflowGraph(workspaceId: $workspaceId, workflowId: $workflowId) {
                 nodes { key decisionModelId decisionBranchQuestion decisionThreshold
                         decisionQuestions { key kind instructions options { name description } }
                         mappings { name expression } outputs { name } }
                 edges { source target branch option } } }""",
        ).execute()
            .path("workflowGraph.nodes[?(@.key == 'decide')].decisionModelId").entityList(Long::class.java).containsExactly(modelId)
            .path("workflowGraph.nodes[?(@.key == 'decide')].decisionBranchQuestion").entityList(String::class.java)
            .containsExactly("department")
            .path("workflowGraph.nodes[?(@.key == 'decide')].decisionThreshold").entityList(Double::class.java).containsExactly(0.8)
            .path("workflowGraph.nodes[?(@.key == 'decide')].decisionQuestions[*].key").entityList(String::class.java)
            .containsExactly("department", "urgent")
            .path("workflowGraph.nodes[?(@.key == 'decide')].decisionQuestions[0].options[*].name").entityList(String::class.java)
            .containsExactly("billing", "returns")
            .path("workflowGraph.nodes[?(@.key == 'decide')].mappings[*].name").entityList(String::class.java).containsExactly("state")
            // What a later node can point at: the answers under their keys.
            .path("workflowGraph.nodes[?(@.key == 'decide')].outputs[*].name").entityList(String::class.java)
            .contains("verdict", "verdict.department.choice", "verdict.department.sure", "verdict.urgent.holds")
            .path("workflowGraph.edges[?(@.branch == 'OPTION')].option").entityList(String::class.java)
            .containsExactly("billing", "returns")
            .path("workflowGraph.edges[?(@.branch == 'UNSURE')].target").entityList(String::class.java).containsExactly("unsure")
    }

    @Test
    fun `an option edge for an option the node does not offer is refused`() {
        val workflowId = workflow()

        graphQlTester.document(
            """mutation { saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                 nodes: [${decisionNode(null, 0.5)}, ${objectNode("billing")}],
                 edges: [{ source: "decide", target: "billing", branch: OPTION, option: "shipping" }]
               }) { nodes { key } } }""",
        ).execute().errors().expect { it.message?.contains("does not offer \"shipping\"") == true }.verify()
    }

    @Test
    fun `an option edge out of a node that does not branch is refused`() {
        val workflowId = workflow()

        graphQlTester.document(
            """mutation { saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                 nodes: [${objectNode("first")}, ${objectNode("billing")}],
                 edges: [{ source: "first", target: "billing", branch: UNSURE }]
               }) { nodes { key } } }""",
        ).execute().errors().expect { it.message?.contains("does not branch on a choice") == true }.verify()
    }

    @Test
    fun `a question key a later node could not name is refused`() {
        val workflowId = workflow()

        graphQlTester.document(
            """mutation { saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                 nodes: [{ key: "decide", kind: DECISION, name: "Route", x: 0, y: 0,
                           decisionQuestions: [{ key: "which team", kind: CHOICE, instructions: "?" }] }],
                 edges: []
               }) { nodes { key } } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.first().extensions["code"]).isEqualTo("DecisionQuestionKeyInvalid")
        }
    }

    @Test
    fun `an option nothing is drawn from is advice, not a refusal`() {
        val workflowId = workflow()
        val modelId = decisionModel()

        graphQlTester.document(
            """mutation { saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                 nodes: [${decisionNode(modelId, 0.8)}, ${objectNode("billing")}],
                 edges: [{ source: "decide", target: "billing", branch: OPTION, option: "billing" }]
               }) { problems { severity message } } }""",
        ).execute()
            .path("saveWorkflowGraph.problems[*].message").entityList(String::class.java).get()
            .let { messages ->
                assertThat(messages).anyMatch { it.contains("no line for \"returns\"") }
                assertThat(messages).anyMatch { it.contains("no line for an unsure answer") }
            }
    }

    /* ------------------------------------------------------------ the run */

    @Test
    fun `a sure choice sends the run down its option, and the request is the documented one`() {
        val workflowId = workflow()
        val modelId = decisionModel(secret = "ts-key")
        saveBranching(workflowId, modelId, threshold = 0.8)
        answer = choice("billing", 0.94)

        val run = run(workflowId)

        assertThat(run.status("decide")).isEqualTo("COMPLETED")
        assertThat(run.branch("decide")).isEqualTo("OPTION" to "billing")
        assertThat(run.status("billing")).isEqualTo("COMPLETED")
        assertThat(run.status("returns")).isEqualTo("SKIPPED")
        assertThat(run.status("unsure")).isEqualTo("SKIPPED")

        val body = JsonMapper.builder().build().readTree(sent.single())
        assertThat(body.path("state").stringValue()).isEqualTo("I was charged twice for one order")
        assertThat(body.path("model").stringValue()).isEqualTo("jev-latest")
        val department = body.path("questions").path("department")
        assertThat(department.path("type").stringValue()).isEqualTo("choice")
        assertThat(department.path("instructions").stringValue()).isEqualTo("Which team should handle this?")
        assertThat(department.path("criteria").path("billing").stringValue()).isEqualTo("Charges and refunds")
        // No description written: the name is what the model reads.
        assertThat(department.path("criteria").path("returns").stringValue()).isEqualTo("returns")
        val urgent = body.path("questions").path("urgent")
        assertThat(urgent.path("type").stringValue()).isEqualTo("noul")
        assertThat(urgent.path("criteria").path("true").stringValue()).isEqualTo("Needs an answer today")
        assertThat(authorizations.single()).isEqualTo("Bearer ts-key")

        // The answers go on as the model gave them, marked sure, under the node's name.
        val output = JsonMapper.builder().build().readTree(run.output("decide"))
        assertThat(output.path("verdict").path("department").path("choice").stringValue()).isEqualTo("billing")
        assertThat(output.path("verdict").path("department").path("sure").asBoolean()).isTrue()
        assertThat(output.path("verdict").path("urgent").path("holds").asBoolean()).isTrue()
    }

    @Test
    fun `an answer under the threshold leaves by the unsure edge`() {
        val workflowId = workflow()
        val modelId = decisionModel()
        saveBranching(workflowId, modelId, threshold = 0.8)
        answer = choice("returns", 0.55)

        val run = run(workflowId)

        assertThat(run.branch("decide")).isEqualTo("UNSURE" to null)
        assertThat(run.status("unsure")).isEqualTo("COMPLETED")
        assertThat(run.status("billing")).isEqualTo("SKIPPED")
        assertThat(run.status("returns")).isEqualTo("SKIPPED")
        // No key on a self-hosted Laya: nothing is sent in its place.
        assertThat(authorizations.single()).isEqualTo("none")
    }

    @Test
    fun `a refused request fails the step in the provider's words`() {
        val workflowId = workflow()
        val modelId = decisionModel()
        saveBranching(workflowId, modelId, threshold = null)
        server.removeContext("/v1/systemone")
        server.createContext("/v1/systemone") { exchange ->
            exchange.requestBody.readAllBytes()
            reply(exchange, 422, """{"detail":"criteria must have at least 2 options"}""")
        }

        val run = run(workflowId)

        assertThat(run.status("decide")).isEqualTo("FAILED")
        assertThat(run.error("decide")).contains("refused the questions (422)").contains("at least 2 options")
    }

    /* ------------------------------------------------------------ the fixture */

    private fun reply(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    /** TypeSafe's documented answer: a choice and a noul, with usage. */
    private fun choice(picked: String, confidence: Double) = """
        {"model":"jev-1.13.0",
         "answers":{
           "department":{"type":"choice","choice":"$picked","confidence":$confidence,
                         "probabilities":{"billing":${if (picked == "billing") confidence else 1 - confidence},
                                          "returns":${if (picked == "returns") confidence else 1 - confidence}}},
           "urgent":{"type":"noul","noul":0.91}},
         "usage":{"input_tokens":42,"output_tokens":0}}
    """.trimIndent()

    private fun provider(secret: String?): Long {
        val endpoint = "http://${server.address.hostString}:${server.address.port}"
        return graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Jev", endpoint: "$endpoint/v1/systemone", type: SYSTEM_ONE
                 ${secret?.let { ", secret: \"$it\"" } ?: ""}
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
    }

    private fun decisionModel(secret: String? = null): Long {
        val providerId = provider(secret)
        return graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $providerId, name: "Jev", modelId: "jev-latest", kind: DECISION
               }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }

    private fun decisionNode(modelId: Long?, threshold: Double?) = """
        { key: "decide", kind: DECISION, name: "Route", x: 0, y: 0, outputName: "verdict",
          ${modelId?.let { "decisionModelId: $it," } ?: ""}
          decisionBranchQuestion: "department",
          ${threshold?.let { "decisionThreshold: $it," } ?: ""}
          decisionQuestions: [
            { key: "department", kind: CHOICE, instructions: "Which team should handle this?",
              options: [{ name: "billing", description: "Charges and refunds" }, { name: "returns" }] },
            { key: "urgent", kind: NOUL, instructions: "Is it urgent?",
              options: [{ name: "true", description: "Needs an answer today" }, { name: "maybe", description: "dropped" }] }
          ],
          mappings: [{ name: "state", expression: "I was charged twice for one order", mode: VALUE }] }
    """.trimIndent()

    private fun objectNode(key: String) =
        """{ key: "$key", kind: OBJECT, name: "$key", x: 300, y: 0, mappings: [{ name: "route", expression: "$key", mode: VALUE }] }"""

    private fun saveBranching(workflowId: Long, modelId: Long, threshold: Double?) = graphQlTester.document(
        """mutation { saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
             nodes: [${decisionNode(modelId, threshold)}, ${objectNode("billing")}, ${objectNode("returns")}, ${objectNode("unsure")}],
             edges: [
               { source: "decide", target: "billing", branch: OPTION, option: "billing" },
               { source: "decide", target: "returns", branch: OPTION, option: "returns" },
               { source: "decide", target: "unsure", branch: UNSURE }
             ]
           }) { problems { severity message } } }""",
    ).execute()

    private fun workflow(): Long = graphQlTester.document(
        """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Triage" }) { workflowId } }""",
    ).execute().path("createWorkflow.workflowId").entity(String::class.java).get().toLong()

    /** A run of the draft, as Run in the editor starts one, read back when it is over. */
    private fun run(workflowId: Long): Ran {
        val id = graphQlTester.document(
            """mutation { startExecution(workspaceId: $workspaceId, workflowId: $workflowId) { id } }""",
        ).execute().path("startExecution.id").entity(String::class.java).get()
        val steps = graphQlTester.document(
            """query { execution(id: $id) { steps { key status branch branchOption output error } } }""",
        ).execute().path("execution.steps").entityList(Map::class.java).get()
        return Ran(steps.associateBy { it["key"] as String })
    }

    private class Ran(val steps: Map<String, Map<*, *>>) {
        fun status(key: String) = steps.getValue(key)["status"]
        fun branch(key: String) = steps.getValue(key)["branch"] to steps.getValue(key)["branchOption"]
        fun output(key: String) = steps.getValue(key)["output"] as String
        fun error(key: String) = steps.getValue(key)["error"] as String
    }
}
