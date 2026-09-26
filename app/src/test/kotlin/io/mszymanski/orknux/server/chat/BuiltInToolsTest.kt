package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.model.ToolCall
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.agent.FinishAnswerTools
import io.mszymanski.orknux.server.llm.LlmSessionRecorder
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

/**
 * The server's own tools, switched on the agent's Tools list. Issue #444.
 *
 * Until this, an agent's Tools list showed three of the tools the server
 * brings itself and the round handed out the rest without asking, so the one
 * list somebody reads to see what an agent may do was wrong about most of what
 * it could do. Every built-in is a row now, and the round reads the same list:
 * what is pinned here is that hiding a row is the server withholding the tool -
 * declared to no model, and refused if a model guesses the name anyway - and
 * that a fresh agent starts with all of them, so nothing changes for anybody
 * who never opens the list.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class BuiltInToolsTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val builtIns: BuiltInTools,
    @Autowired val tools: AgentTools,
    @Autowired val notes: NoteTools,
    @Autowired val agents: AgentRepository,
    @Autowired val sessions: LlmSessionRecorder,
    @Autowired val workspaces: WorkspaceRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun make() {
        workspaceId = requireNotNull(
            (workspaces.findByName("built-ins") ?: workspaces.save(Workspace(name = "built-ins"))).id,
        )
    }

    /* ------------------------------------------------------ the inventory -- */

    /**
     * One list, read by the form and by the round. Every name the grant list
     * governs is in it, and so is every tool that comes with a wider grant,
     * saying which grant.
     */
    @Test
    fun `the inventory names every built-in and what switches it`() {
        val listed = builtIns.all()

        assertThat(listed.filter { it.governance == BuiltInGovernance.GRANT }.map { it.name })
            .containsExactlyElementsOf(BuiltInTools.GRANTED)
        assertThat(listed.filter { it.governance == BuiltInGovernance.SKILL_CATALOGS }.map { it.name })
            .containsExactlyInAnyOrder("skill_list", "skill_load")
        assertThat(listed.filter { it.governance == BuiltInGovernance.MEMORY_CATALOGS }.map { it.name })
            .containsExactlyInAnyOrder("memory_search", "memory_save")
        assertThat(listed.filter { it.governance == BuiltInGovernance.ORKNUX_ACCESS }.map { it.name })
            .isNotEmpty
            .allMatch { it.startsWith("orknux_") }
        assertThat(listed.filter { it.governance == BuiltInGovernance.SHELL_ACCESS }.map { it.name })
            .containsExactlyInAnyOrder("shell_open_session", "shell_run_command", "shell_close_session")
        // A name once, or the form draws two rows with one control between them.
        assertThat(listed.map { it.name }).doesNotHaveDuplicates()
    }

    /** The same list over the API, which is what the form reads. */
    @Test
    fun `the form reads the inventory over the API`() {
        val names = graphQlTester.document("{ builtInTools { name governance } }")
            .execute()
            .path("builtInTools[*].name").entityList(String::class.java).get()

        assertThat(names).containsAll(BuiltInTools.GRANTED)
        assertThat(names).contains("skill_load", "memory_save", "shell_run_command")
    }

    /* ------------------------------------------------------ a fresh agent -- */

    /**
     * On and Always for every one of them, so an agent made today is offered
     * exactly what one made yesterday was - and the three switches that were
     * columns read as they always did.
     */
    @Test
    fun `a fresh agent starts with every built-in on and Always`() {
        val id = created("Fresh")

        graphQlTester.document("{ agent(id: $id) { tools requiredTools artifactAccess finishAccess pictureLinkAccess } }")
            .execute()
            .path("agent.tools").entityList(String::class.java).containsExactly(*BuiltInTools.GRANTED.toTypedArray())
            .path("agent.requiredTools").entityList(String::class.java).containsExactly(*BuiltInTools.GRANTED.toTypedArray())
            .path("agent.artifactAccess").entity(Boolean::class.java).isEqualTo(true)
            .path("agent.finishAccess").entity(Boolean::class.java).isEqualTo(true)
            .path("agent.pictureLinkAccess").entity(Boolean::class.java).isEqualTo(true)
    }

    /**
     * The booleans still work as switches on the list, for a caller written
     * against them: off takes the name out, and reads back as off.
     */
    @Test
    fun `the old switches move the names on the list`() {
        val id = created("Switched")

        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "Switched", finishAccess: false, artifactAccess: false }) {
                 tools requiredTools finishAccess artifactAccess pictureLinkAccess } }""",
        ).execute()
            .path("updateAgent.finishAccess").entity(Boolean::class.java).isEqualTo(false)
            .path("updateAgent.artifactAccess").entity(Boolean::class.java).isEqualTo(false)
            .path("updateAgent.pictureLinkAccess").entity(Boolean::class.java).isEqualTo(true)
            .path("updateAgent.tools").entityList(String::class.java)
            .doesNotContain("finish_answer", "save_artifact", "base64_encode", "base64_decode")
            .path("updateAgent.requiredTools").entityList(String::class.java).doesNotContain("finish_answer")

        // And the list is the other door onto the same switch.
        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "Switched", tools: ["finish_answer"] }) { finishAccess requiredTools } }""",
        ).execute()
            .path("updateAgent.finishAccess").entity(Boolean::class.java).isEqualTo(true)
            // A grant taken away takes its Always mark with it, sent or not.
            .path("updateAgent.requiredTools").entityList(String::class.java).hasSize(0)
    }

    /* ------------------------------------------------- hiding one withholds it */

    /**
     * The lent tools go through the agent's grants once, where the round takes
     * the shed: a note hidden on the agent's page is neither declared nor
     * answered, and the lender never learns about grants.
     */
    @Test
    fun `a hidden note_to_self is neither declared nor answered`() {
        val session = sessions.open(workspaceId, "test", "built-ins-${System.nanoTime()}")
        val shed = requireNotNull(notes.shed(session, "Responder"))

        val hiding = agent(tools = BuiltInTools.GRANTED - NoteTools.NOTE)
        val lent = requireNotNull(BuiltInTools.lentTo(hiding, shed))
        assertThat(lent.specs()).isEmpty()
        assertThat(lent.handles(NoteTools.NOTE)).isFalse()

        // And the round's own tools refuse the name as one that does not exist,
        // which for this agent is the truth.
        val refused = tools.run(hiding, ToolCall("1", NoteTools.NOTE, """{"note":"Steps 1-6 done."}"""))
        assertThat(refused).contains("There is no tool called note_to_self")

        val showing = agent(tools = BuiltInTools.GRANTED)
        val offered = requireNotNull(BuiltInTools.lentTo(showing, shed))
        assertThat(offered.specs().map { it.name }).containsExactly(NoteTools.NOTE)
        assertThat(offered.handles(NoteTools.NOTE)).isTrue()
        assertThat(offered.run(ToolCall("1", NoteTools.NOTE, """{"note":"Steps 1-6 done."}"""))).contains("\"written\":true")
    }

    /** A shed's own names - `task_done`, the chat's drawing - are not the list's to switch. */
    @Test
    fun `a name the list does not govern passes through`() {
        val bare = agent(tools = emptyList())
        assertThat(BuiltInTools.granted(bare, "task_done")).isTrue()
        assertThat(BuiltInTools.granted(bare, "jira_search")).isTrue()
        assertThat(BuiltInTools.granted(bare, FinishAnswerTools.FINISH)).isFalse()
    }

    /**
     * The same rule for the built-ins the round offers itself rather than
     * borrows: saving a file is on the list, and off the list it is not offered.
     */
    @Test
    fun `a hidden save_artifact is not offered, and a shown one is`() {
        val hiding = agent(tools = BuiltInTools.GRANTED - AgentTools.ARTIFACT_TOOL_NAMES)
        assertThat(tools.specsFor(hiding).map { it.name }).doesNotContain(AgentTools.SAVE_ARTIFACT)

        val showing = agent(tools = BuiltInTools.GRANTED)
        assertThat(tools.specsFor(showing).map { it.name }).contains(AgentTools.SAVE_ARTIFACT, AgentTools.BASE64_ENCODE)
    }

    /**
     * Under a ceiling a built-in left at Offer is found like any other tool,
     * and one marked Always is carried. Without a ceiling nothing is found.
     */
    @Test
    fun `under a ceiling a built-in is carried or found per its Always mark`() {
        val pinned = agent(tools = BuiltInTools.GRANTED, required = listOf(AgentTools.SAVE_ARTIFACT), ceiling = 10)
        val held = tools.offeringFor(pinned)
        assertThat(held.core.map { it.name }).contains(AgentTools.SAVE_ARTIFACT)
        assertThat(held.searchable.map { it.name }).contains(AgentTools.BASE64_ENCODE)
        assertThat(BuiltInTools.carried(pinned, NoteTools.NOTE)).isFalse()
        assertThat(BuiltInTools.carried(pinned, AgentTools.SAVE_ARTIFACT)).isTrue()

        val uncapped = agent(tools = BuiltInTools.GRANTED, required = emptyList(), ceiling = null)
        assertThat(tools.offeringFor(uncapped).core.map { it.name }).contains(AgentTools.SAVE_ARTIFACT, AgentTools.BASE64_ENCODE)
        assertThat(BuiltInTools.carried(uncapped, NoteTools.NOTE)).isTrue()
    }

    private fun created(name: String): Long {
        agents.findByWorkspaceIdAndName(workspaceId, name)?.let { agents.delete(it) }
        return graphQlTester.document(
            """mutation { createAgent(input: { workspaceId: $workspaceId, name: "$name", type: LLM }) { id } }""",
        ).execute().path("createAgent.id").entity(Long::class.java).get()
    }

    /** An agent as the round sees it, never saved: the rule is about the row's lists, not the database. */
    private fun agent(tools: List<String>, required: List<String> = tools, ceiling: Int? = null) = Agent(
        workspaceId = workspaceId,
        name = "Scratch",
        type = AgentType.LLM,
        tools = tools.toMutableList(),
        requiredTools = required.toMutableList(),
        maxTools = ceiling,
    )
}
