package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.FinishAnswerTools
import io.mszymanski.orknux.server.agent.SkillTool
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
 * The basic built-ins travel, and the skill tools cannot be left behind the search.
 *
 * Session 896: a Qwen agent under a tool limit carried 42 of 225 tools, and its
 * scratchpads, to-do list, notes, clock and skill tools were not among them -
 * its marks predated the list. Its briefing told it to call skill_list and
 * skill_load before starting work, and it found both "(load it first)". V600
 * marks every built-in a new agent starts Always on the agents that hold it;
 * the skill tools, like finish_answer, are held at Always from then on while the
 * workspace keeps its built-ins fixed.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class BasicToolsAlwaysTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val tools: AgentTools,
    @Autowired val briefing: AgentBriefing,
    @Autowired val agents: AgentRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val jdbc: JdbcTemplate,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun make() {
        val workspace = workspaces.findByName("basic-tools-always") ?: workspaces.save(Workspace(name = "basic-tools-always"))
        workspace.unsafeBuiltInTools = false
        workspaceId = requireNotNull(workspaces.save(workspace).id)
    }

    @Test
    fun `the skill tools and finish_answer are the ones held at Always`() {
        assertThat(BuiltInTools.FIXED_ALWAYS)
            .containsExactlyInAnyOrder(FinishAnswerTools.FINISH, SkillTool.LIST.name, SkillTool.LOAD.name, SkillTool.SEARCH.name)
        assertThat(BuiltInTools.startingAlways()).containsAll(BuiltInTools.FIXED_ALWAYS)
        val response = graphQlTester.document("{ builtInTools { name fixedAlways } }").execute()
        val names = response.path("builtInTools[*].name").entityList(String::class.java).get()
        val flags = response.path("builtInTools[*].fixedAlways").entityList(Boolean::class.javaObjectType).get()
        val flagged = names.zip(flags).filter { it.second }.map { it.first }
        assertThat(flagged).containsExactlyInAnyOrderElementsOf(BuiltInTools.FIXED_ALWAYS)
    }

    @Test
    fun `a save under a tool limit that leaves the skill tools at Offer still carries them, and the briefing does not mark them to load`() {
        val id = created("Limited")
        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "Limited", maxTools: 10, requiredTools: ["${NoteTools.NOTE}"] }) { id } }""",
        ).execute()

        val agent = requireNotNull(agents.findByIdOrNull(id))
        assertThat(agent.requiredTools).contains(NoteTools.NOTE).containsAll(BuiltInTools.FIXED_ALWAYS)
        assertThat(agent.requiredTools).doesNotContain(ScratchpadTools.READ)

        val offering = tools.offeringFor(agent)
        assertThat(offering.core.map { it.name }).contains(SkillTool.LIST.name, SkillTool.LOAD.name, SkillTool.SEARCH.name)
        assertThat(offering.searchable.map { it.name }).doesNotContain(SkillTool.LIST.name, SkillTool.LOAD.name)

        val told = briefing.of(agent).orEmpty().lines()
        listOf(SkillTool.LIST.name, SkillTool.LOAD.name).forEach { name ->
            val line = told.firstOrNull { it.startsWith("- $name (") }
            assertThat(line).describedAs("$name in the briefing's list").isNotNull()
            assertThat(line).doesNotContain(BuiltInTools.TO_LOAD)
        }
    }

    @Test
    fun `with unsafe built-in visibility allowed the skill tools are the agent's to leave at Offer`() {
        val workspace = requireNotNull(workspaces.findByIdOrNull(workspaceId))
        workspace.unsafeBuiltInTools = true
        workspaces.save(workspace)
        try {
            val id = created("Open")
            graphQlTester.document(
                """mutation { updateAgent(id: $id, input: { name: "Open", maxTools: 10, requiredTools: ["${NoteTools.NOTE}"] }) { id } }""",
            ).execute()
            assertThat(requireNotNull(agents.findByIdOrNull(id)).requiredTools).containsExactly(NoteTools.NOTE)
        } finally {
            workspace.unsafeBuiltInTools = false
            workspaces.save(workspace)
        }
    }

    /** An agent from before 1.2.0 whose marks predate the basics gets them, less what it hid. */
    @Test
    fun `the migration marks every basic built-in an agent holds, and leaves what it hid and what starts at Offer`() {
        val id = created("Old marks")
        val agent = requireNotNull(agents.findByIdOrNull(id))
        agent.requiredTools = mutableListOf(NoteTools.NOTE)
        agent.hiddenTools = mutableListOf(ScratchpadTools.DELETE)
        agent.maxTools = 10
        agents.save(agent)

        jdbc.execute(migration())

        val after = requireNotNull(agents.findByIdOrNull(id))
        assertThat(after.requiredTools.first()).isEqualTo(NoteTools.NOTE)
        assertThat(after.requiredTools)
            .containsAll(BuiltInTools.startingAlways() - ScratchpadTools.DELETE)
            .doesNotContain(ScratchpadTools.DELETE)
            .doesNotContainAnyElementsOf(BuiltInTools.STARTS_OFFERED)
        assertThat(tools.offeringFor(after).core.map { it.name }).contains(SkillTool.LIST.name, SkillTool.LOAD.name)

        jdbc.execute(migration())
        assertThat(requireNotNull(agents.findByIdOrNull(id)).requiredTools).doesNotHaveDuplicates()
    }

    /** The names the migration writes out are startingAlways(), all of it and in its order, on both engines. */
    @Test
    fun `the migration names exactly the built-ins a new agent starts Always`() {
        listOf(POSTGRES, SQLITE).forEach { path ->
            val written = Regex("[(]\\d+, '([A-Za-z0-9_]+)'[)]").findAll(read(path)).map { it.groupValues[1] }.toList()
            assertThat(written).describedAs(path).containsExactlyElementsOf(BuiltInTools.startingAlways())
        }
    }

    private fun migration(): String =
        read(if (isSqlite(jdbc.dataSource?.let { jdbcUrlOf(it) })) SQLITE else POSTGRES)

    private fun read(path: String): String =
        ClassPathResource(path).inputStream.use { String(it.readAllBytes(), Charsets.UTF_8) }

    private fun created(name: String): Long {
        agents.findByWorkspaceIdAndName(workspaceId, name)?.let { agents.delete(it) }
        return graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "$name", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
    }

    private companion object {
        const val POSTGRES = "db/migration/postgresql/V600__basic_tools_always.sql"
        const val SQLITE = "db/migration/sqlite/V280__basic_tools_always.sql"
    }
}
