package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.database.isSqlite
import io.mszymanski.orknux.server.database.jdbcUrlOf
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.context.support.WithMockUser

/**
 * The orknux_* tools take Hide, Offer and Always, like every other built-in.
 *
 * Asked for on an agent's Tools list ("why can't I change Always to something
 * like Offer?", of orknux_news): the grant put every one of them in the agent's
 * carried half whatever was marked, so an agent under a tool limit could not
 * leave one to be found. Pinned here: Offer is found rather than carried, Always
 * is carried, Hide is neither - and an agent that predates this, or is given the
 * grant afresh, carries them all as it did.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class OrknuxToolStatesTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val builtIns: BuiltInTools,
    @Autowired val tools: AgentTools,
    @Autowired val searching: ToolSearchTools,
    @Autowired val agents: AgentRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val jdbc: JdbcTemplate,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun make() {
        val workspace = workspaces.findByName("orknux-states") ?: workspaces.save(Workspace(name = "orknux-states"))
        workspaceId = requireNotNull(workspace.id)
    }

    @Test
    fun `an orknux tool at Offer is found, at Always carried, and at Hide neither`() {
        val agent = Agent(
            workspaceId = workspaceId,
            name = "Scratch",
            type = AgentType.LLM,
            orknuxAccess = true,
            hiddenTools = mutableListOf(RUN),
            requiredTools = (BuiltInTools.GRANTED + NEWS).toMutableList(),
            maxTools = 100,
        )
        val held = tools.offeringFor(agent)
        val core = held.core.map { it.name }
        val searchable = held.searchable.map { it.name }

        assertThat(core).describedAs("Always").contains(NEWS).doesNotContain(WORKFLOWS, RUN)
        assertThat(searchable).describedAs("Offer").contains(WORKFLOWS).doesNotContain(NEWS, RUN)
        assertThat(BuiltInTools.carried(agent, WORKFLOWS)).isFalse()
        assertThat(BuiltInTools.carried(agent, NEWS)).isTrue()

        // Found by the search the round lends, and loaded by name.
        val found = mutableSetOf<String>()
        val shed = searching.shed(held.searchable, found, room = { 10 }, carried = core.toSet())
        shed.run(ToolCall("1", ToolSearchTools.FIND, """{"${ToolSearchTools.NAMES}":["$WORKFLOWS"]}"""))
        assertThat(found).contains(WORKFLOWS)

        // Without a limit, Offer is carried too: nothing is ever dropped.
        agent.maxTools = null
        assertThat(tools.offeringFor(agent).core.map { it.name }).contains(NEWS, WORKFLOWS).doesNotContain(RUN)
    }

    @Test
    fun `a new agent starts with every orknux tool at Always, and one can be set to Offer`() {
        val id = created("Offers news", orknuxAccess = true)
        val names = builtIns.orknuxNames()
        assertThat(requireNotNull(agents.findByIdOrNull(id)).requiredTools).containsAll(names)

        val granted = (BuiltInTools.GRANTED + names).joinToString(",") { "\"$it\"" }
        val always = (BuiltInTools.GRANTED + names - NEWS).joinToString(",") { "\"$it\"" }
        val back = graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "Offers news", orknuxAccess: true, maxTools: 100,
               tools: [$granted], requiredTools: [$always] }) { requiredTools } }""",
        ).execute().path("updateAgent.requiredTools").entityList(String::class.java).get()
        assertThat(back).describedAs("the marks on the orknux_* tools are kept").contains(WORKFLOWS).doesNotContain(NEWS)

        val agent = requireNotNull(agents.findByIdOrNull(id))
        val held = tools(agent)
        assertThat(held.first).contains(WORKFLOWS).doesNotContain(NEWS)
        assertThat(held.second).contains(NEWS)
    }

    @Test
    fun `Orknux access given afresh brings its tools in at Always`() {
        val id = created("Granted later", orknuxAccess = false)
        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "Granted later", orknuxAccess: true, maxTools: 100 }) { id } }""",
        ).execute()
        val agent = requireNotNull(agents.findByIdOrNull(id))
        assertThat(agent.requiredTools).containsAll(builtIns.orknuxNames())
        assertThat(tools(agent).first).containsAll(builtIns.orknuxNames())
    }

    /**
     * An agent from before 1.2.0 carried every orknux_* tool it had not hidden;
     * the migration marks those Always, so it still does. Run here against a
     * row put back the way 1.1 left it, on whichever engine the suite runs.
     */
    @Test
    fun `the migration keeps an existing agent carrying its orknux tools`() {
        val id = created("From 1.1", orknuxAccess = true)
        val without = created("From 1.1 without", orknuxAccess = false)
        /*
         * The surface the migration was written for: a tool added since arrives
         * at Offer on an agent that predates it, as the migration says, until a
         * migration of its own marks it.
         */
        val names = builtIns.orknuxNames().filter { it in written() }
        listOf(id, without).forEach { one ->
            val agent = requireNotNull(agents.findByIdOrNull(one))
            agent.requiredTools = BuiltInTools.GRANTED.toMutableList()
            agent.hiddenTools = mutableListOf(RUN)
            agent.maxTools = 100
            agents.save(agent)
        }

        jdbc.execute(migration())

        val agent = requireNotNull(agents.findByIdOrNull(id))
        assertThat(agent.requiredTools).doesNotContainNull()
            .startsWith(*BuiltInTools.GRANTED.toTypedArray())
            .containsAll(names - RUN)
            .doesNotContain(RUN)
        assertThat(tools(agent).first).containsAll(names - RUN).doesNotContain(RUN)
        assertThat(requireNotNull(agents.findByIdOrNull(without)).requiredTools)
            .describedAs("no grant, no marks")
            .noneMatch { it.startsWith("orknux_") }

        // Once is enough: run again, nothing is added twice.
        jdbc.execute(migration())
        assertThat(requireNotNull(agents.findByIdOrNull(id)).requiredTools).doesNotHaveDuplicates()
    }

    /** The names the migration writes out are the surface's, not a misspelling of one. */
    @Test
    fun `the migration names only orknux tools that exist`() {
        val written = written()
        assertThat(written).isNotEmpty.doesNotHaveDuplicates()
        assertThat(builtIns.orknuxNames()).containsAll(written)
    }

    private fun written(): List<String> = Regex("'(orknux_[a-z_]+)'").findAll(migration()).map { it.groupValues[1] }.toList()

    private fun migration(): String {
        val sqlite = isSqlite(jdbc.dataSource?.let { jdbcUrlOf(it) })
        val path = if (sqlite) "db/migration/sqlite/V102__orknux_tools_always.sql" else "db/migration/postgresql/V424__orknux_tools_always.sql"
        return ClassPathResource(path).inputStream.use { String(it.readAllBytes(), Charsets.UTF_8) }
    }

    /** Carried and found, by name. */
    private fun tools(agent: Agent): Pair<List<String>, List<String>> =
        tools.offeringFor(agent).let { held -> held.core.map { it.name } to held.searchable.map { it.name } }

    private fun created(name: String, orknuxAccess: Boolean): Long {
        agents.findByWorkspaceIdAndName(workspaceId, name)?.let { agents.delete(it) }
        return graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "$name", type: LLM, orknuxAccess: $orknuxAccess }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
    }

    private companion object {
        const val NEWS = "orknux_news"
        const val WORKFLOWS = "orknux_workflows"
        const val RUN = "orknux_run_workflow"
    }
}
