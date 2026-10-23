package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.AudioClip
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelChatClient
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.connector.model.ModelUsageRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.attachment.AttachmentDownloads
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.attachment.AttachmentTypes
import io.mszymanski.orknux.server.attachment.ChatAttachment
import io.mszymanski.orknux.server.attachment.ChatAttachmentRepository
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.tuning.Tunable
import io.mszymanski.orknux.server.tuning.Tunables
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
 * A sound attached to a chat message, and which models hear it.
 *
 * [ChatVideoTest]'s twin, with the one question video does not have: whether
 * the server decodes the file's format. A model whose provider carries audio
 * and whose Takes audio is on is sent the sound as an `input_audio` part of the
 * user turn, on that turn only. With the switch off, on a provider with no
 * audio part, over the limit, or as flac to OpenAI's own API - which documents
 * only wav and mp3 - the message is refused in a sentence before the provider
 * hears anything; flac goes as itself to the servers that read it.
 */
@SpringBootTest(properties = ["orknux.attachments.location=target/test-chat-audio"])
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ChatAudioTest(
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
    @Autowired val tunables: Tunables,
    @Autowired val client: ModelChatClient,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer

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

        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        listOf("/chat/completions", "/v1/chat/completions").forEach { path ->
            server.createContext(path) { exchange ->
                sent += exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
                val bytes = (
                    """{"choices":[{"message":{"role":"assistant","content":"I heard it."}}],""" +
                        """"usage":{"prompt_tokens":7,"completion_tokens":2}}"""
                    ).toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
        }
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
        tunables.set(Tunable.CHAT_AUDIO_MAX_MB, null, "alice")
    }

    @Test
    fun `a model that takes audio is sent an mp3 as an input_audio part, on its own turn only`() {
        val (chatId, _) = chat(type = "OPENAI", takesAudio = true)

        stream(chatId, "What is this tune?", listOf(sound("tune.mp3", "audio/mpeg", MP3)))

        val data = Base64.getEncoder().encodeToString(MP3)
        val heard = sent.map { it.replace(" ", "") }.filter { it.contains("input_audio") }
        assertThat(heard).isNotEmpty().allSatisfy { body ->
            assertThat(body).contains(""""input_audio":{"data":"$data","format":"mp3"}""")
            assertThat(body).contains(""""type":"input_audio"""")
            assertThat(body).contains(""""text":"Whatisthistune?""")
        }

        // Nothing of it in the thread a person reads back, and not sent again on the next turn.
        val conversation = requireNotNull(sessions.findByIdOrNull(chatId)).conversationId
        assertThat(history.findByConversationId(conversation).map { it.text.orEmpty() }).noneMatch { it.contains(data) }
        val before = sent.size
        stream(chatId, "And the key?")
        assertThat(sent.drop(before)).isNotEmpty().allSatisfy { body -> assertThat(body).doesNotContain("input_audio") }
    }

    @Test
    fun `a flac goes as itself to a server that decodes it`() {
        val (chatId, _) = chat(type = "OPENAI", takesAudio = true)

        stream(chatId, "Listen", listOf(sound("take.flac", "audio/flac", FLAC)))

        val data = Base64.getEncoder().encodeToString(FLAC)
        assertThat(sent.map { it.replace(" ", "") }.filter { it.contains("input_audio") })
            .isNotEmpty().allSatisfy { assertThat(it).contains(""""input_audio":{"data":"$data","format":"flac"}""") }
    }

    @Test
    fun `a flac to OpenAI's own API is refused in a sentence, before anything is sent`() {
        val (chatId, modelId) = chat(type = "OPENAI", takesAudio = true, endpoint = "https://api.openai.com/v1", name = "OpenAI")

        val refused = assertThrows<ChatAudioFormatNotTakenException> {
            stream(chatId, "Listen", listOf(sound("take.flac", "audio/flac", FLAC)))
        }
        assertThat(refused.message).isEqualTo(
            "OpenAI takes wav and mp3 audio, not flac. Send it as one of those, or choose a model on a server that reads flac.",
        )
        val problem = ChatStreamExceptionHandler().videoRefused(refused)
        assertThat(problem.properties).containsEntry("code", "ChatAudioFormatNotTaken")
        assertThat(attachments.findAll().single().chatSessionId).isNull()

        // The wire says the same to any other caller.
        val answer = client.complete(modelId, listOf(ChatTurn("user", "Listen", audios = listOf(AudioClip("AAAA", "flac")))))
        assertThat(answer).isInstanceOf(ChatCompletion.Failed::class.java)
        assertThat(sent).isEmpty()
    }

    @Test
    fun `a model with Takes audio off is refused before the provider is called`() {
        val (chatId, _) = chat(type = "OPENAI", takesAudio = false)

        val refused = assertThrows<ChatAudioNotTakenException> {
            stream(chatId, "Listen", listOf(sound("tune.mp3", "audio/mpeg", MP3)))
        }

        assertThat(refused.message).isEqualTo(
            "Listener does not take audio. Switch on Takes audio on its page, or choose a model that does.",
        )
        assertThat(sent).isEmpty()
        val problem = ChatStreamExceptionHandler().videoRefused(refused)
        assertThat(problem.properties).containsEntry("code", "ChatAudioNotTaken")
        assertThat(problem.properties!!["arguments"]).isEqualTo(mapOf("model" to "Listener"))
    }

    @Test
    fun `a sound over the limit is refused before the provider is called`() {
        graphQlTester.document("""mutation { setTunable(key: "chat.audio.max.mb", value: 1) { value } }""")
            .execute().path("setTunable.value").entity(Int::class.java).isEqualTo(1)
        val (chatId, _) = chat(type = "OPENAI", takesAudio = true)

        val refused = assertThrows<ChatAudioTooLargeException> {
            stream(chatId, "Listen", listOf(sound("long.mp3", "audio/mpeg", ByteArray(1024 * 1024 + 1))))
        }

        assertThat(refused.message).startsWith("long.mp3 is 2 MB, and a sound sent to a model may be at most 1 MB.")
        assertThat(sent).isEmpty()
    }

    @Test
    fun `a provider with no audio part is never sent one, and the switch cannot be put on there`() {
        val providerId = provider("OLLAMA", "Another Ollama")
        graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $providerId, name: "Local", modelId: "qwen-omni", kind: CHAT, takesAudio: true
               }) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().extensions["code"]).isEqualTo("ModelParameterNotTaken")
        }

        val (chatId, modelId) = chat(type = "OLLAMA", takesAudio = false)
        val refused = assertThrows<ChatAudioNotCarriedException> {
            stream(chatId, "Listen", listOf(sound("tune.mp3", "audio/mpeg", MP3)))
        }
        assertThat(refused.message).contains("whose API takes no audio").doesNotContain("Switch on")

        val answer = client.complete(modelId, listOf(ChatTurn("user", "Listen", audios = listOf(AudioClip("AAAA", "mp3")))))
        assertThat(answer).isInstanceOf(ChatCompletion.Failed::class.java)
        assertThat(sent).isEmpty()
    }

    @Test
    fun `a MIDI file is not a sound, and is never refused as one`() {
        val (chatId, _) = chat(type = "OPENAI", takesAudio = false)

        stream(chatId, "What is in this?", listOf(sound("song.mid", "audio/midi", byteArrayOf(0x4d, 0x54, 0x68, 0x64))))

        assertThat(sent).isNotEmpty().allSatisfy { assertThat(it).doesNotContain("input_audio") }
    }

    @Test
    fun `the switch is offered on OpenAI's own host and elsewhere, and saved and audited`() {
        val hosted = provider("OPENAI", "OpenAI", endpoint = "https://api.openai.com/v1")
        graphQlTester.document("{ chatModelParameters(providerId: $hosted) { name kind } }").execute()
            .path("chatModelParameters[?(@.name == 'takesAudio')].kind").entityList(String::class.java).containsExactly("SWITCH")

        val modelId = graphQlTester.document(
            """mutation { createModel(input: { providerId: $hosted, name: "Listener", modelId: "gpt-audio", kind: CHAT }) { id takesAudio } }""",
        ).execute().also { it.path("createModel.takesAudio").entity(Boolean::class.java).isEqualTo(false) }
            .path("createModel.id").entity(Long::class.java).get()
        graphQlTester.document(
            """mutation { updateModel(id: $modelId, input: { name: "Listener", modelId: "gpt-audio", takesAudio: true }) { takesAudio } }""",
        ).execute().path("updateModel.takesAudio").entity(Boolean::class.java).isEqualTo(true)
        graphQlTester.document(
            """mutation { updateModel(id: $modelId, input: { name: "Listener", modelId: "gpt-audio" }) { takesAudio } }""",
        ).execute().path("updateModel.takesAudio").entity(Boolean::class.java).isEqualTo(true)
        assertThat(audit.findAll().map { it.message }).contains("Model Listener takes audio")
    }

    @Test
    fun `an upload's sound is given one word, by its alias or its extension`() {
        assertThat(AttachmentTypes.of("song.mid", "application/octet-stream")).isEqualTo("audio/midi")
        assertThat(AttachmentTypes.of("song.MIDI", null)).isEqualTo("audio/midi")
        assertThat(AttachmentTypes.of("song.mid", "audio/mid")).isEqualTo("audio/midi")
        assertThat(AttachmentTypes.of("take.flac", "audio/x-flac")).isEqualTo("audio/flac")
        assertThat(AttachmentTypes.of("take.wav", "audio/x-wav")).isEqualTo("audio/wav")
        assertThat(AttachmentTypes.of("tune.mp3", "")).isEqualTo("audio/mpeg")
        // Anything else is kept as the browser said it.
        assertThat(AttachmentTypes.of("notes.txt", "text/plain")).isEqualTo("text/plain")
        assertThat(AttachmentTypes.of("blob.bin", null)).isEqualTo("application/octet-stream")

        // And the three sounds are served to be played where they are.
        listOf("audio/mpeg", "audio/flac", "audio/wav").forEach { assertThat(AttachmentDownloads.SHOWABLE).contains(it) }
        assertThat(AttachmentDownloads.SHOWABLE).doesNotContain("audio/midi")
    }

    /* ---------------------------------------------------------- the fixtures */

    private fun stream(chatId: Long, said: String, attachmentIds: List<Long> = emptyList()): String {
        val response = MockHttpServletResponse()
        streaming.stream(chatId, ChatStreamRequest(said, attachmentIds), response).writeTo(response.outputStream)
        return response.contentAsString
    }

    private fun provider(type: String, name: String = "$type provider", endpoint: String? = null): Long = graphQlTester.document(
        """mutation { createModelProvider(input: {
             workspaceId: $workspaceId, name: "$name", type: $type,
             endpoint: "${endpoint ?: "http://${server.address.hostString}:${server.address.port}"}", secret: "sk-test"
           }) { id } }""",
    ).execute().path("createModelProvider.id").entity(Long::class.java).get()

    /** A chat handed to an agent on a model called Listener; the chat's id and the model's. */
    private fun chat(type: String, takesAudio: Boolean, endpoint: String? = null, name: String = "$type provider"): Pair<Long, Long> {
        val providerId = provider(type, name, endpoint)
        val modelId = graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $providerId, name: "Listener", modelId: "qwen-omni", kind: CHAT, takesAudio: $takesAudio
               }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
        val agentId = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Hearer", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        graphQlTester.document("""mutation { updateAgent(id: $agentId, input: { name: "Hearer", modelId: $modelId }) { id } }""")
            .execute()
        val chatId = graphQlTester.document(
            """mutation { startChat(input: { workspaceId: $workspaceId, title: "Sounds" }) { id } }""",
        ).execute().path("startChat.id").entity(Long::class.java).get()
        graphQlTester.document("""mutation { chooseChatAgent(id: $chatId, agentId: $agentId) { id } }""")
            .execute().path("chooseChatAgent.id").hasValue()
        return chatId to modelId
    }

    /** A file in somebody's composer: uploaded by the chat's owner, claimed by no chat yet. */
    private fun sound(name: String, type: String, bytes: ByteArray): Long = requireNotNull(
        attachments.save(
            ChatAttachment(
                workspaceId = workspaceId,
                filename = name,
                contentType = type,
                sizeBytes = bytes.size.toLong(),
                location = store.put(workspaceId, name, bytes),
                uploadedBy = "alice",
            ),
        ).id,
    )

    private companion object {
        /** Not music - nothing here decodes it - but the bytes an mp3 and a flac begin with. */
        val MP3 = byteArrayOf(0x49, 0x44, 0x33, 0x04, 0, 0, 0, 0, 0, 0)
        val FLAC = byteArrayOf(0x66, 0x4c, 0x61, 0x43, 0, 0, 0, 0x22)
    }
}
