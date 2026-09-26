package io.mszymanski.orknux.server.workflow

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.connector.model.ModelUsageRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStatus
import io.mszymanski.orknux.workflow.execution.ExecutionStep
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.ExecutionTrigger
import io.mszymanski.orknux.workflow.execution.NodeKind
import io.mszymanski.orknux.workflow.execution.StepStatus
import io.mszymanski.orknux.workflow.execution.WorkflowExecution
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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An image node's size, quality and style reach the provider, and only when set.
 *
 * Issue #423. The node used to draw at whatever size the model chose, because
 * the request named a model and a prompt and nothing else. It can now ask for
 * the three parameters every OpenAI-shaped image endpoint takes, and what is
 * pinned here is the wire: the words the node holds are the words in the body,
 * under the standard names, and a parameter the node left alone is *absent* -
 * not sent as null, not sent as a guess - so the model's own default stands,
 * which is what every node drawn before this asked for.
 *
 * Driven through [ImageNodeRunner] with a real [ExecutionStep] row, against a
 * stub answering `/images/generations`, because the step's copy is what the
 * runner reads and the stub's body is the only place the request can be seen.
 * The save half - that the editor's values survive a round trip and that a
 * word no endpoint takes is refused in a sentence - goes through the graph
 * mutation like the editor does.
 *
 * Its own attachment directory under `target`, because a drawn picture is
 * filed as real bytes on a real disk.
 */
