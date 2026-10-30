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
    @Autowired val sentences: ChatSentences,
    @Autowired val completionModels: CompletionModels,
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
        graphQlTester.document("mutation { setChatReplySuggestions(count: 3) { chatReplySuggestions } }")
            .execute().errors().verify()
    }

    @Test
    fun `a model that begins with a line break still has its words suggested`() {
        val modelId = model(serve(said = "\\n\\nto Friday morning"))
        choose(modelId)
        val chatId = chat(modelId)

        graphQlTester.document("""{ chatCompletion(chatId: $chatId, text: "Can we move it") }""")
            .execute().errors().verify()
            .path("chatCompletion").entity(String::class.java).isEqualTo(" to Friday morning")
    }

    @Test
    fun `a model that repeats only the last words typed has them taken off`() {
        val modelId = model(serve(said = "the deploy to Friday morning"))
        choose(modelId)
        val chatId = chat(modelId)

        graphQlTester.document("""{ chatCompletion(chatId: $chatId, text: "Can we move the deploy") }""")
            .execute().errors().verify()
            .path("chatCompletion").entity(String::class.java).isEqualTo(" to Friday morning")
    }

    @Test
    fun `a repeat the token limit cut short is no suggestion rather than the text again`() {
        val modelId = model(serve(said = "Can we move the deploy"))
        choose(modelId)
        val chatId = chat(modelId)

        graphQlTester.document("""{ chatCompletion(chatId: $chatId, text: "Can we move the deploy to Friday and tell") }""")
            .execute().errors().verify()
            .path("chatCompletion").valueIsNull()
    }

    /**
     * Why completion "stopped for a while". The composer aborts its fetch when
     * the text changes, and that never reached the model: the abandoned call ran
     * on to its end, and on a model that serves one request at a time the
     * request the person was waiting for queued behind it and ran out of time.
     * A newer request for the same chat now hangs the older one up.
     */
    @Test
    fun `a newer completion for the chat hangs up the one it replaces`() {
        val modelId = model(serve(said = " later", delayMillis = 3000))
        choose(modelId)
        val chatId = chat(modelId)
        val session = sessions.findById(chatId).get()

        val first = java.util.concurrent.CompletableFuture.supplyAsync {
            val started = System.nanoTime()
            sentences.continuation(session, "Can we") to (System.nanoTime() - started) / 1_000_000
        }
        // The first is with the model before the second is asked.
        val deadline = System.currentTimeMillis() + 2000
        while (sent.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertThat(sent).hasSize(1)
        val second = sentences.continuation(session, "Can we move it")

        val (words, millis) = first.get()
        assertThat(words).isNull()
        assertThat(millis).isLessThan(2500)
        assertThat(second).isEqualTo(" later")
    }

    @Test
    fun `replies are suggested under the latest answer as plain lines, and kept for that answer`() {
        val modelId = model(serve(said = "1. Can we run it on Friday?\\n- \\\"Show me the logs\\\"\\n\\n* What about staging?\\nAnd a fourth"))
        choose(modelId)
        val chatId = chat(modelId)
        history.saveAll(
            conversation(chatId),
            listOf(UserMessage("When does the deploy run?"), AssistantMessage("Every weekday at nine.")),
        )

        val asked = """{ chatReplySuggestions(chatId: $chatId) }"""
        graphQlTester.document(asked).execute().errors().verify()
            .path("chatReplySuggestions").entityList(String::class.java)
            .containsExactly("Can we run it on Friday?", "Show me the logs", "What about staging?")
        val body = sent.single()
        // Shown the answer being replied to, held to the installation's limit, and told not to think.
        assertThat(body).contains("Every weekday at nine.").contains("\"max_tokens\":150").contains("\"reasoning_effort\":\"none\"")

        // Asked again for the same answer - a reload - and the model is not asked again.
        graphQlTester.document(asked).execute().errors().verify()
            .path("chatReplySuggestions").entityList(String::class.java)
            .containsExactly("Can we run it on Friday?", "Show me the logs", "What about staging?")
        assertThat(sent).hasSize(1)
        // Nothing written into the chat.
        assertThat(history.findByConversationId(conversation(chatId))).hasSize(2)

        // A new answer is asked about afresh.
        history.saveAll(
            conversation(chatId),
            listOf(
                UserMessage("When does the deploy run?"),
                AssistantMessage("Every weekday at nine."),
                UserMessage("And on Fridays?"),
                AssistantMessage("At noon."),
            ),
        )
        graphQlTester.document(asked).execute().errors().verify()
            .path("chatReplySuggestions").entityList(String::class.java).hasSize(3)
        assertThat(sent).hasSize(2)
    }

    @Test
    fun `no replies are suggested while the chat waits for an answer, or with none asked for`() {
        val modelId = model(serve(said = "Sure"))
        choose(modelId)
        val chatId = chat(modelId)
        history.saveAll(conversation(chatId), listOf(UserMessage("When does the deploy run?")))

        graphQlTester.document("""{ chatReplySuggestions(chatId: $chatId) }""").execute().errors().verify()
            .path("chatReplySuggestions").entityList(String::class.java).hasSize(0)

        history.saveAll(
            conversation(chatId),
            listOf(UserMessage("When does the deploy run?"), AssistantMessage("Every weekday at nine.")),
        )
        graphQlTester.document("mutation { setChatReplySuggestions(count: 0) { chatReplySuggestions } }")
            .execute().errors().verify()
        graphQlTester.document("""{ chatReplySuggestions(chatId: $chatId) }""").execute().errors().verify()
            .path("chatReplySuggestions").entityList(String::class.java).hasSize(0)
        assertThat(sent).isEmpty()
    }

    @Test
    fun `with no completion model no replies are suggested, and somebody else's chat is refused`() {
        val modelId = model(serve(said = "Sure"))
        val chatId = chat(modelId)
        history.saveAll(conversation(chatId), listOf(UserMessage("Hi"), AssistantMessage("Hello.")))
        graphQlTester.document("""{ chatReplySuggestions(chatId: $chatId) }""").execute().errors().verify()
            .path("chatReplySuggestions").entityList(String::class.java).hasSize(0)

        choose(modelId)
        val theirs = chat(modelId, owner = "bob")
        graphQlTester.document("""{ chatReplySuggestions(chatId: $theirs) }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors.first().extensions["code"]).isEqualTo("ChatSessionNotFound")
            }
        assertThat(sent).isEmpty()
    }

    @Test
    fun `suggested replies' numbers are Admin settings, refused outside their range`() {
        graphQlTester.document(
            """{ installationSettings { chatReplySuggestions chatReplySuggestionsConfigured chatReplySuggestionsMaxTokens
                 chatReplySuggestionsTimeoutMillis chatReplySuggestionsTimeoutMillisConfigured } }""",
        ).execute().errors().verify()
            .path("installationSettings.chatReplySuggestionsConfigured").entity(Int::class.java).isEqualTo(3)
            .path("installationSettings.chatReplySuggestionsMaxTokens").entity(Int::class.java).isEqualTo(150)
            .path("installationSettings.chatReplySuggestionsTimeoutMillisConfigured").entity(Int::class.java).isEqualTo(8000)

        graphQlTester.document("mutation { setChatReplySuggestions(count: 7) { chatReplySuggestions } }")
            .execute().errors().satisfy { errors ->
                assertThat(errors.first().extensions["code"]).isEqualTo("ReplySuggestionsOutOfRange")
            }
        graphQlTester.document("mutation { setChatReplySuggestionsMaxTokens(tokens: 0) { chatReplySuggestionsMaxTokens } }")
            .execute().errors().satisfy { errors ->
                assertThat(errors.first().extensions["code"]).isEqualTo("ReplySuggestionsMaxTokensOutOfRange")
            }
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
        assertThat(body).contains("\"max_tokens\":64")
        // Told not to think, though the model has no effort set: a thinking model otherwise spends
        // all its tokens thinking and answers nothing - what Gemma 4 on llama.cpp did.
        assertThat(body).contains("\"reasoning_effort\":\"none\"")

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
            .path("setWorkspaceCompletionModel.completionPauseMillis").entity(Int::class.java).isEqualTo(300)

        graphQlTester.document("""mutation { setWorkspaceCompletionModel(workspaceId: $workspaceId, modelId: null) { completionModelId } }""")
            .execute().errors().verify()
            .path("setWorkspaceCompletionModel.completionModelId").valueIsNull()
    }

    /** The administrator's model reaches a workspace on Default and no other. */
    @Test
    fun `a workspace on Default uses the installation's model, and None or its own is left alone`() {
        val elsewhere = requireNotNull(workspaces.save(Workspace(name = "owner-${System.nanoTime()}")).id)
        val shared = model(serve(said = " from the default"), inWorkspace = elsewhere)
        fun choose(arguments: String) = graphQlTester.document(
            """mutation { setWorkspaceCompletionModel(workspaceId: $workspaceId, $arguments) { completionModelId completionModelDefault completionAvailable } }""",
        ).execute().errors().verify()
        fun available() = graphQlTester.document("""query { workspace(id: $workspaceId) { completionAvailable } }""")
            .execute().path("workspace.completionAvailable").entity(Boolean::class.java)

        graphQlTester.document("mutation { setChatCompletionModel(modelId: $shared) { chatCompletionModelId } }")
            .execute().errors().verify()
            .path("setChatCompletionModel.chatCompletionModelId").entity(Long::class.java).isEqualTo(shared)
        try {
            choose("modelId: null")
            available().isEqualTo(false)

            choose("modelId: null, useDefault: true")
                .path("setWorkspaceCompletionModel.completionModelDefault").entity(Boolean::class.java).isEqualTo(true)
            available().isEqualTo(true)
            assertThat(completionModels.of(workspaces.findById(workspaceId).orElseThrow())).isEqualTo(shared)

            val own = model(serve(said = "x"))
            choose("modelId: $own")
                .path("setWorkspaceCompletionModel.completionModelDefault").entity(Boolean::class.java).isEqualTo(false)
            assertThat(completionModels.of(workspaces.findById(workspaceId).orElseThrow())).isEqualTo(own)

            choose("modelId: null, useDefault: true")
            graphQlTester.document("mutation { setChatCompletionModel(modelId: null) { chatCompletionModelId } }")
                .execute().errors().verify()
            available().isEqualTo(false)
        } finally {
            graphQlTester.document("mutation { setChatCompletionModel(modelId: null) { chatCompletionModelId } }").execute()
        }
    }

    @Test
    fun `the four numbers are Admin settings, refused outside their range`() {
        graphQlTester.document(
            """{ installationSettings { chatCompletionContextTurns chatCompletionMaxTokens chatCompletionTimeoutMillis
                 chatCompletionPauseMillis chatCompletionPauseMillisConfigured } }""",
        ).execute().errors().verify()
            .path("installationSettings.chatCompletionContextTurns").entity(Int::class.java).isEqualTo(6)
            .path("installationSettings.chatCompletionMaxTokens").entity(Int::class.java).isEqualTo(64)
            .path("installationSettings.chatCompletionPauseMillisConfigured").entity(Int::class.java).isEqualTo(300)

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

    private fun model(endpoint: String, inWorkspace: Long = workspaceId): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $inWorkspace, name: "Stub", endpoint: "$endpoint", secret: "sk-test"
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
