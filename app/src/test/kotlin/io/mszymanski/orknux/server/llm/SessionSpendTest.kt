package io.mszymanski.orknux.server.llm

import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser

/**
 * What a session has cost, call by call - a round that asked for tools as well
 * as one that answered - priced where the model is, and read back as the
 * session page asks for it. And the workspace's switch for showing it.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class SessionSpendTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val spend: SessionSpend,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private var session: Long = 0

    @BeforeEach
    fun make() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "spend-${System.nanoTime()}")).id)
        session = recorder.open(workspaceId, "test", "spend-${System.nanoTime()}")
    }

    private fun model(inputPrice: Double?, outputPrice: Double?): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub ${System.nanoTime()}", endpoint: "http://stub.invalid", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        val prices = listOfNotNull(
            inputPrice?.let { "inputCostPerMillion: $it" },
            outputPrice?.let { "outputCostPerMillion: $it" },
        ).joinToString("") { ", $it" }
        return graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $providerId, name: "Stub ${System.nanoTime()}", modelId: "stub", kind: CHAT$prices
               }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }

    private fun spent() = graphQlTester.document(
        """query { llmSession(id: $session) { spent { inputTokens outputTokens cachedInputTokens cost partlyUnpriced } } }""",
    ).execute()

    @Test
    fun `every call is added, a round that asked for tools as well, and priced where the model is`() {
        val priced = model(inputPrice = 2.0, outputPrice = 10.0)

        spend.add(
            session,
            priced,
            ChatCompletion.CalledTools(
                calls = emptyList(),
                millis = 10,
                inputTokens = 1_000_000,
                outputTokens = 100_000,
                cachedInputTokens = 400_000,
                turn = ChatTurn(role = "assistant", content = ""),
            ),
        )
        spend.add(session, priced, ChatCompletion.Answered("done", 10, inputTokens = 500_000, outputTokens = 50_000))

        spent()
            .path("llmSession.spent.inputTokens").entity(Double::class.java).isEqualTo(1_500_000.0)
            .path("llmSession.spent.outputTokens").entity(Double::class.java).isEqualTo(150_000.0)
            .path("llmSession.spent.cachedInputTokens").entity(Double::class.java).isEqualTo(400_000.0)
            // 1.5M in at $2 and 150k out at $10.
            .path("llmSession.spent.cost").entity(String::class.java).isEqualTo("4.5")
            .path("llmSession.spent.partlyUnpriced").entity(Boolean::class.java).isEqualTo(false)
    }

    @Test
    fun `a model with no price counts its tokens and marks the cost as partial`() {
        val unpriced = model(inputPrice = null, outputPrice = null)

        spend.add(session, unpriced, ChatCompletion.Answered("done", 10, inputTokens = 1_200, outputTokens = 300))

        spent()
            .path("llmSession.spent.inputTokens").entity(Double::class.java).isEqualTo(1_200.0)
            .path("llmSession.spent.cost").valueIsNull()
            .path("llmSession.spent.partlyUnpriced").entity(Boolean::class.java).isEqualTo(true)
    }

    @Test
    fun `a workspace shows the meter until it turns it off`() {
        graphQlTester.document("""query { workspace(id: $workspaceId) { sessionCostShown } }""").execute()
            .path("workspace.sessionCostShown").entity(Boolean::class.java).isEqualTo(true)
        graphQlTester.document(
            """mutation { setWorkspaceSessionCostShown(workspaceId: $workspaceId, shown: false) { sessionCostShown } }""",
        ).execute()
            .path("setWorkspaceSessionCostShown.sessionCostShown").entity(Boolean::class.java).isEqualTo(false)
    }
}
