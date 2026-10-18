package io.mszymanski.orknux.server.dependency

import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * What uses a model, and what a provider holds.
 *
 * A model page had no Used by, and removing a model said nothing: the agents
 * thinking with it were left with none and could not run, and the workspace
 * settings naming it pointed at nothing, each found out on its next use. A
 * model now answers with its agents, the settings that name it - opened at the
 * setting - its actions and the workflows whose nodes draw or decide with it,
 * and is refused removal while any of them does.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ModelDependantsTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private var providerId: Long = 0

    @BeforeEach
    fun seed() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "models-${System.nanoTime()}")).id)
        providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Local", endpoint: "http://local.invalid/v1", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
    }

    @Test
    fun `a chat model answers with its agents and the settings that name it, and is not removed while it does`() {
        val model = model("Gemma", "gemma", "CHAT")
        val unused = model("Spare", "spare", "CHAT")
        val agent = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Responder", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        graphQlTester.document(
            """mutation { updateAgent(id: $agent, input: { name: "Responder", modelId: $model }) { id } }""",
        ).execute().path("updateAgent.id").hasValue()
        requireNotNull(workspaces.findByIdOrNull(workspaceId)).also { it.companionModelId = model }.let(workspaces::save)

        val found = dependants("MODEL", model)
        found.path("componentDependants.entries[*].kind").entityList(String::class.java).containsExactly("AGENT", "WORKSPACE")
        found.path("componentDependants.entries[0].id").entity(Long::class.java).isEqualTo(agent)
        found.path("componentDependants.entries[1].name").entity(String::class.java).isEqualTo("Companion model")
        found.path("componentDependants.entries[1].section").entity(String::class.java).isEqualTo("companion-model")
        dependants("MODEL", unused).path("componentDependants.entries").entityList(Map::class.java).hasSize(0)

        graphQlTester.document("mutation { removeModel(id: $model) }").execute().errors().satisfy { errors ->
            assertThat(errors.single().extensions["code"]).isEqualTo("ModelInUse")
            assertThat(errors.single().message).contains("Responder", "the workspace's companion model")
        }
        graphQlTester.document("mutation { removeModel(id: $unused) }").execute()
            .path("removeModel").entity(Boolean::class.java).isEqualTo(true)
    }

    @Test
    fun `an image model answers with the workflow whose node draws with it, and the provider with its models`() {
        val model = model("Painter", "gpt-image-1", "IMAGE")
        val workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Drawing" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(String::class.java).get().toLong()
        graphQlTester.document(
            """mutation { saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                 nodes: [{ key: "draws", kind: IMAGE, name: "Draw", x: 0, y: 0, imageModelId: $model,
                   mappings: [{ name: "prompt", expression: "a hen", mode: VALUE }] }],
                 edges: [] }) { workflowId } }""",
        ).execute().path("saveWorkflowGraph.workflowId").hasValue()

        val found = dependants("MODEL", model)
        found.path("componentDependants.entries[*].kind").entityList(String::class.java).containsExactly("WORKFLOW")
        found.path("componentDependants.entries[0].name").entity(String::class.java).isEqualTo("Drawing")

        dependants("MODEL_PROVIDER", providerId)
            .path("componentDependants.entries[*].name").entityList(String::class.java).containsExactly("Painter")
    }

    private fun model(name: String, id: String, kind: String): Long = graphQlTester.document(
        """mutation { createModel(input: { providerId: $providerId, name: "$name", modelId: "$id", kind: $kind }) { id } }""",
    ).execute().path("createModel.id").entity(Long::class.java).get()

    private fun dependants(kind: String, id: Long) = graphQlTester.document(
        """{ componentDependants(kind: $kind, componentId: $id) { entries { kind id name section } hidden } }""",
    ).execute()
}
