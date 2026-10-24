package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.memory.ChatMemoryRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A chat's own reasoning effort, chosen from the composer. Issue #654.
 *
 * What is worth holding: the chat offers exactly the words its model's page
 * would, the word chosen is what the next turn sends - on every path a chat
 * turn takes to the provider - and the model's own setting is never touched,
 * because the model is shared by everything else that names it.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ChatReasoningEffortTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val conversation: AgentConversation,
    @Autowired val sessions: ChatSessionRepository,
    @Autowired val history: ChatMemoryRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0
    private var server: HttpServer? = null
    private val received = CopyOnWriteArrayList<String>()
    private val mapper = ObjectMapper()

    @BeforeEach
    fun reset() {
        sessions.findAll().forEach { history.deleteByConversationId(it.conversationId) }
        sessions.deleteAll()
        agents.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        received.clear()
    }

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

    @Test
    fun `a chat offers its model's efforts, sends the one chosen, and leaves the model alone`() {
        val modelId = azureModel(serveResponses())
        val chatId = chat(agent("Reviewer", modelId))

        graphQlTester.document("""{ chatSession(id: $chatId) { reasoningEffort reasoningEfforts } }""")
            .execute().errors().verify()
            .path("chatSession.reasoningEffort").valueIsNull()
            .path("chatSession.reasoningEfforts").entityList(String::class.java)
            .containsExactly("minimal", "low", "medium", "high")

        graphQlTester.document("""mutation { setChatReasoningEffort(id: $chatId, effort: "low") { reasoningEffort } }""")
            .execute().errors().verify()
            .path("setChatReasoningEffort.reasoningEffort").entity(String::class.java).isEqualTo("low")

        val turn = send(chatId)
        assertThat(effortSent(turn)).isEqualTo("low")
        // The model's own setting is what it was: unset.
        assertThat(requireNotNull(models.findByIdOrNull(modelId)).reasoningEffort).isNull()

        graphQlTester.document("""mutation { setChatReasoningEffort(id: $chatId, effort: null) { reasoningEffort } }""")
            .execute().errors().verify()
            .path("setChatReasoningEffort.reasoningEffort").valueIsNull()

        val after = send(chatId)
        // Back to the model's setting, which asks for no reasoning at all.
        assertThat(mapper.readTree(after).has("reasoning")).isFalse()
    }

    @Test
    fun `the chat's effort takes the place of the model's own`() {
        val modelId = azureModel(serveResponses(), effort = "high")
        val chatId = chat(agent("Reviewer", modelId))

        val turn = send(chatId)
        assertThat(effortSent(turn)).isEqualTo("high")

        graphQlTester.document("""mutation { setChatReasoningEffort(id: $chatId, effort: "minimal") { id } }""")
            .execute().errors().verify()
        val after = send(chatId)
        assertThat(effortSent(after)).isEqualTo("minimal")
        assertThat(requireNotNull(models.findByIdOrNull(modelId)).reasoningEffort).isEqualTo("high")
    }

    /**
     * A chat on a bare model asks in one call rather than through the agent's
     * loop, and that call carries the effort too.
     */
    @Test
    fun `a chat on a bare model sends its effort`() {
        val modelId = azureModel(serveResponses())
        val chatId = requireNotNull(
            sessions.save(
                ChatSession(
                    workspaceId = workspaceId,
                    conversationId = "9a1c2e4f-1b2d-4c3e-8f70-6a5b4c3d2e1f",
                    title = "Bare",
                    userId = "alice",
                    modelId = modelId,
                    reasoningEffort = "medium",
                ),
            ).id,
        )

        val turn = send(chatId)
        assertThat(effortSent(turn)).isEqualTo("medium")
    }

    /**
     * The streaming door - a chat open in the browser - goes through
     * `ModelChatClient.stream`. A provider answering a streamed request with
     * one plain body is asked again without streaming, so the stub here sees
     * both requests, and both must carry the chat's word.
     */
    @Test
    fun `a streamed round carries the effort as well`() {
        val modelId = azureModel(serveResponses())
        val agentId = agent("Reviewer", modelId)

        val answer = conversation.answer(
            modelId,
            agentId,
            listOf(ChatTurn("user", "Quickly, please.")),
            watch = object : RoundWatch {},
            reasoningEffort = "low",
        )

        assertThat(answer).isInstanceOf(ChatCompletion.Answered::class.java)
        assertThat(received).isNotEmpty
        assertThat(received.map(::effortSent)).containsOnly("low")
    }

    @Test
    fun `a model that takes no effort offers none and refuses one`() {
        val modelId = openAiShapedModel(serveCompletions())
        val chatId = chat(agent("Reviewer", modelId))

        graphQlTester.document("""{ chatSession(id: $chatId) { reasoningEfforts } }""")
            .execute().errors().verify()
            .path("chatSession.reasoningEfforts").entityList(String::class.java).hasSize(0)

        graphQlTester.document("""mutation { setChatReasoningEffort(id: $chatId, effort: "low") { id } }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors.first().extensions?.get("code")).isEqualTo("ModelParameterNotTaken")
            }
        assertThat(requireNotNull(sessions.findByIdOrNull(chatId)).reasoningEffort).isNull()
    }

    @Test
    fun `a word the model does not take is refused`() {
        val chatId = chat(agent("Reviewer", azureModel(serveResponses())))

        graphQlTester.document("""mutation { setChatReasoningEffort(id: $chatId, effort: "extreme") { id } }""")
            .execute().errors().satisfy { errors ->
                assertThat(errors.first().extensions?.get("code")).isEqualTo("ModelParameterValueInvalid")
            }
    }

    @Test
    fun `somebody else's chat cannot be changed`() {
        val chatId = requireNotNull(
            sessions.save(
                ChatSession(
                    workspaceId = workspaceId,
                    conversationId = "5d4c3b2a-1f0e-4d9c-8b7a-6f5e4d3c2b1a",
                    title = "Bob's",
                    userId = "bob",
                    modelId = azureModel(serveResponses()),
                ),
            ).id,
        )

        graphQlTester.document("""mutation { setChatReasoningEffort(id: $chatId, effort: "low") { id } }""")
            .execute().errors().satisfy { errors -> assertThat(errors).isNotEmpty }
        assertThat(requireNotNull(sessions.findByIdOrNull(chatId)).reasoningEffort).isNull()
    }

    /**
     * Sends, and hands back the request the turn itself made. The first one:
     * a chat's first exchange is followed by the call that names it, which is
     * a call of its own on the model's settings and not a turn of the chat.
     */
    private fun send(chatId: Long): String {
        received.clear()
        graphQlTester.document("""mutation { sendChatMessage(id: $chatId, text: "Go on.") { answer { content } } }""")
            .execute().errors().verify()
            .path("sendChatMessage.answer.content").entity(String::class.java).isEqualTo("Done.")
        return received.first()
    }

    private fun effortSent(body: String): String? =
        mapper.readTree(body).path("reasoning").path("effort").asString(null)

    private fun chat(agentId: Long): Long = graphQlTester.document(
        """mutation { startChat(input: { workspaceId: $workspaceId, agentId: $agentId }) { id } }""",
    ).execute().errors().verify().path("startChat.id").entity(Long::class.java).get()

    private fun agent(name: String, modelId: Long): Long {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "$name", type: LLM }) { id } }""",
        ).execute().errors().verify().path("createAgent.id").entity(Long::class.java).get()
        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "$name", modelId: $modelId }) { id } }""",
        ).execute().errors().verify()
        return id
    }

    /** A reasoning deployment behind Azure OpenAI, reached through its Responses API. */
    private fun azureModel(endpoint: String, effort: String? = null): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Azure", type: AZURE_OPENAI, endpoint: "$endpoint", secret: "azure-test"
               }) { id } }""",
        ).execute().errors().verify().path("createModelProvider.id").entity(Long::class.java).get()

        val given = effort?.let { """, reasoningEffort: "$it"""" } ?: ""
        return graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $providerId, name: "Sol", modelId: "gpt-6-sol", kind: CHAT$given
               }) { id } }""",
        ).execute().errors().verify().path("createModel.id").entity(Long::class.java).get()
    }

    /** A llama.cpp-style server behind the OpenAI shape, which takes no effort. */
    private fun openAiShapedModel(endpoint: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub", endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().errors().verify().path("createModelProvider.id").entity(Long::class.java).get()

        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT }) { id } }""",
        ).execute().errors().verify().path("createModel.id").entity(Long::class.java).get()
    }

    private fun serveResponses(): String = serve("/openai/v1/responses") {
        """{"id":"resp_1","object":"response","created_at":1,"model":"gpt-6-sol","status":"completed",
           "output":[{"type":"message","id":"msg_1","role":"assistant","status":"completed",
             "content":[{"type":"output_text","text":"Done.","annotations":[]}]}],
           "usage":{"input_tokens":3,"output_tokens":1,"total_tokens":4}}"""
    }

    private fun serveCompletions(): String = serve("/chat/completions") {
        """{"choices":[{"message":{"role":"assistant","content":"Done."}}]}"""
    }

    private fun serve(path: String, answer: () -> String): String {
        val started = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        started.createContext(path) { exchange ->
            received += exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val bytes = answer().toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        started.start()
        server = started
        return "http://${started.address.hostString}:${started.address.port}"
    }
}
