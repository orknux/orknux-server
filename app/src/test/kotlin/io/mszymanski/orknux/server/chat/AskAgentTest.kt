package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper

/**
 * One agent putting a question to another.
 *
 * Issue #350. An agent needing work done in a system it holds no tools for had
 * two ways out and both are bad: be granted those tools as well - forty
 * descriptions in its context, and a chain of lookups in its rounds before the
 * work it was asked about begins - or hand the job back to whoever asked.
 *
 * So an agent may be granted other agents and gets one tool. The specialist
 * answers in a conversation of its own, and what comes back is the answer rather
 * than the twelve rounds that produced it.
 *
 * What is pinned here is the grant and the bounds on it, rather than the model
 * call: which agents are offered, that an agent reached this way is granted none
 * of its own - the one rule that makes a ring of specialists impossible - and
 * that nothing outside the grant can be reached by naming it.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class AskAgentTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val asking: AgentRunTools,
    @Autowired val tools: AgentTools,
    @Autowired val agents: AgentRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0
    private var otherWorkspaceId: Long = 0

    @BeforeEach
    fun make() {
        agents.deleteAll()
        /*
         * Found rather than made again: a workspace name is unique and the table
         * is not emptied between classes.
         */
        workspaceId = requireNotNull(
            (workspaces.findByName("asking") ?: workspaces.save(Workspace(name = "asking"))).id,
        )
        otherWorkspaceId = requireNotNull(
            (workspaces.findByName("asking elsewhere") ?: workspaces.save(Workspace(name = "asking elsewhere"))).id,
        )
    }

    private fun agent(name: String, workspace: Long = workspaceId, asks: List<Long> = emptyList()): Agent =
        agents.save(
            Agent(
                workspaceId = workspace,
                name = name,
                type = AgentType.LLM,
                agents = asks.toMutableList(),
                // As a fresh agent is made: `ask_agent` is a name on the Tools
                // list since #444, and a row built without it has hidden the
                // tool this test is about.
                tools = BuiltInTools.GRANTED.toMutableList(),
            ),
        )

    private fun said(answer: String): String = mapper.readTree(answer).path("error").stringValue()

    /* ---------------------------------------------- whether it is offered */

    @Test
    fun `an agent granted nobody is offered nothing`() {
        val alone = agent("Alone")

        assertThat(asking.offered(alone)).isFalse()
        assertThat(tools.specsFor(alone).map { it.name }).doesNotContain(AgentRunTools.ASK)
    }

    @Test
    fun `an agent granted somebody is offered the tool, naming who it may ask`() {
        val specialist = agent("Librarian")
        val asker = agent("Support", asks = listOf(requireNotNull(specialist.id)))

        assertThat(asking.offered(asker)).isTrue()

        val spec = tools.specsFor(asker).single { it.name == AgentRunTools.ASK }
        assertThat(spec.description).contains("Librarian")
        assertThat(spec.parameters.map { it.name })
            .containsExactly(AgentRunTools.AGENT, AgentRunTools.QUESTION, AgentRunTools.TITLE)
        // Both needed: a question with nobody to ask, or somebody to ask with no
        // question, is a round spent being told so. The title is not - the
        // first line of the question stands in (issue #379).
        assertThat(spec.parameters.filter { it.required }.map { it.name })
            .containsExactly(AgentRunTools.AGENT, AgentRunTools.QUESTION)
    }

    /* ------------------------------------------------ what it will not do */

    @Test
    fun `an agent it was not granted cannot be reached by naming it`() {
        val specialist = agent("Librarian")
        val stranger = agent("Auditor")
        val asker = agent("Support", asks = listOf(requireNotNull(specialist.id)))

        val refused = asking.run(asker, """{"agent":"${stranger.name}","question":"anything"}""")
        assertThat(said(refused)).contains("not been given an agent called")
        // And it is told what it may ask, rather than only what it may not.
        assertThat(said(refused)).contains("Librarian")
    }

    @Test
    fun `an agent in another workspace is not among them however it is named`() {
        val elsewhere = agent("Librarian", workspace = otherWorkspaceId)
        val asker = agent("Support", asks = listOf(requireNotNull(elsewhere.id)))

        /*
         * Granted by id in the row and still unreachable: the grant is read
         * against the asking agent's own workspace, so a row that named one
         * elsewhere - a restore, a hand-edited database - offers nothing.
         */
        assertThat(asking.offered(asker)).isFalse()
        assertThat(said(asking.run(asker, """{"agent":"Librarian","question":"anything"}"""))).isNotEmpty()
    }

    @Test
    fun `an agent with no model is reported rather than called`() {
        val specialist = agent("Librarian")
        val asker = agent("Support", asks = listOf(requireNotNull(specialist.id)))

        assertThat(said(asking.run(asker, """{"agent":"Librarian","question":"what is the policy"}""")))
            .contains("has no model chosen")
    }

    @Test
    fun `a question with nothing in it is refused rather than asked`() {
        val specialist = agent("Librarian")
        val asker = agent("Support", asks = listOf(requireNotNull(specialist.id)))

        assertThat(said(asking.run(asker, """{"agent":"Librarian"}"""))).contains("are both needed")
        assertThat(said(asking.run(asker, """{"question":"what is the policy"}"""))).contains("are both needed")
    }

    /* ------------------------------------------------------- the one level */

    /**
     * The rule the whole feature rests on.
     *
     * A depth counter would be a number somebody has to choose, and every value
     * of it leaves a ring of specialists calling each other until it runs out.
     * Nothing to call cannot be got round.
     */
    @Test
    fun `an agent reached this way is granted no agents of its own`() {
        val deepest = agent("Archivist")
        val middle = agent("Librarian", asks = listOf(requireNotNull(deepest.id)))
        val asker = agent("Support", asks = listOf(requireNotNull(middle.id)))

        // The middle one holds a grant of its own on its row, and uses it when
        // it is the one being asked directly.
        assertThat(asking.offered(middle)).isTrue()
        assertThat(tools.specsFor(middle).map { it.name }).contains(AgentRunTools.ASK)

        // What the asker reaches is the same agent without it. Asserted through
        // the refusal, which names who the specialist could have asked: reached
        // this way, that list is empty and the tool is not there to be called.
        assertThat(asking.offered(asker)).isTrue()
        val spec = tools.specsFor(asker).single { it.name == AgentRunTools.ASK }
        assertThat(spec.description).contains("Librarian").doesNotContain("Archivist")
    }

    /* --------------------------------------------------- setting the grant */

    @Test
    fun `the grant is what the screen sets and reads back`() {
        val specialist = agent("Librarian")
        val asker = agent("Support")

        graphQlTester.document(
            """mutation { updateAgent(id: ${asker.id}, input: {
                 name: "Support", agentIds: [${specialist.id}]
               }) { agentIds } }""",
        ).execute().path("updateAgent.agentIds").entityList(String::class.java)
            .containsExactly(specialist.id.toString())

        assertThat(agents.findById(requireNotNull(asker.id)).orElseThrow().agents)
            .containsExactly(specialist.id)
    }

    @Test
    fun `an agent cannot be given itself to ask`() {
        val asker = agent("Support")

        graphQlTester.document(
            """mutation { updateAgent(id: ${asker.id}, input: {
                 name: "Support", agentIds: [${asker.id}]
               }) { agentIds } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors).singleElement()
                .satisfies({ assertThat(it.message).contains("cannot be given itself") })
        }

        assertThat(agents.findById(requireNotNull(asker.id)).orElseThrow().agents).isEmpty()
    }

    /**
     * Refused rather than dropped, which is the rule the connection grant keeps:
     * a grant silently thrown away is a form that says it saved and a tool that
     * then cannot see what somebody ticked.
     */
    @Test
    fun `an agent from another workspace is refused rather than dropped`() {
        val elsewhere = agent("Librarian", workspace = otherWorkspaceId)
        val asker = agent("Support")

        graphQlTester.document(
            """mutation { updateAgent(id: ${asker.id}, input: {
                 name: "Support", agentIds: [${elsewhere.id}]
               }) { agentIds } }""",
        ).execute().errors().satisfy { errors ->
            assertThat(errors).singleElement()
                .satisfies({ assertThat(it.message).contains("not one of this workspace's agents") })
        }

        assertThat(agents.findById(requireNotNull(asker.id)).orElseThrow().agents).isEmpty()
    }
}
