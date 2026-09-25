package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.connector.connection.WorkspaceConnectionService
import io.mszymanski.orknux.connector.model.LlmModelRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * The agent's setup, snapshotted for a session's log. Issue #391.
 *
 * A transcript opens with the first message, so a reader cannot tell what the
 * agent was configured with when it answered. This builds the account that is
 * kept at the start of a session: the model, the system prompt, and the grants
 * - tools, skills, connections, memory - as they stood. Names rather than ids,
 * because it is read by a person; resolved once, when the session opens, so a
 * later edit to the agent does not rewrite the record of what answered.
 */
@Service
class AgentDetails(
    private val models: LlmModelRepository,
    private val connections: WorkspaceConnectionService,
    private val mapper: ObjectMapper,
) {

    /** The snapshot, as JSON, for [LlmSession.agentDetails]. */
    fun snapshot(agent: Agent): String {
        val modelName = agent.modelId?.let { models.findByIdOrNull(it)?.name }
        val connectionNames = agent.connections
            .mapNotNull { connections.workspaceConnection(it)?.takeIf { held -> held.workspaceId == agent.workspaceId }?.name }
        return mapper.writeValueAsString(
            linkedMapOf(
                "agent" to agent.name,
                "model" to modelName,
                "systemPrompt" to agent.systemPrompt,
                "tools" to agent.tools.toList(),
                "skills" to agent.skillCatalogs.toList(),
                "memory" to agent.memoryCatalogs.toList(),
                "connections" to connectionNames,
            ),
        )
    }
}
