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
 * An image node's size, quality and style reach the provider, only when set,
 * and only when the model takes them.
 *
 * Issue #423 gave the node the three parameters every OpenAI-shaped image
 * endpoint takes; #431 made the list the *model's*. What is pinned here is the
 * wire: the words the node holds are the words in the body, under the standard
 * names; a parameter the node left alone is *absent* - not sent as null, not
 * sent as a guess - so the model's own default stands; and a parameter the
 * model's endpoint does not take is absent too, whatever the step carries.
 *
 * Driven through [ImageNodeRunner] with a real [ExecutionStep] row, against a
 * stub answering `/images/generations`, because the step's copy is what the
 * runner reads and the stub's body is the only place the request can be seen.
 * The save half goes through the graph mutation like the editor does: a DALL-E
 * 3 refuses a size it does not draw and takes one it does, a self-hosted
 * provider takes any dimensions and refuses a style, gpt-image-1 refuses a
 * style, and each refusal names what the model does take.
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
        val modelId = imageModel("dall-e-3")
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
        val modelId = imageModel("gpt-image-1")
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
        val modelId = imageModel("gpt-image-1")
        val step = step(modelId, size = "512x512", quality = null, style = null)

        runner.run(step, input = null, trigger = null)

        val body = sent.single().replace(" ", "")
        assertThat(body).contains("\"size\":\"512x512\"")
        assertThat(body).doesNotContain("\"quality\"")
        assertThat(body).doesNotContain("\"style\"")
    }

    /**
     * A step planned with a style reaches a model that takes none without it.
     * The save refuses that today; a step already planned carries its own copy,
     * and the wire is where the provider's 400 would have come from.
     */
    @Test
    fun `a parameter the model does not take is left out of the request`() {
        val modelId = imageModel("gpt-image-1-mini")
        val step = step(modelId, size = "1536x1024", quality = "high", style = "vivid")

        val result = runner.run(step, input = null, trigger = null)

        assertThat(result.status).describedAs(result.output).isEqualTo(StepStatus.COMPLETED)
        val body = sent.single().replace(" ", "")
        assertThat(body).contains("\"size\":\"1536x1024\"")
        assertThat(body).contains("\"quality\":\"high\"")
        assertThat(body).doesNotContain("\"style\"")
    }

    /** What the editor saves comes back as it went in, which is what the run copies. */
    @Test
    fun `an image node keeps its size, quality and style across a save`() {
        val workflowId = workflow()
        val modelId = imageModel("dall-e-3")

        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [{
                  key: "draws", kind: IMAGE, name: "Image model", x: 0, y: 0, imageModelId: $modelId,
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
     * A word the model does not take is refused when written, in a sentence
     * naming the words it does, rather than as a 400 in the middle of a run a
     * week later. DALL-E 3 draws three sizes and takes two qualities.
     */
    @Test
    fun `a value off the model's list is refused at save with the model's list`() {
        val workflowId = workflow()
        val modelId = imageModel("dall-e-3")

        save(workflowId, modelId, """imageQuality: "ultra"""").errors().expect {
            it.message?.contains("\"ultra\" is not a quality Drawer takes") == true &&
                it.message?.contains("standard, hd") == true &&
                it.message?.contains("low") == false
        }.verify()
        save(workflowId, modelId, """imageSize: "800x600"""").errors().expect {
            it.message?.contains("\"800x600\" is not a size Drawer takes") == true &&
                it.message?.contains("1024x1024, 1792x1024, 1024x1792") == true
        }.verify()
        save(workflowId, modelId, """imageSize: "1792x1024"""")
            .path("saveWorkflowGraph.nodes[0].imageSize").entity(String::class.java).isEqualTo("1792x1024")
    }

    /**
     * A provider that is not OpenAI's takes any width and height, in multiples
     * of eight from 64 to 4096, and nothing else: no quality, no style.
     */
    @Test
    fun `a self-hosted model takes free dimensions and refuses a style`() {
        val workflowId = workflow()
        val modelId = imageModel("sdxl-turbo", type = "OLLAMA")

        save(workflowId, modelId, """imageSize: "800x600"""")
            .path("saveWorkflowGraph.nodes[0].imageSize").entity(String::class.java).isEqualTo("800x600")
        save(workflowId, modelId, """imageSize: "801x600"""").errors().expect {
            it.message?.contains("\"801x600\" is not a size Drawer takes") == true &&
                it.message?.contains("from 64 to 4096, a multiple of 8") == true
        }.verify()
        save(workflowId, modelId, """imageStyle: "vivid"""").errors().expect {
            it.message == "Drawer does not take a style; it takes size"
        }.verify()
        save(workflowId, modelId, """imageQuality: "hd"""").errors().expect {
            it.message == "Drawer does not take a quality; it takes size"
        }.verify()
    }

    /**
     * A self-hosted server that speaks OpenAI's shape is registered as type
     * OPENAI too. What tells it apart is the host: an id OpenAI does not have,
     * served anywhere but OpenAI's own host, is a model of the server's - and
     * those take a free size. A model that names itself keeps its own list
     * wherever it is served, which the dall-e-3 tests above already show against
     * this same stub host.
     */
    @Test
    fun `an unknown model on an OpenAI-shaped self-hosted server takes free dimensions`() {
        val workflowId = workflow()
        val modelId = imageModel("sdxl-turbo", type = "OPENAI")

        save(workflowId, modelId, """imageSize: "800x600"""")
            .path("saveWorkflowGraph.nodes[0].imageSize").entity(String::class.java).isEqualTo("800x600")
        save(workflowId, modelId, """imageStyle: "vivid"""").errors().expect {
            it.message == "Drawer does not take a style; it takes size"
        }.verify()
    }

    /** gpt-image-1, in any of its sizes, takes a size and a quality and no style. */
    @Test
    fun `gpt-image-1 refuses a style and takes its own sizes and qualities`() {
        val workflowId = workflow()
        val modelId = imageModel("gpt-image-1-mini")

        save(workflowId, modelId, """imageStyle: "natural"""").errors().expect {
            it.message == "Drawer does not take a style; it takes size, quality"
        }.verify()
        save(workflowId, modelId, """imageSize: "auto", imageQuality: "low"""")
            .path("saveWorkflowGraph.nodes[0].imageSize").entity(String::class.java).isEqualTo("auto")
            .path("saveWorkflowGraph.nodes[0].imageQuality").entity(String::class.java).isEqualTo("low")
        save(workflowId, modelId, """imageQuality: "hd"""").errors().expect {
            it.message?.contains("low, medium, high, auto") == true
        }.verify()
    }

    /** What the editor asks before drawing its controls: one entry per parameter the model takes. */
    @Test
    fun `the editor can ask what a model takes`() {
        val dallE = imageModel("DALL-E-3", name = "Painter")
        graphQlTester.document("""{ imageModelParameters(modelId: $dallE) { name kind choices minSide maxSide step } }""")
            .execute()
            .path("imageModelParameters[*].name").entityList(String::class.java).containsExactly("size", "quality", "style")
            .path("imageModelParameters[0].kind").entity(String::class.java).isEqualTo("CHOICE")
            .path("imageModelParameters[2].choices").entityList(String::class.java).containsExactly("vivid", "natural")
            .path("imageModelParameters[0].minSide").valueIsNull()

        val hosted = imageModel("flux-schnell", name = "Hosted", type = "OLLAMA")
        graphQlTester.document("""{ imageModelParameters(modelId: $hosted) { name kind choices minSide maxSide step } }""")
            .execute()
            .path("imageModelParameters[*].name").entityList(String::class.java).containsExactly("size")
            .path("imageModelParameters[0].kind").entity(String::class.java).isEqualTo("DIMENSIONS")
            .path("imageModelParameters[0].choices").entityList(String::class.java).hasSize(0)
            .path("imageModelParameters[0].minSide").entity(Int::class.java).isEqualTo(64)
            .path("imageModelParameters[0].maxSide").entity(Int::class.java).isEqualTo(4096)
            .path("imageModelParameters[0].step").entity(Int::class.java).isEqualTo(8)

        val dallE2 = imageModel("dall-e-2", name = "Old")
        graphQlTester.document("""{ imageModelParameters(modelId: $dallE2) { name choices } }""")
            .execute()
            .path("imageModelParameters[*].name").entityList(String::class.java).containsExactly("size")
            .path("imageModelParameters[0].choices").entityList(String::class.java).containsExactly("256x256", "512x512", "1024x1024")

        /*
         * And the self-hosted server registered as OPENAI answers the editor
         * the same way it answers the save. It did not: the query read the
         * type and the id but not the host, so the editor drew gpt-image-1's
         * quality list for a model the save then refused a quality on.
         */
        val selfHosted = imageModel("sd-cpp-local", name = "Local", type = "OPENAI")
        graphQlTester.document("""{ imageModelParameters(modelId: $selfHosted) { name kind } }""")
            .execute()
            .path("imageModelParameters[*].name").entityList(String::class.java).containsExactly("size")
            .path("imageModelParameters[0].kind").entity(String::class.java).isEqualTo("DIMENSIONS")

        graphQlTester.document("""{ imageModelParameters(modelId: 999999) { name } }""")
            .execute().errors().expect { it.message?.contains("No model with id 999999") == true }.verify()
    }

    /**
     * A node with no model yet keeps what it was typed: there is nothing to
     * hold it to, and the run skips a node with no model before reading any of
     * this. Choosing a model afterwards holds the next save to that model.
     */
    @Test
    fun `a node with no model keeps its values until a model is chosen`() {
        val workflowId = workflow()

        save(workflowId, null, """imageSize: "800x600", imageStyle: "vivid"""")
            .path("saveWorkflowGraph.nodes[0].imageSize").entity(String::class.java).isEqualTo("800x600")

        val modelId = imageModel("dall-e-3")
        save(workflowId, modelId, """imageSize: "800x600"""").errors().expect {
            it.message?.contains("is not a size Drawer takes") == true
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

    /** The graph saved with one image node carrying these fields, as the editor sends it. */
    private fun save(workflowId: Long, modelId: Long?, fields: String) = graphQlTester.document(
        """
        mutation {
          saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
            nodes: [{ key: "draws", kind: IMAGE, name: "Image model", x: 0, y: 0,
                      ${modelId?.let { "imageModelId: $it," } ?: ""} $fields, mappings: [] }],
            edges: []
          }) { nodes { key imageSize imageQuality imageStyle } }
        }
        """,
    ).execute()

    /**
     * A provider in front of the stub and an image model on it, called what the
     * spec is matched on: `dall-e-3`, `gpt-image-1-mini`, or anything at all on
     * a provider that is not OpenAI's.
     */
    private fun imageModel(modelId: String, name: String = "Drawer", type: String = "OPENAI"): Long {
        val endpoint = "http://${server.address.hostString}:${server.address.port}"
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "$name provider", endpoint: "$endpoint",
                 type: $type, secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        return graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $providerId, name: "$name", modelId: "$modelId", kind: IMAGE
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
