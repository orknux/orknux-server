package io.mszymanski.orknux.server.model

import io.mszymanski.orknux.connector.model.ChatApi
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
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
 * Which API an Azure OpenAI provider's chats go through is the provider's
 * setting: Responses by default, because chat completions refuse a reasoning
 * model its tools, and chat completions on request, so an installation can go
 * back to the old road. Only Azure OpenAI chooses; every other type is refused
 * a choice it does not have.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ProviderChatApiTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    @Test
    fun `an Azure provider starts on Responses, and is set back to chat completions from its page`() {
        val id = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Azure", type: AZURE_OPENAI,
                 endpoint: "https://acme.openai.azure.com", secret: "k"
               }) { id chatApi } }""",
        ).execute()
            .path("createModelProvider.chatApi").entity(String::class.java).isEqualTo("RESPONSES")
            .path("createModelProvider.id").entity(Long::class.java).get()
        assertThat(providers.findById(id).get().chatApi).isEqualTo(ChatApi.RESPONSES)

        update(id, "AZURE_OPENAI", "chatApi: CHAT_COMPLETIONS")
            .path("updateModelProvider.chatApi").entity(String::class.java).isEqualTo("CHAT_COMPLETIONS")
        assertThat(providers.findById(id).get().chatApi).isEqualTo(ChatApi.CHAT_COMPLETIONS)
        assertThat(audit.findAll().map { it.message }).contains("Provider Azure chat API set to Chat completions")

        // Left out, it stays where it was: a caller written before the setting cannot move it.
        update(id, "AZURE_OPENAI", "").path("updateModelProvider.chatApi").entity(String::class.java).isEqualTo("CHAT_COMPLETIONS")

        graphQlTester.document("""{ modelProvider(id: $id) { chatApi } }""").execute()
            .path("modelProvider.chatApi").entity(String::class.java).isEqualTo("CHAT_COMPLETIONS")
    }

    @Test
    fun `an Azure provider can be created on chat completions`() {
        graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Azure", type: AZURE_OPENAI,
                 endpoint: "https://acme.openai.azure.com", secret: "k", chatApi: CHAT_COMPLETIONS
               }) { chatApi } }""",
        ).execute().path("createModelProvider.chatApi").entity(String::class.java).isEqualTo("CHAT_COMPLETIONS")
    }

    @Test
    fun `any other type holds no chat API, and is refused one`() {
        graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Local", type: OPENAI,
                 endpoint: "http://llama.example.invalid/v1", secret: "k", chatApi: RESPONSES
               }) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().extensions["code"]).isEqualTo("ProviderChatApiNotTaken")
        }
        assertThat(providers.findAll()).isEmpty()

        val id = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Local", type: OPENAI,
                 endpoint: "http://llama.example.invalid/v1", secret: "k"
               }) { id chatApi } }""",
        ).execute()
            .path("createModelProvider.chatApi").valueIsNull()
            .path("createModelProvider.id").entity(Long::class.java).get()

        graphQlTester.document(
            """mutation { updateModelProvider(id: $id, input: {
                 name: "Local", endpoint: "http://llama.example.invalid/v1", type: OPENAI, chatApi: CHAT_COMPLETIONS
               }) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().extensions["code"]).isEqualTo("ProviderChatApiNotTaken")
        }
        assertThat(providers.findById(id).get().chatApi).isNull()
    }

    @Test
    fun `a provider moved off Azure lets go of its choice, and one moved onto Azure starts on Responses`() {
        val id = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Moving", type: AZURE_OPENAI,
                 endpoint: "https://acme.openai.azure.com", secret: "k", chatApi: CHAT_COMPLETIONS
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()

        update(id, "OPENAI", "").path("updateModelProvider.chatApi").valueIsNull()
        assertThat(providers.findById(id).get().chatApi).isNull()

        update(id, "AZURE_OPENAI", "").path("updateModelProvider.chatApi").entity(String::class.java).isEqualTo("RESPONSES")
    }

    @Test
    fun `a duplicate carries the provider's chat API`() {
        val id = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Azure", type: AZURE_OPENAI,
                 endpoint: "https://acme.openai.azure.com", secret: "k", chatApi: CHAT_COMPLETIONS
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()

        graphQlTester.document("""mutation { duplicateModelProvider(id: $id) { chatApi } }""").execute()
            .path("duplicateModelProvider.chatApi").entity(String::class.java).isEqualTo("CHAT_COMPLETIONS")
    }

    private fun update(id: Long, type: String, extra: String) = graphQlTester.document(
        """mutation { updateModelProvider(id: $id, input: {
             name: "${providers.findById(id).get().name}", endpoint: "https://acme.openai.azure.com", type: $type
             ${if (extra.isEmpty()) "" else ", $extra"}
           }) { chatApi } }""",
    ).execute()
}
