package io.mszymanski.orknux.server.task

import io.mszymanski.orknux.connector.model.LlmModel
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.AgentRepository
import io.mszymanski.orknux.server.agent.AgentType
import io.mszymanski.orknux.server.agent.Chattiness
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.util.Optional
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * The agent a task's turn is put to is the agent, every field of it.
 *
 * The copy named the fields it carried and dropped the rest, so a task's agent
 * had no connections - one granted Prometheus asked a person for it - nor its
 * secrets, variables, subagents, round limit, tool cap or reasoning effort.
 * Read by reflection, so a field added to [Agent] later fails here until it is
 * carried or named below as left on purpose.
 */
class TaskWorkerTest {

    /** What the copy sets itself rather than taking from the agent, and why. */
    private val leftOnPurpose = setOf(
        // A switched-off agent is refused before the copy is made; the copy is always on.
        "enabled",
        // The copy is never saved, so it was never saved by anybody.
        "lastModifiedAt",
        "lastModifiedBy",
    )

    private val agents = mock(AgentRepository::class.java)
    private val models = mock(LlmModelRepository::class.java)
    private val grants = mock(TaskGrantRepository::class.java)
    private val worker = TaskWorker(agents, models, grants)

    @Test
    fun `a task's worker carries every field of its agent`() {
        val agent = Agent(
            id = 7,
            workspaceId = 3,
            name = "Ops",
            type = AgentType.LLM,
            description = "Looks at production",
            systemPrompt = "Be careful.",
            modelId = 11,
            memoryShare = 40,
            maxRounds = 12,
            maxSubagentsAtOnce = 2,
            reasoningEffort = "high",
            chattiness = Chattiness.HIGH,
            maxTools = 30,
            orknuxAccess = true,
            mayAskForTools = false,
            boxLabelsText = "linux",
            mcpServers = mutableListOf("kibana"),
            memoryCatalogs = mutableListOf("ops-notes"),
            skillCatalogs = mutableListOf("ops-skills"),
            secretCatalogs = mutableListOf("prod-secrets"),
            variableCatalogs = mutableListOf("prod-vars"),
            hiddenSkills = mutableListOf("deploy"),
            requiredSkills = mutableListOf("triage"),
            tools = mutableListOf("find_connections", "save_artifact"),
            requiredTools = mutableListOf("save_artifact"),
            hiddenTools = mutableListOf("orknux_delete_issue"),
            connections = mutableListOf(21L, 22L),
            agents = mutableListOf(5L),
            icon = "server",
        )
        `when`(agents.findById(7)).thenReturn(Optional.of(agent))
        `when`(models.findById(11)).thenReturn(Optional.of(LlmModel(id = 11, providerId = 1, name = "Stub", modelId = "stub")))
        `when`(grants.findByTaskIdOrderByGrantedAtAscIdAsc(1)).thenReturn(emptyList())

        val copy = worker.of(Task(id = 1, workspaceId = 3, title = "Look at logs", prompt = "Look", agentId = 7, modelId = 11, createdBy = "alice", turnsAllowed = 10, secondsAllowed = 600)).agent

        val fields = requireNotNull(Agent::class.primaryConstructor).parameters.map { it.name }
        val properties = Agent::class.memberProperties.associateBy { it.name }
        val differing = fields.filterNot { it in leftOnPurpose }.filter { name ->
            val property = requireNotNull(properties[name]) { "Agent has no property $name" }
            property.get(copy) != property.get(agent)
        }
        assertThat(differing).withFailMessage("A task's worker leaves these fields of its agent behind: $differing").isEmpty()
    }

    /** And an approval widens it, without dropping what the agent already had. */
    @Test
    fun `an approved server joins the agent's own`() {
        val agent = Agent(id = 7, workspaceId = 3, name = "Ops", type = AgentType.LLM, modelId = 11, mcpServers = mutableListOf("grafana"))
        `when`(agents.findById(7)).thenReturn(Optional.of(agent))
        `when`(models.findById(11)).thenReturn(Optional.of(LlmModel(id = 11, providerId = 1, name = "Stub", modelId = "stub")))
        `when`(grants.findByTaskIdOrderByGrantedAtAscIdAsc(1)).thenReturn(
            listOf(TaskGrant(taskId = 1, requestId = null, capability = TaskCapability.MCP_SERVER, subject = "kibana", grantedBy = "alice")),
        )

        val copy = worker.of(Task(id = 1, workspaceId = 3, title = "Look at logs", prompt = "Look", agentId = 7, modelId = 11, createdBy = "alice", turnsAllowed = 10, secondsAllowed = 600)).agent

        assertThat(copy.mcpServers).containsExactly("grafana", "kibana")
        assertThat(agent.mcpServers).containsExactly("grafana")
    }
}