@SpringBootTest(properties = ["orknux.attachments.location=target/test-image-node-parameters"])
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ImageNodeParametersTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val runner: ImageNodeRunner,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val pictures: ExecutionPictureRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val usage: ModelUsageRepository,
    @Autowired val workflows: WorkflowRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer

    /** Every body the stub was sent, which is where the request can be read. */
    private val sent = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun reset() {
        pictures.deleteAll()
        steps.deleteAll()
        executions.deleteAll()
        nodes.deleteAll()
        edges.deleteAll()
        assignments.deleteAll()
        workflows.deleteAll()
        usage.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        sent.clear()

        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/images/generations") { exchange ->
            sent += exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val bytes = """{"created":1,"data":[{"b64_json":"$PIXEL"}]}""".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `a step's size, quality and style go into the request under their standard names`() {
        val modelId = imageModel()
        val step = step(modelId, size = "1024x1536", quality = "hd", style = "natural")

        val result = runner.run(step, input = null, trigger = null)

        assertThat(result.status).describedAs(result.output).isEqualTo(StepStatus.COMPLETED)
        val body = sent.single().replace(" ", "")
        assertThat(body).contains("\"size\":\"1024x1536\"")
        assertThat(body).contains("\"quality\":\"hd\"")
        assertThat(body).contains("\"style\":\"natural\"")
        // Still one picture per request: that is what the per-image price counts.
        assertThat(body).contains("\"n\":1")
    }

    /**
     * Absent, not null. A provider handed `"size":null` may refuse it, and one
     * handed a size this application guessed would draw at that guess; either
     * way the model's default would have stopped being the default.
     */
    @Test
    fun `a parameter the node left alone is not in the request at all`() {
        val modelId = imageModel()
        val step = step(modelId, size = null, quality = null, style = null)

        val result = runner.run(step, input = null, trigger = null)

        assertThat(result.status).describedAs(result.output).isEqualTo(StepStatus.COMPLETED)
        val body = sent.single()
        assertThat(body).doesNotContain("\"size\"")
        assertThat(body).doesNotContain("\"quality\"")
        assertThat(body).doesNotContain("\"style\"")
    }

    /** And each on its own: one set and two left alone is one field and two absences. */
    @Test
    fun `only the parameters that are set are sent`() {
        val modelId = imageModel()
        val step = step(modelId, size = "512x512", quality = null, style = null)

        runner.run(step, input = null, trigger = null)

        val body = sent.single().replace(" ", "")
        assertThat(body).contains("\"size\":\"512x512\"")
        assertThat(body).doesNotContain("\"quality\"")
        assertThat(body).doesNotContain("\"style\"")
    }

    /** What the editor saves comes back as it went in, which is what the run copies. */
    @Test
    fun `an image node keeps its size, quality and style across a save`() {
        val workflowId = workflow()

        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [{
                  key: "draws", kind: IMAGE, name: "Image model", x: 0, y: 0,
                  imageSize: "1792x1024", imageQuality: "standard", imageStyle: "vivid",
                  mappings: [{ name: "prompt", expression: "a hen in a hat", mode: VALUE }]
                }],
                edges: []
              }) { nodes { key imageSize imageQuality imageStyle } }
            }
            """,
        ).execute()
            .path("saveWorkflowGraph.nodes[0].imageSize").entity(String::class.java).isEqualTo("1792x1024")
            .path("saveWorkflowGraph.nodes[0].imageQuality").entity(String::class.java).isEqualTo("standard")
            .path("saveWorkflowGraph.nodes[0].imageStyle").entity(String::class.java).isEqualTo("vivid")

        graphQlTester.document(
            """query { workflowGraph(workspaceId: $workspaceId, workflowId: $workflowId) {
                 nodes { imageSize imageQuality imageStyle } } }""",
        ).execute()
            .path("workflowGraph.nodes[0].imageSize").entity(String::class.java).isEqualTo("1792x1024")
            .path("workflowGraph.nodes[0].imageQuality").entity(String::class.java).isEqualTo("standard")
            .path("workflowGraph.nodes[0].imageStyle").entity(String::class.java).isEqualTo("vivid")
    }

    /** Blank is the model's default, stored as nothing rather than as an empty word. */
    @Test
    fun `a blank parameter is saved as the model's default`() {
        val workflowId = workflow()

        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [{ key: "draws", kind: IMAGE, name: "Image model", x: 0, y: 0, imageSize: "  ", mappings: [] }],
                edges: []
              }) { nodes { imageSize imageQuality } }
            }
            """,
        ).execute()
            .path("saveWorkflowGraph.nodes[0].imageSize").valueIsNull()
            .path("saveWorkflowGraph.nodes[0].imageQuality").valueIsNull()
    }

    /**
     * A word no endpoint takes is refused when written, in a sentence naming the
     * words that are, rather than as a 400 in the middle of a run a week later.
     */
    @Test
    fun `a value off the list is refused at save with the list`() {
        val workflowId = workflow()

        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [{ key: "draws", kind: IMAGE, name: "Image model", x: 0, y: 0, imageQuality: "ultra", mappings: [] }],
                edges: []
              }) { nodes { key } }
            }
            """,
        ).execute().errors().expect {
            it.message?.contains("\"ultra\" is not a quality an image model takes") == true &&
                it.message?.contains("standard, hd, low, medium, high") == true
        }.verify()
    }

    /** A size that is not on the list goes the same way, whatever kind of node it is not. */
    @Test
    fun `the parameters are kept only on an image node`() {
        val workflowId = workflow()

        // On an agent node they mean nothing, and are dropped rather than refused
        // or kept: the same rule every other kind-specific field follows.
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [{ key: "talks", kind: AGENT, name: "Agent", x: 0, y: 0, imageSize: "1024x1024", outputName: "reply" }],
                edges: []
              }) { nodes { imageSize } }
            }
            """,
        ).execute().path("saveWorkflowGraph.nodes[0].imageSize").valueIsNull()
    }

    /* ----------------------------------------------------------- the fixture */

    /** A run and one image step in it, as the planner would have written them. */
    private fun step(modelId: Long, size: String?, quality: String?, style: String?): ExecutionStep {
        val execution = executions.save(
            WorkflowExecution(
                workspaceId = workspaceId,
                workflowId = 1,
                workflowName = "Draw things",
                status = ExecutionStatus.RUNNING,
                trigger = ExecutionTrigger.MANUAL,
                startedAt = OffsetDateTime.now(),
            ),
        )
        return steps.save(
            ExecutionStep(
                executionId = requireNotNull(execution.id),
                nodeKey = "draw",
                kind = NodeKind.IMAGE,
                name = "Draw",
                imageModelId = modelId,
                imageSize = size,
                imageQuality = quality,
                imageStyle = style,
                outputName = "image",
                mappings = """{"prompt":{"expression":"a red bicycle","reference":false,"from":null}}""",
                order = 0,
                x = 0.0,
                y = 0.0,
            ),
        )
    }

    /** An OpenAI-shaped provider in front of the stub, and an image model on it. */
    private fun imageModel(): Long {
        val endpoint = "http://${server.address.hostString}:${server.address.port}"
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub", endpoint: "$endpoint",
                 type: OPENAI, secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        return graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $providerId, name: "Drawer", modelId: "stub-image", kind: IMAGE
               }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }

    private fun workflow(): Long = graphQlTester.document(
        """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Drawing" }) { workflowId } }""",
    ).execute().path("createWorkflow.workflowId").entity(String::class.java).get().toLong()

    private companion object {
        /** A one-pixel PNG's opening bytes, as a provider would send them. */
        val PIXEL: String = Base64.getEncoder().encodeToString(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
    }
}
