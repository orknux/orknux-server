package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.ChatCompletion
import io.mszymanski.orknux.connector.model.ChatTurn
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.attachment.InstallationSettings
import io.mszymanski.orknux.server.llm.LlmSessionEventKind
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A turn that outgrows the model's window is summarised and carries on, and the
 * session says so with the summary itself: the steps it replaced are gone from
 * what the model sees, so what it was told instead is the one account of them a
 * reader can check. The line used to say only that it had happened.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class TurnShrinkNoteTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val conversation: AgentConversation,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val events: LlmSessionEventRepository,
    @Autowired val settings: InstallationSettings,
    @Autowired val agents: AgentRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer

    /** What the stub answers, in order: a status and a body. */
    private val script = ConcurrentLinkedQueue<Pair<Int, String>>()
    private var keptBefore: Int = 0

    @BeforeEach
    fun reset() {
        agents.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "shrink")).id)
        script.clear()
        keptBefore = settings.sessionCompactionKeepTurns()
        settings.setSessionCompactionKeepTurns(2, "alice")
    }

    @AfterEach
    fun stop() {
        settings.setSessionCompactionKeepTurns(keptBefore, "alice")
        server.stop(0)
    }

    @Test
    fun `the line saying a turn was summarised carries the summary`() {
        val agentId = agent("Builder", model(serve()))
        val agent = requireNotNull(agents.findByIdOrNull(agentId))
        val session = recorder.open(workspaceId, "test", "shrink-${System.nanoTime()}")
        script += 200 to called("call_1")
        script += 200 to called("call_2")
        script += 200 to called("call_3")
        script += 400 to """{"error":{"message":"request (69015 tokens) exceeds the available context size (65536 tokens)"}}"""
        script += 200 to answered("Three lookups listed the skills; nothing was built yet.")
        script += 200 to answered("Done.")

        val said = conversation.answer(
            requireNotNull(agent.modelId), agent,
            listOf(ChatTurn("system", "Work."), ChatTurn("user", "Build it.")), session,
        )

        assertThat(said).isInstanceOf(ChatCompletion.Answered::class.java)
        val note = events.findAll().filter { it.sessionId == session && it.kind == LlmSessionEventKind.SYSTEM }
            .single { it.content.orEmpty().startsWith("The turn outgrew the model's window") }
        assertThat(note.content).contains("Three lookups listed the skills; nothing was built yet.")
    }

    /**
     * llama.cpp does not refuse a prompt that fills its window: it starts, runs
     * out of room, and stops for length with nothing said. Read as the context
     * being exceeded - well short of the model's own output limit - the turn is
     * summarised and carries on, where asking the same round again only came
     * back empty again, three times, and the turn failed.
     */
    @Test
    fun `an empty reply cut off by a full context window is summarised, not asked again as it was`() {
        val agentId = agent("Builder", model(serve()))
        val agent = requireNotNull(agents.findByIdOrNull(agentId))
        val session = recorder.open(workspaceId, "test", "full-${System.nanoTime()}")
        script += 200 to called("call_1")
        script += 200 to called("call_2")
        script += 200 to called("call_3")
        script += 200 to """{"choices":[{"message":{"role":"assistant","content":""},"finish_reason":"length"}],
            "usage":{"prompt_tokens":65535,"completion_tokens":12}}"""
        script += 200 to answered("Three lookups so far.")
        script += 200 to answered("Done.")

        val said = conversation.answer(
            requireNotNull(agent.modelId), agent,
            listOf(ChatTurn("system", "Work."), ChatTurn("user", "Build it.")), session,
        )

        assertThat(said).isInstanceOf(ChatCompletion.Answered::class.java)
        val notes = events.findAll().filter { it.sessionId == session && it.kind == LlmSessionEventKind.SYSTEM }.mapNotNull { it.content }
        assertThat(notes).anyMatch { it.startsWith("The turn outgrew the model's window") }
        assertThat(notes).noneMatch { it.startsWith("The model answered with nothing") }
    }

    private fun called(id: String) =
        """{"choices":[{"message":{"role":"assistant","content":null,
           "tool_calls":[{"id":"$id","type":"function","function":{"name":"skill_list","arguments":"{}"}}]}}],
           "usage":{"prompt_tokens":7,"completion_tokens":2}}"""

    private fun answered(text: String) =
        """{"choices":[{"message":{"role":"assistant","content":"$text"}}],
           "usage":{"prompt_tokens":9,"completion_tokens":1}}"""

    private fun serve(): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val (status, body) = script.poll() ?: (200 to answered("Out of script."))
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
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
