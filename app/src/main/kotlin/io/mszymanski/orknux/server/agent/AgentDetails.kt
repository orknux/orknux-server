package io.mszymanski.orknux.server.agent

import io.mszymanski.orknux.connector.connection.WorkspaceConnectionService
import io.mszymanski.orknux.connector.model.LlmModelRepository
import io.mszymanski.orknux.server.chat.AgentTools
import io.mszymanski.orknux.server.chat.BuiltInTools
import io.mszymanski.orknux.server.chat.ToolShed
import io.mszymanski.orknux.server.chat.briefedWith
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
 * **The system prompt is the text that was sent, not the agent's own field.**
 * Issue #454: this wrote `agent.systemPrompt`, which is one paragraph of what
 * the model read and is null on every agent whose instructions are its grants -
 * so the block said "no system prompt" about agents that had been told a page of
 * things. The caller composes the system text for the round and hands the same
 * string here, and a lent shed's paragraph goes on the end by the same rule the
 * round uses ([briefedWith]).
 *
 * What is deliberately left out of it is the agent's own working state - the note
 * it wrote itself, the to-do list it is working down - which the caller puts in
 * the same turn. Those change every turn and are lines of the log in their own
 * right, and folding them in would write a fresh page of prompt into the
 * transcript between every pair of turns, which is what the comparison below
 * exists to prevent.
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
     * @param prompt the standing instructions this turn is answered under, as
     *   the caller composed them for the round - not `agent.systemPrompt`, which
     *   is only the first paragraph of it and is null for most agents. Issue
     *   #454: what the record said and what the model read had drifted apart, so
     *   a node that replaced the prompt, an agent whose whole instruction is its
     *   grants briefing, and every paragraph a lent shed adds all read as "no
     *   system prompt at all". The caller composes it once and hands the same
     *   string to the round and to this, and [briefedWith] puts the lent shed's
     *   paragraph on the end here exactly as the round puts it there.
     */
    fun snapshot(agent: Agent, lent: ToolShed?, prompt: String?): String {
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
                /*
                 * And which agent that is, so the log can lead to it. Issue
                 * #454: the name was the only thing kept, and two agents in a
                 * workspace may be called nearly the same thing - a reader
                 * wanting to see the setup behind a name had to go and find it
                 * on the Agents list. Null only where the row was never saved,
                 * which nothing that answers a turn is; old lines carry none,
                 * which is why the field is nullable everywhere above the JSON.
                 */
                "agentId" to agent.id,
                "model" to modelName,
                // Through the lending, so the recorded briefing lists what it lends, as the round sends it. Issue #546.
                "systemPrompt" to briefedWith(prompt, BuiltInTools.lentTo(agent, lent)?.briefing()),
                "tools" to held.sorted(),
                "findable" to found.sorted(),
                "skills" to agent.skillCatalogs.sorted(),
                "memory" to agent.memoryCatalogs.sorted(),
                "connections" to connectionNames.sorted(),
            ),
        )
    }
}
