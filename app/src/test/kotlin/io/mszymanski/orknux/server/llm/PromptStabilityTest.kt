package io.mszymanski.orknux.server.llm

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.chat.AgentConversation
import io.mszymanski.orknux.server.chat.ChatService
import io.mszymanski.orknux.server.chat.ChatSessionRepository
import io.mszymanski.orknux.server.workspace.Workspace
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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Every request a conversation sends extends the one before it, across turns.
 *
 * Captured against a llama.cpp server: inside a turn each round's request
 * extended the last, and at the next message the rounds were gone - the
 * assistant message carrying `reasoning_content` and `tool_calls`, and the tool
 * results, collapsed into the final answer and a recall block - so the prompt
 * cache missed from the third message on and the whole session was read again
 * on every message. See [SessionPrompt].
 *
 * What is asserted is what a prefix cache needs: each request's messages are
 * the previous request's messages, each one serialised identically, followed by
 * something new; and the system turn and the tools are the same throughout.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class PromptStabilityTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val chats: ChatService,
    @Autowired val chatSessions: ChatSessionRepository,
    @Autowired val history: ChatMemoryRepository,
    @Autowired val conversation: AgentConversation,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val prompts: SessionPrompt,
    @Autowired val agents: AgentRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer
    private val received = CopyOnWriteArrayList<String>()
    private val script = ConcurrentLinkedQueue<String>()
    private val mapper = ObjectMapper()

    @BeforeEach
    fun reset() {
        chatSessions.findAll().forEach { history.deleteByConversationId(it.conversationId) }
        chatSessions.deleteAll()
        agents.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        received.clear()
        script.clear()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `a chat's requests each extend the last, tool rounds and reasoning included, across turns`() {
        val agentId = agent("Reviewer", model(serve()))
        script += called("call_1", "I should see what skills I hold.")
        script += answered("I review code.")
        script += called("call_2", "Arithmetic; no tool needed, but let me check.")
        script += answered("Four.")
        script += answered("You're welcome.")
        script += answered("You are welcome.")

        val chat = requireNotNull(chats.start(workspaceId, "alice", "Stable", agentId).id)
        send(chat, "What can you do?")
        send(chat, "And what is 2+2?")
        send(chat, "Thanks.")

        assertThat(received).hasSize(5)
        extendsEachOther(received)
        // The round of the first turn is still there in the second: the call
        // with the reasoning it was made with, and the answer to it.
        val second = messages(received[2])
        assertThat(second.map { it.path("role").asString() })
            .containsExactly("system", "user", "assistant", "tool", "assistant", "user")
        assertThat(second[2].path("reasoning_content").asString()).isEqualTo("I should see what skills I hold.")
        assertThat(second[2].path("tool_calls")[0].path("id").asString()).isEqualTo("call_1")
        assertThat(second[3].path("tool_call_id").asString()).isEqualTo("call_1")
        assertThat(second[4].path("content").asString()).isEqualTo("I review code.")
        // No recall block rebuilt in front of the question.
        assertThat(received[2]).doesNotContain("What your tools returned earlier")

        /*
         * And asking the last answer again sends exactly what the answer it
         * replaces was asked with: the question stays, the answer goes.
         */
        val again = chats.beginRegenerate(chat)
        val said = chats.ask(again) as ChatCompletion.Answered
        chats.finishSend(chat, said.content)
        assertThat(received).hasSize(6)
        assertThat(messages(received[5]).map(::text)).isEqualTo(messages(received[4]).map(::text))
    }

    /**
     * What is said into a session between turns - a task's "carry on", a
     * person's message - is added after what was sent, never folded into it.
     */
    @Test
    fun `a session's next turn adds what was said since, after everything already sent`() {
        val agentId = agent("Worker", model(serve()))
        val agent = requireNotNull(agents.findByIdOrNull(agentId))
        val modelId = requireNotNull(agent.modelId)
        val session = recorder.open(workspaceId, "test", "stable-${System.nanoTime()}")
        script += called("call_a", "Look first.")
        script += answered("Started.")
        script += called("call_b", "Again.")
        script += answered("Finished.")

        assertThat(prompts.history(session, Int.MAX_VALUE)).isNull()
        recorder.userSaid(session, "alice", "Do the job.")
        conversation.answer(modelId, agent, listOf(ChatTurn("system", "Work."), ChatTurn("user", "Do the job.")), session)
        recorder.userSaid(session, "task", "Carry on.")

        val sent = requireNotNull(prompts.history(session, Int.MAX_VALUE))
        assertThat(sent.last()).isEqualTo(ChatTurn("user", "Carry on."))
        conversation.answer(modelId, agent, listOf(ChatTurn("system", "Work.")) + sent, session)

        assertThat(received).hasSize(4)
        extendsEachOther(received)

        // Outgrown, it is dropped whole, once, and the turn is built afresh.
        assertThat(prompts.history(session, 10)).isNull()
        assertThat(prompts.history(session, Int.MAX_VALUE)).isNull()
    }

    private fun extendsEachOther(bodies: List<String>) {
        val first = mapper.readTree(bodies.first())
        bodies.zipWithNext().forEachIndexed { at, (before, after) ->
            val was = messages(before).map(::text)
            val now = messages(after).map(::text)
            assertThat(now.size).describedAs("request ${at + 2} adds to request ${at + 1}").isGreaterThan(was.size)
            assertThat(now.subList(0, was.size))
                .describedAs("request ${at + 2} begins with request ${at + 1}")
                .isEqualTo(was)
            val next = mapper.readTree(after)
            assertThat(text(next.path("tools"))).describedAs("tools of request ${at + 2}")
                .isEqualTo(text(first.path("tools")))
        }
    }

    private fun messages(body: String): List<JsonNode> = mapper.readTree(body).path("messages").values().toList()

    /** As the provider received it, field order included. */
    private fun text(node: JsonNode): String = mapper.writeValueAsString(node)

    private fun send(chat: Long, text: String) {
        val start = chats.beginSend(chat, text)
        val said = chats.ask(start)
        assertThat(said).isInstanceOf(ChatCompletion.Answered::class.java)
        chats.finishSend(chat, (said as ChatCompletion.Answered).content)
    }

    private fun called(id: String, thinking: String) =
        """{"choices":[{"message":{"role":"assistant","content":null,"reasoning_content":"$thinking",
           "tool_calls":[{"id":"$id","type":"function","function":{"name":"skill_list","arguments":"{}"}}]}}],
           "usage":{"prompt_tokens":7,"completion_tokens":2}}"""

    private fun answered(text: String) =
        """{"choices":[{"message":{"role":"assistant","content":"$text"}}],
           "usage":{"prompt_tokens":9,"completion_tokens":1}}"""

    private fun serve(): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            received += body
            val bytes = (script.poll() ?: answered("Out of script.")).toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}"
    }

    private fun agent(name: String, modelId: Long): Long {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "$name", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "$name", modelId: $modelId }) { id } }""",
        ).execute()
        return id
    }

    private fun model(endpoint: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub", endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()

        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT })
               { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }
}
