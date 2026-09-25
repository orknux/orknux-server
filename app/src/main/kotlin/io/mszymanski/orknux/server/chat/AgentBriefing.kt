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
 * for, and the agent loads the one that applies with [SkillTool].
 *
 * Memory is named here but not spelled out, which is the same trade and was not
 * always the arrangement. It used to say nothing at all, on the reasoning that
 * memory "is looked up when it turns out to be needed" - and nothing ever told
 * the agent it would turn out to be needed. The result was a feature that
 * worked and was used once: agents searched when somebody said "check your
 * memory" and never otherwise. What is here now is how much is written down and
 * where, which is a line, plus when to go and look.
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
    /** What this agent has been written down for it; see the note in `of`. */
    private val memories: io.mszymanski.orknux.server.memory.MemoryTool,
    private val connections: WorkspaceConnectionService,
    /** Which of the two ways the granted connections are told; see [ConnectionTools]. */
    private val connectionTools: ConnectionTools,
    /** For the command marker the agent advertises its skills under. Issue #381. */
    private val workspaces: io.mszymanski.orknux.server.workspace.WorkspaceRepository,
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
            /*
             * Each skill with the command that asks for it, and the agent told
             * to say so. A person in Slack cannot see the skill list; the only
             * way they learn that `!review` exists is the agent telling them,
             * and the only way `!review` in a message means anything where
             * the graph did not map it is the agent loading the skill itself.
             * Issue #381.
             */
            val marker = workspaces.findById(agent.workspaceId).map { it.commandMarker }
                .orElse(io.mszymanski.orknux.server.trigger.Commands.DEFAULT_MARKER)
            parts += buildString {
                append("You have been given these skills, each describing how this workspace goes about ")
                append("something. Load the one that applies with skill_load before following it; ")
                appendLine("what is listed here is only enough to choose from.")
                instructions.forEach { skill ->
                    append("\n- ").append(skill.name).append(" (").append(marker).append(skill.id).append(")")
                    skill.description?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                }
                appendLine()
                append("\nEach of these skills has a command: the marker and its id, like ")
                append(marker).append(instructions.first().id).append(". ")
                append("Anybody can write one anywhere in a message to have you load and follow that skill, ")
                append("and when a message carries one you load that skill first. This is the one special ")
                append("syntax people have with you - so when they ask what you can do, or how to use ")
                append("commands, tell them these commands and this ").append(marker)
                appendLine("id syntax rather than saying there is none.")
            }
        }

        /*
         * That there is anything written down at all.
         *
         * This said nothing, on the reasoning that memory "is looked up when it
         * turns out to be needed" - but nothing ever told the agent it would
         * turn out to be needed. It had a tool description among twenty others
         * and no reason to believe the catalogue held anything, so it searched
         * when somebody said "check your memory" and never otherwise: the
         * feature worked and was used once.
         *
         * Named and counted rather than listed. What makes an agent look is
         * knowing there is something to find, and that is a line - where the
         * memories themselves are the thing this deliberately does not inline,
         * for the reason the skills above are not spelled out either.
         *
         * The instruction is the other half. "You may search" is a capability
         * and changes nothing; what changes behaviour is being told when, and
         * the when is before answering from what it already believes.
         */
        val remembered = memories.catalogsFor(agent).filter { it.memoryCount > 0 }
        if (remembered.isNotEmpty()) {
            val held = remembered.sumOf { it.memoryCount }
            parts += buildString {
                append("This workspace has written ").append(held)
                append(if (held == 1) " thing down" else " things down")
                append(" that you can read, in ")
                append(remembered.joinToString(", ") { "${it.name} (${it.memoryCount})" })
                appendLine(".")
                append("Search it with memory_search before answering anything about how this workspace ")
                append("works, what it has decided, or who its customers are - what is written down there ")
                append("is what somebody wanted you to know, and it beats what you would otherwise assume. ")
                appendLine("Write down with memory_save anything you are told that the next conversation would need.")
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
