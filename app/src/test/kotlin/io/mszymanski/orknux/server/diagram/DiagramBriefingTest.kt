package io.mszymanski.orknux.server.diagram

import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.chat.AgentBriefing
import io.mszymanski.orknux.server.chat.BuiltInTools
import io.mszymanski.orknux.server.database.isSqlite
import io.mszymanski.orknux.server.database.jdbcUrlOf
import io.mszymanski.orknux.server.mcp.DiagramTools
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
 * Agents are told to look in the diagrams, and to keep them right.
 *
 * Pinned: the paragraph is there only while the workspace has a diagram and the
 * agent holds the search; it names the tools as they are declared; the
 * invitation to update goes with the change tool; it reads the same on every
 * round, so a cached prompt prefix holds; and the migration gives the agents
 * that predate the tools the seven at Always and the delete hidden.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class DiagramBriefingTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val briefing: AgentBriefing,
    @Autowired val builtIns: BuiltInTools,
    @Autowired val agents: AgentRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val jdbc: JdbcTemplate,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun make() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "diagram-briefing-${System.nanoTime()}")).id)
    }

    private fun created(name: String, orknuxAccess: Boolean): Long = graphQlTester.document(
        """mutation { createAgent(input: { workspaceId: $workspaceId, name: "$name", type: LLM, orknuxAccess: $orknuxAccess }) { id } }""",
    ).execute().path("createAgent.id").entity(Long::class.java).get()

    private fun diagram() = graphQlTester.document(
        """mutation { createDiagram(workspaceId: $workspaceId, name: "Systems") { id } }""",
    ).execute().path("createDiagram.id").entity(Long::class.java).get()

    private fun told(id: Long): String = briefing.grants(requireNotNull(agents.findByIdOrNull(id))).orEmpty()

    @Test
    fun `the paragraph is there once a diagram is, and names the tools as declared`() {
        val id = created("Reader", orknuxAccess = true)
        assertThat(told(id)).doesNotContain(PARAGRAPH)
        diagram()
        val said = told(id)
        assertThat(said).contains(PARAGRAPH).contains("Search them with " + DiagramTools.SEARCH).contains(DiagramTools.NODE)
            .contains("each with a " + DiagramTools.DESCRIPTION + " saying what it is for")
            .contains("update it with " + DiagramTools.CHANGE).contains("say in your answer what you changed")
            .contains(DiagramTools.AGENTS + " is " + DiagramTools.CHANGES).contains(DiagramTools.LIST)
        // Every name it says is one the agent is offered.
        val offered = builtIns.orknuxNames()
        listOf(DiagramTools.SEARCH, DiagramTools.NODE, DiagramTools.CHANGE, DiagramTools.LIST).forEach { assertThat(offered).contains(it) }
        // The same on the next round, and the next: nothing in it moves with the conversation.
        assertThat(told(id)).isEqualTo(said)
        // Within one conversation too. Not compared with no session at all: ask_person is offered only in a chat.
        val inSession = briefing.grants(requireNotNull(agents.findByIdOrNull(id)), session = 12345L)
        assertThat(inSession).contains(PARAGRAPH)
        assertThat(briefing.grants(requireNotNull(agents.findByIdOrNull(id)), session = 12345L)).isEqualTo(inSession)
    }

    @Test
    fun `an agent without the search, or without Orknux access, is not sent to the diagrams`() {
        diagram()
        assertThat(told(created("No access", orknuxAccess = false))).doesNotContain(PARAGRAPH)
        val hidden = created("Hidden search", orknuxAccess = true)
        val agent = requireNotNull(agents.findByIdOrNull(hidden))
        agent.hiddenTools.add(DiagramTools.SEARCH)
        agents.save(agent)
        assertThat(told(hidden)).doesNotContain(PARAGRAPH)
    }

    @Test
    fun `without the change tool it searches, and is not asked to update`() {
        diagram()
        val id = created("Look only", orknuxAccess = true)
        val agent = requireNotNull(agents.findByIdOrNull(id))
        agent.hiddenTools.add(DiagramTools.CHANGE)
        agents.save(agent)
        assertThat(told(id)).contains(PARAGRAPH).doesNotContain("update it with").doesNotContain("takes changes")
    }

    /** Run against agents put back the way they were before the tools existed, on whichever engine the suite runs. */
    @Test
    fun `the migration marks the seven Always on agents with access and hides the delete on every agent`() {
        val with = created("Old with access", orknuxAccess = true)
        val without = created("Old without access", orknuxAccess = false)
        listOf(with, without).forEach { one ->
            val agent = requireNotNull(agents.findByIdOrNull(one))
            agent.requiredTools = BuiltInTools.GRANTED.toMutableList()
            agent.hiddenTools = mutableListOf()
            agents.save(agent)
        }
        val keep = requireNotNull(agents.findByIdOrNull(with))
        keep.hiddenTools = mutableListOf(DiagramTools.EXPORT)
        agents.save(keep)

        // Twice: run again, nothing is added twice.
        repeat(2) { statements().forEach { jdbc.execute(it) } }

        val marked = requireNotNull(agents.findByIdOrNull(with))
        val always = DiagramTools.NAMES - DiagramTools.STARTS_HIDDEN - DiagramTools.EXPORT
        assertThat(marked.requiredTools).startsWith(*BuiltInTools.GRANTED.toTypedArray()).containsAll(always)
            .doesNotContain(DiagramTools.EXPORT, DiagramTools.DELETE).doesNotHaveDuplicates()
        assertThat(marked.hiddenTools).containsExactly(DiagramTools.EXPORT, DiagramTools.DELETE)
        val plain = requireNotNull(agents.findByIdOrNull(without))
        assertThat(plain.requiredTools).noneMatch { it.startsWith("orknux_") }
        assertThat(plain.hiddenTools).containsExactly(DiagramTools.DELETE)
    }

    @Test
    fun `the migration names every diagram tool and nothing else`() {
        val written = Regex("'(orknux_[a-z_]+)'").findAll(migration()).map { it.groupValues[1] }.toSet()
        assertThat(written).isEqualTo(DiagramTools.NAMES)
        assertThat(builtIns.orknuxNames()).containsAll(written)
    }

    /** One statement at a time: the SQLite driver runs only the first of several handed to it at once. */
    private fun statements(): List<String> = migration().lines().filterNot { it.trimStart().startsWith("--") }
        .joinToString("\n").split(";").map { it.trim() }.filter { it.isNotEmpty() }

    private companion object {
        /** The paragraph's opening; the tool names alone are in the briefing's list of tools anyway. */
        const val PARAGRAPH = "This workspace keeps diagrams"
    }

    private fun migration(): String {
        val sqlite = isSqlite(jdbc.dataSource?.let { jdbcUrlOf(it) })
        val path = if (sqlite) "db/migration/sqlite/V129__orknux_diagram_tools.sql" else "db/migration/postgresql/V451__orknux_diagram_tools.sql"
        return ClassPathResource(path).inputStream.use { String(it.readAllBytes(), Charsets.UTF_8) }
    }
}
