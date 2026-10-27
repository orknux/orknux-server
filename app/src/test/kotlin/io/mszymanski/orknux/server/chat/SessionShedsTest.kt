package io.mszymanski.orknux.server.chat

import com.sun.net.httpserver.HttpServer
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.connector.model.ModelProviderRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.LlmSessionRepository
import io.mszymanski.orknux.server.llm.SessionPrompt
import io.mszymanski.orknux.server.task.Task
import io.mszymanski.orknux.server.task.TaskLoop
import io.mszymanski.orknux.server.task.TaskRepository
import io.mszymanski.orknux.server.watcher.Watcher
import io.mszymanski.orknux.server.watcher.WatcherConditionKind
import io.mszymanski.orknux.server.watcher.WatcherFollowUp
import io.mszymanski.orknux.server.watcher.WatcherRepository
import io.mszymanski.orknux.server.watcher.WatcherStatus
import io.mszymanski.orknux.server.schedule.ScheduleTools
import io.mszymanski.orknux.server.watcher.WatcherTools
import io.mszymanski.orknux.server.workflow.WorkflowEdgeRepository
import io.mszymanski.orknux.server.workflow.WorkflowNodeRepository
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import io.mszymanski.orknux.workflow.execution.ExecutionLogRepository
import io.mszymanski.orknux.workflow.execution.ExecutionStepRepository
import io.mszymanski.orknux.workflow.execution.WorkflowExecutionRepository
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
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
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Every kind of turn lends the same session tools. See [SessionSheds].
 *
 * Each door used to list them by hand, and the lists drifted: a chat's agent had
 * scratchpads, a timer and watchers, and no note, no to-do list and no clock,
 * while the same agent in a workflow step had all of them. So this runs one
 * agent, with every built-in granted, through each door there is - a workflow
 * step, a task, a watcher's follow-up, a chat and an agent asked by another -
 * and reads what reached the model: the session's tools are in every request, in
 * the same order, and each door adds only what is its own.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class SessionShedsTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val sessionSheds: SessionSheds,
    @Autowired val chats: ChatSessionRepository,
    @Autowired val history: ChatMemoryRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val models: LlmModelRepository,
    @Autowired val providers: ModelProviderRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val sessions: LlmSessionRepository,
    @Autowired val events: LlmSessionEventRepository,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val prompts: SessionPrompt,
    @Autowired val tasks: TaskRepository,
    @Autowired val taskGrants: io.mszymanski.orknux.server.task.TaskGrantRepository,
    @Autowired val taskRequests: io.mszymanski.orknux.server.task.TaskRequestRepository,
    @Autowired val taskMessages: io.mszymanski.orknux.server.task.TaskMessageRepository,
    @Autowired val loop: TaskLoop,
    @Autowired val watchers: WatcherRepository,
    @Autowired val followUp: WatcherFollowUp,
    @Autowired val asks: AgentRunTools,
    @Autowired val logs: ExecutionLogRepository,
    @Autowired val steps: ExecutionStepRepository,
    @Autowired val executions: WorkflowExecutionRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val workflows: WorkflowRepository,
) {

    private var workspaceId: Long = 0
    private lateinit var server: HttpServer
    private val received = CopyOnWriteArrayList<String>()
    private val mapper = ObjectMapper()

    @BeforeEach
    fun reset() {
        chats.findAll().forEach { history.deleteByConversationId(it.conversationId) }
        chats.deleteAll()
        watchers.deleteAll()
        taskGrants.deleteAll()
        taskMessages.deleteAll()
        taskRequests.deleteAll()
        tasks.deleteAll()
        logs.deleteAll()
        steps.deleteAll()
        executions.deleteAll()
        nodes.deleteAll()
        edges.deleteAll()
        assignments.deleteAll()
        workflows.deleteAll()
        events.deleteAll()
        sessions.deleteAll()
        agents.deleteAll()
        models.deleteAll()
        providers.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "backend")).id)
        received.clear()
    }

    @AfterEach
    fun stop() = server.stop(0)

    @Test
    fun `every door lends the same session tools, and only its own besides`() {
        val modelId = model(serve())
        val agentId = agent("Worker", modelId)

        // The set itself, pinned: a tool added to it is a decision this names.
        val session = recorder.open(workspaceId, "pin", "pin")
        val shared = requireNotNull(sessionSheds.of(requireNotNull(agents.findByIdOrNull(agentId)), session))
            .specs().map { it.name }
        assertThat(shared).containsExactlyInAnyOrder(
            NoteTools.NOTE,
            ScratchpadTools.LIST, ScratchpadTools.READ, ScratchpadTools.WRITE, ScratchpadTools.APPEND,
            ScratchpadTools.REPLACE, ScratchpadTools.SEARCH, ScratchpadTools.KEEP, ScratchpadTools.DELETE,
            ZipTools.ZIP_FILES, ZipTools.ZIP_EXTRACT,
            TodoTools.ADD, TodoTools.LIST, TodoTools.REORDER, TodoTools.NOTE, TodoTools.COMPLETE,
            DateTools.NOW, LinkTools.LINK,
            TimerTools.SET, TimerTools.LIST, TimerTools.CANCEL,
            WatcherTools.SET, WatcherTools.UPDATE, WatcherTools.LIST, WatcherTools.FINISH,
            ScheduleTools.SET, ScheduleTools.LIST, ScheduleTools.CANCEL,
            // Session ratings are on until an administrator turns them off.
            io.mszymanski.orknux.server.rating.RatingTools.RATE,
            io.mszymanski.orknux.server.rating.RatingTools.REMOVE,
            // Flagging the conversation for the installation's administrators.
            io.mszymanski.orknux.server.flag.SessionFlagTools.FLAG,
            io.mszymanski.orknux.server.flag.SessionFlagTools.UNFLAG,
        )

        val offered = linkedMapOf(
            "step" to offeredBy { step(agentId) },
            "task" to offeredBy { task(agentId, modelId) },
            "watcher" to offeredBy { watcherTurn(agentId) },
            "chat" to offeredBy { chat(agentId) },
            "subagent" to offeredBy { subagent(agentId, modelId) },
        )

        offered.forEach { (door, names) ->
            assertThat(names.filter { it in shared })
                .describedAs("the session's tools a %s lends, in the order every door lends them", door)
                .containsExactlyElementsOf(shared)
        }

        // And what is each door's own.
        assertThat(offered.getValue("step")).contains(FINISH).doesNotContain(TASK_DONE)
        assertThat(offered.getValue("watcher")).contains(FINISH).doesNotContain(TASK_DONE)
        assertThat(offered.getValue("task")).contains(TASK_DONE).doesNotContain(FINISH)
        assertThat(offered.getValue("chat")).doesNotContain(FINISH, TASK_DONE)
        assertThat(offered.getValue("subagent")).doesNotContain(FINISH, TASK_DONE)
    }

    @Test
    fun `a chat's agent keeps a to-do list, and its open items come back to it`() {
        val agentId = agent("Worker", model(serve()))
        val chatId = chatWith(agentId)

        send(chatId, "Plan the release")
        val first = mapper.readTree(received.first())
        assertThat(toolNames(first)).contains(TodoTools.ADD, TodoTools.COMPLETE, NoteTools.NOTE, DateTools.NOW)
        // And told when the list is worth keeping, beside how a turn works.
        assertThat(first.path("messages").first().path("content").asString())
            .contains(AgentBriefing.HOW_YOU_RUN + " " + AgentBriefing.PLAN_WITH_TODOS)

        /*
         * The conversation rebuilt from the log, as it is when what was sent
         * outgrew its ceiling or a compaction summarised it: the open item is
         * put back at its head, after the system turn and never in it.
         */
        val session = requireNotNull(chats.findByIdOrNull(chatId)?.llmSessionId)
        prompts.reset(session)
        received.clear()
        send(chatId, "What is left?")

        val messages = mapper.readTree(received.first()).path("messages")
        assertThat(messages[0].path("role").asString()).isEqualTo("system")
        assertThat(messages[0].path("content").asString()).doesNotContain("Write the changelog")
        assertThat(messages[1].path("role").asString()).isEqualTo("user")
        assertThat(messages[1].path("content").asString())
            .startsWith("Your to-do list for this job")
            .contains("Write the changelog")
    }

    @Test
    fun `a chat's agent with the to-do list hidden is neither offered it nor told about it`() {
        val agentId = agent("Worker", model(serve()))
        val agent = requireNotNull(agents.findByIdOrNull(agentId))
        agent.hiddenTools = mutableListOf(TodoTools.ADD)
        agents.save(agent)

        send(chatWith(agentId), "Hello")

        val asked = mapper.readTree(received.first())
        assertThat(toolNames(asked)).doesNotContain(TodoTools.ADD).contains(NoteTools.NOTE, TodoTools.COMPLETE)
        assertThat(asked.path("messages").first().path("content").asString())
            .contains(AgentBriefing.HOW_YOU_RUN)
            .doesNotContain(AgentBriefing.PLAN_WITH_TODOS)
    }

    /** The tools the first request [door] made offered, by name and in order. */
    private fun offeredBy(door: () -> Unit): List<String> {
        received.clear()
        door()
        await().atMost(Duration.ofSeconds(15)).until { received.isNotEmpty() }
        return toolNames(mapper.readTree(received.first()))
    }

    private fun toolNames(request: JsonNode): List<String> =
        request.path("tools").let { tools -> (0 until tools.size()).map { tools.get(it).path("function").path("name").asString() } }

    private fun step(agentId: Long) {
        val workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "Doors" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [
                  { key: "talk", kind: SESSION, name: "the conversation", x: 0, y: 0,
                    mappings: [
                      { name: "sessionKeyPrefix", expression: "node", mode: VALUE },
                      { name: "sessionKey", expression: "one", mode: VALUE }
                    ] },
                  { key: "think", kind: AGENT, name: "Worker", agentId: $agentId, x: 200, y: 0 }
                ],
                edges: [{ source: "talk", target: "think" }]
              }) { nodes { key } }
            }
            """,
        ).execute()
        graphQlTester.document(
            """mutation { startExecution(workspaceId: $workspaceId, workflowId: $workflowId, input: "{}") { id } }""",
        ).execute().path("startExecution.id").hasValue()
    }

    private fun task(agentId: Long, modelId: Long) {
        val task = tasks.save(
            Task(
                workspaceId = workspaceId,
                title = "Build the report",
                prompt = "Write the report.",
                agentId = agentId,
                modelId = modelId,
                createdBy = "alice",
                turnsAllowed = 1,
                secondsAllowed = 600,
            ),
        )
        val id = requireNotNull(task.id)
        task.sessionId = recorder.open(workspaceId, "task", id.toString())
        recorder.userSaid(requireNotNull(task.sessionId), "alice", "Write the report.")
        tasks.save(task)
        loop.advance(id)
    }

    private fun watcherTurn(agentId: Long) {
        val session = recorder.open(workspaceId, "watched", "one")
        val now = OffsetDateTime.now()
        watchers.save(
            Watcher(
                workspaceId = workspaceId,
                sessionId = session,
                agentId = agentId,
                agentName = "Worker",
                tool = "http_get",
                arguments = "{}",
                conditionKind = WatcherConditionKind.REGEX,
                condition = "green",
                intervalSeconds = 60,
                timeoutSeconds = 600,
                status = WatcherStatus.FIRED,
                expiresAt = now.plusMinutes(10),
                nextCheckAt = now.plusMinutes(1),
                lastCheckedAt = now,
            ),
        )
        followUp.follow(session)
    }

    private fun chat(agentId: Long) = send(chatWith(agentId), "Hello")

    private fun subagent(agentId: Long, modelId: Long) {
        val asker = requireNotNull(agents.findByIdOrNull(agent("Asker", modelId)))
        asker.agents = mutableListOf(agentId)
        agents.save(asker)
        val parent = recorder.open(workspaceId, "asking", "one")
        asks.run(asker, """{"agent":"Worker","question":"Count to three"}""", parent)
    }

    private fun send(chatId: Long, text: String) {
        graphQlTester.document("""mutation { sendChatMessage(id: $chatId, text: "$text") { millis } }""")
            .execute().path("sendChatMessage.millis").hasValue()
    }

    /**
     * Adds an item to the to-do list when asked to plan, and otherwise answers.
     * Plain JSON whatever was asked for, which a provider may answer a
     * streaming request with.
     */
    private fun serve(): String {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/chat/completions") { exchange ->
            val body = exchange.requestBody.reader(StandardCharsets.UTF_8).use { it.readText() }
            received += body
            // Read off the conversation, not the whole body: a tool's description may say anything.
            val said = mapper.readTree(body).path("messages").toString()
            val reply = if (said.contains("Plan the release") && !said.contains("\"tool_call_id\"") &&
                !said.contains("What is left")
            ) {
                """
                {"choices":[{"message":{"role":"assistant","content":null,
                  "tool_calls":[{"id":"call_1","type":"function","function":{"name":"${TodoTools.ADD}",
                    "arguments":"{\"text\":\"Write the changelog\"}"}}]}}],
                 "usage":{"prompt_tokens":9,"completion_tokens":4}}
                """.trimIndent()
            } else {
                """{"choices":[{"message":{"role":"assistant","content":"Done."}}],"usage":{"prompt_tokens":9,"completion_tokens":4}}"""
            }.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
            exchange.close()
        }
        server.start()
        return "http://${server.address.hostString}:${server.address.port}"
    }

    private fun model(endpoint: String): Long {
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub", endpoint: "$endpoint", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        return graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Stub", modelId: "stub", kind: CHAT }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
    }

    /** An agent as one is made: every built-in on, and nothing hidden. */
    private fun agent(name: String, modelId: Long): Long {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "$name", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        graphQlTester.document("""mutation { updateAgent(id: $id, input: { name: "$name", modelId: $modelId }) { id } }""")
            .execute()
        return id
    }

    private fun chatWith(agentId: Long): Long {
        val chatId = graphQlTester.document(
            """mutation { startChat(input: { workspaceId: $workspaceId, title: "Doors" }) { id } }""",
        ).execute().path("startChat.id").entity(Long::class.java).get()
        graphQlTester.document("""mutation { chooseChatAgent(id: $chatId, agentId: $agentId) { id } }""")
            .execute().path("chooseChatAgent.id").hasValue()
        return chatId
    }

    private companion object {
        const val FINISH = "finish_answer"
        const val TASK_DONE = "task_done"
    }
}
