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
import tools.jackson.databind.ObjectMapper
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/**
 * That the lookups a chat draws while they are happening say the same thing as
 * the lookups it reads back afterwards.
 *
 * Issue #291, and the second half of #289. That one took the credentials out of
 * what is *stored*: `LlmSessionRecorder` strips a tool call's arguments before
 * the row is saved, so a `git push https://alice:s3cr3t@host/repo.git` reads
 * `alice:***@host` on the page a reload draws. The same fact leaves the round by
 * a second road - `RoundWatch`, which this endpoint forwards straight to the
 * browser - and that one carried the password in full. One command, on one
 * screen, reading two different ways depending on whether you were watching when
 * it ran.
 *
 * **Everything here is asserted on the frames**, decoded out of the response the
 * way the browser's own parser would. That is the point: the previous change
 * passed every test it had, because every one of them looked at the row. A
 * function returning the right string is not the claim - what arrives on the
 * wire is.
 *
 * The two strengths are held apart, and the second half is the half that would
 * go unnoticed if it broke. Arguments are a command line and take the full rule
 * set. Results are arbitrary output - a build log, a `--help` - and take only
 * what is a credential on sight, because a live view that showed
 * `cannot find symbol ***` while the stored copy read correctly would be this
 * bug with the sides swapped. And the last test is the guard on all of it: the
 * model goes on being handed the real command, or the fix has broken the work it
 * was protecting.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ChatStreamRedactionTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val streaming: ChatStreamAPI,
    @Autowired val mapper: ObjectMapper,
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

    /** Every body the stub was sent, which is everything the model was told. */
    private val sent = CopyOnWriteArrayList<String>()

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
        sent.clear()
    }

    @AfterEach
    fun stop() = server.stop(0)

    /**
     * A password in a URL never reaches the screen, and the rest of the command
     * does.
     *
     * The exact command back, with one substring replaced. Not
     * `doesNotContain("s3cr3t")` alone: a fix that sent `***` and nothing else
     * would pass that and leave nobody able to see what their agent ran, which
     * is what the audit log's marker exists to avoid.
     */
    @Test
    fun `a credential in a call's arguments is redacted before the frame is sent`() {
        val chatId = chatCalling("""{"command":"git push https://alice:s3cr3t@github.com/acme/repo.git main"}""")

        val frames = stream(chatId, "Push it")

        assertThat(argumentsOf(frames))
            .isEqualTo("""{"command":"git push https://alice:***@github.com/acme/repo.git main"}""")
        assertThat(frames).doesNotContain("s3cr3t")
    }

    /**
     * And a token a command printed never reaches it either.
     *
     * `env` is the ordinary way this happens, and the shape is one of the few a
     * redactor can be sure about with no flag in front of it. The line it stands
     * on is kept, because which variable held the token is what the reader needs
     * and is not itself a secret.
     */
    @Test
    fun `a token in a tool's result is redacted before the frame is sent`() {
        val chatId = chatCalling("""{"command":"env"}""", printing = ENVIRONMENT)

        val frames = stream(chatId, "What is in the environment?")

        assertThat(resultOf(frames)).contains("GITHUB_TOKEN=***")
        assertThat(resultOf(frames)).doesNotContain("ghp_")
        assertThat(frames).doesNotContain("ghp_")
    }

    /**
     * An ordinary command line arrives exactly as the model wrote it.
     *
     * Over-redacting the live view would be its own bug and a worse one to find:
     * the screen would be quietly wrong about what an agent ran, and nothing
     * would look broken. Every argument here is one the full rule set is entitled
     * to touch if it is careless - `-am` is a short flag, `test` a bare
     * positional - and none of them is a credential.
     */
    @Test
    fun `an ordinary command line reaches the frame byte for byte`() {
        val arguments = """{"command":"mvn -pl app -am test -Dtest=ChatStreamRedactionTest"}"""
        val chatId = chatCalling(arguments)

        val frames = stream(chatId, "Run the tests")

        assertThat(argumentsOf(frames)).isEqualTo(arguments)
    }

    /**
     * And so does an ordinary build log.
     *
     * The one that would break the product rather than leak from it. Every line
     * carries a word the full rule set would replace, and the model is shown this
     * text on the screen beside the copy it is reading to fix the build - a
     * reader watching `cannot find symbol ***` scroll past cannot help it.
     */
    @Test
    fun `an ordinary build log reaches the frame byte for byte`() {
        val chatId = chatCalling("""{"command":"mvn test"}""", printing = BUILD_LOG)

        val frames = stream(chatId, "Why is it failing?")

        val shown = resultOf(frames)
        assertThat(shown).contains("[ERROR] Db.kt:14:22: cannot find symbol: password")
        assertThat(shown).contains("[INFO] --token TEXT is not a recognised option")
        assertThat(shown).contains("spring.datasource.password=hunter2")
        assertThat(shown).contains("AWS_SECRET_ACCESS_KEY is unset")
        assertThat(shown).doesNotContain("***")
    }

    /**
     * The model still gets the real command and the real output.
     *
     * The guard on every test above, and the reason the redaction is at the fork
     * rather than over the round. `AgentConversation` threads the call and its
     * result back into the conversation untouched, so the second request the
     * provider is sent carries both in full - the agent can act on what it ran,
     * and #289's whole design is redacting the record rather than the work. A fix
     * that redacted the conversation would pass all four tests above and leave an
     * agent unable to push, with the reason invisible on every page.
     */
    @Test
    fun `the model is still handed the command and the output as they were`() {
        val chatId = chatCalling(
            """{"command":"git push https://alice:s3cr3t@github.com/acme/repo.git main"}""",
            printing = ENVIRONMENT,
        )

        val frames = stream(chatId, "Push it")

        // Nothing on the wire, in either direction.
        assertThat(frames).doesNotContain("s3cr3t").doesNotContain("ghp_")

        // And both in front of the model on the round that followed: the call it
        // made, on its own turn, and what the tool answered. Found by the
        // `tool_call_id` that only a round answering a tool carries, rather than
        // by counting the requests - naming a chat is a model call too.
        val afterTheTool = sent.single { it.contains("tool_call_id") }
        assertThat(afterTheTool).contains("alice:s3cr3t@github.com")
        assertThat(afterTheTool).contains("ghp_ZmFrZXRva2VuZm9yYXRlc3Q0Mg")
    }

    /**
     * The page a call named rides on its `called` frame, so the line links to
     * it while the turn is still going. Issue #660.
     */
    @Test
    fun `a call that named a page of this installation carries the link on its frame`() {
        val chatId = chatCalling(
            """{"command":"start"}""",
            returning = "{ started: 12, workflow: 'Nightly export', url: '/workspace/$workspaceId/executions/12' }",
        )

        val frames = stream(chatId, "Start the export")

        val link = linkOf(frames)
        assertThat(link.path("url").asString()).isEqualTo("/workspace/$workspaceId/executions/12")
        assertThat(link.path("name").asString()).isEqualTo("Nightly export")
    }

    @Test
    fun `a call that named nothing carries no link`() {
        val chatId = chatCalling("""{"command":"env"}""")

        assertThat(linkOf(stream(chatId, "Run it")).isNull).isTrue()
    }

    /* ------------------------------------------------------------- the frames */

    /** The page the `called` frame says the call named. */
    private fun linkOf(frames: String) = mapper.readTree(
        frames.split(BLANK).single { it.startsWith("event: called\n") }.substringAfter("data: "),
    ).path("link")


    /** What the `call` frame said the agent asked for. */
    private fun argumentsOf(frames: String): String = field(frames, "call", "arguments")

    /** And what the `called` frame said it got back. */
    private fun resultOf(frames: String): String = field(frames, "called", "result")

    /**
     * One field off one frame, decoded the way a browser's parser decodes it.
     *
     * The single frame of that name, rather than the first: a test whose round
     * quietly made two lookups and asserted on one of them would be reporting
     * about half of what reached the screen.
     */
    private fun field(frames: String, event: String, name: String): String {
        val frame = frames.split(BLANK)
            .single { it.startsWith("event: $event\n") }
            .substringAfter("data: ")
        return requireNotNull(mapper.readTree(frame).path(name).asString())
    }

    /**
     * The streaming door, run to the end and read back.
     *
     * The controller is called rather than a socket opened, for the reason
     * `ChatDrawingTest` gives: what is at issue is what the frames carry, not the
     * wire, which `TaskStreamAPITest` covers over real HTTP. The frames go to the
     * response rather than to the stream the body is handed, which is
     * `ServerSentEvents`' whole argument, so that is where they are read from.
     *
     * Nothing is stripped out of them. The other readers of this pattern squeeze
     * the spaces out to make substring matching easier; here the exact bytes are
     * half of what is being asserted.
     */
    private fun stream(chatId: Long, said: String): String {
        val response = MockHttpServletResponse()
        streaming.stream(chatId, ChatStreamRequest(said), response).writeTo(response.outputStream)
        return response.contentAsString
    }

    /* -------------------------------------------------------------- the stubs */

    /**
     * A chat whose agent calls one tool with the given arguments and then
     * answers.
     *
     * The tool is the workspace's own JavaScript, which is the shortest honest
     * way to have a tool whose output this test decides. It ignores what it is
     * handed: the arguments are the model's to compose and the stub composes
     * them, so what the tool does with them would only be a second place for this
     * to go wrong.
     *
     * @param printing what the tool answers with. Empty for the tests that are
     *   about the call rather than the result.
     */
    private fun chatCalling(arguments: String, printing: String = "Done.", returning: String? = null): Long {
        tool(printing, returning)
        val endpoint = serve { body ->
            if (body.contains("tool_call_id")) answered("There you go.") else calling(arguments)
        }
        return chatWithAgent(model(endpoint))
    }

    /**
     * The workspace tool the round calls.
     *
     * Its whole body is a constant, because what is being tested is what happens
     * to text on the way out of the round rather than anything the sandbox does
     * with it.
     */
    private fun tool(printing: String, returning: String? = null): Long = graphQlTester.document(
        """mutation { createTool(input: {
             workspaceId: $workspaceId, name: "run_command", description: "Runs a command",
             source: ${Q}export default function run_command(input) { return ${returning ?: "{ output: \"$printing\" }"}; }$Q,
             typescript: ${Q}export default function run_command(input) { return ${returning ?: "{ output: \"$printing\" }"}; }$Q
           }) { id } }""",
    ).execute().path("createTool.id").entity(Long::class.java).get()

    /**
     * A stub provider, answering in whichever shape it was asked in.
     *
     * A chat handed to an agent is read as a stream, which is the path this test
     * is about; the blocking shape is served too so that a round which does not
     * stream fails on what it is testing rather than on the stub.
     */
    private fun serve(answer: (String) -> Round): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            sent += body
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

    /** One round the stub is to produce, said once and rendered two ways. */
    private data class Round(val said: String = "", val tool: String? = null, val arguments: String = "{}")

    private fun calling(arguments: String) = Round(tool = "run_command", arguments = arguments)

    private fun answered(said: String) = Round(said = said)

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

    /* ----------------------------------------------------------- the fixtures */

    private fun chatWithAgent(modelId: Long): Long {
        val agentId = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Worker", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        graphQlTester.document(
            """mutation { updateAgent(id: $agentId, input: {
                 name: "Worker", modelId: $modelId, tools: ["run_command"]
               }) { id } }""",
        ).execute()

        val chatId = graphQlTester.document(
            """mutation { startChat(input: { workspaceId: $workspaceId, title: "Work" }) { id } }""",
        ).execute().path("startChat.id").entity(Long::class.java).get()
        graphQlTester.document("""mutation { chooseChatAgent(id: $chatId, agentId: $agentId) { id } }""")
            .execute().path("chooseChatAgent.id").hasValue()
        return chatId
    }

    private fun model(endpoint: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub", type: OPENAI, endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()

        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT })
               { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }

    private companion object {
        /** What ends one server-sent frame: the blank line the protocol separates them with. */
        const val BLANK = "\n\n"

        /** The GraphQL block-string delimiter, which Kotlin will not write plainly. */
        const val Q = "\"\"\""

        /**
         * What `env` prints, as one JavaScript string literal.
         *
         * The token is a shape the narrow pass knows on sight, which is what
         * makes it findable in output at all - and the two variables around it
         * are there so the assertion that the line survived is an assertion
         * about a transcript rather than about one word.
         */
        const val ENVIRONMENT =
            "HOME=/home/alice\\nGITHUB_TOKEN=ghp_ZmFrZXRva2VuZm9yYXRlc3Q0Mg\\nSHELL=/bin/sh"

        /**
         * And what a failing build prints. Every line carries a word the full
         * rule set would replace, and none of them is a credential.
         */
        const val BUILD_LOG =
            "[INFO] Scanning for projects...\\n" +
                "[ERROR] Db.kt:14:22: cannot find symbol: password\\n" +
                "[INFO] --token TEXT is not a recognised option\\n" +
                "spring.datasource.password=hunter2\\n" +
                "[WARNING] AWS_SECRET_ACCESS_KEY is unset\\n" +
                "[INFO] BUILD FAILURE"
    }
}
