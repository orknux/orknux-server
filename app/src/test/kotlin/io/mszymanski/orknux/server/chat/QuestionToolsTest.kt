package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.interaction.InteractionRequestRepository
import io.mszymanski.orknux.server.llm.LlmSessionEventRepository
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
import io.mszymanski.orknux.server.llm.SessionEventRepository
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
 * ask_person: the agent's question drawn with its options and their lines,
 * answered with one, several, or the person's own words, and the answer told
 * back to the agent.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class QuestionToolsTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val tools: AgentTools,
    @Autowired val agents: AgentRepository,
    @Autowired val requests: InteractionRequestRepository,
    @Autowired val inbox: SessionEventRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val recorder: LlmSessionRecorder,
    @Autowired val lines: LlmSessionEventRepository,
    @Autowired val audit: WorkspaceAuditRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId = 0L
    private var conversation = 0L
    private lateinit var agent: Agent

    private val options = listOf(
        mapOf("label" to "Workflows", "description" to "Which workflows call which"),
        mapOf("label" to "Issues", "description" to "Depends-on links between issues"),
        mapOf("label" to "Libraries"),
    )

    @BeforeEach
    fun seed() {
        clean()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "questions-${System.nanoTime()}")).id)
        conversation = recorder.open(workspaceId, "chat", "help")
        agent = agents.save(Agent(workspaceId = workspaceId, name = "Helper", type = AgentType.LLM))
    }

    @AfterEach
    fun clean() {
        requests.deleteAll()
        inbox.deleteAll()
        lines.deleteAll()
        agents.deleteAll()
        audit.deleteAll()
        workspaces.deleteAll()
    }

    private fun ask(arguments: Map<String, Any?>): JsonNode =
        mapper.readTree(
            tools.run(agents.findByIdOrNull(agent.id!!)!!, ToolCall("call", QuestionTools.ASK, mapper.writeValueAsString(arguments)), conversation),
        )

    private fun told(): List<String> = inbox.findAll().filter { it.sessionId == conversation }.map { it.body }

    private fun open() = requests.findAll().single { it.chosen == null }.id

    @Test
    fun `the question is drawn with each option and what it means`() {
        assertThat(tools.specsFor(agents.findByIdOrNull(agent.id!!)!!).map { it.name }).contains(QuestionTools.ASK)
        assertThat(ask(mapOf("question" to "Which graph?", "options" to options)).path("status").stringValue()).isEqualTo("pending")

        graphQlTester.document(
            "{ sessionRequests(sessionId: $conversation) { kind question askedBy multiple allowsText options { id label description } } }",
        ).execute()
            .path("sessionRequests[0].kind").entity(String::class.java).isEqualTo(QuestionTools.KIND)
            .path("sessionRequests[0].question").entity(String::class.java).isEqualTo("Which graph?")
            .path("sessionRequests[0].askedBy").entity(String::class.java).isEqualTo("Helper")
            .path("sessionRequests[0].multiple").entity(Boolean::class.java).isEqualTo(false)
            .path("sessionRequests[0].allowsText").entity(Boolean::class.java).isEqualTo(true)
            .path("sessionRequests[0].options[*].label").entityList(String::class.java).containsExactly("Workflows", "Issues", "Libraries")
            .path("sessionRequests[0].options[0].description").entity(String::class.java).isEqualTo("Which workflows call which")
    }

    @Test
    fun `one option chosen is told to the agent by its label`() {
        ask(mapOf("question" to "Which graph?", "options" to options))
        graphQlTester.document("mutation { answerRequest(id: ${open()}, option: \"2\") { chosen outcome } }").execute()
            .path("answerRequest.chosen").entity(String::class.java).isEqualTo("2")
            .path("answerRequest.outcome").entity(String::class.java).isEqualTo("Issues")
        assertThat(told().single()).isEqualTo("alice answered your question \"Which graph?\": \"Issues\".")
    }

    @Test
    fun `several where it allows them, and the person's own words`() {
        ask(mapOf("question" to "Which to include?", "options" to options, "multiple" to true))
        graphQlTester.document(
            "mutation { answerRequest(id: ${open()}, options: [\"1\", \"3\"], text: \"and plugins\") { chosen text } }",
        ).execute()
            .path("answerRequest.chosen").entity(String::class.java).isEqualTo("1,3")
            .path("answerRequest.text").entity(String::class.java).isEqualTo("and plugins")
        assertThat(told().single())
            .isEqualTo("alice answered your question \"Which to include?\": \"Workflows\", \"Libraries\", and wrote: \"and plugins\".")
    }

    @Test
    fun `words alone are an answer, two where one is asked for is not`() {
        ask(mapOf("question" to "Which graph?", "options" to options))
        val id = open()
        graphQlTester.document("mutation { answerRequest(id: $id, options: [\"1\", \"2\"]) { chosen } }").execute()
            .errors().satisfy { assertThat(it.single().message).isEqualTo("This request takes one option") }
        graphQlTester.document("mutation { answerRequest(id: $id) { chosen } }").execute()
            .errors().satisfy { assertThat(it.single().message).isEqualTo("Choose an option or write an answer") }

        graphQlTester.document("mutation { answerRequest(id: $id, text: \"a dependency matrix\") { chosen } }").execute()
            .path("answerRequest.chosen").entity(String::class.java).isEqualTo("own-words")
        assertThat(told().single()).isEqualTo("alice answered your question \"Which graph?\": in their own words: \"a dependency matrix\".")
    }

    @Test
    fun `a menu of one, or options as plain strings, are handled`() {
        assertThat(ask(mapOf("question" to "Sure?", "options" to listOf(mapOf("label" to "Yes")))).path("error").stringValue())
            .contains("between 2 and 8")
        // A model that sends the array as text, or labels without descriptions, is still understood.
        assertThat(ask(mapOf("question" to "Sure?", "options" to "[\"Yes\", \"No\"]")).path("status").stringValue()).isEqualTo("pending")
    }

    @Test
    fun `a request that takes no words refuses them`() {
        // The tool grant questions take one option and nothing else.
        val asked = requests.save(
            io.mszymanski.orknux.server.interaction.InteractionRequest(
                workspaceId = workspaceId, llmSessionId = conversation, kind = GrantRequestTools.KIND,
                question = "Give tools?", options = """[{"id":"accept","label":"Accept"},{"id":"refuse","label":"Refuse"}]""",
            ),
        )
        graphQlTester.document("mutation { answerRequest(id: ${asked.id}, option: \"refuse\", text: \"no\") { chosen } }").execute()
            .errors().satisfy { assertThat(it.single().message).isEqualTo("This request takes one of its options, not words") }
    }
}
