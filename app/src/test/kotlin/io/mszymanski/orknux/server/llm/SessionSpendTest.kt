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

    private fun model(inputPrice: Double?, outputPrice: Double?, cachedPrice: Double? = null, writePrice: Double? = null): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub ${System.nanoTime()}", endpoint: "http://stub.invalid", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        val prices = listOfNotNull(
            inputPrice?.let { "inputCostPerMillion: $it" },
            outputPrice?.let { "outputCostPerMillion: $it" },
            cachedPrice?.let { "cachedInputCostPerMillion: $it" },
            writePrice?.let { "cacheWriteCostPerMillion: $it" },
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

    /**
     * Input the provider served from its cache is charged at the cached price,
     * where the model has one. The input count holds the cached tokens, and
     * they were all charged at the full input price - many times what they
     * cost on a model that caches.
     */
    @Test
    fun `cached input is charged at the cached price, and at the input price where there is none`() {
        val cached = model(inputPrice = 2.0, outputPrice = 10.0, cachedPrice = 0.2)
        val call = ChatCompletion.CalledTools(
            calls = emptyList(),
            millis = 10,
            inputTokens = 1_000_000,
            outputTokens = 100_000,
            cachedInputTokens = 400_000,
            turn = ChatTurn(role = "assistant", content = ""),
        )
        spend.add(session, cached, call)
        // 600k at 2.00, 400k at 0.20 and 100k out at 10.00.
        spent().path("llmSession.spent.cost").entity(Double::class.java).isEqualTo(2.28)

        session = recorder.open(workspaceId, "test", "spend-${System.nanoTime()}")
        spend.add(session, model(inputPrice = 2.0, outputPrice = 10.0), call)
        // No cached price: the whole million at the input price, as before.
        spent().path("llmSession.spent.cost").entity(Double::class.java).isEqualTo(3.0)
    }

    /** Input written into the cache is charged at the write price, and at the input price where there is none. */
    @Test
    fun `cache writes are charged at the write price, and at the input price where there is none`() {
        val call = ChatCompletion.CalledTools(
            calls = emptyList(),
            millis = 10,
            inputTokens = 1_000_000,
            outputTokens = 0,
            cachedInputTokens = 400_000,
            cacheWriteTokens = 200_000,
            turn = ChatTurn(role = "assistant", content = ""),
        )
        spend.add(session, model(inputPrice = 2.0, outputPrice = 10.0, cachedPrice = 0.2, writePrice = 2.5), call)
        // 400k at 2.00, 400k cached at 0.20, 200k written at 2.50.
        spent().path("llmSession.spent.cost").entity(Double::class.java).isEqualTo(1.38)

        session = recorder.open(workspaceId, "test", "spend-${System.nanoTime()}")
        spend.add(session, model(inputPrice = 2.0, outputPrice = 10.0, cachedPrice = 0.2), call)
        // No write price: the 200k at the input price, as before.
        spent().path("llmSession.spent.cost").entity(Double::class.java).isEqualTo(1.28)
    }

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

    /**
     * The sessions list carries each row's spend, and sorts by tokens - in and
     * out together - and by cost, so the dearest conversation can be found.
     */
    @Test
    fun `the list shows each session's spend and sorts by tokens and by cost`() {
        val priced = model(inputPrice = 1.0, outputPrice = 1.0)
        val unpriced = model(inputPrice = null, outputPrice = null)
        val big = recorder.open(workspaceId, "test", "big-${System.nanoTime()}")
        val dear = recorder.open(workspaceId, "test", "dear-${System.nanoTime()}")
        // big: the most tokens and no price; dear: fewer tokens, the most money; session: a little of both.
        spend.add(big, unpriced, ChatCompletion.Answered("a", 1, inputTokens = 900_000, outputTokens = 300_000))
        spend.add(dear, priced, ChatCompletion.Answered("b", 1, inputTokens = 600_000, outputTokens = 400_000))
        spend.add(session, priced, ChatCompletion.Answered("c", 1, inputTokens = 1_000, outputTokens = 1_000))

        fun listed(order: String) = graphQlTester.document(
            """{ llmSessions(workspaceId: $workspaceId, order: $order, ascending: false) {
                 content { id spent { inputTokens outputTokens cost partlyUnpriced } } } }""",
        ).execute()

        listed("TOKENS").path("llmSessions.content[*].id").entityList(Long::class.java).containsExactly(big, dear, session)
        listed("COST").path("llmSessions.content[*].id").entityList(Long::class.java).containsExactly(dear, session, big)
        listed("COST")
            .path("llmSessions.content[0].spent.cost").entity(String::class.java).isEqualTo("1")
            .path("llmSessions.content[0].spent.inputTokens").entity(Double::class.java).isEqualTo(600_000.0)
            .path("llmSessions.content[2].spent.partlyUnpriced").entity(Boolean::class.java).isEqualTo(true)
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
