package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.connector.model.ModelUsageRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.attachment.AttachmentAPI
import io.mszymanski.orknux.server.attachment.AttachmentStore
import io.mszymanski.orknux.server.attachment.ChatAttachment
import io.mszymanski.orknux.server.attachment.ChatAttachmentRepository
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRepository
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
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/**
 * An agent in a chat that draws: the tool it is offered, the picture it makes,
 * and where that picture ends up.
 *
 * Issue #294. A chat had two doors onto a drawing model and neither was a model
 * deciding to use one - a button beside the composer, which is a person pressing
 * it and retyping their description, and `task_draw_picture`, which only exists
 * inside a task. So "draw me the architecture we just discussed" came back as a
 * paragraph about a diagram. The button goes in the same change, which is why
 * one of the tests below is about a picture drawn before it went.
 *
 * One stub serves both APIs, because from the model's point of view they are one
 * provider - the chat completions the agent thinks with and the
 * `/images/generations` it draws at - and having them on one server is what lets
 * "the request was made" and "the request was not made" both be assertions
 * rather than absences of evidence.
 *
 * **Both paths a chat round can take are exercised.** A blocking round is what
 * `sendChatMessage` produces and a streamed one is what the SSE door produces,
 * and the difference is not cosmetic: they are two different reads of the
 * provider's answer, gathering a tool call out of one JSON object in the first
 * case and out of deltas spelling it a few characters at a time in the second. A
 * tool that worked on one and not the other would look entirely correct to
 * whichever half of the suite had been written.
 *
 * Its own attachment directory under `target`, because these tests write real
 * bytes to a real disk: that is the point of them, and a development
 * installation's attachment folder is not the place to do it.
 */
