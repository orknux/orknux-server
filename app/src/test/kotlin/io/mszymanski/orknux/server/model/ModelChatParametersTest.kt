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
 * A chat model's sampling and reasoning settings are the provider's to take:
 * declared per provider, drawn from that declaration, and held to it on every
 * save. Azure OpenAI takes temperature, top-p and a reasoning effort; a
 * llama.cpp server behind the OpenAI shape takes the three additions as well;
 * OpenAI itself and Ollama's `/v1` take temperature and top-p.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ModelChatParametersTest(
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
    fun `each provider declares what its API reads, and nothing more`() {
        assertThat(declared(provider("Azure", "AZURE_OPENAI"))).containsExactly("temperature", "topP", "reasoningEffort")
        assertThat(declared(provider("OpenAI", "OPENAI", "https://api.openai.com/v1"))).containsExactly("temperature", "topP")
        // A llama.cpp server registered under the OpenAI type reads the three additions too.
        assertThat(declared(provider("Local", "OPENAI", "http://llama.example.invalid:8080/v1")))
            .containsExactly("temperature", "topP", "topK", "minP", "repeatPenalty")
        assertThat(declared(provider("Ollama", "OLLAMA", "http://ollama.example.invalid:11434"))).containsExactly("temperature", "topP")
        assertThat(declared(provider("Anthropic", "ANTHROPIC", "https://api.anthropic.com/v1")))
            .containsExactly("temperature", "topP", "topK")

        val azure = provider("Azure2", "AZURE_OPENAI")
        graphQlTester.document("""{ chatModelParameters(providerId: $azure) { name kind choices } }""").execute()
            .path("chatModelParameters[2].kind").entity(String::class.java).isEqualTo("CHOICE")
            .path("chatModelParameters[2].choices").entityList(String::class.java).containsExactly("minimal", "low", "medium", "high")
            .path("chatModelParameters[0].kind").entity(String::class.java).isEqualTo("NUMBER")
    }

    @Test
    fun `another workspace's provider is answered as one that does not exist`() {
        graphQlTester.document("""{ chatModelParameters(providerId: 987654) { name } }""").execute()
            .errors().satisfy { errors -> assertThat(errors.single().extensions["code"]).isEqualTo("ModelProviderNotFound") }
    }

    @Test
    fun `an Azure model is created with a reasoning effort, set and cleared from its page`() {
        val azure = provider("Azure", "AZURE_OPENAI")
        val id = graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $azure, name: "o4-mini", modelId: "o4-mini", kind: CHAT, reasoningEffort: "low", temperature: 1.0
               }) { id reasoningEffort } }""",
        ).execute()
            .path("createModel.reasoningEffort").entity(String::class.java).isEqualTo("low")
            .path("createModel.id").entity(Long::class.java).get()

        update(id, """reasoningEffort: "high"""")
            .path("updateModel.reasoningEffort").entity(String::class.java).isEqualTo("high")
        assertThat(models.findById(id).get().reasoningEffort).isEqualTo("high")
        assertThat(audit.findAll().map { it.message }).contains("Model o4-mini reasoning effort set to high")

        graphQlTester.document("""{ model(id: $id) { reasoningEffort } }""").execute()
            .path("model.reasoningEffort").entity(String::class.java).isEqualTo("high")

        // The form sends every field, so null is an emptied select: the deployment decides again.
        update(id, "reasoningEffort: null").path("updateModel.reasoningEffort").valueIsNull()
        assertThat(models.findById(id).get().reasoningEffort).isNull()
        assertThat(audit.findAll().map { it.message }).contains("Model o4-mini reasoning effort cleared")
    }

    @Test
    fun `a duplicate carries the reasoning effort`() {
        val azure = provider("Azure", "AZURE_OPENAI")
        val id = model(azure, "o3")
        update(id, """reasoningEffort: "medium"""")

        graphQlTester.document("""mutation { duplicateModel(id: $id) { name reasoningEffort } }""").execute()
            .path("duplicateModel.reasoningEffort").entity(String::class.java).isEqualTo("medium")
        graphQlTester.document("""mutation { duplicateModelProvider(id: $azure) { id } }""").execute()
        assertThat(models.findAll().filter { it.name == "o3" }.map { it.reasoningEffort })
            .containsExactly("medium", "medium")
    }

    @Test
    fun `a provider that takes no reasoning effort refuses one`() {
        val openai = provider("OpenAI", "OPENAI", "https://api.openai.com/v1")
        graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $openai, name: "gpt-4o", modelId: "gpt-4o", kind: CHAT, reasoningEffort: "high"
               }) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().message).contains("do not take reasoningEffort")
            assertThat(errors.single().extensions["code"]).isEqualTo("ModelParameterNotTaken")
        }
        assertThat(models.findAll()).isEmpty()

        val id = model(openai, "gpt-4o")
        update(id, """reasoningEffort: "high"""").errors().satisfy { errors ->
            assertThat(errors.single().extensions["code"]).isEqualTo("ModelParameterNotTaken")
        }
        assertThat(models.findById(id).get().reasoningEffort).isNull()

        // A move onto a provider that does not take it is refused rather than carried silently.
        val azure = provider("Azure", "AZURE_OPENAI")
        val thoughtful = model(azure, "o3")
        update(thoughtful, """reasoningEffort: "low"""")
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
    fun `a sampling setting the provider does not read is refused, and one it reads is kept`() {
        val azure = model(provider("Azure", "AZURE_OPENAI"), "o4-mini")
        listOf("topK: 40", "minP: 0.05", "repeatPenalty: 1.1").forEach { setting ->
            update(azure, setting).errors().satisfy { errors ->
                assertThat(errors.single().extensions["code"]).isEqualTo("ModelParameterNotTaken")
            }
        }
        update(azure, "temperature: 0.2, topP: 0.9").path("updateModel.id").hasValue()
        assertThat(models.findById(azure).get().temperature).isEqualTo(0.2)

        val ollama = model(provider("Ollama", "OLLAMA", "http://ollama.example.invalid:11434"), "gemma")
        update(ollama, "topK: 40").errors().satisfy { errors ->
            assertThat(errors.single().message).contains("do not take topK")
        }

        val local = model(provider("Local", "OPENAI", "http://llama.example.invalid:8080/v1"), "gemma")
        update(local, "temperature: 0.7, topP: 0.95, topK: 40, minP: 0.05, repeatPenalty: 1.1").path("updateModel.id").hasValue()
        assertThat(models.findById(local).get().topK).isEqualTo(40)
        assertThat(models.findById(local).get().repeatPenalty).isEqualTo(1.1)
    }

    @Test
    fun `a word Azure does not take is refused, naming the ones it does`() {
        val azure = provider("Azure", "AZURE_OPENAI")
        val id = model(azure, "o4-mini")

        update(id, """reasoningEffort: "extreme"""").errors().satisfy { errors ->
            assertThat(errors.single().message).contains("\"extreme\"").contains("minimal, low, medium, high")
            assertThat(errors.single().extensions["code"]).isEqualTo("ModelParameterValueInvalid")
        }
        assertThat(models.findById(id).get().reasoningEffort).isNull()
    }

    private fun declared(providerId: Long): List<String> =
        graphQlTester.document("""{ chatModelParameters(providerId: $providerId) { name } }""").execute()
            .path("chatModelParameters[*].name").entityList(String::class.java).get()

    private fun update(id: Long, settings: String) = graphQlTester.document(
        """mutation { updateModel(id: $id, input: {
             name: "${models.findById(id).get().name}", modelId: "${models.findById(id).get().modelId}",
             kind: CHAT, $settings
           }) { id reasoningEffort } }""",
    ).execute()

    private fun provider(name: String, type: String, endpoint: String = "https://$name.example.invalid/v1"): Long =
        graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "$name", type: $type, endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()

    private fun model(providerId: Long, name: String): Long = graphQlTester.document(
        """mutation { createModel(input: { providerId: $providerId, name: "$name", modelId: "$name", kind: CHAT }) { id } }""",
    ).execute().path("createModel.id").entity(Long::class.java).get()
}
