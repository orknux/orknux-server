package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.connector.model.ModelUsageRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.memory.ChatMemoryRepository
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Sentence completion in the chat composer. Issue #649.
 *
 * Driven through the query the composer sends, against a stub provider, so what
 * is proved is the whole path: the workspace's completion model is the one
 * asked, it is shown the chat's latest turns and the text typed so far, it is
 * held to the installation's token limit, only the new words come back, and
 * nothing of it is written into the chat.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ChatSentenceCompletionTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val sessions: ChatSessionRepository,
    @Autowired val history: ChatMemoryRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val usage: ModelUsageRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private var server: HttpServer? = null

    /** Every body the stub was sent. */
    private val sent = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun reset() {
        sessions.findAll().forEach { history.deleteByConversationId(it.conversationId) }
        sessions.deleteAll()
        agents.deleteAll()
        usage.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        sent.clear()
    }

    @AfterEach
    fun stop() {
        server?.stop(0)
        // Back to where a fresh installation starts, for the classes that follow.
        graphQlTester.document("mutation { setChatCompletionTimeoutMillis(millis: 4000) { chatCompletionTimeoutMillis } }")
            .execute().errors().verify()
    }

    @Test
    fun `the completion model suggests the rest of the sentence, shown the chat so far, and nothing is written`() {
        val modelId = model(serve(said = " tomorrow morning"))
        choose(modelId)
        val chatId = chat(modelId)
        history.saveAll(
            conversation(chatId),
            listOf(UserMessage("When does the deploy run?"), AssistantMessage("Every weekday at nine.")),
        )

        graphQlTester.document("""{ chatCompletion(chatId: $chatId, text: "Can we move it to") }""")
            .execute().errors().verify()
            .path("chatCompletion").entity(String::class.java).isEqualTo(" tomorrow morning")

        val body = sent.single()
        // The chat so far and the words typed, both in the request.
        assertThat(body).contains("Every weekday at nine.").contains("Can we move it to")
        // Held to the installation's limit rather than the model's own.
        assertThat(body).contains("\"max_tokens\":32")

        // Never written into the chat: the history is the two turns it had.
        assertThat(history.findByConversationId(conversation(chatId))).hasSize(2)
        // And counted, as every model call is, so it is on the usage reports.
        assertThat(usage.findAll().sumOf { it.requests }).isEqualTo(1)
    }

    @Test
    fun `what was typed is not handed back when the model repeats it`() {
        val modelId = model(serve(said = "Can we move it to Friday"))
        choose(modelId)
        val chatId = chat(modelId)

        graphQlTester.document("""{ chatCompletion(chatId: $chatId, text: "Can we move it to ") }""")
            .execute().errors().verify()
            .path("chatCompletion").entity(String::class.java).isEqualTo("Friday")
    }

    @Test
    fun `with no completion model nothing is asked and nothing is suggested`() {
        val modelId = model(serve(said = "never"))
        val chatId = chat(modelId)

        graphQlTester.document("""{ chatCompletion(chatId: $chatId, text: "Hello") }""")
            .execute().errors().verify()
            .path("chatCompletion").valueIsNull()
        assertThat(sent).isEmpty()
    }

    @Test
    fun `a model slower than the limit is given up on and suggests nothing`() {
        graphQlTester.document("mutation { setChatCompletionTimeoutMillis(millis: 300) { chatCompletionTimeoutMillis } }")
            .execute().errors().verify()
            .path("setChatCompletionTimeoutMillis.chatCompletionTimeoutMillis").entity(Int::class.java).isEqualTo(300)
        val modelId = model(serve(said = "too late", delayMillis = 2000))
        choose(modelId)
        val chatId = chat(modelId)

        val started = System.nanoTime()
        graphQlTester.document("""{ chatCompletion(chatId: $chatId, text: "Hello") }""")
            .execute().errors().verify()
            .path("chatCompletion").valueIsNull()
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(1800)
    }

    @Test
    fun `somebody else's chat is refused as one that is not there`() {
        val modelId = model(serve(said = "secret"))
        choose(modelId)
        val chatId = chat(modelId, owner = "bob")

        graphQlTester.document("""{ chatCompletion(chatId: $chatId, text: "Hello") }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors).hasSize(1)
                assertThat(errors.first().extensions["code"]).isEqualTo("ChatSessionNotFound")
            }
        assertThat(sent).isEmpty()
    }

    @Test
    fun `the completion model is a workspace choice, and only a chat model will do`() {
        val modelId = model(serve(said = "x"))
        graphQlTester.document(
            """mutation { setWorkspaceCompletionModel(workspaceId: $workspaceId, modelId: $modelId) { completionModelId completionPauseMillis } }""",
        ).execute().errors().verify()
            .path("setWorkspaceCompletionModel.completionModelId").entity(Long::class.java).isEqualTo(modelId)
            .path("setWorkspaceCompletionModel.completionPauseMillis").entity(Int::class.java).isEqualTo(600)

        graphQlTester.document("""mutation { setWorkspaceCompletionModel(workspaceId: $workspaceId, modelId: null) { completionModelId } }""")
            .execute().errors().verify()
            .path("setWorkspaceCompletionModel.completionModelId").valueIsNull()
    }

    @Test
    fun `the four numbers are Admin settings, refused outside their range`() {
        graphQlTester.document(
            """{ installationSettings { chatCompletionContextTurns chatCompletionMaxTokens chatCompletionTimeoutMillis
                 chatCompletionPauseMillis chatCompletionPauseMillisConfigured } }""",
        ).execute().errors().verify()
            .path("installationSettings.chatCompletionContextTurns").entity(Int::class.java).isEqualTo(6)
            .path("installationSettings.chatCompletionMaxTokens").entity(Int::class.java).isEqualTo(32)
            .path("installationSettings.chatCompletionPauseMillisConfigured").entity(Int::class.java).isEqualTo(600)

        graphQlTester.document("mutation { setChatCompletionMaxTokens(tokens: 0) { chatCompletionMaxTokens } }")
            .execute().errors().satisfy { errors ->
                assertThat(errors.first().extensions["code"]).isEqualTo("CompletionMaxTokensOutOfRange")
            }
        graphQlTester.document("mutation { setChatCompletionPauseMillis(millis: 50) { chatCompletionPauseMillis } }")
            .execute().errors().satisfy { errors ->
                assertThat(errors.first().extensions["code"]).isEqualTo("CompletionPauseOutOfRange")
            }
    }

    private fun choose(modelId: Long) {
        graphQlTester.document(
            """mutation { setWorkspaceCompletionModel(workspaceId: $workspaceId, modelId: $modelId) { completionModelId } }""",
        ).execute().errors().verify()
    }

    private fun conversation(chatId: Long): String = sessions.findById(chatId).get().conversationId

    private fun chat(modelId: Long, owner: String = "alice"): Long = requireNotNull(
        sessions.save(
            ChatSession(
                workspaceId = workspaceId,
                conversationId = java.util.UUID.randomUUID().toString(),
                title = "Deploys",
                userId = owner,
                modelId = modelId,
                agentId = null,
            ),
        ).id,
    )

    private fun model(endpoint: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub", endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().errors().verify().path("createModelProvider.id").entity(Long::class.java).get()

        return graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT
               }) { id } }""",
        ).execute().errors().verify().path("createModel.id").entity(Long::class.java).get()
    }

    /** A provider that answers in the words it is given, the way OpenAI's does, after [delayMillis]. */
    private fun serve(said: String, delayMillis: Long = 0): String {
        val stub = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        stub.executor = java.util.concurrent.Executors.newCachedThreadPool()
        stub.createContext("/chat/completions") { exchange ->
            sent.add(exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() })
            if (delayMillis > 0) runCatching { Thread.sleep(delayMillis) }
            val bytes = (
                """{"choices":[{"message":{"role":"assistant","content":"$said"}}],""" +
                    """"usage":{"prompt_tokens":40,"completion_tokens":3}}"""
                ).toByteArray(StandardCharsets.UTF_8)
            runCatching {
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            exchange.close()
        }
        stub.start()
        server = stub
        return "http://${stub.address.hostString}:${stub.address.port}"
    }
}
