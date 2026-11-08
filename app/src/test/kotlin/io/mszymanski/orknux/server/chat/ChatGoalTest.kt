package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A goal: the chat's agent works turn after turn until a reviewer judges the
 * condition met, out of turns, or stopped.
 *
 * Driven against a stub provider that answers the worker and the reviewer
 * apart by the reviewer's instructions, with the verdicts scripted per test,
 * so nothing here waits on anything but the chat's own wake-ups.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ChatGoalTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val goals: ChatGoalRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0
    private var agentId: Long = 0
    private var modelId: Long = 0
    private var otherModelId: Long = 0
    private lateinit var server: HttpServer

    /** What the worker was sent, one body per call. */
    private val workerHeard = CopyOnWriteArrayList<String>()
    private val worked = AtomicInteger()

    /** The verdicts the reviewer gives, in turn; the last repeats. */
    private var verdicts: List<String> = emptyList()
    private val reviewed = AtomicInteger()

    @BeforeEach
    fun make() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "goals-${System.nanoTime()}")).id)
        serve()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `met on the second turn, after one round of feedback that reaches the worker`() {
        verdicts = listOf(
            """{"met": false, "feedback": "Add a title to the summary."}""",
            """{"met": true, "feedback": "It has a title now."}""",
        )
        val chatId = chat()

        start(chatId, maxIterations = 5)

        await().atMost(Duration.ofSeconds(30)).until { status(chatId) == "MET" }
        val goal = goals.findFirstByChatIdOrderByIdDesc(chatId)!!
        assertThat(goal.iteration).isEqualTo(2)
        assertThat(goal.lastFeedback).isEqualTo("It has a title now.")
        assertThat(worked.get()).isEqualTo(2)
        // The first turn was told the goal; the second, the reviewer's feedback.
        assertThat(workerHeard[0]).contains("Goal · iteration 1 of 5").contains("Summarise the incident")
        assertThat(workerHeard[1]).contains("Goal · iteration 2 of 5").contains("Add a title to the summary.")
        // The reviewer is dismissed once the goal is met.
        assertThat(agents.findTemporaryBySession(goal.llmSessionId)).isEmpty()

        graphQlTester.document("{ chatGoal(chatId: $chatId) { status iteration maxIterations lastFeedback } }").execute()
            .path("chatGoal.status").entity(String::class.java).isEqualTo("MET")
            .path("chatGoal.iteration").entity(Int::class.java).isEqualTo(2)
    }

    @Test
    fun `exhausted at its maximum, with the last feedback said`() {
        verdicts = listOf("""{"met": false, "feedback": "Still no numbers."}""")
        val chatId = chat()

        start(chatId, maxIterations = 2)

        await().atMost(Duration.ofSeconds(30)).until { status(chatId) == "EXHAUSTED" }
        val goal = goals.findFirstByChatIdOrderByIdDesc(chatId)!!
        assertThat(goal.iteration).isEqualTo(2)
        assertThat(goal.lastFeedback).isEqualTo("Still no numbers.")
        assertThat(worked.get()).isEqualTo(2)
        assertThat(reviewed.get()).isEqualTo(2)
    }

    @Test
    fun `stopped by the person, and its reviewer dismissed`() {
        verdicts = listOf("""{"met": false, "feedback": "Keep going."}""")
        val chatId = chat()
        start(chatId, maxIterations = 20)

        graphQlTester.document("mutation { stopChatGoal(chatId: $chatId) { status } }").execute()
            .path("stopChatGoal.status").entity(String::class.java).isEqualTo("STOPPED")

        val goal = goals.findFirstByChatIdOrderByIdDesc(chatId)!!
        assertThat(goal.status).isEqualTo(ChatGoalStatus.STOPPED)
        assertThat(agents.findTemporaryBySession(goal.llmSessionId)).isEmpty()
        // Whatever turn was already under way, no review starts another.
        await().pollDelay(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).until { true }
        assertThat(worked.get()).isLessThanOrEqualTo(1)
        assertThat(status(chatId)).isEqualTo("STOPPED")
    }

    @Test
    fun `a reviewer on a model the agent may not create agents with is refused in words`() {
        val chatId = chat()

        graphQlTester.document(
            """mutation { startChatGoal(chatId: $chatId, input: {
                 goal: "Summarise", condition: "It is short", reviewerModelId: $otherModelId
               }) { id } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors.single().message).contains("Other is not a model Worker may create agents with")
            assertThat(errors.single().extensions["code"]).isEqualTo("ChatGoalReviewerRefused")
        }
        assertThat(goals.findFirstByChatIdOrderByIdDesc(chatId)).isNull()
    }

    @Test
    fun `the agent sets itself a goal with goal_start where it may create agents`() {
        val chatId = chat()
        graphQlTester.document(
            """mutation { updateAgent(id: $agentId, input: { name: "Worker", modelId: $modelId, creatableModelIds: [$modelId] }) { id } }""",
        ).execute().path("updateAgent.id").hasValue()

        graphQlTester.document("{ builtInTools(workspaceId: $workspaceId) { name governance } }").execute()
            .path("builtInTools[?(@.name == 'goal_start')].governance").entityList(String::class.java)
            .containsExactly("CREATABLE_MODELS")
        assertThat(chatId).isPositive()
    }

    private fun start(chatId: Long, maxIterations: Int) {
        graphQlTester.document(
            """mutation { startChatGoal(chatId: $chatId, input: {
                 goal: "Summarise the incident", condition: "The summary has a title and numbers",
                 maxIterations: $maxIterations
               }) { status iteration } }""",
        ).execute().path("startChatGoal.status").entity(String::class.java).isEqualTo("RUNNING")
    }

    private fun status(chatId: Long): String? = goals.findFirstByChatIdOrderByIdDesc(chatId)?.status?.name

    private fun serve() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            val content = if (body.contains("You review another agent's work")) {
                val at = reviewed.getAndIncrement()
                verdicts[minOf(at, verdicts.size - 1)]
            } else {
                workerHeard += body
                "Summary, draft ${worked.incrementAndGet()}."
            }
            val reply = """{"choices":[{"message":{"role":"assistant","content":${quoted(content)}}}],
                "usage":{"prompt_tokens":9,"completion_tokens":4}}""".toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
            exchange.close()
        }
        server.start()
    }

    private fun quoted(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun chat(): Long {
        val endpoint = "http://${server.address.hostString}:${server.address.port}"
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub", endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        modelId = graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
        otherModelId = graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Other", modelId: "other", kind: CHAT }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
        agentId = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Worker", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        graphQlTester.document("""mutation { updateAgent(id: $agentId, input: { name: "Worker", modelId: $modelId }) { id } }""")
            .execute()
        val chatId = graphQlTester.document(
            """mutation { startChat(input: { workspaceId: $workspaceId, title: "Incident" }) { id } }""",
        ).execute().path("startChat.id").entity(Long::class.java).get()
        graphQlTester.document("""mutation { chooseChatAgent(id: $chatId, agentId: $agentId) { id } }""")
            .execute().path("chooseChatAgent.id").hasValue()
        return chatId
    }
}
