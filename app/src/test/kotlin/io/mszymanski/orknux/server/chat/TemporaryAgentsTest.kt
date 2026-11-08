package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.TemporaryAgents
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/**
 * The Delegate visibility, and temporary agents an agent creates with it.
 *
 * What is pinned: a tool held at Delegate is never the agent's own - not
 * offered, refused in words when called - and is exactly what it may hand to an
 * agent it creates; creating refuses a model it was not allowed, a tool it does
 * not hold and a conversation past its cap, each in a sentence; a created agent
 * is askable from the conversation that made it and in no list; and it goes
 * when dismissed or when its conversation has been idle long enough.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class TemporaryAgentsTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val tools: AgentTools,
    @Autowired val asking: AgentRunTools,
    @Autowired val creating: TemporaryAgentTools,
    @Autowired val temporary: TemporaryAgents,
    @Autowired val sheds: SessionSheds,
    @Autowired val agents: AgentRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audits: WorkspaceAuditRepository,
    @Autowired val recorder: io.mszymanski.orknux.server.llm.LlmSessionRecorder,
    @Autowired val sessions: io.mszymanski.orknux.server.llm.LlmSessionRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0
    private var modelId: Long = 0
    private var otherModelId: Long = 0
    private var session: Long = 0
    private lateinit var tool: String

    @BeforeEach
    fun make() {
        val stamp = System.nanoTime()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "temporary-$stamp")).id)
        val providerId = graphQlTester.document(
            """mutation { createModelProvider(input: {
                 workspaceId: $workspaceId, name: "Stub", endpoint: "http://stub.invalid", secret: "sk-test"
               }) { id } }""",
        ).execute().path("createModelProvider.id").entity(Long::class.java).get()
        modelId = graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Writer", modelId: "w", kind: CHAT }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
        otherModelId = graphQlTester.document(
            """mutation { createModel(input: { providerId: $providerId, name: "Other", modelId: "o", kind: CHAT }) { id } }""",
        ).execute().path("createModel.id").entity(Long::class.java).get()
        tool = "shout_$stamp"
        graphQlTester.document(
            """mutation { createTool(input: { workspaceId: $workspaceId, name: "$tool", description: "Says it louder." }) { id } }""",
        ).execute().path("createTool.id").hasValue()
        session = recorder.open(workspaceId, "chat", "temporary-$stamp")
    }

    /** An orchestrator: the workspace tool at Delegate, and the one model to create with. */
    private fun orchestrator(name: String = "Assistant"): Agent {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "$name", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: {
                 name: "$name", modelId: $modelId, delegatedTools: ["$tool"], creatableModelIds: [$modelId]
               }) { tools delegatedTools creatableModelIds } }""",
        ).execute()
            .path("updateAgent.delegatedTools").entityList(String::class.java).containsExactly(tool)
            .path("updateAgent.creatableModelIds").entityList(String::class.java).containsExactly(modelId.toString())
        return requireNotNull(agents.findByIdOrNull(id))
    }

    private fun create(creator: Agent, arguments: String): tools.jackson.databind.JsonNode =
        mapper.readTree(requireNotNull(creating.shed(creator, session)).run(ToolCall("c1", TemporaryAgentTools.CREATE, arguments)))

    private fun researcher(name: String = "Researcher") =
        """{"name":"$name","instructions":"Find things on the web and answer with what you found, briefly.","modelId":$modelId,"tools":[{"name":"$tool","visibility":"ALWAYS"}]}"""

    @Test
    fun `a delegated tool is shown on the agent, never offered to it, and refused in words`() {
        val creator = orchestrator()

        graphQlTester.document("{ agent(id: ${creator.id}) { tools delegatedTools creatableModelIds } }").execute()
            .path("agent.delegatedTools").entityList(String::class.java).containsExactly(tool)
            .path("agent.tools").entityList(String::class.java).doesNotContain(tool)

        assertThat(tools.specsFor(creator).map { it.name }).doesNotContain(tool)
        val refused = mapper.readTree(tools.run(creator, ToolCall("c", tool, "{}"), session))
        assertThat(refused.path("error").stringValue())
            .isEqualTo("$tool is delegated: you may give it to an agent you create, not use it yourself")
    }

    @Test
    fun `a delegated tool can be given to a created agent, which is askable and in no list`() {
        val creator = orchestrator()

        // Offered the two tools, with the models named in its briefing, and ask_agent from the start.
        val shed = requireNotNull(sheds.of(creator, session))
        assertThat(shed.specs().map { it.name }).contains(TemporaryAgentTools.CREATE, TemporaryAgentTools.DISMISS)
        assertThat(shed.briefing()).contains("Writer ($modelId)")
        assertThat(tools.specsFor(creator).map { it.name }).contains(AgentRunTools.ASK)

        val made = create(creator, researcher())
        assertThat(made.path("created").stringValue()).isEqualTo("Researcher")
        val row = requireNotNull(agents.findTemporaryBySession(session).single())
        assertThat(row.temporary).isTrue()
        assertThat(row.createdByAgentId).isEqualTo(creator.id)
        // It holds the tool to use, and nothing it was not given: no built-in, no way to create agents.
        assertThat(tools.specsFor(row).map { it.name }).contains(tool).doesNotContain(NoteTools.NOTE, AgentRunTools.ASK)
        assertThat(row.creatableModelIds).isEmpty()
        assertThat(creating.shed(row, session)).isNull()

        // Askable from the conversation that made it.
        val listed = mapper.readTree(asking.listed(creator, session)).path("agents").values().map { it.path("name").stringValue() }
        assertThat(listed).containsExactly("Researcher")

        // And in no list.
        graphQlTester.document("{ workspaceAgents(workspaceId: $workspaceId) { content { name } } }").execute()
            .path("workspaceAgents.content[*].name").entityList(String::class.java).containsExactly("Assistant")

        assertThat(audits.findAll().map { it.message }).contains("Temporary agent Researcher created by Assistant")
    }

    @Test
    fun `a temporary agent's name is its conversation's - free beside another's, numbered beside the workspace's`() {
        val creator = orchestrator()
        val other = recorder.open(workspaceId, "chat", "another-${System.nanoTime()}")
        fun createIn(conversation: Long, arguments: String) =
            mapper.readTree(requireNotNull(creating.shed(creator, conversation)).run(ToolCall("c", TemporaryAgentTools.CREATE, arguments)))

        // Two conversations, each with its own web-fetcher: neither renamed.
        assertThat(create(creator, researcher("web-fetcher")).path("created").stringValue()).isEqualTo("web-fetcher")
        val theirs = createIn(other, researcher("web-fetcher"))
        assertThat(theirs.path("created").stringValue()).describedAs(theirs.toString()).isEqualTo("web-fetcher")
        assertThat(theirs.has("renamed")).isFalse()

        // The same name again in one conversation, or an agent of the workspace's name, is given a number.
        assertThat(create(creator, researcher("web-fetcher")).path("created").stringValue()).matches("web-fetcher-\\d{4}")
        val workspaceOwn = create(creator, researcher("Assistant"))
        assertThat(workspaceOwn.path("created").stringValue()).matches("Assistant-\\d{4}")
        assertThat(workspaceOwn.path("renamed").stringValue()).contains("rather than \"Assistant\"")

        // A third conversation sees neither, and cannot ask either: they are their conversations' own.
        val third = recorder.open(workspaceId, "chat", "third-${System.nanoTime()}")
        assertThat(mapper.readTree(asking.listed(creator, third)).path("agents").values().map { it.path("name").stringValue() })
            .doesNotContain("web-fetcher")
        val refused = asking.run(creator, """{"agent":"web-fetcher","question":"hi"}""", third)
        assertThat(mapper.readTree(refused).path("error").stringValue()).contains("You have not been given an agent called \"web-fetcher\"")

        // And the workspace may still make an agent of a temporary one's name: theirs are not the workspace's.
        graphQlTester.document("""mutation { createAgent(input: { workspaceId: $workspaceId, name: "web-fetcher", type: LLM }) { name } }""")
            .execute().path("createAgent.name").entity(String::class.java).isEqualTo("web-fetcher")
    }

    @Test
    fun `a question given with agent_create is asked at once, as ask_agent would`() {
        val creator = orchestrator()

        val made = create(creator, researcher().removeSuffix("}") + ""","question":"What is the capital of France?"}""")

        assertThat(made.path("created").stringValue()).isEqualTo("Researcher")
        val asked = made.path("asked")
        assertThat(asked.path("agent").stringValue()).describedAs(made.toString()).isEqualTo("Researcher")
        assertThat(asked.path("session").isNumber).describedAs(made.toString()).isTrue()
        assertThat(made.path("note").stringValue()).startsWith("It has been asked")

        // Without one it is only created, and waits to be asked.
        val quiet = create(creator, researcher("Quiet"))
        assertThat(quiet.has("asked")).isFalse()
    }

    @Test
    fun `creating refuses a model not allowed, a tool not held, and past the cap`() {
        val creator = orchestrator()

        assertThat(
            create(creator, """{"name":"X","instructions":"Answer what you are asked, briefly and plainly, in English.","modelId":$otherModelId}""").path("error").stringValue(),
        ).contains("Model $otherModelId is not one you may create agents with").contains("Writer ($modelId)")

        assertThat(
            create(creator, """{"name":"X","instructions":"Answer what you are asked, briefly and plainly, in English.","modelId":$modelId,"tools":[{"name":"rm_rf","visibility":"OFFER"}]}""")
                .path("error").stringValue(),
        ).contains("You do not hold rm_rf")

        // A stand-in for instructions is refused, saying why; an agent made with no tools is told so.
        assertThat(create(creator, """{"name":"P","instructions":"placeholder","modelId":$modelId}""").path("error").stringValue())
            .contains("says nothing of what it is for").contains("nothing was created")
        // No tools said at all is the parameter forgotten: refused, naming what the creator holds. [] says none on purpose.
        assertThat(create(creator, """{"name":"Bare","instructions":"Answer what you are asked, briefly and plainly, in English.","modelId":$modelId}""")
            .path("error").stringValue()).contains("Say which tools it gets").contains(tool).contains("Nothing was created")
        assertThat(create(creator, """{"name":"Bare","instructions":"Answer what you are asked, briefly and plainly, in English.","modelId":$modelId,"tools":[]}""")
            .path("noTools").stringValue()).contains("only answer from what it knows")
        temporary.dismiss(creator, session, "Bare")

        // A taken name is given a number rather than refused, and the result says what it is called.
        create(creator, researcher("Critic"))
        val again = create(creator, researcher("Critic"))
        assertThat(again.path("created").stringValue()).matches("Critic-\\d{4}")
        assertThat(again.path("renamed").stringValue()).contains("rather than \"Critic\"").doesNotContain("workspace").contains(again.path("created").stringValue())
        assertThat(create(creator, researcher("Fresh")).has("renamed")).isFalse()
        temporary.of(session).forEach { temporary.dismiss(creator, session, it.name) }

        repeat(5) { create(creator, researcher("R$it")) }
        assertThat(create(creator, researcher("R6")).path("error").stringValue())
            .contains("already has 5 agents you created")
        assertThat(agents.findTemporaryBySession(session)).hasSize(5)
    }

    @Test
    fun `dismissing and the idle sweep remove a created agent`() {
        val creator = orchestrator()
        create(creator, researcher("One"))
        create(creator, researcher("Two"))

        val shed = requireNotNull(creating.shed(creator, session))
        val gone = mapper.readTree(shed.run(ToolCall("d", TemporaryAgentTools.DISMISS, """{"agent":"one"}""")))
        assertThat(gone.path("dismissed").stringValue()).isEqualTo("One")
        assertThat(agents.findTemporaryBySession(session).map { it.name }).containsExactly("Two")

        // Not yet: the conversation was used a moment ago.
        assertThat(temporary.sweep()).isZero()
        assertThat(temporary.sweep(OffsetDateTime.now().plusHours(25))).isEqualTo(1)
        assertThat(agents.findTemporaryBySession(session)).isEmpty()
        // The orchestrator stays.
        assertThat(agents.findByIdOrNull(requireNotNull(creator.id))).isNotNull()
    }
}
