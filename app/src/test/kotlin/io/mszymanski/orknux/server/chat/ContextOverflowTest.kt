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
import io.mszymanski.orknux.server.llm.LlmSessionStore
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.script.StoredKind
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
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A file of any size handed to an agent ends in a turn that fits its model's
 * window - cut, recorded or summarised - and never in a failed turn.
 *
 * Chat 981: `svg_read` on a 476,668-character KiCad copper layer went into the
 * turn whole, llama.cpp refused the next request at 452,827 tokens against a
 * window of 262,144, two compactions kept that answer "word for word", and the
 * turn failed. The SVG here is the head of that kind of file
 * (`context-overflow/copper-layer.svg`) with its path data repeated to size.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ContextOverflowTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val conversation: AgentConversation,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val events: LlmSessionEventRepository,
    @Autowired val store: LlmSessionStore,
    @Autowired val settings: InstallationSettings,
    @Autowired val agents: AgentRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer
    private val mapper = ObjectMapper()

    /** What the stub answers, in order, and every request it was sent. */
    private val script = ConcurrentLinkedQueue<Pair<Int, String>>()
    private val requests = CopyOnWriteArrayList<String>()
    private var keptBefore = 0
    private var attemptsBefore = 0

    @BeforeEach
    fun reset() {
        agents.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "overflow")).id)
        script.clear()
        requests.clear()
        keptBefore = settings.sessionCompactionKeepTurns()
        attemptsBefore = settings.sessionCompactionAttempts()
        settings.setSessionCompactionKeepTurns(2, "alice")
    }

    @AfterEach
    fun stop() {
        settings.setSessionCompactionKeepTurns(keptBefore, "alice")
        settings.setSessionCompactionAttempts(attemptsBefore, "alice")
        server.stop(0)
    }

    @Test
    fun `a tool answering two million characters is cut to its share of the model's window`() {
        val (agent, session) = setUp(window = 262_144)
        keep(session, svg(2_000_000))
        script += 200 to called("call_1", "svg_read", """{"key":"pcb.svg"}""")
        script += 200 to answered("Done.")

        val said = conversation.answer(requireNotNull(agent.modelId), agent, asked(), session)

        assertThat(said).isInstanceOf(ChatCompletion.Answered::class.java)
        // 25% of 262,144 tokens, at two characters a token.
        val tool = toolAnswers(requests[1]).single()
        assertThat(tool.length).isLessThanOrEqualTo(131_072)
        assertThat(tool).contains("trimmed from ")
    }

    @Test
    fun `an SVG cut to fit says how long it was, where the rest is and how to look at it`() {
        val (agent, session) = setUp(window = 262_144)
        keep(session, svg(476_668))
        script += 200 to called("call_1", "svg_read", """{"key":"pcb.svg"}""")
        script += 200 to answered("Done.")

        conversation.answer(requireNotNull(agent.modelId), agent, asked(), session)

        val tool = toolAnswers(requests[1]).single()
        val svg = mapper.readTree(tool).path("svg").stringValue()
        assertThat(svg).startsWith("<?xml")
        assertThat(mapper.readTree(tool).path("characters").asInt()).isEqualTo(476_668)
        assertThat(svg.substringAfter("… ")).isEqualTo(
            "(trimmed from 476668 characters; the whole answer is kept under the key \"result.call_1\" for any " +
                "tool that takes a key; it is an SVG, which is better looked at than read: svg_toPng with key " +
                "\"pcb.svg\" draws it as a picture, and picture_view shows you a picture by its key)",
        )
        assertThat(store.get(session, "result.call_1")).isNotNull()
    }

    @Test
    fun `a turn whose newest step alone is over the window is cut to fit and carries on`() {
        // No window on the model's row: the refusal is the first anybody hears of it.
        val (agent, session) = setUp(window = null)
        keep(session, svg(476_668))
        script += 200 to called("call_1", "svg_read", """{"key":"pcb.svg"}""")
        script += 400 to overflow(452_827, 262_144)
        script += 200 to answered("Done.")

        val said = conversation.answer(requireNotNull(agent.modelId), agent, asked(), session)

        assertThat(said).isInstanceOf(ChatCompletion.Answered::class.java)
        val first = toolAnswers(requests[1]).single()
        val retried = toolAnswers(requests[2]).single()
        assertThat(first.length).isGreaterThan(476_668)
        // What was sent, at the rate the refusal gave, less the room kept for the answer.
        assertThat(messageChars(requests[2])).isLessThanOrEqualTo(messageChars(requests[1]) * 262_144L / 452_827 * 3 / 4)
        assertThat(retried).contains("trimmed from ").startsWith("{\"svg\":\"<?xml")
        assertThat(notes(session)).anyMatch { it.startsWith("The turn outgrew the model's window, so 1 of its longest step was cut to fit") }
    }

    /**
     * A copper layer's path data has no gist, so an older answer that long is
     * not handed to the summariser - which would be a second request over the
     * window - but kept as one line saying what it was.
     */
    @Test
    fun `an older oversized answer becomes a one-line record, not a summary`() {
        val (agent, session) = setUp(window = null)
        keep(session, svg(476_668))
        script += 200 to called("call_1", "svg_read", """{"key":"pcb.svg"}""")
        script += 200 to called("call_2", "skill_list", "{}")
        script += 400 to overflow(452_900, 262_144)
        script += 200 to answered("The SVG was read and the skills listed.")
        script += 200 to answered("Done.")

        val said = conversation.answer(requireNotNull(agent.modelId), agent, asked(), session)

        assertThat(said).isInstanceOf(ChatCompletion.Answered::class.java)
        val summariser = requests[3]
        assertThat(summariser.length).isLessThan(20_000)
        assertThat(summariser).contains("svg_read {\\\"key\\\":\\\"pcb.svg\\\"}: answered ")
        val retried: List<String> = mapper.readTree(requests[4]).path("messages").iterator().asSequence().map { it.path("content").toString() }.toList()
        assertThat(retried).anyMatch {
            it.contains("Long answers in those steps, as they were:") &&
                it.contains("svg_read {\\\"key\\\":\\\"pcb.svg\\\"}: answered ") &&
                it.contains("left out of this turn to fit the model's window")
        }
        assertThat(messageChars(requests[4])).isLessThan(20_000)
    }

    @Test
    fun `a context refusal after the last compaction is met by one harder compaction and the turn answers`() {
        settings.setSessionCompactionAttempts(1, "alice")
        val (agent, session) = setUp(window = null)
        script += 200 to called("call_1", "skill_list", "{}")
        script += 200 to called("call_2", "skill_list", "{}")
        script += 200 to called("call_3", "skill_list", "{}")
        script += 400 to overflow(69_015, 65_536)
        script += 200 to answered("Three lookups listed the skills.")
        script += 400 to overflow(66_000, 65_536)
        script += 200 to answered("The skills were listed three times.")
        script += 200 to answered("Done.")

        val said = conversation.answer(requireNotNull(agent.modelId), agent, asked(), session)

        assertThat(said).isInstanceOf(ChatCompletion.Answered::class.java)
        assertThat((said as ChatCompletion.Answered).content).isEqualTo("Done.")
        assertThat(notes(session).filter { it.startsWith("The turn outgrew the model's window") }).hasSize(2)
        assertThat(notes(session)).noneMatch { it.contains("could not answer") }
    }

    private fun setUp(window: Int?): Pair<io.mszymanski.orknux.server.agent.Agent, Long> {
        val agentId = agent("Reader", model(serve(), window))
        val agent = requireNotNull(agents.findByIdOrNull(agentId))
        return agent to recorder.open(workspaceId, "test", "overflow-${System.nanoTime()}")
    }

    private fun asked() = listOf(ChatTurn("system", "Work."), ChatTurn("user", "Analyse the copper layer."))

    private fun keep(session: Long, svg: String) {
        assertThat(store.put(session, "pcb.svg", mapper.writeValueAsString(svg), StoredKind("image/svg+xml", false))).isNull()
    }

    /** The copper layer's head, its path data repeated until it is [length] characters. */
    private fun svg(length: Int): String {
        val head = requireNotNull(javaClass.getResource("/context-overflow/copper-layer.svg")).readText()
        val close = head.lastIndexOf("</g>")
        val out = StringBuilder(head.substring(0, close))
        var x = 0
        val tail = head.substring(close)
        while (out.length + tail.length < length) {
            out.append("<path style=\"fill:#000000; fill-opacity:1.000000; stroke:none;fill-rule:evenodd;\"\r\n")
                .append("d=\"M ").append(10 + x % 80).append(".385000,28.650000\r\n")
                .append("13.385000,30.350000\r\n11.685000,30.350000\r\nZ\" /> \r\n")
            x += 1
        }
        return (out.toString() + tail).take(length - tail.length).let { it + tail }.take(length)
    }

    private fun notes(session: Long): List<String> =
        events.findAll().filter { it.sessionId == session && it.kind == LlmSessionEventKind.SYSTEM }.mapNotNull { it.content }

    private fun toolAnswers(request: String): List<String> =
        mapper.readTree(request).path("messages").filter { it.path("role").stringValue() == "tool" }
            .map { it.path("content").let { c -> if (c.isString) c.stringValue() else c.toString() } }

    private fun messageChars(request: String): Long =
        mapper.readTree(request).path("messages").sumOf { it.path("content").let { c -> if (c.isString) c.stringValue().length else c.toString().length }.toLong() }

    private fun overflow(requested: Int, window: Int) =
        """{"error":{"code":400,"message":"request ($requested tokens) exceeds the available context size ($window tokens), try increasing it","type":"exceed_context_size_error"}}"""

    private fun called(id: String, tool: String, arguments: String) =
        """{"choices":[{"message":{"role":"assistant","content":null,
           "tool_calls":[{"id":"$id","type":"function","function":{"name":"$tool","arguments":${mapper.writeValueAsString(arguments)}}}]}}],
           "usage":{"prompt_tokens":7,"completion_tokens":2}}"""

    private fun answered(text: String) =
        """{"choices":[{"message":{"role":"assistant","content":"$text"}}],
           "usage":{"prompt_tokens":9,"completion_tokens":1}}"""

    private fun serve(): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            requests += exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
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

    private fun model(endpoint: String, window: Int?): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub", endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        val windowed = window?.let { ", contextWindow: $it" } ?: ""
        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT$windowed })
               { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }
}
