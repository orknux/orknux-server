package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
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
 * The orkbox_* tools take Offer and Always while the agent holds a box label.
 *
 * They travelled on every turn whatever was marked; asked for, a new agent or a
 * label given afresh starts them at Offer, found under a tool limit. An agent
 * that predates it keeps carrying them, which the migration marks.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class BoxToolStatesTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val builtIns: BuiltInTools,
    @Autowired val tools: AgentTools,
    @Autowired val agents: AgentRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val jdbc: JdbcTemplate,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun make() {
        val workspace = workspaces.findByName("box-states") ?: workspaces.save(Workspace(name = "box-states"))
        workspaceId = requireNotNull(workspace.id)
    }

    @Test
    fun `a new agent with a box label starts the box tools at Offer, and one can be marked Always`() {
        val id = created("Boxed", labels = true)
        val agent = requireNotNull(agents.findByIdOrNull(id)).also { it.maxTools = 10 }
        assertThat(agent.requiredTools).noneMatch(BuiltInTools::box)
        val (carried, found) = tools(agent)
        assertThat(found).containsAll(builtIns.boxNames())
        assertThat(carried).doesNotContainAnyElementsOf(builtIns.boxNames())

        val always = (BuiltInTools.startingAlways() + EXEC).joinToString(",") { "\"$it\"" }
        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "Boxed", maxTools: 10, requiredTools: [$always] }) { id } }""",
        ).execute()
        val marked = requireNotNull(agents.findByIdOrNull(id))
        assertThat(marked.requiredTools).contains(EXEC)
        assertThat(tools(marked).first).contains(EXEC).doesNotContain(OPEN)
        assertThat(tools(marked).second).contains(OPEN)

        // Without a limit nothing is found: every box tool travels.
        marked.maxTools = null
        assertThat(tools(marked).first).containsAll(builtIns.boxNames())

        // And a label taken away takes the marks with it.
        graphQlTester.document("""mutation { updateAgent(id: $id, input: { name: "Boxed", boxLabels: [] }) { id } }""").execute()
        assertThat(requireNotNull(agents.findByIdOrNull(id)).requiredTools).noneMatch(BuiltInTools::box)
    }

    @Test
    fun `a box label given afresh brings the box tools in at Offer`() {
        val id = created("Boxed later", labels = false)
        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "Boxed later", maxTools: 10, boxLabels: ["linux"] }) { id } }""",
        ).execute()
        val agent = requireNotNull(agents.findByIdOrNull(id))
        assertThat(agent.boxLabels).containsExactly("linux")
        assertThat(agent.requiredTools).noneMatch(BuiltInTools::box)
        assertThat(tools(agent).second).containsAll(builtIns.boxNames())
    }

    /** An agent from before 1.2.0 carried every box tool while it held a label; the migration keeps it so. */
    @Test
    fun `the migration keeps an existing agent carrying its box tools`() {
        val id = created("Boxed in 1.1", labels = true)
        val without = created("Unboxed in 1.1", labels = false)
        listOf(id, without).forEach { one ->
            val agent = requireNotNull(agents.findByIdOrNull(one))
            agent.requiredTools = BuiltInTools.GRANTED.toMutableList()
            agent.maxTools = 10
            agents.save(agent)
        }

        jdbc.execute(migration())

        val agent = requireNotNull(agents.findByIdOrNull(id))
        assertThat(agent.requiredTools).startsWith(*BuiltInTools.GRANTED.toTypedArray()).containsAll(builtIns.boxNames())
        assertThat(tools(agent).first).containsAll(builtIns.boxNames())
        assertThat(requireNotNull(agents.findByIdOrNull(without)).requiredTools).describedAs("no label, no marks")
            .noneMatch(BuiltInTools::box)

        jdbc.execute(migration())
        assertThat(requireNotNull(agents.findByIdOrNull(id)).requiredTools).doesNotHaveDuplicates()
    }

    /** The names the migration writes out are the box surface, all of it. */
    @Test
    fun `the migration names every box tool and nothing else`() {
        val written = Regex("'(orkbox_[a-z_]+)'").findAll(migration()).map { it.groupValues[1] }.toList()
        assertThat(written).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(builtIns.boxNames())
    }

    private fun migration(): String {
        val sqlite = isSqlite(jdbc.dataSource?.let { jdbcUrlOf(it) })
        val path = if (sqlite) "db/migration/sqlite/V260__box_tools_always.sql" else "db/migration/postgresql/V580__box_tools_always.sql"
        return ClassPathResource(path).inputStream.use { String(it.readAllBytes(), Charsets.UTF_8) }
    }

    private fun tools(agent: Agent): Pair<List<String>, List<String>> =
        tools.offeringFor(agent).let { held -> held.core.map { it.name } to held.searchable.map { it.name } }

    private fun created(name: String, labels: Boolean): Long {
        agents.findByWorkspaceIdAndName(workspaceId, name)?.let { agents.delete(it) }
        val boxes = if (labels) """, boxLabels: ["linux"]""" else ""
        return graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "$name", type: LLM$boxes }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
    }

    private companion object {
        const val EXEC = "orkbox_exec"
        const val OPEN = "orkbox_open"
    }
}
