package io.mszymanski.orknux.server.mcp

import io.mszymanski.orknux.connector.model.LlmModel
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelKind
import io.mszymanski.orknux.connector.model.ModelProvider
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.AgentRepository
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
 * Ask AI is offered by default, on any chat model the workspace has.
 *
 * A workspace used to show no Ask AI button until somebody found Quick Chat
 * Model in its settings and chose one, and most never did. Now no choice means
 * Any: the first chat model switched on, in the order the models page lists
 * them. None is still a choice, and a model of the workspace's own still wins.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class QuickChatModelAnyTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val agents: AgentRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val quickChatModels: QuickChatModels,
) {

    private var workspaceId: Long = 0
    private var providerId: Long = 0

    @BeforeEach
    fun reset() {
        agents.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        providerId = requireNotNull(
            providers.save(ModelProvider(workspaceId = workspaceId, name = "Nowhere", endpoint = "http://nowhere.invalid")).id,
        )
    }

    private fun model(name: String, kind: ModelKind = ModelKind.CHAT, enabled: Boolean = true): Long =
        requireNotNull(models.save(LlmModel(providerId = providerId, name = name, modelId = name, kind = kind, enabled = enabled)).id)

    private fun offered(): Boolean = graphQlTester
        .document("""query { workspace(id: $workspaceId) { quickChatAvailable } }""")
        .execute().errors().verify()
        .path("workspace.quickChatAvailable").entity(Boolean::class.java).get()

    private fun workspace() = workspaces.findById(workspaceId).orElseThrow()

    @Test
    fun `a new workspace offers Ask AI on its first chat model switched on`() {
        // Nothing to answer with yet: no button rather than one that fails on every press.
        assertThat(offered()).isFalse()

        model("Whisper", kind = ModelKind.TRANSCRIPTION)
        model("Asleep", enabled = false)
        val chosen = model("Qwen")
        model("Zephyr")

        assertThat(offered()).isTrue()
        assertThat(quickChatModels.of(workspace())).isEqualTo(chosen)
    }

    @Test
    fun `None is still a choice, and Any can be chosen back`() {
        model("Qwen")
        graphQlTester.document("""mutation { setWorkspaceQuickChatModel(workspaceId: $workspaceId, modelId: null) { quickChatModelAny quickChatAvailable } }""")
            .execute().errors().verify()
            .path("setWorkspaceQuickChatModel.quickChatModelAny").entity(Boolean::class.java).isEqualTo(false)
            .path("setWorkspaceQuickChatModel.quickChatAvailable").entity(Boolean::class.java).isEqualTo(false)

        graphQlTester.document("""mutation { setWorkspaceQuickChatModel(workspaceId: $workspaceId, modelId: null, useAny: true) { quickChatModelAny quickChatAvailable } }""")
            .execute().errors().verify()
            .path("setWorkspaceQuickChatModel.quickChatModelAny").entity(Boolean::class.java).isEqualTo(true)
            .path("setWorkspaceQuickChatModel.quickChatAvailable").entity(Boolean::class.java).isEqualTo(true)
    }

    @Test
    fun `a model of the workspace's own wins over Any`() {
        model("Alpha")
        val own = model("Omega")
        graphQlTester.document("""mutation { setWorkspaceQuickChatModel(workspaceId: $workspaceId, modelId: $own) { quickChatModelId quickChatModelAny } }""")
            .execute().errors().verify()
            .path("setWorkspaceQuickChatModel.quickChatModelAny").entity(Boolean::class.java).isEqualTo(false)
        assertThat(quickChatModels.of(workspace())).isEqualTo(own)
    }
}