@SpringBootTest(properties = ["orknux.attachments.location=target/test-chat-drawings"])
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ChatDrawingTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val streaming: ChatStreamAPI,
    @Autowired val serving: AttachmentAPI,
    @Autowired val chats: ChatService,
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
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer

    /** Which paths the stub was asked for, so "nothing was drawn" is an assertion. */
    private val asked = CopyOnWriteArrayList<String>()

    /** Every body sent to the chat endpoint, so what was *offered* can be read off it. */
    private val offered = CopyOnWriteArrayList<String>()

    /** Every body sent to the image endpoint, which is the only place the size asked for can be seen. */
    private val drawn = CopyOnWriteArrayList<String>()

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
        asked.clear()
        offered.clear()
        drawn.clear()
    }

    @AfterEach
    fun stop() = server.stop(0)

    /**
     * The whole of it, on the blocking path: called, drawn, filed, and in the
     * chat afterwards.
     *
     * Five promises and each one is separate. The drawing went to
     * `/images/generations` rather than being described in a chat completion,
     * which is the entirety of what makes this a second API and not a prompt.
     * The row names *this* chat and this workspace, which is what decides who
     * may open the file and what makes the picture part of this conversation
     * rather than a loose upload. The bytes are on the disk where the row says
     * they are. The thread holds a markdown image pointing at the attachment,
     * which is what the screen renders it out of and what makes it survive a
     * reload. And the agent's own answer is still there after it: a picture is
     * added to what the agent said, never instead of it.
     */
    @Test
    fun `an agent draws, the picture is filed on the chat, and the thread holds it`() {
        val chatId = drawingChat()

        graphQlTester
            .document("""mutation { sendChatMessage(id: $chatId, text: "Draw the architecture") { millis } }""")
            .execute().path("sendChatMessage.millis").hasValue()

        assertThat(asked).contains("/images/generations")

        val filed = attachments.findAll().single()
        assertThat(filed.chatSessionId).isEqualTo(chatId)
        assertThat(filed.workspaceId).isEqualTo(workspaceId)
        assertThat(filed.contentType).isEqualTo("image/png")
        // Named from the description, so a file saved to a desktop says what it is.
        assertThat(filed.filename).isEqualTo("a-red-square.png")
        assertThat(store.open(filed.location).use { it.readBytes() })
            .isEqualTo(Base64.getDecoder().decode(PIXEL))

        /*
         * Three lines in the thread, in this order. The question somebody asked,
         * the picture as an assistant turn the moment it was drawn, and then the
         * answer.
         *
         * The description is deliberately not one of them. The button wrote it
         * as a user turn because somebody had typed it; here it is the model's
         * own words, and a user turn holding them would be words put in
         * somebody's mouth and handed back to the model on the next send.
         *
         * Read off the history rather than off `chatMessages`, which is not the
         * thread: it interleaves what the agent looked up on the way, out of the
         * session, so the page can show the working. The thread is what the
         * conversation is, and it is what a reload reads the picture back out of.
         */
        assertThat(thread(chatId)).containsExactly(
            "user:Draw the architecture",
            "assistant:![A red square](/api/attachments/${filed.id})",
            "assistant:There it is.",
        )

        // And it reaches the screen, which reads the thread with the agent's
        // working folded in around it.
        assertThat(shown(chatId)).contains("![A red square](/api/attachments/${filed.id})")

        // Counted as a picture rather than as nought tokens, which is what the
        // corner of the composer says and the one thing an image model's own
        // counts could never say.
        assertThat(requireNotNull(sessions.findByIdOrNull(chatId)).spentPictures).isEqualTo(1)
    }

    /**
     * And the same thing on the streamed path.
     *
     * Not the same test twice. [AgentConversation] reads a watched round as a
     * stream and an unwatched one as one blocking call, so the tool call that
     * reaches the shed is gathered two different ways - out of a whole JSON
     * object above, and here out of deltas that spell the name and the arguments
     * a few characters at a time. The chat's two doors pick one path each, and
     * what an agent may do must not depend on which of them the browser used.
     */
    @Test
    fun `an agent draws on the streamed path too`() {
        val chatId = drawingChat()

        val frames = stream(chatId, "Draw the architecture")

        assertThat(asked).contains("/images/generations")
        val filed = attachments.findAll().single()
        assertThat(filed.chatSessionId).isEqualTo(chatId)
        // The round really was streamed, which is the whole point of this one.
        assertThat(frames).contains("event:call").contains("chat_draw_picture")

        /*
         * And the picture is announced, not merely written down.
         *
         * The thread holds it either way - the assertion below has always
         * passed - but an open chat learns what happened only from these
         * frames, and nothing used to send this one. So a round drew a picture,
         * told the model not to repeat the link, and left the browser showing
         * an answer that talked about a picture nobody could see until they
         * reloaded the page.
         */
        assertThat(frames)
            .describedAs("the drawn picture is announced to the open chat, not only written to the thread")
            .contains("event:drew")
            .contains("/api/attachments/${filed.id}")

        assertThat(thread(chatId)).containsExactly(
            "user:Draw the architecture",
            "assistant:![A red square](/api/attachments/${filed.id})",
            "assistant:There it is.",
        )
    }

    /**
     * The tool is offered to a chat that can use it, and to no other.
     *
     * `AgentTools` states the rule for every tool in this application - a model
     * is only ever offered tools that will run - and this is the half of it a
     * workspace decides by choosing a model to draw with, or not. A tool offered
     * where there is nothing behind it costs a turn to discover, and the model
     * is told it can do something it cannot; it will believe you.
     *
     * One chat across both, because what is offered is decided every send:
     * choosing a model half way through a conversation arms it, and a test that
     * used two chats would not have said so. It is read off the body the chat
     * endpoint was actually sent, because what the model was told is the only
     * thing at issue.
     */
    @Test
    fun `the drawing tool is offered only where the workspace has something to draw with`() {
        val chatId = chatWithAgent(serve { saying("Nothing to draw with.") })

        graphQlTester.document("""mutation { sendChatMessage(id: $chatId, text: "Draw it") { millis } }""").execute()
        assertThat(offered).isNotEmpty
        assertThat(offered.last()).doesNotContain("chat_draw_picture")

        drawWith()

        graphQlTester.document("""mutation { sendChatMessage(id: $chatId, text: "Draw it now") { millis } }""")
            .execute()
        assertThat(offered.last()).contains("chat_draw_picture")
    }

    /**
     * A provider that will not draw is a sentence the agent reads, not an ending.
     *
     * That distinction is the whole reason the shed answers with an error rather
     * than halting the round the way `task_done` does: a description a provider
     * refused is something the agent can rewrite or talk about, and a chat killed
     * because one picture could not be drawn would throw away the answer somebody
     * was waiting for. So the reason has to reach the *model*, which is what the
     * second assertion reads off the next request's body, and never the browser
     * as an exception.
     */
    @Test
    fun `a refused drawing reaches the model as a message and the chat still answers`() {
        val chatId = drawingChat(draws = false) { body ->
            if (body.contains("would not draw")) saying("It would not draw that.") else drawingCall()
        }

        graphQlTester.document("""mutation { sendChatMessage(id: $chatId, text: "Draw it") { answer { content } } }""")
            .execute().path("sendChatMessage.answer.content").entity(String::class.java)
            .isEqualTo("It would not draw that.")

        assertThat(attachments.count()).isZero()
        // The provider's own words, in front of the model, as a tool result and
        // not as a stack trace.
        assertThat(offered.last()).contains("would not draw").contains("error")

        // Nothing half-written into the thread: the question and the answer, and
        // no picture line between them.
        assertThat(thread(chatId)).containsExactly("user:Draw it", "assistant:It would not draw that.")
    }

    /**
     * A model that asks for the tool where it was not offered is told why, not
     * told the tool does not exist.
     *
     * The shed answers to the name whether or not it offered it, which is
     * `TaskTools`' rule and the same reason: a model offered the tool on an
     * earlier turn - before somebody unset the workspace's image model, say - and
     * calling it now must be told what changed, where a name the shed disowned
     * would fall through to the agent's own tools and come back as "there is no
     * tool called chat_draw_picture", which is not what happened.
     *
     * The agent is granted something else so that there is a round at all. An
     * agent offered nothing answers in one call with no loop to run a tool in,
     * which is `AgentConversation`'s own short circuit and not this feature's
     * business.
     */
    @Test
    fun `a model asking to draw where it was not offered is told why`() {
        val chatId = chatWithAgent(
            serve { body ->
                if (body.contains("no image model")) saying("I cannot draw here.") else drawingCall()
            },
            orknux = true,
        )

        graphQlTester.document("""mutation { sendChatMessage(id: $chatId, text: "Draw it") { answer { content } } }""")
            .execute().path("sendChatMessage.answer.content").entity(String::class.java)
            .isEqualTo("I cannot draw here.")

        assertThat(asked).doesNotContain("/images/generations")
        assertThat(attachments.count()).isZero()
        // The sentence, in front of the model, rather than a tool it was told
        // does not exist.
        assertThat(offered.last()).contains("no image model")
    }

    /**
     * A picture drawn before the button went is still a picture.
     *
     * The button is removed in this same change, and every chat that used it
     * holds exactly what this holds: a [ChatAttachment] row, and a markdown image
     * in the thread pointing at `/api/attachments/{id}`. Nothing about taking the
     * control away may touch that, so the thread still renders and the bytes
     * still come back as something a browser will draw.
     */
    @Test
    fun `a thread holding a picture drawn before still renders and still serves it`() {
        val chatId = chatWithAgent(serve { saying("Anything.") })
        val bytes = Base64.getDecoder().decode(PIXEL)
        val filed = attachments.save(
            ChatAttachment(
                workspaceId = workspaceId,
                chatSessionId = chatId,
                filename = "a-red-square.png",
                contentType = "image/png",
                sizeBytes = bytes.size.toLong(),
                location = store.put(workspaceId, "a-red-square.png", bytes),
                uploadedBy = "alice",
            ),
        )
        chats.recordPicture(chatId, "![A red square](/api/attachments/${filed.id})")

        assertThat(thread(chatId)).containsExactly("assistant:![A red square](/api/attachments/${filed.id})")
        assertThat(shown(chatId)).contains("![A red square](/api/attachments/${filed.id})")

        val answer = serving.download(requireNotNull(filed.id))
        assertThat(answer.statusCode.value()).isEqualTo(200)
        assertThat(answer.headers.contentType?.toString()).isEqualTo("image/png")
        assertThat(answer.headers.getFirst("Content-Disposition")).startsWith("inline;")
        assertThat(requireNotNull(answer.body).inputStream.use { it.readBytes() }).isEqualTo(bytes)
    }

    /**
     * And the drawing is in the workspace's audit log.
     *
     * The button's drawing was audited and this one is too: what happened is
     * unchanged - this installation's image model was called and somebody's
     * workspace was charged for a picture. The one thing worth saying
     * differently is who decided, which is what the sentence says.
     */
    @Test
    fun `an agent drawing is audited as one`() {
        val chatId = drawingChat()

        graphQlTester.document("""mutation { sendChatMessage(id: $chatId, text: "Draw it") { millis } }""").execute()

        assertThat(audit.findAll().map { it.message }).contains("An agent drew a picture in a chat")
    }

    /**
     * The tool offers the sizes the workspace's model draws, and a quality
     * only where the model takes one. Issue #436.
     *
     * Read off the body the chat endpoint was sent, because what the model was
     * told is the only thing at issue. A DALL-E 3 is told its three sizes and
     * its two qualities; the stub's own model - an OpenAI-shaped endpoint on a
     * host that is not OpenAI's, which is what a self-hosted server is - is told
     * a range and offered no quality at all, since those servers refuse the
     * whole request over a parameter they do not take.
     */
    @Test
    fun `the drawing tool offers what the workspace's model takes, and no more`() {
        val chatId = chatWithAgent(serve { saying("Noted.") })

        drawWith(modelId = "dall-e-3")
        graphQlTester.document("""mutation { sendChatMessage(id: $chatId, text: "Draw it") { millis } }""").execute()
        assertThat(offered.last())
            .contains("\"width\"")
            .contains("\"height\"")
            .contains("1024x1024, 1792x1024, 1024x1792")
            .contains("\"quality\":{")
            .contains("standard, hd")
            .doesNotContain("\"style\"")

        // A second model on the same stub, and the workspace re-pointed at it:
        // what is offered follows the choice, send by send.
        drawWith(modelId = "stub-image", again = true)
        graphQlTester.document("""mutation { sendChatMessage(id: $chatId, text: "Draw it again") { millis } }""").execute()
        assertThat(offered.last())
            .contains("from 64 to 4096")
            .doesNotContain("\"quality\":{")
    }

    /**
     * A size the agent asks for reaches the drawing model, spelled the way the
     * provider takes it.
     *
     * Two numbers in the call, one `WIDTHxHEIGHT` on the wire: the join is the
     * tool's and it happens once. The quality goes as the word it was.
     */
    @Test
    fun `a size and quality the agent asks for reach the drawing model`() {
        val chatId = drawingChat(modelId = "dall-e-3") { body ->
            if (body.contains("/api/attachments/")) {
                saying("There it is.")
            } else {
                drawingCall("""{"description":"A red square","width":1792,"height":1024,"quality":"hd"}""")
            }
        }

        graphQlTester.document("""mutation { sendChatMessage(id: $chatId, text: "Draw a banner") { millis } }""").execute()

        assertThat(drawn.single()).contains("\"size\":\"1792x1024\"").contains("\"quality\":\"hd\"")
        assertThat(attachments.count()).isEqualTo(1)
    }

    /**
     * A size the model does not draw is refused to the agent with the sizes it
     * does, and the provider is never asked.
     *
     * The refusal is a tool result the model reads and works around - here it
     * says so - not a provider's 400 after a round trip, and not an ending.
     */
    @Test
    fun `a size the model does not draw is refused with its list, before the provider is asked`() {
        val chatId = drawingChat(modelId = "dall-e-3") { body ->
            if (body.contains("is not a size")) {
                saying("It only draws 1024x1024, 1792x1024 or 1024x1792.")
            } else {
                drawingCall("""{"description":"A red square","width":800,"height":600}""")
            }
        }

        graphQlTester.document("""mutation { sendChatMessage(id: $chatId, text: "Draw it small") { answer { content } } }""")
            .execute().path("sendChatMessage.answer.content").entity(String::class.java)
            .isEqualTo("It only draws 1024x1024, 1792x1024 or 1024x1792.")

        assertThat(asked).doesNotContain("/images/generations")
        assertThat(attachments.count()).isZero()
        assertThat(offered.last()).contains("800x600").contains("1024x1024, 1792x1024, 1024x1792").contains("error")
    }

    /* ------------------------------------------------------------- the stubs */

    /**
     * A stub answering chat completions in whichever shape it was asked in.
     *
     * Both, because both are used: a round somebody is watching is read as a
     * stream so the working can appear while it is happening, and a round nobody
     * is watching is one blocking call. What the stub is to say is described once
     * as a [Round] and rendered either way, rather than written out twice - two
     * accounts of what one stub says is two things to keep in step, and the one
     * nobody is looking at is the one that drifts.
     */
    private fun serve(answer: (String) -> Round): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            asked += exchange.requestURI.path
            offered += body
            val round = answer(body)
            val streamed = body.replace(" ", "").contains("\"stream\":true")
            val bytes = (if (streamed) frames(round) else blocking(round)).toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", if (streamed) "text/event-stream" else "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}"
    }

    /**
     * Gives the workspace something to draw with: the image half of the stub, a
     * provider and model in front of it, and the workspace's choice of it.
     *
     * @param draws false for a provider that refuses, answering the way these
     *   providers actually refuse a description - a 400 carrying the reason in
     *   `error.message`, which is the sentence the agent is handed.
     * @param modelId what the model calls itself, which is what decides which
     *   sizes the tool offers: a `dall-e-3` is one wherever it is served, and
     *   an id nothing knows on a host that is not OpenAI's is self-hosted.
     * @param again true the second time in one test, when the image context is
     *   already on the stub and only the model is to change - so the second
     *   provider is named apart from the first, since a workspace refuses two
     *   of one name.
     */
    private fun drawWith(draws: Boolean = true, modelId: String = "stub-image", again: Boolean = false) {
        if (!again) {
            server.createContext("/images/generations") { exchange ->
                asked += exchange.requestURI.path
                drawn += exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
                if (draws) {
                    reply(exchange, """{"data":[{"b64_json":"$PIXEL"}]}""", 200)
                } else {
                    reply(exchange, """{"error":{"message":"That is something it would not draw"}}""", 400)
                }
            }
        }

        val modelId = model(if (again) "Second drawer" else "Drawer", modelId, "IMAGE")
        graphQlTester.document(
            """mutation { setWorkspaceImageModel(workspaceId: $workspaceId, modelId: $modelId) { id } }""",
        ).execute().path("setWorkspaceImageModel.id").hasValue()
    }

    private fun reply(exchange: HttpExchange, body: String, status: Int) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    /**
     * The streaming door, run to the end and read back.
     *
     * The controller is called rather than a socket opened, because what is at
     * issue here is which tools the round was lent and what it did with one, not
     * the wire - which `TaskStreamAPITest` covers over real HTTP. The frames go
     * to the response rather than to the stream the body is handed, which is
     * `ServerSentEvents`' whole argument, so that is where they are read from.
     */
    private fun stream(chatId: Long, said: String): String {
        val response = MockHttpServletResponse()
        streaming.stream(chatId, ChatStreamRequest(said), response).writeTo(response.outputStream)
        return response.contentAsString.replace(" ", "")
    }

    /* ------------------------------------------------- what a round looks like */

    /** One round the stub is to produce, said once and rendered two ways. */
    private data class Round(val said: String = "", val tool: String? = null, val arguments: String = "{}")

    private fun drawingCall(arguments: String = """{"description":"A red square"}""") =
        Round(tool = "chat_draw_picture", arguments = arguments)

    private fun saying(said: String) = Round(said = said)

    /**
     * The round as frames, with the tool call spread over two of them - the name
     * in the first and the arguments in the second - because that is how a
     * provider sends one, and a reader that only coped with a whole call in a
     * single frame would work here and fail everywhere real.
     */
    private fun frames(round: Round): String = buildString {
        if (round.tool != null) {
            append(
                """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function",""" +
                    """"function":{"name":"${round.tool}","arguments":""}}]}}]}""",
            ).append(BLANK)
            append(
                """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":""" +
                    """{"arguments":"${escaped(round.arguments)}"}}]}}]}""",
            ).append(BLANK)
        }
        if (round.said.isNotEmpty()) {
            append("""data: {"choices":[{"delta":{"content":"${round.said}"}}]}""").append(BLANK)
        }
        append("""data: {"choices":[{"delta":{}}],"usage":{"prompt_tokens":7,"completion_tokens":2}}""").append(BLANK)
        append("data: [DONE]").append(BLANK)
    }

    /** The same round as one message, which is what an unwatched round asks for. */
    private fun blocking(round: Round): String {
        val calls = round.tool?.let {
            ""","tool_calls":[{"id":"call_1","type":"function",""" +
                """"function":{"name":"$it","arguments":"${escaped(round.arguments)}"}}]"""
        }.orEmpty()
        return """{"choices":[{"message":{"role":"assistant","content":"${round.said}"$calls}}],""" +
            """"usage":{"prompt_tokens":7,"completion_tokens":2}}"""
    }

    /** A provider sends a tool call's arguments as a JSON string holding JSON. */
    private fun escaped(arguments: String) = arguments.replace("\"", "\\\"")

    /* ---------------------------------------------------------- the fixtures */

    /**
     * A chat with an agent on a workspace that draws, answering the ordinary way
     * unless told otherwise: draw once, then say something now that there is a
     * picture in hand.
     */
    private fun drawingChat(
        draws: Boolean = true,
        modelId: String = "stub-image",
        answer: (String) -> Round = { body ->
            if (body.contains("/api/attachments/")) saying("There it is.") else drawingCall()
        },
    ): Long {
        val chatId = chatWithAgent(serve(answer))
        drawWith(draws, modelId)
        return chatId
    }

    /**
     * The thread as the chat holds it: what was said, in order, and nothing else.
     *
     * Not `chatMessages`, which is the *page* - it folds the agent's lookups in
     * around the thread out of the session, so a round that called a tool shows
     * lines the conversation does not contain. What is at issue here is what a
     * reload reads the picture back out of, and that is the thread.
     */
    private fun thread(chatId: Long): List<String> {
        val conversation = requireNotNull(sessions.findByIdOrNull(chatId)).conversationId
        return history.findByConversationId(conversation)
            .map { "${it.messageType.name.lowercase()}:${it.text.orEmpty()}" }
    }

    /** And what the page draws, working included. */
    private fun shown(chatId: Long): List<String> = graphQlTester
        .document("{ chatMessages(id: $chatId) { content } }").execute()
        .path("chatMessages[*].content").entityList(String::class.java).get()

    /**
     * A chat handed to an agent, on a workspace that has chosen nothing to draw
     * with.
     *
     * @param orknux whether the agent is granted anything of its own. False is
     *   the ordinary case and the honest one for this feature - a chat agent
     *   granted nothing still has to be able to draw. It is turned on only where
     *   a test needs the round's tool loop to run for some *other* reason.
     */
    private fun chatWithAgent(endpoint: String, orknux: Boolean = false): Long {
        val modelId = model("Talker", "stub-chat", "CHAT", endpoint)
        val agentId = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Worker", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        graphQlTester.document(
            """mutation { updateAgent(id: $agentId, input: {
                 name: "Worker", modelId: $modelId, orknuxAccess: $orknux
               }) { id } }""",
        ).execute()

        val chatId = graphQlTester.document(
            """mutation { startChat(input: { workspaceId: $workspaceId, title: "Pictures" }) { id } }""",
        ).execute().path("startChat.id").entity(Long::class.java).get()
        graphQlTester.document("""mutation { chooseChatAgent(id: $chatId, agentId: $agentId) { id } }""")
            .execute().path("chooseChatAgent.id").hasValue()
        return chatId
    }

    /**
     * A model of one kind, on a provider of its own at the stub.
     *
     * A provider each rather than one shared, because that is how an
     * installation is actually arranged - the chat model and the image model are
     * different products even where they are the same company - and because it
     * keeps the endpoint the only thing the two have in common.
     */
    private fun model(
        name: String,
        modelId: String,
        kind: String,
        endpoint: String = "http://${server.address.hostString}:${server.address.port}",
    ): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "$name provider", type: OPENAI,
                 endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()

        return graphQlTester.document(
            """mutation { createModel(input: {
                 providerId: $providerId, name: "$name", modelId: "$modelId", kind: $kind
               }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }

    private companion object {
        /**
         * What ends one server-sent frame: the blank line the protocol separates
         * them with. Named because it follows every frame above, and a bare pair
         * of newlines wedged between two JSON strings is exactly the thing an
         * edit loses without anybody noticing.
         */
        const val BLANK = "\n\n"

        /** A one-pixel PNG, base64, which is a real picture and eight bytes of one. */
        const val PIXEL =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    }
}
