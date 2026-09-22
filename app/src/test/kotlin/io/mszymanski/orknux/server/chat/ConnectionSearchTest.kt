package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.connection.ConnectionType
import io.mszymanski.orknux.connector.connection.CreateWorkspaceConnectionInput
import io.mszymanski.orknux.connector.connection.WorkspaceConnectionService
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
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
 * a tool. What is pinned here is which of the two an agent gets, that the tool
 * answers with ids and not credentials, and that it answers only with what that
 * agent was granted - it changes where the list is read, never what is in it.
 *
 * Makes its own workspace and connections, and leaves them: a workspace is found
 * rather than made again, and the connections are named for this test.
 */
@SpringBootTest
class ConnectionSearchTest(
    @Autowired val finder: ConnectionTools,
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

    /* --------------------------------------------- which of the two it gets */

    @Test
    fun `a handful is still recited in the briefing and no tool is offered`() {
        val few = agent(granted.take(3))

        assertThat(finder.offered(few)).isFalse()
        assertThat(finder.recited(few)).isTrue()

        val said = briefing.of(few).orEmpty()
        assertThat(said).contains("Production Jira")
        assertThat(said).doesNotContain(ConnectionTools.FIND)
    }

    @Test
    fun `past that the briefing points at the tool instead of listing them`() {
        val many = agent(granted)

        assertThat(finder.offered(many)).isTrue()

        val said = briefing.of(many).orEmpty()
        assertThat(said).contains(ConnectionTools.FIND)
        assertThat(said).contains("granted ${granted.size} connections")
        // The wall is gone: the names are not in the system turn any more, which
        // is the whole of what this saves.
        assertThat(said).doesNotContain("Production Jira")
        // And the standing rule survives the change of place, because it is the
        // rule that makes a grant permission rather than encouragement.
        assertThat(said).contains("explicitly told")
    }

    @Test
    fun `an agent granted none is offered nothing and told nothing`() {
        val none = agent(emptyList())

        assertThat(finder.offered(none)).isFalse()
        assertThat(finder.recited(none)).isFalse()
        assertThat(briefing.of(none).orEmpty()).doesNotContain(ConnectionTools.FIND)
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

        val said = finder.run(two, "{}")
        assertThat(said).contains("has not been granted enough connections")
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
