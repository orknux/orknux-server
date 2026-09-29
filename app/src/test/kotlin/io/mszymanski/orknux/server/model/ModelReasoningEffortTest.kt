package io.mszymanski.orknux.server.model

import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.connector.model.ModelUsageRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * A chat model's provider-specific settings: declared per provider type, drawn
 * from that declaration, and held to it on every save. Azure OpenAI's reasoning
 * effort is the first of them.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ModelReasoningEffortTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val usage: ModelUsageRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        usage.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    @Test
    fun `Azure OpenAI declares a reasoning effort, and a provider type that takes none declares nothing`() {
        graphQlTester.document("""{ chatModelParameters(providerType: AZURE_OPENAI) { name choices } }""").execute()
            .path("chatModelParameters[*].name").entityList(String::class.java).containsExactly("reasoningEffort")
            .path("chatModelParameters[0].choices").entityList(String::class.java)
            .containsExactly("minimal", "low", "medium", "high")

        graphQlTester.document("""{ chatModelParameters(providerType: OPENAI) { name } }""").execute()
            .path("chatModelParameters").entityList(Any::class.java).hasSize(0)
        graphQlTester.document("""{ chatModelParameters(providerType: OLLAMA) { name } }""").execute()
            .path("chatModelParameters").entityList(Any::class.java).hasSize(0)
    }

    @Test
    fun `an Azure model is created with a reasoning effort, set and cleared from its page`() {
        val azure = provider("Azure", "AZURE_OPENAI")
        val id = graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $azure, name: "o4-mini", modelId: "o4-mini", kind: CHAT, reasoningEffort: "low"
               }) { id reasoningEffort } }""",
        ).execute()
            .path("createModel.reasoningEffort").entity(String::class.java).isEqualTo("low")
            .path("createModel.id").entity(Long::class.java).get()

        update(id, """"high"""")
            .path("updateModel.reasoningEffort").entity(String::class.java).isEqualTo("high")
        assertThat(models.findById(id).get().reasoningEffort).isEqualTo("high")
        assertThat(audit.findAll().map { it.message }).contains("Model o4-mini reasoning effort set to high")

        graphQlTester.document("""{ model(id: $id) { reasoningEffort } }""").execute()
            .path("model.reasoningEffort").entity(String::class.java).isEqualTo("high")

        // The form sends every field, so null is an emptied select: the deployment decides again.
        update(id, "null").path("updateModel.reasoningEffort").valueIsNull()
        assertThat(models.findById(id).get().reasoningEffort).isNull()
        assertThat(audit.findAll().map { it.message }).contains("Model o4-mini reasoning effort cleared")
    }

    @Test
    fun `a duplicate carries the reasoning effort`() {
        val azure = provider("Azure", "AZURE_OPENAI")
        val id = model(azure, "o3")
        update(id, """"medium"""")

        graphQlTester.document("""mutation { duplicateModel(id: $id) { name reasoningEffort } }""").execute()
            .path("duplicateModel.reasoningEffort").entity(String::class.java).isEqualTo("medium")
        graphQlTester.document("""mutation { duplicateModelProvider(id: $azure) { id } }""").execute()
        assertThat(models.findAll().filter { it.name == "o3" }.map { it.reasoningEffort })
            .containsExactly("medium", "medium")
    }

    @Test
    fun `a provider type that takes no reasoning effort refuses one`() {
        val openai = provider("OpenAI", "OPENAI")
        graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $openai, name: "gpt-4o", modelId: "gpt-4o", kind: CHAT, reasoningEffort: "high"
               }) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().message).contains("does not take reasoningEffort")
            assertThat(errors.single().extensions["code"]).isEqualTo("ModelParameterNotTaken")
        }
        assertThat(models.findAll()).isEmpty()

        val id = model(openai, "gpt-4o")
        update(id, """"high"""").errors().satisfy { errors ->
            assertThat(errors.single().extensions["code"]).isEqualTo("ModelParameterNotTaken")
        }
        assertThat(models.findById(id).get().reasoningEffort).isNull()

        // A move onto a type that does not take it is refused rather than carried silently.
        val azure = provider("Azure", "AZURE_OPENAI")
        val thoughtful = model(azure, "o3")
        update(thoughtful, """"low"""")
        graphQlTester.document(
            """mutation { updateModel(id: $thoughtful, input: {
                 name: "o3", modelId: "o3", kind: CHAT, reasoningEffort: "low", providerId: $openai
               }) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().extensions["code"]).isEqualTo("ModelParameterNotTaken")
        }
        assertThat(models.findById(thoughtful).get().providerId).isEqualTo(azure)
    }

    @Test
    fun `a word Azure does not take is refused, naming the ones it does`() {
        val azure = provider("Azure", "AZURE_OPENAI")
        val id = model(azure, "o4-mini")

        update(id, """"extreme"""").errors().satisfy { errors ->
            assertThat(errors.single().message).contains("\"extreme\"").contains("minimal, low, medium, high")
            assertThat(errors.single().extensions["code"]).isEqualTo("ModelParameterValueInvalid")
        }
        assertThat(models.findById(id).get().reasoningEffort).isNull()
    }

    private fun update(id: Long, effort: String) = graphQlTester.document(
        """mutation { updateModel(id: $id, input: {
             name: "${models.findById(id).get().name}", modelId: "${models.findById(id).get().modelId}",
             kind: CHAT, reasoningEffort: $effort
           }) { id reasoningEffort } }""",
    ).execute()

    private fun provider(name: String, type: String): Long = graphQlTester.document(
        """mutation { createModelProvider(input: {
             workspaceId: $workspaceId, name: "$name", type: $type, endpoint: "https://$name.example.invalid/v1",
             secret: "sk-test"
           }) { id } }""",
    ).execute().path("createModelProvider.id").entity(Long::class.java).get()

    private fun model(providerId: Long, name: String): Long = graphQlTester.document(
        """mutation { createModel(input: { providerId: $providerId, name: "$name", modelId: "$name", kind: CHAT }) { id } }""",
    ).execute().path("createModel.id").entity(Long::class.java).get()
}
