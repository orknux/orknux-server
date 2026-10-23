package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentTool
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.interaction.InteractionRequestRepository
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.SessionEventRepository
import io.mszymanski.orknux.server.revision.ComponentRevisionRepository
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * An agent lists what it could be granted, asks for some of it, and a person
 * accepts or refuses in the conversation; accepted, the grant is the agent's
 * for good.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class GrantRequestToolsTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val tools: AgentTools,
    @Autowired val agents: AgentRepository,
    @Autowired val workspaceTools: AgentToolRepository,
    @Autowired val requests: InteractionRequestRepository,
    @Autowired val inbox: SessionEventRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val lines: LlmSessionEventRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val revisions: ComponentRevisionRepository,
    @Autowired val sessions: LlmSessionRecorder,
    @Autowired val mapper: ObjectMapper,
    @Autowired val workspaceConnections: io.mszymanski.orknux.connector.connection.WorkspaceConnectionRepository,
    @Autowired val tasks: io.mszymanski.orknux.server.task.TaskRepository,
    @Autowired val briefing: AgentBriefing,
    @Autowired val boxRows: io.mszymanski.orknux.server.box.BoxRepository,
    @Autowired val boxTools: io.mszymanski.orknux.server.box.BoxTools,
) {

    private var workspaceId = 0L
    private var conversation = 0L
    private lateinit var agent: Agent

    @BeforeEach
    fun seed() {
        clean()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "grants-${System.nanoTime()}")).id)
        conversation = recorder.open(workspaceId, "chat", "help")
        workspaceTools.save(AgentTool(workspaceId = workspaceId, name = "lookupOrder", description = "Finds an order by its number. More words.", source = "", typescript = ""))
        agent = agents.save(
            Agent(workspaceId = workspaceId, name = "Helper", type = AgentType.LLM).apply {
                hiddenTools = mutableListOf(DateTools.NOW)
            },
        )
    }

    @AfterEach
    fun clean() {
        requests.deleteAll()
        workspaceConnections.deleteAll()
        boxRows.deleteAll()
        inbox.deleteAll()
        lines.deleteAll()
        revisions.deleteAll()
        agents.deleteAll()
        workspaceTools.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
    }

    private fun call(name: String, arguments: Map<String, Any?> = emptyMap()): JsonNode =
        mapper.readTree(tools.run(agents.findByIdOrNull(agent.id!!)!!, ToolCall("call", name, mapper.writeValueAsString(arguments)), conversation))

    private fun told(): List<String> = inbox.findAll().filter { it.sessionId == conversation }.map { it.body }

    private fun offered(): List<String> = tools.specsFor(agents.findByIdOrNull(agent.id!!)!!).map { it.name }

    private fun answer(option: String) = graphQlTester.document(
        "mutation { answerRequest(id: ${requests.findAll().single { it.chosen == null }.id}, option: \"$option\") { chosen outcome items { name kind } } }",
    ).execute()

    @Test
    fun `the agent is offered both, and the list names what it could be given with kind and line`() {
        assertThat(offered()).contains(GrantRequestTools.LIST, GrantRequestTools.ASK)

        val listed = call(GrantRequestTools.LIST).path("tools").values().associate { it.path("name").stringValue() to it }
        assertThat(listed[DateTools.NOW]?.path("kind")?.stringValue()).isEqualTo("built-in tool")
        assertThat(listed["lookupOrder"]?.path("kind")?.stringValue()).isEqualTo("workspace tool")
        assertThat(listed["lookupOrder"]?.path("description")?.stringValue()).isEqualTo("Finds an order by its number.")
        assertThat(listed["http_get"]?.path("kind")?.stringValue()).isEqualTo("built-in tool")
        // A built-in it already holds is not on it.
        assertThat(listed).doesNotContainKey(NoteTools.NOTE)
    }

    @Test
    fun `asking puts the tools to the person, and accepting grants them for good`() {
        val asked = call(GrantRequestTools.ASK, mapOf("tools" to listOf("lookupOrder", DateTools.NOW), "why" to "to answer about order 42"))
        assertThat(asked.path("status").stringValue()).isEqualTo("pending")
        // Not held until somebody says so.
        assertThat(agents.findByIdOrNull(agent.id!!)!!.tools).doesNotContain("lookupOrder")

        graphQlTester.document(
            "{ sessionRequests(sessionId: $conversation) { kind question items { name kind description } options { id } } }",
        ).execute()
            .path("sessionRequests[0].kind").entity(String::class.java).isEqualTo(GrantRequestTools.KIND)
            .path("sessionRequests[0].question").entity(String::class.java).satisfies { assertThat(it).contains("to answer about order 42") }
            .path("sessionRequests[0].items[0].name").entity(String::class.java).isEqualTo("lookupOrder")
            .path("sessionRequests[0].items[0].kind").entity(String::class.java).isEqualTo("workspace tool")
            .path("sessionRequests[0].items[1].name").entity(String::class.java).isEqualTo(DateTools.NOW)

        // The same ask again while it is open does not ask twice.
        call(GrantRequestTools.ASK, mapOf("tools" to listOf(DateTools.NOW, "lookupOrder"), "why" to "again"))
        assertThat(requests.findAll()).hasSize(1)

        answer(GrantRequestTools.ACCEPT)
            .path("answerRequest.outcome").entity(String::class.java).satisfies { assertThat(it).startsWith("Granted - lookupOrder") }

        val after = agents.findByIdOrNull(agent.id!!)!!
        assertThat(after.tools).contains("lookupOrder")
        assertThat(after.hiddenTools).doesNotContain(DateTools.NOW)
        // Lent by its shed, which the grant now lets through.
        assertThat(BuiltInTools.granted(after, DateTools.NOW)).isTrue()
        assertThat(told().single()).contains("alice granted you lookupOrder, ${DateTools.NOW} for good")
        assertThat(audit.findAll().map { it.message }).contains("Agent Helper granted tools lookupOrder, ${DateTools.NOW} (asked in chat)")
        assertThat(revisions.findAll().map { it.componentId }).contains(agent.id)
    }

    @Test
    fun `asked to, accepting also loads them into the conversation, and by default it does not`() {
        call(GrantRequestTools.ASK, mapOf("tools" to listOf("lookupOrder"), "why" to "orders"))
        answer(GrantRequestTools.ACCEPT)
        assertThat(sessions.toolsFound(conversation)).doesNotContain("lookupOrder")

        call(GrantRequestTools.ASK, mapOf("tools" to listOf(DateTools.NOW), "why" to "the time", "load" to true))
        val open = requests.findAll().single { it.chosen == null }
        assertThat(open.question).contains("and loaded now")
        graphQlTester.document("mutation { answerRequest(id: ${open.id}, option: \"${GrantRequestTools.ACCEPT_AND_LOAD}\") { outcome } }").execute()
        assertThat(sessions.toolsFound(conversation)).contains(DateTools.NOW)
        assertThat(told().last()).contains("${DateTools.NOW} is loaded already")
    }

    /** A connection is asked for by name and granted by id, as the agent's page holds it; nothing is loaded for it. */
    @Test
    fun `an agent may ask to use a connection, and accepting lets it`() {
        val jira = graphQlTester.document(
            """mutation { createWorkspaceConnection(input: {
                 workspaceId: $workspaceId, name: "Jira", type: SLACK, url: "https://hooks.slack.com", description: "The support desk. More."
               }) { id } }""",
        ).execute().path("createWorkspaceConnection.id").entity(Long::class.java).get()

        val listed = call(GrantRequestTools.LIST).path("tools").values().associate { it.path("name").stringValue() to it }
        assertThat(listed["Jira"]?.path("kind")?.stringValue()).isEqualTo("connection")
        assertThat(listed["Jira"]?.path("description")?.stringValue()).contains("The support desk.")

        call(GrantRequestTools.ASK, mapOf("tools" to listOf("Jira"), "why" to "to read ticket 7", "load" to true))
        answer(GrantRequestTools.ACCEPT_AND_LOAD)
            .path("answerRequest.outcome").entity(String::class.java).isEqualTo("Granted - Jira")

        assertThat(agents.findByIdOrNull(agent.id!!)!!.connections).containsExactly(jira)
        // A connection is not a tool: nothing was loaded under its name.
        assertThat(recorder.toolsFound(conversation)).doesNotContain("Jira")
        assertThat(call(GrantRequestTools.LIST).path("tools").values().map { it.path("name").stringValue() }).doesNotContain("Jira")
    }

    /** A box is asked for by its label, as the page grants it: one name for every box that carries it. */
    @Test
    fun `an agent may ask for a box, and accepting grants its label`() {
        boxRows.save(io.mszymanski.orknux.server.box.Box(name = "gpu-1", label = "gpu", address = "http://gpu-1.invalid"))
        boxRows.save(io.mszymanski.orknux.server.box.Box(name = "gpu-2", label = "gpu", address = "http://gpu-2.invalid"))
        // With no box, the box tools are not offered at all: asking is the only way to one.
        assertThat(boxTools.offered(agents.findByIdOrNull(agent.id!!)!!)).isFalse()

        val name = "${GrantRequestTools.BOX_PREFIX}gpu"
        val listed = call(GrantRequestTools.LIST).path("tools").values().associate { it.path("name").stringValue() to it }
        assertThat(listed[name]?.path("kind")?.stringValue()).isEqualTo("box")
        assertThat(listed[name]?.path("description")?.stringValue()).contains("gpu-1, gpu-2")

        call(GrantRequestTools.ASK, mapOf("tools" to listOf(name), "why" to "to run the tests", "load" to true))
        answer(GrantRequestTools.ACCEPT_AND_LOAD)
            .path("answerRequest.outcome").entity(String::class.java).isEqualTo("Granted - $name")

        val after = agents.findByIdOrNull(agent.id!!)!!
        assertThat(after.boxLabels).containsExactly("gpu")
        assertThat(boxTools.offered(after)).isTrue()
        // A box is not a tool: nothing was loaded under its name, and it is no longer something to ask for.
        assertThat(recorder.toolsFound(conversation)).doesNotContain(name)
        assertThat(call(GrantRequestTools.LIST).path("tools").values().map { it.path("name").stringValue() }).doesNotContain(name)
    }

    @Test
    fun `refusing grants nothing and says so to the agent`() {
        call(GrantRequestTools.ASK, mapOf("tools" to listOf("lookupOrder"), "why" to "orders"))
        answer(GrantRequestTools.REFUSE).path("answerRequest.outcome").entity(String::class.java).isEqualTo("Refused")

        assertThat(agents.findByIdOrNull(agent.id!!)!!.tools).doesNotContain("lookupOrder")
        assertThat(told().single()).contains("alice refused to give you lookupOrder")
    }

    @Test
    fun `something it holds already, or that does not exist, is refused in a sentence`() {
        val refused = call(GrantRequestTools.ASK, mapOf("tools" to listOf(NoteTools.NOTE, "noSuchTool"), "why" to "x"))
        assertThat(refused.path("error").stringValue())
            .contains("${NoteTools.NOTE}, noSuchTool are not something you could be granted")
        assertThat(requests.findAll()).isEmpty()
    }

    /*
     * A task asks with its own task_request_permission, which parks it and
     * tells whoever asked for it. Offered tools_request too, a model asked "the
     * person in the conversation" of a task nobody was chatting in, the task
     * never stopped, and it waited for ever.
     */
    @Test
    fun `in a task's conversation neither is offered, and asking points to the task's own request`() {
        val taskSession = recorder.open(workspaceId, "task", "work-${System.nanoTime()}")
        tasks.save(
            io.mszymanski.orknux.server.task.Task(
                workspaceId = workspaceId, title = "List the workflows", prompt = "List the workflows",
                agentId = agent.id, sessionId = taskSession, createdBy = "alice", turnsAllowed = 5, secondsAllowed = 60,
            ),
        )
        val held = agents.findByIdOrNull(agent.id!!)!!
        val inTask = tools.offeringFor(held, session = taskSession).let { it.core + it.searchable }.map { it.name }
        assertThat(inTask).doesNotContain(GrantRequestTools.LIST, GrantRequestTools.ASK)
        assertThat(offered()).contains(GrantRequestTools.LIST, GrantRequestTools.ASK)

        val asked = mapper.readTree(
            tools.run(held, ToolCall("call", GrantRequestTools.ASK, mapper.writeValueAsString(mapOf("tools" to listOf("lookupOrder"), "why" to "x"))), taskSession),
        )
        assertThat(asked.path("error").stringValue()).contains(io.mszymanski.orknux.server.task.TaskTools.PERMISSION)
        // And the briefing's list of tools says the same: naming them there had a model look for them and loop.
        val told = briefing.of(held, session = taskSession).orEmpty()
        assertThat(told).doesNotContain(GrantRequestTools.ASK, GrantRequestTools.LIST)
        assertThat(briefing.of(held).orEmpty()).contains(GrantRequestTools.ASK)
        assertThat(requests.findAll()).isEmpty()
        tasks.deleteAll(tasks.findAll().filter { it.workspaceId == workspaceId })
    }

    @Test
    fun `switched off, the agent is offered neither and cannot ask`() {
        agents.save(agents.findByIdOrNull(agent.id!!)!!.apply { mayAskForTools = false })

        assertThat(offered()).doesNotContain(GrantRequestTools.LIST, GrantRequestTools.ASK)
        assertThat(call(GrantRequestTools.ASK, mapOf("tools" to listOf("lookupOrder"), "why" to "x")).path("error").stringValue())
            .contains("may not ask for more tools")
        assertThat(requests.findAll()).isEmpty()
    }

    @Test
    fun `a new agent may ask, and the page switches it with an audit line`() {
        val id = graphQlTester.document(
            "mutation { createAgent(input: { workspaceId: $workspaceId, name: \"Fresh\", type: LLM }) { id mayAskForTools } }",
        ).execute().path("createAgent.mayAskForTools").entity(Boolean::class.java).isEqualTo(true)
            .path("createAgent.id").entity(Long::class.java).get()

        graphQlTester.document(
            "mutation { updateAgent(id: $id, input: { name: \"Fresh\", mayAskForTools: false }) { mayAskForTools } }",
        ).execute().path("updateAgent.mayAskForTools").entity(Boolean::class.java).isEqualTo(false)
        assertThat(audit.findAll().map { it.message }).contains("Agent Fresh may no longer ask for more tools")
    }

    @Test
    @WithMockUser(username = "mallory", roles = ["NOBODY"])
    fun `somebody who cannot see the agent's workspace cannot accept for it`() {
        call(GrantRequestTools.ASK, mapOf("tools" to listOf("lookupOrder"), "why" to "orders"))

        answer(GrantRequestTools.ACCEPT).errors().satisfy { assertThat(it).isNotEmpty() }
        assertThat(agents.findByIdOrNull(agent.id!!)!!.tools).doesNotContain("lookupOrder")
    }
}
