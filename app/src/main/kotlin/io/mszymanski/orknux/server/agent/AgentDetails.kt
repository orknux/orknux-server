package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.connector.connection.WorkspaceConnectionService
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.server.chat.AgentTools
import io.mszymanski.orknux.server.chat.BuiltInTools
import io.mszymanski.orknux.server.chat.ToolShed
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * The agent's setup, snapshotted for a session's log. Issues #391, #441, #446.
 *
 * A transcript opens with the first message, so a reader cannot tell what the
 * agent was configured with when it answered. This builds the account that is
 * written into the log where an agent starts responding: the model, the system
 * prompt, and the grants - tools, skills, connections, memory - as they stood.
 * Names rather than ids, because it is read by a person; resolved at the turn,
 * so a later edit to the agent does not rewrite the record of what answered.
 *
 * **The tools are what the model was declared, not what the row lists.** Issue
 * #446: the block used to print `agent.tools`, which was `slack_*` and
 * `draw_picture`, while the model had also been handed `finish_answer`, the
 * skill tools, the memories, a note, a to-do list, the clock and a scratchpad -
 * so a reader trying to work out why an agent called `skill_load` found no such
 * tool in its setup. What is written now is what [AgentTools.offeringFor] says
 * the agent holds plus what the caller lent it for the turn, held to the
 * agent's grants the way the round holds it; and it is written in two lists
 * where the agent carries a ceiling, because under one "held" is two different
 * things - carried on every turn, or found when a job needs it.
 *
 * The same setup has to come out as the same text every time, because the
 * recorder decides whether a new line is due by comparing this against the
 * last one it logged (#441). So every list is sorted rather than left in the
 * order the row or the offering holds them - a list built in another order
 * would otherwise read as a change of setup, and a session would fill with
 * lines saying nothing had changed.
 */
@Service
class AgentDetails(
    private val models: LlmModelRepository,
    private val connections: WorkspaceConnectionService,
    /** What the agent holds of its own, split as the round splits it. Issue #446. */
    private val tools: AgentTools,
    private val mapper: ObjectMapper,
) {

    /**
     * The snapshot, as JSON, for [io.mszymanski.orknux.server.llm.LlmSessionRecorder.describeAgent].
     *
     * @param lent what the caller is lending the agent for this turn - the same
     *   shed it hands the round - or null where it lends nothing. Asked here so
     *   the account names the tools the model actually saw, and held to the
     *   agent's grants by the same rule the round applies, so a built-in hidden
     *   on the agent's page is absent from both.
     */
    fun snapshot(agent: Agent, lent: ToolShed? = null): String {
        val modelName = agent.modelId?.let { models.findByIdOrNull(it)?.name }
        val connectionNames = agent.connections
            .mapNotNull { connections.workspaceConnection(it)?.takeIf { held -> held.workspaceId == agent.workspaceId }?.name }

        val offering = tools.offeringFor(agent)
        val (carriedLent, findableLent) = BuiltInTools.lentTo(agent, lent)?.specs().orEmpty()
            .partition { BuiltInTools.carried(agent, it.name) }
        val carried = (offering.core + carriedLent).map { it.name }
        val findable = (offering.searchable + findableLent).map { it.name }

        /*
         * Two lists only where the agent has a ceiling of its own. Without one
         * everything is carried up to the provider's own limit, and that limit
         * is a fact about the provider rather than about this agent's setup - a
         * reader shown "findable" beside an agent nobody gave a ceiling would
         * go looking for a setting that is not there.
         */
        val (held, found) = if (agent.maxTools == null) (carried + findable) to emptyList() else carried to findable

        return mapper.writeValueAsString(
            linkedMapOf(
                "agent" to agent.name,
                "model" to modelName,
                "systemPrompt" to agent.systemPrompt,
                "tools" to held.sorted(),
                "findable" to found.sorted(),
                "skills" to agent.skillCatalogs.sorted(),
                "memory" to agent.memoryCatalogs.sorted(),
                "connections" to connectionNames.sorted(),
            ),
        )
    }
}
