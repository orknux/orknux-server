package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.connector.connection.WorkspaceConnectionService
import io.mszymanski.orknux.connector.model.LlmModelRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * The agent's setup, snapshotted for a session's log. Issues #391, #441.
 *
 * A transcript opens with the first message, so a reader cannot tell what the
 * agent was configured with when it answered. This builds the account that is
 * written into the log where an agent starts responding: the model, the system
 * prompt, and the grants - tools, skills, connections, memory - as they stood.
 * Names rather than ids, because it is read by a person; resolved at the turn,
 * so a later edit to the agent does not rewrite the record of what answered.
 *
 * The same setup has to come out as the same text every time, because the
 * recorder decides whether a new line is due by comparing this against the
 * last one it logged (#441). So the grants are sorted rather than left in the
 * order the row holds them - a list reloaded from the database in another
 * order would otherwise read as a change of setup, and a session would fill
 * with lines saying nothing had changed.
 */
@Service
class AgentDetails(
    private val models: LlmModelRepository,
    private val connections: WorkspaceConnectionService,
    private val mapper: ObjectMapper,
) {

    /** The snapshot, as JSON, for [io.mszymanski.orknux.server.llm.LlmSessionRecorder.describeAgent]. */
    fun snapshot(agent: Agent): String {
        val modelName = agent.modelId?.let { models.findByIdOrNull(it)?.name }
        val connectionNames = agent.connections
            .mapNotNull { connections.workspaceConnection(it)?.takeIf { held -> held.workspaceId == agent.workspaceId }?.name }
        return mapper.writeValueAsString(
            linkedMapOf(
                "agent" to agent.name,
                "model" to modelName,
                "systemPrompt" to agent.systemPrompt,
                "tools" to agent.tools.sorted(),
                "skills" to agent.skillCatalogs.sorted(),
                "memory" to agent.memoryCatalogs.sorted(),
                "connections" to connectionNames.sorted(),
            ),
        )
    }
}
