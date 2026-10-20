package io.mszymanski.orknux.server.revision

import io.mszymanski.orknux.server.action.WorkflowFunctionRepository
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentSkillRepository
import io.mszymanski.orknux.server.agent.AgentToolRepository
import io.mszymanski.orknux.server.agent.SkillCatalogRepository
import io.mszymanski.orknux.server.mcp.OrknuxScope
import io.mszymanski.orknux.server.mcp.OrknuxTools
import io.mszymanski.orknux.server.workspace.Workspace
import io.mszymanski.orknux.server.workspace.WorkspaceAuditRepository
import io.mszymanski.orknux.server.workspace.WorkspaceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester
import org.springframework.security.test.context.support.WithMockUser
import java.util.concurrent.atomic.AtomicLong

/**
 * What a component has been, and putting it back.
 *
 * The four kinds with no draft: a save is a version of them, which is the rule
 * [ComponentRevisionKind.release] holds. What is recorded is the state a save
 * *displaced*, stamped with when that state was itself saved — so the live row
 * is always the newest version and a component that existed before any of this
 * gets its history the first time somebody edits it.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class ComponentRevisionTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val revisions: ComponentRevisionRepository,
    @Autowired val functions: WorkflowFunctionRepository,
    @Autowired val tools: AgentToolRepository,
    @Autowired val skills: AgentSkillRepository,
    @Autowired val skillCatalogs: SkillCatalogRepository,
    @Autowired val agents: AgentRepository,
    @Autowired val orknuxTools: OrknuxTools,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val audit: WorkspaceAuditRepository,
) {

    private var workspaceId: Long = 0

    /**
     * A workspace of its own per test, and nothing wiped.
     *
     * Every assertion here is scoped to it, so this class neither depends on
     * what ran before it nor breaks what runs after. The suite's usual
     * `deleteAll()` reset is what it is because most of these tests predate
     * caring; the cost of it on SQLite is a cascade that trips a CHECK on
     * `workflow_action`, and a class that does not need to wipe should not.
     */
    @BeforeEach
    fun reset() {
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "revisions-${counter.incrementAndGet()}")).id)
    }

    /** Everything recorded about this test's workspace, and nothing else's. */
    private fun recorded() = revisions.findAll().filter { it.workspaceId == workspaceId }

    // ------------------------------------------------------------- recording

    /**
     * The first save of a function that has never been edited is what gives it
     * a history — of the state that was there before, which is the whole
     * argument for recording the displaced state rather than the new one.
     */
    @Test
    fun `editing a function keeps what it said before`() {
        val id = function("greet")
        updateFunction(id, "export default function greet() { return { said: 'first' }; }")
        updateFunction(id, "export default function greet() { return { said: 'second' }; }")

        val history = graphQlTester.document(
            """query { componentRevisions(kind: FUNCTION, componentId: $id) { id name savedBy } }""",
        ).execute()
        history.path("componentRevisions").entityList(Any::class.java).hasSize(2)

        // Newest first: the state the second save displaced is the first save's.
        val newest = history.path("componentRevisions[0].id").entity(Long::class.java).get()
        graphQlTester.document("""query { componentRevision(id: $newest) { content contentLanguage } }""")
            .execute()
            .path("componentRevision.contentLanguage").entity(String::class.java).isEqualTo("typescript")
            .path("componentRevision.content").entity(String::class.java)
            .satisfies { assertThat(it).contains("first") }
    }

    /**
     * A revision says when the state it holds was saved, not when it stopped
     * being current. The two are different questions and only one of them is
     * the one a history list answers.
     */
    @Test
    fun `a revision is stamped with the save it holds, not with the save that displaced it`() {
        val id = function("greet")
        val before = requireNotNull(functions.findById(id).orElse(null)).lastModifiedAt

        updateFunction(id, "export default function greet() { return {}; }")

        val held = recorded().single()
        assertThat(held.savedAt).isEqualTo(before)
        assertThat(held.savedBy).isEqualTo("alice")
    }

    /** A tool's parameters are in the snapshot; the export format drops them. */
    @Test
    fun `a tool's parameters survive being recorded and put back`() {
        val id = tool("forecast")
        graphQlTester.document(
            """mutation { updateTool(id: $id, input: {
                 params: [{ name: "city", type: STRING }, { name: "days", type: NUMBER }],
                 source: "export default function forecast(city, days) { return {}; }",
                 typescript: "export default function forecast(city: string, days: number) { return {}; }"
               }) { signature } }""",
        ).execute().path("updateTool.signature").entity(String::class.java)
            .isEqualTo("(city: string, days: number)")

        // A second save takes the parameters off, which is what the restore
        // below has to be able to undo.
        graphQlTester.document(
            """mutation { updateTool(id: $id, input: { params: [],
                 source: "export default function forecast() { return {}; }",
                 typescript: "export default function forecast() { return {}; }"
               }) { signature } }""",
        ).execute().path("updateTool.signature").entity(String::class.java).isEqualTo("()")

        val revision = newestRevision(ComponentRevisionKind.TOOL, id)
        restore(revision)

        graphQlTester.document("""query { tool(id: $id) { signature } }""").execute()
            .path("tool.signature").entity(String::class.java).isEqualTo("(city: string, days: number)")
    }

    /** The toggle is a save: it changes what the workspace has. */
    @Test
    fun `switching a tool off is a version of it`() {
        val id = tool("forecast")
        graphQlTester.document("""mutation { setToolEnabled(id: $id, enabled: false) { enabled } }""")
            .execute().path("setToolEnabled.enabled").entity(Boolean::class.java).isEqualTo(false)

        assertThat(recorded()).singleElement()
            .satisfies({ assertThat(it.kind).isEqualTo(ComponentRevisionKind.TOOL) })
    }

    /**
     * The lesson the issue history learnt: the tracker's record had a hole in
     * it exactly where the MCP tools wrote, because only the browser's door was
     * covered. An agent switched off from a conversation is switched off.
     */
    @Test
    fun `an agent switched off through the MCP tools is recorded too`() {
        val id = agent("Researcher")

        orknuxTools.run(
            OrknuxScope(workspaceId = workspaceId, mayWrite = true),
            "orknux_set_agent_enabled",
            """{"agent": "Researcher", "enabled": false}""",
        )

        assertThat(requireNotNull(agents.findById(id).orElse(null)).enabled).isFalse()
        assertThat(recorded()).singleElement().satisfies({
            assertThat(it.kind).isEqualTo(ComponentRevisionKind.AGENT)
            assertThat(it.componentId).isEqualTo(id)
        })
    }

    /**
     * An agent's history says which agents it may ask, and each save says what
     * it changed - which is how somebody finds the save that took a grant away.
     * The snapshot left that grant out, so its history could not.
     */
    @Test
    fun `a save that takes away an agent to ask says so, and a restore gives it back`() {
        val asker = agent("Lead")
        val asked = agent("Summarizer")
        val update = { ids: String ->
            graphQlTester.document(
                """mutation { updateAgent(id: $asker, input: { name: "Lead", agentIds: [$ids] }) { id } }""",
            ).execute().path("updateAgent.id").entity(Long::class.java).isEqualTo(asker)
        }
        update("$asked")
        graphQlTester.document("""mutation { updateAgent(id: $asked, input: { name: "Summarizer two" }) { id } }""")
            .execute().path("updateAgent.id").entity(Long::class.java).isEqualTo(asked)
        update("")

        val current = graphQlTester.document(
            """query { componentCurrentVersion(kind: AGENT, componentId: $asker) {
                 savedBy changes { field removed added } } }""",
        ).execute()
        current.path("componentCurrentVersion.savedBy").entity(String::class.java).isEqualTo("alice")
        // By the name it has now, not an id nobody knows.
        assertThat(granting(current.path("componentCurrentVersion.changes").entity(Any::class.java).get()))
            .isEqualTo(listOf<String>() to listOf("Summarizer two"))

        // The row that granted it says so too, compared with the version before it.
        val rows = graphQlTester.document(
            """query { componentRevisions(kind: AGENT, componentId: $asker) { changes { field added removed } } }""",
        ).execute().path("componentRevisions[0].changes").entity(Any::class.java).get()
        assertThat(granting(rows)).isEqualTo(listOf("Summarizer two") to listOf<String>())

        restore(newestRevision(ComponentRevisionKind.AGENT, asker))
        assertThat(requireNotNull(agents.findById(asker).orElse(null)).agents).containsExactly(asked)
    }

    /** A skill's markdown is what a reader of its history is shown. */
    @Test
    fun `a skill's content is what its revision reads as`() {
        val catalogId = graphQlTester.document(
            """mutation { createSkillCatalog(workspaceId: $workspaceId, name: "reviews") { id } }""",
        ).execute().path("createSkillCatalog.id").entity(Long::class.java).get()
        val id = graphQlTester.document(
            """mutation { createSkill(input: { workspaceId: $workspaceId, name: "reviewCode",
                 catalogId: $catalogId }) { id } }""",
        ).execute().path("createSkill.id").entity(Long::class.java).get()

        graphQlTester.document(
            """mutation(${'$'}content: String!) { updateSkill(id: $id, input: { content: ${'$'}content })
               { id } }""",
        ).variable("content", skillText("Look at the tests first")).execute()
            .path("updateSkill.id").entity(Long::class.java).isEqualTo(id)

        val revision = newestRevision(ComponentRevisionKind.SKILL, id)
        graphQlTester.document("""query { componentRevision(id: $revision) { contentLanguage content } }""")
            .execute()
            .path("componentRevision.contentLanguage").entity(String::class.java).isEqualTo("markdown")
            .path("componentRevision.content").entity(String::class.java)
            // The state the save displaced: the stub a new skill starts as.
            .satisfies { assertThat(it).doesNotContain("Look at the tests first").contains("## Objective") }
    }

    // -------------------------------------------------------------- restoring

    /**
     * Restoring records what it displaced, so the button that made the mistake
     * is the button that takes it back. Restoring the wrong revision is exactly
     * the mistake this feature exists to be able to undo.
     */
    @Test
    fun `restoring is itself a version, and is undoable`() {
        val id = function("greet")
        updateFunction(id, "export default function greet() { return { said: 'first' }; }")
        updateFunction(id, "export default function greet() { return { said: 'second' }; }")

        // Two saves, so two versions: the stub the function started as, and
        // the first thing written over it. The newest of them says "first".
        assertThat(recorded()).hasSize(2)
        restore(newestRevision(ComponentRevisionKind.FUNCTION, id))
        assertThat(requireNotNull(functions.findById(id).orElse(null)).typescript).contains("first")

        // The restore displaced the state that said "second", and kept it - so
        // going back is another restore rather than a lost afternoon.
        assertThat(recorded()).hasSize(3)
        restore(newestRevision(ComponentRevisionKind.FUNCTION, id))
        assertThat(requireNotNull(functions.findById(id).orElse(null)).typescript).contains("second")
    }

    /** The audit log says a restore happened, as every change here does. */
    @Test
    fun `a restore is written into the audit log`() {
        val id = function("greet")
        updateFunction(id, "export default function greet() { return {}; }")
        restore(newestRevision(ComponentRevisionKind.FUNCTION, id))

        assertThat(audit.findAll().filter { it.workspaceId == workspaceId }.map { it.message })
            .anySatisfy { assertThat(it).startsWith("Function greet restored to the version saved on") }
    }

    /** A name somebody else has taken is refused, not silently worked around. */
    @Test
    fun `a revision whose name is now taken is refused`() {
        val id = tool("forecast")
        graphQlTester.document(
            """mutation { updateTool(id: $id, input: { name: "weather" }) { name } }""",
        ).execute().path("updateTool.name").entity(String::class.java).isEqualTo("weather")
        tool("forecast")

        val revision = newestRevision(ComponentRevisionKind.TOOL, id)
        graphQlTester.document("""mutation { restoreComponentRevision(id: $revision) }""").execute()
            .errors().satisfy { errors ->
                assertThat(errors).singleElement()
                    .satisfies({ assertThat(it.message).contains("already exists") })
            }
    }

    // --------------------------------------------------------------- deleting

    /**
     * There is no foreign key to do this: the rows point into whichever of four
     * tables their kind names, so a delete that did not say so would leave a
     * history nothing could reach and the sweep would be the only thing that
     * ever removed it.
     */
    @Test
    fun `deleting a component takes its history with it`() {
        val id = tool("forecast")
        graphQlTester.document(
            """mutation { updateTool(id: $id, input: { description: "Ask about the weather" }) { id } }""",
        ).execute().path("updateTool.id").entity(Long::class.java).isEqualTo(id)
        assertThat(recorded()).hasSize(1)

        graphQlTester.document("""mutation { deleteTool(id: $id) }""").execute()
            .path("deleteTool").entity(Boolean::class.java).isEqualTo(true)

        assertThat(recorded()).isEmpty()
    }

    // ---------------------------------------------------------------- helpers

    private fun function(name: String): Long = graphQlTester.document(
        """mutation { createFunction(input: { workspaceId: $workspaceId, name: "$name" }) { id } }""",
    ).execute().path("createFunction.id").entity(Long::class.java).get()

    private fun updateFunction(id: Long, source: String) {
        graphQlTester.document(
            """mutation(${'$'}code: String!) { updateFunction(id: $id, input: {
                 source: ${'$'}code, typescript: ${'$'}code }) { id } }""",
        ).variable("code", source).execute().path("updateFunction.id").entity(Long::class.java).isEqualTo(id)
    }

    private fun tool(name: String): Long = graphQlTester.document(
        """mutation { createTool(input: { workspaceId: $workspaceId, name: "$name" }) { id } }""",
    ).execute().path("createTool.id").entity(Long::class.java).get()

    private fun agent(name: String): Long = graphQlTester.document(
        """mutation { createAgent(input: { workspaceId: $workspaceId, name: "$name", type: LLM }) { id } }""",
    ).execute().path("createAgent.id").entity(Long::class.java).get()

    /** What a list of changes says about the agents an agent may ask: added to removed. */
    @Suppress("UNCHECKED_CAST")
    private fun granting(changes: Any): Pair<List<String>, List<String>> {
        val change = (changes as List<Map<String, Any?>>).single { it["field"] == "agentIds" }
        return (change["added"] as List<String>) to (change["removed"] as List<String>)
    }

    private fun newestRevision(kind: ComponentRevisionKind, componentId: Long): Long = graphQlTester.document(
        """query { componentRevisions(kind: $kind, componentId: $componentId) { id } }""",
    ).execute().path("componentRevisions[0].id").entity(Long::class.java).get()

    private fun restore(revisionId: Long) {
        graphQlTester.document("""mutation { restoreComponentRevision(id: $revisionId) }""")
            .execute().path("restoreComponentRevision").entity(Boolean::class.java).isEqualTo(true)
    }

    private companion object {
        /** So each test's workspace has a name of its own; workspace names are unique. */
        val counter = AtomicLong(System.nanoTime())
    }

    /** A skill has to open with frontmatter or it is refused before it is stored. */
    private fun skillText(body: String): String = """
        ---
        name: reviewCode
        description: How to review code here
        ---

        # reviewCode

        $body
    """.trimIndent()
}
