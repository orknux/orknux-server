package io.mszymanski.orknux.server.chat

import io.mszymanski.orknux.connector.connection.WorkspaceConnectionService
import io.mszymanski.orknux.server.agent.Agent
import io.mszymanski.orknux.server.agent.SkillTool
import org.springframework.stereotype.Service

/**
 * What an agent is told before anything is said to it.
 *
 * An agent is a configuration, not a model: it names the model that answers, the
 * instructions it works under, and the skills it has been granted. This turns
 * that into the one system turn both request shapes already understand, so
 * chatting with an agent needs no new path through [ModelChatClient].
 *
 * Skills are listed here, not spelled out. Each one is a page of markdown, and
 * an agent granted five catalogs would spend most of its context on instructions
 * for work it is not doing — so the briefing gives the names and what each is
 * for, and the agent loads the one that applies with [SkillTool]. Memory is not
 * here at all for the same reason it never was: it is looked up when it turns
 * out to be needed, which is what [MemoryTool] is for.
 */
@Service
class AgentBriefing(
    /**
     * Asked rather than queried, so the briefing lists exactly what
     * `skill_list` will list and `skill_load` will load — including the skills
     * a plugin brought. This used to read the catalogs itself, which meant two
     * places deciding what an agent had been granted and only one of them
     * knowing about plugins.
     */
    private val skills: SkillTool,
    private val connections: WorkspaceConnectionService,
    /** Which of the two ways the granted connections are told; see [ConnectionTools]. */
    private val connectionTools: ConnectionTools,
) {

    /**
     * The system turn for this agent, or null when it has nothing to say — an
     * agent with no prompt and no skills is a model with a name on it, and an
     * empty system turn is worth fewer tokens than it costs.
     */
    fun of(agent: Agent): String? {
        val parts = mutableListOf<String>()
        agent.systemPrompt?.takeIf { it.isNotBlank() }?.let(parts::add)

        val instructions = skills.list(agent)

        if (instructions.isNotEmpty()) {
            parts += buildString {
                append("You have been given these skills, each describing how this workspace goes about ")
                append("something. Load the one that applies with skill_load before following it; ")
                appendLine("what is listed here is only enough to choose from.")
                instructions.forEach { skill ->
                    append("\n- ").append(skill.name)
                    skill.description?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                }
                appendLine()
            }
        }

        /*
         * The connections this agent may name, and the rule for naming one.
         *
         * A grant is permission, not encouragement: a tool that takes a
         * connection has a configured default, and an agent that started
         * second-guessing that default because a list of alternatives was in
         * its briefing would be doing exactly what granting them did not mean.
         * So the instruction is explicit and restrictive, and stands right
         * next to the list it governs.
         *
         * Read by id and dropped silently where the id no longer answers -
         * a deleted connection is not this turn's problem, and the settings
         * page is where a stale grant is reported.
         */
        /*
         * Recited while they are few, and pointed at once they are many.
         *
         * A handful of lines in a system turn is nothing and a round trip is
         * not free, so a short list stays exactly where it was. A long one is
         * paid for on every round of every turn for something most turns never
         * read, and past that point the agent is better off asking. See
         * [ConnectionTools].
         */
        if (connectionTools.offered(agent)) {
            parts += buildString {
                append("You have been granted ").append(agent.connections.size)
                append(" connections. Where a tool takes a connection id, find the one you were told ")
                append("to use with `").append(ConnectionTools.FIND).append("` - and only pass an id ")
                appendLine("when you have been explicitly told to use that connection; otherwise leave ")
                appendLine("the tool to its configured default.")
            }
        }

        val reachable = if (connectionTools.recited(agent)) {
            agent.connections
                .mapNotNull { connections.workspaceConnection(it) }
                .filter { it.workspaceId == agent.workspaceId }
        } else {
            emptyList()
        }
        if (reachable.isNotEmpty()) {
            parts += buildString {
                append("These connections have been granted to you. Where a tool takes a connection id, ")
                append("only pass one of these when you have been explicitly told to use that connection - ")
                appendLine("otherwise leave the tool to its configured default.")
                reachable.forEach { connection ->
                    append("\n- ").append(connection.name)
                    append(" (").append(connection.type.name).append(", id ").append(connection.id).append(")")
                }
                appendLine()
            }
        }

        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }
}
