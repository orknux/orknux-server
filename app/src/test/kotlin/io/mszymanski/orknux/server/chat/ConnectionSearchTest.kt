package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.connection.ConnectionType
import io.mszymanski.orknux.connector.connection.CreateWorkspaceConnectionInput
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionService
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
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import tools.jackson.databind.ObjectMapper

/**
 * An agent finding the id of a connection it was granted.
 *
 * Issue #369. A tool that takes a connection wants an id, and the id came from
 * the briefing: every granted connection recited by name, type and id in the
 * system turn. Right for six, wrong for sixty - the briefing is paid for on
 * every round of every turn, for a list most turns never look at, and past a
 * certain length it is a wall a model skims rather than a list it reads.
 *
 * So past [ConnectionTools.LISTED] the briefing stops reciting and says there is
 * a tool. The tool itself is offered at any size - none, one, many - as a
 * built-in on the Tools list, on and Always for a new agent. What is pinned here
 * is what the briefing says at each size, that the tool is offered at each,
 * what it answers (nothing held included, as an answer and not an error), that
 * it answers with ids and not credentials, and that it answers only with what
 * that agent was granted - it changes where the list is read, never what is in it.
 *
 * Makes its own workspace and connections, and leaves them: a workspace is found
 * rather than made again, and the connections are named for this test.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ConnectionSearchTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val finder: ConnectionTools,
    @Autowired val tools: AgentTools,
    @Autowired val agents: AgentRepository,
    @Autowired val briefing: AgentBriefing,
    @Autowired val connections: WorkspaceConnectionService,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val mapper: ObjectMapper,
) {

    private var workspaceId: Long = 0
    private var granted: List<Long> = emptyList()

    @BeforeEach
    fun make() {
        /*
         * Found rather than made again. A workspace name is unique and the table
         * is not emptied between classes, so a second save of the same name is a
         * constraint violation rather than a fixture.
         */
        workspaceId = requireNotNull(
            (workspaces.findByName("connection search") ?: workspaces.save(Workspace(name = "connection search"))).id,
        )

        /*
         * Eight, which is past the recited handful, so the tool is what this
         * agent gets. Found by name where an earlier run left them: the service
         * refuses a duplicate name, and a fixture that only works on a clean
         * database is a fixture that fails the second time it is run.
         */
        val held = connections.workspaceConnections(workspaceId).associateBy { it.name }
        granted = WANTED.map { (name, url) ->
            held[name]?.id ?: connections.createWorkspaceConnection(
                CreateWorkspaceConnectionInput(
                    workspaceId = workspaceId,
                    name = name,
                    type = ConnectionType.HTTP,
                    url = url,
                ),
            ).id
        }
    }

    private fun agent(connectionIds: List<Long>) = Agent(
        workspaceId = workspaceId,
        name = "Finder",
        type = AgentType.LLM,
        connections = connectionIds.toMutableList(),
    )

    private fun found(said: String): List<Map<*, *>> =
        mapper.readTree(said).path("found").let { node ->
            (0 until node.size()).map { mapper.convertValue(node.get(it), Map::class.java) }
        }

    /* ------------------------------------- offered at any size, told by size */

    private fun offered(agent: Agent): Boolean = tools.specsFor(agent).any { it.name == ConnectionTools.FIND }

    @Test
    fun `an agent granted none is offered the tool and told plainly it holds nothing`() {
        val none = agent(emptyList())

        assertThat(offered(none)).isTrue()
        assertThat(finder.recited(none)).isFalse()

        val said = finder.run(none, "{}")
        assertThat(mapper.readTree(said).has("error")).isFalse()
        assertThat(found(said)).isEmpty()
        assertThat(mapper.readTree(said).path("granted").asInt()).isEqualTo(0)
        assertThat(mapper.readTree(said).path("note").asString())
            .contains("no connection").contains("Connections setting")
    }

    @Test
    fun `one grant is offered the tool and still recited in the briefing`() {
        val one = agent(granted.take(1))

        assertThat(offered(one)).isTrue()
        assertThat(finder.recited(one)).isTrue()
        assertThat(briefing.of(one).orEmpty()).contains("Production Jira")

        val matches = found(finder.run(one, "{}"))
        assertThat(matches.map { it["name"] }).containsExactly("Production Jira")
    }

    @Test
    fun `a handful is still recited in the briefing, beside the tool`() {
        val few = agent(granted.take(3))

        assertThat(offered(few)).isTrue()
        assertThat(finder.recited(few)).isTrue()

        val said = briefing.of(few).orEmpty()
        assertThat(said).contains("Production Jira")
        assertThat(found(finder.run(few, "{}"))).hasSize(3)
    }

    @Test
    fun `past that the briefing points at the tool instead of listing them`() {
        val many = agent(granted)

        assertThat(offered(many)).isTrue()
        assertThat(finder.pointedAt(many)).isTrue()

        val said = briefing.of(many).orEmpty()
        assertThat(said).contains(ConnectionTools.FIND)
        assertThat(said).contains("granted ${granted.size} connections")
        // The wall is gone: the names are not in the system turn any more, which
        // is the whole of what this saves.
        assertThat(said).doesNotContain("Production Jira")
        // And the standing rule survives the change of place, because it is the
        // rule that makes a grant permission rather than encouragement.
        assertThat(said).contains("explicitly told")
        assertThat(found(finder.run(many, "{}"))).hasSize(granted.size)
    }

    /**
     * Switched off on the Tools list, it is not offered - and the briefing does
     * not point at a tool the round withholds, so the list is recited instead.
     */
    @Test
    fun `hidden on the Tools list, it is not offered and the grants are recited`() {
        val many = agent(granted).apply { hiddenTools = mutableListOf(ConnectionTools.FIND) }

        assertThat(offered(many)).isFalse()
        assertThat(finder.pointedAt(many)).isFalse()
        val said = briefing.of(many).orEmpty()
        assertThat(said).doesNotContain(ConnectionTools.FIND).contains("Production Jira")
    }

    /** Like every built-in on the list: a new agent holds it, switched on and Always. */
    @Test
    fun `a new agent holds it, on and Always`() {
        val id = graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "Fresh", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()

        val fresh = requireNotNull(agents.findByIdOrNull(id))
        assertThat(fresh.hiddenTools).doesNotContain(ConnectionTools.FIND)
        assertThat(fresh.requiredTools).contains(ConnectionTools.FIND)
        assertThat(BuiltInTools.granted(fresh, ConnectionTools.FIND)).isTrue()
        assertThat(offered(fresh)).isTrue()
        agents.delete(fresh)
    }

    /* ------------------------------------------------------ what it answers */

    @Test
    fun `a query narrows to the connection it names`() {
        val said = finder.run(agent(granted), """{"query":"production jira"}""")

        val matches = found(said)
        assertThat(matches).hasSize(1)
        assertThat(matches.single()["name"]).isEqualTo("Production Jira")
        // The id, which is the whole reason the tool exists.
        assertThat(matches.single()["id"]).isNotNull()
    }

    /**
     * Every word rather than any of them, because a query is narrowing:
     * somebody asking for "production jira" wants the production one, and an
     * answer holding every Jira and every production connection is the question
     * not being answered.
     */
    @Test
    fun `every word has to match, not just one of them`() {
        val matches = found(finder.run(agent(granted), """{"query":"staging jira"}"""))

        assertThat(matches).hasSize(1)
        assertThat(matches.single()["name"]).isEqualTo("Staging Jira")
    }

    @Test
    fun `no query lists everything it holds`() {
        val said = finder.run(agent(granted), "{}")

        assertThat(found(said)).hasSize(granted.size)
        assertThat(mapper.readTree(said).path("granted").asInt()).isEqualTo(granted.size)
    }

    /**
     * The host, which is how somebody tells a staging connection from a
     * production one when both are named after the same system - and nothing
     * else off the connection.
     */
    @Test
    fun `it answers with a host and never with a credential`() {
        val said = finder.run(agent(granted), """{"query":"production jira"}""")

        assertThat(found(said).single()["host"]).isEqualTo("jira.example.com")
        assertThat(said).doesNotContain("secret").doesNotContain("token").doesNotContain("authType")
    }

    /**
     * It changes where the agent reads the list, never what is in it.
     */
    @Test
    fun `nothing outside the grant is findable`() {
        val two = agent(granted.take(2))
        // Offered or not, what it can see is the grant: asked about a connection
        // this agent does not hold, there is nothing to find.
        assertThat(found(finder.run(agent(granted), """{"query":"confluence"}"""))).hasSize(1)

        // And an agent holding two is answered with those two, however it asks.
        assertThat(found(finder.run(two, "{}")).map { it["name"] })
            .containsExactly("Production Jira", "Staging Jira")
        assertThat(found(finder.run(two, """{"query":"confluence"}"""))).isEmpty()
    }
}

private val WANTED = listOf(
    "Production Jira" to "https://jira.example.com",
    "Staging Jira" to "https://jira.staging.example.com",
    "Confluence" to "https://confluence.example.com",
    "Payments API" to "https://payments.example.com",
    "Warehouse" to "https://warehouse.example.com",
    "Billing" to "https://billing.example.com",
    "Reporting" to "https://reporting.example.com",
    "Identity" to "https://identity.example.com",
)
