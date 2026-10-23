package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.connector.model.ModelUsageRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.attachment.ChatAttachment
import io.mszymanski.orknux.server.attachment.ChatAttachmentRepository
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.ai.chat.memory.ChatMemoryRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A video attached to a chat message, and which models it reaches.
 *
 * Four promises. A model whose provider carries video and whose Takes video is
 * on is sent the video itself, as a `video_url` part of the user turn, and only
 * on that turn. A model with the switch off is refused in a sentence before the
 * provider hears anything. So is a video over Admin Settings' limit. And a
 * provider with no video part at all - Ollama here, as Azure and Anthropic -
 * never receives one: the switch cannot be put on there, and a message carrying
 * a video is refused with a sentence that does not tell anybody to look for it.
 *
 * One stub records every request on every path, so "the provider was not
 * called" is an assertion about an empty list rather than an absence of
 * evidence. Its own attachment directory under `target`, because these write
 * real bytes to a real disk.
 */
@SpringBootTest(properties = ["orknux.attachments.location=target/test-chat-videos"])
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ChatVideoTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val streaming: ChatStreamAPI,
    @Autowired val sessions: ChatSessionRepository,
    @Autowired val history: ChatMemoryRepository,
    @Autowired val attachments: ChatAttachmentRepository,
    @Autowired val store: AttachmentStore,
    @Autowired val agents: AgentRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val usage: ModelUsageRepository,
    @Autowired val llmSessions: LlmSessionRepository,
    @Autowired val llmEvents: LlmSessionEventRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val settings: InstallationSettings,
    @Autowired val client: ModelChatClient,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer
    private var limitBefore = 0

    /** Every body the stub was sent, on any path. */
    private val sent = CopyOnWriteArrayList<String>()

    @BeforeEach
    fun reset() {
        sessions.findAll().forEach { history.deleteByConversationId(it.conversationId) }
        attachments.deleteAll()
        sessions.deleteAll()
        llmEvents.deleteAll()
        llmSessions.deleteAll()
        agents.deleteAll()
        usage.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        sent.clear()
        limitBefore = settings.chatVideoMaxMb()

        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        /*
         * The chat completions under both roots a provider type hangs them off -
         * the endpoint itself, and Ollama's `/v1` - and nothing else: a stub that
         * answered every path would answer the probe a new provider is
         * recognised by, and be taken for some other kind of server.
         */
        listOf("/chat/completions", "/v1/chat/completions").forEach { path -> server.createContext(path) { exchange ->
            sent += exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val bytes = (
                """{"choices":[{"message":{"role":"assistant","content":"I watched it."}}],""" +
                    """"usage":{"prompt_tokens":7,"completion_tokens":2}}"""
                ).toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        } }
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
        settings.setChatVideoMaxMb(limitBefore, "alice")
    }

    @Test
    fun `a model that takes video is sent the video as a video_url part, on its own turn only`() {
        val (chatId, _) = chat(type = "OPENAI", takesVideo = true)
        val video = video("clip.mp4", CLIP)

        stream(chatId, "What happens in this?", listOf(video))

        // The answer, and whatever else the first exchange asks the model - naming the chat - beside it.
        val url = "data:video/mp4;base64,${Base64.getEncoder().encodeToString(CLIP)}"
        val watched = sent.map { it.replace(" ", "") }.filter { it.contains("video_url") }
        assertThat(watched).isNotEmpty().allSatisfy { body ->
            assertThat(body).contains("""{"type":"video_url","video_url":{"url":"$url"}}""")
            assertThat(body).contains("""{"type":"text","text":"Whathappensinthis?""")
        }

        // The thread a person reads back holds no base64, and the next turn does not send the video again.
        val conversation = requireNotNull(sessions.findByIdOrNull(chatId)).conversationId
        assertThat(history.findByConversationId(conversation).map { it.text.orEmpty() }).noneMatch { it.contains("base64") }
        val before = sent.size
        stream(chatId, "And after that?")
        assertThat(sent.drop(before)).isNotEmpty().allSatisfy { body ->
            assertThat(body).doesNotContain("video_url").doesNotContain("data:video/")
        }
    }

    @Test
    fun `a model with Takes video off is refused before the provider is called`() {
        val (chatId, _) = chat(type = "OPENAI", takesVideo = false)

        val refused = assertThrows<ChatVideoNotTakenException> { stream(chatId, "Look", listOf(video("clip.mp4", CLIP))) }

        assertThat(refused.message).isEqualTo(
            "Watcher does not take video. Switch on Takes video on its page, or choose a model that does.",
        )
        assertThat(sent).isEmpty()
        // Not claimed: the file is still in the composer, to be sent somewhere that takes it.
        assertThat(attachments.findAll().single().chatSessionId).isNull()

        // And the stream door says it the way GraphQL does, so the browser can translate it.
        val problem = ChatStreamExceptionHandler().videoRefused(refused)
        assertThat(problem.properties).containsEntry("code", "ChatVideoNotTaken")
        assertThat(problem.properties!!["arguments"]).isEqualTo(mapOf("model" to "Watcher"))
    }

    @Test
    fun `a video over the limit is refused before the provider is called`() {
        graphQlTester.document("mutation { setChatVideoMaxMb(megabytes: 1) { chatVideoMaxMb } }")
            .execute().path("setChatVideoMaxMb.chatVideoMaxMb").entity(Int::class.java).isEqualTo(1)
        val (chatId, _) = chat(type = "OPENAI", takesVideo = true)
        val big = ByteArray(1024 * 1024 + 1)

        val refused = assertThrows<ChatVideoTooLargeException> { stream(chatId, "Look", listOf(video("long.mp4", big))) }

        assertThat(refused.message).startsWith("long.mp4 is 2 MB, and a video sent to a model may be at most 1 MB.")
        assertThat(sent).isEmpty()
    }

    @Test
    fun `a provider with no video part is never sent one, and the switch cannot be put on there`() {
        val providerId = provider("OLLAMA", "Another Ollama")
        graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $providerId, name: "Local", modelId: "llava", kind: CHAT, takesVideo: true
               }) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().extensions["code"]).isEqualTo("ModelParameterNotTaken")
        }

        val (chatId, modelId) = chat(type = "OLLAMA", takesVideo = false)
        val refused = assertThrows<ChatVideoNotCarriedException> { stream(chatId, "Look", listOf(video("clip.mp4", CLIP))) }
        assertThat(refused.message).contains("whose API takes no video")
        assertThat(refused.message).doesNotContain("Switch on")

        // And the wire refuses it too, for any caller that is not the chat.
        val answer = client.complete(
            modelId,
            listOf(ChatTurn("user", "Look", videos = listOf("data:video/mp4;base64,AAAA"))),
        )
        assertThat(answer).isInstanceOf(ChatCompletion.Failed::class.java)
        assertThat(sent).isEmpty()
    }

    @Test
    fun `the switch is offered where the provider carries video, and saved`() {
        val providerId = provider("OPENAI")
        graphQlTester.document("{ chatModelParameters(providerId: $providerId) { name kind } }").execute()
            .path("chatModelParameters[?(@.name == 'takesVideo')].kind").entityList(String::class.java).containsExactly("SWITCH")

        val modelId = graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Watcher", modelId: "qwen", kind: CHAT }) { id takesVideo } }""",
        ).execute().also { it.path("createModel.takesVideo").entity(Boolean::class.java).isEqualTo(false) }
            .path("createModel.id").entity(Long::class.java).get()
        graphQlTester.document(
            """mutation { updateModel(id: $modelId, input: { name: "Watcher", modelId: "qwen", takesVideo: true }) { takesVideo } }""",
        ).execute().path("updateModel.takesVideo").entity(Boolean::class.java).isEqualTo(true)
        // A form written before the switch existed leaves it as it is.
        graphQlTester.document(
            """mutation { updateModel(id: $modelId, input: { name: "Watcher", modelId: "qwen" }) { takesVideo } }""",
        ).execute().path("updateModel.takesVideo").entity(Boolean::class.java).isEqualTo(true)
    }

    /* ---------------------------------------------------------- the fixtures */

    private fun stream(chatId: Long, said: String, attachmentIds: List<Long> = emptyList()): String {
        val response = MockHttpServletResponse()
        streaming.stream(chatId, ChatStreamRequest(said, attachmentIds), response).writeTo(response.outputStream)
        return response.contentAsString
    }

    private fun provider(type: String, name: String = "$type provider"): Long = graphQlTester.document(
        """mutation { createModelProvider(input: {
             workspaceId: $workspaceId, name: "$name", type: $type,
             endpoint: "http://${server.address.hostString}:${server.address.port}", secret: "sk-test"
           }) { id } }""",
    ).execute().path("createModelProvider.id").entity(Long::class.java).get()

    /** A chat handed to an agent on a model called Watcher; the chat's id and the model's. */
    private fun chat(type: String, takesVideo: Boolean): Pair<Long, Long> {
        val providerId = provider(type)
        val modelId = graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $providerId, name: "Watcher", modelId: "qwen", kind: CHAT, takesVideo: $takesVideo
               }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
        val agentId = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Viewer", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        graphQlTester.document("""mutation { updateAgent(id: $agentId, input: { name: "Viewer", modelId: $modelId }) { id } }""")
            .execute()
        val chatId = graphQlTester.document(
            """mutation { startChat(input: { workspaceId: $workspaceId, title: "Clips" }) { id } }""",
        ).execute().path("startChat.id").entity(Long::class.java).get()
        graphQlTester.document("""mutation { chooseChatAgent(id: $chatId, agentId: $agentId) { id } }""")
            .execute().path("chooseChatAgent.id").hasValue()
        return chatId to modelId
    }

    /** A video in somebody's composer: uploaded by the chat's owner, claimed by no chat yet. */
    private fun video(name: String, bytes: ByteArray): Long = requireNotNull(
        attachments.save(
            ChatAttachment(
                workspaceId = workspaceId,
                filename = name,
                contentType = "video/mp4",
                sizeBytes = bytes.size.toLong(),
                location = store.put(workspaceId, name, bytes),
                uploadedBy = "alice",
            ),
        ).id,
    )

    private companion object {
        /** Not a playable film - nothing here decodes one - but the bytes an mp4 begins with. */
        val CLIP = byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, 0x6d, 0x70, 0x34, 0x32)
    }
}
