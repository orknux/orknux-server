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
        val workspace = workspaces.findByName("built-ins") ?: workspaces.save(Workspace(name = "built-ins"))
        /*
         * Hiding a built-in is the workspace's to allow. Issue #482: the tools
         * the server brings are offered to every agent unless a workspace has
         * said, in as many words, that it will take the risk of taking one
         * away - and these tests are about what happens when it has. The gate
         * itself is pinned below.
         */
        workspace.unsafeBuiltInTools = true
        workspaces.save(workspace)
        workspaceId = requireNotNull(workspace.id)
    }

    /**
     * And the gate: while a workspace has not allowed it, nothing hides.
     *
     * Issue #482. An agent without a clock invents today's date and an agent
     * that cannot say it has finished answers in prose - behaviour nothing here
     * can stand behind, and none of it reads as a missing tool to the person
     * watching. So a save that names no built-in leaves them all offered, on
     * every door: the screen draws those rows fixed, and this is what makes it
     * true for the MCP, a script and an import as well.
     */
    @Test
    fun `a workspace that has not allowed it keeps every built-in offered`() {
        val workspace = requireNotNull(workspaces.findByName("built-ins"))
        workspace.unsafeBuiltInTools = false
        workspaces.save(workspace)
        try {
            val id = created("Fixed")
            graphQlTester.document(
                """mutation { updateAgent(id: $id, input: { name: "Fixed", tools: [] }) { tools } }""",
            ).execute().path("updateAgent.tools").entityList(String::class.java).get()
                .let { held -> assertThat(held).containsAll(BuiltInTools.GRANTED) }

            assertThat(requireNotNull(agents.findByIdOrNull(id)).hiddenTools)
                .describedAs("nothing is hidden while the workspace has not allowed it")
                .isEmpty()
        } finally {
            workspace.unsafeBuiltInTools = true
            workspaces.save(workspace)
        }
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

    /**
     * What a shed lends is in the list of the agent's tools, marked. Issue #546:
     * the list said a tool not in it is not one you have, and zip_files - lent
     * with the scratchpads - was never in it.
     */
    @Test
    fun `a lent tool is merged into the one list of tools, marked loaded or load it first`() {
        val session = sessions.open(workspaceId, "test", "built-ins-${System.nanoTime()}")
        val shed = requireNotNull(notes.shed(session, "Responder"))
        val nl = 10.toChar().toString()
        val briefing = listOf(
            "These are the tools you have.",
            "- agent_asks (loaded): Lists the agents you asked",
            "- web_search (load it first): Searches the web",
            "",
            "Something after the list.",
        ).joinToString(nl)

        val found = agent(tools = BuiltInTools.GRANTED, required = emptyList(), ceiling = 10)
        val lent = requireNotNull(BuiltInTools.lentTo(found, shed)).specs()
        val merged = requireNotNull(BuiltInTools.listed(briefing, found, lent)).split(nl)
        assertThat(merged).containsSubsequence(
            "- agent_asks (loaded): Lists the agents you asked",
            merged.first { it.startsWith("- note_to_self (load it first)") },
            "- web_search (load it first): Searches the web",
            "",
            "Something after the list.",
        )
        assertThat(merged.joinToString(nl)).doesNotContain("These are yours too")

        val carried = agent(tools = BuiltInTools.GRANTED)
        assertThat(BuiltInTools.listed(briefing, carried, lent)).contains("- note_to_self (loaded)")
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

    /**
     * An agent as the round sees it, never saved: the rule is about the row's
     * lists, not the database.
     *
     * `tools` is what the agent holds, said the way the screen says it - and
     * turned into what the row keeps, which since #455 is the built-ins it does
     * *not* hold. The same translation the door makes, so a test that says
     * "this agent has all but the note" reads as one.
     */
    private fun agent(tools: List<String>, required: List<String> = tools, ceiling: Int? = null) = Agent(
        workspaceId = workspaceId,
        name = "Scratch",
        type = AgentType.LLM,
        tools = tools.filterNot { BuiltInTools.switchable(it) }.toMutableList(),
        hiddenTools = BuiltInTools.hiddenBy(tools),
        requiredTools = required.toMutableList(),
        maxTools = ceiling,
    )

    /* ---------------------------------------- a built-in nobody has judged -- */

    /**
     * The case the list of grants got wrong, and the reason for #455.
     *
     * A row that says nothing about a built-in - an agent made before the name
     * existed, or by a door that never heard of it - holds it. Under the old
     * rule the answer was no, so a built-in shipped in a later release would
     * have arrived switched off on every agent in every installation, silently,
     * and the only cure would have been another migration.
     */
    @Test
    fun `a built-in nobody has hidden is offered, whatever the row says`() {
        val quiet = Agent(workspaceId = workspaceId, name = "Quiet", type = AgentType.LLM)

        BuiltInTools.GRANTED.forEach { name ->
            assertThat(BuiltInTools.granted(quiet, name)).describedAs(name).isTrue()
            assertThat(BuiltInTools.carried(quiet, name)).describedAs(name).isTrue()
        }
        assertThat(quiet.finishAccess).isTrue()
        assertThat(quiet.artifactAccess).isTrue()
        assertThat(quiet.pictureLinkAccess).isTrue()
        // The two that need nothing else: `ask_agent` and `find_connections`
        // wait on a grant of their own - agents to ask, connections to name -
        // and say nothing about this rule either way.
        assertThat(tools.offeringFor(quiet).core.map { it.name })
            .contains(AgentTools.SAVE_ARTIFACT, AgentTools.BASE64_ENCODE)

        // And one deliberately hidden is still hidden, which is the whole of
        // the other half of the rule.
        val hiding = agent(tools = BuiltInTools.GRANTED - NoteTools.NOTE)
        assertThat(BuiltInTools.granted(hiding, NoteTools.NOTE)).isFalse()
        assertThat(BuiltInTools.granted(hiding, DateTools.NOW)).isTrue()
    }

    /**
     * What the screen is shown is what the screen sent, and what is stored is
     * the other half of it. A save that names no built-in hides them all -
     * which is what "these are its tools" has always meant - and naming one
     * again brings it back.
     */
    @Test
    fun `the API keeps the list the form sends, and stores what is hidden`() {
        val id = created("Stored")

        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "Stored", tools: ["note_to_self", "current_time"] }) { tools } }""",
        ).execute().path("updateAgent.tools").entityList(String::class.java)
            .containsExactly(NoteTools.NOTE, DateTools.NOW)

        val stored = requireNotNull(agents.findByIdOrNull(id))
        assertThat(stored.tools).describedAs("no built-in is a grant any more").isEmpty()
        assertThat(stored.hiddenTools).contains(FinishAnswerTools.FINISH, AgentTools.SAVE_ARTIFACT)
        assertThat(stored.hiddenTools).doesNotContain(NoteTools.NOTE, DateTools.NOW)

        graphQlTester.document(
            """mutation { updateAgent(id: $id, input: { name: "Stored", tools: [] }) { tools } }""",
        ).execute().path("updateAgent.tools").entityList(String::class.java).hasSize(0)
        assertThat(requireNotNull(agents.findByIdOrNull(id)).hiddenTools)
            .containsExactlyInAnyOrderElementsOf(BuiltInTools.GRANTED)
    }
}
