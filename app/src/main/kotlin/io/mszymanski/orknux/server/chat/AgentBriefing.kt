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
    /**
     * How many other agents this one may ask, and whether it may ask at all.
     *
     * Through a provider rather than wired in: [AgentRunTools] reaches the
     * conversation, and the conversation reaches this, so a direct edge is a
     * cycle Spring refuses at startup. Asked for at the moment a briefing is
     * written, by which time everything exists. Issue #476.
     */
    private val runTools: org.springframework.beans.factory.ObjectProvider<AgentRunTools>,
    /** For the command marker the agent advertises its skills under. Issue #381. */
    private val workspaces: io.mszymanski.orknux.server.workspace.WorkspaceRepository,
    /** The installation's default marker, where the workspace has none. Issue #402. */
    private val installation: io.mszymanski.orknux.server.attachment.InstallationSettings,
) {

    /**
     * The system turn for this agent, or null when it has nothing to say — an
     * agent with no prompt and no skills is a model with a name on it, and an
     * empty system turn is worth fewer tokens than it costs.
     *
     * The agent's own prose first, then [grants] - what it was given and the
     * rules for using it.
     */
    fun of(agent: Agent): String? =
        listOfNotNull(agent.systemPrompt?.takeIf { it.isNotBlank() }, grants(agent))
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n")

    /**
     * What the agent was given, and the standing rules for using it: its skills
     * and their commands, its memory, its connections. Null where it was given
     * nothing worth a sentence.
     *
     * Apart from [of] because a workflow node may replace the agent's own prose
     * with its own, and when it does this must not go with it: the skills an
     * agent can load and the commands people reach it by are facts about the
     * agent, not part of a persona. A node that overrides the prompt still
     * appends this, so a Slack bot with a bespoke voice still knows its own
     * commands and still says so. Issue #381.
     */
    fun grants(agent: Agent): String? {
        val parts = mutableListOf<String>()

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
            val marker = workspaces.findById(agent.workspaceId).map { it.commandMarker }.orElse(null)
                ?: installation.commandMarker()
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
                /*
                 * And where to get the list again. This paragraph is written once,
                 * at the top of a conversation that may run for hours, and what it
                 * listed is a dozen turns back by the time somebody asks what the
                 * commands are - so an agent asked for its commands was answering
                 * from memory of a list it had half forgotten, or from the skills
                 * loaded into that one turn, which is one skill and reads as a bug.
                 * `skill_list` is the same list, live, and asking for it costs a
                 * call. Issue #471.
                 */
                append("\nCall skill_list when you need this list again - it returns every skill ")
                appendLine("with its id, and it is what to answer from when somebody asks what commands you take.")
                /*
                 * And where two of them answer to one command. Issue #473: an
                 * id is unique inside a plugin and inside the workspace, and
                 * nothing makes it unique across two plugins - so the command
                 * means whichever the order picked, and the other was out of
                 * reach with nobody told. Said here because this paragraph is
                 * where the commands are, and said as the way out of it: the
                 * catalog written in front of the id reaches either one.
                 */
                instructions.filter { it.alsoIn.isNotEmpty() }.distinctBy { it.id.lowercase() }.forEach { one ->
                    appendLine()
                    append("More than one of your skills answers to ").append(marker).append(one.id)
                    append(": the command loads the one in ").append(one.catalog).append(", and ")
                    append(one.alsoIn.joinToString(" and ")).append(" hold another of that id. ")
                    append("Ask skill_load for ").append(one.alsoIn.first()).append(":").append(one.id)
                    appendLine(" to read that one instead.")
                }
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
        /*
         * Whether it may hand any of this to another agent, said outright.
         *
         * Issue #476: a workspace that allows none takes `ask_agent` off the
         * list, which is correct and silent - and a model that has been told
         * nothing assumes the ordinary thing, so agents went on planning to
         * delegate, telling people work had been handed over, and waiting for
         * an answer nobody was writing. A tool that is absent is not a sentence
         * anybody reads; this is.
         *
         * Both halves are said. The number is what the issue asked for - an
         * agent that knows it has two asks spends them on the two things worth
         * asking about - and the refusal is what stops the pretending.
         */
        val asks = runTools.getObject()
        if (asks.offered(agent)) {
            parts += buildString {
                append("You may put a question to another agent with ").append(AgentRunTools.ASK)
                append(", up to ").append(asks.limitFor(agent))
                appendLine(" times in this conversation. Spend them on work you have no tool for.")
            }
        } else if (agent.agents.isNotEmpty()) {
            /*
             * Said only where somebody granted agents and something else - a
             * limit of zero, or every one of them switched off - takes the tool
             * away. An agent nobody gave anybody to needs no sentence about it,
             * and every prompt in the product is not the place to pay for one.
             */
            parts += "You cannot hand any part of this to another agent in this conversation: this " +
                "workspace allows none. Do the work yourself rather than saying you will pass it on."
        }

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
