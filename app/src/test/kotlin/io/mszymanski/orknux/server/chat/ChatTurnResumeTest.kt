package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.workspace.Workspace
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
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * A chat's answer outlives the server that was writing it.
 *
 * A restart in the middle of a turn used to leave the chat ending on the
 * question for good. What is asserted here is the row that says a turn is being
 * written, and the sweep that answers a turn whose row has gone quiet - the two
 * halves that replace the thread that died. The process dying itself is not
 * staged: a row with an old beat and a question at the end of the thread is
 * exactly what a dead process leaves, and is what the next one finds.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ChatTurnResumeTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val sessions: ChatSessionRepository,
    @Autowired val history: ChatMemoryRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val turns: ChatTurnRepository,
    @Autowired val ledger: ChatTurnLedger,
    @Autowired val sweeper: ChatTurnSweeper,
    @Autowired val generations: ChatGenerations,
    @Autowired val service: ChatService,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer
    private val asked = AtomicInteger()

    @BeforeEach
    fun reset() {
        turns.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "durable-${System.nanoTime()}")).id)
        asked.set(0)
    }

    @AfterEach
    fun stop() {
        if (::server.isInitialized) server.stop(0)
        turns.deleteAll()
    }

    @Test
    fun `a turn cut off by a restart is answered when the next server sweeps`() {
        val chatId = chatAskedButNeverAnswered(model(serveSaying("Here after all.")))
        turns.save(ChatTurnRecord(chatId = chatId, beatAt = OffsetDateTime.now().minusMinutes(10)))

        assertThat(sweeper.sweep()).isEqualTo(1)

        waitFor { history.findByConversationId(conversation(chatId)).size == 2 }
        assertThat(history.findByConversationId(conversation(chatId)).map { it.text })
            .containsExactly("Did the sync run?", "Here after all.")
        waitFor { !turns.existsById(chatId) }
        assertThat(asked.get()).describedAs("asked once, not once per sweep").isEqualTo(1)
    }

    @Test
    fun `a turn still being written here is not answered a second time`() {
        val chatId = chatAskedButNeverAnswered(model(serveSaying("Twice?")))
        val generation = ChatGeneration()
        generations.register(chatId, generation)
        try {
            turns.save(ChatTurnRecord(chatId = chatId, beatAt = OffsetDateTime.now().minusMinutes(10)))

            assertThat(sweeper.sweep()).isZero()
            assertThat(turns.findById(chatId).orElseThrow().beatAt).isAfter(OffsetDateTime.now().minusMinutes(1))
            assertThat(asked.get()).isZero()
        } finally {
            generations.release(chatId, generation)
        }
    }

    @Test
    fun `a turn cut off too many times is given up on`() {
        val chatId = chatAskedButNeverAnswered(model(serveSaying("Again.")))
        turns.save(ChatTurnRecord(chatId = chatId, beatAt = OffsetDateTime.now().minusMinutes(10), resumes = 3))

        assertThat(sweeper.sweep()).isZero()
        assertThat(turns.existsById(chatId)).isFalse()
        assertThat(asked.get()).isZero()
    }

    @Test
    fun `a turn being answered is written down, and let go of when it ends`() {
        val chatId = chatAskedButNeverAnswered(model(serveSaying("Unused.")))
        val generation = ChatGeneration()

        generations.register(chatId, generation)
        assertThat(turns.existsById(chatId)).isTrue()

        generations.release(chatId, generation)
        assertThat(turns.existsById(chatId)).isFalse()

        // My Claude Code's answer is the person's Claude Code's, never ours to ask for again.
        val claude = ChatGeneration(durable = false)
        generations.register(chatId, claude)
        assertThat(turns.existsById(chatId)).isFalse()
        generations.release(chatId, claude)
    }

    @Test
    fun `a server stopping hands its turns over at once rather than ending them`() {
        val chatId = chatAskedButNeverAnswered(model(serveSaying("Unused.")))
        val generation = ChatGeneration()
        generations.register(chatId, generation)
        try {
            ledger.stop()
            assertThat(turns.findById(chatId).orElseThrow().beatAt.toInstant()).isEqualTo(ChatTurnLedger.HANDED_OVER.toInstant())
            assertThat(ledger.stopping).isTrue()

            // The turn failing as the server goes down leaves it to be answered again.
            generations.release(chatId, generation)
            assertThat(turns.existsById(chatId)).isTrue()
        } finally {
            ledger.start()
        }
        assertThat(ledger.stopping).isFalse()
    }

    @Test
    fun `an answer kept ends the turn in the same breath`() {
        val chatId = chatAskedButNeverAnswered(model(serveSaying("Unused.")))
        turns.save(ChatTurnRecord(chatId = chatId))

        graphQlTester.document("""mutation { sendChatMessage(id: $chatId, text: "And now?") { answer { content } } }""")
            .execute().errors().verify()

        assertThat(turns.existsById(chatId)).isFalse()
    }

    /** A row that outlived its turn - the answer was kept, and the row was not let go of - is not a turn to answer. */
    @Test
    fun `a chat already answered is not answered again, whatever row is left`() {
        val chatId = chatAskedButNeverAnswered(model(serveSaying("A second answer.")))
        history.saveAll(conversation(chatId), history.findByConversationId(conversation(chatId)) + AssistantMessage("It ran at six."))
        turns.save(ChatTurnRecord(chatId = chatId, beatAt = OffsetDateTime.now().minusMinutes(10)))

        sweeper.sweep()
        // Taken up, and then found to have nothing to answer: let go of, with nothing asked.
        waitFor { !turns.existsById(chatId) }

        assertThat(turns.existsById(chatId)).isFalse()
        Thread.sleep(300)
        assertThat(asked.get()).isEqualTo(0)
        assertThat(history.findByConversationId(conversation(chatId)).map { it.text }).containsExactly("Did the sync run?", "It ran at six.")
    }

    /**
     * Two servers: one handed a turn over as it stopped and went on writing it,
     * the other took it up. The first one's answer arriving late is not kept -
     * the chat would end on two answers - and the turn stays the taker's.
     */
    @Test
    fun `an answer from a server whose turn was taken over is not kept`() {
        val chatId = chatAskedButNeverAnswered(model(serveSaying("Unused.")))
        turns.save(ChatTurnRecord(chatId = chatId, resumes = 1))

        service.finishSend(chatId, "The stopped server's answer.", take = 0)

        assertThat(history.findByConversationId(conversation(chatId)).map { it.text }).containsExactly("Did the sync run?")
        assertThat(turns.existsById(chatId)).isTrue()

        service.finishSend(chatId, "The taker's answer.", take = 1)
        assertThat(history.findByConversationId(conversation(chatId)).map { it.text }).containsExactly("Did the sync run?", "The taker's answer.")
        assertThat(turns.existsById(chatId)).isFalse()
    }

    private fun conversation(chatId: Long) = sessions.findById(chatId).orElseThrow().conversationId

    /** What a process that died after taking the message leaves: the question in the thread, and no answer. */
    private fun chatAskedButNeverAnswered(modelId: Long): Long {
        val conversationId = UUID.randomUUID().toString()
        val id = requireNotNull(
            sessions.save(
                ChatSession(workspaceId = workspaceId, conversationId = conversationId, title = "Cut off", userId = "alice", modelId = modelId),
            ).id,
        )
        history.saveAll(conversationId, listOf(UserMessage("Did the sync run?")))
        return id
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < until) Thread.sleep(50)
    }

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

    private fun serveSaying(said: String): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            asked.incrementAndGet()
            val bytes = """{"choices":[{"message":{"role":"assistant","content":"$said"}}]}""".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}"
    }
}
