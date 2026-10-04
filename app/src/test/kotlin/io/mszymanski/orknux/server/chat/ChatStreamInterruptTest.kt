package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.connector.model.ModelUsageRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.memory.ChatMemoryRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.test.context.support.WithMockUser
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * When a chat whose reader has gone stops the model, and when it does not.
 *
 * Two issues meet here. A voice turn whose reader leaves is stopped rather than
 * paid to finish (#299); a text turn whose reader leaves is finished and written
 * to the history, because a chat is a record and the answer is wanted when the
 * person comes back (#335). Stopping a text turn on purpose is [ChatStreamAPI]'s
 * own interrupt door, not a lost reader.
 *
 * Issue #299, on the server's half. The browser learnt to abort the request when
 * somebody interrupts; this is the claim that aborting it means anything —
 * because it did not. A servlet container on blocking IO says nothing about a
 * browser that has left: it finds out on the next write, and a chat's answer is
 * one long call into a model with nothing to write for the whole of it. An
 * agent's round reports a lookup and then goes quiet while the answer is
 * composed, so there was no write to fail on and nothing noticed anything.
 * Measured before the fix, with the reader gone from the very first frame, the
 * stub provider below streamed **all four hundred** of them and was never hung
 * up on.
 *
 * **The assertion is made on the provider, not on the endpoint.** What is at
 * issue is whether the model call stopped, and the only witness to that is the
 * thing on the other end of it: the stub streams a long answer one frame at a
 * time and records whether its own write was ever refused. An endpoint that
 * closed its response to the browser and left the provider streaming would pass
 * any assertion made this side of it, which is exactly the bug.
 *
 * Both shapes are driven. Every chat opened now has an agent, but the ones made
 * before issue #295 are still there and still hold a bare model, and the two
 * take different branches through [ChatStreamAPI] - one that streams pieces to
 * the browser as they land and one that does not. The one that does not is
 * where this went unnoticed.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ChatStreamInterruptTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val streaming: ChatStreamAPI,
    @Autowired val sessions: ChatSessionRepository,
    @Autowired val history: ChatMemoryRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val agentTools: AgentToolRepository,
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

    /** How many frames the stub provider got onto the wire before it was cut off. */
    private val written = AtomicInteger()

    /** Whether the stub's own write was refused, which is the provider being hung up on. */
    private val torn = AtomicBoolean()

    /** Counted down when the stub has stopped answering, however it stopped. */
    private val done = CountDownLatch(1)

    @BeforeEach
    fun reset() {
        sessions.findAll().forEach { history.deleteByConversationId(it.conversationId) }
        sessions.deleteAll()
        llmEvents.deleteAll()
        llmSessions.deleteAll()
        agents.deleteAll()
        agentTools.deleteAll()
        usage.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
    }

    @AfterEach
    fun stop() = server.stop(0)

    /**
     * The ordinary chat: an agent answers, and nothing is written to the browser
     * while it does.
     *
     * This is the shape the issue was reported against and the one that noticed
     * nothing at all. The agent has no tools, so its round is one streaming call
     * whose pieces go to [RoundWatch.answering] - which this endpoint draws
     * nothing for, because the answer is sent whole when the round ends. So
     * between the question and that one frame there is no write, and before the
     * fix there was therefore nothing for a hang-up to be discovered on.
     */
    @Test
    fun `a voice agent's answer is stopped when the reader goes`() {
        val chatId = chatWithAgent(model(serve()))

        run(chatId)

        assertThat(torn.get()).isTrue()
        assertThat(written.get()).isLessThan(FRAMES)
    }

    /**
     * And a chat from before agents were compulsory, which streams every piece.
     *
     * Held because it is the branch that appeared to work: each chunk is written
     * to the browser as it lands, so a write does fail eventually and the SDK's
     * stream is closed on the way out. That was luck rather than design - it
     * worked only while the model was producing text, and stopped working the
     * moment the answer went quiet - and it is worth keeping honest either way.
     */
    @Test
    fun `a voice bare model's answer is stopped when the reader goes`() {
        val chatId = chatOnBareModel(model(serve()))

        run(chatId)

        assertThat(torn.get()).isTrue()
        assertThat(written.get()).isLessThan(FRAMES)
    }

    /**
     * And nothing an abandoned turn produced is written down.
     *
     * The chat keeps the question and no answer. What the model had composed
     * when the connection went is part of an answer somebody stopped on purpose,
     * and a conversation reopened tomorrow ending in half a sentence attributed
     * to the model is a worse record than one ending on the question.
     */
    @Test
    fun `a voice turn's abandoned answer is not kept`() {
        val chatId = chatWithAgent(model(serve()))

        run(chatId)

        graphQlTester.document("{ chatMessages(id: $chatId) { role content } }")
            .execute()
            .path("chatMessages[*].role").entityList(String::class.java)
            .containsExactly("user")
    }

    /**
     * A text turn whose reader leaves is finished anyway, and written down.
     *
     * The other half of the same decision: a text chat is a record, so an answer
     * whose reader walked away is the one they want when they come back rather
     * than one to throw out. The reader is gone from the first frame - every
     * write to it fails - and the provider still streams the whole answer
     * because nothing hung it up, and the answer is in the history when it ends.
     * Issue #335.
     */
    @Test
    fun `a text turn whose reader leaves is finished and kept`() {
        val chatId = chatWithAgent(model(serve()))

        run(chatId, voice = false)

        // Never hung up on: the provider ran to the end though nobody read it.
        assertThat(torn.get()).isFalse()
        assertThat(written.get()).isEqualTo(FRAMES)

        graphQlTester.document("{ chatMessages(id: $chatId) { role } }")
            .execute()
            .path("chatMessages[*].role").entityList(String::class.java)
            .containsExactly("user", "assistant")
    }

    /**
     * And a text turn is stopped when Stop is pressed, not when the reader goes.
     *
     * Stopping is its own call now - [ChatStreamAPI.interrupt] through
     * [ChatGenerations] - because a lost reader no longer means stop. The
     * reader here stays (writes to a real buffer succeed); the turn is answered
     * on a thread of its own, interrupted part way, and the provider is hung up
     * on with the answer left off the history. Issue #335.
     */
    @Test
    fun `a text turn is stopped when it is interrupted`() {
        val chatId = chatWithAgent(model(serve()))
        val response = MockHttpServletResponse()
        val body = streaming.stream(chatId, ChatStreamRequest("Say something long", voice = false), response)

        val worker = Executors.newSingleThreadExecutor()
        worker.submit { runCatching { body.writeTo(response.outputStream) } }
        // Long enough that the answer is under way; the stub is 5ms a frame.
        Thread.sleep(INTERRUPT_AFTER_MILLIS)
        streaming.interrupt(chatId)

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue()
        assertThat(torn.get()).isTrue()
        assertThat(written.get()).isLessThan(FRAMES)
        worker.shutdownNow()

        graphQlTester.document("{ chatMessages(id: $chatId) { role } }")
            .execute()
            .path("chatMessages[*].role").entityList(String::class.java)
            .containsExactly("user")
    }

    /**
     * A text turn whose reader leaves *while the model is thinking* is finished
     * and kept, and a page that comes back while it is still being written
     * picks it up from its first frame. Issue #201.
     *
     * The reader here is a real one for a while - it reads the stream until the
     * model's first piece of thinking arrives - and then goes, which is somebody
     * navigating away or closing the tab mid-thinking. A second reader then
     * follows the chat, as the page that comes back does, and must get the
     * thinking it missed, the answer and the `done` frame, while the provider
     * runs to the end unhung-up and the history ends on the answer.
     */
    @Test
    fun `a text turn left mid-thinking is finished, kept and can be followed from the start`() {
        val chatId = chatWithAgent(model(serve(thinking = true)))
        val asked = Leaving()
        val body = streaming.stream(chatId, ChatStreamRequest("Think about it", voice = false), asked)
        val pool = Executors.newCachedThreadPool()
        val relayed = pool.submit { runCatching { body.writeTo(asked.outputStream) } }

        // Leave on the first piece of thinking: the model is mid-thought.
        assertThat(asked.thinkingSeen.await(10, TimeUnit.SECONDS)).isTrue()
        asked.leave()
        relayed.get(10, TimeUnit.SECONDS)
        assertThat(written.get()).isLessThan(FRAMES)

        // Back on the page while it is still being written.
        val back = MockHttpServletResponse()
        val following = streaming.follow(chatId, back)
        pool.submit { following.writeTo(back.outputStream) }.get(20, TimeUnit.SECONDS)
        pool.shutdownNow()

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue()
        assertThat(torn.get()).isFalse()
        assertThat(written.get()).isEqualTo(FRAMES)

        val frames = back.contentAsString
        assertThat(frames).startsWith("event: following")
        // From the first frame, not from where the reader joined.
        assertThat(frames).contains("""event: thinking${"\n"}data: {"text":"hmm "}""")
        assertThat(frames.split("event: thinking").size - 1).isEqualTo(FRAMES / 2)
        assertThat(frames).contains("event: chunk").contains("event: done")

        graphQlTester.document("{ chatMessages(id: $chatId) { role content } }")
            .execute()
            .path("chatMessages[*].role").entityList(String::class.java)
            .containsExactly("user", "assistant")
    }

    /**
     * Following a chat with nothing being written says so and ends, rather than
     * holding the page open on an answer that will never come.
     */
    @Test
    fun `following an idle chat says idle and ends`() {
        // Nothing is asked of it; it is only here for the clean-up to stop.
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply { start() }
        val chatId = chatWithAgent(model("http://stub.invalid"))
        val response = MockHttpServletResponse()

        streaming.follow(chatId, response).writeTo(response.outputStream)

        assertThat(response.contentAsString).isEqualTo("event: idle\ndata: {}\n\n")
    }

    /**
     * And Stop still stops a turn that is only being followed: the follower's
     * stream ends, the provider is hung up on and nothing is kept.
     */
    @Test
    fun `stopping a followed turn stops it and keeps nothing`() {
        val chatId = chatWithAgent(model(serve(thinking = true)))
        val asked = Leaving()
        val body = streaming.stream(chatId, ChatStreamRequest("Think about it", voice = false), asked)
        val pool = Executors.newCachedThreadPool()
        val relayed = pool.submit { runCatching { body.writeTo(asked.outputStream) } }
        assertThat(asked.thinkingSeen.await(10, TimeUnit.SECONDS)).isTrue()
        asked.leave()
        relayed.get(10, TimeUnit.SECONDS)

        val back = MockHttpServletResponse()
        // Asked on this thread, which is the one signed in; read on another.
        val follow = streaming.follow(chatId, back)
        val following = pool.submit { follow.writeTo(back.outputStream) }
        Thread.sleep(INTERRUPT_AFTER_MILLIS)
        streaming.interrupt(chatId)
        following.get(10, TimeUnit.SECONDS)
        pool.shutdownNow()

        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue()
        assertThat(torn.get()).isTrue()
        assertThat(back.contentAsString).doesNotContain("event: done")
        graphQlTester.document("{ chatMessages(id: $chatId) { role } }")
            .execute()
            .path("chatMessages[*].role").entityList(String::class.java)
            .containsExactly("user")
    }

    /**
     * A response read by somebody until [leave], and gone after it.
     *
     * Writes land in the buffer while the reader is there, the way a browser
     * reading the stream takes them; after [leave] every write and flush fails,
     * which is what a container does once the connection has closed under it.
     * [thinkingSeen] opens on the first thinking frame, so the test can leave at
     * exactly the moment the issue is about.
     */
    private class Leaving : MockHttpServletResponse() {
        val thinkingSeen = CountDownLatch(1)

        @Volatile
        private var gone = false

        private val seen = StringBuilder()

        private val stream = object : ServletOutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (gone) throw IOException("Broken pipe")
                synchronized(seen) {
                    seen.append(String(b, off, len, StandardCharsets.UTF_8))
                    if (seen.contains("event: thinking")) thinkingSeen.countDown()
                }
            }

            override fun isReady() = true
            override fun setWriteListener(listener: WriteListener?) = Unit
        }

        fun leave() {
            gone = true
        }

        override fun getOutputStream(): ServletOutputStream = stream

        override fun flushBuffer() {
            if (gone) throw IOException("Broken pipe")
        }
    }

    /**
     * The streaming door, run with a reader that has walked away already.
     *
     * `voice` is what a lost reader means: a voice turn is stopped by it (#299),
     * a text turn is not and is written to the history instead (#335). The
     * cases that assert stopping drive a voice turn, because that is the turn a
     * lost reader still stops.
     */
    private fun run(chatId: Long, voice: Boolean = true) {
        val response = Gone()
        streaming.stream(chatId, ChatStreamRequest("Say something long", voice = voice), response)
            .writeTo(response.outputStream)
        // The stub answers on a thread of its own, so what it made of the
        // hang-up is only settled once it has stopped.
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue()
    }

    /**
     * A response whose reader has gone.
     *
     * Every write and every flush fails, which is what a servlet container does
     * once the browser has closed the connection under it. The keep-alive
     * [io.mszymanski.orknux.server.stream.ReaderWatch] sends is a write like any
     * other, so this is also how it finds out.
     */
    private class Gone : MockHttpServletResponse() {
        private val stream = object : ServletOutputStream() {
            override fun write(b: Int) = throw IOException("Broken pipe")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("Broken pipe")
            override fun isReady() = true
            override fun setWriteListener(listener: WriteListener?) = Unit
        }

        override fun getOutputStream(): ServletOutputStream = stream

        override fun flushBuffer() = throw IOException("Broken pipe")
    }

    /**
     * A provider that streams a very long answer, one small frame at a time, and
     * remembers where it got to.
     *
     * Long enough that an answer allowed to run to the end is unmistakable, and
     * slow enough that a hang-up has somewhere to land: a stub that writes four
     * hundred frames in a millisecond is over before anything could stop it,
     * which would make this pass for the wrong reason.
     */
    private fun serve(thinking: Boolean = false): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/chat/completions") { exchange ->
            exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            // Nought means chunked, which is what lets this answer arrive over
            // time rather than be handed over whole.
            exchange.sendResponseHeaders(200, 0)
            try {
                exchange.responseBody.use { out ->
                    repeat(FRAMES) { at ->
                        // The first half thought rather than said, where it is
                        // a model that thinks before it answers.
                        val delta = if (thinking && at < FRAMES / 2) "reasoning_content" else "content"
                        val piece = if (delta == "content") "word " else "hmm "
                        out.write("""data: {"choices":[{"delta":{"$delta":"$piece"}}]}$BLANK""".toByteArray())
                        out.flush()
                        written.incrementAndGet()
                        Thread.sleep(FRAME_MILLIS)
                    }
                    out.write("data: [DONE]$BLANK".toByteArray())
                    out.flush()
                }
            } catch (_: IOException) {
                torn.set(true)
            } finally {
                done.countDown()
            }
            exchange.close()
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}"
    }

    /** A chat of the shape the ones from before issue #295 have: a model, no agent. */
    private fun chatOnBareModel(modelId: Long): Long = requireNotNull(
        sessions.save(
            ChatSession(
                workspaceId = workspaceId,
                conversationId = "3f2b9c4e-8a71-4d55-9c02-6d1e7f0a5b83",
                title = "From before",
                userId = "alice",
                modelId = modelId,
                agentId = null,
            ),
        ).id,
    )

    private fun chatWithAgent(modelId: Long): Long {
        val agentId = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Worker", type: LLM }) { id } }""",
        ).execute().errors().verify().path("createAgent.id").entity(Long::class.java).get()
        graphQlTester.document(
            """mutation { updateAgent(id: $agentId, input: { name: "Worker", modelId: $modelId }) { id } }""",
        ).execute().errors().verify()

        val chatId = graphQlTester.document(
            """mutation { startChat(input: { workspaceId: $workspaceId, title: "Work" }) { id } }""",
        ).execute().errors().verify().path("startChat.id").entity(Long::class.java).get()
        graphQlTester.document("""mutation { chooseChatAgent(id: $chatId, agentId: $agentId) { id } }""")
            .execute().errors().verify().path("chooseChatAgent.id").hasValue()
        return chatId
    }

    private fun model(endpoint: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub", type: OPENAI, endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().errors().verify().path("createModelProvider.id").entity(Long::class.java).get()

        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT })
               { id } }""",
        ).execute().errors().verify().path("createModel.id").entity(Long::class.java).get()
    }

    private companion object {
        /** What ends one server-sent frame: the blank line the protocol separates them with. */
        const val BLANK = "\n\n"

        /** Long enough that an answer running to the end is unmistakable. */
        const val FRAMES = 400

        /** And slow enough that there is a stream to interrupt rather than a burst. */
        const val FRAME_MILLIS = 5L

        /** Long enough that the answer is well under way before Stop is pressed. */
        const val INTERRUPT_AFTER_MILLIS = 300L
    }
}
