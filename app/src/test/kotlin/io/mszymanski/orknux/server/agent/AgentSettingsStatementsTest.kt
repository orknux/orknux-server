package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.server.SqlSeen
import io.mszymanski.orknux.server.workflow.WorkflowEdgeRepository
import io.mszymanski.orknux.server.workflow.WorkflowNodeRepository
import io.mszymanski.orknux.server.workflow.WorkflowPublicationRepository
import io.mszymanski.orknux.server.workflow.WorkflowRepository
import io.mszymanski.orknux.server.workflow.WorkspaceWorkflowRepository
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

/**
 * What an agent's settings page costs the database. Issue #616.
 *
 * In production `componentDependants` took 49 seconds and `componentRevisions`
 * 44. Two things did it. Each workflow the workspace had was asked about in
 * seven statements and a parsed graph, to look at one column. And an agent was
 * loaded in one select joining its ten lists, which returns every combination of
 * their rows: an agent with six names in six of them was a minute on its own.
 *
 * Counted in statements rather than timed, because a count is the same on every
 * machine and a time is not - and asserted as a count that does not grow, three
 * of everything against thirty, which is the shape the page went wrong in.
 */
@SpringBootTest
@AutoConfigureGraphQlTester
@WithMockUser(username = "alice", roles = ["ADMINS"])
class AgentSettingsStatementsTest(
    @Autowired val graphQlTester: ExecutionGraphQlServiceTester,
    @Autowired val agents: AgentRepository,
    @Autowired val workspaces: WorkspaceRepository,
    @Autowired val publications: WorkflowPublicationRepository,
    @Autowired val nodes: WorkflowNodeRepository,
    @Autowired val edges: WorkflowEdgeRepository,
    @Autowired val assignments: WorkspaceWorkflowRepository,
    @Autowired val workflows: WorkflowRepository,
) {

    private var workspaceId: Long = 0

    @BeforeEach
    fun reset() {
        publications.deleteAll()
        nodes.deleteAll()
        edges.deleteAll()
        assignments.deleteAll()
        workflows.deleteAll()
        agents.deleteAll()
        workspaces.deleteAll()
        workspaceId = requireNotNull(workspaces.save(Workspace(name = "settings")).id)
    }

    /**
     * Three of everything costs what thirty does.
     *
     * Workflows naming the agent - half of them published, so both halves of the
     * question are asked - other agents beside it, saves in its history, and
     * other workspaces in the list. Before #616 the dependants were 28 statements
     * against 201, the agents 50 against 374, and the workspaces 10 against 55.
     */
    @Test
    fun `the settings page asks as many statements of thirty workflows, agents and revisions as of three`() {
        val agentId = createAgent("Subject")
        grow(agentId, 3, "first")
        val few = counts(agentId)

        grow(agentId, 27, "then")
        val many = counts(agentId)

        assertThat(many).isEqualTo(few)
        // And the answer is the thirty, not a cheaper wrong one.
        val published = graphQlTester.document(dependants(agentId)).execute()
            .path("componentDependants.entries[*].published").entityList(Boolean::class.java).get()
        assertThat(published).hasSize(30)
        // Every other one of each batch: two of the three, fourteen of the twenty-seven.
        assertThat(published.count { it }).isEqualTo(16)
    }

    /**
     * Each list in a select of its own.
     *
     * One statement joining two lists of six is thirty-six rows; ten lists is
     * the product of all of them. Looked at in the SQL because the count of
     * statements went *up* with the fix - one per list - and what had to go was
     * the join.
     */
    @Test
    fun `an agent is read a list at a time, never with two of its lists in one select`() {
        val agentId = createAgent("Wide")
        widen(agentId)

        val seen = SqlSeen.during {
            graphQlTester.document("""query { agent(id: $agentId) { $AGENT_FIELDS } }""").execute()
                .path("agent.mcpServers").entityList(String::class.java).hasSize(6)
        }.second

        seen.forEach { sql -> assertThat(LISTS.count { it in sql }).describedAs(sql).isLessThanOrEqualTo(1) }
    }

    /**
     * A save that changes no list writes no list.
     *
     * The form sends every list every time, and each used to arrive as a new
     * list - which Hibernate reads as replaced, deleting and reinserting every
     * row of all ten tables to change a prompt.
     */
    @Test
    fun `saving a prompt leaves the lists alone`() {
        val agentId = createAgent("Prompted")
        widen(agentId)

        val seen = SqlSeen.during { save(agentId, "a new prompt", wide = true) }.second

        assertThat(seen.filter { it.startsWith("update agent ") }).hasSize(1)
        val written = seen.filter { sql ->
            LISTS.any { "delete from $it " in sql || "insert into $it " in sql || "update $it " in sql }
        }
        assertThat(written)
            .describedAs("a list rewritten").isEmpty()
        assertThat(agents.findByIdOrNull(agentId)?.mcpServers).containsExactly("m1", "m2", "m3", "m4", "m5", "m6")
        assertThat(agents.findByIdOrNull(agentId)?.systemPrompt).isEqualTo("a new prompt")
    }

    /** The history tab reads the dates and the names, not every revision's snapshot. */
    @Test
    fun `listing an agent's revisions reads none of their snapshots`() {
        val agentId = createAgent("Revised")
        repeat(3) { save(agentId, "prompt $it") }

        val seen = SqlSeen.during {
            graphQlTester.document(revisions(agentId)).execute()
                .path("componentRevisions[*].id").entityList(String::class.java).hasSize(3)
        }.second

        assertThat(seen).noneMatch { it.contains("snapshot") }
    }

    private fun counts(agentId: Long): Map<String, Int> = linkedMapOf(
        "dependants" to count(dependants(agentId)),
        "revisions" to count(revisions(agentId)),
        "agents" to count(
            """query { workspaceAgents(workspaceId: $workspaceId, page: 0, size: 100) { content { $AGENT_FIELDS } totalElements } }""",
        ),
        "workspaces" to count("""query { workspaces(page: 0, size: 100) { content { $WORKSPACE_FIELDS } totalElements } }"""),
        "save" to count(saveDocument(agentId, "the same prompt", wide = false)),
    )

    /** [n] more of everything the page lists. */
    private fun grow(agentId: Long, n: Int, label: String) {
        repeat(n) { createAgent("$label agent $it") }
        repeat(n) { workflowNaming(agentId, "$label flow $it", publish = it % 2 == 0) }
        repeat(n) { save(agentId, "$label prompt $it") }
        repeat(n) { workspaces.save(Workspace(name = "$label workspace $it")) }
    }

    private fun count(document: String): Int =
        SqlSeen.during { graphQlTester.document(document).execute().path("").hasValue() }.second.size

    private fun createAgent(name: String): Long = graphQlTester.document(
        """mutation { createAgent(input: { workspaceId: $workspaceId, name: "$name", type: LLM }) { id } }""",
    ).execute().path("createAgent.id").entity(Long::class.java).get()

    /** Six names in each of six lists, which is a quiet afternoon's configuration. */
    private fun widen(agentId: Long) {
        graphQlTester.document(saveDocument(agentId, "wide", wide = true)).execute().path("updateAgent.id").hasValue()
    }

    private fun save(agentId: Long, prompt: String, wide: Boolean = false) {
        graphQlTester.document(saveDocument(agentId, prompt, wide)).execute().path("updateAgent.id").hasValue()
    }

    private fun saveDocument(agentId: Long, prompt: String, wide: Boolean): String {
        fun names(prefix: String) = if (wide) (1..6).joinToString(", ") { "\"$prefix$it\"" } else ""
        return """
            mutation {
              updateAgent(id: $agentId, input: {
                name: "${agents.findByIdOrNull(agentId)?.name}", systemPrompt: "$prompt",
                mcpServers: [${names("m")}], skillCatalogs: [${names("s")}], memoryCatalogs: [${names("c")}],
                hiddenSkills: [${names("h")}], requiredSkills: [${names("r")}], tools: [${names("t")}]
              }) { $AGENT_FIELDS }
            }
        """
    }

    private fun workflowNaming(agentId: Long, name: String, publish: Boolean) {
        val workflowId = graphQlTester.document(
            """mutation { createWorkflow(input: { workspaceId: $workspaceId, name: "$name" }) { workflowId } }""",
        ).execute().path("createWorkflow.workflowId").entity(Long::class.java).get()
        graphQlTester.document(
            """
            mutation {
              saveWorkflowGraph(workspaceId: $workspaceId, workflowId: $workflowId, input: {
                nodes: [{ key: "a", kind: AGENT, name: "Ask", agentId: $agentId, x: 0, y: 0 }], edges: []
              }) { nodes { key } }
            }
            """,
        ).execute().path("saveWorkflowGraph.nodes[0].key").hasValue()
        if (publish) {
            graphQlTester.document(
                """mutation { publishWorkflow(workspaceId: $workspaceId, workflowId: $workflowId) { status } }""",
            ).execute().path("publishWorkflow.status").entity(String::class.java).isEqualTo("PUBLISHED")
        }
    }

    private fun dependants(agentId: Long) =
        """query { componentDependants(kind: AGENT, componentId: $agentId) { entries { kind id name workspaceId workspaceName published } hidden } }"""

    private fun revisions(agentId: Long) =
        """query { componentRevisions(kind: AGENT, componentId: $agentId) { id kind componentId name savedAt savedBy recordedAt } }"""

    private fun AgentRepository.findByIdOrNull(id: Long): Agent? = findById(id).orElse(null)

    private companion object {
        /** The tables an agent's lists live in. */
        val LISTS = listOf(
            "agent_mcp_server", "agent_memory_catalog", "agent_skill_catalog", "agent_hidden_skill",
            "agent_required_skill", "agent_granted_tool", "agent_required_tool", "agent_hidden_tool",
            "agent_connection", "agent_agent",
        )

        /** What the interface asks of an agent: `AGENT_FIELDS` in orknux-ui/src/api/agents.ts. */
        const val AGENT_FIELDS = "id workspaceId name type description systemPrompt enabled modelId modelName " +
            "mcpServers orknuxAccess shellAccess memoryCatalogs skillCatalogs hiddenSkills requiredSkills tools " +
            "connectionIds agentIds maxTools requiredTools icon memoryShare maxRounds"

        /** And of a workspace: `WORKSPACE_FIELDS` in orknux-ui/src/api/workspaces.ts. */
        const val WORKSPACE_FIELDS = "id name description roles { id name } adminRoles { id name } administered " +
            "companionModelId transcriptionModelId speechModelId imageModelId quickChatModelId quickChatMayWrite " +
            "compactAfterTokens compactionSummaryTokens compactionModelId defaultMemoryShare taskMaxTurns " +
            "taskMaxTurnsDefault agentMaxSubagents agentMaxSubagentsDefault maxToolCallsAtOnce " +
            "maxToolCallsAtOnceDefault sessionCompactAfterTokens sessionCompactAfterTokensDefault " +
            "sessionCompactionKeepTurns sessionCompactionKeepTurnsDefault sessionCompactionSummaryTokens " +
            "sessionCompactionSummaryTokensDefault sessionCompactionAttempts sessionCompactionAttemptsDefault " +
            "sessionCompactionModelId unsafeBuiltInTools commandMarker commandMarkerDefault functionTimeoutSeconds " +
            "functionTimeoutSecondsDefault toolTimeoutSeconds toolTimeoutSecondsDefault voicePauseEndsTurnMs " +
            "voiceSpeechOverRoomPercent voiceUnattendedMicrophoneMs voiceBargeInMs voiceSpeechChunking " +
            "chatShowTimestamps"
    }
}
