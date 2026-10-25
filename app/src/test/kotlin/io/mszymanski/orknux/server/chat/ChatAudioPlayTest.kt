package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.connector.model.ModelUsageRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.attachment.AttachmentAPI
import io.mszymanski.orknux.server.attachment.AttachmentNotPlayableException
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.attachment.ChatAttachment
import io.mszymanski.orknux.server.attachment.ChatAttachmentRepository
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.llm.LlmSessionStore
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.script.StoredKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.memory.ChatMemoryRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.test.context.support.WithMockUser
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.sound.midi.MidiEvent
import javax.sound.midi.MidiSystem
import javax.sound.midi.Sequence
import javax.sound.midi.ShortMessage

/**
 * Sound in a chat: a MIDI file attached to one plays through a rendering the
 * server makes once, an agent attaches what its tools made to its answer, and
 * in a voice conversation - and only there - it can play a sound through the
 * person's speakers, which the open chat hears as a `play` frame.
 *
 * The model is a stub on loopback, as in ChatDrawingTest, and the streaming door
 * is called directly: what is at issue is which tools a round was lent and what
 * came of calling one.
 */
@SpringBootTest(properties = ["orknux.attachments.location=target/test-chat-audio"])
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ChatAudioPlayTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val streaming: ChatStreamAPI,
    @Autowired val serving: AttachmentAPI,
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
    @Autowired val scratch: LlmSessionStore,
) {

    private var workspaceId: Long = 0
    private var server: HttpServer? = null

    /** Every body sent to the model, so what was offered can be read off it. */
    private val offered = CopyOnWriteArrayList<String>()

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
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "music")).id)
        offered.clear()
    }

    @AfterEach
    fun stop() {
        server?.stop(0)
    }

    @Test
    fun `a MIDI attachment is served as MP3 at its audio address, rendered once`() {
        val chatId = chatWithAgent(serve { saying("Hi.") })
        val midi = attach(chatId, "tune.mid", "audio/midi", tune())

        val first = serving.audio(requireNotNull(midi.id))
        assertThat(first.headers.contentType.toString()).isEqualTo("audio/mpeg")
        assertThat(first.headers.getFirst("Content-Disposition")).startsWith("inline").contains("tune.mp3")
        val bytes = requireNotNull(first.body).inputStream.use { it.readAllBytes() }
        assertThat(isMp3(bytes)).describedAs("starts like an MP3").isTrue()
        val kept = requireNotNull(attachments.findByIdOrNull(requireNotNull(midi.id))?.audioLocation)

        // Asked again, the rendering already made is what is served.
        serving.audio(requireNotNull(midi.id)).body?.inputStream?.close()
        assertThat(attachments.findByIdOrNull(requireNotNull(midi.id))?.audioLocation).isEqualTo(kept)

        val text = attach(chatId, "notes.txt", "text/plain", "words".toByteArray())
        assertThatThrownBy { serving.audio(requireNotNull(text.id)) }
            .isInstanceOf(AttachmentNotPlayableException::class.java)
            .hasMessageContaining("not a MIDI file")
    }

    @Test
    fun `an mp3 is served inline, and so is an ogg`() {
        val chatId = chatWithAgent(serve { saying("Hi.") })
        for ((name, type) in listOf("song.mp3" to "audio/mpeg", "song.ogg" to "audio/ogg", "song.m4a" to "audio/mp4")) {
            val file = attach(chatId, name, type, "bytes".toByteArray())
            val served = serving.download(requireNotNull(file.id))
            assertThat(served.headers.contentType.toString()).describedAs(name).isEqualTo(type)
            assertThat(served.headers.getFirst("Content-Disposition")).describedAs(name).startsWith("inline")
            served.body?.inputStream?.close()
        }
        assertThat(io.mszymanski.orknux.server.attachment.AttachmentTypes.of("take.m4a", "audio/x-m4a")).isEqualTo("audio/mp4")
        assertThat(io.mszymanski.orknux.server.attachment.AttachmentTypes.of("take.ogg", "application/ogg")).isEqualTo("audio/ogg")
    }

    @Test
    fun `audio_play is lent to a voice turn and not to a typed one`() {
        val chatId = chatWithAgent(serve { saying("Hello.") })

        stream(chatId, "typed", voice = false)
        assertThat(offered.last()).contains(ChatFileTools.ATTACH).doesNotContain(ChatFileTools.PLAY)

        stream(chatId, "spoken", voice = true)
        assertThat(offered.last()).contains(ChatFileTools.ATTACH).contains(ChatFileTools.PLAY)
    }

    @Test
    fun `in a voice turn an attached MIDI file is played, rendered, through a play frame`() {
        lateinit var midiId: String
        val chatId = chatWithAgent(
            serve { body ->
                if (body.contains("It is playing to the person")) saying("That was your tune.")
                else Round(tool = ChatFileTools.PLAY, arguments = """{"${ChatFileTools.ATTACHMENT}":"$midiId"}""")
            },
        )
        val midi = attach(chatId, "tune.mid", "audio/midi", tune())
        midiId = midi.id.toString()

        val frames = stream(chatId, "play me my tune", voice = true)

        assertThat(frames).contains("event:play")
        assertThat(frames).contains("\"url\":\"/api/attachments/$midiId/audio\"")
        assertThat(frames).contains("\"contentType\":\"audio/mpeg\"")
        assertThat(attachments.findByIdOrNull(midi.id!!)?.audioLocation).isNotNull()
        assertThat(frames).contains("Thatwasyourtune.")
    }

    @Test
    fun `audio_play asked for in a typed turn is refused rather than run`() {
        val chatId = chatWithAgent(
            serve { body ->
                if (body.contains("this turn is not one")) saying("I cannot play it here.")
                else Round(tool = ChatFileTools.PLAY, arguments = """{"${ChatFileTools.KEY}":"song.mp3"}""")
            },
        )
        val frames = stream(chatId, "play it", voice = false)
        assertThat(frames).doesNotContain("event:play")
        assertThat(frames).contains("Icannotplayithere.")
    }

    @Test
    fun `chat_attach_file files a key under the answer, and a MIDI key played in a voice turn is rendered and filed`() {
        var round = 0
        val chatId = chatWithAgent(
            serve { body ->
                when (round) {
                    0 -> saying("Hi.")
                    1 -> if (body.contains("The file is under your answer")) saying("Here it is.")
                    else Round(tool = ChatFileTools.ATTACH, arguments = """{"${ChatFileTools.KEY}":"song.mid","${ChatFileTools.NAME}":"song.mid"}""")
                    else -> if (body.contains("It is playing to the person")) saying("Listen.")
                    else Round(tool = ChatFileTools.PLAY, arguments = """{"${ChatFileTools.KEY}":"song.mid"}""")
                }
            },
        )
        stream(chatId, "hello")
        val session = requireNotNull(sessions.findByIdOrNull(chatId)?.llmSessionId)
        scratch.put(session, "song.mid", "\"" + Base64.getEncoder().encodeToString(tune()) + "\"", StoredKind("audio/midi", true))

        round = 1
        val attached = stream(chatId, "send me the file")
        val filed = attachments.findAll().single { it.filename == "song.mid" }
        assertThat(filed.contentType).isEqualTo("audio/midi")
        assertThat(attached).contains("event:filed").contains("\"id\":\"${filed.id}\"")
        assertThat(filesUnder(chatId).single { it.second.contains("song.mid") }.first).isEqualTo("Here it is.")

        round = 2
        val played = stream(chatId, "play it", voice = true)
        val rendered = attachments.findAll().single { it.filename == "song.mp3" }
        assertThat(rendered.contentType).isEqualTo("audio/mpeg")
        assertThat(isMp3(store.open(rendered.location).use { it.readAllBytes() })).isTrue()
        assertThat(played).contains("event:play").contains("\"url\":\"/api/attachments/${rendered.id}\"")
    }

    /* ---------------------------------------------------------- the fixtures */

    private data class Round(val said: String = "", val tool: String? = null, val arguments: String = "{}")

    private fun saying(said: String) = Round(said = said)

    private fun serve(answer: (String) -> Round): String {
        val stub = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        stub.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            offered += body
            val round = answer(body)
            val streamed = body.replace(" ", "").contains("\"stream\":true")
            val bytes = (if (streamed) frames(round) else blocking(round)).toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", if (streamed) "text/event-stream" else "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        stub.start()
        server = stub
        return "http://${stub.address.hostString}:${stub.address.port}"
    }

    private fun frames(round: Round): String = buildString {
        if (round.tool != null) {
            append(
                """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function",""" +
                    """"function":{"name":"${round.tool}","arguments":"${escaped(round.arguments)}"}}]}}]}""",
            ).append(BLANK)
        }
        if (round.said.isNotEmpty()) append("""data: {"choices":[{"delta":{"content":"${round.said}"}}]}""").append(BLANK)
        append("""data: {"choices":[{"delta":{}}],"usage":{"prompt_tokens":7,"completion_tokens":2}}""").append(BLANK)
        append("data: [DONE]").append(BLANK)
    }

    private fun blocking(round: Round): String {
        val calls = round.tool?.let {
            ""","tool_calls":[{"id":"call_1","type":"function","function":{"name":"$it","arguments":"${escaped(round.arguments)}"}}]"""
        }.orEmpty()
        return """{"choices":[{"message":{"role":"assistant","content":"${round.said}"$calls}}],"usage":{"prompt_tokens":7,"completion_tokens":2}}"""
    }

    private fun escaped(arguments: String) = arguments.replace("\"", "\\\"")

    private fun stream(chatId: Long, said: String, voice: Boolean = false): String {
        val response = MockHttpServletResponse()
        streaming.stream(chatId, ChatStreamRequest(said, emptyList(), voice = voice), response).writeTo(response.outputStream)
        return response.contentAsString.replace(" ", "")
    }

    private fun filesUnder(chatId: Long): List<Pair<String, List<String>>> {
        val shown = graphQlTester.document("{ chatMessages(id: $chatId) { content attachments { filename } } }").execute()
        val contents = shown.path("chatMessages[*].content").entityList(String::class.java).get()
        return contents.indices.map { at ->
            contents[at] to shown.path("chatMessages[$at].attachments[*].filename").entityList(String::class.java).get()
        }
    }

    private fun attach(chatId: Long, name: String, type: String, bytes: ByteArray): ChatAttachment = attachments.save(
        ChatAttachment(
            workspaceId = workspaceId, chatSessionId = chatId, filename = name, contentType = type,
            sizeBytes = bytes.size.toLong(), location = store.put(workspaceId, name, bytes), uploadedBy = "alice",
        ),
    )

    /** Middle C then E, a beat each at 120. */
    private fun tune(): ByteArray {
        val sequence = Sequence(Sequence.PPQ, 480)
        val track = sequence.createTrack()
        listOf(60 to 0L, 64 to 480L).forEach { (pitch, at) ->
            track.add(MidiEvent(ShortMessage(ShortMessage.NOTE_ON, 0, pitch, 100), at))
            track.add(MidiEvent(ShortMessage(ShortMessage.NOTE_OFF, 0, pitch, 0), at + 480))
        }
        return ByteArrayOutputStream().also { MidiSystem.write(sequence, 0, it) }.toByteArray()
    }

    /** An ID3 tag or an MPEG frame sync, which is how an MP3 begins. */
    private fun isMp3(bytes: ByteArray): Boolean =
        bytes.size > 4 && (String(bytes, 0, 3, Charsets.US_ASCII) == "ID3" || ((bytes[0].toInt() and 0xff) == 0xff && (bytes[1].toInt() and 0xe0) == 0xe0))

    private fun chatWithAgent(endpoint: String): Long {
        val modelId = model("Talker", "stub-chat", endpoint)
        val agentId = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Composer", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        graphQlTester.document("""mutation { updateAgent(id: $agentId, input: { name: "Composer", modelId: $modelId }) { id } }""").execute()
        val chatId = graphQlTester.document(
            """mutation { startChat(input: { workspaceId: $workspaceId, title: "Music" }) { id } }""",
        ).execute().path("startChat.id").entity(Long::class.java).get()
        graphQlTester.document("""mutation { chooseChatAgent(id: $chatId, agentId: $agentId) { id } }""")
            .execute().path("chooseChatAgent.id").hasValue()
        return chatId
    }

    private fun model(name: String, modelId: String, endpoint: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "$name provider", type: OPENAI, endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "$name", modelId: "$modelId", kind: CHAT }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }

    private companion object {
        const val BLANK = "\n\n"
    }
}
